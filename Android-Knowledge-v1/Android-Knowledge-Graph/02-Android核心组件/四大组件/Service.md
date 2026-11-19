---
tags: [android]
part: 第2部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[../README]], [[Activity]], [[../组件间通信/Binder机制]], [[../组件间通信/Intent]]"
---

# Service

## 一句话定义

Service 是 Android 中**没有 UI、生命周期独立于界面**的组件，用于执行**不属于界面线程的后台工作**或**为其他进程提供可跨进程调用的能力**。它的本质是：**一个被 AMS 调度的、跑在宿主进程主线程上的对象**，通过 `IBinder` 暴露接口；Service **自己不会自动开线程**，所有耗时逻辑都需要你自己调度到工作线程。

面试开场白：

> Service 有两个完全不同的角色：**"后台任务容器"**（started Service，做完自己结束）和**"跨进程服务提供者"**（bound Service，通过 Binder 暴露接口）。Android 8 之后纯后台 Service 几乎无法保活，所以现代 Android 里，**真正可靠的后台任务应该用 WorkManager，前台服务只用于"用户可感知的持续任务"（导航、播放、下载、录音）**。

## 核心原理

### 1. Service 的三种类型（+ 前台服务）

| 类型 | 启动方式 | 生命周期终点 | 系统如何杀死 | 典型场景 |
| --- | --- | --- | --- | --- |
| **Started Service**（启动型） | `startService()` / `startForegroundService()` | 自己调 `stopSelf()`，或系统杀 | 后台时优先级低，容易被杀 | 一次性后台任务（已不推荐裸用） |
| **Bound Service**（绑定型） | `bindService()` | 所有客户端 `unbindService()` 后销毁 | 客户端没了就解绑 | 提供接口给同进程/跨进程调用 |
| **IntentService**（已废弃） | `startService()` + `IntentService` | 处理完 Intent 自动 `stopSelf()` | 同 started | **API 30 起已废弃**，用 WorkManager 替代 |
| **Foreground Service**（前台服务） | `startForegroundService()` + `startForeground()` | 需显式停止 | **不会**被后台杀（但受类型/时长限制） | 音乐播放、导航、下载、录音、健身记录 |

**IntentService 为什么被废弃**：它的实现是"HandlerThread + 队列串行处理 + 处理完 `stopSelf()`"，看起来完美，但：
1. **Android 8.0 起后台启动限制**：App 在后台时 `startService()` 会抛 `IllegalStateException`（Android 8.0+）或直接不执行（更早版本静默失败）。
2. **没有持久化调度能力**：进程被杀，任务就没了；没有重试、没有约束（网络/电量）。
3. **官方明确推荐改用 WorkManager**（可约束、可持久、可重试）。

**Foreground Service 是不是 started service 的一种**：是。`startForegroundService()` 启动后，你**必须在 5 秒内**调用 `startForeground()` 并发通知，否则系统抛 `ForegroundServiceDidNotStartInTimeException` 并崩溃。

### 2. Service 生命周期

```text
  startService()                 bindService()
      │                              │
      ▼                              ▼
  ┌────────────┐              ┌────────────┐
  │ onCreate()  │◄─────────────┤ onCreate() │  ① 首次创建（只调一次）
  └─────┬──────┘              └─────┬──────┘
        │                             │
        ▼                             ▼
  ┌──────────────────┐         ┌──────────────┐
  │ onStartCommand() │         │  onBind()    │  ② 返回 IBinder
  │  (可多次)        │         └──────┬───────┘
  └─────┬────────────┘                │
        │                             ▼
        │                    ┌──────────────────┐
        │                    │  onUnbind()      │  ③ 所有客户端解绑
        │                    └────────┬─────────┘
        │                             │
        ▼                             ▼
  ┌─────────────────────────────────────────┐
  │              onDestroy()                 │  ④ 销毁，释放资源
  └─────────────────────────────────────────┘
```

**关键点**：

- `onCreate()` **只调用一次**（一个 Service 实例），`onStartCommand()` **可以被调用多次**（每次 `startService` 一次）。
- `onBind()` 返回 `null` 表示**不提供绑定接口**，此时 Service 纯粹是 started 型。
- **既 started 又 bound** 是允许的：Service 会在"所有客户端解绑 **且** `stopSelf()` 被调用"后才销毁。
- **`stopSelf()` 与 `stopService()`**：前者由 Service 自己决定"我做完活可以死了"，后者由客户端强制停。
- **`onRebind()`**：如果 `onBind()` 返回的 Binder 在所有客户端解绑后，Service **没有**被 `stopSelf()`，而是有新客户端再次绑定，会触发 `onRebind()`（需要 `onUnbind()` 返回 true 才生效）。

