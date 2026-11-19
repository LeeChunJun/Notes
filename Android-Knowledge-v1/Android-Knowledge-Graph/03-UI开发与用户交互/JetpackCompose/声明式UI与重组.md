---
tags: [android]
part: 第3部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[状态管理]], [[Compose与View互操作]], [[../README]]"
---

# 声明式UI与重组

## 一句话定义

Compose 是**声明式** UI 框架：你不再"命令控件去做某事"，而是**描述界面在当前状态下应该长什么样**。框架通过**重组（Recomposition）**机制，在状态变化时自动重新执行受影响的 `@Composable` 函数，并把差异渲染到屏幕上。

## 核心原理

### 1. 声明式 vs 命令式

```text
命令式（传统 View）                        声明式（Compose）
────────────────────                       ────────────────────
view.text = "Hello"                        Text("Hello")
view.visibility = GONE                    if (visible) Text("Hello")
button.setOnClickListener { ... }          Button(onClick = { ... })

需要开发者手动维护"何时更新"                  框架根据状态自动推导
状态散落在各个控件里                        状态集中、可组合
```

**关键差异**：`setText("Hello")` 是**一次命令**，框架不知道"Hello"在别处是否也需要更新；`Text("Hello")` 是**一个声明**，只要 `Hello` 相关的输入变了，框架会重新计算并渲染。

```text
命令式：
  数据变化 → 你手动找出所有受影响的控件 → 逐个 setter 更新

声明式：
  数据变化 → 框架重新执行相关 composable → diff → 重新布局/绘制
```

### 2. Composable 函数的本质

一个 `@Composable` 函数在**编译后**不是普通函数，而是被 **Compose Compiler 插件**改写成可以被运行时管理的东西。

```kotlin
@Composable
fun Greeting(name: String) {
    Text(text = "Hello $name")
}
```

编译产物大致等价于：

```kotlin
// 编译器生成的实际结构（伪代码）
fun Greeting(name: String, $composer: Composer, $changed: Int) {
    // 1. 如果输入没变且之前已执行过 → 直接复用上次的节点，跳过执行
    if ($changed and 1 != 0) {          // name 变了
        $composer.startReplaceableGroup(key)
        Text(name, $composer, 0)        // 调用子 composable
        $composer.endReplaceableGroup()
    }
}
```

**Compose Compiler 做了 4 件事**：

| 能力 | 说明 |
| --- | --- |
| **生成 group 结构** | 每个 composable 调用对应一个 "group"，运行时据此知道"这段代码属于哪个可重组范围" |
| **插入状态读取记录** | 把对 `State` 的读取记录下来，形成"**这次执行读了哪些状态**"的依赖图 |
| **插入 skip 判断** | 输入未变 + 依赖状态未变 → **跳过执行**（这就是 Compose 的性能基石） |
| **插入 restartable group** | 标记哪些 group 可以被独立重启（重组） |

> 面试点：**"重组"（recomposition）是 Compose 特有的概念**，"recompose" 只是它的动词形态。Android 传统 View 里没有"重组"，只有 `requestLayout`/`invalidate` 的显式调用。

### 3. 什么会触发重组

```text
触发重组的 4 类原因：

1. 状态变化          —— 某个被读取的 MutableState 被写入
2. 输入变化          —— composable 的参数（inputs）发生变化
3. 依赖变化          —— 读取的 StateFlow / LiveData / Flow 发出了新值
4. 作用域失效        —— 对应 group 被标记为 invalid（如父节点被重组）
```

**重组的粒度（作用域）**：

```text
@Composable
fun Parent() {
    val count = remember { mutableIntStateOf(0) }
    Column {
        Text("Header")           // ← 独立的 group
        Child(count)             // ← 重组时只重启 Child 的 group
        Text("Footer")           // ← 独立的 group
    }
}
```

- 重组**不是**从根节点重新执行整个函数树，而是**从"最小的、失效的 group"开始**，**跳过**参数未变且依赖未变的兄弟 group。
- **`@Composable` 函数体本身就是一个 group 边界**；`Column`/`Row`/`Box` 的 content lambda 各自是一个 group。

