---
tags: [android]
part: 第4部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[SharedPreferences]], [[DataStore]], [[../README]]"
---

# MMKV

## 一句话定义

MMKV 是微信开源的**基于 mmap（内存映射）的高性能 Key-Value 存储**，把数据以 protobuf 编码后**直接映射到内存页**上，读写几乎不产生 IO 与用户态/内核态拷贝，并原生支持**多进程**与 **AES 加密**，是替代 [[SharedPreferences]] 处理"高频写、大 value"场景的首选。

## 核心原理

### 1. mmap 是什么？为什么 MMKV 快

```
   普通文件 IO（write）                    mmap（MMKV）
   ┌──────────────┐                      ┌──────────────┐
   │ 用户态 buffer│                      │  用户态直接  │
   │      ↓ copy  │                      │  读写映射页   │
   │ write() syscall ↓                  │  缺页 → 由OS  │
   │      ↓       │                      │  把文件页换入  │
   │  内核 page cache │                  │  物理内存     │
   │      ↓       │                      └──────────────┘
   │  落盘 (fsync)│                             │
   └──────────────┘                      写入即内存写，
                                          内核异步回写磁盘
   → 2 次拷贝 + 至少 2 次系统调用           → 0 次拷贝 + 0 次系统调用
```

- `mmap` 把文件的一段**虚拟地址**映射到进程地址空间，之后**像访问内存一样读写文件**，由内核负责**缺页调入**与**脏页回写**。
- 关键收益：
  1. **零拷贝**：不需要 `read()` 到用户态 buffer 再拷一份。MMKV 读一个 int 就是**一次内存寻址**。
  2. **零系统调用**（写路径）：`memcpy` 到映射页即可，内核在合适时机回写。
  3. **写延迟合并**：连续写只产生**一次**回写。

### 2. MMKV 的文件布局

```
┌──────────────────────────────────────────────────────┐
│  Actual Size (4 bytes)   ← 有效数据长度               │
├──────────────────────────────────────────────────────┤
│  CRC32 (4 bytes)         ← 校验和（加密模式有额外头）  │
├──────────────────────────────────────────────────────┤
│  Padding                 ← 对齐填充                   │
├──────────────────────────────────────────────────────┤
│  Key1 (protobuf 编码)                                │
│  Value1 (protobuf 编码)                              │
│  Key2, Value2 ...                                     │
└──────────────────────────────────────────────────────┘
```

- **Key 和 Value 都用 protobuf 变长编码**（`writeString` / `writeInt` 等把对象编码成字节流）。
- 读取时**从头扫描**以定位 key → 复杂度 O(n)，但 n 通常很小（几十上百个 key），且是**内存扫描**，比磁盘 IO 快几个数量级。
- **CRC32**：对整个数据区做校验，**文件损坏时能检测出来**（MMKV 会把损坏的实例标记为无效并返回默认值，而不是读到脏数据）。
- **加密模式**：文件头变为 `[IV][cryptKey][actualSize][CRC]`，数据区用 **AES-128-CFB** 加密。

### 3. 写入流程：append + full write back

```
   encode("key", value) → protobuf bytes
              │
              ▼
   写入已 mmap 的内存区域（memcpy）
              │
              ▼
   更新内存中的 key → offset 索引（Dictionary）
              │
              ▼
   标记 dirty，等系统回写（msync / 内核回写线程）
```

- MMKV 维护一个**内存索引 Dictionary**（key → offset），写入时**追加**到文件尾部（append），旧的同名 key 变成"垃圾数据"，读取时以**最新的 offset** 为准。
- 文件满了会触发 **full write back**：把存活数据重新序列化到文件头，回收垃圾空间。
- **因此 MMKV 的写入是"追加式"的**，不是 SP 的"全量重写" → 写放大远小于 SP。

### 4. 多进程支持

| 模式 | 常量 | 说明 |
| --- | --- | --- |
| 单进程 | `MMKV.SINGLE_PROCESS_MODE` | 默认。启用 mmap 的 `MAP_SHARED` 但不做跨进程协调 |
| 多进程 | `MMKV.MULTI_PROCESS_MODE` | 启用**文件锁（`fcntl` flock）+ CRC 校验**，写前检查文件是否有变化并重新加载 |

