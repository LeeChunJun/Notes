---
tags: [android]
part: 第5部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[../README]]"
---

# RxJava

## 一句话定义

RxJava 是 **Reactive Extensions 的 Java 实现**：把数据/事件建模成**数据流（Stream）**，在其上挂载**操作符（Operator）**形成**响应式链**，由**订阅（Subscribe）**触发执行。它最核心的价值不是"异步"，而是**用一条声明式的链把异步数据源、变换、错误处理、生命周期管理统一起来**。

## 核心原理

### 1. 核心概念

| 概念 | 说明 |
| --- | --- |
| **Observable\<T\>** | 数据流的生产者，可发出 `onNext(T)` / `onError(Throwable)` / `onComplete()` |
| **Observer\<T\>** | 数据的消费者，实现 `onNext` / `onError` / `onComplete` |
| **Subscriber** | 同时实现 `Observer` 和 `Subscription` 的对象（RxJava 2 起的接口） |
| **Subscription** | 订阅关系，持有 `Disposable`，调用 `dispose()` **取消订阅** |
| **Disposable** | 一次性资源（可释放），`dispose()` 后不再接收事件 |
| **Scheduler** | 调度器，决定"在哪个线程上执行" |
| **Operator** | 流变换，如 `map` / `filter` / `flatMap` |
| **函数式接口（SAM）** | `Function` / `BiFunction` / `Predicate` / `Consumer` / `Action` |

**三个信号（Signal）的严格语义**：

```text
onNext(T)   数据事件，可以发 0 次或多次
onError(E)  终止信号，只能发 0 次或 1 次，发了就终止流
onComplete() 终止信号，只能发 0 次或 1 次，发了就终止流
```

**规则**：
- `onError` 和 `onComplete` **互斥**——一条流最多终止一次。
- `onError` 之后**不会再有** `onNext`（下游收到的都是终止信号）。
- `subscribe()` **没有指定错误处理时，`onError` 会抛到线程的 `UncaughtExceptionHandler`** → **App 崩溃**。这是 Rx 最容易出事的点。

**冷流 vs 热流**：

| 维度 | 冷流（Cold） | 热流（Hot） |
| --- | --- | --- |
| 订阅时机 | **每个订阅者独立触发一次生产** | 生产与订阅解耦，先发的订阅者收不到 |
| 例子 | `Observable.just(x)`、`apiCall()`、`flow{}` | `PublishSubject`、`BehaviorSubject`、`ConnectableObservable` |
| 多次订阅 | 每个订阅者拿到**完整**一份数据 | 共享同一份数据，**后来者可能丢数据** |

```kotlin
// 冷流：每次 subscribe 都重新执行一次网络请求
val cold = Observable.fromCallable { api.load() }
cold.subscribe { ... }   // 请求 1
cold.subscribe { ... }   // 请求 2（又发了一次！）

// 热流：共享，一次生产多次消费
val hot = PublishSubject.create<String>()
hot.onNext("a")          // 此时还没订阅，没人收到
hot.subscribe { ... }    // 订阅后才有数据
```

### 2. 常用操作符分类

```text
【创建】    just / fromIterable / fromCallable / fromFuture / defer / interval / timer / range
            create / empty / error / never

【变换】    map / cast / flatMap / flatMapIterable / concatMap / mergeMap
            zip / combineLatest

【过滤】    filter / take / skip / takeWhile / skipWhile
            distinct / distinctUntilChanged / throttleFirst / debounce / sample

【组合】    merge / concat / zip / combineLatest / withLatestFrom
            startWith / switchMap

【消费】    subscribe / doOnSubscribe / doOnNext / doOnError / doFinally
            subscribeWith

【异常】    onErrorReturn / onErrorResumeNext / retry / retryWhen / catch

【生命周期】 takeUntil / takeWhile
```

**高频操作符详解**：

| 操作符 | 语义 | 典型场景 |
| --- | --- | --- |
| `map` | 一对一变换 | DTO → UI Model |
| `filter` | 条件过滤 | 过滤掉无效数据 |
| `flatMap` | 一对多，**不保证顺序** | 并发请求后合并 |
| `concatMap` | 一对多，**保证顺序** | 顺序执行的任务（如依次上传） |
| `mergeMap` | = `flatMap`，不保证顺序 | 并发请求合并 |
| `switchMap` | 一对多，**新值来时取消旧订阅** | **搜索框输入**（取消上一个请求） |
| `zip` | 按位置配对，**任一源结束则结束** | `List<User>` zip `List<Avatar>` |
| `combineLatest` | 组合**最新值**，任一源有值就发 | 表单联动（两个输入框） |
| `distinctUntilChanged` | 相邻去重 | 防止重复触发 |
| `debounce` | 防抖（静默 N ms 后发最后一个） | 搜索输入 |
| `throttleFirst` | 节流（周期内只发第一个） | 滚动加载、按钮防连点 |
| `sample` | 定期采样 | 实时数据刷新 UI |
| `takeUntil` | 另一个流发出时取消本流 | 生命周期绑定 |
| `retryWhen` | 带条件的重试 | 网络错误重试 N 次 |
| `doFinally` | 流终止时执行（无论成功失败） | 释放资源、结束 Loading |

**`switchMap` vs `flatMap` vs `concatMap`（面试高频）**：

