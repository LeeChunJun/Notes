---
tags: [android]
part: 第4部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[../README]], [[../本地存储/文件存储]], [[../序列化原理/Parcelable与Serializable]]"
---

# MediaStore与数据共享

## 一句话定义

Android 的数据共享有三套标准机制：**`ContentProvider`**（对外暴露数据的标准组件，基于 URI + CRUD + 权限）、**`MediaStore`**（系统级媒体库，把图片/视频/音频统一收归 `content://media/...` 管理，Android 10 起进入 **scoped storage（分区存储）**、Android 13 起拆分**分区媒体权限**）、**`FileProvider`**（把应用私有文件通过 `content://` 安全地分享给其他应用）。三者构成了"**私有数据 → 安全暴露 → 跨应用流转**"的完整链路。

## 核心原理

### 1. ContentProvider 机制：跨进程数据暴露的标准四组件

```text
   App A（数据提供方）                        App B（数据使用方）
   ─────────────────                          ─────────────────
   MyProvider extends ContentProvider
        │
        ├─ onCreate()            初始化（拿 Context、打开数据库）
        ├─ query(uri, ...)       → Cursor    ← 返回结果集（跨进程）
        ├─ insert(uri, values)   → Uri
        ├─ update(uri, ...)      → Int
        ├─ delete(uri, ...)      → Int
        ├─ openFile(uri, mode)   → ParcelFileDescriptor
        ├─ getType(uri)          → String（MIME）
        ├─ call(method, arg, extras) → Bundle   ← API 29+ 通用调用
        │
        ▼
   ContentResolver.query(uri, ...)  ──── Binder ────▶  Provider 进程
                                                        │
   ★ 跨进程：Provider 的方法在 Provider 所在进程执行，
     返回的 Cursor 由 Binder 传输（数据量大时用 BulkCursor 优化）
```

**URI 的构成**：

```text
   content://authority/path/id

   authority：通常是 <包名>.<类名> 或 <包名>.<provider后缀>
              例：com.example.myapp.provider / com.example.myapp.fileprovider
   path：     资源类型，例：user/、media/
   id：       具体的资源 ID，也可能是路径
```

**URI 匹配（UriMatcher）**：把 URI 拆成 (authority, path, id) 三元组，映射到具体的处理分支。

```java
public class MyProvider extends ContentProvider {

    // 1. 声明 authority（必须在 manifest 中一致）
    public static final String AUTHORITY = "com.example.myapp.provider";

    // 2. 定义 URI 匹配规则
    static {
        URI_MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
        URI_MATCHER.addURI(AUTHORITY, "users", USERS);
        URI_MATCHER.addURI(AUTHORITY, "users/#", USER_ID);
    }

    @Override
    public boolean onCreate() {
        // 初始化：拿到 Context，打开数据库（Room / SQLite）
        db = AppDatabase.get(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        int match = URI_MATCHER.match(uri);
        switch (match) {
            case USERS:
                return db.userDao().queryAll();      // 返回 Cursor，自动跨进程传输
            case USER_ID:
                String id = uri.getLastPathSegment();
                return db.userDao().queryById(Long.parseLong(id));
            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }
    }

    @Override
    public String getType(Uri uri) {
        switch (URI_MATCHER.match(uri)) {
            case USERS:  return "vnd.android.cursor.dir/vnd.example.user";
            case USER_ID: return "vnd.android.cursor.item/vnd.example.user";
            default: throw new IllegalArgumentException();
        }
    }
}
```

```xml
<!-- AndroidManifest.xml -->
<provider
    android:name=".provider.MyProvider"
    android:authorities="com.example.myapp.provider"
    android:exported="true"
    android:grantUriPermissions="true"
    android:readPermission="com.example.myapp.permission.READ_USER"
    android:writePermission="com.example.myapp.permission.WRITE_USER" />
```

**跨进程数据的三种传递方式对比**：

| 方式 | 数据形态 | 优点 | 限制 |
| --- | --- | --- | --- |
| **Cursor** | 结果集 | 支持分页、惰性、排序 | Binder payload 限制（大量数据需 `ParcelFileDescriptor`） |
| **ParcelFileDescriptor** | 文件句柄 | **无大小限制**，流式读取 | 需要自己实现协议（protobuf/自定义） |
| **`call()` 返回 Bundle** | 键值对 | 灵活 | Bundle 1MB 限制 |

> 面试点：**ContentProvider 的 `query` 返回 Cursor，但数据是"跨进程传输"的**。Room 返回的 Cursor 会被 `ContentProvider` 包装成 `CursorWindow` 分块传输 → 这就是"查询 10 万条数据"会卡死的原因。

### 2. MediaStore 的结构

MediaStore 是 Android 系统内置的 `ContentProvider`，它把**所有用户可见的媒体文件**（图片、视频、音频、下载）统一到一个索引库里。

```text
   content://media/
   ├─ external/                 ← 共享存储卷（primary / sdcard / USB）
   │   ├─ images/media          ← 图片
   │   │   ├─ 1                ← id=1 的图片
   │   │   └─ ...
   │   ├─ video/media           ← 视频
   │   ├─ audio/media           ← 音频
   │   └─ downloads             ← 下载
   ├─ internal/                 ← 内部卷（模拟器上用）
   │   ├─ images/media
   │   └─ ...
   └─ (还有 file 表，Android 10+ 已废弃使用)

   常用 URI：
   content://media/external/images/media       → 全部图片
   content://media/external/images/media/42    → id 为 42 的图片
   content://media/external/video/media
   content://media/external/audio/media
```

**MediaStore 的表结构（关键列）**：

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `_id` | INTEGER | 主键，URI 的最后一段 |
| `display_name` | TEXT | 显示名（可含扩展名） |
| `mime_type` | TEXT | MIME 类型，如 `image/jpeg` |
| `width` / `height` | INTEGER | 像素尺寸（Android 16 起部分废弃，用 `Orientation`） |
| `size` | INTEGER | 文件字节数 |
| `date_added` / `date_modified` | INTEGER | 加入/修改时间（**秒**，Unix 时间戳） |
| `relative_path` | TEXT | **Android 10+**：相对路径，如 `Pictures/Screenshots` |
| `is_pending` | INTEGER | **Android 10+**：1 表示文件还在写入中（未完成） |
| `orientation` | INTEGER | EXIF 旋转角 |
| `bucket_id` / `bucket_display_name` | INTEGER/TEXT | 所属相册（Android 10+ 之前叫 `bucket_display_name`） |

