# 01 · STN 公开 API 与数据结构

> 主要源码：`mars/stn/stn.h`（543 行）、`stn.cc`、`stn_logic.h/.cc`、`stn_manager.h/.cc`

---

## 1. 三个 API 形态

Mars 同时提供 **三套** 调用方式，新旧并存：

| 形态 | 入口 | 说明 |
|---|---|---|
| **A. legacy 全局函数指针** | `mars/stn/stn_logic.h` | `extern void (*StartTask)(const Task&);` —— App 可直接替换实现。**微信内部版就是这一套** |
| **B. 实例 API（推荐）** | `mars/stn/stn_manager.h` | `mars::stn::StnManager`（继承 `boot::BaseManager`），从 `Context` 取 |
| **C. Java API** | `mars/libraries/mars_android_sdk/.../StnLogic.java` | Android 静态方法 + `Task` 内部类 |

B 的默认实现就转发到 A（`stn_logic.cc:192-206`），所以两者行为一致。

---

## 2. `struct Task` —— 核心数据结构

`mars/stn/stn.h:60-146`

### 2.1 必填字段

| 字段 | 类型 | 行 | 说明 |
|---|---|---|---|
| `taskid` | `uint32_t` | 99 | 任务唯一标识。**由 `GenTaskID()` 生成**，也是线上帧的 seq |
| `cmdid` | `uint32_t` | 100 | 协议命令号。**走长连时不可为 0**（`net_core.cc:269-274` 会自动摘除 `kChannelLong`） |
| `channel_id` | `uint64_t` | 101 | 注释 "not used"，实际用于长连 profile 的 `start_time` 校验（`longlink_task_manager.cc:548`） |
| `channel_select` | `int32_t` | 102 | 通道位掩码 |
| `transport_protocol` | `int32_t` | 103 | TCP/QUIC |
| `cgi` | `std::string` | 104 | CGI 路径。**短连必填**（`net_core.cc:276-282`） |

### 2.2 可选字段 + 默认值

默认值在 `mars/stn/stn.cc:36-68`（`Task::Task()`）填充。

| 字段 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `send_only` | bool | `false` | **只发不收**（仅长连支持，`longlink_task_manager.cc:654`）。心跳/信令包用 |
| `need_authed` | bool | `false` | 需要上层完成鉴权后才能发。**注意 Java 侧默认是 `true`**（`StnLogic.java`），C++ 默认 false，两边不一致 |
| `limit_flow` | bool | `true` | 参与流量漏斗限制 |
| `limit_frequency` | bool | `true` | 参与频次雪崩限制 |
| `network_status_sensitive` | bool | `false` | 无网络时**直接失败**而不是排队（`net_core.cc:430-443`） |
| `channel_strategy` | int32 | `kChannelNormalStrategy` | 选路策略 |
| `priority` | int32 | `kTaskPriorityNormal(3)` | **队列排序键，越小越先发** |
| `retry_count` | int32 | `-1` → `DEF_TASK_RETRY_COUNT(1)` | 重试次数。**>30 视为非法**（`net_core.cc:259`） |
| `server_process_cost` | int32 | `-1` | 服务端预估处理耗时(ms)，用于动态超时。>120000 非法 |
| `total_timeout` | int32 | `-1` → `DEF_TASK_TIME_OUT(40s)` | 任务总超时(ms)。>600000 非法 |
| `long_polling` | bool | `false` | 长轮询 |
| `long_polling_timeout` | int32 | `-1` | 长轮询超时(ms) |
| `user_context` | void* | NULL | 业务上下文，回调时原样返回。**IM 里放消息对象** |
| `report_arg` | string | — | CGI 上报参数 |
| `channel_name` | string | `DEFAULT_LONGLINK_NAME` | 长连通道名（多长连时区分） |
| `group_name` | string | — | 解码方法分组 |
| `user_id` | string | — | 多用户标识（按 user 批量重试用） |
| `headers` | map | — | HTTP 头（短连） |
| `shortlink_host_list` | vector | — | 当前使用的短连 host 列表 |
| `shortlink_fallback_hostlist` | vector | — | 降级用 host（QUIC 失败回退 TCP） |
| `longlink_host_list` | vector | — | 长连 host 列表 |
| `minorlong_host_list` | vector | — | 副长连 host 列表 |
| `quic_host_list` | vector | — | QUIC host 列表 |
| `max_minorlinks` | int32 | `1` | 副长连最大数量 |
| `redirect_type` | HostRedirectType | `kOther` | Host 重定向类型 |
| `client_sequence_id` | ushort | `0` | 与后台上报对应的 trace id |
| `server_sequence_id` | ushort | `0` | 服务端返回的对账 id |
| `need_realtime_netinfo` | bool | `false` | 需要实时网络信息（网络穿越校验） |
| `extra_info` | map | — | 解析 host 时透传给上层 |

