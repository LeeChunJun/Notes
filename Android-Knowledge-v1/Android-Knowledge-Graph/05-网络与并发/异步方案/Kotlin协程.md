---
tags: [android]
part: 第5部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[../README]]"
---

# Kotlin协程

## 一句话定义

协程是 Kotlin 提供的**用户态可挂起函数（suspend function）**：它让一段代码在**等待时主动交还线程**，从而用**少量线程承载海量并发任务**。协程不是"轻量的线程"，而是**编译器帮你改写的状态机**——挂起与恢复靠 `Continuation` 对象完成。

## 核心原理

### 1. 为什么协程比线程轻

| 维度 | 线程 | 协程 |
| --- | --- | --- |
| **调度方式** | **操作系统内核**抢占式调度 | **用户态**协作式调度（由 `Dispatcher` 决定） |
| **切换成本** | **上下文切换**（约 1~10 μs，涉及内核态/用户态切换、TLB 刷新） | **函数调用 + 栈帧切换**（约几十 ns） |
| **内存占用** | 栈 ~1MB（默认）/线程 | 一个 `Continuation` 对象 + 几个引用（**几十字节**） |
| **数量级** | 几千个线程就会耗尽内存 | **几十万**个协程没问题 |
| **阻塞代价** | 阻塞线程 = 浪费一个 OS 线程 | 挂起协程 = **不占线程** |
| **是否可序列化** | ❌ 线程不能被传递 | ✅ 协程是**可挂起函数**，能安全跨调用栈传递 |

**"协程比线程轻"的本质**：线程切换需要**保存/恢复操作系统上下文**（寄存器、栈、TLS），而协程切换只是**保存/恢复 Kotlin 的局部变量和调用位置**（编译成状态机）。协程挂起时，**它所在的线程被释放去干别的活**，而不是像线程那样卡在 `sleep` 上。

### 2. 编译原理：suspend 函数被改写成什么

```kotlin
// 源码
suspend fun getUser(id: Long): User {
    val res = api.request(id)     // 挂起点
    return res
}
```

**编译器生成的等价字节码语义**：

```java
// 挂起函数返回一个 Object（成功时是结果，失败时是 COROUTINE_SUSPENDED）
Object getUser(long id, Continuation<? super User> $completion) {
    switch ($completion.label) {
        case 0:
            Result<User> res = api.request(id, $completion);   // 内部可能挂起
            if (res == COROUTINE_SUSPENDED) return COROUTINE_SUSPENDED;
            // 被恢复时（label != 0）从上次挂起的地方继续
            return res;
        case 1:
            // 恢复点：继续执行
            ...
    }
}
```

**三个关键结论**：

1. **挂起函数返回的 `Object` 可能是 `COROUTINE_SUSPENDED`**，表示"我还没算完，先歇着"。**调用方不能直接当结果用**。
2. **状态机靠 `label`（状态编号）实现**：每个挂起点对应一个 label，恢复时从 label 跳回去继续。**这就是协程能"记住"局部变量的原因** —— 局部变量被编译器保存成状态机对象里的字段。
3. **`Continuation` 就是"协程的身份 + 恢复入口"**。恢复时调用 `continuation.resumeWith(result)`。

```text
协程 A（运行在 IO 线程）
  │  遇到挂起函数 networkCall()
  ├─ 挂起：保存状态（label=1, 局部变量入状态机对象）
  ├─ 把 Continuation 交给被调用的库（库在未来某个时刻 resume 它）
  └─ 返回 COROUTINE_SUSPENDED，**释放当前线程**

  ... IO 线程去执行别的协程 ...

  │  网络响应到了
  └─ continuation.resumeWith(Result.success(data))
       ├─ 调度器把协程 A 派发回某个线程
       └─ 从 label=1 处继续执行，拿到 data
```

### 3. 协程三件套：Scope、Job、Dispatcher

```text
CoroutineScope（作用域）  ← 管"活多久"
   │
   └── CoroutineContext（上下文）  ← 管"在哪跑、叫什么名字、异常给谁"
          ├── Dispatcher        ← 线程调度
          ├── Job               ← 生命周期 / 取消 / 父子关系
          ├── CoroutineName     ← 调试用
          └── CoroutineExceptionHandler ← 未捕获异常处理
```

| 概念 | 作用 |
| --- | --- |
| **`CoroutineScope`** | 协程的"容器"，持有 `Job` + `Context`。`scope.launch{}` 创建的协程是这个 scope 的**子协程** |
| **`Job`** | 协程的**生命周期句柄**，`cancel()` 取消，`join()` 等待完成，`isActive` 查询状态 |
| **`Deferred`** | `Job` 的子类，`async` 返回，有 `await()` 取结果 |
| **`Dispatcher`** | 决定协程在哪个线程 / 哪个线程池执行 |
| **`CoroutineContext`** | 协程的执行环境，元素以 `key + value` 形式组合，**`+` 是添加，`+` 相同 key 时后者覆盖前者** |

### 4. 结构化并发（Structured Concurrency）

**这是协程最重要的设计**（面试核心）。

**三个保证**：

1. **父子关系**：`launch` 创建的协程是当前作用域的子协程；子协程的 `Job` 是父 `Job` 的子节点。
2. **取消传播**：父协程取消 → **所有子协程、孙子协程递归取消**。
3. **异常传播**：子协程未捕获的异常 → **向上传播给父协程** → 父协程取消 → **兄弟协程也会被取消**（这是"协程Scope会因一个失败而全部失败"的原因）。

```text
父协程 (Job A)
  ├── 子协程 (Job B)
  │      └── 孙协程 (Job C)      ← C 失败 → B 取消 → A 取消
  └── 子协程 (Job D)             ← B 失败 → D 也被取消
```

**"结构化"的含义**：协程的执行有**明确的父子层级和明确的边界**，像"块结构"一样有始有终。

**`coroutineScope {}` vs `supervisorScope {}` vs `launch {}`**：

