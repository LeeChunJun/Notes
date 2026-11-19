---
tags: [android]
part: 第2部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[../README]], [[../组件间通信/Binder机制]], [[../组件间通信/Intent]], [[Activity]]"
---

# ContentProvider

## 一句话定义

ContentProvider 是 Android 四大组件中**唯一面向"数据"而非"事件"或"界面"的组件**，它对外提供**一个统一的、结构化的数据访问接口**（本质是一套 **URI 寻址 + CRUD 语义**的 RPC API）。它**不是数据库**，而是一个**数据访问抽象层**——底层可以是 SQLite、文件、内存、甚至网络。

面试开场白：

> ContentProvider 的三个关键词是"**URI 寻址**"、"**统一的 CRUD 协议**"、"**跨进程数据接口**"。它把"数据在哪个 App"这件事抽象成"**用哪个 URI 查**"，调用方完全不需要知道数据存在哪个进程、哪个文件里。面试里最容易被追问的三点是：**它什么时候初始化**、**它怎么跨进程**、**它怎么保证安全**。

## 核心原理

### 1. 四大特点

| 特点 | 说明 |
| --- | --- |
| **① 对外数据接口** | 通过 URI 对外暴露数据，其他 App 只需知道 URI 就能访问，无需知道实现细节 |
| **② 跨进程能力** | 调用方和 Provider 在不同进程时，底层**自动走 Binder IPC**（调用的是 `ContentProvider.getIContentProvider()` 拿到的 `IContentProvider` 代理） |
| **③ 生命周期极早初始化** | Provider 的 `onCreate()` 在 **`Application.onCreate()` 之前**执行 |
| **④ 标准化的数据模型** | 统一用 `Cursor`（`MatrixCursor`）返回数据，统一用 `ContentValues` 写入，统一用 `Bundle` 传额外参数 |

**ContentProvider 不是"数据库"**：

```text
  调用方                     ContentProvider                    存储介质
┌──────────┐  query(uri)  ┌────────────────┐  内部实现  ┌──────────────┐
│ 你的App  │─────────────►│  对外：URI API │──────────►│ SQLite       │
└──────────┘              │  对内：任意实现 │            │ 文件/MediaStore│
                          └────────────────┘            │ 内存 / 远程API│
                                                          └──────────────┘
```

系统自带的 Provider 例子：
- `MediaStore`（`content://media/external/images/media`）→ 底层是 SQLite + 文件系统混合；
- `ContactsContract`（`content://com.android.contacts/contacts`）→ SQLite；
- `CalendarContract` → SQLite；
- `Settings.System` → 底层是 `SettingsProvider`（写的是系统配置文件）。

### 2. 初始化时机（比 Application.onCreate 还早）

**这是 ContentProvider 最经典的面试题。**

**进程启动顺序**：

```text
  1. ActivityThread.main()
        │
        2. Looper.prepareMainLooper()
        │
        3. ActivityThread.attach(context)      ← 创建 ActivityThread，绑定 AMS
        │
        4. AMS 通过 Binder 调用 attachApplication()
        │      │
        │      └─► Application.attachBaseContext(context)     ← ① ContentProvider 还没初始化
        │
        5. Instrumentation.newApplication(...)
        │      │
        │      └─► Application.onCreate()                    ← ② Application 初始化
        │
        6. installContentProviders()                          ← ③ ContentProvider 初始化
        │      │
        │      └─► ContentProvider.onCreate()                ← ④ Provider 初始化
        │
        7. Application.onCreate() 之后的逻辑继续
```

**实际的准确顺序**（Android 源码 `ActivityThread.handleBindApplication`）：

```text
  attachBaseContext()
      ↓
  Instrumentation.newApplication()  →  Application 的构造函数（还没调 onCreate）
      ↓
  mInstrumentation.callApplicationOnCreate(app)   →  Application.onCreate()
      ↓
  installContentProviders(app, providers)          →  ContentProvider.onCreate()
      ↓
  mInstrumentation.callApplicationOnCreate ... 之后
  Application.registerActivityLifecycleCallbacks ...
```

> ⚠️ **重要更正**：从 Android 源码看，`Application.onCreate()` **实际上先于** `ContentProvider.onCreate()` 被调用。真正早于 `Application.onCreate()` 的是 **`Application.attachBaseContext()`**。
>
> 常见的"ContentProvider 比 Application.onCreate 更早"的表述，来自 **Android 官方文档《Application 生命周期》**中的说法："Application 的 onCreate() 在 ContentProvider 的 onCreate() 之后被调用" —— 官方文档确实这么写，但**框架实现细节在不同版本有差异**。面试时的**稳妥话术**是：
>
> > "ContentProvider 的初始化**非常早**，在 Application 生命周期的早期阶段——早于 Activity、Service、BroadcastReceiver 的任何实例化。至于与 `Application.onCreate()` 的精确先后，取决于 Android 版本；但**可以确定的是**，ContentProvider 早于任何 Activity/Service。这带来一个重要结论：**ContentProvider 里不适合做复杂初始化**，因为它的生命周期是'**随进程**'的，而且早于用户界面。"