### 2.3 保留 ID（`stn.h:89-93`）

```cpp
static const uint32_t kInvalidTaskID                 = 0;
static const uint32_t kNoopTaskID                    = 0xFFFFFFFF;  // 心跳
static const uint32_t kLongLinkIdentifyCheckerTaskID = 0xFFFFFFFE;  // 信令校验
static const uint32_t kSignallingKeeperTaskID        = 0xFFFFFFFD;  // TCP 路径信令包
static const uint32_t kMinorLonglinkCmdMask          = 0xFF000000;  // 副长连 cmdid 高位掩码
```

加上 `kReservedTaskIDStart = 0xFFFFFFF0`（`stn.h:44`）：`0xFFFFFFF0` 以上全归内部任务。

> **IM 意义**：这些保留值把"内部协议"和"业务协议"在 seq 空间里隔开了，
> 业务 taskid 永远落在 `1 .. 0xFFFFFFEF`。

---

## 3. `class Callback` —— 上层必须实现的接口

`mars/stn/stn.h:465-539`。**13 个纯虚函数**。

| 虚函数 | 行 | 纯虚? | 用途 |
|---|---|---|---|
| `bool MakesureAuthed(const std::string& _host, const std::string& _user_id)` | 469 | ✅ | **确保已登录**。返回 false 时任务挂起等待（`longlink_task_manager.cc:471-483`） |
| `void TrafficData(int64_t _send, int64_t _recv)` | 472 | ✅ | 流量统计上报 |
| `std::vector<std::string> OnNewDns(const std::string& host, bool _longlink_host)` | 475 | ✅ | 上层提供 host→IP 列表。**留空则用底层 DNS** |
| `void OnPush(const std::string& _channel_id, uint32_t _cmdid, uint32_t _taskid, const AutoBuffer& _body, const AutoBuffer& _extend)` | 477 | ✅ | **服务端推送**（IM 的下行消息入口） |
| `bool Req2Buf(uint32_t _taskid, void* const _user_context, const std::string& _user_id, AutoBuffer& outbuffer, AutoBuffer& extend, int& error_code, const int channel_select, const std::string& host, const unsigned short client_sequence_id)` | 483 | ✅ | **编码请求体**（业务入口 1/2） |
| `int Buf2Resp(uint32_t _taskid, void* const _user_context, const std::string& _user_id, const AutoBuffer& _inbuffer, const AutoBuffer& _extend, int& _error_code, uint64_t& _flags, const int _channel_select, ushort& server_sequence_id)` | 493 | ✅ | **解码回包**（业务入口 2/2）。**返回值 = `TaskFailHandleType`** |
| `int OnTaskEnd(uint32_t _taskid, void* const _user_context, const std::string& _user_id, int _error_type, int _error_code, const CgiProfile& _profile)` | 503 | ✅ | 任务结束通知。**返回值会覆盖 err_code** |
| `void ReportConnectStatus(int _status, int _longlink_status)` | 511 | ✅ | 连接状态上报（`NetStatus` + 长连状态） |
| `void OnLongLinkNetworkError(ErrCmdType, int, const std::string&, ushort)` | 512 | ❌ | 长连网络错误（默认空实现） |
| `void OnShortLinkNetworkError(ErrCmdType, int, const std::string&, const std::string&, ushort)` | 514 | ❌ | 短连网络错误 |
| `void OnLongLinkStatusChange(int _status)` | 521 | ❌ | 长连状态变化（`LongLink::TLongLinkStatus`） |
| `int GetLonglinkIdentifyCheckBuffer(const std::string& _channel_id, AutoBuffer& _identify_buffer, AutoBuffer& _buffer_hash, int32_t& _cmdid)` | 524 | ✅ | 长连信令校验包。返回 `IdentifyMode` |
| `bool OnLonglinkIdentifyResponse(const std::string& _channel_id, const AutoBuffer& _response_buffer, const AutoBuffer& _identify_buffer_hash)` | 529 | ✅ | 校验回包 |
| `void RequestSync()` | 533 | ✅ | 请求做 sync |
| `void RequestNetCheckShortLinkHosts(std::vector<std::string>& _hostlist)` | 535 | ✅ | 网络检查域名列表 |
| `void ReportTaskProfile(const TaskProfile& _task_profile)` | 536 | ✅ | CGI 执行结果上报（性能画像） |
| `void ReportTaskLimited(int _check_type, const Task& _task, unsigned int& _param)` | 537 | ✅ | 命中限流上报。`_check_type`：`kFrequencyLimit=1` / `kFlowLimit=2`（`anti_avalanche.h:33`） |
| `void ReportDnsProfile(const DnsProfile& _dns_profile)` | 538 | ✅ | DNS 结果上报 |

