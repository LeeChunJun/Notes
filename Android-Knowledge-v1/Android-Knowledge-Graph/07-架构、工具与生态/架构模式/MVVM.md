---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 中级
created: 2026-11-04
updated: 2026-11-04
related: "[[MVI]], [[MVC与MVP]], [[../Jetpack库/LiveData与StateFlow]], [[../依赖注入/Hilt]]"
---

# MVVM

## 一句话定义

MVVM 把界面拆成 **View（渲染状态）+ ViewModel（持有状态与业务）+ Model（数据源）** 三层，核心是**单向数据流（Unidirectional Data Flow）**：View 只把用户事件"向上抛"，ViewModel 把状态"向下发"，View 自己不做业务判断。

## 核心原理

### 1. 单向数据流

```
      用户交互
        │  (Intent / 回调)
        ▼
   ┌─────────────┐
   │  ViewModel  │  持有唯一真相源 State
   └─────────────┘
        │  (State 变化)
        ▼
      View 重新渲染
```

关键约束：

- **状态是单向流动的**：State → View，永远不会反向。
- **View 不持有业务状态**：`TextView.setText("...")` 之后不再关心这个值从哪来。
- **ViewModel 不认识 View**：不知道 `TextView`、不知道 `Activity`，因此能在 JVM 上直接跑单测。

### 2. 分层职责

| 层 | 职责 | 不该做什么 |
| --- | --- | --- |
| View（Activity/Fragment/Composable） | 渲染 State、收集事件、把用户操作转成 Intent | 不写业务规则、不直接调 Repository |
| ViewModel | 持有 State、编排用例、处理副作用、生命周期跟随 | 不持有 Activity/Context（除 ApplicationContext）、不做耗时 IO 本身 |
| Model（Repository/DataSource） | 数据获取与缓存策略，屏蔽数据来源细节 | 不感知 UI 层，不依赖 Android 框架（尽量） |

### 3. State 的三种表达形式

```kotlin
// A. 多个独立状态（简单页面常用）
class SimpleVM : ViewModel() {
    var name = MutableStateFlow("")
    var loading = MutableStateFlow(false)
}

// B. 不可变 State 数据类（推荐：状态一致性最好）
data class UserUiState(
    val loading: Boolean = false,
    val user: User? = null,
    val error: String? = null
)
class UserVM : ViewModel() {
    private val _state = MutableStateFlow(UserUiState())
    val state: StateFlow<UserUiState> = _state.asStateFlow()
}

// C. Sealed Interface 建模互斥状态（推荐：让非法状态不可表示）
sealed interface UserUiState {
    data object Loading : UserUiState
    data class Success(val user: User) : UserUiState
    data class Error(val message: String) : UserUiState
}
```

> 面试点：B 是"平铺"，C 是"互斥"。C 的优势是编译器帮你穷举所有分支，**不会出现 `loading=true` 且 `user!=null` 的矛盾态**。

### 4. 生命周期与配置变更

- `ViewModel` 的存活范围是 **Activity/Fragment 的 `onCreate` 到 `onDestroy`（真正销毁）**，**不包括配置变更（横竖屏）**——所以旋转屏幕状态不丢。
- **进程被系统杀死后重建**（后台久了被回收再回来）：ViewModel 也会丢，需要用 `SavedStateHandle` 保存**少量、可序列化的关键状态**（如搜索关键字、列表滚动位置、选中 ID）。
- `viewModelScope` 在 `onCleared()` 时自动取消所有协程，避免泄漏。

### 5. 依赖注入的角色

MVVM 里的 ViewModel 需要 Repository，Repository 需要 Retrofit/Room。这些依赖应该**由外部注入**，而不是在 ViewModel 内部 `new`：

```kotlin
class UserVM @Inject constructor(
    private val repo: UserRepository
) : ViewModel() { /* ... */ }
```

