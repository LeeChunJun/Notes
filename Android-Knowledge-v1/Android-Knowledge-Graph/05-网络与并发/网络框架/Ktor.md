---
tags: [android]
part: 第5部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[OkHttp]], [[../README]]"
---

# Ktor

## 一句话定义

Ktor 是 JetBrains 出品的 **Kotlin 多平台（KMP）Web 框架**，既能写服务端（Routing + Plugin），也能写客户端（Client + Engine）。在 Android 侧的价值是：**同一套网络层代码可以跑在 Android / iOS / Web / 桌面**，并且天生是**协程原生**的。

## 核心原理

### 1. Ktor 的定位：Client 与 Server

```text
Ktor
 ├── Server 侧
 │    ├── Routing        路由：定义 URL → 处理函数
 │    ├── Plugin          插件：横切能力（认证、CORS、压缩、序列化）
 │    └── Engine          CIO / Netty / Jetty
 └── Client 侧
      ├── HttpClient     客户端门面
      ├── Engine          CIO / OkHttp / Apache / Java / Js / Darwin
      ├── Plugins         ContentNegotiation / Logging / Auth / Serialization
      └── 协程原生 API     request { } / get { } / post { }
```

**关键理解**：Ktor 的 Client **不是**"另一个 Retrofit"，它是一个**更底层的、可组合的 HTTP 客户端**。Retrofit 帮你把接口方法变成请求（竖切），Ktor 帮你用 **DSL 构造请求**（横切 + 灵活）。

### 2. HttpClient 与 Engine

```kotlin
val client = HttpClient(OkHttp) {         // 使用 OkHttp 作为底层引擎
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })   // kotlinx.serialization
    }
    install(Logging) { level = LogLevel.INFO }
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 30_000
    }
    defaultRequest {
        header("Accept", "application/json")
    }
}
```

**Engine 选择**：

| Engine | 平台 | 说明 |
| --- | --- | --- |
| `OkHttp` | JVM / Android | 复用 OkHttp 生态（拦截器、缓存、CertificatePinner） |
| `CIO` | JVM / Android / Native | Ktor 自研，纯 Kotlin 协程实现，**无 Android 依赖**，跨平台统一 |
| `Java` | JVM / Android | 基于 `HttpURLConnection`，无额外依赖 |
| `Apache` | JVM | 基于 Apache HttpClient |
| `Js` | JS | 浏览器 Fetch API |
| `Darwin` | iOS / macOS | NSURLSession |

> **选型建议**：**纯 Android 项目**用 `OkHttp` 或 `CIO`；**KMP 多平台项目**用 `CIO`（保证各平台行为一致，不依赖平台特有 API）；需要复用现有 OkHttp 拦截器生态时用 `OkHttp`。

### 3. 协程原生 API

这是 Ktor 相对 Retrofit + OkHttp 的最大手感差异——**没有"接口 + 代理"，直接用 DSL 描述请求**：

```kotlin
// ① 简单 GET
val body: User = client.get("https://api.example.com/v1/users/1").body()

// ② 复杂请求：request { } DSL
val response = client.request("https://api.example.com/v1/users") {
    method = HttpMethod.Post
    parameter("page", 1)              // query 参数
    parameter("size", 20)
    header("X-Trace-Id", traceId)
    contentType(ContentType.Application.Json)
    setBody(UserCreateRequest(name = "Ada", age = 36))
}.body<Unit>()

// ③ URL 构建
val url = URLBuilder("https://api.example.com/v1").apply {
    parameters.append("page", "1")
    parameters.append("keyword", "android")
}.build()

// ④ 流式下载
client.get(urlString).bodyAsChannel().copyTo(file.writeChannel())
```

**Ktor 的响应处理链**（面试常问）：

```text
HttpResponse
   ├── .body<T>()          → 按 Content-Type 用 ContentNegotiation 反序列化
   ├── .bodyAsText()       → 字符串
   ├── .bodyAsChannel()    → ByteReadChannel（流式，避免大文件进内存）
   ├── .body<ByteArray>()  → 字节数组
   └── .bodyAsFlow()       → Flow<ByteReadChannel>（多段下载）
```