### 3.1 IM 视角的必填最小集

真正必须写业务逻辑的只有 4 个：

```
Req2Buf       → protobuf 序列化
Buf2Resp      → protobuf 反序列化 + 返回错误处理策略
OnTaskEnd     → 任务结束通知（切主线程）
OnPush        → 收到服务端推送
```

其余 9 个纯虚函数可以先返回默认值（sample 就是这么干的）：
`makesureAuthed` → `return true`（`MarsServiceStub.java:150-155`）
`onNewDns` → `return null`（`:158-161`）
`getLongLinkIdentifyCheckBuffer` → `return StnLogic.ECHECK_NEVER`（`:188-193`）
`isLogoned` → `return false`（`:211-213`）
`reportConnectInfo` → 空实现（`:183-185`）

---

## 4. `struct CgiProfile` —— 性能画像

`mars/stn/stn.h:148-168`。**所有时间字段单位都是 tickcount（ms）**。

| 字段 | 含义 |
|---|---|
| `start_time` | 任务开始 |
| `start_connect_time` | 开始建连 |
| `connect_successful_time` | 建连成功 |
| `start_tls_handshake_time` / `tls_handshake_successful_time` | TLS 握手起止 |
| `start_send_packet_time` / `send_packet_finished_time` | 首次发送请求起止 |
| `start_read_packet_time` / `read_packet_finished_time` | 首次接收数据起止 |
| `start_encode_packet_time` / `encode_packet_finished_time` | **req2buf 打包起止** |
| `start_decode_packet_time` / `decode_packet_finished_time` | **buf2resp 解包起止** |
| `channel_type` / `transport_protocol` | 通道与协议 |
| `rtt` | RTT |
| `nettype` | 网络类型 |

> **IM 价值**：这就是你的"消息端到端耗时"埋点数据源。
> 一次消息从点击发送到 UI 收到 ACK 的完整时间线都能拆出来：
> `排队等待 + 建连 + TLS + 编码 + 网络往返 + 解码`。

---

## 5. 其他公共结构

### `struct ConnectCtrl` / `ConnectPorts`（`stn.h:170-182`）

```cpp
struct ConnectCtrl {
    int from_source = 0;                     // DEFAULT
    unsigned interval_ms = 2500;             // 连接间隔
    unsigned ipv4_timeout_ms = 10 * 1000;    // IPv4 连接超时
    unsigned ipv6_timeout_ms = 10 * 1000;    // IPv6 连接超时
    unsigned maxconn = 3;                   // 最大并发连接
    unsigned ipv4_zerortt_check_ms = 300;   // IPv4 0-RTT 检查窗口
    unsigned ipv6_zerortt_check_ms = 400;   // IPv6 0-RTT 检查窗口
};
```

