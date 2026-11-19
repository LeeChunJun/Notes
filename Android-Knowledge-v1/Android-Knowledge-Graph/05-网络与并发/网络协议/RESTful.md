---
tags: [android]
part: 第5部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[../README]]"
---

# RESTful

## 一句话定义

REST（Representational State Transfer，**表述性状态转移**）是 Roy Fielding 在 2000 年博士论文中提出的一套**架构约束**，不是协议也不是标准。它约束"资源如何被表示、状态如何被转移"，目标是让系统**可伸缩、无状态、可缓存、可演进**。

## 核心原理

### 1. 六大约束

| # | 约束 | 含义 | 违反的后果 |
| --- | --- | --- | --- |
| 1 | **客户端-服务器（Client-Server）** | 分离 UI 与数据存储，双方可独立演进 | 前端后端耦合 |
| 2 | **无状态（Stateless）** | **每次请求包含处理该请求所需的全部信息**，服务端**不存会话** | 无法水平扩展、负载均衡困难 |
| 3 | **可缓存（Cacheable）** | 响应要**显式标记**自己能否被缓存 | 缓存失效、性能下降 |
| 4 | **统一接口（Uniform Interface）** | 组件间交互的**标准化方式**，是 REST 的核心 | 客户端要理解每种接口的方言 |
| 5 | **分层系统（Layered System）** | 每一层只知道自己下一层的接口 | 无法加网关/CDN/负载均衡 |
| 6 | **按需代码（Code-On-Demand，可选）** | 服务器可下发客户端执行的代码来扩展功能 | —— |

**统一接口的四个子约束**（这是 REST 最实质的部分）：

1. **标识资源（Identification of Resources）**：每个资源有 **URI**。
2. **通过表示操作资源（Manipulation through Representations）**：客户端拿到资源的**表示（Representation）**（通常是 JSON），通过修改表示来修改资源状态。
3. **自描述消息（Self-Descriptive Messages）**：报文自带**媒体类型**（`Content-Type`），客户端知道怎么解析。
4. **HATEOAS（Hypermedia as the Engine of Application State）**：响应里带**下一步可用的链接**（`Link` 头或 `_links` 字段），客户端靠**链接**驱动流程，而不是硬编码 URL。

> 面试点：REST 里的一切都是**资源**（名词），不是**动作**（动词）。动作要建模成资源 + 方法。这是 REST 与"RPC 风格 API"最本质的区别。

### 2. 资源命名与 URL 设计

```text
✅ 好的命名（名词复数、层级清晰）
GET    /users                获取用户列表
GET    /users/{userId}       获取单个用户
POST   /users                创建用户
PUT    /users/{userId}       整体替换用户
PATCH  /users/{userId}       局部更新用户
DELETE /users/{userId}       删除用户
GET    /users/{userId}/orders 获取该用户的订单

❌ 常见错误（动词式 RPC）
GET    /getUserList
POST  /createUser
POST  /deleteUserById
GET    /user/query?name=xx   ← 复杂查询应该用 GET + query 参数
```

**URL 设计规范**：

| 规范 | 说明 |
| --- | --- |
| **名词复数** | `/users` 而不是 `/user`（集合）；单个资源也用复数路径：`/users/42` |
| **层级用路径** | `/users/42/orders` 表示从属关系 |
| **小写 + 连字符** | `/user-profiles`（避免下划线，URL 中下划线在某些代理里不可见） |
| **无尾斜杠** | 统一 `/users` 而非 `/users/`，避免服务器返回 301 |
| **过滤用 query** | `/users?role=admin&status=active` |
| **动作建模成资源** | `/users/42/follow`（POST），而不是 `POST /followUser` |
| **批量操作** | `DELETE /users?ids=1,2,3` 或 `POST /users/batch-delete` |
| **版本号位置** | 见下文"版本化" |

### 3. HTTP 方法语义

| 方法 | 语义 | **幂等** | 安全 | 有请求体 | 典型响应 |
| --- | --- | --- | --- | --- | --- |
| **GET** | 获取资源表示 | ✅ | ✅ | 通常无 | 200 + body |
| **HEAD** | 只取响应头 | ✅ | ✅ | 无 | 200 无 body |
| **POST** | 创建资源 / 非幂等动作 | ❌ | ❌ | 有 | **201 Created** + Location |
| **PUT** | **整体替换**目标资源 | ✅ | ❌ | 有 | 200 / 204 |
| **PATCH** | **局部修改** | ⚠️ 不保证 | ❌ | 有 | 200 / 204 |
| **DELETE** | 删除资源 | ✅ | ❌ | 可有 | 204 No Content |
| **OPTIONS** | 查询支持的方法/CORS 预检 | ✅ | ✅ | 无 | 200 + Allow |

**幂等性的精确定义**：**同一个请求执行 1 次和执行 N 次（N>1）**对服务器状态的**最终影响相同**（不是"响应相同"）。

**为什么 GET/PUT/DELETE 幂等而 POST 不幂等**：

- `POST /orders` 每调一次就**创建一笔新订单** → 不幂等。
- `PUT /users/42` 每次都是把用户数据**设置**成同样的内容 → 幂等。
- `DELETE /users/42` 第一次删除，之后再删还是"用户不存在" → 幂等。