### 3. scoped storage（分区存储）：Android 10 的核心变化

```text
   Android 9 及之前（传统模型）
   ┌──────────────────────────────────────────────┐
   │  App 私有目录：/data/data/<pkg>/  ← 只有自己能读 │
   │  外部存储：/sdcard/                            │
   │    ├─ 任意读写（需要 WRITE_EXTERNAL_STORAGE）    │
   │    └─ 别的 App 也能读（READ_EXTERNAL_STORAGE）  │
   │  ⚠️ 任何 App 拿到了文件路径就能读你的相册        │
   └──────────────────────────────────────────────┘

   Android 10+（scoped storage 分区存储）
   ┌──────────────────────────────────────────────┐
   │  App 私有目录：/data/data/<pkg>/                │
   │    ├─ files/  ← 自己读写                       │
   │    ├─ cache/  ← 自己读写                       │
   │    └─ 只能通过 FileProvider 授权给别的 App       │
   │                                               │
   │  外部存储（共享集合）：                        │
   │    ├─ 图片 → 只能通过 MediaStore 写入           │
   │    ├─ 视频 → 同上                              │
   │    ├─ 音频 → 同上                              │
   │    └─ 其他文档 → 通过 SAF (ACTION_OPEN_DOCUMENT) │
   │                                               │
   │  ⚠️ 直接用 File 读写别人的媒体文件 → FileNotFoundException│
   └──────────────────────────────────────────────┘
```

**分区存储下的三个基本规则**：

1. **媒体文件（图片/视频/音频）**：读写**必须走 MediaStore**，直接 `File("/sdcard/DCIM/xxx.jpg")` 会失败。
2. **非媒体文件**：用 **SAF（Storage Access Framework）** 的 `ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT` 拿 URI。
3. **应用私有目录**：不受分区存储限制，随便读写。

### 4. Android 13+ 的分区媒体权限

Android 13（API 33）把单一的 `READ_EXTERNAL_STORAGE` 拆成了**按媒体类型的分区权限**：

| 权限 | 覆盖范围 | 说明 |
| --- | --- | --- |
| `READ_MEDIA_IMAGES` | 图片 | 读图片 |
| `READ_MEDIA_VIDEO` | 视频 | 读视频 |
| `READ_MEDIA_AUDIO` | 音频 | 读音频（不含 `MediaStore.Audio` 之外的部分） |
| `READ_MEDIA_VISUAL_USER_SELECTED` | 用户选中的部分 | **API 34 新增**：用户在"选择照片"里只授权部分照片时的权限 |
| `READ_EXTERNAL_STORAGE` | 全部媒体 | **API 33 已废弃**，但 **API ≤32 仍需要** |
| `WRITE_EXTERNAL_STORAGE` | 写 | **API 29 起基本无效**（分区存储下 MediaStore 写入不需要它） |

**权限申请流程（API 33+）**：

```text
   需要读取图片
        │
        ▼
   checkSelfPermission(READ_MEDIA_IMAGES)
        │
   ┌────┴────┐
  已授权    未授权
   │          │
   ▼          ▼
  直接用   requestPermissions(...)
   │          │
   │      ┌───┴────┐
   │    全部授权  部分授权（API 34）
   │      │          │
   │      ▼          ▼
   │    用全部    只能用被选中的那部分
   │              → 需要处理 READ_MEDIA_VISUAL_USER_SELECTED
   │
   ▼
  查询 MediaStore
```

> ⚠️ **Android 14（API 34）的重要变化**：当 App 请求 `READ_MEDIA_IMAGES` 时，系统会弹出**"选择照片"的选择器**（类似 iOS 的有限访问）。如果用户只选了部分，App 只能访问被选中的照片，**需要额外申请 `READ_MEDIA_VISUAL_USER_SELECTED`** 才能知道"还有部分被选中"。
>
> ⚠️ **Android 15（API 35）**：强制要求声明 `READ_MEDIA_VISUAL_USER_SELECTED` 才能使用有限访问模式。

### 5. MediaStore 读写实战

#### 5.1 查询图片

```kotlin
object MediaHelper {

    /**
     * 查询所有图片（返回 Uri 列表）
     */
    fun queryImages(context: Context): List<Uri> {
        val uris = mutableListOf<Uri>()

        // 查询列：只取需要的列，减少 Binder 传输量
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        // content://media/external/images/media
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        context.contentResolver.query(
            collection,                       // 查询哪个集合
            projection,                       // 查哪些列
            null,                             // selection
            null,                             // selectionArgs
            MediaStore.Images.Media.DATE_MODIFIED + " DESC"  // 排序
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val mime = cursor.getString(mimeCol)
                // ⭐ 关键：用 ContentUris.withAppendedId 拼出 content URI
                val uri = ContentUris.withAppendedId(collection, id)
                uris += uri
            }
        }
        return uris
    }

    /**
     * 按相册（bucket）过滤：Android 10+ 用 RELATIVE_PATH，10 以下用 BUCKET_DISPLAY_NAME
     */
    fun queryImagesInAlbum(context: Context, albumName: String): List<Uri> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val selection: String
        val selectionArgs: Array<String>

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+：relative_path 形如 "Pictures/Screenshots/"
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            selectionArgs = arrayOf("$albumName%")
        } else {
            selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
            selectionArgs = arrayOf(albumName)
        }
        // ... 同上 query 逻辑
        return emptyList()
    }
}
```

#### 5.2 保存图片到相册（分区存储的标准写法）

```kotlin
/**
 * Android 10+ 推荐写法：MediaStore + RELATIVE_PATH + IS_PENDING
 */
fun saveImageToGallery(context: Context, bitmap: Bitmap, albumName: String = "MyApp"): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // ① 指定相对路径（分区存储必需）
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$albumName")
            // ② 标记为 pending：此时文件对其他 App 不可见
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    // ③ 插入到 MediaStore（EXTERNAL_CONTENT_URI = 共享存储卷）
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

    // ④ 写入实际数据
    resolver.openOutputStream(uri)?.use { outputStream ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
    } ?: return null

    // ⑤ 取消 pending → 文件正式对其他 App 可见
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val done = ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }
        resolver.update(uri, done, null, null)
    }
    return uri
}

/**
 * 旧版本（Android 9 及以下）的写法：直接写文件 + MediaScanner
 */
fun saveImageLegacy(context: Context, bitmap: Bitmap): Uri? {
    @Suppress("DEPRECATION")
    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    // 通知媒体库扫描这个新文件
    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
    return Uri.fromFile(file)
}
```

