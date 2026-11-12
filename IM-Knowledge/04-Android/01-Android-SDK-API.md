# 01 · Android SDK API 清单

> 来源：`mars/libraries/mars_android_sdk/src/main/java/com/tencent/mars/`

---

## 1. SDK 类一览

| 类 | 包 | 职责 |
|---|---|---|
| `com.tencent.mars.Mars` | 根 | **初始化入口**：`loadDefaultMarsLibrary` / `init` / `onCreate` / `onDestroy` |
| `com.tencent.mars.BaseEvent` | 根 | **生命周期/系统事件**：`onCreate` / `onDestroy` / `onNetworkChange` / `onForeground` |
| `com.tencent.mars.stn.StnLogic` | stn | **网络任务 API**（本文重点） |
| `com.tencent.mars.stn.TaskProfile` | stn | 任务画像 |
| `com.tencent.mars.app.AppLogic` | app | 账号/设备/文件路径 |
| `com.tencent.mars.sdt.SdtLogic` | sdt | 网络探测 |
| `com.tencent.mars.xlog.Xlog` | xlog | 日志 |
| `com.tencent.mars.xlog.Log` | xlog | 日志 API |
| `com.tencent.mars.comm.*` | comm | `Alarm` / `NetStatusUtil` / `NetworkSignalUtil` / `PlatformComm` / `WakerLock` |

---

## 2. `StnLogic` —— 核心 API

`mars/libraries/mars_android_sdk/src/main/java/com/tencent/mars/stn/StnLogic.java`

### 2.1 常量

**任务优先级**（`:29-37`）：
```java
public static final int ETASK_PRIORITY_HIGHEST = 0;
public static final int ETASK_PRIORITY_0 = 0;   ... ETASK_PRIORITY_5 = 5;
public static final int ETASK_PRIORITY_NORMAL = 3;
public static final int ETASK_PRIORITY_LOWEST = 5;
```

**通道选择**（`:40-42`）：
```java
public static final int EShort = 0x1;
public static final int ELong  = 0x2;
public static final int EBoth  = 0x3;
```

**传输协议**（`:45-46`）：
```java
public static final int ETransportProtocolTCP  = 1;
public static final int ETransportProtocolQUIC = 2;
```

**选路策略**（`:25-26`）：
```java
public static final int ENORMAL = 0;
public static final int EFAST   = 1;
```

**错误类型 `errType`**（`:106-115`）：
```java
ectOK = 0, ectFalse = 1, ectDial = 2, ectDns = 3, ectSocket = 4,
ectHttp = 5, ectNetMsgXP = 6, ectEnDecode = 7, ectServer = 8, ectLocal = 9
```

**超时错误码**（`:118-121`）：
```java
FIRSTPKGTIMEOUT   = -500;
PKGPKGTIMEOUT     = -501;
READWRITETIMEOUT  = -502;
TASKTIMEOUT       = -503;
```

**Socket 错误码**（`:123-129`）：
```java
SOCKETNETWORKCHANGE         = -10086;
SOCKETMAKESOCKETPREPARED    = -10087;
SOCKETWRITENWITHNONBLOCK    = -10088;
SOCKETREADONCE              = -10089;
SOCKETSHUTDOWN              = -10090;
SOCKETRECVERR               = -10091;
SOCKETSENDERR               = -10092;
```

**HTTP 错误码**（`:131-132`）：
```java
HTTPSPLITHTTPHEADANDBODY = -10194;
HTTPPARSESTATUSLINE      = -10195;
```

**协议/DNS 错误码**（`:134-136`）：
```java
NETMSGXPHANDLEBUFFERERR = -10504;
DNSMAKESOCKETPREPARED   = -10606;
```

**连接状态**（`:140-146`）：
```java
NETWORK_UNKNOWN     = -1;
NETWORK_UNAVAILABLE =  0;
GATEWAY_FAILED      =  1;
SERVER_FAILED       =  2;
CONNECTTING         =  3;
CONNECTED           =  4;
SERVER_DOWN         =  5;
```

**长连校验模式**（`:149-151`）：
```java
public static int ECHECK_NOW   = 0;
public static int ECHECK_NEXT  = 1;
public static int ECHECK_NEVER = 2;
```

