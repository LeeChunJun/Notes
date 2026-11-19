---
tags: [android]
part: 第9部分
status: 待整理
difficulty: 高级
created: 2026-11-19
updated: 2026-11-19
related: "[[ComposeMultiplatform]], [[../README]]"
---

# Compose Multiplatform

## 一句话定义

Compose Multiplatform 是 **JetBrains 基于 Kotlin Multiplatform 打造的跨平台 UI 框架**——把 Jetpack Compose 从 Android 扩展到 **iOS、Desktop（JVM）、Web（wasmJs）**。它复用了 Compose 的**声明式 UI 模型与重组（recomposition）机制**，但依赖 **JetBrains 维护的 Compose 编译器插件 fork**（因为 Google 的官方编译器插件只支持 Kotlin/JVM 与 Android）。成熟度：**Desktop 接近生产可用，iOS 可用但生态薄弱，Web 仍在早期**。

## 核心原理

### 1. 定位：KMP 之上的 UI 层

```text
┌────────────────────────────────────────────────────────┐
│              你的 App 代码 (commonMain)                  │
│   @Composable 页面 · ViewModel · 状态 · 导航 · 依赖注入  │
└───────────────────────────┬────────────────────────────┘
                            │ 依赖
┌───────────────────────────▼────────────────────────────┐
│        Compose Multiplatform (JetBrains)                 │
│   runtime · ui · material3 · foundation · animation      │
│   resources · components（部分）                         │
└───────────────────────────┬────────────────────────────┘
                            │ 依赖
┌───────────────────────────▼────────────────────────────┐
│           Compose 编译器插件（JetBrains fork）            │
│   把 @Composable 编译到 Kotlin/Native / JVM / Wasm        │
└───────────────────────────┬────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
   Android (Kotlin/JVM)  iOS (Kotlin/Native)  Desktop (JVM)
                                          Web (wasmJs)
```

**关键关系**：

| 概念 | 定位 | 归属 |
| --- | --- | --- |
| **Kotlin Multiplatform** | 编译器层面的多平台能力（源码集、expect/actual、多目标） | JetBrains |
| **Compose Multiplatform** | 基于 KMP 的跨平台 **UI 框架** | JetBrains |
| **Jetpack Compose** | Android 官方的 Compose 实现（Google） | Google / AndroidX |
| **Compose 编译器插件** | 把 `@Composable` 编译成高效代码 | **Google 有官方版；JetBrains 维护 fork** 支持 Native/Wasm |

### 2. 为什么需要编译器插件的 fork

| 层面 | Jetpack Compose（Android） | Compose Multiplatform |
| --- | --- | --- |
| **编译器插件** | Google 官方版，基于 **Kotlin JVM IR 插件** | **JetBrains fork**，需要支持 **Kotlin/Native（LLVM）** 与 **Wasm** 后端 |
| **运行时** | `androidx.compose.runtime` | `org.jetbrains.compose.runtime`（代码基本同源） |
| **UI / Material** | `androidx.compose.material3` | `org.jetbrains.compose.material3`（Android 上直接 typealias 到 androidx） |
| **资源系统** | Android `res/` + `R` 类 | **Compose Resources**（`compose.components.resources`），统一多平台资源加载 |
| **文本** | Android `TextView` 排版 | iOS/Desktop 用 **Skia 文本渲染**（与 Flutter 类似的自绘思路） |
| **产物** | AAR | 各自平台本地产物（iOS 是 `.framework`） |

**"fork" 的本质**：Compose 编译器插件要做大量 **IR 变换与内联**（Composable 的 lambda 内联、`remember` 的 slot 分配、稳定性推断）。这些变换在 **Kotlin/JVM 的 IR 插件 API** 上是成熟的，但 **Kotlin/Native（LLVM 后端）的 IR 插件 API 支持远不完整**。JetBrains fork 里补齐了这部分能力，并调整了后端 codegen。

> **面试话术**：**"Compose Multiplatform 和 Jetpack Compose 是什么关系？"**
> **UI 模型、API、运行时几乎一致**（都是 `@Composable`、`remember`、`Column/Row`、Material3），但**底层依赖不同**：Android 上 Compose Multiplatform 的 `material3` 实际上就是 `androidx.compose.material3` 的 typealias，所以**Android 端零成本**；iOS/Desktop 上则用 JetBrains 自己实现的 runtime 与 **Skia 文本渲染**。最大的差异是**编译器插件**：Google 的官方插件不支持 Kotlin/Native，JetBrains 维护了一个 fork 来支持 iOS，这既是它的技术门槛，也是它迭代慢于官方版的原因。

