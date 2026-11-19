---
tags: [android]
part: 第3部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[声明式UI与重组]], [[状态管理]], [[../README]]"
---

# Compose与View互操作

## 一句话定义

Compose 与 View 体系之间有两个方向的桥梁：**`AndroidView`** 让 Compose 里嵌入传统 View，**`ComposeView`** 让传统 View 里嵌入 Compose。互操作的核心难点不是"能不能嵌"，而是**生命周期对齐**（`ViewCompositionStrategy`）、**状态同步**（View → Compose 的数据回流）和**主题一致性**。

## 核心原理

### 1. 两个方向的桥梁

```text
Compose 中嵌入 View                        View 中嵌入 Compose
────────────────────                       ─────────────────────
AndroidView(factory = { ... })              val composeView = ComposeView(context)
  ├─ factory: 创建 View 实例                composeView.setContent { MyScreen() }
  ├─ modifier: 参与 Compose 布局            // 或用 AbstractComposeView 子类
  └─ update: 状态变化时更新 View            setContentView(composeView)
```

**双向互操作示意**：

```text
┌──────────────────────┐         ┌──────────────────────┐
│      Compose 树       │         │       View 树        │
│  ┌────────────────┐  │         │  ┌────────────────┐  │
│  │ AndroidView    │  │         │  │  ComposeView   │  │
│  │  ┌──────────┐  │  │  ◄────► │  │  ┌──────────┐  │  │
│  │  │  View    │  │  │         │  │  │Composable│  │  │
│  │  └──────────┘  │  │         │  │  └──────────┘  │  │
│  └────────────────┘  │         │  └────────────────┘  │
└──────────────────────┘         └──────────────────────┘
        ↑ 组件库（第三方 SDK、地图、播放器、图表）
```

### 2. AndroidView 详解

```kotlin
@Composable
fun WebViewScreen(url: String) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        // 1. factory：只在第一次组合时调用一次，创建 View 实例
        factory = { context ->
            WebView(context).apply {
                webView = this
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        webView = view      // 回调里更新状态 → 触发重组
                    }
                }
            }
        },
        // 2. update：每次重组都会调用，用于把 Compose 状态同步到 View
        update = { view ->
            if (view.url != url && url.isNotBlank()) {
                view.loadUrl(url)
            }
        }
    )
}
```

| 参数 | 调用时机 | 用途 |
| --- | --- | --- |
| `factory` | **仅第一次**组合时 | 创建并初始化 View |
| `update` | **每次重组**都会调用 | 把最新的 Compose 状态同步到 View |
| `modifier` | — | 让 View 参与 Compose 布局（大小、padding、手势） |
| `onRelease` | View 被释放时（Compose 1.4+） | 清理资源（`webView.destroy()`） |

**`factory` vs `update` 的经典错误**：

```kotlin
// ❌ 错误：在 factory 里直接读取外部状态 → 状态变化不会反映到 View
AndroidView(
    factory = { context -> MyView(context).apply { setText(text) } },  // text 变化不生效
    update = { }
)

// ✅ 正确：状态同步放在 update
AndroidView(
    factory = { context -> MyView(context) },
    update = { it.setText(text) }
)
```

> **注意**：`update` 每次重组都执行，如果里面做重活（如 `loadUrl`）会频繁触发 → 需要自己做 diff 判断。

### 3. ComposeView 详解

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(this).apply {
            // 1. 把 Compose 内容设置进去
            setContent {
                MyAppTheme {
                    MyScreen()
                }
            }
            // 2. 关键：设置 ViewCompositionStrategy
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
        }
        setContentView(composeView)
    }
}
```

**为什么需要 `setViewCompositionStrategy`？**

`ComposeView` 内部持有一个 `Composition`。如果这个 Composition 的生命周期比 View 长（或短），就会出现**内存泄漏**或**状态丢失**。

```text
问题场景：
  Activity onDestroy()
    └─ ComposeView 被移除
         └─ 但 Composition 还持有 Activity 的 Context
              └─ Activity 泄漏！
