---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 高级
created: 2026-11-04
updated: 2026-11-04
related: "[[MVVM]], [[架构模式选型]], [[../Jetpack库/LiveData与StateFlow]]"
---

# MVI

## 一句话定义

MVI（Model-View-Intent）把界面建模为一个**严格的有限状态机**：用户操作被抽象为 **Intent**，所有状态变更都必须经过**纯函数 Reducer** 产出一个**不可变 State**，View 则是 State 的纯渲染函数。

## 核心原理

### 1. 三个角色与两条通道

```
        Intent (意图/事件)              State (不可变状态)
   ┌────────────────────────┐      ┌────────────────────────┐
   │ View ──── 用户操作 ────▶│      │◀── Reducer ───────────││
   │ ▲                      │      │      (State, Intent)    ││
   │ └──── 渲染 State ◀─────┘      └────────────────────────┘
   └────────────────────────┘      ▲
                                    │
                            ViewModel / Store
                            (持有唯一 State)
```

- **Intent**：用户意图或系统事件的**数据结构**（`sealed interface`），如 `AddItem`、`SearchChanged("abc")`。
- **Reducer**：**纯函数** `reduce(state: State, intent: Intent): State`。输入相同则输出相同，不读外部可变状态、不做 IO。
- **State**：**不可变数据类**（`data class` + `val`），是界面渲染的**唯一依据**。
- **Effect / SideEffect**：不属于状态的一次性动作（Toast、导航、震动），单独走 `Channel`。

### 2. 为什么要有 Reducer（核心价值）

MVVM 里 ViewModel 可以随意 `state.update { it.copy(...) }`，状态怎么变"散落各处"，很难推理。MVI 强制收口：

1. **单一入口**：所有状态变更只能来自 `reduce()`，逻辑集中在一处，可读、可审计。
2. **纯函数 = 极易测试**：不需要 Android 环境，不需要 mock 协程调度器，直接断言输入输出。
3. **时间旅行 / 回放**：因为 `reduce` 是纯函数，可以记录 (State, Intent) 序列，重放任意用户操作序列，天然支持崩溃复现与"撤销"。
4. **状态可穷举**：用 sealed interface 表达互斥状态，编译器强制处理所有分支。

### 3. MVI vs MVVM 的本质差异

| 维度 | MVVM | MVI |
| --- | --- | --- |
| 状态变更入口 | 任意处 `update{}`，入口分散 | **只有 `reduce()` 一个入口** |
| Reducer | 通常没有 | **纯函数，可单测、可重放** |
| 事件模型 | 事件常被塞进 State，容易重复消费 | Intent 是"输入"，Effect 是"输出"，两者严格分离 |
| 状态可变性 | `MutableStateFlow<State>` 直接改 | State 不可变，每次产生新对象 |
| 适合场景 | 常规业务页 | 复杂表单、多步流程、强状态机交互、跨页共享状态 |

### 4. Intent 与 Effect 的边界（最容易搞错的地方）

| | Intent | Effect（SideEffect） |
| --- | --- | --- |
| 方向 | **输入**：View → Store | **输出**：Store → View |
| 内容 | 用户意图、输入框文本、点击事件 | 导航、Toast、Snackbar、权限请求、下载文件 |
| 是否持久 | 参与状态计算 | 不参与，参与后重放会重复执行（所以必须一次性消费） |
| 载体 | 进入 `reduce` | `Channel` / `SharedFlow`（`Channel` 保证"恰好一次"） |

> **最经典的坑**：把 Toast 作为 Intent 塞进 State，旋转屏幕后 Toast 又弹一次。正确做法是 Toast 走 Effect 通道。

### 5. 典型实现骨架

```text
Store（持有一个 State）
 ├── onIntent(intent: Intent)   // 唯一入口
 │     1. reduce(state, intent) -> newState
 │     2. state = newState（StateFlow 自动通知）
 │     3. 若 intent 需要副作用 -> 发送 Effect
 ├── state: StateFlow<State>    // View 订阅
 └── effects: Flow<Effect>      // View 订阅（一次性）
```

## 代码示例

### 1. Intent / State / Effect 定义

