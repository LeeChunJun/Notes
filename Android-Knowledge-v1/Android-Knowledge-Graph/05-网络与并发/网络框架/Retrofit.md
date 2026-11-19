---
tags: [android]
part: 第5部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[OkHttp]], [[../README]]"
---

# Retrofit

## 一句话定义

Retrofit 是 Square 出品的**声明式 HTTP 客户端**，它自己**不做任何网络通信**，而是把"一个接口方法"翻译成一个 OkHttp `Request`，再借助 OkHttp 发出去。核心价值是**用接口 + 注解描述 API，用代理 + 反射在运行时动态生成实现**。

## 核心原理

### 1. 整体构成

```text
┌──────────────────────────────────────────────────────────────┐
│  interface ApiService {                                       │
│      @GET("users/{id}") suspend fun getUser(@Path("id") id: Long): User  │
│  }                                                            │
└───────────────┬──────────────────────────────────────────────┘
                │ Retrofit.create(ApiService::class.java)
                │ → Proxy.newProxyInstance(...)
                ▼
┌──────────────────────────────────────────────────────────────┐
│  动态代理 InvocationHandler                                  │
│    1. 解析方法上的注解 → 得到 HTTP method + 相对 url           │
│    2. 解析参数注解 → 拼 query / path / body / header          │
│    3. 用 OkHttp Request.Builder 构造 Request                 │
│    4. 交给 OkHttp Call                                        │
│    5. 用 Converter 解析 ResponseBody → 返回类型               │
└───────────────┬──────────────────────────────────────────────┘
                ▼
        OkHttpClient → 真实网络请求
```

Retrofit 的四个扩展点：

| 扩展点 | 作用 | 常见实现 |
| --- | --- | --- |
| **Converter.Factory** | 响应体 ↔ 对象的转换 | `GsonConverterFactory`、`MoshiConverterFactory`、`kotlinx.serialization` 的 `asConverterFactory` |
| **CallAdapter.Factory** | 决定方法的返回值形态 | `suspend` 返回值、`Call<T>`、`RxJava3CallAdapterFactory` |
| **Interceptor / OkHttpClient** | 请求链路的横切能力 | 日志、鉴权、缓存、重试 |
| **Annotation / 自定义注解** | 扩展方法描述能力 | `@HeaderMap`、`@FieldMap`、`@Url` |

### 2. 核心注解速查

| 注解 | 作用位置 | 说明 |
| --- | --- | --- |
| `@GET` / `@POST` / `@PUT` / `@DELETE` / `@PATCH` / `@HEAD` | 方法 | HTTP 方法 + 相对路径（以 `/` 开头会**替换** baseUrl 的 path，否则**追加**） |
| `@Body` | 参数 | 把整个对象序列化成请求体（JSON / Form / Multipart） |
| `@Query` | 参数 | 拼到 URL query string，`@Query("k") value: String` |
| `@QueryMap` | 参数 | 批量 query，`Map<String, String>` |
| `@QueryName` | 参数 | **不编码**参数名（用于 `@Json` 风格的复杂 key） |
| `@Path` | 参数 | 替换 URL 中的 `{id}` 占位符，**自动 URL 编码** |
| `@Url` | 参数 | **直接指定完整 URL**，覆盖注解上的路径（常用于动态域名/下载） |
| `@Header` | 参数 | 单个 header |
| `@HeaderMap` | 参数 | 批量 header（`Map<String, String>`） |
| `@Field` / `@FieldMap` | 参数 | `application/x-www-form-urlencoded` 表单字段 |
| `@Part` / `@PartMap` | 参数 | `multipart/form-data` 分片 |
| `@FormUrlEncoded` | 方法 | 声明 `@Field` 生效 |
| `@Multipart` | 方法 | 声明 `@Part` 生效 |
| `@Streaming` | 方法 | **不把响应体全读进内存**，返回 `ResponseBody` 供流式处理 |

```text
@POST("users")
suspend fun create(@Body user: User): User
  → POST {baseUrl}/users   body = JSON(user)

@GET("users/{id}/posts")
suspend fun posts(@Path("id") id: Long, @Query("page") page: Int): List<Post>
  → GET {baseUrl}/users/42/posts?page=3

@GET
suspend fun download(@Url url: String): ResponseBody
  → GET {url}   （完全动态 URL）
```

### 3. 动态代理原理（面试高频）

这是 Retrofit 最核心的原理，必须能讲清楚：

