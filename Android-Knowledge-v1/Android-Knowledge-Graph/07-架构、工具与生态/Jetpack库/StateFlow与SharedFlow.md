---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 中级
created: 2026-11-12
updated: 2026-11-12
related: "[[LiveData与StateFlow]], [[Lifecycle与ViewModel]], [[Lifecycle]], [[../架构模式/MVVM]], [[../架构模式/MVI]]"
---

# StateFlow与SharedFlow

## 一句话定义

`StateFlow` 与 `SharedFlow` 都是 `kotlinx.coroutines` 提供的**热流（Hot Flow）**，但语义完全相反：**`StateFlow` 表达"当前状态"**（唯一最新值、必然粘性、按 `equals` 去重、可原子更新），**`SharedFlow` 表达"事件流"**（没有当前值、重放与缓冲可配、广播给所有订阅者）——一句话选型：**状态用 `StateFlow`，一次性事件用 `SharedFlow`**。

## 核心原理

### 1. 横向对比

| 维度 | StateFlow | SharedFlow |
| --- | --- | --- |
| 所属 | Kotlin `kotlinx.coroutines`（`StateFlow` 接口） | Kotlin `kotlinx.coroutines`（`SharedFlow` 接口） |
| 表达语义 | **状态**（现在是什么样） | **事件**（发生了什么） |
| 是否有 `value` | **有**（`StateFlow.value`，随时同步可读） | **没有**（只能 `collect`） |
| 初始值 | **必须有**（`MutableStateFlow(initialValue)`） | **没有**（`MutableSharedFlow()` 无初始值） |
| 是否粘性 | **必然是**（`replay` 固定为 1 的语义） | **可配置**（`replay = 0` 时不粘性） |
| 重复值 | **自动去重**（`equals` 相等不通知） | **不去重**（每次 `emit` 都分发，需手动 `distinctUntilChanged`） |
| 原子更新 | **有**（`update {}` / `getAndUpdate` / `updateAndGet`，CAS 循环） | **无**（只有 `emit` / `tryEmit`） |
| 背压 | 不适用（永远只保留最新值） | **有**（`replay` + `extraBufferCapacity`，`emit` 可能挂起） |
| 中间值 | **会丢**（合流 conflation） | **不丢**（缓冲范围内每个 `emit` 都会送达） |
| 多订阅者 | **共享**一个上游与一个 `value`（读同一份状态） | **共享**一个上游，**广播**给所有订阅者（人人一份） |
| 订阅者语义 | 概念上只有"一个读者"（多屏读同一状态） | N 个读者各收一份（像收音机） |
| 生命周期感知 | 否（需 `collectAsStateWithLifecycle()`） | 否（且 `replay = 0` 时停机期间的事件**永久丢失**） |
| 与 Compose | `collectAsStateWithLifecycle()`（无参重载） | `LaunchedEffect { collect {} }` 或 `collectAsState(initial)` |
| 与 LiveData | `asLiveData()` 单向桥接 | 无官方直连，需自己 `Flow.asLiveData()`（冷流，不推荐） |
| 典型用途 | UI 状态、列表数据、加载/错误标志、输入框内容 | Toast、导航、Snackbar、埋点、跨模块广播 |

### 2. StateFlow 的三大性质

1. **粘性（Stateful）**：内部永远持有一个 `value`，新订阅者立刻拿到当前值 → 天然适合"当前状态"。
2. **合流（Conflation）**：内部用 `equals` 比较新旧值，相等则**不通知** → 避免重复赋值导致的无效重组。
3. **原子性**：`update {}` 基于 CAS 循环实现 read-modify-write，多线程并发更新不丢：

```kotlin
// 安全：update 内部是 CAS 循环
flow.update { it.copy(count = it.count + 1) }

// 不安全：read 与 write 之间可能被其他线程插入
flow.value = flow.value.copy(count = flow.value.count + 1)
```

> [!warning] ⚠️ `update` 的 lambda 可能有副作用
> CAS 失败会重试，所以 `update` 的 lambda 可能**执行多次**。不要在里面发请求、打日志计数，副作用放到 `update` 之后。

### 3. SharedFlow 的三个构造参数