```

### 4. ViewCompositionStrategy 的四种策略

| 策略 | 释放时机 | 适用场景 |
| --- | --- | --- |
| `DisposeOnDetachedFromWindowOrReleasedFromPool`（**默认**） | View 从 Window 移除 / 从池中释放 | View 生命周期与 Composition 一一对应（最常见） |
| `DisposeOnLifecycleDestroyed` | 指定的 `Lifecycle` 变为 `DESTROYED` | View 的生命周期由某个 LifecycleOwner 管理（如 Fragment 中的 ComposeView） |
| `DisposeOnViewTreeLifecycleDestroyed` | View 树所属的 `ViewTreeLifecycleOwner` 被销毁 | **最推荐的通用写法**：自动跟随宿主（Activity/Fragment）销毁 |
| `DisposeOnDetachedFromWindow` | View 从 Window 移除 | View 会反复 attach/detach（如 RecyclerView item） |

**源码级理解**：

```text
ViewCompositionStrategy 的本质是提供一个 "Composition 何时 dispose" 的时机：

DisposeOnViewTreeLifecycleDestroyed
   └─ 注册一个 ViewTreeLifecycleOwner 的 Observer
        └─ onStateChanged → DESTROYED → composition.dispose()

DisposeOnDetachedFromWindowOrReleasedFromPool
   └─ View.onAttachedToWindow / onDetachedFromWindow 回调
        └─ detached → composition.dispose()
```

**Compose 官方推荐**：优先使用 `DisposeOnViewTreeLifecycleDestroyed`；如果 View 会进入 RecyclerView 复用池，用默认策略。

### 5. 嵌套滚动（NestedScroll）

Compose 用 `Modifier.nestedScroll` 与 View 体系互操作。

```text
嵌套滚动的核心协议：

  ┌──────────────────────────────────────────────┐
  │  Child（嵌套滚动子）                          │
  │  onPreScroll → 把剩余 delta 交给 Parent        │
  │  onScroll    → 通知 Parent 我滚了这么多          │
  └──────────────────────────────────────────────┘
                    ↕
  ┌──────────────────────────────────────────────┐
  │  Parent（嵌套滚动父）                         │
  │  onPreScroll → 先消费掉自己该滚的部分            │
  │  onScroll    → 剩余部分给 Child               │
  └──────────────────────────────────────────────┘
```

**Compose 侧**：

```kotlin
@Composable
fun CollapsingHeader() {
    val state = rememberScrollState()

    LazyColumn(
        state = state,
        // 1. 父容器接收子（嵌套在里面的 AndroidView）的滚动
        modifier = Modifier.nestedScroll(
            connection = object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    // 优先让 Header 消耗（折叠）
                    val consumed = state.scrollBy(available.y)
                    return Offset(0f, consumed)
                }
            }
        )
    ) {
        item { Header() }
        items(data) { Row(it) }
    }
}
```

**View 侧接入 Compose**：

```kotlin
class ComposeHostFragment : Fragment() {
    override fun onCreateView(...): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                MyScreen()
            }
        }
    }
}
```

**`androidx.compose.ui:ui` 提供的 View 扩展**：

```kotlin
// View → Compose 的滚动嵌套
val composeView = ComposeView(context).apply {
    setContent {
        val scrollState = rememberScrollState()
        // 把 View 的滚动位置同步给 Compose
        LaunchedEffect(scrollState) {
            snapshotFlow { scrollState.value }.collect { /* ... */ }
        }
    }
}
```

### 6. 主题共享

**Compose → View**：把 Compose 主题的颜色传给 View。

```kotlin
@Composable
fun ThemedWebView(url: String) {
    val primary = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surface

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(background.toArgb())
            }
        },
        update = { view ->
            view.setBackgroundColor(background.toArgb())
        }
    )
}
```

**View → Compose**：把 XML 主题的颜色传给 Compose。

```kotlin
// 1. 在 Compose 中读取 Android 主题属性
@Composable
fun AndroidThemeColor(): Color {
    val context = LocalContext.current
    val typedValue = TypedValue()
    context.theme.resolveAttribute(R.attr.colorPrimary, typedValue, true)
    val colorInt = typedValue.data
    return Color(colorInt)
}

