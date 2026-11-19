---
tags: [android]
part: 第1部分
status: 待整理
difficulty: 高级
created: 2026-11-19
updated: 2026-11-19
related: "[[高阶函数与扩展]], [[DSL]], [[../Java/JVM内存模型]], [[../README]], [[../../07-架构、工具与生态/Jetpack库/StateFlow与SharedFlow]]"
---

# 协程与Flow

## 一句话定义

协程（Coroutine）是 Kotlin 提供的**挂起式（suspending）**并发原语：一个函数可以在执行中途**挂起（suspend）**让出线程，稍后再**恢复（resume）**接着跑，从而在**单线程**上实现并发效果而不必为每个任务分配线程。`Flow` 则是在协程之上构建的**冷流**（cold flow）数据管道，用声明式操作符表达**异步数据流**。一句话记忆：**协程解决"并发执行"，Flow 解决"流式数据处理"，二者共用同一套挂起机制**。

## 核心原理

### 1. 协程三要素：Continuation + CoroutineContext + suspend 函数

理解协程必须先理解这三样东西缺一不可：

| 要素 | 是什么 | 作用 | 面试关键词 |
| --- | --- | --- | --- |
| **`suspend` 函数** | 被 `suspend` 修饰的函数，返回值类型是 `COROUTINE_SUSPENDED` | 提供"可挂起点"，让编译期知道这里可能挂起 | CPS 转换、状态机 |
| **`Continuation<T>`** | 编译器为每个 `suspend` 函数生成的回调接口 | 保存"挂起时的现场 + 恢复时的回调" | `resumeWith(Result)`、`context` |
| **`CoroutineContext`** | 协程的上下文（`CoroutineId` + `Job` + `ContinuationInterceptor` + 线程上下文元素） | 决定协程"在哪跑、归谁管、叫什么" | `Dispatcher`、`Job`、ThreadLocal |

```text
                 ┌──────────────────────────────────────────────┐
                 │            CoroutineScope                    │
                 │  ┌────────────────────────────────────────┐  │
                 │  │        CoroutineContext                 │  │
                 │  │  ├─ ContinuationInterceptor(Dispatcher) │  │
                 │  │  ├─ Job (层级 / 取消 / 完成)             │  │
                 │  │  ├─ CoroutineName (调试)                 │  │
                 │  │  └─ ThreadLocalElement (MDC 等)          │  │
                 │  └────────────────────────────────────────┘  │
                 │              │ launch/async                  │
                 │              ▼                              │
                 │      ┌──────────────┐                       │
                 │      │  Coroutine   │  持有 context + Job   │
                 │      └──────┬───────┘                       │
                 │             │                               │
                 │      ┌──────▼───────────────────────┐       │
                 │      │  挂起函数体 (StateMachine)     │       │
                 │      │  编译成 while(true) switch 状态机 │       │
                 │      │  每次挂起 → 保存 Continuation   │       │
                 │      └──────┬───────────────────────┘       │
                 │             │ 挂起时保存到 Continuation 中     │
                 │      ┌──────▼───────┐                       │
                 │      │ Continuation │  现场 + resume 回调     │
                 │      └──────┬───────┘                       │
                 │             │ 恢复时由 Dispatcher 调度回线程   │
                 │             ▼                               │
                 │      回到挂起点继续执行                       │
                 └──────────────────────────────────────────────┘
```

**`Continuation` 的关键代码**（kotlinx-coroutines-core 的 `kotlin.coroutines.Continuation`）：

```kotlin
public interface Continuation<in T> {
    public val context: CoroutineContext
    public fun resumeWith(result: Result<T>)
}

// 编译器为每个 suspend 函数生成实现
public abstract class SuspendLambda<in T> : Continuation<T> {
    public abstract suspend fun invokeSuspend(result: Any?): Any?
}
```

**核心结论**：协程挂起时**不阻塞线程**，只是把"下一步怎么走"（`Continuation`）保存下来；恢复时由 `Dispatcher` 决定在哪个线程上调用 `resumeWith`。**线程是被复用的，不是被独占的**。

### 2. 状态机原理：编译器如何把 suspend 函数变成 while(true) switch

这是**面试官追问 3 层**必考的一层。Kotlin 编译器对含有 `suspend` 调用的函数做 **CPS（Continuation Passing Style）转换 + 状态机改写**。

原始代码：

```kotlin
suspend fun loadUser(id: String): User {
    val token = fetchToken()        // 挂起点 1
    val user = api.getUser(token, id) // 挂起点 2
    return user.copy(name = user.name.trim())
}
```

编译器改写后大致等价于：

```kotlin
fun loadUser(id: String, $completion: Continuation<User>): Any {
    var result: Any? = null
    var label: Int = 0                       // 状态标签
    while (true) {
        when (label) {
            0 -> {
                label = 1
                try {
                    val token = fetchToken($completion)   // 返回 COROUTINE_SUSPENDED 就返回
                    result = token
                } catch (e: Throwable) {
                    $completion.resumeWith(Result.failure(e))
                    return Unit
                }
            }
            1 -> {
                label = 2
                try {
                    val user = api.getUser(result as String, id, $completion)
                    result = user
                } catch (e: Throwable) { ... }
            }
            2 -> {
                val user = result as User
                $completion.resumeWith(Result.success(user.copy(name = user.name.trim())))
                return Unit
            }
        }
    }
}
```

关键点：

1. **每个挂起点对应一个 `label`**，恢复时通过 `label` 跳回正确的位置继续执行。
2. **挂起函数返回类型是 `Any?`**：要么是真正的返回值，要么是 `COROUTINE_SUSPENDED` 这个特殊单例（`kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED`），表示"我挂起了，等会儿 `resumeWith` 回调我"。
3. **异常处理变成 `try/catch` 包裹**，取消异常 `CancellationException` 也走这条路径，最终被 `resumeWith(Result.failure(e))` 交给 `Job` 处理。
4. **编译期就完成了转换**，没有任何运行时反射或字节码增强 —— 这也是协程相比 Java 8 `CompletableFuture` 的优势：**零反射、零线程阻塞、栈干净**。

> [!tip] 面试话术
> "协程的本质是**编译器帮你把阻塞式代码改写成状态机**。挂起不是阻塞线程，而是**保存一个 `Continuation`（现场 + 恢复回调）并把线程还回去**；恢复时由 `Dispatcher` 决定在哪个线程上 `resumeWith` 这个 `Continuation`。所以协程能在单线程上跑出并发效果，而线程始终是可复用的。"

### 3. 挂起与恢复：完整的生命周期

```text
launch(Dispatchers.Main) {          // ① 创建协程，分配 Job
    println("A")                     // ② 在 Main 线程执行
    val x = withContext(Dispatchers.IO) {
        println("B")                 // ③ 切到 IO 线程执行
        delay(100)                   // ④ delay 挂起：保存 Continuation，IO 线程返回线程池
        42                           // ⑤ 恢复：在 IO 线程计算结果
    }                                 // ⑥ withContext 返回，Dispatchers.Unconfined 语义回到 Main
    println("C: $x")                 // ⑦ 继续在 Main 线程
}                                     // ⑧ 协程结束，Job 完成
```

