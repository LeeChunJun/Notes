---
tags: [android]
part: 第2部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[../README]], [[../组件间通信/Intent]], [[Activity]], [[Service]]"
---

# BroadcastReceiver

## 一句话定义

BroadcastReceiver 是 Android 中**只做一件事：接收并响应广播事件**的组件。它没有 UI、没有生命周期管理，只有一个 `onReceive()` 回调。广播是系统（以及你的 App）向"可能关心这件事的接收方"**广播一条消息**的机制，本质上是**系统级的发布-订阅模型**。

面试开场白：

> BroadcastReceiver 的全部技术点集中在**四个维度**：**注册方式**（静态 / 动态）、**广播类型**（有序 / 无序 / 本地）、**执行环境限制**（onReceive 在主线程、约 10 秒）、**系统限制**（Android 8+ 隐式广播禁令、Android 13+ 必须指定导出标志、Android 14+ 必须声明 exported）。这四条线构成了 90% 的面试题。

## 核心原理

### 1. 广播的分类

```text
                      广播 Broadcast
                           │
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
   系统广播            应用广播            本地广播
  (系统发出)         (应用间发出)      (LocalBroadcastManager)
        │                  │                  │
        └────────┬─────────┘                  │
                 ▼                            ▼
          ┌──────────────┐            ┌──────────────┐
          │  无序广播     │            │  同进程内     │
          │ (Normal)     │            │  不走系统     │
          │  所有接收者   │            │  Deprecated  │
          │  并行/无序    │            └──────────────┘
          └──────────────┘
                 │
                 ▼
          ┌──────────────┐
          │  有序广播     │
          │ (Ordered)    │
          │  按优先级串行 │
          │  可 setResult │
          └──────────────┘
```

| 类型 | 特点 | 能中止吗 | 典型用途 |
| --- | --- | --- | --- |
| **普通广播（无序）** | 所有接收者**并行**接收，顺序不确定 | 不能 | 系统通知型事件（`BOOT_COMPLETED`、电量变化） |
| **有序广播** | 按 `priority` 从高到低**串行**投递，优先级高的先收到 | **能**（`abortBroadcast()`） | 需要"中间件"语义的场景（如 `SMS_RECEIVED`） |
| **粘性广播（Sticky）** | `sendStickyBroadcast()`，最后一条广播会**保留**在系统里，注册时立刻回调 | — | 旧版网络状态获取（已不推荐） |
| **本地广播** | 只在**同一进程内**投递，不经过系统 | — | `LocalBroadcastManager`（**已废弃**） |

**有序广播的工作方式**：

```text
sendOrderedBroadcast()
        │
        ▼
  按 priority 从高到低依次投递给所有接收者
        │
        ├─ 接收者 A（priority=999）：onReceive() → abortBroadcast()
        │                                    └─► 后面的接收者 B、C **收不到**
        ▼
  接收者 B（priority=100）
        ▼
  接收者 C（priority=0）
        │
        ▼
  最终接收者（priority=-999）收到 RESULT_OK/RESULT_CANCELED
```

> ⚠️ `abortBroadcast()` **只在有序广播中有效**。在普通广播里调用**无效**（且在 API 33+ 已废弃该方法在非有序广播中的行为）。

### 2. 注册方式：静态 vs 动态

| 维度 | 静态注册（Manifest） | 动态注册（`registerReceiver`） |
| --- | --- | --- |
| 注册位置 | `AndroidManifest.xml` | 代码中 `Context.registerReceiver()` |
| 生效时机 | **App 进程启动时**（进程一启动就注册） | 注册那一刻起，到 `unregisterReceiver` 为止 |
| 无 App 进程时 | **能收到**（系统会拉起进程） | **收不到** |
| 生命周期 | 跟随进程 | 跟随注册者（Activity/Service） |
| API 26+ 限制 | **受"隐式广播清单限制"** | 不受该限制 |
| API 33+ 要求 | Manifest 必须声明 `android:exported` | 必须传 `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED` |
| 适用场景 | 开机启动、App 被拉起时的全局事件 | 页面级事件（需要随 UI 生命周期） |

**静态注册示例**：