```kotlin
public fun <T> MutableSharedFlow(
    replay: Int = 0,
    extraBufferCapacity: Int = 0,
    onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND
): MutableSharedFlow<T>
```

- **`replay`**：重放缓存大小。新订阅者订阅时**立刻**收到最近 `replay` 个值。
  - `0` = 不重放，新订阅者拿不到任何历史值；
  - `1` = 保留最后一个值（等价于"粘性"）；
  - `> 1` = 保留最近 N 个值。
- **`extraBufferCapacity`**：额外缓冲容量，给"处理不过来"的订阅者兜底。
  - `0` + `SUSPEND` = **会合（rendezvous）语义**：`emit` 必须等到**所有**订阅者都取走才返回 → **最慢的订阅者决定 `emit` 的速度**；
  - `> 0` = 每个订阅者多出 N 个缓冲位，慢一点也不会立刻拖住 `emit`。
- **`onBufferOverflow`**：缓冲满时的策略。
  - `SUSPEND`（默认）：`emit` **挂起**等空间；`tryEmit` 返回 `false` 并**丢值**；
  - `DROP_LATEST`：丢**新值**、保留旧值；`emit` 不挂起；
  - `DROP_OLDEST`：丢**最旧值**、保留新值；`emit` 不挂起。

缓冲区总大小 = `replay + extraBufferCapacity`。

### 4. 背压：`emit` 与 `tryEmit`

```text
emit(value)   // 挂起函数
  └─► 缓冲区有空间？
       ├─ 是 → 写入缓冲 → 唤醒订阅者 → 立即返回
       └─ 否 → onBufferOverflow == SUSPEND → 挂起，等订阅者消费腾位置
                onBufferOverflow == DROP_LATEST/DROP_OLDEST → 丢弃或替换 → 立即返回

tryEmit(value)   // 非挂起函数
  └─► 缓冲区有空间？
       ├─ 是 → 写入 → 返回 true
       └─ 否 → 返回 false（值被丢弃，调用方必须自己处理）
```

> [!warning] ⚠️ `tryEmit` 返回 `false` 时事件就没了
> `tryEmit` 只是"不挂起版本"的 `emit`，**返回值必须检查**。忽略返回值 = 静默丢事件。
> 需要"绝不丢"的场景请用 `emit`（挂起，背压保证送达）或 `Channel`。

### 5. 没有订阅者时，事件去哪了

- `replay = 0` 且当前**无订阅者**：`emit` **立即返回**（不挂起），但值**无人接收，直接丢弃**。
  → 这就是"用 `SharedFlow` 当事件总线导致事件莫名消失"的根因。
- `replay >= 1`：值进入重放缓存，新订阅者能拿到最后 `replay` 个值。

```text
时间轴 ────────────────────────────────────────────────────►

emit(A)  [无订阅者] → 丢弃（replay = 0）
emit(B)  [有订阅者] → 立即送达
        [订阅者取消]
emit(C)  [无订阅者] → 丢弃（replay = 0）
```

要"事件不丢"，用 `Channel`（点对点、缓冲、恰好一次），而不是 `SharedFlow`。

### 6. 二者的关系：`StateFlow` 是 `SharedFlow` 的特例

类型上：**`public interface StateFlow<out T> : SharedFlow<T>`** —— `StateFlow` 是 `SharedFlow` 的**子接口**，凡是接收 `SharedFlow` 的地方都能传 `StateFlow`（反过来不行）。

语义上：官方把 `StateFlow` 定义为 `SharedFlow` 的特例 —— **`replay = 1` + 按 `equals` 合流**。

```kotlin
// 语义上的近似等价
MutableStateFlow(initial)  ≈  MutableSharedFlow(replay = 1) + distinctUntilChanged() + 原子 value 读写
```

那为什么还要单独提供 `StateFlow`？因为它多给了三样东西：

1. **`value` 属性**：可以**同步读取**当前值（`SharedFlow` 只能 `collect`）；
2. **原子更新 API**：`update` / `getAndUpdate` / `updateAndGet`；
3. **类型层面的保证**：拿到 `StateFlow` 就说明"一定有当前值"，不必担心"没有初始值"。

> 实践建议：**能用 `StateFlow` 的地方就用 `StateFlow`**，它天然去重、天然有初值、天然原子。

### 7. 选型决策

