---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 中级
created: 2026-11-04
updated: 2026-11-04
related: "[[Lifecycle]], [[LiveData与StateFlow]], [[../架构模式/MVVM]], [[../依赖注入/Hilt]]"
---
# Lifecycle与ViewModel

## 一句话定义

Lifecycle 是**感知组件生命周期状态变化**的框架，ViewModel 是**依附于生命周期、跨配置变更存活、且可被依赖注入**的状态持有者；两者共同构成 MVVM 的"骨架"。

> [!note] ✏️ 笔记分工
> 本篇聚焦 **ViewModel 侧**：存活范围、`viewModelScope`、`SavedStateHandle`、依赖注入。
> **Lifecycle 框架本身**（事件与状态的两套语义、`LifecycleRegistry` 状态机引擎、观察者 catch-up、`repeatOnLifecycle` 源码级实现、`ProcessLifecycleOwner`、自定义 `LifecycleOwner`）见 [[Lifecycle]]。

## 核心原理

### 1. Lifecycle 的两套 API

| API                                               | 说明                                                                                                             | 适用                                      |
| ------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- | ----------------------------------------- |
| `Lifecycle`（事件驱动）                         | 提供 `LifecycleEvent` 回调（`ON_CREATE`/`ON_START`/`ON_RESUME`/`ON_PAUSE`/`ON_STOP`/`ON_DESTROY`） | 需要精确感知每个阶段时                    |
| `LifecycleOwner` + `DefaultLifecycleObserver` | 声明式注册回调，生命周期结束自动解绑                                                                             | 常规场景，官方推荐                        |
| `Lifecycle.State`（状态驱动）                   | 只有 `CREATED` / `STARTED` / `RESUMED` 三态，是**状态机**的当前值                                    | 状态驱动的协程收集、`repeatOnLifecycle` |

**六事件 vs 三状态** 的映射关系：

```text
Event:  ON_CREATE → ON_START → ON_RESUME → ON_PAUSE → ON_STOP → ON_DESTROY
State:      CREATED ──────▶ STARTED ───▶ RESUMED
               ▲                │            │
               └────── CREATED ◀┴────────────┘
```

- `RESUMED` 状态下一次事件是 `ON_PAUSE` → 回到 `STARTED`。
- `ON_STOP` 之后是 `CREATED`，`ON_DESTROY` 之后不再有状态（`DESTROYED`）。

### 2. 生命周期感知的三种"自动停止"机制

这是面试高频考点，区别要讲清楚：

| 机制                                     | 行为                                                                                      | 典型场景                 |
| ---------------------------------------- | ----------------------------------------------------------------------------------------- | ------------------------ |
| `LiveData.observe(owner)`              | 页面 **不可见（STOPPED 之后）自动暂停分发**，回到可见自动**补发最新值**       | 传统 View + MVVM         |
| `Flow + repeatOnLifecycle(STARTED)`    | 收集协程在 STARTED 时启动、非 STARTED 时**取消**（不是挂起），回到 STARTED 重新收集 | Compose / 传统 View 协程 |
| `Flow + collectAsStateWithLifecycle()` | 内部即 `repeatOnLifecycle(STARTED) + collectAsState`                                    | Compose 推荐写法         |

> [!warning] ⚠️ 常见坑
> 直接 `lifecycleScope.launch { flow.collect {} }` 在 STARTED 时启动，页面 STOPPED 仍会继续收集 → 后台也在发请求、也在更新 UI 变量。**必须用 `repeatOnLifecycle`**。

### 3. ViewModel 的生命周期与"存活范围"

```text
Activity.onCreate()  ──►  ViewModelProvider 创建 ViewModel
                            │
   配置变更（旋转）           │  Activity 销毁重建，ViewModel 保留（onCleared 不调用）
   Activity.onDestroy() ────┘
                            │
   进程被系统杀死 / 用户返回栈销毁（真正 onDestroy 且非配置变更）
                            ▼
                        onCleared() → viewModelScope 取消 → 所有协程结束
```

**四个必须记住的事实**：