| API | 语义 | 子协程失败时 |
| --- | --- | --- |
| **`coroutineScope { }`** | 创建一个子作用域，**等待所有子协程完成** | 第一个失败的子协程会**取消兄弟和父作用域**，异常抛给调用者 |
| **`supervisorScope { }`** | 子协程**相互独立** | 一个失败**只取消自己**，不影响兄弟和父；异常需自己处理（否则交给 `CoroutineExceptionHandler`） |
| **`launch { }`** | 在当前 scope 里启动一个**不等待**的子协程 | 异常不会抛给调用者（会走 `CoroutineExceptionHandler` / 崩溃） |
| **`async { }`** | 同 `launch`，但返回 `Deferred` 有 `await()` | 异常在 `await()` 时抛出（但**仍在 await 时取消父作用域**） |
| **`GlobalScope.launch { }`** | **不结构化**，无父 Job | ⚠️ **不要用**：无法取消，泄漏风险 |

**`SupervisorJob`**：创建一个**没有父 Job**的独立 Job，其子协程相互独立。适合"多个独立任务，只要一个成功即可"（如多个数据源并行拉取）。

**异常传播的关键差异（面试高频）**：

| 场景 | `launch` | `async` |
| --- | --- | --- |
| 有 `try/catch` 在**协程内部** | ✅ 能捕获 | ✅ 能捕获 |
| `try/catch` 在**协程外部**（包住 `launch` 的那一行） | ❌ **捕获不到** | ✅ **能捕获**（在 `await()` 处抛出） |
| 无任何处理 | 走 `CoroutineExceptionHandler`，**未注册则崩溃** | 异常**存在 Deferred 里**，直到 `await()` 才抛出 → **不崩溃但会"静默丢失"** |

> 关键：**`launch` 的异常是"未捕获异常"，会走线程的 `UncaughtExceptionHandler` 导致崩溃；`async` 的异常是"延迟抛出的异常"，存在 `Deferred` 里。** 这解释了为什么 `async` 里忘了 `await()` 会导致异常被吞掉。

### 5. Dispatcher（调度器）

| Dispatcher | 底层 | 线程数 | 用途 |
| --- | --- | --- | --- |
| **`Dispatchers.Main`** | Android 主线程（`Handler` 实现） | 1 | **UI 更新** |
| **`Dispatchers.IO`** | 弹性线程池 | **默认上限 64** | **网络、磁盘 IO** |
| **`Dispatchers.Default`** | 固定线程池 | **= CPU 核数** | **CPU 密集计算** |
| **`Dispatchers.Unconfined`** | 不指定 | — | **基本不用**：在当前线程执行直到第一个挂起点，之后由恢复它的线程继续 |
| **`Dispatchers.Main.immediate`** | 主线程 | 1 | **在主线程时立即执行，不排队**（避免不必要的延迟） |
| **`Dispatchers.IO.limitedParallelism(n)`** | IO 池 | n | 限制并发数 |

**`Dispatchers.IO` 的特殊设计（面试加分点）**：它的线程数上限是 **64**（`max(64, 核数)`），**但它和 `Default` 共享同一个线程池** —— 也就是说，**IO 线程执行完 IO 后会去做 CPU 任务**。这是 `kotlinx-coroutines` 的一个优化：**IO 任务通常是"等网络"而不是"占 CPU"**，所以 IO 线程可以有 64 个（等待成本低），CPU 任务只需要核数个。

**协程的"挂起不占线程"体现在调度器上**：

```text
Dispatchers.IO (64 线程)
  ├─ 协程 A: 发 HTTP 请求 → 挂起 → 释放线程
  ├─ 协程 B: 读文件      → 挂起 → 释放线程
  ├─ 协程 C: 解析 JSON   → 执行 0.3ms → 结束
  └─ ...
  64 个线程可以承载几万个并发 IO 协程
```

### 6. 协程的启动方式

```kotlin
// ① launch：启动，不关心结果
val job = scope.launch {
    val data = api.getUser(1)      // suspend 调用
    Log.d("TAG", data.name)
}
// job.cancel() / job.join()

// ② async：启动，需要结果
val deferred = scope.async { api.getUser(1) }
val user = deferred.await()          // ★ 挂起等待结果

// ③ withContext：切换上下文执行，**返回结果**（最常用）
val user = withContext(Dispatchers.IO) { api.getUser(1) }

// ④ runBlocking：阻塞当前线程直到完成（★ 只在测试/main 用）
fun main() = runBlocking {
    val user = api.getUser(1)
    println(user.name)
}

// ⑤ produce：产出流
val flow: Flow<Int> = flow { emit(1) }
```

**`launch` vs `async` 的本质区别**：

- `launch` 返回 `Job`（只有生命周期，没有结果）。
- `async` 返回 `Deferred<T>`（是 `Job` 的子类，额外有 `await()`）。
- **`async` 必须 `await()`**，否则异常被吞 + 协程可能在未完成时就被作用域结束。

### 7. 取消机制

**取消是协作式的（cooperative）**：协程**不会被打断**，而是**在挂起点检查取消状态**并抛出 `CancellationException`。

```text
Job.cancel()
   ↓
把 Job 的状态置为 Cancelling，标记为"取消中"
   ↓
所有挂起在该 Job 上的 Continuation 被 resumeWith(CancellationException)
   ↓
协程在下一个挂起点恢复时抛出 CancellationException
```

**关键点**：

1. **取消只在挂起点生效**。如果协程在做纯 CPU 计算（没有挂起点），**取消不会立即生效**，会一直算到结束。所以耗时的 CPU 任务要**主动检查**：
   ```kotlin
   for (i in 0..1_000_000) {
       ensureActive()          // ★ 检查取消状态，抛 CancellationException
       // 或者 currentCoroutineContext().ensureActive()
       compute(i)
   }
   ```
2. **`CancellationException` 是 `IllegalStateException` 的兄弟，不是 `Exception` 的"普通错误"**：
   - `CancellationException` 继承自 `IllegalStateException`（Kotlin 中）→ 它是 `Exception` 的子类。
   - **⚠️ 绝不能吞掉它**：`catch (e: Exception)` 会捕获它，然后流程继续往下走 → **协程"取消后还在执行"** → 状态错乱。
   - **正确写法**：
     ```kotlin
     try {
         doSomething()
     } catch (e: CancellationException) {
         throw e        // ★ 重新抛出
     } catch (e: Exception) {
         handleError(e)
     }
     ```
3. **`withContext(NonCancellable)`**：在协程被取消时，仍然执行**必须完成**的清理逻辑（关闭文件、提交结果、释放锁）：
   ```kotlin
   try {
       val data = download()
   } finally {
       withContext(NonCancellable) {
           file.delete()      // ★ 即使被取消也要清理
       }
   }
   ```