```kotlin
// ---- Intent：所有可能的事件，穷举 ----
sealed interface CounterIntent {
    data object Increment : CounterIntent
    data object Decrement : CounterIntent
    data object Reset : CounterIntent
    data class SetStep(val step: Int) : CounterIntent
}

// ---- State：不可变，界面渲染的唯一依据 ----
data class CounterState(
    val count: Int = 0,
    val step: Int = 1,
    val history: List<Int> = emptyList()
)

// ---- Effect：一次性副作用 ----
sealed interface CounterEffect {
    data class ShowMessage(val text: String) : CounterEffect
}
```

### 2. 纯函数 Reducer（可直接单测，无任何 Android 依赖）

```kotlin
object CounterReducer {
    fun reduce(state: CounterState, intent: CounterIntent): CounterState =
        when (intent) {
            is CounterIntent.Increment ->
                state.copy(count = state.count + state.step,
                           history = state.history + (state.count + state.step))

            is CounterIntent.Decrement ->
                state.copy(count = state.count - state.step,
                           history = state.history + (state.count - state.step))

            is CounterIntent.Reset ->
                state.copy(count = 0, history = emptyList())

            is CounterIntent.SetStep ->
                state.copy(step = intent.step.coerceIn(1, 100))
        }
}
```

### 3. Store（等价于 MVI 中的 ViewModel）

```kotlin
class CounterStore : ViewModel() {

    private val _state = MutableStateFlow(CounterState())
    val state: StateFlow<CounterState> = _state.asStateFlow()

    private val _effects = Channel<CounterEffect>(Channel.BUFFERED)
    val effects: Flow<CounterEffect> = _effects.receiveAsFlow()

    fun onIntent(intent: CounterIntent) {
        // 1. 纯函数计算新状态
        val newState = CounterReducer.reduce(_state.value, intent)

        // 2. 状态写回（StateFlow 相同值不会重复通知）
        _state.value = newState

        // 3. 需要副作用时发 Effect，而不是塞进 State
        if (intent is CounterIntent.Reset && newState.count == 0) {
            viewModelScope.launch { _effects.send(CounterEffect.ShowMessage("已重置")) }
        }
    }
}
```

### 4. Compose 侧消费（State 渲染 + Effect 一次性消费）

```kotlin
@Composable
fun CounterRoute(store: CounterStore = viewModel()) {
    val state by store.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        store.effects.collect { effect ->
            when (effect) {
                is CounterEffect.ShowMessage ->
                    Toast.makeText(context, effect.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column {
        Text("计数：${state.count}")
        Button(onClick = { store.onIntent(CounterIntent.Increment) }) { Text("+") }
        Button(onClick = { store.onIntent(CounterIntent.Reset) }) { Text("重置") }
    }
}
```

### 5. Reducer 的单测（毫秒级，无需模拟器）

```kotlin
class CounterReducerTest {
    @Test
    fun `increment adds step`() {
        val result = CounterReducer.reduce(
            CounterState(count = 5, step = 2),
            CounterIntent.Increment
        )
        assertEquals(7, result.count)
    }

    @Test
    fun `step is clamped`() {
        val result = CounterReducer.reduce(
            CounterState(step = 1),
            CounterIntent.SetStep(999)
        )
        assertEquals(100, result.step)
    }

    @Test
    fun `reduce is pure - same input same output`() {
        val s = CounterState(count = 3)
        assertEquals(CounterReducer.reduce(s, CounterIntent.Increment),
                     CounterReducer.reduce(s, CounterIntent.Increment))
    }
}
```

### 6. 进阶：把异步操作也纳入"状态机"

真实业务里数据加载是异步的，MVI 的标准做法是**把异步结果也变成 Intent**，而不是在 reduce 里直接发请求：

```kotlin
sealed interface UserIntent {
    data object Load : UserIntent
    data class LoadSuccess(val users: List<User>) : UserIntent
    data class LoadError(val message: String) : UserIntent
}

fun reduce(state: UserState, intent: UserIntent): UserState = when (intent) {
    UserIntent.Load -> state.copy(loading = true, error = null)
    is UserIntent.LoadSuccess -> state.copy(loading = false, users = intent.users)
    is UserIntent.LoadError -> state.copy(loading = false, error = intent.message)
}
```

