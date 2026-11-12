---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 中级
created: 2026-11-04
updated: 2026-11-04
related: "[[Lifecycle与ViewModel]], [[LiveData与StateFlow]], [[StateFlow与SharedFlow]], [[../架构模式/MVVM]]"
---
# Lifecycle

## 一句话定义

Lifecycle 是 AndroidX 提供的**生命周期感知框架**：它把 Activity / Fragment 的"创建 → 启动 → 恢复 → 暂停 → 停止 → 销毁"抽象成一个**可观察的状态机**，让协程、观察者、数据流能够"在合适的时候启动、在合适的时候自动停止"，从而从根源上杜绝内存泄漏和后台无效工作。

## 核心原理

### 1. 三个核心角色

| 角色                  | 类型   | 职责                                                                                                            |
| --------------------- | ------ | --------------------------------------------------------------------------------------------------------------- |
| `LifecycleOwner`    | 接口   | 持有者角色，只声明一个 `lifecycle: Lifecycle` 属性。`ComponentActivity`、`Fragment`、`Service` 都实现它 |
| `Lifecycle`         | 抽象类 | **状态机本体**：对外暴露当前 `State`、接收 `Event`、管理观察者集合                                    |
| `LifecycleObserver` | 接口   | 观察者角色，回调生命周期变化。`DefaultLifecycleObserver` 是官方推荐实现                                       |
| `LifecycleRegistry` | 实现类 | `Lifecycle` 的具体实现，**真正的状态机引擎**（事件分发、状态合并、观察者同步）                          |

关系一句话：

```text
LifecycleOwner  ──持有──▶  Lifecycle  ◀──实现──  LifecycleRegistry
      │                        │
      └──实现 LifecycleOwner    └──通知──▶ LifecycleObserver 集合
```

### 2. 事件驱动 vs 状态驱动（面试第一考点）

AndroidX 同时提供**两套语义**，各有用途：

| 维度     | `Lifecycle.Event`（事件驱动）                                                                           | `Lifecycle.State`（状态驱动）                                           |
| -------- | --------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| 语义     | "发生了什么"（离散、精确、有序）                                                                          | "现在处于什么阶段"（连续、可比较）                                        |
| 取值     | `ON_CREATE` / `ON_START` / `ON_RESUME` / `ON_PAUSE` / `ON_STOP` / `ON_DESTROY`（+`ON_ANY`） | `INITIALIZED` / `CREATED` / `STARTED` / `RESUMED` / `DESTROYED` |
| 判断方式 | 只能判断"是不是这个事件"                                                                                  | 可用 `isAtLeast()` 判断"是否已达到某阶段"                               |
| 典型用途 | 需要精确知道"刚进后台/刚回前台"                                                                           | `repeatOnLifecycle(STARTED)`、条件启停                                  |

**状态与事件的映射表**（`Lifecycle.Event.getTargetState`）：

| Event          | 目标 State    | 说明                                      |
| -------------- | ------------- | ----------------------------------------- |
| `ON_CREATE`  | `CREATED`   | 对象已创建                                |
| `ON_START`   | `STARTED`   | 可见但未获焦点                            |
| `ON_RESUME`  | `RESUMED`   | 可见且在前台                              |
| `ON_PAUSE`   | `STARTED`   | 失去焦点（如弹对话框、跳到别的 Activity） |
| `ON_STOP`    | `CREATED`   | 完全不可见                                |
| `ON_DESTROY` | `DESTROYED` | 终态，不可恢复                            |

完整状态机：

```text
Event:  ON_CREATE ──▶ ON_START ──▶ ON_RESUME
                       ▲   │            │
             ON_STOP ──┘   └── ON_PAUSE ┘
                        │
                     ON_DESTROY

State:  INITIALIZED ──▶ CREATED ──▶ STARTED ──▶ RESUMED
            │              ▲           │            │
            └──────────────┘           └────────────┘
                         ON_STOP        ON_PAUSE
                     (CREATED)        (STARTED)

         ON_DESTROY ──▶ DESTROYED（终态）
```

三个工具方法（`Lifecycle` 伴生对象）：

- `Event.upFrom(state)`：从某状态**向上**走一步的事件（`CREATED → ON_START`，`STARTED → ON_RESUME`，`RESUMED → null`）。
- `Event.downFrom(state)`：从某状态**向下**一步的事件（`RESUMED → ON_PAUSE`，`STARTED → ON_STOP`，`CREATED → ON_STOP`）。
- `Event.getTargetState(event)`：事件对应的目标状态。