4. **`isActive` 检查**：
   ```kotlin
   while (currentCoroutineContext().isActive) { ... }
   ```
5. **超时**：`withTimeout(1000) { ... }` —— 超过时间会**取消该作用域内的协程**并抛 `TimeoutCancellationException`（它是 `CancellationException` 的子类）。

**取消传播的方向**：

```text
父协程 cancel()
   ↓ 向下传播
子协程被取消

子协程抛异常
   ↓ 向上传播（launch，非 supervisor）
父协程被取消 → 父的父也被取消 → ...
```

### 8. 异常处理

```kotlin
// ① 最基础：try/catch
try {
    val data = api.getUser(1)
} catch (e: IOException) {
    showError(e)
} catch (e: HttpException) {
    showError(e)
}

// ② CoroutineExceptionHandler：兜底未捕获异常
val handler = CoroutineExceptionHandler { _, throwable ->
    CrashReporter.report(throwable)
}

scope.launch(handler) {
    api.getUser(1)      // 未捕获异常 → handler
}

// ③ SupervisorJob：子协程互不影响
scope.launch(SupervisorJob()) {
    launch { mayFail() }   // 失败只取消自己
    launch { alsoMayFail() }
}

// ④ supervisorScope：块级别隔离
supervisorScope {
    launch { mayFail() }
    launch { alsoMayFail() }
}

// ⑤ 重试
retryWhen { cause, attempt ->
    if (cause is IOException && attempt < 3) delay(1000L * attempt) else false
}
```

**异常处理的三个层次（面试常问）**：

| 层次 | 机制 | 捕获什么 |
| --- | --- | --- |
| **协程内部** | `try/catch` | 当前协程块内抛出的异常 |
| **作用域级别** | `SupervisorJob` / `supervisorScope` | 隔离子协程的失败 |
| **全局级别** | `CoroutineExceptionHandler` | 未被 `try/catch` 和 supervisor 处理的异常 |

### 9. Flow

**Flow 的定义**：**冷流**（cold stream）——**每次 collect 都会重新执行上游的构建代码**（与 `Observable.fromCallable` 同理）。

```kotlin
// 基础 Flow
fun getUsers(): Flow<User> = flow {
    val users = api.loadUsers()      // ★ 每次 collect 都会执行
    users.forEach { emit(it) }      // emit 会挂起当前协程
}

// 构建
fun numbers(): Flow<Int> = flow { emit(1); emit(2); emit(3) }
fun fromList(list: List<Int>) = list.asFlow()
fun fromChannel() = channelFlow { send(1); send(2) }
```

**常用操作符**：

| 操作符 | 作用 |
| --- | --- |
| `map` | 一对一变换 |
| `filter` | 过滤 |
| `transform` | 通用变换（可以发多个） |
| `flatMapConcat` / `flatMapMerge` / `flatMapLatest` | 对应 Rx 的 `concatMap`/`mergeMap`/`switchMap` |
| `flowOn` | **切换上游的上下文**（对应 `subscribeOn`） |
| `onEach` | 对每个元素做副作用（不改变流） |
| `catch` | 捕获上游异常 |
| `retry` / `retryWhen` | 重试 |
| `collect` | 消费（终止操作符） |
| `collectLatest` | 消费时取消前一个（相当于 `switchMap`） |
| `takeWhile` / `take` / `drop` | 截取 |
| `debounce` / `sample` | 防抖 / 采样 |
| `stateIn` / `shareIn` | 转成 `StateFlow` / `SharedFlow`（**热流**） |
| `combine` / `zip` | 组合 |

**`flowOn` 与 `collect` 的线程关系（重要）**：

```text
上游（flowOn 之前）      → 在 flowOn 指定的 dispatcher 执行
下游（flowOn 之后）      → 在 collect 所在协程的 dispatcher 执行
```

```kotlin
flow {
    emit(heavyWork())       // 在 IO 线程（flowOn(IO)）
}.map { transform(it) }      // 也在 IO 线程
 .flowOn(Dispatchers.IO)     // ★ 上游全部切到 IO
 .collect { render(it) }    // 在主线程（collect 所在的上下文）
```

**`flowOn` 的规则**：
- **多个 `flowOn` 不互相干扰**（不像 Rx 的 `subscribeOn`）——每个 `flowOn` 只影响它**上面**的部分。
- **`flowOn` 只能向上游传播，不能向下游**。要切换下游，用 `withContext`。

**三种 Flow 构建方式**：

| 方式 | 适用场景 | 特点 |
| --- | --- | --- |
| `flow { }` | 普通顺序发射 | **禁止在内部 `withContext`**（会破坏上下文不变量，抛异常） |
| `channelFlow { }` / `callbackFlow { }` | **并发**发射、来自回调 | 每个 `send` 有自己的协程上下文，可并发 |
| `produce { }` | 生产者-消费者 | 底层用 `Channel` |

```kotlin
// callbackFlow：把回调 API 转成 Flow（自动管理 awaitClose）
fun callbackFlowApi(): Flow<String> = callbackFlow {
    val listener = object : Listener {
        override fun onEvent(e: String) { trySend(e) }   // 线程安全
    }
    register(listener)
    awaitClose { unregister(listener) }     // ★ 必须 awaitClose，否则泄漏
}
```

**冷流 vs 热流**：

| 维度 | `Flow`（冷） | `StateFlow` / `SharedFlow`（热） |
| --- | --- | --- |
| 每次 collect | **重新执行上游** | **共享同一个上游** |
| 适合 | 一次性数据流（网络请求、DB 查询） | 持续的状态（UI 状态、事件总线） |
| 转换 | — | `stateIn(scope, SharingStarted.WhileSubscribed(5000), initial)` |

**`stateIn` vs `shareIn`**：

| 维度 | `stateIn` | `shareIn` |
| --- | --- | --- |
| 返回类型 | `StateFlow<T>` | `SharedFlow<T>` |
| 初始值 | **必需** | 无 |
| 重放（replay） | 固定为 1 | **可配置** |
| 去重 | **相同值会被去重**（`equals`） | 不去重 |

### 10. 与线程、RxJava 的对比

