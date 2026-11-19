---
tags: [android]
part: 第4部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[SharedPreferences]], [[MMKV]], [[../README]]"
---

# DataStore

## 一句话定义

DataStore 是 Jetpack 官方推出的**替代 SharedPreferences 的持久化方案**：用 **protobuf 序列化**保证**类型安全**、用**临时文件 + rename**保证**原子写与崩溃安全**、用 **Flow** 提供**响应式 + 协程**接口，并且**强制单实例（单写者）**以简化一致性模型。

## 核心原理

### 1. 它到底解决了 SP 的哪些问题

| SP 的问题 | DataStore 的解法 |
| --- | --- |
| **类型不安全**：`sp.getString("k", null)` 拿到后 `as Int` 才崩 | `Preferences.Key<Int>` **编译期绑定类型**，key 与类型成对声明，读取时类型不匹配**编译不过** |
| **主线程 IO**：首次 `getSharedPreferences` 同步解析 XML | 所有读操作是 **`Flow`/挂起函数**，天然在协程里执行；官方提供 `dataStore.data.first()` 配合 `Dispatchers.IO` |
| **无原子性**：多次 `edit()` 合并写，中途崩溃可能写一半 | **写临时文件 → fsync → rename 原子替换**，读者永远看到完整的旧版本或完整的新版本 |
| **无响应式**：要自己 `registerOnSharedPreferenceChangeListener` | **`Flow`**：数据变化自动推给所有收集者，天然支持 Compose `collectAsStateWithLifecycle` |
| **无生命周期感知**：listener 持有 Activity 会泄漏 | Flow + `collectAsStateWithLifecycle` 组合，页面不可见自动停止收集 |
| **只能存基本类型**：复杂对象只能塞 JSON 字符串 | **Proto DataStore** 支持任意 **Protobuf message**（含嵌套、集合、枚举） |

### 2. 两种 DataStore

```
                 ┌────────────────────────────┐
                 │        DataStore<T>         │
                 └────────────────────────────┘
                    ▲                ▲
       ┌────────────┘                └────────────┐
       │                                        │
┌──────┴───────────────────┐        ┌───────────┴───────────────┐
│  Preferences DataStore    │        │     Proto DataStore        │
│  T = Preferences          │        │  T = MyProto.Message       │
│  存储：key-value 对        │        │  存储：整个 message         │
│  文件：xxx.preferences_pb  │        │  文件：xxx.pb              │
│  无需写 .proto 文件        │        │  需要写 .proto 并编译      │
│  适合：少量 key 配置        │        │  适合：结构化配置          │
└──────────────────────────┘        └───────────────────────────┘
```

| 维度 | Preferences DataStore | Proto DataStore |
| --- | --- | --- |
| **类型** | `Preferences`（一组 key-value） | 任意 `Message`（Protobuf） |
| **schema** | **不需要** proto 文件 | **需要** `.proto` + protobuf 编译插件 |
| **写入粒度** | 单个 key（`edit { it[key] = v }`） | 整个 message（一次写覆盖全部字段） |
| **读取粒度** | 单个 key（`data.map { it[key] }`） | 整个 message |
| **空对象语义** | key 不存在则用默认值 | 字段有默认值（proto3）/ has 语义（proto2） |
| **适用场景** | 主题、开关、语言、token | 结构化配置（用户偏好、实验参数、多字段配置） |
| **迁移** | 有官方 `SharedPreferencesMigration` | 需手写 `Migration`（对象级） |

> 面试点：**Preferences DataStore 不是"带类型的 SP"这么简单**——它的类型安全来自 `Preferences.Key<T>` 这个**带泛型参数的 key 对象**，而不是来自 key 的名字。名字相同的两个不同类型的 key 是**两个不同的 key**（`intKey("count")` 与 `stringKey("count")` 可以共存）。

### 3. 单实例要求：为什么 DataStore 强制单例

**核心原因：DataStore 的原子写模型建立在"单写者"假设上。**

