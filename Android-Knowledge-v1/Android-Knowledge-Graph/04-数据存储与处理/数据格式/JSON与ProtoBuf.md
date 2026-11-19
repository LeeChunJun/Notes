---
tags: [android]
part: 第4部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[../README]], [[../本地存储/DataStore]], [[../数据库/SQLite与Room]]"
---

# JSON与ProtoBuf

## 一句话定义

JSON 是**文本自描述格式**，优点是通用、可读、生态好，缺点是**体积大、解析慢、弱类型**；ProtoBuf 是**二进制 schema 驱动格式**，用**字段编号 + Varint 编码**把体积压到 JSON 的 1/5 ~ 1/10，解析几乎零反射开销，代价是**不可读、必须有 schema、跨端兼容要守规矩**。Android 端的选型本质是：**要通用可调试选 JSON，要性能与体积选 ProtoBuf**。

## 核心原理

### 1. JSON 的数据模型与代价

JSON 只有 6 种类型：`object`、`array`、`string`、`number`、`boolean`、`null`。

```json
{
  "id": 1001,
  "name": "Alice",
  "email": "alice@example.com",
  "tags": ["android", "kotlin"],
  "profile": { "age": 28, "city": "Hangzhou" },
  "vip": true,
  "avatar": null
}
```

| 问题 | 说明 | 后果 |
| --- | --- | --- |
| **重复的 key** | 标准 JSON 允许同 key 多次出现，各解析器行为不同 | Gson 取最后一个、Moshi 报错、org.json 取最后一个 → **跨端不一致的坑** |
| **无 schema** | 字段可以任意增删 | 拼错 key **运行期才发现**（返回 null） |
| **弱类型** | `number` 不区分 int/long/double/float | `Int` 溢出、`Long` 精度丢失（大 ID） |
| **null 语义** | 字段缺失 vs 字段为 null **语义不同** | Kotlin 的 `null` ↔ JSON `null` ↔ 字段缺失，三者映射规则要自己定 |
| **体积** | key 名重复出现、无压缩、无类型标记 | 同样数据 JSON 通常是 ProtoBuf 的 **3~10 倍** |
| **解析速度** | 需要词法分析 + 类型转换 + 反射/代码生成 | 比二进制格式慢 **3~10 倍** |
| **字符串转义** | 引号、反斜杠、换行都要转义 | 含大量 URL 或 HTML 时体积暴涨 |

> 面试点：**JSON 的字段名是"每次传输都要重写一遍"的**。如果结构里有 20 个字段，每个字段名平均 10 字节，就是 200 字节的纯冗余——这是 ProtoBuf 体积优势的主要来源。

### 2. Android 端 JSON 库对比

| 维度 | `org.json` | Gson | Moshi | kotlinx.serialization |
| --- | --- | --- | --- | --- |
| **出处** | Android SDK 内置 | Google | Square | JetBrains |
| **实现方式** | 手写解析器 | **反射** + `TypeAdapter` | **反射** 或 **KSP codegen** | **编译器插件** |
| **性能** | 慢 | 中 | 快（codegen）/ 中（反射） | **最快**（零反射） |
| **API 风格** | `getString()` 逐字段取 | `gson.fromJson(json, T::class.java)` | `moshi.adapter<T>().fromJson(json)` | `Json.decodeFromString<T>(json)` |
| **类型安全** | 无（key 字符串） | 编译期有泛型，运行期可能 `JsonSyntaxException` | 编译期有泛型 | **编译期最强**（serializer 由插件生成） |
| **null 处理** | `optXxx` 默认值 | `@SerializedName` / `nullSafe` | `nullSafe()` 默认 true | `explicitNulls` / `coerceInputValues` |
| **自定义类型** | 手动 `opt` | `TypeAdapter` | `Adapter` | `@Serializer` |
| **Kotlin 特性** | 不支持（Java API） | 支持但啰嗦 | 支持 | **一等公民**（不可空、默认值、密封类） |
| **额外依赖** | 无 | `com.google.code.gson:gson` | `com.squareup.moshi:moshi` | `org.jetbrains.kotlinx:kotlinx-serialization-json` + 编译器插件 |
| **建议** | 只在极简单场景用 | 存量项目 / 需兼容 Java 老代码 | **Android 新项目首选** | **纯 Kotlin 多端项目首选** |

```text
   解析方式对比
   ┌──────────────────────────────────────────────────────────┐
   │ org.json：逐字段 optXxx，手动转换                          │
   │   JSON → Map/List → 手工取值 → 手工构造 Model             │
   │   慢、无类型安全、字段名散落在代码里                        │
   ├──────────────────────────────────────────────────────────┤
   │ Gson：反射读取字段声明                                     │
   │   JSON → JsonReader → 反射 Field → 赋值                    │
   │   反射开销 + 字段名以 Java 字段名为准（@SerializedName 改） │
   ├──────────────────────────────────────────────────────────┤
   │ Moshi（codegen）：KSP 生成 Adapter                        │
   │   JSON → JsonReader → 生成的 Adapter.read() → 构造 Model  │
   │   无反射，可裁剪不认识字段，错误提前到编译期                 │
   ├──────────────────────────────────────────────────────────┤
   │ kotlinx.serialization：编译器插件生成 Serializer           │
   │   JSON → JsonDecoder → 生成的 serializer → 构造 Model      │
   │   零反射 + Kotlin 原生类型（sealed class、value class）    │
   └──────────────────────────────────────────────────────────┘
```

### 3. Gson 原理与"为什么慢"

Gson 的解析路径：

```text
   gson.fromJson(json, User::class.java)
        │
        ▼
   1. TypeToken 解析出目标类型（Type / TypeAdapter）
        │
        ▼
   2. ReflectiveTypeAdapterFactory 找到 User 的 TypeAdapter
        │   └─ 通过反射遍历 User 的 Field 列表
        │        - 字段名（可用 @SerializedName 覆盖）
        │        - 泛型类型（TypeToken）
        │        - @Expose / transient 修饰
        ▼
   3. JsonReader 逐 token 读取（beginObject → name → value → endObject）
        │
        ▼
   4. 通过 Field.set() 反射写入实例
```

慢在哪里：