多进程模式下的协调机制：

```
   进程 A 写入                      进程 B 读取
   ─────────                      ─────────
   flock(LOCK_EX)  ──┐
   检查 mtime/CRC     │            flock(LOCK_SH)
   若变化 → 重新 mmap │◄──────────  检查 mtime/CRC
   写入 + 更新索引    │            若变化 → 重新加载
   unlock            │            返回最新值
   ────────────────┘
```

- 多进程模式下，MMKV 会**周期性地（默认 1s，可在 `setCheckProcessMode`/`MMKV` 构造时配置）检查**其他进程是否修改了文件，发现变化就**重新 mmap 并重建索引**。
- **代价**：多进程模式**有额外的锁开销与检查开销**，单进程场景不要开。

### 5. 加密能力

```kotlin
// AES-128-CFB 加密，密钥要求 16/24/32 字节（对应 AES-128/192/256）
val mmkv = MMKV.mmkvWithID("secret", MMKV.MULTI_PROCESS_MODE, "MySecretKey123456")
mmkv.encode("token", "abcdefg")
```

- 密钥**直接传字符串**，需要**自行管理密钥的生命周期**（更安全的做法是把密钥放进 Android **Keystore**（见 [[文件存储]]），MMKV 不支持从 Keystore 直接读密钥，需要在初始化时读出来）。
- **注意**：密钥写死在代码里 = 密钥泄露 = 加密形同虚设。面试要点：**"加密的密钥应该存在 Android Keystore / TEE 里"**。
- MMKV 的加密是**对称加密**，性能开销在可接受范围（实测比未加密慢 10%~20%，远小于 SP 的差距）。

### 6. 三大 KV 方案横向对比

| 维度 | SharedPreferences | MMKV | DataStore |
| --- | --- | --- | --- |
| 底层 | XML 文件 + 全量解析 | **mmap + protobuf** | protobuf 文件 + 原子写 |
| 读性能 | 内存 Map，极快（纳秒级） | 内存直读，**接近零开销** | 需反序列化 + Flow 发射 |
| 写性能 | 异步全量重写 XML | **追加写，几乎无 IO** | 临时文件 + rename（原子） |
| 写放大 | **高**（全量重写） | **低**（追加 + 定期整理） | 中（全量写新文件） |
| 类型安全 | ❌ 无（`getXxx` 运行时才报错） | ✅ 有（泛型 API） | ✅✅ 强（`Preferences.Key<T>`） |
| 协程/Flow | ❌ | ❌（有 `asFlow`） | ✅ 原生 |
| 原子性 | ❌（多次 edit 合并，但跨进程不保证） | ✅（单次 write 原子） | ✅（rename 原子） |
| 多进程 | ❌ 不支持 | ✅（MULTI_PROCESS_MODE） | ❌ 不支持（单写者模型） |
| 加密 | ❌ | ✅ AES | ❌（需自行处理） |
| 集合/复杂类型 | ⚠️ 存 JSON 字符串 | ✅ 原生支持集合 | ✅ Proto DataStore 支持 |
| 官方支持 | 是（已不推荐新用） | 第三方（腾讯开源） | ✅ 官方推荐 |
| 适合场景 | 老项目遗留 | **高频写、大 value、跨进程** | **配置项、类型安全、响应式** |

### 7. 选型决策树

```
   存什么？
   ├── 少量配置（主题、开关、token）
   │     └── 新项目 → DataStore（Preferences）
   │           老项目 → SP（能用则别动）或迁移到 DataStore
   ├── 频繁写入 / 单个 value 很大 / 需要跨进程
   │     └── → MMKV
   ├── 结构化、可查询、有关系
   │     └── → Room（见 [[SQLite与Room]]）
   └── 大文件 / 二进制 / 需要下载缓存
         └── → 文件存储（见 [[文件存储]]）
```

## 代码示例

### 1. 初始化与基本读写