关键机制：

- **挂起（suspend）**：`delay()` / `suspendCancellableCoroutine` / 阻塞 API 的包装会让当前协程挂起。挂起时**释放线程**，线程从线程池中被取走干别的活。
- **恢复（resume）**：当条件满足（定时器到期、IO 完成、锁拿到），`Continuation.resumeWith(Result)` 被调用；`ContinuationInterceptor`（即 `Dispatcher`）捕获这个调用，把协程**调度回**合适的线程继续执行 `while(true)` 循环。
- **调度（dispatch）**：恢复 ≠ 立刻在原线程执行。`Dispatchers.Main` 会 post 到 `Handler(Looper.getMainLooper())`；`Dispatchers.Default` 提交到线程池。

### 4. 协程分类：作用域、调度器、结构化并发

#### 4.1 协程作用域（CoroutineScope）

| Scope | 生命周期 | 典型用途 |
| --- | --- | --- |
| `viewModelScope` | ViewModel 的 `onCleared()` | ViewModel 里发请求、订阅数据 |
| `lifecycleScope` | Lifecycle 降到 DESTROYED | Activity/Fragment 里收集 Flow |
| `rememberCoroutineScope()` | Compose 组件离开组合 | Compose 里发一次性协程 |
| `GlobalScope` | 进程存活期（**危险**） | 几乎不该用 |
| `CoroutineScope(SupervisorJob() + Dispatchers.IO)` | 手动管理，需自己 cancel | 应用级长任务（需配合生命周期回调取消） |

> [!warning] ⚠️ `GlobalScope` 的三个问题
> 1. **不结构化**：父作用域取消时它不会取消 → 协程泄漏。
> 2. **异常会崩进程**：`GlobalScope.launch` 里的未捕获异常会交给 `CoroutineExceptionHandler`，没有则走默认处理器 → **Crash**。
> 3. **没有 Job 关联**：`lifecycleScope` 会在 Activity 销毁时自动 cancel，`GlobalScope` 不会 → 持有 View 导致内存泄漏。

#### 4.2 结构化并发（Structured Concurrency）

结构化并发的含义是：**每个协程都隶属于一个作用域，作用域的取消会递归向下传播，作用域会等待所有子协程完成**。

```text
parentScope (Job A)
   │  A 被 cancel → 向下传播
   ├── child1 (Job B)  ──cancel──►  B 的子协程
   ├── child2 (Job C)  ──cancel──►
   └── child3 (Job D)  ──cancel──►

规则 1：父 Job 取消 → 所有子 Job 立即进入 cancelling 状态
规则 2：父 Job 的 complete() 会挂起，直到所有子 Job 完成（join）
规则 3：子 Job 失败 → 默认向上传播，父 Job 失败（除非父是 SupervisorJob）
```

#### 4.3 Job 层级：launch vs async

| API | 返回 | 失败传播 | 异常处理 | 典型用法 |
| --- | --- | --- | --- | --- |
| `launch` | `Job` | ✅ 向上传播，父作用域失败 | `CoroutineExceptionHandler` / `try-catch` | 不需要返回值的并发任务 |
| `async` | `Deferred<T>` | ✅ 向上传播（`await` 时抛） | `await()` 处捕获 | 需要返回值的并发任务 |
| `coroutineScope { }` | `T` | ✅ 子协程失败 → 抛给调用方 | `try-catch` | 串行结构化（顺序执行、结果汇总） |
| `supervisorScope { }` | `T` | ❌ 子协程失败互不影响 | 各自处理 | 一个失败不影响其他 |

**`launch` vs `async` 的经典错误**：

```kotlin
// ❌ 错误：async 的异常不会在这里被捕获，会随 await 抛出
launch {
    val result = async { risky() }.await()  // OK，异常在 await 处抛
}

// ❌ 更常见的错误：并发请求时只 await 一个，另一个的异常被吞
val results = coroutineScope {
    val a = async { api1() }
    val b = async { api2() }
    listOf(a.await(), b.await())   // 若 api1 抛异常，api2 也被取消
}
```

> 面试追问：**"两个 async 并发，一个失败了另一个怎么办？"**
> 答：在同一个 `coroutineScope` 里，一个 `async` 失败会**取消父作用域**，从而取消另一个（结构化并发的"失败即取消"语义）。如果希望互不影响，就用 `supervisorScope` + 每个 `async` 内部自己 `runCatching`。

### 5. 取消的协作性（Cooperative Cancellation）

**这是协程最重要也最容易被误解的机制。**

取消**不是中断（interrupt）**，而是设置一个"取消标志"（`Job` 状态变为 `Cancelling`），协程在**下一个挂起点**检查并抛出 `CancellationException`。

```text
cancel() 触发
    │
    ▼
Job 状态 → Cancelling（isActive = false）
    │
    ▼
向所有子 Job 传播
    │
    ▼
子协程在【下一个挂起点 / 取消检查点】抛出 CancellationException
    │
    ├─▶ CPU 密集型代码：没有挂起点 → 永远不会停！⚠️
    ├─▶ catch (e: Exception) 会把 CancellationException 一起吃掉 ⚠️
    └─▶ finally 块仍会执行（用于资源清理）
```

**协作性取消的三个必答点**：

1. **CPU 密集任务需要手动检查取消**：`while (true) { if (!isActive) break; heavyCompute() }`，或者用 `ensureActive()`。
2. **不要吞掉 `CancellationException`**：正确的写法是

```kotlin
try {
    doWork()
} catch (e: CancellationException) {
    throw e          // ✅ 必须重新抛出，否则协程"假装成功"，破坏结构化并发
} catch (e: IOException) {
    // ✅ 只处理真正的业务异常
}
```

3. **`withContext(NonCancellable)` 用于必须执行的清理**：在 `finally` 里做 IO（比如关闭文件）时，需要包一层 `NonCancellable`，否则清理动作会被立即取消。

```kotlin
suspend fun load(): Data = try {
    fetchData()
} finally {
    withContext(NonCancellable) {
        cache.flush()   // 取消状态下也必须执行完
    }
}
```

### 6. Dispatchers 切换原理与线程池复用

| Dispatcher | 底层线程 | 数量 | 适用场景 |
| --- | --- | --- | --- |
| `Dispatchers.Main` | 主线程 `Looper` | 1（+ `Handler.immediate` 特性） | UI 更新、生命周期感知 |
| `Dispatchers.Main.immediate` | 同上 | 1 | 已在主线程时**不切换**，直接执行 |
| `Dispatchers.IO` | 弹性线程池 | 最多 64（`max(64, cpus)`） | 阻塞 IO（网络、磁盘） |
| `Dispatchers.Default` | 固定 CPU 核数线程池 | `max(2, cpus)` | CPU 密集（计算、加密、压缩） |
| `Dispatchers.Unconfined` | 当前线程 / 恢复线程 | — | 微秒级轻量任务，**会破坏结构化并发**，生产少用 |

**切换原理**：