| 维度 | 线程 | RxJava | 协程 |
| --- | --- | --- | --- |
| **调度方式** | OS 抢占 | `Scheduler`（用户态） | `Dispatcher`（用户态） |
| **任务单元** | `Runnable` | `Observable` | `suspend fun` |
| **结果返回** | 无（要 `Callable`） | `Single` / `Observable<T>` | **直接返回值** |
| **取消** | `Thread.interrupt()`（不可靠） | `Disposable.dispose()` | **`Job.cancel()` + 协作式检查**（可靠） |
| **线程切换** | `new Thread{}` / 线程池 | `subscribeOn`/`observeOn` | **`withContext`** |
| **生命周期** | 无概念 | 需手动 dispose | **结构化并发，自动绑定作用域** |
| **异常** | `try/catch` | `onError`（不处理会崩） | `try/catch` + `CoroutineExceptionHandler` |
| **组合** | 需手写 | 丰富但复杂 | `Flow` 操作符 + 结构清晰 |
| **背压** | 无 | `Flowable` | **Flow 原生**（`collectLatest` 等） |
| **学习成本** | 低 | 高 | 中 |

**Android 上的线程选择建议**：

```text
CPU 密集（图像处理、加密、压缩）
   → Dispatchers.Default（核数个线程）

IO 密集（网络、磁盘、数据库）
   → Dispatchers.IO（最多 64 线程）

UI 更新
   → Dispatchers.Main.immediate（已在主线程则立即执行，不排队）

需要顺序串行
   → Dispatchers.Default.limitedParallelism(1) 或 Mutex

需要保证执行（进程被杀也要执行）
   → WorkManager（不是协程能解决的）
```

## 代码示例

### 1. 协程基础：launch / async / withContext

```kotlin
class UserRepository(private val api: ApiService) {

    /** launch：启动即忘，不关心结果 */
    fun preload(userId: Long, scope: CoroutineScope) {
        scope.launch {
            val user = api.getUser(userId)          // suspend 调用，自动切到 IO
            Log.d("TAG", "loaded ${user.name}")
        }
    }

    /** async：需要结果，用 await() 拿 */
    suspend fun loadUserAsync(userId: Long): User {
        val deferred = CoroutineScope(Dispatchers.IO).async { api.getUser(userId) }
        return deferred.await()
    }

    /** withContext：切换上下文并返回结果（最常用） */
    suspend fun loadUser(userId: Long): User =
        withContext(Dispatchers.IO) { api.getUser(userId) }

    /** 并发执行多个，用 awaitAll 汇总 */
    suspend fun loadHome(): HomeData = coroutineScope {
        val user = async(Dispatchers.IO) { api.getUser(1) }
        val orders = async(Dispatchers.IO) { api.getOrders() }
        val messages = async(Dispatchers.IO) { api.getMessages() }
        HomeData(user.await(), orders.await(), messages.await())
    }
}
```

### 2. 结构化并发：父子关系与取消传播

```kotlin
class SyncViewModel(private val repo: Repository) : ViewModel() {

    /** viewModelScope 在 ViewModel 销毁时自动取消，父协程取消会传播到所有子协程 */
    fun sync() {
        viewModelScope.launch {
            try {
                // 三个子协程并发执行
                val userJob = launch(Dispatchers.IO) { repo.syncUser() }
                val orderJob = launch(Dispatchers.IO) { repo.syncOrders() }
                val msgJob = launch(Dispatchers.IO) { repo.syncMessages() }

                // 等待所有子协程完成
                joinAll(userJob, orderJob, msgJob)
                _uiState.value = UiState.Success
            } catch (e: CancellationException) {
                throw e        // ★ 重新抛出，保持取消语义
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message)
            }
        }
    }
}
```

### 3. 异常处理：三个层次

```kotlin
// ① 协程内部 try/catch
scope.launch {
    try {
        val data = api.getUser(1)
    } catch (e: IOException) {
        showError("网络异常")
    } catch (e: HttpException) {
        showError("HTTP ${e.code()}")
    }
}

// ② CoroutineExceptionHandler：兜底
val handler = CoroutineExceptionHandler { _, throwable ->
    CrashReporter.report(throwable)
    showError("未知错误")
}
scope.launch(handler) { api.getUser(1) }

// ③ SupervisorJob：子协程独立，一个失败不影响其他
scope.launch(SupervisorJob()) {
    launch { api.getUser(1) }        // 失败只取消自己
    launch { api.getOrders() }       // 继续执行
}

// ④ supervisorScope：块级隔离
supervisorScope {
    launch { mayFail() }
    launch { alsoMayFail() }
}
```

### 4. 超时与取消

```kotlin
// ① withTimeout：超时抛 TimeoutCancellationException
suspend fun fetchWithTimeout(): User =
    withTimeout(5_000) { api.getUser(1) }

// ② orTimeout（返回 null 而不是抛异常）
suspend fun fetchOrNull(): User? =
    withTimeoutOrNull(5_000) { api.getUser(1) }

// ③ 协程内主动检查取消（CPU 密集任务必须做）
suspend fun heavyComputation(): Result {
    while (currentCoroutineContext().isActive) {
        ensureActive()      // ★ 抛 CancellationException
        doHeavyWork()
    }
    return Result
}

// ④ NonCancellable：确保清理逻辑执行
suspend fun downloadWithCleanup(url: String, dest: File) {
    try {
        val body = api.download(url)
        dest.outputStream().use { out -> body.source().copyTo(out) }
    } finally {
        withContext(NonCancellable) {
            dest.delete()   // ★ 即使被取消也要清理
        }
    }
}

// ⑤ 手动取消
val job = scope.launch { doLongWork() }
job.cancel()
job.join()      // 等待取消完成
```

### 5. Flow 的完整用法