### 3. 目标平台与成熟度

| 平台 | 目标 | 成熟度 | 备注 |
| --- | --- | --- | --- |
| **Android** | `androidTarget()` | ✅ **生产可用** | 基本等价于 Jetpack Compose |
| **Desktop (JVM)** | `jvm("desktop")` | ✅ **生产可用** | Kotlin/JVM + Skia；可打包成 `.app` / `.msi` / `.deb` |
| **iOS** | `iosArm64()` / `iosSimulatorArm64()` | ⚠️ **可用，生态薄弱** | Kotlin/Native；文本渲染、滚动惯性、键盘处理与 iOS 原生有差异；**无障碍支持不完整** |
| **Web (wasmJs)** | `wasmJs()` | ⚠️ **早期** | Kotlin/Wasm 后端；Canvas 渲染；包体积与启动时间待优化 |

**iOS 端的具体短板（面试常问）**：

1. **文本渲染**：Compose Multiplatform 在 iOS 上用 **Skia/CoreText 混合**排版，与 `SwiftUI`/`UIKit` 的排版结果**不完全一致**（字距、行高、emoji、中日韩字体回退）。
2. **手势**：滚动惯性（`rememberScrollState` 的 fling 行为）与 iOS 原生 `UIScrollView` 不一致。
3. **无障碍（Accessibility）**：iOS 端的 VoiceOver 支持不完整。
4. **原生控件嵌入**：缺少 Flutter 那样的 `PlatformView` 成熟方案（Compose Multiplatform 有 `UIKitView` 但能力有限）。
5. **第三方库**：绝大多数 Compose 库是 AndroidX 专有，不支持 iOS。
6. **文本输入**：`TextField` 在 iOS 上的光标、选区、输入法行为与原生有差异。

### 4. 架构：commonMain + platformMain

```text
composeApp/
├── src/
│   ├── commonMain/kotlin/       ← 可共享的 UI + 逻辑
│   │   ├── App.kt                ← 根 Composable
│   │   ├── screens/
│   │   └── components/
│   ├── androidMain/kotlin/       ← Android 入口（MainActivity）
│   ├── iosMain/kotlin/            ← iOS 入口（MainViewController.kt）
│   ├── jvmMain/kotlin/            ← Desktop 入口（main.kt）
│   └── commonTest/kotlin/
└── composeApp/build.gradle.kts
```

**关键设计**：**根 Composable 在 commonMain，各平台入口只负责"挂载"它**。

```kotlin
// commonMain：根 UI，所有平台复用
@Composable
fun App() {
    MaterialTheme {
        var count by remember { mutableStateOf(0) }
        Column(modifier = Modifier.padding(16.dp)) {
            Text("计数：$count", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = { count++ }) { Text("加一") }
        }
    }
}
```

```kotlin
// androidMain：Android 入口
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
```

```kotlin
// iosMain：iOS 入口
fun MainViewController(): UIViewController = ComposeUIViewController {
    App()
}
```

```kotlin
// jvmMain：Desktop 入口
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Compose MP") {
        App()
    }
}
```

### 5. 资源系统：Compose Resources

Android 用 `res/` + `R` 类，多平台需要另一套。Compose Multiplatform 提供了 **Compose Resources**：

```kotlin
// commonMain/kotlin/AppResources.kt
import org.jetbrains.compose.resources.Res
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

object AppResources {
    val appName: StringResource = Res.string("app_name")
    val logo: DrawableResource = Res.drawable("logo")
}
```

```kotlin
// commonMain：使用资源
@Composable
fun Header() {
    Column {
        Image(painterResource(AppResources.logo), contentDescription = null)
        Text(stringResource(AppResources.appName))
    }
}
```

```text
composeApp/src/commonMain/composeResources/
├── drawable/
├── fonts/
├── strings.xml
└── values/strings.xml
```