> [!note] ✏️ `ON_PAUSE` 与 `ON_STOP` 的区别
> 两者都会让状态离开 `RESUMED`，但 `ON_PAUSE` 只表示"失去焦点"（页面还可见），`ON_STOP` 表示"完全不可见"。**Android 8.0 之前存在"Activity 不可见但仍处于 RESUMED"的多窗口缺陷**，因此判断"是否还在前台"要用 `RESUMED` 而不是"有没有 `onPause`"。

### 3. `LifecycleRegistry`：状态机引擎

```kotlin
class LifecycleRegistry @JvmOverloads constructor(
    owner: LifecycleOwner? = null,
    enforceMainThread: Boolean = true
) : Lifecycle(), LifecycleOwner
```

核心能力：

1. **状态只能单调向前**：`currentState` 的 setter 会把当前状态**推进**到目标状态，并自动派发中间缺失的事件。例如当前是 `INITIALIZED`，直接 `currentState = RESUMED`，框架会自动补发 `ON_CREATE → ON_START → ON_RESUME`。**不能倒退**（`RESUMED` 不能直接回到 `CREATED`）。
2. **观察者"追平"（catch-up）**：`addObserver(observer)` 时，如果宿主已经处于某个状态，框架会**立即把新观察者同步到当前状态**——即补发它"错过的"事件。所以 `addObserver` 后可能马上收到 `onCreate`/`onStart` 回调，**不要假设注册后才开始回调**。
3. **主线程约束**：`enforceMainThread = true`（默认）时，所有状态变更必须在主线程，否则抛 `IllegalStateException`。测试环境可用 `LifecycleRegistry.createUnsafe(owner)` 绕过。
4. **`removeObserver` 幂等**：移除不存在的观察者不会抛异常。

### 4. 观察者的三种注册方式

| 方式                         | 说明                                               | 状态                                            |
| ---------------------------- | -------------------------------------------------- | ----------------------------------------------- |
| `DefaultLifecycleObserver` | 声明式，按方法名对应事件，IDE 有提示               | **推荐**（需 `lifecycle-common-java8`） |
| `LifecycleEventObserver`   | 一个 `onStateChanged(owner, event)` 处理所有事件 | 需要按事件分支时用                              |
| `@OnLifecycleEvent` 注解   | 通过注解 + 反射调用方法                            | **已废弃**，存在反射性能与混淆问题        |

`LifecycleEvent.ON_ANY` 可以用一个回调监听全部事件：

```kotlin
lifecycle.addObserver { _, event ->
    if (event == Lifecycle.Event.ON_ANY) { /* 所有事件都会走这里 */ }
}
```

### 5. 生命周期感知的协程（核心价值）

#### `lifecycleScope`

`lifecycleScope` 是 `LifecycleOwner` 的扩展属性（`lifecycle-runtime-ktx`）：

```kotlin
val LifecycleOwner.lifecycleScope: LifecycleCoroutineScope
```

- 内部 = `SupervisorJob() + Dispatchers.Main.immediate`。
- **在宿主进入 `ON_DESTROY` 时自动取消** → 其中所有协程随之取消 → 持有的 View/Activity 引用被释放。
- 比 `viewModelScope` 活得**短**：`viewModelScope` 在配置变更时存活，`lifecycleScope` 在 `onDestroy` 就结束了（两者对比详见 [[Lifecycle与ViewModel]]）。

#### `repeatOnLifecycle`

```kotlin
suspend fun Lifecycle.repeatOnLifecycle(
    state: Lifecycle.State,
    block: suspend CoroutineScope.() -> Unit
)
```

**工作原理**（源码级）：

1. 内部注册一个 `LifecycleEventObserver`；
2. 宿主进入 `ON_START`（或已达到 `state`）→ **启动**一个子协程作用域来执行 `block`；
3. 宿主状态**掉出** `state`（`ON_STOP`）→ **取消**该子作用域（是取消，不是挂起）；
4. 再次回到 `ON_START` → **重新启动** `block`；
5. 宿主 `ON_DESTROY` → 结束整个 `repeatOnLifecycle`（函数返回）。