**"跳过"的条件（必须同时满足）**：

1. **所有输入参数都 `==` 相等**（对于 `stable` 类型）或**引用相等**（对于 `unstable` 类型）。
2. **函数内读取的状态都没变**。
3. **composable 没有被标记为需要重启**（`@NonRestartableComposable` 是反例）。

### 4. 状态读取追踪（Snapshot 系统）

Compose 的状态追踪靠 **Snapshot（快照）系统**：

```text
┌──────────────────────────────────────────────────────┐
│  读取时：                                            │
│  val v = state.value                                 │
│       │                                              │
│       ▼                                              │
│  Snapshot.current.observeReads { ... }                │
│  → 记录 "当前 group 读取了 state 这个对象"             │
│  → 同时建立反向依赖：state → group                   │
└──────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────┐
│  写入时：                                            │
│  state.value = newValue                              │
│       │                                              │
│       ▼                                              │
│  Snapshot 通知所有依赖它的 group 失效（invalidate）      │
│  → 安排下一帧执行重组                                  │
└──────────────────────────────────────────────────────┘
```

**Snapshot 的关键概念**：

| 概念 | 说明 |
| --- | --- |
| `Snapshot` | 一致的状态视图；全局状态是一个"当前快照" |
| `MutableSnapshot` | 可写的独立快照，用于在快照内做批量修改 |
| `SnapshotStateList` / `SnapshotStateMap` | 支持快照的可变集合 |
| `GlobalSnapshot` | 写操作需要显式 apply 到全局 |
| **解决并发写冲突** | 多个线程同时写同一个状态时，通过快照的"读-改-写"原子性避免丢失更新 |

**状态写入的线程规则**：

```kotlin
// 快照状态可以在任意线程读
val v = state.value

// 写入：从后台线程写会抛异常吗？
// 答：在 Compose 1.x 中，写 MutableState 是线程安全的，
// 但"读-改-写"不是原子的，所以推荐在主线程写，或用 withContext。
```

### 5. Compose 的渲染流程

```text
@Composable 函数
      │
      ▼
Compose 节点树（Composition）
  - Group（编译器插入的重组边界）
  - SlotTable（记录节点的"身份 + 位置"）
      │
      ▼
LayoutNode 树
  - 每个节点对应一个 LayoutNode
  - 负责 Measure / Place
      │
      ▼
AndroidComposeView（继承 AbstractComposeView）
      │
      ▼
RenderNode 树（Android 图形层）
      │
      ▼
RenderThread ──► GPU ──► 屏幕
```

**与传统 View 的对比**：

| 维度 | 传统 View | Compose |
| --- | --- | --- |
| 节点树 | `View` / `ViewGroup` | `LayoutNode`（Compose 内部） |
| 测量 | `View.measure` + `onMeasure` | `MeasurePolicy.measure`（`SubcomposeMeasureScope`） |
| 布局 | `View.layout` + `onLayout` | `Placeable.place`（绝对定位） |
| 绘制 | `Canvas` 指令 → `DisplayList` | `DrawScope` → `Canvas` 指令 → `RenderNode` |
| 身份 | `View` 的 `id` | `Key`（用于在重组中保持状态） |
| 重绘触发 | `invalidate()` | 状态变更 → 重组 |
| 跳过能力 | ❌（整棵子树重绘） | ✅（group 可跳过） |
| 状态保持 | `onSaveInstanceState` | `remember` / `rememberSaveable` / `SaveableStateHolder` |

**Compose 的测量比 View 简单**：

- View 的测量是"父问子、子答"的递归协议；
- Compose 的测量是"**父给约束、子给尺寸**"：父通过 `Constraints`（`minWidth/maxWidth/minHeight/maxHeight`）告诉子"你的可用空间范围"，子返回自己想要的尺寸。父可以**忽略**子返回的尺寸强行使用自己的尺寸（`MeasureScope` 中父的 `layout` 决定最终位置）。

