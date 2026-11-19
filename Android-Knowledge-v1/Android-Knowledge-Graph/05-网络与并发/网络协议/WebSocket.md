---
tags: [android]
part: 第5部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[../README]]"
---

# WebSocket

## 一句话定义

WebSocket 是 **RFC 6455** 定义的、在 **TCP 之上**的**全双工持久连接协议**：客户端通过一次 **HTTP/1.1 Upgrade 握手**把连接"升级"成 WebSocket，此后双方可以**随时主动推送消息**，不再需要"轮询"或"长轮询"。

## 核心原理

### 1. 为什么需要 WebSocket

| 方案 | 服务端 → 客户端 | 延迟 | 连接开销 | 适用 |
| --- | --- | --- | --- | --- |
| **轮询** | 只能被动等客户端来问 | 高（间隔级） | 每个轮询一次完整 HTTP | 兜底方案 |
| **长轮询** | 服务端 hold 住请求直到有数据 | 中 | 连接被长期占用，占用线程/连接数 | 不支持长连接的中间设备 |
| **SSE（Server-Sent Events）** | 服务端主动推 | 低 | 单向，基于 HTTP | **只要服务端→客户端** |
| **WebSocket** | 双向主动推 | **最低** | 一次握手，长期复用 | **双向实时交互** |

- **SSE 是 HTTP 响应流**（`text/event-stream`），只能服务端推、客户端不能推，且**受同源策略和连接数限制**，但实现极简（Spring 有 `@SendMessage` 级别的支持）。
- **WebSocket 是独立协议**（握手后就是自己的帧格式），双向、低延迟，是**即时通讯、实时行情、协同编辑**的标准方案。

### 2. 握手过程（HTTP/1.1 Upgrade）

```text
Client                                                  Server
  │                                                       │
  │──── GET /ws HTTP/1.1 ────────────────────────────────►│
  │     Host: ws.example.com                              │
  │     Upgrade: websocket                                 │
  │     Connection: Upgrade                                │
  │     Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==        │
  │     Sec-WebSocket-Version: 13                          │
  │     (可选) Sec-WebSocket-Protocol: chat                │
  │     (可选) Sec-WebSocket-Extensions: permessage-deflate│
  │                                                       │
  │◄─── HTTP/1.1 101 Switching Protocols ─────────────────│
  │     Upgrade: websocket                                 │
  │     Connection: Upgrade                                │
  │     Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo= │
  │     (可选) Sec-WebSocket-Protocol: chat                │
  │                                                       │
  │══════ WebSocket 帧数据（不再是 HTTP 报文）════════════►│
```

**关键点**：

1. **握手仍是标准 HTTP 请求**（GET + 若干 header），服务端**先解析成一个普通 HTTP 请求**，识别到 `Upgrade: websocket` 后回 `101`。
2. **`Sec-WebSocket-Key`**：客户端随机生成的 **16 字节 base64** 值，**不是密钥**，作用是**防缓存代理误判**（证明客户端确实理解 WebSocket 协议）。
3. **`Sec-WebSocket-Accept` 的计算**（面试可能让你手算）：
   ```
   GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
   accept = Base64( SHA1( key + GUID ) )
   ```
   服务端把 `Sec-WebSocket-Key` 与 GUID 拼接，取 SHA-1，再 Base64 → 就是 `Sec-WebSocket-Accept`。客户端用同样算法校验，**验证服务端确实支持 WebSocket**。
4. **握手成功（101）后，连接上的数据不再是 HTTP 格式，而是 WebSocket 帧**。此后两个方向都可主动发送。
5. **子协议协商（Subprotocol）**：客户端用 `Sec-WebSocket-Protocol: chat` 声明支持的子协议，服务端在 101 响应里回显选中的那个。用来区分消息格式（JSON / Protobuf / 自定义）。
6. **扩展协商**：如 `permessage-deflate`（**压缩**，WebSocket 的数据量大时很有用）、`x-webkit-deflate-frame`。

