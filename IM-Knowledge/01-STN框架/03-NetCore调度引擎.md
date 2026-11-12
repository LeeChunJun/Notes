# 03 · NetCore 调度引擎

> 主要源码：`mars/stn/src/net_core.h`（262 行）、`net_core.cc`（1300+ 行）

---

## 1. 职责与组成

`NetCore` 是 STN 的**中央调度器**，负责：
- 任务入队前的校验与默认值填充
- 通道选路
- 短连/长连任务管理器的分发
- 网络变化处理
- 连接状态聚合上报
- 雪崩防护 / 动态超时 / 弱网逻辑的持有

```cpp
// mars/stn/src/net_core.h:68-262
class NetCore {
 private:
    comm::MessageQueue::MessageQueueCreater messagequeue_creater_;   // ★ 私有 STN 线程
    comm::MessageQueue::ScopeRegister asyncreg_;                      // ★ AsyncInvoke 句柄
    boot::Context* context_;

    std::shared_ptr<NetSource> net_source_;
    NetCheckLogic* netcheck_logic_;
    AntiAvalanche* anti_avalanche_;
    DynamicTimeout* dynamic_timeout_;

    ShortLinkTaskManager* shortlink_task_manager_;
    int shortlink_error_count_;
    ZombieTaskManager* zombie_task_manager_;      // #ifdef USE_LONG_LINK
    LongLinkTaskManager* longlink_task_manager_;   // #ifdef USE_LONG_LINK
    TimingSync* timing_sync_;                     // #ifdef USE_LONG_LINK

    bool shortlink_try_flag_;
    LongLinkEncoder* default_longlink_encoder;
    comm::WakeUpLock* wakeup_lock_;                // #ifdef ANDROID
};
```

生命周期信号（`net_core.h:72-85`，boost::signals2）：
```cpp
boost::signals2::signal<void()> NetCoreCreateBegin;
boost::signals2::signal<void()> NetCoreCreate;
boost::signals2::signal<void()> NetCoreRelease;
```

---

## 2. 线程模型：STN 的私有消息队列线程

`net_core.cc:77-78`：

```cpp
, messagequeue_creater_(true, XLOGGER_TAG)   // iscreate=true → 立刻起线程
, asyncreg_(MessageQueue::InstallAsyncHandler(messagequeue_creater_.CreateMessageQueue()))
```

**`CreateMessageQueue()` 创建一个独立线程**执行 `RunLoop().Run()`（`comm/messagequeue/message_queue.cc:1066-1071`）。

### 2.1 队列 ID == 线程 TID

`message_queue.cc:208-216`：

```cpp
MessageQueue_t CurrentThreadMessageQueue() {
    return ThreadUtil::currentthreadid();
}
```

### 2.2 挂在 STN 队列上的消费者

| 组件 | 创建位置 | 队列 |
|---|---|---|
| `ShortLinkTaskManager` | `net_core.cc:84-86` | STN 队列 |
| `ZombieTaskManager` | `net_core.cc:207` | STN 队列 |
| `LongLinkTaskManager` | `net_core.cc:214-219` | STN 队列 |
| `LongLinkConnectMonitor` | `longlink_connect_monitor.cc:176` | STN 队列（`InstallAsyncHandler(_id)`） |
| `SignallingKeeper` | `signalling_keeper.cc` | STN 队列 |
| `NetSourceTimerCheck` | `netsource_timercheck.cc` | STN 队列 |

**但 `LongLink` 本身有自己的 pthread**（名字 `::lonklink`，`longlink.cc:173-196`）。
所以一个典型 IM App 有这几条线程：

