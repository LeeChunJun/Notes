---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 中级
created: 2026-11-04
updated: 2026-11-04
related: "[[LiveData与StateFlow]], [[../架构模式/MVVM]], [[../README]]"
---

# Paging

## 一句话定义

Paging 3 是 Jetpack 提供的**分页数据加载库**，用 `PagingSource`（数据源）+ `PagingData`（分页结果流）+ `RemoteMediator`（远端与本地协调）三件套，把"分页列表"变成一条可以像 `Flow` 一样被消费的数据流，并自动处理分页边界、预加载、缓存与刷新策略。

## 核心原理

### 1. 三个核心类型

| 类型 | 角色 | 说明 |
| --- | --- | --- |
| `PagingSource<Key, Value>` | **数据源** | 抽象"从第几页取数据"，分页逻辑的唯一实现处 |
| `PagingData<Value>` | **分页结果** | 不可变的分页数据集合，由 `Pager` 生成，是 `Flow` |
| `RemoteMediator<Key, Value>` | **远端-本地协调** | 网络分页 + 本地缓存（如 Room）双层数据源时的粘合剂 |

### 2. `PagingSource` 的契约

```kotlin
abstract class PagingSource<Key : Any, Value : Any> {
    abstract suspend fun load(params: LoadParams<Key>): LoadResult<Key, Value>

    // 可选：key 的前后翻页方向
    open val jumpingSupported: Boolean = true
}
```

`LoadParams` 提供：

| 参数 | 含义 |
| --- | --- |
| `key` | 起始位置（第一页为 `null`，或用 `PagingSource` 自定义游标） |
| `loadSize` | 本次要加载的**数量**（由 `PagingConfig` 与预取距离算出） |
| `placeholdersEnabled` | 是否启用占位符 |
| `prevKey` / `nextKey` | 上一页 / 下一页的 key（`null` 表示没有） |

`LoadResult` 三种：

- `LoadResult.Page(data, prevKey, nextKey)`：成功。
- `LoadResult.Error(e)`：失败（可重试）。
- `LoadResult.Invalid`：**数据过期，需要重新从第一页加载**（用于"数据失效"场景，如登录态变化）。

### 3. `PagingConfig`：分页策略

```kotlin
val config = PagingConfig(
    pageSize = 20,             // 每页条数
    prefetchDistance = 10,      // 距底部还有 10 条时预取下一页
    initialLoadSize = 40,       // 首次加载的条数（默认 = pageSize * 3）
    enablePlaceholders = false, // 是否显示占位符（用于"未知总数"的场景）
    maxSize = 200,              // 最多保留在内存中的条数（超出丢弃旧页）
    jumpThreshold = 30          // 快速滑动时"跳跃"加载的阈值
)
```

关键概念：

- **`pageSize` 是"分页的粒度"**，不是"每次 UI 显示多少条"。`loadSize` 由框架计算。
- **`prefetchDistance`**：提前多少条触发预取。太小 → 请求频繁；太大 → 首屏后空白。
- **`maxSize`**：内存上限，框架会**丢弃远离当前位置的页面**，导致用户往回滑需要重新加载。
- **`jumpThreshold`**：用户快速滑动超过阈值时，触发"跳跃加载"（直接请求目标位置附近的数据），减少空白。

### 4. `Pager`：把 `PagingSource` 变成 `Flow<PagingData>`

```kotlin
val pagingData: Flow<PagingData<User>> = Pager(config = config) {
    UserPagingSource(api)
}.flow.cachedIn(viewModelScope)
```

- `Pager.flow` 是**冷流**：每收集一次就创建新的 `PagingSource` 实例并重新从第一页开始。
- **`cachedIn(scope)` 至关重要**：把分页数据缓存在给定的 scope 中，让**多个收集者共享同一个分页会话**（否则每个 `collect` 都会重新加载第一页）。

### 5. `RemoteMediator`：网络 + 本地的双层结构

```text
        ┌──────────────────────────────────────────┐
        │  RemoteMediator                            │
        │   initialize()   → 判断从哪一页开始          │
        │   load()         → 拉网络数据并写入本地      │
        └───────────────┬──────────────────────────┘
                        │ 写入
                        ▼
        ┌──────────────────────────────────────────┐
        │  Room (本地)  ← PagingSource (从本地读)     │
        │  UI 只订阅本地这一层                        │
        └──────────────────────────────────────────┘
```

优势：

- **UI 只观察本地数据库**，网络回来的数据写进 Room，UI 自动收到通知 → 天然"单一数据源"。
- 断网时仍能展示上次缓存的数据。
- 翻页时先读本地 → 本地没有再由 Mediator 去网络拉。

`RemoteMediator` 的两个关键方法：

