---
tags: [android]
part: 第2部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[./README]], [[./四大组件/Activity]], [[./四大组件/Service]], [[./四大组件/ContentProvider]]"
---

# Application与Context

## 一句话定义

**Application** 是 Android 中**代表"应用进程本身"**的组件（继承 `ContextWrapper`），一个进程有且仅有一个实例，它的生命周期等于整个进程的存续期。**Context** 则是 Android 中**"与系统环境交互的能力集合"**——所有能拿到系统服务、资源、环境配置的类都继承自它。

面试开场白：

> Context 是 Android 最"无处不在"又最"容易被误用"的东西。它的核心价值是**能力抽象**（谁需要什么能力，就用哪种 Context），核心风险是**生命周期错配导致的内存泄漏**。面试里问 Context，考的其实是三件事：① **Context 家族的能力差异**；② **生命周期与泄漏**；③ **Application 的初始化时机与陷阱**。

## 核心原理

### 1. Application 是什么

**核心事实**：

- 一个 App 进程有且仅有一个 `Application` 实例。
- 它**继承 `ContextWrapper`**（本身不实现任何能力，只是"装饰"真正的 `ContextImpl`）。
- 它是**进程内第一个被创建的用户类实例**（`attachBaseContext` 阶段）。
- 它的生命周期 = 进程的生命周期。

**生命周期回调序列**：

```text
  Application 实例创建
        │
        ▼
  attachBaseContext(Context base)          ← ① 最早。此时 base Context 可用，
        │                                      但还没有 Application 自己的字段
        │                                      （官方建议在此做多进程判断、ContentProvider 初始化）
        ▼
  onCreate()                                ← ② 常规初始化：日志、崩溃上报、第三方 SDK
        │
        ▼
  进程运行中...
        │
        ▼
  onTerminate()                            ← ③ 【几乎不会被调用】
```

> **`onTerminate()` 在真实设备上基本不会被调用**——它只在**模拟器**（或"进程被系统正常结束且 Android 运行时决定通知"）下有机会触发。**绝不能**把资源释放逻辑放在这里。

**多进程场景**：如果你的 App 配置了多个进程（如 `android:process=":push"`），**每个进程都会创建一个独立的 Application 实例**。这会导致：
- 第三方 SDK 被初始化多次；
- 单例在主进程和子进程里是**不同的对象**（内存隔离）；
- 需要在 `attachBaseContext` 里判断当前进程，避免重复初始化。

```kotlin
class MyApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 多进程判断：只在主进程做重初始化
        val processName = getProcessName()
        Log.i(TAG, "attachBaseContext in process: $processName")
    }

    override fun onCreate() {
        super.onCreate()
        // 崩溃上报、第三方 SDK 初始化
        CrashReporter.init(this)
    }

    companion object {
        private const val TAG = "MyApplication"

        fun getProcessName(): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return Application.getProcessName()
            }
            // 兼容旧版本
            val pid = Process.myPid()
            val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            return activityManager.runningAppProcesses
                ?.firstOrNull { it.pid == pid }?.processName ?: ""
        }
    }
}
```

### 2. ContentProvider 初始化早于 Application.onCreate 的原因

**为什么 ContentProvider 会"抢跑"**：

1. **ContentProvider 是"进程级数据服务"**——它必须**先于任何界面组件**可用，因为别的进程可能在你还没显示任何界面前就要查你的数据。
2. **框架的初始化顺序**：系统在 `ActivityThread.handleBindApplication()` 里，依次完成
   ```text
   attachBaseContext → Application 构造 → Application.onCreate → installContentProviders → 注册组件
   ```
   （不同 Android 版本在 `Application.onCreate` 与 Provider 初始化上的先后略有差异，但**Provider 一定早于 Activity/Service**。）
3. **这个"最早钩子"被广泛利用**：
   - **热修复框架**（Tinker / Sophix / 阿里 HotFix）：把 SDK 的 Provider 放在**用户 Provider 列表的最前面**，从而在业务代码执行前完成补丁加载。
   - **崩溃收集 / 性能监控 SDK**：用 Provider 做"无侵入式初始化"。
   - **多进程初始化**：在 Provider 里判断进程、只初始化一次。

**实践含义**：

| 结论 | 影响 |
| --- | --- |
| Provider 的 `onCreate` **早于 Activity/Service** | 不能依赖界面组件已初始化 |
| Provider 的 `onCreate` 里**不适合做耗时初始化** | 会拖慢所有用到该 Provider 的调用方 |
| Provider 的 `onCreate` 里**不适合做复杂逻辑** | 崩溃会导致整个 App 起不来，且报错点不明确 |

### 3. Context 家族

```text
                    android.content.Context（抽象类）
                    声明了所有能力的方法
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
  ContextWrapper        ContextImpl            （抽象层）
  （装饰者，持有 mBase）  （真正的实现）
        │
        ├── Application
        ├── Service
        └── Activity
```

