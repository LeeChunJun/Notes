---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 中级
created: 2026-11-04
updated: 2026-11-04
related: "[[Lifecycle与ViewModel]], [[../架构模式/MVVM]], [[../README]]"
---

# Navigation

## 一句话定义

Navigation 是 Jetpack 的**导航框架**，用 `NavGraph`（导航图）+ `NavController`（导航控制器）+ `NavHost`（导航宿主）把"页面跳转"从 `startActivity` 的手工代码，变成**可视化配置 + 单例状态管理 + 类型安全传参**。

## 核心原理

### 1. 三个核心角色

| 角色 | 职责 | 说明 |
| --- | --- | --- |
| `NavController` | 记录**当前导航状态**（back stack） | 是**唯一的导航状态持有者**，负责 back stack 的压栈/出栈、状态保存与恢复 |
| `NavGraph` | 声明**有哪些目的地（destination）**及跳转关系 | 可用 XML（`nav_graph.xml`）或代码构建（Navigation Compose） |
| `NavHost` / `NavHostFragment` | 把 `NavController` 与 UI 绑定 | Compose 用 `NavHost`；View 用 `NavHostFragment` |

配套的 `NavDestination` 表示**一个具体页面**（一个 route / 一个 `@Composable` 函数 / 一个 Fragment 类）。

### 2. Back Stack 的原理

- `NavController` 内部维护一个 **`NavBackStack`**，栈中每个元素是 `NavBackStackEntry`。
- 每次 `navigate()` **压入**一个 entry；`popBackStack()` **弹出**一个 entry，并**回调该 entry 之前 entry 的视图重新显示**。
- **每个 `NavBackStackEntry` 各自持有一份 `SavedStateHandle`** → 这就是"用 `savedStateHandle` 传参"能自动保存恢复的原因。
- 系统配置变更（旋转）时，`NavController` 的状态由 `NavHostFragment` 通过 `onSaveInstanceState` 保存恢复 → **返回栈不会因旋转而丢失**。
- **进程被杀后恢复**：返回栈被恢复，但每个页面的 ViewModel **不会**恢复（除非用 `SavedStateHandle`）。

### 3. 两种传参方式

```kotlin
// 方式 A：传参给下一个页面
navController.navigate(R.id.detailFragment) {
    args = bundleOf("userId" to 42)
}

// 方式 B：取回上一个页面回传的结果
navController.previousBackStackEntry
    ?.savedStateHandle
    ?.getLiveData<String>("result_key")
    ?.observe(viewLifecycleOwner) { result -> /* 处理结果 */ }
```

- 方式 B 的原理：上一个页面的 entry 持有 `savedStateHandle`，下一个页面拿到 `previousBackStackEntry` 往里 `set`，上一个页面 observe 到变化。

### 4. Safe Args：类型安全的传参

没有 Safe Args 时，跳转参数只能靠 `bundleOf("userId" to 42)`——**key 拼错不会编译报错，是运行时的坑**。Safe Args 用注解生成方向类：

```kotlin
// 生成的代码（你只写注解）
navController.navigate(UserDetailFragmentArgs(userId = 42).toBundle())

// 取参数
private val args: UserDetailFragmentArgs by navArgs()
val userId = args.userId
```

Safe Args 是 **Android Gradle Plugin 的能力**：在 `nav_graph.xml` 里给 destination 声明 `android:id="@+id/userDetailFragment"` 后，AGP 会为它生成 `UserDetailFragmentArgs` / `UserDetailFragmentDirections`。

### 5. Navigation Compose

Compose 下的 Navigation 有两个体系：

| 体系 | 版本 | 特点 |
| --- | --- | --- |
| `androidx.navigation:navigation-compose:2.7.x`（官方） | 2.7.0 起 GA | 基于 `rememberNavController` + `NavHost` + `composable(route)` |
| `androidx.navigation:navigation-compose:2.8+`（新版 API） | 2.8.0 | 类型安全路由（`NavType` + `Serializable`/`Parcelable` 泛型）、`NavHost` 内联 `remember` |

2.8+ 的类型安全路由写法：

```kotlin
// 定义
@Serializable
data class UserDetail(val userId: String, val name: String)

NavHost(navController, startDestination = "home") {
    composable("home") { HomeScreen(onNavigate = { navController.navigate("detail/$id") }) }
    composable<UserDetail>("detail/{userId}/{name}") { entry ->
        val route = entry.toRoute<UserDetail>()
        DetailScreen(route.userId)
    }
}
```

> [!note]
> 官方 Compose 导航在 2.8 之前还有 `hilt-navigation-compose` 提供 `hiltViewModel()` 的导航集成（`NavBackStackEntry` 作为 `ViewModelStoreOwner`）。

