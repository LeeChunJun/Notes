---
tags: [android]
part: 第2部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[../README]], [[Service]], [[../组件间通信/Intent]], [[../Application与Context]]"
---

# Activity

## 一句话定义

Activity 是 Android 四大组件中**唯一有"用户界面入口"的组件**，它代表**一个屏幕/一个页面**，由系统通过 `ActivityManagerService (AMS)` 管理生命周期与任务栈（Task）。它的本质是**一个窗口（Window）的宿主 + 一个生命周期状态机**：状态机由系统按"用户可见性"驱动，窗口由 `WindowManager` 交给 `ViewRootImpl` 渲染到 SurfaceFlinger。

面试开场白可以这样说：

> Activity 的核心职责有三件事——**承载 UI（通过 setContentView 挂 Window）**、**表达生命周期状态机（onCreate → onStart → onResume → onPause → onStop → onDestroy）**、**参与任务栈管理（决定"返回键回到哪一屏"）**。面试里绝大多数 Activity 相关问题，都可以归结为"生命周期回调语义 + 任务栈语义 + 配置变更后的重建"这三条主线。

## 核心原理

### 1. 生命周期全流程图

```text
                          ┌───────────────────────────────┐
   startActivity()  ───►   │          onCreate()          │  ① 创建：初始化对象、加载布局
                          │  setContentView / findViewById │
                          └───────────────┬───────────────┘
                                          ▼
                          ┌───────────────────────────────┐
                          │          onStart()            │  ② 启动：可见，但不可交互焦点
                          │  注册"可见才需要"的资源        │
                          └───────────────┬───────────────┘
                                          ▼
                          ┌───────────────────────────────┐
   用户点击/获得焦点 ──►   │         onResume()            │  ③ 恢复：可交互、最顶端
                          │  onNewIntent() 也在此刻        │
                          └───────────────┬───────────────┘
                                          │ 切后台 / 弹 Dialog(全屏)
                                          ▼
                          ┌───────────────────────────────┐
                          │          onPause()            │  ④ 暂停：完全不可见（但可能仍可见）
                          │  必须在这里释放相机等独占资源    │
                          └───────────────┬───────────────┘
                                          │ 完全不可见 / 被遮挡
                                          ▼
                          ┌───────────────────────────────┐
                          │          onStop()             │  ⑤ 停止：UI 线程不再刷新
                          │  Animator 在这里才真正停止     │
                          └───────────────┬───────────────┘
                                          │ 回到前台
                                          ▼
                          ┌───────────────────────────────┐
   onRestart()  ◄────────│          onStart()            │  从后台回前台必经 onRestart
                          └───────────────┬───────────────┘
                                          ▼
                          ┌───────────────────────────────┐
                          │         onResume()            │
                          └───────────────┬───────────────┘
                                          │ 进程被杀 / finish()
                                          ▼
                          ┌───────────────────────────────┐
                          │        onDestroy()            │  ⑥ 销毁：释放所有资源
                          └───────────────────────────────┘
```

**完整的回调序列（面试常背）**：

| 场景 | 回调顺序 |
| --- | --- |
| 首次启动 | `onCreate → onStart → onResume` |
| 返回键退出（未 finish 进程） | `onPause → onStop → onDestroy` |
| Home 键切后台 | `onPause → onStop`（Activity 还在栈里，**不销毁**） |
| 从后台切回 | `onRestart → onStart → onResume` |
| 打开另一个全屏 Activity | A: `onPause → onStop`；B: `onCreate → onStart → onResume` |
| 配置变更（旋转屏幕） | `onPause → onStop → onSaveInstanceState → onDestroy → onCreate → onStart → onRestoreInstanceState → onResume` |
| 进程被系统杀死后重建 | 同上，但 `savedInstanceState` 非空 |

### 2. 生命周期各阶段的语义与资源占用

| 回调 | 可见性 | 可交互 | 典型应做的事 | 绝不该做的事 |
| --- | --- | --- | --- | --- |
| `onCreate` | 不可见 | 否 | `setContentView`、初始化成员、注册只依赖实例的回调 | 申请独占资源（相机）、注册长期运行的 Handler 轮询 |
| `onStart` | 可见 | 否 | 注册"只需可见"的观察者（传感器、位置） | 启动动画（Animation 依赖可见性） |
| `onResume` | 可见 | 是 | 恢复交互、连接游戏/视频会话 | 重复注册（会与 onPause 不配对） |
| `onPause` | 可能可见 | 否 | **释放独占资源**（相机、录音）、保存编辑中的数据 | 释放普通网络请求（会白屏等待） |
| `onStop` | 不可见 | 否 | 取消动画、注销不需要的注册、持久化数据 | 释放 UI 相关资源（可能还要恢复） |
| `onDestroy` | 不可见 | 否 | 释放全部资源、解绑 Service/Binder | 依赖它一定会被调用（进程被杀时可能不调用） |

