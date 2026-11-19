---
tags: [android]
part: 第5部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[../README]]"
---

# Thread与Handler

## 一句话定义

Java 线程是**操作系统调度的基本单位**（"执行流"），而 Android 的 **Handler 是线程内的消息派发机制**：把任务（`Runnable`）或消息（`Message`）投递到某个线程的**消息队列**，由该线程的 `Looper` 循环取出并执行。**线程解决"谁来执行"，Handler 解决"什么时候执行、切到哪里执行"。**

## 核心原理

### 1. Java 线程基础

| 概念 | 说明 |
| --- | --- |
| **Thread** | `Runnable` 的执行载体，`start()` 后进入 `run()` |
| **Runnable** | 无返回值的任务接口（`void run()`） |
| **Callable\<V\>** | 有返回值、可抛异常的接口（`V call() throws Exception`） |
| **Future\<V\>** | `Callable` 的结果容器，支持 `get()`（阻塞）、`cancel()`、`isDone()` |
| **ThreadLocal\<T\>** | **线程本地变量**，每个线程独立持有一份 `ThreadLocalMap` |
| **volatile** | 保证可见性 + 禁止指令重排，**不保证原子性** |
| **synchronized** |  monitor 锁，可重入，基于 JVM 指令（`monitorenter`/`monitorexit`） |
| **CAS / AtomicInteger** | 无锁并发，基于 `Unsafe` 的原子指令 |

**Thread vs Runnable 的区别（经典题）**：

```text
继承 Thread：
  class MyThread extends Thread {
      public void run() { doWork(); }
  }
  ✗ Java 单继承，Thread 已被占用，无法再继承其他类
  ✗ 每个任务都要 new 一个 Thread 对象（创建线程有系统开销）

实现 Runnable：
  class MyTask implements Runnable {
      public void run() { doWork(); }
  }
  ✓ 可以复用线程池，多个任务共享一个 Runnable 实例
  ✓ 可以继承其他类
  ✗ 无法直接返回值、无法抛受检异常（需要 Callable 包装）
```

> 结论：**几乎总是用 `Runnable` + 线程池**，而不是继承 `Thread`。

**Callable + FutureTask 的组合**：

```java
Callable<String> task = () -> {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    return compute();
};
FutureTask<String> future = new FutureTask<>(task);
new Thread(future).start();           // 用线程启动 FutureTask
String result = future.get();          // 阻塞等待结果
```

### 2. ThreadPoolExecutor 的七个参数

```java
public ThreadPoolExecutor(
    int corePoolSize,                       // ① 核心线程数
    int maximumPoolSize,                    // ② 最大线程数
    long keepAliveTime,                     // ③ 非核心线程空闲存活时间
    TimeUnit unit,                          // ④ 时间单位
    BlockingQueue<Runnable> workQueue,      // ⑤ 工作队列
    ThreadFactory threadFactory,            // ⑥ 线程工厂
    RejectedExecutionHandler handler        // ⑦ 拒绝策略
)
```

**Android 推荐配置**（来自 `Executors` 工具类内部）：

```kotlin
val cpuCount = Runtime.getRuntime().availableProcessors()

val executor = ThreadPoolExecutor(
    corePoolSize = cpuCount,                    // 核心 = CPU 核数
    maximumPoolSize = cpuCount,                 // ★ Android 上最大 = 核心（不扩容）
    keepAliveTime = 0L,                         // 核心线程永不超时
    TimeUnit.MILLISECONDS,
    LinkedBlockingQueue<Runnable>(),            // 无界队列
    ThreadFactory { r -> Thread(r, "worker-${counter.getAndIncrement()}").apply { isDaemon = false } },
    DiscardOldestPolicy()                       // 或 CallerRunsPolicy
)
```

> **Android 与服务端的不同**：**服务端**线程池通常是 `core=N, max=2N, queue=有界, 拒绝=CallerRuns`；**Android** 建议 `core = max = CPU核数, queue = 无界`，因为移动端任务多为 IO 密集，且**不能让任务无限堆积耗尽内存**。Android 的 `Executors.newFixedThreadPool` / `newCachedThreadPool` 就是这么实现的。

**七个参数的完整含义**：

| 参数 | 含义 | 设置建议 |
| --- | --- | --- |
| **corePoolSize** | 核心线程数，**即使空闲也保留**（除非设了 `allowCoreThreadTimeOut(true)`） | CPU 密集 = 核数；IO 密集 = 核数 × 2~4 |
| **maximumPoolSize** | 最大线程数 | **必须 ≥ corePoolSize** |
| **keepAliveTime** | **非核心线程**的空闲存活时间 | 30~60s |
| **unit** | 时间单位 | `SECONDS` |
| **workQueue** | 任务队列 | **Android 用无界 `LinkedBlockingQueue`**；服务端用**有界**队列（防止 OOM） |
| **threadFactory** | 创建线程，可设置**名字**（便于排查）、**优先级**、**是否守护线程** | **一定要给线程起名字** |
| **handler** | 队列满 + 线程数达上限时的拒绝策略 | 见下表 |