### 6. `hiltViewModel()` 与导航作用域

```kotlin
composable("user/{id}") { entry ->
    // 该页面的 ViewModel，作用域 = 这个 NavBackStackEntry
    val vm: UserVM = hiltViewModel()
    // 也能拿到导航参数
    val id = entry.arguments?.getString("id")
}
```

- **作用域规则**：Composable 的 `hiltViewModel()` 默认绑定到**最近的 `ViewModelStoreOwner`**，在 Navigation Compose 中就是该 `NavBackStackEntry`。
- 页面被 pop 出栈 → entry 被销毁 → ViewModel 被清除 → `viewModelScope` 取消 → 不会泄漏。
- **共享 ViewModel**：需要父级作用域时用 `hiltViewModel(viewModelStoreOwner = navController.getBackStackEntry("parent"))` 或 Activity 作用域（`activityViewModels()` 的 Compose 版本）。

### 7. 常见导航操作

```kotlin
navController.navigate(route)                       // 压栈
navController.navigate(route) {
    popUpTo("home") { inclusive = true }            // 弹到 home（不含）
    launchSingleTop = true                          // 目标已在栈顶则不重复压栈
    restoreState = true                             // 恢复之前保存的该目的地状态
}
navController.popBackStack()                        // 返回
navController.popBackStack("home", inclusive = false)// 返回到指定页
navController.navigateUp()                          // 系统返回键行为（等同 pop）
```

> [!tip] 底部导航 + 多返回栈
> 配合 `saveState` / `restoreState` 可以做到"每个 Tab 保留各自的返回栈"：
> ```kotlin
> navController.navigate(tab.route) {
>     popUpTo(navController.graph.findStartDestination().id) { saveState = true }
>     launchSingleTop = true
>     restoreState = true
> }
> ```

## 代码示例

### 1. View 体系：nav_graph.xml

```xml
<!-- res/navigation/nav_graph.xml -->
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_graph"
    app:startDestination="@id/homeFragment">

    <fragment
        android:id="@+id/homeFragment"
        android:name="com.example.home.HomeFragment"
        android:label="@string/home">
        <action
            android:id="@+id/action_home_to_detail"
            app:destination="@id/userDetailFragment" />
    </fragment>

    <fragment
        android:id="@+id/userDetailFragment"
        android:name="com.example.detail.UserDetailFragment"
        android:label="@string/detail">
        <argument
            android:name="userId"
            app:argType="string" />
    </fragment>
</navigation>
```

```kotlin
// Activity 中挂载
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // activity_main.xml 中放 <androidx.fragment.app.FragmentContainerView
        //   android:name="androidx.navigation.fragment.NavHostFragment"
        //   app:defaultNavHost="true"
        //   app:navGraph="@navigation/nav_graph" />
    }
}
```

```kotlin
// 跳转与取参
findNavController().navigate(
    R.id.action_home_to_detail,
    bundleOf("userId" to "42")
)

// 接收参数
private val args: UserDetailFragmentArgs by navArgs()
val userId: String = args.userId
```

### 2. Navigation Compose 完整示例

```kotlin
@Composable
fun AppNavGraph(navController: NavHostController = rememberNavController()) {

    NavHost(navController = navController, startDestination = "home") {

        composable("home") {
            HomeScreen(
                onClickUser = { id -> navController.navigate("detail/$id") },
                onClickSettings = { navController.navigate("settings") }
            )
        }

        composable(
            route = "detail/{userId}",
            arguments = listOf(navArgument("userId") { type = NavType.StringType })
        ) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId").orEmpty()
            val vm: UserDetailViewModel = hiltViewModel()   // 作用域 = 该 entry
            UserDetailScreen(
                userId = userId,
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
```

### 3. 传回结果（Fragment → Fragment）

```kotlin
// 发起方
navController.previousBackStackEntry?.savedStateHandle?.set("selected_id", 42)
navController.popBackStack()

// 接收方
navController.currentBackStackEntry?.savedStateHandle
    ?.getLiveData<Int>("selected_id")
    ?.observe(viewLifecycleOwner) { id -> /* 使用 */ }
```

### 4. 底部导航 + 多返回栈

```kotlin
@Composable
fun AppScaffold() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(
                    Tab("home", "首页"),
                    Tab("profile", "我的")
                ).forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, null) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding -> AppNavGraph(navController) }
}
```

### 5. 深链接（Deep Link）

```xml
<fragment
    android:id="@+id/userDetailFragment"
    android:name="com.example.detail.UserDetailFragment">
    <deepLink app:uri="myapp://user/{userId}" />
</fragment>
```

```kotlin
// 手动触发深链接
val intent = Intent(Intent.ACTION_VIEW, Uri.parse("myapp://user/42"))
navController.handleDeepLink(intent)
```