```kotlin
class App : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. 初始化 MMKV（推荐在 Application 里做一次，rootDir 默认是 filesDir/mmkv）
        val rootDir = MMKV.initialize(this)

        // 2. 默认实例（单进程模式）
        MMKV.defaultMMKV()

        // 3. 自定义实例：mmkvWithID 指定唯一 ID（= 文件名），不同 ID = 不同文件
        val settings = MMKV.mmkvWithID("settings")
    }
}

// ---------- 使用 ----------
class SettingsRepo {

    // 默认实例
    private val defaultKv = MMKV.defaultMMKV()

    // 自定义实例（推荐：不同业务用不同 ID，避免互相污染）
    private val userKv = MMKV.mmkvWithID("user_profile")

    /** 写：基本类型 */
    fun saveToken(token: String) {
        // encode 返回 Boolean，表示是否写入成功
        val ok = userKv.encode("token", token)
        if (!ok) {
            // 极少数情况：磁盘空间不足 / 文件被破坏
            Log.w("SettingsRepo", "encode token failed")
        }
    }

    /** 读：默认值兜底 */
    fun getToken(): String? = userKv.decodeString("token", null)

    /** 读整型 */
    fun getLaunchCount(): Int = userKv.decodeInt("launch_count", 0)

    /** 写整型：返回旧值（可选） */
    fun increaseLaunchCount(): Int {
        val old = userKv.decodeInt("launch_count", 0)
        userKv.encode("launch_count", old + 1)
        return old
    }

    /** 删除单个 key */
    fun clearToken() {
        userKv.removeValueForKey("token")
    }

    /** 清空整个实例 */
    fun clearAll() {
        userKv.clearAll()
    }
}
```

### 2. 多进程模式 + 集合 + 复杂对象

```kotlin
/** 多进程共享的实例：必须在所有进程里用相同的 ID 和相同的模式初始化 */
object CrossProcessKv {

    private const val KV_ID = "cross_process_config"

    val kv: MMKV by lazy {
        MMKV.mmkvWithID(
            KV_ID,
            MMKV.MULTI_PROCESS_MODE,   // ← 关键：开启多进程
            null                        // 加密密钥（不需要加密就传 null）
        )
    }

    /** 写入一个字符串集合（MMKV 原生支持，无需序列化成 JSON） */
    fun saveTags(tags: Set<String>) {
        kv.encode("tags", tags)
    }

    fun getTags(): Set<String> = kv.decodeStringSet("tags", emptySet()) ?: emptySet()

    /** 存 Boolean / Float / Long / ByteArray 全部原生支持 */
    fun saveState(state: AppState) {
        kv.encode("logged_in", state.loggedIn)
        kv.encode("level", state.level)
        kv.encode("score", state.score)
        kv.encode("avatar", state.avatarBytes)   // ByteArray 天然支持二进制
    }
}

data class AppState(
    val loggedIn: Boolean,
    val level: Int,
    val score: Float,
    val avatarBytes: ByteArray
)
```

### 3. 加密实例（密钥放 Keystore）

```kotlin
/**
 * 密钥不写死在代码里：从 Android Keystore 取出。
 * 注意：Keystore 里的密钥**不可导出**，我们取出的是它的密文+元数据，
 * 实际工程中常见做法是：首次生成随机 key → 用 Keystore 里的 AES 密钥加密这个随机 key → 存到 SharedPreferences。
 */
class SecureKvManager(context: Context) {

    private val appContext = context.applicationContext

    /** 生成/读取 16 字节随机密钥（用 Keystore 保护） */
    private fun obtainRawKey(): ByteArray {
        val prefs = appContext.getSharedPreferences("ks_wrap", Context.MODE_PRIVATE)
        val wrapped = prefs.getString("wrapped_key", null)
        if (wrapped != null) {
            return unwrapWithKeystore(Base64.decode(wrapped, Base64.NO_WRAP))
        }
        val raw = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val wrapped = wrapWithKeystore(raw)
        prefs.edit().putString("wrapped_key", Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit()
        return raw
    }

    fun encryptedKv(id: String): MMKV =
        MMKV.mmkvWithID(id, MMKV.SINGLE_PROCESS_MODE, obtainRawKey().toHexString())

    // ---- Keystore 加解密（AndroidKeyStore AES/GCM）----
    private fun wrapWithKeystore(plain: ByteArray): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(KEY_ALIAS, null) as? SecretKey ?: generateKsKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(plain)
    }

    private fun unwrapWithKeystore(cipherText: ByteArray): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        return cipher.doFinal(cipherText)
    }

    private fun generateKsKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
             .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
             .build()
        )
        return gen.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "kv_wrapping_key"
    }
}

private fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it) }
```