### 3. ThreadPoolExecutor 的执行流程

```text
execute(task)
   │
   ├─ ① 当前线程数 < corePoolSize ?
   │      └─ 是 → addWorker(task, core=true)   ← 直接创建核心线程执行
   │
   ├─ ② workQueue.offer(task) 成功 ?
   │      └─ 是 → 任务入队，等核心线程空闲时取走
   │
   ├─ ③ 当前线程数 < maximumPoolSize ?
   │      └─ 是 → addWorker(task, core=false)  ← 创建非核心线程执行
   │
   └─ ④ 否 → 执行拒绝策略 handler.rejectedExecution(task, this)
```

**关键点（面试常问）**：

1. **执行顺序是"先核心线程 → 再入队 → 再扩容非核心线程 → 最后拒绝"**，**不是**"先入队 → 再扩容"。
2. **用无界队列时，第 ③ 步永远不会走到** —— 因为队列永远不会满，`offer` 永远成功。所以 `maximumPoolSize` **形同虚设**。这是 `Executors.newFixedThreadPool` 的隐患。
3. **核心线程创建时机**：第一个任务到达时**立即创建**核心线程（而不是预热）。`prestartAllCoreThreads()` 可预创建。
4. **拒绝策略在主线程调用时特别危险**：`CallerRunsPolicy` 会让**主线程执行任务** → 直接 ANR。

**四种内置拒绝策略**：

| 策略 | 行为 | 适用场景 |
| --- | --- | --- |
| **AbortPolicy**（默认） | 抛 `RejectedExecutionException` | 需要显式感知失败 |
| **CallerRunsPolicy** | **由调用线程（提交任务的线程）执行** | 背压机制，保证不丢任务；⚠️ 主线程提交会 ANR |
| **DiscardPolicy** | 静默丢弃 | 可丢的任务（如埋点上报） |
| **DiscardOldestPolicy** | 丢弃队列**最老**的任务，再重试 | 需要最新数据的场景（实时行情） |

> Android 官方 `ThreadPoolExecutor` 的 `RejectedExecutionHandler` 文档特别提到：**当使用 `CallerRunsPolicy` 且在主线程提交任务时，会导致应用无响应**。

### 4. 主线程与 Looper/MessageQueue/Handler

```text
┌──────────────────── 主线程（UI 线程）────────────────────┐
│                                                            │
│   Looper.loop()                                            │
│      │                                                     │
│      ├─ MessageQueue.next()  ← 阻塞等待消息                │
│      │     ↓                                                │
│      ├─ msg.target.dispatchMessage(msg)                     │
│      │     └─ handler.handleMessage(msg)                   │
│      │                                                     │
│      └─ 循环（直到 quit）                                   │
└────────────────────────────────────────────────────────────┘
```

- **主线程（UI 线程）**由 `Looper.prepareMainLooper()` 在 `ActivityThread.main()` 里创建，**其 Looper 是全局唯一的**（`Looper.getMainLooper()`）。
- **UI 线程的所有刷新（`View.invalidate` → `requestLayout`）最终都要在主线程执行**，这也是为什么不能在工作线程直接改 UI。
- 详细的 Looper / MessageQueue / 同步屏障原理见 [[../Handler机制原理]]。

### 5. Handler 的 post / postDelayed 原理

```kotlin
handler.post(runnable)
handler.postDelayed(runnable, delayMillis)
handler.sendMessage(message)
handler.sendMessageDelayed(message, delayMillis)
handler.sendEmptyMessage(what)
```

**内部流程（以 post 为例）**：

```text
handler.post(runnable)
   │
   └─ postDelayed(runnable, 0)
        │
        └─ sendMessageDelayed(msg, 0)
             │
             ├─ msg.target = this          ★ 关键：Message 记住自己是发给谁的
             ├─ msg.when = uptimeMillis() + 0
             │
             └─ messageQueue.enqueueMessage(msg, false)
                    │
                    ├─ 按 when 升序插入链表
                    └─ needWake = true → nativeWake()
```

**`postDelayed` 的关键点**：

1. **不是"延迟 delay 毫秒后再执行"**，而是**消息的 `when` 字段 = 当前 uptime + delay**。`MessageQueue` 按 `when` 排序，到时间才取出。
2. **延迟任务占用的是队列位置，不是线程**。等待期间线程是空闲的（阻塞在 `nativePollOnce`）。
3. **精确度**：`MessageQueue` 用 `SystemClock.uptimeMillis()`（**单调时钟**，不受用户改系统时间影响）。系统进入深度休眠时 `uptimeMillis` **不前进**，所以 `postDelayed` 的任务会**延迟到唤醒后才执行**。
4. **`post` 的 Runnable 会被包装成 Message**：
   ```java
   public final boolean post(Runnable r) {
       return sendMessageDelayed(getPostMessage(r), 0);
   }
   private static Message getPostMessage(Runnable r) {
       Message m = new Message();
       m.callback = r;      // ★ callback 字段保存 Runnable
       return m;
   }
   ```
   分发时 `Handler.dispatchMessage` 会优先执行 `msg.callback`（见 [[../Handler机制原理]]）。