> [!warning] ⚠️ 关键区别
> "掉出状态时**取消**、回到状态时**重新收集**"意味着：被取消期间 `StateFlow` 的新值会被 `StateFlow` 自己**缓存（replay）**，回到前台后能立刻拿到最新值；而 `SharedFlow`（无 replay）在这个窗口内发出的事件**会永久丢失**，所以一次性事件要用 `Channel`。

#### `flowWithLifecycle` vs `repeatOnLifecycle`

|                                   | `flowWithLifecycle`                              | `repeatOnLifecycle`                              |
| --------------------------------- | -------------------------------------------------- | -------------------------------------------------- |
| 回到前台时                        | **不重新收集**，只是恢复对同一个上游流的订阅 | **重新收集**整个 `block`                   |
| 上游是 `StateFlow`              | 恢复后能拿到最新值                                 | 恢复后能拿到最新值                                 |
| 上游是 `SharedFlow`（replay=0） | 恢复后**收不到**停机期间的事件               | 恢复后**也收不到**（因为重新订阅时事件已过） |
| 适合                              | 单个数据流的"可见才订阅"                           | 多流并发、需要在停机时**释放资源**           |

> 面试标准答法：**`flowWithLifecycle` 是"订阅开关"，`repeatOnLifecycle` 是"作用域开关"**。需要同时管理多条流、且在不可见时释放资源（如停止动画、取消请求）时用后者。

两者的核心区别在于**设计定位与使用场景**：`repeatOnLifecycle` 是一个**挂起函数**，用于在特定生命周期状态下执行任意代码块（通常用于收集多个 Flow）；而 `flowWithLifecycle` 是一个 **Flow 操作符**，专门用于让单个 Flow 具备生命周期感知能力，仅在活跃状态下发射数据。

#### `repeatOnLifecycle` 与 `flowWithLifecycle` 对比

| 特性               | `repeatOnLifecycle`                                                                                                        | `flowWithLifecycle`                                                                                                                  |
| :----------------- | :--------------------------------------------------------------------------------------------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------- |
| **本质**     | 挂起函数（需在协程中调用）                                                                                                   | Flow 扩展操作符（返回新 Flow）                                                                                                         |
| **作用对象** | 生命周期对象（`Lifecycle`）                                                                                                | Flow 对象                                                                                                                              |
| **核心行为** | 当生命周期达到目标状态时，**启动新的协程**执行代码块；低于目标状态时**取消**该协程；再次达到时**重新启动** | 当生命周期达到目标状态时，**收集上游 Flow** 并向下游发射值；低于目标状态时**取消上游收集**（下游 Flow 本身仍活跃但不发射） |
| **适用场景** | 需要**收集多个 Flow**，或需要在生命周期感知的代码块中执行**非 Flow 操作**（如配置任务）                          | 仅需**处理单个 Flow** 时，作为链式调用的一部分                                                                                   |
| **资源开销** | **更优**。推荐用于多个 Flow 的场景，可为每个 Flow 单独 `launch`，无需创建热流                                        | 内部通过 `callbackFlow` 实现，会**创建热流**并引入缓冲区                                                                       |

#### 选用建议

**优先使用 `repeatOnLifecycle`**：当你需要同时监听多个数据流（例如同时观察 UI 状态和导航事件），或者在启动 Flow 收集前需要先执行一些一次性配置逻辑时，它更加灵活且资源消耗更少。

**使用 `flowWithLifecycle`**：当你只处理单一数据流，且希望代码保持简洁的链式调用风格时（例如 `flow.flowWithLifecycle(...).collect { ... }`），它会非常方便。

**注意**：`flowWithLifecycle` 仅取消**上游** Flow 的收集。如果在其后跟随了 `combine` 等操作符，下游操作符仍会保持活跃，因此操作符的**先后顺序**会影响行为。

#### Compose 侧

```kotlin
// lifecycle-runtime-compose 2.6.0+
val state by viewModel.uiState.collectAsStateWithLifecycle()   // = repeatOnLifecycle(STARTED) + collectAsState

// lifecycle-runtime-ktx 2.7+ / lifecycle-runtime-compose
val lifecycleState = lifecycle.currentStateAsState()            // Compose 读当前 State
val flow = lifecycle.currentStateFlow                          // StateFlow<Lifecycle.State>
```

### 6. 进程级生命周期：`ProcessLifecycleOwner`

```kotlin
// 需要 androidx.lifecycle:lifecycle-process
ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
```