**幂等为什么重要**：

1. **网络超时后的安全重试**：客户端超时了，它不知道服务端执行没执行。如果接口幂等，重试是安全的；不幂等就可能**重复下单**。
2. **HTTP 缓存**：幂等的响应可以被安全缓存和重放。
3. **CDN / 网关重试**、**熔断降级重试**都依赖幂等。

**幂等怎么保证**：

- **PUT / DELETE**：天然幂等（但要小心 `DELETE /orders?all=true` 这种"批量删"仍然幂等，可是 `POST /pay` 不幂等）。
- **POST 幂等化三招**：
  1. **幂等键（Idempotency Key）**：客户端生成唯一 key（如 UUID），放请求头 `Idempotency-Key: xxx`；服务端记录该 key 的处理结果，重复请求直接返回首次结果。
  2. **业务唯一键**：如"用订单号 `orderNo` 唯一确定一笔订单"，服务端按唯一键去重。
  3. **服务端状态机**：把"创建"改成"推进状态"——`POST /orders/{orderNo}/pay` 幂等（第二次返回已支付），`POST /orders/{orderNo}/refund` 幂等。

### 4. 状态码设计规范

```text
2xx 成功
  200 OK              查询/更新成功，有 body
  201 Created         创建成功（必须带 Location 头指向新资源）
  202 Accepted        已接受，异步处理中（如提交导出任务）
  204 No Content      成功但无 body（DELETE 首选）

3xx 重定向
  301 永久移动     302 临时移动     304 未修改（缓存）

4xx 客户端错误
  400 Bad Request        请求格式错误/参数校验失败
  401 Unauthorized       未认证（需要登录/token）
  403 Forbidden          已认证但无权限
  404 Not Found          资源不存在
  405 Method Not Allowed 方法不支持（必须带 Allow 头）
  409 Conflict           状态冲突（如唯一键冲突、版本冲突）
  410 Gone               曾经存在，现被永久删除（语义比 404 更精确）
  413 Payload Too Large  请求体过大
  415 Unsupported Media Type  媒体类型不支持
  422 Unprocessable Entity  语法正确但语义无法处理（部分团队用 400）
  429 Too Many Requests   限流（必须带 Retry-After 头）

5xx 服务端错误
  500 Internal Server Error  未预期的异常
  501 Not Implemented        功能未实现
  502 Bad Gateway            网关收到无效上游响应
  503 Service Unavailable    过载/维护（带 Retry-After）
  504 Gateway Timeout        网关等待上游超时
```

**常见争议点**：

| 场景 | 争议 | 建议 |
| --- | --- | --- |
| **401 vs 403** | 401 是"没登录"，403 是"登录了但没权限" | 严格区分。**401 必须带 `WWW-Authenticate` 头** |
| **400 vs 422** | 语法错误 vs 语义错误 | 团队内统一即可；国内很多团队统一用 400 |
| **创建成功用 200 还是 201** | REST 规范说 201 | **用 201 + `Location`**，更规范 |
| **DELETE 返回什么** | 200 + body 还是 204 | **204 No Content**（或 200 带删除结果），不要返回被删对象 |
| **业务错误码放哪** | HTTP 状态码 vs body | **HTTP 状态码表达传输/协议语义，业务码放 body**（如 `{"code": 40001, "msg": "余额不足"}`） |

> **重要原则**：不要把业务错误"塞进" HTTP 状态码。HTTP 状态码是**协议层**语义，400 表示"请求格式有问题"，而"余额不足"是**业务规则**拒绝，应该用 **HTTP 200 + body 里的业务码**，或用 409/422 这类语义匹配的码。团队内需统一。

### 5. 分页、排序、过滤与字段稀疏

**分页（Pagination）**：

| 方案 | 说明 | 优缺点 |
| --- | --- | --- |
| **Offset 分页**（`?page=1&size=20`） | 服务端算 `LIMIT offset, size` | 简单；**深分页慢**（要扫过前 offset 行）；**数据变动时会出现重复/遗漏** |
| **Cursor 分页**（`?cursor=xxx`） | 服务端返回不透明 cursor | **深分页快**、稳定；但**不能跳到任意页** |
| **Keyset 分页**（`?after_id=42`） | 用上一页最后一条的排序键 | 性能最好，适合无限滚动；需固定排序键 |

```kotlin
// Offset 分页响应
@Serializable
data class PagedResponse<T>(
    val items: List<T>,
    val page: Int,
    val pageSize: Int,
    val total: Long,          // 总数（需要 COUNT 查询，可能慢）
    val hasMore: Boolean
)

// Cursor 分页响应
@Serializable
data class CursorPage<T>(
    val items: List<T>,
    val nextCursor: String?,   // null 表示没有更多
    val hasMore: Boolean
)
```

**排序**：`?sort=created_at` / `?sort=-created_at,name`（多字段）。
**过滤**：`?status=active&role=admin&created_after=2024-01-01`。
**字段稀疏（Sparse Fieldsets）**：`GET /users/42?fields=id,name,avatar` —— 只返回需要的字段，**减少带宽**。注意：**这不是"部分更新"**（那是 PATCH）。