**三种典型的 Context 实例**：

| Context | 获取方式 | 生命周期 | 特点 |
| --- | --- | --- | --- |
| **Application Context** | `context.applicationContext` / `getApplicationContext()` | **进程级**（最长） | 不会泄漏；但**不能**用它做 UI 相关的操作（弹 Dialog、显示 Toast、启动 Activity 需要 FLAG_NEW_TASK） |
| **Activity Context** | `activity` / `this` | Activity 生命周期 | 能弹 Dialog、拿 WindowManager、显示 Toast；但**传给长生命周期对象会泄漏** |
| **Service Context** | `service` | Service 生命周期 | 同 Activity 但少 UI 能力 |

**ContextWrapper 的设计（装饰者模式）**：

```java
public class ContextWrapper extends Context {
    private final Context mBase;   // 被装饰的真身

    @Override
    public Resources getResources() {
        return mBase.getResources();
    }
    // 所有方法都转发给 mBase

    protected void attachBaseContext(Context base) {
        mBase = base;
    }
}
```

**装饰者模式的价值**：`Context` 是抽象类，`ContextImpl` 是"**裸实现**"，`ContextWrapper` 可以**动态替换 mBase**。这让 Android 能在**不修改系统代码**的前提下定制 Context 行为——这正是以下技术的原理：

- **ContextWrapper 定制**（Hook 系统 API、Hook 第三方 SDK 的初始化时机）；
- **AndroidManifest 里的 `android:name` 换成自己的 Application 继承类**；
- **多进程共享数据**（用 `attachBaseContext` 把 mBase 换成一个"跨进程共享数据的 Context"）。

**Context 的能力清单（按使用频率）**：

| 能力 | 说明 | 典型 API |
| --- | --- | --- |
| **获取系统服务** | `getSystemService(Class)` | `ContextCompat.getSystemService(this, WindowManager::class.java)` |
| **资源访问** | 布局、字符串、主题、Drawable | `getResources()`, `getString()`, `getDrawable()` |
| **启动组件** | 启动 Activity/Service/发广播 | `startActivity()`, `startService()`, `sendBroadcast()` |
| **文件与存储** | 内部存储、SharedPreferences、数据库 | `openFileOutput()`, `getSharedPreferences()` |
| **权限检查** | 运行时权限 | `checkSelfPermission()`, `requestPermissions()` |
| **UI 相关** | 弹窗、Toast、输入法、窗口 | `startActivityForResult()`, `getSystemService(WindowManager)` |
| **环境信息** | 屏幕密度、语言、方向、配置 | `getResources().getConfiguration()` |
| **包信息** | 版本号、签名、已装应用 | `getPackageManager()`, `getPackageName()` |
| **进程信息** | 进程名、PID | `getProcessName()`, `getCacheDir()` |

### 4. getApplicationContext vs Context 的选择

**决策树**：

```text
  你拿到的 Context 要用多久？
       │
       ├── 只在当前方法/当前回调里用（立即用完就丢）
       │      → 用手头的 Context（Activity/Service 的）
       │
       └── 要存起来（字段、单例、回调、注册到系统）
              │
              ├── 只是为了拿资源/字符串/系统服务
              │      → applicationContext ✅
              │
              └── 需要 UI 能力（弹 Dialog、显示 Toast、加窗口）
                     │
                     ├── 对象生命周期 ≤ Activity
                     │      → 可以用 Activity Context（但要确保及时解绑）
                     │
                     └── 对象生命周期 > Activity
                            → ⚠️ 绝对不行！
                              用 applicationContext + 回调/事件流把 UI 动作抛回 View 层
```

**黄金法则**：

> **凡是要"存起来"的 Context，默认用 `applicationContext`；凡是要"立刻用完"的 Context，用哪个都行。**

**为什么 applicationContext 不会泄漏**：

- 它是**进程级单例**，生命周期与进程一致；
- 它内部持有的 `ContextImpl` 引用的是**进程级资源**，不含任何 Activity/View 的强引用；
- 把它存进单例、静态变量、Handler、回调，**都不会导致 Activity 泄漏**。

**但要注意**：`applicationContext` **不等于万能**：

| 操作 | applicationContext 能做吗 |
| --- | --- |
| `getResources()` / `getString()` | ✅ |
| `getSystemService()` | ✅ |
| `startService()` / `sendBroadcast()` | ✅ |
| `startActivity()` | ⚠️ 需要 `FLAG_ACTIVITY_NEW_TASK`（否则抛 `AndroidRuntimeException: Calling startActivity() from outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag`） |
| `showDialog()` | ❌ 没有 Window token，抛异常 |
| `startActivityForResult()` | ❌ 同上 |
| `getSystemService(WindowManager)` 后加 View | ❌ 抛 `BadTokenException` |