```text
上游发出 A ──► A1 ... A2        switchMap: A2 到来时取消 A1
      B ──► B1 ... B2                  只保留 B1、B2
      C ──► C1

flatMap / mergeMap:  A1 A2 B1 B2 C1   （并发，顺序不定）
concatMap:           A1 A2 B1 B2 C1   （串行，严格有序）
```

| 场景 | 用哪个 | 原因 |
| --- | --- | --- |
| **搜索框输入** | `switchMap` | 取消过期请求，避免旧结果覆盖新结果 |
| **依次上传多个文件** | `concatMap` | 必须串行，顺序不能乱 |
| **并发拉取多个接口再合并** | `flatMap`/`mergeMap` | 最大化并发 |
| **表单：两个字段联动** | `combineLatest` | 组合两者最新值 |

### 3. 背压（Backpressure）

**背压是什么**：当**生产者比消费者快**时（如上游是一个快速发射的数据库查询，下游 UI 处理很慢），数据会**堆积在内部队列** → **内存溢出（OOM）**。背压机制就是**让下游能够向上游反馈"我处理不过来"**。

**RxJava 2 的三分法**：

| 类型 | 背压支持 | 说明 |
| --- | --- | --- |
| `Observable<T>` | ❌ **不支持** | 只能发 `onNext`，**无法请求更多** |
| `Flowable<T>` | ✅ 支持 | 基于 ` Reactive Streams` 规范，有 `request(n)` |
| `Single<T>` | ❌ | 只有一个结果（成功/失败） |
| `Maybe<T>` | ❌ | 0 或 1 个结果 |
| `Completable` | ❌ | 无结果，只有完成/失败 |

**三个背压策略操作符**：

| 操作符 | 行为 | 内存风险 | 适用 |
| --- | --- | --- | --- |
| `onBackpressureBuffer()` | **无界缓冲**，上游发多少缓冲多少 | ⚠️ **可能 OOM** | 下游最终能跟上 |
| `onBackpressureLatest()` | 只保留**最新一个**，丢弃旧的 | 低 | UI 实时刷新（如股票行情） |
| `onBackpressureDrop()` | **直接丢弃**超出缓冲区的数据 | 低 | 数据可丢（如埋点） |

**`MissingBackpressureException`（RxJava 2 特有，最经典的坑）**：

```text
Observable（无背压）
   │  内部用 onBackpressureBuffer 变成 Flowable
   ▼
Flowable
   │  上游实际是"同步阻塞"的（如 fromIterable 一个大 List）
   ▼
Subscriber 只 request(1)
   │
   ▼
上游继续发射 → 找不到对应的 request → 抛 MissingBackpressureException
```

**解决方案**：
```kotlin
// ① 手动指定请求量
flowable.subscribe(observer)          // 默认 request(Long.MAX_VALUE)
// ② 用 onBackpressureBuffer 兜底
flowable.onBackpressureBuffer().subscribe(observer)
// ③ 改用 Flowable 的正确构造方式（避免中间的 Observable→Flowable 转换）
```

**RxJava 1 → 2 的重要变化**：
- RxJava 1 的 `OnSubscribe` 概念被移除，改为 `Subscription` + `Disposable`。
- `Observable` 不再有背压 → 用 `Flowable`。
- `Subscriber` 必须实现 `Subscription`（用 `LambdaSubscriber` 或 `subscribe(Consumer, Consumer)` 简化）。
- `Action` 的 `call()` 改为 `run()`。

### 4. 线程调度

**两个核心操作符**：

| 操作符 | 作用范围 | 记忆法 |
| --- | --- | --- |
| **`subscribeOn`** | **上游**（数据源侧）的订阅和发射 | "**订阅**在哪发生" |
| **`observeOn`** | **下游**（观察者侧）的消费 | "**观察**在哪发生" |

```text
Observable.just(1, 2, 3)
    .map { transform(it) }        // 上游部分
    .subscribeOn(Schedulers.io()) // ★ 整个上游（含 map）在 IO 线程
    .observeOn(Schedulers.main()) // ★ 下游消费在主线程
    .subscribe { render(it) }
```

**关键理解**：

- `subscribeOn` 影响的是**它调用位置之前**的所有上游操作（包括数据源本身的订阅）。
- `observeOn` 影响的是**它调用位置之后**的所有下游操作。
- **多个 `subscribeOn` 中，只有最靠近数据源的那个生效**（因为它决定了订阅动作在哪发生）。
- **多个 `observeOn` 可以叠加**，各自管各自的下游段。

**常用 Scheduler**：

| Scheduler | 线程数 | 用途 |
| --- | --- | --- |
| `Schedulers.io()` | **无界（弹性）** | **IO 密集**：网络、磁盘。**Android 上首选** |
| `Schedulers.computation()` | **= CPU 核数** | **CPU 密集**：计算、加密、图像处理 |
| `Schedulers.single()` | 1 | 单线程，串行 |
| `Schedulers.newThread()` | 每次新建 | 很少用（每次都创建线程，浪费） |
| `Schedulers.trampoline()` | **当前线程** | 同步执行，不切线程（用于测试） |
| `AndroidSchedulers.mainThread()` | 1（主线程） | UI 更新（需 `rxandroid`） |
| `Schedulers.io()` vs `newSingleThread()` | — | `io` 复用线程，**不要用 `newSingleThread` 干 IO**（Android 上 `Schedulers.io()` 有特殊实现，会在 app 空闲时暂停线程以省电） |