```kotlin
// Retrofit.create() 的核心伪代码
public static <T> T create(Class<T> service) {
    return (T) Proxy.newProxyInstance(
        service.getClassLoader(),
        new Class<?>[]{service},
        new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                // 1. 处理 Object 的方法（toString/equals/hashCode）
                // 2. 解析 method 上的 HTTP 注解
                RequestFactory.parse(method, args, requestBuilder, ...)
                // 3. 构造 OkHttp Request
                okhttp3.Request request = requestBuilder.build();
                // 4. 创建 Call
                okhttp3.Call call = okHttpClient.newCall(request);
                // 5. 用 CallAdapter 决定返回什么
                //    - suspend 方法 → 返回 Call<Object>，由 Kotlin 协程挂起
                //    - 返回 Call<T> → 直接返回 call
                // 6. 用 Converter 解析响应
                return callAdapter.adapt(call);
            }
        }
    );
}
```

**关键点**：

1. `ApiService` 是一个**接口**，没有实现类。`Retrofit.create()` 用 `java.lang.reflect.Proxy` **动态生成实现**。
2. 每次调用接口方法，代理都会走 `InvocationHandler.invoke(proxy, method, args)`，在这里完成"注解 → Request"的翻译。
3. **运行时才解析注解**，所以方法名改了不影响功能，但也意味着**编译期无法校验 URL 拼接结果**（这是 Retrofit 相对 Ktor/手写 URL 的一个缺点）。

```text
调用者: api.getUser(42)
    │
    ▼  Proxy.invoke()
InvocationHandler.invoke(method = getUser, args = [42L])
    │
    ├─ 读 method.getAnnotation(GET.class) → "users/{id}"
    ├─ 读参数上的 @Path("id") → args[0] = 42
    ├─ urlBuilder.addPathSegments("users/42")
    ├─ new Request.Builder().url(url).get().build()
    ├─ okHttpClient.newCall(request) → Call
    └─ Converter<Unit, User>.convert(response.body) → User
```

> 面试话术：**"Retrofit 用 JDK 动态代理生成接口实现，`InvocationHandler` 在运行时读方法上的注解，把方法调用翻译成 OkHttp 的 Request，交给 OkHttp 执行，再用 Converter 把响应体转成目标类型。它自己不碰 socket。"**

### 4. Converter Factory 机制

`Converter.Factory` 负责"**响应体 → 对象**"和"**对象 → 请求体**"两个方向的转换。

```text
Converter.Factory
   ├── responseBodyConverter(type, annotations, retrofit) → Converter<ResponseBody, T>
   └── requestBodyConverter(type, parameterAnnotations, annotations, retrofit) → Converter<T, RequestBody>
```

**内置/常用的三种序列化方案对比**：

| 方案 | 依赖 | 性能 | 特点 |
| --- | --- | --- | --- |
| Gson | `converter-gson` | 中（反射） | 生态最广，`GsonConverterFactory.create()` |
| Moshi | `converter-moshi` | 较高（Kotlin codegen / 反射） | Kotlin 一等公民，官方推荐，可生成适配器 |
| kotlinx.serialization | `kotlinx-serialization-json` + `retrofit2-kotlinx-serialization-converter` | 高（编译期生成） | 需要 `@Serializable`，编译期校验，**推荐新项目** |

```kotlin
// ① Gson
val gson = GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").create()
Retrofit.Builder()
    .baseUrl(BASE_URL)
    .addConverterFactory(GsonConverterFactory.create(gson))
    .build()

// ② Moshi
val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
Retrofit.Builder()
    .baseUrl(BASE_URL)
    .addConverterFactory(MoshiConverterFactory.create(moshi))
    .build()

// ③ kotlinx.serialization
val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
Retrofit.Builder()
    .baseUrl(BASE_URL)
    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
    .build()
```

### 5. CallAdapter：suspend 函数是怎么实现的

Retrofit 2.6+ 支持 `suspend` 函数，**看起来像"接口方法直接返回数据"**，但它并不是在代理里 `runBlocking` 阻塞等待——那样会浪费线程。

真实机制：

```text
suspend fun getUser(id: Long): User
    │
    │ Kotlin 编译器把 suspend 函数编译成：
    │   Object getUser(long id, Continuation<? super User> $completion)
    ▼
Proxy.invoke(method, args = [42L, continuation])
    │
    ├─ CallAdapter 识别到方法返回类型是 Continuation（说明是 suspend 方法）
    ├─ 创建一个 OkHttp Call
    ├─ 通过 Call.enqueue(Callback) 发起异步请求
    ├─ 拿到 Response 后，用 Converter 解析成 User
    └─ continuation.resumeWith(Result.success(user))   ← 恢复协程
```

