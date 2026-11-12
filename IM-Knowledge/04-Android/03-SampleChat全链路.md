# 03 · SampleChat IM 全链路

> 源码：`samples/android/marsSampleChat/` + `samples/Server/`

---

## 1. 工程结构

### 1.1 客户端（Gradle 工程，2 个 module）

```
samples/android/marsSampleChat/
├── app/                                  ==== UI / 业务层 ====
│   └── src/main/java/com/tencent/mars/sample/
│       ├── SampleApplicaton.java         Application：Xlog/MarsServiceProxy 初始化
│       ├── ConversationActivity.java     会话列表（Launcher Activity）
│       ├── ConversationListAdapter.java  RecyclerView + DataBinding
│       ├── chat/
│       │   ├── ChatActivity.java         聊天页
│       │   ├── ChatDataCore.java         消息仓库 + Observable + BroadcastReceiver
│       │   ├── ChatMsgEntity.java
│       │   ├── ChatMsgViewAdapter.java
│       │   └── TextMessageTask.java      ★ 发送消息的 Task
│       ├── core/
│       │   ├── ActivityEvent.java / ActivityEventConnection.java
│       │   ├── BusinessHandler.java
│       │   ├── MainService.java          ★ Push 消息分发线程
│       │   ├── MessageHandler.java       ★ 解析推送消息
│       │   └── StatisticHandler.java
│       ├── statistic/                    FlowReport / TaskReport / SdtReport
│       └── utils/Constants.java
│   └── src/main/proto/                   chat.proto / main.proto / messagepush.proto / topic.proto
└── wrapper/                              ==== 任务抽象层 ====
    ├── src/main/aidl/.../remote/
    │   ├── MarsService.aidl
    │   ├── MarsTaskWrapper.aidl
    │   └── MarsPushMessageFilter.aidl
    └── src/main/java/com/tencent/mars/sample/wrapper/
        ├── TaskProperty.java             ★ 注解
        ├── remote/
        │   ├── AbstractTaskWrapper.java   ★ 反射读注解
        │   ├── JsonMarsTaskWrapper.java
        │   ├── MarsServiceProxy.java      ★ 客户端代理
        │   ├── MarsTaskProperty.java
        │   ├── NanoMarsTaskWrapper.java   ★ protobuf 编解码
        │   ├── PushMessage.java / PushMessageHandler.java
        ├── service/
        │   ├── MarsServiceNative.java     ★ Service（:marsservice 进程）
        │   ├── MarsServiceStub.java       ★ STN/App/SDT 回调实现
        │   ├── MarsServiceProfile.java / Factory / DebugProfile
```

### 1.2 服务端（Gradle 工程，Java + Netty + Jersey）

```
samples/Server/server/src/main/
├── java/com/tencent/mars/
│   ├── proxy/
│   │   ├── NetMsgHeader.java           ★ Mars 帧编解码
│   │   ├── NetMsgHeaderHandler.java    ★ Netty InboundHandler
│   │   └── ProxyServer.java            ★ Netty ServerBootstrap
│   ├── logicserver/
│   │   ├── TopicChats.java             ★ 话题广播（push）
│   │   └── ProxySession.java
│   ├── webserver/
│   │   ├── EchoCgi.java                @Path("/mars/hello")
│   │   ├── HelloCgi.java               @Path("/mars/hello2")
│   │   ├── SendMessageCgi.java         @Path("/mars/sendmessage")
│   │   └── GetConversationListCgi.java @Path("/mars/getconvlist")
│   ├── datacenter/CacheData.java       SQLite 内存库
│   └── utils/BaseConstants.java
├── proto/  chat.proto / main.proto / messagepush.proto / topic.proto
└── webapp/WEB-INF/web.xml              Jersey ServletContainer
```

---

## 2. 端到端发送链路