### 5. 内存泄漏的典型场景

**泄漏的本质**：

> **一个生命周期更短的对象，被一个生命周期更长的对象强引用，导致短生命周期对象无法被回收。**

**典型场景一：单例持有 Activity Context**

```kotlin
// ❌ 泄漏：Singleton 活到进程结束，Activity 被持有
object UserManager {
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context   // 传进来的可能是 Activity！
    }

    fun showUserInfo() {
        Toast.makeText(context, "user", Toast.LENGTH_SHORT).show()
    }
}
```

**修复**：

```kotlin
object UserManager {
    // ✅ 用 applicationContext
    fun init(context: Context) {
        this.context = context.applicationContext
    }
    private var context: Context? = null
}
```

**典型场景二：Handler / 延迟消息持有 Activity**

```kotlin
// ❌ 泄漏：延迟 60 秒的 Runnable 持有 Activity
class MyActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    override fun onCreate(savedInstanceState: Bundle?) {
        handler.postDelayed({
            toast("hi")   // 匿名内部类隐式持有 outer this
        }, 60_000)
    }
}
```

**修复**：

```kotlin
// ✅ 方案 1：onDestroy 移除回调
override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    super.onDestroy()
}

// ✅ 方案 2：用 lambda + WeakReference
// ✅ 方案 3：用 lifecycleScope + lifecycle-aware 收集
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        delay(60_000)
        toast("hi")
    }
}
```

**典型场景三：内部类 / 匿名类隐式持有外部类**

```kotlin
// ❌ 泄漏：非静态内部类隐式持有 Activity
class MyActivity : AppCompatActivity() {
    private inner class DownloadCallback : Callback {
        override fun onResult() {
            // 这里能访问 Activity 的成员 → 隐式持有 Activity
        }
    }
}
```

**修复**：改成 `static`（Kotlin 里的 `object` 或顶层函数），或用 `WeakReference`。

**典型场景四：注册了系统回调但未注销**

```kotlin
// ❌ 泄漏
override fun onCreate(...) {
    registerReceiver(receiver, filter)
    registerContentObserver(observer, true, null)
    sensorManager.registerListener(this, sensor)
}
// 没有 onDestroy 里 unregister
```

**修复**：**成对注册/注销**，并在 `onDestroy` 中清理。

**典型场景五：静态变量持有 View**

```kotlin
// ❌ 泄漏
companion object {
    var currentView: View? = null   // 持有 Activity 的 View 树
}
```

**典型场景六：Fragment 持有 Activity 的 View**

```kotlin
// ❌ 泄漏：onDestroyView 后 binding 仍持有已销毁的 View 树
class MyFragment : Fragment() {
    private var _binding: FragmentMyBinding? = null
    override fun onDestroyView() {
        super.onDestroyView()
        // 忘记 _binding = null
    }
}
```

**修复**：

```kotlin
override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
}
```

**检测手段**：

- **LeakCanary**：自动检测 Activity/Fragment 的泄漏并给出引用链；
- **Android Studio Memory Profiler** → "**Leak Canaries**"；
- **StrictMode**：`StrictMode.setVmPolicy` 监控 VM 里的慢操作；
- **Choreographer 帧率 + `dumpsys meminfo`** 交叉验证。

### 6. 其他 Context 相关的知识点

**① `Context.getActivity()` 扩展（Kotlin 扩展函数）**

```kotlin
// Activity 本身就有 getActivity()（Fragment 中）
// 扩展一个通用的从 Context 找 Activity：
fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
```

> ⚠️ **这个扩展有风险**：它依赖 Context 链上的 Activity，**在 Service/Receiver 中可能返回 null**，而且如果缓存了返回值仍会泄漏。**现代做法是不用这个扩展，而是用回调/事件流**。

**② Kotlin 扩展函数让 Context 更好用**

```kotlin
// 颜色转 dp/sp
val Int.dp: Float
    get() = this * Resources.getSystem().displayMetrics.density

val Float.sp: Float
    get() = this * Resources.getSystem().displayMetrics.scaledDensity

// 扩展 Toast，避免每次都要传 context
fun Context.toast(msg: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, msg, duration).show()
}
```

**③ `registerReceiver` 的 Context 选择**

```kotlin
// Activity 里注册 → 用 this（随 Activity 生命周期，onDestroy 时必须注销）
registerReceiver(receiver, filter)

// Application 里注册 → 用 applicationContext（进程级）
(applicationContext as Application).registerReceiver(receiver, filter)
```

**④ Android 12+ 的 Context 强化**