```
   一次写入的原子替换流程（DataStore 内部）
   ┌────────────────────────────────────────────┐
   │ 1. 在同目录创建临时文件 xxx.tmp             │
   │ 2. 写入新内容                               │
   │ 3. fsync(tmp) —— 保证内容落盘               │
   │ 4. rename(tmp, xxx.preferences_pb)          │
   │    ← POSIX rename 是原子操作                │
   │ 5. fsync(父目录) —— 保证目录项落盘          │
   └────────────────────────────────────────────┘

   读者视角：要么读到旧文件完整内容，要么读到新文件完整内容，
            永远不会读到"写了一半"的文件。
```

- 这个流程**不包含任何"并发写协调"机制**：没有文件锁、没有 CAS、没有版本号。
- 如果**两个 DataStore 实例**指向**同一个文件**：
  - 实例 A 写临时文件 `a.tmp` → rename 成 `xxx.pb`；
  - 实例 B 也写临时文件 `b.tmp` → rename 成 `xxx.pb`；
  - **A 的写会被 B 覆盖**（后写者胜），而且**A 的临时文件可能残留**，还可能出现"rename 时的目标文件已被别人换掉"的窗口。
- 所以 DataStore 在初始化时做**主动检查**：如果发现**同一文件已有活跃实例**，直接抛：
  ```
  IllegalStateException: There are multiple DataStores active for the same file
  ```
- **结论**：**"一个文件 = 一个 DataStore 实例 = 一个进程内唯一写入者"**。

**推论（面试话术）**：
> "DataStore 官方明确说它**不支持多进程**。所以如果你的 App 有多个进程需要共享配置，DataStore 不适用——要么用 **MMKV 的 MULTI_PROCESS_MODE**，要么自己用 **ContentProvider + 文件锁** 封装。"

### 4. 实现原理拆解

```
   DataStore<T>
        │
        ├── Interceptor（拦截器链）── Migration / CorruptionHandler
        │
        ▼
   ┌──────────────────────────────────────┐
   │  DataStoreImpl（Preferences/Proto）    │
   │  ├─ scope: CoroutineScope            │
   │  ├─ data: Flow<T>（共享上游 + 缓存）  │
   │  └─ writeData: suspend (Mutex 保护)   │
   └──────────────────────────────────────┘
        │
        ▼
   ┌──────────────────────────────────────┐
   │  Okio FileSystem（DataStore 依赖 Okio）│
   │  ├─ 读：source() → BufferedSource      │
   │  └─ 写：sink() → 临时文件 → rename     │
   └──────────────────────────────────────┘
```

关键点：

1. **基于 Okio**：DataStore 用 `okio.FileSystem` 做文件读写，Okio 是 Kotlin 的 Okio（前身是 OkHttp 的 IO 层），提供 `Source`/`Sink` 的流式 API。
2. **`Mutex` 串行化写**：同一 DataStore 内的写操作通过 `Mutex` 排队，**保证同一实例内的写是有序的**（这正是"单实例"要求能成立的原因）。
3. **读是共享的 Flow**：内部维护一个 `flow`（`MutableStateFlow` 或 `channelFlow`），所有收集者**共享同一个上游**，只有一次真实的文件读取，**不会 N 个收集者读 N 次文件**。
4. **读缓存**：DataStore 会在内存中保留最近一次读到的数据，后续读取**不重复解析文件**；文件有外部修改时（多进程场景）它不感知。
5. **写后发射**：写成功后向 `flow` 发射新值 → 收集者收到更新。

### 5. 读取的两种方式与生命周期

```kotlin
// A. 一次性读取（适合初始化时取一次值）
val theme = dataStore.data.first()[ThemeKey]   // suspend，一次 IO

// B. 持续订阅（适合 UI 驱动）
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        dataStore.data.collect { prefs ->
            val dark = prefs[ThemeKey] ?: false
            binding.switch.isChecked = dark
        }
    }
}

// C. Compose
val dark by dataStore.data.map { it[ThemeKey] ?: false }
    .collectAsStateWithLifecycle(initialValue = false)
```

> 面试点：`dataStore.data` 的**第一个值**需要真正读文件（可能阻塞），所以**在协程里用 `first()` 或 `collect`**；不要在 `runBlocking` 里同步取（除非明确在 IO 线程）。

