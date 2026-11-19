---
tags: [android]
part: 第2部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[../README]], [[Binder机制]], [[../四大组件/Activity]], [[AIDL]]"
---

# Intent

## 一句话定义

Intent 是 Android 中**组件之间传递"做什么"和"给谁做"的描述对象**。它既是**启动器**（启动 Activity、Service、发送广播），也是**数据载体**（通过 extras 携带参数）。它的本质是一个**系统级的"消息 + 寻址 + 路由"三元组**：系统读懂它，才知道该把这个消息交给哪个组件、怎么处理。

面试开场白：

> Intent 有两个身份：**"消息"**（extras 里带数据）和**"路由"**（action + data 决定谁接收）。面试里最核心的考点是**隐式 Intent 的匹配规则与冲突处理**、**Intent 的安全性（显式 Intent 泄露、PendingIntent 劫持）**、以及**PendingIntent 三个核心 Flag 的语义**。

## 核心原理

### 1. Intent 的组成结构

```text
        Intent
          │
          ├── Action        ── 要做什么（"android.intent.action.VIEW"）
          │                   命名约定：intent.action.PACKAGE  或  自定义
          │
          ├── Data (Uri)    ── 数据是什么（"content://...", "file://...", "tel:123")
          │                   scheme://host/path
          │
          ├── Category      ── 附加的种类（"android.intent.category.LAUNCHER"）
          │
          ├── Type          ── MIME 类型（"image/png"），常与 Data 配套
          │
          ├── Component      ── 精确目标（包名 + 类名），构成"显式 Intent"
          │
          ├── Package       ── 限定目标包（粗于 Component）
          │
          ├── Extras        ── 附加数据（Bundle）
          │
          └── Flags         ── 控制启动行为（FLAG_ACTIVITY_*、FLAG_GRANT_* 等）
```

**核心 API**：

```kotlin
val intent = Intent().apply {
    action = "com.example.app.ACTION_SYNC"
    data = Uri.parse("content://com.example.app.provider/items/1")
    type = "application/json"
    addCategory(Intent.CATEGORY_DEFAULT)
    putExtra("user_id", 1001L)
    putExtra("payload", bundleOf("key" to "value"))
    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
```

### 2. 显式 Intent vs 隐式 Intent

| 维度 | 显式 Intent（Explicit） | 隐式 Intent（Implicit） |
| --- | --- | --- |
| 目标 | **明确指定**组件（`setComponent` / `setPackage` / 构造函数带 Class） | **不指定**，由系统按 `intent-filter` 匹配 |
| 分发 | 系统**直接**投递给该组件 | 系统遍历所有注册了匹配 filter 的组件 |
| 权限 | 需要目标 `exported="true"` 才能被外部启动 | 目标必须 `exported="true"` 才能接收 |
| 安全性 | **高**（目标确定，无劫持风险） | **低**（可能被恶意 App 劫持） |
| 典型用法 | 内部跳转、显式 Service、显式广播 | 打开系统页面（浏览器、拨号、分享、拍照） |

```kotlin
// 显式：直接指定目标组件
val explicit = Intent(this, DetailActivity::class.java)
    .putExtra(DetailActivity.EXTRA_ID, 42)
startActivity(explicit)

// 隐式：只描述动作与数据
val implicit = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
startActivity(implicit)

// 隐式 + 限定包（提高安全性，避免劫持）
val safer = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
    .setPackage("com.android.chrome")
startActivity(safer)
```

**判定显式/隐式的代码**：

```kotlin
// Intent.isExcessed... 正确 API：
val isExplicit = intent.component != null   // 组件已指定
val hasPackage = intent.`package` != null   // 包已指定
```

### 3. 隐式 Intent 的过滤匹配规则

**系统匹配流程**：

```text
  startActivity(implicitIntent)
        │
        ▼
  遍历设备上所有已安装 App 的 <activity> / <service> / <receiver>
        │
        ├─ ① 显式匹配（component/package 匹配）→ 直接命中
        │
        └─ ② 隐式匹配：对每个候选组件的每个 <intent-filter> 逐条检查
              │
              ├─ action 匹配
              ├─ category 匹配
              ├─ data（scheme/host/path/mimeType）匹配
              └─ 所有条件都满足 → 候选
        │
        ▼
  0 个候选 → ActivityNotFoundException
  1 个候选 → 直接启动
  N 个候选 → 弹选择框（Android 5.0 之后 ALWAYS ask 行为）
```

**匹配细则（面试高频，必须精确）**：

| 匹配项 | 规则 |
| --- | --- |
| **action** | Intent 有 action 时，filter 必须有**相同的 action**；Intent **无 action** 时，filter 也**不能有 action**（有 action 就不匹配） |
| **category** | Intent 的每个 category 都必须在 filter 中出现；filter 多出来的 category **不影响**匹配 |
| **data** | 只有在 **Intent 和 filter 都设置了 data**（或都没有）时才参与匹配；**一旦 Intent 设了 data，就必须匹配 scheme、host、path** |
| **scheme** | 必须相同（区分大小写） |
| **host** | 必须相同；`*` 通配 |
| **path / pathPrefix / pathPattern** | 任一匹配即可；`pathPattern` 支持 `*` 和 `.*` |
| **mimeType** | Intent 设了 type 时必须匹配；用 `setDataAndType()` 同时设置 data 和 type |
| **CATEGORY_DEFAULT** | 用 `startActivity()`（而非 `startActivityForResult`）启动时，系统**隐式要求** filter 包含 `CATEGORY_DEFAULT` |