> 常见混淆：**"字段稀疏（sparse fieldsets）"** 出现在 **JSON:API** 规范里，指的是**响应中只包含请求的字段**；而 **PATCH 的 `application/json-patch+json`** 里的 `null` 字段表示**删除该字段**。两者的 `null` 语义不同，是面试常见陷阱。

### 6. HATEOAS 与版本化

**HATEOAS（超媒体驱动）**：响应中带上"下一步可以做什么"的链接，客户端**按链接导航**而不是硬编码 URL。

```json
{
  "id": 42,
  "name": "Ada",
  "_links": {
    "self":    { "href": "/v1/users/42" },
    "orders":  { "href": "/v1/users/42/orders" },
    "avatar":  { "href": "/v1/users/42/avatar" },
    "deactivate": { "href": "/v1/users/42/deactivate", "method": "DELETE" }
  }
}
```

- **优点**：服务端可以自由调整 URL 结构，客户端不受影响；**天然支持"当前用户能否删除该资源"这类权限表达**。
- **缺点**：响应体变大；客户端/调试工具更复杂；**实际落地少**（多数 App 后端不实现）。
- **HATEOAS 是 REST 的约束之一，但实现与否取决于团队**——面试要点明"不是必须完全实现"。

**版本化（Versioning）**：

| 方案 | 示例 | 优缺点 |
| --- | --- | --- |
| **URL 版本** | `/v1/users`、`/v2/users` | **最直观**、易调试、易灰度（网关按路径路由）；缺点是"同一个资源多个 URL"，污染 URL 空间 |
| **Header 版本** | `Accept: application/vnd.app.v2+json` | URL 干净（符合 REST 严格派）；但**调试困难**（要改 header），缓存要带 header 区分 |
| **查询参数版本** | `/users?version=2` | 不推荐，语义混乱 |
| **请求体版本** | `{"version": 2, ...}` | 不推荐 |
| **不版本化 + 兼容性** | 靠**只加字段不改字段**的向前兼容策略 | 最优雅但要求客户端与服务端**协同发布**，成本高 |

**实践建议**：
- **App 与服务端联合发版困难**（App 无法强制升级）→ 必须**支持旧版本**，用 **URL 版本 + 网关路由 + 灰度**最省心。
- **字段兼容策略**：**只新增可选字段，不删除/重命名/改语义**。删除字段要等所有客户端升级后再删（通常要等 1~2 个大版本）。
- **版本号应该是"大版本"而不是"每次改接口"**：小改动靠兼容性解决。

### 7. Android 端的 API 设计

**统一响应包装**：

```kotlin
@Serializable
data class ApiResponse<T>(
    val code: Int,          // 业务码：0 = 成功
    val message: String,
    val data: T? = null,
    val requestId: String? = null,   // 便于排障对账
    val timestamp: Long = System.currentTimeMillis()
)
```

**错误码体系设计**：

```text
0        成功
400xx    客户端参数错误    40001 参数缺失  40002 参数格式错误
401xx    认证失败        40101 token 过期  40102 token 无效
403xx    权限不足        40301 无操作权限
404xx    资源不存在      40401 用户不存在
409xx    状态冲突        40901 重复提交（幂等命中）
429xx    限流            42901 请求过于频繁
500xx    服务端错误      50001 内部异常    50002 依赖服务超时
503xx    服务不可用      50301 熔断中
```

**设计原则**：
- **错误码要能定位问题**：能区分"参数错"和"服务端错"，客户端能据此决定"提示用户"还是"重试"。
- **带 `requestId`**：客户端上报日志时带上，服务端按 ID 查链路，**这是线上排障的关键**。
- **message 面向用户**（可直接展示），**code 面向开发**。
- **不要泄露内部异常栈**：`message` 里不要出现 `java.lang.NullPointerException at com.xxx`。

### 8. GraphQL vs REST

| 维度 | REST | GraphQL |
| --- | --- | --- |
| **端点数量** | 多个端点（`/users`、`/orders`…） | **单一端点** `/graphql` |
| **数据获取** | **过取（over-fetching）** 或 **欠取（under-fetching）** | **按需字段**，一次请求拿齐 |
| **请求次数** | 页面要调 N 个接口 → **N+1 问题** | 1 次 |
| **版本演进** | 需要版本号 | **字段废弃机制**（`@deprecated`）天然兼容 |
| **缓存** | HTTP 缓存天然可用 | **难缓存**（POST 查询难被 CDN/HTTP 缓存） |
| **复杂度** | 低，调试简单（curl 即可） | **高**：schema、resolver、N+1、缓存策略都要设计 |
| **文件上传** | 简单（multipart） | 规范复杂 |
| **实时** | 用 WebSocket 另做 | **内置 Subscription** |
| **服务端复杂度** | 低 | **高**（字段级权限、查询复杂度限制、深度限制） |