**`onStartCommand` 的返回值决定被杀死后的行为**（面试高频）：

| 返回值 | 被杀后行为 | 场景 |
| --- | --- | --- |
| `START_STICKY` | **进程被杀后系统会用 null Intent 重建 Service**（`onStartCommand` 收到 null intent） | 需要自动恢复的持续任务（同步） |
| `START_NOT_STICKY` | **不重建** | 一次性任务（推荐） |
| `START_REDELIVER_INTENT` | 重建并**用最后那个 Intent 重新调用** `onStartCommand` | 需要保留原始 Intent 的任务 |
| `START_STICKY_COMPAT` | 兼容旧行为的 STICKY，不保证重建 | 遗留代码 |

> ⚠️ **`START_STICKY` 不是保活魔法**。它只在"**进程被系统杀掉**"时有意义，而且重建时机不确定（可能几分钟后才重建，也可能不重建）。它**不能**对抗"Doze 模式"、"后台执行限制"、"厂商清理"。这些"保活"神话在面试里是经典扣分点。

### 3. 前台服务（Foreground Service）

**为什么需要前台服务**：Android 对后台工作有严格限制（Doze、App Standby、后台启动限制）。前台服务通过**在通知栏显示一个持续通知**来"占用用户注意力"，从而获得更高的进程优先级（`adj = foreground_service`），**进程在后台时不会被 LMK 优先杀死**。

**前台服务的三要素**：

1. **必须发通知**：`startForeground(id, notification)`，否则 5 秒后崩溃。
2. **必须声明权限**（API 34+ 强制）：`android.permission.FOREGROUND_SERVICE`。
3. **必须声明类型**（API 34+ 强制）：Manifest 中 `android:foregroundServiceType`，并在 `startForeground()` 时传对应的 type。

```xml
<manifest>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <!-- Android 14 (API 34) 起，特定类型还需要额外权限 -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

    <application>
        <service
            android:name=".PlaybackService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />
    </application>
</manifest>
```

**Android 14 (API 34) 的 `foregroundServiceType` 规则**：

| type | 需要的额外权限 | 运行时还要申请 | 说明 |
| --- | --- | --- | --- |
| `mediaPlayback` | `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | — | 音乐/视频播放 |
| `mediaProcessing` | `FOREGROUND_SERVICE_MEDIA_PROCESSING` | — | 图片/视频转码 |
| `dataSync` | `FOREGROUND_SERVICE_DATA_SYNC` | — | 数据传输、备份 |
| `phoneCall` | `FOREGROUND_SERVICE_PHONE_CALL` | `MANAGE_OWN_CALLS` / `READ_PHONE_STATE` | 通话 |
| `location` | `FOREGROUND_SERVICE_LOCATION` | `ACCESS_FINE/COARSE_LOCATION` | 持续定位 |
| `camera` | `FOREGROUND_SERVICE_CAMERA` | `CAMERA` | 相机 |
| `connectedDevice` | `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 蓝牙等权限 | 外设通信 |
| `shortService` | — | — | 最多 3 分钟的短任务（**targetSdk 34+ 专用**） |
| `specialUse` | `FOREGROUND_SERVICE_SPECIAL_USE` | — | 必须在 Manifest 里写明理由 |
| `systemExempted` | — | — | 仅系统应用 |

**代码调用（Android 14+ 必须传 type）**：

```kotlin
// Android 14 起重载版本：显式指定类型
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    )
} else {
    startForeground(NOTIFICATION_ID, notification)
}
```

**Android 15 (API 35) / targetSdk 35 的 6 小时超时限制**：