**data 匹配的精确规则（最容易搞错）**：

```text
  Intent:  data = "content://host/p1"
  Filter1: <data android:scheme="content" android:host="host" />   → 匹配 ✓
  Filter2: <data android:scheme="content" />                       → 匹配 ✓（host 不指定 = 任意）
  Filter3: <data android:scheme="https" />                         → 不匹配 ✗
  Filter4: (无 data)                                                → 不匹配 ✗（Intent 设了 data）

  Intent:  无 data
  Filter5: <data android:scheme="content" />                       → 不匹配 ✗
  Filter6: (无 data)                                                → 匹配 ✓
```

> **口诀**：**"Intent 有 data，filter 必须有 data 且 scheme 相同；Intent 无 data，filter 必须无 data。"**

**CATEGORY_DEFAULT 的历史原因**：早期 `startActivityForResult` 靠 `CATEGORY_DEFAULT` 区分"能返回结果的"和"不能的"。现在这个区分已经不重要，但**约定保留了下来**。

### 4. 查询匹配的组件：PackageManager

```kotlin
val pm = packageManager

// 1) 查询所有能处理该 Intent 的 Activity
val activities: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
activities.forEach { info ->
    Log.d("PM", "${info.activityInfo.packageName} / ${info.activityInfo.name}")
}

// 2) 系统快捷方式：能否处理（返回 null 表示不能）
val canHandle: Boolean = intent.resolveActivity(pm) != null

// 3) 系统分享面板（推荐，会预装"直接打开"等选项）
val chooser = Intent.createChooser(intent, "选择应用")
startActivity(chooser)

// 4) Android 11+ Package Visibility：查询前需要声明可见性
//    <queries><intent><action android:name="android.intent.action.VIEW"/>
//    <data android:scheme="https"/></intent></queries>
```

**Android 11 (API 30) 的包可见性限制**：默认情况下，App 只能查询到**自己 + 少量系统关键包**的组件信息。要查询其他 App，需要在 Manifest 里声明 `<queries>`：

```xml
<queries>
    <!-- 声明能处理 https 的 Activity -->
    <intent>
        <action android:name="android.intent.action.VIEW" />
        <data android:scheme="https" />
    </intent>
    <!-- 声明能查询某个包的全部组件 -->
    <package android:name="com.tencent.mm" />
    <!-- 声明能查询特定 action 的 Service -->
    <intent>
        <action android:name="com.example.MY_ACTION" />
    </intent>
</queries>
```

> 未声明时 `queryIntentActivities` 会**只返回你自己的组件**（和声明过的），这是 Android 11 之后非常常见的"明明装了微信却查不到"的原因。

### 5. 常见系统 Intent

| 场景 | Action | Data / Type |
| --- | --- | --- |
| 打开网页 | `Intent.ACTION_VIEW` | `https://...` |
| 拨号 | `Intent.ACTION_DIAL` | `tel:12345`（`DIAL` 不直接拨出，需用户确认） |
| 直接拨出 | `Intent.ACTION_CALL` | `tel:12345`（需 `CALL_PHONE` 权限） |
| 发送短信 | `Intent.ACTION_SENDTO` | `smsto:123` |
| 分享文本 | `Intent.ACTION_SEND` | `text/plain` + `EXTRA_TEXT` |
| 分享文件 | `Intent.ACTION_SEND` | `type` + `EXTRA_STREAM`（Uri） |
| 拍照 | `Intent.ACTION_IMAGE_CAPTURE` | `EXTRA_OUTPUT`（Uri） |
| 选择图片 | `Intent.ACTION_PICK` / `ACTION_GET_CONTENT` | `image/*` |
| 打开设置 | `Intent.ACTION_SETTINGS` | — |
| 打开应用详情 | `Intent.ACTION_APPLICATION_DETAILS_SETTINGS` | `package:` + 包名 |
| 搜索 | `Intent.ACTION_WEB_SEARCH` | `EXTRA_QUERY` |
| 安装 APK | `Intent.ACTION_VIEW` | `content://...` + `application/vnd.android.package-archive` |

**拍照的标准写法（必须处理 `EXTRA_OUTPUT` 的 Uri 权限）**：

```kotlin
val photoUri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
val takePicture = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
    putExtra(MediaStore.EXTRA_OUTPUT, photoUri)     // 指定输出位置
    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
}
takePictureLauncher.launch(takePicture)
```

**分享文本 + 多选**：

```kotlin
val sendIntent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, "分享内容")
}
startActivity(Intent.createChooser(sendIntent, "分享到"))
```

### 6. Intent 传递数据：Parcelable vs 大对象

**Intent extras 的底层是 `Bundle`，跨进程时通过 Binder 传输**，因此有 **1MB 事务缓冲限制**（超出抛 `TransactionTooLargeException`）。

**可传递的类型**：