### 6. 迁移（Migration）

```kotlin
// 官方提供的 SP → Preferences DataStore 迁移
val context: Context = ...

val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
    // 指定文件路径
    produceFile = { context.preferencesDataStoreFile("app_config") },
    // 迁移：把旧的 SharedPreferences 全量搬过来
    migrations = listOf(
        SharedPreferencesMigration(
            context = context,
            preferencesName = "app_config"   // 旧 SP 的名字
        )
    ),
    scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
)
```

**迁移的执行时机与语义**：
- 迁移是 DataStore 的一个 **Interceptor**，在**第一次读取**时执行（也就是第一次访问 `dataStore.data` 时）。
- `SharedPreferencesMigration` 的行为：读取旧 SP 的**所有 key** → 写入新的 DataStore → **删除旧的 SP 文件**。
- **幂等**：迁移是 DataStore 拦截器链的一部分，**每次读取都会经过拦截器**，但官方实现内部有判断保证**只迁移一次**（旧 SP 文件被删后就不再迁移）。

**自写迁移（Proto DataStore 或自定义字段映射）**：

```kotlin
val migration = object : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences, targetData: Preferences): Boolean {
        // 判断是否需要迁移：例如老版本有 key，新版本没有
        return currentData.asMap().isEmpty() && targetData.asMap().isEmpty()
    }

    override suspend fun migrate(currentData: Preferences, targetData: Preferences): Preferences {
        val old = context.getSharedPreferences("legacy_sp", Context.MODE_PRIVATE)
        val mutable = targetData.toMutablePreferences()
        old.all.forEach { (k, v) ->
            when (v) {
                is Boolean -> mutable[booleanPreferencesKey(k)] = v
                is Int -> mutable[intPreferencesKey(k)] = v
                is String -> mutable[stringPreferencesKey(k)] = v
                is Long -> mutable[longPreferencesKey(k)] = v
                is Float -> mutable[floatPreferencesKey(k)] = v
                is Set<*> -> mutable[stringSetPreferencesKey(k)] = v.filterIsInstance<String>().toSet()
            }
        }
        return mutable
    }
}
```

> ⚠️ **迁移的坑**：
> 1. **迁移和写入会竞争**：迁移通过 `Mutex` 与写操作串行化，所以不会互相覆盖 ✅。
> 2. **迁移失败会抛异常**，导致 `data` Flow 抛错 → 需要用 `runCatching` 或 `DataStore` 的 `corruptionHandler` 兜底。
> 3. **SP 文件删除后无法回滚** → 迁移前要确认数据都已转完（官方实现是写完新文件才删旧的）。
> 4. **多进程下不要做迁移**。

### 7. 损坏处理（CorruptionHandler）

```kotlin
val dataStore = PreferenceDataStoreFactory.create(
    produceFile = { context.preferencesDataStoreFile("app_config") },
    corruptionHandler = ReplaceFileCorruptionHandler { prefs ->
        // 文件损坏（写入被强杀/存储故障）时的兜底：返回一个"干净"的默认值
        prefs.toMutablePreferences().apply {
            this[intPreferencesKey("app_version")] = 0
        }
    }
)
```

- DataStore 读取失败（protobuf 解析失败）时，会调用 `corruptionHandler` 拿到一份"替代数据"，**用它覆盖损坏的文件**，让 App 能继续跑。
- `ReplaceFileCorruptionHandler` 会**把替代数据写回文件**（这本身就是一次原子写，所以安全）。
- 这是 DataStore 相对 SP 的一个重要优势：**SP 文件损坏后 `getXxx` 会抛异常或返回默认值但状态不确定**。

## 代码示例

### 1. Preferences DataStore 完整用法