**内存与资源占用的三条规律**：

1. **进程内存随 Activity 栈增长**。每个 Activity 都有 View 树、Window、ViewRootImpl、DecorView，通常单页在 2MB～10MB 量级（取决于布局复杂度）。系统按 LRU 从最久不可见的 Activity 开始杀。
2. **`onPause` 时进程优先级最高但仍可能被杀**。从后台切回时进程可能已被杀，Activity 会被**重新创建**而不是恢复——这是"后台回来白屏/回到登录页"类 Bug 的根因。
3. **可见性不等于生命周期状态**。Android 只给"是否可见"的粗粒度信号，`onPause` 与 `onStop` 之间可能只是被一个半透明 Dialog 覆盖。所以**资源释放的粒度要选在 `onPause`，而不是 `onStop`**——宁可多释放一次，也不要独占资源被系统抢占（相机打开时切后台，系统会发通知强制释放）。

### 3. 启动模式（Launch Mode）与任务栈

Android 的任务栈（Task）本质是 **Activity 的栈**，一个 Task 归属一个"最近任务"（Recents）。启动模式决定了 `startActivity()` 时是**复用栈中已有实例**还是**新建实例**。

| 启动模式 | 同任务内已有同组件实例时 | 栈行为 | 关键回调 | 典型场景 |
| --- | --- | --- | --- | --- |
| `standard` | **总是新建**实例 | 压栈 | — | 普通页面（默认） |
| `singleTop` | 若在**栈顶**则复用，否则新建 | 栈顶复用 | 复用时走 `onNewIntent` | 通知跳转页（避免自己跳自己叠一堆） |
| `singleTask` | 全栈搜索，找到则**清空其上所有 Activity**并复用 | 复用并出栈 | 复用时走 `onNewIntent` | 登录页、Launcher 主界面（回退直接回桌面） |
| `singleInstance` | 全系统唯一，**独占一个 Task** | 独立 Task | 复用时走 `onNewIntent` | 早期悬浮窗/通话界面（已不推荐） |
| `singleInstancePerTask` | 每个 Task 内唯一（API 34+ 新增） | Task 内复用 | 复用时走 `onNewIntent` | 替代 singleInstance 的新方案 |

**TaskAffinity（任务亲缘性）**：

- Activity 的 `taskAffinity` 属性决定它**优先进入哪个任务栈**，默认值是应用的包名。
- 典型用法：`android:taskAffinity="com.example.app.settings"` + `FLAG_ACTIVITY_NEW_TASK` 或 `singleTask`，让 Settings 独立成一个任务栈，**返回键直接回桌面而不是回到 App 内**。
- 独立栈（`launchMode="singleInstance"` 或 `FLAG_ACTIVITY_NEW_TASK` + 不同 affinity）在 **Android 10 (API 29) 之后被限制**：不能在后台任务栈中随意启动，只能从前台启动。

**Intent Flag 与启动模式的搭配**（面试常考）：

| Flag | 效果 |
| --- | --- |
| `FLAG_ACTIVITY_NEW_TASK` | 复用已有任务栈（带 affinity 的），否则新建；单例启动时必备 |
| `FLAG_ACTIVITY_CLEAR_TOP` | 目标之上全部出栈；配合 `SINGLE_TOP` 才复用 |
| `FLAG_ACTIVITY_SINGLE_TOP` | 等价于 `launchMode="singleTop"` |
| `FLAG_ACTIVITY_REORDER_TO_FRONT` | 把已有实例移到栈顶（不重建） |
| `FLAG_ACTIVITY_NEW_DOCUMENT` / `MULTIPLE_TASK` | 文档模式，每次启动独立实例 |

> 面试话术：`singleTask` 的"清栈"动作会让目标 Activity 走 `onNewIntent()` 而不是 `onCreate()`，**所以它拿到的 Intent 是新的**——必须在 `onNewIntent` 里重新读取 `getIntent()` 的参数，否则会拿到过期的数据。这是一个高频 Bug。