> **Android 特有**：`Schedulers.io()` 在 Android 上是**特殊实现**（基于 `HandlerThread` + `ScheduledExecutorService`），**会检测 App 是否在前台**，不在前台时延迟执行任务以省电。`Schedulers.computation()` 是普通固定线程池。**这一点是面试加分项。**

**常见线程切换模板**：

```kotlin
// 网络请求 + 主线程消费
api.getUser(id)
    .subscribeOn(Schedulers.io())
    .observeOn(AndroidSchedulers.mainThread())
    .subscribe({ user -> binding.text.text = user.name },
               { e -> showError(e) })

// 数据处理在 CPU 线程
api.getRawData()
    .subscribeOn(Schedulers.io())
    .map { heavyTransform(it) }        // map 在 io 线程
    .observeOn(Schedulers.computation())
    .map { anotherTransform(it) }      // 这段在 computation 线程
    .observeOn(AndroidSchedulers.mainThread())
    .subscribe { ... }
```

### 5. 作用域管理与内存泄漏

**RxJava 的三大泄漏场景**：

1. **订阅未释放**：`subscribe()` 返回的 `Disposable` 没被 `dispose()` → 订阅关系一直持有 Observer → Observer 持有 Activity → Activity 泄漏。
2. **上游源永不终止**：`Observable.interval()`、`Subject` 等**永不完成**的流，订阅会**永久存在**。
3. **事件在页面销毁后才到达** → 回调操作已销毁的 View → 崩溃或泄漏。

**解决方案 1：`CompositeDisposable`（手动管理）**

```kotlin
class MyViewModel : ViewModel() {

    private val disposables = CompositeDisposable()

    fun load() {
        api.getUsers()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ ... }, { ... })
            .also { disposables.add(it) }      // ★ 收集起来
    }

    override fun onCleared() {
        disposables.clear()                     // ★ 统一释放
        super.onCleared()
    }
}
```

**解决方案 2：`autoDispose`（推荐，RxAndroid 3+ / RxJava 3）**

```kotlin
// 绑定到 View 的生命周期，View detach 时自动 dispose
api.getUsers()
    .observeOn(AndroidSchedulers.mainThread())
    .autoDispose(this)          // ★ this = View 或 ViewModel
    .subscribe({ ... }, { ... })
```

**解决方案 3：自定义 `takeUntil` 绑定生命周期**

```kotlin
api.getUsers()
    .takeUntil(lifecycleSubject)     // 生命周期结束时自动取消
    .subscribe(...)
```

**RxJava 3 的 `autoDispose` 集成**（RxJava 3.0+ 内置 `androidx.lifecycle` 支持）：

```kotlin
// build.gradle
implementation "io.reactivex.rxjava3:rxandroid:3.0.2"

// 用法
api.getUsers()
    .autoDispose(lifecycleScope)   // 或 CoroutineScope
    .subscribe(...)
```

### 6. RxJava 与协程 Flow 的对比

| 维度 | RxJava | Kotlin 协程 Flow |
| --- | --- | --- |
| **数据流类型** | `Observable` / `Flowable` | `Flow<T>` |
| **订阅** | `subscribe(observer)` | `collect { }` |
| **变换** | `map` | `map` |
| **流合并** | `flatMap` / `merge` | `flatMapMerge` / `merge` |
| **组合** | `zip` / `combineLatest` | `zip` / `combine` |
| **防抖** | `debounce` | `debounce` |
| **背压** | `Flowable` + `request(n)` | **原生支持**（`BufferOverflow` + `collectLatest`） |
| **取消** | `Disposable.dispose()` | **协程取消（结构化并发）** |
| **线程切换** | `subscribeOn` + `observeOn` | `flowOn` + `collect` 的上下文 |
| **错误处理** | `onError` 终止流 | **`catch` 终止流**；异常必须处理（否则取消作用域） |
| **异常传染** | 终止整个流 | 终止整个流（`catch` 可捕获） |
| **组合多个流** | `combineLatest` 等 | `combine` / `zip` |
| **学习成本** | 高（操作符多、概念多） | 低（贴近顺序代码） |
| **Android 生态** | RxJava + RxAndroid | `kotlinx-coroutines-android`（官方） |

**互转**（`kotlinx-coroutines-rx3`）：

```kotlin
// Flow → Observable
val observable: Observable<Int> = flow.asObservable()

// Observable → Flow
val flow: Flow<Int> = observable.asFlow()

// RxJava 3 的 Flowable → Flow
val flow: Flow<Int> = flowable.asFlow()

// Flow → Flowable
val flowable: Flowable<Int> = flow.asFlowable()
```

```kotlin
// dependencies
implementation "org.jetbrains.kotlinx:kotlinx-coroutines-rx3:1.8.1"
```

**选型建议**：
- **新项目 → 协程 Flow**。官方支持、AndroidX 全面集成（`repeatOnLifecycle`、`collectAsStateWithLifecycle`）、学习成本低、错误处理更安全。
- **老项目 → 继续 RxJava**，用 `asFlow()` 桥接，渐进式迁移。
- **RxJava 的不可替代价值**在**背压的严格语义**和**成熟的操作符生态**（对于高频数据流如音视频、行情），但绝大多数业务场景协程已经够用。

### 7. RxJava 的"坑"清单