关键点：

1. Kotlin 的 `suspend` 函数在字节码层面**多了一个 `Continuation` 参数**，这就是 Retrofit 能"识别 suspend 方法"的原因。
2. `SuspendForBodyCallAdapter` 返回的 `Call<T>` 的 `enqueue` 里，最终调用 `continuation.resumeWith(...)`。
3. **协程挂起期间不占用线程**——这正是协程相对 `Call.execute()` + `runBlocking` 的优势。

### 6. 返回 Response<T> 还是直接返回 T

```kotlin
interface ApiService {
    // ① 直接返回 T：非 2xx 时抛 HttpException
    @GET("users/{id}") suspend fun getUser(@Path("id") id: Long): User

    // ② 返回 Response<T>：非 2xx 时不抛异常，交给调用方判断
    @GET("users/{id}") suspend fun getUser(@Path("id") id: Long): Response<User>
}
```

| 返回类型 | 2xx | 4xx/5xx | 适用场景 |
| --- | --- | --- | --- |
| `T` | 返回解析后的对象 | **抛 `HttpException`**（`code()` / `message()` 可读，body 需手动取） | 绝大多数业务接口，配合统一错误处理 |
| `Response<T>` | `isSuccessful=true` | `isSuccessful=false`，`errorBody()` 可读错误详情 | 需要精细区分错误码、上传下载等 |
| `Unit` | 返回 Unit | 抛异常 | DELETE 等无返回体的接口 |
| `ResponseBody` + `@Streaming` | 流式 | 抛异常 | 大文件下载 |

### 7. 文件上传与下载

```kotlin
interface FileApi {
    /** 单文件上传：@Part + RequestBody */
    @Multipart
    @POST("upload")
    suspend fun uploadFile(
        @Part file: MultipartBody.Part
    ): UploadResult

    /** 表单 + 多文件混合上传 */
    @Multipart
    @POST("upload/multi")
    suspend fun uploadFiles(
        @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>,
        @Part files: List<MultipartBody.Part>
    ): UploadResult

    /** 流式下载：不把整个文件读进内存 */
    @Streaming
    @GET
    suspend fun download(@Url url: String): ResponseBody
}
```

```kotlin
// 组装 Part
val body = file.asRequestBody("image/jpeg".toMediaType())
val part = MultipartBody.Part.createFormData("file", file.name, body)
val result = api.uploadFile(part)

// 下载并落盘
suspend fun downloadToFile(url: String, dest: File) = withContext(Dispatchers.IO) {
    api.download(url).use { body ->   // ResponseBody 是 Closeable
        val total = body.contentLength()
        body.source().use { source ->
            dest.outputStream().use { out ->
                val buffer = okio.Buffer()
                var read: Long
                while (source.read(buffer, 8192).also { read = it } != -1L) {
                    out.write(buffer.readByteArray(read.toInt()))
                }
            }
        }
    }
}
```

### 8. 统一错误处理

Retrofit 原生对非 2xx 抛 `HttpException`，但业务通常需要 `code` / `message` / 业务错误码统一处理。三种常见方案：

```kotlin
// 方案 A：返回 Response<T>，在 Repository 层统一转换
suspend fun <T> apiCall(block: suspend () -> Response<T>): ApiResult<T> = try {
    val response = block()
    if (response.isSuccessful) {
        ApiResult.Success(response.body()!!)
    } else {
        val err = response.errorBody()?.string()
        ApiResult.Error(parseBusinessError(err))
    }
} catch (e: CancellationException) {
    throw e   // 协程取消必须原样抛出！
} catch (e: HttpException) {
    ApiResult.Error(...)
} catch (e: IOException) {
    ApiResult.Error(Error.Network)
}

// 方案 B：自定义 CallAdapter / Result CallAdapter
// 方案 C：拦截器统一处理业务错误码（把业务 code != 0 转成异常）
```

> **必须注意**：捕获 `Exception` 时要**先 rethrow `CancellationException`**，否则协程取消会被当成网络失败吞掉（结构化并发的"取消传播"会断裂）。详见 [[../异步方案/Kotlin协程]]。

## 代码示例

### 1. 完整的 Retrofit 客户端配置

