# 01 · comm/socket 与多 IP 竞速

> 主要源码：`mars/comm/socket/`、`mars/comm/unix/socket/`

（详细的多 IP 竞速算法见 `03-长连接/02-建连与多IP竞速.md`，这里补充 socket 层细节与 IM 视角。）

---

## 1. `mars/comm/socket/` 文件清单

| 文件 | 职责 |
|---|---|
| `complexconnect.h/.cc` | ★ 多 IP 并发连接（Happy Eyeballs 思想） |
| `tcpclient_fsm.h/.cc` | TCP 连接状态机（非阻塞 connect / 读写） |
| `unix_socket.h/.cc` | socket API 封装 + 选项设置 |
| `socketbreaker.h` | 打断机制（平台分发） |
| `socket_address.h` | 地址抽象（v4/v6/proxy） |
| `local_ipstack.h/.cc` | IPv4/IPv6 栈探测 |
| `getipinfo.h` | IP 信息 |
| `block_socket.h/.cc` | 阻塞式 socket（短连用） |

---

## 2. `unix_socket.h/.cc` —— socket API 封装

`mars/comm/socket/unix_socket.h:109-133` 声明：

| 函数 | 位置 | 作用 |
|---|---|---|
| `socket_set_nobio(fd)` | `.cc:25-39` | `fcntl(F_SETFL, O_NONBLOCK)`（Win: `ioctlsocket(FIONBIO)`） |
| `socket_set_tcp_mss(fd, size)` | `.cc:331-337` | `setsockopt(IPPROTO_TCP, TCP_MAXSEG)` |
| `socket_get_tcp_mss(fd, &size)` | `.cc:339-348` | `getsockopt(TCP_MAXSEG)` |
| **`socket_fix_tcp_mss(fd)`** | `.cc:350-352` | **强制 MSS = 1400** |
| **`socket_disable_nagle(sock, nagle)`** | `.cc:354-356` | `setsockopt(TCP_NODELAY)` |
| `socket_error(sock)` | `.cc:358-365` | `getsockopt(SOL_SOCKET, SO_ERROR)` |
| `socket_isnonetwork(err)` | `.cc:367-374` | `ENETDOWN`/`ENETUNREACH` → 无网 |
| `socket_reuseaddr(sock, opt)` | `.cc:376-378` | `SO_REUSEADDR` |
| `socket_reserve_sendbuf(sock, len)` | `.cc:380-388` | 读 `SO_SNDBUF`，**仅当当前值 < 目标值才设置** |
| `socket_get_nwrite/nread` | `.cc:390-432` | iOS `SO_NWRITE/SO_NREAD`；Android `ioctl(SIOCOUTQ/SIOCINQ)` |
| `socket_ipv6only(sock, only)` | `.cc:434-436` | `setsockopt(IPPROTO_IPV6, IPV6_V6ONLY)` |

### 2.1 `socket_reserve_sendbuf` 的"只增不减"

```cpp
// unix_socket.cc:380-388
// 读 SO_SNDBUF，仅当当前值 < 目标值才 setsockopt
```

> **原因**：Linux 会把 `setsockopt(SO_SNDBUF, n)` 的值**翻倍**作为内部缓冲，
> 且**不能调小于内核默认值**。强行设置反而可能被内核加倍变大。
> **必须"先读再判断"。**
>
> 这个坑只有真正在 Linux/Android 上调过 socket 才知道。

---

## 3. `TcpClientFSM` —— TCP 状态机

`mars/comm/socket/tcpclient_fsm.h/.cc`。

### 3.1 状态

```cpp
// tcpclient_fsm.h:36-41
enum TSocketStatus {
    EStart = 0,
    EConnecting,
    EReadWrite,
    EEnd,
};
```

### 3.2 `PreConnectSelect`（`:167-237`）

```
socket()
  → socket_fix_tcp_mss()      // Wi-Fi 下强制 MSS=1400
  → socket_ipv6only(false)    // 仅 Windows
  → socket_set_nobio()
  → connect()                 // 非阻塞，EINPROGRESS 视为成功
  → 状态 = EConnecting，注册 write + exception 事件
```

### 3.3 `AfterConnectSelect`（`:239-298`）

| 情况 | 结果 |
|---|---|
| `Exception_FD_ISSET` | 取 `SO_ERROR` → 失败 |
| `Write_FD_ISSET` 且 error == 0 | **`EReadWrite` + `_OnConnected(Rtt())`** |
| 超时 | `ETIMEDOUT` |

### 3.4 `AfterReadWriteSelect`（`:310-420`）

- `send()` 后 `send_buf_.Move(-ret)` —— **消费已发送部分，无需 memcpy**
- `recv()` 前若 `Capacity() - Length() < 8KB` → `AddCapacity(16KB)`（`:364-366`）—— **按需扩容**

---

## 4. `SocketBreaker` —— 打断阻塞 I/O

`mars/comm/unix/socket/socketbreaker.h:28-58` / `.cc`。

### 4.1 原理

Linux `pipe()` 造一对 fd，都设为 `O_NONBLOCK`（`.cc:69-70`）。
写端 fd 被注册进 `poll()` 集合；
`Break()` 往管道写 1 字节 → `poll` 立即返回 → 阻塞中的 `Select()` 被唤醒。

### 4.2 API 与实现

```cpp
// unix/socket/socketbreaker.h:33-45
bool IsCreateSuc() const;
bool ReCreate();                       // 重建管道
void Close();
bool Break();                          // 写 1 字节打断
bool Break(int reason);                // 带原因码
bool Clear();                          // 读空管道，复位
bool PreciseBreak(uint32_t cookie);   // 写 4 字节 cookie
bool PreciseClear(uint32_t* cookie);
bool IsBreak() const;
int  BreakerFD() const;               // 读端 fd，注册进 poll
int  BreakReason() const;
```