### 4. 配置变更与 Activity 重建

**什么是配置变更（Configuration Change）**：屏幕方向、语言/地区、字体缩放、dark mode、深浅色主题、屏幕尺寸（分屏/折叠屏展开）、键盘布局（softKeyboard）、locale、uiMode 等。系统默认行为是**销毁并重建 Activity**。

```text
onPause → onStop → onSaveInstanceState(Bundle) → onDestroy
        → onCreate(savedInstanceState) → onStart → onRestoreInstanceState → onResume
```

**为什么这样设计**：Android 的四大组件都可能被系统随时重建，**系统不保证实例存活**，所以状态必须可序列化。旋转屏幕导致"输入的草稿丢失"是典型反模式。

**处理方式**：

1. **主动声明不重建**（适合纯 UI 页面）：

```xml
<activity
    android:name=".MainActivity"
    android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|smallestScreenSize|uiMode" />
```

`configChanges` 里 **Android 13 (API 33) 起必须包含 `uiMode`** 才能拦截深色模式切换。

2. **被动接受重建 + 保存状态**：

```kotlin
// 保存（onSaveInstanceState 只在"可能被杀"时调用，旋转时也调用）
override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putString(KEY_QUERY, editText.text.toString())
    outState.putInt(KEY_TAB, viewPager.currentItem)
}

// 恢复
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val query = savedInstanceState?.getString(KEY_QUERY).orEmpty()
}
```

> **注意**：`onSaveInstanceState` **只在 Activity 即将被销毁且系统认为"有可能恢复"时调用**。用户在 `onPause` 里调用 `finish()`，或者用户从最近任务划掉 App，**不会**回调 `onSaveInstanceState`。所以它**不能当作可靠的持久化手段**。

3. **用 ViewModel 扛旋转**：`ViewModel` 在配置变更时**不销毁**（`onRetainNonConfigurationInstance` 机制），只有进程被杀才丢。见 [[../../07-架构、工具与生态/Jetpack库/Lifecycle与ViewModel]]。

### 5. onSaveInstanceState 与 savedInstanceState 机制

```text
系统准备销毁 Activity
        │
        ▼
ActivityThread.handleStopActivity / handleSaveInstanceState
        │
        ├─► performSaveInstanceState()  ──► Activity.onSaveInstanceState(Bundle)
        │        （把状态写入 Bundle，Bundle 交给 AMS 暂存）
        │
        ▼
进程被杀死 / 内存紧张
        │
        ▼
AMS 记录这个 Bundle（对应 ActivityRecord 的 savedState）
        │
        ▼
用户回到 App，AMS 通过 startActivity 重建
        │
        ▼
ActivityThread.performLaunchActivity
        │
        ├─► onCreate(savedInstanceState)   ← Bundle 传进来
        └─► onRestoreInstanceState(savedInstanceState)  ← View 状态恢复
```

**关键点**：

- `savedInstanceState` 的**内容由系统保存**，大小受 Binder 事务缓冲限制（1MB），塞大对象会 `TransactionTooLargeException`。
- `onRestoreInstanceState` 恢复的是 **View 层状态**（`TextView` 的文本、`ScrollView` 的位置），由 `View.onSaveInstanceState` 的 `mSavedState` 机制完成，**优先级高于 XML 里的 `android:freezesText`**。
- **进程死亡后重建**时，系统会通过 `Intent` 里的 `EXTRA` 或 Intent 本身恢复"启动参数"，而不是靠 `savedInstanceState`。
- `savedInstanceState` 的生命周期是"**Activity 实例**"，不是"进程"。进程被杀 → Activity 重建 → `savedInstanceState` 会被 AMS 传回来，所以它**也能扛住进程死亡**（前提是被 AMS 记录了）。

### 6. Activity 与 Fragment 生命周期关系

```text
Activity.onCreate()
   └─ Fragment.onAttach()          ← 拿到 Context + Activity
   └─ Fragment.onCreate()
   └─ Fragment.onCreateView()      ← 创建 View
   └─ Fragment.onViewCreated()     ← View 已创建，可 findViewById
   └─ Fragment.onActivityCreated() ← Activity 的 onCreate 已完成
Activity.onStart()
   └─ Fragment.onStart()
Activity.onResume()
   └─ Fragment.onResume()          ← 此时 Fragment 可交互
Activity.onPause()
   └─ Fragment.onPause()
Activity.onStop()
   └─ Fragment.onStop()
Activity.onDestroy()
   └─ Fragment.onDestroy()
   └─ Fragment.onDetach()
```