1. **ViewModel 在 `onCreate` 之后才被创建**（第一次 `by viewModels()` 访问时），通过 `ViewModelProvider` 管理。
2. **配置变更（旋转屏幕、分屏、切换语言、深色模式切换）不会销毁 ViewModel**——这是它最核心的价值。
3. **进程被杀死后 ViewModel 会丢**，必须靠 `SavedStateHandle` 恢复关键状态。
4. **`onCleared()` 只在 ViewModel 真正结束时调用**，此时 `viewModelScope` 被取消。

### 4. `viewModelScope` 的作用

```kotlin
val viewModelScope = viewModelScope  // CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
```

- 绑定 ViewModel 生命周期，`onCleared()` 时**自动取消**其中所有协程 → 防止内存泄漏。
- `SupervisorJob` 保证一个子协程异常失败不会连带取消兄弟协程。
- `Dispatchers.Main.immediate`：默认在主线程；`immediate` 表示已经在主线程时**不重新 post**，直接执行（避免不必要的帧延迟）。

### 5. `SavedStateHandle`：进程恢复的桥梁

- 内部以 `Bundle` 形式持久化到 `savedInstanceState`，进程重建后自动恢复。
- 适合放**少量、可序列化**的数据：搜索词、选中 ID、当前 Tab、列表滚动位置、表单草稿。
- 不适合放大数据（`Bundle` 有 1MB 量级限制，过大会 `TransactionTooLargeException`）。
- 通过 `getStateFlow(key, default)` 还可以直接返回一个 `StateFlow`，自动跟随恢复后的值变化。

### 6. ViewModel 与依赖注入

```kotlin
// 手动：需要自己写 Factory
val vm: UserVM by viewModels { UserVM.Factory(repo) }

// Hilt：自动注入
@HiltViewModel
class UserVM @Inject constructor(private val repo: UserRepository) : ViewModel()
```

Hilt 通过 `HiltViewModelFactory` + `@AssistedInject`/自定义 `CreationExtras` 把 `SavedStateHandle` 和依赖一起传给 ViewModel 构造函数——这是 Hilt 的能力，不是 ViewModel 自带的。

## 代码示例

### 1. 自定义 LifecycleObserver

```kotlin
class ScreenVisibilityObserver(
    private val onVisible: () -> Unit,
    private val onHidden: () -> Unit
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) = onVisible()
    override fun onStop(owner: LifecycleOwner) = onHidden()
}

// 使用（在 View 层，注意成对添加/移除，或用 lifecycleScope 绑定）
class MainActivity : AppCompatActivity() {
    private val observer = ScreenVisibilityObserver(
        onVisible = { startPolling() },
        onHidden = { stopPolling() }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(observer)
    }
}
```

### 2. 传统 View 中正确的协程收集方式

```kotlin
class UserListActivity : AppCompatActivity() {
    private val viewModel: UserListViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_list)

        lifecycleScope.launch {
            // STARTED 才收集，STOPPED 自动取消
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                launch { viewModel.events.collect { handle(it) } }
            }
        }
    }
}
```

### 3. Compose 中获取 ViewModel + 生命周期感知收集

```kotlin
@Composable
fun UserListRoute(
    viewModel: UserListViewModel = hiltViewModel()   // Hilt 提供，自动处理 SavedStateHandle
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    // 页面不可见时自动停止收集
    LaunchedEffect(Unit) {
        viewModel.events.collect { /* ... */ }
    }
}
```

### 4. 用 `SavedStateHandle` 保存筛选条件

```kotlin
class FilterViewModel(private val savedState: SavedStateHandle) : ViewModel() {

    // 自动恢复，进程重建后仍是上次的关键字
    val keyword: StateFlow<String> = savedState.getStateFlow("keyword", "")

    val type: StateFlow<Int> = savedState.getStateFlow("type", TYPE_ALL)

    fun onKeywordChanged(value: String) {
        savedState["keyword"] = value
    }

    fun onTypeSelected(value: Int) {
        savedState["type"] = value
    }
}
```