- **`dataSync` 类型**的前台服务，在 **targetSdk 35** 的应用上，**每个日历日累计运行上限为 6 小时**。超过后系统调用 `onTimeout(int startId, int fgsType)`，你**必须停止前台服务**，否则系统抛 `ForegroundServiceStartNotAllowedException`。
- API 34 上，`dataSync` 的上限是 **6 小时**（`onTimeout` 在 API 34 引入），API 35 上改为**每 24 小时滚动 6 小时**（基于 App Standby bucket 的访问配额）。
- 其他类型（如 `mediaPlayback`、`location`）**没有时长上限**，但 `mediaPlayback` 会受"**用户停止媒体会话**"和通知渠道权限的影响。
- 应对：把长时间同步任务拆成多个 WorkManager 任务，或使用 `JobScheduler` 的 `setRequiredNetworkType` + 长任务 `setForeground`。

**Android 15 的其他 FGS 变更**：
- `BOOT_COMPLETED` 广播**不能再启动**前台服务（Android 15 限制）。
- `dataSync` 类型不能在**应用启动的前台服务**(12 小时内) 运行（`Service.onTimeout` + 配额）。

### 4. 后台执行限制（Android 8.0+ 的重要变化）

**Android 8.0 (API 26) 起**：当 App **处于后台**时调用 `startService()`：
- 系统**不再**创建 Service 实例，而是抛出 `IllegalStateException`（如果 App 没有可见 Activity）。
- 官方解决方案：`startForegroundService()` + 5 秒内 `startForeground()`。

**后台限制的三道闸门**：

```text
  1. 后台启动限制（Oreo, API 26）
     └─ 后台 startService() → IllegalStateException

  2. 后台执行限制（Pie, API 28）
     └─ 后台 Service 无法访问网络、读写存储、获取 wake lock

  3. App Standby / Doze（9.0+ / 6.0+）
     └─ 受限 App 桶：JobScheduler 延迟、网络受限
```

**Android 9 (API 28) 的后台执行限制**具体含义：在后台运行的 Service **不能**：
- 读写应用私有存储（`getSharedPreferences`、`openFileOutput` 会抛 `IllegalStateException`）；
- 访问网络（`NetworkOnMainThreadException` 或网络被挂起）；
- 获取 wake lock。

**正确替代方案对比**：

| 需求 | 正确方案 | 理由 |
| --- | --- | --- |
| 可延迟、需网络、需要约束 | **WorkManager** | 持久化、约束感知、自动重试、进程被杀后仍会执行 |
| 精确定时（如整点、对齐闹钟） | **JobScheduler** | 系统调度，`setExact` 需 `SCHEDULE_EXACT_ALARM` 权限 |
| 用户可见的持续任务（导航/播放/下载） | **Foreground Service** | 有通知、进程优先级高 |
| 应用退到后台后必须继续一小段时间 | **`JobScheduler` + `startForeground`** | 兼容方案 |
| 纯内存态的即时任务 | **协程 + ViewModelScope / lifecycleScope** | 生命周期感知，无跨进程需求 |

**WorkManager vs Service 的本质区别**：

- WorkManager 内部**用 `JobScheduler`（API 23+）或 `AlarmManager` + `BroadcastReceiver` 兜底**实现；每个 Work 就是一个 `Worker`（本质是 JobService 里的一个 Job）。
- WorkManager 的 Worker **运行在 App 进程内的后台线程池**，不是 Service。
- WorkManager 的任务**持久化在数据库里**，进程死了、App 重启了，任务还在。

### 5. Android 12+ (API 31) 后台启动 FGS 的限制

**Android 12 的限制**：App **处于后台时**，**不允许启动前台服务**——会抛 `ForegroundServiceStartNotAllowedException`。

例外（仍然允许在后台启动 FGS）：
1. App 从 **可见 Activity** 转入后台（过渡状态）；
2. App 有 **`Activity` 在最近任务的最顶端**（最近任务可见）；
3. App 在**前台服务或前台运行**；
4. 高优先级 **FCM 消息**（`FirebaseMessagingService` 的高优先级消息）；
5. 用户在**系统设置**里为 App 授予"后台运行"豁免（豁免列表）；
6. `exact alarm` 触发；
7. App 是设备所有者/系统应用。

**Android 14 (API 34) 进一步收紧**：
- **必须声明 `foregroundServiceType`**，且运行时传入的 type 必须**与 Manifest 声明一致或为其子集**。
- **类型专属权限**：没有 `FOREGROUND_SERVICE_LOCATION` 权限就不能起 `location` 类型的前台服务。

**Android 15 (API 35)**：
- `dataSync` 类型有 6 小时/日 配额；
- `BOOT_COMPLETED` 不能起 FGS；
- 前台服务启动前必须有 `FOREGROUND_SERVICE` 权限，否则抛异常。