| 开销来源 | 说明 | 缓解 |
| --- | --- | --- |
| **反射遍历字段** | 每次解析都要拿 `Field[]`、读注解 | 用 `TypeAdapter` 缓存（**Gson 的 adapter 有缓存**，所以不是每次都反射扫描） |
| **`Field.set` 反射写入** | 每个字段一次反射调用 | → 用 **Moshi codegen** 或 **kotlinx.serialization** 换成直接 setter 调用 |
| **`JsonReader` 的字符串缓冲** | `nextString` 要做转义处理、分配 `String` | 库层面优化有限 |
| **装箱** | `int` → `Integer` → `int` | ProtoBuf / kotlinx 有时仍装箱 |
| **无 `String` 复用** | 每次 key 都新建 String | Moshi/Kotlinx 有一定优化 |

> ⚠️ **Gson 的一个经典坑**：`Gson` 依赖 **Java 字段名**（不是 Kotlin 属性名），而且**不区分 `is` 前缀的 getter**。Kotlin 的 `val isVip: Boolean` 编译成 `isVip()` getter + `isVip` 字段 —— 有时能对上，有时对不上。**最保险的是写 `@SerializedName("is_vip")`。**

### 4. Moshi：反射 vs KSP codegen

Moshi 的核心设计是 **TypeAdapter**：一个"把一个类型和 JSON 之间双向转换"的策略接口。

```kotlin
// Moshi 的适配器工厂链
// Moshi.Builder()
//   .add(KotlinJsonAdapterFactory())  // 反射适配 Kotlin 类（需 kotlin-reflect）
//   .add(KotlinCodeGenAdapterFactory()) // KSP 生成的适配器（需 moshi-kotlin-codegen）
//   .add(Rfc3339DateJsonAdapter.FACTORY)
//   .build()
```

| 模式 | 依赖 | 特点 |
| --- | --- | --- |
| **反射模式** | `moshi` + `kotlin-reflect` | 依赖 `kotlin-reflect`（**体积 +3MB**）；依赖运行时反射 → **混淆器需保留元数据**；无法处理 `internal`、无法裁剪未知字段 |
| **KSP codegen** | `moshi` + `moshi-kotlin-codegen`（ksp） | 编译期生成 `UserJsonAdapter`；**无反射、无 kotlin-reflect**；**构造参数带默认值时能正确处理缺失字段**；速度更快 |

Moshi codegen 的关键优势：**构造参数默认值**。反射模式下，如果 JSON 里缺某个字段且该字段在 Kotlin 中有默认值，Moshi 反射适配器**无法调用带默认值的合成构造**（除非用 `KotlinJsonAdapterFactory`）；codegen 生成的代码直接调用带默认值的构造 → 天然支持"服务端新增字段、客户端没更新"。

Moshi 的 **"跳过未知字段"** 行为：`JsonReader.skipValue()` → 遇到 JSON 里有、而 Kotlin 类里没有的字段，**直接跳过而不是报错** → 这就是 ProtoBuf 兼容性的等价物（JSON 场景下靠"宽容解析"实现）。

### 5. kotlinx.serialization：编译器插件原理

kotlinx.serialization 的核心是：**编译器插件在编译期为每个 `@Serializable` 类生成 `xxx.serializer()` 对象**。

```text
   编译期（Kotlin 编译器插件 kotlin-serialization）
   ┌───────────────────────────────────────────────────────────┐
   │ 1. 扫描 @Serializable 类/对象                              │
   │ 2. 生成 Companion.serializer() → 构造 KSerializer<T>      │
   │    - descriptor: 字段名、序号、是否可选、默认值              │
   │    - deserialize: 读 JSON → 调构造函数                       │
   │    - serialize:   对象 → JSON                              │
   │ 3. 生成的代码直接调用构造器和属性访问器 → 零反射            │
   │ 4. @Polymorphic 时额外生成 polymorphic 序列化器            │
   └───────────────────────────────────────────────────────────┘

   运行时
   Json.decodeFromString<User>(text)   // 走生成的 serializer
   Json.encodeToString(user)
```

配置（`build.gradle.kts`）：

```kotlin
plugins {
    kotlin("plugin.serialization") version "2.0.0"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
```

```kotlin
@Serializable
data class User(
    val id: Long,
    val name: String,
    @SerialName("email_address") val email: String,   // 改字段名
    @EncodeDefault(EncodeDefault.Mode.NEVER) val role: String = "user"  // 默认值不输出
)

@Serializable
sealed interface ApiResult {
    @Serializable data class Success(val data: User) : ApiResult
    @Serializable data class Error(val code: Int, val msg: String) : ApiResult
}

// 编译期生成的 serializer
val userSerializer = User.serializer()
Json.decodeFromString<User>(text)
```

| 特性 | 说明 |
| --- | --- |
| **零反射** | 插件生成的代码直接调构造器 → 无 `kotlin-reflect` 依赖 |
| **sealed class 多态** | `@Serializable sealed interface` + `Json { polymorphicSerializer }` → 编译期生成 `$serializer` 体系 |
| **默认值语义明确** | `encodeDefaults = false` 默认不输出等于默认值的字段（**体积优化**） |
| **`ignoreUnknownKeys`** | 默认 `false` → 未知字段**报错**（比 Moshi 严格）；生产环境通常开 `true` |
| **`explicitNulls`** | `false` 时不输出 null 字段 |
| **`coerceInputValues`** | `true` 时 null 赋给非空字段会取默认值而不是报错 |
| **自定义 serializer** | `@Serializable(with = XxxSerializer::class)` 或 `JsonSerializersModule` 注册 |

### 6. ProtoBuf 原理：Varint、字段编号、wire format

ProtoBuf 的核心思想：**用字段编号代替字段名，用二进制变长整数编码数值。**

```proto
syntax = "proto3";

message User {
  int64  id      = 1;   // 字段编号 1
  string name    = 2;   // 字段编号 2
  string email   = 3;
  repeated string tags = 4;
  bool   vip     = 5;
}
```

**Wire format（编码规则）**：每个字段 = `tag + value`。