```xml
<receiver
    android:name=".receiver.BootReceiver"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

**动态注册示例**：

```kotlin
class NetworkMonitor(private val context: Context) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val connected = ConnectivityManagerCompat.isActiveNetworkMetered(ctx!!) == false
            // 处理网络变化
        }
    }

    fun register() {
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        // API 33+ 必须显式指定导出标志
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED   // 不接收其他 App 的广播
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    fun unregister() {
        context.unregisterReceiver(receiver)
    }
}
```

### 3. 生命周期极短（onReceive ~10 秒）

**核心事实**：

- `onReceive()` **运行在主线程**（对于动态注册是注册者所在进程的主线程；静态注册是 App 主线程）。
- 系统给 `onReceive()` 的执行时间大约 **10 秒**（前台进程约 10s，后台进程约 30s 后被杀）。超时或未完成，系统会抛 `ANR`（对于前台）或直接杀进程。
- `onReceive()` **返回后，进程可能立刻被系统回收**（静态注册时尤其明显：广播处理完，进程若无其他组件，系统会结束它）。

**在 onReceive 里能做什么 / 不能做什么**：

| 操作 | 是否推荐 | 原因 |
| --- | --- | --- |
| 读 SharedPreferences / 数据库 | ✅ 可以 | 快 |
| 发通知 | ✅ 可以 | 快 |
| 启动 Activity | ⚠️ 谨慎 | Android 10+ 后台启动 Activity 受限（需 `NEW_TASK` 且系统可能拦截） |
| 启动 Service | ⚠️ 谨慎 | Android 8+ 后台启动限制 → 应用 `startForegroundService()` 并在 5 秒内 `startForeground()` |
| 网络请求 | ❌ 危险 | 主线程 `NetworkOnMainThreadException`；即便开线程，进程可能在请求完成前被杀 |
| 复杂初始化 | ❌ 危险 | 进程会被杀 |

**正确做法**：

```kotlin
class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 1. 拿到 PendingResult，告诉系统"我还没处理完"
        val pendingResult = goAsync()
        // 2. 把耗时工作交给 WorkManager（持久化、可重试、跨进程死亡安全）
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<SyncWorker>().build()
        )
        // 3. 如果必须自己处理，用 goAsync + 后台线程，并在结束时 finish()
        // pendingResult.finish()
    }
}
```

**`goAsync()` 的正确用法**：

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    val pendingResult = goAsync()
    Thread {
        try {
            doHeavyWork(context, intent)
        } finally {
            // 必须调用，否则系统认为超时并可能 ANR
            pendingResult.finish()
        }
    }.start()
}
```

> 面试点：`goAsync()` 只给你大约 **10 秒**（前台）/ 30 秒（后台）的额外时间，且**进程仍有可能被系统杀死**。所以**能用 WorkManager 就用 WorkManager**。

### 4. Android 8.0+ 隐式广播限制

**Android 8.0 (API 26) 的"隐式广播清单限制"**：

> **Manifest 中注册的 receiver，不再能接收绝大多数"隐式广播"**（implicit broadcast）。

**什么叫隐式广播**：
- **显式广播**：Intent 里**明确指定了组件**（`setComponent` / `setPackage`），只投递给这个组件。
- **隐式广播**：Intent 只带 `action`（可带 data/category），由系统按 `intent-filter` 匹配分发。

**被限制的典型 action**（清单里注册**不再生效**，除非加 `android:directBootAware="true"` 或改用动态注册）：

| Action | 说明 |
| --- | --- |
| `ACTION_LOCKED_BOOT_COMPLETED` / `ACTION_BOOT_COMPLETED` | 开机（`BOOT_COMPLETED` 现在**必须**用动态注册才能收到） |
| `ACTION_USER_PRESENT` | 解锁 |
| `ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED` | 时间/时区变化 |
| `ACTION_LOCALE_CHANGED` | 语言变化 |
| `ACTION_USB_ACCESSORY_ATTACHED` | USB 外设 |
| `ACTION_CONNECTION_STATE_CHANGED` | 网络状态 |
| `ACTION_PACKAGE_DATA_CLEARED` | 数据清除 |

**未被限制的（仍然可以用清单注册）**：
- **显式广播**：由你自己发送、明确指定组件的。
- **仅限本 App 的系统广播**：如 `ACTION_MY_PACKAGE_REPLACED`、`ACTION_PACKAGE_REPLACED`（自己包被替换）、`ACTION_PACKAGE_FULLY_REMOVED`（自己包被卸载）——系统只为你的包单独发这份广播。
- `ACTION_PACKAGE_ADDED`？—— 不在豁免列表中，需要动态注册或改用 `ACTION_MY_PACKAGE_REPLACED`。