**面试话术**："后台启动 FGS 的门槛是'**App 刚刚还可见**'。所以从通知栏 PendingIntent 或从 Job 里启动 FGS，要么通过 PendingIntent 的用户交互触发，要么用 `JobScheduler` 的 `setUserInitiated`（API 34+）。"

### 6. Service 的线程模型

**Service 的回调（`onCreate`、`onStartCommand`、`onBind`）全部运行在宿主进程的**主线程**。Service **不会自动开后台线程**。

```text
  Application (主线程)
        │
        ├── Activity.onCreate()      ← 主线程
        ├── Service.onCreate()       ← 主线程（同一个线程！）
        ├── BroadcastReceiver.onReceive() ← 主线程
        └── ContentProvider.query()  ← 主线程（通常）
```

**正确做法**：在 Service 内部用 `HandlerThread`、`Executor`、`Kotlin 协程`（`CoroutineScope`）处理耗时任务，并在 `onDestroy` 里取消。

```kotlin
class MyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handlerThread = HandlerThread("worker").apply { start() }
    private lateinit var worker: Handler

    override fun onCreate() {
        super.onCreate()
        worker = Handler(handlerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        worker.post {
            // 耗时任务在 HandlerThread 上
            val result = heavyWork(intent?.getStringExtra("key"))
            Log.i("MyService", "done: $result")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        handlerThread.quitSafely()
    }
}
```

**面试话术**："Service 是'**没有 UI 的组件**'，不是'**后台线程**'。它跑在主线程上，只是没有界面刷新需求。'Service 里做耗时任务卡主线程'是初级面试最常见的错误认知之一。"

### 7. Bound Service 与 Binder

Bound Service 通过 `onBind()` 返回一个 `IBinder`，客户端可以：

```kotlin
class LocalBinder : Binder() {
    fun getService(): MyService = this@MyService
}

override fun onBind(intent: Intent): IBinder = binder

// 客户端
private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        val binder = service as MyService.LocalBinder
        val svc = binder.getService()   // 同进程：直接拿到实例
        svc.doWork()
    }
    override fun onServiceDisconnected(name: ComponentName) { }
}

bindService(Intent(this, MyService::class.java), connection, BIND_AUTO_CREATE)
```

- **同进程绑定**：返回的 Binder 就是 Service 实例本身（`LocalBinder`），`onServiceConnected` 立刻回调。
- **跨进程绑定**：返回的是**代理 Binder**，调用会经 IPC 到远端进程，见 [[../组件间通信/Binder机制]] 与 [[../组件间通信/AIDL]]。
- `BIND_AUTO_CREATE`：客户端绑定时若 Service 未启动则创建它。
- **同进程绑定必须在 `onStart` 之后解除**，否则会抛 `IllegalStateException: ServiceConnection leaked`（Android 4.0+ 严格检查）。

## 代码示例

### 1. 前台服务（Android 14+ 类型感知）

```kotlin
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_URL = "url"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14+：必须传 foregroundServiceType，且与 Manifest 声明一致
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // 真正的播放逻辑交给协程，不阻塞主线程
        serviceScope.launch {
            player.play(url)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        // 播放被杀后不自动恢复（媒体类可用 START_STICKY 由上层决定）
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "播放控制",
            NotificationManager.IMPORTANCE_LOW   // 前台服务通知建议 LOW，避免打扰
        )
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在播放")
            .setContentText("歌曲名称")
            .setSmallIcon(R.drawable.ic_play)
            .setOngoing(true)          // 不可被划掉
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
```

Manifest：

```xml
<service
    android:name=".PlaybackService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback" />
```

### 2. Bound Service + Binder（同时支持同进程与跨进程）

```kotlin
// 服务端
class MusicService : Service() {

    private val binder = MusicBinder()

    inner class MusicBinder : Binder() {
        /** 供同进程客户端直接调用 */
        fun getService(): MusicService = this@MusicService

        /** 供跨进程客户端（AIDL）调用，最终会走 transact */
        fun start(title: String) = playInternal(title)
    }

    private fun playInternal(title: String) {
        Log.i("MusicService", "play: $title")
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
    }
}

// 客户端（同进程）
class PlayerActivity : AppCompatActivity() {

    private var musicService: MusicService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            // LocalBinder 时可以拿到真实实例；跨进程时这里拿到的是代理
            musicService = (service as MusicService.MusicBinder).getService()
            musicService?.start("晴天")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            musicService = null
        }
    }

    override fun onStart() {
        super.onStart()
        // 同进程绑定：必须在 onStart 之后、onStop 之前 bind/unbind，否则抛 ServiceConnection leaked
        bindService(Intent(this, MusicService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        unbindService(connection)
    }
}
```