| 坑 | 说明 | 解决 |
| --- | --- | --- |
| **未处理 `onError` → 崩溃** | `subscribe()` 不带错误处理时，`onError` 抛到 `UncaughtExceptionHandler` | 永远传 `onError`；或用 `subscribeOnErrorResume` |
| **忘记 `dispose()`** | 泄漏 | `CompositeDisposable` / `autoDispose` |
| **`observeOn` 位置错误** | `observeOn` 之后的操作在主线程执行，若那里有耗时操作 → ANR | 耗时操作放 `observeOn` **之前** |
| **RxJava 2 的 `MissingBackpressureException`** | `Observable` 转 `Flowable` 时 | 用 `onBackpressureBuffer()` 或改用 `Flowable` |
| **嵌套订阅** | 订阅套订阅难以追踪 | 优先用操作符组合（`flatMap`） |
| **`interval` + `take` 的定时泄漏** | `interval` 永不结束 | `take(n)` / `takeUntil` |
| **主线程订阅 + 同步源** | `Observable.just()` 在主线程订阅会立即执行 | 数据源用 `subscribeOn(io)` |
| **`Schedulers.newSingleThread()` 干 IO** | 每次新建线程，慢且耗电 | 用 `Schedulers.io()` |

## 代码示例

### 1. 标准网络请求封装（统一错误处理 + 生命周期）

```kotlin
object Api {

    /** 统一把 HTTP/业务错误转成领域异常 */
    sealed class ApiException(message: String) : Exception(message) {
        class Network(message: String) : ApiException(message)
        class Http(val code: Int, message: String) : ApiException(message)
        class Business(val bizCode: Int, message: String) : ApiException(message)
    }

    fun <T : Any> request(
        source: () -> Response<T>,
        onSuccess: (T) -> Unit,
        onError: (Throwable) -> Unit
    ): Disposable {
        return Observable.fromCallable { source() }
            .subscribeOn(Schedulers.io())            // 上游在 IO 线程
            .map { response ->
                if (!response.isSuccessful) {
                    throw ApiException.Http(response.code(), "HTTP ${response.code()}")
                }
                val body = response.body()
                    ?: throw ApiException.Http(response.code(), "empty body")
                if (body.code != 0) {
                    throw ApiException.Business(body.code(), body.message)
                }
                body.data ?: throw ApiException.Http(response.code(), "null data")
            }
            .observeOn(AndroidSchedulers.mainThread())  // 下游在主线程
            .subscribe(
                { onSuccess(it) },
                { e -> onError(e) }                     // ★ 必须处理 onError，否则崩溃
            )
    }
}
```

### 2. Retrofit + RxJava CallAdapter

```kotlin
// 依赖
// implementation "com.squareup.retrofit2:adapter-rxjava3:2.9.0"

// 返回 Call<T> 而不是 suspend
interface RxApi {
    @GET("users/{id}")
    fun getUser(@Path("id") id: Long): Call<User>

    @GET("users")
    fun getUsers(@Query("page") page: Int): Call<List<User>>
}

// 使用
val disposable = api.getUser(42)
    .subscribeOn(Schedulers.io())
    .observeOn(AndroidSchedulers.mainThread())
    .subscribe({ user -> show(user) }, { e -> showError(e) })
```

```kotlin
// 或者直接返回 Observable
interface RxObservableApi {
    @GET("users/{id}")
    fun getUser(@Path("id") id: Long): Observable<User>
}
```

### 3. 搜索框防抖 + 取消旧请求（switchMap）

```kotlin
class SearchViewModel(private val api: Api) : ViewModel() {

    private val disposables = CompositeDisposable()
    private val queryFlow = MutableStateFlow("")

    val results: LiveData<Resource<List<Item>>> = queryFlow
        .debounce(300)                                    // 防抖 300ms
        .distinctUntilChanged()                           // 相同查询不重复请求
        .filter { it.isNotBlank() }
        .flatMapLatest { q ->                            // ★ switchMap 的等价物：取消旧请求
            api.getUsers(q)
                .map { Resource.Success(it) }
                .onErrorReturn { Resource.Error(it) }
                .subscribeOn(Schedulers.io())
        }
        .asLiveData()

    fun onQueryChanged(q: String) {
        queryFlow.value = q
    }

    override fun onCleared() {
        disposables.clear()
    }
}
```

### 4. ViewModel 中的作用域管理

```kotlin
class UserListViewModel(private val api: Api) : ViewModel() {

    private val disposables = CompositeDisposable()

    private val _uiState = MutableLiveData<UiState>()
    val uiState: LiveData<UiState> = _uiState

    fun refresh() {
        _uiState.value = UiState.Loading
        api.getUsers()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { list -> _uiState.value = UiState.Success(list) },
                { e -> _uiState.value = UiState.Error(e.message.orEmpty()) }
            )
            .also { disposables.add(it) }        // ★ 收集
    }

    override fun onCleared() {
        disposables.clear()                     // ★ 释放
        super.onCleared()
    }
}
```

### 5. 组合多个流：zip vs combineLatest

```kotlin
// zip：按位置配对，第一个流结束就整体结束
Observable.zip(
    Observable.fromIterable(userIds),
    Observable.fromIterable(avatars)
) { id, avatar -> UserWithAvatar(id, avatar) }
.subscribeOn(Schedulers.io())
.observeOn(AndroidSchedulers.mainThread())
.subscribe { ... }

// combineLatest：组合各自最新值，任一源有值就发
combineLatest(
    Observable.just("搜索词"),
    Observable.just(50)
) { keyword, count -> "$keyword-$count" }
.subscribe { ... }
```

