---
tags: [android]
part: 第4部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[../README]], [[../本地存储/DataStore]], [[../数据格式/JSON与ProtoBuf]]"
---

# SQLite与Room

## 一句话定义

SQLite 是 Android 内置的**嵌入式 C 语言关系型数据库引擎**，用一个文件承载整库，Android 用 `SQLiteOpenHelper` / `SQLiteDatabase` 封装了它的 API；Room 则是在 SQLite 之上用**注解处理器（APT）在编译期生成 DAO 实现**的 ORM 框架，把 SQL 错误、类型不匹配、实体字段与表结构不同步等问题**从运行时提前到编译期**，并提供 **Flow / RxJava** 响应式查询与**结构化迁移**能力。

## 核心原理

### 1. SQLite 基础模型

SQLite 是一个**库（library）而非服务器（server）**：没有独立进程、没有网络协议、没有用户权限体系，整个数据库就是磁盘上的一个（或几个）文件。

```text
   一个 SQLite 数据库文件
   ┌───────────────────────────────────────────┐
   │  Page 0   表头页：magic "SQLite format 3"  │
   │  Page 1   sqlite_master：所有表/索引定义  │
   │  Page 2+  各表的数据页（B-tree 叶子）      │
   └───────────────────────────────────────────┘

   逻辑结构：
   db
   ├─ table user(id INTEGER PRIMARY KEY, name TEXT, age INTEGER)
   │   ├─ 表结构存在 sqlite_master（sqlite_sequence / sqlite_schema）
   │   └─ 数据存在各数据页的 B-tree 中
   ├─ index idx_user_name ON user(name)
   └─ trigger / view / ...
```

| 概念 | 说明 | 面试要点 |
| --- | --- | --- |
| **表 Table** | 二维结构，`CREATE TABLE t (id INTEGER PRIMARY KEY, ...)` | `INTEGER PRIMARY KEY` 是 **rowid 别名**，不是普通索引 |
| **行 rowid** | 内部自增主键，`INSERT` 时自动分配 | `WITHOUT ROWID` 表没有 rowid，主键就是聚簇索引 |
| **SQL** | DDL(`CREATE`/`ALTER`)、DML(`SELECT`/`INSERT`/`UPDATE`/`DELETE`)、DCL(`GRANT`)、TCL(`COMMIT`) | Android 里没有用户级权限，DCL 基本用不到 |
| **事务 Transaction** | `BEGIN` → DML → `COMMIT`/`ROLLBACK` | 保证**原子性**，也决定**性能**（见下文 WAL） |
| **索引 Index** | B-tree 结构，加速查找 | **索引会拖慢写入**（每次写都要维护索引） |
| **游标 Cursor** | `query()` 返回的结果集游标 | `Cursor` 是**窗口**，必须 `close()`，否则泄漏 |
| **Database** | 一个 `.db` 文件；`getReadableDatabase` vs `getWritableDatabase` | Android 中 SQLiteDatabase **不是线程安全**的 |
| **WAL** | Write-Ahead Logging，日志文件 `xxx.db-wal` | Android 4.1+ 默认开启，**读写不互斥** |

### 2. Android 的封装：SQLiteOpenHelper 与 SQLiteDatabase

```text
   应用代码
      │
      ▼
   SQLiteOpenHelper（管理 db 版本、onCreate/onUpgrade）
      │  getReadableDatabase() / getWritableDatabase()
      ▼
   SQLiteDatabase（openDatabase → 打开文件、管理连接池）
      │
      ▼
   SQLiteOpenHelper.Callback.onCreate/onUpgrade
      │
      ▼
   native → libsqlite.so → 磁盘上的 .db 文件
```

关键 API 语义：

| API | 说明 | 坑 |
| --- | --- | --- |
| `SQLiteOpenHelper(context, name, factory, version)` | 声明库名与版本 | `version` 只增不减 |
| `getWritableDatabase()` | 可读可写；不存在则 `onCreate` | **不要在主线程调用** |
| `getReadableDatabase()` | 可读；磁盘满时降级为只读 | — |
| `rawQuery(sql, selectionArgs)` | 返回 `Cursor`，**不立即执行** | 必须 `use{}` 关闭 |
| `query(...)` | 参数化查询 | 参数用 `?` 占位，**绝不要拼字符串** |
| `execSQL(sql, bindArgs)` | 执行无返回结果的 SQL | 同样用 `?` |
| `insert/update/delete` | 返回影响行数 | — |
| `beginTransaction/setTransactionSuccessful/endTransaction` | 手动事务 | **必须 `finally { endTransaction() }`** |
| `enableWriteAheadLogging()` | 开启 WAL | 默认开启，`setWriteAheadLoggingEnabled` 可改 |

### 3. Room 架构：Entity / DAO / Database

```text
   ┌────────────────────────────────────────────────────────┐
   │  @Database class AppDatabase : RoomDatabase()          │
   │    ├─ entities = [User::class, Post::class]            │
   │    └─ abstract fun userDao(): UserDao                  │
   └────────────────────────────────────────────────────────┘
            │                                  │
            │ APT 生成                          │ APT 生成
            ▼                                  ▼
   ┌────────────────────────┐        ┌────────────────────────┐
   │  UserDao_Impl          │        │  AppDatabase_Impl      │
   │  implements UserDao    │        │  extends RoomDatabase  │
   │  ├─ 真实执行 SQL       │        │  ├─ openHelper         │
   │  ├─ 事务边界           │        │  ├─ invalidationTracker│
   │  └─ Flow 通知          │        │  └─ SQLiteOpenHelper   │
   └────────────────────────┘        └────────────────────────┘
            │                                  │
            └────────────┬─────────────────────┘
                         ▼
                androidx.room:room-runtime
                         │
                         ▼
                  SQLiteDatabase（底层）
```