```text
   tag = (字段编号 << 3) | wire_type
   ┌───────────────┬──────────────┐
   │ field_number  │  wire_type   │
   │   varint      │   3 bits     │
   └───────────────┴──────────────┘

   wire_type 取值：
     0 = Varint        (int32/int64/uint32/uint64/bool/enum)
     1 = 64-bit fixed  (double/fixed64/sfixed64)
     2 = Length-delimited (string/bytes/嵌套message/packed repeated)
     5 = 32-bit fixed  (float/fixed32/sfixed32)
```

**Varint（变长整数）**：用 1~10 个字节表示整数，**小数字占小空间**。

```text
   数值 300 的 Varint 编码：
   300 = 0b1_00101100
   低 7 位一组，从低位到高位，每组最高位是"续位标志"

   字节 1: 01011100  (0x5C)  ← 低7位=1011100, 续位=0? 
   正确拆解：
     300 & 0x7F        = 44  → 0x2C | 0x80 = 0xAC   (续位=1)
     300 >> 7          = 2   → 0x02              (续位=0)
   编码结果：0xAC 0x02  → 2 字节

   对比：int32 固定 4 字节 → Varint 只用 2 字节
   对比：int64 固定 8 字节 → Varint 最多 10 字节，但小数值仍省
```

| 类型 | JSON 表示 | ProtoBuf 编码 | 典型大小 |
| --- | --- | --- | --- |
| `int64 id = 1` | `"id":1001` (8 字节 + 引号) | tag(1) + varint(2) | **3 字节** |
| `string name = 2` | `"name":"Alice"` | tag(1) + len(1) + bytes(5) | **7 字节** |
| `bool vip = 5` | `"vip":true` | tag(1) + varint(1) | **2 字节** |

> 关键点：**字段编号（1,2,3）代替了字段名**（"id","name"）→ 省掉大量重复字符串。这是 ProtoBuf 体积优势的根本来源。

### 7. ProtoBuf 的向后兼容规则

这是 ProtoBuf 面试的**核心考点**。

| 规则 | 说明 | 反例（破坏兼容） |
| --- | --- | --- |
| **不要改字段编号** | 编号是契约，改了就等于换字段 | `name = 2` 改成 `name = 3` |
| **不要改字段名**（只影响 JSON/Ruby 等文本映射） | proto 内部用编号，名字只用于生成代码与调试 | — |
| **删除字段要 `reserved`** | 保留编号，防止以后误用 | `reserved 3; reserved "old_name";` |
| **新增字段必须是可选的** | proto3 默认值不参与序列化；新增字段老客户端会跳过 | — |
| **改变字段类型要兼容** | 允许 `int32↔int64↔uint32↔uint64↔bool`；`string↔bytes`；`fixed32↔sfixed32↔float` | `string` ↔ `int32` 非法 |
| **repeated ↔ singular 不能直接换** | 需要用新的字段编号重发 | — |
| **`oneof` 改普通字段** | 会破坏兼容（除非用新编号） | — |
| **枚举值只能加不能改** | 加新值是兼容的；改旧值的含义不兼容 | — |

```proto
message User {
  reserved 5, 6;              // 保留已删除字段的编号
  reserved "old_name";

  int64  id   = 1;
  string name = 2;

  // 新增字段：编号必须是新的、之前没用过的
  string nickname = 7;
}
```

**Proto3 的默认值与"字段是否存在"**：

| 情况 | proto2 | proto3 |
| --- | --- | --- |
| 字段未设置 | 不可读 | 读出**默认值**（0 / "" / false） |
| 判断是否存在 | `hasXxx()` | 无（需 `optional` 修饰） |
| 序列化默认��� | 不写 | **不写**（除非 `optional`） |

> ⚠️ **proto3 的"默认值丢失"是经典坑**：客户端发 `score = 0`，服务端反序列化后是 `0` —— 无法区分"没传"和"传了 0"。解决：proto3 的 `optional` 关键字（生成 `hasXxx()`）或用 `oneof` / 包装类型。

### 8. Android 端使用 ProtoBuf

```kotlin
// build.gradle.kts
plugins {
    id("com.google.protobuf") version "0.9.4"
}
dependencies {
    implementation("com.google.protobuf:protobuf-javalite:3.25.3")   // lite：适合移动端
    // 或 protobuf-java：full，支持反射/descriptor，体积更大
}
```

```kotlin
// User 是 protobuf 生成的类
val user = User.newBuilder()
    .setId(1001L)
    .setName("Alice")
    .addTags("android")
    .build()

val bytes: ByteArray = user.toByteArray()          // 序列化
val restored: User = User.parseFrom(bytes)         // 反序列化
```

> **javalite vs java**：
> - `protobuf-javalite`：**不支持反射/descriptor**，代码更小更快，适合 **App 端**。
> - `protobuf-java`：支持 `Any`、反射、descriptor、动态消息，适合 **服务端**。
> - ⚠️ **跨端要求两端用同一种实现**（lite 与 full 的二进制格式兼容，但 API 不兼容）。

### 9. 数据压缩

序列化格式解决的是"**编码效率**"，压缩解决的是"**传输与存储的字节数**"，两者可叠加。

| 算法 | 压缩率 | 速度 | CPU | Android 支持 |
| --- | --- | --- | --- | --- |
| **gzip (DEFLATE)** | 较好（文本 ~4:1） | 中 | 中 | ✅ `GZIPOutputStream` / OkHttp 自动加 |
| **zstd** | **优于 gzip**（~5:1） | **快** | 低 | ⚠️ 需引入 `com.facebook.fresco:zstd` 或 `zstd-jni` |
| ** brotli** | 优于 gzip | 快 | 中 | ⚠️ 需三方库；HTTP 头 `Content-Encoding: br` |
| **lz4** | 一般 | **极快** | 低 | ⚠️ 需三方库 |
| **无压缩** | 1:1 | 最快 | 最低 | — |

```text
   原始数据
      │  ① 序列化（编码）
      ▼
   ┌──────────┐
   │ JSON /   │   ← 决定"结构效率"（字段名 vs 字段编号）
   │ ProtoBuf │
   └──────────┘
      │  ② 压缩
      ▼
   ┌──────────┐
   │ gzip /   │   ← 决定"字节冗余压缩率"
   │ zstd     │
   └──────────┘
      │
      ▼
   网络传输 / 磁盘存储
```