**官方给出的三条绕行路径**：

1. **改用动态注册**（推荐）：在 `onCreate`/`onStart` 注册。
2. **用 JobScheduler 替代**：`JobScheduler` 支持 `setTrigger`（开机后触发）、`setPersisted(true)`（跨重启保留）。
3. **显式广播**：自己发显式广播给自己。

**面试话术**："Android 8 的隐式广播限制，本质是**保护系统启动性能**——过去几十个 App 在开机时都要被拉起并注册广播，系统启动被拖慢。Google 的思路是'**系统的系统事件改用 JobScheduler，App 自己的事件用显式广播**'。"

### 5. Android 13/14+ 的导出标志（exported flag）

**Android 13 (API 33) 起**：动态注册 `BroadcastReceiver` **必须**指定导出标志，否则抛 `SecurityException`：

```kotlin
context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)   // 接收系统/其他 App 的广播
context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) // 只接收本 App 的广播
```

| 标志 | 含义 | 安全建议 |
| --- | --- | --- |
| `RECEIVER_EXPORTED` | 接收**其他 App 和系统**发送的广播 | 只有确实需要系统广播（如 `CONNECTIVITY_ACTION`）时才用 |
| `RECEIVER_NOT_EXPORTED` | **只接收本 App**发送的广播 | **默认首选**，防止恶意 App 伪造广播攻击 |

**Android 14 (API 34) 起**：Manifest 中声明的 receiver，**必须显式声明 `android:exported`**（值为 `true` 或 `false`），不再有默认值。未声明会**安装失败**（编译期由 AGP 检查报错）。

```xml
<!-- 需要接收系统广播 → exported="true" -->
<receiver
    android:name=".receiver.BootReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>

<!-- 只处理本 App 内部事件 → exported="false" -->
<receiver
    android:name=".receiver.InternalReceiver"
    android:exported="false" />
```

> 面试点：**`exported="true"` 的 receiver 是一个攻击面**。恶意 App 可以发送伪造广播触发你的逻辑（如伪造"支付成功"广播）。原则是：**只接收系统/自己可信来源的广播才用 exported=true**，其余一律 false。

### 6. LocalBroadcastManager 已废弃

**LocalBroadcastManager**（`androidx.localbroadcastmanager`）用于**进程内**的广播，特点：
- 不经过系统，**不跨进程**；
- 不会被其他 App 收到（安全性更好）；
- 性能更高（无 IPC）。

**为什么废弃**：
1. 官方认为它**解决了一个不存在的问题**——进程内通信用 **Kotlin 协程的 `SharedFlow` / `StateFlow`** 或 **Kotlin `Channel`** 更简单、更强类型、更符合现代写法。
2. 它**没有权限模型**（不能细粒度控制谁能发），且不支持跨进程。
3. `androidx.localbroadcastmanager` 已被标记 deprecated。

**现代替代方案**：

```kotlin
// 用 SharedFlow 做进程内事件总线
object EventBus {
    private val _events = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun emit(event: String) {
        _events.tryEmit(event)
    }
}

// 使用方
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                EventBus.events.collect { event -> handle(event) }
            }
        }
    }
}
```

### 7. 常见系统广播

| Action | 触发时机 | 清单注册是否可用（API 26+） | 权限 |
| --- | --- | --- | --- |
| `android.intent.action.BOOT_COMPLETED` | 开机完成 | ❌（需动态注册或 JobScheduler） | `RECEIVE_BOOT_COMPLETED` |
| `android.intent.action.USER_PRESENT` | 用户解锁 | ❌ | — |
| `android.net.conn.CONNECTIVITY_CHANGE` | 网络变化 | ❌（需动态注册） | — |
| `android.intent.action.PACKAGE_ADDED` | 安装了包 | ❌ | — |
| `android.intent.action.PACKAGE_REMOVED` | 卸载了包 | ❌ | — |
| `android.intent.action.MY_PACKAGE_REPLACED` | **自己的包**被更新 | ✅ | — |
| `android.intent.action.SCREEN_ON` / `SCREEN_OFF` | 屏幕开关 | ❌ | — |
| `android.intent.action.BATTERY_CHANGED` | 电池状态 | ✅（粘性广播） | — |
| `android.intent.action.TIME_TICK` | 每分钟 | ❌ | — |
| `android.provider.Telephony.SMS_RECEIVED` | 收到短信 | ✅（高危，需默认短信应用） | — |
| `android.intent.action.SEND` / `SENDTO` | 用户分享 | ✅ | — |