### `struct LonglinkConfig`（`stn.h:184-211`）

```cpp
LonglinkConfig(const std::string& _name,
               const std::string& _group = DEFAULT_LONGLINK_GROUP,
               bool _isMain = false);
// 字段：
std::string name;                            // channel_id
std::vector<std::string> host_list;
bool is_keep_alive;                          // false => 由 task 触发重连（不自动重连）
std::string group;
LongLinkEncoder* longlink_encoder;           // ★ 自定义协议编码器
bool isMain;
int link_type = Task::kChannelLong;          // 可为 kChannelMinorLong
int packer_encoder_version = PackerEncoderVersion::kOld;
std::string packer_encoder_name = "";
std::vector<std::string> (*dns_func)(...);   // 自定义 DNS
bool need_tls;                               // 默认 true
```

### `struct ShortlinkConfig` / `QuicParameters`（`stn.h:213-229`）

```cpp
struct QuicParameters {
    bool enable_0rtt = true;
    std::string alpn;
    std::string hostname;
    unsigned conn_timeout_ms = 0;
};
struct ShortlinkConfig {
    ShortlinkConfig(bool _use_proxy, bool _use_tls, std::string _tls_group);
    bool use_proxy = false;
    bool use_tls = true;
    bool use_quic = false;
    QuicParameters quic;
    std::string tls_group;
};
```

---

## 6. 全局 API（legacy 函数指针版）

`mars/stn/stn_logic.h`。所有"可被 App 覆盖"的接口都是 **全局函数指针变量**。

### 6.1 配置类

| 声明 | 行 | 说明 |
|---|---|---|
| `void SetCallback(Callback* const callback)` | 92 | 注册上层回调 |
| `void (*SetLonglinkSvrAddr)(const std::string& host, const std::vector<uint16_t> ports, const std::string& debugip)` | 98-100 | 设置长连 server 地址。**`debugip` 非空时忽略 `host`** |
| `void (*SetShortlinkSvrAddr)(const uint16_t port, const std::string& debugip)` | 103 | 设置短连端口 |
| `void (*SetDebugIP)(const std::string& host, const std::string& ip)` | 106 | 为指定 host 设置调试 IP（**优先级最高**） |
| `void (*SetBackupIPs)(const std::string& host, const std::vector<std::string>& iplist)` | 111 | 备份 IP（newdns/dns 不可用时兜底） |

### 6.2 任务生命周期类

| 声明 | 行 | 同步性 |
|---|---|---|
| `bool (*StartTask)(const Task& task)` | 114 | **异步**（返回 true 只表示已投递） |
| `void (*StopTask)(uint32_t taskid)` | 117 | **同步**（内部 `ASYNC_BLOCK_START`） |
| `bool (*HasTask)(uint32_t taskid)` | 120 | 同步 |
| `void (*RedoTasks)()` | 124 | **重连长连并重做所有任务**（改 server IP 后必须调用） |
| `void (*TouchTasks)()` | 127 | 手动驱动任务循环（一般鉴权成功后调用） |
| `void (*DisableLongLink)()` | 130 | 关闭长连能力 |
| `void (*ClearTasks)()` | 133 | 停止并清除所有任务 |
| `void (*Reset)()` | 136 | ClearTasks + 重建 NetCore |
| `void (*ResetAndInitEncoderVersion)(int, std::string)` | 138 | 同上 + 设置打包器版本 |

### 6.3 长连/信令类

| 声明 | 行 | 说明 |
|---|---|---|
| `void (*SetSignallingStrategy)(long period, long keeptime)` | 142 | 信令保活参数，默认 **5s / 20s** |
| `void (*KeepSignalling)()` | 146 | 主动保活一次 |
| `void (*StopSignalling)()` | 148 | 停止保活 |
| `void (*MakesureLonglinkConnected)()` | 151 | 立即触发长连建连 |
| `bool (*LongLinkIsConnected)()` | 153 | 查询长连状态 |
| `std::shared_ptr<LongLink> DefaultLongLink()` | 159 | 获取默认长连实例 |
| `uint32_t (*getNoopTaskID)()` | 163 | noop 任务 ID |
| `bool (*ProxyIsAvailable)(...)` | 155-157 | 代理可用性探测 |

