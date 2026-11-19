---
tags: [android]
part: 第5部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[Retrofit]], [[../README]]"
---

# OkHttp

## 一句话定义

OkHttp 是 Square 开源的 **Android / Java 平台的 HTTP 客户端**，它不是"业务 API 框架"（那是 Retrofit 的活），而是一套**高性能的底层 HTTP 通信引擎**：负责连接管理、拦截器链、缓存、Keep-Alive 复用、WebSocket 长连接。面试一句话：**"OkHttp 解决的是'怎么把一个 HTTP 请求高效地发出去'，Retrofit 解决的是'怎么声明式地描述一个接口'。"**

## 核心原理

### 1. 五大核心角色

OkHttp 的对象模型非常薄，理解这五个类就理解了大半：

| 对象 | 职责 | 面试定位 |
| --- | --- | --- |
| `OkHttpClient` | 客户端门面，持有配置（超时、缓存、连接池、拦截器） | **应当全局单例**，每次 new 会浪费连接池 |
| `Request` | 请求描述（url / method / headers / body） | 不可变数据类，构造后才可发送 |
| `Response` | 响应描述（code / headers / body） | `body` 只能读一次，`close()` 必须调用 |
| `Call` | 一次可执行的请求，持有 `Request` 与 `Response` | `execute()` 同步 / `enqueue()` 异步 |
| `Dispatcher` | 调度中心，管理异步队列与同步调用 | 控制**最大并发数**与**同主机并发数** |

```text
                 ┌──────────────────────┐
   newCall(req)  │    OkHttpClient      │  单例
        │        │  ├─ Dispatcher       │  调度器
        ▼        │  ├─ ConnectionPool   │  连接复用
   ┌─────────┐   │  ├─ Cache            │  磁盘缓存
   │  Call   │   │  ├─ Interceptor链   │  责任链
   │ request │   │  └─ Authenticator    │  401 鉴权
   └─────────┘   └──────────────────────┘
        │
        ├── execute()   → 同步，直接在当前线程做 socket IO
        └── enqueue()   → 异步，交给 Dispatcher 的线程池
```

> 面试点：**`OkHttpClient` 必须单例**。它内部持有 `ConnectionPool` 与 `Dispatcher` 的线程池，每次 `new OkHttpClient()` 都会新建一套线程池和连接池，导致连接无法复用、线程泄漏。

### 2. 拦截器链（Application Interceptor → Network Interceptor）

OkHttp 的灵魂是**责任链模式**。一个请求会被一串 `Interceptor` 依次处理：

```text
RealCall.getResponseWithInterceptorChain()
  │
  ├─ 1. Application Interceptors   ← 用户自定义（重试/日志/鉴权）
  │     ├─ RetryAndFollowUpInterceptor   （内部实现，重试与 30x 跟随）
  │     ├─ BridgeInterceptor              （内部实现，Cookie/ gzip/ Host 头）
  │     ├─ CacheInterceptor               （内部实现，缓存读写）
  │     ├─ ConnectInterceptor             （内部实现，真正建立连接）
  │     │     └─ Exchange  ← Network Interceptors 插入到这里
  │     ├─ CallServerInterceptor          （内部实现，写请求、读响应）
  │     └─ RetryAndFollowUpInterceptor    （内部实现，重试与 30x 跟随）
  └─ 2. Network Interceptors     ← 用户自定义（只看单个网络请求）
```

**两类拦截器的关键区别**（面试高频）：

| 维度 | Application Interceptor | Network Interceptor |
| --- | --- | --- |
| 位置 | 在 Retry / Redirect 之前 | 在真正发网络请求时，每个网络请求都会经过 |
| 重定向/重试 | **能看到**多次重试（会看到 3 次 200） | 只看到一次最终请求 |
| 缓存命中 | **看不到**缓存命中（缓存直接返回 Response） | 能看到，因为缓存拦截器在其之前 |
| 拿到连接信息 | 拿不到 `chain.connection()` | **可以**拿到 `Connection`、DNS、握手耗时 |
| 适合做什么 | 全局日志、鉴权 Header、签名、请求改写 | 网络耗时监控、抓包、连接层信息采集 |