**Fragment 比 Activity 多的两个阶段**：`onAttach` / `onDetach`（与 Context 绑定/解绑）、`onCreateView` / `onViewCreated`（View 生命周期，与 Fragment 实例生命周期解耦）。Fragment 的 View 可以在 Fragment 存活期间被销毁重建（如 `replace` + `addToBackStack`），而 Fragment 实例不变。

**Fragment 的 `setMaxLifecycle`**：`FragmentTransaction.setMaxLifecycle(fragment, Lifecycle.State.STARTED)` 会让 Fragment 的生命周期"落后于"Activity——用于 ViewPager2 预加载相邻页时，避免不可见页也跑动画/播放器。

### 7. Activity Result API（替代 startActivityForResult）

旧 API 存在三个经典问题：`startActivityForResult` 在 `onActivityResult` 里拿不到引用（易泄漏 Activity）、无法在 `Fragment`/`ViewModel` 里统一发起、RequestCode 冲突。

新 API（`ActivityResultLauncher`）：

```kotlin
class MainActivity : AppCompatActivity() {

    // 必须在 onCreate 之前（STARTED 之前）注册
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { imageView.setImageURI(it) }
    }

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { /* ... */ }
    }
}
```

**关键约束（面试点）**：`registerForActivityResult` **必须在 Activity `STARTED` 之前调用**（即 `onCreate` 中、且 `super.onCreate()` 之后尽早），否则抛 `IllegalStateException: LifecycleOwner ... attempting to register while current state is RESUMED`。因为回调派发依赖 Lifecycle 观察者，注册太晚会漏掉结果。

**Fragment 中**用 `registerForActivityResult` 时，**必须在 `onCreate` 或更早**（Fragment 的 `INITIALIZED` 状态），不能等到 `onViewCreated`。

**Compose 中**用 `rememberLauncherForActivityResult`。

### 8. 高频面试问题速查

| 问题 | 一句话答案 |
| --- | --- |
| `onRestart` vs `onStart` | `onRestart` **只在从后台回前台**时调用；`onStart` 在**首次启动和每次回前台**都会调用。`onRestart` 后面一定紧跟 `onStart`。 |
| `onPause` vs `onStop` | `onPause` = **完全不可交互**（哪怕还看得见）；`onStop` = **完全不可见**。被 Dialog 覆盖 → 只到 `onPause`；被另一个 Activity 覆盖 → 到 `onStop`。 |
| `singleTask` 的 `onNewIntent` | 复用已有实例时**不会重新走 `onCreate`**，只走 `onNewIntent`；需在此 `setIntent(intent)` 并重新解析参数。 |
| 内存不足被回收 | 系统按 LRU 杀进程；回来时 Activity 被**重新创建**，状态靠 `savedInstanceState` + `Intent` 恢复，**内存中的字段全丢**。 |
| `finish()` 和 `onDestroy()` 的关系 | `finish()` 只是**通知系统销毁**，回调在之后；进程被杀时 `onDestroy` **可能不执行**。 |
| `finishAffinity()` | 结束当前 Task 中所有 Activity（连同 affinity 匹配的）。 |
| `recreate()` | 主动触发重建，等价于配置变更流程，会调 `onSaveInstanceState`。 |

## 代码示例

### 1. 完整的生命周期日志与资源配对

```kotlin
class LifecycleDemoActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate, savedState=${savedInstanceState != null}")
        setContentView(R.layout.activity_lifecycle_demo)
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")
        handler.post(tick)          // 只有可见时才轮询
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        // 恢复独占资源（如重新连接蓝牙设备）
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
        // 释放独占资源：相机/录音必须在 onPause 释放，
        // 因为系统可能在 Activity 不可见时强制回收这类资源
        releaseExclusiveResources()
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")
        handler.removeCallbacks(tick)
    }

    override fun onRestart() {
        super.onRestart()
        Log.d(TAG, "onRestart")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        // 注意：进程被杀时 onDestroy 可能不执行，资源释放不能只依赖这里
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_COUNT, counter)
    }

    companion object {
        private const val TAG = "LifecycleDemo"
        private const val KEY_COUNT = "count"
        var counter = 0
    }
}
```

### 2. 启动模式与 onNewIntent 的正确处理

