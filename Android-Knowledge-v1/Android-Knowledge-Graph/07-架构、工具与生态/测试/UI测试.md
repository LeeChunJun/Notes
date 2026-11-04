---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 中级
created: 2026-11-04
updated: 2026-11-04
related: "[[单元测试]], [[Compose测试]], [[../README]]"
---

# UI测试

## 一句话定义

UI 测试（Instrumented Test / 仪器测试）在**模拟器或真机**上运行，通过 Espresso、UIAutomator 等框架**从用户视角**操作界面并断言结果，用于验证"用户真的能完成这个流程"。

## 核心原理

### 1. 仪器测试与本地单元测试的边界

| 维度 | 单元测试（`src/test`） | 仪器测试（`src/androidTest`） |
| --- | --- | --- |
| 运行环境 | JVM | 模拟器 / 真机 |
| 能否访问 Android 框架 | 不能（除非 Robolectric） | **能，真实框架** |
| 能否操作界面 | 不能 | **能** |
| 速度 | 毫秒~秒 | **分钟级** |
| 稳定性 | 高 | 中（受动画、网络、后台任务影响） |
| 调试能力 | 一般 | 强（可截图、看真实渲染） |

### 2. Espresso 的工作原理

```text
测试线程
   │  onView(matcher).perform(action)
   ▼
Espresso 通过 AccessibilityNodeInfo 树找到目标 View
   │  在主线程上执行 Action（点击、输入、滚动）
   ▼
主线程操作完成 → 等待 Idling Resource 空闲
   │  执行 Assertion
   ▼
结果
```

关键概念：

- **Matcher**：在 View 层级中定位目标（`withId`、`withText`、`withContentDescription`）。
- **Action**：对目标执行操作（`click()`、`typeText()`、`scrollTo()`、`replaceText()`）。
- **ViewAssertion**：断言状态（`matches(isDisplayed())`、`matches(withText("xxx"))`）。
- **Idling Resource**：Espresso 等待"应用空闲"再做下一步 —— 这是保证测试不 flaky 的核心机制。

### 3. Idling Resource：UI 测试稳定性的关键

- 异步任务（网络、Handler、协程）执行时，Espresso 不知道"还没忙完"，就可能提前断言导致 flaky。
- **Idling Resource** 告诉 Espresso："我忙的时候你等等"。
- **Kotlin 协程没有内置 Espresso Idling Resource** → 需要自己实现：

```kotlin
class CoroutineIdlingResource : IdlingResource {
    private val counter = AtomicInteger(0)

    override fun getName() = "Coroutine"
    override fun isIdleNow() = counter.get() == 0
    override fun registerIdleTransitionCallback(cb: IdlingResource.Callback?) {}

    fun increment() = counter.incrementAndGet()
    fun decrement() = counter.decrementAndGet()
}
```

或者用 `kotlinx-coroutines-test` 的 `TestCoroutineScheduler` + Espresso 集成库。

### 4. `ActivityScenario`：测试 Activity 的正确方式

```kotlin
@Test
fun showsUserList() {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            assertTrue(activity.findViewById<TextView>(R.id.tv_title).text.isNotEmpty())
        }
    }
}
```

- `ActivityScenario` 会正确走完 Activity 的**完整生命周期**（创建 → 启动 → 恢复 → 暂停 → 停止 → 销毁）。
- 支持 `recreate()` 模拟配置变更：

```kotlin
scenario.recreate()   // 模拟旋转屏幕
```

- **`launch` 的 Intent 定制**：

```kotlin
val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
    putExtra("userId", "42")
}
ActivityScenario.launch<MainActivity>(intent).use { ... }
```

### 5. UIAutomator：跨应用、跨系统 UI

- Espresso 只能操作**当前应用**的 UI；UIAutomator 可以操作**其他应用**（如系统设置、相机、通知栏）。
- 常用于：权限弹窗（`GrantPermissionRule`）、系统分享面板、跨应用流程。