> **为什么需要 `IS_PENDING`**：写入大文件需要时间。如果不标记 pending，其他 App（相册）可能在文件写了一半时就去扫描它 → **读到半张图**。`IS_PENDING=1` 时 MediaStore 会"藏起"这个条目，写完再置 0 公开。

#### 5.3 Android 10+ 用 MediaStore 读文件内容

```kotlin
/**
 * 分区存储下，不能用 File 直接读别人的媒体文件，必须通过 ContentResolver
 */
fun readMediaBytes(context: Context, uri: Uri): ByteArray? {
    // 用 ContentResolver.openInputStream（本质是 openFileDescriptor + ParcelFileDescriptor）
    return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
}

/**
 * 更推荐：直接拿文件描述符，交给图片库（Glide / Coil）解码
 */
fun openFd(context: Context, uri: Uri) =
    context.contentResolver.openFileDescriptor(uri, "r")
```

### 6. FileProvider：共享私有文件

FileProvider 是 ContentProvider 的一个**子类**，专门用来把**应用私有目录里的文件**通过 `content://` URI 安全分享出去。

```text
   分享方 App                              接收方 App
   ──────────                              ──────────
   /data/data/<pkg>/files/report.pdf
        │
        │ FileProvider.getUriForFile()
        ▼
   content://com.example.myapp.fileprovider/my_files/report.pdf
        │                    ▲
        │                    └── 路径映射来自 file_paths.xml
        ▼
   Intent + FLAG_GRANT_READ_URI_PERMISSION
        │
        └── Binder ──▶ 接收方拿到 URI → ContentResolver.openInputStream()
                        ★ 系统校验：接收方必须被授予该 URI 的临时权限
```

**file_paths.xml 配置**：

```xml
<!-- res/xml/file_paths.xml -->
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <!-- 内部存储：filesDir 映射为 "files/" -->
    <files-path name="my_files" path="." />

    <!-- 内部存储：cacheDir 映射为 "cache/" -->
    <cache-path name="my_cache" path="." />

    <!-- 外部存储：只有特定子目录可映射（分区存储下受限） -->
    <external-path name="my_external" path="." />

    <!-- 外部存储的公共目录（Android 10+ 强烈不推荐） -->
    <external-files-path name="my_external_files" path="." />

    <!-- 根目录（危险，尽量避免） -->
    <!-- <root-path name="root" path="/" /> -->
</paths>
```

**Manifest 声明**：

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

> ⚠️ **多个 FileProvider 的 authority 必须唯一**（用 `${applicationId}.fileprovider`、`.share`、`_downloads` 后缀区分）。Google Play 的常见崩溃：`InstalledProviderInfo ... has no meta-data ... provider`。

### 7. 分享文件：ACTION_SEND + FileProvider

```kotlin
/**
 * 分享一个私有文件给其他 App
 */
fun shareFile(context: Context, file: File, mimeType: String = "application/pdf") {
    // ① 通过 FileProvider 把 File 转成 content:// URI
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    // ② 构造 Intent
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        // ③ 关键：授予临时读权限
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ④ 可选：指定分享目标（如只发给微信）——用 PackageManager.queryIntentActivities
    }

    // ⑤ 启动
    context.startActivity(Intent.createChooser(intent, "分享"))
}

/**
 * 分享文本 + 文件（多文件用 EXTRA_STREAM 的 ArrayList）
 */
fun shareFiles(context: Context, files: List<File>) {
    val uris = ArrayList(files.map { file ->
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    })
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享"))
}
```

**接收方怎么拿权限**：接收方在 `onCreate` 里通过 `Intent.FLAG_GRANT_READ_URI_PERMISSION` 自动获得**该 Intent 生命周期内**的 URI 读权限。

> ⚠️ **常见坑**：
> 1. **用 `Uri.fromFile(file)` 分享 → `FileUriExposedException`**（Android 7+ 禁止跨应用传 `file://` URI）→ 必须用 FileProvider。
> 2. **忘记 `addFlags(FLAG_GRANT_READ_URI_PERMISSION)`** → 接收方 `openInputStream` 抛 `SecurityException`。
> 3. **接收方在 `onCreate` 之后（如异步加载）才读 URI** → 权限可能已失效 → 应该在 `onCreate` 同步读取或用 `takePersistableUriPermission`（SAF 场景）。
> 4. **用 `Intent.createChooser`** → 会丢失部分 flags？实际上 `createChooser` 会保留 `FLAG_GRANT_READ_URI_PERMISSION`（Android 5.1+ 修复过），但**自定义 target package 时要注意**。

### 8. Deep Link / App Link 与剪贴板

#### 8.1 Deep Link / App Link

```text
   https://example.com/order/123
        │
        ▼
   App Links（Android 12+ 自动验证，https + 域名验证）
   或 Custom Scheme（myapp://order/123）
        │
        ▼
   AndroidManifest 的 intent-filter 匹配
        │
        ▼
   Activity/Service 的 onNewIntent / onCreate 接收
```

```xml
<!-- AndroidManifest.xml -->
<activity android:name=".ui.OrderActivity" android:exported="true">
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https" android:host="example.com" />
    </intent-filter>
</activity>
```

```kotlin
// 接收端解析
val intent = intent
val uri = intent.data   // https://example.com/order/123
val orderId = uri?.lastPathSegment?.toLongOrNull()

// 发起方
val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/order/123"))
startActivity(intent)
```

> ⚠️ **安全**：处理 Deep Link 时**必须校验参数**，否则可能被恶意构造的 URI 触发越权操作。

#### 8.2 Clipboard（剪贴板）

```kotlin
// 写入
val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
clipboard.setPrimaryClip(ClipData.newPlainText("label", "content"))

// 读取（Android 12+ 起，读取剪贴板只在 App 有焦点或默认输入法时允许）
val clip = clipboard.primaryClip
val text = clip?.getItemAt(0)?.coerceToText(context)?.toString()
```