**这个"早初始化"的两个实际后果**：

1. **ContentProvider 的 `onCreate` 里可以安全地使用 `getContext().getApplicationContext()`**（Application 已 attach）。
2. **ContentProvider 不能依赖 Activity/Service 已经初始化**——因为它比它们都早。

**面试话术**："ContentProvider 早初始化是**特性**不是 bug——有些框架（如热修复框架、崩溃收集 SDK）**故意把自己的 Provider 放在 App 的 Provider 列表最前面**，从而抢在业务代码之前执行，实现'**最早的初始化钩子**'。"

### 3. URI 匹配规则

**URI 结构**：

```text
content://com.example.app.provider/user/123/profile

  │        │                        │      │       │
  │        │                        │      │       └─ path segment #4 (PROFILE)
  │        │                        │      └───────── path segment #3 (123)
  │        │                        └──────────────── path segment #2 (USER)
  │        └───────────────────────────────────────── authority (包名 + provider 名)
  └────────────────────────────────────────────────── scheme (content://)
```

**`UriMatcher` 的匹配类型**：

| 类型 | 常量 | 匹配规则 | 示例 |
| --- | --- | --- | --- |
| **精确匹配** | `addURI(authority, path, code)` | path 完全相等 | `content://auth/user` |
| **路径前缀** | `addURI(authority, pathPrefix, code)` | path 以该字符串开头 | `content://auth/user/` 前缀匹配 `user/123` |
| **路径模式** | `addURI(authority, pathPattern, code)` | 支持 `*` 和 `.*` 的正则式 | `.*` 匹配任意 path |
| **仅 authority** | `addURI(authority, null, code)` | 只匹配 authority（忽略 path） | 匹配所有 `content://auth/xxx` |

**匹配优先级（重要）**：`UriMatcher` 按注册顺序**逐个尝试**，**先注册先匹配**。因此必须把**更具体的规则注册在前面**：

```kotlin
// ✅ 正确顺序：具体在前，通用在后
uriMatcher.addURI(AUTHORITY, "/user/profile", CODE_PROFILE)
uriMatcher.addURI(AUTHORITY, "/user/*",     CODE_USER_DETAIL)
uriMatcher.addURI(AUTHORITY, "/user",        CODE_USER_LIST)
uriMatcher.addURI(AUTHORITY, null,           CODE_ALL)
```

> 若把 `/user` 注册在 `/user/profile` 前面，`content://auth/user/profile` 会被 `/user` 匹配掉（因为 `/user` 是精确匹配，只在 path 完全相等时命中——所以这里 `/user` 不会误匹配 `/user/profile`）。但如果用了 `addURI(AUTH, "/user", CODE)` 且同时有 `addURI(AUTH, "/user/", CODE2)` 前缀规则，顺序就很重要。**稳妥做法：具体规则优先注册**。

**`ContentResolver` 的用法**：

```kotlin
val resolver = context.contentResolver

// 查询
val cursor = resolver.query(
    Uri.parse("content://com.example.app.provider/user/123"),
    arrayOf("_id", "name", "age"),   // projection
    "age > ?",                        // selection
    arrayOf("18"),                    // selectionArgs
    "name ASC"                       // sortOrder
)
cursor?.use {
    val idIndex = it.getColumnIndexOrThrow("_id")
    while (it.moveToNext()) {
        val id = it.getLong(idIndex)
    }
}
```

**标准列名约定**：`_id`（主键）、`_display_name`、`_size`、`mime_type`、`last_modified` 等。

### 4. CRUD 四件套

**ContentProvider 的标准接口**（全部可以 `override`）：

| 方法 | 作用 | 返回 |
| --- | --- | --- |
| `query(uri, projection, selection, selectionArgs, sortOrder)` | 查 | `Cursor` |
| `insert(uri, values)` | 增 | `Uri`（新记录的 URI） |
| `update(uri, values, selection, selectionArgs)` | 改 | 影响的行数 `Int` |
| `delete(uri, selection, selectionArgs)` | 删 | 影响的行数 `Int` |
| `getType(uri)` | 取 MIME 类型 | `String` |
| `call(method, arg, extras)` | 自定义方法（API 11+） | `Bundle` |
| `openFile(uri, mode)` | 打开文件（API 19+） | `ParcelFileDescriptor` |
| `openAssetFile(...)` | 打开 assets 文件 | `ParcelFileDescriptor` |

**标准实现示例**：