## 常见问题 / 面试要点

**读者问：Navigation 框架解决了什么问题？不用它会怎样？**

**AI答：**

1. **不用 Navigation 的痛点**：
   - `startActivity` 手写 Intent，**跳转关系散落全项目**，没人能一眼看清"从哪些页面能到哪些页面"；
   - **返回值靠 `startActivityForResult`**，代码冗长且已废弃；
   - **返回栈不可控**：想"返回时跳过某些页面"要写一堆 `FLAG_ACTIVITY_CLEAR_TOP`；
   - **进程被杀恢复后返回栈丢失**，回到首页（除非自己存 `savedInstanceState`）；
   - **参数传递没有类型安全**，key 写错运行时才报错。
2. **Navigation 的解决方案**：
   - **`nav_graph.xml` 可视化描述整个应用的页面拓扑**，IDE 有可视化编辑器，review 时一眼看清；
   - **`NavController` 统一管理 back stack**，支持 `popUpTo`、`launchSingleTop`、`restoreState` 等声明式操作；
   - **自动保存/恢复返回栈**（配置变更与进程恢复）；
   - **类型安全传参**（Safe Args）与 **结果回传**（`savedStateHandle`）；
   - **每个目的地有独立的 `NavBackStackEntry`**，天然承载该页面的 ViewModel 与 SavedStateHandle。
3. **面试一句话**："Navigation 把'跳转'从**命令式代码**变成了**声明式图结构**，并统一了返回栈、参数与状态恢复。"

---

**读者问：Navigation 的返回栈（back stack）是怎么工作的？**

**AI答：**

1. **数据结构**：`NavController` 内部持有 `NavBackStack`（一个 `ArrayDeque<NavBackStackEntry>`）。
2. **压栈**：`navigate()` 创建一个新的 `NavBackStackEntry`（对应一个 destination）并加入栈顶。
3. **出栈**：`popBackStack()` 移除栈顶 entry，栈顶变为上一个 entry，框架通知其视图"重新显示"（重新走 `onStart`/`onResume`）。
4. **状态保存**：
   - 每个 entry 有独立的 `SavedStateHandle` 与 `ViewModelStore`；
   - **配置变更**：`NavHostFragment` 把整个 `NavController` 的状态存入 `onSaveInstanceState`，重建后恢复 → 旋转屏幕返回栈不丢；
   - **进程恢复**：同样从 Bundle 恢复，但**每个页面的 ViewModel 不会恢复**（ViewModel 不是持久化的），只有 `SavedStateHandle` 的内容能恢复。
5. **多返回栈**：`popUpTo(id) { saveState = true }` + `restoreState = true` 可以让每个 Tab 拥有独立的返回栈（切换 Tab 时保存/恢复）。
6. **面试加分**：能说出"`NavBackStackEntry` 是 ViewModel 和 SavedStateHandle 的作用域边界"这句话，就说明你理解到实现层面了。

---

**读者问：Safe Args 是什么？不加会有什么风险？**

**AI答：**

1. **Safe Args 是什么**：AGP 的一个插件，根据 `nav_graph.xml` 里 destination 的 `android:id` 和 `<argument>` 声明**生成类型安全的参数类**。
2. **不加的风险**：
   - key 字符串手写：`bundleOf("userId" to id)`，**写错 key 编译通过、运行时取不到**（拿到 null），而且导航到下一个页面才崩，排查成本高；
   - 类型不安全：`putInt` 传了 `String`，运行时 `ClassCastException`；
   - 参数名重构时无法全局检查。
3. **Safe Args 的收益**：
   - 编译期就能发现 key 拼错、类型不匹配；
   - IDE 里跳转代码有类型提示；
   - 参数有默认值（`android:defaultValue`）时自动处理。
4. **使用方式**：在模块的 `build.gradle.kts` 里启用插件：

```kotlin
plugins {
    id("com.android.application")
    id("androidx.navigation.safeargs.kotlin")
}
```

5. **Compose 2.8+ 的替代方案**：官方 Compose 导航开始支持**类型安全路由**（`@Serializable data class` + `toRoute<T>()`），本质上达到了同样的目标。

---

**读者问：导航时怎么传参数？页面之间怎么互相传结果？**

**AI答：**

1. **向下传参（三种）**：
   - **Bundle**：`navigate(R.id.x, bundleOf("id" to 1))` + `arguments?.getString("id")`（最原始）。
   - **Safe Args**：`navigate(UserDetailArgs(id = 1).toBundle())` + `by navArgs()`（类型安全，推荐）。
   - **类型安全路由**（Compose 2.8+）：`navigate("detail/1")` + `entry.toRoute<UserDetail>()`（推荐）。
