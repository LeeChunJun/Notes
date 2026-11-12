# 04 · SDT 与可观测性

> 主要源码：`mars/sdt/`

---

## 1. SDT 是什么

**SDT = Signal Detect Tool**（腾讯内部叫"信令检测工具"）。

它是一个**端上网络诊断 + 数据采集**框架，能力：
1. **网络探测**（ping / dns / tcp / http）
2. **STN 任务追踪**（记录每个 CGI 请求的完整时间线）
3. **DNS 解析结果追踪**
4. **结果上报 / 本地查看**

---

## 2. 目录结构

```
mars/sdt/
├── src/
│   ├── checkimpl/           ★ 网络探测实现
│   │   ├── pingquery.h/.cc      ping 探测
│   │   ├── dnsquery.h/.cc       dns 探测
│   │   ├── tcpquery.h/.cc       tcp 探测
│   │   └── httpquery.h/.cc      http 探测
│   ├── stnlogic/             ★ STN 任务追踪
│   ├── protocol.h/.cc        探测协议
│   └── ...
└── ...
```

---

## 3. 网络探测

`com/tencent/mars/sdt/SdtLogic.java`：

```java
public class SdtLogic {
    public static void setCallBack(ICallBack);
    public static void setHttpNetcheckCGI(String cgi);

    public enum NetCheckType {
        kPingCheck, kDnsCheck, kNewDnsCheck, kTcpCheck, kHttpCheck
    }
    // TcpCheckErrCode ...
}
```

| 探测类型 | 做什么 | 诊断什么 |
|---|---|---|
| `kPingCheck` | ICMP ping | 基础连通性、丢包率、时延 |
| `kDnsCheck` | DNS 解析 | DNS 是否可用、解析耗时、返回 IP 是否合理 |
| `kNewDnsCheck` | HTTPDNS | 自研 DNS 是否正常 |
| `kTcpCheck` | TCP 建连 | 端口是否通、握手耗时 |
| `kHttpCheck` | HTTP 请求 | 业务侧是否可达 |

### 3.1 典型用法

```
用户反馈"消息发不出去"
  → App 调 SdtLogic 触发一轮探测
  → 得到：ping 通 / DNS 通 / TCP 通 / HTTP 失败
  → 结论：网络通，但业务服务不可达 → 提示用户稍后再试 + 上报服务端
```

> **IM 价值**：这是**用户侧自动诊断**的基础。
> 能自动区分"用户没网"和"我们服务挂了"，大幅降低客服成本。

---

## 4. STN 任务追踪

`mars/sdt/src/stnlogic/`。

**记录每个 CGI 请求的完整时间线**：

```
任务开始
  → 排队等待
  → DNS 解析
  → TCP 建连
  → TLS 握手
  → 请求编码
  → 发送
  → 等待响应
  → 响应解码
  → 任务结束
```

这些数据来自 `Callback::ReportTaskProfile(const TaskProfile&)`。

### 4.1 `TaskProfile` 的内容

`mars/stn/task_profile.h`：

| 字段 | 说明 |
|---|---|
| `task` | 原始 Task 副本 |
| `taskid` / `cmdid` / `cgi` | 任务标识 |
| `start_time` / `end_time` | 起止时间 |
| `err_code` / `err_type` | 错误 |
| `dyntime_status` | 动态超时状态（网络质量） |
| `channel_select` / `link_type` | 通道 |
| `history` | **重试链**（每次重试的快照） |
| `ConnectProfile` | DNS 时间、连接时间、命中 IP、端口、RTT、断连原因 |
| `GetFailStep()` | **失败阶段归因** |

### 4.2 `GetFailStep()` 归因优先级

`task_profile.h:447-465`：

```
成功 → DNS → Connect（ip_index == -1）→ FirstPkg（last_receive_pkg_time == 0）
     → Decode（kEctEnDecode）→ PkgPkg（Socket/Http/NetMsgXP）
     → Timeout（kEctLocalTaskTimeout）→ Server → Other
```

> **这张表是线上 IM 质量分析的起点**：
> 拿到失败任务就能判断责任方：
> - `kStepDns` → DNS 服务问题
> - `kStepConnect` → 网络/机房问题
> - `kStepFirstPkg` → 服务端慢/网关丢包
> - `kStepDecode` → 客户端 bug
> - `kStepServer` → 服务端返回错误

### 4.3 Android 侧

`com/tencent/mars/stn/TaskProfile.java:10-75`：