- 在 **Android** 上，`Res.string` 会被映射到 Android 的 `R.string`（**零成本复用系统资源机制**）。
- 在 **iOS/Desktop** 上，从编译产物中读取。

### 6. 优势与劣势对比

| | 优势 | 劣势 |
| --- | --- | --- |
| **优势** | ① **Kotlin 一套语言**，从 Android 到 iOS 到 Desktop；② **Android 端零成本**（typealias 到 androidx）；③ **UI 与逻辑都能共享**（比纯 KMP 多一层 UI 复用）；④ **Compose 的声明式与重组机制成熟**；⑤ **性能接近原生**（编译成 Kotlin/Native 机器码，非解释执行）；⑥ 官方背书（JetBrains + Google 生态） | ① **iOS 端生态薄弱**（文本渲染、手势、无障碍有差异）；② **迭代慢于官方 Compose**（fork 维护成本）；③ **iOS 第三方库几乎为零**；④ **构建链复杂**（Native 编译 + Xcode 链接 + 资源打包）；⑤ **CI 需要 Mac 节点**；⑥ **包体积**（Kotlin/Native runtime 约 10–20 MB）；⑦ **Wasm 端早期** |
| **对比 Flutter** | Kotlin 语法、Android 生态可复用、性能接近原生 | UI 一致性不如 Flutter、iOS 成熟度差距大、社区活跃度低 |

### 7. Compose Multiplatform vs Flutter

| 维度 | **Compose Multiplatform** | **Flutter** |
| --- | --- | --- |
| **语言** | Kotlin | Dart |
| **UI 一致性** | 中（iOS 有差异） | **高** |
| **Android 复用** | **极高**（直接用 AndroidX） | 低（要重写） |
| **iOS 性能** | 接近原生（Kotlin/Native） | 好（自绘引擎） |
| **iOS 成熟度** | ⚠️ 早期 | ✅ 成熟 |
| **热重载** | ✅ 支持（但 iOS 上需 Xcode 支持） | ✅ 成熟 |
| **第三方生态** | 主要是 JetBrains 自家 + 部分 AndroidX | **极其丰富**（pub.dev） |
| **包体** | Kotlin/Native runtime 约 10–20 MB | 引擎约 10–20 MB |
| **Web** | wasmJs（早期） | ✅ 成熟 |
| **团队要求** | Kotlin 即可 | Dart |

**选择建议**：

```text
已有 Kotlin/Android 团队，UI 可接受平台差异，iOS 只是"顺便支持"
  → Compose Multiplatform

要求 Android/iOS 像素级一致，UI 复杂且跨端迭代频繁
  → Flutter

只是想把 Kotlin 逻辑复用到 iOS，不追求 UI 复用
  → 纯 KMP（不引 Compose Multiplatform）
```

## 代码示例

### 1. 完整的 Compose Multiplatform 工程配置

```kotlin
// composeApp/build.gradle.kts
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("com.android.application")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    wasmJs {
        browser()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation(compose.ui)
                implementation(compose.components.uiToolingPreview)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                @OptIn(ExperimentalKotlinGradlePluginApi::class)
                implementation(compose.uiTest)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation("androidx.core:core-ktx:1.15.0")
            }
        }
    }
}

android {
    namespace = "com.example.composeapp"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.composeapp"
        minSdk = 24
        targetSdk = 34
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "com.example.composeapp.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ComposeApp"
            packageVersion = "1.0.0"
        }
    }
}
```

### 2. commonMain：共享 UI 与状态

```kotlin
// composeApp/src/commonMain/kotlin/com/example/composeapp/App.kt
package com.example.composeapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.composeapp.model.Product
import com.example.composeapp.model.ProductViewModel

/**
 * 根 Composable：所有平台（Android / iOS / Desktop / Web）共用这一个 UI。
 * 平台差异通过 expect/actual 或直接使用 compose.components.resources 处理。
 */
@Composable
fun App() {
    MaterialTheme {
        // 用 rememberSaveable 保存轻量 UI 状态（跨平台通用）
        var selectedTab by rememberSaveable { mutableStateOf(0) }

        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Compose MP 示例") })
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                        Text("列表")
                    }
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                        Text("详情")
                    }
                }
                when (selectedTab) {
                    0 -> ProductList(onSelect = { /* 切到详情 Tab */ selectedTab = 1 })
                    1 -> ProductDetail()
                }
            }
        }
    }
}

/** 商品列表：跨平台共用的业务 UI */
@Composable
fun ProductList(onSelect: (Product) -> Unit) {
    val products = rememberProductList()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(products.size) { index ->
            val product = products[index]
            ListItem(
                headlineContent = { Text(product.name) },
                supportingContent = { Text("¥${product.price / 100.0}") },
                modifier = Modifier.clickable { onSelect(product) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ProductDetail() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("选择一个商品查看详情")
    }
}
```