```kotlin
// Application Interceptor：全局日志 + 统一 Header
val logging = Interceptor { chain ->
    val request = chain.request()
    val t0 = System.nanoTime()
    val response = chain.proceed(request)
    val cost = (System.nanoTime() - t0) / 1_000_000
    Log.d("OkHttp", "${request.method} ${request.url} -> ${response.code} (${cost}ms)")
    response
}
```

> 面试话术：**"Application Interceptor 在 RetryAndFollowUp 之外，所以它对每一次重试都能看到；Network Interceptor 在 ConnectInterceptor 内部，能拿到连接对象，能感知到真实的 socket 耗时，但看不到缓存命中。"**

### 3. 同步 execute() vs 异步 enqueue()

| 维度 | `execute()` | `enqueue()` |
| --- | --- | --- |
| 执行线程 | **当前线程**（阻塞） | Dispatcher 的 `ExecutorService` |
| 回调 | 无，直接返回 `Response` | `Callback.onResponse/onFailure` |
| 线程要求 | **必须在子线程调用**，否则 NetworkOnMainThreadException | 无要求，内部已切线程 |
| 异常 | 抛 `IOException` | 走 `onFailure` |
| 典型用法 | 单元测试、同步封装层 | UI 层网络请求 |

`enqueue()` 的内部流程：

```text
enqueue(AsyncCall)
   │
   ├─ 交给 Dispatcher
   │    ├─ promoteCallToReady  → 加入 readyAsyncCalls（按 host 排队）
   │    ├─ executorService.execute(asyncCall)  → 线程池执行
   │    └─ AsyncCall.run()
   │         └─ getResponseWithInterceptorChain()
   │              └─ 拿到 Response → 回调 Callback（线程池线程）
   │
   └─ 注意：Response 交给回调线程，主线程更新 UI 需自己 post
```

**为什么 `enqueue()` 必须在子线程？**
这是最常被问到的追问。真相是：**`enqueue()` 本身不需要在子线程**，OkHttp 会自动切到自己的线程池。真正的原因是"**如果你在主线程调 `execute()`**"会抛 `NetworkOnMainThreadException`（因为 `execute()` 在当前线程做 socket IO，Android 的 `StrictMode` 检测到主线程网络访问就直接抛异常）。

但工程上仍然建议把网络调用放进子线程，理由是：

1. `execute()` 阻塞调用者，网络慢就直接卡住调用线程（若调用者在主线程 = ANR）。
2. 即使 `enqueue()`，回调线程也是 OkHttp 自己的池，**主线程更新 UI 仍需要 `Handler`/`LiveData`/`StateFlow` 中转**，所以"网络层不碰主线程"这条纪律更清晰。

> 面试话术：**"`enqueue()` 不要求子线程，因为 OkHttp 内部有 Dispatcher 线程池；`execute()` 才必须在子线程。Android 的 `NetworkOnMainThreadException` 是 StrictMode 的检测结果，判定依据是这次调用发生在主线程且做了 socket IO。"**

### 4. 缓存机制

OkHttp 的缓存分两层：**内存缓存（不内置，由你用 `CacheControl`/自定义 Map 实现）** 和 **磁盘缓存（`Cache` 类）**。

```kotlin
val cache = Cache(File(cacheDir, "http_cache"), 10L * 1024 * 1024) // 10MB
val client = OkHttpClient.Builder()
    .cache(cache)
    .build()
```

**缓存命中判断流程**：

```text
请求进入 CacheInterceptor
   │
   ├─ 请求带 no-cache / no-store / max-age=0？ ── 是 ──> 跳过缓存，直接走网络
   │
   ├─ 查磁盘缓存（按 URL + Vary 头的变体索引）
   │      ├─ 没命中 ──> 走网络
   │      └─ 命中 ──> 得到缓存 Response
   │
   ├─ 判断缓存新鲜度
   │      ├─ 缓存仍然新鲜（未过期）
   │      │      └─> 直接返回缓存 Response（不请求网络）★ 强缓存
   │      └─ 缓存已过期
   │             ├─ 请求带 If-None-Match / If-Modified-Since？
   │             │     ├─ 带 ──> 走网络，但服务器可能返回 304 Not Modified
   │             │     │        └─ OkHttp 用 304 + 缓存内容 合成一个 200 响应 ★ 协商缓存
   │             │     └─ 不带 ──> 直接走网络返回新内容
   │             └─ 无条件重新验证
   └─ 写回磁盘缓存（CacheControl 允许存储 且 响应码可缓存）
```

