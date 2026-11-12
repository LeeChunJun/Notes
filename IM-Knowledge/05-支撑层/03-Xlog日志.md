# 03 · Xlog 日志体系

> 主要源码：`mars/xlog/`、`mars/libraries/mars_android_sdk/.../xlog/Xlog.java`、`Log.java`

---

## 1. 为什么不用 logcat

| logcat 的问题 | Xlog 的解法 |
|---|---|
| **性能差**（Java 层的 `Log.d` 有格式化开销和跨进程写） | native 直接写文件，异步 appender |
| **会丢**（缓冲区满就丢） | 内存队列 + 落盘，容量可控 |
| **无法脱敏** | 内置日志**加密**能力 |
| **无法压缩** | 内置 zlib / zstd 压缩 |
| **无法分级落盘** | 不同级别可以分开文件 |
| **进程被杀丢日志** | `appenderFlush()` 强制刷盘 |
| **无法回传** | 日志文件可打包上传 |

> **IM 场景**：日志是排查"消息为什么没发出去"的唯一手段，
> **必须能离线采集**（用户反馈问题时上传日志）。

---

## 2. 架构

```
┌──────────────────────────────────────────────────┐
│  上层                                             │
│  xlog::XlogInfo / XDEBUG2 / XINFO2 / XERROR2 ...  │
│  Java: Log.v/d/i/w/e/f(tag, fmt, args...)         │
└──────────────────┬───────────────────────────────┘
                   │
┌──────────────────▼───────────────────────────────┐
│  mars/xlog/src/logger.h/.cc                       │
│  - 日志级别过滤                                   │
│  - 格式化                                         │
└──────────────────┬───────────────────────────────┘
                   │
┌──────────────────▼───────────────────────────────┐
│  mars/xlog/src/appender.h/.cc                     │
│  - 内存队列（异步）/ 同步写                        │
│  - 文件轮转（按大小 / 按天）                        │
│  - 压缩（zlib / zstd）                            │
│  - 加密（log_crypt.cc）                            │
└──────────────────┬───────────────────────────────┘
                   │
┌──────────────────▼───────────────────────────────┐
│  mars/comm/xlogger/  (C API 底座)                 │
│  xloggerbase.c / xlogger.h                        │
└──────────────────────────────────────────────────┘
```

---

## 3. C++ 侧的日志宏

`mars/comm/xlogger/xlogger.h`：

| 宏 | 级别 |
|---|---|
| `XDEBUG2` | Debug |
| `XINFO2` | Info |
| `XWARN2` | Warn |
| `XERROR2` | Error |
| `XFATAL2` | Fatal |
| `XVERBOSE2` | Verbose |

**格式**（来自 `owl/mpl/preprocessor.h` 的变参宏库）：
```cpp
xinfo2(TSF "task socket close sock:%0, noop timeout, %1", _sock, _reason);
```

`TSF` 是 "The String Format" 的占位标记，Mars 用它实现**零开销的格式化**：
- 参数以 `void*` 形式压栈，格式化时才真正做类型转换
- 未被使用的参数不会产生字符串拼接开销

> **IM 性能价值**：
> `xinfo2(TSF "...%0...", expensive_computation())` 在日志级别不够时，
> `expensive_computation()` 仍然会被求值（C++ 的参数求值时机），
> 所以**不要在日志里放昂贵的计算**。

---

## 4. 初始化 API

### 4.1 C++

```cpp
// mars/xlog/xlogger.h / mars/xlog/src/xlogger.cc
xlog::XlogInfo::Init(logdir, prefix, level, mode, backupdir, plaintext, ciphersuite);
xlog::XlogInfo::SetConsoleLogOpen(flag);
xlog::XlogInfo::SetLevel(level);
xlog::XlogInfo::Flush();
```

**参数含义**：

| 参数 | 说明 |
|---|---|
| `logdir` | 日志目录 |
| `prefix` | 日志文件名前缀 |
| `level` | 日志级别 |
| `mode` | 压缩模式（zlib / zstd / 压缩级别） |
| `backupdir` | 压缩/加密后的归档目录 |
| `plaintext` | **是否明文**（false = 加密） |
| `ciphersuite` | 加密套件 |

### 4.2 Java

`com/tencent/mars/xlog/Xlog.java`：

```java
public static void open(ILog appender, int level, int mode, String tag,
                        boolean encrypt, String fileName, String logDir);
public static void appenderOpen(int level, int mode, String tag, String filename,
                                String logDir, long cryptKey);
public static void appenderClose();
public static void appenderFlush();
public static void setConsoleLogOpen(boolean open);
public static void openLogInstance();
public static void newXlogInstance(String name);
```

**级别**：
```java
Xlog.LEVEL_ALL, LEVEL_VERBOSE, LEVEL_DEBUG, LEVEL_INFO,
LEVEL_WARN, LEVEL_ERROR, LEVEL_FATAL, LEVEL_NONE
```

**压缩模式**：
```java
Xlog.COMPRESS_LEVEL1 ... COMPRESS_LEVEL9
Xlog.ZLIB_MODE, Xlog.ZSTD_MODE
```

**追加器模式**：
```java
Xlog.AppednerModeAsync, Xlog.AppednerModeSync
```

### 4.3 `Log` API

`com/tencent/mars/xlog/Log.java`：

```java
public static void setLogImp(LogImp logImp);   // ★ 注入 xlog 实现
public static void v/d/i/w/e/f(String tag, String fmt, Object... args);
public static void appenderOpen/Close/Flush();
public static void openLogInstance/closeLogInstance();
public static class LogInstance { ... }         // 多实例日志
```