### 3. 帧结构

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-------+-+-------------+-------------------------------+
|F|R|R|R| opcode|M| Payload len |    Extended payload length    |
|I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
|N|V|V|V|       |S|             |   (if payload len==126/127)   |
| |1|2|3|       |K|             |                               |
+-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
|     Masking-key (if MASK set, 4 bytes)                     | ...
+---------------------------------------------------------------+
|                          Payload Data                       |
```

**字段说明**：

| 字段 | 长度 | 含义 |
| --- | --- | --- |
| **FIN** | 1 bit | 是否为**最后一帧**。分片传输时中间帧 FIN=0，最后一帧 FIN=1 |
| **RSV1/2/3** | 3 bit | 保留位，**RSV1 用于 permessage-deflate 扩展** |
| **opcode** | 4 bit | 帧类型（见下表） |
| **MASK** | 1 bit | 载荷是否被掩码。**客户端发往服务端的帧必须为 1**；服务端发往客户端的帧**必须为 0** |
| **Payload len** | 7/16/64 bit | 载荷长度：<126 直接表示；=126 用 16 位（2 字节扩展）；=127 用 64 位（8 字节扩展） |
| **Masking-key** | 4 byte | 掩码密钥，仅当 MASK=1 时存在 |
| **Payload Data** | 可变 | 应用数据 |

**Opcode 帧类型**：

| Opcode | 名称 | 说明 |
| --- | --- | --- |
| `0x0` | Continuation | 后续帧（分片消息的延续） |
| `0x1` | Text | **文本帧**（必须是 UTF-8） |
| `0x2` | Binary | **二进制帧** |
| `0x8` | Close | **关闭握手**，可带 2 字节状态码 + reason |
| `0x9` | Ping | **心跳探测**，对端必须回 Pong |
| `0xA` | Pong | 心跳响应 |

**掩码（Masking）为什么存在**：不是为了安全（掩码可被轻易还原），而是**为了防止"缓存代理污染攻击"**——防止恶意页面构造的字节序列恰好被代理误当成 HTTP 响应注入缓存。**RFC 要求客户端必须掩码，服务端必须拒绝未掩码的客户端帧**。

**分片（Fragmentation）**：大数据可以拆成多个帧（首帧 FIN=0 + 后续 Continuation 帧，最后一帧 FIN=1）。但**实际工程中极少使用分片**——直接发一个大帧更高效，OkHttp 也不主动分片。

### 4. 心跳与保活

**为什么需要心跳**：TCP 连接是"沉默即死"的。当中间设备（NAT 网关、路由器、防火墙）在一段时间内看不到数据，会**主动关闭空闲连接**（NAT 映射表老化，常见超时 30~300 秒）。WebSocket 协议本身没有心跳，**必须由应用层实现**。

```text
App ──── Ping (opcode 0x9) ────► Server
App ◄─── Pong (opcode 0xA) ───── Server
        （若在 pongTimeout 内收到 Pong，则认为连接健康）