```kotlin
class DetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 首次创建时读参数
        render(intent.getStringExtra(EXTRA_ID))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 关键：singleTask / singleTop 复用实例时，必须替换 intent 并重新读取参数
        setIntent(intent)
        render(intent.getStringExtra(EXTRA_ID))
    }

    private fun render(id: String?) {
        title = id ?: "empty"
    }

    companion object {
        const val EXTRA_ID = "extra_id"

        fun start(context: Context, id: String) {
            val intent = Intent(context, DetailActivity::class.java).apply {
                putExtra(EXTRA_ID, id)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }
    }
}
```

对应 Manifest：

```xml
<activity
    android:name=".DetailActivity"
    android:exported="false"
    android:launchMode="singleTask" />
```

### 3. Activity Result API + 配置变更状态保存

```kotlin
class FormActivity : AppCompatActivity() {

    private lateinit var nameInput: EditText
    private var selectedUri: Uri? = null

    // 必须在 onCreate 中尽早注册（super.onCreate 之后即可）
    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            preview.setImageURI(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_form)
        nameInput = findViewById(R.id.name_input)
        preview = findViewById(R.id.preview)

        // 1) 恢复进程死亡/旋转前的状态
        savedInstanceState?.let { state ->
            nameInput.setText(state.getString(KEY_NAME).orEmpty())
            selectedUri = state.getParcelable(KEY_URI, Uri::class.java)
            selectedUri?.let { preview.setImageURI(it) }
        }

        // 2) 选择图片
        findViewById<Button>(R.id.pick).setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        // 3) 提交
        findViewById<Button>(R.id.submit).setOnClickListener {
            val name = nameInput.text.toString()
            // 保存草稿，防止用户切后台被回收后丢失
            getSharedPreferences("draft", MODE_PRIVATE)
                .edit().putString("name", name).apply()
            Toast.makeText(this, "已提交：$name", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_NAME, nameInput.text.toString())
        outState.putParcelable(KEY_URI, selectedUri)
    }

    companion object {
        private const val KEY_NAME = "name"
        private const val KEY_URI = "uri"
    }
}
```

### 4. 状态保存的现代写法：SavedStateHandle

```kotlin
class SearchViewModel(private val state: SavedStateHandle) : ViewModel() {

    val query: StateFlow<String> = state.getStateFlow("query", "")

    fun onQueryChanged(text: String) {
        state["query"] = text
    }
}

class SearchActivity : AppCompatActivity() {
    private val vm: SearchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SavedStateHandle 的内容最终会被写入 savedInstanceState，
        // 因此既能扛配置变更，也能扛进程死亡重建
        vm.query.observe(this) { q -> binding.etSearch.setText(q) }
    }
}
```

## 常见问题 / 面试要点

**读者问：Activity 的完整生命周期是什么？`onRestart` 和 `onStart` 到底有什么区别？**

**AI答：**

1. **完整序列**：首次启动 `onCreate → onStart → onResume`；回前台 `onRestart → onStart → onResume`；进后台 `onPause → onStop`；销毁 `onDestroy`。
2. **`onRestart` vs `onStart` 的本质区别**：
   - `onStart` 表示"变成可见"，**首次启动也会调用**。
   - `onRestart` 表示"从完全不可见（Stopped）状态回到可见"，**只在后台→前台这一种场景触发**，首次启动**不会**调用。
   - 换句话说：`onRestart` 的存在意义是给"需要重新获取资源/重新注册监听"的场景一个**精确的钩子**——只有真正"暂停过"才需要重启资源，避免每次都白干。
3. **面试话术**："`onStart` 回答'我可见了吗'，`onRestart` 回答'我是不是从后台被暂停过又回来了'。前者是可见性事件，后者是恢复事件。"
4. **常见坑**：把资源释放写在 `onStop`、重新获取写在 `onRestart`——如果 Activity 只是被一个 Dialog 遮挡（只走 `onPause` 不走 `onStop`），资源不会被释放也不需要重新获取，逻辑其实是对的；但如果资源是**独占型**（相机、录音），放 `onPause` 才安全，因为 `onPause` 之后系统随时可能收走。

---

**读者问：`onPause` 和 `onStop` 到底什么时候触发？被 Dialog 覆盖会走到哪个？**

**AI答：**

1. **判定标准是"完全不可交互"和"完全不可见"**：
   - `onPause`：Activity **完全不可交互**（失去输入焦点），但**可能仍然部分可见**。
   - `onStop`：Activity **完全不可见**（被完全遮挡或已销毁视图）。