> **为什么要 `setLogImp`？**
> 因为 `Log` 是一个"门面"（Facade），实际实现由 `LogImp` 注入。
> 这样上层代码不依赖具体的日志库，也方便在测试环境替换。

---

## 5. Android 初始化（sample）

`SampleApplicaton.java:96-128`：

```java
private void openXlog() {
    String processName = getProcessName();
    String logFileName = processName.indexOf(":") == -1 ? "MarsSample"
                        : ("MarsSample_" + processName.substring(processName.indexOf(":") + 1));
    // ...
    xlog.appenderOpen(level, Xlog.AppednerModeAsync, "", logPath, logFileName, 0);
    Log.setLogImp(xlog);
}
```

> ★ **多进程必须用不同的日志文件名**（按进程名后缀区分）。
> 否则多个进程同时写同一个文件会互相覆盖或写坏。

**最佳实践**：在 `Application.onCreate` 里初始化，但在**独立进程**里也要初始化
（`:marsservice` 进程有自己的 `Application` 生命周期？—— 注意 Android 里
**每个进程都有独立的 Application 实例**，所以 `SampleApplicaton.onCreate` 在两个进程都会跑，
这正是 `getProcessName()` 判断的必要性）。

---

## 6. 文件轮转与命名

`mars/xlog/src/appender.h/.cc`：

| 机制 | 说明 |
|---|---|
| **按大小切分** | `appender_set_max_file_size()` |
| **按天切分** | `appender_set_max_alive_duration()` |
| **压缩** | 达到阈值后压缩成 `.zip` / `.zst` |
| **加密** | `mars/xlog/crypt/log_crypt.cc` |
| **归档** | 压缩/加密后移到 `backupdir` |

**文件命名**：`<prefix>_<日期>_<序号>.log`（压缩后加后缀）。

> **IM 运维价值**：
> 客户端日志要能被用户上传，所以要：
> - 加密（防止用户看到敏感信息）
> - 压缩（减少上传流量）
> - 分级（能单独捞出 ERROR 日志）
> - 可配置保留天数

---

## 7. 日志加密

`mars/xlog/crypt/log_crypt.cc`。

> **开源版有完整的日志加密实现**（不同于长连接协议层没有加密）。
> 日志文件加密后即使被捞出去也不会泄露消息内容。

---

## 8. IM 的日志实践

### 8.1 日志级别约定

| 级别 | 用途 | 是否可上传 |
|---|---|---|
| VERBOSE | 详细流程（STN 内部） | ❌ |
| DEBUG | 业务调试信息 | ❌ |
| INFO | **关键业务节点**（消息发送/接收成功、连接建立） | ✅ |
| WARN | 可恢复异常（重试、降级） | ✅ |
| ERROR | 不可恢复异常（消息失败、连接反复断） | ✅ |
| FATAL | 崩溃 | ✅ |

### 8.2 必须打的日志点

| 事件 | 内容 |
|---|---|
| **消息发送** | msgId、convId、耗时、结果 |
| **消息接收（push）** | msgId、convId、延迟 |
| **长连建立/断开** | 原因码、RTT、命中 IP |
| **重连决策** | 间隔、活跃态 |
| **任务失败** | errType、errCode、failStep |
| **登录态变化** | — |

### 8.3 必须脱敏的字段

| 字段 | 处理 |
|---|---|
| **消息内容** | ★ **绝对不能打进日志**（sample 的 `MemoryDump.dumpHex` 打了完整报文） |
| **token / 密码** | 打掩码 |
| **手机号 / 用户 ID** | 部分掩码 |
| **推送 token** | 打掩码 |
| **证书内容** | 不打 |

> **sample 的问题**：`NanoMarsTaskWrapper.java:47` 打了完整报文的 hex dump。
> **生产环境必须移除或脱敏。**

---

## 9. 与 STN 的联动

### 9.1 流量统计

`GetSignalOnNetworkDataChange()(XLOGGER_TAG, writelen, 0)`（`longlink.cc:955`）
→ `StnManager::OnNetworkDataChange`（`stn_manager.cc:128`）
→ `tag == XLOGGER_TAG` 时转 `TrafficData(send, recv)` → 上层统计。

### 9.2 崩溃信号

```cpp
// stn_logic.cc:226-227
GetSignalOnSingalCrash().connect(&onSingalCrash);
GetSignalOnExceptionCrash().connect(&onExceptionCrash);
```

→ `StnManager::OnSingalCrash` → **关闭 xlog appender**（`stn_manager.cc:114-118`）
→ 保证崩溃时日志被刷盘。

> **IM 价值**：崩溃现场能拿到最后一批日志，是排查 IM 崩溃问题的关键。

---

## 10. 常见问题

| 症状 | 原因 | 处理 |
|---|---|---|
| **日志不落盘** | 没调 `appenderOpen` / 目录不可写 | 检查权限 |
| **多进程日志互相覆盖** | 用了同一个文件名 | 按进程名区分 |
| **日志丢失（崩溃时）** | 没 flush | 在 `onSingalCrash` 里 `appenderFlush` |
| **日志占空间** | 没配轮转/保留期 | 配 `max_file_size` + `max_alive_duration` |
| **性能下降** | 日志级别太高 + 异步队列积压 | 降级别 |
| **日志内容是密文** | 加密了 | 用对应 cryptKey 解密 |
| **SDT 打不开日志** | 格式不匹配 | 用 Mars 的 LogViewer |