### 3. 共享状态与 ViewModel

```kotlin
// composeApp/src/commonMain/kotlin/com/example/composeapp/model/ProductViewModel.kt
package com.example.composeapp.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 跨平台共享的 ViewModel。
 * 注意：这里用的是 Compose 自己的状态机制（mutableStateOf + mutableStateListOf），
 * 而不是 androidx.lifecycle.ViewModel —— 后者在 KMP 下虽然可用，
 * 但 Compose Multiplatform 场景下直接用 Compose 状态更轻量。
 */
class ProductViewModel {

    var loading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    private val _products = mutableStateListOf<Product>()
    val products: List<Product> get() = _products

    suspend fun refresh() {
        loading = true
        errorMessage = null
        runCatching { repository.fetchProducts() }
            .onSuccess { _products.clear(); _products.addAll(it) }
            .onFailure { errorMessage = it.message }
        loading = false
    }
}

data class Product(val id: String, val name: String, val price: Long)
```

```kotlin
// composeApp/src/commonMain/kotlin/com/example/composeapp/model/Repository.kt
package com.example.composeapp.model

import kotlinx.coroutines.delay

/** 平台无关的仓库接口 */
interface ProductRepository {
    suspend fun fetchProducts(): List<Product>
}

/** 简单的内存实现（真实项目用 Ktor + expect/actual 注入） */
object InMemoryProductRepository : ProductRepository {
    override suspend fun fetchProducts(): List<Product> {
        delay(300)
        return listOf(
            Product("1", "Kotlin 编程", 9900),
            Product("2", "Compose 入门", 7900),
            Product("3", "跨平台实战", 12900),
        )
    }
}

val repository: ProductRepository = InMemoryProductRepository
```

### 4. 平台入口代码

```kotlin
// composeApp/src/androidMain/kotlin/com/example/composeapp/MainActivity.kt
package com.example.composeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 端：直接用官方 Jetpack Compose（typealias 到 androidx）
        setContent { App() }
    }
}
```

```kotlin
// composeApp/src/iosMain/kotlin/com/example/composeapp/MainViewController.kt
package com.example.composeapp

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * iOS 入口：返回一个 UIViewController，
 * Swift 侧通过 UIKitViewControllerRepresentable 包装进 SwiftUI。
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    App()
}
```

```swift
// iOSApp/ContentView.swift
import SwiftUI
import UIKit
import ComposeApp

// 用 UIKitViewControllerRepresentable 把 Kotlin 的 Compose 视图包进 SwiftUI
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.keyboard)   // 处理 iOS 键盘遮挡
    }
}
```

```kotlin
// composeApp/src/jvmMain/kotlin/com/example/composeapp/main.kt
package com.example.composeapp

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Compose MP 示例"
    ) {
        App()
    }
}
```

### 5. 资源定义与使用

```kotlin
// composeApp/src/commonMain/kotlin/com/example/composeapp/Resources.kt
package com.example.composeapp

import org.jetbrains.compose.resources.Res
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.DrawableResource

object AppResources {
    val appName: StringResource = Res.string("app_name")
    val welcome: StringResource = Res.string("welcome_message")
    val iconLogo: DrawableResource = Res.drawable("logo")
}
```

```kotlin
// commonMain：跨平台使用资源
@Composable
fun ResourceSample() {
    Column {
        Text(stringResource(AppResources.welcome))
        Image(painterResource(AppResources.iconLogo), contentDescription = null)
    }
}
```

```xml
<!-- composeApp/src/commonMain/composeResources/values/strings.xml -->
<resources>
    <string name="app_name">Compose MP 示例</string>
    <string name="welcome_message">欢迎使用 Compose Multiplatform</string>
</resources>
```

### 6. 跨平台 UI 测试