### 4. 监听变更 + 性能压测对照

```kotlin
/** MMKV 的内容变化监听（Kotlin 扩展） */
val kv = MMKV.mmkvWithID("settings")
kv.registerContentChangeNotify { mmapId ->
    // 另一个实例（或多进程里的另一个进程）修改了数据，回调触发
    Log.d("MMKV", "content changed: $mmapId")
}

/** 简单基准测试：对比 SP 与 MMKV 的写耗时（真机观察差异） */
object BenchMark {
    fun run(context: Context) {
        val sp = context.getSharedPreferences("bench_sp", Context.MODE_PRIVATE)
        val kv = MMKV.mmkvWithID("bench_mmkv")

        val payload = "x".repeat(1024)   // 1KB value

        // SP：apply 是异步的，要用 commit 才能测出写盘耗时
        var t = System.nanoTime()
        repeat(100) { sp.edit().putString("k$it", payload).commit() }
        val spMs = (System.nanoTime() - t) / 1_000_000.0

        // MMKV：写入是内存操作，立即返回
        t = System.nanoTime()
        repeat(100) { kv.encode("k$it", payload) }
        val mmkvMs = (System.nanoTime() - t) / 1_000_000.0

        Log.d("BenchMark", "SP=${spMs}ms, MMKV=${mmkvMs}ms")
    }
}
```

## 常见问题 / 面试要点

**读者问：MMKV 是什么？为什么比 SharedPreferences 快？**

**AI答：**

1. **定位**：MMKV（**M**emory **M**apped **K**ey **V**alue）是腾讯微信团队开源的**基于 mmap 的高性能 KV 存储**，已进入 Apache 顶级项目（2023 年）。
2. **快的三个原因**：
   - **mmap 零拷贝**：数据文件被映射到进程虚拟地址空间，读就是**内存寻址**，不需要 `read()` + 用户态拷贝。
   - **写是内存写**：写入映射页，由内核异步回写磁盘；**连续写合并回写**。写盘延迟被彻底隐藏。
   - **protobuf 编码**：Key/Value 用 protobuf 变长编码存储，**比 XML 紧凑得多**（同样的数据体积通常只有 SP XML 的 1/3~1/2），且解析是**顺序扫描内存**，不需要 XML DOM 构建。
3. **对比 SP 的三个结构性劣势**（这是回答的重点）：
   - SP 写盘是**全量重写整个 XML**（写放大），MMKV 是**追加写 + 定期整理**。
   - SP 读是**HashMap 命中**（其实也很快），但 SP **首次要全量 XML 解析**，MMKV 首次也是扫描，但**无 DOM 构建开销**。
   - SP 的 XML 格式冗余（`<string name="...">` 重复标签名），MMKV 的 protobuf 编码紧凑。
4. **实测数据**（官方 benchmark 量级）：MMKV 写入约为 SP 的 **1/4 ~ 1/10** 耗时；**1000 个 key、单 value 几 KB** 时，SP 需要几十~几百 ms，MMKV 在 **1ms 以内**。注意：这是**内存写**，SP 的 `apply()` 是异步的所以看起来"快"，真正公平的比较要对比**掉电前数据是否安全**和**写入总耗时**。
5. **什么时候用 MMKV**：**高频写**（埋点缓存、日志缓冲、实时计数）、**单个 value 较大**（几十 KB 的 JSON）、**需要跨进程读写**、**需要加密**。

---

**读者问：MMKV 为什么"掉电不丢数据"？它和 SP 的持久化保证有什么不同？**

**AI答：**

1. **先纠正一个前提**：**MMKV 不是"绝对掉电不丢"**，它依赖的是**操作系统的回写策略**。
2. **机制**：
   - MMKV 用 `mmap` 映射文件，**写入 = 修改映射页的内存内容**。内核会把这页标记为 **dirty**，并由**内核的写回线程**在合适时机（脏页超过阈值、内存紧张、定时）**写回磁盘**。
   - 因此"写入返回"只意味着"**数据已在内存映射页中**"，**不等于"已在物理磁盘上**"。
   - **突然掉电**（拔电）可能丢失**尚未回写的脏页**（一般最多丢最近几秒的写入，Linux 默认 dirty 比例与超时机制决定）。
   - **正常杀进程 / 崩溃**：进程死亡**不影响**内核的脏页回写，**数据不会丢**（这是 mmap 相对 `write()` 的巨大优势：`write()` 返回后数据在 page cache，同样有丢失风险，但 mmap 少一次 syscall + 少一次拷贝）。