```kotlin
class UserProvider : ContentProvider() {

    private lateinit var dbHelper: UserDbHelper

    override fun onCreate(): Boolean {
        // 注意：ContentProvider 初始化极早，这里只能做轻量初始化
        dbHelper = UserDbHelper(context!!)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val db = dbHelper.readableDatabase
        val (table, where, args) = buildQuery(uri, projection, selection, selectionArgs)
        val cursor = db.query(table, projection, where, args, null, null, sortOrder)
        // 必须设置 notification uri，否则 ContentObserver 收不到通知
        cursor.setNotificationUri(context!!.contentResolver, uri)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val db = dbHelper.writableDatabase
        val id = db.insert("users", null, values)
        val newUri = ContentUris.withAppendedId(BASE_URI, id)
        // 通知观察者：数据变了
        context!!.contentResolver.notifyChange(newUri, null)
        return newUri
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        val db = dbHelper.writableDatabase
        val (table, where, args) = buildQuery(uri, null, selection, selectionArgs)
        val count = db.update(table, values, where, args)
        if (count > 0) context!!.contentResolver.notifyChange(uri, null)
        return count
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        val db = dbHelper.writableDatabase
        val (table, where, args) = buildQuery(uri, null, selection, selectionArgs)
        val count = db.delete(table, where, args)
        if (count > 0) context!!.contentResolver.notifyChange(uri, null)
        return count
    }

    override fun getType(uri: Uri): String = when (uriMatcher.match(uri)) {
        CODE_USER_LIST -> "vnd.android.cursor.dir/vnd.com.example.app.user"
        CODE_USER_DETAIL -> "vnd.android.cursor.item/vnd.com.example.app.user"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    // 把 URI 中的 id 合并到 where 条件里，防止越权访问
    private fun buildQuery(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Triple<String, String?, Array<out String>?> {
        when (uriMatcher.match(uri)) {
            CODE_USER_LIST -> return Triple("users", selection, selectionArgs)
            CODE_USER_DETAIL -> {
                val id = ContentUris.parseId(uri)
                val newSelection = if (selection.isNullOrBlank()) "id = ?" else "$selection AND id = ?"
                val newArgs = arrayOf(id.toString()) + (selectionArgs ?: emptyArray())
                return Triple("users", newSelection, newArgs)
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    companion object {
        const val AUTHORITY = "com.example.app.provider"
        val BASE_URI: Uri = Uri.parse("content://$AUTHORITY")

        const val CODE_USER_LIST = 1
        const val CODE_USER_DETAIL = 2

        @JvmStatic
        val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "/user", CODE_USER_LIST)
            addURI(AUTHORITY, "/user/*", CODE_USER_DETAIL)
        }

        fun userUri(id: Long): Uri = ContentUris.withAppendedId(BASE_URI, id)
    }
}
```

### 5. MIME 类型与参数传递

**MIME 类型**（`getType(uri)`）：

| 类型 | 格式 | 含义 |
| --- | --- | --- |
| 目录（多条记录） | `vnd.android.cursor.dir/vnd.<pkg>.<type>` | 表示"这是一个集合" |
| 单条记录 | `vnd.android.cursor.item/vnd.<pkg>.<type>` | 表示"这是一条记录" |
| 文件 | `application/pdf`、`image/jpeg` | `openFile` / `openAssetFile` 使用 |

也可以用 `ContentResolver.getType(uri)` 从客户端查询类型。

**参数传递的三种方式**：

1. **selection / selectionArgs**：SQL WHERE 条件（注意 **SQL 注入**，`selectionArgs` 是绑定参数，**绝不要把用户输入拼进 selection**）。
2. **query 的 Bundle 参数**（API 26+）：`query(uri, projection, queryArgs, cancellationSignal)`，用 `ContentResolver.QUERY_ARG_SQL_SELECTION` 等 Bundle 传参，**更安全**（`selectionArgs` 在 API 30+ 已废弃）。
3. **`call(method, arg, extras)`**（API 11+）：自定义方法，支持 `Bundle` 输入输出。

```kotlin
// call 的用法
val result: Bundle? = context.contentResolver.call(
    Uri.parse("content://com.example.app.provider"),
    "getStats",
    null,
    bundleOf("range" to "last7days")
)
```

### 6. ContentProvider 的跨进程原理（实际走 Binder）

**调用链**：

```text
  调用方进程                          Provider 进程
┌──────────────────┐            ┌──────────────────────┐
│ ContentResolver  │            │                      │
│  .query(uri,...) │            │                      │
│        │         │            │                      │
│        ▼         │            │                      │
│ acquireUnstable/ │  Binder    │  ContentProvider      │
│ acquireProvider  │──transact─►│  .onTransact()       │
│ (null)           │  (IPC)     │        │             │
│        │         │            │        ▼             │
│        ▼         │            │  query() 实现         │
│ IContentProvider │            │        │             │
│  (Proxy)         │            │        ▼             │
└──────────────────┘            │   SQLite / 文件       │
                                └──────────────────────┘
```

**关键实现**：

- `ContentResolver.query()` 内部先 `acquireProvider(uri)`：
  - 若 Provider **在同进程** → 直接拿到 `ContentProvider` 实例，**直接调用** `provider.query()`，**无 IPC**。
  - 若在**其他进程** → 通过 `ActivityManager.getContentProvider()` 拿到 **`IContentProvider` 的 Binder 代理**，调用 `query()` 会走 `ContentProviderNative.onTransact()` → `ContentProvider.this.query()`。
- **返回值 `Cursor` 的传递**：Cursor 不能直接序列化。它被包装成 `CursorToBulkCursorAdaptor` / `BulkCursorCursor`，**内部数据仍然留在 Provider 进程**，客户端通过 **`ParcelFileDescriptor`（共享内存）+ Binder 回调（`fillWindow`）** 惰性拉取窗口数据。这是"**远程 Cursor**"机制。