- 反映的是**整个应用**的前后台，而不是单个 Activity。
- 有一个约 **700ms 的延迟**：多个 Activity 切换时先延后派发 `ON_STOP`，避免中间状态闪烁。
- 典型用途：埋点上报前后台、自动播放/暂停音乐、锁定屏幕。

### 7. 谁是 `LifecycleOwner`？

| 实现者                                        | 生命周期范围                 | 注意                                         |
| --------------------------------------------- | ---------------------------- | -------------------------------------------- |
| `ComponentActivity` / `AppCompatActivity` | Activity 全程                | `onDestroy` 才结束                         |
| `Fragment`                                  | Fragment 全程                | 视图销毁在 `onDestroyView`，**更早** |
| `Fragment.viewLifecycleOwner`               | Fragment 的**视图**    | 自定义 View 里用它，不要用 `fragment`      |
| `Service`（`ServiceLifecycleOwner`）      | Service 全程                 | 配合 `LifecycleService`                    |
| `ViewTreeLifecycleOwner`                    | 从 View 树向上找最近的 owner | 自定义 View 用它拿到 owner                   |
| 自定义类                                      | 自己控制                     | 用 `LifecycleRegistry` 手动驱动            |

> [!tip] 💡 `Fragment` 的两套生命周期
> `Fragment` 同时实现两个 owner：`fragment`（整个 Fragment）和 `viewLifecycleOwner`（Fragment 的 View）。
> 在 `Fragment` 里 `requireView()` 可能抛异常（视图已销毁），而且 Fragment 可能在返回栈里存活很久 → **在 Fragment 中绑定 UI（View、LiveData observe、协程）必须用 `viewLifecycleOwner`**，否则会造成视图泄漏。

## 代码示例

### 1. `DefaultLifecycleObserver`（Fragment 中）

```kotlin
class UserFragment : Fragment(R.layout.fragment_user), DefaultLifecycleObserver {

    private val viewModel: UserViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 绑定到视图生命周期：视图销毁时自动解绑
        viewLifecycleOwner.addObserver(this)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) { /* 可见 */ }
    override fun onStop(owner: LifecycleOwner)  { /* 不可见 */ }
    override fun onDestroy(owner: LifecycleOwner) { /* 释放资源 */ }
}
```

### 2. 自定义 `LifecycleOwner`（用 `LifecycleRegistry` 驱动）

```kotlin
class CameraSession(private val callback: Callback) : LifecycleOwner {

    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle get() = registry

    fun start() {
        registry.currentState = Lifecycle.State.CREATED   // 自动派发 ON_CREATE
        registry.currentState = Lifecycle.State.STARTED   // 自动派发 ON_START
        callback.onReady()
    }

    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun pause() {
        registry.currentState = Lifecycle.State.STARTED   // 自动派发 ON_PAUSE
    }

    fun stop() {
        registry.currentState = Lifecycle.State.CREATED   // 自动派发 ON_STOP
    }

    fun release() {
        registry.currentState = Lifecycle.State.DESTROYED // 自动派发 ON_DESTROY
    }
}
```

### 3. `LifecycleRegistry` + 自定义观察者（测试用）

```kotlin
class TestOwner : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)  // 解除主线程约束
    override val lifecycle: Lifecycle get() = registry

    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }
}

@Test
fun `observer catches up to current state`() {
    val owner = TestOwner()
    val events = mutableListOf<Lifecycle.Event>()

    owner.moveTo(Lifecycle.State.STARTED)          // 先推进状态
    owner.lifecycle.addObserver { _, e -> events += e }  // 后注册 → 立刻收到追赶事件

    assertEquals(
        listOf(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_START),
        events
    )
}
```

### 4. 传统 View 中的正确写法

```kotlin
class UserActivity : AppCompatActivity() {

    private val viewModel: UserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                launch { viewModel.events.collect { handle(it) } }
            }
        }
    }
}
```

### 5. Compose 中读取生命周期状态

```kotlin
@Composable
fun VideoPlayer(viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleState by remember { mutableStateOf(Lifecycle.State.RESUMED) }

    // 需要精确知道"当前是不是 RESUMED"时用 currentStateAsState
    val current by LocalLifecycleOwner.current.lifecycle.currentStateAsState()

    LaunchedEffect(current) {
        if (current.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.play()
        } else {
            viewModel.pause()
        }
    }
}
```

### 6. `flowWithLifecycle` vs `repeatOnLifecycle` 实战对比