**Cache-Control 指令对照**：

| 指令 | 含义 |
| --- | --- |
| `no-store` | 完全不存缓存（敏感数据必带） |
| `no-cache` | 可以存，但每次使用前必须回源校验 |
| `max-age=60` | 60 秒内视为新鲜，可直接用 |
| `max-stale=600` | 过期后 600 秒内仍可用 |
| `min-fresh=60` | 至少还有 60 秒新鲜期才用 |
| `must-revalidate` | 过期后必须回源，不能用陈旧副本 |
| `immutable` | 资源不会变，永不重新校验 |

**ETag / If-None-Match 协商缓存**（面试常问）：

```text
第一次请求:
  GET /api/user  →  200 OK
  ETag: "v1-abc"
  Cache-Control: max-age=0

第二次请求（缓存已过期但仍可校验）:
  GET /api/user
  If-None-Match: "v1-abc"          ← 客户端带上 ETag
  → 服务器比对 ETag 未变化
  → 304 Not Modified（无 body）
  → OkHttp 用本地缓存的 body + 304 的 header 合成 200 响应返回
  → 流量节省最大（只传 header，不传 body）
```

> 面试点：**强缓存（Cache-Control max-age）不发网络请求，协商缓存（ETag）发请求但可能只回 304**。这两个是 HTTP 缓存的两条腿，OkHttp 都实现了。

### 5. 连接池与 Keep-Alive

HTTP/1.1 默认开启 **Keep-Alive**（持久连接）：一个 TCP 连接可以承载多个请求，避免每次都做 TCP 三次握手 + TLS 握手。

```kotlin
val client = OkHttpClient.Builder()
    .connectionPool(ConnectionPool(maxIdleConnections = 5, keepAliveDuration = 5, TimeUnit.MINUTES))
    .build()
```

- `ConnectionPool` 维护一个 **`Deque<RealConnection>`**，按最后使用时间排序。
- 空闲连接被复用时，会先检查 `connection.isEligible(reuseAddress)`：判断**连接是否过期**（`maxIdleDuration`）以及**主机地址是否一致**。
- 一个 `OkHttpClient` 同一时刻对**同一个 host 最多 5 个并发 HTTP/1.1 请求**（HTTP/2 不受此限，因为多路复用）；OkHttp 4.x 中这个值被移到 `Dispatcher.maxRequestsPerHost`。
- 连接池为空且无新连接时，才真正建立新连接（TCP 握手 → TLS 握手 → 请求）。

```text
请求1 ──┐
        ├─→ 复用同一条 RealConnection（keep-alive）
请求2 ──┘

ConnectionPool:
  [RealConnection#1 (idle, host=example.com, lastUsed=t1)]
  [RealConnection#2 (idle, host=api.other.com, lastUsed=t2)]
```

### 6. 常见配置与扩展

```kotlin
val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)   // 建立连接超时
    .readTimeout(30, TimeUnit.SECONDS)      // 读取数据超时（注意：WebSocket 需设 0）
    .writeTimeout(30, TimeUnit.SECONDS)     // 写入数据超时
    .callTimeout(60, TimeUnit.SECONDS)      // 整个调用超时（含重定向/重试）
    .retryOnConnectionFailure(true)         // 连接失败自动重试
    .followRedirects(true)                  // 自动跟随 3xx
    .followSslRedirects(true)
    .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
    .cache(Cache(File(cacheDir, "http"), 20L * 1024 * 1024))
    .addInterceptor(AuthInterceptor())
    .addNetworkInterceptor(NetworkMonitorInterceptor())
    .addInterceptor(CacheControlInterceptor())  // 强制离线优先
    .cookieJar(CookieJarImpl())
    .dispatcher(Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 10
    })
    .build()
```

> **超时之间的关系**：`callTimeout` 是总闸门，其他三个是分项超时，`callTimeout` 到期会中断整个调用。

## 代码示例

### 1. 统一的 OkHttpClient 单例 + 拦截器链