```kotlin
// 自定义 Compose 布局：父把宽度强制固定，子只报告高度
@Composable
fun FixedWidthRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, maxWidth = constraints.maxWidth)) }
        val height = placeables.maxOf { it.height }
        layout(constraints.maxWidth, height) {                       // 父决定最终尺寸
            var x = 0
            placeables.forEach { it.placeRelative(x, 0); x += it.width }
        }
    }
}
```

### 6. 稳定型（stability）与不稳定型（instability）

Compose 编译器会分析每个类型的**稳定性**，决定重组时能否跳过。

| 类型 | 定义 | 是否稳定 |
| --- | --- | --- |
| `String`、基础类型 | 不可变，equals 可比较 | ✅ stable |
| `data class`（全部属性稳定） | 不可变 + equals/hashCode | ✅ stable |
| `data class`（含不稳定属性） | 有可变或不可比较字段 | ❌ unstable |
| 普通 class | 无 equals，引用比较 | ❌ unstable |
| `List<T>` | `List` 接口本身 **unstable**（因为不知道实现类是否可变） | ⚠️ 需注意 |
| `MutableList<T>` | 可变 | ❌ unstable |
| `@Immutable` / `@Stable` 注解 | 手动声明 | ✅ 强制 stable |

**为什么稳定性很重要**：

```text
参数不稳定 → 每次重组都必须重新执行这个 composable（无法跳过）
           → 即使数据没变，也白白跑一遍
```

**优化手段**：

```kotlin
// 1. 用不可变 data class
data class User(val id: Long, val name: String)

// 2. 集合用不可变类型
val users: List<User> = listOf(...)   // 编译器能识别为 stable（Kotlin 的 List）

// 3. 自定义类型加注解
@Immutable
data class UiConfig(val theme: String, val fontScale: Float)

// 4. 让编译器知道：把 lambda 变成稳定类型
@Composable
fun StableLambda(content: @Composable () -> Unit) { ... }
```

**Lambda 的稳定性**（高频面试点）：

```kotlin
// 编译器认为 lambda 是 unstable 的，除非它是"可记忆的"
// Compose 编译器插件会自动把捕获了 stable 变量的 lambda 变成 stable
@Composable
fun Parent() {
    val name = "Tom"                       // stable 局部变量
    Child { Text(name) }                   // 捕获 stable 变量 → lambda 稳定 → 可跳过
}

@Composable
fun Parent2() {
    val list = mutableStateListOf<String>() // unstable
    Child { Text(list.size.toString()) }    // 捕获 unstable → lambda 不稳定 → 每次都重组
}
```

> 这就是**为什么 lambda 要 `remember`**：`remember { }` 让 lambda 的**引用稳定**，避免每次重组都创建新 lambda 导致子 composable 参数变化而无法跳过。

### 7. 性能优化的核心口诀

```text
1. 状态尽可能下移      —— 状态读在哪里，重组范围就在哪里
2. 尽可能使用 remember —— 缓存昂贵的对象，避免重复创建
3. 尽可能使用派生状态  —— derivedStateOf 缩小重组范围
4. 尽可能使用不可变类型 —— stable 类型才能被跳过
5. 尽可能使用 lambda defer —— 延迟到布局阶段再执行
6. 列表使用 key       —— 保证重组时 item 的状态不错位
```

**状态下移的经典例子**：

```kotlin
// ❌ 不好：整个列表会跟着 count 重组
@Composable
fun Bad(state: Int) {
    val count by remember { mutableIntStateOf(0) }
    Column {
        Text("Count: $count")     // 读了 count
        HeavyList(1000)           // 也被迫重组（虽然它的参数没变，但父的 group 被重启）
    }
}

// ✅ 好：把状态读推到需要它的最小范围
@Composable
fun Good() {
    val count by remember { mutableIntStateOf(0) }
    Column {
        Counter(count, onCountChange = { count++ })  // lambda 稳定
        HeavyList(1000)                              // 参数没变 → 被跳过
    }
}
```