- **ProtoBuf + gzip** 常见组合，但注意：**ProtoBuf 本身已经去掉了大部分"人类可读冗余"**，对已压缩数据再做 gzip 的增益**远小于** 对 JSON 做 gzip。
- **JSON + gzip** 在 HTTP 场景仍很常见，因为**大多数服务器/CDN 默认支持 gzip/br**，无需改两端。
- **何时不要压缩**：数据已加密（压缩率≈0 且有侧信道风险）、数据量很小（压缩头开销 > 收益）、CPU 敏感场景。

```kotlin
// gzip 压缩
fun gzip(input: ByteArray): ByteArray {
    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use { it.write(input) }
    return bos.toByteArray()
}
```

### 10. 选型决策树

```text
                    需要跨语言 / 跨端（Java、Go、Swift、Python）？
                              │
              ┌───────────────┴────────────────┐
             是                               否
              │                                │
      强 schema 契约？                    内部数据结构？
              │                                │
     ┌────────┴────────┐              ┌────────┴────────┐
    是                 否              有性能/体积要求？   否
     │                  │                │                │
 ProtoBuf          JSON + 校验     JSON + kotlinx   JSON + Gson/Moshi
 (gRPC 默认)        (schema 校验)    .serialization
```

| 场景 | 推荐 | 理由 |
| --- | --- | --- |
| **HTTP REST 公开 API** | JSON | 可读、可调试、浏览器友好、几乎所有后端框架原生支持 |
| **App 与自建后端** | JSON（Moshi/kotlinx） | 联调方便；开启 gzip 后体积差距缩小 |
| **App 与自建后端（长连接/IM/音视频信令）** | **ProtoBuf** | 流量敏感、延迟敏感、schema 可控 |
| **gRPC** | **ProtoBuf**（强制） | gRPC 的默认编码 |
| **跨端共享模型（Android/iOS/Web）** | ProtoBuf 或 **KMP + kotlinx.serialization** | 一份 schema，多端生成 |
| **本地持久化（偏好配置）** | **Proto DataStore** | 见 [[../本地存储/DataStore]] |
| **数据库里的复杂字段** | JSON 字符串列 或 ProtoBuf 二进制列 | 二进制列不能直接 SQL 查询 |
| **日志/埋点上报** | JSON（或 Protobuf 的二进制上报通道） | 埋点量大 → 体积敏感 |

## 代码示例

### 1. kotlinx.serialization 完整用法

```kotlin
// ---------- build.gradle.kts ----------
// plugins { kotlin("plugin.serialization") version "2.0.0" }
// dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3") }

// ---------- 模型 ----------
@Serializable
data class UserProfile(
    val id: Long,
    val name: String,
    @SerialName("email_address") val email: String,          // 自定义 JSON 字段名
    val tags: List<String> = emptyList(),
    val avatarUrl: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val role: String = "user"  // 默认值不序列化
)

// ---------- 多态：sealed interface 需要注册 polymorphic 序列化器 ----------
@Serializable
sealed interface ApiResult {
    @Serializable data class Success(val data: UserProfile) : ApiResult
    @Serializable data class Failure(val code: Int, val message: String) : ApiResult
}

// ---------- 自定义 serializer（把 Long 时间戳序列化成 ISO 字符串） ----------
object LocalDateSerializer : KSerializer<LocalDate> {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) =
        encoder.encodeString(formatter.format(value))
    override fun deserialize(decoder: Decoder): LocalDate =
        LocalDate.parse(decoder.decodeString(), formatter)
}

// ---------- Json 实例 ----------
object NetJson {
    val default = Json {
        ignoreUnknownKeys = true        // 忽略服务端新增字段（兼容性关键）
        isLenient = true                // 允许不规范的 JSON
        coerceInputValues = true        // null → 非空字段时取默认值而不是报错
        explicitNulls = false           // 不输出 null 字段
        encodeDefaults = false          // 不输出等于默认值的字段
        prettyPrint = false
        serializersModule = SerializersModule {
            contextual(LocalDate::class, LocalDateSerializer)
            polymorphic(ApiResult::class) {
                subclass(ApiResult.Success::class, ApiResult.Success.serializer())
                subclass(ApiResult.Failure::class, ApiResult.Failure.serializer())
            }
        }
    }
}

// ---------- 使用 ----------
val json = NetJson.default.encodeToString(profile)     // 序列化
val restored: UserProfile = NetJson.default.decodeFromString(json)  // 反序列化
val result: ApiResult = NetJson.default.decodeFromString(body)
```

### 2. Moshi（KSP codegen）完整用法

```kotlin
// ---------- build.gradle.kts ----------
// plugins { id("com.google.devtools.ksp") version "2.0.0-1.0.24" }
// dependencies {
//     implementation("com.squareup.moshi:moshi:1.15.1")
//     ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.1")
// }

@JsonClass(generateAdapter = true)   // KSP 生成 UserJsonAdapter
data class UserProfile(
    val id: Long,
    @Json(name = "email_address") val email: String,
    val tags: List<String> = emptyList()
)

// ---------- 使用 ----------
val moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .add(Rfc3339DateJsonAdapter.FACTORY)
    .build()

val adapter = moshi.adapter<UserProfile>()
val json = adapter.toJson(profile)
val restored = adapter.fromJson(json)
```

### 3. Gson 存量项目写法（注意坑）

```kotlin
data class UserProfile(
    val id: Long,
    @SerializedName("email_address") val email: String,   // 必须显式指定，否则用 Java 字段名
    @SerializedName("is_vip") val isVip: Boolean,
    val tags: List<String> = emptyList()
)

val gson = GsonBuilder()
    .setDateFormat("yyyy-MM-dd'T'HH:mm:ss")   // 日期格式
    .serializeNulls()                           // 是否输出 null 字段
    .create()

val json = gson.toJson(profile)
val restored = gson.fromJson(json, UserProfile::class.java)
```

> ⚠️ **Gson 的坑**：
> 1. 用 **Java 字段名**做 key（不是 Kotlin 属性名），`@SerializedName` 是唯一可靠的改名方式。
> 2. **R8/ProGuard 混淆会破坏字段名** → 必须加 `-keepclassmembers class com.xxx.model.** { <fields>; }` 或用 `@SerializedName` 保护。
> 3. 遇到 **未知字段默认忽略**（这点反而是优点）。