```kotlin
object HttpClient {

    /** 全局单例：内部持有 ConnectionPool 与 Dispatcher 线程池，不可重复创建 */
    val instance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .cache(Cache(File(AppContext.cacheDir, "http_cache"), 20L * 1024 * 1024))
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            // ① Application 拦截器：只加一次，作用于整个调用链
            .addInterceptor(HeaderInterceptor())
            .addInterceptor(AuthInterceptor(TokenStore()))
            .addInterceptor(LogInterceptor())
            // ② Network 拦截器：每个真实网络请求都会经过，能拿到 Connection
            .addNetworkInterceptor(NetworkTimingInterceptor())
            .build()
    }
}

/** 统一注入公共 Header（User-Agent / Accept / TraceId） */
class HeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
            .header("User-Agent", "MyApp/1.0 (Android)")
            .header("Accept", "application/json")
            .header("X-Trace-Id", TraceId.next())
        return chain.proceed(builder.build())
    }
}

/** 鉴权拦截器：token 过期时触发一次刷新（注意不要死循环） */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = tokenStore.getAccessToken()
        val request = if (token.isNullOrEmpty()) original
        else original.newBuilder().header("Authorization", "Bearer $token").build()

        val response = chain.proceed(request)
        // 401 且本次请求没有带 token，说明是 token 过期 → 刷新后重放一次
        if (response.code == 401 && token != null) {
            val newToken = tokenStore.refreshBlocking() ?: return response
            val replay = original.newBuilder()
                .header("Authorization", "Bearer $newToken")
                .build()
            response.close() // 必须关闭旧响应，否则连接泄漏
            return chain.proceed(replay)
        }
        return response
    }
}

/** 耗时日志：区分 DNS / 连接 / TLS / 服务端处理 */
class LogInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val start = System.nanoTime()
        val response = chain.proceed(req)
        val total = (System.nanoTime() - start) / 1_000_000
        Log.d("HTTP", "${req.method} ${req.url} code=${response.code} total=${total}ms")
        return response
    }
}

/** Network 拦截器：可以读取真实的连接层信息 */
class NetworkTimingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val conn = chain.connection() // 仅在 Network Interceptor 中非 null
        val start = System.nanoTime()
        val response = chain.proceed(chain.request())
        val cost = (System.nanoTime() - start) / 1_000_000
        Log.d("NET", "socket=${conn?.socket()?.remoteSocketAddress} cost=${cost}ms")
        return response
    }
}
```

### 2. 同步与异步调用

```kotlin
// ① 同步：必须在子线程（否则抛 NetworkOnMainThreadException）
fun fetchSync(): String {
    val request = Request.Builder()
        .url("https://api.example.com/v1/user/profile")
        .get()
        .build()
    HttpClient.instance.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        return response.body?.string() ?: ""
    }
}

// ② 异步：OkHttp 内部切线程，回调在 OkHttp 线程池线程上
fun fetchAsync(onResult: (String) -> Unit, onError: (Throwable) -> Unit) {
    val request = Request.Builder()
        .url("https://api.example.com/v1/user/profile")
        .build()

    HttpClient.instance.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            onError(e)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {  // use 保证 body 被 close
                if (!response.isSuccessful) {
                    onError(IOException("HTTP ${response.code}"))
                    return
                }
                val body = response.body?.string().orEmpty()
                onResult(body)
            }
        }
    })
}

// ③ 协程友好的写法：把 enqueue 封装成 suspend 函数
suspend fun fetchAwait(): String = suspendCancellableCoroutine { cont ->
    val request = Request.Builder().url("https://api.example.com/v1/user/profile").build()
    val call = HttpClient.instance.newCall(request)

    // 协程被取消时同步取消 OkHttp 的 Call（关键：避免请求泄漏）
    cont.invokeOnCancellation { call.cancel() }

    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (response.isSuccessful) {
                    cont.resume(response.body?.string().orEmpty())
                } else {
                    cont.resumeWithException(IOException("HTTP ${response.code}"))
                }
            }
        }
    })
}
```

### 3. 文件上传与流式下载