| 类型 | 跨进程是否安全 | 说明 |
| --- | --- | --- |
| 基本类型（int/long/String/boolean...） | ✅ | 直接 |
| `Bundle` | ✅ | 嵌套 |
| `Parcelable` | ✅ | **必须实现 `Parcelable`**；自定义类需 `CREATOR` |
| `Serializable` | ⚠️ | 能用但**性能差**（Java 序列化），跨进程大数据不要用 |
| `ArrayList<Parcelable>` | ✅ | 注意 `intent.getParcelableArrayListExtra` 的类型退化 API 30+ |
| 复杂对象（Room Entity、List<User>） | ⚠️ | **不要直接传**，用 ID 或 URI |

**大对象的正确做法**：

```kotlin
// ❌ 错误：直接传大列表，跨进程必炸 TransactionTooLargeException
intent.putExtra("list", userList)

// ✅ 正确 1：传 ID，接收方自己查数据库
intent.putExtra(EXTRA_USER_ID, userId)

// ✅ 正确 2：传文件 Uri（内容由 ContentProvider 惰性提供）
intent.putExtra(EXTRA_FILE_URI, contentUri)

// ✅ 正确 3：传 Parcelable 的"引用对象"（对象在内存中，跨进程时会重建）
intent.putExtra(EXTRA_MODEL, ParcelableModel(id, name))

// ✅ 正确 4：自定义 Binder 传大对象（传的是引用，不传数据）
intent.putExtra(EXTRA_BINDER, binder)
```

**Parcelable 的正确写法**：

```kotlin
@Parcelize
data class UserParcel(
    val id: Long,
    val name: String,
    val avatar: Bitmap?     // 大位图不建议放这里
) : Parcelable
```

需要 `kotlin-parcelize` 插件。也可以手写 `writeToParcel` / `CREATOR`：

```kotlin
class UserManual(val id: Long, val name: String) : Parcelable {
    constructor(parcel: Parcel) : this(parcel.readLong(), parcel.readString() ?: "") {}

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(id)
        dest.writeString(name)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<UserManual> {
        override fun createFromParcel(parcel: Parcel): UserManual = UserManual(parcel)
        override fun newArray(size: Int): Array<UserManual?> = arrayOfNulls(size)
    }
}
```

### 7. PendingIntent 机制

**为什么需要 PendingIntent**：

> 有时候你**不**想立刻执行某个 Intent，而是想把它**交给系统**，让系统在**将来某个时刻、以你的身份**执行。例如：通知栏按钮、AlarmManager 闹钟、Widget 点击。

- 如果你直接把 Intent 交给系统，系统在执行时**只有系统自己的权限**，无法替你访问你的私有资源。
- `PendingIntent` 记录了"**创建者（creator）的身份与权限**"，系统执行它时，**以创建者的身份**执行。

**三个核心 Flag**：

| Flag | 含义 | 使用建议 |
| --- | --- | --- |
| **`FLAG_IMMUTABLE`** | 接收方**不能修改** Intent（action/data/extras 都不可改） | **默认首选**。API 31+ 强制要求显式指定 |
| **`FLAG_MUTABLE`** | 接收方**可以修改** Intent 内容 | 只在确实需要填充 extras 时用（如系统 `ACTION_SEND`） |
| **`FLAG_ONE_SHOT`** | 该 PendingIntent **只能使用一次**，用后即失效（不可复用） | 一次性动作（如一次性支付确认） |
| **`FLAG_NO_CREATE`** | 如果**已存在**相同 PendingIntent，则**不创建**、返回 null | 需要判断"是否已注册"时 |
| **`FLAG_CANCEL_CURRENT`** | 取消旧的，**创建新的**（extras 用新值） | 需要彻底替换旧的时 |
| **`FLAG_UPDATE_CURRENT`** | **复用**已有的，但**更新 extras** 为新值 | 重复设置闹钟、通知按钮的常用选择 |

**额外的更新行为 Flag（API 31+）**：
- `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`：更新 extras 但**保持 PendingIntent 身份不变**（同一 requestCode 视为同一个）。
- `FLAG_MUTABLE` + `FLAG_UPDATE_CURRENT`：会**替换**已有的 PendingIntent。

**requestCode 的作用**：区分不同的 PendingIntent。**requestCode 相同 + Intent 的"关键字段"相同**（`filterEquals`：action/data/type/class/categories，**extras 不参与比较**）时，系统会**复用**已有的 PendingIntent 对象。

```kotlin
// 1) 通知栏的删除按钮
val deleteIntent = Intent(this, DeleteReceiver::class.java).apply {
    action = "com.example.app.ACTION_DELETE"
    putExtra(EXTRA_ID, id)
}
val pendingDelete = PendingIntent.getBroadcast(
    this,
    id.toInt(),                       // requestCode：每个条目不同
    deleteIntent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)

// 2) 闹钟：系统到点后会以你的身份发出这个广播
val alarmIntent = Intent(this, AlarmReceiver::class.java)
    .setAction("com.example.app.ALARM")
    .putExtra(EXTRA_MSG, "该起床了")
val pendingAlarm = PendingIntent.getBroadcast(
    this,
    100,
    alarmIntent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)
alarmManager.setExactAndAllowWhileIdle(
    AlarmManager.RTC_WAKEUP, triggerAt, pendingAlarm
)

// 3) FLAG_NO_CREATE：检查是否已注册
val existing = PendingIntent.getBroadcast(
    this, 100, alarmIntent,
    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
)
if (existing != null) {
    // 已存在，替换
    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, existing)
}
```