| # | 阶段 | 类 / 方法 | 位置 |
|---|---|---|---|
| 1 | 用户点发送 | `ChatActivity.btnSend.setOnClickListener` → `onClickBtnSend()` | `ChatActivity.java:94-99, 179` |
| 2 | **本地乐观插入 UI** | `ChatDataCore.getInstance().addData(topicName, entity)` | `ChatActivity.java:193-198` → `ChatDataCore.java:70-74` |
| 3 | 构造 Task | `new TextMessageTask(topicName, message).onOK(...).onError(...)` | `ChatActivity.java:202-216` |
| 4 | 填 protobuf 请求 | `request.setAccessToken/From/To/Text/Topic` | `TextMessageTask.java:47-58` |
| 5 | 投递到队列 | `MarsServiceProxy.send(wrapper)` → `inst.queue.offer(...)` | `MarsServiceProxy.java:105-107` |
| 6 | Worker 线程取出 | `queue.take()` → `service.send(taskWrapper, properties)` | `MarsServiceProxy.java:183-223`（Worker 线程 `:225-240`） |
| 7 | cmdID 覆盖 | `GLOBAL_CMD_ID_MAP.get(cgiPath)` | `MarsServiceProxy.java:205-210` |
| 8 | **跨进程 AIDL** | `MarsService.send(MarsTaskWrapper, Bundle)` | `MarsService.aidl:11` |
| 9 | 建 Task | `new StnLogic.Task(EShort, 0, "", null)` → 设置 host/cgi/channelSelect/cmdID | `MarsServiceStub.java:72-102` |
| 10 | 注册映射 | `TASK_ID_TO_WRAPPER.put(_task.taskID, taskWrapper)` | `MarsServiceStub.java:104` |
| 11 | **启动原生任务** | `StnLogic.startTask(_task)` | `MarsServiceStub.java:108` |
| 12 | JNI → C++ | `Java_com_tencent_mars_stn_StnLogic_startTask` | `mars/stn/jni/com_tencent_mars_stn_StnLogic_Java2C.cc:177-229` |
| 13 | 编包回调 | native → `StnLogic.req2Buf` → `MarsServiceStub.req2Buf` → `wrapper.req2buf()` | `MarsServiceStub.java:235-252` |
| 14 | protobuf 序列化 | `request.build().toByteArray()` | `NanoMarsTaskWrapper.java:42-58` |
| 15 | **组帧** | `__STNetMsgXpHeader{head_length, client_version, cmdid, seq, body_length}` (全 `htonl`) + body | `mars/stn/proto/longlink_packer.cc:36-44, 106-126` |
| 16 | 长链写出 | `LongLink::Send` → TCP write | `mars/stn/src/longlink.cc` |

### 线上帧

```
 0        4        8        12       16       20
+--------+--------+--------+--------+--------+------------------+
|head_len|cli_ver | cmdid  |  seq   |body_len|   body (protobuf)|
|   20   |  200   |   3    | taskID |  N     |                  |
+--------+--------+--------+--------+--------+------------------+
```

---

## 3. 服务端链路

| # | 阶段 | 类 / 方法 | 位置 |
|---|---|---|---|
| 17 | TCP accept | `ProxyServer.start()` → `serverBootstrap.bind(port).sync()`，默认 **8081** | `ProxyServer.java:84-102, 117-129` |
| 18 | Pipeline 装配 | `channel.pipeline().addLast(new NetMsgHeaderHandler())` | `ProxyServer.java:108-115` |
| 19 | 连接建立 | `NetMsgHeaderHandler.channelActive()` → `TopicChats.joinTopic(ctx)` | `NetMsgHeaderHandler.java:72-79` |
| 20 | 收帧 | `channelRead` → `new ByteBufInputStream((ByteBuf)msg)` → `msgXp.decode(is)` | `NetMsgHeaderHandler.java:82-90` |
| 21 | 解帧 | 读 5 个 int；校验 `clientVersion == 200`；再读 `bodyLen` 字节 body | `NetMsgHeader.java:65-104` |
| 22 | cmdid → CGI | `CMD_PATH_MAP.get(msgXp.cmdId)` | `NetMsgHeaderHandler.java:53-59, 100` |
| 23 | 转发 CGI | `doHttpRequest(path, body)` → Jersey HTTP POST `http://localhost:8080/<path>` | `NetMsgHeaderHandler.java:179-187` |
| 24 | CGI 处理 | `SendMessageCgi.sendmessage(InputStream)` | `webserver/SendMessageCgi.java:34-70` |
| 25 | 回包 | `msgXp.body = IOUtils.toByteArray(inputStream)`；`msgXp.encode()`；`ctx.writeAndFlush(...)` | `NetMsgHeaderHandler.java:136-144` |
| 26 | 广播 | `TopicChats.pushMessage(topic, text, from, ctx)` | `NetMsgHeaderHandler.java:137-140` → `TopicChats.java:87-89` |
| 27 | 广播实现 | `Pusher` 线程遍历 `topicJoiners.get(topic)`，`ctx != sender` 才发；`cmdId = 10001`，`seq = 0` | `TopicChats.java:95-137` |