**解包失败处理策略**（`:154-157`）：
```java
public static int RESP_FAIL_HANDLE_NORMAL          = 0;
public static int RESP_FAIL_HANDLE_DEFAULT         = -1;
public static int RESP_FAIL_HANDLE_SESSION_TIMEOUT = -13;
public static int RESP_FAIL_HANDLE_TASK_END        = -14;
```

### 2.2 静态方法

| 方法 | 类别 | 说明 | 行 |
|---|---|---|---|
| `setCallBack(ICallBack)` | java | 注册 STN 回调 | 187-189 |
| `setLonglinkSvrAddr(String host, int[] ports)` | java 包装 | → native `(host, ports, null)` | 314-315 |
| `setLonglinkSvrAddr(String host, int[] ports, String debugIP)` | **native** | 设置长连域名/端口/调试 IP | 314 |
| `setShortlinkSvrAddr(int port)` | java 包装 | → native `(port, null)` | 324 |
| `setShortlinkSvrAddr(int port, String debugIP)` | **native** | 短连端口 | 322 |
| `setDebugIP(String host, String ip)` | **native** | **最高优先级 IP 覆盖** | 331 |
| `startTask(Task)` | **native** | **异步启动任务** | 334 |
| `stopTask(int taskID)` | **native** | **同步停止** | 337 |
| `hasTask(int taskID)` | **native** | 任务是否在队列 | 340 |
| `redoTask()` | **native** | 重做所有长短连任务（会重连长连） | 345 |
| `clearTask()` | **native** | 停止并清除所有未完成任务 | 350 |
| `reset()` | **native** | 停止、清除并重新初始化 | 355 |
| `resetAndInitEncoderVersion(int)` | **native** | 同上 + 设置 encoder 版本 | 360 |
| `setBackupIPs(String host, String[] ips)` | **native** | 备份 IP | 367 |
| `makesureLongLinkConnected()` | **native** | 检测长连，未连上则重连 | 372 |
| `setSignallingStrategy(long period, long keepTime)` | **native** | 信令保活间隔/时长（默认 5s / 20s） | 381 |
| `keepSignalling()` | **native** | 立即发一次保活 | 386 |
| `stopSignalling()` | **native** | 停止保活 | 391 |
| `setClientVersion(int)` | **native** | 写入长连私有头（**必须与服务端一致**） | 397 |
| `genTaskID()` | **native** | 生成任务 ID | 405 |
| `requestDoSync()` | java | 转发 `ICallBack.requestDoSync()` | 619-630 |
| `isLogoned()` | java | 转发 `ICallBack.isLogoned()` | 632-643 |

### 2.3 JNI 回调入口（private static，native 调用）

| 方法 | 行 |
|---|---|
| `makesureAuthed` | 411 |
| `onNewDns` | 430 |
| `onPush` | 448 |
| `req2Buf` | 470 |
| `buf2Resp` | 491 |
| `onTaskEnd` | 514 |
| `trafficData` | 532 |
| `reportConnectStatus` | 552 |
| `getLongLinkIdentifyCheckBuffer` | 571 |
| `onLongLinkIdentifyResp` | 591 |
| `requestNetCheckShortLinkHosts` | 605 |
| `reportTaskProfile` | 649 |
| `getLoadLibraries` | 403 |

---

## 3. `StnLogic.Task`

`StnLogic.java:24-101`：

```java
public static class Task {
    // 构造
    public Task() {
        this.taskID = genTaskID();
        this.headers = new HashMap<>();
    }

    public Task(final int channelselect, final int cmdid, final String cgi,
                final ArrayList<String> shortLinkHostList) {
        this.taskID = genTaskID();
        this.channelSelect = channelselect;
        this.cmdID = cmdid;
        this.cgi = cgi;
        this.shortLinkHostList = shortLinkHostList;

        this.sendOnly = false;
        this.needAuthed = true;         // ★ 与 C++ 默认值不同！
        this.limitFlow = true;
        this.limitFrequency = true;

        this.channelStrategy = ENORMAL;
        this.networkStatusSensitive = false;
        this.priority = ETASK_PRIORITY_NORMAL;
        this.retryCount = -1;
        this.serverProcessCost = 0;
        this.totalTimeout = 0;
        this.userContext = null;
        this.headers = new HashMap<>();
        this.longPolling = false;
        this.longPollingTimeout = 0;
    }

    // require
    public int taskID;                     // 唯一任务标识
    public int channelSelect;              // EShort / ELong / EBoth
    public int cmdID;
    public String cgi;
    public ArrayList<String> shortLinkHostList;

    // optional
    public boolean sendOnly;
    public boolean needAuthed;
    public boolean limitFlow;
    public boolean limitFrequency;
    public int channelStrategy;
    public boolean networkStatusSensitive;
    public int priority;
    public int retryCount = -1;
    public int serverProcessCost;          // 服务端预计处理耗时
    public int totalTimeout;               // ms
    public Object userContext;
    public String reportArg;
    public Map<String, String> headers;
    public boolean longPolling;
    public int longPollingTimeout;
}
```