**PendingIntent 的四个工厂方法**：

```kotlin
PendingIntent.getActivity(context, code, intent, flags)      // 启动 Activity
PendingIntent.getBroadcast(context, code, intent, flags)     // 发送广播
PendingIntent.getService(context, code, intent, flags)       // 启动 Service
PendingIntent.getForegroundService(context, code, intent, flags) // 启动前台服务
```

### 8. Intent 安全

**威胁一：显式 Intent 泄露（Intent Redirection / confused deputy）**

**攻击链**：

```text
  恶意 App                你的 App（中间人）              敏感组件
     │                        │                            │
     │ Intent(隐式)           │                            │
     ├───────────────────────►│ 你的 exported Activity     │
     │                        │ 收到 intent 后             │
     │                        │ 直接 startActivity(intent)  │
     │                        ├───────────────────────────►│ 敏感 Activity
     │                        │   （用你的 App 的权限）      │
```

- 你的 App 拥有某个敏感 Activity（`exported="true"`）的启动权限，但**没有**去检查 Intent 的来源；
- 恶意 App 只需要发一个隐式 Intent，你的 App 就"替它"启动了敏感组件。

**防御**：

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val intent = intent
    // ① 校验来源：只接受本 App 内部构造的 Intent
    if (intent.component?.packageName != packageName) {
        // 外部直接发来的显式 Intent → 拒绝
        finish()
        return
    }
    // ② 校验参数合法性
    val id = intent.getStringExtra(EXTRA_ID)
    if (id.isNullOrBlank()) { finish(); return }
}
```

**系统提供的方案**：`IntentSanitizer`（API 33+）—— 在 `Activity` 启动前剥离不合法的字段。

**威胁二：Intent 中携带的"待伪造参数"**

- 例如你的 App 收到 `Intent("com.example.action.PAY")` + `putExtra("paid", true)`，直接据此解锁 → 恶意 App 伪造广播/Intent 即可绕过。
- **防御**：本地 Intent 的**关键参数必须可验证**——如用 **HMAC 签名**、**服务端确认**、**系统 API 校验**（如 `MediaStore` 的权限）。

**威胁三：PendingIntent 劫持**

- 见 [[../四大组件/BroadcastReceiver]] 中的 PendingIntent 部分。
- 关键点：**必须用 `FLAG_IMMUTABLE`**。

**威胁四：隐式 Intent 被恶意 App 劫持**

```kotlin
// ❌ 危险：恶意 App 可以注册同样的 intent-filter 抢走
val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
startActivity(intent)

// ✅ 安全：限定包
val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
    .setPackage("com.android.chrome")   // 或用 PackageManager.resolveActivity 精确选择

// ✅ 更安全：显式 Intent（内部跳转永远用显式）
val intent = Intent(this, DetailActivity::class.java)
```

**威胁五：Activity 的 `exported="true"` + 未校验参数**

```xml
<!-- 敏感 Activity 应当 exported="false" -->
<activity android:name=".PayActivity" android:exported="false" />
```

### 9. Intent 与组件的启动

```kotlin
// Activity
startActivity(intent)
startActivityForResult(intent, requestCode)   // 已废弃，用 Activity Result API

// Service
startService(intent)                    // Android 8+ 后台受限
startForegroundService(intent)          // 需 5 秒内 startForeground()
bindService(intent, connection, flags)  // 绑定

// 广播
sendBroadcast(intent)
sendOrderedBroadcast(intent, resultReceiver, scheduler, initialCode, ...)
```

**广播的定向发送（避免被其他 App 接收）**：

```kotlin
intent.setPackage(packageName)                    // 限定包（安全，推荐）
intent.setComponent(ComponentName(pkg, cls))      // 精确到组件（更安全）
```

## 代码示例

### 1. 显式 Intent + Parcelable 传参

```kotlin
// 1) 参数模型：实现 Parcelable
@Parcelize
data class Order(
    val orderId: String,
    val amount: Long,
    val items: List<String>
) : Parcelable

// 2) 发送
object OrderRouter {
    const val EXTRA_ORDER = "extra_order"

    fun start(context: Context, order: Order) {
        val intent = Intent(context, OrderDetailActivity::class.java).apply {
            putExtra(EXTRA_ORDER, order)
            // 跨进程时若目标未导出，需要用 ClipData 传递 URI 权限（见下）
        }
        context.startActivity(intent)
    }
}

// 3) 接收
class OrderDetailActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // API 33+ 用新重载，避免类型退化问题
        val order: Order? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(OrderRouter.EXTRA_ORDER, Order::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(OrderRouter.EXTRA_ORDER)
        }
        order?.let { render(it) }
    }
}
```

### 2. 隐式 Intent + 冲突处理 + 系统分享

```kotlin
class ShareActivity : AppCompatActivity() {