| 注解 | 作用 | 关键参数 |
| --- | --- | --- |
| `@Entity` | 映射一张表 | `tableName`、`indices`、`primaryKeys` |
| `@Dao` | 标记接口，由 APT 生成实现 | `@Dao` 接口方法可以是 `suspend`、`Flow`、普通同步 |
| `@Database` | 标记 DB 类，指定版本与实体 | `version`、`exportSchema` |
| `@PrimaryKey` | 主键 | `autoGenerate = true` |
| `@ColumnInfo` | 改列名/加索引 | `name`、`index`、`defaultValue` |
| `@ForeignKey` | 外键约束 | 需要 `PRAGMA foreign_keys=ON` 才生效 |
| `@Index` | 索引 | `@Index(value=["name"], unique=true)` |
| `@TypeConverter` | 自定义类型 ↔ 数据库列 | 全局注册或按字段注册 |
| `@Embedded` | 内嵌对象展开成多个列 | 一对一无外键 |
| `@Relation` | 一对多 / 多对多 | 配合 `@Transaction` |
| `@Query` | 写 SQL | **编译期校验语法与表结构** |
| `@Insert/@Update/@Delete` | 增删改 | `@Insert(onConflict = OnConflictStrategy.REPLACE)` |
| `@Transaction` | 事务 | 方法级或 DAO 方法级 |

### 4. Room 的代码生成原理（APT）

Room 的核心黑科技是：**用 Java 注解处理器（APT）在编译期读懂你的 DAO 接口与 SQL，并生成 Java/Kotlin 实现类**。

```text
   编译期流程（kapt / KSP）
   ┌──────────────────────────────────────────────────────┐
   │ 1. Kotlin/Java 源码编译前，APT 扫描所有注解          │
   │ 2. 找到 @Dao 接口：解析方法签名与 @Query 里的 SQL   │
   │ 3. 用 SQLite 的 "CREATE TABLE IF NOT EXISTS" 反向  │
   │    解析出所有表的列名、类型、主键、索引              │
   │ 4. 校验每条 @Query 的 SQL：表是否存在、列是否存在、  │
   │    参数个数与类型是否匹配、返回类型能否映射          │
   │ 5. 生成 Dao_Impl.java：把 @Query 的 SQL 拼成        │
   │    StringBuilder 常量，参数用 bindArg0/bindArg1      │
   │ 6. 生成 Database_Impl.java：建表、注册 Invalidation │
   │    Tracker、构造 DAO 实例                          │
   │ 7. 编译产物与手写代码一起参与最终编译                │
   └──────────────────────────────────────────────────────┘
```

- **APT（Annotation Processing Tool）** 是 Java 时代的方案，需要 `kapt`（对 Kotlin 而言是额外的一轮处理，慢）。
- **KSP（Kotlin Symbol Processing）** 是 Kotlin 官方推出的替代方案，**直接基于 Kotlin 编译前端**，速度比 kapt 快一个数量级，Room 2.5+ 全面推荐 KSP。
- Room 内部用 **SQLite JDBC 的方言解析器**（`sqlite-parser`）来解析 SQL，而不是简单正则。

**生成的代码大致长这样**（`UserDao_Impl` 的核心）：

```java
public final class UserDao_Impl implements UserDao {
  private final AppDatabase_Impl __db;

  @Override
  public Object query(long id, Continuation<? super User> continuation) {
    return __db.getInvalidationTracker().createFlow(
      /* observedEntities */ new String[] {"User"},
      __db.getTransactionExecutor(),
      new Callable<User>() {
        @Override
        public User call() {
          // 这里是 Room 从 @Query 里解析出来的 SQL
          final String _sql = "SELECT `id`, `name`, `age` FROM `User` WHERE `id` = ?";
          final Cursor _cursor = __db.query(_sql, new String[]{"id"}, new Object[]{id});
          try {
            final Integer _id;
            final String _name;
            final Integer _age;
            if (_cursor.moveToFirst()) {
              _id = _cursor.getInt(_cursor.getColumnIndexOrThrow("id"));
              _name = _cursor.getString(_cursor.getColumnIndexOrThrow("name"));
              _age = _cursor.getInt(_cursor.getColumnIndexOrThrow("age"));
            } else {
              _id = null; _name = null; _age = null;
            }
            return new User(_id, _name, _age);
          } finally { _cursor.close(); }
        }
      }, continuation);
  }
}
```

> 面试点：**Room 的 DAO 实现不是反射**。反射只在**运行时**才能发现；而 Room 需要**在编译期知道 SQL 是否正确**，这只能靠 APT/KSP 在编译期读源码 + 解析 SQL。

### 5. 编译期 SQL 校验：Room 到底检查了什么

这是 Room 最有价值的特性，把"上线后 crash"变成"编译不过"。

| 校验项 | 错误示例 | 编译期表现 |
| --- | --- | --- |
| **SQL 语法** | `@Query("SELECT FROM WHERE")` | `error: Syntax error` |
| **表是否存在** | `@Query("SELECT * FROM NoSuchTable")` | `error: Cannot find table` |
| **列是否存在** | `@Query("SELECT no_such_col FROM User")` | `error: Cannot find column` |
| **列名与返回类型** | `data class User(val id: Int)` 但表里是 `name TEXT` | 提示无法生成构造函数 |
| **参数个数** | `@Query("SELECT * FROM User WHERE id = ?")` 但方法有 2 个参数 | `error: 1 parameter but 2 arguments` |
| **参数类型** | `?` 绑到 `Int` 但列是 TEXT | 编译通过，但运行期行为需注意 |
| **返回类型映射** | `@Query("SELECT * FROM User")` 返回 `Flow<User>` | 检查能否从 Cursor 构造 User |
| **Flow + 写操作的组合** | DAO 方法同时标注 `@Query` 与 `@Insert` | 编译期报错 |

典型报错文本：

```text
error: Cannot find column 'nmae' in table 'User'
  @Query("SELECT nmae FROM User")
             ^
```

### 6. TypeConverter / Embedded / Relation