> 面试话术："ContentProvider 的跨进程能力**不是自己实现的**，而是**复用 ContentProvider 的 Binder 通道**——`ContentProvider` 内部持有一个 `IContentProvider` 代理（`ContentProviderNative`），所有 CRUD 都通过 `transact()` 走 IPC。它和 AIDL 的区别在于：**接口是系统预定义的，不需要你自己写 .aidl**。"

### 7. 权限模型

**三种权限保护方式**：

**① Manifest 级别的权限**（保护整个 Provider）：

```xml
<provider
    android:name=".UserProvider"
    android:authorities="com.example.app.provider"
    android:exported="true"
    android:grantUriPermissions="true"
    android:readPermission="com.example.app.permission.READ_USER"
    android:writePermission="com.example.app.permission.WRITE_USER" />
```

- `readPermission` / `writePermission`：调用方必须持有对应权限（自定义权限需 `protectionLevel="normal"` 或 `"signature"`）。
- `exported="false"`：**只有本 App 能访问**，外部 App 调用会抛 `SecurityException`。

**② URI 级别的临时授权（`grantUriPermission`）**：

```kotlin
// 授权：允许某个 App 临时访问某个具体 URI
context.grantUriPermission(
    targetPackage,                    // 接收方包名
    uri,                              // 具体 URI
    Intent.FLAG_GRANT_READ_URI_PERMISSION or
    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
)
```

**③ 临时授权 + Intent Flag（更常用）**：

```kotlin
// 发送方：在 Intent 里带授权 flag
val intent = Intent(Intent.ACTION_VIEW).apply {
    data = uri
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
// 接收方：读取授权
val takeFlags = intent.flags and
    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
context.contentResolver.takePersistableUriPermission(uri, takeFlags)
```

**④ 代码级校验（在 Provider 内部）**：

```kotlin
override fun query(...): Cursor? {
    // 检查调用者是否持有权限
    val callingUid = Binder.getCallingUid()
    if (!isCallerAllowed(callingUid)) {
        throw SecurityException("Caller $callingUid not allowed")
    }
    // 检查是否在调用栈中
    val stack = context!!.getSystemService(ActivityManager::class.java)
    val pid = Binder.getCallingPid()
    val callingPackage = stack.getRunningAppProcesses()
        ?.firstOrNull { it.pid == pid }?.processName
    // 校验签名（防伪造）
    val sig = context!!.packageManager.getPackageInfo(callingPackage!!, 64).signatures
    ...
    return cursor
}
```

**⑤ `ContentProvider` 的 `call()` 方法做动态授权**：很多 App 用 `call("authorize", ...)` 返回一个带 token 的临时授权。

### 8. 常见系统 Provider

| Provider | Authority | 用途 | 必需权限（API 29+ 分区存储） |
| --- | --- | --- | --- |
| **MediaStore** | `com.android.providers.media.external` | 图片/视频/音频 | `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO` |
| **ContactsContract** | `com.android.contacts` | 联系人 | `READ_CONTACTS` |
| **CalendarContract** | `com.android.calendar` | 日历 | `READ_CALENDAR` |
| **CallLog** | `com.android.providers.contacts` | 通话记录 | `READ_CALL_LOG` |
| **Settings** | `com.android.settings` / `android.provider.Settings` | 系统设置 | 读：`READ_SETTINGS`；写：`WRITE_SETTINGS`（特殊权限） |
| **Telephony.Sms** | `com.android.providers.telephony` | 短信 | `READ_SMS` |
| **Downloads** | `com.android.providers.downloads.documents` | 下载文件 | — |

**MediaStore 的现代用法（分区存储）**：

```kotlin
// 写入图片到 MediaStore（不需要存储权限）
val values = ContentValues().apply {
    put(MediaStore.Images.Media.DISPLAY_NAME, "screenshot_$timestamp.png")
    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
    put(MediaStore.Images.Media.IS_PENDING, 1)
}
val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
contentResolver.openOutputStream(uri)!!.use { out ->
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
}
values.clear()
values.put(MediaStore.Images.Media.IS_PENDING, 0)
contentResolver.update(uri, values, null, null)
```

**读取系统通讯录**：

```kotlin
val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
val projection = arrayOf(
    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
    ContactsContract.CommonDataKinds.Phone.NUMBER
)
contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
    val nameIdx = cursor.getColumnIndex(Phone.DISPLAY_NAME)
    while (cursor.moveToNext()) {
        Log.d("Contacts", "${cursor.getString(nameIdx)}")
    }
}
```

### 9. ContentObserver：监听数据变化

```kotlin
// 注册观察者
val uri = Uri.parse("content://com.example.app.provider/user")
contentResolver.registerContentObserver(uri, true, object : ContentObserver(Handler(Looper.getMainLooper())) {
    override fun onChange(selfChange: Boolean) {
        // 数据变了，重新查询并刷新 UI
        loadUsers()
    }
})

// 注销（必须，否则泄漏）
contentResolver.unregisterContentObserver(observer)
```