---

## 4. 回程链路

| # | 阶段 | 位置 |
|---|---|---|
| 28 | native 解帧 → `StnLogic.buf2Resp` → `MarsServiceStub.buf2Resp` → `wrapper.buf2resp(buf)` | `MarsServiceStub.java:255-270` |
| 29 | `response.mergeFrom(buf)`；`onPostDecode(response)` 决定 ok/error 回调 | `NanoMarsTaskWrapper.java:61-74`；`TextMessageTask.java:66-73` |
| 30 | native → `StnLogic.onTaskEnd` → `MarsServiceStub.onTaskEnd` → `wrapper.onTaskEnd(errType, errCode)` | `MarsServiceStub.java:216-232` |
| 31 | `TextMessageTask.onTaskEnd` → `uiHandler.post(callback)` | `TextMessageTask.java:76-82` |
| 32 | 发送方 UI | 本地消息早已乐观插入，**服务端回包不会新增列表项** | `ChatActivity.java:193-198` |

---

## 5. 推送链路（他人消息 → 我的 UI）★

| # | 阶段 | 位置 |
|---|---|---|
| 35 | 服务端 push 帧 `seq=0` → native `longlink_ispush`（`PUSH_DATA_TASKID == taskid`） | `longlink_packer.cc:98, 175-177` |
| 36 | `LongLinkTaskManager::__OnResponse` 判定为 push → `fun_on_push_` | `longlink_task_manager.cc:966-974` |
| 37 | `StnManager::OnPush` → `StnCallbackBridge::OnPush` → `C2Java_OnPush` → `StnLogic.onPush(channelID, cmdid, taskid, data)` | `mars/stn/jni/com_tencent_mars_stn_StnLogic_C2Java.cc` |
| 38 | `StnLogic.onPush` → `callBack.onPush(cmdid, taskid, data)` → `MarsServiceStub.onPush` | `StnLogic.java:448-459`；`MarsServiceStub.java:164-175` |
| 39 | 遍历 AIDL filter → `MarsPushMessageFilter.Stub.onRecv(cmdId, data)`（跨进程回 app 进程） | `MarsPushMessageFilter.aidl:9`；`MarsServiceProxy.java:59-76` |
| 40 | `PushMessageHandler.process(message)` → `MainService.pushMessages.offer()` | `core/MainService.java:76-79` |
| 41 | `PUSH-RECEIVER` 线程 → `BusinessHandler.handleRecvMessage` → `MessageHandler` | `MainService.java:50-74`；`core/MessageHandler.java:35-57` |
| 42 | 解析 `Messagepush.MessagePush.parseFrom(buffer)` → `sendBroadcast(Constants.PUSHACTION)` | `MessageHandler.java:40-46`；`utils/Constants.java:22-23` |
| 43 | `ChatDataCore.RecvMessageReceiver.onReceive` → 追加 entity → `notifyObservers()` | `chat/ChatDataCore.java:86-115` |
| 44 | `ChatActivity.update(Observable, Object)` → `adapter.notifyDataSetChanged()` + 滚到底 | `ChatActivity.java:173-177` |

> **架构亮点**：
> 推送消息的处理被分成了**两层**：
> - `:marsservice` 进程：只负责收到并转发
> - UI 进程：负责解析、落库、更新 UI
>
> 这样 UI 进程被杀掉也不影响长连接接收消息（消息在 service 进程被接收，
> 但如果 service 进程也死了……就丢了 —— sample 没有做持久化）。

---

## 6. 任务抽象层设计

### 6.1 `TaskProperty` 注解