```kotlin
object NetworkModule {

    private val json = Json {
        ignoreUnknownKeys = true      // 服务端多返回字段时不崩
        explicitNulls = false         // 不输出 null 字段，省流量
        coerceInputValues = true      // null 字段用默认值填充
    }

    /** 全局单例：Retrofit 内部持有 OkHttpClient，重复创建会浪费连接池 */
    val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.example.com/v1/")
            .client(OkHttpClientHolder.client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .addCallAdapterFactory(CoroutineCallAdapterFactory.create())   // 支持 suspend
            .build()
    }

    val api: ApiService by lazy { retrofit.create(ApiService::class.java) }
}

object OkHttpClientHolder {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(HeaderInterceptor())
            .addInterceptor(AuthInterceptor())
            .retryOnConnectionFailure(true)
            .build()
    }
}
```

### 2. 接口定义（体现设计规范）

```kotlin
interface ApiService {

    // ── 认证 ──
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @POST("auth/refresh")
    suspend fun refreshToken(@Body body: RefreshRequest): RefreshResponse

    // ── 用户 ──
    @GET("users/{id}")
    suspend fun getUser(@Path("id") userId: Long): User

    @GET("users")
    suspend fun getUsers(
        @Query("page") page: Int = 1,
        @Query("size") pageSize: Int = 20,
        @Query("keyword") keyword: String? = null,
        @QueryMap filters: Map<String, String> = emptyMap()
    ): PagedResponse<User>

    @POST("users")
    suspend fun createUser(@Body user: User): User

    @PUT("users/{id}")
    suspend fun updateUser(@Path("id") userId: Long, @Body user: User): User

    @PATCH("users/{id}")
    suspend fun patchUser(
        @Path("id") userId: Long,
        @Body patch: Map<String, @JvmSuppressWildcards Any>
    ): User

    @DELETE("users/{id}")
    suspend fun deleteUser(@Path("id") userId: Long): Response<Unit>

    // ── 文件 ──
    @Multipart
    @POST("files")
    suspend fun upload(@Part file: MultipartBody.Part): FileMeta

    @Streaming
    @GET
    suspend fun download(@Url url: String): ResponseBody

    // ── 需要精细控制错误时返回 Response<T> ──
    @GET("orders/{orderNo}")
    suspend fun getOrder(@Path("orderNo") orderNo: String): Response<Order>
}
```

### 3. 统一 Result 封装与 Repository

```kotlin
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Failure(
        val code: Int,
        val message: String,
        val cause: Throwable? = null,
        val httpCode: Int = 0
    ) : ApiResult<Nothing>
}

/** 统一的调用封装：处理 HTTP 错误、业务错误码、网络异常 */
suspend fun <T> apiCall(block: suspend () -> Response<T>): ApiResult<T> {
    return try {
        val response = block()
        if (response.isSuccessful) {
            @Suppress("UNCHECKED_CAST")
            val body = response.body() ?: return ApiResult.Failure(-1, "empty body")
            // 业务错误码检查：约定 code == 0 为成功
            val biz = (body as? BizResponse<*>)?.code ?: 0
            if (biz != 0) {
                ApiResult.Failure(biz, (body as BizResponse<*>).message.orEmpty())
            } else {
                ApiResult.Success(body)
            }
        } else {
            val raw = response.errorBody()?.string()
            val biz = parseBizError(raw)
            ApiResult.Failure(biz?.code ?: response.code(), biz?.message ?: "HTTP ${response.code()}", httpCode = response.code())
        }
    } catch (e: CancellationException) {
        throw e  // ★ 关键：协程取消必须透传，不能被吞掉
    } catch (e: HttpException) {
        ApiResult.Failure(e.code(), e.message(), e)
    } catch (e: IOException) {
        ApiResult.Failure(-1, "网络异常，请检查网络连接", e)
    } catch (e: Exception) {
        ApiResult.Failure(-1, e.message ?: "未知错误", e)
    }
}
```

```kotlin
class UserRepository(private val api: ApiService) {

    /** 返回 Flow，便于组合缓存策略与 UI 状态 */
    fun getUserFlow(userId: Long): Flow<ApiResult<User>> = flow {
        emit(apiCall { api.getUser(userId) })
    }.flowOn(Dispatchers.IO)

    suspend fun refreshTokenIfNeeded(): Boolean = try {
        val resp = api.refreshToken(RefreshRequest(TokenStore.refreshToken))
        TokenStore.saveAccessToken(resp.accessToken)
        true
    } catch (e: Exception) {
        false
    }
}
```

### 4. 401 自动刷新 Token（拦截器 + Authenticator）