### 4. ContentNegotiation 与 kotlinx.serialization

```kotlin
install(ContentNegotiation) {
    json(Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    })
    // 可同时注册多个格式，按 Content-Type 自动选择
    xml(Xml)
}
```

- `ContentNegotiation` 插件负责 **序列化（对象 → 请求体）** 与 **反序列化（响应体 → 对象）**，依赖 `Content-Type` 选择格式。
- 客户端需要在 `Accept` 头里声明期望的格式：`application/json`。
- **服务端**也用同一个插件，这样**客户端与服务端可以共享同一批 `@Serializable` 模型**——这是 KMP 最大的红利之一。

### 5. 插件（Plugin）机制

插件是 Ktor 的横切扩展点，替代了 OkHttp 拦截器的部分职责：

```kotlin
val client = HttpClient(OkHttp) {
    install(ContentNegotiation) { json() }
    install(Logging) { level = LogLevel.HEADERS }
    install(Auth) {
        bearer {
            loadTokens { TokenStore.accessToken }          // 首次取 token
            refreshTokens { TokenStore.refreshToken() }     // 过期时刷新
        }
    }
    install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    install(DefaultRequest) { header("User-Agent", "MyApp/1.0") }
    install(HttpRedirect) { followRedirects = true }
    // 自定义插件
    install(createClientPlugin("TraceId") {
        onRequest { request, _ -> request.headers.append("X-Trace-Id", TraceId.next()) }
    })
}
```

**OkHttp 拦截器 vs Ktor 插件**：

| 维度 | OkHttp Interceptor | Ktor Plugin |
| --- | --- | --- |
| 粒度 | 请求/响应级别 | 可钩住 `onRequest` / `onResponse` / `onTransformBody` 等多个阶段 |
| 能力 | 只能包住整个调用 | 能改写 body、能拦截序列化/反序列化 |
| 跨平台 | 仅 JVM | ✅ 所有平台 |
| 生态 | 依赖 OkHttp | Ktor 生态（官方 + 社区） |

### 6. Ktor vs OkHttp/Retrofit

| 维度 | Retrofit | OkHttp | Ktor |
| --- | --- | --- | --- |
| 抽象层级 | 接口声明层 | HTTP 引擎 | 引擎 + DSL 客户端 + 服务端 |
| 风格 | 声明式（接口 + 注解） | 命令式（Builder） | DSL（命令式但更简洁） |
| 协程 | 支持 `suspend`（2.6+） | 需自己封装 | **原生协程**（`body()` 是 suspend） |
| 多平台 | ❌ 仅 JVM | ❌ 仅 JVM | ✅ KMP 全平台 |
| 序列化 | Gson/Moshi/kotlinx（Converter） | 无（需手动） | kotlinx.serialization（ContentNegotiation） |
| WebSocket | ✅ 内置 | ✅ 内置 | ✅ `webSocketSession { }` |
| 横切扩展 | 靠 OkHttp 拦截器 | Interceptor | Plugin |
| 编译期校验 | ❌（注解运行时解析） | ❌ | 部分（DSL 类型安全） |
| 学习成本 | 低 | 中 | 中偏高 |

### 7. KMP 场景下的网络层选型

```text
┌────────────────── commonMain（共享 100% 网络代码） ──────────────────┐
│  ApiClient.kt          ← HttpClient + ContentNegotiation + 插件配置    │
│  UserApi.kt            ← suspend fun getUser(): User                  │
│  models/User.kt        ← @Serializable data class User                │
└───────────────────────────────┬───────────────────────────────────────┘
                                │ expect/actual 或直接依赖 commonMain
        ┌───────────────────────┼───────────────────────┐
        ▼                       ▼                       ▼
   androidMain             iosMain                 webMain
   CIO/OkHttp Engine       Darwin Engine             Js Engine
```

**KMP 网络层的四个决策点**：