3. **与 SP 的对比**：
   - SP 的 `apply()` 走的是 `AtomicFile.write` → `FileOutputStream.write` → **同步写入 page cache**，数据在**进程内 pending 队列**；进程被 SIGKILL 时 pending 写**可能丢失**。
   - MMKV 的写入**没有"进程内 pending 队列"**，数据立即落在**共享内存页**上（内核对象），进程死掉不影响回写。
4. **真正的强一致方案**：如果你需要"写入返回 = 已落盘"的保证，**MMKV 也不提供**（没有 `fsync` 接口）。要这个保证就得用数据库的事务提交（[[../数据库/SQLite与Room]]）或自己写 `FileChannel.force(true)`。
5. **面试话术**："mmap 解决的是**IO 次数与拷贝次数**，不是**持久化语义**。持久化语义由内核回写策略决定。真要强一致，得走 DB 事务或 `fsync`。"

---

**读者问：MMKV 的多进程模式是怎么实现的？和 SP 的多进程问题有什么区别？**

**AI答：**

1. **SP 多进程为什么不行**（回顾）：SP 的 `mLoaded` 标志导致**只有进程内首次访问才读文件**，之后全靠内存缓存 → **进程间互相看不见**；`MODE_MULTI_PROCESS` 只是在每次 get 时重读，**不解决并发写丢更新**，且**API 23 已废弃**。
2. **MMKV 的多进程机制**：
   - **文件锁**（`fcntl` 的 `F_SETLK`）：写操作前取 `LOCK_EX` 排他锁，读操作取 `LOCK_SH` 共享锁 → **同一时刻只有一个进程在写**。
   - **变更检测**：MMKV 记录文件的 `mtime` 与内容 `CRC32`；读之前（或周期性）检查，发现变化就**重新 mmap 并重建内存索引**（`Dictionary`）。
   - **内存索引一致**：多进程模式下索引的更新也在锁保护下进行。
   - **写入追加 + CRC 校验**：进程 B 写入时，进程 A 通过 CRC 不匹配能感知文件已变。
3. **代价**：多进程模式**每毫秒级要检查一次**文件变化（可配置），有额外的 `fcntl` 系统调用与内存同步开销 → **单进程场景不要开**。
4. **仍然需要注意**：
   - 多进程模式下，**每个进程都必须用相同的 ID（文件名）和相同模式初始化**，否则会各自写不同文件。
   - **不要在多进程模式下对同一个 key 高频写**——锁竞争会让并发写退化。
   - **数据一致性仍非事务级**：MMKV 是"**单 key 原子**"，不是"**多 key 事务**"。需要多 key 一致 → 用 DB 事务。

---

**读者问：MMKV 支持加密吗？怎么管理密钥？**

**AI答：**

1. **支持**：`MMKV.mmkvWithID(id, mode, cryptKey)` 传入密钥即开启加密。
   - 算法：**AES-128-CFB**（流密码模式）。
   - 密钥长度要求：AES-128 → **16 字节**，AES-192 → 24 字节，AES-256 → 32 字节。
   - 文件结构变为：`[IV(16B)][cryptKeyLen(1B)][cryptKey(nB)][actualSize(4B)][CRC32(4B)][encrypted data]`。
   - **注意**：IV 是**随机生成并写在文件头**，同一个文件每次写入会**重新生成 IV**。
2. **性能影响**：加密后写入要额外做 AES，官方实测**慢 10%~20%**（仍远快于 SP）。
3. **密钥管理（面试重点）**：
   - ❌ **密钥硬编码在代码里**：`"MySecretKey123456"` 这种——反编译 APK 就能拿到，**加密形同虚设**。
   - ✅ 正解：**Android Keystore**（TEE / StrongBox 硬件密钥）保管主密钥 → 用它加密一份随机的**数据密钥（DEK）** → DEK 存在 SP/文件里 → MMKV 用 DEK 加密。
   - 更彻底的做法：**用 Keystore 里的密钥直接对 MMKV 数据做加密**（自己实现 MMKV 的 encryption 层，或用 AndroidX Security 的 `EncryptedFile`）。
   - 代码示例见上文 `SecureKvManager`。