**移除消息**：

```kotlin
handler.removeCallbacks(runnable)                       // 移除特定的 Runnable
handler.removeCallbacksAndMessages(null)                // 移除该 Handler 的全部消息（⚠️ 会误删其他任务）
handler.removeMessages(what)                            // 按 what 移除
```

> **⚠️ 经典坑**：`removeCallbacksAndMessages(null)` 会清空**该 Handler 的所有消息**。如果多个功能共用一个 Handler，一个功能取消会误伤其他功能。**最佳实践：给不同功能用不同的 Handler 实例**，或用 `what` 区分。

### 6. AsyncTask：原理与被废弃的原因

**AsyncTask 的执行流程**：

```text
execute(task)  →  onPreExecute()          【主线程】
                →  doInBackground()         【工作线程】
                →  onPostExecute(result)    【主线程】

executeOnExecutor(executor, task)
                →  onPreExecute()          【主线程】
                →  doInBackground()         【executor 指定的线程池】
                →  onPostExecute(result)    【主线程】
```

**内部实现（Android 源码级）**：

```text
AsyncTask
   ├─ sDefaultExecutor = SerialExecutor（串行，一次只跑一个任务）
   │     └─ 用一个 ArrayDeque + synchronized 串行化
   ├─ sWorker = ThreadPoolExecutor(1, 1, 3s, ..., SerialExecutor)
   │     └─ 底层工作线程池
   ├─ mWorker = WorkerRunnable
   │     └─ 持有 FutureTask<Result>，doInBackground 后调用 postResult
   └─ mHandler = new Handler(Looper.getMainLooper())
         └─ 负责把结果切回主线程
```

**被废弃的原因（Android 30 / API 30 起 `@Deprecated`）**：

1. **默认串行执行，容易踩坑**：早期 `execute()` 是**串行**的（`SerialExecutor`），一个耗时任务会**阻塞后面所有任务**。而且开发者常常误以为它是并行的。
2. **生命周期不受控**：AsyncTask 持有 `Activity`/`Fragment` 引用，**旋转屏幕后任务继续执行，回调到已销毁的 Activity** → 内存泄漏或崩溃。
3. **没有取消传播机制**：`onCancelled()` 不会自动中断 `doInBackground` 里的线程，必须手动 `isCancelled()` 检查 + `Thread.interrupt()`。
4. **串行 Executor 队列会无限堆积**：提交 100 个任务，最后一个要等前 99 个跑完。
5. **API 设计缺陷**：结果只能在 `onPostExecute` 拿到，**无法返回 `LiveData`/`Flow`**，无法组合、无法测试。
6. **官方替代**：**`kotlinx.coroutines`**（协程 + `lifecycleScope`）、**`WorkManager`**（需要保证执行的任务）、**`java.util.concurrent`**（纯 Java 场景）。

> 面试话术：**"AsyncTask 被废弃主要是生命周期不受控和默认串行两个问题。现在用协程的 `lifecycleScope.launch` + `withContext(Dispatchers.IO)`，天然解决生命周期绑定和取消传播。"**

### 7. Android 提供的并发工具

| 工具 | 用途 | 说明 |
| --- | --- | --- |
| **`Executors.newFixedThreadPool(n)`** | 固定大小线程池 | 内部 `LinkedBlockingQueue`（无界），**maxPoolSize 无效** |
| **`Executors.newCachedThreadPool()`** | 弹性线程池 | 线程数无上限，空闲 60s 回收；**短任务大量提交时可能创建海量线程** |
| **`Executors.newSingleThreadExecutor()`** | 单线程，串行任务 | 保证顺序执行 |
| **`Executors.newScheduledThreadPool(n)`** | 定时/周期任务 | 底层 `ScheduledThreadPoolExecutor` |
| **`HandlerThread`** | **带 Looper 的线程** | 见下 |
| **`AsyncTask`**（已废弃） | 简化异步 | 见上 |
| **`WorkManager`** | **可延迟、可保证执行的后台任务** | 进程被杀也能恢复，**适合上传/同步** |
| **`IntentService`**（已废弃） | 后台串行服务 | 已被 `WorkManager` 取代 |
| **`kotlinx.coroutines`** | **现代方案** | 见 [[Kotlin协程]] |

**HandlerThread 的用法**（面试常考）：

```kotlin
// 创建一条带 Looper 的后台线程
val handlerThread = HandlerThread("my-worker").apply { start() }

// 拿到它的 Handler —— post 到这个 Handler 的任务就在该线程执行
val handler = Handler(handlerThread.looper)

handler.post {
    // 这里运行在 "my-worker" 线程
    val data = loadDataFromDb()
    Log.d("TAG", "current thread = ${Thread.currentThread().name}")  // my-worker
}

// 使用完毕后必须 quit，否则线程泄漏
handlerThread.quitSafely()
// HandlerThread.quit() 立即停止；quitSafely() 处理完队列中已有消息再停
```