// 2. 更通用的：使用 Material 的 View 桥接
@Composable
fun MaterialThemeFromXml() {
    val view = LocalView.current
    if (!view.isInEditMode) {
        MaterialTheme(
            colors = MaterialTheme.colorsFrom(view.context)   // 或自定义映射
        ) {
            content()
        }
    }
}
```

**更实用的方案（官方推荐）**：在 XML 主题中定义 Compose 用的颜色资源，Compose 中直接读。

```xml
<!-- values/themes.xml -->
<style name="Theme.MyApp" parent="Theme.Material3.DayNight.NoActionBar">
    <item name="composePrimary">@color/brand_primary</item>
    <item name="composeSurface">@color/brand_surface</item>
</style>
```

```kotlin
@Composable
fun MyAppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val primary = context.theme.resolveColor(R.attr.composePrimary)
    val surface = context.theme.resolveColor(R.attr.composeSurface)

    MaterialTheme(
        colorScheme = lightColorScheme(primary = primary, surface = surface),
        content = content
    )
}
```

### 7. RecyclerView 在 Compose 中的用法

**方案 A：`AndroidView` 直接嵌入（不推荐，性能差）**

```kotlin
@Composable
fun RecyclerInCompose(items: List<Item>) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = MyAdapter(items) { }
            }
        },
        update = { rv -> (rv.adapter as MyAdapter).submitList(items) }
    )
}
```

> ❌ 问题：Compose 无法控制 RecyclerView 的内部滚动与 Compose 滚动同步；性能也差。

**方案 B：用 `LazyColumn` 重写（推荐）**

```kotlin
@Composable
fun ItemsInCompose(items: List<Item>, onClick: (Item) -> Unit) {
    LazyColumn {
        items(items, key = { it.id }) { item ->
            ItemRow(item, onClick = onClick)
        }
    }
}
```

**方案 C：把 RecyclerView 嵌进 Compose，并用 `nestedScroll` 桥接滚动**

```kotlin
@Composable
fun RecyclerWithNestedScroll(items: List<Item>) {
    val listState = rememberLazyListState()

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = MyAdapter(items)
                // 把 RecyclerView 的滚动位置暴露给 Compose
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                        // 可以在这里同步到 Compose 状态
                    }
                })
            }
        },
        update = { rv ->
            (rv.adapter as MyAdapter).submitList(items)
        }
    )
}
```

### 8. 互操作的性能注意事项

| 问题 | 原因 | 应对 |
| --- | --- | --- |
| `update` 每次重组都跑 | 重组频繁 | 在 `update` 里做 diff，只在真变化时操作 View |
| View 内部有动画时 Compose 不知道 | 两套渲染系统 | 用 `AndroidView` + `state` 手动同步 |
| 滚动位置不同步 | 两套滚动容器 | `nestedScroll` 桥接，或直接用 Compose 实现 |
| 主题不一致 | 两套主题系统 | 手动映射颜色，或用 XML 主题 + `resolveColor` |
| 内存泄漏 | Composition 未释放 | `setViewCompositionStrategy(DisposeOnViewTreeLifecycleDestroyed)` |
| `LazyList` 中嵌 `AndroidView` | 复用导致状态错乱 | 给 `AndroidView` 加 `key`；避免在 LazyList 中嵌 View |

### 9. ComposeView 的内存泄漏原理

```text
泄漏链路：

Activity (Context)
   ↑ 强引用
Composition
   ↑ 强引用
ComposeView
   ↑ 被 Window 移除后仍被 Composition 引用

如果 Activity destroy 时 Composition 没有 dispose：
   → Activity 被 Composition 持有 → 泄漏