> ⚠️ **Java 与 C++ 的默认值不一致**：
> | 字段 | C++ (`stn.cc:36-68`) | Java (`StnLogic.java:60-75`) |
> |---|---|---|
> | `need_authed` | `false` | **`true`** |
> | `total_timeout` | `-1` → 40s | `0` |
> | `server_process_cost` | `-1` | `0` |
> | `long_polling_timeout` | `-1` | `0` |
>
> **这是从 Java 接入时最容易踩的坑**：`needAuthed = true` 意味着
> **你必须实现 `makesureAuthed` 并返回 true，否则任务永远发不出去。**
> sample 的 `MarsServiceStub.makesureAuthed` 直接 `return true`（`MarsServiceStub.java:150-155`），
> 所以能跑通，但**真实实现必须做真正的登录态检查**。

---

## 4. `StnLogic.ICallBack`

`StnLogic.java:201-295`。**13 个方法**：

| 方法 | 何时调用 | 必须实现 |
|---|---|---|
| `boolean makesureAuthed(String host)` | 任务发送前 | ✅ |
| `List<String> onNewDns(String host)` | 需要解析域名时 | ✅ |
| `void onPush(String channelID, int cmdid, int taskid, byte[] data)` | **服务端推送** | ✅ |
| `byte[] req2Buf(int taskid, Object userContext, int channelSelect, String host, int clientSequenceId)` | **编码请求体** | ✅ |
| `int buf2Resp(int taskid, Object userContext, int channelSelect, byte[] data, int[] serverSequenceId)` | **解码回包** | ✅ |
| `void onTaskEnd(int taskid, Object userContext, int errType, int errCode, CgiProfile profile)` | 任务结束 | ✅ |
| `void trafficData(long send, long recv)` | 流量统计 | ✅ |
| `void reportConnectInfo(int status, int longlinkstatus)` | 连接状态变化 | ✅ |
| `int getLongLinkIdentifyCheckBuffer(String channelID, byte[] identifyBuffer, byte[] bufferHash)` | 长连校验 | ✅ |
| `boolean onLongLinkIdentifyResp(String channelID, byte[] response, byte[] identifyHash)` | 校验回包 | ✅ |
| `void requestDoSync()` | 请求 sync | ✅ |
| `void requestNetCheckShortLinkHosts(ArrayList<String> hostlist)` | 请求网络探测 | ✅ |
| `boolean isLogoned()` | 询问登录态 | ✅ |
| `void reportTaskProfile(TaskProfile profile)` | 任务画像上报 | ✅ |

> **`req2Buf` / `buf2Resp` 是业务的核心。**
> 注意 `buf2Resp` 的返回 `int` 就是 `RESP_FAIL_HANDLE_*`，语义与 C++ 完全一致。

---

## 5. `StnLogic.CgiProfile`

`StnLogic.java:165-180`：

```java
public static class CgiProfile {
    public long taskStartTime = 0;
    public long startConnectTime = 0;
    public long connectSuccessfulTime = 0;
    public long startHandshakeTime = 0;
    public long handshakeSuccessfulTime = 0;
    public long startSendPacketTime = 0;
    public long startReadPacketTime = 0;
    public long readPacketFinishedTime = 0;
    public long rtt = 0;
    public int channelType = 0;
    public int protocolType = 0;
}
```

> **比 C++ 版少几个字段**（没有 encode/decode 的时间戳）——
> Java 侧拿不到 `req2buf`/`buf2resp` 的耗时。