**HandlerThread 的本质**：它是一个**继承 Thread 且内部持有 Looper 的线程**。`start()` 后 `run()` 里调用 `Looper.prepare()` + `Looper.loop()`，所以这个线程有了自己的消息队列，可以接收 Handler 任务。

**典型用途**：**串行执行的后台任务队列**（如顺序处理图片解码、顺序写数据库），保证**顺序**且**不占用主线程**。

```text
主线程                          HandlerThread("worker")
  │                                  │
  │── post(task1) ──────────────────►│  MessageQueue: [task1]
  │── post(task2) ──────────────────►│             [task1, task2]
  │                                  │  执行 task1 → 执行 task2
  │◄── post(task3) ─────────────────►│  （严格按投递顺序串行）
```

### 8. 内存可见性与线程安全

| 问题 | 现象 | 解决方案 |
| --- | --- | --- |
| **可见性** | 线程 A 改了普通变量，线程 B 一直读到旧值 | `volatile` / 加锁 / `AtomicInteger` |
| **原子性** | `count++` 实际是"读-改-写"三步，可能丢失更新 | `AtomicInteger` / `synchronized` / `LongAdder` |
| **有序性** | 指令重排导致可见顺序错乱 | `volatile`（含内存屏障）/ 锁 |
| **死锁** | 互相持有对方需要的锁 | 固定加锁顺序 / `tryLock` + 超时 / 避免嵌套锁 |
| **线程池 OOM** | 无界队列 + 大量任务堆积 | 有界队列 + 合理拒绝策略 |

**`volatile` 的语义**：保证**可见性**（写立即对其他线程可见）+ **禁止指令重排**（加内存屏障）。**但 `count++` 仍然不安全**——它是复合操作，需要 `AtomicInteger.incrementAndGet()` 或加锁。

**`synchronized` 的特性**：**可重入**（同一线程可重复获取同一把锁）、**原子性 + 可见性 + 有序性**、**会阻塞**（线程竞争时挂起）。

## 代码示例

### 1. 生产级线程池（带命名、监控、优雅关闭）

```kotlin
object AppExecutors {

    private val cpuCount = Runtime.getRuntime().availableProcessors()

    /** IO 密集型：核心 = 核数，最大 = 核数 * 2，无界队列 */
    val io: ExecutorService by lazy {
        ThreadPoolExecutor(
            corePoolSize = cpuCount,
            maximumPoolSize = cpuCount * 2,
            keepAliveTime = 60L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            NamedThreadFactory("io-worker"),
            // ★ 不用 CallerRunsPolicy：主线程提交时会导致 ANR
            ThreadPoolExecutor.AbortPolicy()
        )
    }

    /** CPU 密集型：核心 = 最大 = 核数，避免超额订阅 */
    val compute: ExecutorService by lazy {
        ThreadPoolExecutor(
            corePoolSize = cpuCount,
            maximumPoolSize = cpuCount,
            keepAliveTime = 0L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            NamedThreadFactory("compute-worker"),
            ThreadPoolExecutor.AbortPolicy()
        )
    }

    fun shutdown() {
        io.shutdown()      // 不再接受新任务，已提交任务继续执行
        compute.shutdown()
    }
}

/** 线程工厂：给线程起名字，方便线上排查（ANR 栈、内存 dump 里一眼认出归属） */
class NamedThreadFactory(private val prefix: String) : ThreadFactory {
    private val index = AtomicInteger(0)
    override fun newThread(r: Runnable): Thread =
        Thread(r, "$prefix-${index.getAndIncrement()}").apply {
            isDaemon = false      // Android 上守护线程没意义，且会被系统杀掉
            priority = Thread.NORM_PRIORITY
        }
}
```

### 2. Handler 基础用法：主线程 / 子线程切换

```kotlin
class MainThreadHelper {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 切到主线程执行（已在主线程则直接执行） */
    fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post(action)
    }

    /** 延迟执行：postDelayed 内部是 when = uptime + delay，不是真的 sleep */
    fun delay(delayMs: Long, action: () -> Unit) {
        mainHandler.postDelayed(action, delayMs)
    }

    /** 取消：必须传同一个 Runnable 实例，否则取消不掉 */
    fun cancel(action: Runnable) {
        mainHandler.removeCallbacks(action)
    }
}
```

```kotlin
// 使用演示
class MyActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)   // 每秒刷新
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(tickRunnable)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tickRunnable)   // ★ 必须在 onStop 移除，否则泄漏
    }
}
```

### 3. HandlerThread：串行后台任务队列

```kotlin
class SerialTaskRunner(private val name: String = "serial-runner") {

    private val thread = HandlerThread(name).apply { start() }
    private val handler = Handler(thread.looper)

    /** 按投递顺序串行执行 */
    fun submit(task: () -> Unit) {
        handler.post(task)
    }

    fun submitDelayed(delayMs: Long, task: () -> Unit) {
        handler.postDelayed(task, delayMs)
    }

    /** 优雅关闭：先处理完队列中的消息再停 */
    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}

// 使用：顺序解码并写入数据库
class ImagePipeline {
    private val runner = SerialTaskRunner("image-pipeline")

    fun process(uri: String) {
        runner.submit {
            val bitmap = decode(uri)          // 耗时
            db.insert(bitmap)                 // 顺序写，避免并发写冲突
        }
    }
}
```