```kotlin
override suspend fun initialize(): InitializeAction  // 返回 Start(键) / Append(键)
override suspend fun load(loadSize: Int, state: MutablePagingState<Int, User>): MediatorResult
```

- `initialize()` 返回 `InitializeAction.Start` 表示从头加载；返回 `Append(key)` 表示从某个 key 继续（例如离线时从本地最后一条接着拉）。
- `load()` 返回 `MediatorResult.Success(endOfPagination = Boolean)` 或 `MediatorResult.Error(e)`。

### 6. Paging 与 Compose / View 的对接

```kotlin
// Compose
val lazyPagingItems = vm.users.collectAsLazyPagingItems()

LazyColumn {
    items(lazyPagingItems.itemCount) { index ->
        val user = lazyPagingItems[index]
        if (user != null) UserItem(user)
        else Placeholder()   // 占位符（enablePlaceholders = true 时）
    }
    // 加载状态
    when (lazyPagingItems.loadState.refresh) {
        is LoadState.Loading -> item { CircularProgressIndicator() }
        is LoadState.Error -> item { RetryButton { lazyPagingItems.retry() } }
        else -> {}
    }
}

// View（RecyclerView + PagingDataAdapter）
adapter = UserAdapter().apply {
    loadStateFlow.collect { adapter.loadState = it }   // 显示脚部加载/错误
}
```

`LoadState` 三种：

| LoadState | 含义 | 处理 |
| --- | --- | --- |
| `refresh` | **首次加载或下拉刷新** | 显示整屏 Loading / 错误重试 |
| `append` | **加载更多**（滑到底部） | 显示列表底部 Loading / 错误重试 |
| `prepend` | 向前加载（列表顶部） | 少见（初始从中间开始加载时） |

## 代码示例

### 1. 基于网络分页的 `PagingSource`（页码 key）

```kotlin
class UserPagingSource(
    private val api: UserApi
) : PagingSource<Int, User>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, User> {
        val page = params.key ?: 1   // 第一页 key 为 null
        return try {
            val response = api.getUsers(page = page, size = params.loadSize)
            LoadResult.Page(
                data = response.items,
                prevKey = if (page == 1) null else page - 1,
                nextKey = if (response.hasMore) page + 1 else null
            )
        } catch (e: IOException) {
            LoadResult.Error(e)
        } catch (e: HttpException) {
            LoadResult.Error(e)
        }
    }

    // key 失效时（例如数据被删除），重新从头加载
    override fun invalidate() = Unit
}
```

### 2. 基于游标（cursor）的 `PagingSource`

```kotlin
class CursorPagingSource(private val api: UserApi) : PagingSource<String, User>() {
    override suspend fun load(params: LoadParams<String>): LoadResult<String, User> {
        val cursor = params.key
        val response = api.getUsers(cursor = cursor, size = params.loadSize)
        return LoadResult.Page(
            data = response.items,
            prevKey = null,
            nextKey = response.nextCursor   // 游标为 null 表示没有下一页
        )
    }
}
```

> 游标分页对**数据持续插入**的场景更稳定（页码分页在插入新数据时可能重复/遗漏）。

### 3. `Pager` + ViewModel

```kotlin
class UserListViewModel(
    private val repo: UserRepository
) : ViewModel() {

    val users: Flow<PagingData<User>> = Pager(
        config = PagingConfig(pageSize = 20, prefetchDistance = 10)
    ) {
        repo.pagingSource()
    }.flow
        .cachedIn(viewModelScope)   // 必须：多个收集者共享分页会话
}
```

### 4. `RemoteMediator`：网络 + Room 双层分页

```kotlin
// 1) Room 实体
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val name: String,
    val position: Int          // 记录服务端顺序
)

// 2) DAO 的 PagingSource
@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY position")
    fun pagingSource(): PagingSource<Int, UserEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(users: List<UserEntity>)

    @Query("DELETE FROM users")
    suspend fun clear()
}

// 3) RemoteMediator
@OptIn(ExperimentalPagingApi::class)
class UserRemoteMediator(
    private val api: UserApi,
    private val dao: UserDao
) : RemoteMediator<Int, UserEntity>() {

    override suspend fun initialize(): InitializeAction =
        try {
            // 首次进入：清空本地并从第一页开始
            dao.clear()
            InitializeAction.Start
        } catch (e: IOException) {
            InitializeAction.Start
        }

    override suspend fun load(
        loadSize: Int,
        state: MutablePagingState<Int, UserEntity>
    ): MediatorResult {
        val page = state.nextPageKey ?: return MediatorResult.Success(endOfPagination = true)
        return try {
            val response = api.getUsers(page = page, size = loadSize)
            dao.insertAll(response.items.map { it.toEntity(page) })
            MediatorResult.Success(endOfPagination = !response.hasMore)
        } catch (e: IOException) {
            MediatorResult.Error(e)
        }
    }
}

// 4) 组装
val users: Flow<PagingData<UserEntity>> = Pager(config = PagingConfig(pageSize = 20)) {
    dao.pagingSource()
}.flow
    .cachedIn(viewModelScope)

// Pager 的 flow 与 RemoteMediator 组合
val remoteMediator = UserRemoteMediator(api, dao)
```