```kotlin
@Test
fun opensSettings() {
    onView(withText("设置")).perform(click())
    device.findObject(By.text("网络和互联网")).click()   // UIAutomator
}
```

### 6. 测试运行器与依赖

```kotlin
android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
```

### 7. AndroidTest 的运行方式

```bash
# 全部仪器测试
./gradlew connectedAndroidTest

# 指定设备
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.MyTest

# 用 Gradle Managed Devices（云端设备矩阵）
./gradlew pixel2api30DebugAndroidTest
```

## 代码示例

### 1. Espresso 基础测试

```kotlin
@RunWith(AndroidJUnit4::class)
class LoginActivityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(LoginActivity::class.java)

    @Test
    fun emptyInput_showsError() {
        onView(withId(R.id.btn_login)).perform(click())

        onView(withId(R.id.tv_error))
            .check(matches(isDisplayed()))
            .check(matches(withText("用户名和密码不能为空")))
    }

    @Test
    fun successfulLogin_navigatesToHome() {
        onView(withId(R.id.et_name)).perform(typeText("tom"), closeSoftKeyboard())
        onView(withId(R.id.et_pwd)).perform(typeText("123456"), closeSoftKeyboard())
        onView(withId(R.id.btn_login)).perform(click())

        // 等待导航完成
        onView(withId(R.id.tv_welcome))
            .check(matches(withText("欢迎 tom")))
    }

    @Test
    fun longText_doesNotCrash() {
        onView(withId(R.id.et_name))
            .perform(typeText("a".repeat(500)), closeSoftKeyboard())
        onView(withId(R.id.btn_login)).perform(click())
        onView(withId(R.id.tv_error)).check(matches(isDisplayed()))
    }
}
```

### 2. 列表测试（RecyclerView）

```kotlin
@Test
fun listShowsItems() {
    onView(withId(R.id.recycler)).check(matches(hasDescendant(withText("Tom"))))
}

@Test
fun scrollToLastItem() {
    onView(withId(R.id.recycler)).perform(
        scrollToPosition(99)
    )
    onView(withId(R.id.recycler)).check(matches(hasDescendant(withText("第 100 项"))))
}
```

### 3. 权限弹窗处理

```kotlin
@get:Rule
val permissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

@Test
fun takePhoto() {
    onView(withId(R.id.btn_camera)).perform(click())
    onView(withId(R.id.iv_preview)).check(matches(isDisplayed()))
}
```

### 4. 自定义 Idling Resource 等待协程

```kotlin
class CoroutineIdlingResource : IdlingResource {
    private val counter = AtomicInteger(0)
    override fun getName(): String = "Coroutine"
    override fun isIdleNow(): Boolean = counter.get() == 0
    override fun registerIdleTransitionCallback(cb: IdlingResource.Callback?) = Unit

    fun increment() { counter.incrementAndGet() }
    fun decrement() { counter.decrementAndGet() }
}

// 在测试中注册
@Before
fun setup() {
    IdlingRegistry.getInstance().register(idlingResource)
}
@After
fun teardown() {
    IdlingRegistry.getInstance().unregister(idlingResource)
}
```

### 5. 端到端业务链路测试

```kotlin
@RunWith(AndroidJUnit4::class)
class CheckoutFlowTest {

    @Test
    fun userCanCompletePurchase() {
        ActivityScenario.launch(HomeActivity::class.java).use {
            onView(withText("立即购买")).perform(click())
            onView(withId(R.id.et_card)).perform(typeText("4111111111111111"), closeSoftKeyboard())
            onView(withId(R.id.btn_pay)).perform(click())
            onView(withText("支付成功")).check(matches(isDisplayed()))
        }
    }
}
```

### 6. 截图辅助定位失败原因

```kotlin
private fun takeScreenshot(name: String) {
    val bitmap = InstrumentationRegistry.getInstrumentation()
        .uiAutomation.takeScreenshot()
    // 保存到 app 的外部目录，便于 pull 出来查看
}
```

## 常见问题 / 面试要点