2. **典型场景对照**：

   | 场景 | 触发的回调 |
   | --- | --- |
   | 弹出非全屏 Dialog / 弹出 PopupWindow | 只到 `onPause` |
   | 启动另一个 Activity（全屏） | `onPause → onStop` |
   | 按 Home 键 | `onPause → onStop` |
   | 屏幕熄灭 | `onPause → onStop` |
   | 分屏被另一应用占据 | `onPause → onStop` |
   | 权限弹窗（部分 ROM） | 通常只到 `onPause`（可能到 `onStop`，依 ROM 实现） |

3. **面试话术**："`onPause` 是'让出焦点'，`onStop` 是'完全退场'。系统给的是粗粒度信号，不要依赖精确的顺序做精细判断。"
4. **常见坑**：在 `onStop` 里才释放相机 → 用户打开相机后切到别处，系统强杀你的进程或抢占相机，导致 Camera 报错。**独占资源必须在 `onPause` 释放**。

---

**读者问：Activity 被系统回收（内存不足）后重新创建，和配置变更重建有什么区别？**

**AI答：**

1. **回调序列几乎相同**：`onPause → onStop → onSaveInstanceState → onDestroy → onCreate → onStart → onResume`，但：
   - **进程被回收时 `onDestroy` 不会执行**（进程直接没了，没有机会回调）。
   - 配置变更重建时 `onDestroy` **会执行**。
2. **数据恢复来源不同**：
   - 配置变更：字段还在吗？不在，实例重建了，但 `ViewModel` **保留**（非配置性实例保留机制）。
   - 进程死亡：`ViewModel` **也丢**。必须靠 `savedInstanceState`（AMS 暂存的 Bundle）+ `Intent` 中的参数 + 持久化存储（DB/SP）。
3. **面试话术**："判断"是否发生了进程死亡"的黄金标准是 `savedInstanceState != null`——在 `onCreate` 里判空即可区分'首次创建'与'恢复'。"
4. **常见坑**：把关键数据只存在内存字段里（`var userId: String? = null`），进程死亡后丢失 → 回来时页面空白。正确做法是 `Intent.putExtra`（可重建）+ `SavedStateHandle`（少量状态）+ DB/SP（权威数据）。
5. **补充**：`onSaveInstanceState` 只在"**系统认为有可能恢复**"时调用。如果用户按返回键 `finish()` 了、或从最近任务里划掉 App，就**不会**回调。**不要用它做数据持久化**。

---

**读者问：`singleTask` 到底怎么工作？为什么我的 Activity 收到的是旧数据？**

**AI答：**

1. **机制**：以 `singleTask` 启动时，系统在**当前任务栈（以及同 affinity 的其他栈）**里搜索目标组件的实例：
   - **找不到** → 新建实例并压栈。
   - **找到了** → **把该实例之上的所有 Activity 全部出栈（销毁）**，然后把该实例**移到栈顶**，**不再创建新实例**。
2. **结果**：目标 Activity **不会重新走 `onCreate`**，只走 `onNewIntent(Intent)`。此时：
   - `getIntent()` 返回的**还是旧的 Intent**（系统没自动替换）。
   - 必须自己 `setIntent(intent)` 并重新解析 `extras`。
3. **面试话术**："`singleTask` 的本质是'**清栈复用**'。它的两个副作用是：① 上面所有页面被销毁（用户按返回会直接离开这个 Task）；② 复用时必须处理 `onNewIntent`，否则参数不更新。"
4. **常见坑**：
   - 忘记 `setIntent()` → 拿到过期参数。
   - `singleTask` 用了 `Intent.FLAG_ACTIVITY_CLEAR_TOP` 但**没配 `singleTop`** → `CLEAR_TOP` 在没有 `SINGLE_TOP` 时默认销毁重建目标（历史上 Android 4 之前的行为不同），导致 `onCreate` 又走一遍。
   - `singleTask` + `taskAffinity` 独立栈 → Android 10+ 后台启动受限。

---

**读者问：Activity 启动模式有哪几种？`taskAffinity` 有什么实际用途？**

**AI答：**

1. **五种启动模式**（表格见上文"核心原理"第 3 节）。关键记忆点：
   - `standard` → 每次都新建。
   - `singleTop` → **只复用栈顶**。
   - `singleTask` → **全栈搜索 + 清栈复用**。
   - `singleInstance` → **独占一个 Task**（Android 10+ 已被 `singleInstancePerTask` 取代/限制）。
   - `singleInstancePerTask` → API 34 新增，每个 Task 内单例。