```kotlin
// ---- TypeConverter：把自定义类型转成可存储的列 ----
class InstantConverter {
    @TypeConverter
    fun toTimestamp(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun fromTimestamp(ts: Long?): Instant? = ts?.let { Instant.ofEpochMilli(it) }
}

// ---- Embedded：把一个普通类"打平"成父表的多个列 ----
data class Address(
    val street: String,
    val city: String
)

@Entity(tableName = "profile")
data class Profile(
    @PrimaryKey val id: Long,
    val nickname: String,
    @Embedded val address: Address   // → 列: street, city
)

// ---- Relation：一对多 ----
data class UserWithPosts(
    @Embedded val user: User,      // User 表的列
    @Relation(parentColumn = "id", entityColumn = "userId")
    val posts: List<Post>
)

@Dao
interface UserDao {
    @Transaction
    @Query("SELECT * FROM User WHERE id = :id")
    suspend fun getUserWithPosts(id: Long): UserWithPosts?
}
```

| 关系 | 实现 | 说明 |
| --- | --- | --- |
| **一对一** | `@Embedded` | 无外键，简单内联 |
| **一对多** | `@Relation` + `@Transaction` | Room 会发两条查询，在事务中组装 |
| **多对多** | `@Relation(associateBy = Junction(...))` | 需要中间表 + `@Junction` |
| **多对多（自带 @Relation 的 @JvmStatic 桥接）** | 额外写一个 `associateBy` 关联对象 | Room 2.4+ 支持 |

> `@Relation` **必须配合 `@Transaction`**，否则两次查询之间数据可能被并发写入改变。

### 7. Flow / RxJava 与响应式查询

Room 的响应式能力基于 **InvalidationTracker**（表级失效跟踪）：

```text
   写操作发生
      │
      ▼
   InvalidationTracker.notifyTablesChanged("User")
      │  维护每张表的 "observer 列表"
      ▼
   所有订阅了该表的 Flow 被唤醒
      │
      ▼
   重新执行查询 → 发射新 List<User>
```

| 返回类型 | 行为 |
| --- | --- |
| `suspend fun getAll(): List<User>` | 一次性查询，不订阅 |
| `fun getAll(): Flow<List<User>>` | 持续订阅，数据变化自动推送 |
| `fun getAll(): LiveData<List<User>>` | 同上，生命周期感知 |
| `fun getAll(): Single<List<User>>` | RxJava2，一次性 |
| `fun getAll(): Flowable<List<User>>` | RxJava2，持续 |
| `fun getAll(): Flow<List<User>>` + `@Transaction` | 保证多条查询结果一致快照 |

**注意**：Room 的 Flow **不是"数据库推送"**，而是"**有写入才唤醒 + 重新执行查询**"。如果其他进程/其他连接改了库，Room **感知不到**（除非用 `RoomDatabase.setQueryCallback` 或多进程支持）。

### 8. 数据库迁移

```text
   版本 v1  ──升级──>  版本 v2
   CREATE TABLE User(id, name)
                │
                │  Room 比较 "identity hash"
                ▼
   发现 schema 不匹配 → 找不到 Migration → 崩溃
                （IllegalStateException: Room cannot verify the data integrity）
```

Room 的迁移机制依赖 **`room_master_table`** 里存的一份 **schema 哈希（identity hash）**。每次打开数据库，Room 会：

1. 读取 `room_master_table` 中的 `identity_hash`。
2. 与当前代码里通过 APT 生成的 schema 哈希比较。
3. 不一致 → 查找 `Migration`（`onUpgrade` 依次执行 v1→v2、v2→v3）。
4. 找不到匹配的 Migration → **抛异常**（除非允许破坏性迁移）。

| 方案 | 写法 | 数据 | 适用 |
| --- | --- | --- | --- |
| **AutoMigration** | `autoMigrations = AutoMigration(from = 1, to = 2)` | **保留** | 加列、改列默认值、加索引（Room 2.6+） |
| **手写 Migration** | `Migration(1, 2) { db.execSQL("ALTER TABLE ...") }` | **保留** | 改列名、改类型、拆表 |
| **fallbackToDestructiveMigration** | `Room.databaseBuilder(...).fallbackToDestructiveMigration()` | **全删** | 纯本地缓存类数据 |
| **fallbackToDestructiveMigrationOnDowngrade** | 仅降级时删库 | 部分 | 灰度期间版本回退 |
| **导出 schema** | `room.schemaLocation` + `exportSchema = true` | — | **必须开**：用于生成 AutoMigration 与测试 |

**AutoMigration 的限制**（面试常问）：它**只能推断"加列"和"改默认值"**，涉及以下操作必须手写：
- **改列名** → 需要"建新表 + 搬数据 + 删旧表 + 改外键"。
- **改列类型** → SQLite 是弱类型，但列的 **affinity** 变了，AutoMigration 不会处理。
- **删列** → AutoMigration 不会帮你删（保守策略）。
- **加外键 / 改主键** → 必须手写。

### 9. 主线程访问禁令

Room **默认禁止主线程访问数据库**，因为 Room 底层是 SQLite，查询是**同步阻塞 IO**。

```kotlin
Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
    .build()   // 默认 allowMainThreadQueries() == false
```

- 主线程调用 → 抛 `IllegalStateException: Cannot access database on the main thread since it may potentially lock the UI for a long period of time.`
- `allowMainThreadQueries()` 可以关掉这个检查，但**只在测试或极小数据集时使用**。
- 正确做法：
  - DAO 方法声明为 `suspend` → Room 自动把执行切到 Room 的**查询执行器**（默认 `ArchTaskExecutor` 内部 IO 线程池）。
  - 或者用 `Flow` + `collectAsStateWithLifecycle`，Room 内部在 IO 线程查询、主线程回调。

### 10. WAL 模式、事务与性能

**默认 journal_mode**（Android）：从 Android 4.1（API 16）开始，**默认开启 WAL**。