**跨进程的变化通知**：`notifyChange(uri, null)` 会通过 `ContentResolver` 的 **Binder** 通知到所有注册的观察者——**即使观察者与 Provider 在不同进程**，也能收到。这是 ContentProvider 跨进程能力的又一体现。

## 代码示例

### 1. 自定义 ContentProvider（完整可用版）

```kotlin
class NoteProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.example.app.note_provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")

        const val PATH_NOTES = "notes"
        const val PATH_NOTE_ID = "notes/#"

        private const val CODE_NOTES = 100
        private const val CODE_NOTE_ID = 101

        @JvmStatic
        val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_NOTES, CODE_NOTES)
            addURI(AUTHORITY, "notes/#", CODE_NOTE_ID)
        }

        // 供外部 App 使用的列定义
        const val COL_ID = "_id"
        const val COL_TITLE = "title"
        const val COL_CONTENT = "content"
        const val COL_CREATED = "created_at"
    }

    private lateinit var db: NoteDatabase

    override fun onCreate(): Boolean {
        // ContentProvider 初始化极早（早于 Activity/Service），
        // 这里只做轻量的 DB 打开，不要做耗时初始化
        db = NoteDatabase.getInstance(requireNotNull(context))
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val qb = SQLiteQueryBuilder().apply {
            tables = "notes"
        }
        when (uriMatcher.match(uri)) {
            CODE_NOTES -> {
                // 查询全部，selection 由调用方传入
            }
            CODE_NOTE_ID -> {
                qb.appendWhere("${COL_ID} = ${ContentUris.parseId(uri)}")
            }
            else -> throw IllegalArgumentException("Unsupported URI: $uri")
        }
        val cursor = qb.query(db.readableDatabase, projection, selection, selectionArgs, null, null, sortOrder)
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        require(uriMatcher.match(uri) == CODE_NOTES) { "Insert only allowed on $PATH_NOTES" }
        val id = db.writableDatabase.insert("notes", null, values)
        val newUri = ContentUris.withAppendedId(CONTENT_URI, id)
        requireNotNull(context).contentResolver.notifyChange(CONTENT_URI, null)
        return newUri
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val (where, args) = when (uriMatcher.match(uri)) {
            CODE_NOTES -> selection to selectionArgs
            CODE_NOTE_ID -> "${COL_ID} = ?" to arrayOf(ContentUris.parseId(uri).toString())
            else -> throw IllegalArgumentException("Unsupported URI: $uri")
        }
        val count = db.writableDatabase.delete("notes", where, args)
        if (count > 0) requireNotNull(context).contentResolver.notifyChange(CONTENT_URI, null)
        return count
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        val (where, args) = when (uriMatcher.match(uri)) {
            CODE_NOTES -> selection to selectionArgs
            CODE_NOTE_ID -> "${COL_ID} = ?" to arrayOf(ContentUris.parseId(uri).toString())
            else -> throw IllegalArgumentException("Unsupported URI: $uri")
        }
        val count = db.writableDatabase.update("notes", values, where, args)
        if (count > 0) requireNotNull(context).contentResolver.notifyChange(CONTENT_URI, null)
        return count
    }

    override fun getType(uri: Uri): String = when (uriMatcher.match(uri)) {
        CODE_NOTES -> "vnd.android.cursor.dir/vnd.$AUTHORITY.note"
        CODE_NOTE_ID -> "vnd.android.cursor.item/vnd.$AUTHORITY.note"
        else -> throw IllegalArgumentException("Unsupported URI: $uri")
    }
}
```

Manifest 声明：

```xml
<provider
    android:name=".NoteProvider"
    android:authorities="com.example.app.note_provider"
    android:exported="false"
    android:grantUriPermissions="true" />
```

### 2. 客户端访问 Provider

```kotlin
class NoteRepository(private val context: Context) {

    private val resolver = context.contentResolver
    private val uri = Uri.parse("content://com.example.app.note_provider/notes")

    fun insert(title: String, content: String): Uri? {
        val values = ContentValues().apply {
            put("title", title)
            put("content", content)
            put("created_at", System.currentTimeMillis())
        }
        return resolver.insert(uri, values)
    }

    fun queryAll(): List<Note> {
        val result = mutableListOf<Note>()
        // API 30+ 推荐用 Bundle 传 selectionArgs（避免 SQL 注入）
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "created_at > ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("0"))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "created_at DESC")
        }
        resolver.query(uri, arrayOf("_id", "title", "content", "created_at"), queryArgs, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("_id")
            val titleIdx = cursor.getColumnIndexOrThrow("title")
            val contentIdx = cursor.getColumnIndexOrThrow("content")
            val createdIdx = cursor.getColumnIndexOrThrow("created_at")
            while (cursor.moveToNext()) {
                result += Note(
                    id = cursor.getLong(idIdx),
                    title = cursor.getString(titleIdx),
                    content = cursor.getString(contentIdx),
                    createdAt = cursor.getLong(createdIdx)
                )
            }
        }
        return result
    }

    fun delete(id: Long): Int {
        val itemUri = ContentUris.withAppendedId(uri, id)
        return resolver.delete(itemUri, null, null)
    }
}
```