> ⚠️ **Android 10+ 限制**：后台 App **不能读取剪贴板**（只有前台或默认输入法能读）。Android 13+ 剪贴板预览还带**敏感信息自动遮罩**。

### 9. 共享数据的完整选型

| 需求 | 方案 | 权限要求 |
| --- | --- | --- |
| **分享私有文件给别的 App** | `FileProvider` + `ACTION_SEND` | 无（用临时授权） |
| **让别的 App 读我的媒体库** | `MediaStore` + `READ_MEDIA_*` | 需要运行时权限 |
| **把自己的媒体写进系统相册** | `MediaStore.insert` | Android 10+ **无需权限** |
| **让别的 App 查我的结构化数据** | 自定义 `ContentProvider` | `readPermission` 或临时授权 |
| **读别的 App 的 SAF 文件** | `ACTION_OPEN_DOCUMENT` | 一次授权 + 可 `takePersistableUriPermission` |
| **系统分享面板分享文本/链接** | `ACTION_SEND`（无 URI） | 无 |
| **Deep Link 唤起** | `intent-filter` | 无（App Link 需要域名验证） |

## 代码示例

### 1. 自定义 ContentProvider（完整实现）

```kotlin
class UserProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.example.myapp.provider"
        private const val PATH_USERS = "users"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_USERS")
    }

    private lateinit var db: AppDatabase

    override fun onCreate(): Boolean {
        // 注意：getContext() 在 onCreate 里可用
        db = AppDatabase.get(requireNotNull(context))
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = when (uri.pathSegments.size) {
            1 -> db.userDao().queryAll()                       // content://.../users
            2 -> {
                val id = uri.lastPathSegment!!.toLong()
                db.userDao().queryById(id)                     // content://.../users/1
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        // ⭐ 必须设置通知 URI，ContentResolver 才会收到 notifyChange
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    override fun getType(uri: Uri): String = when (uri.pathSegments.size) {
        1 -> "vnd.android.cursor.dir/vnd.example.user"
        else -> "vnd.android.cursor.item/vnd.example.user"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val user = User(
            id = values?.getAsLong("id") ?: 0L,
            email = values?.getAsString("email") ?: "",
            name = values?.getAsString("name") ?: ""
        )
        val id = db.userDao().insert(user)
        val newUri = ContentUris.withAppendedId(CONTENT_URI, id)
        requireNotNull(context).contentResolver.notifyChange(newUri, null)
        return newUri
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val id = uri.lastPathSegment?.toLong() ?: return 0
        val count = db.userDao().deleteById(id)
        requireNotNull(context).contentResolver.notifyChange(uri, null)
        return count
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("Not supported")
    }
}
```

```kotlin
// 客户端使用
val resolver = context.contentResolver
resolver.query(UserProvider.CONTENT_URI, arrayOf("_id", "name"), null, null, null)?.use { c ->
    while (c.moveToNext()) { /* ... */ }
}
```

### 2. MediaStore 完整工具类

```kotlin
object MediaStoreHelper {

    /** 权限检查：Android 13+ 按类型，13 以下用 READ_EXTERNAL_STORAGE */
    fun hasPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** 查询图片，返回带 id/名字/尺寸的模型 */
    data class MediaItem(
        val id: Long,
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val relativePath: String?,
        val dateModified: Long
    )

    fun queryImages(context: Context, limit: Int? = null): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(MediaStore.Images.Media.RELATIVE_PATH)
        } else emptyArray()

        context.contentResolver.query(
            collection, projection, null, null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            } else -1

            while (cursor.moveToNext() && (limit == null || result.size < limit)) {
                val id = cursor.getLong(idCol)
                result += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = cursor.getString(nameCol) ?: "",
                    mimeType = cursor.getString(mimeCol) ?: "image/*",
                    size = cursor.getLong(sizeCol),
                    relativePath = if (pathCol >= 0) cursor.getString(pathCol) else null,
                    dateModified = cursor.getLong(dateCol)
                )
            }
        }
        return result
    }

    /** 保存图片（Android 10+ 用 IS_PENDING 保证原子可见） */
    fun saveImage(context: Context, bytes: ByteArray, displayName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MyApp")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
        }
        return uri
    }

    /** 删除自己创建的文件（Android 10+ 不需要权限） */
    fun deleteImage(context: Context, uri: Uri): Boolean =
        context.contentResolver.delete(uri, null, null) > 0
}
```

### 3. FileProvider 分享（完整可用）

```kotlin
object ShareHelper {

    /** 分享私有文件：FileProvider + ACTION_SEND */
    fun shareFile(context: Context, file: File, mimeType: String = "application/octet-stream") {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享文件"))
    }

    /** 分享文本链接 */
    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "分享"))
    }

    /**
     * 分享给指定 App（如只分享到微信）
     * ⚠️ 目标 App 必须有 ACTION_SEND 的 intent-filter
     */
    fun shareToSpecificApp(context: Context, packageName: String, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(packageName)
        }
        context.startActivity(intent)
    }
}
```

### 4. Android 13+ 分区媒体权限申请

```kotlin
class GalleryPermissionHelper(private val activity: ComponentActivity) {

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onPermissionResult(true)
        } else {
            // Android 14+：可能是"部分授权"
            onPartialPermission()
        }
    }

    fun ensureImagePermission(onResult: (Boolean) -> Unit) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
        } else {
            requestPermission.launch(permission)
        }
    }
}
```

### 5. SAF：读写非媒体文件

```kotlin
// 打开文档（用户选一个文件）
private val openDocument = registerForActivityResult(
    ActivityResultContracts.OpenDocument()
) { uri: Uri? ->
    uri ?: return@registerForActivityResult
    // ⭐ 关键：持久化权限，否则进程被杀后权限丢失
    contentResolver.takePersistableUriPermission(
        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
    )
    // 通过 URI 读取
    contentResolver.openInputStream(uri)?.use { it.readBytes() }
}

// 调用（传 mimeType 过滤）
openDocument.launch(arrayOf("application/pdf", "text/plain"))
```

## 常见问题 / 面试要点

**读者问：Android 10 之后怎么访问用户的相册图片？为什么不能直接用文件路径？**

**AI答：**