这样单测时换成 Fake 即可，这就是为什么 MVVM 常和 Hilt/Dagger 一起出现（详见 [[../依赖注入/Hilt]]）。

## 代码示例

### 1. ViewModel：State + StateFlow + viewModelScope

```kotlin
class UserListViewModel @Inject constructor(
    private val repository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserListUiState())
    val uiState: StateFlow<UserListUiState> = _uiState.asStateFlow()

    // 一次性事件用 Channel，而不是塞进 State
    private val _events = Channel<UserListEvent>(Channel.BUFFERED)
    val events: Flow<UserListEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { repository.getUsers() }
                .onSuccess { list ->
                    _uiState.update { it.copy(loading = false, users = list) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(loading = false, error = e.message ?: "加载失败")
                    }
                }
        }
    }
}

data class UserListUiState(
    val loading: Boolean = false,
    val users: List<User> = emptyList(),
    val error: String? = null
)

sealed interface UserListEvent {
    data class ShowToast(val message: String) : UserListEvent
    data class NavigateToDetail(val userId: String) : UserListEvent
}
```

### 2. View（传统 View + ViewBinding）

```kotlin
class UserListActivity : AppCompatActivity() {

    private val viewModel: UserListViewModel by viewModels()
    private lateinit var binding: ActivityUserListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 收集 State —— 生命周期感知，界面停止时自动取消
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.progress.isVisible = state.loading
                    binding.errorText.isVisible = state.error != null
                    binding.errorText.text = state.error
                    binding.recycler.adapter = UserAdapter(state.users)
                }
            }
        }

        // 2. 收集一次性事件
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is UserListEvent.ShowToast ->
                            Toast.makeText(this@UserListActivity, event.message, Toast.LENGTH_SHORT).show()
                        is UserListEvent.NavigateToDetail ->
                            startActivity(DetailActivity.intent(this@UserListActivity, event.userId))
                    }
                }
            }
        }

        // 3. 用户交互 -> 抛给 ViewModel
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
    }
}
```

### 3. View（Jetpack Compose）

```kotlin
@Composable
fun UserListRoute(viewModel: UserListViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(Modifier.fillMaxSize()) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            state.error != null -> Text("出错了：${state.error}")
            else -> LazyColumn {
                items(state.users) { user ->
                    Text(user.name, modifier = Modifier.clickable { /* -> VM */ })
                }
            }
        }
    }
}
```

### 4. ViewModel 的单元测试（不依赖 Android 环境）

```kotlin
class UserListViewModelTest {

    private val repo = FakeUserRepository()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `refresh emits users`() = runTest(dispatcher) {
        val vm = UserListViewModel(repo)
        vm.refresh()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals(2, state.users.size)
    }
}
```

## 常见问题 / 面试要点

**读者问：MVVM 的核心思想是什么？为什么 Google 官方推荐它？**

**AI答：**

1. **核心思想：单向数据流（UDF）**。数据只有一条流动方向：`用户操作 → ViewModel → State → View`。View 不再自己改自己，而是"声明当前状态，渲染当前状态"。
2. **解决的三个真实痛点**：
   - **逻辑可测**：ViewModel 是纯 Kotlin，不碰任何控件 → 纯 JVM 单测，CI 上毫秒级跑完。
   - **状态可保活**：旋转屏幕、切后台再回来，状态不丢（ViewModel 存活于配置变更）。
   - **职责可分离**：新人只改 UI 不用动业务，业务同学/测试能直接读 ViewModel 逻辑。
3. **Google 推荐它的原因**：Jetpack 官方库（ViewModel / LiveData / StateFlow / Lifecycle）全都以 MVVM 为前提设计；Jetpack Compose 官方的 `StateHolder`、`hiltViewModel()`、Lifecycle-aware 收集，全都是在 MVVM 上叠加能力。**Compose + MVVM 是官方推荐的默认组合**。
4. **一句话总结**：MVVM 不是"分了三层"这么简单，它是把"**状态**"从 View 里抽出来集中管理，让 UI 变成状态的一个**纯函数渲染结果**：`UI = f(State)`。

