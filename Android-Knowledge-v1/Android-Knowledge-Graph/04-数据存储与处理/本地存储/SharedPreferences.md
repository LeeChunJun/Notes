---
tags: [android]
part: 第4部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[DataStore]], [[MMKV]], [[../README]]"
---

# SharedPreferences

## 一句话定义

SharedPreferences 是 Android 提供的**轻量 Key-Value 持久化组件**，本质是"**内存里的一个 Map + 磁盘上的一个 XML 文件**"：写入时把 Map 异步刷成 XML，读写时先命中内存 Map，因此读取极快，但一致性、类型安全与线程模型都有明显短板，新项目应优先选 [[DataStore]]。

## 核心原理

### 1. 本质：XML 文件 + 内存缓存 Map

```
   getSharedPreferences("config", MODE_PRIVATE)
              │
              ▼
   ┌──────────────────────────────┐
   │  SharedPreferencesImpl        │
   │  ├─ Map<String, Object> cache │ ← 真正的数据源（内存）
   │  ├─ File mFile                │ ← /data/data/<pkg>/shared_prefs/config.xml
   │  └─ Queue<Runnable> mWriteQueue│ ← 异步写盘队列
   └──────────────────────────────┘
              │ apply() / commit()
              ▼
   XML on disk（真正的持久化载体）
```

- **读路径**：先从 `cache` 里取 → **不碰磁盘**（除了首次加载）。
- **写路径**：`cache` 立刻更新（内存对调用方立即可见），磁盘刷新行为由 `apply()`/`commit()` 决定。
- **磁盘格式**：一个 `<map>` 根节点，每个 key 一个子节点；`Set<String>` 会被序列化成 `<string name="key">v1</string><string name="key">v2</string>` 这种**重复 name 的怪格式**。

```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="dark_mode" value="true" />
    <int name="launch_count" value="42" />
    <string name="user_token">abc123</string>
    <string name="tags">["a","b"]</string>
</map>
```

> 面试点：SP 的"快"是**用内存缓存换来的**，代价是**跨进程/多实例场景下内存态与磁盘态可能不一致**。

### 2. apply() vs commit()

| 维度 | `apply()` | `commit()` |
| --- | --- | --- |
| 写盘时机 | **异步**（把写盘任务 `post` 到 `mWorkQueue` 线程） | **同步**（在当前线程阻塞写盘） |
| 返回值 | `Unit`（无返回值） | `Boolean`（是否成功写入磁盘） |
| 生命周期回调 | 无 `onCommit` 回调，**进程被杀前会尽量执行 pending 写**（`QueuedWork` 机制） | 无回调，但返回时磁盘已落盘 |
| 主线程调用 | **安全**（不会因写盘阻塞主线程） | **危险**（主线程调用会阻塞 IO → 可能 ANR） |
| 数据丢失风险 | 理论上：进程被 `kill -9` / 崩溃且 pending 写未执行时可能丢 | 返回 true 后数据一定在磁盘上 |
| 适用场景 | 高频写、UI 线程写入 | 需要确认落盘时机（如写入 token 后立刻结束进程） |

**`apply()` 什么时候真正落盘？**

```
apply()
   └─> Enqueue write to queue
         └─> posted to mWorkQueue thread
               └─> 写 XML（持有 mWritingToDiskLock 写锁）
                     └─> 通知 QueuedWork.waitToFinish() 的等待者
```

`ActivityThread.handleStopActivity` / `handleServiceArgs` 等生命周期点会调用 `QueuedWork.waitToFinish()`，**主动等所有 pending 的写盘完成**。所以正常情况下"点返回键退出 App"不会丢数据。

> 面试话术：**"`apply()` 在什么时候会丢数据？"** —— 只有在 `write` 被挂起且**进程被强杀（SIGKILL）或崩溃**时；系统对普通 `kill`（SIGTERM）会先通知进程退出，ActivityThread 会 `waitToFinish()`。所以线上偶现"刚设置完 flag 就被杀导致 flag 丢失"，在低内存杀后台进程的场景确实可能发生。

### 3. 线程安全模型