**开机启动的现代写法（JobScheduler）**：

```kotlin
class BootJobScheduler {
    fun schedule(context: Context) {
        val job = JobInfo.Builder(
            JobSchedulerJobService::class.java,
            JobInfo.Builder(0).let { 1 }
        )
            .setPersisted(true)                    // 跨重启保留，需要 RECEIVE_BOOT_COMPLETED
            .setMinimumLatency(0)
            .build()

        (context.getSystemService(JobScheduler::class.java))
            .schedule(job)
    }
}

class JobSchedulerJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        // 做初始化工作
        return false   // false = 工作已完成，无需继续占用
    }
    override fun onStopJob(params: JobParameters): Boolean = false
}
```

### 8. PendingIntent + 广播

`PendingIntent` 是**"未来某刻以别人身份执行某个 Intent 的令牌"**。它可以被交给系统（通知栏、AlarmManager），系统在未来有权代表你发送该 Intent。

**通过 PendingIntent 触发广播**（通知栏的常见模式）：

```kotlin
fun buildPendingDeleteIntent(context: Context, id: Long): PendingIntent {
    val intent = Intent(context, DeleteReceiver::class.java).apply {
        action = "com.example.app.ACTION_DELETE"
        putExtra(EXTRA_ID, id)
    }
    return PendingIntent.getBroadcast(
        context,
        id.toInt(),          // requestCode：区分不同 PendingIntent
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

class DeleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        // 删除逻辑
    }
}
```

**关键点**：
- `PendingIntent.getBroadcast()` 得到的 PendingIntent 被系统"兑现"时，等价于**系统代表你的 App 发出一条显式广播**给 `DeleteReceiver`。
- **`requestCode` 相同 + Intent "相同"（filterEquals：action/data/type/class/categories 相同，extras 不参与比较）** 时，后创建的 PendingIntent 会**复用/覆盖**前一个 —— 这就是必须用 `FLAG_UPDATE_CURRENT`（更新 extras）而 `FLAG_CANCEL_CURRENT`（取消旧的，创建新的）的原因。
- **必须加 `FLAG_IMMUTABLE`**（API 31+ 强制）：否则系统要求 `FLAG_MUTABLE`，恶意 App 可能修改你的 PendingIntent 里的 Intent（Intent hijacking）。

### 9. 广播安全的核心原则

| 风险 | 说明 | 对策 |
| --- | --- | --- |
| **伪造广播** | 恶意 App 发广播触发你的逻辑 | `exported="false"` / `RECEIVER_NOT_EXPORTED`；对关键数据**做发送方校验** |
| **参数注入** | 广播 extras 被篡改 | 校验参数范围（白名单、类型检查、签名校验） |
| **Intent 劫持（PendingIntent）** | 恶意 App 修改 PendingIntent 的 Intent | `FLAG_IMMUTABLE` |
| **敏感数据泄露** | 广播是系统级可见的 | 不要在广播 extras 里放敏感信息；用 `setPackage` 定向发送 |
| **`setPackage` vs `setComponent`** | `setPackage` 只限定包，`setComponent` 精确到组件 | 精确场景用 `setComponent`（且需导出） |

**定向发送广播（不暴露给其他 App）**：

```kotlin
// 只发给本 App 的 receiver
intent.setPackage(packageName)
// 或精确指定组件
intent.setComponent(ComponentName(packageName, "com.example.app.MyReceiver"))
```

## 代码示例

### 1. 静态注册的 Manifest receiver（Android 14+ exported 明确声明）

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

    <application ...>

        <!-- 1) 开机启动：Android 8+ 清单注册收不到 BOOT_COMPLETED，
                这里改用 MY_PACKAGE_REPLACED + JobScheduler 兜底 -->
        <receiver
            android:name=".receiver.BootReceiver"
            android:enabled="true"
            android:exported="true"
            android:directBootAware="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>

        <!-- 2) 只处理本 App 内部事件：exported=false，禁止外部触发 -->
        <receiver
            android:name=".receiver.InternalEventReceiver"
            android:exported="false" />

    </application>