---

## 6. `Mars` —— 初始化入口

`com/tencent/mars/Mars.java`：

```java
public class Mars {
    public static void loadDefaultMarsLibrary() { ... }   // 加载 c++_shared / marsxlog / marsstn
    public static void init(Context context, Handler handler) { ... }
    public static void onCreate(boolean isFirstStartup) { ... }
    public static void onDestroy() { ... }
}
```

### 6.1 ★ 初始化顺序约束

```java
// Mars.java:45-57
public static void onCreate(boolean isFirstStartup) {
    if (isFirstStartup && hasInitialized)      BaseEvent.onCreate();
    else if (!isFirstStartup)                  BaseEvent.onCreate();
    else throw new IllegalStateException(
        "function MarsCore.init must be executed before Mars.onCreate when application firststartup.");
}
```

> **必须先 `Mars.init(context, handler)` 再 `Mars.onCreate(true)`**，否则抛 `IllegalStateException`。
> 这是 sample 里最容易被忽略但会崩溃的顺序问题。

---

## 7. `BaseEvent` —— 生命周期与系统事件

`com/tencent/mars/BaseEvent.java`：

```java
public class BaseEvent {
    // native 方法，由 C++ baseevent 层调用
    public static native void onCreate();
    public static native void onDestroy();
    public static native void onNetworkChange();
    public static native void onForeground(boolean isForeground);
    public static native void onSingalCrash(int sig);
    public static native void onExceptionCrash();

    // 网络变化广播接收器
    public static class ConnectionReceiver extends BroadcastReceiver { ... }
}
```

### 7.1 `ConnectionReceiver`

`BaseEvent.java:28-118`。监听 `CONNECTIVITY_ACTION` 广播，做 **Wi-Fi BSSID/SSID 级别的"是否真的换网"判定**，
避免因为同一个 Wi-Fi 的信号波动而误触发重连。

> **IM 价值**：频繁的"假换网"会导致长连反复重建，用户体感是"消息一直转圈"。

### 7.2 `onForeground(boolean)`

App 前后台切换时调用。这个信号直接驱动 `LongLinkConnectMonitor::__OnSignalForeground`，
进而影响重连节奏（前台 1 分钟内 → 30 秒重连；不活跃 → 阶梯退避）。

> **IM 落地**：必须在 `Activity.onResume` / `onPause` 里调用，
> 否则 Mars 不知道 App 状态，重连策略会退到最保守档。

---

## 8. `AppLogic`

`com/tencent/mars/app/AppLogic.java`：

```java
public class AppLogic {
    public static void setCallBack(ICallBack);

    public interface ICallBack {
        String getAppFilePath();        // ★ STN 策略文件落盘目录（Heartbeat.ini 等）
        AccountInfo getAccountInfo();   // ★ 账号信息（影响重连退避策略！）
        int getClientVersion();         // ★ 必须与长连帧 client_version 一致
        DeviceInfo getDeviceType();
    }

    public static class AccountInfo {
        public String uin;
        public String userName;
    }
    public static class DeviceInfo {
        public String devicename;
        public String devicetype;
    }
}
```

> ★ **`getAccountInfo()` 直接影响重连策略**：
> `username` 为空 + App 不活跃 → 重连间隔退到 **7 天**！
> （`longlink_connect_monitor.cc:146-148`）
>
> ★ **`getAppFilePath()`** 决定 `Heartbeat.ini`、`SpecialINI` 等策略文件的落盘位置。
> **返回空或不可写目录会导致智能心跳无法持久化。**
>
> ★ **`getClientVersion()`** 必须与服务端一致，否则长连完全连不上。

---

## 9. `Xlog` / `Log`

`com/tencent/mars/xlog/Xlog.java`、`Log.java`。

### 9.1 `Xlog` 初始化

```java
// Xlog.java
public static void open(ILog appender, int level, int mode, String tag, boolean encrypt, String fileName, String logDir);
public static void appenderOpen(int level, int mode, String tag, String filename, String logDir, long cryptKey);
public static void appenderClose();
public static void appenderFlush();
public static void setConsoleLogOpen(boolean open);
public static void openLogInstance();
public static void newXlogInstance(String name);
```