- `Context.getSharedPreferences(name, mode)` 本身是**线程安全**的：内部用 `synchronized (mLock)` 保护，**同一文件名的 SP 实例在同一个进程内是同一个对象**（在 `ContextImpl` 里按 name 缓存）。
- `getXxx` / `edit()` / `putXxx` 都是**加锁**的（`synchronized (mLock)`），所以多线程并发读写不会导致 Map 结构损坏。
- **但**：`getXxx` 返回的是**对象引用**，如果是 `Set<String>` / 可变对象，你在**事务之外**修改它不会写回文件；而 `putStringSet` 内部会做一次 `HashSet` 拷贝，所以放进去之后改原集合是无效的。

```kotlin
// ✅ 正确：拿到副本再改
val tags = sp.getStringSet("tags", emptySet())!!.toMutableSet()
tags.add("new")

// ❌ 错误：以为改了就是改了
val tags2 = sp.getStringSet("tags", emptySet())!!
tags2.add("new")   // 不影响磁盘，也不影响内存中的原集合
```

### 4. 首次加载会阻塞主线程

```java
// ContextImpl.getSharedPreferences 内部（简化）
public SharedPreferences getSharedPreferences(String name, int mode) {
    synchronized (mPrefCacheLock) {
        SharedPreferences sp = mPrefCache.get(name);
        if (sp == null) {
            sp = new SharedPreferencesImpl(name, mode);   // ← 同步读 XML 解析
            mPrefCache.put(name, sp);
        }
        return sp;
    }
}
```

- `SharedPreferencesImpl` 构造时会**同步读取并解析 XML**（`loadFromDisk`），**在调用 `getSharedPreferences` 的那个线程上执行**。
- 因此：**首次调用 `getSharedPreferences()` 会阻塞主线程**，XML 越大越慢。这是典型的 **ANR 来源**（尤其是首屏初始化里读 SP）。
- 系统在 `ActivityThread` 里提供 `Context.getSharedPreferences` 的预加载？——**没有**，但 Android 提供了一个 workaround：
  - **`QueuedWork`**：SP 的异步写盘会注册到 `QueuedWork`，`ActivityThread` 在 `handleLaunchActivity` 的 `attach` 阶段会 `QueuedWork.waitToFinish()`（等待 SP 的 **写**），但**不等待读**。
  - 官方推荐的规避方式：把 SP 读取放到 **子线程 + 阻塞式等待**（`lifecycleScope.launch(Dispatchers.IO)`），或在 `Application.onCreate` 里于子线程预热。

```kotlin
// Application 中预热 SP，避免首屏主线程解析
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 子线程预热，主线程首次 getSharedPreferences 时缓存已就绪
        Thread { getSharedPreferences("config", MODE_PRIVATE) }.start()
    }
}
```

### 5. 多进程问题（重点坑）

**结论：SP 不支持多进程并发读写。**

- `MODE_MULTI_PROCESS` 常量在 **API 23（Android 6.0）已被废弃**，官方明确说明"不可靠，且将在未来移除"。即便设置也不会真的重新加载文件。
- 更重要的是 **SP 内部有 `mLoaded` 标志**：一个进程只有在 `getSharedPreferences` 第一次调用时才会 `loadFromDisk`，之后**只读内存缓存**。两个进程各自持有各自的内存 Map，**互相看不见对方的修改**。
- 甚至**同进程内的多个实例**如果跨进程使用 ContentProvider 传 SP，也是不可靠的。
- **正确方案**：
  1. 跨进程共享少量配置 → 用 **ContentProvider** 封装（自己实现读写，每次都读文件）。
  2. 跨进程共享 → 用 **`MODE_MULTI_PROCESS` 已被废弃，改用 DataStore + 多进程支持？**——注意：**DataStore 官方也不支持多进程**（DataStore 的单写者模型假设单进程）。
  3. 跨进程共享 → 真正的答案通常是 **MMKV（`MMKV.MULTI_PROCESS_MODE`）** 或 **Room + WAL + 文件锁** 或 **直接用 Binder 服务**。

### 6. 性能问题清单

| 问题 | 表现 | 对策 |
| --- | --- | --- |
| **首次读阻塞主线程** | 首屏卡顿、ANR | 子线程预热 / 改用 DataStore |
| **全量解析 XML** | 每次 `getSharedPreferences` 新实例都要全量解析 | 文件别太大，key 别太多（建议 < 100 个） |
| **commit() 阻塞主线程** | ANR | 用 `apply()`，或在 IO 线程 `commit()` |
| **Set\<String\> 存集合** | 序列化成重复 `<string name>`，读取时还要特殊解析 | 存 JSON 字符串，或换 MMKV / DataStore |
| **大 value（几 MB 的 JSON）** | 内存翻倍 + 解析慢 | 存文件（见 [[文件存储]]）而不是 SP |
| **无类型安全** | 取错类型抛 `ClassCastException` | 用 DataStore 的 `Preferences.Key<T>` |
| **无事务/无原子性** | 多个 `edit()` 合并成一次写，中间崩溃可能写一半 | 用 `edit()` 批量提交；DataStore 原生原子 |