### 4. Callable + Future：带超时的任务

```kotlin
suspend fun <T> withTimeoutFuture(timeoutMs: Long, task: Callable<T>): T? =
    withContext(Dispatchers.IO) {
        val future = AppExecutors.io.submit(task)
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)     // 中断任务
            null
        }
    }

// 使用
val result = withTimeoutFuture(3_000) {
    Callable { computeHeavyResult() }
}
```

### 5. 优雅关闭线程池（App 退出 / 页面销毁）

```kotlin
class Repository(private val executor: ExecutorService = AppExecutors.io) {

    fun shutdown() {
        executor.shutdown()                        // 1. 不再接收新任务
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()             // 2. 超时则中断正在执行的任务
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()      // 3. 恢复中断标志
        }
    }
}
```

### 6. 异步任务的取消与中断

```kotlin
val executor = AppExecutors.io

val future = executor.submit {
    var count = 0
    while (!Thread.currentThread().isInterrupted) {   // ★ 检查中断标志
        count++
        Thread.sleep(10)
    }
    count
}

// 取消：interrupt 会让 sleep 抛 InterruptedException，让阻塞 IO 抛 InterruptedIOException
future.cancel(true)

// 取消前主动轮询 isCancelled 的场景：
val future2 = executor.submit {
    while (!isCancelled) {
        doWork()
    }
}
future2.cancel(true)
```

### 7. 周期任务：ScheduledExecutorService vs Handler

```kotlin
// 方式一：ScheduledExecutorService（适合纯后台任务）
val scheduler = Executors.newScheduledThreadPool(1)
scheduler.scheduleAtFixedRate(
    { syncData() },        // 任务
    0,                     // 首次延迟
    5, TimeUnit.MINUTES    // 周期
)

// 方式二：Handler.postDelayed（适合需要在主线程更新 UI）
handler.postDelayed(object : Runnable {
    override fun run() {
        refreshUi()
        handler.postDelayed(this, 5_000)
    }
}, 0)
```

## 常见问题 / 面试要点

**读者问：为什么不建议用 AsyncTask？它被废弃的原因是什么？**

**AI答：**

1. **AsyncTask 做了什么**：内部有 `SerialExecutor`（串行队列）+ 一个 `ThreadPoolExecutor(1,1,...)`（真正干活的线程）+ 一个绑定主线程 Looper 的 `Handler`（负责回调切回主线程）。`doInBackground` 在工作线程，`onPreExecute`/`onPostExecute` 在主线程。
2. **废弃的四个核心理由**：
   - **生命周期不受控**：持有 `Activity`/`Fragment` 引用，**旋转屏幕后任务照跑，回调打到已销毁的 Activity** → 内存泄漏或 `IllegalStateException`。
   - **默认串行**：`execute()` 走 `SerialExecutor`，**一个任务阻塞后面所有任务**，而很多开发者误以为它是并行的。
   - **取消不彻底**：`cancel()` 不会真正中断 `doInBackground` 里的线程，必须自己 `isCancelled()` + `Thread.interrupt()`。
   - **结果无法组合**：只能在 `onPostExecute` 拿到返回值，不能返回 `LiveData`/`Flow`，无法链式组合、无法单测。
3. **队列问题**：`SerialExecutor` 用 `ArrayDeque` 串行，任务**无上限堆积**，前面的慢任务会让最后的任务等很久。
4. **现代替代方案**：
   ```kotlin
   // 生命周期绑定 + 自动取消 + 灵活组合
   lifecycleScope.launch {
       val data = withContext(Dispatchers.IO) { repository.load() }
       binding.text.text = data
   }
   ```
   - 协程版天然解决"生命周期"（`viewModelScope`/`lifecycleScope` 在销毁时取消）和"取消传播"（`CancellationException` 自动传到子协程）。
   - 需要"保证执行"的任务（如数据上传）用 **WorkManager**（进程被杀也能恢复）。
5. **面试话术**：**"AsyncTask 最大的问题是生命周期不受控（旋转屏幕回调到已销毁的 Activity）和默认串行执行。现在统一用协程：lifecycleScope + withContext(Dispatchers.IO)，生命周期绑定和取消传播都自动解决。"**

---

**读者问：ThreadPoolExecutor 的七个参数是什么？各怎么设置？**

**AI答：**

1. **七个参数**：`corePoolSize`、`maximumPoolSize`、`keepAliveTime`、`unit`、`workQueue`、`threadFactory`、`handler`。
2. **执行流程（必须能背）**：
   ```
   线程数 < core？          → 建核心线程执行
   否则队列能放？          → 入队
   否则线程数 < max？      → 建非核心线程执行
   否则                    → 拒绝策略
   ```
   ⚠️ **顺序是"核心 → 队列 → 扩容"，不是"核心 → 扩容 → 队列"**。这意味着**用无界队列时 `maximumPoolSize` 永远用不上**（队列永不满）。