### 8. `@Preview` 与 Live Edit

```kotlin
@Preview(name = "Light", showBackground = true, widthDp = 360, heightDp = 640)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Large Font", fontScale = 1.5f)
@Composable
fun GreetingPreview() {
    MyAppTheme {
        Greeting("Android")
    }
}
```

- `@Preview` 可以在 Android Studio 中实时预览，无需运行到设备。
- Live Edit（Android Studio Hedgehog+）支持**不重启应用**就看到代码修改的效果（基于 Compose 的"可重启"能力）。
- `layout inspector` 可查看 Compose 树的层级与语义。

## 代码示例

### 1. 声明式 vs 命令式的等价写法

```kotlin
// ---------- 命令式（传统 View） ----------
class GreetingActivity : AppCompatActivity() {
    private lateinit var textView: TextView
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_greeting)
        textView = findViewById(R.id.text)
        button = findViewById(R.id.button)

        // 手动维护"什么时候更新"
        textView.text = getGreeting()
        button.setOnClickListener {
            textView.text = getGreeting()
        }
    }
    private fun getGreeting() = "Hello, ${System.currentTimeMillis()}"
}
```

```kotlin
// ---------- 声明式（Compose） ----------
@Composable
fun GreetingScreen() {
    // 状态是 UI 的唯一输入
    val name by remember { mutableStateOf("Android") }
    val count by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Hello, $name! 点击了 $count 次",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { count++ }) {
            Text("点击")
        }
    }
}
```

### 2. 观察重组范围（`@Trace` / 自定义 Recomposer 日志）

```kotlin
// 用 androidx.compose.runtime 的 CompositionLocal 配合自定义 Modifier 观察重组
import androidx.compose.runtime.traceEventStart
import androidx.compose.runtime.traceEventEnd

@Composable
fun TracedBox(content: @Composable () -> Unit) {
    val counter = remember { mutableIntStateOf(0) }
    // 每次重组都会重新执行这个 composable
    SideEffect {
        counter.value++     // 仅用于观察
    }
    androidx.compose.runtime.traceEventStart("TracedBox")
    content()
    androidx.compose.runtime.traceEventEnd()
}
```

```kotlin
// 更实用的方式：开启 Compose 编译器的 recomposition 报告
// build.gradle.kts
composeCompiler {
    reportsDestination = layout.buildDirectory.reports("compose")
    metricsDestination = layout.buildDirectory.reports("compose")
    includeSourceInformation = true
}
```

### 3. 稳定型优化：不可变数据类 + `remember` lambda

```kotlin
// 不可变数据类 → stable → 参数不变时可跳过重组
@Immutable
data class User(
    val id: Long,
    val name: String,
    val avatarUrl: String
)

@Composable
fun UserList(users: List<User>, onClick: (User) -> Unit) {
    // 提升 lambda 稳定性的两个写法：
    // 1. remember 缓存引用（写法一：remember 包裹）
    val stableOnClick = remember(onClick) { onClick }

    LazyColumn {
        items(users, key = { it.id }) {   // key 保证重组时 item 状态不错位
            UserItem(user = it, onClick = stableOnClick)
        }
    }
}

@Composable
fun UserItem(user: User, onClick: (User) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(user) }   // 捕获的 lambda 引用稳定
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(user.name, style = MaterialTheme.typography.bodyLarge)
    }
}
```

### 4. 自定义布局（Compose 版的"手写 onLayout"）

```kotlin
/** 一个简单的堆叠布局：所有子 View 依次向右下偏移 */
@Composable
fun StaggeredLayout(
    modifier: Modifier = Modifier,
    offset: Dp = 24.dp,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        // 1. 给子 View 约束（父可以限制子）
        val placeables = measurables.map { measurable ->
            measurable.measure(
                constraints.copy(
                    maxWidth = (constraints.maxWidth - offset.roundToPx()).coerceAtLeast(0),
                    maxHeight = (constraints.maxHeight - offset.roundToPx()).coerceAtLeast(0)
                )
            )
        }
        // 2. 决定自身尺寸
        val width = placeables.maxOf { it.width } + offset.roundToPx()
        val height = placeables.sumOf { it.height } + offset.roundToPx()
        // 3. 摆放
        layout(width, height) {
            var y = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(offset.roundToPx(), y)
                y += placeable.height
            }
        }
    }
}
```