### 7. SP 的 XML 写入机制（写放大）

```java
// SharedPreferencesImpl.EditorImpl.commitToMemory()
for (Map.Entry<String, Object> e : mModified.entrySet()) {
    String key = e.getKey();
    Object value = e.getValue();
    if (value instanceof Set) {
        Set<String> set = (Set<String>) value;
        List<String> values = new ArrayList<>(set);
        writeSet(map, key, values);
    } else if (value instanceof String) {
        writeString(map, key, (String) value);
    } else if (value instanceof Integer) { ... }
    // 未被删除且未被修改的 key 从旧 XML 里原样拷过来
}
```

注意最后一行：**SP 是"全量重写"**——每次写盘都会把**所有 key** 重新序列化一遍（未修改的 key 从旧文件里原样搬运）。key 越多、value 越大，单次写盘成本越高。

## 代码示例

### 1. 正确使用 SharedPreferences（封装一层）

```kotlin
/**
 * SharedPreferences 封装：
 * 1. 统一走 apply()，避免主线程 commit 造成 ANR
 * 2. 集合类型统一存 JSON 字符串，避免 XML 重复 name 的怪格式
 * 3. 提供 init() 供子线程预热
 */
class AppPrefs(context: Context) {

    private val appContext = context.applicationContext
    private val sp: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val PREF_NAME = "app_config"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_TOKEN = "user_token"
        private const val KEY_TAGS = "user_tags"
    }

    /** 子线程预热：让 XML 解析发生在非主线程，避免首屏 ANR */
    fun warmUp() {
        Thread { sp.getString(KEY_TOKEN, null) }.start()
    }

    /** 写：apply 异步返回，不阻塞主线程 */
    fun setThemeMode(mode: Int) {
        sp.edit().putInt(KEY_THEME, mode).apply()
    }

    /** 读：内存命中，极快；首次访问会触发 XML 解析（可能阻塞） */
    fun getThemeMode(): Int = sp.getInt(KEY_THEME, ThemeMode.SYSTEM)

    fun setToken(token: String?) {
        sp.edit().apply {
            if (token == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, token)
        }.apply()
    }

    fun getToken(): String? = sp.getString(KEY_TOKEN, null)

    /** 集合：序列化成 JSON 字符串存储，读时反序列化 */
    fun setTags(tags: List<String>) {
        sp.edit().putString(KEY_TAGS, Json.encodeToString(tags)).apply()
    }

    fun getTags(): List<String> {
        val raw = sp.getString(KEY_TAGS, null) ?: return emptyList()
        return runCatching { Json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    /** 需要确认落盘时（例如写入登录态后立刻 finish 进程）才用 commit */
    fun setTokenAndFlush(token: String): Boolean {
        return sp.edit().putString(KEY_TOKEN, token).commit()
    }
}
```

### 2. 监听变化 + 跨页面同步

```kotlin
// 注册监听（注意：持有 Context 要用 applicationContext，否则泄漏）
class ConfigWatcher(private val prefs: SharedPreferences) :
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "theme_mode" -> AppCompatDelegate.setDefaultNightMode(
                prefs.getInt("theme_mode", ThemeMode.SYSTEM)
            )
            "user_token" -> TokenStore.onTokenChanged(prefs.getString("user_token", null))
        }
    }
}

// 注册 / 反注册要成对：onCreate 注册，onDestroy 反注册
class SettingsActivity : AppCompatActivity() {
    private val watcher = ConfigWatcher(
        getSharedPreferences("app_config", MODE_PRIVATE)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSharedPreferences("app_config", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(watcher)
    }

    override fun onDestroy() {
        getSharedPreferences("app_config", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(watcher)
        super.onDestroy()
    }
}
```

> ⚠️ **内存泄漏坑**：`registerOnSharedPreferenceChangeListener` 会让 SP 持有 listener 强引用；如果 listener 持有 Activity，就泄漏了 Activity。**必须传 applicationContext 拿 SP，且在 `onDestroy` 反注册**。