3. **参数设置建议（分场景）**：
   - **CPU 密集型**（计算、编解码）：`core = max = CPU核数`。线程多了只会增加上下文切换，**超订阅反而更慢**。
   - **IO 密集型**（网络、磁盘）：`core = CPU核数`，`max = 核数 × 2~4`，`keepAlive = 30~60s`。
   - **Android 端**：建议 `core = max = 核数` + **无界队列**，因为移动端任务以 IO 为主，且不能让任务无限堆积（服务端才需要有界队列 + 拒绝）。
   - **`workQueue`**：`LinkedBlockingQueue`（可选容量，有界才有拒绝）、`SynchronousQueue`（`newCachedThreadPool` 用，直接交给线程）、`ArrayBlockingQueue`（有界）。
   - **`threadFactory`**：**必须设置线程名**，否则线上 ANR 栈 / 线程 dump 里全是 `pool-1-thread-3`，无法定位归属。
   - **`handler`**：`AbortPolicy`（默认，抛异常）或 `DiscardPolicy`（可丢任务）。**⚠️ 慎用 `CallerRunsPolicy`** —— 主线程提交任务时会让主线程执行，直接 ANR。
4. **Android 官方 `Executors` 的坑**：
   - `newFixedThreadPool` 内部是 `LinkedBlockingQueue`（无界）→ `maximumPoolSize = Integer.MAX_VALUE` **形同虚设**，任务无限堆积可能 OOM。
   - `newCachedThreadPool` 线程数无上限，短任务海量提交会**创建海量线程** → OOM / 系统被杀。
   - **结论：生产环境自己 `new ThreadPoolExecutor(...)` 显式指定全部参数。**
5. **面试话术**：**"七个参数是 core、max、keepAlive、unit、queue、factory、handler。执行顺序是先核心线程、再入队、再扩容非核心、最后拒绝，所以无界队列会让 max 失效。Android 上我 core=max=核数、无界队列、自定义线程工厂加线程名、AbortPolicy。"**

---

**读者问：HandlerThread 是什么？怎么用？**

**AI答：**

1. **是什么**：一个**内部持有 Looper 的 Thread**。它的 `run()` 里执行 `Looper.prepare()` + `Looper.loop()`，所以这条线程**有自己的消息队列**，可以接收 Handler 任务。
2. **典型用法**：
   ```kotlin
   val handlerThread = HandlerThread("my-worker").apply { start() }
   val handler = Handler(handlerThread.looper)
   handler.post { /* 运行在 "my-worker" 线程 */ }
   // 用完必须关闭，否则线程泄漏
   handlerThread.quitSafely()
   ```
3. **为什么需要它**（而不是直接 `Thread { }`）：
   - **串行执行**：`Handler` 的消息队列保证**严格按投递顺序**执行，一个任务没跑完后面的不会插队。
   - **可投递**：可以在任意线程 `post` 任务到这条线程，天然完成"跨线程的串行任务队列"。
   - **可取消**：`removeCallbacks` / `removeCallbacksAndMessages`。
4. **典型场景**：
   - **顺序写数据库 / 顺序解码图片**（并发写会冲突，串行又不想占用主线程）。
   - **需要"后台线程 + 消息队列"组合**（比如一个常驻的音频/蓝牙处理线程）。
   - **替代 HandlerThread 的现代方案**：用协程的 `limitedParallelism(1)` 或一个 `Mutex` 保护的协程作用域：
     ```kotlin
     val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
     // 效果等价于 HandlerThread 的串行队列
     ```
5. **坑**：
   - **必须 `quit()` / `quitSafely()`**，否则线程一直活着 → 泄漏。`quit()` 立即停止并丢弃队列中消息；`quitSafely()` 处理完已有消息再停（推荐）。
   - `quitSafely` 后再 `post` 会返回 `false`（消息被丢弃）。
   - `HandlerThread` **不是** `WorkManager` 的替代品 —— 后台任务需要"保证执行"时必须用 WorkManager。
6. **面试话术**：**"HandlerThread 是带 Looper 的线程，用它的 Handler post 任务可以保证在这个后台线程上串行执行，适合顺序写库、顺序解码这类需求。用完必须 quitSafely，否则线程泄漏。现在协程场景可以用 limitedParallelism(1) 替代。"**

---

**读者问：Handler 的 post 和 postDelayed 是怎么实现的？postDelayed 会占用线程吗？**

**AI答：**

1. **实现链路**：
   ```
   handler.post(r)      → postDelayed(r, 0)
   handler.postDelayed(r, delay) → postDelayed(r, delay)
       ↓
   sendMessageDelayed(msg, delay)
       ↓
   msg.target = this              // ★ Message 记住发给谁
   msg.when = uptimeMillis() + delay
       ↓
   messageQueue.enqueueMessage(msg)
       ↓
   按 when 升序插入队列 + nativeWake() 唤醒阻塞的线程
   ```