---

**读者问：View 的事件怎么传到 ViewModel？为什么不能让 View 直接调 Repository？**

**AI答：**

1. **传递路径**：`View 控件监听器 / Compose 的 onClick → 调用 ViewModel 的方法（如 `viewModel.onRefreshClick()`)`。
2. **为什么不直接调 Repository**：
   - **状态没有统一出口**。多个数据源（网络 + 数据库 + 缓存）同时更新界面时，状态分散在各控件里，容易出现"下拉刷新完成了但列表还是旧的"。
   - **无法单测**。Repository 调用逻辑一旦写在 View 里，就必须跑起来才能测，CI 覆盖率几乎为 0。
   - **无法复用/无法集中做副作用**（埋点、跳转、Toast）——散落在 View 的回调里，埋点会漏。
   - **无法复用逻辑**。同一个"登录成功后的处理"要写在两个页面就是复制粘贴。
3. **View 直接调 ViewModel 的方法即可**，ViewModel 内部再调 Repository，这一层间接层就是"解耦"的价值。

---

**读者问：LiveData 和 StateFlow 在 MVVM 里怎么选？可以混用吗？**

**AI答：**

先给结论：**新项目纯 Compose 优先 StateFlow；有 ViewBinding / Fragment 且团队熟悉 LiveData 就可以 LiveData；同一个模块内不要混用两套响应式。**

1. **LiveData 特点**：**生命周期感知**（只在 STARTED 之后才回调，页面不可见自动暂停）、**粘性**（数据会缓存，最后一个值不会丢）、`observe` 写法直观。
2. **StateFlow 特点**：是 Kotlin 协程的 `Flow`，**不感知生命周期**（需要 `collectAsStateWithLifecycle()` 才获得生命周期感知能力）、**粘性 + 合流（conflation）**（相同值用 `equals` 比较会被去重，天然适合"状态"）、**多协程安全**（`MutableStateFlow` 的 `update{}` 是原子操作）。
3. **关键差异**：
   - LiveData 的 `setValue` 在**主线程**，StateFlow 可以从任意线程 `update`。
   - LiveData 的分发是"**每个 Observer 独立收到一次**"，StateFlow 是"**共享一个上游**"（上游只执行一次，多个收集者共享）——这是 StateFlow 更省资源的原因。
   - LiveData 不适合处理"**一次性事件**"（会重复消费），StateFlow 同样会遇到，需要 `Channel`/`SharedFlow` 解决。
4. **混用的坑**：如果 View 用 LiveData 收集、VM 内部用 StateFlow，需要在 VM 里暴露两个流，容易出现"两个真相源"。要么统一，要么在 VM 里做单向桥接（StateFlow → `asLiveData()`）。

详见 [[../Jetpack库/LiveData与StateFlow]]。

---

**读者问：ViewModel 里能放 Context 吗？需要做进程恢复该怎么做？**

**AI答：**

1. **能放什么 Context**：
   - **可以**放 `Application`（`AndroidViewModel` / `@ApplicationContext` 注入的 Context）。它生命周期 = App 生命周期，不会泄漏。
   - **绝对不可以**放 `Activity`/`Fragment`（或任何持有它们的 View），这会造成内存泄漏，而且 ViewModel 比 Activity 活得久，会拿到已经销毁的界面对象。
   - 需要"界面级 Context"（如 Dialog、InputMethodManager）时，用 `viewModelScope` + `MutableSharedFlow` 把事件抛给 View 层执行。
2. **进程恢复**：
   - `ViewModel` 只能扛住**配置变更**（旋转/分屏/语言切换），扛不住**进程死亡**。
   - 进程死亡后要恢复的关键状态，必须放进 `SavedStateHandle`（内部用 `Bundle` 持久化到 `savedInstanceState`）。
   - 适合存：搜索关键字、当前 Tab、列表位置、选中 ID、表单草稿。
   - **不适合存**：大列表（Bundle 有大小限制与 TransactionTooLargeException 风险）、文件、复杂对象（要转成可序列化的小对象）。