### 6.4 多长连扩展（`_ext`）

> 源码注释明确标注 "these APIs are subject to change in developing"。

| 声明 | 行 | 实现 |
|---|---|---|
| `void (*CreateLonglink_ext)(LonglinkConfig& _config)` | 171 | `stn_logic.cc:507` |
| `void (*DestroyLonglink_ext)(const std::string& name)` | 172 | `stn_logic.cc:518` |
| `std::vector<std::string> (*GetAllLonglink_ext)()` | 173 | **未实现**（`stn_manager.cc:632` 返回空 vector） |
| `void (*MarkMainLonglink_ext)(const std::string& name)` | 174 | `stn_logic.cc:543` |
| `bool (*LongLinkIsConnected_ext)(const std::string& name)` | 176 | `stn_logic.cc:529` |
| `void (*MakesureLonglinkConnected_ext)(const std::string& name)` | 177 | `stn_logic.cc:554` |

### 6.5 回调自由函数（`stn_logic.h:184-261`）

这些是 `Callback` 方法的全局转发版本，实现位于 `stn_logic.cc:576-806`：

```cpp
bool   MakesureAuthed(const std::string& _host, const std::string& _user_id);   // :184
void   TrafficData(int64_t _send, int64_t _recv);                                 // :187
std::vector<std::string> OnNewDns(const std::string& _host, bool _longlink_host,
                                   const std::map<std::string,std::string>& _extra_info);  // :190
void   OnPush(const std::string& _channel_id, uint32_t _cmdid, uint32_t _taskid,
              const AutoBuffer& _body, const AutoBuffer& _extend);                // :194
bool   Req2Buf(...);                                                              // :200
int    Buf2Resp(...);                                                             // :210
int    OnTaskEnd(...);                                                            // :220
void   ReportConnectStatus(int status, int longlink_status);                      // :228
void   OnLongLinkNetworkError(...);                                               // :230
void   OnShortLinkNetworkError(...);                                              // :231
void   OnLongLinkStatusChange(int _status);                                       // :237
int    GetLonglinkIdentifyCheckBuffer(...);                                       // :239
bool   OnLonglinkIdentifyResponse(...);                                           // :244
void   RequestSync();                                                             // :248
void   RequestNetCheckShortLinkHosts(std::vector<std::string>& _hostlist);        // :252
void   ReportTaskProfile(const TaskProfile& _task_profile);                        // :254
void   ReportTaskLimited(int _check_type, const Task& _task, unsigned int& _param); // :256
void   ReportDnsProfile(const DnsProfile& _dns_profile);                          // :258
uint32_t GenTaskID();                                                             // :260
unsigned short GenSequenceId();                                                   // :261
```

### 6.6 回调桥

```cpp
void SetStnCallbackBridge(StnCallbackBridge* _callback_bridge);  // :180
StnCallbackBridge* GetStnCallbackBridge();                       // :181
```

---

## 7. `StnManager`（实例 API）

`mars/stn/stn_manager.h:26`。`class StnManager : public mars::boot::BaseManager`。

### 7.1 生命周期钩子

| 方法 | 头 | 实现 | 说明 |
|---|---|---|---|
| `OnInitConfigBeforeOnCreate(int _packer_encoder_version)` | 36 | 64 | OnCreate 前设置打包器版本 |
| `OnInitConfigBeforeOnCreateV2(int, std::string)` | 37 | 69 | 同上 + encoder 名 |
| `OnCreate()` | 38 | 77 | 创建 `NetCore` |
| `OnDestroy()` | 39 | 96 | `NetCore::__Release` → 删 callback_bridge_ |
| `OnSingalCrash(int _sig)` | 40 | 114 | 关闭 xlog appender |
| `OnExceptionCrash()` | 41 | 118 | 同上 |
| `OnNetworkChange(void (*pre_change)())` | 42 | 122 | 先 `pre_change()` 再 `net_core_->OnNetworkChange()` |
| `OnNetworkDataChange(const char* _tag, int64_t _send, int64_t _recv)` | 43 | 128 | `tag == XLOGGER_TAG` 时转 `TrafficData` |
| `OnAlarm(int64_t _id)` | 45 | 140 | **仅 Android** |