1. **根本原因：scoped storage（分区存储）**。Android 10（API 29）起，应用**不再拥有整个外部存储的读写权限**，而是：
   - **私有目录**（`/data/data/<pkg>/`）：完全自由。
   - **共享媒体集合**（图片/视频/音频）：**必须通过 MediaStore**。
   - **其他文档**：通过 **SAF**。
2. **为什么文件路径不行**：
   - Android 10+ 上，`File("/sdcard/DCIM/Camera/xxx.jpg").readBytes()` → **`FileNotFoundException` / `Permission denied`**。
   - 因为 `/sdcard` 被挂载为 **FUSE 层**，它会根据**调用方的 UID** 决定能不能访问 → 应用对**其他应用创建的媒体文件**没有直接访问权。
3. **Android 10+ 的正确做法**：

   ```kotlin
   // ✅ 方式 1：MediaStore 查询（需要 READ_MEDIA_* 权限）
   context.contentResolver.query(
       MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
       arrayOf(MediaStore.Images.Media._ID),
       null, null, "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
   )

   // ✅ 方式 2：MediaStore 插入（写，不需要任何权限）
   context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

   // ✅ 方式 3：SAF 打开文档（读非媒体文件）
   startActivityForResult(Intent.ACTION_OPEN_DOCUMENT, ...)
   ```
4. **MediaStore 查询也不总是需要权限**：
   - 查询**自己创建**的媒体文件（`IS_PENDING` 处理过的、owner 是自己）→ **不需要权限**。
   - 查询**别的 App 创建**的 → 需要 `READ_MEDIA_IMAGES`（API 33+）或 `READ_EXTERNAL_STORAGE`（≤32）。
5. **版本差异表**：

   | API | 权限 | 写媒体 | 读媒体 |
   | --- | --- | --- | --- |
   | ≤ 28 | `WRITE_EXTERNAL_STORAGE` + `READ_EXTERNAL_STORAGE` | 直接写文件 | 直接读文件 |
   | 29~32 | `READ_EXTERNAL_STORAGE`（写权限基本失效） | **MediaStore** | **MediaStore** |
   | 33 | `READ_MEDIA_IMAGES/VIDEO/AUDIO` | MediaStore | MediaStore |
   | 34+ | 上面的 + `READ_MEDIA_VISUAL_USER_SELECTED`（部分授权） | MediaStore | MediaStore |

6. **面试话术**：
   > "Android 10 引入 scoped storage 后，应用对共享存储只有**受限访问**。写媒体必须走 **MediaStore**（且不需要权限），读别人的媒体要走 MediaStore 查询并申请 `READ_MEDIA_*` 权限，Android 14 还支持**用户只授权部分照片**。读非媒体文件用 **SAF**。直接拿文件路径读会抛 `FileNotFoundException`，因为 `/sdcard` 的 FUSE 层按 UID 做隔离。"

---

**读者问：MediaStore 和直接用文件路径有什么区别？为什么系统要用 MediaStore？**

**AI答：**

1. **本质区别**：

   | 维度 | 文件路径（`/sdcard/...`） | MediaStore（`content://media/...`） |
   | --- | --- | --- |
   | **本质** | **文件系统的路径** | **系统媒体库的索引数据库** |
   | **是否有元数据** | ❌ 只有文件本身 | ✅ 尺寸、拍摄时间、EXIF 旋转、所属相册 |
   | **权限模型** | 文件系统权限（FUSE 按 UID） | **ContentProvider 的 URI 权限模型**（可精细授权单个文件） |
   | **索引/搜索** | ❌ 要自己遍历目录 | ✅ 系统已索引，查询极快 |
   | **多用户隔离** | 需自己处理 | ✅ 系统处理 |
   | **云备份同步** | ❌ 不参与 | ✅ Google Photos 可同步 |
   | **是否真实文件** | 是 | **不一定**（可能是云端占位符） |

2. **系统为什么用 MediaStore**：
   - **索引需求**：相册 App 需要"按拍摄时间、按相册、按地理位置"查询照片 → 这些信息在文件本身里没有（EXIF 可能有拍摄时间，但没有"相册"概念）。
   - **权限需求**：需要"**只授权某一张照片**"的粒度 → 文件路径做不到，URI 可以。
   - **多源需求**：照片可能来自相机、下载、微信、云端 → 统一索引。
   - **状态管理**：MediaStore 记录 `IS_PENDING`、`ORIENTATION`、`IS_TRASHGED`（回收站）等生命周期状态。
3. **一个重要的现代变化——云端媒体**：
   - Android 11+（R）引入 **`MediaStore.getCloudUri`** / **`MediaStore.setRequireOriginal`**（API 30+）。
   - 用户开启"云备份"后，媒体库里可能有**云端占位文件**：只有 URI，**没有本地字节**。
   - 此时 `openInputStream(uri)` 会**自动触发下载**（或抛 `RecoverableSecurityException` 需要用户确认）。
   - ⚠️ 这是"图片加载失败"类线上问题的常见原因之一 → 用 Glide/Coil 时要处理这种情况。
4. **面试话术**：
   > "MediaStore 本质是**系统媒体库的索引表**，不是一个文件系统。它的价值在于**元数据索引**（拍摄时间、相册、EXIF）、**基于 URI 的细粒度授权**（可以只授权一张照片）、以及**统一多来源**（相机/下载/云端）。另外要注意 Android 11+ 的云端媒体：URI 存在但本地没字节，读取会触发下载，这是线上图片加载失败的常见原因。"

---

**读者问：FileProvider 是什么？为什么要用它而不是直接传文件路径？**

**AI答：**

1. **FileProvider 是什么**：ContentProvider 的子类，作用是**把私有目录的文件映射成一个 `content://` URI**，并通过 **URI 权限授权（临时 grant）** 让其他 App 访问。
2. **为什么不能直接传路径**：
   - **安全**：私有目录的路径对别的 App 无意义（它们读不到），且暴露路径等于**泄露内部结构**。
   - **Android 7+（N）起禁止 `file://` URI 跨应用传递** → 直接 `Uri.fromFile(file)` 启动分享会抛：
     ```
     java.lang.IllegalArgumentException: Failed to find configured root at ...
     android.os.FileUriExposedException: exposed URI beyond app through Intent.getParcelableExtra()
     ```
     这是 **StrictMode** 检出的。
   - **权限不可控**：传路径 = 授予"整个目录"的潜在访问；传 URI = **精确到单个文件的临时授权**。