### 5. 自定义 ViewModelProvider.Factory（不依赖 Hilt 时）

```kotlin
class UserVMFactory(
    private val repo: UserRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(UserVM::class.java)) {
            return UserVM(repo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: $modelClass")
    }
}

// 使用
val vm: UserVM by viewModels { UserVMFactory(repo) }
```

### 6. 自定义 ViewModel 的 Scope（Activity/Fragment 级共享）

```kotlin
class UserVM : ViewModel() {
    // Activity 作用域：Fragment 间共享
    val activityScope = viewModelScope

    // Fragment 作用域：仅自己
    val fragmentScope = viewModelScope
}
```

## 常见问题 / 面试要点

**读者问：ViewModel 到底能活多久？为什么旋转屏幕不会丢数据？**

**AI答：**

1. **存活范围**：`ViewModel` 保存在宿主（Activity/Fragment）的 **`ViewModelStore`** 里，而这个 Store 保存在 `NonConfigurationInstance` 中（Activity 传给新实例的"非配置实例"）。
2. **旋转屏幕时发生了什么**：
   - 系统回调 `onDestroy()`（`changingConfigurations = true`）→ 销毁旧 Activity；
   - 但 **`ViewModelStore` 被保存在 `ActivityThread` 的 `NonConfigurationInstances` 中，不会随 Activity 销毁而释放**；
   - 新 Activity 创建时，`onRetainNonConfigurationInstance()` 把这个 Store 取回来交给新 Activity；
   - 新 Activity 首次 `by viewModels()` 访问时，`ViewModelProvider` 从 Store 里找到**同一个** ViewModel 实例返回。
3. **什么情况下会丢**：
   - 进程被系统杀死（后台内存不足）→ 整个进程内存消失，ViewModel 必然丢；
   - Activity 真正销毁且不是配置变更（如 `finish()` 返回、任务被清空）；
   - 手动 `ViewModelStore.clear()`。
4. **所以 ViewModel 解决的是"配置变更导致的状态丢失"，不是"进程死亡的状态恢复"**——后者要靠 `SavedStateHandle` + `onSaveInstanceState`。

> 一句话："**ViewModel 抗旋转，不抗杀进程；SavedStateHandle 抗杀进程但不抗大数据。**"

---

**读者问：ViewModel 里的协程在什么线程跑？怎么防止内存泄漏？**

**AI答：**

1. **线程**：`viewModelScope` 默认是 `Dispatchers.Main.immediate`，即**主线程**。所以 ViewModel 里**绝不能直接做耗时操作**（网络、磁盘、大量计算）。
2. **正确做法**：ViewModel 只负责"发起 + 编排"，耗时部分下沉到 Repository 的 `suspend` 函数里，用 `withContext(Dispatchers.IO)` 切换。

```kotlin
class UserRepo(private val api: Api) {
    suspend fun load(): List<User> = withContext(Dispatchers.IO) { api.getUsers() }
}
```

3. **防泄漏的三重保障**：
   - **`viewModelScope` 自动取消**：`onCleared()` 时 `SupervisorJob` 被取消 → 所有 `launch` 的协程被取消 → 协程持有的 ViewModel 引用（进而 ViewModel 持有的东西）随之释放。
   - **避免捕获 Activity**：即使 ViewModel 存活期间引用了 Activity 的 lambda，也会因为 Activity 销毁后 ViewModel 仍存活而泄漏。**因此规则是：ViewModel 中禁止出现 `Activity`/`View` 类型**。
   - **Flow 用 `stateIn` 而非 `shareIn`（谨慎）**：`stateIn` 有初始值且上游在无订阅时也会保持最新值，更适合"状态"；`shareIn` 无初始值且上游可能停止。
4. **容易漏掉的泄漏点**：`Handler(Looper.getMainLooper()).postDelayed`、注册到单例的监听器、`GlobalScope.launch`——这些**不受 ViewModel 生命周期管理**，必须手动在 `onCleared()` 取消，或改用 `viewModelScope`。

---