### 4. ProtoBuf 定义与使用

```proto
// app/src/main/proto/user.proto
syntax = "proto3";

option java_package = "com.example.proto";
option java_multiple_files = true;
option java_outer_classname = "UserProto";

message User {
  int64  id              = 1;
  string name            = 2;
  string email           = 3;
  repeated string tags   = 4;
  bool   vip             = 5;
  // 新增字段必须用新编号，且不改变旧字段语义
  string nickname        = 6;

  reserved 7, 8;
  reserved "old_field";
}

message UserList {
  repeated User users = 1;
}
```

```kotlin
// build.gradle.kts
// plugins { id("com.google.protobuf") version "0.9.4" }
// dependencies { implementation("com.google.protobuf:protobuf-javalite:3.25.3") }

val user = User.newBuilder()
    .setId(1001L)
    .setName("Alice")
    .setEmail("alice@example.com")
    .addTags("android")
    .addTags("kotlin")
    .setVip(true)
    .build()

// 序列化：得到 ByteArray
val bytes: ByteArray = user.toByteArray()

// 反序列化
val restored = User.parseFrom(bytes)

// 跨端兼容验证：老版本客户端解析新版本数据时，未知字段被跳过
```

### 5. gzip 压缩工具

```kotlin
object Gzip {
    fun compress(data: ByteArray): ByteArray {
        ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { it.write(data) }
            return bos.toByteArray()
        }
    }

    fun decompress(data: ByteArray): ByteArray {
        GZIPInputStream(data.inputStream()).use { gis ->
            return gis.readBytes()
        }
    }
}

// ---------- 使用：先序列化，再压缩（顺序不能反） ----------
val payload = NetJson.default.encodeToString(profile).toByteArray()
val compressed = Gzip.compress(payload)
// 发送
val body = compressed
// 接收端
val raw = Gzip.decompress(body)
val profile = NetJson.default.decodeFromString<UserProfile>(raw)
```

## 常见问题 / 面试要点

**读者问：JSON 和 ProtoBuf 到底怎么选？依据是什么？**

**AI答：**

1. **四个判断维度**：

   | 维度 | 倾向 JSON | 倾向 ProtoBuf |
   | --- | --- | --- |
   | **是否跨语言/跨端** | 无所谓 | 有 Java/iOS/Web/嵌入式 → **ProtoBuf** |
   | **是否有强 schema 契约** | 无（靠 OpenAPI 约束） | 有（`.proto` 单一事实源） |
   | **流量/体积是否敏感** | 不敏感 | **敏感**（IM、埋点、日志上报） |
   | **是否需要人可读/可调试** | 需要 | 不需要 |

2. **体积对比实测（同一模型）**：

   ```json
   {"id":1001,"name":"Alice","email":"alice@example.com","tags":["android","kotlin"],"vip":true}
   ```
   → **91 字节**

   ProtoBuf（同样数据）：
   ```
   08 E9 07                                   // field 1 varint 1001
   12 05 41 6C 69 63 65                       // field 2 string "Alice"
   1A 11 61 6C 69 63 65 40 65 78 61...        // field 3 string email
   22 08 61 6E 64 72 6F 69 64                 // field 4 string "android"
   22 06 6B 6F 74 6C 69 6E                    // field 4 string "kotlin"
   28 01                                       // field 5 bool true
   ```
   → **约 45 字节**，**约为 JSON 的 1/2**（字段少时差距不极端；字段多、名字长时 ProtoBuf 优势更明显，常见 3~5 倍）。

   > 补充一个常被引用的对比：**Google 官方 benchmark** 中 protobuf 比 JSON **小 3~5 倍**、解析**快 5~10 倍**。面试时给出量级即可，不必纠结具体数字。
3. **实践中的"第三条路"**：
   - **JSON + gzip**：服务端/CDN 默认支持，**两端零改动**就能把体积压到接近 ProtoBuf（文本类 JSON 压缩率很高）。**这是性价比最高的方案**。
   - **JSON + 短字段名**：把 `user_name` 改成 `un`，也能省不少，但牺牲可读性。
4. **面试话术**：
   > "我的判断标准是：**先看是否跨语言、是否有 schema 契约**。我们对外的 REST API 用 JSON（可读、易调试、工具链友好），开了 gzip 后体积可接受；**内部 IM 信令和埋点上报**用 ProtoBuf，因为流量大且 schema 由我们控制。**关键不是二选一，而是按通道选型**。"

---

**读者问：Gson 为什么比 Moshi / kotlinx.serialization 慢？慢在哪？**

**AI答：**

1. **Gson 的解析路径**：反射拿 `Field[]` → 读注解 → `JsonReader` 逐 token → `Field.set()` 反射写入。
2. **开销点**：

   | 开销 | 具体表现 |
   | --- | --- |
   | **反射读字段** | `Class.getDeclaredFields()` 遍历 + 读 `@SerializedName` 等注解（Gson 内部**有 TypeAdapter 缓存**，所以不是每次都遍历，但缓存 key 是类型） |
   | **反射写字段** | 每个字段一次 `Field.set(obj, value)`，**含装箱**（`int`→`Integer`→`int`） |
   | **字符串分配** | 每次 `nextName()` 产生新 String；**没有字符串池复用** |
   | **无代码生成** | 没有把"读哪个字段"编译成 switch/静态代码，运行时全靠反射查表 |

3. **Moshi codegen 怎么解决**：
   - KSP 生成 `UserJsonAdapter`：`override fun fromJson(reader: JsonReader): User { reader.beginObject(); var id = 0L; ... when (reader.selectName(options)) { "id" -> id = reader.nextLong(); ... } return User(id, ...) }`
   - **字符串常量做 `when` 分支** → 走 `String.hashCode` + `equals`，比反射快得多；**直接调构造器**，无 `Field.set`。
4. **kotlinx.serialization 为什么更快**：
   - 编译器插件生成 `serializer()`，直接顺序读取字段并调用构造器，**连 `selectName` 的 hash 分派都省了**（它生成的是顺序 if 或字典）。
   - 官方 benchmark 中 kotlinx.serialization 通常是三者中最快的。