    private fun shareText(text: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, "来自 MyApp")
        }

        // 方案 A：系统选择器（推荐，预装"直接分享"快捷项）
        startActivity(Intent.createChooser(sendIntent, "分享到"))

        // 方案 B：自己处理冲突 —— 查询所有处理者
        val pm = packageManager
        val targets = pm.queryIntentActivities(sendIntent, 0)
        when {
            targets.isEmpty() -> {
                // 没有 App 能处理：安装浏览器或提示用户
                Toast.makeText(this, "没有可用的应用", Toast.LENGTH_SHORT).show()
            }
            targets.size == 1 -> {
                // 唯一处理者：直接启动
                val ri = targets[0]
                startActivity(Intent.createChooser(sendIntent, null).apply {
                    // 也可以直接用 ri.activityInfo.packageName 构造显式 Intent
                })
            }
            else -> {
                // 多个处理者：弹自定义列表（比系统 chooser 体验更可控）
                showCustomChooser(targets)
            }
        }
    }

    private fun showCustomChooser(targets: List<ResolveInfo>) {
        val names = targets.map { it.loadLabel(packageManager).toString() }
        AlertDialog.Builder(this)
            .setTitle("选择应用")
            .setItems(names.toTypedArray()) { _, index ->
                val ri = targets[index]
                val explicit = Intent(ri.activityInfo.packageName).apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareTextContent)
                }
                startActivity(explicit)
            }
            .show()
    }
}
```

### 3. 自定义 ACTION + 声明 intent-filter + 处理

```kotlin
// 发送方
val intent = Intent("com.example.app.ACTION_SYNC").apply {
    putExtra("id", 42)
}
// 注意：跨 App 自定义 action 建议加 package 限定或使用 ContentProvider/Binder
intent.setPackage("com.example.otherapp")
startActivity(intent)

// 接收方 AndroidManifest.xml
<activity
    android:name=".receiver.SyncActivity"
    android:exported="true"
    android:launchMode="singleTask">
    <intent-filter>
        <action android:name="com.example.app.ACTION_SYNC" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:scheme="sync" android:host="example" />
    </intent-filter>
</activity>

// 接收方代码
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (intent.action == "com.example.app.ACTION_SYNC") {
        val id = intent.getIntExtra("id", -1)
        handleSync(id)
    }
}
```

### 4. PendingIntent：通知栏按钮 + 闹钟

```kotlin
class AlarmManagerHelper(private val context: Context) {

    companion object {
        private const val REQUEST_CODE = 1001
        const val ACTION_ALARM = "com.example.app.ACTION_ALARM"
        const val EXTRA_MESSAGE = "message"
    }