```
┌──────────────────────────────────────────────────────────┐
│ App 主线程 / UI 线程                                     │
├──────────────────────────────────────────────────────────┤
│ STN 线程（MessageQueue）                                 │
│   - NetCore 调度                                          │
│   - LongLinkTaskManager（长连任务队列 + 1s tick）          │
│   - ShortLinkTaskManager                                  │
│   - LongLinkConnectMonitor（重连决策）                     │
│   - SignallingKeeper（UDP 保活）                          │
│   ★ 业务的 Req2Buf / Buf2Resp 在这里被调用                  │
├──────────────────────────────────────────────────────────┤
│ LongLink I/O 线程（每条长连一个，名字 "::lonklink"）       │
│   - connect                                             │
│   - select 读写循环                                      │
│   - unpack                                              │
├──────────────────────────────────────────────────────────┤
│ ShortLink I/O 线程（短连走 tcpclient_fsm / block_socket） │
├──────────────────────────────────────────────────────────┤
│ LongLinkConnectMonitor 线程 "::con_mon"（仅 iOS 周期探测）  │
└──────────────────────────────────────────────────────────┘
```

> **IM 性能要点**：`Req2Buf`/`Buf2Resp` 跑在 STN 线程上。
> 如果这两个函数里做磁盘 IO、加解密、大对象序列化，**整条 STN 队列都会卡住**。
> 正确做法：序列化放到业务自己的线程池，STN 回调里只做内存操作。

---

## 3. `ASYNC_BLOCK_START` —— 同步/异步互转惯用法

`mars/comm/messagequeue/message_queue.h:770-878`：

```cpp
#define ASYNC_BLOCK_START mars::comm::MessageQueue::AsyncInvoke([=] () {   // :774
#define ASYNC_BLOCK_END   }, AYNC_HANDLER, msg_name);                       // :771-772
#define SYNC2ASYNC_FUNC(func)                                             // :785
#define RETURN_SYNC2ASYNC_FUNC(func, ret)                                  // :798
#define RETURN_WAIT_SYNC2ASYNC_FUNC(func, ret)                             // :826
#define WAIT_SYNC2ASYNC_FUNC(func)                                         // :840
#define WAIT_SYNC2ASYNC_FUNC_WITH_HOOK(func, pre, post, ret, timeout)      // :857
```

**语义**：把当前函数的整个函数体**搬到目标消息队列线程执行**，且调用方**等待执行完成**（同步语义）。

经典用法（`longlink_connect_monitor.cc:368-399`）：

```cpp
void LongLinkConnectMonitor::__OnSignalForeground(bool _isForeground) {
    ASYNC_BLOCK_START
    if (is_released_) return;
    ScopedLock lock(mutex_);
    ...
    __AutoIntervalConnect();
    ASYNC_BLOCK_END
}
```

> **为什么需要**：信号（`OnForeground` / `OnNetworkChange`）可能在**任意线程**触发，
> 但重连决策必须串行化到 STN 线程，否则会和 `__RunLoop` 竞争。

**注意 `AsyncInvoke` 在同队列内调用时不会死锁** —— `message_queue.cc:591-599`：
如果 post 到的队列就是当前队列，直接嵌套执行 `RunLoop`。

---

## 4. `MessageQueue` 核心 API

`mars/comm/messagequeue/message_queue.h` / `.cc`

### 4.1 投递 API

| API | 位置 | 语义 |
|---|---|---|
| `PostMessage(handler, msg, timing)` | `.cc:391-413` | 追加到队尾，顺序执行 |
| `SingletonMessage(replace, handler, msg, timing)` | `.cc:415-452` | 同 handler + 同 message 去重 |
| `BroadcastMessage(queueid, msg, timing)` | `.cc:454-479` | `reg.seq = 0` 广播 |
| **`FasterMessage(handler, msg, timing)`** | `.cc:502-534` | ★ **去重 + "更早者胜"** |
| `PostMessageAtFirst(handler, msg)` | `.cc:536-553` | `push_front` 插队 |
| `WaitMessage(postid, timeout)` | `.cc:555-618` | 阻塞等待执行完毕 |
| `FoundMessage(postid)` | `.cc:620-648` | 非阻塞查询 |
| `CancelMessage(postid)` / `CancelMessage(handler)` / `CancelMessage(handler, title)` | `.cc:650-752` | 取消 |
| `InstallMessageHandler(handler, recvbroadcast, queueid)` | `.cc:329-346` | 注册 handler |
| `InstallAsyncHandler(queueid)` | `.cc:793-796` | 取某队列的默认异步 handler |