5. **实测感受**（粗略量级）：解析 1MB JSON
   - org.json：~150ms
   - Gson：~80ms
   - Moshi（反射）：~60ms
   - Moshi（codegen）：~35ms
   - kotlinx.serialization：~25ms
   （数量级参考，取决于机型与模型）
6. **但更重要的优化不是换库**：
   - **避免在主线程解析** → 这才是 ANR 的根因。
   - **避免超大 JSON 整体解析** → 用流式解析（`JsonReader` 手动逐条读，或 OkHttp 的 `ResponseBody.source()` + `JsonReader`）处理大数组。
   - **复用对象** → Gson 的 `fromJson` 每次新建实例；高频小对象可自己池化。
7. **面试话术**：
   > "Gson 慢的核心是**反射**，尤其是 `Field.set` 写入。Moshi 用 KSP codegen 把反射换成了生成的代码，kotlinx.serialization 用编译器插件更进一步直接生成 serializer。新项目我会选 Moshi codegen 或 kotlinx.serialization；而且**真正的性能问题是主线程解析**，这个必须靠架构解决而不是换库。"

---

**读者问：ProtoBuf 怎么保证跨端兼容？有哪些规则必须遵守？**

**AI答：**

1. **兼容性的底层机制**：ProtoBuf 的二进制格式**只用字段编号，不用字段名**。解码端遇到不认识的编号 → **按 wire type 跳过**；遇到认识的编号但类型不匹配 → 报错。
2. **兼容 vs 不兼容的定义**：

   | 方向 | 含义 |
   | --- | --- |
   | **向前兼容（forward compatible）** | 老客户端能读新服务端发的数据 |
   | **向后兼容（backward compatible）** | 新客户端能读老服务端发的数据 |

   ProtoBuf 的设计目标是**双向兼容**（不像 Avro 只能选一个方向）。
3. **必须遵守的规则**（面试背这三条就够）：

   | 规则 | 为什么 |
   | --- | --- |
   | **① 绝不修改已有字段的编号** | 编号是唯一契约，改了 = 换了个字段 |
   | **② 绝不修改已有字段的类型**（除兼容组） | wire type 变了 → 解码失败 |
   | **③ 删除字段必须 `reserved`** | 防止以后复用编号导致老数据误解 |

4. **允许的变更**：
   - **新增字段**（用新编号）→ 老客户端自动跳过 ✅
   - **新增枚举值** → 老客户端读到未知枚举值会放进 `UNRECOGNIZED`（proto3 会保留在 unknown fields）✅
   - **兼容的类型变更**：
     - `int32` ↔ `int64` ↔ `uint32` ↔ `uint64` ↔ `bool`
     - `fixed32` ↔ `sfixed32` ↔ `float`
     - `fixed64` ↔ `sfixed64` ↔ `double`
     - `string` ↔ `bytes`（需 UTF-8 合法）
5. **不允许的变更**：
   - `string` ↔ `int32`（wire type 不同）
   - `repeated` ↔ `singular`
   - `oneof` ↔ 普通字段
   - 修改 `map` 的 value 类型到不兼容类型
   - 修改 `message` ↔ `scalar`
6. **实战建议**：
   - `.proto` 文件**用 Git 管理**，改前跑 `buf breaking`（Buf 工具的兼容性检查）。
   - **不要删除字段**，只加 `reserved` 并停止使用。
   - **给字段加注释说明语义**，避免"编号不变但含义变了"的**隐性破坏**（这是最危险的：类型对得上但业务含义变了，数据全错）。
7. **面试话术**：
   > "ProtoBuf 的兼容性靠**字段编号**。规则就三条：**编号不改、类型不乱改、删字段要 reserved**。但真正的坑不是技术性的，是**语义性的**——编号和类型都没变，但字段含义变了（比如 `amount` 从分改成元），这种隐性破坏编译器发现不了，只能靠 code review 和 `.proto` 的注释来防。"

---

**读者问：ProtoBuf 的 Varint 和 wire format 是什么？为什么能省空间？**

**AI答：**

1. **wire format**：每个字段在二进制里表示为 `tag + value`：
   ```
   tag = (field_number << 3) | wire_type
   ```
   `wire_type` 只占 3 bit，标识 value 的编码方式：`0=Varint`、`1=64bit fixed`、`2=Length-delimited`、`5=32bit fixed`。
2. **Varint（变长整数）**：把整数按 **7 bit 一组**编码，**每组的最高位是"后续还有组"的标志位**。
   ```text
   300 的编码：
   300 = 0b1_00101100
   组 0（低 7 位）= 0101100 = 0x2C  → 加上续位 = 0xAC
   组 1（剩余）  = 0000010 = 0x02  → 无续位
   结果：0xAC 0x02  → 2 字节
   ```
   - **小数值省空间**：`0` → 1 字节；`300` → 2 字节；`16384` → 3 字节。
   - **大数值有上限**：`int64` 最多 10 字节（而不是固定 8 字节）——**这是 Varint 的缺点**：负数要用 **10 字节**（因为要用 ZigZag 编码或补码转换）。
3. **为什么整体更省**：
   - **字段名消失**：JSON 里 `"email_address"` 占 14 字节，ProtoBuf 里只有 tag 的 1~2 字节。
   - **类型不占空间**：JSON 的 `true`/`false`/数字都要写全；ProtoBuf 的 bool 是 1 字节 varint。
   - **无重复结构标记**：JSON 的 `{}` `[]` `,` `"` 全是开销。
   - **repeated 紧凑编码**：`packed repeated` 把多个值连续存，**每个值只花 1 字节 tag**（普通方式每个元素都要 tag + value）。
4. **代价**：
   - **不可读**：抓包看到的是二进制，**必须能拿到 `.proto` 才能解析**。
   - **JSON 映射不是无损的**：ProtoBuf ↔ JSON 转换需要 `JsonFormat.printer()`，**字段名要写进 Any（JSON）里**（体积又回去了）。
   - **不能用 SQL 直接查**。
   - **调试成本高**：需要 `protoc --decode` 工具。