**GraphQL 的核心问题**：
- **N+1 查询**：一个 `posts` 字段背后要查 N 次数据库 → 用 **DataLoader** 批量合并。
- **查询深度攻击**：恶意构造深层嵌套查询打垮 DB → 限制**查询深度**和**复杂度**。
- **HTTP 缓存失效**：GraphQL 几乎都用 POST（虽然规范支持 GET 查询），CDN 缓存难做 → 需要 **Apollo Persisted Queries / CDN 键控**。

**选型建议**：
- **数据关系复杂、字段需求多变、客户端团队多** → GraphQL。
- **接口简单、客户端少、要极致缓存性能、要易调试** → REST。
- **混合**：对外 REST（开放 API），对内 GraphQL（BFF）。

### 9. RESTful 的常见"伪 REST"

```text
❌ /api/getUserList?userId=42&page=1     ← 动词 + 传输层参数混杂
❌ POST /api/deleteUser                   ← 动词式 RPC
❌ POST /api/updateUserStatus             ← 动作没有建模为资源
❌ GET  /api/listAllUsersWithOrdersAnd...  ← 一个接口干太多事
❌ 响应里塞数据库字段（create_time、is_deleted、update_by）  ← 泄漏实现细节
❌ 用 200 携带错误信息 {"code": 500, "msg": "..."}  ← 至少用 4xx/5xx
```

**"用 POST 避免 GET 被缓存"** 也是常见误解——GET 缓存是**特性**，应该通过 `Cache-Control` 控制，而不是换方法。

## 代码示例

### 1. 资源命名清晰的 API 接口定义

```kotlin
@Serializable
data class User(
    val id: Long,
    val name: String,
    val email: String,
    val avatarUrl: String? = null,
    val createdAt: String
)

@Serializable
data class Order(
    val orderNo: String,        // 业务唯一键，天然幂等
    val amount: Long,
    val status: String
)

interface RestfulApi {

    // ── 集合与单资源 ──
    @GET("users")
    suspend fun listUsers(
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
        @Query("sort") sort: String = "-created_at",
        @Query("role") role: String? = null,
        @Query("fields") fields: String? = null      // 字段稀疏
    ): PagedResponse<User>

    @GET("users/{id}")
    suspend fun getUser(
        @Path("id") id: Long,
        @Query("fields") fields: String? = null
    ): User

    @POST("users")
    suspend fun createUser(@Body user: CreateUserRequest): Response<CreatedResource>

    @PUT("users/{id}")
    suspend fun replaceUser(@Path("id") id: Long, @Body user: ReplaceUserRequest): User

    @PATCH("users/{id}")
    suspend fun patchUser(@Path("id") id: Long, @Body patch: JsonPatch): User

    @DELETE("users/{id}")
    suspend fun deleteUser(@Path("id") id: Long): Response<Unit>

    // ── 从属资源 ──
    @GET("users/{id}/orders")
    suspend fun listUserOrders(
        @Path("id") id: Long,
        @Query("cursor") cursor: String? = null
    ): CursorPage<Order>

    // ── 动作建模为子资源（保证幂等） ──
    @POST("users/{id}/deactivation")
    suspend fun deactivateUser(@Path("id") id: Long): Response<Unit>

    @POST("orders/{orderNo}/payment")
    suspend fun payOrder(
        @Path("orderNo") orderNo: String,
        @Header("Idempotency-Key") idempotencyKey: String   // 幂等键
    ): Order

    @DELETE("orders/{orderNo}/payment")
    suspend fun refundOrder(@Path("orderNo") orderNo: String): Order
}
```

### 2. 统一响应包装与错误码

```kotlin
@Serializable
data class ApiResponse<T>(
    val code: Int = 0,
    val message: String = "",
    val data: T? = null,
    val requestId: String? = null
)

@Serializable
data class PagedResponse<T>(
    val items: List<T> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = 20,
    val total: Long = 0,
    val hasMore: Boolean = false
)

@Serializable
data class CursorPage<T>(
    val items: List<T> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false
)

@Serializable
data class CreatedResource(
    val id: Long,
    val createdAt: String
)

/** 业务错误码常量 */
object ErrorCode {
    const val OK = 0
    const val PARAM_MISSING = 40001
    const val TOKEN_EXPIRED = 40101
    const val NO_PERMISSION = 40301
    const val USER_NOT_FOUND = 40401
    const val DUPLICATE_SUBMIT = 40901
    const val RATE_LIMITED = 42901
    const val SERVER_ERROR = 50001
    const val CIRCUIT_BROKEN = 50301
}

/** 把 HTTP 状态码 + 业务码统一映射为领域错误 */
sealed class ApiError(val code: Int, val message: String) : Exception(message) {
    class Network(message: String) : ApiError(-1, message)
    class Timeout(message: String) : ApiError(-2, message)
    class Http(val status: Int, message: String) : ApiError(status, message)
    class Business(val bizCode: Int, message: String) : ApiError(bizCode, message)
    class Unknown(message: String) : ApiError(-99, message)
}
```

### 3. 幂等下单：客户端生成幂等键

