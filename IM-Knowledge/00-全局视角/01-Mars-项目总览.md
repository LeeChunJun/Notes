# 01 · Mars 项目总览

> 源码根目录：`D:\WorkData\mars-master`
> 版本：v1.2.x（`mars/README.md` release badge），架构为 "mars2" 重构版

---

## 1. Mars 是什么

腾讯开源的 **跨平台 C++ 移动端基础网络组件库**，2016 年开源。取名 "Mars" = 内部代号 **mmnet**。

它不是一个完整 App，而是一组 **"每个 App 都会重复造轮子"的基础设施**：

| 模块 | 目录 | 解决的问题 |
|---|---|---|
| **Xlog** | `mars/xlog` | 高性能、线程安全、可压缩、可加密、可轮转的日志系统（替代 logcat） |
| **STN** | `mars/stn` | **Short Task Network** —— 长短连接网络框架（**IM 的核心**） |
| **SDT** | `mars/sdt` | 自定义调试工具：网络探测（ping/dns/tcp/http）、STN 任务追踪 |
| **boot** | `mars/boot` | 进程级 Manager 容器 + 生命周期信号总线 |
| **baseevent** | `mars/baseevent` | 横切层：前后台切换、网络变化、崩溃信号 |
| **comm** | `mars/comm` | 通用能力：socket / 消息队列 / 缓冲区 / DNS / HTTP / 线程 / 加密 |
| **app** | `mars/app` | 应用适配层：账号、设备信息、全局配置、活跃态 |

**一句话定位**：
> Mars = 微信客户端网络层的"零件箱"。STN 是其中最重的一块，也是 IM 长连接的工业级实现。

---

## 2. STN 为什么叫 "Short Task Network"，却主要做长连接

这是一个**历史遗留的命名**。它的含义是：

- **"Short Task"** 指的是"短生命周期的一次性请求任务"（类似 CGI 调用），而不是"短连接"。
- STN 的设计目标是：**用一个统一的 Task 抽象，同时管理"一次性短任务"和"复用长连接的任务"**。

看 `mars/stn/stn.h:60` 的 `struct Task`，它有 `channel_select` 位掩码：

```cpp
static const int kChannelShort     = 0x1;   // 纯短连（HTTP）
static const int kChannelLong      = 0x2;   // 纯长连（信令）
static const int kChannelBoth      = 0x3;   // 长连优先，未连上降级短连
static const int kChannelMinorLong = 0x4;   // 副长连（独立 host 的第二条长连接）
static const int kChannelNormal    = 0x5;   // 普通通道：副长连 > 短连（不含主长连）
static const int kChannelAll       = 0x7;   // 全部：副长连 > 主长连 > 短连
```

选路逻辑在 `mars/stn/src/net_core.cc:296-324`（`NetCore::__ChooseChannel`）。

**IM 的真实场景映射**：
- 聊天消息收发 → `kChannelLong`（长连接，低延迟）
- 拉历史消息 / 拉会话列表 → `kChannelShort`（HTTP，可缓存、可重试）
- 消息推送（服务端主动下发）→ 长连接的 push 通道

---

## 3. 一次完整 IM 交互在 Mars 里的抽象

```
┌──────────────────────────────────────────────────────────────────┐
│ App 层（Android/Java）                                           │
│  ChatActivity → TextMessageTask → MarsServiceProxy → AIDL        │
└───────────────────────────┬──────────────────────────────────────┘
                            │ StnLogic.startTask(task)
┌───────────────────────────▼──────────────────────────────────────┐
│ JNI 桥  mars/stn/jni/com_tencent_mars_stn_StnLogic_Java2C.cc   │
└───────────────────────────┬──────────────────────────────────────┘
┌───────────────────────────▼──────────────────────────────────────┐
│ STN 层                                                          │
│  NetCore::StartTask  → 参数校验 → 选通道 → 分发到 TaskManager   │
│      ├─ LongLinkTaskManager  (lst_cmd_ 队列, 1s tick 调度)        │
│      └─ ShortLinkTaskManager (lst_cmd_ 队列)                     │
└───────────────────────────┬──────────────────────────────────────┘
                            │ Req2Buf() 由上层实现（业务编码）
┌───────────────────────────▼──────────────────────────────────────┐
│ 协议层                                                           │
│  LongLinkEncoder.longlink_pack → __STNetMsgXpHeader(20B) + body │
└───────────────────────────┬──────────────────────────────────────┘
                            │ TCP/TLS
┌───────────────────────────▼──────────────────────────────────────┐
│ 长连接层  LongLink                                               │
│  专属 pthread "::lonklink"：connect → select 读写循环 → 断链    │
└──────────────────────────────────────────────────────────────────┘
```