2. **`taskAffinity` 用途**：
   - Activity **优先进入**哪个任务栈，默认是应用包名。
   - 配 `singleTask` + 不同 affinity → 让页面成为**独立 Task 的根**，返回键直接回桌面。典型：设置页、登录页、分享页。
   - 实现方式：Manifest `android:taskAffinity="com.example.app.settings"`，或 `Intent.FLAG_ACTIVITY_NEW_TASK` + `FLAG_ACTIVITY_MULTIPLE_TASK`。
3. **面试话术**："启动模式决定'**实例能不能复用**'，launchMode + Intent Flag 一起决定最终行为；`taskAffinity` 决定'**实例住在哪个栈**'。三者可以组合，但优先级是：Intent Flag > Manifest launchMode > 默认 standard。"
4. **常见坑**：在 `singleTask` Activity 里用 `startActivity` 不加 `FLAG_ACTIVITY_NEW_TASK`（其实 `singleTask` 场景下系统会自动处理，行为在不同版本有差异，显式加更稳）。

---

**读者问：`registerForActivityResult` 有什么坑？为什么会抛 IllegalStateException？**

**AI答：**

1. **新旧 API 对比**：

   | | `startActivityForResult` | `ActivityResultLauncher` |
   | --- | --- | --- |
   | 注册时机 | 任意 | **必须在 Lifecycle `STARTED` 之前** |
   | 回调位置 | `onActivityResult` | 注册时传入的 lambda |
   | 类型安全 | 无（`Int` + Bundle） | 有（泛型契约） |
   | 可在 ViewModel/Fragment 复用 | 难 | 易（`ViewModel` 可持有 launcher） |
   | RequestCode 冲突 | 有 | 无 |

2. **抛异常的原因**：结果回调是通过 **Lifecycle 的 `ON_START` 事件**派发给已注册的 observer 的。如果在 `RESUMED` 之后才注册，Activity 已经 `STARTED` 过，**那次派发已经发生**，回调永远收不到——框架为了避免这种"注册即丢失"的静默错误，直接抛 `IllegalStateException` 提醒你。
3. **正确做法**：`registerForActivityResult(...)` 放在**成员属性初始化**处（它在 `onCreate` 之前、`INITIALIZED` 阶段执行），或 `onCreate` 中 `super.onCreate()` 之后尽早。
4. **Fragment 特别注意**：Fragment 的 `registerForActivityResult` 必须在其 **`INITIALIZED` 状态**（即 `onAttach` 之后、`onCreate` 之前或 `onCreate` 中）注册。写在 `onViewCreated` 里在某些场景会失败。
5. **面试话术**："新版 API 的本质是'**把结果回调变成 Lifecycle 事件驱动的观察者**'，因此注册时机必须早于 `STARTED`，这是它相比旧 API 最大的行为约束。"

---

**读者问：Activity 的内存占用大概是多少？系统是怎么决定杀哪个 Activity 的？**

**AI答：**

1. **占用构成**：Activity 本身对象极小（几 KB），真正的开销是 **View 树 + Bitmap + 资源**。一个普通页面 2～5MB；含大图/列表的页面可达 20MB+。
2. **系统的回收策略是"杀进程"而不是"杀 Activity"**：LMK（Low Memory Killer Daemon）按 **OOM Score / adj（调整值）** 排序杀进程。
3. **adj 优先级（从高到低）**：
   - `foreground`（用户正在看的）→ 永不杀。
   - `visible`（可见但不可交互，如 Dialog/透明 Activity）→ 几乎不杀。
   - `service`（有前台服务）→ 不杀。
   - `background`（不可见但进程活着）→ **优先被回收**。
   - `cached`（空 Activity 栈保留进程以加速恢复）→ 内存紧张时第一个被杀。
4. **面试话术**："系统杀的是**整个进程**，不是单个 Activity。所以'Activity 被回收'实际上是'进程被杀、Activity 被重建'。这意味着进程里的所有静态变量、单例、内存缓存全部丢失。"
5. **应对策略**：
   - 用 `ViewModel` 扛配置变更，用 `SavedStateHandle` + 持久化扛进程死亡。
   - 大图片用 Glide/Coil，缓存可控，避免直接进内存。
   - 静态单例里的 `Context` 必须用 `applicationContext`。

---

**读者问：Activity 与 Fragment 的生命周期到底怎么对应？Fragment 有哪些 Activity 没有的阶段？**