- **Context.getSystemService(Class)**：`ContextCompat.getSystemService(context, WindowManager::class.java)`（AndroidX 版本，兼容旧版本）；
- **`Context.getDisplay()` / `Context.getDisplayOrDefault()`**（API 30+）：替代已废弃的 `getWindowManager().getDefaultDisplay()`；
- **PackageManager 的 `getPackageInfo` 新签名**（API 33+ 需要 `PackageInfoFlags`）；
- **`Context.createDeviceProtectedStorageContext()`**：拿到**设备加密（Direct Boot）**下的 Context，用于开机阶段（`BOOT_COMPLETED` 之前）访问加密存储。

**⑤ `Context` 与 `Intent` 的关系**

`Context.startActivity(intent)` 内部会：
- 用 `intent.resolveActivityIfNeeded()` 处理 `FLAG_ACTIVITY_NEW_TASK`；
- 通过 `ActivityTaskManagerService`（一个 Binder 调用）把 Activity 启动请求发给系统。

**⑥ `Application` 中不推荐做的事**：

| 不推荐 | 原因 | 替代 |
| --- | --- | --- |
| 在 `onCreate` 里做大量耗时初始化 | 拖慢冷启动（**ANR 风险**） | 延迟到首屏之后；或用 `androidx.startup` + `InitializationProvider` |
| 在 `onCreate` 里初始化需要 Activity 的 SDK | Activity 还不存在 | 用 `ContentProvider` 的 `onCreate` 或首屏 Activity |
| 在 `onCreate` 里读大文件/解析大 JSON | 同上 | 异步 + 缓存 |
| 在 `onTerminate` 里释放资源 | **几乎不会被调用** | `onDestroy` 不存在；靠 GC + 进程结束 |

### 7. Android 12+ 的 SplashScreen 与 Application 冷启动优化

**冷启动时序（简版）**：

```text
  App 进程创建
     → Application.attachBaseContext
     → Application.onCreate
     → ContentProvider.onCreate（多个）
     → Activity.onCreate
     → 首屏渲染（Choreographer 绘制第一帧）
```

**优化点**：
- **ContentProvider 数量**：每个 Provider 都是一次初始化，`InitializationProvider`（AndroidX Startup）默认会创建多个初始化器；
- **Application.onCreate 里不要同步初始化重型 SDK**；
- **`androidx.startup` 的 `AppInitializer`**：把初始化做成 `Initializer`，可声明依赖关系与延迟初始化。

## 代码示例

### 1. 标准的 Application 实现

```kotlin
class App : Application() {

    /** 全局配置容器 */
    lateinit var config: AppConfig
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 1) 多进程判断：在最早期就知道自己在哪个进程
        val processName = currentProcessName()
        isMainProcess = processName == packageName
        // 2) 可以在这里做多进程数据共享（把 mBase 替换为定制 Context）
    }

    override fun onCreate() {
        super.onCreate()
        config = AppConfig.load(this)

        // 3) 轻量 SDK：日志、崩溃上报
        CrashReporter.init(this, config)

        // 4) 重量 SDK：延迟到首屏之后
        AppCompatDelegate.setDefaultNightMode(config.nightMode)

        // 5) 注册进程级生命周期回调
        registerActivityLifecycleCallbacks(AppLifecycleCallbacks)
    }

    private var isMainProcess: Boolean = true

    companion object {
        fun currentProcessName(): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return Application.getProcessName()
            }
            val pid = Process.myPid()
            val am = Application.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            return am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: ""
        }
    }
}
```

Manifest：

```xml
<application
    android:name=".App"
    android:allowBackup="false"
    android:supportsRtl="true"
    android:theme="@style/Theme.App">
    ...
</application>
```

### 2. 自定义 Application 承载全局状态

```kotlin
class App : Application() {

    /** 用 StateFlow 暴露全局状态，配合生命周期感知收集 */
    private val _loginState = MutableStateFlow<LoginState>(LoginState.LoggedOut)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private val _userCache = MutableStateFlow<User?>(null)
    val userCache: StateFlow<User?> = _userCache.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        // 从本地恢复上次登录状态（轻量）
        restoreSession()
    }

    fun login(user: User) {
        _user.value = user
        _loginState.value = LoginState.LoggedIn(user)
        persistSession(user)
    }

    fun logout() {
        _user.value = null
        _loginState.value = LoginState.LoggedOut
        clearSession()
    }

    private fun restoreSession() {
        // 用 DataStore/SharedPreferences，读取很快
        val prefs = getSharedPreferences("session", MODE_PRIVATE)
        val id = prefs.getLong("user_id", -1L)
        if (id > 0) {
            _user.value = User(id, prefs.getString("name", "").orEmpty())
            _loginState.value = LoginState.LoggedIn(_user.value!!)
        }
    }

    private fun persistSession(user: User) {
        getSharedPreferences("session", MODE_PRIVATE).edit()
            .putLong("user_id", user.id)
            .putString("name", user.name)
            .apply()
    }

    private fun clearSession() {
        getSharedPreferences("session", MODE_PRIVATE).edit().clear().apply()
    }
}
```