</manifest>
```

### 2. 动态注册 + 生命周期自动解绑（推荐写法）

```kotlin
class MainActivity : AppCompatActivity() {

    // 用属性初始化，保证 receiver 引用稳定（防止被 GC 后 unregister 失败）
    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connected = NetworkUtil.isConnected(context)
            binding.status.text = if (connected) "已连接" else "未连接"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+：必须指定导出标志。
            // 系统广播需要 RECEIVER_EXPORTED；仅本 App 事件用 RECEIVER_NOT_EXPORTED
            registerReceiver(connectivityReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(connectivityReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 必须在 onDestroy 之前解除注册，否则泄漏
        runCatching { unregisterReceiver(connectivityReceiver) }
    }
}
```

**更优雅的 Kotlin 写法（避免忘记 unregister）**：

```kotlin
// 自定义扩展函数
inline fun <reified T : BroadcastReceiver> Context.registerReceiver(
    crossinline onReceive: (Context, Intent) -> Unit
): Closeable {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onReceive(context, intent)
    }
    val filter = IntentFilter(T::class.java.simpleName)  // 实际项目请传具体 action
    registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    return Closeable { unregisterReceiver(receiver) }
}

// 使用
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val closeable = registerReceiver<MyReceiver> { _, intent ->
        handle(intent)
    }
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.CREATED) {
            awaitClose { closeable.close() }   // Activity 销毁时自动解除注册
        }
    }
}
```

### 3. onReceive 中转交 WorkManager（正确处理耗时任务）

```kotlin
class LocationRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 1. 告诉系统"我还没处理完"，最多延长约 10 秒
        val pendingResult = goAsync()

        // 2. 正确的做法：把任务交给 WorkManager（持久化、进程死了也会跑）
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueue(request)

        // 3. 立即 finish：任务已交给 WorkManager，本 receiver 任务结束
        pendingResult.finish()
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ok = repository.refreshLocation()
        return if (ok) Result.success() else Result.retry()
    }
}
```

### 4. PendingIntent 触发的广播 + 安全校验

```kotlin
object AlarmHelper {

    fun scheduleReminder(context: Context, taskId: Long, delayMs: Long) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.example.app.action.SHOW_REMINDER"
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            // FLAG_IMMUTABLE：防止他人篡改 Intent；FLAG_UPDATE_CURRENT：extras 会被更新
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + delayMs,
            pendingIntent
        )
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 防御式校验：即使是 PendingIntent 触发，也要验证数据合法性
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId <= 0) return

        // 二次校验：确认这条 PendingIntent 确实是本应用发起的
        // （生产环境可结合 signature 校验）
        val expected = ReminderReceiver::class.java.name
        val matched = context.packageManager.queryBroadcastReceivers(
            intent, PackageManager.MATCH_DEFAULT_ONLY
        ).any { it.activityInfo.name == expected }

        if (!matched) return

        NotificationHelper.showReminder(context, taskId)
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
    }
}
```

### 5. LocalBroadcastManager 的现代替代（SharedFlow 事件总线）

```kotlin
// 旧写法（已废弃）
// LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
// LocalBroadcastManager.getInstance(context).registerReceiver(receiver, filter)

// 新写法：类型安全的进程内事件总线
sealed interface AppEvent {
    data class TokenRefreshed(val token: String) : AppEvent
    data class NetworkChanged(val connected: Boolean) : AppEvent
}

object EventBus {
    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    fun post(event: AppEvent) {
        _events.tryEmit(event)
    }
}