```kotlin
// composeApp/src/commonTest/kotlin/com/example/composeapp/AppTest.kt
package com.example.composeapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

class AppTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `列表显示商品`() {
        composeRule.setContent { App() }

        // 语义树查询：跨平台通用
        composeRule.onNodeWithText("Kotlin 编程").assertExists()
        assertTrue(true)
    }
}
```

```bash
# Desktop 上跑测试
./gradlew :composeApp:desktopTest

# Android 上跑测试
./gradlew :composeApp:testDebugUnitTest

# iOS 上跑测试（需要 Mac + Xcode）
./gradlew :composeApp:iosSimulatorArm64Test
```

### 7. 打包 Desktop 应用

```bash
# 打包 Desktop 应用（生成 dmg / msi / deb）
./gradlew :composeApp:packageDistributionForCurrentOS

# 或直接运行
./gradlew :composeApp:run

# 打包 iOS 产物（framework）
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
./gradlew :composeApp:linkReleaseFrameworkIosArm64
```

## 常见问题 / 面试要点

**读者问：Compose Multiplatform 成熟吗？现在能用它做生产项目吗？**

**AI答：**

1. **分平台看成熟度**：
   - **Android**：✅ **完全生产可用**。Compose Multiplatform 的 Android artifact 实际上就是 `androidx.compose.*` 的 typealias，行为与官方 Jetpack Compose **完全一致**，零风险。
   - **Desktop (JVM)**：✅ **基本生产可用**。Kotlin/JVM + Skia 渲染，JetBrains 自己的 IDE（IntelliJ IDEA / Fleet）就用它做 UI 层，技术上靠谱。适合工具类、内部系统、跨平台桌面客户端。
   - **iOS**：⚠️ **可用但有明确短板**。UI 能跑、性能接近原生，但**文本渲染细节、滚动惯性、输入法、无障碍（VoiceOver）与 UIKit 有差异**，也没有成熟的第三方组件库。**适合"以 Android 为主、iOS 顺带支持"的场景，不适合 iOS 体验要求高的 C 端产品**。
   - **Web (wasmJs)**：⚠️ **早期**。Kotlin/Wasm 后端刚稳定不久，包体积与启动时间不理想，生态几乎没有。
2. **最大的结构性障碍——编译器插件 fork**：
   - Google 官方的 Compose 编译器插件只支持 **Kotlin/JVM**；
   - JetBrains 必须维护一个 **fork** 来支持 **Kotlin/Native（LLVM 后端）** 与 **Wasm**；
   - 后果：① **新 Compose 特性的落地要等 fork 同步**，通常滞后官方版本；② **fork 的 bug 修复与官方不一致**；③ **Kotlin/Native 的 IR 插件 API 本身也不完整**，很多优化做不了。
3. **落地建议**：
   - **共享 70% 的 UI + 100% 的逻辑**，平台特有页面（支付、权限、分享）留在各端；
   - iOS 端**必须做真机视觉走查**（文本截断、emoji、字体回退、键盘）；
   - **不要在 iOS 端依赖 Compose 特有动画**（部分动效在 Native 后端行为不同）；
   - 关键路径在 iOS 上**做真机性能基线**（滚动、列表、启动）。
4. **面试话术**：**"Compose Multiplatform 现在到什么程度了？你会怎么用？"** —— 我会**分层看待**：Android 端零风险、Desktop 可靠、iOS 需要走查、Web 不碰。对于需要"一套 UI 多端"的项目，如果**对 iOS 细节要求高**，我会优先 Flutter；如果**团队只有 Kotlin 且 Android 是主战场**，Compose Multiplatform 是很划算的选择。

---

**读者问：Compose Multiplatform 和 KMP 是什么关系？只用 KMP 不加 Compose Multiplatform 能做 UI 吗？**

**AI答：**

1. **层次关系**：
   - **KMP**（Kotlin Multiplatform）= **编译器层面的多平台能力**：源码集分层、`expect/actual`、多目标编译。它**本身不含任何 UI 框架**。
   - **Compose Multiplatform** = **建立在 KMP 之上的 UI 框架**，提供了跨平台的 `@Composable`、`runtime`、`foundation`、`material3`、`ui`、`animation`、`resources`。
   - 关系类比：**KMP ≈ Kotlin 的"跨平台编译开关"，Compose Multiplatform ≈ "跨平台的 UI 库"**。