## 常见问题 / 面试要点

**读者问：MVI 是不是就是 MVVM 换个名字？**

**AI答：**

不是，差别是**结构性的**，可以用三个可验证的标准区分：

1. **有没有单一的纯函数 Reducer**：
   - MVVM：状态可以在任意地方 `update{}`，**没有唯一入口**。
   - MVI：状态**只能**由 `reduce(state, intent)` 产生，且它必须是纯函数。
   - 判别方法：问"如果我要重放用户的所有操作，能不能得到同样的最终界面？"——MVI 能，MVVM 一般不能（因为 ViewModel 里有 IO、有随机、有时间依赖）。
2. **事件的方向语义不同**：
   - MVVM：事件 = 状态的一种（`isRefreshing: Boolean`），容易被误当状态消费。
   - MVI：**Intent 是输入**（进入 reducer），**Effect 是输出**（一次性副作用，走独立通道），两者严格分离。
3. **状态是否不可变且可穷举**：
   - MVI 通常用 `data class` + `sealed interface`，状态是"可枚举的快照"，因此才能做时间旅行/回放。

一句话：**MVI 是 MVVM + "唯一状态入口 + 纯函数 reducer + 输入输出通道分离"的严格化版本**。它把"状态机"这个概念真正落地，而不是把 StateFlow 换个名字。

---

**读者问：MVI 的 Reducer 必须是纯函数，那网络请求怎么做？**

**AI答：**

这是 MVI 落地最常见的困惑。原则：**Reducer 绝不做 IO，但 IO 的结果会被"翻译"成 Intent 回到 Reducer**。

1. **Reducer 内只允许**：纯计算（加减、映射、过滤、校验、状态迁移），**不允许**网络、数据库、文件、时钟、`Random`。
2. **副作用在 Store/ViewModel 层发起**：

```kotlin
fun onIntent(intent: UserIntent) {
    when (intent) {
        UserIntent.Load -> {
            _state.update { it.copy(loading = true) }
            viewModelScope.launch {
                val result = runCatching { repository.load() }
                result.fold(
                    onSuccess = { onIntent(UserIntent.LoadSuccess(it)) },  // 结果变回 Intent
                    onFailure = { onIntent(UserIntent.LoadError(it.message.orEmpty())) }
                )
            }
        }
        else -> _state.value = reduce(_state.value, intent)
    }
}
```

3. **注意 `fold` 的写法**：成功和失败都转成 Intent 重新进入 `onIntent`，这样**状态迁移路径唯一**，不会出现"某些分支直接改 state 导致状态机分叉"。
4. **可测试性怎么保证**：单测 Reducer 时不测 IO，只测"给定 State + Intent，输出 State 是否符合预期"；IO 部分用 Fake Repository + `runTest` 测 Store 的编排逻辑。

---

**读者问：为什么 MVI 要区分 State 和 Effect？Toast 直接放进 State 不行吗？**

**AI答：**

不行，放进 State 一定会出 bug，原因是 **State 是"当前事实"，Effect 是"发生过一次的事件"**，两者语义不同。

1. **状态是"是/否"型事实**：如 `isLoggedIn = true`、`count = 5`。它描述"现在是什么样"。
2. **Effect 是"发生过一次"**：如"刚刚弹了个 Toast"、"刚刚发起了一次导航"。它描述"发生过什么"。
3. **具体会出的 bug（旋转屏幕）**：
   - 把 Toast 写成 `state.showToast = true`，View 收到后弹 Toast 并 `update { it.copy(showToast = false) }`。
   - 旋转屏幕 → Activity 重建 → 新的 View 再次订阅 State → 又看到 `showToast` 相关状态 → **Toast 再弹一次**。
   - 同样地，`navigateToDetail` 放进 State 会导致**重复跳转**，甚至在恢复时被触发，性能与体验双重问题。