```kotlin
/** OkHttp Authenticator：只在 401 时被调用，可安全执行同步刷新 */
class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val refreshApi: AuthApi
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        // 已有重试过则不再重试，防止无限循环
        if (response.priorResponse != null) return null

        synchronized(this) {
            val currentToken = tokenStore.accessToken
            // 另一个线程已经刷新过了，直接用新 token 重试
            val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")
            if (currentToken != null && currentToken != requestToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }
            // 同步刷新（注意：Authenticator 在 OkHttp 线程上调用，允许阻塞 IO）
            val newToken = runCatching { refreshApi.refreshBlocking(tokenStore.refreshToken) }
                .getOrNull() ?: return null
            tokenStore.saveAccessToken(newToken)
            return response.request.newBuilder()
                .header("Authorization", "Bearer $newToken")
                .build()
        }
    }
}
```

> `Authenticator` 与 `Interceptor` 的区别：`Authenticator` **只在服务端返回 401 时**触发，且可以用 `response.priorResponse` 判断是否已重试；`Interceptor` 对所有请求生效。

## 常见问题 / 面试要点

**读者问：Retrofit 的动态代理原理是什么？为什么接口不需要写实现类？**

**AI答：**

1. **一句话**：Retrofit 用 JDK 的 `java.lang.reflect.Proxy` 在运行时为接口生成实现类，生成类的 `invoke` 被转发到一个 `InvocationHandler`；这个 Handler 在运行时读取方法上的注解，把"方法调用"翻译成 OkHttp 的 `Request`。
2. **逐步拆解**：
   - `Retrofit.create(ApiService::class.java)` → `Proxy.newProxyInstance(classLoader, arrayOf(ApiService), handler)`。
   - 代理对象被调用 `api.getUser(42)` → 进入 `handler.invoke(proxy, method, args)`。
   - Handler 里：
     1. 判断是否 `Object` 的方法（`toString`/`equals`/`hashCode`）→ 特殊处理。
     2. 解析 `method` 上的 `@GET/@POST/...` 和路径。
     3. 遍历 `method.parameters`，按参数上的 `@Path/@Query/@Body/@Header/@Url` 分类处理，拼 URL、构造 body、设置 header。
     4. `okHttpClient.newCall(request)` 得到 `Call`。
     5. 通过 `CallAdapterFactory` 决定返回形态：`Call<T>` 直接返回；`suspend` 方法返回一个内部 `Call`，由 `SuspendForBodyCallAdapter` 在 `enqueue` 回调里 `continuation.resumeWith(...)`。
     6. 响应体由 `Converter` 转成目标类型。
3. **为什么用动态代理而不是编译期生成代码**：动态代理免去了注解处理器（APT）的复杂度与构建耗时，代价是**运行时反射开销**和**编译期无法校验 URL 合法性**。
4. **追问：注解是什么时候被解析的？**——**每次调用方法时**都会解析（`RequestFactory.parse`），不是启动时一次性解析。所以热路径上有一定反射开销，但相对于网络 IO 可忽略。
5. **面试话术**：**"Retrofit = JDK 动态代理 + 注解解析 + OkHttp + Converter/CallAdapter。它本身不发请求，只做翻译。"**

---

**读者问：Retrofit 怎么实现统一错误处理？业务错误码和 HTTP 错误码怎么区分？**

**AI答：**

1. **三层错误要分清**：
   | 层级 | 例子 | 处理位置 |
   | --- | --- | --- |
   | **网络层错误** | 无网络、DNS 失败、超时 | 捕获 `IOException` |
   | **HTTP 协议层错误** | 401 / 404 / 500 | `HttpException` 或 `Response.isSuccessful` |
   | **业务层错误** | HTTP 200 但 `code != 0` | 统一封装层解析 body |
2. **推荐做法**：接口统一返回 `Response<T>`，在 Repository/数据层的 `apiCall { }` 封装里统一转换为 `ApiResult`。
3. **四种可选实现**：
   - **方案 A（最常用）**：接口返回 `Response<T>`，数据层 `try/catch` + `when` 判断 `isSuccessful` + 解析业务码。
   - **方案 B**：接口直接返回 `T`，用**自定义 `CallAdapter`** 把 `HttpException` 转成 `Result`（Retrofit 2.9+ 官方提供了 `ResultCallAdapterFactory` 示例）。
   - **方案 C**：**拦截器**统一处理——在 Application Interceptor 里读 body，若是业务错误码则抛自定义异常。但拦截器里读 body 需要缓存 body，容易踩坑，**不推荐**。
   - **方案 D**：密封类 + 泛型 `ApiResult<T>`（推荐给新项目），把成功/失败建模成互斥类型，编译器强制你处理所有分支。