### 3. ContentObserver：监听跨进程数据变化

```kotlin
class NoteViewModel(application: Application) : AndroidViewModel(application) {

    private val resolver = application.contentResolver
    private var observer: ContentObserver? = null

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    init {
        refresh()
        // 注册观察者：Provider 在其他进程 notifyChange 时也会回调到这里
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }
        resolver.registerContentObserver(
            Uri.parse("content://com.example.app.note_provider/notes"),
            true,     // notifyForDescendants：子 URI 也通知
            observer
        )
    }

    private fun refresh() {
        _notes.value = NoteRepository(getApplication()).queryAll()
    }

    override fun onCleared() {
        observer?.let { resolver.unregisterContentObserver(it) }
        super.onCleared()
    }
}
```

### 4. 临时 URI 授权（安全共享文件）

```kotlin
// 发送方：把私有文件临时授权给接收方
fun shareFile(context: Context, targetPackage: String, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.grantUriPermission(targetPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(intent)
}

// FileProvider 配置（res/xml/file_paths.xml）
// <paths>
//     <files-path name="internal" path="docs/" />
//     <cache-path name="cache" path="shared/" />
// </paths>
```

### 5. 用 `call()` 实现自定义方法

```kotlin
// Provider 端
class StatsProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return when (method) {
            "getStats" -> Bundle().apply {
                putInt("total", 42)
                putString("method", arg ?: "")
            }
            else -> throw IllegalArgumentException("unknown method: $method")
        }
    }
    // 其他 CRUD 省略
}

// 客户端
val bundle = context.contentResolver.call(
    StatsProvider.CONTENT_URI,
    "getStats",
    "daily",
    null
)
val total = bundle?.getInt("total")
```

## 常见问题 / 面试要点

**读者问：ContentProvider 到底是什么？它和 SQLite 有什么区别？**

**AI答：**

1. **本质区别**：
   - **SQLite** 是**存储引擎**（数据真正躺在哪里）。
   - **ContentProvider** 是**数据访问的对外接口层**（数据怎么被找到、怎么被授权、怎么被通知变更）。
2. **关系**：
   - ContentProvider **通常**用 SQLite 作为底层实现，但**不是必须**。
   - 系统的 `MediaStore` 就是"SQLite 索引 + 真实文件在文件系统"的混合实现。
   - 你完全可以写一个"Provider 背后是 SharedPreferences"，或"Provider 背后是内存 Map"，或"Provider 背后是网络请求"。
3. **ContentProvider 额外提供的能力**（这些是 SQLite 没有的）：
   - **跨进程访问**（通过 Binder 自动完成）；
   - **权限模型**（Manifest 权限 + URI 临时授权 + 代码级校验）；
   - **变更通知**（`notifyChange` + `ContentObserver`）；
   - **统一的寻址**（URI），调用方无需知道数据实现。
4. **面试话术**："ContentProvider 是**接口**，SQLite 是**实现**。ContentProvider 定义了'**数据如何被访问**'的协议，SQLite 定义了'**数据如何被存储**'的机制。把它们混为一谈，是初级面试常见的认知错误。"
5. **补充加分点**：提到 `Room` 也是建立在 SQLite 之上的，但 Room 是"**本地 ORM**"，不是 ContentProvider；Room 想跨进程对外暴露数据时，仍需套一层 ContentProvider。

---

**读者问：ContentProvider 是什么时候初始化的？为什么有人说它比 Application.onCreate 还早？**

**AI答：**

1. **确定的结论**：ContentProvider 的 `onCreate()` **远早于任何 Activity / Service / BroadcastReceiver 的实例化**。因为 Provider 是"**进程启动即可用**"的数据入口——别的进程可能在你还没显示任何界面前就要查你的数据。
2. **与 Application 的精确顺序**：
   - **框架实现**（`ActivityThread.handleBindApplication`）：`attachBaseContext()` → `Application` 构造 → `Application.onCreate()` → **`installContentProviders()`（Provider.onCreate）** → `Application.onCreate()` 之后的注册。
   - **官方文档的说法**：官方《Application 生命周期》文档描述为 Provider 的 `onCreate` 在 `Application.onCreate` **之后**，而这与上述源码顺序一致；流传的"Provider 更早"通常指的是**早于 Activity/Service**。
   - **稳妥话术**（面试可用）：
     > "ContentProvider 的初始化在 Application 生命周期的早期，早于所有界面组件；至于与 `Application.onCreate()` 的严格先后，官方文档与不同版本实现有差异，我在实践中**不会依赖这个顺序**——我依赖的是'**它一定早于 Activity**'这条确定事实。"
3. **这个"最早初始化"的实际价值**：
   - **热修复框架**（Tinker/Sophix）把自己的 Provider 放在**用户 Provider 列表的最前面**，从而在业务代码之前完成补丁加载；
   - **崩溃收集 / 性能监控 SDK** 用 Provider 作为"最早的初始化钩子"；
   - **无侵入式 SDK 初始化**。