2. **`post` 的 Runnable 是怎么变成 Message 的**：
   ```java
   private static Message getPostMessage(Runnable r) {
       Message m = new Message();
       m.callback = r;      // ★ Runnable 存在 callback 字段
       return m;
   }
   ```
   分发时 `Handler.dispatchMessage` 会**优先执行 `msg.callback`**，执行完就不再调 `handleMessage`。
3. **不占用线程**：`postDelayed` 只是**在队列里排一个未来的位置**。等待期间线程阻塞在 `MessageQueue.next()` 的 `nativePollOnce` 里，**不消耗 CPU**。
4. **两个重要细节**：
   - **用的是 `SystemClock.uptimeMillis()`**（**单调时钟**），**不受用户修改系统时间影响**。
   - **深度休眠时 `uptimeMillis` 不前进** → `postDelayed` 的任务会**推迟到系统唤醒后**才执行。需要精确时间的定时任务（如倒计时）要用 `SystemClock.elapsedRealtime()`。
5. **取消的正确姿势**：
   ```kotlin
   private val task = Runnable { doSomething() }
   handler.postDelayed(task, 1000)
   handler.removeCallbacks(task)      // ★ 必须传同一个 Runnable 实例
   ```
   - `removeCallbacksAndMessages(null)` 会**清空该 Handler 的所有消息** —— 多个功能共用 Handler 时容易误伤。
6. **面试话术**：**"postDelayed 不是 sleep，是把消息的 when 设成 uptime+delay 塞进队列，MessageQueue 按 when 排序，到点才取出。等待不占线程。取消要传同一个 Runnable 实例，removeCallbacksAndMessages(null) 会清空全部。"**

---

**读者问：主线程为什么不能做耗时操作？ANR 是怎么判定的？**

**AI答：**

1. **主线程的职责**：主线程负责**所有 UI 的创建、测量、布局、绘制、事件响应**（见 [[../Handler机制原理]]）。**任何阻塞都会导致界面无响应**。
2. **ANR 的判定标准**（Android 系统层面）：
   - **输入事件超时 5 秒**：用户按键或触摸事件在 5 秒内没被处理完。
   - **前台 Service 超时 20 秒**（部分版本 10~60 秒，视 API 与场景）：前台服务在限定时间内没响应 `onStartCommand`。
   - **广播接收超时 10 秒**：前台广播的 `onReceive` 未在 10 秒内完成（前台 10s，后台 60s）。
   - **CPU 占用 90% 持续 5 秒**（`ANR in native code` / 广播）。
3. **ANR 发生时系统做了什么**：dump 出 `/data/anr/traces.txt`（含各线程栈）、弹出"应用无响应"对话框、必要时**杀掉进程**。
4. **常见的触发原因**：
   - 主线程做**网络请求**（`NetworkOnMainThreadException` 会先抛，但很多 IO 不抛）。
   - 主线程做**磁盘读写**、**大量计算**、**大量对象分配导致 GC**。
   - **死锁**（主线程等锁，锁又被其他线程持有）。
   - 主线程被 `synchronized` 阻塞。
5. **排查手段**：
   - **Android Studio Profiler**（抓 main thread 栈）。
   - **`adb shell dumpsys activity processes | grep -i anr`**。
   - **线上监控**：通过 `ApplicationExitInfo`（API 30+）可以拿到**系统记录的 ANR 原因和 traces**，这是线上 ANR 归因的标准方案。
   - **`/data/anr/traces.txt`**（需 root 或用 `ApplicationExitInfo`）。
6. **面试话术**：**"主线程负责 UI 的绘制和事件分发，一旦阻塞界面就冻结。系统按输入 5 秒、前台服务 20 秒、广播 10 秒判定 ANR。排查靠 traces 和 ApplicationExitInfo。避免办法就是把耗时操作放到子线程/协程的 IO 调度器上。"**

---

**读者问：怎么保证线程安全？`volatile` 和 `synchronized` 有什么区别？**

**AI答：**

1. **三个并发问题**：
   - **可见性**：一个线程改了共享变量，其他线程看不到最新值（CPU 多核缓存 + JIT 优化）。
   - **原子性**：`count++` 是"读-改-写"三步，可能丢失更新。
   - **有序性**：编译器/CPU 可能重排指令。
2. **`volatile` 的保证**：
   - ✅ **可见性**：写操作立即对其他线程可见（写内存 + 读内存屏障）。
   - ✅ **有序性**：禁止相关的指令重排。
   - ❌ **不保证原子性**：`volatile int count; count++` **仍然会丢失更新**（两个线程可能读到同一个值）。
   - 典型用途：**状态标记位**（`volatile boolean running`）、**单例的 DCL**（双重检查锁）、**线程安全的原子操作**（`AtomicInteger` 内部就是 volatile + CAS）。
