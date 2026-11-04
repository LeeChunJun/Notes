---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 中级
created: 2026-11-04
updated: 2026-11-04
related: "[[Lifecycle与ViewModel]], [[../架构模式/MVVM]], [[../架构模式/MVI]]"
---

# LiveData与StateFlow

## 一句话定义

LiveData 与 StateFlow 都是**可观察的数据容器**，但前者是**框架级、生命周期感知**的（AndroidX Lifecycle 提供），后者是**Kotlin 协程级**的（自带粘性、原子更新、合流去重），两者都是 MVVM 中"状态向下流动"的载体。

## 核心原理

### 1. 横向对比

| 维度 | LiveData | StateFlow |
| --- | --- | --- |
| 所属 | AndroidX `lifecycle-livedata` | Kotlin `kotlinx.coroutines` |
| 感知生命周期 | **是**（`observe(owner)` 自动暂停/恢复） | **否**（需 `collectAsStateWithLifecycle()` 才具备） |
| 是否粘性 | 是（始终持有最新值，重新观察会立即回调） | 是（始终持有 `value`） |
| 数据类型 | 任意 `T` | 任意 `T` |
| 更新方式 | `setValue`（主线程）/ `postValue`（任意线程） | `value =`（线程安全）/ `update {}`（CAS 原子更新） |
| 线程安全 | `postValue` 会合并多次调用 | `update {}` 是原子的 read-modify-write |
| 相等值 | **会**再次分发（`setValue` 相同值也通知） | **不会**（`equals` 相同即跳过，`StateFlow` 基于 `equals` 合流） |
| 多个观察者 | 每个观察者**独立**收到分发 | **共享**一个上游，上游只执行一次 |
| 一次性事件 | 不支持，需自己封装 `Event` | 不支持，需 `Channel`/`SharedFlow` |
| 与 Compose | `observeAsState()`（需 `runtime-livedata`） | `collectAsStateWithLifecycle()` |
| 与 RxJava | `asObservable()` | `asFlow()` / `asObservable()` |

### 2. LiveData 的分发机制

```text
setValue(v)
   └─► dispatchingValue = true
        └─► 遍历所有活跃的 Observer
             └─► observer.onChanged(v)   // 在主线程
        └─► dispatchingValue = false
```

关键点：

- `setValue` 必须在**主线程**调用，否则抛 `IllegalStateException`；`postValue` 会把值**post 到主线程 Looper**。
- `postValue` 在主线程队列繁忙时，多次调用**只保留最后一个值**（coalescing）。
- `LiveData` 内部用 `mData` 缓存最新值；新观察者注册时，若已有值会**立即回调一次**（粘性）。
- `observe(owner, observer)` 会依据 `owner` 的生命周期状态决定是否立即分发：`STARTED`/`RESUMED` 才分发，`CREATED` 时会**记住这次订阅，等进入 STARTED 再分发**。

### 3. StateFlow 的三个核心性质

1. **粘性（Stateful）**：永远持有一个 `value`，新的收集者立刻拿到当前值 → 天然适合"当前状态"。
2. **合流（Conflation）**：`MutableStateFlow` 内部用 `equals` 比较新旧值，相等则**不通知** → 避免因重复赋值导致的无效重组。
3. **原子性**：`update {}` 基于 CAS 循环实现 read-modify-write，多线程并发更新不会丢失：

```kotlin
// 安全
flow.update { it.copy(count = it.count + 1) }

// 不安全（read 与 write 之间可能被其他线程插入）
flow.value = flow.value.copy(count = flow.value.count + 1)
```

此外，`StateFlow` 是**热流（Hot Flow）**：上游独立于收集者运行，没有收集者时 `value` 仍会更新。

### 4. 一次性事件的正确处理

`LiveData` 和 `StateFlow` 都会**重复分发**已消费的事件（例如旋转屏幕后重新订阅 → 再次收到"请弹 Toast"）。三种解决方案：

```kotlin
// 方案 A：Channel（推荐，保证恰好消费一次）
private val _events = Channel<Event>(Channel.BUFFERED)
val events = _events.receiveAsFlow()

// 方案 B：SharedFlow + replay = 0（不重放，但可能丢事件）
private val _events = MutableSharedFlow<Event>(replay = 0, extraBufferCapacity = 1)

// 方案 C：LiveData 包装 Event（老项目常用）
class Event<out T>(private val content: T) {
    var hasBeenHandled = false
        private set
    fun getContentIfNotHandled(): T? =
        if (hasBeenHandled) null else { hasBeenHandled = true; content }
}
```

> Channel 是"**收件箱**"模型（每个事件被一个消费者取走即消失），SharedFlow 是"**广播**"模型（每个收集者都收到，但受 `replay` 与缓冲影响）。