```text
   rollback journal 模式（默认之前）
   写：持有写锁 → 读也被阻塞（读写互斥）
   ┌─────────┐        ┌─────────┐
   │  Reader │   ✗    │  Writer │  ← 同一时刻只能有一个
   └─────────┘        └─────────┘

   WAL 模式（Android 4.1+ 默认）
   写：追加到 -wal 日志；读：读快照（不阻塞写）
   ┌─────────┐        ┌─────────┐
   │  Reader │   ✓    │  Writer │  ← 读写并发
   └─────────┘        └─────────┘
        │
        ▼
   checkpoint 时把 wal 内容写回主 db 并截断
```

| 维度 | rollback journal | WAL |
| --- | --- | --- |
| 读写并发 | **不并发** | **并发**（读者读快照） |
| 崩溃恢复 | 回放 rollback journal | 回放 `-wal`（更小更快） |
| 磁盘 IO 模式 | 随机写 | **顺序追加**（对闪存更友好） |
| 内存代价 | 低 | 需缓存 wal 页（`-wal` 默认等同页大小） |
| 网络文件系统 | 可用 | **不可用**（依赖共享内存 mmap） |

**事务与性能**：

- **为什么必须用事务**：每次 `INSERT`（非事务模式）都是"**隐式事务**"——一次 fsync，**几千次插入 = 几千次 fsync** → 极慢。
- **用事务**：1000 次插入包在一个事务里 = **1 次 fsync** → 提升可达**两个数量级**。
- Room 的 `@Insert` / `@Update` / `@Delete` **方法默认自带事务**；但**循环里逐条调用** = N 个事务 → 要么用 `@Insert` 接受 `List`，要么加 `@Transaction` 包住循环。

```kotlin
@Dao
interface UserDao {
    @Insert
    suspend fun insertAll(users: List<User>)   // 列表插入：单事务批量执行

    @Transaction
    suspend fun replaceAll(users: List<User>) {
        deleteAll()
        insertAll(users)     // 两个调用在同一个事务里
    }
}
```

### 11. 数据库加密

| 方案 | 层级 | 说明 | 代价 |
| --- | --- | --- | --- |
| **SQLCipher** | 整库加密 | 替换 Android 自带的 `libsqlite.so`，用密钥打开数据库 | **失去 Room 的编译期 SQL 校验**（Room 需要 SQLite 的 `sqlite_master` 解析，SQLCipher 的 schema 表名不同 → Room 官方支持有限，常需改表名或用 `SupportSQLiteOpenHelper` 兼容层） |
| **字段级加密** | 列级 | 敏感列存密文（`EncryptedColumn`） | 查询/索引失效，需自行处理 |
| **Keystore + AES** | 密钥管理 | 密钥存 Android Keystore（硬件保护），数据层加密 | 实现成本最高，安全性最好 |

> **工程实践**：Room 官方提供了 **`androidx.sqlite:sqlite-ktx`** 与 **SQLCipher 的 `net.zetetic:android-database-sqlcipher`**，Room 场景下通过 `SupportFactory(passphrase)` 打开：
> ```kotlin
> val factory = SupportFactory(passphrase)   // SQLCipher
> Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
>     .openHelperFactory(factory)
>     .build()
> ```
> ⚠️ 注意：Room 的编译期校验依赖解析 `sqlite_master`，**SQLCipher 会把 schema 表重命名/加密**，若报错可尝试在 `SupportFactory` 中配置，或改用 **字段级加密** 方案。

### 12. 多进程数据库

Room 官方**不支持多进程**（`RoomDatabase` 没有多进程模式），但 SQLite 本身支持（WAL + 文件锁）。要做多进程：

1. **开启 WAL**：WAL 模式使用 `-shm` 共享内存文件，是跨进程读写的基础。
2. **单写者原则**：只让一个进程写，其他进程只读 → 避免写-写冲突（SQLite 的写锁是**进程级**的，跨进程靠文件锁）。
3. **Room 侧的问题**：
   - `InvalidationTracker` **只跟踪本进程连接的写入** → 其他进程的写入**不会推送 Flow**。
   - Room 打开数据库时会做 **schema 校验**，多进程同时升级会冲突。
   - 官方建议：**不要在多进程里用 Room**，用 `SupportSQLiteOpenHelper` 直接操作 SQLite，或者把写操作收敛到一个进程 + Binder 服务。

## 代码示例

### 1. 完整的 Room 数据库定义