### 5. 重组范围的直观验证

```kotlin
@Composable
fun RecompositionScopeDemo() {
    var count by remember { mutableIntStateOf(0) }
    val list = remember { List(1000) { "Item $it" } }

    Column {
        Text("count = $count")
        Button(onClick = { count++ }) { Text("+1") }

        // 用 key 包裹可观测区域：Compose 的 Layout Inspector / Recomposer 会显示边界
        key("heavy") {
            HeavyContent(list)
        }
    }
}

@Composable
fun HeavyContent(items: List<String>) {
    // 这个 composable 在 count 变化时不会被重组，
    // 因为它的参数 list 引用没变（remember 缓存），且它没读 count
    Column {
        items.forEach { Text(it) }
    }
}
```

## 常见问题 / 面试要点

**读者问：Compose 的"重组"和传统 View 的"重绘"有什么区别？**

**AI答：**

1. **术语澄清**："重组"（recomposition）**只属于 Compose**，View 体系里没有这个概念，Android 官方文档也明确区分了两者。
2. **触发方式不同**：
   - View：开发者**显式调用** `invalidate()` / `requestLayout()`；
   - Compose：**状态变化自动触发**，框架根据"读取依赖图"精确推导需要重跑哪些函数。
3. **粒度不同**：
   - View：`invalidate()` 会让**整棵子树**重新绘制（除非用 `invalidate(rect)`）；
   - Compose：只重启**最小的失效 group**，参数未变、依赖未变的兄弟 group 被**跳过**（skip）。
4. **概念对应关系**：
   ```text
   View 的 invalidate()      ≈ Compose 的"重新执行 composable + 重绘"
   View 的 requestLayout()   ≈ Compose 的"重新 measure + place"
   View 的"重绘整棵子树"     ≈ Compose 的"跳过不需要的 group"
   ```
5. **根本区别**：View 是**命令式**的（UI 是状态的副作用），Compose 是**声明式**的（UI = f(state)）。Compose 的性能来自"**能跳过**"，View 的性能只能靠"**少刷新**"。
6. **面试话术**："**View 的重绘是全量的、由开发者控制的；Compose 的重组是增量的、由框架推导的。Compose 的杀手锏是 skip——输入不变就不执行。**"

---

**读者问：什么会触发重组？怎么缩小重组范围？**

**AI答：**

1. **触发原因（4 类）**：
   - **状态变化**：`mutableStateOf` 的值被写入，且被当前 group 读取过；
   - **输入变化**：composable 的参数（inputs）发生变化；
   - **Flow 发出新值**：`collectAsState` 读到的值变了；
   - **作用域失效**：父 group 被重启时，依赖它的子 group 也被标记为待重组（但会先尝试跳过）。
2. **缩小的 6 个手段**：
   1. **状态读向下移动**：把 `val x by state` 放到真正需要它的那一层 composable 里，而不是父级。
   2. **用 `remember` 缓存昂贵对象**（`Paint`、`Bitmap`、lambda、格式化结果）。
   3. **用 `derivedStateOf` 派生**（详见 [[状态管理]]），让重组只在派生值真正变化时发生。
   4. **用不可变 + stable 类型**（`data class` + `@Immutable`），保证"参数没变"能被正确判定。
   5. **列表用 `key`**：`items(list, key = { it.id })`，避免重组时状态错位，也减少不必要的重组。
   6. **把副作用和状态读取分离**：用 `LaunchedEffect` 读状态，事件回调写状态。
3. **反模式**：
   ```kotlin
   // ❌ 父级读状态 → 整个子树被拖下水
   @Composable fun Parent() {
       val count by remember { mutableIntStateOf(0) }
       Column {
           Text("$count")
           HeavyList()
       }
   }
   ```