```text
withContext(Dispatchers.IO) { ... }
   │
   ├─ ① 检查 newContext 与旧 context 是否相同 → 相同则不切换，直接执行
   │
   ├─ ② 创建新的 DispatchedContinuation（持有旧 Continuation 作为 delegate）
   │
   ├─ ③ 通过 ContinuationInterceptor.interceptContinuation() 包装
   │
   ├─ ④ 调用 newContext[ContinuationInterceptor].dispatchResume(context, block)
   │     └─ Dispatchers.IO → 提交到 ElasticThreadPool 线程池执行 block
   │
   └─ ⑤ block 执行完 → resumeWith(OldContinuation)
         └─ Unconfined / 旧 Dispatcher → 回到原线程继续
```

**线程池复用**：Kotlin 协程的 `Dispatchers.IO` 与 `Dispatchers.Default` **共享同一个底层线程池实现（`DefaultScheduler`）**，区别在于 IO 的 `maxConcurrency = 64`、Default 的 `maxConcurrency = CPU 核数`。两个 dispatcher 可以**共享线程**——这正是 IO 线程数可以超过核数的原因。

**线程池复用带来的"线程污染"问题**：

```kotlin
withContext(Dispatchers.IO) {
    // 这里可能运行在一个被复用的 IO 线程上
    val locale = Locale.getDefault()   // ← 读取的是该线程的 ThreadLocal
}
```

解决：用 `ThreadLocal.asContextElement()` 把线程局部变量带入协程上下文。

### 7. Flow：冷流 / 热流与收集语义

```text
              冷流 Cold Flow                    热流 Hot Flow
        ┌────────────────────────┐        ┌────────────────────────┐
        │ 每次 collect 都从头执行  │        │ 独立于 collect 一直存在 │
        │  有上游生产者 + 下游消费者 │        │  多个收集者共享同一上游 │
        │  例：flow { emit(1) }  │        │  例：StateFlow/SharedFlow│
        └────────────────────────┘        └────────────────────────┘
```

| 维度 | 冷流（`Flow`） | 热流（`StateFlow` / `SharedFlow`） |
| --- | --- | --- |
| 每次 `collect` | **重新执行上游**（新的生产者） | 订阅已存在的生产者 |
| 多收集者 | 每个收集者独立触发上游 | 共享一个上游实例 |
| 是否会丢数据 | 不丢（默认 suspend 背压） | 可能丢（replay=0 且无订阅者） |
| 典型 | `flow{}` / `Flow` 转换链 | `StateFlow` / `SharedFlow` / `callbackFlow` |
| 生命周期 | 收集者控制 | 由生产者控制 |

### 8. Flow 操作符全解

| 操作符 | 作用 | 是否改变上下文 | 是否挂起 |
| --- | --- | --- | --- |
| `map` | 转换每个元素 | ❌ | ❌（同步） |
| `filter` | 过滤元素 | ❌ | ❌ |
| `transform` | 更灵活的转换（可发射多个） | ❌ | ❌ |
| `flatMapConcat` | **顺序**扁平化（串行等待每个内部流） | ❌ | ✅ |
| `flatMapMerge` | **并发**扁平化 | ❌ | ✅ |
| `flatMapLatest` | 取最新（取消前一个） | ❌ | ✅ |
| `flowOn` | **切换上游**上下文 | ✅ 只影响上游 | ✅ |
| `catch` | 捕获上游异常 | ❌ | ❌ |
| `onEach` | 副作用（打日志） | ❌ | ❌ |
| `buffer` | 引入缓冲，解耦生产消费 | ❌ | ✅ |
| `conflate` | 只保留最新值（合流） | ❌ | ❌ |
| `takeWhile` / `dropWhile` | 条件截断 | ❌ | ❌ |
| `distinctUntilChanged` | 去重（相邻） | ❌ | ❌ |
| `combine` / `merge` | 合并多个流 | ❌ | ✅ |
| `stateIn` / `shareIn` | 冷流转热流 | — | ✅ |

**`flowOn` 的关键规则（面试高频）**：

> `flowOn` **只影响它上方（upstream）的代码**，不影响下方。
> 因为 Flow 的操作符是**洋葱模型**：数据自上而下流过，回调自下而上传播。

```text
flow {
    println("上游 A")          // ← flowOn(Dispatchers.IO) 影响这里
    emit(1)
}.map {
    println("中间 B")          // ← 不受影响
    it
}.flowOn(Dispatchers.IO).collect {
    println("下游 C")          // ← 不受影响
}
```

**多个 `flowOn` 会合并**：离数据源最近的 `flowOn` 生效（`flowOn(IO).flowOn(Default)` 等价于 `flowOn(IO)`）。

### 9. 背压（Backpressure）与 buffer

**背压是 Reactive Streams 的核心概念**：下游消费不过来时，必须有机制把压力**向上游传导**，否则会 OOM。

```text
无 buffer（默认）：
  Producer ──emit──► [直接交给 collector] ──► Consumer
  若 collector 处理慢，emit 会挂起（背压传导），producer 停住 ✅

有 buffer(n)：
  Producer ──emit──► [环形缓冲 n] ──► Consumer
  缓冲满时 emit 挂起；缓冲空时 consumer 等待 ✅ 允许一定程度的并行

conflate()：
  Producer ──emit──► [容量 1，始终丢旧留新] ──► Consumer
  永不阻塞 producer，永远不丢最新值 ✅ 适合"只关心最新"的场景
```

```kotlin
// buffer：让上游在 IO 线程继续跑，下游在主线程慢慢处理
flow {
    repeat(100) { emit(it) }
}.buffer(64)                    // 最多缓冲 64 个
 .map { delay(10); it }         // 下游慢处理
 .collect { render(it) }
```

**常见误用**：`buffer` 只是"允许上下游解耦"，**不会增加总吞吐量**（CPU 瓶颈时）。`conflate` 会**丢数据**——用之前先确认业务能接受丢中间值。

### 10. SharedFlow vs StateFlow vs Channel

| 维度 | `StateFlow` | `SharedFlow` | `Channel` |
| --- | --- | --- | --- |
| 语义 | **当前状态** | **事件流** | **点对点队列** |
| 有初始值 | ✅ 必须 | ❌ | ❌（可 `Channel()`） |
| 粘性 | ✅ 必然（replay=1） | 可配（replay=0 默认不粘） | ❌ |
| 去重 | ✅ 按 `equals` 合流 | ❌ | ❌ |
| 多消费者 | 共享一个 value（读同一状态） | **广播**，人人一份 | **竞态消费**，一个消息只被一个接收者取走 |
| 背压 | 不适用 | `emit` 可挂起 | `send` 可挂起（缓冲满） |
| 关闭 | 不需要 | 不需要 | 需要 `close()` |
| 典型 | UI 状态 | Toast / 导航 / 埋点 | 生产者-消费者、一次性事件 |

**关键区别场景**：