```kotlin
// ---------- 1. 定义 key（类型安全，编译期绑定） ----------
val ThemeKey = booleanPreferencesKey("theme_dark")
val LangKey = stringPreferencesKey("language")
val LaunchCountKey = intPreferencesKey("launch_count")
val TokenKey = stringPreferencesKey("user_token")
val TagsKey = stringSetPreferencesKey("user_tags")

// ---------- 2. 单例封装（关键：整个进程只有一个实例） ----------
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    companion object {
        @Volatile
        private var INSTANCE: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsStore(create(context.applicationContext)).also { INSTANCE = it }
            }

        private fun create(context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                produceFile = { context.preferencesDataStoreFile("settings") },
                // 从老的 SharedPreferences 迁移一次
                migrations = listOf(
                    SharedPreferencesMigration(
                        context = context,
                        preferencesName = "app_config"
                    )
                ),
                // 损坏时用默认值兜底
                corruptionHandler = ReplaceFileCorruptionHandler { prefs ->
                    prefs.toMutablePreferences()
                },
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            )
    }

    // ---------- 3. 写 ----------
    suspend fun setThemeDark(dark: Boolean) {
        // updateData 是原子操作：读-改-写整个文件
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { this[ThemeKey] = dark }
        }
    }

    suspend fun setToken(token: String?) {
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                if (token == null) remove(TokenKey) else this[TokenKey] = token
            }
        }
    }

    suspend fun increaseLaunchCount() {
        dataStore.updateData { prefs ->
            val cur = prefs[LaunchCountKey] ?: 0
            prefs.toMutablePreferences().apply { this[LaunchCountKey] = cur + 1 }
        }
    }

    // ---------- 4. 读（一次性） ----------
    suspend fun getToken(): String? = dataStore.data.first()[TokenKey]

    suspend fun getThemeDark(): Boolean = dataStore.data.first()[ThemeKey] ?: false

    // ---------- 5. 读（订阅，UI 驱动） ----------
    val themeFlow: Flow<Boolean> = dataStore.data.map { it[ThemeKey] ?: false }

    val tokenFlow: Flow<String?> = dataStore.data.map { it[TokenKey] }
}
```

### 2. Compose 中消费 DataStore

```kotlin
@Composable
fun SettingsScreen(store: SettingsStore = SettingsStore.get(LocalContext.current)) {

    // 生命周期感知收集：页面不可见时自动停止收集，回到前台自动恢复
    val dark by store.themeFlow.collectAsStateWithLifecycle(initialValue = false)
    val token by store.tokenFlow.collectAsStateWithLifecycle(initialValue = null)

    Column {
        Switch(
            checked = dark,
            onCheckedChange = { checked ->
                // 写操作要放到协程里（updateData 是 suspend）
                rememberCoroutineScope().launch { store.setThemeDark(checked) }
            }
        )
        Text("Token: ${token ?: "未登录"}")
    }
}
```

### 3. Proto DataStore 完整用法

```proto
// app/src/main/proto/user_prefs.proto
syntax = "proto3";

option java_package = "com.example.datastore";
option java_multiple_files = true;

message UserPreferences {
  // 深色模式
  bool dark_mode = 1;
  // 语言
  string language = 2;
  // 首页 Tab 索引
  int32 home_tab = 3;
  // 关注的标签
  repeated string followed_tags = 4;
  // 上次同步时间戳（秒）
  int64 last_sync_epoch = 5;
}
```

```kotlin
// build.gradle.kts
plugins {
    id("com.google.protobuf")
}

dependencies {
    implementation("androidx.datastore:datastore:1.1.1")
    implementation("com.google.protobuf:protobuf-javalite:3.25.1")
}

// ---------- Proto DataStore ----------
object ProtoPrefsStore {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val dataStore: DataStore<UserPreferences> = DataStoreFactory.create(
        serializer = UserPreferencesSerializer,   // 见下
        scope = scope
    )
}

/** Protobuf DataStore 的 Serializer：负责序列化/反序列化 + 原子写 */
object UserPreferencesSerializer : Serializer<UserPreferences> {

    override val defaultValue: UserPreferences = UserPreferences.getDefaultInstance()

    override suspend fun readFrom(input: BufferedInputStream): UserPreferences =
        try {
            // 输入流可能读到一半（进程被杀）→ 用 CodedInputStream + 兼容读取
            UserPreferences.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            // 解析失败：按官方建议尝试"读能读的部分"
            throw CorruptionException("Cannot read proto", e)
        }

    override suspend fun writeTo(t: UserPreferences, output: BufferedOutputStream) {
        // CodedOutputStream 会计算 size 并做分块写入
        withContext(Dispatchers.IO) {
            t.writeTo(output)
        }
    }
}
```