**AI答：**

1. **Fragment 的三套生命周期**（官方文档的经典比喻）：
   - **实例生命周期**：`onAttach → onCreate → onCreateView → onViewCreated → onActivityCreated → onStart → onResume → onPause → onStop → onDestroyView → onDestroy → onDetach`。
   - **View 生命周期**：`onCreateView → onViewCreated → onViewCreated` 之后的 View 状态保存恢复 → `onDestroyView`。
   - **Fragment 生命周期（与 Activity 对齐）**：`onStart/onResume/onPause/onStop`。
2. **Fragment 独有的阶段**：
   - `onAttach` / `onDetach`：绑定/解绑 Context（可拿到 Activity）。
   - `onCreateView` / `onDestroyView` / `onViewCreated`：**View 的创建与销毁与 Fragment 实例解耦**——`replace()` + `addToBackStack()` 会销毁 View 但保留 Fragment 实例。
3. **与 Activity 的对应**：`Fragment.onResume` 一定在 `Activity.onResume` **之后**；`Fragment.onPause` 在 `Activity.onPause` **之前**。因为 Activity 会先把自己的状态降级，再下发给 Fragment。
4. **面试话术**："Fragment 是'**依附于 Activity 的 UI 片段**'，它的生命周期被宿主 Activity 驱动，且额外多了 View 层生命周期——这正是 `FragmentTransaction.setMaxLifecycle` 能让不可见页'降级运行'的原因。"
5. **常见坑**：在 `onDestroyView` 里不释放 binding（导致泄漏）——`onDestroyView` 后 Fragment 实例还活着，但 View 树已销毁，应该置空 binding；在 `onDestroy` 里释放就晚了。

---

**读者问：`finish()`、`onDestroy()`、`onStop()` 三者的关系是什么？`onDestroy` 一定会执行吗？**

**AI答：**

1. **`finish()` 的语义**：调用 `finish()` 只是**给系统一个"请销毁这个 Activity"的请求**，它会：
   - 结束该 Activity 的动画；
   - 把它从 Activity 栈移除；
   - 最终触发 `onPause → onStop → onDestroy`。
   - 它**不会立即**、**同步**调用 `onDestroy`。
2. **`onDestroy` 不一定执行**：**进程被系统直接杀死**时（LMK 杀进程、崩溃、用户强制停止 App），`onDestroy` **不会**被调用。所以资源释放不能只依赖 `onDestroy`。
3. **相关 API**：
   - `finish()`：结束当前 Activity。
   - `finishAffinity()`：结束本 Task 中所有 affinity 匹配的 Activity。
   - `finishAndRemoveTask()`：结束并把 Task 从最近任务里移除。
   - `moveTaskToBack()`：把 Task 移到后台（**不结束**），返回键回来的效果。
4. **面试话术**："`finish()` 是'请求'，`onDestroy` 是'结果'，而'进程被杀'是'没有结果的意外'。所以三者在编写资源释放代码时必须分别考虑。"
5. **常见坑**：在 `onDestroy` 里做网络请求的取消/数据库写入——进程被杀时不会执行，数据会丢。可靠的落盘应该在 `onStop`（`onStop` 在进程被杀前的正常流程里基本都会走）。

## 关联笔记

- [[../README]]：第 2 部分总览与知识图谱
- [[Service]]：另一个无 UI 但有生命周期的组件
- [[../组件间通信/Intent]]：Activity 的启动本质是 Intent 驱动的
- [[../Application与Context]]：Activity 是 Context 的子类，理解两者关系
- [[../../07-架构、工具与生态/架构模式/MVVM]]：Activity 与 ViewModel 的协作关系
- [[../../07-架构、工具与生态/Jetpack库/Lifecycle与ViewModel]]：Lifecycle 的状态机实现
- [[../../06-系统原理与性能优化/系统核心机制/Activity启动流程]]：AMS 如何启动 Activity

## 参考资料

- Android 官方文档 · Activity 生命周期：https://developer.android.com/guide/components/activities/activity-lifecycle
- Android 官方文档 · Activity 启动模式与任务栈：https://developer.android.com/guide/components/activities/activity-types
- Android 官方文档 · Activity Result API：https://developer.android.com/training/activity-result
- Android 官方文档 · 保存界面状态：https://developer.android.com/guide/components/activities/saving-state
- 《Android 高级编程》第 4 版 · 第 3 章（Activity 与资源管理）