    fun schedule(message: String, triggerAtMillis: Long) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM
            putExtra(EXTRA_MESSAGE, message)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            // FLAG_UPDATE_CURRENT：extras 会被更新为最新的
            // FLAG_IMMUTABLE：防止他人篡改 Intent（API 31+ 强制）
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val am = context.getSystemService(AlarmManager::class.java)
        // Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限才能用 setExact
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    fun cancel() {
        val intent = Intent(context, AlarmReceiver::class.java).apply { action = ACTION_ALARM }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()   // 释放 PendingIntent 本身
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val msg = intent.getStringExtra(AlarmManagerHelper.EXTRA_MESSAGE).orEmpty()
        NotificationHelper.show(context, "闹钟", msg)
    }
}
```

### 5. 安全的显式 Intent 启动（防 Intent 泄露）

```kotlin
class ExternalEntryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ① 来源校验：只接受本 App 构造的 Intent（防止被当作"跳板"）
        val callerPackage = callingPackage
        val isFromSelf = callerPackage == packageName
        if (!isFromSelf) {
            Log.w(TAG, "Rejected external intent from $callerPackage")
            finish()
            return
        }

        // ② 参数校验：白名单 + 类型检查 + 范围检查
        val action = intent.action
        if (action !in ALLOWED_ACTIONS) {
            finish()
            return
        }
        val target = intent.getStringExtra(EXTRA_TARGET)
        if (target.isNullOrEmpty() || !target.matches(SAFE_PATTERN)) {
            finish()
            return
        }

        // ③ 真正执行
        route(target)
    }

    companion object {
        private const val TAG = "ExternalEntry"
        const val EXTRA_TARGET = "target"
        const val ACTION_OPEN = "com.example.app.ACTION_OPEN"
        private val ALLOWED_ACTIONS = setOf(ACTION_OPEN)
        private val SAFE_PATTERN = Regex("^[a-zA-Z0-9_/.-]{1,64}$")
    }
}
```

## 常见问题 / 面试要点

**读者问：显式 Intent 和隐式 Intent 有什么区别？各自的优缺点？**

**AI答：**

1. **本质区别**：
   - **显式**：Intent 里**明确写了目标组件**（`Intent(context, XxxActivity::class.java)` 或 `setComponent`），系统**直接投递**。
   - **隐式**：Intent 只描述"**做什么**"（action/category/data），系统**遍历所有组件的 intent-filter 做匹配**。
2. **对比表**：

   | 维度 | 显式 | 隐式 |
   | --- | --- | --- |
   | 目标确定 | 是 | 否（靠系统匹配） |
   | 性能 | 直接投递，**快** | 需要遍历所有 filter，**慢** |
   | 安全性 | **高**（无劫持） | **低**（可被劫持） |
   | 目标 exported 要求 | 跨 App 时需 `exported="true"` | 同上 |
   | 典型场景 | App 内部跳转 | 调用系统能力（浏览器/分享/拨号） |

3. **实践原则**：
   - **App 内部跳转永远用显式**（性能 + 安全）；
   - **调用系统能力用隐式**；
   - 隐式启动时**加上 `setPackage()`** 或先 `resolveActivity()` 精确选择，防止被恶意 App 劫持。
4. **面试话术**："显式 Intent 是'**点名道姓**'，隐式 Intent 是'**喊一嗓子谁接谁干**'。Android 的性能优化和安全加固这两年的主线，都指向'**内部尽量显式，外部注意劫持**'。"

---

**读者问：隐式 Intent 的匹配规则具体是什么？为什么我的 Intent 匹配不到任何 Activity？**

**AI答：**

1. **匹配的四要素**（必须**全部**满足）：
   - **action**：Intent 的 action 必须在 filter 中**完全相同**存在；**Intent 无 action 时，filter 也必须无 action**；
   - **category**：Intent 的每个 category 都必须在 filter 中；filter 多出的不影响；
   - **data**：**Intent 有 data → filter 必须有 data 且 scheme 匹配**；**Intent 无 data → filter 必须无 data**；
   - **type**：Intent 设了 type 必须匹配。
2. **`CATEGORY_DEFAULT` 的强制要求**：用 `startActivity()` 启动时，系统**隐式要求**目标 filter 包含 `android.intent.category.DEFAULT`。这是很多人"写了 filter 却匹配不上"的原因之一。
3. **最常见的三个匹配失败原因**：
   - **① 忘了 `CATEGORY_DEFAULT`**；
   - **② data 规则搞反**：Intent 里 `data = "https://x"`，filter 里只写了 `<data android:host="x"/>`（没写 scheme）→ 不匹配；
   - **③ Android 11+ 包可见性**：没在 `<queries>` 里声明 → `queryIntentActivities` 查不到（但 `startActivity` 仍能启动）。
4. **data 匹配的精确规则**（口诀）："**Intent 有 data，filter 必须有 data；Intent 无 data，filter 必须无 data**"。filter 只写 `scheme` 而不写 `host/path` 时，**匹配任意 host/path**。
5. **面试话术**："Intent 过滤匹配的本质是'**逐维度的逻辑与**'：action 相等 ∧ category 包含 ∧ data 匹配 ∧ type 匹配。debug 时**从 data 开始查**，因为 data 的规则最反直觉。"

---

**读者问：多个 App 都能处理同一个隐式 Intent 怎么办？多个 Activity 匹配到会怎样？**

**AI答：**

1. **系统行为**：
   - **0 个匹配** → 抛 `ActivityNotFoundException`（可捕获后降级到浏览器/提示安装）；
   - **1 个匹配** → 直接启动；
   - **多个匹配** → 弹**系统选择框**（ResolverActivity），让用户选。
2. **Android 5.0 之前**：弹选择框后，**一旦用户选择，就默认记住**（"始终"选项），以后直接进那个 App。Android 5.0 之后改为**每次都问**（除非 App 显式设置 `Intent.EXTRA_ALWAYS_USE` 或 `EXTRA_NEVER_SELECT_NOT_GOOGLE_APPS` 等偏好）。
3. **应用层的冲突处理方案**：
   - **方案 A：用系统 chooser**（推荐）：`startActivity(Intent.createChooser(intent, "选择应用"))`。优点是体验统一、能显示"直接分享"的快捷项。
   - **方案 B：自己查 + 自定义列表**：`queryIntentActivities()` 拿到列表后渲染自己的 UI（体验更好，可加图标排序、置顶常用项）。
   - **方案 C：用 `setPackage()` 精确指定**（如只发微信）：`intent.setPackage("com.tencent.mm")`——**指定包名后系统不会再弹选择框，也不会被劫持**。
   - **方案 D：加 `Intent.EXTRA_EXCLUDE_COMPONENTS`**（API 24+）排除某些组件。
4. **面试话术**："处理隐式 Intent 冲突有三种姿势：'**交给系统**'（chooser）、'**自己挑**'（查询+自定义 UI）、'**精确指定**'（setPackage）。生产环境里，**分享类功能用系统 chooser，跨 App 定向调用用 setPackage**。"
5. **补充加分点**：提到 Android 11 的 `<queries>` 声明——不声明 `<queries>` 的话，`queryIntentActivities` 返回的列表会是空的，导致"自定义 chooser 只有一个选项"。

---

**读者问：PendingIntent 是什么？`FLAG_IMMUTABLE`、`FLAG_MUTABLE`、`FLAG_ONE_SHOT` 分别是什么意思？**

**AI答：**

1. **PendingIntent 的本质**：一个**"延迟执行的 Intent 的代理令牌"**。它把 Intent 交给系统，并**携带创建者的身份与权限**，让系统在未来某个时刻**以创建者的身份**执行这个 Intent。
2. **为什么不能直接给系统 Intent**：因为那样系统执行时**只有系统自己的权限**，无法访问你 App 的私有资源（如你的 ContentProvider、你自己的 receiver）。
3. **三个核心 Flag**：

   | Flag | 语义 | 场景 |
   | --- | --- | --- |
   | `FLAG_IMMUTABLE` | 接收方**不能修改** Intent 的 action/data/type/extras | **默认首选**；API 31+ 强制要求显式二选一 |
   | `FLAG_MUTABLE` | 接收方**可以修改** Intent | 确实需要填充 extras 时（如某些系统分享场景） |
   | `FLAG_ONE_SHOT` | 该 PendingIntent **只能被触发一次**，用后立即失效 | 一次性动作（一次性确认、一次性通知点击） |

4. **辅助 Flag**（也常被问）：
   - `FLAG_UPDATE_CURRENT`：**复用**已有的 PendingIntent，但**更新 extras**（最常用）；
   - `FLAG_CANCEL_CURRENT`：**取消旧的并创建新的**（旧通知的 PendingIntent 也会失效）；
   - `FLAG_NO_CREATE`：**已存在则返回 null**，不创建（用于"检查是否已注册"）。
5. **requestCode 的作用**：区分不同的 PendingIntent。**requestCode 相同 + Intent 的 `filterEquals` 相同**（action/data/type/class/categories 相同，**extras 不比较**）时，系统复用同一个 PendingIntent 对象——**这就是为什么重复设置闹钟时必须加 `FLAG_UPDATE_CURRENT`，否则 extras 还是旧的**。
6. **面试话术**："`PendingIntent` 是 Android 的'**权限代理**'——它借用了创建者的权限在系统里执行动作。因此它的安全模型是'**不可篡改**'：`FLAG_IMMUTABLE` 是默认，`FLAG_MUTABLE` 是例外。`FLAG_ONE_SHOT` 则限定'**一次性**'，防止重放攻击。"
7. **常见坑**：
   - 忘了 `FLAG_UPDATE_CURRENT` → 通知上显示的还是旧文案；
   - 用了 `FLAG_MUTABLE` → Intent 劫持漏洞；
   - `FLAG_ONE_SHOT` 用在需要多次触发的场景（通知点击后第二次无效）。

---

**读者问：Intent 有什么安全风险？如何防止显式 Intent 泄露？**

**AI答：**

1. **风险一：Intent 泄露 / 混淆代理（Confused Deputy）**：
   - 你的 App 有一个 `exported="true"` 的 Activity（比如支付确认页）；
   - 恶意 App 发一条**隐式 Intent** 给它，你的 App 收到后**不校验来源就转发/执行**；
   - 结果：恶意 App 借你的 App 的权限/身份做了它自己做不了的事。
   - **防御**：① 敏感 Activity 设 `exported="false"`；② 校验 `callingPackage` / `callingActivity`；③ 校验参数白名单；④ 使用 `IntentSanitizer`（API 33+）。
2. **风险二：隐式 Intent 劫持**：
   - 恶意 App 注册相同的 `intent-filter`，抢走你的 Intent（尤其携带敏感 extras 的）。
   - **防御**：`setPackage()` / `setComponent()`；或先 `resolveActivity()` 精确选择。
3. **风险三：PendingIntent 劫持**：
   - `FLAG_MUTABLE` 让接收方能篡改 Intent。
   - **防御**：一律用 `FLAG_IMMUTABLE`（API 31+ 强制）。
4. **风险四：本地 Intent 参数被伪造**：
   - 你的 App 收到 `ACTION_PAY` + `putExtra("paid", true)` 就直接解锁 → 恶意 App 伪造即可。
   - **防御**：**关键业务状态必须以服务端为准**，本地 Intent 只作为"**触发一次重新校验**"的信号；或者对参数做 **HMAC 签名**。
5. **风险五：extras 泄露敏感数据**：
   - 广播/隐式 Intent 的 extras 可能被其他 App 读到。
   - **防御**：敏感数据走 **ContentProvider + URI 授权**或 **Binder**。
6. **面试话术**："Intent 安全的核心原则是'**默认不信任任何外部 Intent**'。三板斧：① **最小暴露**（exported=false）；② **严格校验**（来源 + 参数）；③ **权威判定在服务端**。"

---

**读者问：Intent 传大对象会怎样？超过多大？该怎么传？**

**AI答：**

1. **限制来源**：Intent 的 extras 走 `Bundle`，跨进程时经 **Binder 事务缓冲区**，系统限制约 **1MB**。超出抛 `TransactionTooLargeException`（在部分场景下表现为 `TransactionTooLargeException: data parcel size 1234567 bytes`）。
2. **实际更早的瓶颈**：即使没超 1MB，序列化大对象也**慢、占内存、易 OOM**。所以**大对象永远不要直接传**。
3. **正确传法（按优先级）**：
   - **① 传 ID**：接收方拿 ID 自己查数据库/Provider（**最推荐**）；
   - **② 传 URI**：文件用 `FileProvider` 的 `content://` Uri，内容惰性读取、不占内存；
   - **③ 传 Binder**：传的是"引用"，不是"数据"，适合实时回调场景；
   - **④ 传 Parcelable 小模型**：`@Parcelize data class` 只承载 id、name、url 这类小字段。