// 订阅方
class ProfileActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                EventBus.events.collect { event ->
                    when (event) {
                        is AppEvent.TokenRefreshed -> updateToken(event.token)
                        is AppEvent.NetworkChanged -> showNetworkState(event.connected)
                    }
                }
            }
        }
    }
}
```

## 常见问题 / 面试要点

**读者问：BroadcastReceiver 的 `onReceive` 里为什么不能做耗时操作？10 秒是从哪来的？**

**AI答：**

1. **执行线程**：`onReceive()` 运行在**主线程**。这是 Android 的硬性设计——因为 receiver 可能是静态注册的（App 进程刚被拉起），系统需要在可控时间内完成回调并决定是否继续拉起进程。
2. **时间限制**：
   - **前台进程**中的 `onReceive`：约 **10 秒**。超过会被视为 ANR。
   - **后台进程**中的 `onReceive`：约 **30 秒**，之后系统直接杀进程。
   - 这些是**经验值**，AOSP 没有公开的硬性 API，但业界普遍按 10s 处理。
3. **更根本的问题不是时间，而是"进程随时可能死"**：
   - 静态注册的 receiver 处理完广播后，若进程内无其他组件，系统**立即回收进程**；
   - 即使开了子线程做网络请求，请求还没回来进程就被杀了。
4. **正确方案**：
   - **首选 WorkManager**：任务持久化在数据库，进程死了系统会重新调度，天然支持约束与重试。
   - **次选 `goAsync()`**：拿到 `PendingResult`，用子线程处理，结束时必须 `finish()`。但仍受 10 秒限制，且**进程仍可能被杀**。
5. **面试话术**："`onReceive` 是'**通知我一下**'，不是'**请你去干活**'。系统的设计意图是让 receiver 保持轻量，重活交给有调度能力的组件。"

---

**读者问：静态注册和动态注册怎么选？各自的优缺点是什么？**

**AI答：**

1. **核心差异维度**：

   | 维度 | 静态注册 | 动态注册 |
   | --- | --- | --- |
   | 生效范围 | 整个 App 生命周期 | 注册到注销之间 |
   | 无进程时 | **能收到**（系统拉起进程） | 收不到 |
   | Android 8+ 隐式广播 | **收不到**（大部分 action） | **能收到** |
   | 生命周期绑定 | 跟进程 | 跟注册者（Activity/Service/Application） |
   | 安全性 | 靠 `exported` + 权限 | 靠 `RECEIVER_EXPORTED/NOT_EXPORTED` |

2. **选择原则**：
   - **动态注册**（默认首选）：
     - 页面级事件（网络变化、屏幕开关、电量）；
     - 只在 Activity 可见时才有意义的事件；
     - 需要**实时**响应（静态注册的广播有延迟，且受后台限制）。
   - **静态注册**：
     - 必须"**App 未运行也要响应**"的场景（如 `MY_PACKAGE_REPLACED`、自定义的跨 App 通知）；
     - 只处理**自己 App 内**的显式广播。
3. **面试话术**："能用动态就用动态。静态注册意味着'**任何人都能拉起你的进程**'，是攻击面也是性能负担。"
4. **常见坑**：动态注册忘记 `unregisterReceiver` → 泄漏 Activity；`unregisterReceiver` 在未注册时调用会抛 `IllegalArgumentException`（用 `runCatching` 兜住）。

---

**读者问：Android 8.0 为什么要限制隐式广播？该怎么绕过？**

**AI答：**

1. **限制内容**：Android 8.0 起，**Manifest 中注册的 receiver 不再接收大多数隐式广播**（`BOOT_COMPLETED`、`CONNECTIVITY_CHANGE`、`USER_PRESENT`、`PACKAGE_ADDED` 等）。
2. **为什么**：
   - **启动性能**：开机时几十个 App 都要被拉起进程、注册 receiver、跑 `onReceive`（很多还做了耗时的初始化），系统启动被拖到几十秒。
   - **用户体验**：后台 App 偷偷对系统事件做反应（如弹广告、唤醒服务），耗电耗流量。
   - **安全性**：可被恶意 App 伪造广播触发。
3. **三条官方路径**：
   - **动态注册**（最常用）：在 `onCreate`/`onStart` 注册，`onStop`/`onDestroy` 注销。
   - **JobScheduler 替代**：`setPersisted(true)` + `setMinimumLatency(0)` 模拟"开机触发"，且能被系统智能调度（省电）。
   - **显式广播**：自己 `setComponent` 发给自己。
4. **注意**：这个限制**只针对 Manifest 注册**；**动态注册完全不受影响**。
5. **面试话术**："这是 Android 的一次'**从事件驱动转向调度驱动**'的迁移。系统事件不再广播给所有 App，而是由系统自己在合适时机调度你的 Job。"

---

**读者问：Android 13/14 对 BroadcastReceiver 的 `exported` 要求分别是什么？为什么这么改？**

**AI答：**

1. **Android 12 (API 31)**：Manifest 中**含 `intent-filter` 的**组件（Activity/Service/Receiver）**必须显式声明 `android:exported`**。不声明则**安装失败**。
2. **Android 13 (API 33)**：**动态注册** `registerReceiver()` 必须指定导出标志：
   - `Context.RECEIVER_EXPORTED`：接收系统和其他 App 的广播；
   - `Context.RECEIVER_NOT_EXPORTED`：只接收本 App 的广播（**推荐默认**）。
   - 不传则抛 `SecurityException`。
3. **Android 14 (API 34)**：Manifest 中的 receiver **必须**声明 `android:exported`，即使它没有 `intent-filter`。
4. **为什么改**：
   - **历史漏洞**：默认 `exported=true`（有 intent-filter 时）导致恶意 App 可以直接启动你的 Activity/Service、触发你的 Receiver；
   - **Intent 劫持**：Android 12 引入 `PendingIntent` 的 `FLAG_MUTABLE/IMMUTABLE` 强制要求，也是同一条安全主线；
   - **强制显式化**：让开发者在写代码时就做出"**这个组件对外暴露吗**"的决策，而不是依赖默认值。
5. **面试话术**："`exported` 的默认值从'**看 intent-filter 猜**'变成'**必须显式写**'，本质是把安全决策从'隐式约定'提升为'显式契约'。这是 Android 12~14 安全收紧的主线之一。"
6. **常见坑**：给 receiver 设 `exported="false"` 后系统广播收不到（如 `CONNECTIVITY_ACTION` 需要 `true`）。**正确做法：需要系统广播就用 `EXPORTED`，需要防伪造就用 `NOT_EXPORTED`，别一刀切。**

---

**读者问：广播安全吗？怎么防止恶意 App 伪造广播攻击我的 App？**

**AI答：**

1. **攻击面**：任何 `exported="true"` 的 receiver，都是可以被任意 App 发广播触发的。典型攻击：
   - 伪造"支付成功"广播 → 免费解锁；
   - 伪造"用户已登录"广播 → 越权；
   - 伪造"收到短信"广播 → 钓鱼。
2. **防御手段**：
   - **① 缩小暴露面**：默认 `exported="false"` / `RECEIVER_NOT_EXPORTED`。
   - **② 权限保护**：给 receiver 加 `android:permission="com.example.app.PERMISSION"`，只有持有该权限的 App 才能发送。
   - **③ 发送方校验**（在 `onReceive` 里）：检查广播是否来自系统。

     ```kotlin
     override fun onReceive(context: Context, intent: Intent) {
         // 校验是否是系统组件发送的
         if (intent.getStringExtra("sender") != "android") return
         // 或者检查 callingPackage（需要从 AMS 拿，见 ActivityManager.getSentFromPackage）
     }
     ```

   - **④ 关键操作二次验证**：真正的权限判定必须走**服务器校验**或**系统 API 校验**（如 `MediaStore` 的权限），不能靠本地广播。
   - **⑤ 数据校验**：即使收到合法广播，也要校验 extras 的**取值范围和完整性**。
3. **面试话术**："**本地广播不能作为信任来源**。客户端的任何校验都可被绕过——真正的权威判定必须在服务端。广播只能作为'**触发一次重新同步**'的信号，而不是'**事实**'。"
4. **常见坑**：用广播传递"支付成功"并直接解锁功能 → 必被破解。正确做法是广播只触发"**去服务端查询订单状态**"。

---

**读者问：`PendingIntent` 是什么？为什么 `FLAG_IMMUTABLE` 是必须的？**

**AI答：**

1. **`PendingIntent` 的本质**：一个**"代理令牌"**——你把一个 Intent 交给系统（通知栏、AlarmManager、Widget），系统在未来某个时刻**代表你**执行这个 Intent。它记录了"**由谁发起**"（creator 应用的 uid），所以目标组件收到的广播是"**系统代表你发的**"，能获得相应权限。
2. **三个关键点**：
   - 它是**惰性**的：创建时不执行，执行时机由系统决定。
   - 它是**身份凭证**：携带创建者的权限。
   - 它是**可去重的**：相同 requestCode + 相同 Intent（`filterEquals`，extras 不参与）会复用。
3. **FLAG_IMMUTABLE vs FLAG_MUTABLE**：
   - `FLAG_IMMUTABLE`（推荐）：接收方**不能修改** Intent 的内容（不能改 action/data/extras）。安全默认值。
   - `FLAG_MUTABLE`：接收方**可以修改** Intent（例如给系统 Intent 填充 extras，常见于 `ACTION_SEND`）。**只有确实需要填充时才用**，否则是 Intent 劫持漏洞。
   - **API 31+ 强制**：不指定 flag 时，系统会抛 `IllegalStateException`，提示必须明确选择。
4. **Intent 劫持攻击原理**：如果你的通知用了 `FLAG_MUTABLE` 或没加 `FLAG_IMMUTABLE`（旧版本），恶意 App 可以拿到你的 PendingIntent，**填入自己的 Intent**，然后系统执行时就会执行攻击者的 Intent——可能调起你的敏感组件。
5. **面试话术**："`PendingIntent` 是 Android 的'**权限代理**'——它让一个本来没权限的动作，借用了创建者的权限。因此必须做到'**不可篡改**'，这就是 `FLAG_IMMUTABLE` 成为强制的根本原因。"
6. **常见坑**：`PendingIntent.getActivity` 时**忘了 `FLAG_UPDATE_CURRENT`**，导致 extras 是旧值（因为系统复用了已有的 PendingIntent）。

---

**读者问：本地广播为什么被废弃？现在应该怎么做进程内通信？**

**AI答：**

1. **LocalBroadcastManager 的特点**（优点）：进程内投递、不经过系统、**不需要导出标志**、性能更好。
2. **废弃原因**：
   - **它解决的问题不成立**：进程内通信用协程的 `SharedFlow`/`Channel` 只需几行代码，且**类型安全**（编译期检查），而 LocalBroadcastManager 是 `Intent` 字符串匹配的，运行时才炸。
   - **没有权限模型**：只能靠"包名过滤"这种粗糙手段。
   - **与 Jetpack 的方向冲突**：官方在推协程 + 响应式，进程内事件总线应该是 `SharedFlow`。
   - `androidx.localbroadcastmanager` 官方已标记 deprecated。
3. **现代替代**：
   - **事件总线**：`MutableSharedFlow<AppEvent>` + `tryEmit`，订阅方用 `repeatOnLifecycle(STARTED)` 收集。
   - **状态共享**：`StateFlow`（单一真相源）。
   - **一次性事件**：`Channel`。
   - **场景内通信**：直接用 Kotlin 的**函数参数/接口回调**或 `Fragment Result API`（`setFragmentResult`/`setFragmentResultListener`）——这是最推荐的方式。
4. **面试话术**："LocalBroadcastManager 的'进程内安全'特性，现在被 Kotlin 协程的强类型 + 生命周期感知更好地解决了。**能用类型就别用字符串**。"
5. **额外加分点**：提到 `Fragment Result API` 和 `Activity Result API` 作为"**组件间通信的现代官方方案**"，能体现你跟进了新 API。

## 关联笔记

- [[../README]]：第 2 部分总览
- [[../组件间通信/Intent]]：广播的本质就是一条广播 Intent
- [[Activity]]：动态注册 receiver 时 Activity 是宿主
- [[Service]]：广播可以作为 Service 的触发源
- [[ContentProvider]]：ContentProvider 也可以用 `ContentObserver` 监听数据变化
- [[../Application与Context]]：`registerReceiver` 需要 Context
- [[../../06-系统原理与性能优化/性能优化专项/电量优化]]：JobScheduler 与 WorkManager 的省电机制
- [[../../07-架构、工具与生态/架构模式/MVVM]]：用 SharedFlow 替代本地广播做事件分发

## 参考资料

- Android 官方文档 · BroadcastReceiver 概览：https://developer.android.com/guide/components/broadcasts
- Android 官方文档 · 隐式广播限制：https://developer.android.com/guide/components/broadcasts#manifest-declared-receivers
- Android 官方文档 · 上下文注册接收器与导出标志：https://developer.android.com/develop/ui/views/components/manifest-receiver-element
- Android 官方文档 · PendingIntent 与不可变性：https://developer.android.com/guide/components/pending-intents
- 《Android 高级编程》第 4 版 · 第 8 章（广播接收器）