```kotlin
// Channel：一次性的"命令"，如导航指令 —— 必须保证不丢、只处理一次
private val _nav = Channel<NavEvent>(Channel.BUFFERED)
val nav: Flow<NavEvent> = _nav.receiveAsFlow()

// SharedFlow：广播给多个观察者，如全局埋点事件
private val _events = MutableSharedFlow<AppEvent>(replay = 0)

// StateFlow：状态容器
private val _state = MutableStateFlow(UiState())
```

> [!tip] 面试话术
> "`StateFlow` 是**状态**，永远只有一个最新值，订阅者读的是同一份；`SharedFlow` 是**事件**，广播给所有订阅者，可能丢；`Channel` 是**队列**，点对点消费，一个消息只被一个人拿走。选型的判断依据是：**这份数据是"现在是什么"还是"发生了什么"，以及要不要保证不丢、只消费一次**。"

### 11. 高频 API 语义速查

| API | 语义 | 关键点 |
| --- | --- | --- |
| `runBlocking` | 阻塞当前线程直到协程完成 | **仅用于 `main()` 入口和测试**，生产代码禁用 |
| `withContext` | 切换上下文执行块，**返回块的值** | 挂起函数；不切换时几乎零开销 |
| `launch` | 启动并发任务，不返回值 | 返回 `Job` |
| `async` | 启动并发任务并返回 `Deferred` | 必须 `await()` 才拿结果 |
| `coroutineScope` | 结构化作用域（子协程失败会抛出） | 顺序执行、结果汇总 |
| `supervisorScope` | 子协程失败互不影响 | 一个失败不拖垮其他 |
| `SupervisorJob` | 无父 Job 的特殊 Job | 手动管理取消 |
| `CoroutineExceptionHandler` | 协程未捕获异常的兜底处理器 | **只对 `launch` 顶层有效**，`async` 的异常在 `await` 处抛 |
| `suspendCancellableCoroutine` | 把回调 API 转成挂起函数 | **支持取消**，回调时先 `isActive` 检查 |
| `callbackFlow` | 把回调 API 转成 Flow | **必须 `awaitClose { unregister() }`** |
| `channelFlow` | 用 `send` 发射的 Flow | 可并发 `send` |
| `Dispatchers.Main.immediate` | 已在主线程则不切换 | 避免不必要的 post |

**`suspendCancellableCoroutine` 的标准写法**：

```kotlin
suspend fun <T> Call<T>.await(): T = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback<T> {
        override fun onResponse(call: Call<T>, response: Response<T>) {
            if (!cont.isActive) return          // ① 已取消则丢弃回调
            if (response.isSuccessful) {
                cont.resume(response.body()!!) // ② 恢复协程
            } else {
                cont.resumeWithException(HttpException(response)) // ③ 抛异常
            }
        }
        override fun onFailure(call: Call<T>, t: Throwable) {
            if (!cont.isActive) return
            cont.resumeWithException(t)
        }
    })
    // ④ 协程被取消时取消网络请求
    cont.invokeOnCancellation { cancel() }
}
```

## 代码示例

### 1. 结构化并发：并发请求 + 失败隔离

```kotlin
/**
 * 并发加载用户信息与订单列表。
 * 关键点：
 * 1. coroutineScope 保证子协程全部完成后才返回，且父作用域能感知失败
 * 2. supervisorScope 隔离单个子任务的失败（这里用 async + runCatching 双重保险）
 * 3. withContext(Dispatchers.IO) 把阻塞 IO 移出主线程
 */
suspend fun loadDashboard(userId: String): Dashboard = supervisorScope {
    // 两个子协程并发执行；互不阻塞
    val profile = async(Dispatchers.IO) {
        runCatching { api.fetchProfile(userId) }.getOrElse { Profile.empty() }
    }
    val orders = async(Dispatchers.IO) {
        runCatching { api.fetchOrders(userId) }.getOrElse { emptyList() }
    }
    Dashboard(
        profile = profile.await(),       // await 会挂起等待，但两个 async 是并发的
        orders = orders.await()
    )
}
```

### 2. 取消的正确处理：区分业务异常与取消异常

```kotlin
class SyncManager(private val repo: Repository) {

    /**
     * 用 suspendCancellableCoroutine 把回调式 API 转成挂起函数。
     * 好处：协程被取消时，网络请求也会被取消，不会泄漏。
     */
    suspend fun sync(): Result<Unit> = try {
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                repo.syncAsync(object : Repository.Callback {
                    override fun onDone() {
                        if (cont.isActive) cont.resume(Unit)   // 已取消则直接忽略
                    }

                    override fun onError(e: Throwable) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                })
                // 协程取消时同步终止底层请求
                cont.invokeOnCancellation { repo.cancelSync() }
            }
        }
        Result.success(Unit)
    } catch (e: CancellationException) {
        // ✅ 关键：CancellationException 必须重新抛出，不能被业务逻辑吞掉
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

### 3. 冷流与热流的组合：Flow 管道 + StateFlow 状态容器

```kotlin
class UserViewModel(private val repo: UserRepository) : ViewModel() {

    // 1. 冷流：Repository 返回的 Flow，每次 collect 都会重新请求
    private val upstream: Flow<User> = repo.observeUser()

    // 2. 转热流：stateIn 让上游只跑一次，多个观察者共享
    val user: StateFlow<UserUiState> = upstream
        .map<User, UserUiState> { UserUiState.Loading -> data }
        .catch { emit(UserUiState.Error(it.message ?: "未知错误")) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),  // 5 秒内无订阅者则停止上游
            initialValue = UserUiState.Loading
        )

    // 3. 副作用流：map 中做 IO，用 flowOn 挪到 IO 线程
    fun refresh() {
        viewModelScope.launch {
            repo.refresh()
                .onEach { Log.d(TAG, "refreshed: $it") }
                .flowOn(Dispatchers.IO)
                .catch { Log.e(TAG, "refresh failed", it) }
                .collect()
        }
    }
}
```

### 4. callbackFlow：把回调式 API 变成 Flow（带背压与取消）

```kotlin
/**
 * 把 Android 的位置监听回调包装成冷流。
 * 关键点：
 * 1. awaitClose 必须调用，否则回调一直持有 Flow 导致泄漏
 * 2. trySend 而非 send：LocationListener 回调不在协程上下文内，不能挂起
 */
fun LocationManager.locationUpdates(): Flow<Location> = callbackFlow {
    val listener = LocationListener { location ->
        // trySend：非挂起版 send，缓冲区满时返回 false（此处位置更新可丢弃）
        trySend(location)
    }
    // 注册监听
    requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 10f, listener)
    // 关键：通道关闭（收集结束/取消）时注销监听
    awaitClose { removeUpdates(listener) }
}
    .flowOn(Dispatchers.Default)   // 回调可能在 Binder 线程，用 flowOn 隔离