### 5. `asLiveData()`：StateFlow → LiveData 桥接

```kotlin
val usersLiveData: LiveData<List<User>> = usersFlow.asLiveData()
```

- `asLiveData()` 会自动在 **5 秒后**停止收集上游（默认 `timeoutInMs`），避免后台空耗。
- 适合"已有 StateFlow 逻辑，但新页面想用 LiveData"的渐进迁移场景。
- **不要双向桥接**（LiveData → StateFlow → LiveData），会引入重复分发与内存开销。

## 代码示例

### 1. LiveData：传统 View 中的 MVVM

```kotlin
class UserListViewModel(private val repo: UserRepository) : ViewModel() {

    private val _users = MutableLiveData<List<User>>(emptyList())
    val users: LiveData<List<User>> = _users          // 对外只读

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    fun load() {
        _loading.postValue(true)                      // 任意线程安全
        viewModelScope.launch {
            val result = runCatching { repo.getUsers() }
            result.onSuccess { _users.value = it }     // 主线程赋值
            result.onFailure { _loading.postValue(false) }
            _loading.postValue(false)
        }
    }
}
```

```kotlin
// View：自动感知生命周期，页面不可见时停止分发
viewModel.users.observe(this) { list -> binding.recycler.adapter = UserAdapter(list) }
viewModel.loading.observe(this) { binding.progress.isVisible = it }
```

### 2. StateFlow：Compose 中的 MVVM

```kotlin
class UserListViewModel(private val repo: UserRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(UserListUiState())
    val uiState: StateFlow<UserListUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            runCatching { repo.getUsers() }
                .onSuccess { list -> _uiState.update { it.copy(loading = false, users = list) } }
                .onFailure { e -> _uiState.update { it.copy(loading = false, error = e.message) } }
        }
    }
}
```

```kotlin
// Compose：collectAsStateWithLifecycle = collectAsState + repeatOnLifecycle(STARTED)
val state by viewModel.uiState.collectAsStateWithLifecycle()
```

### 3. 用 `stateIn` 把 Flow 变成可共享的状态

```kotlin
class UserVM(private val repo: UserRepository) : ViewModel() {

    // 冷流 -> 热流：多个收集者共享同一次上游执行
    val users: StateFlow<List<User>> = repo.observeUsers()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),  // 5s 内无订阅则停止上游
            initialValue = emptyList()
        )

    val isEmpty: StateFlow<Boolean> = users
        .map { it.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
```

### 4. StateFlow + Channel 的标准组合

```kotlin
class UserVM(private val repo: UserRepository) : ViewModel() {

    private val _state = MutableStateFlow(UserState())
    val state: StateFlow<UserState> = _state.asStateFlow()

    private val _events = Channel<UserEvent>(Channel.BUFFERED)
    val events: Flow<UserEvent> = _events.receiveAsFlow()

    fun onRefresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { repo.refresh() }
                .onSuccess { _events.send(UserEvent.Refreshed) }
                .onFailure { e -> _events.send(UserEvent.ShowError(e.message.orEmpty())) }
            _state.update { it.copy(loading = false) }
        }
    }
}
```

### 5. Compose 侧：状态 + 事件消费

```kotlin
@Composable
fun UserListRoute(vm: UserListViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UserEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
                is UserEvent.Refreshed -> { /* ... */ }
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        // ... 渲染 state
    }
}
```

### 6. LiveData 事件包装器（旧项目迁移期使用）

```kotlin
class Event<out T>(private val content: T) {
    private var handled = false
    fun getContentIfNotHandled(): T? = if (handled) null else { handled = true; content }
    fun peekContent(): T = content
}
```

## 常见问题 / 面试要点

**读者问：LiveData 和 StateFlow 到底有什么区别？我该用哪个？**

**AI答：**

按 **"本质差异 → 行为差异 → 选型建议"** 三步答：

1. **本质差异**：
   - LiveData 是 **Android 框架级**的观察者模式实现，内部持有 `LifecycleOwner` 引用来控制分发。
   - StateFlow 是 **Kotlin 协程的 Flow 实现**，遵循协程的**结构化并发**与**冷/热流**语义，本身完全不知道 Android 生命周期。
2. **关键行为差异**（这三条最容易考）：
   - **生命周期感知**：LiveData 原生具备；StateFlow **没有**，必须靠 `collectAsStateWithLifecycle()` 或 `repeatOnLifecycle` 补齐。
   - **相等值分发**：LiveData `setValue` 相同值**仍会通知**；StateFlow 基于 `equals` **会去重**（这是 Compose 减少无效重组的关键）。
   - **上游共享**：LiveData 每个 Observer 独立分发；StateFlow 多收集者**共享一个上游**，只执行一次。