4. **Parcelable vs Serializable**：
   - `Parcelable` 是 Android 专用、二进制格式、**快**；
   - `Serializable` 是 Java 标准、通用但**慢**（Java 序列化开销大），跨进程大数据尤其不推荐；
   - **`Serializable` 的另一个坑**：`getSerializableExtra()` 在 API 33+ 需要传 Class，否则返回 null（类型退化问题）。
5. **面试话术**："Intent 是'**信封**'，不是'**货车**'。它的设计前提是'**小而轻的指令**'。大对象要么给个地址（ID/URI），要么给个回调（Binder），不要硬塞。"

---

**读者问：Intent 里可以放哪些类型？跨进程传递时有什么坑？`

**AI答：**

1. **可放类型**：基本类型、`String`、`Bundle`、`Parcelable`、`Serializable`、`ArrayList<Parcelable>`、`Intent`（嵌套）、`Bitmap`（不推荐）、`Binder`（传引用）。
2. **三个高频坑**：
   - **① 类型退化（API 33+）**：`intent.getParcelableExtra(key)` 在 API 33+ 必须传 Class：`getParcelableExtra(key, MyClass::class.java)`；旧版本仍返回未指定类型的对象，跨进程时**可能拿到错误类型** → 用 `@Parcelize` 并在接收端强转时加防御。
   - **② 集合类型退化**：`getParcelableArrayListExtra(key)` 同理需要 `getParcelableArrayListExtra(key, MyClass::class.java)`。
   - **③ `Serializable` 的兼容性**：反序列化时类名被混淆（ProGuard）会导致 `ClassNotFoundException` → 需要 `@Keep` 或混淆规则保留。
3. **Parcelable 的性能**：Parcelable 的 `writeToParcel` 是**手写的、字段级的**，比 Java 序列化快一个数量级；而且 Parcelable 在跨进程时**反序列化出的对象是新实例**（Serializable 反序列化同理），不要以为还是同一个对象引用。
4. **面试话术**："Parcelable 快是因为'**按字段二进制编码，不做通用对象图遍历**'。它也有代价：手写 `writeToParcel`/`CREATOR` 容易漏字段，所以现在官方推荐 `@Parcelize` 注解。"

---

**读者问：`Intent` 和 `Bundle` 有什么区别？`Intent.getExtras()` 什么时候返回 null？`

