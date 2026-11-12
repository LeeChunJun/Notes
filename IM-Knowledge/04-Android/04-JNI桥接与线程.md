# 04 · JNI 桥接与线程

> 主要源码：`mars/stn/jni/`（4 个文件）

---

## 1. JNI 文件清单

| 文件 | 方向 | 说明 |
|---|---|---|
| `com_tencent_mars_stn_StnLogic_Java2C.cc` | Java → C++ | 业务调 native 的入口 |
| `com_tencent_mars_stn_StnLogic_C2Java.cc` | C++ → Java | native 回调 Java 的入口 |
| `stn_logic_C2Java.h` | — | C2Java 的函数声明 |
| `stn_manager_wrapper.cc/.h` | — | `jnicat` 注册式 StnManager 桥 |
| `stn_manager_callback_wrapper.cc/.h` | — | 同上，回调部分 |

---

## 2. Java → C++ 的导出函数

`com_tencent_mars_stn_StnLogic_Java2C.cc`：

| JNI 函数 | 行 |
|---|---|
| `Java_com_tencent_mars_stn_StnLogic_getLoadLibraries` | 59 |
| `Java_com_tencent_mars_stn_StnLogic_reset` | 69 |
| `Java_com_tencent_mars_stn_StnLogic_resetAndInitEncoderVersion` | 79 |
| `Java_com_tencent_mars_stn_StnLogic_setLonglinkSvrAddr` | 96 |
| `Java_com_tencent_mars_stn_StnLogic_setShortlinkSvrAddr` | 124 |
| `Java_com_tencent_mars_stn_StnLogic_setDebugIP` | 138 |
| `Java_com_tencent_mars_stn_StnLogic_setBackupIPs` | 153 |
| **`Java_com_tencent_mars_stn_StnLogic_startTask`** | 178 |
| `Java_com_tencent_mars_stn_StnLogic_stopTask` | 279 |
| `Java_com_tencent_mars_stn_StnLogic_hasTask` | 286 |
| `Java_com_tencent_mars_stn_StnLogic_redoTask` | 298 |
| `Java_com_tencent_mars_stn_StnLogic_touchTasks` | 304 |
| `Java_com_tencent_mars_stn_StnLogic_clearTask` | 314 |
| `Java_com_tencent_mars_stn_StnLogic_makesureLongLinkConnected` | 325 |
| `Java_com_tencent_mars_stn_StnLogic_setSignallingStrategy` | 336 |
| `Java_com_tencent_mars_stn_StnLogic_keepSignalling` | 350 |
| `Java_com_tencent_mars_stn_StnLogic_stopSignalling` | 361 |
| `Java_com_tencent_mars_stn_StnLogic_setClientVersion` | 367 |
| `Java_com_tencent_mars_stn_StnLogic_genTaskID` | 371 |
| `Java_com_tencent_mars_stn_StnLogic_trigNooping` | 375 |
| `Java_com_tencent_mars_stn_StnLogic_genSequenceId` | 380 |

---

## 3. `startTask` 的 JNI 实现 ★

`com_tencent_mars_stn_StnLogic_Java2C.cc:178-229`。**这是 Java Task → C++ Task 的转换点**。

```cpp
JNIEXPORT void JNICALL Java_com_tencent_mars_stn_StnLogic_startTask(JNIEnv* _env, jclass, jobject _task) {
    // ① 解析 Java Task 对象
    mars::stn::Task task;

    jfieldID fid;
    // taskID
    fid = _env->GetFieldID(_task_clz, "taskID", "I");
    task.taskid = _env->GetIntField(_task, fid);
    // channelSelect
    fid = _env->GetFieldID(_task_clz, "channelSelect", "I");
    task.channel_select = _env->GetIntField(_task, fid);
    // cmdID
    fid = _env->GetFieldID(_task_clz, "cmdID", "I");
    task.cmdid = _env->GetIntField(_task, fid);
    // cgi
    fid = _env->GetFieldID(_task_clz, "cgi", "Ljava/lang/String;");
    jstring jcgi = (jstring)_env->GetObjectField(_task, fid);
    task.cgi = ...;
    // sendOnly / needAuthed / limitFlow / limitFrequency
    // channelStrategy / networkStatusSensitive / priority
    // retryCount / serverProcessCost / totalTimeout
    // longPolling / longPollingTimeout
    // reportArg / headers
    // shortLinkHostList

    // ② 调用 C++ API
    mars::stn::StartTask(task);
}
```