3. **选型建议**：
   - **新项目 / Compose 优先 StateFlow**：协程原生、与 Compose 配合最好、无生命周期陷阱。
   - **纯 View 体系的小项目 / 团队 LiveData 熟练**：LiveData 也没问题，代码更短。
   - **同一个模块内不要混用**：LiveData 和 StateFlow 两套并存时容易出现"两个真相源"。
   - **迁移期**可以 `asLiveData()` 单向桥接。

---

**读者问：LiveData 会不会内存泄漏？它是怎么避免的？**

**AI答：**

1. **会泄漏的写法**（真的存在过）：

```kotlin
// 错误：Activity 作为观察者被 LiveData 强引用，Activity 销毁后无法回收
class MyVM : ViewModel() {
    val live = MutableLiveData<String>()
}
activity.live.observeForever { /* ... */ }   // 强引用 Activity
```

2. **LiveData 的两道防线**：
   - **`observe(owner, observer)`**：观察者被包装成 `LifecycleBoundObserver`，**弱引用**持有 `LifecycleOwner`（实际是 `WeakHashMap` 存 Observer，`observe` 时用 `WeakReference` 包 Observer），并且在 `owner` 销毁时**自动移除**该观察者。
   - **`observeForever` 不具备这两点**：既不绑定生命周期，也不自动移除 → **必须手动 `removeObserver` / `removeObservers(owner)`**，否则泄漏。
3. **ViewModel 里的 LiveData 到底会不会泄漏 Activity**：**不会**，前提是用 `observe(owner)` 且 owner 是 Activity/Fragment。LiveData 内部会检测 `owner` 进入 `DESTROYED` 并**自动解绑**。
4. **仍然要注意**：如果 LiveData 里放的是 **Activity/View 类型的对象**（比如 `MutableLiveData<Activity>()`），那就是明确的泄漏源 —— 状态里绝不放界面对象。
5. **StateFlow 的对比**：StateFlow 不感知生命周期，**完全依赖你自己在 `onCleared()` 取消 `viewModelScope`**。用 `viewModelScope` + `stateIn` 就没有泄漏风险；用 `GlobalScope` 就有。

---

**读者问：StateFlow 怎么保证线程安全？为什么推荐用 `update {}` 而不是直接赋值？**

**AI答：**

1. **StateFlow 的线程安全机制**：内部状态用一个 `AtomicReference` 持有，赋值时用 **CAS（compareAndSet）** 循环：
   - 读 `value` 是原子读；
   - 写 `value` 内部是 `compareAndSet(old, new)`，失败则重试。
   - 所以"赋值"本身是安全的。
2. **但 `value = f(value)` 不是原子的**：读和写之间存在时间窗口。

```kotlin
// 有竞态：两个线程同时 +1，结果可能只 +1
counter.value = counter.value + 1

// 正确：update 内部是原子的 CAS 循环
counter.update { it + 1 }
```

3. **`update` 的实现**（简化）：

```kotlin
public inline fun <T> MutableStateFlow<T>.update(function: (T) -> T) {
    while (true) {
        val prevValue = value
        val nextValue = function(prevValue)
        if (compareAndSet(prevValue, nextValue)) return
    }
}
```

4. **另一个附带好处**：`update` 的 lambda 可能会被**多次调用**（CAS 失败重试），所以 lambda 里**不能有副作用**（不要在里面发请求、改日志计数）。如果需要副作用，把副作用放到 `update` 之后。

5. **与 `MutableLiveData.postValue` 的对比**：
   - `postValue` 是"**合并**"语义：主线程繁忙时多次 post 只保留最后一个值；
   - `StateFlow.update` 是"**不丢更新**"语义：每次调用都会基于最新值计算（CAS 重试），不会丢。
   - **需要"每个中间值都不能丢"**（如计数器）用 StateFlow + `update`；**只需要"最新值"**（如搜索关键字）两者都可以。

---

**读者问：为什么用 `collectAsStateWithLifecycle()` 而不是 `collectAsState()`？**

**AI答：**

1. **`collectAsState()` 的问题**：它在 Composable **进入组合时就开始收集，退出组合时取消**，但**不感知界面可见性**。后果：
   - 界面不可见（切到后台、跳到别的页面）时**仍在收集** → 后台持续发请求、浪费电量；
   - 状态更新时即使界面不可见也会重组 → 无效计算。
2. **`collectAsStateWithLifecycle()` 的做法**：内部等价于

```kotlin
val lifecycle = LocalLifecycleOwner.current.lifecycle
val state by flow.collectWithLifecycle(lifecycle)   // 生命周期感知版本
```

   它使用 **`repeatOnLifecycle(Lifecycle.State.STARTED)`**：
   - 界面 `STARTED` → 开始收集；
   - 界面低于 `STARTED` → **取消**收集；
   - 再次 `STARTED` → 重新收集（StateFlow 粘性，会立刻给出最新值）。