```

### 5. 完整的 Flow 操作符组合与缓冲区策略

```kotlin
/**
 * 组合多个数据源，按需合流，并处理异常。
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun observeSearchResults(queryFlow: Flow<String>): Flow<SearchUiState> =
    queryFlow
        // ① debounce：防抖，避免用户每敲一个字就发请求（300ms 内的输入被合并）
        .debounce(300)
        // ② distinctUntilChanged：去掉重复查询
        .distinctUntilChanged()
        // ③ mapLatest：新的查询到来时取消旧的请求（取最新）
        .mapLatest { q ->
            if (q.isBlank()) SearchUiState.Empty
            else SearchUiState.Loading
        }
        // ④ flatMapConcat：串行扁平化，保证结果按查询顺序发出
        .flatMapConcat { state ->
            when (state) {
                is SearchUiState.Empty -> flowOf(state)
                is SearchUiState.Loading ->
                    repo.search(queryFlow)                 // 冷流
                        .map { SearchUiState.Content(it) as SearchUiState }
                        .onStart { emit(SearchUiState.Loading) }
                        .catch { emit(SearchUiState.Error(it.message ?: "")) }
            }
        }
        // ⑤ flowOn：把上面所有网络操作挪到 IO 线程（只影响上游）
        .flowOn(Dispatchers.IO)
        // ⑥ buffer：在上游和下游之间加缓冲，让上游不必等待下游渲染
        .buffer(16)
        // ⑦ conflate：如果下游（渲染）很慢，只保留最新结果
        .conflate()
```

### 6. 用 Channel 表达"只消费一次"的一次性事件

```kotlin
class LoginViewModel : ViewModel() {

    // Channel：点对点、缓冲、恰好一次 —— 适合导航指令这类"必须处理一次"的事件
    private val _navigation = Channel<NavCommand>(Channel.BUFFERED)
    val navigation: Flow<NavCommand> = _navigation.receiveAsFlow()

    fun onLoginSuccess() {
        viewModelScope.launch {
            // send 是挂起的：缓冲满时会等，不会丢事件（对比 tryEmit）
            _navigation.send(NavCommand.GoHome)
        }
    }

    override fun onCleared() {
        // Channel 不需要显式 close：viewModelScope 取消后 receiveAsFlow 会结束
        super.onCleared()
    }
}
```

## 常见问题 / 面试要点

**读者问：协程到底解决了什么问题？为什么不用 Thread / Handler / RxJava？**

**AI答：**

1. **解决的核心问题**：Thread 的问题是**数量与切换成本**。IO 密集型任务用线程池，线程数一多上下文切换开销大；用单线程又阻塞。协程的答案是**挂起而非阻塞**——挂起时释放线程，一个线程可以承载成千上万个并发任务。
2. **与三种方案的对比**：

   | 方案 | 线程占用 | 取消 | 异常传播 | 生命周期绑定 | 代码侵入 |
   | --- | --- | --- | --- | --- | --- |
   | Thread/Executor | 独占 1 个线程 | `interrupt`（不可靠） | 靠 `Future.get` | ❌ 手动管理 | 高（回调地狱） |
   | RxJava | 独占 1 个线程 | `Disposable.dispose` | `onError` | 需手动绑定 | 高（链式 API 学习成本） |
   | Kotlin 协程 | **挂起时释放线程** | `Job.cancel()`（协作式） | 结构化自动传播 | ✅ `lifecycleScope`/`viewModelScope` | **低（写同步代码）** |

3. **协程最大的杀手锏是"结构化并发"**：任务的生命周期天然嵌套在作用域里，父取消子必取消，子完成父才完成，异常自动向上传播并被正确处理。
4. **面试话术**："协程不是比线程更轻的线程，它是**编译器支持的挂起函数 + 状态机**。没有挂起点的地方（比如纯 CPU 死循环），协程和线程行为一致——这也是 `Dispatchers.Default` 存在的意义。"

---

**读者问：`launch`、`async`、`runBlocking`、`withContext` 有什么区别？各自用在哪？**

**AI答：**

1. **`launch`**：启动一个**不需要返回值**的并发任务，返回 `Job`。
   - 用法：`viewModelScope.launch { doSomething() }`
   - 异常：未捕获异常会向上传播给父 Job，最终可能崩溃（`async` 不会，`await` 处才抛）。
2. **`async`**：启动一个**需要返回值**的并发任务，返回 `Deferred<T>`。
   - 用法：`val data = async { repo.load() }.await()`
   - **必须 await**，否则协程可能还没完成就返回了；忘记 `await` 是经典 bug。
3. **`withContext(Dispatchers.IO) { }`**：**切换上下文**并返回块的值。
   - 它是**挂起函数**，会挂起当前协程，等块执行完再回到原上下文。
   - 内部实现上会创建一个新的 `DispatchedContinuation`，本质上是"协程内嵌套协程"。
   - 用途：把阻塞 IO 挪出主线程，同时拿回结果。
4. **`runBlocking`**：**阻塞当前线程**直到协程完成。
   - 用法：`fun main() = runBlocking { delay(1000); println("done") }`
   - **生产代码禁用**：它会占住一条线程（例如主线程），完全违背协程的设计初衷。
   - **允许的场合**：`main()` 入口、JUnit 测试（`runTest` 除外）、脚本。
5. **一句话记法**：`launch` 不要结果、`async` 要结果、`withContext` 换线程并要结果、`runBlocking` 同步等结果。

> 补充追问：**`async` 里不 `await` 会怎样？**
> `async` 返回的 `Deferred` 若从未被 `await`，它的异常会被"吞掉"（因为没人来取），这在 Kotlin 1.6+ 会输出警告（`CoroutineCreationDuringComposition` 之类），实际项目中会**静默丢失错误**。

---

**读者问：`SupervisorJob` 和 `SupervisorScope` 有什么区别？什么时候用？**

**AI答：**

1. **本质区别**：
   - `SupervisorJob()` 是一个 **`Job` 本身**，可以作为 `CoroutineScope` 的元素，用 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 创建作用域。
   - `supervisorScope { }` 是一个**挂起函数**，创建一个作用域，在这个作用域内 `launch` 的子协程互不影响。
2. **共同语义**：**子协程失败不会取消父作用域，也不会取消兄弟协程**。
3. **核心差异**：
   - `SupervisorJob` 手动管理，需要自己 `cancel()`（否则泄漏）。
   - `supervisorScope` 是**结构化的**——代码块结束时自动等待/清理子协程。
4. **什么时候用**：
   - 一个页面要**并行做多件互不影响的事**（如同时请求用户资料、订单、消息），其中一个失败不应影响其他 → `supervisorScope`。
   - 应用级常驻任务（长连接、轮询），需要跨多个页面存活 → `SupervisorJob` + 手动 `cancel()`。
5. **关键点**：即使在 `supervisorScope` 里，**未捕获的异常仍然会走 `CoroutineExceptionHandler`**（不吞、不崩溃，但要配 Handler）；而在普通 `coroutineScope` 里，异常会向上抛给调用方。

---

**读者问：`CoroutineExceptionHandler` 什么时候会生效？为什么 `async` 里的异常它抓不到？**

**AI答：**

1. **生效条件**：`CoroutineExceptionHandler` **只对协程的"根"（root coroutine）未捕获异常生效**，也就是 `launch` 启动的最外层协程。
2. **原理**：
   - `launch` 的根协程在完成时，如果 `Job` 以异常结束，`JobSupport` 会调用 `handleCoroutineException(context, exception)`。
   - 这个方法遍历 `CoroutineContext` 找 `CoroutineExceptionHandler`，找到就调它；找不到就交给 `Thread.currentThread().uncaughtExceptionHandler` → **崩溃**。
3. **`async` 为什么抓不到**：`async` 把异常**存到了 `Deferred` 里**，等待 `await()` 时才重新抛出。所以：
   - `async` 的异常**不会**立刻触发 Handler，也不会崩溃。
   - 异常在 **`await()` 那一行**抛出，需要用 `try/catch` 捕获。
4. **陷阱**：
   - `async` 创建后**从不 `await`** → 异常被静默吞掉（Kotlin 1.6+ 有警告）。
   - 在**子协程**里加 Handler 无效（子协程的异常会向父传播，由根协程的 Handler 处理）。
5. **正确姿势**：

```kotlin
val handler = CoroutineExceptionHandler { _, e -> Log.e(TAG, "uncaught", e) }

// 根协程：Handler 生效
scope.launch(handler) { throw RuntimeException("boom") }

// async：要在 await 处 catch
scope.launch {
    try {
        val r = async { risky() }.await()   // 异常在这里
    } catch (e: Exception) { handle(e) }
}
```

---

**读者问：Flow 为什么是冷流？`collect` 两次会发生什么？**

**AI答：**

1. **冷流的定义**：Flow 的**上游代码在 `collect` 时才真正开始执行**。`flow { ... }` 里的代码在每次 `collect` 时都会重新跑一遍。
2. **collect 两次的结果**：

```kotlin
val flow = flow {
    println("上游开始")
    emit(1); emit(2)
}
flow.collect { println("A: $it") }   // 打印：上游开始 / A:1 / A:2
flow.collect { println("B: $it") }   // 再次打印：上游开始 / B:1 / B:2
```

   → 上游**执行了两次**，产生了**两份独立的数据**。这就是冷流。

3. **热流对比**：`StateFlow`/`SharedFlow` 的上游独立存在，`collect` 拿到的是**同一份数据的不同订阅**，上游不会重新跑。
4. **工程意义**：
   - 冷流适合"**按需拉取**"的场景（点下拉刷新才请求）。
   - 但如果直接用冷流给多个界面提供数据，会**重复请求** → 需要 `stateIn` / `shareIn` 转热。
5. **常见坑**：

```kotlin
// ❌ 每次 recomposition 都可能重新 collect（虽然 collectAsState 有 remember 保护）
val state by flow.collectAsState(initial)

// ✅ 用 stateIn 在 ViewModel 里转成 StateFlow，UI 只订阅不触发上游
val state = repo.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Initial)
```

---

**读者问：`flowOn` 到底影响哪一部分？`catch` 能捕获下游的异常吗？**

**AI答：**

1. **`flowOn` 的位置语义**：Flow 的操作符是**洋葱结构**——数据自上而下，回调自下而上。
   - `flowOn` **只影响它上面的（上游）代码**。
   - 数据在 `flowOn` 之下游的部分，不受其线程影响。
2. **为什么能这样实现**：因为 `flowOn` 内部把上游代码包进一个 `ChannelFlowOperatorImpl`，在其中用 `flowScope` + `Channel` 切换线程执行上游；`flowOn` 本身会 `flowOn` 拦截 **context + Job** 两个元素（对 Job 特殊处理以支持结构化并发）。
3. **`catch` 的捕获范围**：`catch` **只能捕获上游异常**，**不能捕获下游（collector）抛出的异常**。

```kotlin
flow {
    emit(1)
    throw RuntimeException("上游")
}.catch { e -> println("捕获: $e") }   // ✅ 能捕获
 .collect { throw RuntimeException("下游") }  // ❌ catch 抓不到，会直接抛给协程
```

4. **原因**：`catch` 的实现是 `catchImpl`，它在**上游**与 `catch` 之间插入了 `try-catch`；下游的异常发生在 `catch` 之外，异常路径是"下游 → 外层作用域"，所以 `catch` 无能为力。
5. **实践建议**：下游异常（渲染失败、断言失败）应该由**最外层的作用域**统一处理（`CoroutineExceptionHandler` 或 `try/catch`），而不是 `catch`。
6. **补充**：`flowOn` 的一个副作用是它会**改变上游的 Job**（因为要支持取消传播），所以 `flowOn` 之后的上游协程是 `flowScope` 的子协程，取消语义仍然正确。

---

**读者问：`flowOn(Dispatchers.IO)` 和 `collect(Dispatchers.IO)` 有什么区别？哪个更好？**

**AI答：**

1. **区别**：
   - `flowOn(Dispatchers.IO)`：**切换上游的执行线程**。收集者仍在原线程（例如主线程），只有上游的网络/DB 操作在 IO 线程。
   - `collect(Dispatchers.IO)`（或 `withContext(Dispatchers.IO) { flow.collect {} }`）：**整个收集过程都在 IO 线程**，包括 `collect` 里的 lambda。
2. **选择原则**：
   - **推荐 `flowOn`**：UI 层的最终 `collect` 应该在主线程（更新 UI），只有数据获取在 IO。
   - `collect(Dispatchers.IO)` 只在**整个流程都不碰 UI**时使用（如在后台预取数据）。
3. **性能差异**：
   - `flowOn` 内部用 **Channel 做线程切换**，每次切换有上下文切换成本，但只切换一次（上游整段）。
   - `collect(Dispatchers.IO)` 切换成本更低（整个协程体在一个线程上），但**UI 更新会跑到 IO 线程**，导致 `CalledFromWrongThreadException`。
4. **面试话术**："**`flowOn` 管上游，`collect` 管整条链**。在 ViewModel 里我一般不指定 `collect` 的线程（由 UI 层在主线程 collect），而用 `flowOn(Dispatchers.IO)` 把数据源挪到后台。这样职责清晰，也避免了 UI 更新跑错线程。"

---

**读者问：`SharedFlow`、`StateFlow`、`Channel` 到底怎么选？为什么不用 LiveData？**

**AI答：**

1. **选型决策树**：

```text
需要表达"现在的状态是什么"？
  ├─ 是 → StateFlow（必然粘性 + 去重 + 原子更新）
  └─ 否 → 需要"每个订阅者都收到一份"？
            ├─ 是 → SharedFlow（广播，可能丢，用 replay 兜底）
            └─ 否 → 需要"保证不丢、只处理一次"？
                      ├─ 是 → Channel（点对点 FIFO）
                      └─ 否 → 冷 Flow（每次 collect 重新拉数据）
```

2. **核心差异（三句话）**：
   - `StateFlow`：**状态**。只有一个最新值，按 `equals` 去重，订阅者读的是**同一份**。
   - `SharedFlow`：**事件广播**。每个订阅者收到一份，可以丢（`replay=0` 且无订阅者时直接丢弃）。
   - `Channel`：**队列**。点对点，一个消息只被一个接收者取走，可缓冲、可挂起、**保证送达**（在缓冲范围内）。
3. **为什么不用 LiveData**：
   - LiveData **不感知 Kotlin 协程**，需要 `asLiveData()` 桥接（且是冷流）。
   - LiveData 的 `setValue` 必须在主线程，而 `MutableStateFlow.update{}` 可以在任意线程原子更新。
   - LiveData 的分发是"每个 Observer 独立走一遍"，而 StateFlow 是"共享一个上游"——**多订阅者时 StateFlow 更省资源**。
   - LiveData 与 Lifecycle 强耦合，不利于在非 Android 环境（纯 JVM 单测）复用。
4. **面试话术**："""我用 `StateFlow` 承载 UI 状态（唯一真相源），用 `Channel` 承载一次性导航事件（保证不丢、只处理一次），用 `SharedFlow` 做全局事件广播。LiveData 我只在存量项目里保留，新代码统一用 Flow。""

---

**读者问：取消是协作的，那一个死循环的协程能停下来吗？`

**AI答：**

1. **不能自动停**。`cancel()` 只是把 `Job` 状态置为 `Cancelling` 并设置 `isActive = false`，**不会打断线程**。协程只有在**挂起点或取消检查点**才会响应。
2. **CPU 密集循环必须手动检查**：

```kotlin
// ✅ 正确：显式检查 isActive
viewModelScope.launch {
    while (isActive) {
        heavyCompute()
        // 或在循环内调用 ensureActive()，它会在已取消时抛 CancellationException
    }
}

// ❌ 错误：死循环不检查，永远不响应取消
viewModelScope.launch {
    while (true) { heavyCompute() }
}
```

3. **CPU 密集任务的正确做法**：
   - 用 `Dispatchers.Default`（不是 IO）。
   - 循环内加 `isActive` / `ensureActive()` 检查。
   - 任务粒度切分，让每个小段之间有机会挂起。
4. **与线程中断（`Thread.interrupt`）的对比**：
   - `Thread.interrupt` 是**抢占式**的：设置中断标志 + 抛 `InterruptedException`；但**不响应中断的代码**（如 `while(true)` 空转）依然停不下来，而且会打断 `Thread.sleep` 等。
   - 协程取消是**协作式**的：更安全、更可预测，但也更依赖程序员自觉。
5. **面试话术**："协程取消是**协作式**的，本质是'在下一个挂起点检查标志位'。所以 CPU 密集循环必须显式 `isActive` 检查，否则协程会变成'不可取消的线程'，拖垮整个作用域。"

---

**读者问：`suspendCancellableCoroutine` 和 `suspendCoroutine` 有什么区别？为什么回调转协程要用前者？**

**AI答：**

1. **区别**：
   - `suspendCoroutine { cont -> ... }`：只提供 `CancellableContinuation` 的**一半能力**，**不支持取消回调**。协程被取消时，阻塞的回调**仍然会继续**（回调回来时 `cont.resume` 会失败或抛异常），可能造成资源泄漏。
   - `suspendCancellableCoroutine { cont -> ... }`：额外提供 `cont.invokeOnCancellation { ... }`，可以在协程取消时**主动终止底层操作**（取消网络请求、注销监听、关流）。
2. **标准模板**：

```kotlin
suspend fun <T> apiCall(block: (Result<T>) -> Unit): T =
    suspendCancellableCoroutine { cont ->
        block { result ->
            if (cont.isActive) {                 // ① 已取消就不处理
                result.fold(
                    onSuccess = { cont.resume(it) },
                    onFailure = { cont.resumeWithException(it) }
                )
            }
        }
        cont.invokeOnCancellation { cancelUnderlying() }   // ② 取消时清理
    }
```

3. **`isActive` 检查为什么必需**：回调可能在协程取消**之后**才到达。此时 `cont.resume()` 会抛出 `IllegalStateException("Already resumed")`，或者更糟——把结果投递到一个已死的协程上。检查 `isActive` 是标准做法。
4. **`resume` 的两种重载**（Kotlin 1.3+ 有 `onCancellation` 参数）：`cont.resume(value) { cause, value, context -> ... }` 可以处理"恢复瞬间协程已被取消"的竞态。
5. **对比 `callbackFlow`**：
   - 单个结果 → `suspendCancellableCoroutine`。
   - 一串结果 → `callbackFlow` + `awaitClose { unregister() }`。

---

**读者问：`runBlocking` 能不能用在 Android 的 Activity 里？**

**AI答：**

1. **结论**：**不能**。`runBlocking` 会阻塞调用它的线程直到协程完成。在 Activity 里调用 `runBlocking { /* 里面有 delay */ }` 会**卡住主线程**，触发 ANR。
2. **为什么它存在**：
   - `fun main()` 入口——JVM 程序没有事件循环，需要一个同步阻塞的入口。
   - 单元测试——`runTest`（kotlinx-coroutines-test）是更好的选择，它用虚拟时间调度，不需要真的等待。
   - 脚本 / CLI 工具。
3. **常见误用**：
   - 在 `onCreate` 里 `runBlocking { repository.load() }` → 主线程卡死。
   - 在 `runBlocking` 里调用 `Dispatchers.Main` → 死锁（主线程被自己阻塞，Main dispatcher 的任务永远排不上）。
4. **Android 上的替代**：
   - Activity/Fragment → `lifecycleScope`。
   - ViewModel → `viewModelScope`。
   - Compose → `rememberCoroutineScope()`。
   - 应用级 → 自建 `CoroutineScope` + 绑定 `Application.onTerminate` 或用 `ProcessLifecycleOwner`。
5. **面试话术**："`runBlocking` 只能出现在 JVM 的 `main()` 入口或者测试里。Android 上任何地方用它都会阻塞主线程；尤其是里面如果引用 `Dispatchers.Main`，会直接死锁。"

---

**读者问：什么是结构化并发？它解决了什么问题？**

**AI答：**

1. **定义**：结构化并发（Structured Concurrency）要求**并发任务的生命周期嵌套在词法作用域里**，遵循三条规则：
   1. 父 Job 取消 → **所有**子 Job 取消（向下传播）。
   2. 父 Job 完成 → 必须**等待所有**子 Job 完成（向上等待）。
   3. 子 Job 失败 → **父 Job 失败**（异常向上传播，除非父是 `SupervisorJob`）。
2. **解决的核心问题——"并发代码的三大孤儿问题"**：
   - **孤儿任务（Orphan）**：任务脱离了作用域，取消不掉（`GlobalScope` 的原罪）。
   - **孤儿异常**：异常没人接，直接崩溃。
   - **孤儿数据**：任务还在跑，作用域已经销毁 → 内存泄漏 / 状态不一致。
3. **在 Android 中的体现**：
   - `viewModelScope` 在 `onCleared()` 取消所有协程 → 没有孤儿任务。
   - `lifecycleScope` 在 `Lifecycle.DESTROYED` 取消。
   - `repeatOnLifecycle(STARTED)` 在停止时取消收集、重新开始时重新收集 → 自动解决"后台还在收数据"的浪费与泄漏。
4. **面试话术**："结构化并发让**并发任务的生老病死**和**作用域的生命周期**绑定：父取消子必取消、子完成父才完成、子失败父必知。这三条规则同时解决了任务泄漏、异常崩溃和生命周期错乱。`GlobalScope` 之所以被废弃，就是因为它破坏了这套规则。"

---

**读者问：`Flow` 的背压（backpressure）机制是怎么实现的？为什么生产者不会 OOM？**

**AI答：**

1. **背压的语义**：当下游消费速度慢于上游生产速度时，压力必须**向上游传导**，让上游减速甚至暂停。
2. **实现机制**：
   - `Flow` 的 `collect` 内部通过 `SafeCollector` + **`Channel`** 传递元素（`flow` 的核心实现就是 `ChannelFlowOperatorImpl`）。
   - `Channel` 的默认容量是 `BUFFERED`（64）或 `RENDEZVOUS`（0），**满了之后 `send` 会挂起** → 这就是背压。
   - 挂起后生产者协程被挂起，**不占用线程**，等下游消费腾出空间再恢复。
3. **为什么不会 OOM**：
   - 元素在 Channel 里**有界**，数量固定；生产者被挂起后不再生产 → 内存占用有上界。
4. **三种策略**：

| 策略 | 行为 | 丢数据 | 适用 |
| --- | --- | --- | --- |
| `buffer(n)` | 缓冲 n 个，满则挂起 | ❌ 不丢 | 需要完整数据流 |
| `conflate()` | 容量 1，丢旧留新 | ✅ 丢中间值 | 只需最新值（如搜索建议） |
| `buffer(CONFLATED)` | 同上 | ✅ | 同上 |

5. **实践建议**：
   - 数据库/网络分页列表 → 用 `buffer` 保持完整性。
   - 搜索建议、行情、位置更新 → 用 `conflate`，避免生产端被拖慢。
6. **面试话术**："背压不是 Kotlin 特有的概念，是 Reactive Streams 的规范。Kotlin Flow 用有界 `Channel` 实现：缓冲满时 `send` 挂起，压力自动传导回上游。要注意 `conflate` 是**有意丢数据**，用之前必须确认业务能接受。"

---

**读者问：`delay` 到底做了什么？为什么 `delay` 能让出线程？**

**AI答：**

1. **`delay` 的实现**：本质是 `suspendCancellableCoroutine` + `ScheduledExecutorService`。
   - 向调度器注册一个延时任务；
   - 挂起当前协程（返回 `COROUTINE_SUSPENDED`）；
   - 延时到期后，调度线程调用 `cont.resume(Unit)` → 通过 `ContinuationInterceptor` 调度回原 Dispatcher 继续执行。
2. **为什么能"让出线程"**：因为挂起时**协程不持有线程**——线程被 `Dispatchers` 的线程池回收，去执行别的任务。挂起的协程状态（现场）保存在 `Continuation` 对象里（通常在堆上），而不是线程栈上。
3. **`delay` 与 `Thread.sleep` 的区别**：

| | `Thread.sleep` | `delay` |
| --- | --- | --- |
| 是否阻塞线程 | ✅ 阻塞 | ❌ 挂起，释放线程 |
| 能否被取消 | ❌ 只能 `interrupt` | ✅ 抛 `CancellationException` |
| 需要在哪调用 | 任意线程 | 仅 `suspend` 函数或协程内 |
| 线程数影响 | 阻塞一条线程 | 不影响 |

4. **在主线程 `delay` 是安全的**：`delay(1000)` 在主线程上不会 ANR，因为它不阻塞消息循环，主线程可以继续处理其他消息。
5. **补充**：`delay` 与 `Handler.postDelayed` 语义类似，但 `delay` 可取消、可组合（`delay` 属于协程取消体系，`Handler` 回调不会自动取消）。

---

**读者问：协程的异常能在 `catch` 块里被捕获吗？为什么 `try-catch` 捕获不了协程里的异常？**

**AI答：**

1. **普通 `try-catch` 能捕获挂起函数抛出的异常**——只要异常发生在**同一个协程**的调用栈里。

```kotlin
scope.launch {
    try {
        doSomething()      // 协程内的普通函数，异常能捕获 ✅
    } catch (e: Exception) { handle(e) }
}
```

2. **捕获不到的三种情况**：
   - **异常发生在另一个协程里**：`launch` 的异常属于那个协程的 Job，外层 `try-catch` 抓不到。
   - **根协程的异常**：`launch` 的异常在协程**结束**时才被处理，`try-catch` 必须包在协程体内部（包在外面无效）。
   - **`async` 的异常**：`async` 把异常存在 `Deferred` 里，必须 `await()` 时才抛。
3. **异常传播路径**：

```text
协程体抛出异常
   ↓