### 3.1 关键点

| 点 | 说明 |
|---|---|
| **`taskID` 是 Java 侧生成的** | `StnLogic.Task` 构造函数里 `this.taskID = genTaskID()`（`StnLogic.java:49,56`），走 JNI `genTaskID` → `StnManager::GenTaskID()` |
| **字段名必须完全匹配** | `GetFieldID` 找不到字段会抛 `NoSuchFieldError` |
| **`userContext` 不通过 JNI 传** | Java 的 `Object userContext` 在 JNI 层被忽略 —— 所以 sample 用 `TASK_ID_TO_WRAPPER` map |
| **调用是异步的** | `mars::stn::StartTask(task)` 立即返回，任务在 STN 线程上排队 |

---

## 4. C++ → Java 的回调

`com_tencent_mars_stn_StnLogic_C2Java.cc`。

### 4.1 回调清单

| C2Java 函数 | 触发时机 | 目标 Java 方法 |
|---|---|---|
| `C2Java_OnPush` | 服务端推送 | `StnLogic.onPush(channelID, cmdid, taskid, data)` |
| `C2Java_Req2Buf` | 编码请求体 | `StnLogic.req2Buf(...)` |
| `C2Java_Buf2Resp` | 解码回包 | `StnLogic.buf2Resp(...)` |
| `C2Java_OnTaskEnd` | 任务结束 | `StnLogic.onTaskEnd(...)` |
| `C2Java_TrafficData` | 流量统计 | `StnLogic.trafficData(...)` |
| `C2Java_ReportConnectStatus` | 连接状态 | `StnLogic.reportConnectStatus(...)` |
| `C2Java_MakesureAuthed` | 鉴权门控 | `StnLogic.makesureAuthed(host)` |
| `C2Java_OnNewDns` | DNS | `StnLogic.onNewDns(host)` |
| `C2Java_GetLongLinkIdentifyCheckBuffer` | 信令校验 | `StnLogic.getLongLinkIdentifyCheckBuffer(...)` |
| `C2Java_OnLongLinkIdentifyResp` | 校验回包 | `StnLogic.onLongLinkIdentifyResp(...)` |
| `C2Java_RequestNetCheckShortLinkHosts` | 网络探测 | `StnLogic.requestNetCheckShortLinkHosts(...)` |
| `C2Java_ReportTaskProfile` | 画像上报 | `StnLogic.reportTaskProfile(profile)` |

### 4.2 ★ 回调在哪个线程？

| 回调 | 线程 |
|---|---|
| `Req2Buf` | **STN 线程** |
| `Buf2Resp` | **STN 线程** |
| `OnTaskEnd` | **STN 线程** |
| `OnPush` | **LongLink I/O 线程**（`::lonklink`） |
| `ReportConnectStatus` | STN 线程（异步信号投递） |
| `ReportTaskProfile` | STN 线程 |
| `TrafficData` | LongLink I/O 线程（`GetSignalOnNetworkDataChange`） |

> **IM 落地要点**：
> - `Req2Buf`/`Buf2Resp`/`OnTaskEnd` → 在 STN 线程，**不能阻塞**
> - `OnPush` → 在 I/O 线程，**更不能阻塞**（会卡住整个长连接的读写循环！）
>
> **`OnPush` 在 I/O 线程上是最危险的一个** ——
> 如果业务在这里做数据库写入或者大对象序列化，
> **整条长连接都会卡住**，所有消息延迟。
>
> **正确做法**：`OnPush` 里只做"拷贝到 buffer + 投递到业务线程"，立刻返回。

### 4.3 回调桥 `StnCallbackBridge`

`mars/stn/stn_callback_bridge.h/.cc`。

```
C++ Callback（stn_manager 内部）
      ↓ 转发
StnCallbackBridge
      ↓ 转发
StnManager::SetCallback(用户 Callback)
      ↓ 或
JNI 层 → Java
```