```kotlin
/** 冷流：每次 collect 都重新请求 */
fun observeUsers(): Flow<User> = flow {
    val users = api.getUsers()          // suspend 调用
    users.forEach { emit(it) }
}.flowOn(Dispatchers.IO)                 // 上游在 IO 线程

/** 转换 + 副作用 */
val uiUsers: Flow<UserUi> = observeUsers()
    .map { it.toUiModel() }              // 变换
    .onEach { analytics.track(it) }      // 副作用
    .filter { it.isVisible }
    .catch { e -> emitAll(errorFlow(e)) } // 异常处理
    .flowOn(Dispatchers.IO)

/** 转成热流供多个收集者共享（避免重复请求） */
class UserViewModel(private val repo: Repository) : ViewModel() {

    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users: StateFlow<List<User>> = _users.asStateFlow()

    // 或用 stateIn
    val usersFromRepo: StateFlow<List<User>> = repo.observeUsers()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),   // 5 秒无订阅则停止上游
            initialValue = emptyList()
        )

    fun refresh() {
        viewModelScope.launch {
            _users.value = repo.loadUsers()
        }
    }
}
```

### 6. callbackFlow：把回调 API 转成 Flow

```kotlin
/** 把 OkHttp 的回调转成 Flow */
fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }      // ★ 协程取消 → 取消 HTTP 请求
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}

/** 用 callbackFlow 包装原生回调 API */
fun locationUpdates(): Flow<Location> = callbackFlow {
    val listener = LocationListener { location -> trySend(location) }
    locationClient.addLocationListener(listener)
    awaitClose { locationClient.removeLocationListener(listener) }   // ★ 必须
}
```

### 7. ViewModel 中的标准写法

```kotlin
class UserListViewModel(
    private val repository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserListUiState())
    val uiState: StateFlow<UserListUiState> = _uiState.asStateFlow()

    // 一次性事件用 Channel
    private val _events = Channel<UserListEvent>(Channel.BUFFERED)
    val events: Flow<UserListEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null

    fun refresh() {
        // 取消上一次请求（switchMap 语义）
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val list = repository.getUsers()          // Retrofit suspend
                _uiState.update { it.copy(loading = false, users = list) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun onEvent(event: UserListEvent) {
        viewModelScope.launch {
            _events.send(event)
        }
    }
}
```

### 8. Compose 中的收集

```kotlin
@Composable
fun UserListRoute(viewModel: UserListViewModel = hiltViewModel()) {
    // 生命周期感知：只有 STARTED 时才收集，停止时自动取消
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(Modifier.fillMaxSize()) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            state.error != null -> Text("出错了：${state.error}")
            else -> LazyColumn {
                items(state.users) { user ->
                    Text(user.name, modifier = Modifier.clickable { viewModel.onEvent(UserListEvent.Click(user)) })
                }
            }
        }
    }
}
```

### 9. 自定义调度器与并发限制

```kotlin
/** 限制并发数为 3（模拟"最多 3 个并行上传"） */
val uploadDispatcher = Dispatchers.IO.limitedParallelism(3)

suspend fun uploadAll(files: List<File>) = coroutineScope {
    files.map { file ->
        async(uploadDispatcher) { upload(file) }   // 最多 3 个并发
    }.awaitAll()
}

/** 用 Mutex 保护临界区（协程版的 synchronized） */
private val mutex = Mutex()
private var counter = 0

suspend fun increment() {
    mutex.withLock {
        counter++
    }
}
```

### 10. 与 RxJava 互转

```kotlin
// 依赖
// implementation "org.jetbrains.kotlinx:kotlinx-coroutines-rx3:1.8.1"

// Flow → Observable
val observable: Observable<Int> = flow.asObservable()

// Observable → Flow
val flow: Flow<Int> = observable.asFlow()

// Flowable → Flow
val flow2: Flow<Int> = flowable.asFlow()

// 在协程里消费 Observable
val result = rxObservable
    .subscribeOn(Schedulers.io())
    .asFlow()
    .first()
```

## 常见问题 / 面试要点

**读者问：协程为什么比线程轻？**

**AI答：**

1. **调度层级不同**：
   - **线程**：由**操作系统内核**调度。切换需要**保存/恢复内核上下文**（寄存器、栈指针、TLB），成本 **1~10 μs**，且涉及用户态↔内核态切换。
   - **协程**：由**用户态的 `Dispatcher`** 调度。切换只是**保存/恢复 Kotlin 局部变量和程序计数器**，成本 **几十 ns**。
2. **内存占用**：
   - 线程：默认栈 **1 MB**（Android 上可调），**几千个线程就耗尽内存**。
   - 协程：一个 `Continuation` 对象 + 若干引用（**几十字节**），**几十万协程没问题**。
3. **挂起 vs 阻塞**：
   - 线程阻塞（`Thread.sleep`、socket read）→ **占着一个 OS 线程干不了别的**。
   - 协程挂起 → **交还线程**，调度器立刻用这个线程跑别的协程。**少量线程承载海量并发**。
4. **实现本质（关键）**：
   - `suspend` 函数被编译器**改写成状态机**（每个挂起点一个 `label`），挂起时返回 `COROUTINE_SUSPENDED`。
   - 恢复靠 **`Continuation.resumeWith`** —— 这是一个**普通的函数调用**，不涉及内核。
   - 所以协程的"切换"本质是**函数调用 + 栈帧切换**。
5. **⚠️ 澄清一个常见误解**：
   - 协程**不是"轻量的线程"**，它是**可挂起的函数**。
   - **协程仍然是"一段代码"，不是"一个执行流"**。多个协程可以**轮流**跑在**同一个线程**上。
   - `Dispatchers.Main` 上的协程和 UI 代码**跑在同一个线程**，靠**协作式让出**（挂起点）来避免阻塞。
6. **面试话术**：**"协程轻是因为它不涉及内核调度，切换只是状态机恢复，成本是纳秒级；而线程切换要保存内核上下文，微秒级。另外协程挂起会释放线程，所以少量线程能承载海量并发。根本原因是 suspend 函数被编译成状态机 + Continuation。"**

---

**读者问：协程的取消是怎么实现的？为什么说是协作式的？**

**AI答：**

1. **取消的传播路径**：
   ```
   job.cancel()
      ↓
   Job 状态 → Cancelling（标记"正在取消"）
      ↓
   把挂起在这个 Job 上的所有 Continuation
   用 CancellationException 恢复
      ↓
   协程在下一个挂起点恢复时抛出 CancellationException
      ↓
   try/finally 中的 finally 块执行（清理逻辑）
      ↓
   Job 状态 → Cancelled
   ```