### 4.2 `FasterMessage` 的去重算法

```cpp
// 队列中已有同 handler+同 message 的消息时，
// 比较 __ComputerWaitTime（还需等待多久），
// 保留等待时间更短的那个（即"更快到期"的），删除另一个。
```

> **STN 用它的原因**：`__RunLoop` 是 1 秒节拍。
> 如果网络变化、任务下发、前后台切换多处同时触发调度，
> `FasterMessage` 保证**只有一个**待执行消息，且执行时间取最早的 —— 天然去重 + 天然"就近执行"。

调用点：`longlink_task_manager.cc:300-304`、`shortlink_task_manager.cc:193`。

### 4.3 定时语义 `MessageTiming`

`message_queue.h:165-204`：

```cpp
enum TMessageTiming { kAfter, kPeriod, kImmediately };   // :167-171
```

| 类型 | 语义 |
|---|---|
| `kImmediately` | 立刻执行（默认 `KDefTiming`） |
| `kAfter(_after)` | 延迟 after 毫秒执行一次 |
| `kPeriod(_after, _period)` | 先延迟 after，之后每 period 执行一次 |

> **注意**：本版本（1.2.x）的枚举是 `kImmediately`，**没有 `kNow`**。

### 4.4 `ScopeRegister` —— RAII 订阅

`message_queue.h:751-766`，实现 `.cc:1187-1206`：

```cpp
class ScopeRegister {
 public:
    ScopeRegister(const MessageHandler_t& _reg);   // :1187 拷贝 handler
    ~ScopeRegister();                              // :1190 → Cancel()
    const MessageHandler_t& Get() const;
    void Cancel() const;                           // :1199 UnInstall + CancelMessage
    void CancelAndWait() const;                    // :1203 Cancel + WaitForRunningLockEnd
};
```

**STN 里的宏**：
```cpp
#define AYNC_HANDLER asyncreg_.Get()
```
出现在 `net_core.h:237`、`longlink.h:216`、`longlink_task_manager.h:170`、
`shortlink.h:108`、`netsource_timercheck.h:72`、`signalling_keeper.h:58`、
`zombie_task_manager.h:65`、`longlink_metadata.h:83`。

> **意义**：`ScopeRegister` 持有者析构时自动退订 + 取消未执行消息 → 避免"对象已析构但消息还在队列里 → 野指针崩溃"。
> **这是 C++ 里做消息订阅的标准 RAII 手法。**

---

## 5. 连接状态聚合上报

`net_core.cc:981-1082`（`__ConnStatusCallBack`）。把「短连错误计数」+「长连状态」合成为一对状态上报。

```
shortlink_error_count_ >= kShortlinkErrTime(3) → NetStatus::kServerFailed(2)
shortlink_error_count_ == 0                    → NetStatus::kConnected(4)

长连 kConnectFailed → longlink 状态 kServerFailed
长连 kConnectIdle / kConnecting → longlink 状态 kConnecting
长连 kConnected → longlink 状态 kConnected（同时清零 shortlink_error_count_）
```

> **IM 价值**：`ReportConnectStatus(status, longlink_status)` 是上层判断
> "网络是否可用"的唯一权威来源。上层应该用它驱动 UI 上的"连接中/已连接/重连中"提示，
> **不要自己维护一个 `isConnected` 布尔值**（必然和真实状态不同步）。

---

## 6. `NetSource` —— IP / 端口供给

`mars/stn/src/net_source.h/.cc`。

职责：根据 host 拿到一组候选 `(IP, port)`，供 `ComplexConnect` 竞速。

来源优先级（`net_source.cc`）：
```
1. DebugIP（SetDebugIP 设置，优先级最高）     → IPSourceType::kIPSourceDebug
2. NewDNS（新 DNS 服务，腾讯自研）              → kIPSourceNewDns
3. SimpleDNS / 本地 DNS                       → kIPSourceDNS
4. BackupIP（SetBackupIPs 设置的兜底）          → kIPSourceBackup
5. Proxy（代理）                              → kIPSourceProxy
```