```

**心跳设计的四个关键决策**：

1. **用 WebSocket 协议级的 Ping/Pong，还是应用层心跳消息？**
   - **协议级 Ping/Pong（opcode 0x9）**：由框架/库自动发送，**不占用业务带宽**，服务端框架一般自动回 Pong。但**中间的网关可能不转发 Ping 帧**（部分代理只转发 DATA 帧）。
   - **应用层心跳**：发一条 `{"type":"ping"}` 的业务消息，服务端回 `{"type":"pong"}`。**穿透性好**（能验证端到端业务链路），但占用业务带宽，且需要服务端配合。
   - **生产建议**：**两者结合** —— 协议级保活 + 应用层心跳做业务超时判定。
2. **心跳间隔**：必须**小于**中间设备的空闲超时。常见取 **30~60 秒**（微信/QQ 的长连接心跳约 4~5 分钟，但那是优化后的策略；常规 App 用 30~60 秒更安全）。
3. **超时判定**：收到 Ping 后若 `pongTimeout`（通常 10~15 秒）内没收到任何响应 → 判定连接失效 → **主动关闭并重连**。
4. **服务端心跳策略**：服务端也可以主动 Ping 客户端，客户端必须回 Pong。**客户端若不回，服务端应关闭连接**。

### 5. 断线重连（退避策略）

```text
第 1 次失败 → 等待 1s   → 失败
第 2 次失败 → 等待 2s   → 失败
第 3 次失败 → 等待 4s   → 失败
第 4 次失败 → 等待 8s   → 失败
第 5 次失败 → 等待 16s  → 失败
第 6 次失败 → 等待 30s（上限）→ 失败
```

**指数退避（Exponential Backoff）+ 抖动（Jitter）**：

- **指数退避**：`delay = min(base * 2^n, maxDelay)`。避免服务端恢复瞬间被大量客户端同时冲击（**惊群效应**）。
- **抖动（随机化）**：`delay = random(0, delay)` 或 `delay * (0.5 + random(0.5))`。让不同客户端的请求**在时间上散开**。**只做指数退避不做抖动，在大规模客户端场景下仍会形成流量尖峰**。

**重连时要做的事**：

1. **重新握手**（新的 HTTP Upgrade 过程）。
2. **断点续传 / 消息补齐**：服务端应保存客户端最后一条成功处理的消息 ID，重连后客户端带上 `lastMessageId`，服务端补发遗漏的消息。**这需要服务端配合设计**。
3. **本地消息队列**：断线期间的消息**先入本地队列**，重连成功后按序补发（注意去重与幂等）。
4. **超时放弃策略**：重试 N 次仍失败 → 降级为轮询或提示用户。

### 6. 消息可靠性

**WebSocket 协议本身不保证消息可靠性**（它保证的是**字节流的顺序与完整性**，不保证"应用消息一定被业务处理"）。

| 保障层次 | 手段 | 说明 |
| --- | --- | --- |
| **传输可靠** | TCP | 字节不丢、不乱序（同一连接内） |
| **消息完整** | 帧 + FIN + 应用层长度/分隔符 | 分片消息重组 |
| **业务确认（ACK）** | 应用层消息 ID + ack | 发送方保留 pending 队列，超时重发 |
| **端到端幂等** | 消息 ID 去重 | 服务端记录已处理的消息 ID，重复的丢弃 |
| **顺序保证** | 单连接 + 序号 | 多连接并行时需应用层排序 |

**业务层 ACK 的典型设计**：

```json
// 客户端 → 服务端
{"id":"msg-123","type":"chat","ts":1712345678,"payload":{"text":"hi"}}
// 服务端 → 客户端
{"id":"msg-123","type":"ack","code":0}
```

- 客户端维护 `pendingMap: Map<msgId, PendingMessage>`，发送后启动超时定时器。
- 超时未收到 ack → **重发**（相同 msgId）。
- 服务端维护 `processedIds`（可用 Redis + TTL）做**去重**，保证幂等。

> 面试话术：**"WebSocket 传的是 TCP 字节流，协议层不保证业务语义的消息可靠性。要做到'不丢不重'，必须在应用层加消息 ID + ACK + 去重表 + 重发机制。"**

### 7. 推送方案对比

| 维度 | WebSocket | FCM (Firebase) | 厂商推送（华为/小米/OPPO） |
| --- | --- | --- | --- |
| **连接方式** | App 主动维持长连接 | **Google 服务器 → 设备**，依赖 Google 服务 | 依赖厂商系统服务 |
| **到达率** | 高（连接不断时） | 极高（Google 基础设施） | 高（国内 ROM 保活好） |
| **时延** | **最低**（几十 ms） | 高（但 FCM 高优先级可到秒级） | 中~高 |
| **国内可用性** | ✅ 不依赖 GMS | ❌ **国行 GMS 受限，需自建通道** | ✅ |
| **依赖厂商** | 无 | Google | 需接入各厂商 SDK（适配成本高） |
| **下行（服务端→客户端）** | ✅ | ✅ | ✅ |
| **上行（客户端→服务端）** | ✅（天然双向） | ❌（需另建通道） | ❌ |
| **费用** | 自己承担服务器成本 | 免费额度 + 超量收费 | 基本免费 |
| **适用** | 聊天、协同、实时行情（需双向） | 营销推送、跨平台通用通知 | 国内 App 的保活推送 |

**工程上的常见组合**：

```text
【长连接 WebSocket】  ← 服务端有消息要实时下发（IM、订单状态、实时数据）
        +
【FCM / 厂商推送】   ← 用户完全退出 App 后仍要收到（FCM 尤其适合海外）
        ↓