```text
需要表达"当前是什么"？
  ├─ 是 ──────────────────────────────► StateFlow
  └─ 否（表达"发生了什么"）
       ├─ 每个订阅者都要收到（广播）──► SharedFlow
       ├─ 只能有一个消费者，且绝不能丢 ─► Channel
       └─ 只在 ViewModel 内部、不需要外部消费 ─► 不需要流，直接用返回值
```

## 代码示例

### 1. StateFlow：状态的标准写法

```kotlin
class UserListViewModel(private val repo: UserRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(UserListUiState())
    val uiState: StateFlow<UserListUiState> = _uiState.asStateFlow()

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }   // 原子更新；equals 相等时不会通知
    }
}
```

```kotlin
// Compose：collectAsStateWithLifecycle = collectAsState + repeatOnLifecycle(STARTED)
val state by viewModel.uiState.collectAsStateWithLifecycle()

// 传统 View：collectAsStateWithLifecycle 的等价写法
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { state -> render(state) }
    }
}
```

### 2. SharedFlow：事件的标准写法

```kotlin
class UserListViewModel(private val repo: UserRepository) : ViewModel() {

    // replay = 0：不重放；extraBufferCapacity = 1：给慢订阅者留一个缓冲位
    private val _events = MutableSharedFlow<UserEvent>(replay = 0, extraBufferCapacity = 1)
    val events: SharedFlow<UserEvent> = _events.asSharedFlow()

    fun onRefresh() {
        viewModelScope.launch {
            runCatching { repo.refresh() }
                .onSuccess { _events.emit(UserEvent.Refreshed) }          // 用 emit，不丢事件
                .onFailure { e -> _events.emit(UserEvent.ShowError(e.message.orEmpty())) }
        }
    }
}
```

```kotlin
// Compose：SharedFlow 没有 value，只能在 LaunchedEffect 里收集
LaunchedEffect(Unit) {
    viewModel.events.collect { event ->
        when (event) {
            is UserEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
            is UserEvent.Refreshed -> { /* ... */ }
        }
    }
}
```

### 3. ViewModel 组合：StateFlow 管状态 + SharedFlow 管事件

```kotlin
class UserListViewModel(private val repo: UserRepository) : ViewModel() {

    // 状态：唯一当前值，粘性、可原子更新
    private val _uiState = MutableStateFlow(UserListUiState())
    val uiState: StateFlow<UserListUiState> = _uiState.asStateFlow()

    // 事件：广播给所有订阅者，不重放
    private val _events = MutableSharedFlow<UserEvent>(replay = 0, extraBufferCapacity = 1)
    val events: SharedFlow<UserEvent> = _events.asSharedFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            runCatching { repo.getUsers() }
                .onSuccess { list ->
                    _uiState.update { it.copy(loading = false, users = list) }
                    _events.emit(UserEvent.Refreshed)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(loading = false, error = e.message) }
                    _events.emit(UserEvent.ShowError(e.message.orEmpty()))
                }
        }
    }
}
```

> **状态与事件分离**是 MVVM/MVI 的核心纪律：状态可重放、可重建；事件一次性、不重放。两者混在一个流里，旋转屏幕后必然"事件重放"或"事件丢失"。

### 4. `stateIn` vs `shareIn`：冷流转热流

```kotlin
class UserVM(private val repo: UserRepository) : ViewModel() {

    // 冷流 -> StateFlow：自带初始值 + 去重 + 原子 value
    val users: StateFlow<List<User>> = repo.observeUsers()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),  // 5s 内无订阅则停止上游
            initialValue = emptyList()
        )

    // 冷流 -> SharedFlow：replay 由自己决定，0 = 不重放（会丢中间值）
    val results: SharedFlow<SearchResult> = repo.search()
        .distinctUntilChanged()                              // SharedFlow 默认不去重
        .shareIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            replay = 1                                        // 0 = 不重放；1 = 保留最后一个
        )
}
```

| 对比 | `stateIn` | `shareIn` |
| --- | --- | --- |
| 返回类型 | `StateFlow<T>` | `SharedFlow<T>` |
| 是否要初始值 | **要**（`initialValue`） | 不要 |
| 是否有 `value` | 有 | 无 |
| 是否自动去重 | 是（`equals`） | 否（需手动 `distinctUntilChanged`） |
| `replay` 可配 | 否（固定 1） | 是（默认 0） |
| 典型用途 | UI 状态 | 事件流、搜索结果等中间值 |