3. **FileProvider 的完整配置**：
   ```xml
   <!-- res/xml/file_paths.xml -->
   <paths>
       <files-path name="files" path="." />          <!-- context.filesDir -->
       <cache-path name="cache" path="." />           <!-- context.cacheDir -->
       <external-files-path name="ext_files" path="." />
       <external-cache-path name="ext_cache" path="." />
       <!-- <external-path name="ext" path="." />      <!-- 分区存储下不推荐 -->
       <!-- <root-path name="root" path="/" />          <!-- 危险，避免 -->
   </paths>
   ```
   ```xml
   <provider
       android:name="androidx.core.content.FileProvider"
       android:authorities="${applicationId}.fileprovider"
       android:exported="false"
       android:grantUriPermissions="true">
       <meta-data
           android:name="android.support.FILE_PROVIDER_PATHS"
           android:resource="@xml/file_paths" />
   </provider>
   ```
4. **路径映射规则**：`content://authority/<name>/<相对路径>`
   - `files-path name="files" path="."` → `content://pkg.fileprovider/files/report.pdf` 对应 `context.filesDir/report.pdf`。
5. **四个常见坑**：

   | 坑 | 现象 | 解决 |
   | --- | --- | --- |
   | 忘记 `addFlags(FLAG_GRANT_READ_URI_PERMISSION)` | 接收方 `SecurityException` | Intent 加 flag |
   | 多 provider authority 冲突 | 安装时 `INSTALL_FAILED_CONFLICTING_PROVIDER` | authority 加后缀区分 |
   | `file_paths.xml` 路径不覆盖目标文件 | `IllegalArgumentException: Failed to find configured root` | 检查 `<files-path>` 的 `path` |
   | 目标文件在 `external-path` 根目录 | 分区存储下不可访问 | 改用 `external-files-path` 或先复制到私有目录 |

6. **面试话术**：
   > "FileProvider 是把**私有文件安全暴露**的标准方案。核心价值是三点：一是 Android 7+ 禁止跨应用传 `file://` URI；二是它实现了**基于 URI 的细粒度临时授权**，只放行这一个文件；三是接收方拿到的是不透明 URI，拿不到你的内部目录结构。工程上最大的坑是**忘记加 `FLAG_GRANT_READ_URI_PERMISSION`**，以及多 provider 的 authority 冲突。"

---

**读者问：Android 13+ 的分区媒体权限有什么变化？为什么这么设计？**

**AI答：**

1. **变化**：Android 13（API 33）把 `READ_EXTERNAL_STORAGE` **拆分**为三个：
   - `READ_MEDIA_IMAGES`
   - `READ_MEDIA_VIDEO`
   - `READ_MEDIA_AUDIO`

   `READ_EXTERNAL_STORAGE` **被废弃**（在 API 33 上不再授予任何东西），但 **API ≤ 32 的设备仍需申请它** → 所以要做**版本分支**。
2. **为什么这么设计**：
   - **最小权限原则**：一个只做壁纸的 App 不需要读视频的权限。
   - **用户可感知**：弹窗文案从"读取照片和媒体"变成"读取你的照片"，**用户知道 App 要干什么** → 授权率更高。
   - **可单独撤销**：用户可以在设置里只撤销图片权限，保留视频。
3. **Android 14（API 34）的进一步变化——部分访问**：
   - 申请 `READ_MEDIA_IMAGES` 时，系统弹出**照片选择器**，用户可以**只选一部分照片**。
   - App 只能访问被选中的照片。
   - 需要额外声明 `READ_MEDIA_VISUAL_USER_SELECTED` 才能**检测到"用户选了部分"**这个状态。
   - Android 15（API 35）**强制要求**声明该权限才能启用部分访问。
4. **部分访问下的正确逻辑**：

   ```kotlin
   val canReadFull = checkSelfPermission(READ_MEDIA_IMAGES) == GRANTED
   val canReadPartial = checkSelfPermission(READ_MEDIA_VISUAL_USER_SELECTED) == GRANTED
   // canReadFull  → 用完整的选择器 UI
   // canReadPartial → 只能显示被选中的照片；再次请求会走选择器
   // 都没有       → 引导用户去设置
   ```
5. **常见错误**：
   - ❌ 只申请 `READ_EXTERNAL_STORAGE`（在 API 33+ 上**拿不到任何权限**，而且 `requestPermissions` 会直接返回 denied）。
   - ❌ 硬编码一个权限字符串，**不做版本分支** → 必须在 `Build.VERSION.SDK_INT` 上分支，或用 `PermissionChecker`。
   - ❌ 在**部分访问**时仍然展示"全部照片"的 UI（会显示空白/缺失）→ 需要向用户解释"部分照片不可见"。
6. **面试话术**：
   > "Android 13 把单一的存储权限拆成了**按媒体类型的分区权限**，这是最小权限原则的落地，也符合 GDPR 那套'按数据类型授权'的思路。Android 14 又进一步支持**部分照片访问**——用户只授权一部分，App 需要额外申请 `READ_MEDIA_VISUAL_USER_SELECTED` 才能感知这个状态。所以我们的权限封装要处理**四态**：全量、部分、拒绝、永久拒绝。"

---

**读者问：ContentProvider 的底层原理是什么？为什么跨进程能查到数据？**

**AI答：**

1. **跨进程调用链**：

   ```text
   App B: contentResolver.query(uri, ...)
        │
        │ ContentResolver → acquireProvider(uri) → 通过 PackageManager 查 authority
        │ → 找到 Provider 的 ComponentName → 启动/绑定 Provider 所在进程
        ▼
   Binder.transact(QUERY, arg, null, reply)
        │
        ▼
   system_server: ActivityManagerService 把请求路由到目标进程
        │  （若 Provider 进程未启动，会先启动它）
        ▼
   目标进程: ProviderTransport.dispatch → ContentProvider.transport.call(...)
        │  ★ Provider 的 query() 在【Provider 所在进程】执行
        ▼
   目标进程执行 Room 查询 → 得到 Cursor
        │  ★ Cursor 的数据需要跨进程传回！
        ▼
   Binder 事务返回 Cursor 句柄 → App B 通过它读数据
        │
        ▼
   App B: cursor.moveToNext() → 通过 Binder 逐块取数据（CursorWindow）
   ```