4. **面试话术**："**重组范围 = 读状态的范围。状态读到哪儿，作用域就到哪儿。**"

---

**读者问：为什么 `remember` 里的 lambda 要用 `remember` 包起来？`remember { }` 和直接写 lambda 有什么区别？**

**AI答：**

1. **根本原因：稳定性**。Compose 编译器分析 composable 参数时，会判断参数是否 **stable**。**Lambda 默认是不稳定的**，因为两个内容相同的 lambda 引用不相等，无法用 `==` 判断"没变"。
2. **不包 `remember` 的后果**：
   ```kotlin
   @Composable
   fun Parent() {
       val count by remember { mutableIntStateOf(0) }
       Child { Text("$count") }     // 每次重组都创建一个新 lambda 实例
   }

   @Composable
   fun Child(content: @Composable () -> Unit) {
       // content 参数引用每次都变 → 无法跳过 → Child 每次都重组
   }
   ```
3. **包 `remember` 后**：
   ```kotlin
   @Composable
   fun Parent() {
       val count by remember { mutableIntStateOf(0) }
       Child(remember { { Text("$count") } })  // 引用稳定 → 可跳过
   }
   ```
4. **更推荐的做法（现代写法）**：
   ```kotlin
   @Composable
   fun Parent() {
       val count by remember { mutableIntStateOf(0) }
       // 编译器会把捕获 stable 变量的 lambda 自动记忆（"compose lambda memoization"）
       Child { Text("$count") }
   }
   ```
   Compose 编译器 1.5+ 会自动记忆那些**捕获了 stable 变量且不使用非 stable 变量的 lambda**。
5. **什么时候必须手动 `remember`**：
   - lambda 捕获了**不稳定**的变量（如 `mutableStateListOf`）；
   - lambda 需要持有昂贵的初始化逻辑（如 `val fmt = remember { DateFormat.getDateInstance() }`）；
   - 需要**每次重组使用同一个对象实例**（如 `remember { BitmapFactory.decodeResource(...) }`）。
6. **面试话术**："**`remember` 的本质是『跨重组缓存一个引用』。lambda 默认不稳定，`remember` 让引用稳定，从而让子 composable 可以被跳过。**"

---

**读者问：Compose 的性能优化有哪些口诀？**

**AI答：**

1. **Google 官方推荐的 6 条**（来自 Android 官方文档 "Compose performance"）：
   1. **尽可能使用 stable 类型**：`data class` + 不可变属性 + `@Immutable`。
   2. **尽可能使用不可变集合**：`List` 而不是 `ArrayList`/`MutableList`。
   3. **使用 `remember` 缓存昂贵对象**：避免每帧新建 `Paint`、`Bitmap`、格式化器。
   4. **把状态向下移动**：状态读在哪儿，重组范围就到哪儿。
   5. **使用派生状态**：`derivedStateOf` 把"重组"转成"重新求值"，粒度更细。
   6. **使用 `defer`**：把计算推迟到布局/绘制阶段，测量阶段只算一次。
2. **额外的社区共识**：
   - 列表永远加 `key`；
   - 避免在 composable 里做 `String.format` / 时间格式化 → `remember` 缓存；
   - 避免在 `onDraw` 里做复杂计算 → `drawWithCache`；
   - 用 `Modifier` 复用（`Modifier.padding(16.dp)` 会创建新实例，但影响很小；高频列表项可提取常量）。
3. **工具支持**：
   - **Compose Compiler Reports**（`reportsDestination`）→ HTML 报告看重组次数；
   - **Layout Inspector** → 看语义树；
   - **Macrobenchmark** → 测量启动、重组、帧率；
   - **`traceEventStart` / `traceEventEnd`** → 自定义 trace。
4. **面试话术**："**Compose 性能优化的核心就一句话：让能跳过的都跳过。稳定类型 + 状态下移 + remember 缓存 + 派生状态，四件套。**"

---

