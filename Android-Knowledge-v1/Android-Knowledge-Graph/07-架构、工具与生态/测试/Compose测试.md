---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 中级
created: 2026-11-04
updated: 2026-11-04
related: "[[单元测试]], [[UI测试]], [[../架构模式/MVVM]]"
---

# Compose测试

## 一句话定义

Compose 测试基于 **Compose 的语义树（Semantics Tree）** 提供 `onNodeWithText`、`performClick` 等声明式 API，让 UI 断言**不依赖具体 View 树结构**，成为 Compose 项目 UI 测试的首选方案。

## 核心原理

### 1. 语义树：Compose 测试的基石

Compose **不是传统的 View 树**，它是一棵 **Composition（组合）树**。为了让测试、无障碍、输入法都能"理解"界面，Compose 为每个可交互节点生成一棵 **语义树（Semantics Tree）**：

```text
组合树（内部实现）          语义树（对外暴露）
┌─ Box                       ┌─ Node(role=Button, text="提交")
│  ├─ Text("提交")            └─ Node(role=Text, text="请输入手机号")
│  └─ TextField
```

- 语义树**是公开契约**，测试、无障碍（TalkBack）、输入法都基于它。
- 测试通过 **Matcher** 在语义树中查找节点，而不是通过 View id。
- **含义**：Compose 测试不关心你的 UI 是用 `Column` 还是 `Box` 实现的，只关心"用户能看到的文本、描述、角色"。

### 2. 三种测试 Rule

| Rule | 环境 | 用途 |
| --- | --- | --- |
| `createComposeRule()` | 设备/模拟器（`androidTest`） | 普通 UI 测试 |
| `createAndroidComposeRule<MainActivity>()` | 设备/模拟器 | 需要与 Activity 交互（取得 Activity、启动 Intent、权限） |
| `createComposeRule()` + Robolectric | JVM（`test`） | 快速测试（不能测真实渲染、Bitmap） |

### 3. 核心 API

```kotlin
// 查找节点
onNodeWithText("提交")            // 精确文本
onNodeWithText("提交", substring = true)
onNodeWithText("提交", ignoreCase = true)
onNodeWithContentDescription("搜索")
onNodeWithTag("myTag")
onNodeWithText("登录").assertIsDisplayed()
onAllNodesWithText("项目").assertCountEquals(3)

// 操作
onNodeWithText("提交").performClick()
onNodeWithText("提交").performTouchInput { click() }
onNodeWithText("提交").performTextInput("hello")
onNodeWithTag("list").performScrollToNode(hasText("第 50 项"))

// 断言
assertIsDisplayed()
assertIsNotDisplayed()
assertIsEnabled()
assertIsNotEnabled()
assertHasClickAction()
assertTextEquals("欢迎")
assertCountEquals(1)
assertIsSelected()
```

### 4. 状态/环境注入：`setContent` + `CompositionLocalProvider`

```kotlin
@Test
fun showsErrorWhenSubmitEmpty() {
    composeTestRule.setContent {
        MyAppTheme {
            LoginScreen(
                state = LoginUiState(error = "用户名不能为空"),
                onSubmit = {}
            )
        }
    }

    composeTestRule.onNodeWithText("用户名不能为空").assertIsDisplayed()
}
```

- **`setContent` 的内容必须自己创建 Composable**，不能依赖 Activity 生命周期（因为 `createComposeRule()` 不提供 Activity）。
- **需要 ViewModel 时**：用 `hiltViewModel()`（需 `createAndroidComposeRule<HiltTestActivity>()`）或直接构造一个假 ViewModel 传进去。
- **推荐做法**：**UI 测试只测"State → UI"的渲染**和**"交互 → 回调"的传递**，业务逻辑由 ViewModel 的单测覆盖。

### 5. 注入与等待

```kotlin
composeTestRule.setContent { MyScreen(state = state) }

// 修改状态后需要等待重组完成
composeTestRule.runOnIdle { state = state.copy(loading = true) }
composeTestRule.waitForIdle()
composeTestRule.onNodeWithTag("loading").assertIsDisplayed()
```

- **`waitForIdle()`**：等待组合完成、动画结束、协程空闲（空闲资源）。
- **`runOnIdle {}`**：在**主线程空闲时**执行代码（用于安全地修改状态）。
- **动画问题**：测试中无限动画会导致 `waitForIdle` **永不返回**（超时失败）。解决：用 `composeTestRule.mainClock.autoAdvance = false` 手动控制时间，或在测试中禁用动画。