```kotlin
/** 多文件上传：用 MultipartBody 构造 multipart/form-data 请求 */
fun uploadFiles(
    files: List<File>,
    fields: Map<String, String> = emptyMap(),
    onProgress: (Long, Long) -> Unit = { _, _ -> }
): Response {
    val body = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .apply { fields.forEach { (k, v) -> addFormDataPart(k, v) } }
        .apply {
            files.forEach { file ->
                addFormDataPart(
                    "file[]",
                    file.name,
                    ProgressRequestBody(file.asRequestBody(), onProgress)
                )
            }
        }
        .build()

    val request = Request.Builder()
        .url("https://api.example.com/v1/upload")
        .post(body)
        .build()

    return HttpClient.instance.newCall(request).execute()
}

/** 带进度回调的 RequestBody 包装 */
class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (Long, Long) -> Unit
) : RequestBody() {

    override fun contentType() = delegate.contentType()

    override fun contentLength() = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength() ?: -1L
        val counting = object : ForwardingSink(sink) {
            var written = 0L
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                onProgress(written, total)
            }
        }
        val buffered = Okio.buffer(counting)
        delegate.writeTo(buffered)
        buffered.flush()
    }
}

/** 流式下载：必须用 source()，不要用 string()/bytes()，否则大文件 OOM */
fun downloadFile(url: String, dest: File): Long {
    val request = Request.Builder().url(url).build()
    HttpClient.instance.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val body = response.body ?: throw IOException("empty body")
        body.source().use { source ->
            dest.outputStream().use { out ->
                val buffer = okio.Buffer()
                while (true) {
                    if (source.exhausted()) break
                    val read = source.read(buffer, 8192)
                    if (read == -1L) break
                    out.write(buffer.readByteArray(read).toByteArray())
                }
            }
        }
        return dest.length()
    }
}
```

### 4. 请求取消与超时控制

```kotlin
// ① 通过 Call.cancel() 取消：会中断正在进行的 socket IO
val call = HttpClient.instance.newCall(request)
call.cancel()
// 注意：cancel 后 enqueue 的回调会收到 onFailure(IOException("Canceled"))

// ② 协程中推荐用 ensureActive + invokeOnCancellation
suspend fun fetchWithCancelTag(tag: Job) {
    // coroutineScope + invokeOnCancellation 的写法见上面 fetchAwait

// ③ OkHttp 内部超时（callTimeout）是最可靠的兜底
val client = OkHttpClient.Builder()
    .callTimeout(15, TimeUnit.SECONDS)
    .build()
```

## 常见问题 / 面试要点

**读者问：OkHttp 的拦截器执行顺序是什么？Application Interceptor 和 Network Interceptor 有什么区别？**

**AI答：**

1. **完整顺序（自上而下）**：
   ```
   用户 Application Interceptors（按 addInterceptor 添加顺序）
     → RetryAndFollowUpInterceptor
     → BridgeInterceptor
     → CacheInterceptor
     → ConnectInterceptor
        → 用户 Network Interceptors（每个真实网络请求都执行一遍）
     → CallServerInterceptor
   → （响应沿原路返回，逆序 unwrap）
   ```
2. **四类典型自定义拦截器的归属**：
   - **Application**：统一 Header、鉴权 Token、请求签名、请求体加密、全局日志（**只需执行一次**）。
   - **Network**：网络耗时/DNS/TLS 埋点、连接质量监控、Mock 拦截（**每次真实网络请求都执行**）。
3. **四个关键差异点**（面试官最爱追问）：
   - **重试可见性**：Application 在 RetryAndFollowUp **之外**，所以它能看到 3 次重试；Network 只看到最终一次。
   - **重定向可见性**：Application 能看到 302 → 200 的全过程；Network 只看到最后的 200。
   - **缓存可见性**：Application **看不到**缓存命中（缓存命中时直接返回，根本不进入网络链路）；Network 能看到。
   - **连接信息**：`chain.connection()` 在 Application 中为 `null`，在 Network 中非 null，可拿到 `socket()`、`route()`。
4. **一句记忆**："**Application 管'这一次调用要做什么'，Network 管'这一次网络往返发生了什么'。**"

---

**读者问：OkHttp 的缓存机制是怎么工作的？强缓存和协商缓存的区别？**

**AI答：**

1. **OkHttp 自带磁盘缓存（`Cache` 类），不自带内存缓存**。内存缓存要自己用 `LruCache` + `CacheControl` 实现。
2. **两条缓存路径**：
   - **强缓存（Fresh Cache）**：响应头带 `Cache-Control: max-age=60`，60 秒内再请求**完全不发网络请求**，直接返回磁盘缓存。零流量、最快。
   - **协商缓存（Conditional Request）**：缓存已过期，客户端带上 `If-None-Match: <ETag>`（或 `If-Modified-Since`），服务器发现资源未变则回 `304 Not Modified`（**无 body**），OkHttp 用 304 的 header + 缓存的 body 合成一个完整的 200 响应。只有几十字节流量。