【收到推送后唤起长连接拉取】
```

**降级策略**：App 启动时先尝试连 WebSocket；连不上（或被系统限制）→ 退化为**短轮询**；再不行 → 依赖 FCM/厂商推送。**必须做降级**，否则国产 ROM 的后台限制会让长连接形同虚设。

> Android 后台限制：Doze 模式下（详见 [[../README]]），非白名单 App 的网络会被挂起；前台服务（`foregroundServiceType="dataSync"`）或厂商推送通道是常见解法。

## 代码示例

### 1. OkHttp WebSocket 的基本使用

```kotlin
class ChatWebSocket(
    private val onMessage: (String) -> Unit,
    private val onStateChanged: (State) -> Unit
) {
    private val client = OkHttpClient.Builder()
        // ★ WebSocket 必须关闭读超时，否则空闲连接会被 OkHttp 主动断开
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)   // 协议级心跳：30s 一次 Ping
        .build()

    private var webSocket: WebSocket? = null

    fun connect(serverUrl: String, token: String) {
        val request = Request.Builder()
            .url(serverUrl)                        // ws:// 或 wss://
            .header("Authorization", "Bearer $token")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStateChanged(State.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 二进制消息
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                onStateChanged(State.CLOSING)
                // 必须主动 close 完成关闭握手
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStateChanged(State.CLOSED)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStateChanged(State.FAILED)
                // 触发重连
            }
        })
    }

    fun send(text: String): Boolean = webSocket?.send(text) ?: false

    fun close() {
        webSocket?.close(1000, "bye")   // 1000 = Normal Closure
        webSocket = null
    }

    enum class State { CONNECTING, CONNECTED, CLOSING, CLOSED, FAILED }
}
```

### 2. 带心跳 + 指数退避重连的健壮封装

```kotlin
class ReconnectingWebSocket(
    private val url: String,
    private val tokenProvider: () -> String,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
    private val onState: (State) -> Unit
) {
    private companion object {
        const val BASE_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 30_000L
        const val PONG_TIMEOUT_MS = 15_000L
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private var retryCount = 0
    private var lastPongAt = 0L

    /** 应用层心跳：定时发 ping 消息，若超时未收到任何响应则主动重连 */
    private val heartbeatJob = scope.launch(Dispatchers.Default) {
        while (isActive) {
            delay(30_000)                       // 心跳间隔 30s
            val ws = socket ?: continue
            if (System.currentTimeMillis() - lastPongAt > PONG_TIMEOUT_MS) {
                // 超过 pong 超时，判定连接已死
                ws.cancel()
                reconnect()
                continue
            }
            ws.send("""{"type":"ping","ts":${System.currentTimeMillis()}}""")
        }
    }

    fun connect() {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${tokenProvider()}")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retryCount = 0
                lastPongAt = System.currentTimeMillis()
                onState(State.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 收到任意消息都视为连接存活
                lastPongAt = System.currentTimeMillis()
                if (text.contains("\"pong\"")) return
                onMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onState(State.FAILED)
                reconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onState(State.CLOSED)
                reconnect()
            }
        })
    }

    /** 指数退避 + 抖动 */
    private fun reconnect() {
        val delay = minOf(BASE_DELAY_MS * (1L shl retryCount), MAX_DELAY_MS)
        val jittered = delay / 2 + Random.nextLong(delay / 2 + 1)   // 0.5x ~ 1.0x 随机抖动
        retryCount++
        scope.launch(Dispatchers.Default) {
            delay(jittered)
            connect()
        }
    }

    fun close() {
        heartbeatJob.cancel()
        socket?.close(1000, "bye")
        socket = null
    }
}
```

### 3. 应用层 ACK + 重发 + 去重（保证业务可靠性）

```kotlin
data class PendingMessage(
    val msgId: String,
    val body: String,
    val sentAt: Long,
    var retries: Int = 0
)

class ReliableChatClient(private val scope: CoroutineScope) {

    private val pending = ConcurrentHashMap<String, PendingMessage>()
    private val sentIds = ConcurrentHashMap<String, Long>()   // 已确认的消息 ID（去重）