```kotlin
// ---------- Entity ----------
@Entity(
    tableName = "User",
    indices = [Index(value = ["email"], unique = true)]   // 唯一索引，防止重复邮箱
)
data class User(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "email") val email: String,
    val name: String,
    val avatarUrl: String?,
    val createdAt: Long = System.currentTimeMillis()
)

// ---------- TypeConverter ----------
class InstantConverter {
    @TypeConverter
    fun toMillis(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun fromMillis(millis: Long?): Instant? = millis?.let(Instant::ofEpochMilli)
}

// ---------- 关联 POJO ----------
data class UserWithPosts(
    @Embedded val user: User,
    @Relation(parentColumn = "id", entityColumn = "userId")
    val posts: List<Post>
)

// ---------- DAO ----------
@Dao
interface UserDao {

    // 查询返回 Flow：数据变化自动推送（Room 内部用 InvalidationTracker 感知写操作）
    @Query("SELECT * FROM User ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<User>>

    // suspend 查询：Room 自动切到 IO 线程执行
    @Query("SELECT * FROM User WHERE email = :email LIMIT 1")
    suspend fun findByEmail(email: String): User?

    // 编译期校验：表不存在 / 列不存在 / 参数个数不匹配 → 直接编译失败
    @Query("SELECT * FROM User WHERE name LIKE '%' || :keyword || '%'")
    suspend fun search(keyword: String): List<User>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: User): Long

    // 批量插入：整个 List 在一个事务里，只 fsync 一次
    @Insert
    suspend fun insertAll(users: List<User>)

    @Update
    suspend fun update(user: User)

    @Delete
    suspend fun delete(user: User)

    @Query("DELETE FROM User")
    suspend fun deleteAll()

    // 一对多：必须加 @Transaction，保证两次查询看到一致快照
    @Transaction
    @Query("SELECT * FROM User WHERE id = :id")
    suspend fun getUserWithPosts(id: Long): UserWithPosts?

    // @Transaction 包住多次调用 → 整体原子
    @Transaction
    suspend fun replaceAll(users: List<User>) {
        deleteAll()
        insertAll(users)
    }
}

// ---------- Database ----------
@Database(
    entities = [User::class, Post::class],
    version = 2,
    exportSchema = true                       // 必须！否则无法生成 AutoMigration 与迁移测试
)
@TypeConverters(InstantConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
                // 手写迁移：v1 -> v2，新增 nickname 列
                .addMigrations(MIGRATION_1_2)
                // 降级时也提供迁移，避免灰度期版本回退崩溃
                .addMigrations(MIGRATION_2_1)
                .build()

        // v1 -> v2
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `User` ADD COLUMN `nickname` TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_User_email` ON `User` (`email`)")
            }
        }

        // v2 -> v1（降级，灰度回滚用）
        val MIGRATION_2_1 = object : Migration(2, 1) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // SQLite 3.35+ 支持 DROP COLUMN，低版本需要"重建表法"
                db.execSQL("ALTER TABLE `User` DROP COLUMN `nickname`")
            }
        }
    }
}
```

### 2. Hilt 注入 Room + Repository

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.get(context)

    @Provides
    fun provideUserDao(db: AppDatabase): UserDao = db.userDao()
}

class UserRepository @Inject constructor(
    private val userDao: UserDao
) {
    // 直接暴露 Flow，ViewModel 里用 collectAsStateWithLifecycle 消费
    fun observeUsers(): Flow<List<User>> = userDao.observeAll()

    suspend fun addUser(name: String, email: String) {
        userDao.insert(User(email = email, name = name))
    }
}
```

### 3. ViewModel 中消费 Room 的 Flow

```kotlin
class UserListViewModel @Inject constructor(
    private val repository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<UserListUiState>(UserListUiState())
    val uiState: StateFlow<UserListUiState> = _uiState.asStateFlow()

    init {
        // 生命周期感知：页面不可见时自动取消订阅，Room 的查询也随之停止
        viewModelScope.launch {
            repository.observeUsers()
                .catch { e -> _uiState.update { it.copy(error = e.message)) } }
                .collect { list ->
                    _uiState.update { it.copy(loading = false, users = list)) }
                }
        }
    }
}
```

### 4. 原生 SQLite（不用 Room）的写法

```kotlin
class AppDbHelper(context: Context) :
    SQLiteOpenHelper(context, "legacy.db", null, 2) {

    // 首次创建
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS User (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                email TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_user_createdAt ON User(createdAt)")
    }

    // 版本升级
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE User ADD COLUMN nickname TEXT")
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)   // 外键默认关闭！
    }
}

// ---------- 使用 ----------
val helper = AppDbHelper(context)
// 注意：getWritableDatabase 会做 IO，务必在子线程
val db = helper.writableDatabase

// 事务批量插入：N 次 fsync → 1 次 fsync
db.beginTransaction()
try {
    db.execSQL("INSERT INTO User(email, name, createdAt) VALUES(?,?,?)",
        arrayOf("a@x.com", "A", System.currentTimeMillis()))
    db.setTransactionSuccessful()
} finally {
    db.endTransaction()   // 必须在 finally 里，否则事务不结束会锁库
}

// 查询：Cursor 是窗口，必须关闭
db.rawQuery("SELECT * FROM User WHERE name LIKE ?", arrayOf("%bob%")).use { cursor ->
    val nameIdx = cursor.getColumnIndexOrThrow("name")
    while (cursor.moveToNext()) {
        Log.d("User", cursor.getString(nameIdx))
    }
}
```

### 5. 迁移测试（Room Testing）

```kotlin
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        AutoMigrationSpec::class.java,       // 或指定 exported schema
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2_preservesData() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL("INSERT INTO User(email, name) VALUES('a@x.com', 'A')")
            close()
        }

        // 打开到 v2，会自动执行 MIGRATION_1_2
        helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2)

        // 校验数据还在
        helper.createDatabase(TEST_DB, 2).apply {
            val cursor = query("SELECT COUNT(*) FROM User")
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
            close()
        }
    }
}
```

## 常见问题 / 面试要点

**读者问：Room 的原理是什么？它到底做了什么"魔法"？**

**AI答：**

1. **一句话**：Room 是一个**编译期代码生成器**，运行时它就是普通的 `SupportSQLite` 操作。
2. **编译期**：
   - APT / KSP 扫描 `@Entity`、`@Dao`、`@Database`。
   - Room 用 **SQLite 方言解析器** 反向解析每个 Entity 生成"建表 SQL"，执行它得到**真实 schema**（表名、列名、列类型、NULL 约束、默认值、主键、外键、索引）。
   - 再用这个 schema **校验每一条 `@Query` 的 SQL**：语法、表存在性、列存在性、参数绑定数量、返回类型可构造性。
   - 校验通过后生成 `XxxDao_Impl.java` / `AppDatabase_Impl.java`，把 SQL **字符串化**（`String _sql = "SELECT ..."`），把 `@Query` 参数映射成 `bindArg` 占位符，把返回类型映射成 Cursor 读取代码。
3. **运行时**：
   - `RoomDatabase_Impl` 持有一个 `SupportSQLiteOpenHelper`，负责打开文件、创建表、执行 `Migration`、维护 `InvalidationTracker`。
   - DAO 方法体是生成好的 Java 代码，**没有反射**（这一点和 Gson / ButterKnife 不同）。