1. **Engine**：跨平台优先选 `CIO`（行为一致）；Android 上如果已有 OkHttp 生态可用 `OkHttp`。
2. **序列化**：统一用 **kotlinx.serialization**（编译期生成，跨平台无反射）。
3. **平台特定能力**（如 Android 的 `networkSecurityConfig`、证书校验）用 `expect/actual` 隔离。
4. **HttpClient 生命周期**：在 KMP 里 `HttpClient` 应作为**单例**长期持有（它内部持有连接池与线程池），在 `commonMain` 用 `object` 或 DI 容器持有。

## 代码示例

### 1. Ktor Client 的标准配置

```kotlin
object ApiClient {

    /** 全局单例：内部持有 Engine 的连接池与协程作用域，不应重复创建 */
    val http: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = true   // 非 2xx 直接抛异常（可关闭改为手动判断）

            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true   // 服务端加字段不崩
                    explicitNulls = false
                    encodeDefaults = true
                })
            }

            install(Logging) {
                level = LogLevel.HEADERS
                sanitizeHeader { it == HttpHeaders.Authorization }   // 脱敏
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }

            install(DefaultRequest) {
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                header("X-Client-Version", AppVersion.current())
            }

            install(Auth) {
                bearer {
                    loadTokens { TokenStore.accessToken }
                    refreshTokens {
                        // 同步刷新（Auth 插件在 IO 上下文中调用）
                        TokenStore.refreshBlocking()
                    }
                }
            }

            // 自定义插件：请求追踪
            install(createClientPlugin("TraceIdPlugin") {
                onRequest { request, _ ->
                    request.headers.append("X-Trace-Id", TraceId.next())
                }
            })
        }
    }

    /** 应用退出时关闭 */
    fun close() = http.close()
}
```

### 2. 定义 API 层（协程原生）

```kotlin
@Serializable
data class User(
    val id: Long,
    val name: String,
    val email: String,
    val avatarUrl: String? = null
)

@Serializable
data class PagedResponse<T>(
    val items: List<T>,
    val total: Long,
    val page: Int
)

class UserApi(private val client: HttpClient = ApiClient.http) {

    /** 简单 GET + 反序列化 */
    suspend fun getUser(id: Long): User =
        client.get("https://api.example.com/v1/users/$id").body()

    /** 带 query 的 GET */
    suspend fun getUsers(page: Int = 1, size: Int = 20, keyword: String? = null): PagedResponse<User> =
        client.get("https://api.example.com/v1/users") {
            parameter("page", page)
            parameter("size", size)
            if (!keyword.isNullOrEmpty()) parameter("keyword", keyword)
        }.body()

    /** POST JSON */
    suspend fun createUser(request: UserCreateRequest): User =
        client.post("https://api.example.com/v1/users") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    /** PATCH：局部更新 */
    suspend fun patchUser(id: Long, patch: Map<String, String>): User =
        client.patch("https://api.example.com/v1/users/$id") {
            contentType(ContentType.Application.Json)
            setBody(patch)
        }.body()

    /** DELETE：返回 Unit */
    suspend fun deleteUser(id: Long) {
        client.delete("https://api.example.com/v1/users/$id").body<Unit>()
    }

    /** 流式下载：不把大文件读进内存 */
    suspend fun download(url: String, dest: File) = withContext(Dispatchers.IO) {
        client.get(url).bodyAsChannel().copyTo(dest.writeChannel())
    }

    /** 手动判断状态码（关闭 expectSuccess 后可用） */
    suspend fun getUserRaw(id: Long): HttpResponse =
        client.get("https://api.example.com/v1/users/$id")
}
```

### 3. 统一错误处理与 Result 封装