### 7.2 外部接口（与 §6 对应）

`SetCallback` / `SetStnCallbackBridge` / `GetStnCallbackBridge` / `SetDefaultLongLinkEncoder` / `SetDefaultShortLinkTlsGroup`（默认 `"default"`）/
`SetLonglinkSvrAddr` / `SetShortlinkSvrAddr` / `SetDebugIP` / `SetBackupIPs` /
`StartTask` / `StopTask` / `HasTask` / `RedoTasks` / `TouchTasks` / `DisableLongLink` / `ClearTasks` / `Reset` / `ResetAndInitEncoderVersion` /
`SetSignallingStrategy` / `KeepSignalling` / `StopSignalling` / `MakesureLonglinkConnected` / `LongLinkIsConnected` / `DefaultLongLink` /
`ProxyIsAvailable` / `getNoopTaskID` / `CreateLonglink_ext` / `DestroyLonglink_ext` / `GetAllLonglink_ext` / `MarkMainLonglink_ext` / `LongLinkIsConnected_ext` /
`GetLongLinkHosts` / `SetLongLink` / `SetShortlink` / `GenTaskID` / `GenSequenceId`

### 7.3 内部成员

```cpp
Callback* callback_ = NULL;
StnCallbackBridge* callback_bridge_ = NULL;
std::shared_ptr<NetCore> net_core_ = NULL;
int packer_encoder_version_;
std::string packer_encoder_name_;
std::vector<std::string> empty_longlink_hosts;
LongLinkEncoder* default_longlink_encoder = nullptr;
std::string default_tls_group_name = "default";
```

---

## 8. `GenTaskID` 的回绕保护

`mars/stn/stn_manager.cc:421-427`：

```cpp
uint32_t StnManager::GenTaskID() {
    if (BOOST_UNLIKELY(atomic_read32(&gs_taskid) >= kReservedTaskIDStart)) {
        atomic_write32(&gs_taskid, 1);
    }
    return atomic_inc32(&gs_taskid);
}
```

`kReservedTaskIDStart = 0xFFFFFFF0`。即：**taskid 用到 0xFFFFFFF0 就回绕到 1**，永不与内部保留 ID 冲突。

底层实现（`stn.cc:32-34`）：

```cpp
static uint32_t gs_taskid = 1;
Task::Task() : Task(atomic_inc32(&gs_taskid)) {}
```

> **IM 注意**：taskid 是 32 位，理论上会回绕。如果你的服务端把 taskid 当作幂等键，需要考虑回绕（Mars 本身不保证）。
> **正确做法**：幂等键用业务层的 `client_sequence_id` 或业务消息 ID，不要用 taskid。

---

## 9. Java 侧 `StnLogic.Task`

`mars/libraries/mars_android_sdk/src/main/java/com/tencent/mars/stn/StnLogic.java:24-101`

```java
public static class Task {
    // 必填
    public int taskID;
    public int channelSelect;
    public int cmdID;
    public String cgi;
    public String[] shortLinkHostList;
    // 可选
    public boolean sendOnly;
    public boolean needAuthed = true;      // ★ 与 C++ 默认值不同！
    public boolean limitFlow = true;
    public boolean limitFrequency = true;
    public int channelStrategy = EChannelStrategyNormal;
    public boolean networkStatusSensitive = false;
    public int priority = ETASK_PRIORITY_NORMAL;   // = 3
    public int retryCount = -1;
    public int serverProcessCost = -1;
    public int totalTimeout = 0;
    public Object userContext;
    public String reportArg;
    public Map<String,String> headers;
    public boolean longPolling;
    public int longPollingTimeout = -1;
}
```

**通道常量**（`StnLogic.java:40-42`）：
```java
public static final int EShort = 0x1;
public static final int ELong  = 0x2;
public static final int EBoth  = 0x3;
```