3. **命中判断的三个必要条件**（缺一不可）：
   - 响应码**可缓存**（200、203、300、301、308 等；`Authorization` 头的响应默认不可缓存，除非有 `public`）。
   - 请求方法允许（GET/HEAD 默认可缓存，POST 默认不可，除非显式设置）。
   - 响应头无 `no-store`；`no-cache` 表示"可存但每次都要校验"。
4. **缓存 key 是什么**：**URL + Vary 头指定的请求头组合**。如果服务器返回 `Vary: Accept-Encoding`，则不同 `Accept-Encoding` 会存不同副本。
5. **常见坑**：
   - 用户登录后，缓存里可能还有**上一个账号的数据** → 必须在登出时 `cache.evictAll()`，或给请求加 `Cache-Control: no-cache`。
   - `POST` 请求默认**不入缓存**，很多面试者误以为所有请求都能缓存。
   - `Cache` 目录必须放在 `context.cacheDir`（系统可清理），放 `filesDir` 会一直占用户空间。

---

**读者问：为什么 `enqueue()` 必须在子线程调用？`execute()` 呢？**

**AI答：**

1. **技术上不需要**。`enqueue()` 会把任务交给 `Dispatcher` 的 `ExecutorService`，网络 IO 发生在 OkHttp 自己的线程池线程上，主线程调用完全安全。
2. **真正会在主线程抛异常的是 `execute()`**。因为 `execute()` 在**调用者线程**同步做 socket IO，Android 的 `StrictMode` 检测到"主线程 + 网络访问"就抛 `NetworkOnMainThreadException`。官方文档也是这么描述的：*"Network traffic on the main thread will result in NetworkOnMainThreadException being thrown."*
3. **工程规范上仍建议网络调用放子线程**，三条理由：
   - `execute()` 阻塞调用线程，网络抖动会拖慢整个调用链（若在主线程就是 ANR）。
   - 保持"**网络层不依赖主线程**"的一致心智，回调再通过 `LiveData/StateFlow/Flow` 切回主线程，线程归属清晰。
   - 便于统一错误处理与生命周期绑定。
4. **面试话术**："**`enqueue` 不要求子线程，`execute` 必须在子线程；`NetworkOnMainThreadException` 是 StrictMode 检测的，不是 OkHttp 抛的。**"

---

**读者问：OkHttp 的连接池是什么？Keep-Alive 是怎么工作的？**

**AI答：**

1. **Keep-Alive 的意义**：HTTP/1.1 默认持久连接。一条 TCP 连接可以传多个请求，避免每次请求都重新做 **TCP 三次握手 + TLS 握手**（后者尤其昂贵，2-RTT 起步）。这是移动端优化响应时间的头号手段。
2. **`ConnectionPool` 的实现**：`ArrayDeque<RealConnection>`，**按最后使用时间排序**。新建连接入队尾，复用时从队头取最久未用的。
3. **复用条件**（`RealConnection.isEligible`）：
   - 连接未超过 `keepAliveDuration`（默认 5 分钟）。
   - `route.address()` 与新请求的 host/port/ssl 完全一致。
   - 连接的 socket 没有被服务端关闭。
4. **重要限制**：OkHttp 对**同一个 host 最多 5 个并发 HTTP/1.1 请求**（`Dispatcher.maxRequestsPerHost`），这是 HTTP/1.1 下浏览器也有的限制；超出就排队。**HTTP/2 不受此限**（多路复用）。
5. **ConnectionPool 是 per-OkHttpClient 的** → **这就是 `OkHttpClient` 必须单例的根本原因**。每次 new 一个 client = 新建一套连接池 + 新建一套线程池，之前的连接全部浪费，线程还会泄漏。

---

**读者问：OkHttp 怎么实现请求签名/加密？拦截器里做和在外面做有什么区别？**

**AI答：**

1. **推荐在 Application Interceptor 里做**。原因：
   - 只执行一次，**签名不会被重复计算**（Network Interceptor 遇到重试/重定向会执行多次，签名参数一旦被改写就会失效）。
   - 位于 `RetryAndFollowUpInterceptor` 之外，能感知到完整调用链。
