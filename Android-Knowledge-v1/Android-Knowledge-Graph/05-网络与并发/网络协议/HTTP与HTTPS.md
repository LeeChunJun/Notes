---
tags: [android]
part: 第5部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[../README]]"
---

# HTTP与HTTPS

## 一句话定义

HTTP 是**应用层**的请求-响应协议：无状态、文本化、基于 TCP。HTTPS 则是 **HTTP 跑在 TLS 之上**，用**非对称加密做身份认证（证书链）+ 对称加密做数据传输（会话密钥）**，从而实现**机密性、完整性、身份可验证**三大安全目标。

## 核心原理

### 1. HTTP 报文结构

**请求报文**：

```text
GET /v1/users/42?active=true HTTP/1.1        ← 请求行 (Request Line)
Host: api.example.com                          ← 请求头 (Headers)
Accept: application/json
Authorization: Bearer eyJhbGci...
Content-Type: application/json
Content-Length: 58
Connection: keep-alive

{"name":"Ada","age":36}                         ← 请求体 (Body)
```

- **请求行** = `方法 + 路径 + 协议版本`。
- **请求头** = 键值对，以 `\r\n` 分隔，以空行结束。
- **请求体** = 可选，GET 通常没有。

**响应报文**：

```text
HTTP/1.1 200 OK                                ← 状态行 (Status Line)
Content-Type: application/json; charset=utf-8  ← 响应头
Content-Length: 58
Cache-Control: max-age=60
ETag: "v1-abc"
Set-Cookie: sid=xxx; Secure; HttpOnly

{"id":42,"name":"Ada","age":36}                ← 响应体
```

### 2. 常用状态码

| 类别 | 码值 | 含义 |
| --- | --- | --- |
| **1xx** | 100 Continue / 101 Switching Protocols | 信息性。`101` 是 **WebSocket 升级**的响应 |
| **2xx** | 200 OK / 201 Created / 202 Accepted / 204 No Content | 成功。`204` 表示**成功但无响应体**（DELETE 常用） |
| **3xx** | 301 / 302 / 304 / 307 / 308 | 重定向 / 缓存 |
| **4xx** | 400 / 401 / 403 / 404 / 405 / 409 / 413 / 429 | **客户端错误**（你错了） |
| **5xx** | 500 / 502 / 503 / 504 | **服务端错误**（它错了） |

**301 vs 302 vs 307 vs 308**（高频追问）：

| 码 | 语义 | 方法是否改变 | 缓存 | 典型场景 |
| --- | --- | --- | --- | --- |
| **301** | Moved Permanently | **会**改成 GET（历史行为不规范） | **永久缓存** | 域名永久迁移、HTTP → HTTPS |
| **302** | Found (临时) | **会**改成 GET | 临时 | 登录后跳转、临时维护页 |
| **303** | See Other | **强制改成 GET** | 临时 | 提交表单后跳到结果页（POST → GET） |
| **304** | Not Modified | 无响应体 | **协商缓存命中** | 资源未变，用本地缓存 |
| **307** | Temporary Redirect | **不改变**方法 | 临时 | **POST 表单重定向必须用 307** |
| **308** | Permanent Redirect | **不改变**方法 | 永久 | 301 的"方法不变"版本 |

> 面试话术：**"301 是永久且历史上会把 POST 变 GET；302 是临时；307/308 保留原方法。302 和 307 的关键差别是 POST 是否被改成 GET，POST 场景必须用 307，否则会丢请求体。"**

### 3. HTTP/1.1 的关键特性

```text
HTTP/0.9  纯文本，无头
HTTP/1.0  每次请求新建 TCP 连接，无默认 keep-alive
HTTP/1.1  默认 keep-alive（持久连接）、分块传输、Host 头必需、管线化(pipelining)
HTTP/2    二进制分帧 + 多路复用 + HPACK + 流优先级 + 服务器推送
HTTP/3    QUIC(UDP) + TLS1.3 + 0-RTT
```

**队头阻塞（Head-of-Line Blocking）**：

- **HTTP/1.1 的问题**：一条 TCP 连接上，请求必须**按顺序**发送响应。即使前一个请求慢（要等数据库），后面的请求即使服务端已经处理完，也必须排在队尾等。
- **管线化（Pipelining）**：HTTP/1.1 支持连续发多个请求，但服务端仍要按序返回 → **队头阻塞完全没解决**，而且很多客户端/服务器默认关闭。所以实践中 HTTP/1.1 是"**一个连接上同一时刻只跑一个请求**"。
- **HTTP/2 的解决**：把响应拆成独立的 **Stream**，多个 Stream 在同一 TCP 连接上**并发**传输，**响应顺序可以乱**。但 TCP 层面仍有队头阻塞——**一个 TCP 包丢了，后面所有 Stream 的数据都得等重传**。
- **HTTP/3 的彻底解决**：基于 **UDP + QUIC**，每个 Stream 独立做可靠传输，丢一个包只影响那一个 Stream。**应用层解决了传输层队头阻塞**。