```kotlin
composeTestRule.mainClock.autoAdvance = false
composeTestRule.mainClock.advanceTimeBy(1000)
```

### 6. 截图测试（视觉回归）

Compose 没有官方的像素级截图断言，但有成熟方案：

- **Roborazzi**：基于 Compose 渲染结果生成 PNG，可做**像素对比断言**（差异率阈值）。
- **Paparazzi**：基于 **LayoutLib** 在 JVM 上渲染，**不需要设备**，速度快，适合 PR 级别的视觉回归。
- **官方方式**：`captureToImage()`（`androidx.compose.ui:ui-test-junit4`，需 API 26+）拿到 `ImageBitmap` → 转成 Bitmap 做自定义断言。

```kotlin
// Roborazzi 示例
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test fun snapshot() {
        composeTestRule.setContent { MyScreen(state = DemoState) }
        composeTestRule.onRoot().captureRoboImage("src/test/screenshots/my_screen.png")
    }
}
```

### 7. Compose 测试的性能问题

- Compose UI 测试比 Espresso **慢**（语义树匹配 + 组合开销）。
- **建议**：
  - 用 `@Test` 分方法测单个组件，而不是一个大页面全测；
  - 用 **`composeTestRule.setContent` 多次**不如拆成多个测试类（每个测试独立组合更快）；
  - 避免在测试中做真实网络请求（用假数据）。

## 代码示例

### 1. 基础测试

```kotlin
@RunWith(AndroidJUnit4::class)
class LoginScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun submitButton_triggersCallback() {
        var submitted = false

        composeTestRule.setContent {
            LoginScreen(
                state = LoginUiState(username = "tom", password = "123456"),
                onSubmit = { submitted = true }
            )
        }

        composeTestRule.onNodeWithText("登录").performClick()

        assertTrue(submitted)
    }

    @Test
    fun error_isShown() {
        composeTestRule.setContent {
            LoginScreen(state = LoginUiState(error = "用户名或密码错误"), onSubmit = {})
        }

        composeTestRule.onNodeWithText("用户名或密码错误").assertIsDisplayed()
    }
}
```

### 2. 与 ViewModel 集成（使用 Hilt）

```kotlin
@HiltAndroidTest
class UserListScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var repo: FakeUserRepository   // 通过 @TestInstallIn 替换

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun showsUsers() {
        composeTestRule.setContent {
            UserListRoute(viewModel = hiltViewModel())
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Tom").assertIsDisplayed()
        composeTestRule.onNodeWithText("Jerry").assertIsDisplayed()
    }
}
```

### 3. 测试输入与滚动

```kotlin
@Test
fun typing_updatesState() {
    lateinit var state: LoginUiState

    composeTestRule.setContent {
        val vm = remember { FakeLoginViewModel() }
        state = vm.state
        LoginScreen(state = state, onSubmit = {})
    }

    composeTestRule.onNodeWithTag("username_field").performTextInput("tom")

    composeTestRule.waitForIdle()
    assertEquals("tom", state.username)
}

@Test
fun scrollsToBottomItem() {
    composeTestRule.setContent {
        LazyColumn { items(100) { Text("Item $it") } }
    }

    composeTestRule.onNodeWithTag("list")
        .performScrollToNode(hasText("Item 99"))

    composeTestRule.onNodeWithText("Item 99").assertIsDisplayed()
}
```

### 4. 禁用/控制动画

```kotlin
@Test
fun progressAnimation() {
    composeTestRule.mainClock.autoAdvance = false

    composeTestRule.setContent { CircularProgressIndicator() }

    composeTestRule.mainClock.advanceTimeBy(500)
    // 断言中间状态
    composeTestRule.mainClock.autoAdvance = true
}
```

### 5. 截图回归（Roborazzi）

```kotlin
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun loginScreen_snapshot() {
        composeTestRule.setContent {
            LoginScreen(state = LoginUiState(username = "tom"), onSubmit = {})
        }
        composeTestRule.onRoot().captureRoboImage("build/screenshots/login.png")
    }
}
```