```kotlin
class OrderRepository(private val api: RestfulApi) {

    /**
     * 下单：使用业务唯一键 orderNo + 幂等键。
     * 网络超时后重试是安全的：服务端按 idempotencyKey 去重。
     */
    suspend fun submitOrder(orderNo: String, request: OrderRequest): Result<Order> =
        runCatching {
            api.payOrder(
                orderNo = orderNo,
                // ★ 同一个订单重试时必须复用同一个 key
                idempotencyKey = "pay-$orderNo"
            )
        }

    /** 客户端本地生成订单号：时间戳 + 随机 + 设备标识，降低碰撞概率 */
    fun generateOrderNo(): String {
        val ts = System.currentTimeMillis()
        val rand = Random.nextInt(100_000, 999_999)
        val device = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return "$ts-$rand-${device.hashCode().toString().takeLast(6)}"
    }
}
```

### 4. 客户端做 REST 缓存（复用 OkHttp 的 Cache）

```kotlin
val client = OkHttpClient.Builder()
    .cache(Cache(File(cacheDir, "http"), 20L * 1024 * 1024))
    .addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        // 给 GET 响应补充缓存策略
        response.newBuilder()
            .removeHeader("Pragma")
            .header("Cache-Control", "public, max-age=60")
            .build()
    }
    .build()
```

### 5. GraphQL 客户端示例（对比参考）

```kotlin
interface GraphQLApi {
    @POST("graphql")
    suspend fun query(@Body request: GraphQLRequest): GraphQLResponse
}

@Serializable
data class GraphQLRequest(
    val query: String,
    val variables: Map<String, JsonElement> = emptyMap(),
    val operationName: String? = null
)

/**
 * 一次请求拿齐用户 + 订单，避免 REST 的 N+1 次请求
 */
val query = """
    query UserWithOrders(${'$'}id: ID!) {
      user(id: ${'$'}id) {
        id
        name
        email
        orders(first: 10) {
          nodes { orderNo amount status }
        }
      }
    }
""".trimIndent()
```

### 6. HATEOAS 响应模型（可选）

```kotlin
@Serializable
data class Link(
    val href: String,
    val method: String = "GET"
)

@Serializable
data class HypermediaUser(
    val id: Long,
    val name: String,
    @SerialName("_links") val links: Map<String, Link> = emptyMap()
)
```

## 常见问题 / 面试要点

**读者问：RESTful 的"无状态"是什么意思？**

**AI答：**

1. **定义**：**无状态（Stateless）** 指**服务端不保存任何客户端会话上下文**。每一个请求都是**自包含**的——服务端处理这个请求所需的全部信息，都在请求本身（URL + Header + Body）里。
2. **"不存会话"具体指什么**：
   - 服务端**不存** `HttpSession`（Java 的 `session.setAttribute`）。
   - 认证信息由客户端**每次携带**（Cookie 里的 sessionId，或 `Authorization: Bearer <token>`）。
   - 请求处理完之后，服务端**不保留**任何与该客户端相关的状态。
3. **好处（这是面试的重点）**：
   - **水平扩展**：任意服务器节点都能处理任意请求 → 可以随便加机器 behind 负载均衡。**有状态则必须做会话共享（Session Sticky / Redis），成本高。**
   - **无状态服务易容灾**：节点挂了不丢会话。
   - **可缓存**：响应可以独立缓存（因为结果只取决于请求）。
   - **可重放/可重试**：同���请求重发是安全的（幂等配合）。
4. **代价**：
   - **每次请求都要带认证信息** → 流量更大。
   - **Token 变大**（JWT 把状态放进 Payload）。
   - **无法做"服务端强制下线"**：除非引入黑名单/版本号。
5. **⚠️ 关键澄清（很多人答错）**：**"无状态"不等于"整个系统无状态"**。服务端仍然需要数据库来存储资源数据，数据库当然是有状态的。REST 说的无状态是**"通信层（会话状态）无状态"**，即**HTTP 请求-响应之间不保留上下文**。
6. **面试话术**：**"REST 的无状态是指服务端不保存会话上下文，每个请求自带全部信息，所以能任意水平扩展。代价是每次都要带 token，且服务端无法主动让已发的 token 失效。"**

---

**读者问：什么是幂等？怎么保证接口幂等？**

**AI答：**

1. **定义**：同一个请求执行 **1 次**和执行 **N 次（N>1）**，对服务器**最终状态的影响相同**。（注意：是"最终状态"，不是"响应内容相同"。）
2. **方法的天然幂等性**：
   - **GET** ✅ —— 只读。
   - **PUT** ✅ —— "替换"语义，重复 PUT 只是设置成同样的值。
   - **DELETE** ✅ —— 第一次删除成功，之后再删资源已不存在，最终状态一致。
   - **POST** ❌ —— 每次都创建新资源（重复下单、重复扣款）。
   - **PATCH** ⚠️ —— 不保证（如果 patch 内容是"数量 +1"就不幂等；是"数量设为 5"就幂等）。
   - **HEAD / OPTIONS** ✅
3. **为什么必须幂等**：
   - **超时重试**：客户端超时了不知道服务端执行没执行 → 不幂等的接口重试就可能重复下单/重复支付。
   - **网关/熔断器重试**、**CDN 重放**、**用户手抖连点两下**。
   - **HTTP 缓存**要求安全。