2. **只用 KMP 能做 UI 吗**——**能**，但要各端分别写：
   - Android 端：用 Compose（Google 官方）或传统 View；
   - iOS 端：用 SwiftUI/UIKit，通过 KMP 导出的 API 与 Kotlin 通信；
   - **UI 完全不共享**，只共享 `commonMain` 的业务逻辑与数据层。
   - 这就是当前 **KMP 最主流的落地形态**（共享逻辑，UI 各写）。
3. **加上 Compose Multiplatform 后**：
   - `commonMain` 里可以直接写 `@Composable` → **UI 也能共享**；
   - 依赖 **Compose 编译器插件的 Kotlin/Native fork**（这是最难的部分）；
   - iOS 端通过 `ComposeUIViewController { App() }` 把 Compose 视图包装成 `UIViewController`，再由 Swift 用 `UIViewControllerRepresentable` 嵌进 SwiftUI。
4. **决策矩阵**：

| 需求 | 方案 |
| --- | --- |
| 只要共享逻辑 | **纯 KMP**（无 Compose MP 依赖，最轻） |
| 要共享逻辑 + 大部分 UI，团队只有 Kotlin | **KMP + Compose Multiplatform** |
| 要共享 UI 且要求像素级一致 | **Flutter** |
| 已有 Android 应用，只想给 iOS 加功能 | **纯 KMP** 或**原生 iOS**（不要为了一个 iOS 页面引入 Compose MP） |

5. **面试话术**：**"KMP 和 Compose Multiplatform 是一回事吗？"** —— 不是。**KMP 是编译器能力**（能不能多平台编译），**Compose Multiplatform 是 UI 框架**（编译出来的东西长什么样）。KMP 不带 UI，Compose Multiplatform 依赖 KMP。可以只用前者，也可以两者叠加。

---

**读者问：Compose Multiplatform 和 Flutter 怎么选？**

**AI答：**

1. **先看决定性因素**：

| 因素 | 倾向 Compose MP | 倾向 Flutter |
| --- | --- | --- |
| **团队语言栈** | 已有 Kotlin/Android 团队 | 愿意转 Dart |
| **iOS 体验要求** | 可接受一定差异 | 要求像素级一致 |
| **UI 复用率目标** | 70–90% | 95%+ |
| **Android 现有资产复用** | **高**（AndroidX 直接用） | 低（要重写） |
| **第三方生态需求** | 少 | **多**（pub.dev） |
| **Web 需求** | 弱 | 强 |
| **热重载体验要求** | 一般 | **强** |
| **包体积敏感** | 敏感（Native runtime 10–20 MB） | 不敏感（引擎差不多大） |

2. **iOS 端成熟度的实质差异（关键决策点）**：
   - **Flutter** 在 iOS 上用的是**自绘引擎 + Skia/Impeller**，所有平台**渲染完全一致**，iOS 端已非常成熟（大量 iOS 独享 App 用 Flutter）。
   - **Compose Multiplatform** 在 iOS 上用 Kotlin/Native + Skia 文本渲染，**细节差异存在**，且**没有 Android 之外的第三方 UI 库**。
   - **如果 iOS 是核心市场 → Flutter；如果 iOS 是"顺带支持" → Compose MP。**
3. **Flutter 的隐性成本**：
   - 团队要学 Dart + Flutter Widget 体系（与 Compose 心智模型相似，但 API 不同）；
   - Android 端无法直接复用 Compose/View 代码；
   - 自绘引擎带来**输入法、无障碍、系统分享、相机预览**等平台能力的额外集成成本（但 Flutter 的插件生态已把这块做得很成熟）。
4. **Compose MP 的隐性成本**：
   - iOS 需要 **Mac + Xcode** 做构建与真机验证，CI 成本高；
   - Compose 编译器插件 fork 的**版本滞后**；
   - 遇到 Native 层的 bug 时，**可查资料少**（JetBrains 社区 + issue tracker）。