```java
public class TaskProfile {
    public int taskId;
    public int cmdId;
    public String cgi;
    public long startTaskTime;
    public long endTaskTime;
    public int dyntimeStatus;
    public int errCode;
    public int errType;
    public int channelSelect;
    public ConnectProfile[] historyNetLinkers;
}
```

---

## 5. Android 侧的 SDT 接入

`samples/android/marsSampleChat/wrapper/.../service/MarsServiceStub.java:278-280`：

```java
@Override public void reportSignalDetectResults(int detectType, int detectResult,
                                                String detectKeyValue, int[] ipList, int errCode) {
    // 转成 SDTRESULT_CMDID(10005) 推给 UI 进程
}
```

UI 侧展示：`app/.../statistic/SdtReportFragment.java`。

---

## 6. IM 的可观测性体系

### 6.1 四层数据采集

```
┌──────────────────────────────────────────────────────────┐
│ 第 4 层：业务指标（自建）                                  │
│   消息端到端延迟 P50/P90/P99、消息失败率、发送成功率        │
├──────────────────────────────────────────────────────────┤
│ 第 3 层：STN 任务画像（Mars 提供）                          │
│   TaskProfile：每次请求的完整时间线 + 失败归因              │
├──────────────────────────────────────────────────────────┤
│ 第 2 层：连接画像（Mars 提供）                             │
│   ConnectProfile：DNS/TCP/RTT/命中IP/断连原因              │
├──────────────────────────────────────────────────────────┤
│ 第 1 层：原始日志（Xlog）                                   │
│   STN 内部日志 + 业务日志                                   │
└──────────────────────────────────────────────────────────┘
```

### 6.2 必接的 Mars 回调

| 回调 | 用途 | 优先级 |
|---|---|---|
| `ReportTaskProfile` | 任务画像 → 端到端延迟 | ★★★ |
| `ReportConnectStatus` | 连接状态 → 掉线率 | ★★★ |
| `ReportTaskLimited` | 限流命中 → 用户提示 | ★★★ |
| `TrafficData` | 流量统计 | ★★ |
| `ReportDnsProfile` | DNS 画像 | ★★ |
| `OnLongLinkStatusChange` | 长连状态 | ★★ |
| `RequestNetCheckShortLinkHosts` | 网络探测 | ★ |

### 6.3 核心 IM 指标

| 指标 | 计算方式 | 意义 |
|---|---|---|
| **长连建立成功率** | 成功次数 / 尝试次数 | 网络 + 服务端健康度 |
| **平均建连耗时** | `ConnectProfile.conn_cost` 聚合 | 网络质量 |
| **首选 IP 命中率** | `ip_index == 0` 比例 | DNS / 机房健康度 |
| **长连平均存活时长** | `longlink lifetime` 日志聚合 | 稳定性 |
| **心跳失败率** | `SmartHeartbeat.fail_heart_count_` | 链路质量 |
| **消息端到端延迟** | `CgiProfile` 时间戳差 | **用户体验核心** |
| **消息失败率** | `OnTaskEnd` 中失败比例 | 服务端质量 |
| **失败归因分布** | `GetFailStep()` 分布 | 问题定位 |
| **重连间隔分布** | 退避决策日志 | 弱网程度 |
| **切网后恢复时长** | 网络变化 → 消息恢复 | 移动端体验 |

---

## 7. IM 监控告警建议

| 告警 | 阈值（示例） | 含义 |
|---|---|---|
| 长连建立成功率 < 90% | 5 分钟窗口 | 网络或服务端异常 |
| 消息端到端 P99 > 3s | 5 分钟窗口 | 服务端处理慢 |
| 消息失败率 > 5% | 5 分钟窗口 | 服务端错误 |
| 心跳失败率 > 10% | 10 分钟窗口 | 链路质量差 |
| 首包超时占比 > 20% | 10 分钟窗口 | 服务端慢 |
| 雪崩拦截数突增 | 5 分钟窗口 | 服务端故障 |
| 重连间隔中位数 > 60s | 30 分钟窗口 | 弱网用户占比高 |

---

## 8. 与 SDT 相关的调试技巧

| 场景 | 工具 |
|---|---|
| 看某个任务的完整时间线 | `ReportTaskProfile` 上报到自建后台 |
| 复现网络问题 | `SdtLogic` 触发一轮探测 |
| 看 STN 内部决策 | Xlog 搜关键字（见 `03-长连接/05-异常处理与断链恢复.md` §8） |
| 看 DNS 解析结果 | `ReportDnsProfile` |
| 看流量 | `TrafficData` |
| 离线分析 | Xlog 文件（加密的话用 cryptKey 解密） |