4. **让 POST 幂等的三种手段**：
   - **① 幂等键（Idempotency-Key）**：客户端生成 UUID 放请求头；服务端用 `(key, userId)` 做唯一索引，重复请求**直接返回首次结果**（业界支付接口的标准做法，如 Stripe）。
   - **② 业务唯一键**：如 `orderNo` 唯一确定一笔订单，服务端按唯一键 upsert。
   - **③ 状态机建模**：把"创建"变成"推进状态"——`POST /orders/{orderNo}/payment`（重复调用返回"已支付"）、`POST /orders/{orderNo}/refund`（重复调用返回"已退款"）。
5. **Android 侧的实践**：
   - 下单/支付时，**用业务唯一键（订单号）而不是随机 UUID** 做幂等键，保证**同一笔订单的所有重试用同一个 key**。
   - 订单号格式：`时间戳 + 随机数 + 设备标识`，降低碰撞。
   - **客户端禁用重复点击**只是 UI 层的保护，**不能替代服务端幂等**。
6. **面试话术**：**"幂等就是执行 N 次和执行 1 次最终状态一样。GET/PUT/DELETE 天然幂等，POST 不幂等。要让 POST 幂等，用 Idempotency-Key 头 + 服务端去重表，或者用业务唯一键，或者把动作建模成幂等的状态机接口。"**

---

**读者问：接口版本怎么设计？URL 版本和 Header 版本怎么选？**

**AI答：**

1. **三种主要方案**：
   | 方案 | 示例 | 特点 |
   | --- | --- | --- |
   | **URL 版本** | `/v1/users`、`/v2/users` | **最直观**，curl/浏览器可直接调试，网关易按路径路由，**灰度友好** |
   | **Header 版本** | `Accept: application/vnd.app.v2+json` | URL 干净，符合 REST 严格派；但**调试必须改 header**，缓存要按 header 区分 |
   | **查询参数** | `/users?version=2` | 不推荐，语义混乱 |
2. **App 场景的关键约束**：**App 无法强制用户升级**！老版本 App 会长期存在（有的能活好几年），所以：
   - **必须支持旧版本服务端**，直到数据量显示老版本活跃度可忽略。
   - **网关层做版本路由**：`/v1/*` → 老服务，`/v2/*` → 新服务。
   - **字段兼容策略**（比版本号更重要）：**只新增可选字段，不删除、不重命名、不改变已有字段语义**。
   - **删除字段的正确流程**：先标记废弃 → 等客户端全量升级（至少一个大版本 + 灰度数据验证）→ 再删除。
3. **版本号粒度**：**不要"每次改接口就加版本"**，那是灾难（v1.0.1、v1.0.2…）。版本对应**不兼容变更**；兼容变更靠新增字段。
4. **Header 版本的坑**：
   - `Accept` 头是 HTTP 语义里的"内容协商"，塞版本号会**干扰 CDN 的缓存键**和浏览器的缓存行为。
   - **必须有 `Vary: Accept`**，否则 CDN 会把 v1 响应返给 v2 请求。
5. **面试话术**：**"App 端我倾向 URL 版本 + 网关路由，因为调试友好、灰度友好。真正重要的是字段兼容策略：只加不改，删除要等老客户端死透。Header 版本更 REST 但 CDN 缓存要配 Vary。"**

---

**读者问：RESTful 的六大约束是什么？哪些可以灵活处理？**

**AI答：**

1. **六约束**：
   - **客户端-服务器**：分离关注点，双方独立演进。
   - **无状态**：每个请求自包含，服务端不存会话 → 可水平扩展。
   - **可缓存**：响应显式声明可否缓存 → 性能与一致性可平衡。
   - **统一接口**：URI 标识资源、通过表示操作资源、自描述消息、HATEOAS。**这是 REST 的核心和精髓**。
   - **分层系统**：各层只依赖相邻层 → 可插入网关/CDN/负载均衡/鉴权。
   - **按需代码**（可选）：服务端下发代码扩展客户端。
2. **统一接口的四个子点（这是最常被追问的）**：
   - 资源用 **URI** 标识。
   - 通过资源的**表示**（Representation）来操作它。
   - 报文**自描述**（`Content-Type` 告诉客户端怎么解析）。
   - **HATEOAS**：响应带链接，客户端靠链接导航。
3. **实际落地时的"打折"**：
   - **HATEOAS 几乎没人完整实现**（响应体变大、调试复杂）。**能说出这一点是加分项**。
   - **按需代码**基本无人用（安全风险）。
   - **可缓存**常被忽视（很多接口该设 `Cache-Control` 却不设）。
   - **无状态**在有 Session 的后台管理系统里会打折（但 App API 应当无状态）。
4. **REST vs RPC**：
   - **RPC**（"把远程函数调用当 HTTP"）：`POST /api/getUser`、`POST /api/getOrderList` —— 传输是 HTTP，语义是函数调用。**在互联网开放 API 上不推荐**（耦合强、扩展差、无法利用 HTTP 缓存与状态码）。
   - **REST**：一切皆资源，动词用 HTTP 方法表达。
   - **注意 gRPC**：gRPC 走 HTTP/2，用 protobuf，是彻底的 RPC —— **不适合公网开放 API**（调试需要 grpcurl，浏览器/防火墙不友好），但**内部服务间通信很合适**。