```

**解法**：

```kotlin
composeView.setViewCompositionStrategy(
    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
)
```

或手动：

```kotlin
override fun onDestroy() {
    composeView.disposeComposition()   // 手动释放
    super.onDestroy()
}
```

## 代码示例

### 1. Compose 中嵌入 View（含状态同步与资源释放）

```kotlin
@Composable
fun VideoPlayerScreen(videoUrl: String, onError: (String) -> Unit) {
    // 用 remember 持有 View 引用，便于在 update / onRelease 中操作
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
        factory = { context ->
            PlayerView(context).apply {
                playerView = this
                useController = true
                // View 回调 → 更新 Compose 状态
                setOnErrorListener { _, what, extra ->
                    onError("播放失败 $what/$extra")
                    true
                }
            }
        },
        update = { view ->
            // Compose 状态变化 → 同步到 View（做 diff，避免重复加载）
            if (isPlaying && !view.isPlaying) {
                view.start()
            } else if (!isPlaying && view.isPlaying) {
                view.pause()
            }
        },
        onRelease = { view ->          // Compose 1.4+
            view.release()             // 释放播放器资源
            playerView = null
        }
    )
}
```

### 2. View 中嵌入 Compose（Fragment 版，正确处理生命周期）

```kotlin
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val composeView = ComposeView(requireContext()).apply {
            // 关键：让 Composition 跟随 View 树的 LifecycleOwner 销毁
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                MyAppTheme {
                    val viewModel: ProfileViewModel = hiltViewModel()
                    val state by viewModel.uiState.collectAsStateWithLifecycle()

                    ProfileScreen(
                        state = state,
                        onBack = { findNavController().popBackStack() },
                        onRetry = viewModel::refresh
                    )
                }
            }
        }
        // 把 ComposeView 放进 XML 布局
        binding.composeContainer.addView(composeView)
    }

    override fun onDestroyView() {
        _binding = null    // 避免 ViewBinding 泄漏
        super.onDestroyView()
    }
}
```

### 3. XML 布局中声明 ComposeView

```xml
<!-- res/layout/fragment_profile.xml -->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <androidx.compose.ui.platform.ComposeView
        android:id="@+id/composeView"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />
</FrameLayout>
```

```kotlin
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    binding.composeView.setViewCompositionStrategy(
        ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
    )
    binding.composeView.setContent {
        MyAppTheme { ProfileScreen() }
    }
}
```

### 4. 嵌套滚动桥接：Compose 容器 + AndroidView 子内容

```kotlin
@Composable
fun NestedScrollDemo() {
    val outerScroll = rememberScrollState()
    val innerScroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(outerScroll)      // 外层滚动
    ) {
        Box(Modifier.height(200.dp).fillMaxWidth().background(Color.Gray))

        // 内层 AndroidView 参与嵌套滚动
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .nestedScroll(
                    connection = object : NestedScrollConnection {
                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                            // 外层还没滚到底 → 让外层先消费
                            if (outerScroll.value < outerScroll.maxValue) {
                                val consumed = outerScroll.scrollBy(available.y)
                                return Offset(0f, consumed)
                            }
                            return Offset.Zero
                        }
                    }
                ),
            factory = { context ->
                RecyclerView(context).apply {
                    layoutManager = LinearLayoutManager(context)
                    adapter = SimpleAdapter((0..50).map { "Item $it" })
                }
            }
        )
    }
}
```

### 5. 主题桥接：把 XML 主题的颜色读进 Compose

```xml
<!-- values/themes.xml -->
<resources>
    <style name="Theme.MyApp" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="composePrimary">@color/brand_primary</item>
        <item name="composeOnPrimary">@color/white</item>
        <item name="composeSurface">@color/brand_surface</item>
        <item name="android:windowLightStatusBar">true</item>
    </style>
</resources>
```

```kotlin
// Compose 中解析主题属性
@Composable
private fun Context.resolveThemeColor(@AttrRes attr: Int, fallback: Color): Color {
    val typedValue = TypedValue()
    theme.resolveAttribute(attr, typedValue, true)
    return if (typedValue.resourceId != 0) ContextCompat.getColor(this, typedValue.resourceId)
           else Color(typedValue.data)
}