**读者问：Compose 的渲染流程和传统 View 有什么异同？最终是怎么画到屏幕上的？**

**AI答：**

1. **共同点**：**最终都走 Android 原生的图形栈**，都通过 `RenderNode` → `RenderThread` → GPU 渲染；都受 `VSync` / `Choreographer` 驱动。
2. **流程对比**：
   ```text
   传统 View：
   View 树 → ViewRootImpl.performTraversals
          → measure/layout/draw → DisplayList → RenderThread → GPU

   Compose：
   Composable → Composition（SlotTable + Group）
             → LayoutNode 树 → AndroidComposeView
             → RenderNode 树 → RenderThread → GPU
   ```
3. **差异点**：
   - **测量模型**：View 是"父问子、子答"；Compose 是"父给 `Constraints`、子报告尺寸、父可强制覆盖"。
   - **绘制模型**：View 有 `onDraw`/`dispatchDraw` 两阶段；Compose 统一在 `DrawScope` 中。
   - **失效粒度**：View 全子树重绘；Compose 的 `RenderNode` 可以按节点粒度失效，且能跳过 group。
   - **节点身份**：View 用 `id`；Compose 用 `Key`（`key()` / `items(key=)`）保证重组时的身份稳定。
4. **面试话术**："**Compose 不是另起炉灶，它最终仍然编译成 Android 的 `RenderNode` 交给 `RenderThread`；它改变的是『如何描述 UI』和『如何决定重绘范围』。**"

---

**读者问：`@Composable` 函数可以像普通函数一样在 `if` / `for` 里调用吗？有什么限制？**

**AI答：**

1. **可以**，这是 Compose 的核心特性之一——UI 结构由普通 Kotlin 控制流决定。
2. **限制与注意事项**：
   - **composable 调用必须发生在 composable 上下文**中（`@Composable` 函数体内、`@Composable` lambda 内、`CompositionLocal` 等）。
   - **`if` 分支中状态会被"按位置"保存**：如果分支条件翻转，`remember` 的槽位会对不上。**解决：使用 `key()`**。
     ```kotlin
     if (isEditing) {
         key("edit") { EditField(...) }     // 保证独立身份
     } else {
         key("view") { Text(...) }
     }
     ```
   - **`for` 循环中必须用 `key`**：列表项需要稳定身份，否则重组时 `remember` 状态会错位。
     ```kotlin
     items(list, key = { it.id }) { item -> ... }
     ```
   - **不能在 composable 里调用普通的阻塞 IO**（会卡主线程）；应放到 `LaunchedEffect` + 协程里。
   - **不能返回 UI 类型**：composable 不返回值（返回 `Unit`）。
   - **不能在非 composable 上下文里读 `State`**：`by` 委托读 `State.value` 必须在 composable 中（`State<T>.value` 本身可以在任何地方读，但 `by` 需要 `getValue` 扩展，在非 composable 场景会报错"@Composable invocations can only happen from the context of a @Composable function"）。
3. **Composable lambda 的调用位置限制**：`content()` 只能在 composable 上下文中调用。
4. **面试话术**："**Compose 用 Kotlin 的控制流表达 UI 结构，这是它最大的优雅之处；代价是需要 `key` 来维持身份稳定。**"

---

**读者问：什么是"稳定型（stable）"和"不稳定型（unstable）"？为什么它这么重要？**

**AI答：**

1. **定义**：稳定性描述一个类型的**值是否可能发生变化**以及**两个实例能否用 `==` 判断相等**，用于决定 composable 能否被跳过。
2. **判定规则**：
   - **Stable**：类型不可变，且所有属性也是 stable；`equals` 有意义（基础类型、`String`、全 stable 属性的 `data class`）。
   - **Unstable**：可变类型，或没有 `equals` 的普通 class，或含有不稳定属性的 `data class`。
   - **Runtime-stable**：运行时才知道是否稳定的类型，编译器保守处理。
3. **为什么重要**：**决定"参数没变"能否被检测到**。
   ```text
   stable 参数   + equals 相等  → 可以跳过（skip）→ 不重组
   unstable 参数 + 引用不相等  → 无法跳过      → 必须重组
   ```