2. **向上回传结果（推荐 `savedStateHandle`）**：

```kotlin
// 接收方（B 页面）写回给 A
navController.previousBackStackEntry?.savedStateHandle?.set("result", "ok")
navController.popBackStack()

// 发起方（A 页面）监听
navController.currentBackStackEntry?.savedStateHandle
    ?.getLiveData<String>("result")
    ?.observe(viewLifecycleOwner) { /* 处理 */ }
```

3. **原理**：结果写入的是**上一个 entry** 的 `SavedStateHandle`，而 A 页面的 ViewModel 也能通过 `SavedStateHandle` 拿到同一个流（Compose 导航 2.8 提供 `NavBackStackEntry` 传递到 ViewModel 的能力）。
4. **不推荐**的做法：全局单例、EventBus、`startActivityForResult`（已废弃）、把结果塞进 Application 静态字段。
5. **注意**：参数里不要直接放**大对象**（Bitmap、列表），跨进程/恢复时会出问题；只传 ID，回页面自己查。

---

**读者问：`NavController` 和 `ViewModel` 的生命周期是怎么绑定的？页面退出后 ViewModel 会被清吗？**

**AI答：**

1. **绑定关系**：在 Navigation Compose 中，`NavHost` 的每个 `composable` 都对应一个 `NavBackStackEntry`；`NavBackStackEntry` 实现了 `ViewModelStoreOwner` 和 `SavedStateRegistryOwner`。
2. **作用域**：
   - `hiltViewModel()` 在 Composable 内调用时，默认绑定到**最近的 `ViewModelStoreOwner`** → 就是该页面的 `NavBackStackEntry`。
   - 因此**同一个页面被多次 push（不同参数）会得到不同的 ViewModel 实例**（不同 entry），这通常正是期望行为。
3. **清理时机**：
   - 页面被 **pop 出栈** → entry 被移除 → 其 `ViewModelStore.clear()` 被调用 → **ViewModel 的 `onCleared()` 触发** → `viewModelScope` 取消 → 协程结束、请求中断。
   - **配置变更**（旋转）→ entry 不销毁 → **ViewModel 保留**（这是核心价值）。
4. **共享 ViewModel**（父页面向子页面传数据）：

```kotlin
// 子页面拿到父级作用域的 ViewModel
val parentVm: ParentVM = hiltViewModel(
    viewModelStoreOwner = navController.getBackStackEntry("parentRoute")
)
```

5. **常见坑**：
   - 在 Composable 里用 `viewModel()`（不带 `hiltViewModel`）时，需确保 `ViewModelStoreOwner` 正确，否则会每次重组都新建实例。
   - **手动 `popBackStack` 后再操作该页面的 ViewModel 会拿到已清除的实例**（`onCleared` 已执行）。

---

**读者问：Navigation Compose 和 View 体系的 Navigation 能混用吗？需要全部迁移吗？**

**AI答：**

1. **可以混用**：老 Activity/Fragment 里用 `NavHostFragment`，新写的 Compose 页面可以作为 `composable` 目的地直接加入同一个 `nav_graph.xml`（`navigation-compose` 官方支持 Fragment 与 Composable 混合）。
2. **不需要全量迁移**：
   - 逐个把 Fragment 换成 Compose 屏幕，导航图里对应节点从 `<fragment>` 改成 `<composable>`；
   - 保留 `NavController` 作为唯一的导航状态持有者，避免出现两套导航栈。
3. **迁移时的坑**：
   - `hiltViewModel()` 与 `hilt-navigation-compose` 版本要匹配；
   - 老 Fragment 收到参数的方式（`arguments`）与 Compose 目的地（`entry.arguments`）要区分；
   - 同一个 `NavController` 不要同时被两个 `NavHost` 承载（状态会互相干扰）。
4. **面试表达**："Navigation 的本质是**导航状态管理**，与 UI 技术栈无关。Compose 只是它的一个渲染后端，所以可以渐进迁移。"

## 关联笔记

- [[Lifecycle与ViewModel]]：`NavBackStackEntry` 作为 ViewModel 作用域
- [[LiveData与StateFlow]]：`savedStateHandle` 与结果回传
- [[../架构模式/MVVM]]：导航事件走 Effect 通道的实现
- [[../README]]：第 7 部分总览

## 参考资料

- Android 官方 · Navigation 概览：https://developer.android.com/guide/navigation
- Android 官方 · Navigation Compose：https://developer.android.com/develop/ui/compose/navigation
- Android 官方 · Safe Args：https://developer.android.com/guide/navigation/safe-args
- Android 官方 · 用 Navigation 传数据：https://developer.android.com/develop/ui/compose/navigation#pass-data