3. **附加好处**：`collectAsState()` 在收集 Flow 时使用的是 `compositionContext`（本质是 recomposer 的帧时钟），生命周期感知版本用 `Dispatchers.Main.immediate`，**不会因为帧时钟而延迟到下一帧**。
4. **什么时候可以用 `collectAsState()`**：界面不会离开组合的场景几乎不存在，**官方一律推荐 `collectAsStateWithLifecycle()`**。另外 `produceState`/`LaunchedEffect` 也有对应的 `produceStateWithLifecycle`。
5. **版本要求**：`collectAsStateWithLifecycle` 需要 `lifecycle-runtime-compose` 2.6+ / Compose 1.6+（`lifecycle-runtime-compose` 依赖）。

---

**读者问：如何处理"一次性事件"（Toast、导航、Snackbar）？各种方案的坑？**

**AI答：**

先说**为什么会有问题**：事件（"弹个 Toast"）与状态（"用户名是 Tom"）语义不同。事件是**过去式**，如果作为状态放进 StateFlow/LiveData，重新订阅时会被**再次消费** → 旋转屏幕后 Toast 又弹一次、页面又跳一次。

1. **方案 A：`Channel`（当前最佳实践）**

```kotlin
private val _events = Channel<Event>(Channel.BUFFERED)
val events = _events.receiveAsFlow()
```

   - **恰好一次**语义：每个事件被一个消费者取走后即消失；
   - `BUFFERED`（默认 64）+ `send` 挂起不阻塞；用 `trySend` 需自行处理失败；
   - `receiveAsFlow()` 支持多个收集者，但**事件只会被其中一个拿到**（"竞争消费"），若需要每个收集者都收到，用 `Channel` + `shareIn` 或直接用 SharedFlow。
2. **方案 B：`SharedFlow`**
   - `MutableSharedFlow<Event>(replay = 0, extraBufferCapacity = 1)`：**不重放**，重新订阅拿不到历史事件；但 `replay = 0` 时，若当前无订阅者，`emit` 会**挂起**（或用 `tryEmit` 直接丢）。
   - `replay = 1`：会重放最后一条 → **重现"旋转后又弹一次"的 bug**，要慎用。
   - 适合"**每个订阅者都要收到**"的场景（如全局提示）。
3. **方案 C：LiveData + `Event` 包装器**（老项目常用）
   - 用 `getContentIfNotHandled()` 消费一次；但 `hasBeenHandled` 存在对象里，**多订阅者场景不安全**，且代码较啰嗦。
4. **结论**：`Channel` 适合"**单消费者的一次性事件**"；`SharedFlow(replay=0)` 适合"**广播型事件**"；状态永远放 `StateFlow`，不要混。

---

**读者问：StateFlow 什么时候会"丢失"中间值？我需要注意什么？**

**AI答：**

1. **StateFlow 是合流（conflation）的**：连续多次快速更新，**中间的中间值可能不会被观察到**。例如 `count` 从 0 连加 100 次，收集者可能只看到最终值 100。
2. **这是设计如此**：StateFlow 表达的是"**当前状态**"，而不是"**事件流**"。中间值没有意义时（如进度条百分比）可以忽略。
3. **需要每个中间值都收到时**：
   - 用 **`SharedFlow`**（不 conflate，且 `replay=0` 时保证每个 emit 都被当前订阅者看到）；
   - 或用 **`Channel`**（不合并，先进先出）。
4. **注意 StateFlow 的"合流"还有另一层含义**：当上游 Flow 产生大量值而收集者处理较慢时，**中间的中间值会被丢弃**（这是背压处理，不是 bug）。
5. **另一个常见误解**：StateFlow 的 `value` **永远不为 null**（类型非空时），没有"初始 null"问题；这是它相对 `LiveData`（初始为 null）的一个优势——`MutableStateFlow` 必须有初始值，避免了"null 表示加载中"这类脏状态。

## 关联笔记

- [[Lifecycle与ViewModel]]：生命周期感知的基础
- [[../架构模式/MVVM]]：状态驱动的 ViewModel 写法
- [[../架构模式/MVI]]：Intent 与 Effect 的通道设计
- [[../架构模式/MVC与MVP]]：RxJava 时代 LiveData 的替代品
- [[../README]]：第 7 部分总览

## 参考资料

- Android 官方 · LiveData：https://developer.android.com/topic/libraries/architecture/livedata
- Android 官方 · StateFlow：https://developer.android.com/topic/libraries/architecture/stateflow
- Kotlin 官方 · StateFlow 与 SharedFlow：https://kotlinlang.org/docs/flow.html#stateflow
- 《Jetpack Compose 实战》· 状态与副作用章节