```text
HTTP/1.1（同一 TCP 连接）
  请求1 ─────────────────────► 响应1（慢）
  请求2 ─────────────────────► 响应2（快，但必须等响应1）★ 队头阻塞

HTTP/2（同一 TCP 连接，多路复用）
  Stream1 ─────────────────► 响应1（慢）
  Stream2 ─────────────────► 响应2（快，立刻返回）✔
```

### 4. HTTP/2 的四大特性

1. **二进制分帧（Binary Framing）**：HTTP/2 把报文拆成二进制 **frame**，每帧有固定 9 字节头（长度 24bit + 类型 8bit + flags 8bit + 32bit Stream ID）。
   - 帧类型：`DATA`（数据）、`HEADERS`（头部）、`RST_STREAM`（重置）、`SETTINGS`（连接参数）、`WINDOW_UPDATE`（流控）、`PING`（心跳）。
   - **流控**：连接级 + 流级，用 `WINDOW_UPDATE` 帧告知对端还能发多少字节，**避免慢客户端拖垮服务端**。
2. **多路复用（Multiplexing）**：多个 Stream 共享一条 TCP 连接，**并发**传输，不需要开多个连接。
3. **头部压缩（HPACK）**：HTTP/1.1 的 header 是纯文本且重复度高（`User-Agent`、`Cookie`、`Authorization` 每个请求都一样）。HPACK 用：
   - **静态表**：61 个常用 header 的固定索引（`:method: GET` 直接编码成 1 个字节）。
   - **动态表**：连接内维护的 header 键值表，新出现的 header 首次发送时入表，后续用索引。
   - **Huffman 编码**：对字符串做 Huffman 编码进一步压缩。
   - 效果：重复请求的 header 从几百字节降到**几字节**。
4. **流优先级与服务器推送**：可以给 Stream 设置优先级/依赖；服务器还可以在未收到请求时推送关联资源（`PUSH_PROMISE`）。后者在实际中因为复杂度高已基本被废弃。
5. **其他**：**强制 TLS（h2）**、**CONNECT 隧道**、**Cookie 拆分**（避免巨量 Cookie 触发 HPACK 动态表膨胀攻击）。

> 面试话术：**"HTTP/2 靠二进制分帧把报文变成 Stream，多路复用解决 HTTP/1.1 的应用层队头阻塞，HPACK 解决 header 冗余。但它跑在 TCP 上，丢包仍会阻塞所有 Stream，这是 HTTP/3 用 QUIC 解决的问题。"**

### 5. HTTP/3 与 QUIC

```text
┌────────────────────────────────────────────┐
│  HTTP/3          语义层（方法/状态码/header）│
├────────────────────────────────────────────┤
│  QUIC            传输层（基于 UDP）           │
│   ├─ 多路复用（无队头阻塞）                 │
│   ├─ 可靠性（按 Stream 独立重传）            │
│   ├─ 拥塞控制（可插拔）                     │
│   ├─ 连接迁移（Connection Migration）        │
│   └─ 0-RTT（0-RTT 握手数据）                │
├────────────────────────────────────────────┤
│  TLS 1.3        内嵌在 QUIC 中               │
├────────────────────────────────────────────┤
│  UDP             传输层                      │
└────────────────────────────────────────────┘
```

**QUIC 的三大改进**：

1. **消除传输层队头阻塞**：QUIC 在 UDP 之上自己实现可靠传输，**每个 Stream 独立重传**，丢一个包只影响那个 Stream。
2. **0-RTT / 1-RTT 握手**：
   - TCP + TLS 1.2：TCP 1-RTT + TLS 2-RTT = **3-RTT** 才可发数据。
   - TLS 1.3 单独：**1-RTT**。
   - QUIC 把 TLS 1.3 **内嵌**，且支持 **0-RTT**（客户端在握手未完成时就携带 Early Data 发送请求）→ **首次连接 1-RTT，复用连接 0-RTT**。
3. **连接迁移（Connection Migration）**：基于 **Connection ID** 而非四元组（IP + Port）标识连接。手机从 WiFi 切到 4G，IP 变了但连接不断，**HTTP 请求无需重试**（HTTP/2 + TCP 下必须重连）。

> Android 侧：Android 11 (API 30) 的 `NetworkStack` 改用 Cronet 后支持 HTTP/3；OkHttp 5 提供了 `Quic` 实验性支持（基于 `Cronet`）。

### 6. HTTPS = HTTP + TLS

**HTTPS 的三大安全目标**：