```kotlin
// A. 单流，只要"可见才订阅" → flowWithLifecycle 足够
lifecycleScope.launch {
    viewModel.refreshTrigger
        .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
        .collect { doRefresh() }
}

// B. 多流 + 需要在不可见时释放资源 → repeatOnLifecycle
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch { vm.state.collect(::render) }
        launch { vm.location.collect { tracker.start(it) } }   // 离开前台自动停止定位
    }
}
```

### 7. 应用前后台监听

```kotlin
class AppVisibilityWatcher {

    fun observe(onForeground: () -> Unit, onBackground: () -> Unit) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = onForeground()
                override fun onStop(owner: LifecycleOwner) = onBackground()
            }
        )
    }
}
```

### 8. `Fragment` 中最常见的泄漏写法与修复

```kotlin
// ❌ 错误：绑定到 Fragment 生命周期，视图销毁后仍持有 View
fragment.lifecycle.addObserver(this)
fragment.viewLifecycleOwner.lifecycleScope.launch { ... }

// ✅ 正确：绑定到视图生命周期
viewLifecycleOwner.addObserver(this)
viewLifecycleOwner.lifecycleScope.launch { ... }
```

## 常见问题 / 面试要点

**读者问：六大事件和三大状态到底是什么关系？为什么 AndroidX 要设计两套 API？**

**AI答：**

1. **映射关系**：状态是事件的**累积结果**，事件是状态的**变化瞬间**。
   - `ON_CREATE` 之后进入 `CREATED`；`ON_START` 之后进入 `STARTED`；`ON_RESUME` 之后进入 `RESUMED`。
   - 事件只有 6 个（+`ON_ANY`），状态有 5 个（`INITIALIZED`、`CREATED`、`STARTED`、`RESUMED`、`DESTROYED`），其中真正"活着"的是 `CREATED`/`STARTED`/`RESUMED` 三个。
   - 事件数量多于状态，是因为存在**同状态不同事件**的情况：`CREATED` 既可能来自 `ON_CREATE`（向上），也可能来自 `ON_STOP`（向下），但状态值一样。
2. **为什么两套并存**：
   - **事件**回答"发生了什么"，适合需要精确挂接行为的场景（埋点上报时机、动画、页面跳转）。
   - **状态**回答"现在怎么样"，适合**条件判断**（`isAtLeast(STARTED)`）和**集合收集**（`repeatOnLifecycle`）。
   - 如果只有事件，开发者要自己记住"哪些事件等价于可见"，非常容易漏（`ON_PAUSE` 可见、`ON_STOP` 不可见）；如果只有状态，就无法区分"刚进后台"和"在后台待了一会儿"。
3. **一句话**：**状态用于"判断"，事件用于"响应"。**

---

**读者问：`repeatOnLifecycle`、`flowWithLifecycle`、`collectAsStateWithLifecycle` 三者有什么区别？**

**AI答：**

1. **`repeatOnLifecycle(state) { }`**（`lifecycle-runtime-ktx`）：
   - 内部注册观察者，状态**进入**目标态时启动 block，**离开**时取消 block，**再次进入**时**重新执行** block。
   - 作用域感：整个 block 是一个子作用域，适合并发管理多条流、释放资源。
   - `collectAsStateWithLifecycle` 内部就是 `repeatOnLifecycle(STARTED) + collectAsState`。
2. **`flowWithLifecycle(lifecycle, minActiveState)`**：
   - 只是给上游流加一个"**可见才订阅**"的开关。
   - **不会重新收集**：回到前台时恢复对同一上游的订阅，但上游若已 emit 过数据，能否拿到取决于上游类型。
   - 适合单个流、且希望**保持订阅连续性**的场景。
3. **`collectAsStateWithLifecycle()`**（`lifecycle-runtime-compose`）：
   - Compose 专用，内部 = `repeatOnLifecycle(STARTED)` + `collectAsState`。
   - 页面不可见时**停止收集**（因为 `collectAsState` 基于 `produceState`，停止收集后不会触发重组）。
4. **常见错误**：直接 `lifecycleScope.launch { flow.collect {} }` → 页面不可见后**仍在收集**，后台发请求、仍在更新状态，浪费资源并可能崩溃（操作已销毁的 View）。
5. **选型口诀**：**多流/要释放资源 → `repeatOnLifecycle`；单流/保持订阅 → `flowWithLifecycle`；Compose → `collectAsStateWithLifecycle`。**

---