```kotlin
// Flow 的等价写法（更直观）
combine(flowOf("搜索词"), flowOf(50)) { k, c -> "$k-$c" }.collect { }
zip(flowOf(1, 2, 3), flowOf("a", "b", "c")) { n, s -> "$n$s" }.collect { }
```

### 6. 并发请求 + 错误隔离

```kotlin
// 并发拉取多个接口，flatMap 合并
Observable.zip(
    api.getUserInfo().subscribeOn(Schedulers.io()),
    api.getOrders().subscribeOn(Schedulers.io()),
    api.getMessages().subscribeOn(Schedulers.io())
) { user, orders, messages -> HomeData(user, orders, messages) }
    .observeOn(AndroidSchedulers.mainThread())
    .subscribe({ data -> showHome(data) }, { e -> showError(e) })

// 需要"部分失败也能展示"时，用 onErrorReturnItem 隔离单个错误
api.getOrders()
    .onErrorReturnItem(emptyList())
    .subscribeOn(Schedulers.io())
    .observeOn(AndroidSchedulers.mainThread())
    .subscribe { orders -> showOrders(orders) }
```

### 7. 背压处理

```kotlin
// 从一个生产者快速的流（如数据库查询）转换时，必须处理背压
Observable.fromIterable(hugeList)              // 冷流，会一次性发射全部
    .onBackpressureBuffer()                     // 无界缓冲（可能 OOM）
    .subscribeOn(Schedulers.computation())
    .observeOn(AndroidSchedulers.mainThread())
    .subscribe { items -> adapter.submitList(items) }

// 实时行情：只保留最新值
Flowable.interval(0, 1, TimeUnit.SECONDS)
    .map { fetchPrice() }
    .onBackpressureLatest()                     // 只保留最新
    .observeOn(AndroidSchedulers.mainThread())
    .subscribe { price -> binding.price.text = price.toString() }
```

### 8. 重试与错误恢复

```kotlin
api.getUsers()
    .subscribeOn(Schedulers.io())
    .retryWhen { errors ->
        errors
            .zipWith(Observable.range(1, 3)) { error, attempt -> error to attempt }
            .flatMap { (error, attempt) ->
                if (error is IOException && attempt < 3) {
                    Observable.timer(attempt.toLong(), TimeUnit.SECONDS)   // 指数退避
                } else {
                    Observable.error(error)
                }
            }
    }
    .observeOn(AndroidSchedulers.mainThread())
    .subscribe({ list -> show(list) }, { e -> showError(e) })
```

## 常见问题 / 面试要点

**读者问：Flowable 和 Observable 有什么区别？什么时候用 Flowable？**

**AI答：**

1. **本质区别：背压（Backpressure）**。
   - **`Observable`**：RxJava 2 起**不支持背压**。它的 `onNext` 是"推送"语义——上游发多少就发多少，**下游无法告诉上游"慢一点"**。
   - **`Flowable`**：实现了 **Reactive Streams** 规范，支持 `request(n)`，下游能**显式请求**数据量，上游据此控制发射速度。
2. **为什么会抛 `MissingBackpressureException`**：
   ```
   Observable（无背压）
      │  subscribeOn/observeOn 等操作符把它转成 Flowable
      ▼
   Flowable 内部用 onBackpressureBuffer 缓冲
      ▼
   但如果中间的源头是"同步阻塞"且发射量巨大（如 fromIterable(百万条)），
   而 Subscriber 只 request(1)，
      ▼
   上游继续发 → 无对应 request → MissingBackpressureException
   ```
3. **三个背压策略**：
   - `onBackpressureBuffer()`：无界缓冲，**有 OOM 风险**。
   - `onBackpressureLatest()`：只保留最新，**适合 UI 实时刷新**（行情、位置）。
   - `onBackpressureDrop()`：直接丢弃，**适合可丢的数据**（埋点、日志）。
4. **选型建议**：
   - **数据源本身有明确速率且消费能力匹配**（网络请求、DB 查询）→ **`Observable` 足够**。
   - **数据源可能突发高速**（传感器、高频轮询、大列表遍历）→ **`Flowable`** + 明确的背压策略。
   - **Android 业务绝大多数场景**：`Observable` + `subscribeOn(io)` + `observeOn(main)` 就够了。
5. **RxJava 3 的对应关系**：`Observable` → 仍无背压；`Flowable` → 支持背压；新增 `Publisher` 接口。
6. **面试话术**：**"Flowable 支持背压，Observable 不支持。RxJava 2 把 Observable 改成无背压后，很多场景会抛 MissingBackpressureException。实时数据用 Flowable + onBackpressureLatest，普通网络请求用 Observable 就够。"**

---

**读者问：zip 和 combineLatest 有什么区别？**

**AI答：**

1. **`zip`**：**按位置配对**。它会等两个流**各自发出第 N 个值**，然后把它们**组合成一个**发出。
   - **任一源 `onComplete` → 整个 zip 立即完成**（多出来的不再等）。
   - **示例**：`zip(flowOf(1,2,3), flowOf("a","b","c"))` → `(1,"a"), (2,"b"), (3,"c")`。
   - **比喻**：**拉链**，两边齿对齿咬合。