`wrapper/.../TaskProperty.java:29-44`：

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@Inherited
public @interface TaskProperty {
    String host() default "";
    String path();
    boolean shortChannelSupport() default true;
    boolean longChannelSupport() default false;
    int cmdID() default -1;
}
```

**消费**（`AbstractTaskWrapper.java:33-43`）：

```java
final TaskProperty taskProperty = this.getClass().getAnnotation(TaskProperty.class);
if (taskProperty != null) {
    setHttpRequest(taskProperty.host(), taskProperty.path());
    setShortChannelSupport(taskProperty.shortChannelSupport());
    setLongChannelSupport(taskProperty.longChannelSupport());
    setCmdID(taskProperty.cmdID());
}
```

**转成 Bundle key**（`MarsTaskProperty.java:28-33`）：

| Bundle key | 常量 | 类型 |
|---|---|---|
| `host` | `OPTIONS_HOST` | String |
| `cgi_path` | `OPTIONS_CGI_PATH` | String |
| `cmd_id` | `OPTIONS_CMD_ID` | int |
| `short_support` | `OPTIONS_CHANNEL_SHORT_SUPPORT` | boolean |
| `long_support` | `OPTIONS_CHANNEL_LONG_SUPPORT` | boolean |
| `task_id` | `OPTIONS_TASK_ID` | int（运行期回填） |

**Bundle 是跨进程序列化载体**：客户端进程填属性 → AIDL 传给 `:marsservice` → `MarsServiceStub.send()` 读出来组装 `StnLogic.Task`。

### 6.2 注解 vs 运行时覆盖

- `TextMessageTask` 用**注解**（`TextMessageTask.java:31-37`）：
  ```java
  @TaskProperty(host = "marsopen.cn", path = "/mars/sendmessage",
                cmdID = Main.CmdID.CMD_ID_SEND_MESSAGE_VALUE,
                longChannelSupport = true, shortChannelSupport = false)
  ```
  → `channelSelect = ELong`（`MarsServiceStub.java:90-91`）

- `ConversationActivity` 用**匿名** `NanoMarsTaskWrapper`（`ConversationActivity.java:201-248`），**没有注解** →
  `properties` 为空 → 改为运行时 `setHttpRequest(CONVERSATION_HOST, "/mars/getconvlist")`（`:250`）。
  Bundle 里 `short_support`/`long_support` 缺省 → `MarsServiceStub` 用默认值 `true/false`（`:82-83`）
  → `channelSelect = EShort` → **走短链 HTTP，打到 `marsopen.cn:80`，不是本地 Jetty**。

> **这是一个 sample 的不一致**：会话列表请求发到了公网而不是本地服务器。

### 6.3 通道选择映射

`MarsServiceStub.java:82-96`：

```java
if (shortSupport && longSupport)      _task.channelSelect = StnLogic.Task.EBoth;
else if (shortSupport)                _task.channelSelect = StnLogic.Task.EShort;
else if (longSupport)                 _task.channelSelect = StnLogic.Task.ELong;
else { Log.e(TAG,"invalid channel strategy"); throw new RemoteException("Invalid Channel Strategy"); }
```

`EShort=0x1, ELong=0x2, EBoth=0x3`（`StnLogic.java:40-42`）。

### 6.4 protobuf 编解码

`NanoMarsTaskWrapper.java:27-78`：

```java
@Override public byte[] req2buf() {
    try {
        onPreEncode(request);                              // 业务前置钩子
        final byte[] flatArray = request.build().toByteArray();
        Log.d(TAG, "encoded request to buffer, [%s]", MemoryDump.dumpHex(flatArray));
        return flatArray;
    } catch (Exception e) { e.printStackTrace(); }
    return new byte[0];                                   // ★ 失败返回空包（隐患）
}

@Override public int buf2resp(byte[] buf) {
    try {
        response.mergeFrom(buf);
        onPostDecode(response);
        return StnLogic.RESP_FAIL_HANDLE_NORMAL;
    } catch (Exception e) { Log.e(TAG, "%s", e); }
    return StnLogic.RESP_FAIL_HANDLE_TASK_END;            // ★ 不会重试
}
```

- `GeneratedMessageLite.Builder` 泛型 → 依赖 **protobuf-lite**
- `JsonMarsTaskWrapper`（`remote/JsonMarsTaskWrapper.java:14-61`）是同构的 JSON 版本（Gson）
- `MemoryDump.dumpHex()`（`utils/print/MemoryDump.java:28-46`）用于十六进制日志

---

## 7. 服务端实现

### 7.1 Netty Pipeline

`proxy/ProxyServer.java:76-115`：

```java
public ProxyServer(int port) {
    this.port = port;
    bossGroup  = new NioEventLoopGroup();
    workerGroup = new NioEventLoopGroup();
    channelHandler = new ChannelInitializerImpl<SocketChannel>();
}
...
serverBootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
        .childHandler(channelHandler)
        .option(ChannelOption.SO_BACKLOG, 128)
        .childOption(ChannelOption.SO_KEEPALIVE, true);