| 目标 | 靠什么实现 |
| --- | --- |
| **机密性（Confidentiality）** | 对称加密（AES-GCM / ChaCha20）加密数据 |
| **完整性（Integrity）** | AEAD 的认证标签 / HMAC |
| **身份认证（Authentication）** | 数字证书 + 证书链 + CA 根证书 |

**TLS 1.3 握手流程**（面试必背，画得出图才算过关）：

```text
Client                                                  Server
  │                                                       │
  │──── ClientHello ────────────────────────────────────►│  ①
  │     · TLS 版本 1.3                                  │
  │     · 随机数 (client random)                         │
  │     · 密码套件列表 (cipher suites)                    │
  │     · key_share（客户端的临时公钥 X25519）            │
  │                                                       │
  │◄─── ServerHello ─────────────────────────────────────│  ②
  │     · 选定密码套件                                   │
  │     · 随机数 (server random)                         │
  │     · key_share（服务端临时公钥）                     │
  │                                                       │
  │◄─── {EncryptedExtensions, {Certificate, CertificateVerify}, Finished} ─│ ③
  │     · 证书链（服务端证书 + 中间 CA）                   │
  │     · CertificateVerify（用私钥签名，证明"我持有私钥"）│
  │     · Finished（校验握手消息未被篡改）                │
  │                                                       │
  │  双方各自计算：                                       │
  │     master_secret = HKDF(ECDHE 共享密钥, ...)        │
  │     → 派生出 对称密钥（加密流量）+ MAC 密钥            │
  │                                                       │
  │──── Finished ───────────────────────────────────────►│ ④
  │                                                       │
  │══════ 加密的 Application Data ══════════════════════►│ ⑤
  │      （HTTP 请求以 AES-GCM 加密后传输）                │
```

**TLS 1.3 的关键简化**：

- 完整握手只需 **1-RTT**（TLS 1.2 需要 2-RTT）。
- **去掉了 RSA 密钥传输、静态 DH**，只保留 **(EC)DHE** 前向保密。
- 之前的 ChangeCipherSpec 消息被删除，密文从 ServerHello 之后就开始。
- 支持 **0-RTT**（会话恢复时）。

### 7. 数字证书链与信任模型

```text
根证书（Root CA，Android 系统内置）
   │ 签发
   ▼
中间证书（Intermediate CA）
   │ 签发
   ▼
叶子证书（Leaf Certificate / Server Certificate）
   - 域名（CN / SAN）
   - 有效期
   - 公钥
   - 签名（由中间 CA 的私钥签）
```

**信任链校验步骤**（客户端）：

1. 从服务端拿到叶子证书 + 中间证书链。
2. 在**本地信任库（TrustStore）**里找**根证书**（Android 系统预置了几百个 CA 根证书）。
3. 逐级验签：叶子证书的签名由中间 CA 的公钥验证，中间证书的签名由根 CA 的公钥验证。
4. 校验**域名匹配**（检查 SAN 中的 DNS 名是否匹配请求域名）。
5. 校验**有效期**（`notBefore` / `notAfter`）。
6. 校验**用途**（KeyUsage / ExtendedKeyUsage：服务器证书必须有 `serverAuth`）。

**为什么需要中间 CA**：根证书的私钥极其宝贵，一旦泄露整个信任体系崩塌。根 CA 只签发少量中间 CA，中间 CA 再签发叶子证书，**限制了泄露的爆炸半径**。

**Android 的信任链有个特殊点**：**用户安装的 CA 证书默认不被信任**（除非是系统 CA 或应用显式信任），这与桌面浏览器不同。

### 8. 证书固定（Certificate Pinning）

**目的**：即使设备信任了被入侵的 CA（或用户安装了恶意 CA），只要 App 里硬编码了**期望的公钥指纹**，攻击者的中间证书也无法冒充服务器。

```kotlin
val client = OkHttpClient.Builder()
    .certificatePinner(
        CertificatePinner.Builder()
            .add("api.example.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .add("api.example.com", "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=")  // 备用 pin（轮换期）
            .build()
    )
    .build()
```

- **pin 的对象**：证书的 **SPKI（Subject Public Key Info）** 的 SHA-256 指纹。**换证书但公钥不变 → pin 不失效**（这是 SPKI pin 而非整证书 pin 的优势）。
- **风险**：
  1. **pin 写错或证书轮换没提前更新 → 整个 App 完全无法联网**。这是最严重的后果。
  2. 需要**备份 pin（backup pin）**，且要提前一个证书周期发布新版本。
  3. **Google 早已不推荐 Certificate Pinning**（`publickey pinning`），原因就是运维风险大于收益；更推荐 **Network Security Config 的 `<pin-set>`**（带 `expiration` 属性，可自动过期），或依赖 **Certificate Transparency** + **网络层强制 HTTPS**。