2. **`combineLatest`**：**组合各自最新的值**。任意一个源发出新值，就用**它 + 另一方当前的最新值**发出一个新组合。
   - **不会因为某个源完成而结束**（完成的那个源贡献它的最后一个值）。
   - **示例**：`combineLatest(flowOf(1,2,3), flowOf("a"))` → `(1,"a"), (2,"a"), (3,"a")`。
   - **比喻**：**调音台**，每个推子位置对应当前增益。
3. **对比表**：
   | 维度 | zip | combineLatest |
   | --- | --- | --- |
   | 配对方式 | **按位置** | **按最新值** |
   | 触发条件 | **两方都到齐**才发 | **任一方有值**就发 |
   | 源完成时 | **整体结束** | 完成的源贡献最后值，其他继续 |
   | 长度不同 | **取较短的** | **取较长的**（完成的源保持最后值） |
   | 典型场景 | 头像+昵称、坐标+时间点 | **表单联动**、股票+汇率 |
4. **还有第三个：`withLatestFrom`**：
   - 行为类似 `combineLatest`，但**只有"主源"（调用它的那个）能触发发射**，其他源只是提供最新值 → **不会因为其他源变化而发射**。
   - 适合"主事件驱动，附带读取当前状态"。
5. **面试话术**：**"zip 是按位置配对，任一源结束整体就结束；combineLatest 是组合各源的最新值，任一源有值就发。表单两个输入框联动用 combineLatest，列表的头像和昵称对应用 zip。"**

---

**读者问：`subscribeOn` 和 `observeOn` 有什么区别？多个会怎样？**

**AI答：**

1. **`subscribeOn` 影响"上游"** —— 决定**订阅动作（数据源被启动）和上游发射**在哪个线程发生。
   - 记忆：**"在哪订阅"**。
   - **只影响它调用位置之前**的流部分。
   - **⚠️ 多个 `subscribeOn` 时，最靠近数据源（最下游位置的那个 `subscribeOn`）生效** —— 因为它决定了订阅动作在哪发生，而订阅动作决定了整个上游链的线程。
     ```kotlin
     Observable.just(1)
         .subscribeOn(Schedulers.io())     // 位置 A
         .map { it }
         .subscribeOn(Schedulers.computation())  // 位置 B —— ★ B 生效！
     ```
     因为 B 的 `subscribeOn` 会把**订阅动作**放到 computation 线程，而 A 的订阅动作被 B 的调用链包住，实际都跑在 computation。
     **结论：一般只写一个 `subscribeOn`。**
2. **`observeOn` 影响"下游"** —— 决定**事件被消费（Observer 收到 `onNext`）在哪个线程**。
   - 记忆：**"在哪观察"**。
   - 影响它**调用位置之后**的所有操作。
   - **可以叠加多个**，各自管各自的后半段：
     ```kotlin
     .observeOn(Schedulers.computation())   // 这段下游在 computation
     .map { heavy(it) }
     .observeOn(AndroidSchedulers.mainThread())  // 这段下游在主线程
     ```
3. **⚠️ 高频错误：`observeOn` 位置放错**：
   ```kotlin
   // ❌ 错误：heavy() 在主线程执行 → ANR
   api.getData()
       .subscribeOn(Schedulers.io())
       .observeOn(AndroidSchedulers.mainThread())
       .map { heavyTransform(it) }
       .subscribe { ... }

   // ✅ 正确：耗时操作在 observeOn(main) 之前
   api.getData()
       .subscribeOn(Schedulers.io())
       .map { heavyTransform(it) }
       .observeOn(AndroidSchedulers.mainThread())
       .subscribe { ... }
   ```
4. **Android 常用模板**：
   ```kotlin
   api.getData()
       .subscribeOn(Schedulers.io())              // 网络在 IO 线程
       .map { transform(it) }                    // 变换也在 IO 线程
       .observeOn(AndroidSchedulers.mainThread()) // 消费在主线程
       .subscribe(onNext, onError)
   ```
5. **`Schedulers.io()` 的 Android 特殊实现**（加分点）：Android 上的 `io()` **不是普通的无界线程池**，它会检测 App 是否在前台，**不在前台时延迟执行任务**以节省电量；且**任务量受控**，不会像 `newCachedThreadPool` 那样无限创建线程。
6. **面试话术**：**"subscribeOn 管上游（在哪订阅），observeOn 管下游（在哪消费）。多个 subscribeOn 只有最靠近数据源的那个生效；observeOn 可以叠加。Android 上要注意耗时操作要放在 observeOn(main) 之前，否则跑到主线程上会 ANR。"**

---

**读者问：RxJava 常见的内存泄漏有哪些？怎么避免？**

**AI答：**

1. **三类泄漏**：
   - **订阅未释放**：`subscribe()` 返回的 `Disposable` 不 `dispose()` → 订阅关系持有 Observer → Observer 持有 Activity → Activity 泄漏。
   - **源永不终止**：`Observable.interval()`、`Subject`、`BehaviorSubject` 等**没有 `onComplete`** 的流 → 订阅永久存在。
   - **回调到已销毁页面**：RxJava **不会自动感知生命周期**，页面销毁后事件仍会回调 → 操作已销毁的 View → 崩溃（`NullPointerException` / `IllegalStateException`）或泄漏。