2. **Cursor 跨进程的关键机制**：
   - Room 返回的 `Cursor` 在 Provider 进程里执行查询，**但数据要传回调用方**。
   - Android 用 **`CursorWindow`**（一块共享内存）承载结果集，通过 **Binder FD 传递** → **一次性把大块数据放进共享内存**。
   - 如果结果集太大（超过 CursorWindow 限制，通常 2MB 左右），会**分块（chunk）传输** → `CursorWindow` 用多个 FD。
   - ⚠️ **这就是"查询 10 万条数据卡死"的原因**：数据要跨进程 + 分块传输。
3. **ContentProvider 的三种数据返回方式**：

   | 方式 | 机制 | 适用 |
   | --- | --- | --- |
   | **Cursor** | CursorWindow（共享内存）分块 | **查询、可排序、可分页** |
   | **`openFile()` → ParcelFileDescriptor** | **FD 直接传递，零数据拷贝** | **大文件、二进制流** |
   | **`call()` → Bundle** | Bundle 走 Binder payload | 简单键值对（受 1MB 限制） |

4. **ContentProvider 的其他关键机制**：
   - **`notifyChange(uri)`** → 通知 ContentResolver "数据变了" → 注册了 `ContentObserver` 的地方会收到回调 → 这就是"图片列表自动刷新"的原理。
   - **`setNotificationUri(cursor, uri)`** → Cursor 自带通知 URI，ContentResolver 在 cursor 关闭时会自动解绑。
   - **`ContentResolver.registerContentObserver`** → 跨进程观察数据变化。
   - **URI 权限**：`<grant-uri-permission>` + `FLAG_GRANT_READ_URI_PERMISSION` → **临时授权**，不写 permission 也能精确授权单个 URI。
5. **面试话术**：
   > "ContentProvider 的跨进程能力来自 **Binder**。Provider 的 `query()` 在**它自己所在的进程**执行，返回的 Cursor 通过 `CursorWindow`（共享内存 + FD）分块传回调用方——所以查大量数据会卡。如果数据很大又不适合查表，正确做法是实现 `openFile()` 返回 `ParcelFileDescriptor`，接收方用 `openFileDescriptor` 拿 FD 后流式读，**完全不过 Binder payload**。"

---

**读者问：怎么把文件分享给微信？用 `file://` 报错 `FileUriExposedException` 怎么办？**

**AI答：**

1. **报错原因**：Android 7.0（API 24）起，**禁止跨应用传递 `file://` URI**（StrictMode 的 `FileUriExposure` 检测）——因为这会暴露 App 的内部文件路径，且绕过了权限控制。
2. **正确做法：FileProvider**：

   ```kotlin
   val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
   val intent = Intent(Intent.ACTION_SEND).apply {
       type = "image/jpeg"
       putExtra(Intent.EXTRA_STREAM, uri)
       addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
       setPackage("com.tencent.mm")     // 定向到微信
   }
   context.startActivity(intent)
   ```
3. **定向分享的注意点**：
   - `setPackage` 只能定向到**包名**，不能定向到某个 Activity（除非用 `setComponent`，但那需要目标 App 导出对应 Activity）。
   - 用 `setPackage` 前应该先 `packageManager.queryIntentActivities(intent, 0)` 判断目标是否存在 → 避免 `ActivityNotFoundException`。
   - 微信要求**必须传 `EXTRA_STREAM`**（纯文本分享到微信会话是支持的，但图片必须用 Stream）。
4. **微信的特殊要求**：
   - 分享图片时 URI 必须是**真实可读的**（`FileProvider` 的 URI 在微信进程里通过 `ContentResolver.openInputStream` 打开 → 需要临时读权限）。
   - **临时权限的生命周期 = Intent 的生命周期** → 接收方必须在 `onCreate` 之后**尽快**读取；如果接收方延迟很久才读，可能失败。稳妥做法是接收方在 `onCreate` 里同步取，或由接收方立刻 `takePersistableUriPermission`（需接收方是系统选择器场景）。
5. **微信不支持自定义 scheme 的 FileProvider 之外的 URI？** → 微信对 `content://` 的支持是标准的，但**部分定制 ROM 或旧版微信**对某些 `authority` 格式有问题 → 建议 authority 用 `${applicationId}.fileprovider` 这种常规格式。
6. **其他常见分享方式对比**：

   | 方式 | 代码量 | 可控性 | 推荐 |
   | --- | --- | --- | --- |
   | **FileProvider + ACTION_SEND** | 中 | 高 | ✅ **推荐** |
   | `ACTION_SEND` 直接传 `file://` | 低 | — | ❌ Android 7+ 崩溃 |
   | **微信官方 SDK（WXFileObject）** | 高 | 高 | 需要微信能力时 |
   | `MediaStore` 插入 + 分享 Uri | 中 | 中 | 分享相册里的图 |

7. **面试话术**：
   > "`FileUriExposedException` 是 Android 7+ 的安全策略：跨应用不能传 `file://` URI。正确做法是 FileProvider——把私有文件映射成 `content://` URI，再用 `FLAG_GRANT_READ_URI_PERMISSION` 授予**精确到这个文件**的临时读权限。这比传路径安全得多，因为接收方拿到的 URI 只能读这一个文件，拿不到你的目录结构。"

---

**读者问：ContentProvider 和 FileProvider 有什么区别？自定义 Provider 有什么坑？**

**AI答：**

1. **区别**：

   | 维度 | 自定义 ContentProvider | FileProvider |
   | --- | --- | --- |
   | **用途** | **对外暴露结构化数据**（可查询、可排序、可订阅） | **对外暴露私有文件**（流式读取） |
   | **数据模型** | 表格（Cursor） | 文件（ParcelFileDescriptor） |
   | **权限** | `readPermission` / `writePermission`，或 URI 临时授权 | **只能 URI 临时授权**（`exported=false`） |
   | **查询能力** | ✅ 支持 projection / selection / sort | ❌ 只有 openFile |
   | **数据变化通知** | ✅ `notifyChange` + `ContentObserver` | ❌ |
   | **典型场景** | 通讯录、跨 App 数据共享 | 分享文件、相机预览 |