- **Android 官方推荐写法**（`network_security_config.xml`）：
  ```xml
  <pin-set expiration="2026-12-31">
      <pin digest="SHA-256">Base64编码的SPKI指纹=</pin>
  </pin-set>
  ```

### 9. Android 网络安全配置

`res/xml/network_security_config.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- ① 域名级配置 -->
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">api.example.com</domain>
        <!-- 信任用户安装的 CA（调试用，release 必须去掉） -->
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
        <!-- 证书固定 -->
        <pin-set expiration="2027-01-01">
            <pin digest="SHA-256">APiFmiIycT...=</pin>
        </pin-set>
    </domain-config>

    <!-- ② 全局默认配置 -->
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>

    <!-- ③ debug 覆盖：允许明文与用户证书 -->
    <debug-overrides>
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>
```

在 `AndroidManifest.xml` 中引用：

```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ...>
```

**关键点**：

1. **明文流量开关**：
   - `android:usesCleartextTraffic="true"`（Manifest 级别，**全局**，targetSdk 28+ 默认 false）。
   - `cleartextTrafficPermitted="false"`（network_security_config 级别，**可按域名精细控制**，优先级更高）。
   - **注意**：仅配置 `network_security_config` 而没设 `usesCleartextTraffic`，从 **API 24~27** 开始生效，**API 23 及以下仍以 Manifest 为准**。
2. **用户证书信任**：`<certificates src="user" />` 让 App 信任用户自己安装的 CA。**只在 debug 构建启用**，release 启用等于给了抓包/中间人攻击开门。
3. **XML 里的坑**：
   - `pin` 的 `digest` 必须是 **Base64 的 SPKI SHA-256**（不是整证书）。
   - `expiration` 一旦过期，pin **自动失效**（不会导致断网，只是回到普通信任链）——这是比 OkHttp 硬编码更安全的地方。
   - `<domain includeSubdomains="true">` 不写 `includeSubdomains` 时只匹配精确域名。

### 10. 面试速查：HTTP 缓存头

| 请求头 | 作用 |
| --- | --- |
| `If-None-Match` | 携带上次响应的 `ETag`，服务端未变则回 304 |
| `If-Modified-Since` | 携带上次响应的 `Last-Modified` 时间 |
| `Cache-Control: no-cache` | 缓存可存但每次都要回源校验 |
| `Cache-Control: max-age=N` | N 秒内视为新鲜（强缓存） |
| `Cache-Control: no-store` | 完全不存 |

| 响应头 | 作用 |
| --- | --- |
| `ETag` | 资源版本标识（类似 hash） |
| `Last-Modified` | 资源最后修改时间 |
| `Vary` | 指定哪些请求头会影响响应内容（影响缓存分桶） |
| `Content-Encoding: gzip` | 压缩 |
| `Content-Length` / `Transfer-Encoding: chunked` | 定长 / 分块传输 |
| `Access-Control-Allow-Origin` | CORS 跨域控制 |
| `Strict-Transport-Security` | HSTS，强制后续请求走 HTTPS |

## 代码示例

### 1. 用 OkHttp 配置证书固定与网络抓包友好的设置

```kotlin
object SecureHttp {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // ① 证书固定：写死期望的 SPKI SHA-256 指纹
            .certificatePinner(
                CertificatePinner.Builder()
                    .add("api.example.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                    // 备用 pin：证书轮换期间必须能命中，否则线上全断
                    .add("api.example.com", "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=")
                    .build()
            )
            // ② 强制 HTTPS（配合 network_security_config 双重保险）
            .addInterceptor { chain ->
                val original = chain.request()
                val secured = if (original.url.isHttps) original
                else original.newBuilder().url(original.url.newBuilder().scheme("https").build()).build()
                chain.proceed(secured)
            }
            .build()
    }
}
```

### 2. 网络安全配置文件（res/xml/network_security_config.xml）

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- 生产域名：只允许 HTTPS，只信任系统 CA，启用证书固定 -->
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">api.example.com</domain>
        <domain includeSubdomains="true">cdn.example.com</domain>
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
        <pin-set expiration="2027-01-01">
            <pin digest="SHA-256">k3XqP9l0mN2vB8sT4uW6yZ1aC5dE7fG0hI9jK2lM4nO6pQ8rS0tU2vW4xY6zA1bC3dE=</pin>
        </pin-set>
    </domain-config>

    <!-- 本地开发/测试环境：允许明文 HTTP（仅 debug 用） -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">10.0.2.2</domain>
        <domain includeSubdomains="true">192.168.0.0</domain>
    </domain-config>

    <!-- 全局兜底：禁止明文 -->
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>

    <!-- debug 构建才信任用户证书，方便 Charles/mitmproxy 抓包 -->
    <debug-overrides>
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>
```

### 3. 自定义 TrustManager（一般只在测试环境使用）

```kotlin
/**
 * 仅用于本地抓包调试：信任用户安装的 CA。
 * ⚠️ 绝对不能打进 release 包！
 */