`IPSourceType`（`stn.h:328-335`）：
```cpp
kIPSourceNULL = 0, kIPSourceDebug, kIPSourceDNS, kIPSourceNewDns, kIPSourceProxy, kIPSourceBackup
```
字符串映射（`stn.h:383-390`）：`{"NullIP","DebugIP","DNSIP","NewDNSIP","ProxyIP","BackupIP"}`

### 6.1 `SimpleIPPortSort` —— IP 排序策略

`mars/stn/src/simple_ipport_sort.h/.cc`。把 DNS 返回的多个 IP 按"上次成功率 / RTT"排序，
**上次连成功的 IP 排前面**，从而在竞速中更快命中。

> **IM 价值**：这是"选对机房"的基础 —— IP 排序直接决定长连建立的耗时。

### 6.2 `NetSourceTimerCheck` —— 备用 IP 探测

`mars/stn/src/netsource_timercheck.h/.cc`。独立线程，周期 `kTimeCheckPeriod = 10s`（`longlink_connect_monitor.cc:47`），
后台探测备用 IP 是否可用，为下次建连做准备。

---

## 7. `AntiAvalanche` —— 雪崩防护

`mars/stn/src/anti_avalanche.h/.cc`。

**要解决的问题**：
> 服务端出故障时，客户端全部疯狂重试 → 重试流量压垮本已故障的服务端 → 故障扩大。

**机制**（推断自调用点 `longlink_task_manager.cc:521-528`）：
- 按 `cmdid`（+ 可能 `user_id`）维度维护失败计数
- 失败次数超过阈值 → 拒绝新任务，返回 `kEctLocalAntiAvalanche(-4)`
- 有时间窗衰减

配套：
- `frequency_limit.cc` —— 频次限制（`_check_type = kFrequencyLimit = 1`）
- `flow_limit.cc` —— 流量漏斗（`_check_type = kFlowLimit = 2`）

命中时通过 `Callback::ReportTaskLimited(_check_type, _task, _param)` 上报，
上层可以据此做用户提示（"网络繁忙，请稍后重试"）。

> **IM 落地**：IM 的"发消息"是高频操作，很容易触发频控。
> **必须实现 `ReportTaskLimited`**，否则用户只会看到"发送失败"而不知道为什么。

---

## 8. `WeakNetworkLogic` —— 弱网识别

`mars/stn/src/weak_network_logic.h/.cc`。

```
OnConnectEvent(is_suc, rtt, index)
  ├─ kSceneRtt   : RTT 过大 → 判定弱网
  └─ kSceneIndex : 命中的 IP 序号 > 0 → 说明第一个 IP 不通，也算弱网
```

对外：
```cpp
bool IsCurrentNetworkWeak();
bool IsLastValidConnectFail();
```

**用途**：
1. 弱网时**放宽超时**（配合 DynamicTimeout）
2. 弱网时**减少消息发送频率**（避免加剧拥塞）
3. 弱网时给用户提示（"当前网络较差"）

---

## 9. `TimingSync` —— 时间校准

`mars/stn/src/timing_sync.h/.cc`。用长连往返时间估计客户端与服务端的时间差，
用于需要时间戳的业务（如消息排序、服务端时间校准）。

---

## 10. `NetCheckLogic` —— 网络探测

`mars/stn/src/net_check_logic.h/.cc`。触发条件：`Callback::RequestNetCheckShortLinkHosts()`。

配合 `Callback::RequestSync()` 一起用：
- 上层觉得"网络可能有问题" → `RequestNetCheckShortLinkHosts()` 让 Mars 去 ping 一批短连域名
- 结果通过 `RequestSync()` 的机制回报

> **IM 场景**：长连反复连不上时，可以主动做一次网络探测，判断"是服务端挂了还是本地网络断了"，
> 从而给用户不同的提示。