### 5. Channel vs SharedFlow：三种"一次性事件"载体

```kotlin
// A. Channel：点对点，多个收集者"竞争消费"，事件不丢
private val _toast = Channel<Toast>(Channel.BUFFERED)      // 默认缓冲 64
val toast: Flow<Toast> = _toast.receiveAsFlow()
fun show(msg: String) { _toast.trySend(Toast(msg)) }        // 缓冲满时返回 false

// B. SharedFlow：广播，每个订阅者都收到一份，但可能丢
private val _globalTip = MutableSharedFlow<Tip>(replay = 0, extraBufferCapacity = 1)
val globalTip: SharedFlow<Tip> = _globalTip.asSharedFlow()
fun tip(t: Tip) { _globalTip.tryEmit(t) }

// C. LiveData + Event 包装器：老项目迁移期方案
```

| 维度 | Channel | SharedFlow | LiveData + Event |
| --- | --- | --- | --- |
| 分发模型 | **点对点**（竞争的多个消费者） | **广播**（每个消费者一份） | 广播（但 `Event` 包装器在多订阅者下不安全） |
| 事件是否可能丢 | 基本不丢（有缓冲 + 背压） | **会**（`replay = 0` 且无订阅者时直接丢） | 会（`postValue` 合并多次调用） |
| 生命周期感知 | 否 | 否 | **是**（`observe(owner)`） |
| 重放 | 不重放（消费即消失） | 可配 `replay` | 会重放最新值 |

### 6. Compose 侧消费 SharedFlow 的两种写法

```kotlin
@Composable
fun UserListRoute(vm: UserListViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    // 写法 A：副作用型（Toast、导航）——推荐
    LaunchedEffect(Unit) {
        vm.events.collect { event -> /* ... */ }
    }

    // 写法 B：需要把事件折算成状态时用 collectAsState(initial)
    val lastEvent by vm.events.collectAsState(initial = null as UserEvent?)
}
```

> `collectAsState()` 的**无参重载只接受 `StateFlow`**（因为要读 `value`）；对 `SharedFlow` 要用 `collectAsState(initial)` 或 `LaunchedEffect` + `collect`。

## 常见问题 / 面试要点

**读者问：SharedFlow 和 StateFlow 的本质区别是什么？我该用哪个？**

**AI答：**

按 **"语义 → 行为 → 选型"** 三步答：

1. **语义差异**（最核心）：
   - `StateFlow` = **状态**：容器里永远有且只有**一个当前值**，表达"现在是什么样"。
   - `SharedFlow` = **事件流**：没有"当前值"的概念，表达"发生了什么"，可以发 0 条、1 条、1 万条。
2. **由此派生的行为差异**：
   - **必然粘性 vs 可配**：新订阅者立刻拿到 `StateFlow` 的最新值；`SharedFlow` 要不要给历史由 `replay` 决定。
   - **去重 vs 不去重**：`StateFlow` 按 `equals` 合流，避免无效重组；`SharedFlow` 每次 `emit` 都分发。
   - **原子更新 vs 无**：`StateFlow` 有 `update {}`（CAS），`SharedFlow` 只能 `emit`。
   - **背压**：只有 `SharedFlow` 有缓冲与溢出策略；`StateFlow` 永远只留最新值。
   - **中间值**：`StateFlow` 会丢中间值，`SharedFlow` 在缓冲范围内不丢。
3. **选型建议**：
   - **状态一律 `StateFlow`**（列表、加载中、错误、输入框内容）；
   - **一次性事件用 `SharedFlow`**（Toast、导航、埋点）或 **`Channel`**（不能丢的单消费者事件）；
   - **同一模块不要把状态和事件混在一个流里**，这是旋转屏幕后"事件重放/丢失"的根因。

---

**读者问：`MutableSharedFlow(replay = 0, extraBufferCapacity = 1)` 为什么还是会丢事件？**

**AI答：**