`StnManager` 的所有 `Callback` 实现（`stn_manager.h:58-138`）都是：
```cpp
xassert2(callback_bridge_ != NULL);
callback_bridge_->Req2Buf(...);   // 转发
```

> **作用**：解耦 STN 内部与用户的 Callback，允许中间插入代理/统计/打桩。

---

## 5. `jnicat` 注册式桥

`mars/stn/jni/stn_manager_wrapper.cc/.h` + `stn_manager_callback_wrapper.cc/.h` +
`mars/comm/jni/jnicat/jnicat_core.h`。

`jnicat` 是 Mars 自己写的 **JNI 自动绑定框架**：
用宏把 C++ 类的方法自动映射成 Java 方法，省去手写 `GetMethodID` 的样板代码。

```cpp
JNICAT_JAVA_TYPE(mars::stn::StnManager)
...
```

> **IM 落地意义**：如果你要基于 Mars 做自己的 SDK 封装，
> `jnicat` 能大幅减少 JNI 胶水代码。

---

## 6. JNI 性能与安全

### 6.1 性能

| 事项 | 建议 |
|---|---|
| **回调频率** | `OnPush` 在 I/O 线程，避免在回调里做重活 |
| **byte[] 拷贝** | `C2Java_Req2Buf` 的返回值需要拷贝成 `jbyteArray` |
| **AIDL 跨进程** | 独立进程方案每次请求多一次序列化 |
| **JNI 局部引用** | 回调里不要创建大量 local ref（会 OOM） |

### 6.2 安全

| 事项 | 说明 |
|---|---|
| **日志脱敏** | `MemoryDump.dumpHex()` 会把完整报文打到日志 → **生产环境必须脱敏** |
| **`getAppFilePath`** | 返回可写目录，注意权限 |
| **`client_version`** | 不可被篡改（否则可绕过版本校验） |
| **长连加密** | 开源版只有 TLS；**TLS 证书校验必须做**（`need_tls` + `tls_group`） |

> `MemoryDump.dumpHex()` 的调用点：`NanoMarsTaskWrapper.java:47`。
> **生产实现必须移除或脱敏**，否则消息内容会全部落到日志文件里。

---

## 7. 调试技巧

### 7.1 看 native 崩溃栈

```
adb shell debuggerd -b <pid>
```

或用 `ndk-stack`。

### 7.2 看 STN 内部状态

Xlog 日志里搜关键字：

```
"connect status from"        状态迁移
"task socket close sock"     断链
"noop timeout"               心跳超时
"task no found task"         响应匹配失败
"longlink_progress next connect interval"   退避决策
"start noop taskid"          心跳发送
"process active made interval connect"      活跃态重连
"rebuild now"                强制重建
```

### 7.3 看消息全生命周期

`ReportTaskProfile` 上报的 `TaskProfile` 里有完整的时间线：

```
startTime → startConnectTime → connectSuccessfulTime
          → startSendPacketTime → startReadPacketTime → readPacketFinishedTime
```

计算各段耗时即可定位瓶颈。

---

## 8. IM 的 JNI 层最佳实践

1. **`OnPush` 立刻返回**
   ```java
   @Override public void onPush(String channelID, int cmdid, int taskid, byte[] data) {
       // 只做浅拷贝 + 投递
       final byte[] copy = data.clone();
       businessExecutor.execute(() -> handlePush(cmdid, copy));
   }
   ```

2. **`Req2Buf` 用预编码好的字节**
   ```java
   @Override public byte[] req2Buf(int taskid, Object userContext, ...) {
       return (byte[]) userContext;   // 业务线程已经序列化好了
   }
   ```

3. **`Buf2Resp` 只做反序列化**
   ```java
   @Override public int buf2Resp(int taskid, Object userContext, int channelSelect,
                                 byte[] data, int[] serverSequenceId) {
       Response resp = Response.parseFrom(data);   // protobuf-lite 快
       serverSequenceId[0] = resp.seqId;
       return resp.errCode == 0 ? RESP_FAIL_HANDLE_NORMAL : RESP_FAIL_HANDLE_TASK_END;
   }
   ```

4. **UI 更新统一切主线程**
   ```java
   private static final Handler UI = new Handler(Looper.getMainLooper());
   ```