### 3. 用 WorkManager 替代 IntentService（推荐的现代做法）

```kotlin
class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 1. 读输入
        val filePath = inputData.getString(KEY_FILE) ?: return Result.failure()

        // 2. 检查业务前置条件
        if (!isNetworkAvailable()) return Result.retry()

        return try {
            upload(filePath)
            Result.success(outputDataOfData("url" to uploadedUrl))
        } catch (e: IOException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_FILE = "file_path"

        fun enqueue(context: Context, filePath: String) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_FILE to filePath))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "upload_$filePath",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
```

### 4. Android 15 (targetSdk 35) 的 dataSync 超时处理

```kotlin
class SyncService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15 增加了带类型的重载
            startForeground(
                ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
        return START_NOT_STICKY
    }

    /**
     * Android 14+：当 dataSync 类型的前台服务超过时长配额时，
     * 系统回调此方法，必须停止前台服务，否则抛异常。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Log.w("SyncService", "FGS timeout, type=$fgsType, must stop now")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        // 业务上应把剩余任务重新排进 WorkManager
        WorkManager.getInstance(this).enqueue(SyncWorker.request())
    }
}
```

## 常见问题 / 面试要点

**读者问：Service 和 Thread 有什么区别？为什么不直接用 Thread？**

**AI答：**

1. **本质区别**：
   - `Thread` 是**执行单元**，解决"代码在哪个线程跑"。
   - `Service` 是**Android 组件**，解决"任务的生命周期由谁管理、进程被杀了怎么办、要不要通知用户"。
2. **Service 提供了 Thread 给不了的能力**：
   - **生命周期与进程管理**：Service 的创建/销毁由 AMS 管理；进程被杀时系统知道有个 Service 在（可按 `onStartCommand` 返回值重建）。
   - **跨进程调用**：Service 可以通过 Binder 对外暴露接口（Thread 不能）。
   - **用户可见性**：前台服务能出现在通知栏，让用户知道 App 在耗电。
   - **任务调度**：Service 可以配合 `JobScheduler`/`AlarmManager` 被系统调度。
3. **Service 的回调跑在主线程**，所以 Service ≠ 后台线程。要在 Service 里用线程，仍然要自己 `HandlerThread`/协程。
4. **面试话术**："Thread 是'执行机制'，Service 是'**被系统调度的、有生命周期的组件**'。前者解决并发，后者解决**生命周期与调度**。两者不是替代关系，正确姿势是'Service 提供生命周期，协程/线程池提供并发'。"
5. **常见坑**：以为 Service 天然在后台线程，直接在 `onStartCommand` 里做网络请求 → **NetworkOnMainThreadException**。

---

**读者问：Service 能不能执行耗时任务？为什么 IntentService 被废弃了？**

**AI答：**

1. **能，但必须自己开线程**。Service 的所有回调在主线程，耗时任务要放到 `HandlerThread`、`ExecutorService` 或协程的 `Dispatchers.IO` 里。
2. **IntentService 的历史价值**：它把"HandlerThread + 串行队列 + 处理完 `stopSelf()`"封装起来，天然适合"一次性串行后台任务"。
3. **废弃的三个原因**：
   - **Android 8.0 后台启动限制**：后台 `startService()` 抛 `IllegalStateException`，IntentService 根本没机会启动。
   - **没有约束与重试**：网络不可用时无法等待；失败后不会重试。
   - **任务不持久**：进程被杀，任务丢失。
4. **官方替代方案**：
   - **WorkManager**：需要约束、重试、保证最终执行。
   - **JobScheduler**：需要精确定时或系统级调度。
   - **前台服务**：用户可见的持续任务。
5. **面试话术**："IntentService 解决的是'**怎么不卡主线程**'，但没解决'**怎么保证任务做完**'。WorkManager 补上了后者——持久化 + 约束 + 重试，这是两者代际差别的核心。"

---

**读者问：前台服务为什么需要通知？Android 14/15 对前台服务做了什么限制？**