2. **自定义 ContentProvider 的坑**：

   | 坑 | 现象 | 解决 |
   | --- | --- | --- |
   | **`exported` 配置** | Android 12+ 必须显式声明；不声明安装失败 | 显式写 `android:exported="true/false"` |
   | **authority 冲突** | `INSTALL_FAILED_CONFLICTING_PROVIDER` | 加包名前缀 |
   | **多进程同时初始化** | `onCreate` 里初始化 Room → 死锁风险 | `onCreate` 里**不要做重初始化**，用 lazy |
   | **`getType` 返回错** | 客户端 `ContentResolver.getType()` 判断错 | dir 用 `vnd.android.cursor.dir/`，item 用 `...item/` |
   | **忘了 `setNotificationUri`** | 客户端收不到 `notifyChange` | Cursor 上调用 |
   | **URI 权限没生效** | 接收方 `SecurityException` | `grantUriPermissions="true"` + `FLAG_GRANT_*` |
   | **在 `onCreate` 里用 `getContext()` 之前** | NPE | `onCreate` 之后用 `context` |
   | **大 Cursor** | 查询 10 万条卡死 | 分页 + `openFile` |

3. **ContentProvider 的初始化时机**：Provider 的 `onCreate` 在**第一次有组件访问它时**才执行（不是 App 启动时）→ 所以别在 `onCreate` 里做耗时初始化。
4. **多进程的 Provider**：如果 `android:process=":remote"` → Provider 在独立进程 → 初始化成本更高、跨进程调用更多 → 一般不需要。
5. **面试话术**：
   > "两者定位不同：**自定义 ContentProvider 是'暴露数据'（Cursor、可查询、可订阅变化），FileProvider 是'暴露文件'（流式读、只有 URI 授权）**。自定义 Provider 最容易踩的坑是 Android 12+ 强制要求显式声明 `exported`，以及忘了 `setNotificationUri` 导致客户端收不到数据变更通知。"

---

**读者问：Deep Link 和 App Link 有什么区别？Deep Link 有哪些安全风险？**

**AI答：**

1. **区别**：

   | 维度 | Deep Link（自定义 scheme） | App Link（Android 12+） |
   | --- | --- | --- |
   | **scheme** | 自定义，如 `myapp://` | **必须是 `https`** |
   | **验证** | ❌ 无（任何 App 都能注册同名 scheme） | ✅ **域名验证**（`assetlinks.json`） |
   | **冲突** | ⚠️ 多个 App 可抢同一 scheme → 弹选择框 | ✅ 系统直接跳转到已验证的 App |
   | **Web 兜底** | 无 | ✅ 未安装 App 时走浏览器 |
   | **要求** | 无 | 域名需能访问 `/.well-known/assetlinks.json`（HTTPS） |

2. **App Link 的验证文件**：
   ```json
   // https://example.com/.well-known/assetlinks.json
   [{
     "relation": ["delegate_permission/common.handle_all_urls"],
     "target": {
       "namespace": "android_app",
       "package_name": "com.example.myapp",
       "sha256_cert_fingerprints": ["AA:BB:CC:..."]
     }
   }]
   ```
   - `sha256_cert_fingerprints` 是**签名证书的 SHA-256 指纹**（不是 MD5）。
   - ⚠️ **必须包含所有签名变体**（debug keystore + release keystore），否则 debug 包无法验证。

3. **Deep Link 的安全风险**：
   - ⚠️ **未校验参数导致越权**：恶意 App 构造 `myapp://pay?amount=1&to=attacker` → 必须**校验来源与参数**。
   - ⚠️ **Intent 重定向（Intent Redirect）**：恶意 App 发送一个 `Intent` 里面嵌套你的 `Intent`（`EXTRA_INTENT`）→ 骗你的 App 转发敏感操作。**Android 12+ 禁止**未声明 `android:exported` 的组件接收嵌套 Intent。
   - ⚠️ **自定义 scheme 被抢注**：攻击者注册同名 scheme → 你的 Deep Link 被劫持 → **优先用 App Link**。
   - ⚠️ **Web 端注入**：如果 Deep Link 跳 WebView，必须校验域名，防"任意 URL 加载"。
4. **防护手段**：
   ```kotlin
   // ① 校验参数白名单
   val id = uri.lastPathSegment?.toLongOrNull() ?: return
   // ② 校验调用来源（可选但推荐）
   val callingPackage = callingActivity?.packageName
   // ③ 不要用 WebView 加载未校验的 URL
   // ④ 敏感操作要求二次确认
   ```
5. **面试话术**：
   > "App Link 是 Deep Link 的安全升级版：它强制 https 并通过**域名所有权验证**（assetlinks.json + 证书指纹），彻底解决了自定义 scheme 被恶意 App 抢注的问题。安全上我最关注两点：一是**参数必须校验**，防止构造 URI 触发越权操作；二是 **Intent 重定向**——Android 12+ 禁止未声明 exported 的组件处理嵌套 Intent，就是为了防这个。"

## 关联笔记

- [[../README]]：第 4 部分总览与数据共享主线
- [[../本地存储/文件存储]]：私有文件的存放位置与 FileProvider 的关系
- [[../本地存储/SharedPreferences]]：FileProvider 的路径映射与私有目录
- [[../数据库/SQLite与Room]]：ContentProvider 背后的数据源通常是 Room
- [[../序列化原理/Parcelable与Serializable]]：ContentProvider 跨进程传数据的序列化选择
- [[../数据格式/JSON与ProtoBuf]]：ContentProvider 返回的数据格式与 openFile 协议设计
- [[../../02-Android核心组件/README]]：ContentProvider 是四大组件之一，理解其生命周期
- [[../../06-系统原理与性能优化/README]]：Binder 通信与存储分区机制的底层原理

## 参考资料

- Android 官方 ContentProvider 指南：https://developer.android.com/guide/topics/providers/content-providers
- Android 官方 FileProvider 文档：https://developer.android.com/reference/androidx/core/content/FileProvider
- Android 官方 MediaStore 文档：https://developer.android.com/reference/android/provider/MediaStore
- Android 官方存储分区与权限（分媒体类型权限）：https://developer.android.com/training/data-storage/shared/media
- Android 官方 App Links 验证指南：https://developer.android.com/training/app-links/verify-android-applinks