...
public class ChannelInitializerImpl<T extends Channel> extends ChannelInitializer<T> {
    @Override protected void initChannel(T channel) throws Exception {
        channel.pipeline().addLast(new NetMsgHeaderHandler());   // ★ 只有这一个
    }
}
```

**线程模型**：
- `bossGroup` 负责 accept
- `workerGroup` 负责已建立连接的读写；默认线程数 = `2 × CPU 核数`
- ★ **`channelRead` 在 EventLoop 线程上同步执行**，而 `doHttpRequest` 是**阻塞式 Jersey HTTP 调用** → 阻塞整个 EventLoop

### 7.2 cmdid → CGI 映射

`proxy/NetMsgHeaderHandler.java:53-59`：

```java
private static Map<Integer, String> CMD_PATH_MAP = new HashMap<>();
static {
    CMD_PATH_MAP.put(Main.CmdID.CMD_ID_HELLO_VALUE,        "mars/hello");
    CMD_PATH_MAP.put(Main.CmdID.CMD_ID_SEND_MESSAGE_VALUE, "/mars/sendmessage");
    CMD_PATH_MAP.put(Main.CmdID.CMD_ID_HELLO2_VALUE,       "/mars/hello2");
}
```

处理分支（`:101-157`）：`CMD_ID_HELLO` / `CMD_ID_HELLO2` / `CMD_ID_SEND_MESSAGE` / `CMDID_NOOPING(6)`。

> ★ **`CMD_ID_CONVERSATION_LIST(4)`、`CMD_ID_JOINTOPIC(5)`、`CMD_ID_LEFTTOPIC(7)`、`CMD_ID_AUTH(2)`
> 都落到 `default: break;` —— 无任何响应。**

### 7.3 CGI 转发

`proxy/NetMsgHeaderHandler.java:179-187`：

```java
private InputStream doHttpRequest(String path, InputStream data) {
    final Client client = ClientBuilder.newClient(new ClientConfig());   // ★ 每次 new，从不 close
    final InputStream response = client.target("http://localhost:8080/")
            .path(path)
            .request(MediaType.APPLICATION_OCTET_STREAM)
            .post(Entity.entity(data, MediaType.APPLICATION_OCTET_STREAM), InputStream.class);
    return response;
}
```

- Jetty 监听 **8080**（`server/build.gradle:31-36`：`jettyRun { contextPath='/'; httpPort=8080 }`）
- Jersey 通过 `server/src/main/webapp/WEB-INF/web.xml:10-13` 扫描 `com.tencent.mars.webserver`

| CGI 类 | `@Path` | 请求体 | 响应体 |
|---|---|---|---|
| `EchoCgi` | `/mars/hello` | `Main.HelloRequest` | `Main.HelloResponse` |
| `HelloCgi` | `/mars/hello2` | `Main.HelloRequest` | `Main.HelloResponse`（支持 64KB/128KB dump） |
| `SendMessageCgi` | `/mars/sendmessage` | `Chat.SendMessageRequest` | `Chat.SendMessageResponse` |
| `GetConversationListCgi` | `/mars/getconvlist` | `Main.ConversationListRequest` | `Main.ConversationListResponse` |

所有 CGI 用 `StreamingOutput` + `response.writeTo(os)` 直接输出 protobuf 字节流
（`SendMessageCgi.java:58-63`），`Content-Type: application/octet-stream`。

### 7.4 服务端推送 `TopicChats`

`logicserver/TopicChats.java`：

```java
private String[][] conDetails = new String[][]{
        new String[] {"Mars", "0", "STN Discuss"},
        new String[] {"Mars", "1", "Xlog Discuss"},
        new String[] {"Mars", "2", "SDT Discuss"}};                 // :35-39

public ConcurrentHashMap<String, ConcurrentLinkedDeque<ChannelHandlerContext>> topicJoiners; // :41

public void joinTopic(ChannelHandlerContext context) {              // :62-68
    for (String topicName : topicJoiners.keySet())
        if (!topicJoiners.get(topicName).contains(context))
            topicJoiners.get(topicName).offer(context);
}