> 注：官方还提供了 `ProtoDataStoreSerializer`（配合 `protoDataStoreFile`）与生成的 `xxxSerializer` 扩展，上面手写版便于理解原理。

```kotlin
// 读写 Proto DataStore
class UserPrefsRepo(private val ds: DataStore<UserPreferences>) {

    suspend fun setDarkMode(enabled: Boolean) {
        ds.updateData { it.toBuilder().setDarkMode(enabled).build() }
    }

    suspend fun getDarkMode(): Boolean = ds.data.first().darkMode

    // 订阅：整个 message 作为一个 Flow 发射
    val prefsFlow: Flow<UserPreferences> = ds.data

    // 订阅单个字段（用 distinctUntilChanged 去重，避免重组）
    val darkModeFlow: Flow<Boolean> = ds.data
        .map { it.darkMode }
        .distinctUntilChanged()
}
```

### 4. 用 Hilt 注入 DataStore（推荐工程实践）

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("settings") },
        migrations = listOf(SharedPreferencesMigration(context, "app_config")),
        scope = scope
    )

    @Provides
    @Singleton
    fun provideSettingsStore(
        dataStore: DataStore<Preferences>
    ): SettingsStore = SettingsStore(dataStore)
}
```

> **关键**：用 `@Singleton` 保证**整个进程只有一个实例**，这正是 DataStore 单例要求的正确落地方式。

## 常见问题 / 面试要点

**读者问：DataStore 和 SharedPreferences 到底差在哪？**

**AI答：**

1. **一句话**：**SP 是"配置文件的内存缓存"**，**DataStore 是"有类型、有流、有原子语义的持久化容器"**。
2. **五点差异**：
   - **类型安全**：SP 的 key 是**裸字符串**，取错类型在运行时 `ClassCastException`；DataStore 用 `Preferences.Key<T>` **编译期绑定**，`stringKey("x")` 与 `intKey("x")` 是两个不同的 key。
   - **原子性**：SP 的多次 `edit()` 会合并成一次写，但**写盘过程不是原子的**（临时文件机制在 SP 里也有，但 SP 的全量重写仍可能丢更新）；DataStore 的写是**"临时文件 → fsync → rename"**，POSIX 保证 rename 原子 → 读者**永远看到完整版本**。
   - **响应式**：SP 需要 `registerOnSharedPreferenceChangeListener`（**有内存泄漏风险**、回调在写线程）；DataStore 直接给 **`Flow`**，与 `collectAsStateWithLifecycle` 天然契合 Compose。
   - **崩溃安全**：SP 文件损坏 → 读出来是脏数据或抛异常；DataStore 有 **`CorruptionHandler`** 兜底并**自动修复文件**。
   - **线程模型**：SP 允许主线程调用但**首次调用会阻塞主线程**（同步解析 XML）；DataStore 的 API 全部是 `suspend`/`Flow`，**结构上杜绝了主线程 IO**。
3. **DataStore 的代价**：
   - **每次写 = 一次全量文件写 + rename**（比 SP 的内存写慢，也比 MMKV 慢）。
   - **不支持多进程**（单写者模型）。
   - **不支持加密**（需自行在序列化层处理）。
4. **迁移成本**：官方 `SharedPreferencesMigration` 一行代码搞定，成本很低 → **没有理由不用**。

---

**读者问：为什么 DataStore 强制单实例？多个实例会怎样？**

**AI答：**

1. **机制**：DataStore 用 **"临时文件 + rename"** 实现原子写，**这套机制里没有任何并发写协调**（无文件锁、无 CAS、无版本号）。两个实例写同一个文件 = **后写覆盖先写**，且临时文件会互相踩。
2. **主动检测**：DataStore 在创建时检查**同文件是否已有活跃实例**，若有直接抛：
   ```
   IllegalStateException: There are multiple DataStores active for the same file.
   ```
   这不是 bug，是**刻意的 fail-fast**：宁可启动时崩溃，也不要运行期数据错乱。
3. **单实例还保证**：
   - 写操作通过内部的 **`Mutex`** 串行化 → 同一实例内的写**有序**。
   - 读的 `Flow` **共享同一个上游** → 多个收集者**只读一次文件**，不是 N 次。
4. **工程做法**：
   - 用 **`@Singleton` + Hilt** 提供（推荐）。
   - 或用 **`by lazy` 的伴生对象** + `@Volatile` + `synchronized` 双检锁（示例代码中的写法）。
   - **不要**在每个 Activity/Fragment 里各 `PreferenceDataStoreFactory.create` 一次。
5. **推论 → 多进程不支持**：DataStore 的**单写者假设**天然排除了多进程。如果需要跨进程 KV → **MMKV 的 MULTI_PROCESS_MODE**；跨进程结构化数据 → **Room + WAL** 或 **ContentProvider**。

---

**读者问：Preferences DataStore 和 Proto DataStore 怎么选？**

**AI答：**

1. **判断标准**：
   | 场景 | 选择 |
   | --- | --- |
   | 十几个独立的开关/偏好，每项单独读写 | **Preferences DataStore** |
   | 一组**强相关**的配置（一个 message 的多个字段） | **Proto DataStore** |
   | 需要**频繁整体读写**、字段多且成组 | **Proto DataStore** |
   | 团队不想引入 protobuf 编译流程 | **Preferences DataStore** |
   | 需要**多字段一次性原子更新** | **Proto DataStore**（天然） |
2. **Proto DataStore 的核心优势**：
   - **整体原子性**：一个 message 就是**一个整体**，一次 `updateData` 全部字段一起生效，**不会出现"改了 A 没改 B"的中间态**。
   - **schema 驱动**：字段编号固定 → **向前兼容**（老客户端读新数据忽略未知字段）。
   - **类型丰富**：支持 `repeated`（列表）、`map`、嵌套 message、枚举、`oneof`。
3. **Preferences DataStore 的优势**：
   - **零 schema 成本**：不用写 `.proto`、不用配 protobuf gradle 插件、不用生成类。
   - **单 key 读写粒度细**：`prefs[Key]` 只取一个字段。
4. **面试话术**：
   > "我们的配置分两块：主题、语言这类**独立的偏好**用 Preferences DataStore；**用户资料（昵称、头像 URL、偏好标签列表）**是一组强相关字段，用 **Proto DataStore**，因为它能保证这组字段**整体原子更新**，而且加了新字段后老版本会自动忽略，不会崩。"

---

**读者问：DataStore 的写入是原子的吗？崩溃了会怎样？**

**AI答：**

1. **写流程（内部实现）**：
   ```
   1. 计算新内容（序列化后的字节）
   2. 在同目录写临时文件（如 settings.tmp）
   3. fsync(临时文件)          ← 内容真正落盘
   4. rename(临时文件 → settings.preferences_pb)  ← 原子
   5. fsync(父目录)            ← 目录项落盘
   ```
2. **原子性**：**POSIX `rename` 在同一文件系统内是原子操作**——其他进程的 `open()` 要么拿到旧 inode，要么拿到新 inode，**不会拿到"写了一半"的文件**。
3. **崩溃安全**：
   - 崩溃发生在 3 之前 → 临时文件残留，主文件**完好无损**（下次启动会清理临时文件）。
   - 崩溃发生在 4 和 5 之间 → 文件名已切换但目录项可能未落盘 → 极端掉电可能丢失新版本，**旧版本也不完整**（这是唯一的数据丢失窗口，官方通过 fsync 父目录来尽量收敛）。
4. **一致性读**：因为 rename 原子，**并发读者**永远看到**完整的旧版本或完整的新版本**，不会看到半个文件。
5. **对比 SP**：SP 的写是**直接截断重写原文件**（内部其实也用了临时文件机制 `AtomicFile`，但 XML 全量重写），崩溃时"写了一半"的风险高于 DataStore 的原子 rename。

---

**读者问：DataStore 支持多进程吗？跨进程怎么共享配置？**

**AI答：**

1. **不支持**。官方文档明确：DataStore 是 **single-process** 的，多进程访问**未定义行为**（不保证一致，还可能触发 `IllegalStateException`）。
2. **根本原因**：**单写者模型**（见前题）——原子 rename 机制没有并发写协调；读缓存也不会感知其他进程的写入。
3. **跨进程的替代方案**：
   - **KV 场景** → **[[MMKV]]** 的 `MMKV.MULTI_PROCESS_MODE`（文件锁 + CRC + 变更检测）。
   - **结构化数据** → **[[../数据库/SQLite与Room]]** 且**必须开启 WAL 模式**（跨进程共享 DB 的必要条件），并把写入收敛到单一进程。
   - **少量配置** → 自定义 **[[../数据共享/MediaStore与数据共享|ContentProvider]]**，每次读写都绕过内存缓存。
   - **根本上不要跨进程**：把共享逻辑收敛到**一个进程 + Binder/AIDL 服务**（见第 7 部分"组件间通信"）。
4. **面试话术**："DataStore 的设计是**单进程、单写者**。它换来的是**类型安全 + 原子写 + 响应式**。如果需求是跨进程，我会选 MMKV 或 ContentProvider。"

---

**读者问：怎么把 SP 的老数据迁到 DataStore？迁移期间会不会丢数据？**

**AI答：**

1. **官方方案**：
   ```kotlin
   PreferenceDataStoreFactory.create(
       produceFile = { context.preferencesDataStoreFile("settings") },
       migrations = listOf(
           SharedPreferencesMigration(context, "app_config")  // 旧 SP 名
       )
   )
   ```
2. **迁移的执行时机**：迁移是 DataStore 的 **Interceptor**，在**第一次读取时**触发（不是启动时，也不是写时）。
3. **迁移的顺序与安全性**：官方实现是**先写入新 DataStore，再删除旧 SP 文件** → 中途崩溃时，旧 SP 还在，下次启动继续迁移 → **不会两边都没数据**。
4. **自定义迁移**（字段映射、Proto DataStore）：
   ```kotlin
   object MyMigration : DataMigration<Preferences> {
       override suspend fun shouldMigrate(current: Preferences, target: Preferences): Boolean = true
       override suspend fun migrate(current: Preferences, target: Preferences): Preferences { ... }
   }
   ```
   - **务必**保证 `migrate` 是**幂等**的（可重复执行结果一致）。
   - **迁移是整体替换**（`updateData` 语义），不是"合并"——如果你希望保留新版本已写入的字段，要在 `migrate` 里**先读 current 再合并**。
5. **灰度策略**：迁移完成后**不要立刻删掉 SP 的读取代码**，先保留一版"新读 DataStore，miss 则回落 SP"的逻辑，观察一个版本再删。
6. **注意**：**迁移只在第一次读时执行**，如果你的 App 启动后从未读过 DataStore，迁移就不会发生 → 建议在启动时主动 `dataStore.data.first()` 触发。

---

**读者问：DataStore 的 `data` Flow 读起来是阻塞的吗？会不会 ANR？**

**AI答：**

1. **不会自动阻塞主线程，但会阻塞协程**：`dataStore.data` 是一个 **冷 Flow**，**第一个收集者**会触发真实的文件读取（parse protobuf）。这个读取发生在**收集协程的线程**上。
2. **危险写法**：
   ```kotlin
   // ❌ 在主线程 runBlocking 读 DataStore → 阻塞主线程
   val token = runBlocking { dataStore.data.first()[TokenKey] }
   ```
   这段代码会**卡住主线程**直到文件读完 → 极端情况 ANR。
3. **正确写法**：
   ```kotlin
   // ✅ 挂起函数里读（调用方决定线程）
   suspend fun getToken(): String? = dataStore.data.first()[TokenKey]

   // ✅ 在 IO 线程读
   val token = withContext(Dispatchers.IO) { dataStore.data.first()[TokenKey] }
   ```
4. **UI 层**：`collect` / `collectAsStateWithLifecycle` 在**主线程收集**是安全的——因为 DataStore 内部的上游读取在 `Dispatchers.IO` 上做（官方实现里 `flow` 用 `withContext(Dispatchers.IO)` 读取文件），收集回调回到主线程。
5. **性能建议**：
   - **不要**每个页面各 `collect` 一次导致重复解析——DataStore 的 `data` Flow **共享上游**（内部是一个 `shareIn`/`stateIn` 语义），**多个收集者只读一次文件**。
   - 但如果你在**多个独立作用域**里各建一个 `dataStore.data.map{...}`，它们**共享同一个 DataStore 实例的上游** → 仍然只读一次 ✅（这正是单实例的另一个收益）。

---

**读者问：DataStore 的 `updateData` 会不会有并发问题？为什么它不是线程安全的"裸"操作？**

**AI答：**

1. **`updateData` 是原子读-改-写**：
   ```kotlin
   suspend fun setThemeDark(dark: Boolean) {
       dataStore.updateData { prefs ->
           prefs.toMutablePreferences().apply { this[ThemeKey] = dark }
       }
   }
   ```
   内部是：**加 `Mutex` → 读当前数据 → 应用 transform → 写临时文件 → rename**。整个过程**在锁内**，所以**两个并发 `updateData` 不会互相覆盖**（不会"后写的 transform 基于旧快照，覆盖了前一个的修改"）。
2. **对比 SP**：SP 的 `edit().put...apply()` 是"**读取当前内存 Map → 修改 → 写盘**"，多次 `edit()` 会**合并**（`commitToMemory` 时合并），但**两个线程同时 `edit()`** 时，第二个的 `put` 可能基于**过期的 Map 快照**（SP 在 `commitToMemory` 时会重新读 `mMap`，所以相对安全，但**跨 `edit()` 的多 key 变更不是原子的**）。
3. **DataStore 的"原子"粒度**：
   - `updateData` 的 transform 里的所有修改**要么全部生效，要么全部不生效**。
   - 但**"读-改-写"整体不是跨进程原子的**（单进程内由 Mutex 保证）。
4. **最佳实践**：
   - **不要**在 transform 之外读值再回写（会丢更新）：
     ```kotlin
     // ❌ 竞态：读和写之间可能有别的写
     val cur = dataStore.data.first()[CountKey] ?: 0
     dataStore.updateData { it.toMutablePreferences().apply { this[CountKey] = cur + 1 } }
     ```
   - ✅ **在 transform 里读**：
     ```kotlin
     dataStore.updateData { prefs ->
         val cur = prefs[CountKey] ?: 0
         prefs.toMutablePreferences().apply { this[CountKey] = cur + 1 }
     }
     ```
5. **注意**：transform 里**不要做耗时操作**（网络请求、大量计算）——它**持锁**，会阻塞其他写和读。

## 关联笔记

- [[SharedPreferences]]：DataStore 的替代对象，理解 SP 的不足才能理解 DataStore 的设计
- [[MMKV]]：DataStore 在高频写与多进程场景的替代方案
- [[文件存储]]：大 payload 不该进 DataStore
- [[../数据库/SQLite与Room]]：结构化查询数据走 Room，配置项走 DataStore
- [[../数据格式/JSON与ProtoBuf]]：Proto DataStore 依赖 Protobuf 编解码
- [[../数据共享/MediaStore与数据共享]]：DataStore 不支持多进程，跨进程看这里
- [[../README]]：第 4 部分总览

## 参考资料

- Android 官方 DataStore 文档：https://developer.android.com/topic/libraries/architecture/datastore
- Android 官方 DataStore 迁移指南：https://developer.android.com/topic/libraries/architecture/datastore#migrate
- AOSP `androidx/datastore` 源码（`DataStoreImpl`、`SingleProcessDataStore`）
- AndroidX Preferences DataStore API Reference：https://developer.android.com/reference/kotlin/androidx/datastore/preferences/package-summary
- Okio 官方文档：https://square.github.io/okio/