5. **面试话术**：
   > "ProtoBuf 省空间的核心是**用字段编号代替字段名**，配合 **Varint 对小数值做变长编码**。代价是不可读、必须有 schema、调试必须带 `.proto`。所以我的选型是：对外 API 用 JSON 保持可调试性，内部高频通道用 ProtoBuf 省流量。"

---

**读者问：kotlinx.serialization 的编译器插件做了什么？为什么它比反射快？**

**AI答：**

1. **插件做的事**：Kotlin 编译器在编译 `@Serializable` 类时，**自动生成一个嵌套的 `Companion` 对象和 `xxxSerializer()` 方法**：
   ```kotlin
   @Serializable
   data class User(val id: Long, val name: String)

   // 插件生成的（概念上）：
   public object User$$serializer : KSerializer<User> {
       override val descriptor = buildClassSerialDescriptor("User") {
           element<Long>("id"); element<String>("name")
       }
       override fun deserialize(decoder: Decoder): User {
           val id = decoder.decodeSerializableElement(descriptor, 0, Long.serializer())
           val name = decoder.decodeSerializableElement(descriptor, 1, String.serializer())
           return User(id, name)
       }
   }
   ```
2. **为什么快**：
   - **零反射**：直接调 `Long.serializer().deserialize(decoder)` 与构造器，**没有 `Field.get/set`**。
   - **字段顺序编译期已知**：可以顺序读，而不是运行时遍历字段表。
   - **无 `kotlin-reflect` 依赖**：包体更小。
   - **可裁剪**：没引用到的 serializer 不会生成（部分优化）。
3. **对比三者的"生成时机"**：

   | 库 | 生成时机 | 工具 | 反射 |
   | --- | --- | --- | --- |
   | Gson | **无生成** | — | ✅ 全靠反射 |
   | Moshi | 编译期 | **KSP** | ❌ codegen 模式 |
   | Moshi | 运行时 | — | ✅ 反射模式（需 kotlin-reflect） |
   | kotlinx.serialization | 编译期 | **Kotlin 编译器插件** | ❌ |

4. **插件带来的 Kotlin 特性**：
   - **`sealed class` 多态** → 生成 polymorphic serializer，`Json` 里注册即可。
   - **默认值** → `encodeDefaults = false` 默认不输出默认值字段 → **天然省体积**。
   - **`@SerialName`** → 改 JSON key。
   - **泛型** → `List<User>` 的 serializer 由插件递归生成。
5. **注意**：
   - 插件**必须启用**（`kotlin("plugin.serialization")`），否则 `@Serializable` 只是个普通注解，**没有 serializer**。
   - 版本必须与 Kotlin 版本**严格匹配**。
   - `Json` 的配置**是全局的**（一个 Json 实例一套规则），**不推荐在各处 `Json {}` 各建一个**（每次都新建配置对象 + 缓存失效）。
6. **面试话术**：
   > "kotlinx.serialization 的核心是**编译器插件在编译期生成 serializer**，把 Gson 那种运行时反射变成生成代码。它的额外红利是 Kotlin 原生支持——`sealed class` 多态、默认值不输出、`value class`，这些都是反射方案做不好的。代价是必须绑定 Kotlin 版本，且 `@Serializable` 忘记开插件会静默失效。"

---

**读者问：JSON 解析遇到 null、字段缺失、类型不一致怎么处理？各库的策略是什么？**

**AI答：**

1. **三种"空"的语义**（这是最容易出 bug 的地方）：

   | 情况 | Kotlin | JSON | 语义 |
   | --- | --- | --- | --- |
   | 字段不存在 | `null`（可空类型） | 无 key | **"没有这个信息"** |
   | 字段显式为 null | `null` | `"k":null` | **"明确知道没有值"** |
   | 字段有默认值 | 默认值 | 无 key 或有值 | **"未提供，用默认"** |

2. **各库策略对比**：

   | 库 | 未知字段 | null → 非空字段 | 缺字段（有默认值） |
   | --- | --- | --- | --- |
   | **kotlinx.serialization** | 默认**抛异常**，需 `ignoreUnknownKeys = true` | 抛异常，需 `coerceInputValues = true` | ✅ 自动取默认值 |
   | **Moshi** | ✅ **默认跳过** | 抛异常（`nullSafe()` 只处理整体为 null） | ✅ codegen 支持 |
   | **Gson** | ✅ 默认忽略 | 赋 null（运行时才炸） | ⚠️ 反射模式下**不会调用带默认值的构造** → 字段为 null |
   | **org.json** | ✅ 忽略 | `optXxx` 返回默认值 | ✅ 手动 `optString(k, default)` |

3. **实战建议**：
   - **服务端字段只增不减**（JSON 场景下的兼容策略）→ 客户端**必须开"忽略未知字段"**。
     - kotlinx：`ignoreUnknownKeys = true`
     - Moshi：默认行为
     - Gson：默认行为
   - **可空字段的取舍**：
     - 用 `String?` → 强制处理 null。
     - 用 `String = ""` + `coerceInputValues` → 更省心但**丢失"null vs 空串"的信息**。
   - **大 ID 用 `Long`**：JSON number 是 double 精度（2^53），**超过 9007199254740991 的 ID 会精度丢失** → **必须用 String 传 ID**，或者用 Long + 非 JSON 格式（ProtoBuf）。
4. **常见坑**：
   - **Gson + R8 混淆**：字段名被混淆 → JSON key 全错 → **必须 `@SerializedName` 或 keep 规则**。
   - **kotlinx 默认严格**：忘了 `ignoreUnknownKeys = true` → 服务端加字段 → **全量客户端崩溃**。这是线上事故高发点。
   - **Moshi 的 `nullSafe()`**：默认是 true，表示"整个 JSON 为 null 时返回 null 而不是抛异常"，不是"字段 null 自动转默认值"。
5. **面试话术**：
   > "JSON 的兼容性靠**客户端宽容**。我的三条规矩：**一、服务端字段只增不减；二、客户端一律忽略未知字段**（kotlinx 的 `ignoreUnknownKeys`、Moshi 默认、Gson 默认）；**三、ID 用 String 或 Long 不用 Int**——因为 JSON number 是 double，超过 2^53 会丢精度，这是被坑过很多次的。"

---

**读者问：什么时候需要压缩？gzip 和 zstd 怎么选？序列化顺序有讲究吗？**