**读者问：`lifecycleScope` 和 `viewModelScope` 有什么区别？分别在什么时机被取消？**

**AI答：**

|                  | `lifecycleScope`                                 | `viewModelScope`                             |
| ---------------- | -------------------------------------------------- | ---------------------------------------------- |
| 归属             | `LifecycleOwner`（Activity / Fragment / 自定义） | `ViewModel`                                  |
| 取消时机         | 宿主 `ON_DESTROY` 时                             | ViewModel `onCleared()` 时                   |
| 配置变更（旋转） | **会**被取消并重建                           | **不会**被取消，ViewModel 保留           |
| 存活长短         | 短                                                 | 长                                             |
| 适合             | UI 相关的短任务（动画、收集 UI 状态）              | 业务逻辑、与 UI 无关的长任务（数据请求、轮询） |

1. `lifecycleScope` 的 Job 是 `SupervisorJob()`，子协程失败不会连坐兄弟协程；取消时同样会取消所有子协程。
2. **判断依据**：任务是否需要跨配置变更存活。需要 → `viewModelScope`；不需要 → `lifecycleScope`。
3. Fragment 中要用 `viewLifecycleOwner.lifecycleScope`，否则视图销毁后协程还在跑 → 泄漏。
4. 三种作用域（`viewModelScope` / `lifecycleScope` / `rememberCoroutineScope`）的完整对比见 [[Lifecycle与ViewModel]]。

---

**读者问：`addObserver` 之后回调是立刻触发还是等到下一个事件？**

**AI答：**

1. **立刻触发（catch-up）**。`LifecycleRegistry.addObserver` 会把新观察者**同步到当前状态**：如果当前已经是 `STARTED`，会立刻回调 `onCreate` + `onStart`；如果已经是 `RESUMED`，会补 `onCreate` + `onStart` + `onResume`。
2. 实现方式：注册时先算出"观察者当前状态"（从 `INITIALIZED` 开始）与"宿主当前状态"的差值，然后**补发**缺失的事件序列。
3. **实践影响**：
   - 不要在 `onStart` 里假设"页面一定是刚启动的"，它可能在注册瞬间就被调用多次。
   - `DefaultLifecycleObserver` 的 `onCreate` 可能在 `addObserver` 那一刻就触发 → 在里面初始化的资源要注意重复创建。
   - `lifecycle.addObserver` 后**必须**在不再需要时 `removeObserver`，否则观察者被持有 → 泄漏（`LifecycleRegistry` 持有强引用）。

---

**读者问：判断应用是"在前台"还是"在后台"应该怎么做？直接用 Activity 的 `onPause`/`onStop` 行不行？**

**AI答：**

1. **不行**，单个 Activity 的生命周期只能反映**自己**。应用可能同时存在多个 Activity（A 跳 B，A 只是 `onStop` 没 `onDestroy`），此时应用仍在前台。
2. **正确做法**：用 `ProcessLifecycleOwner.get().lifecycle`（需依赖 `androidx.lifecycle:lifecycle-process`）：
   - `ON_START` ≈ 应用进入前台；`ON_STOP` ≈ 应用进入后台。
   - 有约 **700ms 延迟**，用于过滤 Activity 之间的中间态，避免误判。
3. **另一个坑**：Android 8.0 之前多窗口模式下，一个不可见的 Activity 仍可能处于 `RESUMED` → 判断"前台"要用 `Lifecycle.State.RESUMED` 的状态判断，而不是"有没有 `onPause` 被调用过"。
4. 常见用途：埋点统计时长、自动暂停音视频、锁屏、内存紧张时释放缓存。

---

**读者问：自定义一个 `LifecycleOwner` 要注意什么？为什么不直接用 Activity？**

**AI答：**

1. **做法**：实现 `LifecycleOwner` 接口，内部持有一个 `LifecycleRegistry`，对外暴露 `lifecycle`；由业务代码手动推进状态。
2. **必须遵守的规则**：
   - **状态单调向前**：只能 `INITIALIZED → CREATED → STARTED → RESUMED`，或向下走；**不能从 `DESTROYED` 复活**。
   - `DESTROYED` 是**终态**，一旦进入，`addObserver` 不会再收到任何回调，`lifecycleScope` 也已取消。
   - 默认 `enforceMainThread = true`，状态推进必须在**主线程**；测试中用 `LifecycleRegistry.createUnsafe(owner)` 解除限制。