### 3. Hilt 中获取 Application Context

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationContext
    fun provideApplicationContext(@ApplicationContext app: Application): Context = app

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
}
```

**在 Repository / ViewModel 中使用**：

```kotlin
@Singleton
class UserRepository @Inject constructor(
    @ApplicationContext private val context: Context,   // ✅ 不会泄漏
    private val api: UserApi
) {
    fun cachedName(): String =
        context.getSharedPreferences("user", Context.MODE_PRIVATE)
            .getString("name", "") ?: ""
}
```

### 4. Context 的正确使用规范

```kotlin
class DataRepository @Inject constructor(
    @ApplicationContext private val appContext: Context
) {

    /**
     * ✅ 正确：只在方法内部短暂使用 Activity Context
     *    —— 但更好的做法是根本不接收 Activity Context
     */
    fun showErrorWithActivity(activity: Activity, message: String) {
        // 不推荐：把 activity 存起来（虽然这里没存，但参数传递仍有风险）
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * ✅ 推荐：Repository 不感知 UI，
     *    通过 Flow 把错误抛给 ViewModel，由 View 层决定如何展示
     */
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    suspend fun loadUser(id: Long) {
        runCatching { api.fetchUser(id) }
            .onFailure { _errors.tryEmit(it.message ?: "加载失败") }
    }

    /**
     * ✅ 需要启动 Activity 时，用 applicationContext + NEW_TASK
     */
    fun openSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", appContext.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)   // ★ 必须
        }
        appContext.startActivity(intent)
    }
}
```

### 5. 安全的 Context 扩展工具

```kotlin
object ContextExt {

    /** 从 Context 链上找 Activity（谨慎使用，可能为 null） */
    fun Context.findActivity(): Activity? {
        var context: Context? = this
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }

    /** dp → px */
    val Int.dp: Int
        get() = (this * Resources.getSystem().displayMetrics.density).toInt()

    /** sp → px */
    val Float.sp: Float
        get() = this * Resources.getSystem().displayMetrics.scaledDensity

    /** 安全的 Toast（用 applicationContext，避免泄漏） */
    fun Context.safeToast(msg: CharSequence) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    /** 安全地获取系统服务 */
    inline fun <reified T : Any> Context.getSystemServiceCompat(): T? {
        return ContextCompat.getSystemService(this, T::class.java)
    }
}
```

### 6. 进程级生命周期回调

```kotlin
object AppLifecycleCallbacks : Application.ActivityLifecycleCallbacks {

    private var startedCount = 0

    /** 前后台切换监听（现代替代 onActivityStarted/stopped 计数法） */
    val isForeground = MutableStateFlow(false)

    override fun onActivityStarted(activity: Activity) {
        if (startedCount == 0) isForeground.value = true
        startedCount++
    }

    override fun onActivityStopped(activity: Activity) {
        startedCount--
        if (startedCount <= 0) {
            startedCount = 0
            isForeground.value = false
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
```

### 7. 多进程判定的正确写法

```kotlin
class App : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        // API 28+ 的标准做法
        val pid = Process.myPid()
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
                .runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
                ?: ""
        }

        isMainProcess = processName == packageName
        Log.i(TAG, "attachBaseContext: process=$processName, main=$isMainProcess")
    }

    override fun onCreate() {
        super.onCreate()
        // ✅ 只在主进程初始化重型 SDK，避免子进程重复初始化
        if (isMainProcess) {
            HeavySdk.init(this)
        }
    }