5. **面试话术**：**"六大约束里最核心的是统一接口。实际落地中 HATEOAS 和按需代码基本被放弃，其余四条必须遵守。"**

---

**读者问：REST 和 GraphQL 怎么选？**

**AI答：**

1. **核心差异**：
   - **REST**：多端点、请求-响应、**可能过取也可能欠取**、HTTP 缓存天然可用、调试简单。
   - **GraphQL**：单端点、**按需字段**、一次请求拿齐、**版本演进靠字段废弃**、**缓存困难**、服务端复杂度高。
2. **GraphQL 解决的问题**：
   - **Over-fetching**：REST 列表接口返回 20 个字段，App 只用 3 个 → 浪费流量（移动端尤甚）。
   - **Under-fetching**：一个页面要调 5 个 REST 接口 → 5 次 RTT。
   - **N+1 请求**：App 发 5 个请求，服务端又各查多次 DB。
3. **GraphQL 的代价（必须讲，否则显得片面）**：
   - **N+1 查询** → 需要 **DataLoader** 批量合并。
   - **查询深度/复杂度攻击** → 需要限制深度和复杂度。
   - **HTTP 缓存失效** → 几乎必须用 POST → CDN 缓存难做，需要 Persisted Queries。
   - **字段级权限控制复杂**。
   - **文件上传规范繁琐**。
   - **调试链路变长**（一个 GraphQL 请求内部触发 N 个数据源）。
4. **选型建议**：
   - **数据关系复杂、字段需求多变、客户端团队多/多端**（比如同一个后台给 Web + iOS + Android）→ **GraphQL**。
   - **接口简单、客户端少、强缓存需求、追求可调试性** → **REST**。
   - **混合模式（现在的主流）**：**对外 REST**（开放 API、SDK 友好），**对内 GraphQL BFF**（聚合多个后端服务），或者 **REST 网关 + GraphQL 内部**。
5. **面试话术**：**"GraphQL 解决 over-fetching 和多请求往返，但引入 N+1、缓存困难和复杂度问题。我的选择标准是：多端且字段需求多变选 GraphQL，否则 REST。生产上常见的是混合。"**

---

**读者问：REST 接口的状态码应该怎么用？业务错误码怎么放？**

**AI答：**

1. **两层错误体系**：
   - **HTTP 状态码**：表达**协议/传输层**语义（请求格式错、无权限、资源不存在、服务端崩了）。
   - **业务错误码**：放在 body 里（`{"code": 40001, "message": "余额不足"}`），表达**业务规则**拒绝。
2. **常见争议与建议**：
   - **401 vs 403**：401 = **没认证**（没登录/token 无效，带 `WWW-Authenticate`）；403 = **认证了但没权限**。**不要混用**。
   - **400 vs 422**：400 = 语法/格式错；422 = 语法对但语义无法处理。**团队统一即可**，国内多数团队统一 400 + body 里细分。
   - **创建成功**：用 **201 + `Location` 头**（更规范）；`202` 用于异步任务。
   - **DELETE**：用 **204 No Content**。
   - **限流**：**429 + `Retry-After` 头**。
   - **服务不可用**：**503 + `Retry-After`**（客户端应退避重试）。
3. **业务错误码不要塞进 HTTP 状态码**：
   - ❌ `HTTP 200 {"code": 500, "msg": "余额不足"}` —— **HTTP 200 意味着"传输成功"，业务失败用 4xx/5xx 会与框架的拦截器逻辑冲突**（很多拦截器看到非 2xx 就直接抛异常丢 body）。
   - ❌ `HTTP 500 {"code": 40001, "msg": "参数缺失"}` —— 语义完全错，监控会把它统计成"服务端故障"。
   - ✅ **HTTP 400/409/422 + body 里带细分业务码**。
   - ✅ 或者 **HTTP 200 + body 业务码**，但要在团队规范里明确"200 只代表传输成功"。**关键是全团队统一，不要两种混用。**
4. **错误码要能指导客户端行为**：
   - `40101 token 过期` → 客户端**自动刷新 token 并重放**。
   - `42901 限流` → 客户端**退避重试**。
   - `50301 熔断中` → 客户端**降级到本地缓存**并提示。
   - `40001 参数缺失` → **不重试**，提示用户。
   - **不同错误码决定客户端是否重试，这是错误码设计的核心价值。**
5. **安全红线**：**不要把异常栈、SQL、内部类名返回给客户端**（`message` 只放可展示文案，详细信息记服务端日志，用 `requestId` 关联）。

---

**读者问：客户端做分页、缓存、字段稀疏要注意什么？**

**AI答：**

1. **分页方式选择**：
   - **Offset（`page`/`size`）**：适合**总数已知、需要跳页**的场景（后台管理）。**深分页慢**（`LIMIT 100000, 20` 要扫 10 万行）。
   - **Cursor（游标）**：适合**无限滚动**。稳定（不受数据变动影响）、深翻快，但**不能跳到第 N 页**。游标应该是**不透明字符串**（服务端可以随时改内部实现）。
   - **Keyset（`after_id`）**：性能最好，要求有**唯一且有序**的排序键。
   - **数据变动时的重复/遗漏**：Offset 分页在翻页过程中有新数据插入会导致**重复或遗漏** → 无限滚动场景**强烈建议用 Cursor**。