3. **为什么要自定义**：把"生命周期"从 UI 框架中解耦，让纯逻辑对象（如下载任务、播放器、蓝牙连接）也能接入统一的状态机，从而复用同一套 `repeatOnLifecycle` 写法。
4. **典型坑**：状态推进和资源释放**顺序**错了——例如先 `ON_DESTROY` 再释放资源，而某个观察者依赖该资源 → 崩溃。正确顺序是：先让观察者感知 `ON_DESTROY`（`LifecycleRegistry` 派发），再释放自有资源。

---

**读者问：生命周期相关的内存泄漏有哪些典型场景？**

**AI答：**

1. **观察者未移除**：`lifecycle.addObserver(observer)` 后不 `removeObserver` → `LifecycleRegistry` 强引用观察者 → 观察者引用的 View/Context 无法回收。
2. **在 `Fragment` 里绑定到 `fragment` 而不是 `viewLifecycleOwner`** → Fragment 长期驻留返回栈，View 泄漏。
3. **在 `onDestroy` 里手动启动的协程**（用了 `GlobalScope` 或自建 `CoroutineScope`）→ 永不取消 → 持有 Activity。
4. **`lifecycleScope.launch` 中引用了 Activity**（如 `startActivity`）→ 协程未结束前持有引用；虽然 `ON_DESTROY` 会取消，但 `onDestroy` 之前的窗口仍可能出问题。
5. **匿名内部类 Observer 捕获外部 `this`** → 同 1。
6. **修复原则**：**优先用 `lifecycleScope` + `repeatOnLifecycle`**，让框架帮你取消；必须手动注册观察者时，保证成对 `add`/`remove`；自定义 View 里用 `ViewTreeLifecycleOwner.get(this)` 拿 owner。

---

**读者问：`Lifecycle` 和 `SavedState`、`ViewModel` 是什么关系？**

**AI答：**

1. `ComponentActivity` 同时实现了 `LifecycleOwner` 与 `SavedStateRegistryOwner`：
   - `Lifecycle` 管**运行期**的存活与回调分发（进程活着期间）。
   - `SavedStateRegistry` 管**跨进程死亡**的状态保存与恢复（数据写进 `savedInstanceState` 的 `Bundle`）。
   - `ViewModel` 管**跨配置变更**的状态存活（存在 `NonConfigurationInstance` 里）。
2. 三者覆盖三种"丢失场景"：
   - 配置变更（旋转）→ **ViewModel** 保住。
   - 进程被杀死 → **SavedStateHandle** 保住（少量数据）。
   - 正常销毁（`finish()`）→ 都不需要保活。
3. `SavedStateHandle` 正是通过 `SavedStateRegistry` + `ViewModelStore` 协同实现的：ViewModel 被重建时，`SavedStateHandle` 从 `savedInstanceState` 里恢复（用法详见 [[Lifecycle与ViewModel]]）。

## 关联笔记

- [[Lifecycle与ViewModel]]：`Lifecycle` 管"何时活"，`ViewModel` 管"活多久"；本篇讲框架与状态机，那篇讲存活范围、`SavedStateHandle` 与依赖注入
- [[LiveData与StateFlow]]：`observe(owner)` 与 `collectAsStateWithLifecycle()` 都是"生命周期感知的数据流"
- [[StateFlow与SharedFlow]]：状态与事件两种载体，以及停机窗口内事件丢失的原因
- [[../架构模式/MVVM]]：生命周期感知是 MVVM 中"单向数据流"落地的基础
- [[../架构模式/MVI]]：`repeatOnLifecycle` 决定 State 与 SideEffect 的投递时机
- [[../README]]：第 7 部分总览

## 参考资料

- Android 官方 · Lifecycle 概览：https://developer.android.com/topic/libraries/architecture/lifecycle
- Android 官方 · 生命周期状态：https://developer.android.com/topic/libraries/architecture/lifecycle/lifecycle-state
- Android 官方 · 感知生命周期的数据流：https://developer.android.com/topic/libraries/architecture/lifecycle/lifecycle-aware
- Android 官方 · 与 Kotlin 协程配合：https://developer.android.com/topic/libraries/architecture/coroutines
- Android 官方 · Compose 感知生命周期：https://developer.android.com/develop/ui/compose/side-effects
- AOSP 源码 · `LifecycleRegistry` / `RepeatOnLifecycle`：`frameworks/support/lifecycle/lifecycle-runtime/src/main/java/androidx/lifecycle/`