**读者问：`viewModelScope`、`lifecycleScope`、`rememberCoroutineScope` 有什么区别？**

**AI答：**

三者的**取消时机**和**存活周期**不同，这决定了它们该用在哪：

| 作用域                     | 取消时机                                       | 典型用途                                     | 注意                                                                                           |
| -------------------------- | ---------------------------------------------- | -------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `viewModelScope`         | ViewModel 的 `onCleared()`                   | ViewModel 里发起的业务逻辑                   | 跨配置变更不取消，适合"业务请求"                                                               |
| `lifecycleScope`         | Lifecycle `ON_DESTROY`                       | Activity/Fragment 里收集 Flow、动画          | 协程在 `ON_DESTROY` 时取消；**但收集本身要用 `repeatOnLifecycle(STARTED)` 控制开关** |
| `rememberCoroutineScope` | Composable 离开组合 / 该 `remember` key 变化 | Compose 事件回调里发起的协程（如点击后动画） | **不能在 Composable 函数体里直接 `launch`**，因为重组会重启                            |

1. **`viewModelScope` 关键点**：Activity 重建（旋转）时 ViewModel 存活 → **协程不取消**，请求结果不会因旋转而丢失。这是它与 `lifecycleScope` 最大的差别。
2. **`lifecycleScope` 关键点**：默认 `Dispatchers.Main.immediate`；配合 `repeatOnLifecycle(Lifecycle.State.STARTED)` 使用，页面不可见时**取消收集**（而不是暂停）。
3. **`rememberCoroutineScope` 关键点**：在 Compose 中，事件回调里需要启动协程时用它；它跟随组合生命周期，离开组合即取消。**反例**：

```kotlin
@Composable
fun Bad() {
    // 每次重组都会重新启动一个协程 —— 错误
    LaunchedEffect(Unit) { /* 视图层持续任务应该放这里 */ }
    val scope = rememberCoroutineScope()
    Button(onClick = { scope.launch { doSomething() } })   // 正确
}
```

4. **一句话记忆**：**`viewModelScope` = 业务，生命周期跟着 ViewModel；`lifecycleScope` = 界面收集；`rememberCoroutineScope` = Compose 事件。**

---

**读者问：什么是"生命周期感知"，`repeatOnLifecycle` 到底解决了什么问题？**

**AI答：**

1. **要解决的问题**：`lifecycleScope.launch` 只保证"**Activity 销毁时不泄漏**"，但**页面不可见（HOME 键、弹 dialog、跳转下一页）时协程仍在跑**。后果：

   - 后台持续发网络请求，浪费流量与电量；
   - 后台更新 UI 状态，甚至操作已不可见的界面；
   - 多个页面同时订阅同一个 Flow 时，上游被重复触发（多次请求）。
2. **`repeatOnLifecycle(STARTED)` 的行为**：

   - 生命周期**到达** `STARTED`（或更高）→ **启动**内部的 `launch` 块；
   - 生命周期**低于** `STARTED`（`CREATED`）→ **取消**内部的 `launch` 块（是取消，不是挂起）；
   - 再次到达 `STARTED` → **重新启动**该块（所以里面的代码会重新执行，注意别写"只应执行一次"的初始化逻辑在块内）。
3. **块内可以 launch 多个子协程**，它们共享生命周期；块被取消时子协程一并取消。
4. **和 `LiveData` 的区别**：

   - `LiveData` 在不可见时是**暂停分发**，回到可见时**补发最新值**（所以不会丢数据）；
   - `repeatOnLifecycle` 是**取消上游**，回到可见时**重新订阅**，上游会**从头执行**（所以 Flow 若是 `flow { emit(...) }` 会重新请求）。
   - 需要"最新值不丢"时，StateFlow 因为自带**粘性**（始终有 `value`）表现得更好。

   > `repeatOnLifecycle` 的源码级原理（观察者注册、取消与重启时机）、与 `flowWithLifecycle` 的对比，见 [[Lifecycle]]。
   >

---

**读者问：ViewModel 能持有 Context 吗？`AndroidViewModel` 有什么用？**