```kotlin
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Failure(val code: Int, val message: String, val cause: Throwable? = null) : ApiResult<Nothing>
}

suspend fun <T> apiCall(block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(block())
} catch (e: CancellationException) {
    throw e    // ★ 协程取消必须透传
} catch (e: ClientRequestException) {
    // 4xx：客户端错误
    val msg = e.response?.bodyAsText()
    ApiResult.Failure(e.response?.status?.value ?: -1, msg ?: "请求参数错误", e)
} catch (e: ServerResponseException) {
    // 5xx：服务端错误
    ApiResult.Failure(e.response?.status?.value ?: -1, "服务异常，请稍后再试", e)
} catch (e: ResponseException) {
    // 其他非 2xx（expectSuccess = true 时）
    ApiResult.Failure(e.response?.status?.value ?: -1, e.message ?: "网络错误", e)
} catch (e: IOException) {
    ApiResult.Failure(-1, "网络异常，请检查网络连接", e)
} catch (e: Exception) {
    ApiResult.Failure(-1, e.message ?: "未知错误", e)
}
```

### 4. KMP 共享网络层（commonMain + actual）

```kotlin
// commonMain/kotlin/.../ApiClient.kt
expect fun createHttpClient(): HttpClient

class UserRepository(private val api: UserApi) {
    suspend fun loadUser(id: Long): ApiResult<User> = apiCall { api.getUser(id) }
}
```

```kotlin
// androidMain/kotlin/.../HttpClient.android.kt
actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) { json() }
    install(AndroidCertificatePinner) {     // 平台特定的证书固定
        add("api.example.com", "sha256/AAAA...")
    }
}
```

```kotlin
// iosMain/kotlin/.../HttpClient.ios.kt
actual fun createHttpClient(): HttpClient = HttpClient(Darwin) {
    install(ContentNegotiation) { json() }
}
```

> **要点**：模型（`@Serializable data class`）、API 声明、错误处理封装全在 `commonMain`，只有**平台引擎与平台能力**下沉到 `actual`。

### 5. Ktor 服务端示例（了解即可）

```kotlin
fun Application.module() {
    install(ContentNegotiation) { json() }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
    }
    routing {
        get("/api/v1/users/{id}") {
            val id = call.parameters["id"]!!.toLong()
            call.respond(User(id = id, name = "Ada", email = "ada@example.com"))
        }
        post("/api/v1/users") {
            val req = call.receive<UserCreateRequest>()
            call.respond(HttpStatusCode.Created, User(1, req.name, ""))
        }
    }
}
```

## 常见问题 / 面试要点

**读者问：Ktor 是什么？它和 OkHttp、Retrofit 是什么关系？**

**AI答：**

1. **Ktor 是 JetBrains 的 Kotlin 多平台 Web 框架**，分 Server 与 Client 两块：
   - **Server**：Ktor Server 用 Routing 定义 URL → 处理函数的映射，用 Plugin 做横切（认证、CORS、压缩、序列化），可跑在 Netty / CIO / Jetty 上。
   - **Client**：Ktor Client 是协程原生的 HTTP 客户端，底层 Engine 可选 `CIO` / `OkHttp` / `Apache` / `Java` / `Js` / `Darwin`。
2. **与 OkHttp/Retrofit 的关系是"替代 + 互补"**：
   - **OkHttp 是 Engine**。Ktor 的 `OkHttp` Engine 就是把 Ktor 的请求模型翻译成 OkHttp 的 Request，所以 OkHttp 的能力（拦截器、缓存、`CertificatePinner`）在 Ktor 里依然可用。
   - **Retrofit 是接口声明层**，Ktor 是 **DSL 请求构造层**。Ktor 没有"接口 + 动态代理"这套机制，而是用 `client.get { }` / `client.request { }` 直接构造请求。
   - 换句话说：**Retrofit 是竖切（按业务模块切分接口），Ktor 是横切 + 平台无关**。
3. **什么时候选 Ktor**：
   - 有 **KMP（Kotlin Multiplatform）** 需求，要共享网络层。
   - 需要**服务端**（Ktor Server 写 BFF/网关）。
   - 想要**原生协程 API**，不想写 Retrofit 那一堆注解。
4. **什么时候不选**：单端 Android 项目、团队对 Retrofit 已经很熟、且不需要服务端 → **Retrofit 更稳**（生态成熟、IDE 友好、错误处理资料多）。

---