1. **`extraBufferCapacity` 是"每个订阅者"的缓冲，不是全局队列**。没有订阅者时，没有"订阅者的缓冲位"可占用 → 值无处可放 → **直接丢弃**。
2. **`replay = 0`** 意味着连"重放缓存"这一格都不留 → 彻底没有留存位置。
3. **`tryEmit` 更危险**：它返回 `true` 只是表示"写入成功"，在 `replay = 0` 且无订阅者时**值其实已经丢了**，返回 `true` 会让人误以为送达。**必须检查返回值**。
4. **慢订阅者也会丢**：默认 `SUSPEND` 策略下 `emit` 会**挂起**（不丢，但要等）；一旦换成 `DROP_LATEST`/`DROP_OLDEST`，慢订阅者的值就会被丢。
5. **正确做法**：
   - 要"**绝不丢**" → 用 `Channel`（缓冲 + 背压 + 消费即消失）；
   - 要"**每个订阅者都收到**" 且允许极端情况下丢失 → 用 `SharedFlow`，并接受 `replay = 0` 的语义；
   - 要"**新订阅者能补上最后一次**" → `replay = 1`（但注意这会让旋转屏幕后的事件被重放）。
6. **工程解法**：等订阅者就位再发事件，可用 `subscriptionCount`：

```kotlin
private val _events = MutableSharedFlow<Event>(replay = 0, extraBufferCapacity = 1)

// kotlinx.coroutines 1.5+：SharedFlow 接口上提供订阅者数量
_events.subscriptionCount.first { it > 0 }
_events.emit(Event.Started)     // 此时一定有订阅者，不会丢
```

---

**读者问：StateFlow 是不是就是 `replay = 1` 的 SharedFlow？为什么官方还要单独提供 StateFlow？**

**AI答：**

1. **类型关系**：`public interface StateFlow<out T> : SharedFlow<T>` —— `StateFlow` **是** `SharedFlow` 的子接口。所以 `StateFlow` 可以传给任何需要 `SharedFlow` 的参数，`SharedFlow` 不能传给需要 `StateFlow` 的参数。
2. **语义关系**：官方把 `StateFlow` 定义为 `SharedFlow` 的特例 —— **`replay = 1` + 按 `equals` 合流**。
3. **但 `StateFlow` 多给了三样东西**，这才是它存在的意义：
   - **`value` 属性**：可以**同步读取**当前值，不需要 `collect`；`SharedFlow` 只能 `collect`。
   - **原子更新 API**：`update` / `getAndUpdate` / `updateAndGet`（CAS 循环）。
   - **类型即契约**：拿到 `StateFlow` 就等于拿到"一定有当前值"的保证，不用操心初始值。
4. **反过来说**，如果不需要 `value`、不需要原子更新、也不需要去重，用 `SharedFlow` 更灵活（可配 `replay` 与缓冲）。
5. **实战口诀**：**默认用 `StateFlow`，只有明确需要"广播 + 可配置重放"时才用 `SharedFlow`。**

---

**读者问：Channel 和 SharedFlow 都能发事件，到底选哪个？**

**AI答：**

1. **分发模型不同**（决定性因素）：
   - `Channel` = **点对点**（收件箱模型）：多个收集者**竞争**消费，每个事件只被**其中一个**拿到，消费后即消失。
   - `SharedFlow` = **广播**（收音机模型）：每个收集者**都收到一份**同一事件。
2. **丢失风险不同**：
   - `Channel` 有缓冲 + 挂起发送 → 基本不丢；`send` 在缓冲满时挂起，`trySend` 返回 `false`。
   - `SharedFlow(replay = 0)` 在无订阅者时**直接丢**。
3. **选型结论**：
   - **全局提示、埋点、跨模块通知**（多人都要收）→ `SharedFlow`；
   - **单消费者的一次性指令**（如"关闭键盘"、"跳转到详情页"）→ `Channel`；
   - **状态** → `StateFlow`（不要用 Channel 传状态）。
4. **补充**：`Channel` 也有"多个收集者但事件只被一个拿到"的坑；如果既要多播又不能丢，**最稳的组合是 `Channel` + `shareIn`**，或用 `SharedFlow(replay = 1)` 并在业务层做幂等。

---

**读者问：`emit` 挂起了怎么办？`onBufferOverflow` 的三个策略怎么选？**