4. **为什么不直接用 SQLite**：Room 给你的是 **① 编译期正确性 ② 少写样板代码 ③ Flow 响应式 ④ 结构化迁移 ⑤ 关系映射**。
5. **面试话术**：
   > "Room 的核心是 **KSP/APT 在编译期读懂 DAO 接口、解析 SQL 并生成实现**。所以我们把 'SQL 写错' 从线上崩溃变成了编译失败。这就是它相对 `SupportSQLiteOpenHelper` 最大的价值——**把错误的发现时机从运行时提前到编译时**。"

---

**读者问：Room 和直接写 SQLite 有什么区别？什么时候该用哪个？**

**AI答：**

1. **七个维度对比**：

   | 维度 | 原生 SQLite | Room |
   | --- | --- | --- |
   | SQL 错误发现时机 | **运行时** | **编译期** |
   | 样板代码 | 大量（Cursor、ContentValues、getColumnIndex） | 接近零 |
   | 实体映射 | 手动写 Cursor → Model | `@Entity` 自动 |
   | 响应式 | 无（自己开轮询/Loader） | **Flow / LiveData** |
   | 关系查询 | 手动 JOIN + 手动组装 | `@Relation` + `@Transaction` |
   | 迁移 | 手写 `onUpgrade` | **AutoMigration** + schema 校验 |
   | 编译期类型安全 | 无 | 有（返回类型不匹配编译失败） |

2. **Room 的代价**：
   - **需要注解处理**（kapt 慢，KSP 快）→ 编译时间增加。
   - **schema 必须导出**（`exportSchema = true` + `room.schemaLocation`）→ 需要配 Gradle。
   - **不能用在动态 SQL 场景**：SQL 拼接（表名/条件动态变化）时 Room 无法在编译期校验，只能用 `@RawQuery` 绕过部分检查。
   - **改字段名 = 破坏性变更**：Room 认为"列丢了"→ 编译期不报错，但运行时 schema 校验会失败 → 必须写 Migration。
3. **什么时候仍然用原生 SQLite**：
   - 需要**动态 SQL**（用户自定义筛选条件）。
   - 需要**自定义 PRAGMA / 扩展函数 / 加密库**且 Room 校验不兼容（SQLCipher 场景）。
   - **多进程共享**（Room 官方不支持）。
   - 超小型、一次性查询（几条 SQL）。
4. **面试话术**：
   > "默认选 Room，因为编译期校验带来的收益远大于成本。只有在**动态 SQL**、**特殊加密库**或**多进程**这几种场景才退回原生 `SQLiteDatabase`。"

---

**读者问：Room 怎么做到编译期校验 SQL？写错了具体报什么错？**

**AI答：**

1. **机制**：APT/KSP 在编译期：
   - **正向**：把每个 `@Entity` 的建表 SQL 在内存中执行一遍（用 SQLite 引擎的 `sqlite3_exec` 或等价解析器），拿到**真实的表结构**。
   - **反向**：把每条 `@Query` 的 SQL 用**同样的 SQLite 解析器**解析成语法树，检查引用的表/列是否在上面的真实结构中存在。
   - **参数比对**：SQL 里的 `?` 数量 vs 方法参数数量。
2. **能抓到的典型错误**：

   ```text
   error: Cannot find table 'Users' in database
   error: Cannot find column 'nmae' in table 'User'
   error: A query was returned from a @Query method but the return type is not a supported type
   error: There are 2 parameters in the query but 1 arguments provided
   error: Cannot figure out how to save this field into database
   ```

3. **抓不到的（重要）**：
   - **运行时才能发现的逻辑错误**：比如 `WHERE age > 'abc'`（SQLite 弱类型不会报错，只会不匹配）。
   - **数据层面的错误**：唯一约束冲突（`@Insert` 时才触发）。
   - **动态 SQL 拼接**：Room 只检查静态部分。
   - **索引缺失导致的慢查询**：编译期无感知。
4. **与手写 SQL 的本质区别**：手写 SQL 是"**字符串**"，编译期不校验；Room 的 `@Query` 是"**注解参数**"，编译器会把它当作一等公民解析。
5. **面试话术**：
   > "Room 的编译期校验不是靠正则匹配 SQL 文本，而是**用 SQLite 自己的解析器解析 SQL 语法树**，再和 Entity 反推出来的真实表结构做交叉验证。所以它能抓出列名拼错、表不存在、参数个数不匹配这类问题——但抓不出语义错误和性能问题。"

---

**读者问：Room 的数据库迁移怎么做？AutoMigration 和手写 Migration 怎么选？**

**AI答：**

1. **Room 的迁移验证机制**：
   - Room 把每个版本的 schema 导出为 JSON（需 `exportSchema = true` + `room.schemaLocation "$projectDir/schemas"`）。
   - 数据库里有一张系统表 `room_master_table`，存当前版本的 **identity hash**。
   - 打开数据库时 Room 比对"当前代码的 hash"与"库里的 hash"，不一致就调 `onUpgrade` 并执行 `Migration`。
   - **执行完每个 Migration 后，Room 会再次校验最终 schema 是否与目标版本一致**，不一致直接抛异常 → 这保证了"迁移脚本写错"不会静默通过。
2. **AutoMigration 的能力边界**：

   | 变更类型 | AutoMigration | 必须手写 |
   | --- | --- | --- |
   | 新增列（带默认值） | ✅ | |
   | 新增索引 | ✅（2.6+） | |
   | 改列默认值 | ✅ | |
   | **重命名列** | ❌ | 建新表 → COPY → DROP → RENAME |
   | **改列类型/affinity** | ❌ | 重建表法 |
   | **删除列** | ❌（保守保留） | 重建表法 |
   | 加外键 / 改主键 | ❌ | 重建表法 |
   | 拆表 / 合表 | ❌ | 手写 |