fun debugTrustManager(): X509TrustManager {
    val trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.first() as X509TrustManager

    return object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}

// 使用（仅 debug）
val debugClient = OkHttpClient.Builder()
    .sslSocketFactory(
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(debugTrustManager()), null)
        }.socketFactory,
        debugTrustManager()
    )
    .build()
```

### 4. 用代码获取证书指纹（配合 pin 校验）

```kotlin
fun sha256SpkiFingerprint(cert: X509Certificate): String {
    val spki = cert.publicKey.encoded                 // SPKI 的 DER 编码
    val digest = MessageDigest.getInstance("SHA-256").digest(spki)
    return Base64.encodeToString(digest, Base64.NO_WRAP)
}
```

### 5. 判断响应是否命中缓存 / 强缓存是否过期

```kotlin
data class CacheStatus(
    val fromNetwork: Boolean,      // true = 本次走了网络
    val fromCache: Boolean,
    val ageSeconds: Int?,          // 缓存已存在多久
    val remainingSeconds: Int?     // 强缓存还剩多久有效
)

suspend fun inspectCache(url: String): CacheStatus = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).build()
    OkHttpClientHolder.client.newCall(request).execute().use { response ->
        val network = response.networkResponse   // 非 null = 走了网络
        val cache = response.cacheResponse       // 非 null = 命中了缓存
        val age = response.header("Age")?.toIntOrNull()
        val cacheControl = response.cacheControl

        CacheStatus(
            fromNetwork = network != null,
            fromCache = cache != null,
            ageSeconds = age,
            remainingSeconds = if (cacheControl.maxAge > 0) cacheControl.maxAge - (age ?: 0) else null
        )
    }
}
```

## 常见问题 / 面试要点

**读者问：讲一下 HTTPS 的握手过程？**

**AI答：**

1. **先说清目标**：HTTPS = HTTP + TLS。TLS 解决三件事：**机密性**（对称加密）、**完整性**（AEAD 认证标签）、**身份认证**（证书链）。数据加密用**对称密钥**，因为对称快；密钥怎么安全传过去，用**非对称加密 + 证书**。
2. **TLS 1.3 握手五步**（能画出来才算过关）：
   - **① ClientHello**：携带 TLS 版本、随机数、支持的密码套件列表、**key_share（客户端临时公钥，X25519/X25519MLKEM768）**、以及 HTTP/2 相关的 ALPN。
   - **② ServerHello**：服务端选定密码套件、返回随机数、**key_share（服务端临时公钥）**。
   - **③ 加密的扩展 + 证书 + CertificateVerify + Finished**（服务端 → 客户端）：
     - `EncryptedExtensions`：协商 ALPN（h2 / http/1.1）等。
     - `Certificate`：服务端证书链。
     - `CertificateVerify`：服务端用**证书私钥对握手记录签名**，证明"我确实持有该证书对应的私钥"。
     - `Finished`：握手消息的 MAC，证明握手过程没被篡改。
   - **④ 客户端 Finished**（客户端 → 服务端）。
   - **⑤ 双方各自用 ECDHE 共享密钥 + HKDF 派生**出同一套对称密钥，后续用 AES-128-GCM / ChaCha20-Poly1305 加密传输 HTTP 报文。
3. **关键细节**：
   - **会话密钥靠 ECDHE 协商**，客户端和服务端各自生成临时密钥对，交换公钥，用**私钥 + 对端公钥**算出共享密钥 → **前向保密**（即使服务端私钥泄露，历史流量也无法解密）。
   - **证书校验**：链式验签 + 域名匹配 + 有效期 + 用途。
   - **TLS 1.3 只有 1-RTT**（TLS 1.2 是 2-RTT，TCP+TLS1.2 合计 3-RTT）；**TLS 1.3 的 Finished 是加密的**，且去掉了 ChangeCipherSpec。
   - **0-RTT**：会话恢复时可携带 Early Data，但**0-RTT 数据可被重放**，业务上必须幂等。
4. **和 HTTP/3 的关系**：QUIC 把 TLS 1.3 **内嵌进传输层**，所以握手更快（1-RTT，甚至 0-RTT），而且**换 IP 不用重握手**。
5. **面试话术**：**"HTTPS 握手 = 验证身份 + 协商密钥。TLS 1.3 只要 1-RTT：ClientHello 带自己的临时公钥，ServerHello 回一个，剩下靠 ECDHE 算出共享密钥，证书链在 ServerHello 之后用服务端私钥签名送达，用来证明身份。"**

---

**读者问：HTTP/2 的多路复用解决了什么问题？它还有什么局限？**

**AI答：**

1. **解决的问题：HTTP/1.1 的应用层队头阻塞（HOL Blocking）**。
   - HTTP/1.1 上一条连接同一时刻只处理一个请求（管线化默认关闭，且即便开启，服务端也要按序返回）。
   - 前一个请求慢 → 后面已经处理完的请求也得干等，**白白浪费连接**。
   - 传统解法是"开多个 TCP 连接"（浏览器对同一域名最多 6 条），但**每个连接都要三次握手 + TLS 握手**，而且连接数暴涨。
   - **HTTP/2 用多路复用解决**：一条 TCP 连接上开多个 **Stream**，每个 Stream 有独立的 ID（不是 TCP 端口，5 位，可开百万个），响应可以**乱序返回**。
2. **HTTP/2 带来的其他收益**：
   - **二进制分帧**：不再受"一请求一响应"的报文边界限制，一帧可以携带任意流的数据。
   - **HPACK 头部压缩**：静态表 + 动态表 + Huffman，重复 header 从几百字节降到几字节。
   - **流控**：连接级 + 流级 WINDOW，避免慢客户端拖垮整个连接。
   - **优先级**（PRIORITY 帧）、**服务器推送**。
3. **局限（追问点）**：
   - **TCP 层的队头阻塞仍在**！HTTP/2 跑在 TCP 上，一个 TCP 段丢失，后面所有 Stream 的数据都要等重传 → **一个丢包，全连接卡住**。这是 HTTP/2 最大的痛点。
   - **中间设备兼容性**：很多企业防火墙、代理不理解 HTTP/2 帧格式，会把 HTTP/2 降级为 HTTP/1.1（ALPN 协商失败）。
   - **TCP 队头阻塞的锅不好甩给上层**，运维排查困难。
   - **服务器推送被废弃**：实际收益低、复杂度高。
4. **HTTP/3 的解决**：基于 **QUIC（UDP 之上自实现可靠传输）**，每个 Stream **独立重传**，彻底消除传输层队头阻塞；再加上 **0-RTT** 和 **连接迁移**（手机切 WiFi/4G 不掉线）。
5. **面试话术**：**"HTTP/2 的多路复用解决的是 HTTP/1.1 的应用层队头阻塞，让一条连接并发跑多个 Stream；但它跑在 TCP 上，丢包仍会阻塞所有 Stream，传输层队头阻塞要靠 HTTP/3 的 QUIC 才彻底解决。"**

---

**读者问：证书 pinning 是什么？有什么用？有什么风险？**

**AI答：**

1. **定义**：在 App 里**硬编码期望的服务器公钥指纹（SPKI 的 SHA-256）**。客户端收到服务器证书后，比对指纹，**不匹配就中断连接**，无论本地的信任链是否信任该证书。
2. **解决什么问题**：
   - 防御**企业代理 / 公共 WiFi 的中间人**（即使它们安装了企业根证书）。
   - 防御**用户自行安装的恶意 CA 证书**（用户装个 CA 就能抓你的 App 流量，pin 之后无效）。
   - 防御**CA 本身被入侵**（伪造证书骗过系统信任库）。
   - 本质是**把信任从"公共 CA"收窄到"这一把公钥"**。
3. **两种 pin 方式**：
   - **整证书 pin**（整张证书的 SHA-256）：证书续期就失效 → **不推荐**。
   - **SPKI pin**（公钥部分的 SHA-256）：**换证书但公钥不变 → pin 仍然有效**。业界标准做法，Google 推荐的 `pin-set` 就是这种。
4. **风险（必须讲，这是加分点）**：
   - **运维风险极高**：pin 写错 / 证书轮换没提前发版 → **整个 App 完全无法联网**。这是最严重的后果，且无法热修复（除非留了 backup pin）。
   - **必须配置 backup pin**：一个主 pin + 一个备用 pin，主 pin 过期前发布新版本。
   - **有效期难定**：证书通常 90 天（Let's Encrypt）或 1~2 年（商业 CA），要留足发版周期。
   - **Google 已不推荐 Certificate Pinning**（`publickey pinning` 已在 Play 政策中标记为不推荐），官方更推荐：
     - **`network_security_config.xml` 的 `<pin-set expiration="...">`**：带过期时间，过期自动失效（回到普通信任链，不会断网），**风险可控**。
     - 依赖 **Certificate Transparency** 监控 + **强制 HTTPS + 网络层校验**。
   - **中间人攻击者可以"降级"**：只要 pin 过期或配置错误，攻击就恢复，所以 pinning 不是万无一失。
5. **正确姿势**：
   - 用 **XML `<pin-set>`** 而不是硬编码在代码里。
   - 一定配 **backup pin**。
   - 设 **`expiration`**。
   - debug 包**不启用 pin**（否则抓包全挂）。
   - 配合 `cleartextTrafficPermitted="false"` + HSTS。
6. **面试话术**：**"证书 pinning 把信任从公共 CA 收窄到指定公钥，能防企业代理和用户自装 CA。但运维风险大——pin 写错就是全线断网，所以要用带 expiration 的 pin-set 并保留 backup pin，Google 现在也不推荐硬编码 pinning。"**

---

**读者问：Android 怎么控制明文流量和用户证书信任？**

**AI答：**

1. **两处配置，优先级不同**：
   - **Manifest**：`android:usesCleartextTraffic="true|false"` —— **全局粒度**。targetSdk 28+ **默认 false**。
   - **`network_security_config.xml`**：`cleartextTrafficPermitted="false"` —— **可按域名精细控制**，**优先级高于 Manifest**。
   - **坑**：`network_security_config` 从 **API 24~27** 才生效（对应 `cleartextTrafficPermitted` 属性）；**API 23 及以下仍以 Manifest 的 `usesCleartextTraffic` 为准**。如果 minSdk < 24 且想全局禁明文，**两个地方都要写**。
2. **信任用户证书**：
   - 默认**不信任**用户安装的 CA（Android 7.0 引入，与桌面浏览器行为不同）。
   - 需要时在 `<trust-anchors>` 里加 `<certificates src="user" />`。
   - **最佳实践：只在 `<debug-overrides>` 里加**（仅 debug 构建生效），release 永远只信任 `system`。
3. **域名级配置**：
   ```xml
   <domain-config cleartextTrafficPermitted="false">
       <domain includeSubdomains="true">api.example.com</domain>
   </domain-config>
   ```
   - `includeSubdomains="true"` 才会匹配 `api.example.com` 的所有子域。
4. **HSTS**：服务端返回 `Strict-Transport-Security: max-age=31536000`，浏览器会强制后续请求走 HTTPS。**注意 Android 的 `HttpsURLConnection` / OkHttp 并不默认消费 HSTS 头**（Chrome 自己实现了），所以不要指望它自动生效。
5. **排查现场问题**：
   - `Cleartext HTTP traffic to xxx not permitted` → 明文被禁。
   - `Trust anchor for certification path not found` → 证书链问题（常见于自签名证书）。
   - `Hostname ... not verified` → 域名不匹配（用了 IP 而证书是域名）。
   - `Certificate expired` → 设备时间不对或证书过期。
6. **面试话术**：**"Android 有两层控制：Manifest 的 usesCleartextTraffic 是全局的，network_security_config 可以按域名精细控制且优先级更高。用户 CA 默认不信任，只在 debug-overrides 里放开方便抓包。生产包只信任 system 证书。"**

---

**读者问：HTTP 缓存的强缓存和协商缓存是怎么回事？ETag 和 Last-Modified 有什么区别？**

**AI答：**

1. **强缓存（Fresh Cache）**：响应带 `Cache-Control: max-age=60`（或 `Expires`）。60 秒内再请求，**浏览器/客户端直接用本地副本，根本不发请求**。
   - `max-age=0` 表示"每次都要校验"，等价于 `no-cache`。
   - `no-store` 表示"根本别存"。
2. **协商缓存（Conditional Request）**：缓存已过期，客户端带上**验证信息**再发请求：
   - **`If-None-Match: "<etag>"`**（对应响应的 `ETag`）→ 服务端比对，未变回 **304 Not Modified**（**无 body**）。
   - **`If-Modified-Since: <date>`**（对应 `Last-Modified`）→ 服务端比对时间戳，未变回 304。
3. **ETag vs Last-Modified**：
   | 维度 | ETag | Last-Modified |
   | --- | --- | --- |
   | 精度 | **精确到内容**（通常 hash） | **秒级**（HTTP 日期格式只到秒） |
   | 可靠性 | 高，内容变就变 | 低，**1 秒内多次修改检测不到** |
   | 计算成本 | 需要服务端算 hash | 直接用 mtime |
   | 分布式场景 | 需保证多节点 hash 一致 | 需保证多节点 mtime 一致 |
   | 优先级 | **更高** | 兜底 |
   - 客户端**同时**发 `If-None-Match` 和 `If-Modified-Since`，服务端**优先检查 ETag**。
4. **304 的作用**：只传 header 不传 body，**节省流量**，但仍有一次 RTT。
5. **`Vary` 头**：告诉缓存"哪些请求头会影响响应"，例如 `Vary: Accept-Encoding` → 缓存按 (URL + Accept-Encoding) 分桶。
6. **面试话术**：**"强缓存是 max-age 内不发请求；协商缓存是带 ETag/If-None-Match 发请求，服务端回 304。ETag 比 Last-Modified 精确，因为 HTTP 时间戳只有秒级精度，同一秒内的改动检测不到。"**

---

**读者问：HTTP 是无状态的，那 Cookie 和 Session 怎么实现状态？登录态怎么保持？**

**AI答：**

1. **HTTP 无状态**：每个请求独立，服务器不记得上一个请求。这带来**水平扩展的便利**（任意节点都能处理请求）和**每次请求都要带认证信息**的开销。
2. **Cookie 是客户端机制**：
   - 服务端 `Set-Cookie: sid=xxx; Path=/; HttpOnly; Secure; SameSite=Lax`。
   - 客户端（`CookieJar`）保存，后续请求自动带上 `Cookie: sid=xxx`。
   - **`HttpOnly`**：JS 读不到，防 XSS 窃取。
   - **`Secure`**：只在 HTTPS 发送。
   - **`SameSite=Lax/Strict/None`**：防 CSRF。
3. **Token（JWT）方案**：
   - 登录后服务端返回 `access_token`（JWT，含 payload + 签名），客户端存起来，每次请求放 `Authorization: Bearer <token>`。
   - **优点**：无状态，服务端不用存 session → 天然水平扩展。
   - **缺点**：**无法主动失效**（除非维护黑名单或短有效期 + refresh token）。
4. **Android 端的最佳实践**：
   - Token **不要放 SharedPreferences 明文**（可用 `EncryptedSharedPreferences` 或 `Android Keystore` 加密）。
   - OkHttp 用 `CookieJar` 实现自动管理（注意持久化 CookieJar 也要加密）。
   - Token 过期用 `Authenticator` 统一刷新（详见 [[../网络框架/Retrofit]]）。
5. **面试话术**：**"HTTP 无状态指的是协议层不记录上下文；状态靠 Cookie（客户端携带的会话标识）或 Token（自包含的 JWT）来承载。Token 的代价是无法主动失效，所以要短 access_token + refresh_token + 黑名单/版本号。"**

---

**读者问：HTTP/1.1 的 Keep-Alive、流水线分别是什么？HTTP/2 还需要吗？**

**AI答：**

1. **Keep-Alive（持久连接）**：HTTP/1.1 默认开启。一条 TCP 连接传输多个请求，避免每次都做 TCP 三次握手 + TLS 握手。
   - 通过 `Connection: keep-alive` 头显式声明；HTTP/1.0 默认关闭，用 `Connection: keep-alive` 开启。
   - 服务端通过 `Keep-Alive: timeout=5, max=100` 告知可复用时长。
   - **浏览器限制**：对同一域名最多 6 条连接（HTTP/1.1）。
2. **管线化（Pipelining）**：在上一条响应回来之前，就发出下一个请求（**不等响应**）。
   - **几乎没被广泛使用**，原因：① 响应必须**按序**返回 → 队头阻塞没解决，反而更糟；② 中间设备兼容性差；③ 错误处理复杂（前一个请求出错后面全乱）。
3. **HTTP/2 还需要 Keep-Alive 吗**：**需要，而且更重要**。HTTP/2 的多路复用**完全建立在一条长期存在的 TCP 连接之上**——HTTP/2 反而更依赖连接复用，因为一条连接能承载的请求数远超 HTTP/1.1。
   - 但**不再需要"Keep-Alive"这个头**（HTTP/2 规范中该头被忽略，连接复用是默认行为）。
4. **面试话术**：**"HTTP/1.1 的 Keep-Alive 是持久连接，省掉握手；管线化因为响应必须按序返回反而加重队头阻塞，基本被淘汰。HTTP/2 的多路复用更依赖长连接，一条连接跑多个 Stream。"**

## 关联笔记

- [[../网络框架/OkHttp]]：OkHttp 的缓存、连接池、CertificatePinner 实现
- [[../网络框架/Retrofit]]：HTTP 语义在注解中的体现
- [[../网络协议/RESTful]]：HTTP 方法语义与状态码设计规范
- [[../网络协议/WebSocket]]：HTTP/1.1 Upgrade 升级为 WebSocket 的过程
- [[../README]]：第 5 部分总览
- [[../Handler机制原理]]：Handler/Looper 与主线程消息机制

## 参考资料

- MDN HTTP 概述：https://developer.mozilla.org/zh-CN/docs/Web/HTTP/Overview
- RFC 9110（HTTP Semantics）：https://www.rfc-editor.org/rfc/rfc9110
- RFC 9113（HTTP/2）：https://www.rfc-editor.org/rfc/rfc9113
- RFC 9000（QUIC）：https://www.rfc-editor.org/rfc/rfc9000
- RFC 8446（TLS 1.3）：https://www.rfc-editor.org/rfc/rfc8446
- Android 网络安全配置文档：https://developer.android.com/privacy-and-security/security-config
- Android 证书固定文档：https://developer.android.com/privacy-and-security/security-certificate