**AI答：**

1. **`emit` 为什么会挂起**：默认 `onBufferOverflow = SUSPEND`，当某个订阅者的缓冲区（`replay + extraBufferCapacity`）满时，`emit` 挂起等空间 → **最慢的订阅者决定发射速度**。
2. **`emit` 挂起不是错误**，它就是背压机制：发送方被慢消费者拖住，保证"不丢"。但要注意**协程作用域**：在 `viewModelScope` 里挂起是安全的；在一个已经被取消的作用域里会抛 `CancellationException`。
3. **三个策略的选择**：
   - `SUSPEND`（默认）：**状态类、不允许丢**的流；`emit` 保证送达。
   - `DROP_LATEST`：**实时性优先**（如行情、倒计时）——只保最新，旧的没处理完就丢。
   - `DROP_OLDEST`：**保新弃旧**——宁可丢中间值也要保证最新值最终到达（监控指标上报常用）。
4. **配套建议**：
   - 用 `DROP_LATEST`/`DROP_OLDEST` 时，`emit` 不再挂起，可以放心在非挂起上下文用 `tryEmit`；
   - 用 `SUSPEND` 时优先 `emit`，必须用 `tryEmit` 就要**检查返回值**并做重试/降级；
   - 永远不要在 `SharedFlow` 上假设"发出去了就有人收到"。

---

**读者问：屏幕旋转后，为什么 `SharedFlow` 发的事件收不到了？**

**AI答：**

1. **旋转 = Activity 销毁重建**，`repeatOnLifecycle(STARTED)` 的 `block` 被**取消**（不是挂起），随后重新执行 → **收集者被销毁并重建**。
2. **`replay = 0` 的 `SharedFlow` 没有历史**：旧 Activity 销毁后到新 Activity 订阅前发出的事件，**无人接收且无处缓存 → 永久丢失**。
3. **对比 `StateFlow`**：`StateFlow` 的 `replay = 1` 语义保证新订阅者立刻拿到**最新状态** → 旋转后状态自动恢复，不会"丢状态"。
4. **对比 `LiveData`**：它原生绑定 `LifecycleOwner`，重建后自动补发最新值（但也因此会**重放事件**）。
5. **三种解法**：
   - **事件本身不重要**（如 Toast）→ 接受丢失，或用 `replay = 1` 接受重放（二选一，不能都要）；
   - **事件必须送达** → 用 `Channel`（ViewModel 存活期间缓存，不随 Activity 销毁而丢）；
   - **事件本质是状态** → 改设计：把"是否已弹过 Toast"做成 `StateFlow` 里的一个 `consumed` 标志，由 UI 消费后回写（Android 官方推荐做法）。
6. **面试加分点**：能说清"**状态要可重放、事件要恰好一次**"，就说明理解了 MVVM 的状态/事件二分。

## 关联笔记

- [[LiveData与StateFlow]]：LiveData 与 `StateFlow` 的横向对比、一次性事件的三种方案
- [[Lifecycle与ViewModel]]：`viewModelScope`、`SavedStateHandle.getStateFlow()` 与 ViewModel 存活范围
- [[Lifecycle]]：`repeatOnLifecycle` 的取消/重订阅机制，以及停机窗口内事件丢失的原因
- [[../架构模式/MVVM]]：单向数据流与状态容器选型
- [[../架构模式/MVI]]：State 与 SideEffect 的通道设计（`Channel` vs `SharedFlow`）
- [[../README]]：第 7 部分总览

## 参考资料

- Kotlin 官方 · StateFlow：https://kotlinlang.org/docs/flow.html#stateflow
- Kotlin 官方 · SharedFlow：https://kotlinlang.org/docs/flow.html#sharedflow
- Kotlin 官方 · 缓冲与溢出策略：https://kotlinlang.org/docs/flow.html#buffering
- Kotlin 官方 API · MutableSharedFlow：https://kotlinlang.org/api/kotlinx.coroutines/kotlinx.coroutines.flow/-mutable-shared-flow/
- Android 官方 · 在 Android 上使用 Flow：https://developer.android.com/topic/libraries/architecture/coroutines
- 《Jetpack Compose 实战》· 状态与副作用章节
- 《Android 高级编程》第 4 版 · 第 7 章