### 6. Robolectric 版（无需设备）

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LoginScreenRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun error_isShown() {
        composeTestRule.setContent {
            LoginScreen(state = LoginUiState(error = "出错了"), onSubmit = {})
        }
        composeTestRule.onNodeWithText("出错了").assertIsDisplayed()
    }
}
```

## 常见问题 / 面试要点

**读者问：Compose 测试和 Espresso 有什么区别？Compose 项目还需要 Espresso 吗？**

**AI答：**

1. **本质区别**：
   - **Espresso** 操作的是 **View 树**，通过 `AccessibilityNodeInfo` 定位 Android View；
   - **Compose 测试** 操作的是 **语义树（Semantics Tree）**，Compose 在组合时为节点附加语义（文本、角色、contentDescription、测试 tag）。
2. **Compose 项目的实践结论**：
   - **UI 断言用 Compose 测试**：更快、API 更简洁、可读性更好。
   - **Espresso 仍然有用**的场景：
     - 测 **Activity / Fragment 层面**的行为（Intent 跳转、生命周期、进程恢复）；
     - 测 **Compose 与 View 混用**的页面；
     - 需要**跨应用 / 系统级**交互（配合 UIAutomator）。
   - 所以：**不是二选一，而是分工**。
3. **Compose 测试的优势**：
   - **不依赖 id**：用文本/描述定位，重构布局不影响测试；
   - **API 声明式**：`onNodeWithText("x").performClick().assertIsDisplayed()` 链式表达；
   - **可测纯 Composable**：`setContent { MyComponent() }` 就能测一个小组件，不需要 Activity。
4. **限制**：Compose 测试**不能替代 Espresso 的 Activity 生命周期测试**（除非用 `createAndroidComposeRule`）。

---

**读者问：Compose 的语义树（Semantics Tree）是什么？为什么测试要基于它？**

**AI答：**

1. **是什么**：Compose 内部维护的是**组合树**（描述"如何构建 UI"），但很多功能（测试、无障碍、输入法）需要知道"界面上有什么"。Compose 因此在组合过程中为节点建立**语义信息**，形成一棵独立的**语义树**。
2. **语义信息包含**：
   - `text`（文本）、`contentDescription`（无障碍描述）；
   - `role`（Button、Checkbox、Switch 等语义角色）；
   - `TestTag`（测试专用标记）；
   - 状态：`isEnabled`、`isSelected`、`isDisplayed`、`isToggleable`；
   - 边界信息 `Bounds`（用于点击定位与滚动）。
3. **为什么测试基于它**：
   - **与实现解耦**：测试不需要知道你是用 `Column` 还是 `Box`、也不需要 id；只要用户能看到的语义一致，测试就通过 → **重构友好**；
   - **与无障碍同源**：TalkBack 读的就是语义树 → 测试里能断言的内容，无障碍也能感知 → **一石二鸟**。
4. **关键限制（面试加分）**：
   - **Compose 的 `Text` 不自动合并语义**！如果界面上是 `"Hello"` + `"World"` 两个 `Text`，语义树里是**两个节点**，而不是一个 `"Hello World"`。需要 `Modifier.semantics(mergeDescendants = true) { }` 或 `Text(..., Modifier.semantics { contentDescription = "Hello World" })`。
   - 这也是 `onNodeWithText` 找不到跨多个 `Text` 的整句的原因。
   - 解决方案：用 `useUnmergedTree = true` 区分合并前后的树；用 `onNode(hasText("Hello", substring = true))` 匹配。
5. **测试 tag 的使用**：`Modifier.testTag("username_field")` —— 当没有可读文本（如 IconButton）时用 tag 定位。

---

**读者问：Compose 测试为什么会超时？动画和协程怎么处理？**

**AI答：**

1. **超时的根因**：`waitForIdle()` 会等待"**组合空闲 + 无待处理动画 + 无待处理重组 + 空闲资源空闲**"。任何一项永远不满足，就会**无限等待 → 超时失败**。
2. **最常见原因**：
   - **无限动画**：`rememberInfiniteTransition`（如旋转 loading、脉冲动画）**永远有动画在跑** → 永不空闲。
   - **持续重组**：状态每秒都在变（如每秒更新的倒计时）。
   - **协程不断发射**：ViewModel 的 `Flow` 一直发数据。
3. **解决方案**：

```kotlin
// 方案 1：关闭自动推进，手动控制时间
composeTestRule.mainClock.autoAdvance = false
composeTestRule.setContent { MyScreen() }
composeTestRule.mainClock.advanceTimeBy(1000)   // 推进 1 秒
assertTrue(composeTestRule.onNodeWithText("1").assertIsDisplayed())
```

```kotlin
// 方案 2：在被测 Composable 中禁用动画
@Composable
fun MyScreen(animationsEnabled: Boolean = true) {
    val transition = rememberInfiniteTransition()
    val offset by transition.animateFloat(...)
    if (!animationsEnabled) { /* 直接给静态值 */ }
}
```

4. **协程的处理**：
   - 用 `runTest` + `StandardTestDispatcher` 控制 ViewModel 的协程；
   - 或在测试中注入"可手动推进"的调度器；
   - Compose 测试有 `composeTestRule.waitUntil { condition }` 可用于等待条件成立（默认 1 秒超时）。
5. **排查方法**：测试失败时先看**是不是动画**——最简单的验证是把被测 Composable 的动画参数关掉再跑一次。

---

**读者问：Compose 测试怎么覆盖"用户操作 → 状态变化"？应该测到什么粒度？**

**AI答：**

1. **推荐的分层（避免测试与实现耦合）**：
   - **UI 测试（Compose）**：验证 **State → UI** 的渲染，和 **交互 → 回调** 的传递。
     - 给一个确定的 `State`，断言界面显示了什么；
     - 点击按钮，断言**回调被调用**（用 `var clicked = false` 捕获）。
   - **ViewModel 单测**：验证 **用户意图 → State 变化** 的业务逻辑。
   - 这样职责清晰：Compose 测试不跑网络、不验证业务规则；ViewModel 测试不碰界面。
2. **示例（推荐写法）**：

```kotlin
@Test
fun clickSubmit_invokesCallback() {
    var submitted = false
    composeTestRule.setContent {
        LoginScreen(state = LoginUiState(), onSubmit = { submitted = true })
    }
    composeTestRule.onNodeWithText("登录").performClick()
    assertTrue(submitted)
}
```

3. **不推荐写法**：在 Compose 测试里塞入真实 ViewModel + 真实网络 → 慢、flaky、难定位问题。
4. **进阶**：如果确实要端到端（用 `hiltViewModel()` + Fake Repository），把它**单独归为"集成测试"**并数量控制。
5. **粒度建议**：
   - **组件级**：每个 Composable 单独测（`Button`、`UserItem`）→ 快、稳；
   - **页面级**：测关键状态组合（加载中/空/错误/有数据）；
   - **E2E**：关键业务链路，用 `createAndroidComposeRule<MainActivity>()`。

---

**读者问：Compose 有截图测试吗？怎么防止 UI 回归？**

**AI答：**

1. **有，且已成为主流**。三种方式：
   - **Paparazzi**：基于 **LayoutLib** 在 **JVM 上**渲染 Compose，**不需要设备**，速度极快（毫秒~秒级），适合每次 PR 都跑。缺点：不能测真实交互（只渲染）。
   - **Roborazzi**：在设备/Robolectric 上渲染并保存 PNG，支持**像素级差异断言**（设置阈值），也能做交互后截图。
   - **官方 `captureToImage()`**：Compose UI Test 内置（需 API 26+），拿到 `ImageBitmap` → 自己写断言或存文件。
2. **典型流程**：
   - PR 上跑 Paparazzi（快，无设备）；
   - 夜间或发版前跑 Roborazzi 真机截图；
   - 截图存入 Git（`src/test/screenshots/`），CI 里做差异对比，超过阈值则失败并输出 diff 图。
3. **注意**：截图测试**对环境敏感**（字体、主题、系统版本、屏幕尺寸差异都会导致误报）。实践要点：
   - 固定字体（`fontFamily` 显式指定）；
   - 固定主题与密度；
   - 设置合理的**差异容忍阈值**（如 0.1%）；
   - 把截图测试与功能测试**分开模块**运行，避免互相影响。
4. **价值**：功能测试只能验证"元素存在/可点击"，**截图测试能捕获"样式错位、主题异常、深色模式问题"** —— 这是功能性断言覆盖不到的盲区。

## 关联笔记

- [[单元测试]]：测试金字塔底层
- [[UI测试]]：Espresso / UIAutomator 与 Activity 层面测试
- [[../架构模式/MVVM]]：State → UI 的渲染模型
- [[../架构模式/MVI]]：状态驱动 UI 与效果处理
- [[../README]]：第 7 部分总览

## 参考资料

- Android 官方 · Compose 测试：https://developer.android.com/develop/ui/compose/testing
- Android 官方 · 语义树：https://developer.android.com/develop/ui/compose/accessibility/semantics
- Paparazzi：https://github.com/cashapp/paparazzi
- Roborazzi：https://github.com/takahirom/roborazzi