4. **提升稳定性的手段**：
   - 用 `data class` + `val` + 不可变类型；
   - 加 `@Immutable` / `@Stable` 注解（需你自己保证语义正确，否则会引入 bug）；
   - 用 `List` / `Map` 接口类型而不是 `MutableList` / `ArrayList`；
   - lambda 用 `remember` 或依赖编译器自动记忆。
5. **反面案例**：
   ```kotlin
   class User(var name: String)   // 可变 + 无 equals → unstable
   ```
   每次父重组都会重建 `User` → 子 composable 无法跳过。
6. **面试话术**："**稳定性是 Compose 的『可跳过性』的判定依据。类型设计得好，性能自然好；这是 Compose 相比 View 体系更『讲究类型设计』的地方。**"

---

**读者问：`derivedStateOf` 什么时候用？它和 `remember` 有什么区别？**

**AI答：**

1. **核心区别**：
   - `remember`：**缓存一个值**，在重组之间保持不变；读什么写什么，由你决定。
   - `derivedStateOf`：**缓存一个派生值**，只有当**真正读取的源状态**变化时才重新计算。
2. **`derivedStateOf` 的独特价值**：**它把"重组"降级成"重新求值"**。
   ```kotlin
   // ❌ 问题：list 变化 → 重组（哪怕 filteredList 结果没变）
   val filtered = list.filter { it.active }
   val count = filtered.size
   if (count > 0) Text("有 $count 项")

   // ✅ 改进：只有 count 变化才重组
   val count by remember { derivedStateOf { list.count { it.active } } }
   ```
3. **典型场景**：
   - **滚动位置派生**：`val showButton by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }` —— 滚动过程中每秒触发几十次状态读取，但只有"是否显示按钮"这个布尔值变化时才重组。
   - **排序/过滤**：对大列表排序，只有排序结果变化才重组。
   - **文本格式化**：避免每次重组都做昂贵的格式化。
4. **注意事项**：
   - **`derivedStateOf` 里的读取本身也会建立依赖**，所以里面的源状态变化会触发**重新求值**（但不一定触发重组）。
   - **不要滥用**：`derivedStateOf` 本身有开销（多一层对象 + 依赖追踪），简单的派生直接内联计算即可。
   - **`remember` + `derivedStateOf` 要配合使用**，否则每次重组都会新建一个 derived 对象，缓存失效。
   - **新版本有 `rememberDerivedStateOf`**（Compose 1.7+），它是 `derivedStateOf` 的 Compose-aware 版本，**推荐优先使用**。
5. **面试话术**："**`derivedStateOf` 的价值是『用重新求值代替重组』。当源状态变化频繁、但派生结果变化稀疏时，它能把重组次数降一个数量级。**"

## 关联笔记

- [[状态管理]]：`remember` / `rememberSaveable` / `derivedStateOf` / 副作用 API 的完整用法
- [[Compose与View互操作]]：`AndroidView` / `ComposeView` 与两套体系的边界
- [[../传统View系统/绘制流程]]：传统 View 的 measure/layout/draw 对照
- [[../传统View系统/自定义View]]：什么场景仍然需要自定义 View
- [[../布局与主题/MaterialDesign]]：Compose Material3 与 Material2 的差异
- [[../动画/属性动画]]：Compose 动画 API（`animate*AsState` / `Animatable`）
- [[../README]]：第 3 部分总览

## 参考资料

- Google 官方文档 "Understanding Compose"：https://developer.android.com/develop/ui/compose/architecture
- Google 官方文档 "Compose performance best practices"：https://developer.android.com/develop/ui/compose/performance
- Google 官方文档 "Compose compiler plugin"：https://developer.android.com/develop/ui/compose/compiler-plugin
- androidx 源码：`androidx.compose.runtime`（`Composer`、`Recomposer`、`Snapshot`）
- 《Jetpack Compose 官方文档》· Compose 架构章节