4. **关键坑**：
   - **`CancellationException` 必须先 rethrow**，否则协程取消被当成业务失败吞掉（这是 Retrofit + 协程最常见的线上 bug 之一）。
   - `HttpException` 的 `response()` 里才有 `errorBody()`，很多写法直接 `e.message()` 拿到的只是 "HTTP 500 Client Error"，拿不到业务错误信息。
   - 解析错误 body 时要**先 `string()` 一次**再关闭，`errorBody()` 只能读一次。
5. **面试话术**：**"我让接口返回 `Response<T>`，在数据层用一个 `apiCall` 包装成 `ApiResult`，里面分三层处理：IO 异常、HTTP 状态码、业务 code；并且单独 catch CancellationException 透传。"**

---

**读者问：Retrofit 的 `suspend` 函数是怎么实现的？为什么不用 `runBlocking`？**

**AI答：**

1. **原理**：Kotlin 编译器会把 `suspend fun getUser(id: Long): User` 编译成 `Object getUser(long id, Continuation<? super User> $completion)`。**多出来的 `Continuation` 参数就是"可挂起"的标记**。
2. **Retrofit 的做法**：
   - `SuspendForBodyCallAdapterFactory` 检测到方法有 `Continuation` 参数 → 走 suspend 分支。
   - 返回一个内部 `Call<Object>`，它的 `enqueue` 里：发起 OkHttp 请求 → 拿到响应 → 用 Converter 解析 → **`continuation.resumeWith(Result.success(result))`**（失败时 `resumeWithException`）。
3. **为什么不能用 `runBlocking`**：
   - `runBlocking` 会**阻塞当前线程**直到结果返回。如果在主线程调用 → ANR；如果在 IO 线程调用 → 白白占住一个线程，失去了协程"挂起不占线程"的核心优势。
   - 协程的价值就在于**挂起时释放线程**，让少量线程承载大量并发任务。Retrofit 用 `enqueue` + `Continuation.resume` 完整保留了这一点。
4. **对比表**：
   | 方案 | 线程占用 | 主线程安全 | 推荐 |
   | --- | --- | --- | --- |
   | `suspend fun`（Retrofit 2.6+） | 挂起时 0 线程 | ✅ | ✅ 首选 |
   | `Call<T>` + `enqueue` | 0（回调在 OkHttp 池） | ✅ | 需手动切线程 |
   | `Call<T>.execute()` | 阻塞当前线程 | ❌ | 仅子线程/测试 |
   | `runBlocking { }` | 阻塞当前线程 | ❌ | ❌ 不要用 |
5. **补充**：`suspend` 方法**仍然可以在非协程环境调用**，但会返回一个需要 `Continuation` 的中间值；实践中永远在 `suspend fun` 里调用。

---

**读者问：`@Body`、`@Field`、`@Part`、`@Query` 有什么区别？分别什么时候用？**

**AI答：**

1. **按 Content-Type 分类**：
   | 注解 | 产生的 Content-Type | 数据位置 |
   | --- | --- | --- |
   | `@Body` | `application/json`（由 Converter 决定） | 请求体 |
   | `@Field` / `@FieldMap` | `application/x-www-form-urlencoded` | 请求体（表单编码） |
   | `@Part` / `@PartMap` | `multipart/form-data` | 请求体（分片） |
   | `@Query` / `@QueryMap` | 无 | URL query string |
   | `@Path` | 无 | URL 路径段 |
   | `@Header` / `@HeaderMap` | 无 | 请求头 |
2. **`@Body` 的限制**：一个方法**只能有一个 `@Body`**；且 `@Body` 的对象会被 Converter 完整序列化，**大文件不要用 `@Body`**。
3. **`@Part` 的限制**：可以多个；但**每个 `@Part` 的类型必须是 `RequestBody` / `MultipartBody.Part` / `File`（需 `@JvmSuppressWildcards`）**。
4. **multipart 与 urlencoded 的选择**：
   - **urlencoded**（`@Field`）：只传简单键值对、**体积小**、不需要文件。
   - **multipart**（`@Part`）：需要**文件或二进制**；注意每个 part 都有独立的 header，**比 urlencoded 浪费约 200~300 字节/part**，part 很多时体积明显膨胀。
5. **常见坑**：
   - `@Field` 需要方法上加 `@FormUrlEncoded`，否则报错。
   - `@Part` 需要方法上加 `@Multipart`。
   - `@Query` 的 `null` 值默认**不会出现在 query 中**（Retrofit 会跳过 null），这正好可用于可选参数；但**空字符串 `""` 会出现**，语义不同，要区分请显式处理。
   - `@Path` 会自动 URL 编码，若路径参数本身含 `/`，需要用 `@EncodedPath` 或手动 `encodedPath`。