4. **正确解法**：
   - State 只放**可持久渲染的事实**（如"用户名已填"）。
   - 一次性动作用 `Channel`（`Channel.BUFFERED` + `receiveAsFlow()`）承载，**恰好消费一次**；或者用 `SharedFlow` + `extraBufferCapacity` + 明确设计"可重放"语义（需自行处理 replay）。
   - View 侧用 `LaunchedEffect` / `repeatOnLifecycle` 消费，消费完即失效。

> 面试表达："**状态回答'现在是什么'，事件回答'刚刚发生了什么'。把事件当状态就会在配置变更后重放。**"

---

**读者问：MVI 有什么缺点？实践中怎么落地才不至于过度设计？**

**AI答：**

**缺点（要主动说，显得有判断力）：**

1. **样板代码多**：每个页面都要定义 Intent、State、Effect、Reducer、Store 五件套，页面少时性价比低。
2. **State 容易膨胀**：一个大页面的 State 可能包含十几个字段，Reducer 的 `when` 越来越长。
3. **团队学习成本**：新人需要理解"为什么要分这么多层"，否则会乱写。
4. **事件流调试难度上升**：链路过长（View → Intent → Reducer → State → View），定位问题需要打断点的地方更多。

**落地建议（渐进式）：**

1. **不要一上来全量 MVI**。先用 MVVM 做基础，把"复杂状态交互的页面"（多步表单、支付流程、IM 聊天、播放器）改成 MVI。
2. **Reducer 只在纯逻辑处使用**。能用 ViewModel 方法解决的，不必强上 Reducer。
3. **Intent 用 sealed interface 穷举**，不要用 `data class Intention(val type: String)` 这种弱类型。
4. **Effect 统一走一个 Effect 基类**，避免每个页面自造一套。
5. **配合 unidirectional data flow 库**（如 `Mvvi`/`Reducer` 类库）减少样板，或在项目内做一层通用 Store 基类。

**什么时候值得用 MVI**：状态多且有明确迁移规则、交互路径可枚举、需要回放/审计、多人协作需要统一约定。

---

**读者问：面试官问"MVI 的 Reducer 怎么保证是纯函数"，我该怎么答？**

**AI答：**

给出**可执行的判定标准**，比空谈"要纯函数"更有说服力：

1. **依赖注入**：只依赖参数 `state` 和 `intent`，不读 ViewModel 的字段、不读全局单例、不读 `Context`。
2. **无副作用**：不调用网络/数据库/文件、不打日志副作用（纯日志可接受但最好不写）、不修改入参（State 用 `data class` 天然不可变，但 `List` 等要保证不被 `MutableList` 污染）。
3. **确定性**：同样输入 → 同样输出。不要在 Reducer 里用 `System.currentTimeMillis()`、`Random.nextInt()`，如果确实需要时间，把"当前时间"作为 Intent 的一部分传入（如 `Tick(val now: Long)`），这样才可回放。
4. **无顺序依赖**：`reduce(s, i1)` 后 `reduce(., i2)` 的结果 = `reduce(reduce(s,i1), i2)`，即满足结合律，才能重放。
5. **可回放验证**：把 (State, Intent) 序列记录下来，重放应得到完全相同的最终 State——这是纯函数最强的**可执行证明**，也是可以写进单测的。

> 补一句加分项：MVI 的"可回放"特性在生产上价值很大——线上崩溃时可以用用户操作序列回放到本地复现，这是纯函数架构的复利。

## 关联笔记

- [[MVVM]]：MVI 的直接前身
- [[架构模式选型]]：MVVM/MVI 何时选谁
- [[../Jetpack库/LiveData与StateFlow]]：State 与 Effect 的载体选择
- [[../Jetpack库/Lifecycle与ViewModel]]：Store 的宿主生命周期
- [[../测试/单元测试]]：Reducer 纯函数测试
- [[../README]]：第 7 部分总览

## 参考资料

- Google 官方架构指南 · "UI 层"章节中的单向数据流：https://developer.android.com/topic/architecture/ui-layer
- Riverpod / Mobius 等库对 MVI 变体的实现思路
- 《重构》第 2 版 · 第 8 章"用 State 承载状态"、第 11 章"用不可变对象承载状态"