2. **客户端缓存**：
   - **用 OkHttp 的 `Cache`**（磁盘缓存 + `Cache-Control`）自动处理，**优先用强缓存（max-age）**，配合 `ETag` 做协商缓存。
   - **"网络优先"策略**：拦截器里加 `Cache-Control: no-cache` → 每次都回源校验（304 流量很小）。
   - **"离线优先"策略**：**不强制 no-cache**，有缓存直接用（`max-age` 未过期时完全不发请求），无网时返回缓存。
   - **本地数据库兜底**：Room 存列表数据，接口失败时展示数据库内容 + "数据可能不是最新"。
3. **字段稀疏（sparse fieldsets）**：
   - `GET /users/42?fields=id,name,avatar` → 只返回指定字段。
   - **⚠️ 与 JSON Patch 的 `null` 语义冲突**：稀疏字段集里**没返回的字段 = 不存在**；而 `application/json-patch+json` 里 `null` = **删除该字段**。混淆会导致客户端 bug。
   - **客户端要能容忍字段缺失**：所有 DTO 字段都**给默认值**（`val name: String = ""`），否则服务端少返回一个字段客户端就崩。
4. **排序稳定性**：
   - 排序字段**必须唯一**（如 `-created_at, id`），否则同值记录的顺序不确定，翻页时会出现重复/遗漏。
5. **面试话术**：**"无限滚动用 cursor 分页，Offset 分页在数据变动时会重复或遗漏。字段稀疏要注意和 JSON Patch 的 null 语义区分，客户端 DTO 字段一定要有默认值。"**

---

**读者问：RESTful 和"前后端分离"是什么关系？Android App 算不算 REST 客户端？**

**AI答：**

1. **关系**：
   - **REST 是一种架构风格（约束集合）**，**前后端分离是一种工程组织方式**。
   - **前后端分离不必然是 REST**：后端也可以用 RPC 风格（`POST /api/getUser`）供前端调用——**那不叫 RESTful**，但依然是"前后端分离"。
   - **RESTful 要求**：资源用 URI 标识、用 HTTP 方法表达动作、无状态、可缓存、统一接口。
2. **Android App 天然是 REST 客户端**：
   - App 作为 client，不持有会话状态（无状态约束天然满足），只消费服务端暴露的资源。
   - **HATEOAS 的价值在 App 端尤其明显**：服务端可以通过响应告诉 App"当前用户能否删除该订单"（`Link` 里带 `method`），App 只需判断链接是否存在，不用自己写一堆角色判断逻辑。
3. **App 端的特殊约束**（REST 设计时要考虑）：
   - **流量成本**：移动网络昂贵 → 字段稀疏、分页大小、响应压缩（gzip/br）都要考虑。
   - **版本不能强推**：必须向后兼容（见版本化）。
   - **弱网**：需要幂等、缓存、超时与重试策略。
   - **安全**：Token 存储加密（`EncryptedSharedPreferences` / Keystore），敏感接口用 HTTPS + 证书固定。
4. **一个反例**：很多 App 的接口是 `POST /api/getUserList` —— **这是"披着 HTTP 外衣的 RPC"**，不是 RESTful。问题：
   - 无法利用 HTTP 缓存。
   - 状态码全部退化成 200，客户端要靠 body 里的 code 判断，**HTTP 语义完全浪费**。
   - 语义不清：抓包时看不出这个接口做了什么。
5. **面试话术**：**"前后端分离是工程组织，RESTful 是架构约束，两者正交但常一起出现。App 是典型的无状态 REST 客户端。很多 App 接口其实是 POST 一把梭的伪 REST，浪费了 HTTP 缓存和状态码语义。"**

## 关联笔记

- [[../网络协议/HTTP与HTTPS.md|HTTP与HTTPS]]：HTTP 方法语义、状态码、缓存头详解
- [[../网络框架/Retrofit]]：REST 接口在 Retrofit 中的注解映射
- [[../网络框架/OkHttp]]：HTTP 缓存、连接复用与压缩
- [[../网络框架/Ktor]]：KMP 场景下的 API 定义方式
- [[../网络协议/WebSocket]]：非 REST 的实时通信方案
- [[../README]]：第 5 部分总览

## 参考资料

- Roy Fielding 博士论文《Architectural Styles and the Design of Network-based Software Architectures》：https://www.ics.uci.edu/~fielding/pubs/dissertation/top.htm
- REST 约束速查（RESTful API 书籍作者）：https://restfulapi.net/
- Richardson Maturity Model：https://martinfowler.com/articles/richardsonMaturityModel.html
- MDN HTTP 方法参考：https://developer.mozilla.org/zh-CN/docs/Web/HTTP/Methods
- Richardson 的 API 组合与版本控制文章：https://martinfowler.com/articles/evolve-apis.html