**读者问：Ktor 的优势是什么？相比 OkHttp/Retrofit 有什么取舍？**

**AI答：**

1. **核心优势**：
   - **KMP 多平台**：同一份 `commonMain` 网络代码跑 Android / iOS / Web / Desktop / Native，**模型和接口定义也共享**。
   - **原生协程**：`body()` 直接是 `suspend` 函数，没有"回调 → 协程"的胶水代码。
   - **序列化统一**：客户端与服务端共享同一批 `@Serializable` 模型，**不会出现"客户端和服务端 JSON 字段对不上"**的问题。
   - **插件体系**：横切能力（认证、日志、超时、序列化）以插件形式组合，比堆拦截器更清晰。
   - **服务端能力**：能用 Kotlin 写 BFF / API Gateway / 微服务，团队语言统一。
2. **取舍（劣势）**：
   - **生态与心智模型不如 Retrofit**：Retrofit 的注解 + 接口在 IDE 里体验极好，Ktor 的 DSL 需要写更多样板。
   - **没有接口代理 → 无编译期校验**：Retrofit 里 `@GET("user/{id}")` 写错路径编译期发现不了，Ktor 也一样；但 Ktor 至少**类型安全**（返回类型明确）。
   - **Android 上的调试成本**：Ktor 的日志、异常栈不如 Retrofit 直观；遇到线上问题排查路径更曲折。
   - **依赖体积**：Ktor client + engine + serialization 比 Retrofit 一套要"重"一些（可通过只引入需要的模块缓解）。
   - **国内资料少**：遇到冷门问题时中文排查资料少。
3. **面试话术**：**"Ktor 的杀手锏是 KMP 共享。如果只是单端 Android，Retrofit 的心智成本和生态更优；一旦要做 iOS 或服务端，Ktor 的收益就是压倒性的。"**

---

**读者问：KMP 场景下的网络层该怎么选型？**

**AI答：**

1. **四条决策主线**：
   - **① Engine 选择**：
     - **`CIO`**：Ktor 自研，纯 Kotlin 协程实现，**跨平台行为一致**（不会在 iOS 上因 NSURLSession 的怪癖产生差异），**推荐作为 KMP 默认**。
     - **`OkHttp`**：Android 端复用现有 OkHttp 拦截器、缓存、证书固定生态；缺点是**只能在 JVM 用**，iOS 端仍要换 Engine，会产生**行为不一致风险**。
     - **`Darwin`**：iOS 端基于 NSURLSession，尊重系统代理与后台传输能力。
   - **② 序列化**：**统一用 kotlinx.serialization**。它是**编译期生成代码**（非反射），跨平台零成本，且是 Ktor 官方推荐。Gson 依赖反射，在 KMP/Native 上不可用；Moshi 有 KSP codegen 但跨平台配置更繁琐。
   - **③ 平台差异隔离**：`expect/actual` 只下沉**平台特定能力**（证书固定、系统代理、后台任务、缓存目录），其余（模型、接口、错误处理、重试）全在 `commonMain`。
   - **④ Client 生命周期**：`HttpClient` 是**重量级对象**（持有连接池与协程作用域），在 KMP 里应作为**单例**长期持有；不要在每次请求时 `HttpClient { }` 新建，否则连接无法复用。
2. **一份典型分层**：
   ```text
   commonMain
     ├── models/         @Serializable 数据模型
     ├── ApiClient.kt    HttpClient 配置（引擎用 expect/actual 注入）
     ├── UserApi.kt      suspend 接口封装
     └── Result.kt       统一错误封装
   androidMain / iosMain / webMain
     └── HttpClient.xxx.kt   actual fun createHttpClient(): HttpClient
   ```
3. **常见坑**：
   - 在 `commonMain` 里直接 `import android.util.Log` → 编译失败（要用 `expect/actual` 或 KMP Logger）。
   - `HttpClient` 在 `commonMain` 里用 `object` 持有时要确保**进程存活期内不重建**。
   - `expect/actual` 的 `actual` 必须在**每个平台模块**都有实现，漏一个就编译不过。