2. **四种解决方案**：
   - **① `CompositeDisposable`（手动）**：
     ```kotlin
     private val disposables = CompositeDisposable()
     fun load() {
         api.getData().subscribe(...).also { disposables.add(it) }
     }
     override fun onCleared() { disposables.clear() }
     ```
   - **② `autoDispose`（推荐，RxJava 3 + RxAndroid）**：
     ```kotlin
     api.getData()
         .autoDispose(this)          // ★ this = View / ViewModel / LifecycleOwner
         .subscribe(onNext, onError)
     ```
     绑定到 `View` 时是 **`autoDispose(view)`**（在 `onDetachedFromWindow` 时释放），不是 `autoDispose(activity)`。
   - **③ `takeUntil` + 生命周期 Subject**：
     ```kotlin
     api.getData().takeUntil(destroySignal).subscribe(...)
     ```
   - **④ 换成协程**：`viewModelScope` / `lifecycleScope` 在销毁时**自动取消**，从根上消除这类泄漏。
3. **额外注意**：
   - `dispose()` 会**取消订阅，但不会取消底层已经发出的 HTTP 请求**（除非用 `Call.cancel()`）。协程 + `invokeOnCancellation { call.cancel() }` 能真正取消网络请求。
   - `Subject`（`PublishSubject`/`BehaviorSubject`）如果被多个订阅者共享，**最后一个订阅者 dispose 后 Subject 还在**，要注意其生命周期。
4. **面试话术**：**"RxJava 不感知生命周期，所以必须主动 dispose。用 CompositeDisposable 收集，onCleared 里 clear；或者用 RxJava 3 的 autoDispose 绑定 View/ViewModel。根本解决是换协程，viewModelScope 自动取消。"**

---

**读者问：RxJava 和 Kotlin 协程 Flow 怎么选？**

**AI答：**

1. **核心对比**：
   | 维度 | RxJava | 协程 Flow |
   | --- | --- | --- |
   | 订阅 | `subscribe(observer)` | `collect { }` |
   | 线程切换 | `subscribeOn` + `observeOn` | `flowOn` + 收集端的上下文 |
   | 取消 | 手动 `Disposable.dispose()` | **协程取消，结构化并发自动传播** |
   | 背压 | `Flowable` + `request(n)`，需要显式处理 | **原生支持**（缓冲策略 + `collectLatest`） |
   | 错误处理 | `onError` 终止流，**不处理会崩溃** | `catch` 终止流，**异常会取消作用域** |
   | 生态 | RxJava + RxAndroid | **官方 kotlinx-coroutines** |
   | 学习成本 | 高（操作符多、概念多、副作用难推理） | 低（结构化并发、顺序代码） |
2. **协程的优势**：
   - **结构化并发**：订阅的生命周期 = 协程作用域的生命周期，**不需要手动 dispose**。
   - **异常传播规则清晰**：异常沿协程作用域向上传播，`try/catch` 和 `catch {}` 行为明确。
   - **AndroidX 集成**：`repeatOnLifecycle`、`collectAsStateWithLifecycle` 都是官方 API。
   - **与 Retrofit 天然契合**：Retrofit 2.6+ 原生支持 `suspend`。
3. **RxJava 的优势（仍存在的场景）**：
   - **背压语义更严格**（`Flowable` 的 `request(n)` 是 Reactive Streams 标准）。
   - **操作符生态更成熟**（复杂的时间窗口聚合、窗口化等）。
   - **老项目存量**，改造有风险。
4. **选型建议**：
   - **新项目 → 协程 Flow**（无争议）。
   - **老项目 → 渐进迁移**：用 `kotlinx-coroutines-rx3` 的 `asFlow()` / `asObservable()` 桥接。
   - **中间态**：Retrofit 同时支持 `suspend` 和 `Call<T>`，新接口用 `suspend`，老接口保留 `Call`。
5. **互转 API**：
   ```kotlin
   val obs: Observable<Int> = flow.asObservable()
   val fl:  Flow<Int>    = observable.asFlow()
   val fl2: Flow<Int>    = flowable.asFlow()
   ```
6. **面试话术**：**"新项目我选协程 Flow：结构化并发自动管理取消、异常处理规则明确、和 Retrofit suspend 天然契合、AndroidX 官方集成。RxJava 在背压语义和操作符生态上仍有价值，老项目用 kotlinx-coroutines-rx3 桥接渐进迁移。"**

---

**读者问：为什么 RxJava 的 `onError` 不处理会崩溃？怎么优雅地处理错误？**

**AI答：**

1. **为什么崩溃**：
   - RxJava 的设计是**错误会终止整条流**。
   - `subscribe()` 有三个重载。如果用**只接受 `Consumer` 的那个**（或 Kotlin 的 `subscribe { }` 单参数形式），`onError` 会走 `LambdaObserver.onError` 的默认实现：
     ```java
     public void onError(Throwable t) {
         // 交给插件处理；默认直接抛
         RxJavaPlugins.onError(t);
     }
     ```
   - `RxJavaPlugins.onError` 默认会**调用当前线程的 `UncaughtExceptionHandler`** → **App 崩溃**。