**AI答：**

1. **为什么必须有通知**：前台服务的本质是"**占用用户注意力换取系统资源**"。通知是这份"注意力合同"的凭证——用户能看到 App 在耗电/耗流量/用定位，并可随时关掉。没有通知，App 就能在后台无限跑，这正是 Android 一直在治理的问题。
2. **Android 8.0**：`startForegroundService()` 后必须 **5 秒内**调 `startForeground()`，否则抛 `ForegroundServiceDidNotStartInTimeException`。
3. **Android 9**：后台前台服务的类型受限（当时仅 dataSync/location 等少数）。
4. **Android 12**：**后台不允许启动前台服务**，抛 `ForegroundServiceStartNotAllowedException`；例外包括"刚从可见 Activity 转后台"、"最近任务在前台"、"高优先级 FCM"等。
5. **Android 14（API 34）**：
   - 必须声明 `android:foregroundServiceType`；
   - 运行时 `startForeground()` 必须传入匹配的 type；
   - 每种 type 需要对应的 `FOREGROUND_SERVICE_xxx` 权限（缺权限会抛 `SecurityException`）；
   - `location`/`camera`/`microphone`/`dataSync` 还需要先拿到运行时权限。
6. **Android 15（API 35，targetSdk 35）**：
   - **`dataSync` 类型每个日历日累计上限 6 小时**，超时回调 `onTimeout(startId, fgsType)`，必须停止；
   - `BOOT_COMPLETED` 广播不能再启动前台服务；
   - `shortService` 类型：最多 3 分钟，适合短任务。
7. **面试话术**："前台服务的门槛是'**用户能感知 + 有明确类型 + 有配额**'。Android 15 给 `dataSync` 加 6 小时配额，就是在防止厂商 App 把前台服务当'永久后台线程'用。"

---

**读者问：后台执行限制具体限制了什么？为什么我的 Service 访问网络会失败？**

**AI答：**

1. **限制的时间线**：
   - **Android 8.0**：后台 `startService()` 被禁（抛 `IllegalStateException`），必须改用 `startForegroundService()`。
   - **Android 9（API 28）**：后台 Service **不能访问网络、读写存储、获取 wake lock**。具体表现是：
     - 访问网络 → `NetworkOnMainThreadException` 或网络被挂起；
     - 读写 SharedPreferences/文件 → `IllegalStateException`；
     - `WakeLock` → 被拒绝。
2. **例外**：Service **绑定在前台 Activity**、Service **本身是前台服务**、Service **是 bound 且客户端在前台** 时不受限。
3. **正确的技术选型**：

   | 场景 | 方案 |
   | --- | --- |
   | 后台上传/同步（可延迟） | `WorkManager` + `NetworkType.CONNECTED` 约束 |
   | 精确定时（闹钟式） | `JobScheduler` / `AlarmManager.setExactAndAllowWhileIdle` |
   | 用户可见的持续任务 | 前台服务（带 type 声明） |
   | 短任务（< 3 分钟） | `WorkManager` 的 `setExpedited()` 或 `shortService` 类型 FGS |

4. **面试话术**："后台限制的本质是'**省电 + 保护用户**'。系统认为'用户没看到你，就不该你在后台偷偷跑'。所以现代 Android 的答案是'**要么让用户看到（前台服务），要么让系统调度（WorkManager）**'。"
5. **常见坑**：为了绕过限制，把 Service 伪装成前台服务但发一个空通知、或滥用 `FOREGROUND_SERVICE_SPECIAL_USE`——上架 Google Play 会被拒。

---

**读者问：`START_STICKY` 能保活 Service 吗？服务被杀了之后会怎样？**

**AI答：**

1. **`START_STICKY` 的准确语义**：**仅当进程被系统因内存原因杀死后**，系统会**在将来某个不确定的时刻重建该 Service**，并调用 `onStartCommand(intent=null, flags=0, startId=...)`，**intent 为 null**。
2. **`START_NOT_STICKY`**：被杀后不重建。
3. **`START_REDELIVER_INTENT`**：被杀后重建并**重新投递最后一次 Intent**。
4. **`START_STICKY` 不能做什么**：
   - 不能对抗 **Doze 模式 / App Standby** 的调度延迟；
   - 不能对抗**后台执行限制**；
   - 不能对抗**厂商 ROM 的清理策略**（MIUI/EMUI 等）；
   - 不能保证**立刻**重建（可能是几小时甚至不重建）。