**核心思想**：
> STN 负责"把一个业务请求可靠地送到服务端并把响应可靠地取回来"，**业务协议完全由上层定义**。
> 上层通过实现 `stn::Callback` 的 `Req2Buf` / `Buf2Resp` 两个纯虚函数来接入。

---

## 4. 关键设计取舍（面试可讲）

### 4.1 上层只实现两个方法就能接入

`mars/stn/stn.h:465-539` 的 `class Callback` 有 **13 个纯虚函数**，但对业务来说真正必填的只有：

```cpp
virtual bool Req2Buf(uint32_t _taskid, void* const _user_context,
                     const std::string& _user_id,
                     AutoBuffer& outbuffer, AutoBuffer& extend,
                     int& error_code, const int channel_select,
                     const std::string& host,
                     const unsigned short client_sequence_id) = 0;   // stn.h:483

virtual int  Buf2Resp(uint32_t _taskid, void* const _user_context,
                     const std::string& _user_id,
                     const AutoBuffer& _inbuffer, const AutoBuffer& _extend,
                     int& _error_code, uint64_t& _flags,
                     const int channel_select,
                     unsigned short& server_sequence_id) = 0;         // stn.h:493
```

- `Req2Buf`：把业务对象序列化成字节（IM 里就是 protobuf `toByteArray()`）。
- `Buf2Resp`：把响应字节反序列化成业务对象，**返回值决定任务的后续处理**（成功 / 重试 / 直接结束 / 会话超时）。

这个设计让 STN 完全不侵入业务，也让它可以同时承载 HTTP CGI 和自定义二进制协议。

### 4.2 长连接和短连接共享同一个 Task 抽象

`Task.channel_select` 位掩码 + `Task.cgi`（短连的 CGI 路径）+ `Task.cmdid`（长连的命令号）
—— 同一个 Task 描述可以走两条完全不同的物理通道。

好处：**业务层不需要关心当前走的是长连还是短连**。IM 里"重试时从长连降级到短连"这种需求可以零成本实现。

### 4.3 一切都跑在 STN 自己的线程上

- STN 有自己的 `MessageQueue` 线程（`net_core.h:236-237`）。
- 每条长连接还有**自己独占的 pthread**（名字 `::lonklink`，`longlink.cc:173-196`）。
- 业务的 `Req2Buf` / `Buf2Resp` 回调**在 STN 线程上被调用**，所以必须快、不能阻塞。

这是 IM 客户端最容易踩的坑：**在 `Req2Buf` 里做网络 IO = 阻塞整个 STN 线程 = 全部消息延迟**。

---

## 5. 开源版的能力边界（诚实说明）

| 能力 | 开源版有？ | 说明 |
|---|---|---|
| 长连接 / 短连接 / 通道选路 | ✅ | 核心能力齐全 |
| 心跳保活（智能心跳 + UDP 信令） | ✅ | `smart_heartbeat.cc` + `signalling_keeper.cc` |
| 重连退避 | ✅ | `longlink_connect_monitor.cc` |
| 多 IP 竞速建连 | ✅ | `comm/socket/complexconnect.cc` |
| 弱网识别 / 动态超时 | ✅ | `weak_network_logic.cc` + `dynamic_timeout.cc` |
| 雪崩防护 / 频控 / 流控 | ✅ | `anti_avalanche.cc` / `frequency_limit.cc` / `flow_limit.cc` |
| TLS | ⚠️ 部分 | `need_tls` 字段存在，完整 TLS 握手在微信内部版 |
| QUIC | ⚠️ 仅枚举 | `kTransportProtocolQUIC` 只有枚举值，无实现 |
| 私有协议（magic/加密/压缩） | ❌ | 只有 `LongLinkEncoder` 插件接口 |
| 微信内网 cmdid 体系 | ❌ | `cmdInnerPing/cmdAuth/cmdInnerReport` 等不存在 |
| 服务端 | ⚠️ 仅 sample | `samples/Server` 是 Netty Demo，不是生产实现 |

---

## 6. 相关文件速查

| 我想看 | 路径 |
|---|---|
| STN 公共数据结构 | `mars/stn/stn.h` |
| STN 公共 API（全局函数指针版） | `mars/stn/stn_logic.h` / `.cc` |
| STN 实例 API（Context/Manager 版） | `mars/stn/stn_manager.h` / `.cc` |
| 调度引擎 | `mars/stn/src/net_core.cc` |
| 协议编解码 | `mars/stn/proto/longlink_packer.cc`、`shortlink_packer.cc` |
| 长连接 | `mars/stn/src/longlink.cc` |
| 重连策略 | `mars/stn/src/longlink_connect_monitor.cc` |
| Android SDK | `mars/libraries/mars_android_sdk/` |
| IM Demo 客户端 | `samples/android/marsSampleChat/` |
| IM Demo 服务端 | `samples/Server/` |