---

## 11. `NetCore` 析构顺序

`net_core.cc:151-162`：

```cpp
NetCore::~NetCore() {
    asyncreg_.Cancel();                    // ① 先退订，避免新消息进来
    if (!already_release_net_) ReleaseNet();
    MessageQueue::MessageQueueCreater::ReleaseNewMessageCreator(messagequeue_creater_);  // ② 再停线程
    delete wakeup_lock_;
}
```

**顺序很重要**：必须先退订再停线程，否则线程退出时还在执行回调 → 崩溃。

---

## 12. `Reset` 与 `ResetAndInitEncoderVersion`

`stn_manager.cc:534` / `:549`：

```cpp
void StnManager::Reset() {
    net_core_.reset();       // 释放
    net_core_ = std::make_shared<NetCore>(context_);   // 重建
    net_core_->NetCoreCreate()();
}

void StnManager::ResetAndInitEncoderVersion(int _encoder_version, std::string _encoder_name) {
    packer_encoder_version_ = _encoder_version;
    packer_encoder_name_ = _encoder_name;
    Reset();
}
```

> **IM 用途**：切换长连服务器 IP（灰度、容灾切换）时，调用 `Reset()` + `SetLonglinkSvrAddr()`。
> 或者用 `SetBackupIPs()` + `RedoTasks()`（更轻量，不断任务）。

---

## 13. `NetCore` 与 `LongLink` 的通信

`NetCore` 通过 `LongLinkTaskManager` 间接操作 `LongLink`：

```
NetCore
  └─ LongLinkTaskManager
       ├─ longlink_metas_ : name → LongLinkMetaData
       │                        └─ LongLink（本体）
       │                        └─ LongLinkConnectMonitor（重连决策）
       │                        └─ NetSourceTimerCheck
       │                        └─ SignallingKeeper
       └─ 通过 GetLongLink(channel_name) 拿到 LongLink
```

`LongLinkMetaData`（`mars/stn/src/longlink_metadata.h`）是**聚合器**：
一条长连接 = 1 个 `LongLinkMetaData` = 1 个 `LongLink` + 1 个 `LongLinkConnectMonitor` + 1 个 `NetSourceTimerCheck` + 1 个 `SignallingKeeper`。

> **IM 架构价值**：多长连（主长连 + 副长连 + 不同业务线长连）就是这个设计的直接产物。
> 每条长连独立心跳、独立重连、独立信令，互不干扰。

---

## 14. `NetCore` 的 `__ChooseChannel` 完整逻辑

`net_core.cc:296-324`（重述，带注释）：

```cpp
int NetCore::__ChooseChannel(const Task& _task, const std::string& _channel_name) {
    int channel = Task::kChannelShort;

    // 副长连优先级最高
    if (_task.channel_select & Task::kChannelMinorLong) {
        channel = Task::kChannelMinorLong;
    }
    // 全部通道
    else if (_task.channel_select & Task::kChannelAll) {
        channel = Task::kChannelLong;
    }
    // 长短皆可
    else if (_task.channel_select & Task::kChannelBoth) {
        channel = Task::kConnected ? Task::kChannelLong : Task::kChannelShort;
    }
    // 普通通道（副长连 > 短连，不含主长连）
    else if (_task.channel_select & Task::kChannelNormal) {
        channel = Task::kChannelShort;
    }

    // Fast 策略：长连任务数超限则强制短连
    if (_task.channel_strategy == Task::kChannelFastStrategy) {
        if (longlink_task_manager_->GetTaskCount(_channel_name) > kFastSendUseLonglinkTaskCntLimit) {
            channel = Task::kChannelShort;
        }
    }
    return channel;
}
```

> ⚠️ `kChannelBoth` 的判断用的是 **`kConnected`**（`NetCore` 的连接状态变量），
> 不是"此刻长连对象是否已连接"。这个状态由 `__ConnStatusCallBack` 更新，
> 存在轻微延迟。**所以 `kChannelBoth` 的降级判断不是 100% 精确的。**