### 3. 多进程场景的正确替代方案

```kotlin
/**
 * 跨进程共享配置：用 ContentProvider 暴露读接口，
 * 内部每次都从磁盘重新读取，绕过 SP 的进程内缓存。
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        // 每次查询都新建 SP 实例 → 触发 loadFromDisk，拿到别的进程写的最新值
        val sp = context!!.getSharedPreferences("shared_config", Context.MODE_MULTI_PROCESS)
        val cursor = MatrixCursor(arrayOf("key", "value"))
        sp.all.forEach { (k, v) -> cursor.addRow(arrayOf(k, v?.toString())) }
        return cursor
    }

    // ... insert / update / delete 同样每次新建实例后 commit()
}
```

> 更推荐的跨进程方案见 [[MMKV]]（原生支持多进程）与 [[../数据共享/MediaStore与数据共享]]（ContentProvider 正确用法）。

### 4. 用 SharedPreferences 存枚举/复杂对象的坑与绕过

```kotlin
// ❌ 坑：直接存 Int 状态码，换枚举顺序就崩
sp.edit().putInt("login_state", 2).apply()

// ✅ 更好：存稳定的字符串 key
sp.edit().putString("login_state", LoginState.TOKEN_VALID.name).apply()

// 读取时用 when 兜底未知值（未来新增枚举也不会崩）
fun loginState(): LoginState =
    when (sp.getString("login_state", null)) {
        "NONE" -> LoginState.NONE
        "TOKEN_VALID" -> LoginState.TOKEN_VALID
        else -> LoginState.NONE   // 兜底，防止脏数据导致崩溃
    }
```

## 常见问题 / 面试要点

**读者问：`apply()` 和 `commit()` 有什么区别？什么时候用哪个？**

**AI答：**

1. **本质区别**：两者都先**同步更新内存 Map**（所以调用后立刻 `getXxx` 就能读到新值），区别只在**写盘时机**：
   - `apply()`：把写盘任务**异步**投递到 SP 内部的写队列线程，**立即返回**（返回 `Unit`）。
   - `commit()`：在**当前线程同步**写盘，**阻塞**直到写完，返回 `Boolean` 表示是否成功。
2. **线程安全**：两者**都可以在主线程调用而不会"报错"**，但 `commit()` 在主线程会**阻塞 IO**，数据量大时可能 **ANR**；`apply()` 主线程安全。
3. **落盘保证**：
   - `commit()` 返回 `true` → 数据**一定已经在磁盘上**。
   - `apply()` → 还没落盘，但 `ActivityThread` 会在 `handleStopActivity` / `handleServiceArgs` 等时机调用 `QueuedWork.waitToFinish()` **等所有 pending 写完成**；所以**正常的返回键退出不会丢数据**。
   - **例外**：进程被 `SIGKILL` 强杀、Native Crash 导致进程直接死、或在写盘前系统杀后台进程 → pending 写丢失。
4. **什么时候用 `commit()`**：
   - 写入"必须保证落盘后才能做下一步"的**关键状态**（如 token 落盘后立刻跳转到另一个进程）。
   - 需要知道写盘**是否成功**（返回值）。
   - **在 IO 线程上**调用 `commit()`（如 `Application.onCreate` 的后台初始化里批量写配置）。
5. **什么时候用 `apply()`**：绝大多数场景，尤其 UI 线程写开关、状态、缓存。
6. **常见坑**：
   - 面试题最爱追问 **"apply 会丢数据吗"** —— 答"正常不会，强杀/崩溃会"，并能说出 `QueuedWork` 机制，就是加分项。
   - `apply()` **没有回调**，想知道何时落盘只能自己用 `commit()` 或等 `QueuedWork.waitToFinish()`。

---

**读者问：SharedPreferences 是线程安全的吗？多线程并发读写会不会有问题？**

**AI答：**

1. **同一进程内是线程安全的**：
   - `getSharedPreferences(name, mode)` 在 `ContextImpl` 里用 `synchronized (mPrefCacheLock)` 保护，**同名 SP 在同进程内是同一个实例**（缓存在 `mPrefCache`）。
   - `SharedPreferencesImpl` 内部所有读写都持有 `mLock`（`synchronized`），**不会破坏 Map 结构**。