3. **`synchronized` 的保证**：
   - ✅ 原子性（互斥）
   - ✅ 可见性（解锁 happens-before 加锁）
   - ✅ 有序性
   - ✅ **可重入**（同一线程可重复获取同一把锁，用计数器实现）
   - ❌ **会阻塞**（竞争时线程挂起 → 上下文切换开销）
4. **选型表**：
   | 场景 | 方案 |
   | --- | --- |
   | 简单计数器 / 状态标志 | `AtomicInteger` / `volatile` |
   | 高频计数（统计） | `LongAdder`（比 `AtomicLong` 高并发下更快） |
   | 复杂临界区 | `synchronized` / `ReentrantLock` |
   | 需要超时/可中断/公平锁 | `ReentrantLock` |
   | 读多写少 | `ReadWriteLock` / `CopyOnWriteArrayList` |
   | 线程隔离 | `ThreadLocal` |
5. **`ThreadLocal`**：
   - 每个线程有独立的 `ThreadLocalMap`，`set`/`get` 只操作自己那份。
   - Android 典型用法：**`Looper.myLooper()` 就是 ThreadLocal**（每线程一个 Looper）；还有 `ViewThreadLocal`（Canvas 绘制上下文）。
   - **⚠️ 内存泄漏风险**：`ThreadLocal` 的 key 是弱引用，但 **value 是强引用**。线程池里的线程是长生命周期的 → ThreadLocal 的 value 永不过期 → 泄漏。**用完必须 `remove()`**。
6. **面试话术**：**"volatile 保证可见性和有序性但不保证原子性，count++ 仍要用 AtomicInteger 或锁；synchronized 三者都保证且可重入。选型上：标志位用 volatile，计数用 Atomic/LongAdder，复杂临界区用锁。ThreadLocal 用完必须 remove，否则在线程池里会泄漏。"**

---

**读者问：线程池的任务堆积了怎么办？为什么 `Executors` 提供的工厂方法有坑？**

**AI答：**

1. **任务堆积的根因**：
   - **无界队列 + 生产速度 > 消费速度** → 队列无限增长 → **OOM**。
   - `Executors.newFixedThreadPool(n)` 内部就是 `LinkedBlockingQueue`（**无界**），所以 `maximumPoolSize = Integer.MAX_VALUE` **形同虚设**，任务无限堆积。
   - `Executors.newCachedThreadPool()` 线程数无上限（`SynchronousQueue`），海量短任务会**创建海量线程** → 线程数过多导致 OOM、系统被杀。
   - `Executors.newSingleThreadExecutor()` 同样是无界队列。
2. **正确做法**：
   ```kotlin
   ThreadPoolExecutor(
       corePoolSize = cpuCount,
       maximumPoolSize = cpuCount * 2,
       keepAliveTime = 60, TimeUnit.SECONDS,
       workQueue = ArrayBlockingQueue<Runnable>(256),   // ★ 有界队列
       threadFactory = NamedThreadFactory("worker"),
       handler = ThreadPoolExecutor.DiscardPolicy()      // 或 CallerRuns（注意主线程风险）
   )
   ```
3. **有界队列带来的连锁效应**：队列满 → 触发第 ③ 步**扩容到 `maximumPoolSize`** → 还是满 → 走**拒绝策略**。所以**有界队列必须和合理的 `maximumPoolSize` + 拒绝策略一起设计**。
4. **Android 特殊考虑**：
   - 移动端内存敏感，**宁可丢任务也不要 OOM** → 拒绝策略倾向 `DiscardPolicy`（可丢的埋点）/ 自定义策略（丢时上报监控）。
   - **绝不要在主线程用 `CallerRunsPolicy`** —— 会让主线程执行耗时任务，直接 ANR。
5. **监控建议**：给线程池加 `beforeExecute` / `afterExecute` / `terminated` 钩子，上报**活跃线程数、队列大小、完成数、拒绝数**，队列长期接近满就是过载信号。
6. **面试话术**：**"Executors 的工厂方法用无界队列或无上限线程数，任务堆积会 OOM。生产上自己 new ThreadPoolExecutor，用有界队列 + 合理 max + 明确拒绝策略，并加监控钩子。"**

## 关联笔记

- [[../Handler机制原理]]：Looper / MessageQueue / Handler 的底层实现
- [[Kotlin协程]]：协程如何取代线程 + Handler 的组合
- [[RxJava]]：响应式方案与背压、线程调度
- [[../网络框架/OkHttp]]：网络请求的线程调度实践
- [[../网络协议/WebSocket]]：心跳定时任务与线程管理
- [[../README]]：第 5 部分总览

## 参考资料

- Java 并发编程实战（Brian Goetz 等）
- Android 源码 `ThreadPoolExecutor` / `Executors` / `AsyncTask`（AOSP）：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/os/AsyncTask.java
- Android 线程池最佳实践文档：https://developer.android.com/reference/java/util/concurrent/ThreadPoolExecutor
- 《Android 高级编程》第 4 版 · 线程与异步任务章节
- Java Language Specification 第 17 章（Threads and Locks）：https://docs.oracle.com/javase/specs/jls/se17/html/jls-17.html