4. **面试话术**：**"KMP 网络层我统一用 CIO engine + kotlinx.serialization，公共 API 和模型放 commonMain，只有引擎选择和平台能力用 expect/actual 下沉。"**

---

**读者问：Ktor 的 ContentNegotiation 是干什么的？和 Retrofit 的 Converter 有什么区别？**

**AI答：**

1. **ContentNegotiation 做什么**：根据 **Content-Type** 自动选择序列化器，完成双向转换：
   - **请求方向**：`setBody(obj)` → 查 `Content-Type` → 用对应 serializer 序列化成字节。
   - **响应方向**：`body<T>()` → 查响应的 `Content-Type` → 用对应 serializer 反序列化成 `T`。
2. **与 Retrofit Converter 的区别**：
   | 维度 | Retrofit Converter | Ktor ContentNegotiation |
   | --- | --- | --- |
   | 转换粒度 | 每个 `Converter.Factory` 只管一种类型 | 一个插件内可注册**多种格式**（json/xml/protobuf） |
   | 选择依据 | 返回类型 + 注解 | **`Content-Type` 请求头/响应头** |
   | 扩展方式 | 实现 `Converter.Factory` | 实现 `ContentConverter` / 注册 `ContentType` |
   | 请求体方向 | `requestBodyConverter` | 同一个插件对称处理 |
3. **关键点**：Ktor 的 `body<T>()` **依赖 `Content-Type`**。如果服务端返回 `text/plain` 而你调 `body<User>()`，会抛 `NoTransformationFoundException`。解决：
   - 请求时显式 `contentType(ContentType.Application.Json)`；
   - 或 `install(ContentNegotiation) { json(...) }` 的默认 `ContentType` 与服务端对齐；
   - 或 `client.request { }.bodyAsText()` 后自行解析。
4. **面试话术**：**"ContentNegotiation 是 Ktor 的序列化插件，按 Content-Type 选择格式；Retrofit 的 Converter 是按返回类型选择格式。Ktor 的优势是同一套客户端能同时处理 JSON/XML/ProtoBuf。"**

---

**读者问：Ktor 怎么实现请求重试、超时、熔断？**

**AI答：**

1. **超时**：`install(HttpTimeout)` 三个层级：
   - `requestTimeoutMillis`：**整个请求**的总时长（含重试、重定向）。
   - `connectTimeoutMillis`：建立连接。
   - `socketTimeoutMillis`：socket 读写空闲。
2. **重试**：
   - **Ktor 3.x** 内置 `HttpRequestRetry` 插件：
     ```kotlin
     install(HttpRequestRetry) {
         maxRetries = 3
         exponentialDelay(base = 2.0, maxDelayMs = 5_000)
         retryIf { _, response -> response.status.value >= 500 }
         retryOnExceptionIf { _, cause -> cause is IOException }
     }
     ```
   - 旧版本或需要更精细控制时，用 `expectSuccess = false` + 手动循环 + 指数退避。
   - **注意幂等性**：只对 GET/HEAD 或带幂等键的请求重试，否则可能重复下单。
3. **熔断 / 降级**：Ktor **没有内置熔断器**，需要：
   - 自己实现一个插件（统计失败率 → `onRequest` 时判断熔断状态 → 直接抛异常或返回降级数据）。
   - 或在 Ktor **服务端**侧用 `kotlin-circuitbreaker` 等库。
4. **限流**：`RateLimit` 插件（Ktor 3.x 提供 `install(RateLimit)`）。
5. **面试话术**：**"超时用 HttpTimeout 插件，重试用 HttpRequestRetry（指数退避 + 只在 5xx/IO 异常时重试），熔断 Ktor 客户端没有内置，得自己写插件。"**

---

**读者问：Ktor 的 Engine 有什么区别？Android 上该用哪个？**

**AI答：**