    fun send(text: String) {
        val msgId = UUID.randomUUID().toString()
        val body = """{"id":"$msgId","type":"chat","payload":${Json.encodeToString(text)}}"""
        pending[msgId] = PendingMessage(msgId, body, System.currentTimeMillis())
        socket?.send(body)

        // 启动超时重发定时器
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(3_000)
                val p = pending[msgId] ?: return@launch   // 已被 ack 确认，退出
                if (p.retries >= 3) {
                    pending.remove(msgId)
                    onGiveUp(msgId)
                    return@launch
                }
                p.retries++
                socket?.send(p.body)      // 重发同一条，消息 ID 不变
            }
        }
    }

    /** 收到服务端消息：区分 ack 与业务消息 */
    fun onServerMessage(json: String) {
        val obj = Json.parseToJsonElement(json).jsonObject
        when (obj["type"]?.jsonPrimitive?.content) {
            "ack" -> {
                val id = obj["id"]!!.jsonPrimitive.content
                pending.remove(id)              // 确认成功，停止重发
                sentIds[id] = System.currentTimeMillis()
            }
            else -> {
                val id = obj["id"]?.jsonPrimitive?.content
                // ★ 幂等去重：同一条消息重复到达时直接丢弃
                if (id != null && sentIds.containsKey(id)) return
                if (id != null) sentIds[id] = System.currentTimeMillis()
                handleBusinessMessage(obj)
            }
        }
    }

    private fun onGiveUp(msgId: String) { /* 提示用户发送失败 */ }
    private fun handleBusinessMessage(obj: JsonObject) { /* 业务处理 */ }
}
```

### 4. 收到推送后唤醒长连接（FCM + WebSocket 组合）

```kotlin
class PushWakeupService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        // 收到推送：立即建立/唤醒 WebSocket，由服务端推送真正的业务数据
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            ChatSocketHolder.get().connect()
        }
    }
}
```

### 5. Ktor 的 WebSocket 客户端（KMP 场景）

```kotlin
val client = HttpClient(CIO) {
    install(WebSockets)          // ★ 注意：OkHttp Engine 不支持 WebSocket
}