3. **示例**：

```kotlin
class SearchViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    val query: StateFlow<String> = savedState.getStateFlow("query", "")

    fun onQueryChanged(q: String) {
        savedState["query"] = q
    }
}
```

---

**读者问：面试时让你现场画 MVVM 的数据流，怎么画？**

**AI答：**

按这 4 层、3 个方向画，30 秒内讲清：

```
┌──────────────────────────────────────────────┐
│ View (Activity / Fragment / Composable)      │
│  - 渲染 state（collect / collectAsState）      │
│  - 抛事件 onClick -> vm.onXxx()               │
└───────────────▲──────────────────▲──────────┘
                │ State 向下         │ Event/Intent 向上
                │                   │
┌───────────────┴───────────────────┴──────────┐
│ ViewModel                                     │
│  - 持有唯一 State（StateFlow / LiveData）      │
│  - viewModelScope.launch { ... }              │
│  - 事件出口 Channel/SharedFlow                │
└───────────────────────▲──────────────────────┘
                        │ 依赖注入（Hilt）
┌───────────────────────┴──────────────────────┐
│ Model: Repository → RemoteDataSource /        │
│        LocalDataSource (Room / DataStore)     │
└──────────────────────────────────────────────┘
```

3 个必须说出口的点：

1. **箭头是单向的**：State 只往下，事件只往上。
2. **ViewModel 不 import 任何 `android.view` / `Activity` 类**——这是可测试性的物理保证。
3. **依赖由容器注入**：Repository 由 Hilt 给，不是 `new` 出来的，所以可替换为 Fake 做单测。

---

**读者问：MVVM 有什么缺点？什么场景不适合用？**

**AI答：**

1. **ViewModel 容易变成"第二个 Activity"**。如果把导航逻辑、Toast、埋点、格式化全塞进 VM，VM 会长到几千行。**解法**：状态放 VM，**副作用（导航、Toast、Snackbar）走事件流由 View 执行**，格式化用状态映射（`uiState` 直接给出最终要显示的字符串）。
2. **状态爆炸**。多个 Boolean 标志容易组合出非法态（如 `loading && error != null`）。**解法**：用 sealed interface 建模互斥状态。
3. **数据传递成本**。多页面共享状态需要额外的方案（如 `StateFlow` + `SharedFlow`、单例 Repository 里持有 `StateFlow`、Navigation 传参）。MVVM 本身不提供"跨页面共享状态"的标准方案——这是 MVI 或引入"共享 VM"后的课题。
4. **不适合的场景**：页面极简单（一个 Toast 按钮）时套 MVVM 是过度设计；或者团队完全由 Compose + 单向数据流 + `remember` 就够用的小项目。
5. **真正的价值区间**：**中大型、多人协作、需要长期维护、逻辑复杂**的项目。

## 关联笔记

- [[MVC与MVP]]：前身与对比
- [[MVI]]：更严格的单向数据流
- [[架构模式选型]]：四种模式横向对比
- [[../Jetpack库/Lifecycle与ViewModel]]：ViewModel 的生命周期与作用域
- [[../Jetpack库/LiveData与StateFlow]]：响应式数据载体
- [[../依赖注入/Hilt]]：为 ViewModel 注入 Repository
- [[../测试/单元测试]]：ViewModel 的单测写法
- [[../README]]：第 7 部分总览

## 参考资料

- Google 官方架构指南：https://developer.android.com/topic/architecture
- Google "Learn Jetpack Compose / Architecture" 课程中的 MVVM 与单向数据流章节
- 《Android 高级编程》第 4 版 · 第 7 章
- 《重构》第 2 版 · "用 State 承载状态"与单向数据流思想