4. **常见坑**：
   - 开启加密后**必须记住密钥**，密钥丢了数据永久无法恢复（没有后门）。
   - **换了密钥要 migrate**：`MMKV.mmkvWithID(id, mode, newKey)` + `reKeyCryptKey(oldKey, newKey)` 迁移。
   - **多进程 + 加密**：两者可以同时开，但**每个进程必须用相同密钥**。

---

**读者问：MMKV、SharedPreferences、DataStore 三选一，选型标准是什么？**

**AI答：**

**先给决策依据（4 个维度）**：

| 维度 | 问题 | MMKV | SP | DataStore |
| --- | --- | --- | --- | --- |
| **写入频率** | 每秒写多次 / 每次都写？ | ✅ 最优 | ❌ 写放大 | ⚠️ 每次全量写文件 |
| **value 大小** | 单个 value 几十 KB+？ | ✅ | ❌ XML 撑爆 | ❌ 不适合 |
| **数据结构** | 需要类型安全 / 响应式？ | ⚠️ 有类型但无 Flow | ❌ | ✅ 最优 |
| **跨进程** | 多进程读写？ | ✅ | ❌ | ❌ |
| **加密** | 需要落盘加密？ | ✅ | ❌ | ❌ |

**结论话术**：
1. **新项目默认选 DataStore**（官方推荐、类型安全、协程原生、原子写）。
2. **只有当"写入极频繁"或"单 value 很大"时**才选 **MMKV**（这是 SP 和 DataStore 都覆盖不了的场景；DataStore 每次写都是一次全量文件写，频率高时 IO 压力明显）。
3. **SP 属于"能用则别动"**：老项目继续用没问题，但**新代码不要新增 SP 使用点**，迁移到 DataStore。
4. **加密需求**：MMKV 内置 AES 最省事；DataStore 需要自己在序列化层做。
5. **需要跨进程**：MMKV 是唯一"开箱即用"的 KV 方案。

---

**读者问：MMKV 的数据量有上限吗？key 太多会有什么问题？**

**AI答：**

1. **文件大小上限**：理论上受 **32 位 offset 限制**（约 2GB），但 MMKV 内部有 `actualSize` 字段，实际限制是 **单个文件 ≤ 2GB**；同时 MMKV 每次启动会 mmap 整个文件，**文件越大越吃虚拟地址空间与页内存**。
2. **key 数量的真实瓶颈是 O(n) 查找**：MMKV 维护内存索引 `Dictionary`（key → offset），所以查找是 **O(1)**，**不是每次都全量扫描**。这一点常被误解——扫描只发生在**文件损坏后的重建索引**路径。
3. **真正的问题**：
   - **写放大随垃圾数据增长**：MMKV 追加写，同名 key 覆盖会产生**旧值残留**。残留空间靠 **full write back** 回收，回收阈值由 `MMKV` 内部策略决定（文件占用达到一定比例触发整理）。
   - **key 数量极多（数千）**时，Dictionary 本身占内存（每 key 约几十字节），且**整理时的重写成本变高**。
4. **实践建议**：
   - key 数量控制在**几百以内**。
   - **按业务拆分多个实例**（`mmkvWithID` 不同 ID），避免单文件膨胀；MMKV 官方也建议这么用。
   - 需要存**大量结构化数据** → 用 Room（见 [[../数据库/SQLite与Room]]），不要硬塞 MMKV。
   - 需要存**大二进制** → 用文件（见 [[文件存储]]）。

---

**读者问：MMKV 内部是怎么找到某个 key 的？为什么不会像 SP 那样全量解析？**

**AI答：**

1. **内存索引 Dictionary**：MMKV 在内存中维护 `key → (offset, size)` 的映射（`MMKV::Dictionary`），**读操作是哈希查找**。
2. **数据区是 protobuf 编码的 KV 序列**：文件里的实际布局是 `Key1Len+Key1+Value1Len+Value1+Key2Len+...` 这样的连续块；索引里记录的是每个 KV 块的起始 offset。
3. **写入流程**：
   - `encode(key, value)` → 把 key 和 value 各自 protobuf 编码成字节数组。
   - **追加**到文件尾部（`appendDataWithKey`），更新 Dictionary 里的 offset。
   - **同名 key**：Dictionary 里更新 offset 指向新位置，**旧数据变成垃圾**，等待 full write back 回收。