1. **各 Engine 定位**：
   - **`CIO`**：Ktor 自研，纯 Kotlin 协程 + NIO 实现。无第三方依赖，**跨平台行为一致**，KMP 首选。
   - **`OkHttp`**：包一层 OkHttp。Android 上如果已有 OkHttp 拦截器、缓存、`CertificatePinner` 生态，直接复用，迁移成本低。
   - **`Java`**：基于 `HttpURLConnection`，无额外依赖，最轻。
   - **`Apache`**：基于 Apache HttpClient，功能全但重。
   - **`Js`**：浏览器 Fetch API。
   - **`Darwin`**：iOS/macOS 的 NSURLSession。
2. **Android 选型**：
   - **纯 Android 项目**：有 OkHttp 遗留生态 → `OkHttp`；想彻底摆脱 OkHttp 依赖、追求跨平台一致 → `CIO`。
   - **KMP 项目**：Android 端也建议 `CIO`（与 iOS/Web 保持一致），除非 Android 端有特殊 OkHttp 依赖必须复用。
3. **注意**：`OkHttp` Engine 在 Android 上**不支持 WebSocket**（Ktor 的 WebSocket 需要 `CIO` 或 `Java` engine 之一；实际上 Ktor 的 WebSocket 在 `OkHttp` engine 上不被支持，这是常见的选型陷阱）。**如果需要 WebSocket，要选 `CIO`/`Java` Engine**。
4. **面试话术**：**"Engine 就是底层 HTTP 实现的适配层。Android 上一般用 CIO 或 OkHttp；需要 WebSocket 时注意 OkHttp Engine 不支持，得用 CIO。KMP 场景优先 CIO 保证跨平台行为一致。"**

---

**读者问：Ktor 和 Retrofit 在错误处理上的差异？**

**AI答：**

1. **Retrofit**：非 2xx → 抛 `HttpException`（或返回 `Response<T>` 由调用方判断）。
2. **Ktor**：
   - `expectSuccess = true`（默认）：非 2xx → 抛 `ResponseException` 的子类：
     - `ClientRequestException`（4xx）
     - `ServerResponseException`（5xx）
     - `RedirectResponseException`（3xx）
   - `expectSuccess = false`：不抛异常，返回 `HttpResponse` 由调用方看 `status`。
   - 底层 IO 异常仍是 `IOException`（`ConnectTimeoutException` 等）。
3. **写法差异**：
   ```kotlin
   // Ktor
   try {
       val user = client.get(url).body<User>()
   } catch (e: ClientRequestException) { /* 4xx */ }
     catch (e: ServerResponseException) { /* 5xx */ }
     catch (e: IOException) { /* 网络 */ }

   // Retrofit
   try {
       val user = api.getUser(id)
   } catch (e: HttpException) { /* 非 2xx */ }
     catch (e: IOException) { /* 网络 */ }
   ```
4. **共同点（都要做）**：**先 `catch (e: CancellationException) { throw e }`**，否则协程取消会被误判为网络失败。
5. **面试话术**：**"Retrofit 统一抛 HttpException，Ktor 按 4xx/5xx 细分异常类型，也可以关掉 expectSuccess 自己判断。两者都必须把 CancellationException 透传出去。"**

## 关联笔记

- [[OkHttp]]：Ktor 的 OkHttp Engine 底层实现
- [[Retrofit]]：声明式 HTTP 客户端，单端项目的常见选择
- [[../网络协议/HTTP与HTTPS.md|HTTP与HTTPS]]：HTTP/2、TLS 与证书固定
- [[../网络协议/RESTful]]：接口设计规范
- [[../异步方案/Kotlin协程]]：Ktor 的协程原生 API 基础
- [[../异步方案/RxJava]]：响应式方案与协程的对比
- [[../README]]：第 5 部分总览

## 参考资料

- Ktor 官方文档：https://ktor.io/docs/client-create-new-application.html
- Ktor 插件列表：https://ktor.io/docs/plugins-intro.html
- Ktor ContentNegotiation 文档：https://ktor.io/docs/server-serialization.html
- Kotlin Multiplatform 官方文档：https://kotlinlang.org/docs/multiplatform.html
- Ktor GitHub：https://github.com/ktorio/ktor