**级别**：`LEVEL_ALL` / `VERBOSE` / `DEBUG` / `INFO` / `WARN` / `ERROR` / `FATAL` / `NONE`

**压缩模式**：`COMPRESS_LEVEL1` ~ `COMPRESS_LEVEL9`，`ZLIB_MODE` / `ZSTD_MODE`

**追加器模式**：`AppednerModeAsync` / `AppednerModeSync`

### 9.2 `Log` API

```java
Log.setLogImp(LogImp);   // 注入 xlog 实现
Log.v/d/i/w/e/f(tag, fmt, args...);
Log.appenderOpen/Close/Flush();
Log.openLogInstance/closeLogInstance();
Log.LogInstance    // 多实例日志
```

---

## 10. `SdtLogic`

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

**用途**：网络探测（ping / dns / tcp / http），用于：
- 用户反馈"连不上"时，客户端本地诊断
- 采集网络质量数据

---

## 11. `TaskProfile`

`com/tencent/mars/stn/TaskProfile.java:10-75`：

```java
public class TaskProfile {
    public int taskId;
    public int cmdId;
    public String cgi;
    public long startTaskTime;
    public long endTaskTime;
    public int dyntimeStatus;     // 动态超时状态
    public int errCode;
    public int errType;
    public int channelSelect;
    public ConnectProfile[] historyNetLinkers;   // 重试链
    // 内含 ConnectProfile：dns/conn/ip/port/host/断连原因
}
```

> **这是 Android 侧做 IM 质量监控的核心数据结构**。
> `historyNetLinkers` 保留了每次重试的连接画像 → 可以画出"这条消息的重试轨迹"。

---

## 12. Android 接入的最小代码骨架

```java
// ============ 进程 Application ============
public class MyApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        System.loadLibrary("c++_shared");
        System.loadLibrary("marsxlog");
        Xlog.appenderOpen(Xlog.LEVEL_INFO, Xlog.AppednerModeAsync, "",
                          getExternalFilesDir(null).getAbsolutePath(), "MyApp", 0);
        Log.setLogImp(new Xlog.LogImp());

        AppLogic.setCallBack(appCallback);
        StnLogic.setCallBack(stnCallback);
        SdtLogic.setCallBack(sdtCallback);

        Mars.init(this, new Handler(Looper.getMainLooper()));
        StnLogic.setLonglinkSvrAddr("im.example.com", new int[]{8081});
        StnLogic.setShortlinkSvrAddr(80);
        StnLogic.setClientVersion(200);
        Mars.onCreate(true);
        StnLogic.makesureLongLinkConnected();
    }
    @Override public void onTerminate() {
        Mars.onDestroy();
        super.onTerminate();
    }
}
```

```java
// ============ STN 回调实现 ============
class StnCallback implements StnLogic.ICallBack {
    @Override public byte[] req2Buf(int taskid, Object userContext,
                                    int channelSelect, String host, int clientSequenceId) {
        // ★ 纯内存操作，不要阻塞
        return ((MyRequest) userContext).toBytes();
    }
    @Override public int buf2Resp(int taskid, Object userContext, int channelSelect,
                                  byte[] data, int[] serverSequenceId) {
        MyResponse resp = MyResponse.parse(data);
        serverSequenceId[0] = resp.getServerSeqId();
        if (resp.getErrCode() == 0) return StnLogic.RESP_FAIL_HANDLE_NORMAL;
        if (resp.isSessionTimeout()) return StnLogic.RESP_FAIL_HANDLE_SESSION_TIMEOUT;
        return StnLogic.RESP_FAIL_HANDLE_TASK_END;   // 不可重试
    }
    @Override public void onTaskEnd(int taskid, Object userContext,
                                    int errType, int errCode, StnLogic.CgiProfile profile) {
        // ★ 切主线程更新 UI
        new Handler(Looper.getMainLooper()).post(() -> onTaskEndOnUi(...));
    }
    @Override public void onPush(String channelID, int cmdid, int taskid, byte[] data) {
        // ★ 服务端推送（IM 下行消息入口）
    }
    @Override public boolean makesureAuthed(String host) {
        return AccountManager.isLogined();     // ★ 真实实现
    }
    // ... 其余方法见接口
}
```