4. **因此的工程约束**：
   - **Provider 的 `onCreate` 里不能做耗时初始化**（会拖慢所有用到它的调用方）；
   - **不能依赖 Activity/Service 已初始化**；
   - **不能假设 `Application.onCreate` 已执行完**（做单例初始化时要注意时序）。
5. **面试话术**："Provider 早初始化是**特性**：它给了框架一个'**进程一启动我就能干活**'的锚点。代价是——Provider 的初始化代码必须是'**万无一失**'的，因为它一旦崩溃，整个 App 起不来，而且用户看到的是'**应用无响应**'而不是具体页面报错。"

---

**读者问：ContentProvider 是怎么跨进程的？和 AIDL 有什么区别？**

**AI答：**

1. **跨进程链路**：
   - 客户端 `ContentResolver.query(uri, ...)`；
   - `ContentResolver` 内部 `acquireProvider(uri)`：
     - **同进程** → 直接拿到 `ContentProvider` 实例，**直接方法调用，零 IPC**；
     - **跨进程** → 通过 `ActivityManager.getContentProvider()` 拿到 **`IContentProvider` 的 Binder 代理**（`ContentProviderNative`），调用 `query()` 会触发 `Binder.transact()`。
   - Provider 进程侧：`ContentProviderNative.onTransact()` → `ContentProvider.this.query()` → 你的实现。
2. **Cursor 的跨进程传递**：Cursor **不能序列化**。系统把它包成**远程 Cursor**（`BulkCursor`），数据**留在 Provider 进程**，客户端通过 `ParcelFileDescriptor`（共享内存）+ Binder 回调 `fillWindow` **惰性拉取**窗口数据。这就是"**一次传输一个窗口**"的机制。
3. **与 AIDL 的对比**：

   | 维度 | ContentProvider | AIDL |
   | --- | --- | --- |
   | 接口定义 | **系统预定义**，无需写代码 | 需要写 `.aidl` 文件并编译 |
   | 数据模型 | 固定的 `Cursor` / `ContentValues` / `Bundle` | **任意自定义**，强类型 |
   | 返回值 | 只能是 Cursor 或 int，**表达能力有限** | 任意类型 |
   | 权限 | **内置**（Manifest 权限 + URI 授权 + `Binder.getCallingUid()`） | **需自己实现** |
   | 变更通知 | **内置**（`notifyChange` + `ContentObserver`） | 需自己实现 |
   | 适用 | 数据共享（联系人、照片、笔记） | 复杂业务逻辑的跨进程调用 |

4. **面试话术**："ContentProvider 的跨进程能力是'**白送**'的——因为它本身就是系统为'**跨进程数据共享**'设计的标准组件，它的接口协议已经被系统定义好了（`IContentProvider`）。AIDL 是给你'**自定义跨进程接口**'的工具。**能用 ContentProvider 就别写 AIDL**——除非你需要强类型和复杂返回值。"
5. **补充**：`ContentProvider.call()` 提供了一个"**半自定义**"的折中方案——可以传 `Bundle` 进、传 `Bundle` 出，兼具灵活性与内置权限模型。

---

**读者问：ContentProvider 的 URI 匹配是怎么工作的？`UriMatcher` 的几种匹配方式有什么区别？**

**AI答：**

1. **URI 结构**：`content://authority/path1/path2/...`
   - `scheme`（`content`）→ 决定由 ContentResolver 处理而非 Intent；
   - `authority`（`com.example.app.provider`）→ **唯一定位 Provider**（Manifest 中声明）；
   - `path` → 定位 Provider 内的具体资源。
2. **`UriMatcher` 的匹配类型**：

   | 方法 | 匹配规则 | 示例 |
   | --- | --- | --- |
   | `addURI(auth, "path", code)` | **精确**匹配 | `"user"` 只匹配 `content://auth/user` |
   | `addURI(auth, "path/", code)` | **前缀**匹配 | `"user/"` 匹配 `content://auth/user/123` |
   | `addURI(auth, ".*", code)` | **正则**匹配 | `".*"` 匹配任意 path |
   | `addURI(auth, null, code)` | 只匹配 **authority** | 忽略所有 path |

3. **匹配结果的使用**：`uriMatcher.match(uri)` 返回注册的 `code`，据此分发到不同的处理分支。
4. **重要细节**：
   - `UriMatcher` **按注册顺序**逐个尝试，**先注册先匹配** → **具体规则要注册在通用规则之前**。
   - `addURI(auth, "path", code)` 中的 `path` **不带前导斜杠**（规范写法），匹配时会自动补 `/`。
   - `addURI(auth, "path/#", code)` 这种写法**不是**通配符——`#` 只是普通字符，**真正的通配是 `*`（前缀）和 `.*`（正则）**。这是高频误区。
5. **面试话术**："`UriMatcher` 是'**路径 → 整型常量**'的路由表。匹配的本质是'**按注册顺序做字符串/正则比对**'，因此规则顺序和写法精度直接决定了会不会误匹配。"

---

**读者问：ContentProvider 的安全怎么保证？`exported="true"` 会不会有风险？**

**AI答：**