**读者问：Espresso 的原理是什么？为什么它能"操作界面"？**

**AI答：**

1. **定位原理**：Espresso 通过 **AccessibilityNodeInfo** 树（而非 `Activity.findViewById`）定位元素。这样做的好处是：
   - 可以用**用户可感知的属性**定位（文字、contentDescription），测试更贴近用户视角；
   - 不需要知道 View 的 id（重构 UI 时测试更不容易碎）。
2. **操作原理**：Espresso 把 Action（点击、输入、滚动）**post 到主线程**执行，并使用 `ViewAction` 的 `AtomicMotionEventAction` 直接注入触摸事件，所以它能真实触发 `onClick`、动画、滚动惯性等。
3. **等待机制**：每次操作后，Espresso 会等待**主线程 Looper 空闲 + 所有已注册的 Idling Resource 空闲**，然后才执行下一步断言 —— 这是它"不 flaky"的关键。
4. **限制**：
   - **只能操作当前应用的 UI**（跨应用要用 UIAutomator）；
   - **不能跨进程**；
   - **自定义 View**：如果自己重写了 `onTouchEvent` 且不调用父类，Espresso 的点击可能失效，需要给自定义 View 添加 `AccessibilityDelegate` 或改用坐标点击。
5. **面试加分**：能说出"**Espresso 基于无障碍节点树定位 + 主线程注入事件 + Idling Resource 等待空闲**"这三个点，说明是真的用过而不是背概念。

---

**读者问：UI 测试为什么容易失败（flaky）？怎么提高稳定性？**

**AI答：**

1. **常见不稳定来源**：
   - **异步未完成**：网络请求、协程、Handler 延迟任务还没结束就断言；
   - **动画未结束**：转场动画播放中点击被吞；
   - **设备/系统差异**：不同 Android 版本、屏幕尺寸、字体大小导致元素位置不同；
   - **测试间相互影响**：共享的本地数据、后台服务、单例状态；
   - **时间/随机性**：依赖当前时间、随机数、真实网络。
2. **稳定性提升手段**：
   - **等待明确条件**，不要用固定 `Thread.sleep()`。用 Espresso 的自动空闲等待，或写**自定义 ViewAssertion 轮询**（重试直到超时）。
   - **注册 Idling Resource** 覆盖你的异步框架（协程、RxJava、WorkManager）。
   - **注入可控的时钟与随机源**（Clock、Random 作为依赖注入）→ 避免时间相关的不稳定。
   - **数据隔离**：每个测试前清空数据库/SharedPreferences（用 `TestDatabaseRule` 之类）。
   - **动画关闭**：`adb shell settings put global window_animation_scale 0`（CI 环境常用）。
   - **用 `ActivityScenario` 而不是手动 startActivity**，保证生命周期正确。
   - **给 E2E 测试加重试**（失败重跑 1~2 次）作为兜底，但**不要用重试掩盖真 bug**。
3. **分层策略**：把**逻辑验证放到单元测试**（稳定、毫秒级），UI 测试只覆盖**关键链路**——这是减少 flaky 的根本手段。
4. **面试表达**："flaky 的根因是**测试和真实异步世界的耦合**。解法是：把不确定性注入化、把断言条件化、把范围最小化。"

---

**读者问：Espresso 和 UIAutomator 有什么区别？什么时候用哪个？**

**AI答：**

1. **对比**：

| 维度 | Espresso | UIAutomator |
| --- | --- | --- |
| 定位方式 | AccessibilityNodeInfo（App 内） | AccessibilityNodeInfo（跨应用） |
| 作用范围 | **仅当前 App** | 任意 App + 系统界面 |
| API 风格 | `onView(matcher).perform(action)` | `device.findObject(By.text("x"))` |
| 速度 | 较快 | 较慢 |
| 与 App 代码耦合 | 低（用可见文本定位） | 低 |
| 权限弹窗处理 | 需借助 `GrantPermissionRule` | 天然可点系统弹窗 |