**AI答：**

1. **规则**：**ViewModel 不应持有 Activity/Fragment/Dialog 上下文**，因为 ViewModel 的存活时间长于这些对象，持有会造成泄漏，而且可能操作到已销毁的界面。
2. **可以持有的**：**Application 级别的 Context**（全局单例，生命周期 = 进程）。
3. **`AndroidViewModel`**：内部持有一个 `Application` 实例（`getApplication()`），用于那些必须用 Application 资源/服务的场景：

```kotlin
class MyVM(app: Application) : AndroidViewModel(app) {
    val appContext = app   // 安全的 Application 引用
}
```

4. **更好的做法（现代 Android）**：不要继承 `AndroidViewModel`，而是**用 Hilt 注入 `@ApplicationContext`**：

```kotlin
@HiltViewModel
class MyVM @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel()
```

这样 Context 的来源是**依赖注入容器**而不是"ViewModel 自己 new 出来的"，可测试性更好（单测时换成 `ApplicationProvider` 或 mock）。

5. **需要界面级 Context 时怎么办**：不要持有，而是把需求**作为事件抛给 View**：

```kotlin
// ViewModel：只声明"我要开一个输入框"
sealed interface Event { data object RequestInput : Event }
// View：收到后用 Activity 的 Context 打开 Dialog
```

---

**读者问：`SavedStateHandle` 和 `onSaveInstanceState` 有什么区别？什么时候用哪个？**

**AI答：**

1. **`onSaveInstanceState(Bundle)`**：
   - Activity/Fragment 级别，由系统调用，回调给你一个 Bundle 存数据；
   - 适合存**页面级**的少量状态（滚动位置、选中 Tab、表单草稿）；
   - 需要手动 `putXxx` / `getXxx`，类型受限（Primitive、Parcelable、Serializable、ArrayList）。
2. **`SavedStateHandle`**：
   - 本质是**系统帮你接管的 Bundle**，键值对形式，API 更现代；
   - 可通过 `ViewModelProvider` 注入到 ViewModel 构造函数；
   - 支持 `getStateFlow(key, default)` 直接得到 `StateFlow`，且值变化自动同步回 Bundle；
   - 适合存**需要在进程重建后恢复**的少量关键状态。
3. **关键区别**：
   - `SavedStateHandle` 是**给 ViewModel 用的**，且数据在 `onSaveInstanceState` 之后才真正写入持久化 Bundle；数据存在 ViewModel 内部（内存）+ 恢复时回填。
   - 手动 Bundle 是**给 View 用的**。
4. **限制**：
   - Android 7.0+ 起 `Bundle` 用于进程恢复有 **1MB 左右**的限制（实际受 Binder 事务缓冲区约束），超出会 `TransactionTooLargeException`；
   - 所以**不要往 SavedStateHandle 里放大列表、大 Bitmap、复杂对象**。
5. **最佳实践**：能用 `rememberSaveable` 的小 UI 状态用 `rememberSaveable`；业务级、需要跨进程恢复的用 `SavedStateHandle`；其余交给服务器或本地数据库。

## 关联笔记

- [[Lifecycle]]：Lifecycle 状态机、生命周期感知协程与 `repeatOnLifecycle` 的完整拆解
- [[LiveData与StateFlow]]：状态载体与生命周期感知
- [[../架构模式/MVVM]]：ViewModel 在 MVVM 中的角色
- [[../依赖注入/Hilt]]：`hiltViewModel()` 的原理
- [[../测试/单元测试]]：ViewModel 的可测试性设计
- [[../README]]：第 7 部分总览

## 参考资料

- Android 官方 · 生命周期总览：https://developer.android.com/guide/components/activities/activity-lifecycle
- Android 官方 · ViewModel：https://developer.android.com/topic/libraries/architecture/viewmodel
- Android 官方 · `repeatOnLifecycle` 与生命周期感知协程：https://developer.android.com/topic/libraries/architecture/coroutines
- 《Android 高级编程》第 4 版 · 第 7 章