2. **典型实现**：
   ```kotlin
   class SignInterceptor : Interceptor {
       override fun intercept(chain: Interceptor.Chain): Response {
           val original = chain.request()
           val body = original.body
           // body 通常只能写一次：需要缓存 body 内容再重新构造
           val buffer = Buffer().also { body?.writeTo(it) }
           val bytes = buffer.readByteArray()
           val sign = SignUtil.sign(original.url.encodedPath, bytes)
           val signed = original.newBuilder()
               .header("X-Sign", sign)
               .header("X-Timestamp", System.currentTimeMillis().toString())
               .method(original.method, original.method.let { original.body }) // body 需重建
               .build()
           return chain.proceed(signed)
       }
   }
   ```
3. **大坑**：**`RequestBody` 只能写一次**（`writeTo` 是流式写入）。想在拦截器里读取 body 来计算签名，必须先把 body 读进内存 `Buffer` 再构造一个新的 `RequestBody`，否则会抛异常或发出空 body。生产上更稳的做法是**服务端约定用 URL/query + header 参与签名**，不读 body。
4. **注意事项**：
   - 签名要带 **timestamp + nonce**，否则重放攻击。
   - 重试时签名可能过期 → 拦截器里判断时间戳容差，或在 Network 层重新签名。
   - 加密 body 会影响 `Content-Length` 与 `Content-Type`，需要一起改。

---

**读者问：OkHttp 支持 WebSocket 吗？和 Socket.io 有什么区别？**

**AI答：**

1. **支持**。`OkHttpClient.newWebSocket(request, listener)` 返回 `WebSocket`，基于 OkHttp 3.x 起内置实现，**OkHttp 4.x 已把 WebSocket 从独立依赖 `okhttp-websocket` 合并进来**。
2. **能力**：发送文本/二进制、Ping/Pong、关闭握手（Close Frame 带状态码和 reason）。
3. **OkHttp WebSocket vs Socket.io**：
   | 维度 | OkHttp WebSocket | Socket.io |
   | --- | --- | --- |
   | 协议 | 原生 RFC 6455 | Socket.io 私有协议（基于 HTTP 升级） |
   | 房间/命名空间 | 无（需自己实现） | 内置 room / namespace |
   | 断线重连 | **不内置**，需自己实现 | 内置自动重连与降级 |
   | 消息确认 | 无（需业务层 ACK） | 内置 ack / 回调 |
   | 适用 | 轻量、需要完全控制协议 | 需要房间、复杂消息语义 |
4. **面试话术**：**"OkHttp WebSocket 是纯 RFC 6455 实现，Socket.io 是一套带房间和自动重连的框架。OkHttp 更底层更灵活，Socket.io 更省事但协议不互通（服务端必须是 Socket.io）。"**
5. 详见 [[../网络协议/WebSocket]]。

---

**读者问：OkHttp 常见超时类型有哪些？WebSocket 为什么 readTimeout 要设 0？**

**AI答：**

1. **四种超时**：
   | 超时 | 含义 | 典型值 |
   | --- | --- | --- |
   | `connectTimeout` | 建立 TCP+TLS 连接的最长时间 | 10s |
   | `readTimeout` | 两次读操作之间的最大间隔 | 30s |
   | `writeTimeout` | 两次写操作之间的最大间隔 | 30s |
   | `callTimeout` | **整个调用**（含重定向、重试、读取） | 60s |
2. **`callTimeout` 是总闸门**，它到期会中断整个调用链；即使各项都没超时，总时长也会被它截断。
3. **WebSocket 必须设 `readTimeout(0)`（无限）**：WebSocket 长期空闲是正常的，readTimeout 是"socket 空闲超时"，设置过短会导致空闲几十秒后连接被 OkHttp 主动断开（服务端不会发心跳时尤其明显）。同理 `callTimeout` 也要设为 0，因为 `callTimeout` 作用于 WebSocket 的整个生命周期。
4. **超时异常**统一是 `SocketTimeoutException`，注意 `callTimeout` 到期抛的是 `InterruptedIOException("timeout")`，而不是 `SocketTimeoutException`，处理时别只 catch 前者。

---

**读者问：如何取消一个正在进行的 OkHttp 请求？协程里怎么优雅取消？**