2. **选择原则**：
   - **应用内交互 → Espresso**：更语义化、可读性好、失败信息清晰。
   - **跨应用/系统界面 → UIAutomator**：如"点击系统设置的某项"、"验证通知栏"、"处理系统权限弹窗"。
3. **组合使用**：`GrantPermissionRule` 预授权（Espresso 生态）+ UIAutomator 处理确实需要跨应用的操作。
4. **现代替代**：Compose 项目里，**Compose UI Test** 优先级最高；UIAutomator 主要用于系统级交互（见 [[Compose测试]]）。

---

**读者问：`ActivityScenario` 相比直接 `startActivity` 有什么优势？**

**AI答：**

1. **生命周期完整**：`ActivityScenario.launch()` 会真实走完 `onCreate → onStart → onResume`，并在测试结束时走 `onPause → onStop → onDestroy` → 避免"没走 onDestroy"导致资源泄漏与后续测试受污染。
2. **拿到 Activity 实例的方式不同**：
   - `startActivity` 需要 `Instrumentation.startActivitySync` 或在 `onActivity` 回调里拿；
   - `ActivityScenario` 提供 `scenario.onActivity { activity -> ... }` 回调，**在主线程执行**，可以安全操作 UI。
3. **支持配置变更模拟**：`scenario.recreate()` 可以模拟旋转/深色模式切换 → 用来测"状态恢复"。
4. **支持定制 Intent**：`ActivityScenario.launch(intent)` → 测"从某个入口进入页面"的场景。
5. **支持状态恢复测试**：`ActivityScenario.launch<Activity>(intent, state)` 可以传入 `Bundle` 状态，验证进程恢复。
6. **面试表达**："`ActivityScenario` 是官方推荐的 Activity 测试方式，因为它保证**生命周期正确**且提供**主线程安全的 Activity 访问入口**，还能模拟 `recreate()`。"

---

**读者问：UI 测试应该写多少？覆盖哪些场景？CI 上怎么跑？**

**AI答：**

1. **数量原则**：**UI/E2E 测试占总测试量的 10% 以内**，只覆盖**核心业务链路**（登录、下单、支付、注册），不要用它测边界值（那是单测的活）。
2. **该写 UI 测试的场景**：
   - 关键链路的**冒烟验证**（每次发版跑一次）；
   - **难以用单测覆盖**的集成行为（Intent 跳转、权限、进程恢复）；
   - **回归某个历史 bug**（把 bug 复现路径写成 UI 测试）。
3. **不该写的场景**：单个控件的显示/隐藏（用单测 + Compose 测试更合适）、纯数据校验、网络协议细节。
4. **CI 上怎么跑**：
   - 使用 **Gradle Managed Devices**（GMD）—— 在 CI 机器上启动模拟器，无需人工配置；
   - 或使用 **Firebase Test Lab**（云端真机矩阵，覆盖不同系统版本与机型）；
   - **分片并行**（`shardCount` / `testOptions.execution`）加速；
   - **失败自动抓取**：截图、logcat、测试报告（JUnit XML + HTML 报告）。
5. **CI 门禁策略**：**单测必须全绿才能合并**（快，门槛高）；**UI 测试可以设为"夜间跑 + PR 上跑关键子集"**（慢、易 flaky，门槛适度），避免阻塞开发。
6. **面试加分**：能说出"**把 flaky 的 UI 测试放在必过的门禁上，会逼着团队写出坏测试或绕过门禁**"，说明理解工程与质量的平衡。

## 关联笔记

- [[单元测试]]：测试金字塔的底层
- [[Compose测试]]：Compose 时代的 UI 测试
- [[../架构模式/MVVM]]：可测试性设计
- [[../README]]：第 7 部分总览

## 参考资料

- Android 官方 · UI 测试：https://developer.android.com/training/testing
- Android 官方 · Espresso：https://developer.android.com/training/testing/espresso
- Android 官方 · ActivityScenario：https://developer.android.com/training/testing/activities
- Android 官方 · UIAutomator：https://developer.android.com/training/testing/other-components/ui-automator