JobSupport 的 childCancelled / handleJobException
   ↓
向上传播给父 Job（cancel 父）
   ↓
若一路到根且未被 catch
   ↓
CoroutineExceptionHandler 或 未捕获异常处理器 → Crash
```

4. **关键规则**：**`CancellationException` 永远不应该被业务 `catch` 吞掉**。这是官方明确要求的：

```kotlin
try { work() }
catch (e: CancellationException) { throw e }   // ✅ 保留取消语义
catch (e: Exception) { handle(e) }              // ✅ 只处理业务异常
```

5. **面试话术**："协程异常分两类：**异常终止（fail）**和**取消（cancel）**。取消是正常的控制流，不是错误。业务代码如果 `catch (e: Exception)` 把 `CancellationException` 一起吃掉，会破坏结构化并发的语义——父协程以为子协程正常结束了。"

## 关联笔记

- [[高阶函数与扩展]]：Flow 的操作符本身就是高阶函数，`inline` 让操作符链零额外开销
- [[DSL]]：DSL 的接收者 lambda 机制与协程的 `CoroutineScope` 接收者同源
- [[../Java/JVM内存模型]]：协程的挂起栈存放在堆上（Continuation），与 JVM 栈模型直接相关
- [[../Java/集合框架]]：`ConcurrentHashMap` 的原子操作是 `MutableStateFlow.update{}` 的底层基础
- [[../README]]：第 1 部分总览
- [[../../07-架构、工具与生态/Jetpack库/StateFlow与SharedFlow]]：StateFlow / SharedFlow 的完整 API 与背压细节
- [[../../07-架构、工具与生态/Jetpack库/LiveData与StateFlow]]：LiveData 与 Flow 的选型对比
- [[../../07-架构、工具与生态/架构模式/MVVM]]：协程在 ViewModel 中的典型用法

## 参考资料

- Kotlin 官方文档 · 协程（https://kotlinlang.org/docs/coroutines-overview.html）
- Kotlin 官方文档 · Coroutines 基础 · 协程与挂起函数（https://kotlinlang.org/docs/coroutines-basics.html）
- Kotlin 官方文档 · Flow 与 Channel（https://kotlinlang.org/docs/flow.html）
- kotlinx.coroutines API 文档（https://kotlinlang.org/api/kotlinx.coroutines/）
- 《Android 高级编程》第 4 版 · 第 11 章"Android 异步任务与线程"、第 12 章"协程"
- 《Kotlin 编程实战》第 2 版 · 协程与 Flow 章节