public void pushMessage(String topicName, String content, String from, ChannelHandlerContext context) { // :87-89
    new Pusher(topicName, content, from, context).start();
}

private class Pusher extends Thread {                              // :95-137
    public void run() {
        ConcurrentLinkedDeque<ChannelHandlerContext> ctxs = topicJoiners.get(topicName);
        if (ctxs != null) {
            Messagepush.MessagePush messagePush = Messagepush.MessagePush.newBuilder()
                    .setContent(content).setFrom(from).setTopic(topicName).build();
            NetMsgHeader msgXp = new NetMsgHeader();
            msgXp.cmdId = BaseConstants.MESSAGE_PUSH;    // 10001
            msgXp.body  = messagePush.toByteArray();
            for (ChannelHandlerContext context : ctxs)
                if (ctx != context)                       // ★ 不回发给发送者
                    context.writeAndFlush(context.alloc().buffer().writeBytes(msgXp.encode()));
        }
    }
}
```

**关键点**：
- **push 帧的 `seq` 默认为 0** → 客户端 `longlink_ispush` 判定成立 → 走 push 分支
- `cmdId = 10001` 与 Android 侧 `Constants.PUSHCMD = 10001`（`app/.../utils/Constants.java:22`）对齐
- `joinTopic` 把**每个连接加入所有话题**（"0"/"1"/"2"）→ topic 其实是全量广播
- ★ `Pusher` 每次推送 `new Thread` —— **每条消息一个线程**

---

## 8. 服务端缺陷清单（15 条）

| # | 缺陷 | 位置 | 后果 |
|---|---|---|---|
| **S1** | **没有帧解码器** | `ProxyServer.java:112` | TCP 半包/粘包 → 丢帧。**必须加 `LengthFieldBasedFrameDecoder`** |
| **S2** | `InvalidHeaderException` 未捕获 | `NetMsgHeader.java:65` vs `:99` | 客户端收不到错误帧，只能等超时 |
| **S3** | **Netty EventLoop 上做阻塞 HTTP** | `NetMsgHeaderHandler.java:104/119/134` → `:179-187` | 队头阻塞，一个慢 CGI 阻塞所有连接 |
| **S4** | **每连接一个 `Timer` 线程** | `NetMsgHeaderHandler` 构造函数 `:64-70` | 线程泄漏；`TimerTask` 从不 cancel |
| **S5** | 超时检查不关闭连接 | `ContextTimeoutChecker.run()` `:192-205` | 15 分钟无数据的长连不断开，socket 泄漏 |
| **S6** | 每条消息一个 `Pusher` 线程 | `TopicChats.java:88` | 高并发线程爆炸 |
| **S7** | **无鉴权** | `CMD_ID_AUTH(2)` 在 switch 里没有分支 | 任何客户端都能发/收消息 |
| **S8** | `ProxyServer.start()` 吞异常 | `:95-97` `catch (Exception e) {}` | 绑定失败静默退出 |
| **S9** | `ProxySession.channels` 是普通 `HashMap` | `ProxySession.java:34` | 非线程安全 |
| **S10** | `CMD_PATH_MAP` 缺 4 个 cmdid | `:53-59` | 无响应 |
| **S11** | `decode` 忽略 `headLength` | `NetMsgHeader.java:82-84` | 对端带 options 会解析错位 |
| **S12** | `CacheData` 是 SQLite **内存库** | `datacenter/CacheData.java:40` `jdbc:sqlite::memory:` | 消息不持久化，重启即丢 |
| **S13** | `HelloCgi` 的 logger 用了 `EchoCgi.class.getName()` | `webserver/HelloCgi.java:23` | 日志归类错误 |
| **S14** | `TopicChats` 无 per-topic 鉴权 / 无长度频率限制 | 全文 | 可被刷屏 |
| **S15** | proto 不一致 | Server 有 `CMD_ID_HELLO2 = 8`，Android 没有 | Android 无法构造 cmdID=8 |

---

## 9. ★ Android 端没有实现 hello 握手

**事实**：`CMD_ID_HELLO = 1` 只在 `app/src/main/proto/main.proto:8` 和服务端 `EchoCgi` 里存在。
**Android 侧没有任何 Task 发送 cmdID=1。**

真正发 hello 的只有：
- 服务端自测 Netty 客户端 `samples/Server/server/src/test/java/com/tencent/mars/proxy/EchoCgiTest.java:63-81`
- Windows 客户端 `samples/Windows/Business/MarsWrapper.cpp:82-94`（`cmdid_ = CMD_ID_HELLO; cgi_ = "/mars/hello"`）
- iOS 客户端 `samples/iOS/iOSDemo/iOSDemo/PingServerController.m:57-60`

服务端另有 `CMD_ID_HELLO2 = 8` → `/mars/hello2`（`webserver/HelloCgi.java:20`），
支持 `"64KB"/"128KB"` 文本返回等量随机 dump，**用于压测大包**。

> **IM 落地启示**：
> 真实 IM 必须实现**登录握手**：
> ```
> 1. TCP/TLS 连接建立
> 2. 发送鉴权包（cmdid = CMD_ID_AUTH），body = {token, deviceInfo, clientVersion}
> 3. 服务端校验 → 返回 {sessionId, serverTime, pushToken}
> 4. 客户端把 sessionId 存入，后续业务包带上
> 5. 鉴权包超时时 → 触发重新登录流程
> ```
> Mars 的 `Callback::MakesureAuthed()` 就是这个流程的**本地门控**：
> 返回 false 时任务挂起，等你登录成功后 `TouchTasks()` 唤醒。

---

## 10. IM 接入的完整清单（基于 sample 改造）

### 10.1 协议层

```protobuf
// 建议的 cmdid 划分
enum CmdID {
    CMD_ID_UNKNOWN        = 0;
    CMD_ID_AUTH           = 1;    // 鉴权（握手）
    CMD_ID_SEND_MESSAGE   = 2;    // 发消息
    CMD_ID_ACK_MESSAGE    = 3;    // 消息 ack
    CMD_ID_DELIVER_REPORT = 4;    // 送达回执
    CMD_ID_READ_REPORT    = 5;    // 已读回执
    // ★ 6 被 NOOP_CMDID 占用，必须跳过！
    CMD_ID_SYNC_CONV      = 7;    // 同步会话
    CMD_ID_PUSH_MESSAGE   = 10001;// 服务端推送
}
```

### 10.2 客户端必做

- [ ] 鉴权握手（`CMD_ID_AUTH`）
- [ ] `makesureAuthed` 返回真实登录态
- [ ] 消息本地落库（发送状态机）
- [ ] 业务级消息 ID（幂等键）
- [ ] push 处理 + ACK
- [ ] 消息顺序保证（同一会话同一优先级）
- [ ] 失败重试策略（区分可重试/不可重试）

### 10.3 服务端必做

- [ ] 帧解码器
- [ ] 心跳回包（seq 回显 `0xFFFFFFFF`）
- [ ] 鉴权校验 + 会话管理
- [ ] 消息幂等去重
- [ ] 推送路由（按用户/会话）
- [ ] 背压（慢客户端限速）
- [ ] 连接数限流 + 退避拒绝
- [ ] 消息持久化
- [ ] 优雅关闭（不要粗暴 RST）

---

## 11. Sample 里值得学习的 5 个设计

1. **任务属性用注解声明**（`@TaskProperty`）—— 业务代码和传输配置解耦，新人一眼看懂。
2. **传输层抽象成 TaskWrapper**（AIDL）—— 业务层完全不知道 STN 的存在。
3. **推送跨进程转发** —— 网络栈进程只做转发，UI 进程做业务。
4. **本地乐观更新**（`ChatActivity.java:193-198`）—— 发消息立刻上屏，服务端 ACK 后更新状态。
5. **性能画像上报**（`reportTaskProfile` → `CGIHISTORY_CMDID`）—— 客户端自己收集端到端延迟数据。

---

## 12. Sample 里必须改的 5 个问题

1. **无鉴权**（客户端 `makesureAuthed` 硬编码 true；服务端 `CMD_ID_AUTH` 无分支）
2. **服务端无帧解码器** → 必丢帧
3. **服务端 EventLoop 阻塞** → 必卡顿
4. **消息不持久化** → 丢消息
5. **`TASK_ID_TO_WRAPPER` 不清理** → 内存泄漏（sample 的 `onTaskEnd` 里没有 `remove`）