**AI答：**

1. **区别**：
   - `Intent` 是"**一条消息**"——描述**做什么、给谁做**（action/data/component/flags）。
   - `Bundle` 是"**一个键值包**"——纯粹装数据（key → value），本身**不描述行为**。
   - 关系：`Intent` **内含**一个 `Bundle`（`mExtras`）。`putExtras(bundle)` 把 Bundle 合并进 Intent。
2. **`getExtras()` 返回 null 的情况**：
   - Intent **从未调用过** `putExtra`/`putExtras`；
   - 调用了 `putExtra(key, null)` —— 注意：**`putExtra` 的 key 为 null 或 value 为 null 时，extras 本身仍会被创建**（Bundle 非空），但 `getExtras()` 可能返回 null（取决于版本与 `mExtras == null` 判断）；
   - 反序列化后由系统重建的 Intent 可能丢失空 Bundle。
   - **健壮写法**：用 `intent.extras?.getString(key)` 或 `intent.getStringExtra(key)`（后者直接返回 null，不需判空）。
3. **Bundle 的 Parcelable 陷阱**：
   - `Bundle.getParcelable<T>(key)` 在 API 33+ 需要传 Class；
   - `Bundle` 里放 `Parcelable` 时，系统在跨进程时会用 `ClassLoader` 加载，**自定义类必须在目标进程可见**。
4. **面试话术**："Intent 是'**带地址的信封**'，Bundle 是'**信里的内容**'。所以'数据用 Bundle，动作和路由用 Intent'——这就是为什么 `Intent` 有时又被叫作'**一个带 Bundle 的消息对象**'。"

## 关联笔记

- [[../README]]：第 2 部分总览
- [[Binder机制]]：Intent 在跨进程时如何被序列化传输
- [[AIDL]]：需要强类型复杂接口时用 AIDL 而不是 Intent
- [[../四大组件/Activity]]：Activity 的启动、任务栈与 Intent Flag
- [[../四大组件/Service]]：Service 的启动 Intent 与前台服务限制
- [[../四大组件/BroadcastReceiver]]：广播 Intent 与 PendingIntent
- [[../四大组件/ContentProvider]]：`content://` URI 与 Intent 的配合
- [[../Application与Context]]：Context 与 Intent 的关系（Context 本身就是隐式 Intent 的载体）

## 参考资料

- Android 官方文档 · Intent 概览：https://developer.android.com/guide/components/intents-filters
- Android 官方文档 · Intent 过滤器（匹配规则）：https://developer.android.com/guide/topics/manifest/intent-filter-element
- Android 官方文档 · PendingIntent：https://developer.android.com/guide/components/pending-intents
- Android 官方文档 · 包可见性（`<queries>`）：https://developer.android.com/training/package-visibility
- 《Android 高级编程》第 4 版 · 第 3 章（Intent 与 Intent 过滤器）