1. **风险面**：`exported="true"` 的 Provider 是**任何 App 都能访问的数据入口**。历史上大量数据泄露漏洞（如通过 Provider 查询拿到其他 App 的私有数据）就是这么来的。
2. **多层防护手段**：

   | 层次 | 手段 | 说明 |
   | --- | --- | --- |
   | **① Manifest 权限** | `android:readPermission` / `writePermission` | 调用方必须持有权限 |
   | **② 导出开关** | `android:exported="false"` | **只有本 App 能访问**（最简单有效） |
   | **③ URI 临时授权** | `grantUriPermission()` + `FLAG_GRANT_READ_URI_PERMISSION` | 精确到单个 URI、单个 App、临时有效 |
   | **④ 代码校验** | `Binder.getCallingUid()` / `getCallingPid()` | 判断调用方身份，做白名单或签名校验 |
   | **⑤ 参数化查询** | `selectionArgs` / `QUERY_ARG_SQL_SELECTION_ARGS` | 防 SQL 注入 |
   | **⑥ 路径守卫** | 在 `buildQuery` 里把 URI 中的 id 合并进 where | 防"越权查他人数据"（**最容易被忽略的漏洞**） |

3. **`exported` 的选择原则**：
   - **绝大多数 Provider 应该 `exported="false"`**；
   - 需要对外暴露时，**必须**配 `readPermission`/`writePermission` **或** `grantUriPermissions` + 临时授权（**不要只靠 exported=true**）；
   - 系统 Provider（如 `MediaStore`）是 `exported="true"` + 细粒度权限。
4. **越权访问的经典漏洞（面试加分点）**：
   - Provider 支持 `content://auth/user/123` 这种带 id 的 URI；
   - 但 `query()` 实现里**只用了 `selection`，没有把 URI 里的 id 加进 where**；
   - 攻击者传 `selection=null` + `content://auth/user/999999` → **能查到任何人的记录**。
   - **正确做法**：见上文 `buildQuery()`——把 `ContentUris.parseId(uri)` 强制合并进 where 条件。
5. **面试话术**："Provider 的安全不是'**开不开 exported**'一个问题，而是'**Manifest 权限 + URI 粒度授权 + 代码内调用方校验 + 参数化 SQL**'四层防御。**默认 `exported=false`，必须对外时用临时授权。**"

---

**读者问：ContentProvider 通知数据变更的机制是什么？`notifyChange` 和 `ContentObserver` 怎么配合？**

**AI答：**

1. **机制**：
   - Provider 在数据变化后调用 `contentResolver.notifyChange(uri, null)`；
   - 系统（`ContentResolver`）会把这条通知**通过 Binder 广播给所有进程**中注册在该 URI 上的 `ContentObserver`；
   - 观察者的 `onChange()` 被回调（默认在注册时指定线程的 Looper 上；若用 `ContentObserver(null)` 则在调用 `notifyChange` 的线程回调）。
2. **关键前提**：`query()` 返回的 Cursor 必须调用 `cursor.setNotificationUri(resolver, uri)`，否则**不会**收到该 URI 的变更通知。这是高频坑。
3. **`notifyForDescendants` 参数**：`registerContentObserver(uri, true, observer)` 中的 `true` 表示**子 URI 也通知**（如 `content://auth/user` 的变更也会通知 `content://auth/user/123` 的观察者）。
4. **跨进程能力**：由于通知走 Binder，**观察者与 Provider 在不同进程也能收到**。
5. **面试话术**："ContentProvider 的'数据变了'通知和广播类似，但更'**精确**'——它是**按 URI 精确投递**的，而不是全系统广播。这也是为什么它比 `ACTION_DATA_CHANGED` 那种广播更适合做数据同步。"
6. **补充**：Android 8+ 的隐式广播限制**不影响** `notifyChange`，因为它不是 Intent 广播。

## 关联笔记

- [[../README]]：第 2 部分总览
- [[../组件间通信/Binder机制]]：ContentProvider 跨进程的底层通道
- [[../组件间通信/Intent]]：URI 与 Intent 的配合（如 `ACTION_VIEW` + `content://`）
- [[Activity]]：Provider 早于 Activity 初始化
- [[Service]]：Provider 与 Service 的生命周期对比
- [[../Application与Context]]：Provider 的 Context 来源与初始化时机
- [[../../04-数据存储与处理/数据共享/MediaStore与数据共享]]：系统 Provider 的实际使用
- [[../../06-系统原理与性能优化/系统核心机制/权限机制]]：Provider 权限与动态权限的关系

## 参考资料

- Android 官方文档 · ContentProvider 基础：https://developer.android.com/guide/topics/providers/content-providers-basics
- Android 官方文档 · ContentProvider 进阶（URI 匹配、MIME、批量操作）：https://developer.android.com/guide/topics/providers/content-providers-advanced
- Android 官方文档 · Contacts 与 Calendar Provider：https://developer.android.com/guide/topics/providers/contacts-provider
- Android 官方文档 · MediaStore：https://developer.android.com/training/data-storage/shared/media
- 《Android 高级编程》第 4 版 · 第 7 章（内容提供者）