5. **面试话术**："`START_STICKY` 不是'保活'，是'**被杀后有机会复活**'。真正的'任务不丢'要靠 **WorkManager 的持久化**——任务存在数据库里，系统会重新调度。"
6. **常见坑**：用 `START_STICKY` + 自建守护线程（`while(true) sleep`）保活 → 既伤电又伤用户信任，Google Play 上架审核不通过。正确做法是 WorkManager。

---

**读者问：Bound Service 的 `onBind` 返回 null 是什么意思？同进程和跨进程绑定的区别？**

**AI答：**

1. **返回 `null`**：表示"**我不提供绑定接口**"，Service 退化为纯 started 型，`bindService()` 会走 `onServiceConnected` 但拿到的 `IBinder` 为 null（实际上系统会回调 `onServiceConnected` 传 null）。实际上返回 null 时系统认为绑定失败，客户端的 `onServiceConnected` 不会被调用（或收到 null）。
2. **同进程绑定**：
   - `onBind` 返回 `LocalBinder`（内部类，持有 Service 引用）；
   - 客户端 `onServiceConnected` 拿到的是**真实实例**，可以直接调方法，**零 IPC 开销**；
   - `onServiceConnected` 在 `bindService()` 后**立即**回调（同一个 Looper 消息循环内）。
3. **跨进程绑定**：
   - `onBind` 返回 AIDL 生成的 `Stub`（本质是 `Binder` 子类）；
   - 客户端拿到的是**代理对象**（`Proxy`），每次调用都经 `transact()` 跨进程；
   - `onServiceConnected` 可能**延迟**（目标进程要冷启动）。
4. **一个 Service 可以同时支持两种**：`LocalBinder` 内部暴露具体方法供同进程用，`Stub` 供跨进程用，两者可以指向同一实现。
5. **面试话术**："`onBind` 返回的是'**能力的入口**'。同进程返回'**实例本身**'，跨进程返回'**跨进程的接口代理**'。这就是为什么同进程能 `as MyBinder` 强转，跨进程不能。"

---

**读者问：Service 什么时候会被系统杀死？和 Activity 的被杀有什么不同？**

**AI答：**

1. **Service 没有独立的"被回收"概念**——系统杀的是**整个进程**。Service 随进程生死。
2. **进程的 adj 优先级**（从高到低）：
   - `foreground` > `visible` > `service`（有前台服务） > `background` > `cached`（Service 所在的空栈进程）。
3. **有前台服务的进程**：`adj = foreground_service`，**几乎不会被杀**，除非极端内存压力或用户手动停止。
4. **普通后台 Service 所在的进程**：`adj = background`，**优先被回收**。
5. **只有 started Service、没有 Activity 的进程**：`adj = cached`，**第一个被杀**。
6. **面试话术**："Service 不能豁免进程被杀，只能'**提高优先级**'。所以'Service 不会被杀'是个误解。"
7. **常见坑**：在 Service 里用 `AlarmManager.setRepeating` 做轮询保活 → 厂商 ROM 会清理；应该用 `WorkManager` 的 `PeriodicWorkRequest`。

## 关联笔记

- [[../README]]：第 2 部分总览
- [[Activity]]：Service 与 Activity 的生命周期对照
- [[BroadcastReceiver]]：Service 与广播都是"事件驱动"的组件
- [[../组件间通信/Binder机制]]：Bound Service 跨进程的底层
- [[../组件间通信/AIDL]]：跨进程接口定义
- [[../组件间通信/Intent]]：Service 的启动方式
- [[../Application与Context]]：Service 的 Context 来源
- [[../../06-系统原理与性能优化/性能优化专项/电量优化]]：WorkManager 与 JobScheduler 的详细用法

## 参考资料

- Android 官方文档 · Service 概览：https://developer.android.com/guide/components/services
- Android 官方文档 · 前台服务：https://developer.android.com/develop/background-work/services/fgs
- Android 官方文档 · 前台服务类型变更（Android 14）：https://developer.android.com/about/versions/14/changes/fgs-types-required
- Android 官方文档 · WorkManager：https://developer.android.com/topic/libraries/architecture/workmanager
- Android 官方文档 · 后台执行限制：https://developer.android.com/guide/components/background-starts