6. **面试话术**：**"`@Query` 进 URL，`@Path` 替换路径占位符，`@Body` 整个 JSON 序列化进请求体，`@Field` 是表单键值对，`@Part` 是 multipart 分片（传文件用）。"**

---

**读者问：Retrofit 的 baseUrl 和 `@Url` 有什么区别？相对路径以 `/` 开头会怎样？**

**AI答：**

1. **baseUrl**：Retrofit 构造时设定，**必须以 `/` 结尾**（否则启动就抛 `IllegalArgumentException: baseUrl must end in /`）。
2. **路径拼接规则**：
   - 注解路径**不以 `/` 开头** → 追加到 baseUrl 的 path 之后：`baseUrl = https://a.com/v1/` + `users` → `https://a.com/v1/users`。
   - 注解路径**以 `/` 开头** → **替换** baseUrl 的整个 path：`baseUrl = https://a.com/v1/` + `/users` → `https://a.com/users`（`v1` 被丢掉）。这是一个**非常常见的坑**。
3. **`@Url`**：
   - 参数上加 `@Url` 后，该参数**完全覆盖**注解上的路径。
   - 支持**完整 URL**（`https://other.com/a/b`）→ 直接用它，**baseUrl 会被完全忽略**（这也是"换域名/多域名"的常用技巧）。
   - 也支持**相对 URL**（`/v2/users`）→ 相对于 baseUrl 的 host 解析。
   - **不能**同时有 `@Url` 参数和其他 `@Path/@Query` 参数（Retrofit 会报错）。
4. **面试话术**：**"baseUrl 结尾必须有斜杠；路径不以斜杠开头是追加，以斜杠开头是替换整个 path；`@Url` 参数能完全覆盖 URL，做多域名切换很方便。"**

---

**读者问：Retrofit 的 Converter 有哪些？Gson、Moshi、kotlinx.serialization 怎么选？**

**AI答：**

1. **Gson**：
   - 优点：生态最广，容错强（字段缺失不报错），不用注解也能用。
   - 缺点：**反射**，性能一般；**字段名策略不匹配时容易踩坑**（比如服务端用 `user_id`，需要 `FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES`）；对 Kotlin 的 `null` 默认值支持不佳。
2. **Moshi**：
   - 优点：Kotlin 一等公民，**有 codegen（KSP 注解处理器）**可去掉反射，性能好；`KotlinJsonAdapterFactory` 支持 `data class` 默认值与 nullable。
   - 缺点：需要 KSP 配置；未知字段默认报错（需 `JsonReader.Options.failOnUnknown = false` 或 `@Json(ignoreUnknownKeys = true)`，Kotlin 侧用 `@JsonClass(ignoreUnknownKeys = true)`）。
3. **kotlinx.serialization**：
   - 优点：**编译期生成序列化器**，性能最好；**编译期校验字段名/类型**（字段名写错编译不过）；支持 sealed class 多态、默认值天然支持。
   - 缺点：需要 `@Serializable` 注解与 Gradle 插件；反射式（非 codegen）模式性能一般。
4. **选型建议**：
   - **新项目 / Kotlin 优先** → `kotlinx.serialization`。
   - **需要极致 JSON 性能且已有 KSP** → Moshi codegen。
   - **老项目 / 复杂 JSON 兼容** → Gson。
5. **关键差异（面试加分）**：三者对 **`null` 处理**、**多态（sealed class）**、**未知字段**的默认行为不同；kotlinx.serialization 默认 `ignoreUnknownKeys=false`，**服务端加字段会直接崩** —— 上线前务必设 `ignoreUnknownKeys = true`。

---

**读者问：Retrofit 怎么实现拦截？和 OkHttp 拦截器是什么关系？**

**AI答：**

1. **Retrofit 本身没有拦截器机制**（Retrofit 2.x 没有 `addInterceptor`）。所有横切能力都通过**给 Retrofit 配置 OkHttpClient** 来实现。
2. **做法**：
   ```kotlin
   Retrofit.Builder()
       .client(OkHttpClient.Builder().addInterceptor(AuthInterceptor()).build())
       .build()
   ```