2. **但要小心返回值引用**：`getStringSet` 等返回的是**内部集合的引用**（`getStringSet` 返回的是一份拷贝，但 `getString` 返回的 `String` 不可变所以安全）；**在事务外修改返回的可变对象不会写回磁盘**。
3. **主线程阻塞点**：虽然线程安全，但 `getSharedPreferences()` 的**首次调用要同步解析整个 XML**，发生在调用线程上 → **主线程调用 = 主线程 IO = 潜在 ANR**。
4. **写盘串行**：`apply()` 的写盘任务在同一写队列上**串行执行**，且写盘期间会持锁，**读操作会等写完成**（`mWritingToDiskLock`），所以**高频写 + 同时读**时读会被拖慢。
5. **结论**：**线程安全 ≠ 高效**。真正的问题不是并发正确性，而是**主线程 IO、一致性和类型安全**。

---

**读者问：SharedPreferences 支持多进程吗？为什么 `MODE_MULTI_PROCESS` 被废弃了？**

**AI答：**

1. **结论：SP 官方不支持多进程**，`MODE_MULTI_PROCESS` 自 **API 23（Android 6.0）起被废弃**，文档写明"not reliable and will be removed in a future release"。
2. **根因一：内存缓存与磁盘不同步**。
   - `SharedPreferencesImpl` 有 `mLoaded` 标志：**只有进程内第一次 `getSharedPreferences` 时才会 `loadFromDisk()` 读 XML**。
   - 之后所有读都命中内存 Map。**进程 A 改了值并写盘，进程 B 的内存 Map 不会自动刷新** → B 读到的是**过期值**。
   - `MODE_MULTI_PROCESS` 只是"在**每次** `getSharedPreferences` 时重新加载文件"，并不能解决**两个进程同时写**导致的**丢更新**（A 写的被 B 的旧 Map 全量覆盖回去）。
3. **根因二：写盘是"全量重写 + 无文件锁"**。SP 写 XML 时没有跨进程文件锁，两个进程并发写 → **文件损坏 / 数据丢失**。
4. **根因三：应用启动时 SP 会被预加载**（`ActivityThread` 会为当前包预加载 SP 目录），多进程下更易出现"读到半截文件"。
5. **正确方案**：
   - **少量跨进程配置** → 自己写 `ContentProvider` 暴露，每次操作**新建 SP 实例**强制重读（仍不完美但比直接用强）。
   - **需要真正跨进程 KV** → **MMKV**，通过 `MMKV.MULTI_PROCESS_MODE` + 文件锁 + CRC 校验实现。
   - **数据库跨进程** → Room + **WAL 模式**（跨进程共享必须开 WAL）+ 多进程协调（`ContentProvider` 作为唯一写入入口）。
   - **彻底不要跨进程** → 把共享逻辑收敛到**一个进程 + Binder/AIDL 服务**。

---

**读者问：SP 第一次读取会阻塞主线程，那怎么优化？会不会 ANR？**

**AI答：**

1. **会**。`getSharedPreferences()` 构造 `SharedPreferencesImpl` 时**同步**执行 `loadFromDisk()`：读文件 → `XmlUtils` 解析 → 构建 Map。整个过程在**调用方线程**。XML 达到几百 KB（存了一大坨 JSON）时，主线程阻塞上百毫秒是常事，**ANR 阈值是 5s 输入事件 / 10s 广播**，极端场景确实会 ANR。
2. **优化手段**：
   - **子线程预热**：`Application.onCreate` 里起线程 `getSharedPreferences(...)`，让 XML 解析在后台完成；主线程后续访问直接命中缓存。
   - **把 IO 移出主线程**：`lifecycleScope.launch(Dispatchers.IO) { ... }` 里读取再 `withContext(Main)` 更新 UI。
   - **减小 SP 体积**：不要把大 JSON / 列表塞进 SP，改用文件（[[文件存储]]）或数据库（[[../数据库/SQLite与Room]]）。
   - **换方案**：改用 [[DataStore]]（读第一次也要解析，但可以用 `DataStore.data.first()` 在协程里做，配合 `runBlocking` 也建议放在 IO 线程）。
3. **注意**：`QueuedWork.waitToFinish()` 在 `ActivityThread` 的**部分**生命周期点被调用，它等的是 **SP 的写**（`apply()` 的 pending 写），**不解决读的问题**。很多面试者会把这俩搞混。

---

**读者问：SP 能存多大的数据？为什么不建议把集合或大 JSON 存进去？**

**AI答：**