    companion object {
        private const val TAG = "App"
        lateinit var isMainProcess: Boolean
            private set
    }
}
```

## 常见问题 / 面试要点

**读者问：Application 是什么？它的生命周期是怎样的？`onTerminate()` 什么时候调用？**

**AI答：**

1. **Application 是什么**：Android 中**代表应用进程本身**的组件，继承 `ContextWrapper`，**一个进程一个实例**，负责持有**进程级共享状态**和**初始化全局 SDK**。
2. **生命周期**：
   ```text
   进程启动 → attachBaseContext() → Application 构造 → onCreate() → ... → 进程结束
   ```
   - `attachBaseContext(base)`：**最早**回调，`base` 是框架提供的 `ContextImpl`。**官方推荐**：多进程判断、多进程数据共享（重写 `mBase`）、第三方 SDK 的最早期 Hook 都放这里。
   - `onCreate()`：**常规初始化**位置（日志、崩溃上报、第三方 SDK）。
   - `onTerminate()`：**在真机上几乎不会被调用**（只有模拟器或"进程被正常结束"的特殊场景）。**不要在这里释放资源**。
3. **多进程注意**：每个进程都会创建**独立的 Application 实例**。因此单例在多进程下**不共享**，SDK 会被重复初始化 → 必须在 `attachBaseContext` 里判断进程名。
4. **面试话术**："Application 的生命周期就是**进程的生命周期**。它的三个回调各有定位：`attachBaseContext` 做'**最早期的事**'（多进程判断、Context 替换），`onCreate` 做'**常规初始化**'，`onTerminate` 做'**几乎不做事**'——因为真机不调它。"
5. **常见坑**：在 `onCreate` 里做耗时初始化（拉 SDK、读大文件、初始化网络），直接拖慢冷启动 500ms+，是 ANR 的常见诱因。

---

**读者问：为什么很多文章说 ContentProvider 的初始化比 Application.onCreate 还早？到底谁先谁后？**

**AI答：**

1. **先说结论**：**ContentProvider 一定早于 Activity / Service / BroadcastReceiver**，这是没有争议的。至于与 `Application.onCreate()` 的精确先后，**官方文档与 AOSP 实现存在表述差异**。
2. **AOSP 实现的顺序**（`ActivityThread.handleBindApplication`）：
   ```text
   attachBaseContext()
     → Application 构造
     → callApplicationOnCreate()   // Application.onCreate()
     → installContentProviders()   // ContentProvider.onCreate()
     → Application 的 onCreate 之后的注册流程
   ```
3. **官方文档的说法**：《Application 生命周期》文档描述为"**ContentProvider 的 onCreate 在 Application.onCreate 之后**"，与源码一致。流传的"Provider 更早"通常指的是"**早于所有界面组件**"。
4. **面试时的稳妥话术**：
   > "ContentProvider 的初始化**非常早**，早于所有 Activity、Service 等界面组件。关于它与 `Application.onCreate()` 的严格先后，官方文档与不同版本实现有差异，我在工程上**不会依赖这个顺序**——我依赖的确定事实是'**它一定早于 Activity**'。"
5. **但"早初始化"这件事本身的价值是真实的**：热修复框架（Tinker/Sophix）把 SDK 的 Provider 排在**用户 Provider 列表最前面**，从而抢在业务代码之前完成补丁加载。**这是工程上被广泛利用的事实**。
6. **工程结论（重要）**：
   - Provider 的 `onCreate` 里**不能做耗时初始化**（拖慢所有调用方）；
   - Provider 的 `onCreate` 里**不能依赖 Activity/Service 已初始化**；
   - Provider 崩溃会导致**整个 App 起不来**（用户看到"应用无响应"），所以这里的代码必须**极其保守**。

---

**读者问：Context 家族有哪些？`getApplicationContext()` 和 Activity 的 Context 有什么区别？**

**AI答：**

1. **Context 家族**：
   - `Context`（抽象类）：**声明所有能力**。
   - `ContextImpl`：**真正的实现**（内部类，不直接使用）。
   - `ContextWrapper`（装饰者）：持有 `mBase` 并**转发所有调用**。`Application`、`Service`、`Activity` 都继承它。
   - `Activity` / `Service` / `Application`：三种实际的 Context 实例。
2. **三者的核心差异**：

   | | Application Context | Activity Context | Service Context |
   | --- | --- | --- | --- |
   | 生命周期 | **进程级** | Activity 级 | Service 级 |
   | 获取 | `getApplicationContext()` | `this`（在 Activity 内） | `this`（在 Service 内） |
   | 弹 Dialog | ❌ 无 Window token | ✅ | ❌（无 UI 容器） |
   | `startActivity` | ⚠️ 需 `FLAG_ACTIVITY_NEW_TASK` | ✅ | ⚠️ 需 NEW_TASK |
   | 传给长生命周期对象 | ✅ **安全** | ❌ **会泄漏** | ⚠️ 同理 |

3. **装饰者模式的意义**：`ContextWrapper` 的 `mBase` **可以被替换**——这是"**定制 Context**"技术的原理（Hook SDK、注入跨进程共享数据、Context 劫持）。
4. **面试话术**："Context 家族的设计是'**装饰者模式 + 能力分层**'：`Context` 定义能力，`ContextWrapper` 负责转发并允许替换，`ContextImpl` 是最终实现。三种实例的差异**本质是生命周期和 UI 能力**的差异。"
5. **常见坑**：
   - 用 `applicationContext` 弹 Dialog → `BadTokenException` / `AndroidRuntimeException`；
   - 用 Activity Context 启动 Activity 但没加 `NEW_TASK` → 抛异常（applicationContext 场景）。

---

**读者问：为什么长生命周期的对象不能持有 Activity Context？有哪些典型的内存泄漏场景？**

**AI答：**

1. **本质**：**生命周期更短的对象被生命周期更长的对象强引用** → 短生命周期对象无法回收。Activity 被持有 → **Activity + 整个 View 树 + DecorView + Window** 都无法回收（一个普通页面的 View 树轻松几 MB）。
2. **六大典型场景**：
   - **① 单例持有 Activity Context**（最经典）→ 用 `applicationContext`。
   - **② Handler 延迟消息**：`postDelayed` 的 lambda 隐式持有 Activity → `onDestroy` 里 `removeCallbacksAndMessages(null)`，或改用 `lifecycleScope`。
   - **③ 内部类/匿名类**：非静态内部类隐式持有外部类 → 改成 `object`/静态/顶层函数。
   - **④ 注册未注销**：`registerReceiver`、`registerContentObserver`、`registerListener`、`LocationManager` → **成对注册注销**。
   - **⑤ 静态变量持有 View** → 改用 `WeakReference` 或干脆不缓存。
   - **⑥ Fragment 的 binding 未在 `onDestroyView` 置空** → `_binding = null`。
3. **面试话术**："泄漏的本质是'**长短生命周期错配**'。解法有两条路：① **缩短持有者寿命**（改成弱引用、及时注销）；② **换成等长或更短**（用 applicationContext）。**首选第二条**——不要用弱引用掩盖设计问题。"
4. **补充加分点**：
   - `applicationContext` 本身**不会泄漏**，因为它是进程级单例，且持有的 `ContextImpl` 不含 Activity 引用；
   - 用 **LeakCanary** 检测，它会给出完整的**引用链**，比猜要高效；
   - **`WeakReference` 不是万能的**——GC 时机不可控，弱引用可能在真正需要时被回收，反而导致逻辑错误。
5. **根本性方案（现代架构）**：
   - **Repository / ViewModel 里绝不放 Activity Context**，只放 `@ApplicationContext`；
   - **UI 事件（Toast、跳转、Dialog）从 ViewModel 用 `SharedFlow` 抛给 View 层执行**；
   - 这样"**数据层不感知 UI**"，从架构上消灭泄漏的可能。

---

**读者问：怎么检测 Context 相关的内存泄漏？有什么工具？**

**AI答：**

1. **LeakCanary（首选）**：
   - 自动检测 Activity/Fragment/Room/View 等对象的泄漏；
   - **保留堆快照（heap dump）**，用 **LeakCanary 分析器**给出**完整的引用链**（谁持有 → 通过什么路径）；
   - 支持 `AppWatcher` 自定义监控任意对象；
   - 只需要在 debug 依赖里引入，release 打包时自动移除。
   ```kotlin
   // debugImplementation 时自动生效；自定义监控任意对象
   class MyApplication : Application() {
       override fun onCreate() {
           super.onCreate()
           AppWatcher.manualWatcher = object : AppWatcher {
               override fun onAppCreate(application: Application) { }
               override fun onActivityDestroyed(activity: Activity) {
                   LeakCanary.watchForLeaks(activity)
               }
           }
       }
   }
   ```
2. **Android Studio Memory Profiler**：
   - "**Leak Canaries**" 面板能给出类似 LeakCanary 的引用链；
   - "**Heap Dump**" 手动分析；
   - "**Allocation Tracking**" 看对象分配。
3. **`dumpsys meminfo`**：看进程内存占用与组件统计（活动/缓存的 Activity 数），快速判断是否有多个 Activity 累积。
4. **StrictMode**：开发期监控主线程上的磁盘读写、网络、耗时操作。
   ```kotlin
   if (BuildConfig.DEBUG) {
       StrictMode.setThreadPolicy(
           StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
       )
   }
   ```
5. **面试话术**："泄漏排查的黄金流程是：'**LeakCanary 报警 → 看引用链 → 找到持有者 → 改成 applicationContext 或及时注销**'。不要靠猜，引用链是唯一可靠的依据。"
6. **补充**：`ActivityThread` 的 `mActivities` 在 Activity 正常销毁后会被移除，但如果被引用链持有，`dumpsys activity` 里能观察到异常多的 Activity 实例。

---

**读者问：Application 里可以做哪些初始化？有什么坑？**

**AI答：**

1. **可以做的**：
   - **日志与崩溃上报**（轻量）；
   - **全局配置加载**（SharedPreferences / DataStore 读取很快）；
   - **依赖注入容器初始化**（Hilt 的 `HiltAndroidApp` 注解，它就是 Application 的子类）；
   - **`registerActivityLifecycleCallbacks`** 监听前后台切换；
   - **多进程判断**（在 `attachBaseContext`）。
2. **不推荐的**：
   - **耗时初始化**（拉大型 SDK、解析大 JSON、读大文件、建数据库）→ 拖慢冷启动；
   - **需要 Activity 的 SDK**（此时还没有 Activity）；
   - **`onTerminate` 里做清理**（真机不调用）。
3. **冷启动与 ANR 的关系**：
   - Android 启动流程里有 **5 秒的 ANR 看门狗**（`ActivityManagerService` 的 `mServiceWaitTimeout` 相关）；
   - `Application.onCreate` + 第一个 Activity 的 `onCreate` + 首帧渲染**共享这个时间预算**；
   - 所以"**Application 里做重活**"是 ANR 的常见原因之一。
4. **优化手段**：
   - **延迟初始化**：`HomeActivity` 的 `onCreate` 之后，或用 `lifecycleScope.launch { }` + `Dispatchers.Default`；
   - **`androidx.startup` 的 `InitializationProvider`**：把初始化拆成可配置的 `Initializer`，支持依赖关系，且**不需要写 Application 代码**（但它也是 Provider，初始化时机同样很早）；
   - **把非必要初始化移到子线程**：注意线程安全与依赖顺序。
5. **面试话术**："Application 初始化是一条**冷启动的预算线**——它的耗时直接加在首屏时间上。原则是'**必须的放进来，能延后的挪出去，能并行的并行**'。"
6. **加分点**：可以提到 **`android:largeHeap`** 不是解决方案（只是把 OOM 推迟），以及**启动优化的度量**应看 **TTID（Time to Initial Display）** 而不是 `onCreate` 返回。

---

**读者问：`Context` 还能做什么？除了资源访问和启动组件之外？**

**AI答：**

1. **获取系统服务**：`ContextCompat.getSystemService(context, XxxManager::class.java)` —— 拿到 `WindowManager`、`InputMethodManager`、`NotificationManager`、`LocationManager`、`AlarmManager`、`ActivityManager` 等。
2. **权限相关**：`checkSelfPermission()`、`shouldShowRequestPermissionRationale()`、`requestPermissions()`（`Activity` 专属）、`grantUriPermission()`。
3. **包信息**：`getPackageManager().getPackageInfo()` 拿版本号、签名、已安装列表；`getPackageName()`。
4. **环境信息**：`getResources().getConfiguration()`（语言、方向、dark mode）、`getResources().getDisplayMetrics()`（密度）、`getCacheDir()` / `getFilesDir()` / `getExternalFilesDir()`（存储）。
5. **UI 相关（需 Activity Context 或 Window token）**：`startActivityForResult()`、`showDialog()`、`getSystemService(WindowManager)` 加 View、`InputMethodManager.show()`。
6. **跨进程数据**：`getContentResolver()`、`getPackageManager()`、`bindService()`。
7. **`Context.createDeviceProtectedStorageContext()`**（API 24+）：拿到**设备加密（Direct Boot）**下的 Context，用于开机阶段访问加密存储。
8. **`Context.createPackageContext()`**：拿到"另一个包的 Context"（可用于读取其他 App 的资源，`CONTEXT_IGNORE_SECURITY` 有风险）。
9. **`Context.getSystemService(Class)`**（API 23+）与 **`ContextCompat.getSystemService()`**：推荐后者，兼容旧版本且不用强转。
10. **面试话术**："Context 是'**系统环境的能力入口**'。它的方法可以分成四类：**资源与配置**、**组件与进程**、**系统服务**、**权限与存储**。实际开发里最常用的是'**系统服务**'和'**资源/配置**'两类。"
11. **加分点**：提到 `Context` 的 `getTheme()` 与 `applyOverrideConfiguration()` —— 可以在运行时**覆盖配置**（如强制深色模式），这是"**Context 定制**"的经典玩法。

## 关联笔记

- [[./README]]：第 2 部分总览
- [[./四大组件/Activity]]：Activity 是 Context 家族的一员，其生命周期决定了 Context 的可用范围
- [[./四大组件/Service]]：Service Context 的生命周期与能力边界
- [[./四大组件/ContentProvider]]：Provider 的初始化时机与 Context 来源
- [[./四大组件/BroadcastReceiver]]：动态注册 receiver 需要 Context
- [[./组件间通信/Intent]]：Context 是启动组件的入口
- [[../07-架构、工具与生态/依赖注入/Hilt]]：`@ApplicationContext` 注入的规范用法
- [[../07-架构、工具与生态/架构模式/MVVM]]：ViewModel 不持有 Activity Context 的架构原因
- [[../06-系统原理与性能优化/系统核心机制/内存管理]]：Context 泄漏与内存管理的深入

## 参考资料

- Android 官方文档 · Application 生命周期：https://developer.android.com/guide/components/application
- Android 官方文档 · Context：https://developer.android.com/reference/android/content/Context
- Android 官方文档 · 内存泄漏与 Context：https://developer.android.com/topic/performance/memory-overview
- LeakCanary 官方文档：https://square.github.io/leakcanary/
- 《Android 高级编程》第 4 版 · 第 3 章（应用与资源）