2. **为什么是"协作式"**：
   - 协程**不会被打断**。`cancel()` 只是**设置一个标志 + 恢复挂起的协程让它有机会响应**。
   - **只有在挂起点（suspension point）才会检查取消状态**。
   - 如果协程在做**纯 CPU 计算**（没有挂起点），**取消不会立即生效**，它会一直算完。→ **耗时 CPU 任务必须主动 `ensureActive()` 检查**。
3. **取消传播的方向**：
   - **向下**：父协程取消 → 所有子协程递归取消。
   - **向上**：子协程抛出**非 `CancellationException`** 的异常 → 取消父协程（`supervisorScope` 除外）。
4. **`CancellationException` 的特殊地位**（重要）：
   - 它继承自 `IllegalStateException` → 是 `Exception` 的子类。
   - **⚠️ `catch (e: Exception)` 会把它捕获住**。如果吞掉它，协程会在"已取消"的状态下继续执行后面的代码 → **状态错乱、UI 更新到已销毁的页面**。
   - **正确写法**：
     ```kotlin
     try {
         doSomething()
     } catch (e: CancellationException) {
         throw e          // ★ 必须重新抛出
     } catch (e: Exception) {
         handleError(e)
     }
     ```
   - 或者用 `try/finally` 做清理（`finally` 在取消时也会执行）。
5. **`NonCancellable`**：在协程已被取消的上下文里，仍然执行必须完成的清理：
   ```kotlin
   finally {
       withContext(NonCancellable) { file.delete() }
   }
   ```
6. **面试话术**：**"取消是协作式的：cancel() 只是标记状态并恢复挂起的协程，协程在下一个挂起点抛 CancellationException。纯 CPU 任务没有挂起点就不会响应，必须主动 ensureActive 检查。而且 CancellationException 不能被 catch(Exception) 吞掉，否则取消语义就断了。"**

---

**读者问：结构化并发是什么？带来了什么好处？**

**AI答：**

1. **定义**：协程的执行有**明确的父子层级和明确的边界**。`launch`/`async` 创建的协程**总是**是当前作用域的子协程，其 `Job` 是父 `Job` 的子节点。
2. **三个核心保证**：
   - **① 生命周期绑定**：父作用域结束 → 所有子协程结束。
   - **② 取消传播**：父取消 → 子递归取消；子失败（launch）→ 父取消。
   - **③ 异常传播**：子协程的未捕获异常不会"消失"，会一路上抛，最终让整个作用域失败。
3. **对比线程**：
   - 线程是"**平铺**"的：`new Thread{}.start()` 之后就和创建者**再无关系**，父线程结束不影响子线程（这就是线程泄漏的根源）。
   - 协程是"**嵌套**"的：作用域 `{}` 就像代码块，出了块，里面的协程全部结束。
   ```kotlin
   // 线程：父结束了子还在跑（泄漏）
   fun startThread() { Thread { while(true) {} }.start() }

   // 协程：出了作用域就结束
   suspend fun scoped() = coroutineScope {
       launch { while(true) { delay(100) } }   // 协程Scope 结束时自动取消
   }
   ```
4. **好处**：
   - **杜绝泄漏**：不需要手动 `cancel()`，作用域结束自动清理。
   - **错误不会静默丢失**：`launch` 的异常会导致整个作用域失败（而不是像裸线程那样只打到 `UncaughtExceptionHandler`）。
   - **可组合**：`coroutineScope { }` 保证返回时所有子协程已完成 → **不会出现"返回了但子任务还在跑"的竞态**。
5. **`SupervisorJob` / `supervisorScope` 是"例外"**：让子协程相互独立，一个失败不影响其他。用于"多个独立任务，只要部分成功"。
6. **⚠️ `GlobalScope` 违背结构化并发**：没有父 Job，**无法被取消**、异常无法传播 → **生产代码禁止使用**。
7. **面试话术**：**"结构化并发让协程像代码块一样有始有终：父子 Job 层级清晰，取消和异常沿层级传播，作用域结束自动清理。这杜绝了线程式的手动管理泄漏。GlobalScope 违背这个原则，应该禁止使用。"**

---

**读者问：协程的异常处理和线程有什么不同？`launch` 和 `async` 的异常行为有什么区别？**

**AI答：**

1. **线程的异常**：
   - 未捕获异常 → 交给线程的 `UncaughtExceptionHandler` → **默认崩溃整个进程**。
   - `try/catch` 只能捕获**当前线程栈上**抛出的异常。
2. **协程的异常（三个层次）**：
   - **协程内部 `try/catch`**：捕获当前协程块内抛出的异常。
   - **`CoroutineExceptionHandler`**：捕获**未处理**的异常（必须作为 `launch` 的第一个参数传入，**只对根协程生效**）。
   - **`SupervisorJob` / `supervisorScope`**：隔离子协程，一个失败不影响兄弟。
3. **`launch` vs `async` 的核心差异（面试重点）**：
   | 维度 | `launch` | `async` |
   | --- | --- | --- |
   | 返回 | `Job` | `Deferred<T>` |
   | 异常存储 | **不存储**，未捕获时走 `CoroutineExceptionHandler`，**没注册就崩溃** | **存在 Deferred 里**，直到 `await()` 才抛出 |
   | 外部 try/catch | ❌ **捕获不到** | ✅ **能捕获**（在 `await()` 处） |
   | 忘了 await | 无所谓 | ⚠️ **异常被吞** + 协程可能在未完成时随作用域结束 |
4. **重要推论**：
   - `async` 的异常是**"延迟抛出"** → 用 `await()` 才暴露。**在 `coroutineScope` 里 `await()` 抛出的异常会取消整个作用域**。
   - `launch` 的异常是**"未捕获异常"** → **不处理就会让 App 崩溃**。所以 `launch` 里**要么内部 try/catch，要么根协程配 `CoroutineExceptionHandler`**。
5. **`try/catch` 放在协程外 vs 内**：
   ```kotlin
   // ❌ 捕获不到 launch 的异常
   try { scope.launch { throw RuntimeException() } } catch (e: Exception) { }

   // ✅ 异常在协程内部被捕获
   scope.launch { try { ... } catch (e: Exception) { } }

   // ✅ async 的异常可以在外层 catch
   try { scope.async { throw RuntimeException() }.await() } catch (e: Exception) { }
   ```