1. **没有官方硬上限**，但受**内存**与**主线程 IO**双重限制。实践上：
   - **key 数量**：建议 < 100 个，因为每次写盘是**全量重写**。
   - **单个 value**：建议 < 100KB；几 MB 的 JSON 放进 SP 会让内存占用翻倍（XML 文本 + Map 里的 String 各一份）且解析极慢。
2. **存 `Set<String>` 的坑**：SP 把 Set 序列化成**多个同名 `<string>` 标签**：
   ```xml
   <string name="tags">a</string>
   <string name="tags">b</string>
   ```
   读取时靠"同名标签合并"还原。问题：
   - XML 体积膨胀（每个元素带完整标签名）。
   - 语义脆弱——同名标签可能被别的逻辑误读。
   - **正确做法**：存 **JSON 字符串**（`TypeToken` / `kotlinx.serialization`），或改用 MMKV（原生支持集合）。
3. **存大 JSON 的问题**：
   - 读一次要**全量反序列化**成对象，但 SP 只能存**基本类型**（String/Int/Boolean/Float/Long/StringSet），**存不了对象**，只能存 String。
   - 每次读都要 `JSON.parse` → 主线程成本。
   - **结论**：大 JSON 应该落文件（[[文件存储]]），结构化查询应该落数据库（[[../数据库/SQLite与Room]]）。
4. **面试话术**："SP 是**配置存储**，不是**数据存储**。用它存主题、开关、token、首次启动标记这类小而关键的状态；列表、缓存、大 payload 一律走文件或数据库。"

---

**读者问：SP 的 `get` 为什么这么快？它的性能瓶颈在哪？**

**AI答：**

1. **快的原因**：所有数据都在**内存 Map** 里，`getXxx` 就是 `HashMap.get`，**零 IO**。
2. **真正的开销在两个地方**：
   - **首次加载**：全量解析 XML（一次性成本）。
   - **写盘**：每次写盘都**全量重新序列化所有 key**（未修改的 key 从旧 XML 原样搬运）→ 写放大。
3. **写盘串行化**：`apply()` 的写任务在**单一写线程**上排队，写盘期间持有 `mWritingToDiskLock` → **读操作会被阻塞**。高频写 + 并发读 = 读写互相拖慢。
4. **对比**：
   | 方案 | 读 | 写 | 一致性 | 类型安全 |
   | --- | --- | --- | --- | --- |
   | SharedPreferences | 内存 Map，纳秒级 | 异步全量重写 XML | 弱（多进程不同步） | 无 |
   | [[MMKV]] | mmap 内存直读 | mmap + CRC，几乎零 IO | 强（多进程支持） | 有类型 API |
   | [[DataStore]] | Flow + 原子读 | 原子写（临时文件 + rename） | 强（单进程） | 强（`Key<T>`） |
5. **性能建议**：
   - 高频写（每帧/每秒）→ 用 **MMKV**。
   - 结构化、需查询 → **Room**。
   - 配置项 → **DataStore**。

---

**读者问：`registerOnSharedPreferenceChangeListener` 有什么坑？**

**AI答：**

1. **内存泄漏**：SP 会**强引用** listener。若 listener 持有 Activity/View，**Activity 泄漏**（SP 是长生命周期对象）。规范做法：
   ```kotlin
   val sp = context.applicationContext.getSharedPreferences("cfg", MODE_PRIVATE)
   // 或在 onDestroy 里反注册
   ```
2. **回调在哪个线程？** → **写线程**（`apply` 的写盘线程）或 **commit 的调用线程**，**不是主线程**。所以回调里更新 UI 必须切回主线程：
   ```kotlin
   override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
       Handler(Looper.getMainLooper()).post { /* 更新 UI */ }
   }
   ```
3. **触发时机**：
   - `apply()` → 在**内存 Map 更新后、写盘前**回调（实际是 `commitToMemory` 里通知），**写盘失败也会通知过**。
   - `commit()` → 内存更新时通知一次，**写盘成功后不再通知**（SP 不会区分"内存变了"和"落盘了"）。
   - **清除全部** (`clear()`) → `key` 参数为 **`null`**。
4. **不会因为别的进程改了文件而触发**（多进程监听失效的根本原因）。

---

**读者问：怎么把已有的 SP 数据迁移到 DataStore？迁移期间如何保证不丢数据？**

**AI答：**