@Composable
fun XmlThemeMaterialTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    MaterialTheme(
        colorScheme = if (isDark) darkColorScheme(
            primary = context.resolveThemeColor(R.attr.composePrimary, Color.Purple),
            surface = context.resolveThemeColor(R.attr.composeSurface, Color.DarkGray)
        ) else lightColorScheme(
            primary = context.resolveThemeColor(R.attr.composePrimary, Color.Purple),
            surface = context.resolveThemeColor(R.attr.composeSurface, Color.White)
        ),
        content = content
    )
}
```

### 6. 第三方 View（地图/图表/播放器）的通用封装

```kotlin
/** 通用封装：把任意 View 包装成 Compose 组件 */
@Composable
fun <T : View> AndroidViewFactory(
    modifier: Modifier = Modifier,
    factory: (Context) -> T,
    update: (T) -> Unit = {},
    onRelease: (T) -> Unit = {}
) {
    AndroidView(
        modifier = modifier,
        factory = factory,
        update = update,
        onRelease = onRelease
    )
}

// 使用
@Composable
fun MapView(modifier: Modifier = Modifier, onMarkerClick: (String) -> Unit) {
    var map: MapView? by remember { mutableStateOf(null) }

    AndroidViewFactory(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            MapView(context).apply {
                map = this
                onMarkerClickListener = onMarkerClick
                onResume()                    // 某些 SDK 需要手动生命周期管理
            }
        },
        update = { view -> view.onResume() },
        onRelease = { view ->
            view.onPause()
            view.onDestroy()
            map = null
        }
    )
}
```

## 常见问题 / 面试要点

**读者问：ComposeView 为什么会内存泄漏？怎么避免？**

**AI答：**

1. **泄漏链路**：
   ```text
   Activity/Context ←强引用─ Composition ←强引用─ ComposeView
   ```
   `ComposeView` 内部持有一个 `Composition`。如果 Activity 销毁时 Composition 没有被 `dispose()`，它会继续持有 View 的 `Context`（Activity）→ **Activity 泄漏**。
2. **根本原因**：`ComposeView` 本身不知道宿主什么时候该销毁 Composition（它可能被复用、可能加到 Dialog、可能加到 RecyclerView）。
3. **解决方案（按优先级）**：
   - **`setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)`** —— 推荐默认写法，自动跟随 View 树的 `ViewTreeLifecycleOwner` 销毁。
   - `DisposeOnLifecycleDestroyed(lifecycle)` —— 显式指定 Lifecycle。
   - `DisposeOnDetachedFromWindowOrReleasedFromPool` —— 默认策略，适合 View 与 Composition 一一对应。
   - 手动 `composeView.disposeComposition()`（在 `onDestroy` 中）。
4. **Fragment 中的额外注意**：
   - 用 `Fragment` 的 `viewLifecycleOwner` 而不是 `Fragment` 本身做 LifecycleOwner；
   - `onDestroyView()` 里清空 ViewBinding 引用；
   - 如果用 `AbstractComposeView` 子类并重写 `onCreateLifecycleAwareContext`，注意不要持有 Activity 的强引用。
5. **面试话术**："**ComposeView 的泄漏根因是『Composition 的生命周期没有和宿主的生命周期对齐』，`ViewCompositionStrategy` 就是官方给的解耦手段。**"

---

**读者问：`AndroidView` 的 `factory` 和 `update` 有什么区别？各在什么时候调用？**

**AI答：**

1. **`factory`**：**只在第一次组合时调用一次**，用于**创建并初始化** View 实例。
   - 典型用途：`WebView(context).apply { settings.javaScriptEnabled = true }`。
   - **不能在这里读外部状态**：因为它只执行一次，后续状态变化不会反映到 View 上。
2. **`update`**：**每次重组都会调用**，用于把**最新的 Compose 状态同步**到已有的 View。
   - 典型用途：`view.loadUrl(url)`、`view.setText(text)`。
   - ⚠️ **每次重组都执行**，如果操作昂贵（如 `loadUrl`）会频繁触发 → 需要自己做 diff：
     ```kotlin
     update = { view ->
         if (view.url != url) view.loadUrl(url)     // ✅ diff
     }
     ```
3. **`onRelease`**（Compose 1.4+）：View 被从组合中移除时调用，用于**释放资源**（`webView.destroy()`、`player.release()`）。
4. **`modifier`**：让 View 参与 Compose 的布局系统（大小约束、padding、修饰链、手势）。
5. **常见错误**：
   - 在 `factory` 里读状态 → 状态变化不生效；
   - 在 `update` 里做重活 → 频繁重组时卡顿；
   - 忘记 `onRelease` → 资源泄漏（播放器、地图、摄像头）；
   - 在 `LazyColumn` 中用 `AndroidView` → 复用导致状态错乱。
6. **面试话术**："**`factory` 负责『出生』，`update` 负责『成长』，`onRelease` 负责『死亡』。状态同步永远放在 `update`。**"

---

**读者问：`ViewCompositionStrategy` 有哪几种？各自适合什么场景？**

**AI答：**

1. **四种策略**：
   | 策略 | 释放时机 | 适用 |
   | --- | --- | --- |
   | `DisposeOnViewTreeLifecycleDestroyed` | View 树所属 `ViewTreeLifecycleOwner` 销毁 | **默认推荐**：Activity / Fragment 中的 ComposeView |
   | `DisposeOnLifecycleDestroyed(lifecycle)` | 指定 Lifecycle 销毁 | View 的生命周期由别的 LifecycleOwner 管理 |
   | `DisposeOnDetachedFromWindowOrReleasedFromPool` | View 从窗口移除 / 从池释放 | **框架默认**：View 与 Composition 一一对应；RecyclerView 复用场景 |
   | `DisposeOnDetachedFromWindow` | View 从窗口移除 | View 会反复 attach/detach 且希望立即释放 |
2. **`DisposeOnViewTreeLifecycleDestroyed` 的实现**：它通过 `ViewTreeLifecycleOwner.get(view)` 找到宿主的 Lifecycle，注册 Observer，在 `DESTROYED` 时 dispose Composition。
3. **为什么需要策略**：因为 `ComposeView` 的生命周期可能与宿主不一致——它可能被加进 Dialog、被放进 RecyclerView 复用、被移到另一个 Window。
4. **一个常见坑**：`DisposeOnViewTreeLifecycleDestroyed` 在 View **尚未 attach** 时找不到 LifecycleOwner，此时会退化为默认行为。Compose 官方建议对"可能未 attach 的 View"使用默认策略。
5. **面试话术**："**策略的本质是回答『这个 Composition 什么时候该死』。绝大多数场景直接用 `DisposeOnViewTreeLifecycleDestroyed` 即可。**"

---

**读者问：Compose 和 View 混用有什么性能问题？什么场景应该混用？**

**AI答：**

1. **性能问题**：
   - **`update` 频繁执行**：重组频繁时每次都执行 → 要做 diff。
   - **两套渲染系统**：Compose 的 `RenderNode` 树中嵌入一个 View 子树，View 自己的绘制仍走 `ViewRootImpl` 之外的 `RenderNode`，会有额外的同步开销。
   - **滚动不同步**：Compose 的 `LazyColumn` 与 View 的 `RecyclerView` 是两套独立滚动容器，位置信息不会自动同步 → 需要 `nestedScroll` 桥接。
   - **状态回传延迟**：View 的回调 → 更新 Compose 状态 → 重组 → 更新 UI，多一跳。
   - **`LazyColumn` 中嵌 `AndroidView`**：LazyList 会复用槽位，View 的内部状态可能错乱；且 View 的测量比 Compose 贵。
2. **应该混用的场景**：
   - **第三方 SDK 没有 Compose 版本**：地图（高德/Google Maps）、图表（MPAndroidChart、ArthurCharts）、播放器（IJKPlayer、ExoPlayer）、WebView、相机/扫码、Office 文档预览。
   - **历史代码迁移**：老页面用 View，新页面用 Compose，逐步过渡。
   - **Compose 中复用已有的复杂 View 控件**。
3. **不应该混用的场景**：
   - 能用 Compose 原生实现的普通 UI（Text、Image、列表、动画）→ 直接用 Compose，性能更好、代码更统一。
4. **混用的最佳实践**：
   - 把 View 封装成**独立的 Compose 组件**（如 `MapView()`），内部处理生命周期与状态；
   - 用 `remember` 持有 View 引用，避免在 `update` 中重复创建；
   - 一定要设置 `onRelease` 释放资源；
   - 在 `LazyColumn` 中用 `AndroidView` 时加 `key`。
5. **面试话术**："**Compose 优先，View 作为『逃生舱』用于没有 Compose 替代品的第三方 SDK。混用的成本主要在『两套滚动与生命周期』。**"

---

**读者问：嵌套滚动（NestedScroll）是什么？Compose 和 View 之间怎么桥接？**

**AI答：**

1. **定义**：嵌套滚动是 Android 5.0（API 21）引入的**父子滚动容器协同机制**，解决"内层列表滚到顶后，外层容器接着滚"的问题。
2. **协议**：
   ```text
   Child.onNestedPreScroll(dx, dy, consumed)   → 请求父先消费
   Parent.onNestedPreScroll(...)                → 父消费一部分，返回 consumed
   Child.onNestedScroll(dx, dy, consumed)       → 通知父"我滚了这么多"
   Parent.onNestedScroll(...)                   → 父可以再消费剩余
   ```
   还有 fling 版本：`onNestedPreFling` / `onNestedFling`。
3. **Compose 侧**：
   ```kotlin
   Modifier.nestedScroll(connection = object : NestedScrollConnection {
       override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
           val consumed = scrollState.scrollBy(available.y)
           return Offset(0f, consumed)
       }
       override fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
           return Velocity.Zero    // 消费掉 fling，防止外层继续滚
       }
   })
   ```
   - `onPreScroll`：**父先消费**（"我还滚得动，让我先来"）。
   - `onPostScroll`：**子滚完后父再消费**（"子滚完了，剩下的给我"）。
4. **View 侧**：实现 `NestedScrollingParent`（父）/ `NestedScrollingChild`（子）接口，或使用 `NestedScrollView`（自带）。
5. **View → Compose 桥接**：Compose 中的 `AndroidView` 若内部是 `NestedScrollingChild`，需要外层 Compose 容器提供 `NestedScrollingParent` 行为 → 通过 `Modifier.nestedScroll` 的 `onPreScroll` 消费。
6. **面试话术**："**嵌套滚动的本质是『子先问父要不要，父消费后子再滚剩下的』，通过 delta（消耗量）而非位置来协调。**"

---

**读者问：Compose 和 View 的主题怎么保持一致？**

**AI答：**

1. **问题**：两套主题系统互不相通：
   - View：`Theme.Material3.DayNight` + `colors.xml` + `?attr/colorPrimary`；
   - Compose：`MaterialTheme(colorScheme = lightColorScheme(...))`。
2. **方案 A（推荐）：以 XML 主题为单一真相源**。
   - 在 XML 主题中额外声明 Compose 需要的颜色属性：
     ```xml
     <style name="Theme.MyApp" parent="Theme.Material3.DayNight">
         <item name="composePrimary">@color/brand_primary</item>
     </style>
     ```
   - Compose 中用 `context.theme.resolveAttribute()` 读取。
   - 好处：换肤只需改 XML 主题，View 和 Compose 同时生效。
3. **方案 B：以 Compose 为真相源**。
   - Compose 中定义 `MaterialTheme`，在 `AndroidView` 的 `update` 里把颜色同步给 View（`view.setBackgroundColor(...)`）。
4. **方案 C：使用官方 `Material` 库的 View 桥接**：`com.google.android.material` 提供 `MaterialColors.getColor(view, attr)`，可以在 Compose 中拿到 View 主题色。
5. **注意**：
   - **深色模式**：两边都要跟随 `isSystemInDarkTheme()` / `-night` 资源限定符。
   - **字体**：Compose 的 `TextStyle` 与 View 的 `textAppearance` 不同源；可以在 XML 定义 `textAppearance` 后在 Compose 中读 `sp`。
   - **Material2 vs Material3**：Compose 用 Material3（`androidx.compose.material3`），View 用 `com.google.android.material`；颜色角色命名一致但 API 不同。
6. **面试话术**："**混用项目里主题最好『单源』：以 XML 主题为准，Compose 反向读取属性，避免两套颜色配置漂移。**"

---

**读者问：为什么 Google 推荐 Compose 优先？什么情况下还应该用 View？**

**AI答：**

1. **Compose 的优势**：
   - **声明式**：UI = f(state)，状态管理更清晰，减少"状态散落各处"的问题。
   - **更少的样板代码**：无需 `findViewById`、无需 XML + Java 双向绑定；`LiveData` 观察可直接 `collectAsState`。
   - **更好的响应式**：状态变更自动触发精确范围的重组，**跳过**不需要的部分。
   - **一致的视觉与交互**：Material3 组件行为统一、跨设备一致（手机、平板、折叠屏）。
   - **动画 API 简洁**：`animate*AsState`、`AnimatedVisibility`、`Transition` 一行代码完成，天然支持自动中断。
   - **更容易做无障碍**：语义树（Semantics）统一。
   - **官方投入**：新特性优先 Compose（`PullToRefresh`、`HorizontalPager` 等）。
2. **仍应使用 View 的情况**：
   - **第三方 SDK 只有 View 版本**（地图、图表、播放器、OCR、Office）。
   - **大型存量项目**：全量重写成本高、风险大 → 采用"**渐进式迁移**"（新页面用 Compose，老页面用 View）。
   - **团队技能与协作成本**：团队不熟 Compose 时强行迁移反而降低效率。
   - **特殊渲染需求**：某些复杂 `Canvas` 绘制或 `SurfaceView`/`TextureView` 场景。
3. **渐进式迁移策略**：
   - 用 `ComposeView` 在现有 Activity 中嵌入 Compose；
   - 用 `AndroidView` 在 Compose 中嵌入现有 View 组件；
   - 共享同一套 ViewModel 与状态层（StateFlow），两套 UI 共享同一数据源。
   - 共享主题（见上一题）。
4. **面试话术**："**『Compose 优先』指的是新代码默认用 Compose，不是要立刻重写老代码。互操作 API 的存在就是为了让迁移可以渐进进行。**"

## 关联笔记

- [[声明式UI与重组]]：Compose 的节点树与渲染流程
- [[状态管理]]：`collectAsStateWithLifecycle` 与状态容器
- [[../传统View系统/绘制流程]]：View 的渲染管线对照
- [[../传统View系统/事件分发机制]]：Compose 的手势与 View 事件体系的差异
- [[../布局与主题/MaterialDesign]]：Material3 与 Material2 的差异
- [[../布局与主题/主题与样式]]：主题桥接的属性读取方式
- [[../窗口管理/Window与WindowManager]]：`ComposeView` 的 Window 挂载与生命周期
- [[../README]]：第 3 部分总览

## 参考资料

- Google 官方文档 "Interoperability APIs in Compose"：https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views
- Google 官方文档 "Migrate from Views to Compose"：https://developer.android.com/develop/ui/compose/migration
- androidx 源码：`androidx.compose.ui.platform.ComposeView`、`ViewCompositionStrategy`
- Google 官方文档 "Nested scrolling"：https://developer.android.com/develop/ui/views/touch-and-input/nested-scrolling
- 《Jetpack Compose 官方文档》· 互操作章节