**AI答：**

1. **原生方式**：`Call.cancel()`。它会关闭 socket，正在进行的 IO 会抛 `IOException("Canceled")`。
2. **协程方式**：推荐用 `suspendCancellableCoroutine` + `invokeOnCancellation`：
   ```kotlin
   suspend fun fetch(): String = suspendCancellableCoroutine { cont ->
       val call = client.newCall(request)
       cont.invokeOnCancellation { call.cancel() }   // 协程取消 → 同步取消 HTTP
       call.enqueue(object : Callback { /* ... */ })
   }
   ```
3. **常见坑**：
   - **`Response` 必须关闭**（`use { }`），否则连接不会被归还连接池，久了会耗尽连接。
   - `Response.body` 只能读一次（`string()` / `bytes()` 之后流已关闭）；要读两次必须用 `response.peekBody(n)`。
   - 协程 `viewModelScope` 取消时，**不会自动取消** OkHttp 的 Call（除非用上面的 `suspendCancellableCoroutine` 封装），请求仍在跑，浪费流量。

---

**读者问：OkHttp 和 HttpURLConnection 相比强在哪？为什么官方推荐它？**

**AI答：**

1. **API 层面**：链式 Builder 取代 `setRequestProperty` 拼凑；`Call` 抽象统一同步/异步；拦截器链提供统一扩展点。
2. **协议层面**：透明支持 **HTTP/2 与 WebSocket**；`HttpURLConnection` 在 Android 上直到 API 21 之前根本没有 HTTP/2 支持（`HttpsURLConnection`）。
3. **性能层面**：
   - **连接池 + Keep-Alive**（HttpURLConnection 也有，但管理较弱）。
   - 内部用 Okio 做高效缓冲读写，减少内存拷贝。
   - `Dispatcher` 限流，避免同时开太多连接耗尽资源。
4. **工程层面**：
   - **统一缓存实现**（HttpURLConnection 的缓存行为不可控）。
   - **可观测**：拦截器链让日志/监控/熔断成为可能。
   - **共享生态**：Retrofit、Ktor(OkHttp Engine)、Coil、Glide(可选集成) 都建立在它之上。

---

**读者问：OkHttp 的 `Response` 使用有哪些坑？**

**AI答：**

1. **必须关闭**：不关闭 `Response` 会导致连接不归还连接池 → 连接耗尽 → 请求挂起。始终用 `use { }`。
2. **body 只能读一次**：`response.body?.string()` 之后再次读取会抛 `IllegalStateException("closed")`。需要预览用 `peekBody(maxBytes)`。
3. **错误响应也有 body**：HTTP 4xx/5xx 的 body 同样有内容（错误 JSON），很多人在 `if (!isSuccessful) throw` 时直接丢掉错误信息 → 排障困难。**应该先读出错误 body 再抛异常**。
4. **`isSuccessful` 只表示 200..299**，重定向 3xx 在有 `followRedirects` 时已被内部处理。
5. **`code` 与 `message`**：`message` 是 HTTP 状态短语（如 "Not Found"），可能为空或被中间层改写，**不要用它做业务判断**。

## 关联笔记

- [[Retrofit]]：声明式 HTTP 客户端，构建在 OkHttp 之上
- [[Ktor]]：Kotlin 多平台网络框架，可使用 OkHttp 作为 Engine
- [[../网络协议/HTTP与HTTPS]]：HTTP/2、HTTP/3 与 TLS 握手细节
- [[../网络协议/WebSocket]]：OkHttp WebSocket 的使用与心跳设计
- [[../异步方案/Kotlin协程]]：用协程封装 OkHttp 的挂起调用
- [[../Handler机制原理]]：把网络回调切回主线程的底层机制
- [[../README]]：第 5 部分总览

## 参考资料

- OkHttp 官方文档（拦截器链 / API 说明）：https://square.github.io/okhttp/
- OkHttp 源码（`RealCall`、`CacheInterceptor`、`RetryAndFollowUpInterceptor`）：https://github.com/square/okhttp
- Square 技术博客 "OkHttp 3.0"：https://square.github.io/blog/2015/06/02/okhttp-3/
- Android 网络安全配置文档：https://developer.android.com/privacy-and-security/security-config
- 《Android 高级编程》第 4 版 · 网络与线程章节