1. **官方迁移 API**（`androidx.datastore:datastore-preferences`）：
   ```kotlin
   val dataStore = PreferenceDataStoreFactory.create(
       produceFile = { context.preferencesDataStoreFile("app_config") }
   )

   // 在 DataStore 首次创建时从 SP 导入一次
   val migrated = AtomicBoolean(false)
   val ds: DataStore<Preferences> = DataStoreFactory.create(
       corruptionHandler = null,
       migrations = listOf(
           // 官方提供的迁移对象：把 SharedPreferences 全量搬到 Preferences DataStore
       ),
       scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
   ) { "app_config.preferences_pb" }
   ```
   官方实际提供的是 `SharedPreferencesMigration`（配合 `androidx.datastore:datastore-preferences-rxjava3` / `datastore-migration`）：
   ```kotlin
   val migration = SharedPreferencesMigration(
       context,           // 读取旧的 SP
       produceFile = { context.preferencesDataStoreFile("app_config") }
   )
   ```
2. **迁移的正确姿势**（避免"SP 已清空但 DataStore 还没写完"的窗口）：
   - **先写 DataStore，再清 SP**，顺序不能反。
   - 用 `DataStore.updateData { }` 做**幂等**迁移（可以重复执行，结果一致）。
   - 迁移放在 **IO 线程的协程**里，不要放主线程。
3. **灰度期策略**：新版本**同时读两者**（DataStore 优先，miss 再回落 SP），等确认覆盖后再删除 SP 读取路径。
4. **不要自己写 `apply()` 到 SP 再删 SP 文件**——**中间崩溃会两边都没数据**。用 `commit()` 或"写新→删旧"的顺序。

---

**读者问：面试官问"你的项目为什么用 SP / 不用 SP"，你怎么说？**

**AI答：**

先给**判断标准**（这是加分点），再给**迁移路径**：

1. **不该用 SP 的信号**：
   - 需要**类型安全**（key 的类型在编译期确定）。
   - 需要**响应式**（数据变化要驱动 UI 刷新）→ `Flow`。
   - 需要**原子性**（多个 key 要么一起生效要么都不生效）。
   - key 多、value 大、写入频繁。
   - 需要**跨进程**读写。
2. **换什么**：
   - 配置项、类型安全、响应式 → **DataStore**（[[DataStore]]）。
   - 高频写、大 value、需要跨进程 → **MMKV**（[[MMKV]]）。
   - 结构化数据、需要查询 → **Room**（[[../数据库/SQLite与Room]]）。
3. **迁移话术**：
   > "我们原来的 SP 有 30 多个 key，其中 `user_profile` 存了 1MB JSON，而且是在主线程用 `commit()` 写的，低端机上首屏有 200ms 卡顿。改造分两步：先把 JSON 挪到文件（走流式读写），剩下的配置项迁到 **Preferences DataStore**，用官方 `SharedPreferencesMigration` 在 DataStore 初始化时一次性导入，导入走 IO 线程并且**先写后删**；上线后用 `DataStore` 的 `Flow` 驱动 UI，彻底去掉了主线程读 SP 的路径。"
4. **加分补充**：说出 SP 的**内存缓存 + 全量 XML 重写**这个本质，说明你**知道瓶颈在哪**，而不是只知道"SP 老"。

## 关联笔记

- [[DataStore]]：官方替代方案，类型安全 + Flow + 原子写
- [[MMKV]]：mmap 实现的高性能 KV，支持多进程与加密
- [[文件存储]]：大 JSON、下载文件应该落盘而不是 SP
- [[../数据库/SQLite与Room]]：结构化数据与需要查询的场景
- [[../序列化原理/Parcelable与Serializable]]：SP 只能存基本类型，复杂对象如何存
- [[../数据共享/MediaStore与数据共享]]：跨进程/跨应用共享数据的正确姿势
- [[../README]]：第 4 部分总览

## 参考资料

- Android 官方 SharedPreferences 文档：https://developer.android.com/reference/android/content/SharedPreferences
- Android 官方 DataStore 迁移指南：https://developer.android.com/topic/libraries/architecture/datastore
- AOSP `frameworks/base/core/java/android/app/SharedPreferencesImpl.java` 源码
- 《Android 高级编程》第 4 版 · 第 8 章"数据持久化"
- 微信 MMKV 团队博客《MMKV 概述与原理》：https://github.com/Tencent/MMKV/wiki/Overview