5. **面试话术**：**"如果让你现在给一个 30 人的 Android 团队做 iOS 版 App，你选哪个？"** —— 我会先问**两个问题**：① iOS 是不是核心市场（决定要不要为一致性付 Flutter 的学习成本）？② 团队愿不愿意引入 Dart（决定学习成本）。如果 iOS 只是"必须有"，团队又不想学新语言，我会推荐 **KMP 共享逻辑 + iOS 原生 UI**（连 Compose MP 都不引），因为 iOS 端体验比"UI 复用"更重要。**如果两台都要且要求一致，才上 Flutter。**

---

**读者问：Compose Multiplatform 在 iOS 上的性能如何？为什么说"接近原生"？**

**AI答：**

1. **为什么接近原生**：
   - **Kotlin/Native 编译成 LLVM 机器码**，与 Swift/ObjC 同为 AOT 原生代码，**没有解释执行、没有 JS Bridge、没有消息序列化**；
   - **Compose 的重组（recomposition）机制**只重建受影响的 Composable 子树，与 Jetpack Compose 同源，**没有"整页重建"**；
   - **Skia 渲染**（Compose MP 用 Skia 绘制到 `CAMetalLayer`/Surface），GPU 加速。
2. **性能特性**：
   - **启动**：Kotlin/Native runtime 初始化 + framework 加载，比 SwiftUI 稍慢（无 AOT 快照优化，但也不像 JIT 那么慢）；Kotlin/Native 的**二进制体积较大**（每个库的 `.klib` 都要链接）。
   - **滚动**：Compose MP 的滚动实现与 Android 相同（`LazyColumn` + 惰性布局），但 **iOS 的 fling 惯性**与 UIKit 不同，**手感有差异**。
   - **动画**：Compose 的动画 API 跨平台一致，但 iOS 上部分动画**没有系统级的曲线/语义匹配**（如 iOS 的 spring 弹性）。
3. **性能短板与原因**：
   - **文本排版**：Compose MP 在 iOS 用 Skia 排版，**不是 CoreText**，复杂文本（CJK、emoji、混排、字距调整）的性能与正确性不如原生；
   - **无障碍树**：iOS 端 VoiceOver 支持不完整，无障碍遍历可能卡顿；
   - **Compose 编译器插件 fork**：Native 后端的优化（内联、逃逸分析）不如 JVM 后端成熟，**同等代码在 iOS 上的运行效率可能比 Android 低 10–30%**（量级估计，需实测）。
4. **实践建议**：
   - **iOS 端做真机基线测试**（不要用模拟器测性能）；
   - **长列表用 `LazyColumn`**；
   - **避免在重组中做重计算**（用 `remember` + `derivedStateOf`）；
   - **图片用 `coil3`（支持 KMP）** 并设置 `size` 避免解码大图。
5. **面试话术**：**"Compose Multiplatform 在 iOS 上和 SwiftUI 比性能差多少？"** —— **架构上没有本质劣势**（都是 AOT 原生代码 + GPU 渲染），差距主要在**文本排版**（Skia vs CoreText）和 **Compose 编译器插件 fork 的优化滞后**。实测通常在可接受范围，但**必须真机验证**，尤其是长列表滚动与复杂文本页面。

---

**读者问：Compose Multiplatform 的资源系统是怎么做多平台的？和 Android 的 `res/` 有什么不同？**

**AI答：**

1. **Android 的机制**：`res/drawable/xxx.png` + `R.drawable.xxx`，由 AAPT 在构建时处理，支持密度限定（`drawable-hdpi`）、多语言（`values-zh`）、矢量图。
2. **Compose Multiplatform 的机制**：**Compose Resources**（`compose.components.resources`），资源放在 `src/commonMain/composeResources/`：
   ```text
   composeResources/
   ├── drawable/
   ├── fonts/
   ├── values/strings.xml
   └── values-zh/strings.xml
   ```
   通过 `Res.string("app_name")` / `Res.drawable("logo")` 生成 `StringResource` / `DrawableResource` 引用，运行时由 `ResourceEnvironment` 提供实际内容。
3. **平台映射**：
   - **Android**：`Res.string` 映射到 **Android 的 `R.string`**（通过 typealias / 生成代码），**完全复用 Android 资源机制**（密度、多语言、暗色模式全部免费获得）。
   - **iOS/Desktop**：资源在**编译期被打包进产物**，运行时从包内读取；多语言需要自己按 `Locale` 选择 `ResourceEnvironment`。