3. **Verticals（竖切）概念**（面试术语）：
   - **Vertical**：业务功能维度的切分，如"支付模块""登录模块"，用 **Retrofit 多实例 + 不同 baseUrl + 不同 Client** 实现。
   - **Horizontal（横向）**：技术能力维度的切分，如日志、鉴权、缓存，用 **OkHttp Interceptor** 实现。
4. **面试话术**：**"Retrofit 做竖切（多实例隔离业务模块），OkHttp 拦截器做横切（日志/鉴权/缓存）。Retrofit 本身没有拦截器，横切能力全部下沉到 OkHttpClient。"**

---

**读者问：Retrofit 和 OkHttp 到底什么关系？为什么不直接用 OkHttp？**

**AI答：**

1. **职责分层**：
   - **OkHttp**：HTTP 引擎。连接管理、拦截器、缓存、WebSocket、HTTP/2。
   - **Retrofit**：**接口描述层**。把方法 + 注解翻译成 Request，把 Response 翻译成对象。
   - **Retrofit 底层必然用 OkHttp**（`Retrofit.Builder().client(...)` 传的就是 `OkHttpClient`）。
2. **不用 Retrofit 的代价**：
   - 每个请求都要手写 `Request.Builder()`、拼 URL、手动解析 JSON、手动处理错误码。
   - 重复代码多、易漏错误处理、难以统一拦截。
3. **Retrofit 带来的能力**：
   - 接口即文档（IDE 可跳转、可读）。
   - 统一错误处理与响应模型。
   - 借助 Converter + CallAdapter 高度可扩展（换序列化库、换返回 RxJava、换成自定义）。
4. **不用 Retrofit 的场景**：
   - 只有一个或两个简单请求。
   - 需要 WebSocket 或特殊协议。
   - **KMP 多平台**项目（Retrofit 没有官方 KMP 支持 → 用 Ktor）。
5. **面试话术**：**"OkHttp 是发动机，Retrofit 是自动挡变速箱。Retrofit 不发请求，只做方法到 HTTP 请求的翻译。"**

---

**读者问：Retrofit 支持文件上传下载吗？大文件下载怎么避免 OOM？**

**AI答：**

1. **上传**：
   - 用 `@Multipart` + `@Part`，每个文件一个 `MultipartBody.Part`。
   - 需要进度回调 → 自定义 `RequestBody` 包装（覆写 `writeTo` 统计字节数），或用 `ProgressRequestBody` 类库。
   - **注意**：`@Body` 传大文件会**一次性读进内存序列化**，一定不要用。
2. **下载**：
   - **必须加 `@Streaming`**，否则 Retrofit 默认会把整个响应体读进内存（`BufferedSource.readString`），100MB 文件直接 OOM。
   - 返回 `ResponseBody`，用 `body.source()` 流式写文件。
   - `@Streaming` + `ResponseBody` 组合是最稳的写法。
3. **断点续传**：
   - 用 `Range: bytes=<已下载大小>-` 请求头。
   - 服务端支持 `206 Partial Content` 时可续传；否则需自己分片。
4. **大文件上传的分片**：
   - 客户端切片 → 每片单独请求 → 服务端合并（需要服务端配合）。
   - 客户端做 **md5 校验**防止合并出错。
5. **面试话术**：**"`@Streaming` 不加会 OOM，因为 Retrofit 会把 body 全读进内存。进度靠自定义 RequestBody 统计 writeTo 的写入字节数。"**

## 关联笔记

- [[OkHttp]]：Retrofit 的底层引擎，拦截器与连接池
- [[Ktor]]：Kotlin 多平台网络框架，KMP 场景的替代方案
- [[../网络协议/RESTful]]：接口设计规范，Retrofit 注解的语义来源
- [[../网络协议/HTTP与HTTPS.md|HTTP与HTTPS]]：HTTP 语义与状态码规范
- [[../异步方案/Kotlin协程]]：suspend 函数与协程取消的处理
- [[../异步方案/RxJava]]：Retrofit 的 RxJava CallAdapter
- [[../README]]：第 5 部分总览

## 参考资料

- Retrofit 官方文档：https://square.github.io/retrofit/
- Retrofit 源码（`Retrofit.create`、`RequestFactory`、`SuspendForBodyCallAdapter`）：https://github.com/square/retrofit
- Retrofit 官方示例 "Kotlin 协程支持"：https://square.github.io/retrofit/docs/kotlin-suspend/
- Jake Wharton "Omitting Retrofit from now on"（Ktor 时代的选择）：https://square.github.io/retrofit/2.x/retrofit-2.9-release.html
- 《Android 高级编程》第 4 版 · Retrofit 与 OkHttp 章节