> 注意：`Pager` 与 `RemoteMediator` 的组合方式在不同版本略有差异，主流做法是让 `RemoteMediator` 内部触发网络加载并写入 Room，`Pager` 只读 Room；`RemoteMediator` 通过 `Pager` 的 `remoteMediator` 参数或显式调用 `load` 关联。

### 5. Compose 端消费

```kotlin
@Composable
fun UserListRoute(vm: UserListViewModel = hiltViewModel()) {
    val items = vm.users.collectAsLazyPagingItems()

    LazyColumn(Modifier.fillMaxSize()) {
        items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
            val user = items[index]
            UserRow(user)
        }

        when (val state = items.loadState.append) {
            is LoadState.Loading -> item { LoadingRow() }
            is LoadState.Error -> item { ErrorRow(onRetry = { items.retry() }) }
            else -> {}
        }
    }

    // 刷新
    SwipeRefresh(
        isRefreshing = items.loadState.refresh is LoadState.Loading,
        onRefresh = { items.refresh() }
    ) { /* content */ }
}
```

### 6. 加载更多时的错误处理（面试常问）

```kotlin
when (val state = items.loadState.append) {
    is LoadState.Error -> {
        // 只在"追加"失败时展示行内重试，而不是整屏错误
        Button(onClick = { items.retry() }) { Text("加载失败，点击重试") }
    }
    else -> {}
}
```

## 常见问题 / 面试要点

**读者问：Paging 3 和以前用的什么分页方案有什么区别？**

**AI答：**

1. **旧方案的问题**：
   - 手动维护 `pageNum`、在 `onScroll` 里判断"快到底了"然后自己请求 → 逻辑散落、容易重复请求、边界难处理。
   - `RecyclerView` + `ListAdapter` 分页 → 没有统一抽象，每个项目写法都不一样。
   - 首屏/刷新/加载更多的状态要自己用多个 Boolean 管理 → 容易出现 `loading && error != null` 的脏状态。
2. **Paging 3 的改进**：
   - **统一抽象**：`PagingSource` 抽象数据来源，UI 只订阅 `Flow<PagingData>`。
   - **状态内建**：框架用 `CombinedLoadStates` 统一管理 `refresh`/`append`/`prepend` 三种加载状态。
   - **预加载与缓存**：内置 `prefetchDistance`、`maxSize`、`jumpThreshold`，并通过 `cachedIn` 实现分页会话共享。
   - **组合性**：`PagingSource`（数据）+ `RemoteMediator`（远端协调）+ `PagingData`（结果），可实现"网络 + 数据库"双层架构。
   - **可测试**：`PagingSource` 是纯逻辑类，可以直接写单测验证 `LoadResult`。
3. **一句话**：**Paging 3 把"分页"从一堆散落的 if-else 变成了一个可组合、可测试的数据流。**

---

**读者问：`cachedIn` 到底有什么作用？不加会怎样？**

**AI答：**

1. **作用**：`Pager.flow` 是**冷流**。`cachedIn(scope)` 会把生成的 `PagingData` **缓存在指定的 scope（通常是 `viewModelScope`）** 中，让所有收集者**共享同一个分页会话**（同一份已加载数据、同一组加载状态、同一份去重逻辑）。
2. **不加 `cachedIn` 的后果**：
   - **每次 `collect` 都重新从第一页开始**：如果 View 重新创建（旋转屏幕）或 Compose 重组导致重新订阅，列表会闪回顶部并重新请求第一页。
   - **重复请求**：多个收集者各自触发 `PagingSource.load`。
   - **加载状态丢失**：`LoadState` 回到初始值，UI 上"加载中"指示器乱闪。
3. **正确用法**：

```kotlin
class VM : ViewModel() {
    val users: Flow<PagingData<User>> = Pager(config) { source() }
        .flow
        .cachedIn(viewModelScope)   // 放在 ViewModel 里，不要放在 Composable 里
}
```

4. **常见错误**：
   - 在 Composable 里 `cachedIn(rememberCoroutineScope())` → 组合销毁后缓存丢失，等于没用。
   - `cachedIn` 的 scope 必须是**比 UI 生命周期更长**的作用域（`viewModelScope` / `applicationScope`）。

---

**读者问：`RemoteMediator` 解决什么问题？什么场景下需要它？**

**AI答：**