4. **限制**：
   - **不能直接用 Android 的 `res/` 目录**（那是 Android 专有格式）；
   - **Drawable 资源类型受限**（主要是图片与字体，**不支持 XML drawable、selector、layer-list**）；
   - **iOS 端的多语言**需要手动切换 `ResourceEnvironment`（Android 端自动跟随系统）。
5. **面试话术**：**"Compose Multiplatform 怎么保证 Android 端资源零成本？"** —— 通过把 `Res.string(...)` 映射到 Android 的 `R.string`，Compose Multiplatform 的 Android artifact 本身就是 `androidx.compose.*` 的 typealias，所以**资源加载、密度适配、多语言、暗色模式全部走 Android 原生机制**，没有任何额外开销。

---

**读者问：Compose Multiplatform 的依赖是怎么组织的？`compose.runtime` 这些依赖是怎么回事？**

**AI答：**

1. **Compose Multiplatform 插件（`org.jetbrains.compose`）** 提供一组 **"依赖访问器"**，让不同平台的 artifact 指向各自正确的实现：

```kotlin
// build.gradle.kts
compose.runtime      // 运行时（Android 上 = androidx.compose.runtime）
compose.foundation  // 基础布局（Row/Column/Box/LazyColumn）
compose.material3    // Material 3 组件
compose.materialIconsExtended
compose.ui           // Modifier、布局、绘制
compose.animation
compose.components.resources   // 跨平台资源
compose.components.uiToolingPreview
```

2. **插件的作用**：它根据当前**编译目标**（androidTarget / jvm / ios / wasmJs）把上面的访问器解析到**对应的 artifact**：
   - Android → `androidx.compose.runtime:runtime` 等；
   - iOS/Desktop → `org.jetbrains.compose.runtime:runtime` 等；
   - **Wasm** → 对应的 wasmJs artifact。
3. **这样写的好处**：
   - **业务代码只需写一份 `dependencies`**，不用为每个平台分别声明；
   - **代码里 import 的包名完全一致**（都是 `androidx.compose.*`）——JetBrains 提供了 typealias，让 `import androidx.compose.material3.Text` 在所有平台都能解析。
4. **注意**：
   - **不能使用 AndroidX 专有的 artifact**（如 `androidx.compose.material:material-icons-extended` 之外的纯 Android 库、`androidx.activity`）在 `commonMain`；
   - `androidMain` 里可以正常用 `androidx.activity:activity-compose`；
   - `commonMain` 想用 `activity-compose` 的 `ComponentActivity` → 只能用 Compose MP 提供的 `androidx.activity:activity-compose` 的 KMP 版本（**目前 `androidx.activity` 的 KMP 版本支持有限**），否则就要 `expect/actual` 自己包。
5. **面试话术**：**"Compose Multiplatform 的 compose.material3 在 Android 上是 JetBrains 自己的实现吗？"** —— 不是。插件会把它解析到 **`androidx.compose.material3:material3`**（Android 官方版本），JetBrains 只是提供了 typealias 层。所以在 Android 上**用 Compose Multiplatform 和用官方 Jetpack Compose 是同一套代码、同一个 runtime**，性能与行为完全一致；只有 iOS/Desktop 端才用 JetBrains 自己的实现。

## 关联笔记

- [[../KotlinMultiplatform/KMP与原生集成]]：KMP 的源码集、expect/actual 与目标平台
- [[../Flutter与原生混合编译/工程集成]]：Flutter 混合栈的构建与集成对比
- [[../ReactNative混合开发]]：RN 与 Compose MP / Flutter 的跨端方案对比
- [[../../07-架构、工具与生态/架构模式/MVVM]]：跨端共享的状态与视图模型组织
- [[../../07-架构、工具与生态/Jetpack库/LiveData与StateFlow]]：跨端响应式状态载体
- [[../README]]：第 9 部分总览

## 参考资料

- Compose Multiplatform 官方文档：https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform.html
- Compose Multiplatform GitHub 仓库：https://github.com/JetBrains/compose-multiplatform
- Compose Multiplatform 示例（Kotlin 官方仓库）：https://github.com/Kotlin/kotlin-multiplatform-samples
- Compose Resources 文档：https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-resources.html
- Kotlin/Wasm 文档：https://kotlinlang.org/docs/wasm.html