| 实现 | 位置 | 要点 |
|---|---|---|
| `ReCreate()` | `.cc:52-80` | `pipe()` → 两端 `fcntl(F_SETFL, O_NONBLOCK)` |
| `Break()` | `.cc:82-99` | **幂等**（`breaked_` 已置位直接 true）；写 1 字节 `'1'` |
| `Clear()` | `.cc:110-146` | 循环 `read()` 到 `EWOULDBLOCK` |
| `PreciseBreak/Clear` | `.cc:148-179` | 4 字节 `uint32_t cookie`；`PreciseClear` 用零超时 `poll` 探测 |

### 4.3 在 `SocketSelect` 中的角色

```cpp
// unix/socket/socketselect.h:67-100
bool SocketSelect::IsBreak()    { return socket_poll_.BreakerIsBreak(); }
bool SocketSelect::IsException(){ return socket_poll_.BreakerIsError(); }
```

> macOS 上曾用 kqueue 实现（`.cc:20-179` 的 `#if 0` 死代码保留），现统一为 poll。

---

## 5. `socket_address` 与 NAT64

`mars/comm/socket/socket_address.h`。

**核心方法**：
- `IsV6()` —— 判断是否 IPv6
- `v4tov6_address(localstack)` —— **把 v4 地址映射成 v4-mapped-v6（`::ffff:a.b.c.d`）**

**NAT64 场景**：纯 IPv6 网络下访问 IPv4-only 服务器需要 NAT64 网关做协议转换。
统一映射后代码路径一致。

`mars/comm/socket/local_ipstack.h/.cc`：
探测当前设备是 IPv4-only / IPv6-only / 双栈。

---

## 6. `mars/comm/network/` —— 网络信息

| 文件 | 职责 |
|---|---|
| `netinfo_util.h/.cc` | `GetNetworkType()` / `GetDetailNetInfo()` / `get_local_ip()` |
| `getifaddrs.h/.cc` | 网卡枚举 |
| `getgateway.h/.cc` | 网关探测（判断是否在热点/受限网络） |
| `local_routetable.h/.cc` | 本地路由表 |
| `getdnssvraddrs.h/.cc` | DNS 服务器地址 |
| `getaddrinfo_with_timeout.h/.cc` | ★ 带超时的 `getaddrinfo` |

### 6.1 `getaddrinfo_with_timeout`

**为什么需要**：系统 `getaddrinfo` 在 DNS 服务器不可达时会**阻塞几十秒**，
完全无法取消。Mars 自己实现了一个带超时的版本。

> **IM 价值**：DNS 超时直接决定长连建立的最坏耗时。
> 默认的 `getaddrinfo` 卡 30 秒是不可接受的。

### 6.2 `getgateway`

判断当前网络是否有网关 → 用于识别**热点/受限网络**（很多酒店/机场 Wi-Fi 会拦截长连接）。

---

## 7. `mars/comm/dns/` —— DNS

`mars/comm/dns/dns.h:46` `class DNS`。

功能：
- 普通 DNS 解析
- **NewDNS**（腾讯自研的 HTTPDNS，通过 HTTP 请求获取 IP 列表）

> **IM 价值**：HTTPDNS 能解决"运营商劫持 DNS"和"CDN 调度不准"的问题。
> Mars 的 `IPSourceType::kIPSourceNewDns` 就是它。

---

## 8. `mars/comm/thread/` —— 线程原语

`mars/comm/thread/thread.h` → `mars/comm/unix/thread/thread.h`。

提供：`Thread` / `Mutex` / `Lock` / `Condition` / `SpinLock`。

### 8.1 `Thread::start(bool* _newone)`

`mars/comm/unix/thread/thread.h:179-208`：

```cpp
bool start(bool* _newone = NULL);
```

**语义**：线程已在运行 → 直接返回，`*_newone = false`；否则 `pthread_create`，`*_newone = true`。

> **这是 Mars 长连接"幂等启动"的基础**（见 `03-长连接/01-长连接生命周期.md` §3.1）。

---

## 9. `mars/comm/alarm.h` —— 跨线程定时器

```cpp
class Alarm {
 public:
    Alarm(boost::function<void()> _func, bool _repeat = false, boot::Context* = NULL);
    void Start(int after_ms);      // 启动
    void Cancel();                 // 取消
    bool IsWaiting() const;        // 是否在等待
    Alarm::TStatus Status() const; // kWaiting / kOnAlarm
    uint64_t After() const;        // 剩余时间
    uint64_t ElapseTime() const;   // 已经过的时间
};
```

**用途**：
- 长连心跳间隔 / 心跳超时
- 重连退避
- 动态超时计时

> **IM 价值**：`Alarm` 跑在 **MessageQueue 线程**上（不是 OS 定时器），
> 所以它的精度受队列调度影响（1 秒 tick 的任务里可能偏几毫秒）。

---

## 10. IM 视角的 socket 优化清单

| 优化 | Mars 默认 | 说明 |
|---|---|---|
| **禁 Nagle** | ✅ 长连已开 | 小消息立即发 |
| **MSS 1400** | ✅ Wi-Fi 下 | 绕 PMTU 黑洞 |
| **多 IP 竞速** | ✅ | Happy Eyeballs |
| **TCP keepalive** | ❌ 不用 | 应用层心跳更可靠 |
| **SO_SNDBUF 预留** | ❌ 无调用点 | **可以自己加**，大消息场景有用 |
| **SO_RCVBUF 预留** | ❌ | 同上 |
| **IPv6 双栈** | ✅ | |
| **NAT64 映射** | ✅ | |
| **连接超时** | 10s | IM 场景建议降到 5s |
| **并发连接数** | 3 | 弱网可提到 4~5 |