6. **`CancellationException` 的特殊处理**：它**必须重新抛出**，不能被 `catch (Exception)` 吞掉（否则协程在取消后继续执行）。
7. **面试话术**：**"launch 的异常是未捕获异常，会走 CoroutineExceptionHandler，没注册就崩溃，而且外层 try/catch 捕获不到；async 的异常存在 Deferred 里，await 时才抛出，能被外层 catch，但忘了 await 就被吞了。另外 CancellationException 必须重新抛出，不能被 catch(Exception) 吞掉。"**

---

**读者问：协程的线程切换是怎么实现的？`withContext` 和 `Dispatchers` 是什么关系？**

**AI答：**

1. **`withContext` 的语义**：**切换到指定上下文执行代码块，块结束后再切回来，并返回块的结果**。
   ```kotlin
   val user = withContext(Dispatchers.IO) { api.getUser(1) }
   ```
   - 相当于"**在这个线程上跑这段代码，然后回来**"。
   - **不是阻塞**——如果块内有挂起点，协程会挂起并**释放当前线程**；块执行完（可能是另一个线程）后再恢复到原上下文。
2. **切换的机制**：
   - `withContext` 会创建一个**新的协程**（子协程）来执行块，`Dispatchers.IO` 的调度器决定它在哪个线程池执行。
   - 执行完毕后，父协程被 `resume` 回原来的上下文继续。
   - **如果块内没有挂起点**，`withContext` 可能会在**当前线程直接执行**（优化），不真正切线程 —— 所以 `withContext(Dispatchers.IO) { 纯计算 }` 仍会跑在原线程上。
3. **常见误用**：
   ```kotlin
   // ❌ 没有意义：withContext 只是换线程，不改变什么
   withContext(Dispatchers.IO) { computePure() }

   // ✅ 应该用 Dispatchers.Default（CPU 密集）
   withContext(Dispatchers.Default) { computePure() }
   ```
4. **Dispatcher 与线程池**：
   - `Dispatchers.Main` → Android 主线程（用 `Handler` 实现）。
   - `Dispatchers.IO` → **弹性池，上限 64**，与 `Default` **共享线程**（IO 任务等待期间线程可以去跑 CPU 任务）。
   - `Dispatchers.Default` → **固定池，线程数 = CPU 核数**。
5. **`flowOn` vs `withContext`**：
   - `flowOn(dispatcher)`：**只影响它上面的部分**（上游），且**多个 flowOn 互不干扰**。
   - `withContext(dispatcher)`：**只影响块内部**。
   - **`flow {}` 内部禁止 `withContext`**（违反"上下文不变量"，编译期能检测到，运行时抛异常）→ 需要切换上游线程用 `flowOn`。
6. **面试话术**：**"withContext 切到指定上下文执行代码块并返回结果，内部是创建一个子协程由 Dispatcher 调度；挂起时释放线程，完成后恢复回原上下文。flowOn 只影响上游且可叠加，withContext 只影响块内。Flow 里不能用 withContext 切换线程，要用 flowOn。"**

---

**读者问：Flow 是什么？冷流和热流有什么区别？怎么把 Flow 转成热流？**

**AI答：**

1. **Flow 是什么**：**冷流（cold stream）** —— **每次 `collect` 都会重新执行上游的构建代码**。
   ```kotlin
   fun getUsers(): Flow<User> = flow {
       val users = api.getUsers()     // ★ 每次 collect 都重新请求
       users.forEach { emit(it) }
   }
   ```
   这与 `Observable.fromCallable { }` 同理（对比 `Observable.just()` 才是真正的一次性）。
2. **冷流 vs 热流**：
   | 维度 | 冷流（`Flow`） | 热流（`StateFlow`/`SharedFlow`） |
   | --- | --- | --- |
   | 执行时机 | **每个收集者独立触发** | 与收集者解耦 |
   | 多个收集者 | **各自执行一遍上游**（可能重复请求） | **共享一个上游** |
   | 晚到的收集者 | 能拿到全部数据 | 只能拿到**之后**的数据（除非有 replay） |
   | 典型用途 | 一次性数据（网络请求、DB 查询） | 持续状态（UI 状态、事件流） |
3. **转成热流：`stateIn` / `shareIn`**：
   ```kotlin
   val users: StateFlow<List<User>> = repo.observeUsers()
       .stateIn(
           scope = viewModelScope,
           started = SharingStarted.WhileSubscribed(5_000),
           initialValue = emptyList()
       )
   ```
   - **`stateIn`** → `StateFlow<T>`：**必需初始值**，**replay = 1**（新收集者立刻拿到最新值），**相同值会去重**（`equals` 比较）。
   - **`shareIn`** → `SharedFlow<T>`：**无初始值**，**replay 可配置**，**不去重**。
4. **`SharingStarted` 的三个选项**：
   - `WhileSubscribed(stopTimeoutMillis)`：**有订阅者时上游运行**，最后一个订阅者消失后**延迟 stopTimeout 毫秒再停**（防抖动）。**最常用**。
   - `Eagerly`：**立即启动上游**，不管有没有订阅者（如日志上报）。
   - `Lazily`：**第一个订阅者出现时启动**，之后**永不停止**。
5. **`collectAsStateWithLifecycle`（Compose）**：
   - 比 `collectAsState` 多了**生命周期感知**：页面不可见时**停止收集**（从而让 `WhileSubscribed` 的上游也停），回来时重新收集并拿到最新值。
   - **必须用它**而不是 `collectAsState`，否则后台页面还在消耗资源。
6. **`collectLatest` vs `collect`**：
   - `collect { }`：串行处理，每个元素处理完才处理下一个。
   - `collectLatest { }`：处理新元素时**取消前一个未完成的处理**（类似 `switchMap`）→ **适合"快速输入搜索"**。
7. **面试话术**：**"Flow 是冷流，每次 collect 都重新执行上游，所以多个收集者会重复请求。用 stateIn 转成 StateFlow 热流来共享，WhileSubscribed 保证没人订阅时上游停止。Compose 里用 collectAsStateWithLifecycle 做生命周期感知。"**

---

**读者问：`Dispatchers.IO` 和 `Dispatchers.Default` 有什么区别？为什么 IO 的线程数更多？**

**AI答：**