2. **三种优雅处理方式**：
   - **① 显式传 `onError`**（最基本）：
     ```kotlin
     api.getData()
         .subscribe({ show(it) }, { e -> showError(e) })
     ```
     **注意**：Kotlin 的 `subscribe { }` 单参数 lambda **等价于只处理 `onNext`，错误会崩溃**！必须用 `subscribe(onNext, onError)` 双参数形式。
   - **② 链上游恢复**（在流内部消化错误）：
     ```kotlin
     api.getData()
         .onErrorReturnItem(emptyList())       // 用默认值替代错误
         .subscribe({ show(it) }, { /* 这里几乎不会触发 */ })
     ```
   - **③ 全局兜底**（应用级）：
     ```kotlin
     RxJavaPlugins.setErrorHandler { throwable ->
         // 上报到 Crashlytics / Bugly，全局兜底，避免"一处没处理就崩整个 App"
         CrashReporter.report(throwable)
     }
     ```
3. **错误处理的三种策略操作符**：
   - `onErrorReturn(item)`：把错误**转成一个正常值**（流继续）。
   - `onErrorResumeNext(nextObservable)`：出错时**切换到另一个流**（如降级到本地缓存）。
   - `retry()` / `retryWhen()`：**重试**（网络抖动场景）。
4. **分层建议**：
   - **数据层**：`onErrorResumeNext { localCacheFlow() }` —— 网络失败降级到本地缓存。
   - **UI 层**：`subscribe(onNext, onError)` —— 展示错误提示。
   - **全局**：`RxJavaPlugins.setErrorHandler` —— 最后一道防线。
5. **面试话术**：**"RxJava 的错误会终止整条流，如果 subscribe 没有传 onError，默认会抛到 UncaughtExceptionHandler 导致崩溃。Kotlin 的 subscribe { } 单参数写法就是这种陷阱。处理上：UI 层显式传 onError，数据层用 onErrorResumeNext 降级，全局用 RxJavaPlugins.setErrorHandler 兜底。"**

---

**读者问：RxJava 的操作符太多，记不住怎么办？有哪些必须掌握的？**

**AI答：**

1. **按"场景"记，而不是按名字记**：
   | 需求场景 | 必记操作符 |
   | --- | --- |
   | **数据变换** | `map` |
   | **过滤** | `filter`、`take`、`skip` |
   | **异步任务转流** | `fromCallable`、`defer`（每次订阅都重新执行） |
   | **流合并（并发）** | `flatMap` / `mergeMap` |
   | **流合并（顺序）** | `concatMap` |
   | **取消旧请求** | `switchMap` / `flatMapLatest` |
   | **多源组合** | `zip`、`combineLatest` |
   | **去重/防抖/节流** | `distinctUntilChanged`、`debounce`、`throttleFirst` |
   | **错误处理** | `onErrorReturn`、`onErrorResumeNext`、`retryWhen` |
   | **副作用** | `doOnSubscribe`、`doFinally` |
   | **生命周期** | `takeUntil` |
   | **定时** | `interval`、`timer` |
2. **最该深入的 5 个（面试会追问）**：
   - `switchMap`（取消旧请求，搜索场景）
   - `concatMap` vs `flatMap`（顺序 vs 并发）
   - `zip` vs `combineLatest`
   - `debounce` vs `throttleFirst`（防抖 vs 节流）
   - `takeUntil`（生命周期绑定）
3. **两个操作符的区分**：
   - **`debounce`（防抖）**：静默期结束后**发最后一个** → 适合"输入停止后才搜索"。
   - **`throttleFirst`（节流）**：周期内**只发第一个**，其余丢弃 → 适合"滚动到底加载"（但更常用 `throttle` + 尾部触发）。
4. **学习建议**：
   - 不要背 API，要**按场景分类记**。
   - 实际项目中**90% 只用到 10 个操作符**：`map`、`filter`、`flatMap`、`switchMap`、`debounce`、`distinctUntilChanged`、`zip`/`combineLatest`、`onErrorResumeNext`、`doFinally`、`takeUntil`。
   - 遇到复杂需求先想"**这个需求是变换、过滤、合并还是组合**"，再去找对应算子。
5. **面试话术**：**"操作符我不背名字，按场景归类：变换用 map、过滤用 filter、合并分 concatMap（顺序）和 flatMap（并发）、取消旧请求用 switchMap、多源组合用 zip/combineLatest、防抖去重用 debounce/distinctUntilChanged。实际项目常用的就十来个。"**

## 关联笔记

- [[Kotlin协程]]：协程与 Flow，RxJava 的现代替代
- [[../Handler机制原理]]：Android 消息机制与线程调度
- [[Thread与Handler]]：线程池、HandlerThread 等原生并发工具
- [[../网络框架/Retrofit]]：Retrofit 的 RxJava CallAdapter
- [[../网络框架/OkHttp]]：网络请求与线程调度
- [[../README]]：第 5 部分总览

## 参考资料

- RxJava 官方 Wiki（运算符列表）：https://github.com/ReactiveX/RxJava/wiki/Operators
- Reactive Streams 规范：https://www.reactive-streams.org/
- RxJava 3.x 文档：https://github.com/ReactiveX/RxJava
- Jake Wharton "RxAndroid 2.0" 与 "RxJava 2" 系列文章：https://medium.com/@jakewharton
- 《RxJava 响应式编程》（周志明 / Ivan Inderogin 相关中文资料）
- Kotlin 协程与 RxJava 互操作（kotlinx-coroutines-rx3）：https://github.com/Kotlin/kotlinx.coroutines/tree/master/kotlinx-coroutines-rx3