**AI答：**

1. **压缩解决的是"字节冗余"，序列化解决的是"结构效率"，两者正交**：
   - JSON 未压缩 → gzip → 体积可能比 ProtoBuf 未压缩还小（文本 JSON 压缩率高）。
   - ProtoBuf 未压缩 → gzip → 增益**远小于**对 JSON 压缩（因为 ProtoBuf 已经没有人类可读冗余了）。
2. **算法对比**：

   | 算法 | 压缩率 | 速度 | Android | 场景 |
   | --- | --- | --- | --- | --- |
   | **gzip** | 基线（~4:1 文本） | 中 | ✅ 系统内置 | HTTP 通用，服务器默认支持 |
   | **zstd** | **优于 gzip**（~5:1） | **更快** | 需三方库 | 移动端自研协议、App 内存储 |
   | **brotli** | 优于 gzip | 快 | 需三方库 | HTTP `Content-Encoding: br`，CDN 常用 |
   | **lz4** | 一般 | 极快 | 需三方库 | 实时性要求高的场景 |

3. **顺序铁律**：**先序列化，再压缩**。
   - 反过来（先压缩再序列化）不成立，因为压缩后的字节不是合法 JSON/binary 格式。
   - 接收端：**先解压，再反序列化**。
4. **什么时候不该压缩**：
   - **数据量很小**（< 1KB）→ gzip 头（约 10~18 字节）+ CPU 开销不划算。
   - **数据已加密** → 压缩率接近 0，且加密前压缩有**侧信道风险**（CRIME/BREACH 类攻击）。
   - **CPU 极度敏感**（低端机、实时场景）。
   - **数据已是压缩格式**（图片、音视频）。
5. **HTTP 层的自动压缩**：
   - OkHttp **默认自动加 `Accept-Encoding: gzip`** 并**自动解压**响应（透明 gzip）。
   - 如果你自己要压缩请求体，需要手动加 `Content-Encoding: gzip` 头，**并关闭 OkHttp 的自动解压**避免双重解压。
6. **面试话术**：
   > "压缩是**在序列化之后**的一层优化。选型上：走 HTTP 就用 gzip，因为服务器和 CDN 默认都支持，客户端零成本；如果是自研长连接协议，我会用 zstd——同样的压缩率但快得多，尤其在低端机上省电省 CPU。**已加密的数据不要压缩**，这是安全实践。"

---

**读者问：ProtoBuf 能直接存进 Room 数据库吗？和 JSON 列怎么选？**

**AI答：**

1. **ProtoBuf 二进制列**（推荐）：
   ```kotlin
   @Entity
   data class CacheEntity(
       @PrimaryKey val key: String,
       @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val payload: ByteArray   // ProtoBuf 序列化结果
   )
   ```
   - 优点：**体积小、读写快**（无 JSON 解析开销）。
   - 缺点：**SQL 无法直接查询内容**；需要用 `TypeConverter` 转成 ByteArray。
2. **JSON 字符串列**：
   ```kotlin
   class JsonConverter {
       @TypeConverter fun toJson(v: UserProfile): String = NetJson.default.encodeToString(v)
       @TypeConverter fun fromJson(s: String): UserProfile = NetJson.default.decodeFromString(s)
   }
   ```
   - 优点：**可读**（`adb shell` 直接查库能看懂）、**部分可查**（`LIKE`）。
   - 缺点：**体积大、每次读写都要解析**。
3. **选型建议**：

   | 场景 | 选择 |
   | --- | --- |
   | 纯缓存、二进制大对象、无查询需求 | **ProtoBuf BLOB** |
   | 需要人工排查数据（客服、运营查库） | **JSON 字符串** |
   | 需要对内容做 SQL 筛选 | **拆成独立列**（不要塞 JSON） |
   | 需要和后端字段强一致 | **JSON**（便于对齐排查） |

4. **更根本的建议**：**不要把大对象塞进数据库列**。数据库存**索引与关系**，大 payload 存**文件**（内部存储），库里只存路径 → 参见 [[../本地存储/文件存储]]。
5. **TypeConverter 的坑**：
   - Room 的 `TypeConverter` **不能返回 null 给非空字段**（会 NPE）。
   - 转换器**每次读写都会被调用** → 复杂序列化有性能成本。
   - `@TypeConverter` 的方法**必须是非静态的实例方法或顶层函数**（Room 生成代码里要调用它）。
6. **面试话术**：
   > "ProtoBuf 可以存 Room，但我更倾向**只把可查询的字段建成列**，大 payload 走文件。非要存的话用 `typeAffinity = BLOB` 存 ProtoBuf 的 ByteArray——体积小、读写快，代价是没法用 SQL 查内容，只能整个读出来在内存里反序列化。"

## 关联笔记

- [[../README]]：第 4 部分总览与数据格式选型主线
- [[../本地存储/DataStore]]：Proto DataStore 直接建立在 Protobuf 之上，是"schema 驱动序列化"的典型落地
- [[../数据库/SQLite与Room]]：数据库中的复杂字段常以 JSON / ProtoBuf 二进制列存储
- [[../序列化原理/Parcelable与Serializable]]：进程内传对象用 Parcelable，持久化/传输用 JSON/ProtoBuf
- [[../本地存储/文件存储]]：大 payload 应该落文件而不是数据库列
- [[../数据共享/MediaStore与数据共享]]：ContentProvider 共享的数据格式选择
- [[../../07-架构、工具与生态/Jetpack库/LiveData与StateFlow]]：解析结果如何转为 UI 状态
- [[../../05-网络与并发/README]]：JSON/ProtoBuf 是网络层的载荷格式，与 OkHttp/Retrofit 强相关

## 参考资料

- Protocol Buffers 官方文档（编码规范与兼容性）：https://protobuf.dev/programming-guides/encoding/
- Protocol Buffers 官方文档（语言指南与兼容性保证）：https://protobuf.dev/programming-guides/proto3/
- kotlinx.serialization 官方文档：https://kotlinlang.org/docs/serialization.html
- Moshi 官方文档（GitHub README）：https://github.com/square/moshi
- Gson 官方文档（用户指南，含 R8 混淆规则）：https://github.com/google/gson/blob/main/README.md