3. **手写 Migration 的标准写法（改列名）**：

   ```kotlin
   val MIGRATION_2_3 = object : Migration(2, 3) {
       override fun migrate(db: SupportSQLiteDatabase) {
           // SQLite 没有 ALTER COLUMN RENAME（3.25 前），用"重建表法"
           db.execSQL("""
               CREATE TABLE User_new (
                   id INTEGER PRIMARY KEY AUTOINCREMENT,
                   email TEXT NOT NULL,
                   nick_name TEXT,
                   createdAt INTEGER NOT NULL DEFAULT 0
               )
           """.trimIndent())
           db.execSQL("INSERT INTO User_new(id, email, nick_name, createdAt) " +
                      "SELECT id, email, nickname, createdAt FROM User")
           db.execSQL("DROP TABLE User")
           db.execSQL("ALTER TABLE User_new RENAME TO User")
       }
   }
   ```

   ⚠️ 重建表法要**注意外键**：迁移期间要 `PRAGMA foreign_keys=OFF`，迁移完再 ON，否则 DROP 旧表会级联删数据。
4. **破坏性迁移**：
   - `fallbackToDestructiveMigration()`：迁移找不到时**删库重建** → 数据全丢。
   - 只适合"**纯缓存**"（比如 Room 存的搜索历史、草稿），**不适合用户业务数据**。
   - 线上事故案例：加了字段忘了写 Migration → 升级后用户数据被清空。
5. **面试话术**：
   > "我们的原则是：**能用 AutoMigration 就用 AutoMigration**，但只要涉及**改列名、改类型、拆表**就手写迁移，并且**每个迁移都要写 `MigrationTest` 覆盖**。`fallbackToDestructiveMigration` 我们只在纯缓存库上开，业务库绝不开启。"

---

**读者问：Room 能不能在主线程访问？`allowMainThreadQueries` 有什么风险？**

**AI答：**

1. **默认禁止**：`RoomDatabase` 默认 `allowMainThreadQueries == false`，主线程调用抛：
   ```
   IllegalStateException: Cannot access database on the main thread since it may
   potentially lock the UI for a long period of time.
   ```
2. **为什么禁止**：SQLite 查询是**同步阻塞 IO**，且在**事务/写锁**下可能等待其他连接 → 主线程卡住就是 **ANR**。
3. **Room 的自动线程切换**：
   - `suspend` 方法 → Room 用自己的 **`queryExecutor` / `transactionExecutor`**（默认 `ArchTaskExecutor` 的 IO 线程池）执行，调用方 `await`。
   - `Flow` 方法 → Room 在 executor 上执行查询，把结果 `emit` 到收集协程所在的线程（通常主线程）。
   - **同步方法**（既非 `suspend` 也非 `Flow`）→ **在调用线程执行** → 主线程调用直接崩。
4. **`allowMainThreadQueries()` 的正确使用场景**：
   - **单元测试**（Robolectric / instrumented test 里直接断言 DAO 结果）。
   - **极小数据集**的一次性查询（如"读一个开关位"）。
   - ❌ **绝不要**在生产代码里为了"省事"打开它。
5. **常见误区**：
   - "我用 `Flow` 就一定在主线程查了" → 错，Room 内部仍然在 IO 线程查，只是回调在主线程。
   - "我用 `suspend` 就安全" → 对，但**别在 `runBlocking` 里包 `suspend` DAO 调用**，那又回到主线程阻塞。
6. **面试话术**：
   > "Room 默认禁止主线程访问，这是刻意的 fail-fast 设计。因为数据库访问是同步阻塞 IO，而且可能在等锁——主线程等锁就是 ANR。我们的做法是 DAO 全用 `suspend` 或 `Flow`，让 Room 在自己的 executor 上执行。`allowMainThreadQueries` 只在测试里开。"

---

**读者问：WAL 模式是什么？为什么 Room 默认开启？多进程能用吗？**

**AI答：**

1. **WAL（Write-Ahead Logging）**：把**修改先追加写入 `-wal` 日志文件**，主库文件在 checkpoint 时才更新。
2. **并发模型对比**：
   - rollback journal：写者持**排他锁**，读者被阻塞 → 读写互斥。
   - WAL：写者追加日志（顺序写），读者读**一致性快照** → **读写并发**。
3. **默认开启**：Android **4.1（API 16）** 起，`SQLiteOpenHelper` 默认 `enableWriteAheadLogging` 为 true（对于 `getWritableDatabase`）。
   - Room 的 `RoomDatabase` 也继承这一默认。
   - ⚠️ 早期版本 `SQLiteDatabase.enableWriteAheadLogging()` 需要在 `getReadableDatabase()` 之前调用。
4. **WAL 的副作用**：
   - `-wal` 和 `-shm` 文件会留在数据库目录 → **备份时必须一起复制**（用 `SQLiteDatabase` 的 `backup` API 或 `VACUUM INTO`，不要直接 copy `.db`）。
   - WAL 需要**共享内存**（`-shm`）→ **不能放在网络文件系统**。
   - 长事务会让 WAL 膨胀 → checkpoint 延迟。
5. **多进程**：
   - SQLite 的 WAL **本身就是为多进程并发读设计的**（用一个 `-shm` 做读写锁协调）。
   - 但 **Room 不支持多进程**：
     - `InvalidationTracker` 只感知**本进程**连接的写入 → 别的进程改了数据，你的 Flow **不会自动刷新**。
     - Room 的 schema 校验在多进程同时打开时可能冲突。
     - Room 没有"主进程"概念。
   - **多进程正确姿势**：**单写者 + 多读者**，写操作全部走一个进程的 Binder 服务；或用 `SupportSQLiteOpenHelper` + 自己处理锁。
6. **面试话术**：
   > "WAL 是 Android 4.1 之后的默认模式，它把读写从互斥变成并发，对我们的 App 很有价值——比如后台同步写库时，前台查询不会卡。但 WAL 依赖共享内存，不能放 NFS；多进程虽然 SQLite 层面支持，Room 层面官方不支持，因为 Room 的 InvalidationTracker 是进程内的。"

---

**读者问：数据库加密怎么做？SQLCipher 和 Room 能一起用吗？**

**AI答：**