// 客户端会话
client.webSocketSession(
    urlString = "wss://ws.example.com/chat",
    request = { header("Authorization", "Bearer $token") }
) {
    // 发送
    send(Frame.Text("""{"type":"ping"}"""))
    // 接收
    for (frame in incoming) {
        if (frame is Frame.Text) println(frame.readText())
    }
}
```

## 常见问题 / 面试要点

**读者问：WebSocket 是什么？和 HTTP 长轮询有什么区别？**

**AI答：**

1. **定义**：WebSocket 是 **RFC 6455** 定义的、建立在 **TCP 之上**的**全双工通信协议**。它通过一次 HTTP/1.1 `Upgrade` 握手完成协议切换，之后数据以独立的**帧格式**传输，两个方向都可以主动推送。
2. **与长轮询的对比**：
   | 维度 | 长轮询 | WebSocket |
   | --- | --- | --- |
   | 连接模型 | HTTP 请求保持打开，服务端有数据才返回 | 一次握手，长期双向连接 |
   | 客户端上行 | 需要重新发起 HTTP 请求 | **随时可发** |
   | 每次消息开销 | 一整套 HTTP 头（几百字节） | 2~10 字节帧头 |
   | 服务端资源 | 长期占用一个连接 + 线程 | 一个连接，可承载海量连接 |
   | 中间设备兼容 | 好（就是 HTTP） | 差（部分代理/防火墙不支持 Upgrade） |
3. **和 SSE 的区别**：SSE 是**单向**的（服务端 → 客户端），基于普通 HTTP 响应流，**客户端不能通过同一条连接上行**（需要另开 HTTP 请求）。WebSocket 双向。**只需要下行推送时 SSE 更简单**（不需要处理心跳帧、断线重连由浏览器内置）。
4. **什么时候用**：
   - **双向实时**（IM、协同编辑、实时交易、游戏）→ **WebSocket**。
   - **只要服务端推**（新闻流、监控大盘）→ **SSE 或 FCM** 更省事。
   - **低频、不需要实时** → **普通 HTTP + 轮询** 足够，别过度设计。
5. **面试话术**：**"WebSocket 是一次 HTTP Upgrade 换来的全双工长连接。相比长轮询它省掉了每条消息的 HTTP 头开销，而且客户端也能主动推。缺点是中间设备兼容性和重连逻辑要自己扛。"**

---

**读者问：讲一下 WebSocket 的握手过程。`Sec-WebSocket-Accept` 是怎么算出来的？**

**AI答：**

1. **握手四步**：
   - 客户端发 **标准 HTTP GET**，带 `Upgrade: websocket`、`Connection: Upgrade`、`Sec-WebSocket-Key: <16字节随机base64>`、`Sec-WebSocket-Version: 13`。
   - 服务端识别到 Upgrade，**回 `101 Switching Protocols`**，带 `Sec-WebSocket-Accept`。
   - 客户端**校验 Accept** 是否正确。
   - 此后连接上的数据不再是 HTTP 报文，而是 **WebSocket 帧**。
2. **`Sec-WebSocket-Accept` 的计算**（一定要能写出来）：
   ```
   GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
   accept = Base64( SHA1( Sec-WebSocket-Key + GUID ) )
   ```
   客户端用同样算法算一遍，和服务端返回的值比对，**不一致就断开**。
3. **Key 的作用**：不是密钥！它是**防缓存污染的随机挑战**。如果某个中间缓存代理错误地把某次 WebSocket 握手响应缓存了，后续别的客户端会拿到错误的 Accept，从而无法完成握手。同时也**验证服务端确实实现了 WebSocket 协议**（而不是个普通 HTTP 服务器）。
4. **子协议协商（加分点）**：客户端通过 `Sec-WebSocket-Protocol: chat` 声明期望的子协议；服务端在 101 响应中**回显选中项**（不选则不返回该头，客户端视为不接受）。这是协商机制，不是客户端单方面指定。
5. **扩展协商**：如 `permessage-deflate`（压缩），服务端同意则在响应里回 `Sec-WebSocket-Extensions: permessage-deflate`。
6. **面试话术**：**"握手就是一次特殊的 HTTP GET，服务端回 101，Accept = Base64(SHA1(Key + 固定GUID))，用来防代理缓存污染并验证服务端支持 WebSocket。之后协议切换成帧格式。"**

---

**读者问：WebSocket 的心跳怎么设计？为什么要心跳？**

**AI答：**

1. **为什么需要**：
   - **NAT 超时**：路由器/NAT 网关的映射表老化（常见 30~300 秒），空闲后**主动丢弃映射**，外部再也打不进这条连接。表现为"客户端以为还连着，服务端早就断了"。
   - **半开连接（Half-Open）**：一端崩溃/断网，对端 TCP 处于 `ESTABLISHED` 但实际不通，收不到 FIN。**只有主动发数据才能发现**。
   - WebSocket 协议**没有内置心跳**，必须应用层实现。
2. **两种心跳**：
   - **协议级 Ping/Pong（opcode 0x9 / 0xA）**：由库自动发（OkHttp 的 `pingInterval`）。不占业务带宽，服务端框架一般自动回。但**某些网关会丢弃 Ping 帧**（只透传 DATA 帧）。
   - **应用层心跳**：发 `{"type":"ping"}` 业务消息。**端到端可靠**，能穿透网关验证完整链路，但占业务带宽且需服务端配合。
   - **推荐组合**：协议级保活 + 应用层心跳做业务超时判定。
3. **关键参数**：
   - **心跳间隔**：必须**小于**中间设备的空闲超时。常用 **30~60 秒**。太长会漏掉断线，太短浪费电量和流量。
   - **Pong 超时**：收到 Ping 后 **10~15 秒**内没收到任何响应 → 判定连接失效。
   - **注意**：OkHttp 的 `pingInterval` 会在连接**闲置时**才发（不是固定周期），且服务端不回 Pong 时 OkHttp 会**自己断开**。
4. **Android 特有坑**：
   - **`readTimeout` 必须设为 0**（无限），否则 OkHttp 会在空闲超时后断开 WebSocket。
   - **`pingInterval` 与 `readTimeout` 是两回事**：前者是心跳，后者是 socket 读超时。
   - **Doze / 后台限制**：App 进入后台后，Android 可能限制网络；需要 `foregroundService` 或依赖厂商推送通道。
5. **面试话术**：**"心跳是为了对抗 NAT 超时和半开连接。协议级 Ping/Pong 更省，但可能过不了网关，所以生产上一般是协议级 + 应用层心跳双保险，间隔 30~60 秒，超时 10~15 秒判定断线重连。"**

---

**读者问：断线重连怎么实现？为什么要用指数退避？**

**AI答：**

1. **触发重连的场景**：网络切换（WiFi ↔ 4G）、Doze 唤醒、服务器主动踢、TCP 超时、App 从后台恢复。
2. **指数退避（Exponential Backoff）**：
   ```
   delay(n) = min(baseDelay * 2^n, maxDelay)
   例：1s → 2s → 4s → 8s → 16s → 30s(封顶)
   ```
   **为什么**：如果所有客户端同时掉线（服务器重启、网络抖动），用固定间隔会导致**所有客户端在同一时刻发起重连**，形成**惊群效应（Thundering Herd）**，把刚恢复的服务器再次打垮。指数退避把重连请求**在时间上摊开**。
3. **抖动（Jitter）**：只有指数退避还不够——多个客户端的 `2^n` 序列是**同步的**，仍然会在同一时刻聚集。所以要加随机：
   ```
   delay = random(0, min(baseDelay * 2^n, maxDelay))       // Full Jitter（AWS 推荐）
   或 delay = base * 2^n * (0.5 + random(0.5))              // Equal Jitter
   ```
   **面试话术**：**"指数退避解决'不要立刻重试'，抖动解决'不要和别的客户端同时重试'。缺一不可。"**
4. **重连后要补的事**：
   - **重新鉴权**（token 可能已过期）。
   - **消息补齐**：客户端携带 `lastMessageId`，服务端补发断连期间的消息（需要服务端配合）。
   - **本地消息队列**：断连期间产生的消息先入队，重连成功后按序补发（**注意幂等和去重**）。
5. **重试上限与降级**：连续失败 N 次后（通常 5~10 次）→ 停止自动重连，**降级为短轮询**或提示用户检查网络。

---

**读者问：WebSocket 和 FCM / 厂商推送怎么选？可以一起用吗？**

**AI答：**

1. **对比**：
   | 维度 | WebSocket | FCM | 厂商推送 |
   | --- | --- | --- | --- |
   | 方向 | **双向** | 下行 | 下行 |
   | 时延 | **最低（几十 ms）** | 中（高优先级可到百毫秒级） | 中~高 |
   | 依赖 | 自己搭服务器 | Google 服务 | 各厂商系统服务 |
   | 国内可用 | ✅ | ❌ 国行 GMS 受限 | ✅ |
   | 上行消息 | ✅ | ❌ 需另建通道 | ❌ |
   | 成本 | 服务器 + 流量 | 免费额度 | 基本免费 |
2. **结论**：
   - **需要双向实时**（IM、协同、行情）→ **WebSocket 为主**。
   - **App 完全退出也要收到** → **FCM / 厂商推送兜底**。
   - **国内 App** → **厂商推送（华为/小米/OPPO/vivo）+ 自建长连接**。
3. **组合方案（生产标准做法）**：
   ```
   用户打开 App
     └─ 建立 WebSocket 长连接（前台）
          └─ 收到推送（FCM / 厂商通道）时，唤起长连接并拉取业务数据
   用户完全退出 App
     └─ 只能靠 FCM / 厂商推送（离线推送）
   ```
   即：**推送负责"叫醒"，长连接负责"取数据"**。这样即使推送内容被系统折叠（`collapse_key`），也能通过长连接补齐。
4. **注意点**：
   - **国内合规**：必须申请推送权限、隐私政策中说明、遵循《个信安法》。
   - **FCM 在国内不可靠**：需接厂商通道，且**各厂商 SDK 适配成本高**（华为 HMS、小米 OPPO vivo 各自一套）。
   - **保活**：国产 ROM 的后台限制（自启动、后台弹出、电池优化）是长连接存活的关键。

---

**读者问：WebSocket 消息丢失或重复怎么办？怎么保证业务可靠性？**

**AI答：**

1. **先分清"传输可靠"和"业务可靠"**：
   - **TCP 保证**：字节不丢、不乱序、不重复（同一连接内）。
   - **WebSocket 协议保证**：分片重组、消息边界完整。
   - **业务可靠（服务端真的处理了这条消息）**：**协议不保证**，必须应用层实现。
2. **三个常见丢消息场景**：
   - **断连期间的消息**：客户端发的，服务端没收到 → 需要**本地队列 + 重连补发**。
   - **服务端处理了但 ACK 丢了**：客户端重发 → 服务端收到**重复**消息 → 需要**幂等**。
   - **连接断开时服务端正在推送的消息**：服务端推了但没推完 → 需要**消息 ID + 客户端上报 lastId + 服务端补发**。
3. **标准方案：消息 ID + ACK + 幂等表**：
   ```text
   客户端 → 服务端: {id: "uuid-1", type:"chat", payload:{...}}
   服务端 → 客户端: {id: "uuid-1", type:"ack", code:0}
   ```
   - 客户端：发送后把消息放进 `pendingMap`，启动超时定时器；收到 ACK 就移除；超时则**用相同 ID 重发**。
   - 服务端：收到消息先查**去重表**（Redis + TTL），已处理过就直接回 ACK 不再处理；否则处理并记录 ID。
   - **同一套逻辑同时解决"丢"和"重"**：重发用同一 ID，服务端幂等 → 业务上等价于"恰好一次"。
4. **顺序保证**：单条 WebSocket 连接内 TCP 保证顺序；但**如果为了容灾开了多条连接**，跨连接的顺序需要应用层用**全局序列号**保证。
5. **面试话术**：**"WebSocket 只保证传输可靠，业务可靠要靠消息 ID + ACK + 幂等表。重发用同一个 ID，服务端去重，这样丢包和重复在业务层面被抹平。"**

---

**读者问：WebSocket 和 HTTP/2 的多路复用是不是一回事？**

**AI答：**

1. **本质区别**：
   - **HTTP/2 多路复用**：多个**独立的 HTTP 请求/响应**在一条 TCP 连接上并发。语义仍是"请求-响应"，服务端不能**主动**发起某个请求（只能靠 Server Push，且已被废弃）。
   - **WebSocket**：连接建立后是一条**独立的长连接协议**，两个方向都能**随时主动**发消息。语义是"会话/通道"，不是请求-响应。
2. **能否在同一连接上同时用 HTTP/2 和 WebSocket？**：
   - **传统方式不行**：WebSocket 的握手是 **HTTP/1.1 Upgrade**。HTTP/2 里没有 `Upgrade` 机制（RFC 7540 明确移除）。
   - **RFC 8441（Bootstrapping WebSockets with HTTP/2）**：定义了 **Extended CONNECT** 方式在 HTTP/2 上建立 WebSocket，客户端发 `CONNECT` 且 `:protocol = "websocket"`。**但浏览器支持极少**，实践中主要是服务端框架用。
3. **多路复用 vs 长连接**：
   - HTTP/2 的多个 Stream **共享一条连接**，任一 Stream 出问题（应用层错误）**不影响其他**。
   - WebSocket 是**单条消息流**，所有消息在同一序列上，**必须自己处理顺序和可靠性**。
4. **面试话术**：**"HTTP/2 多路复用是'一条连接上并发跑多个请求响应'，HTTP 语义不变；WebSocket 是把连接升级成一条双向消息流，语义完全不同。HTTP/2 上也能开 WebSocket，但用的是 RFC 8441 的 Extended CONNECT，浏览器基本不支持。"**

## 关联笔记

- [[../网络协议/HTTP与HTTPS.md|HTTP与HTTPS]]：HTTP/1.1 Upgrade 机制、HTTP/2 对比
- [[../网络框架/OkHttp]]：OkHttp WebSocket 的 API 与连接池
- [[../网络协议/RESTful]]：请求-响应式 API 设计
- [[../异步方案/Kotlin协程]]：协程管理长连接的生命周期
- [[../异步方案/Thread与Handler]]：心跳定时任务与线程管理
- [[../README]]：第 5 部分总览

## 参考资料

- RFC 6455（The WebSocket Protocol）：https://www.rfc-editor.org/rfc/rfc6455
- RFC 8441（Bootstrapping WebSockets with HTTP/2）：https://www.rfc-editor.org/rfc/rfc8441
- MDN WebSocket 指南：https://developer.mozilla.org/zh-CN/docs/Web/API/WebSocket
- OkHttp WebSocket 文档：https://square.github.io/okhttp/4.x/okhttp/okhttp3/-web-socket/
- Ktor WebSockets 文档：https://ktor.io/docs/server-websockets.html
- 《WebSocket 协议：一场实时通信的革命》（万维通）