1. **问题场景**：列表数据既有**网络分页**又有**本地缓存**（Room）。如果 UI 直接订阅网络分页：
   - 离线打不开页面；
   - 网络慢时页面空白；
   - 想做"先展示缓存，再无缝更新网络数据"很难。
2. **`RemoteMediator` 的解法**：把职责拆成两层——
   - **UI 只订阅本地**（Room 的 `PagingSource`）；
   - **`RemoteMediator` 负责**：判断从哪一页开始拉网络（`initialize()`）、拉取并写入本地（`load()`）、在本地不足时触发网络请求。
   - 网络数据写进 Room → Room 发出 `InvalidationTracker` 通知 → PagingSource 重新发射 → **UI 自动刷新**。
3. **`initialize()` 的两种返回值**：
   - `InitializeAction.Start` —— 从头开始（通常同时清空本地）。
   - `InitializeAction.Append(key)` —— 从某个 key 继续（例如离线时从本地最大 key 接着拉）。
4. **典型场景**：离线可用的新闻/资讯/社交信息流、需要"预加载下一屏内容"的场景。
5. **代价**：复杂度明显上升（两张表/两个数据源/一致性维护），**只有确实需要离线或双层架构时才用**。简单场景直接 `Pager { NetworkPagingSource }` 即可。

---

**读者问：分页时如何处理"数据插入导致重复/错乱"？**

**AI答：**

1. **页码分页的问题**：用户在第 3 页时，服务器在第 1 页前插入了 2 条新数据 → 用户继续下拉时，按 `page=4` 请求，返回的是**新的第 4 页**，与已显示的第 4 页内容**重叠** → 列表出现重复项。
2. **解决方案**：
   - **游标分页（cursor）**：服务端返回 `nextCursor`（基于 `id` 或 `updated_at`），客户端只传游标 → 不受前面插入影响，是**最稳的做法**。
   - **去重**：Paging 3 内置 `placeholdersEnabled` 与基于 `itemKey` 的去重机制；Compose 用 `items(count, key = items.itemKey { it.id })` 提供稳定 key，帮助 LazyColumn 正确复用 item。
   - **本地库方案**：数据全部落 Room，用 `position` 或 `id` 排序，UI 只读本地 → 天然不会错乱（这也是 `RemoteMediator` 方案的附带好处）。
3. **补充**：给后端提要求时明确要 **`cursor` + `hasMore`**，而不是只给 `page` + `total`；`total` 在频繁插入的流式数据上也不可靠。

---

**读者问：`prefetchDistance`、`pageSize`、`initialLoadSize` 怎么设置？`

**AI答：**

1. **`pageSize`**：服务端单次返回的条数。设置成**后端推荐值**（如 20/50）。注意：这是**分页粒度**，不是"一屏显示多少条"。
2. **`initialLoadSize`**：首次加载的条数，默认 `pageSize * 3`。作用是**首屏尽量填满**，减少首屏白屏与后续请求次数。后端支持时建议设为"一屏能显示的条数 × 1.5"。
3. **`prefetchDistance`**：距列表末尾还剩多少条时**预取下一页**。
   - 太小（1~2）→ 请求频繁，网络抖动时用户频繁看到加载中；
   - 太大（≥ 一屏）→ 白屏时间变长，浪费流量；
   - **经验值：`pageSize` 的 0.5~1 倍**（如 pageSize=20 → prefetch=10）。
4. **`maxSize`**：内存中保留的最大条数，默认 `pageSize * 5 + 30`（Paging 3.2+ 默认 `maxSize` 无上限，需自行设置；设太小会导致用户回滑时重新加载）。
5. **`enablePlaceholders`**：只有当**总条数已知**且需要先展示骨架屏时才开；否则会先显示 N 个占位符，滑动过程中不断被替换 → 视觉抖动。
6. **调优方法**：用 `LoadState` 埋点统计预取时机，看首屏时间与请求次数；用 Android Studio 网络面板确认请求是否与预取时机吻合。

## 关联笔记

- [[LiveData与StateFlow]]：分页数据也是 Flow 的一种
- [[Lifecycle与ViewModel]]：`cachedIn(viewModelScope)` 依赖 ViewModel 作用域
- [[../架构模式/MVVM]]：分页逻辑放在 Repository 层
- [[../测试/单元测试]]：`PagingSource` 的单测
- [[../README]]：第 7 部分总览

## 参考资料

- Android 官方 · Paging 3 概览：https://developer.android.com/topic/libraries/architecture/paging/v3-overview
- Android 官方 · Paging 3 指南：https://developer.android.com/topic/libraries/architecture/paging/v3-guide
- Android 官方 · `RemoteMediator`：https://developer.android.com/topic/libraries/architecture/paging/v3-remote-mediator