1. **核心区别**：
   | 维度 | `Dispatchers.IO` | `Dispatchers.Default` |
   | --- | --- | --- |
   | **线程数** | **上限 64**（`max(64, 核数)`） | **= CPU 核数** |
   | **适用** | **IO 密集**：网络、磁盘 | **CPU 密集**：计算、加密、编解码 |
   | **扩容策略** | **弹性**：需要时创建，用完 60s 回收 | **固定**：启动时按核数建好 |
2. **为什么 IO 的线程数可以更多**：
   - **CPU 密集任务的成本是"占满 CPU"** —— 线程数超过核数只会增加**上下文切换**，让所有任务都变慢。
   - **IO 密集任务的成本是"等待"** —— 线程在 `read()`/`connect()` 上**阻塞等待**时**不占 CPU**。等 64 个线程全在等网络，也才用了不到一个核的算力。所以给 IO 更多线程是安全的。
3. **⚠️ 共享线程池的设计（加分点）**：
   - `Dispatchers.IO` 和 `Dispatchers.Default` **共享同一个线程池**（`DefaultScheduler`）！区别在于 **IO 的任务有"弹性"特性**：IO 任务在等待时可以**让出线程**给 CPU 任务执行。
   - 具体实现：IO 任务的阻塞调用被包装成"**可让出（yielding）**"的阻塞 —— 线程在真正阻塞前会先把 CPU 让给其他任务。
   - **这意味着 `Dispatchers.IO` 的实际并发能力远大于 64**（因为阻塞时可以复用线程）。
4. **Android 特有**：
   - `Dispatchers.IO` 在 Android 上是**特殊实现**（基于 `HandlerThread` + `ScheduledExecutorService`），**会检测 App 是否在前台**，不在前台时**延迟执行任务**以省电。
   - `Dispatchers.Default` 是普通的固定线程池。
5. **选择原则**：
   ```text
   网络请求 / 文件读写 / 数据库查询  → Dispatchers.IO
   图片处理 / 加密 / JSON 大对象解析  → Dispatchers.Default
   UI 更新                        → Dispatchers.Main.immediate
   ```
   - **JSON 解析**要细分：Gson/Moshi 的反射解析是 CPU 密集 → `Default`；`kotlinx.serialization` 的 codegen 解析相对轻量但仍偏 CPU → `Default`。
6. **面试话术**：**"IO 的线程数上限 64，Default 等于核数。因为 CPU 任务超过核数只会加剧上下文切换，而 IO 任务阻塞时是'等待'不是'占用'，给更多线程反而提升并发。而且 Android 上 IO 调度器会在 App 后台时延迟任务来省电。"**

---

**读者问：协程怎么和 Retrofit 配合？`suspend` 函数到底是怎么实现的？**

**AI答：**

1. **Retrofit 的 `suspend` 支持（2.6+）**：
   - Retrofit 通过 **`CallAdapterFactory`** 识别 suspend 方法（字节码上多一个 `Continuation` 参数）。
   - 返回的 `Call` 的 `enqueue` 回调里，用 `Converter` 解析响应，然后 **`continuation.resumeWith(Result.success(data))`** 恢复协程。
   - **挂起期间不占用任何线程** —— 这是它比 `Call.execute()` + `runBlocking` 优越的根本原因。
2. **为什么 Retrofit 不能用 `runBlocking`**：
   - `runBlocking` **阻塞当前线程**直到结果返回 → 白白占住线程，失去了协程"挂起不占线程"的核心价值；在主线程调用就是 ANR。
3. **完整链路**：
   ```text
   协程调用 api.getUser(1)
      ↓  （编译器改写：传入 Continuation）
   Retrofit 动态代理 invoke()
      ↓
   构造 OkHttp Request → newCall → call.enqueue(callback)
      ↓
   ★ 协程挂起（返回 COROUTINE_SUSPENDED），释放线程
      ↓
   ... 网络响应回来（OkHttp 线程池线程）...
      ↓
   Converter 解析 → continuation.resumeWith(Result.success(user))
      ↓
   ★ 协程被调度器恢复到某个线程，从挂起点继续
   ```
4. **取消的传播**：
   - Retrofit 的 `suspend` 调用在协程被取消时，**默认不会取消底层的 HTTP 请求**（请求仍会跑完，浪费流量）。
   - 解决：自己用 `suspendCancellableCoroutine` 封装，调用 `cont.invokeOnCancellation { call.cancel() }`。
   ```kotlin
   suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
       cont.invokeOnCancellation { cancel() }      // ★ 协程取消 → 取消 HTTP
       enqueue(object : Callback { ... })
   }
   ```
5. **与协程取消的配合**：
   - `viewModelScope` 取消 → `withContext(Dispatchers.IO)` 块被取消 → Retrofit 的 `suspend` 调用挂起被恢复为 `CancellationException` → 若用了上面的 `await()` 封装，**HTTP 请求也会被真正取消**。
6. **面试话术**：**"Retrofit 2.6+ 通过 CallAdapter 支持 suspend：识别 Continuation 参数后走 enqueue 异步，响应回来时 resume 协程，整个过程挂起不占线程。用 runBlocking 会阻塞线程失去协程的意义。另外要注意协程取消默认不会取消 HTTP 请求，需要用 suspendCancellableCoroutine + invokeOnCancellation 封装。"**

## 关联笔记

- [[RxJava]]：响应式方案，与协程 Flow 的对比与互转
- [[Thread与Handler]]：线程池、Handler 等原生并发工具
- [[../Handler机制原理]]：Handler/Looper 与主线程消息机制
- [[../网络框架/Retrofit]]：Retrofit 的 suspend 支持
- [[../网络框架/OkHttp]]：把 enqueue 封装成 suspend 函数
- [[../网络框架/Ktor]]：Ktor 的协程原生 API
- [[../README]]：第 5 部分总览

## 参考资料

- Kotlin 官方协程文档：https://kotlinlang.org/docs/coroutines-overview.html
- Kotlin 协程最佳实践（Android Developers）：https://developer.android.com/kotlin/coroutines
- kotlinx.coroutines API 参考：https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/
- 《Kotlin 协程官方文档》"Structured concurrency" 章节：https://kotlinlang.org/docs/coroutines-structured-concurrency.html
- Android 开发者文档 "Coroutines in Android"：https://developer.android.com/kotlin/coroutines/understanding
- Roman Belov（JetBrains）"Coroutine Anatomy" 系列演讲