1. **三个层级**：

   | 方案 | 加密范围 | 查询能力 | 迁移成本 |
   | --- | --- | --- | --- |
   | **SQLCipher** | 整个 db 文件 | 完全可用（引擎层解密） | 需重新加密现有库 |
   | **字段级加密** | 指定列 | 该列**无法索引、无法比较、无法 SQL 过滤** | 需改 DAO |
   | **Keystore 托管密钥 + AES-GCM** | 列/文件 | 同上 | 最高 |

2. **SQLCipher + Room**：
   ```kotlin
   val passphrase = SQLiteDatabase.getBytes("my-secret".toCharArray())
   val factory = SupportFactory(passphrase)
   Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
       .openHelperFactory(factory)
       .build()
   ```
   ⚠️ **坑**：Room 的编译期校验依赖解析 `sqlite_master`，而 **SQLCipher 把 schema 表改名/加密**（默认 `sqlite_master` 变成 `sqlite_master` 但内容加密），可能导致 Room 报 "Room cannot verify the data integrity"。
   - 解决办法：使用社区支持的兼容方案（配置 `SupportFactory` 的 `clearPassphrase`、或改 Room 的 `identityHash` 生成、或在 SQLCipher 上手写 `SupportSQLiteOpenHelper`）。
3. **密钥管理（必须）**：
   - 密钥**绝不能硬编码在代码里**（apk 反编译即可拿到）。
   - 正确做法：Android **Keystore**（硬件密钥）生成密钥 → 导出加密后的 key blob → 存 `SharedPreferences`；或者用 `EncryptedSharedPreferences`。
   - 派生：口令 + salt → **PBKDF2 / Argon2** → master key；再由 master key 加密 db。
4. **性能代价**：加密会带来 **5%~20% 的性能损失**（每次页读取都要解密），且**无法使用 mmap 加速**。
5. **面试话术**：
   > "我们的合规要求是全库加密，选 SQLCipher。但要注意两点：一是密钥必须存在 Keystore 而不是代码里；二是 SQLCipher 和 Room 的 schema 校验有兼容问题，我们踩过 `Room cannot verify the data integrity` 这个坑，最终是用官方支持的 `SupportFactory` 配置解决的。"

---

**读者问：Room 的 Flow 是怎么实现的？数据变了它怎么知道的？其他进程改了能感知吗？**

**AI答：**

1. **实现原理**：`InvalidationTracker` 维护一张**内存中的表失效表**：
   - 每个被观察的表（`observedEntities`）对应一个 **观察者列表**。
   - Room 生成的 DAO 代码在写操作后调用 `__db.getInvalidationTracker().refreshVersionsAsync()`。
   - `refreshVersionsAsync` 在**事务提交后**检查数据库文件上的 **`sqlite_sequence` 里的 `version` 表**（Room 会创建一个 `room_table_modification_log` 表记录每次修改的表名）。
   - 发现某表版本号变了 → **唤醒**该表的所有观察者 → 重新执行查询 → 发射新列表。
2. **关键细节**：Room 的 `InvalidationTracker` 底层用 **`room_table_modification_log` 表 + 事务 ID**，保证"能检测到其他连接（包括同进程的其他 Database 实例）的写入"。
3. **能不能跨进程？**：
   - ❌ **不能自动感知**。`InvalidationTracker` 的通知是**进程内**的：另一个进程改了库，本进程的 Room 不知道，除非你**主动轮询**或使用 `enableMultiInstanceInvalidation`（Room 2.6+ 的实验特性，需要开 WAL）。
   - Room 2.6 提供了 `RoomDatabase.Builder.enableMultiInstanceInvalidation()`，但它要求 **WAL 模式**，且官方标注为实验 API。
4. **Flow 的一个常见误解**：
   - Flow **不是**"数据库主动 push 行级变更"，而是"**表级失效 → 重跑整个查询 → 发射新 List**"。
   - 所以**查询要轻**。在 `@Query` 里做 `JOIN` + 排序 + 分页时，每次表变更都会重跑。
5. **性能优化**：
   - 用 `@Transaction` + `@Relation` 时，Room 会把多次查询包在一个事务里，**只发射一次**。
   - 大列表用 **Paging 3 + Room**（见 [[../../07-架构、工具与生态/Jetpack库/Paging]]），避免一次性把整表读进内存。
6. **面试话术**：
   > "Room 的 Flow 是**表级失效通知**驱动的：任何写操作提交后，`InvalidationTracker` 发现表版本变了，就唤醒订阅者重新执行查询。所以它是'重查'而不是'增量推'。这也意味着——查询写得越重，Flow 的开销越大。还有一点很重要：**它是进程内的，跨进程改了库它感知不到**。"

## 关联笔记

- [[../README]]：第 4 部分总览与存储选型主线
- [[../本地存储/DataStore]]：配置项类数据不应进数据库，DataStore 更合适
- [[../本地存储/SharedPreferences]]：轻量 KV 与 Room 的边界
- [[../本地存储/文件存储]]：大对象、媒体文件应该落文件而不是数据库
- [[../数据格式/JSON与ProtoBuf]]：Room 实体字段与序列化格式的配合（如 JSON 列）
- [[../序列化原理/Parcelable与Serializable]]：ContentProvider 传输实体时的序列化选择
- [[../数据共享/MediaStore与数据共享]]：ContentProvider 的另一面是跨应用共享
- [[../../07-架构、工具与生态/Jetpack库/LiveData与StateFlow]]：Room 的 Flow 如何转成 UI 状态

## 参考资料

- Android 官方 Room 文档：https://developer.android.com/training/data-storage/room
- Android 官方数据库指南：https://developer.android.com/training/data-storage/sqlite
- SQLite 官方 WAL 文档：https://www.sqlite.org/wal.html
- Android 官方备份指南（备份数据库文件需排除 journal）：https://developer.android.com/guide/topics/data/autobackup
- Room 源码与文档（Google I/O 2017 "Room 持久化库"）：https://developer.android.com/training/data-storage/room/overview