4. **与 SP 的本质差异**：
   | | SharedPreferences | MMKV |
   | --- | --- | --- |
   | 磁盘格式 | XML（需要 DOM 解析） | protobuf 字节流（顺序扫描） |
   | 索引 | 内存 HashMap（加载时全量构建） | 内存 Dictionary（加载时扫描构建，之后增量更新） |
   | 写 | **全量重写整个 XML** | **追加 + 索引更新** |
   | 加载 | XML 解析（慢，有对象分配） | 字节扫描（快，几乎零分配） |
5. **加载路径**：`MMKV` 构造 → `checkLoadData()` → 读文件头拿 `actualSize` → 扫描数据区重建 Dictionary → mmap 整个文件。之后所有读写都是内存操作。

---

**读者问：面试官问"MMKV 和 DataStore 你怎么选"，你怎么说？**

**AI答：**

1. **先给本质差异（这是关键）**：
   - **MMKV 是"内存映射的 KV 文件"**：追求**吞吐与延迟**，写入模型是"**内存写 + 内核异步回写**"。
   - **DataStore 是"有状态流的持久化配置"**：追求**一致性与类型安全**，写入模型是"**写临时文件 → fsync → rename 原子替换**"。
2. **对比维度（表格化回答）**：
   - **一致性**：DataStore 每次写都是**原子替换**（读者要么看到旧内容要么看到新内容，不会读到半截）；MMKV 追加写+mmap，读者在同一进程内一定一致，跨进程有一致性窗口（靠锁+CRC 检测收敛）。
   - **崩溃安全**：DataStore 的临时文件机制保证**崩溃不会损坏主文件**；MMKV 靠 CRC 检测损坏。
   - **类型安全**：DataStore `Preferences.Key<T>` 编译期定类型；MMKV 有 `encode/decode` 泛型 API 但**编译期不校验 key 类型**；SP 完全无。
   - **响应式**：DataStore 原生 `Flow`，数据变化自动推送；MMKV 有 `asFlow()`（基于内容变更通知），SP 需要自己注册 listener。
   - **性能**：MMKV 显著优于 DataStore（DataStore 每次写都是一次全量文件写 + rename）。
3. **选型结论**：
   - **配置项 / 主题 / 开关 / token / 需要 UI 自动响应** → **DataStore**。
   - **高频写（每秒上百次）、大 value、跨进程、需要加密** → **MMKV**。
   - **混合用法**（常见）：**配置走 DataStore，高频缓存走 MMKV**。
4. **加分点**：说出 DataStore 的**"单写者模型"**——它要求**每个文件全局只有一个 DataStore 实例**，因为它的原子替换语义建立在"只有一个写者"的前提上，多写者会互相覆盖临时文件。这也是 MMKV 存在的另一个理由（MMKV 支持多进程多写者）。

## 关联笔记

- [[SharedPreferences]]：MMKV 的主要替代对象，理解 SP 的写放大才能理解 MMKV 的优势
- [[DataStore]]：官方推荐的现代方案，与 MMKV 互补
- [[文件存储]]：大二进制与缓存文件的落地方式，含 Keystore 密钥管理
- [[../数据库/SQLite与Room]]：结构化数据不要塞进 KV 存储
- [[../数据格式/JSON与ProtoBuf]]：MMKV 内部就用 protobuf 编码，本质是"把 protobuf 协议写进 mmap 文件"
- [[../README]]：第 4 部分总览

## 参考资料

- MMKV 官方仓库与文档：https://github.com/Tencent/MMKV
- MMKV Wiki（原理与性能数据）：https://github.com/Tencent/MMKV/wiki/Overview
- Android 官方 DataStore 文档：https://developer.android.com/topic/libraries/architecture/datastore
- Linux mmap 手册：https://man7.org/linux/man-pages/man2/mmap.2.html
- 《Android 高级编程》第 4 版 · 第 8 章
