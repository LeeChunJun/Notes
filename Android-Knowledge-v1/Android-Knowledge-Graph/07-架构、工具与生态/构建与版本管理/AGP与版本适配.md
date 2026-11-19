---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 中级
created: 2026-11-04
updated: 2026-11-04
related: "[[Gradle构建系统]], [[../基础工具链/AndroidStudio]], [[../README]]"
---

# AGP与版本适配

## 一句话定义

AGP（Android Gradle Plugin）是驱动 Android 构建的 Gradle 插件；而"版本适配"指的是在 Android 每年发布新系统后，及时调整 `compileSdk`/`targetSdk`、处理新的行为变更（Behavior Change），并满足 Google Play 的上架门槛。

## 核心原理

### 1. 三个 SDK 版本

| 属性 | 含义 | 作用 |
| --- | --- | --- |
| `minSdk` | **最低支持的 Android 版本** | 决定哪些老设备能安装；影响可用的 API 与兼容库 |
| `targetSdk` | **你已适配的目标版本** | **系统据此决定启用哪些行为变更**（这是关键） |
| `compileSdk` | **编译时使用的 API 版本** | 决定你能调用哪些新 API（与 targetSdk 无关） |

**核心认知**：`compileSdk` 管"能不能用这个 API"，`targetSdk` 管"系统会不会对你改变行为"。**两者可以不同**（通常 `compileSdk` ≥ `targetSdk`）。

```kotlin
android {
    compileSdk = 35        // 用 Android 15 的 API 编译
    defaultConfig {
        minSdk = 24        // 支持 Android 7.0+
        targetSdk = 35     // 已适配 Android 15 的行为变更
    }
}
```

### 2. 版本对应关系表

| Android 版本 | API Level | 关键变更 | AGP 要求（最低） | 编译要求 |
| --- | --- | --- | --- | --- |
| Android 14 (Upside Down Cake) | 34 | 前台服务类型必填；targetSdk 34 起强制 edge-to-edge；运行时读写媒体权限；`registerReceiver` 导出标志 | AGP 8.3+ | JDK 17 |
| Android 15 (Vanilla Ice Cream) | 35 | 强制 edge-to-edge；16KB page size；前台服务 6h 超时；DataStore 偏好修复；`PendingIntent` 隐式 Intent 限制 | AGP 8.6+（16KB 需 8.5.1+） | JDK 17 |
| Android 16 (Baklava) | 36 | targetSdk 36 的行为变更；JobScheduler 配额收紧；并行任务优化；更强的 `back` 预测 | AGP 8.9+/8.10+ | JDK 17+ |
| Android 17 | 37（预览/发布中） | 以官方 Release Notes 为准 | 最新 AGP | JDK 21 推荐 |

> ⚠️ 具体数值以官方 Release Notes 为准（每年 Q2 正式发布，Q3/Q4 推 beta）。

### 3. Google Play 的 targetSdk 门槛（必须知道）

| 生效时间 | 新应用 / 更新应用的 targetSdk 要求 |
| --- | --- |
| 2024-08-31 | 新应用与更新需 **targetSdk ≥ 34**（Android 14） |
| 2025-08-31 | 新应用与更新需 **targetSdk ≥ 35**（Android 15） |
| 2026-08-31（预期） | 新应用与更新需 **targetSdk ≥ 36**（Android 16） |

**同时还有两条历史规则**：
- 自 **2023-08-31** 起，新应用必须 **targetSdk ≥ 33**；
- 自 **2024-11-01** 起，**必须支持 64 位**（`armeabi-v7a` + `arm64-v8a`）；
- 现有应用若不更新，可能被**从商店下架或无法发布更新**。

### 4. Android 14 关键变更（targetSdk 34）

1. **前台服务必须声明类型**：
   ```xml
   <service
       android:name=".UploadService"
       android:foregroundServiceType="dataSync" />
   ```
   且运行时需 `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`。
2. **强制 Edge-to-Edge**（targetSdk 35 时全面强制，34 起部分）：
   - 状态栏/导航栏变透明，App 内容绘制到全屏；
   - 必须处理**状态栏 inset**，否则内容被遮挡；
   - **Compose**：`enableEdgeToEdge()` + `WindowInsets.safeDrawing`；
   - **View 体系**：`ViewCompat.setOnApplyWindowInsetsListener`。
3. **运行时读写媒体权限**：访问 `MediaStore` 的图片/视频需 `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`。
4. **`registerReceiver` 必须显式指定导出标志**（`RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED`）。
5. **非 SDK 接口限制加强**（灰名单进一步收紧）。
6. **Java 17 最低要求**：AGP 8.x 要求 JDK 17。

### 5. Android 15 关键变更（targetSdk 35）

1. **强制 Edge-to-Edge**（targetSdk 35 起全量）：
   - 不再提供状态栏/导航栏背景自动处理；
   - 必须自己适配 insets，或使用官方 `enableEdgeToEdge()`。
2. **16KB page size 支持**（Android 15 QPR1+ 设备逐步普及）：
   - 未适配的 App 在 16KB 设备上**无法运行**（加载 so 失败）；
   - **适配方法**：AGP ≥ 8.5.1 + NDK r27+，重新打包 native 库；
   - **检查工具**：`/opt/android-sdk/build-tools/<version>/zipalign -c -P 16 -v <apk>`。
3. **前台服务超时**：targetSdk 35 的 `dataSync` 类型前台服务**每 24 小时最多 6 小时**，超时必须调用 `stopForeground()`，否则抛 `ForegroundServiceDidNotStopInTimeException`。
4. **`PendingIntent` 限制**：禁止向其他应用的可变隐式 Intent 发送 `PendingIntent`。
5. **`Intent` 的 `SENSITIVE` 元数据**。
6. **DataStore 偏好数据损坏修复**。
7. **ART GC 优化**：部分设备上 GC 时间减少。

### 6. Android 16 关键变更（targetSdk 36）

1. **`back` 预测 API 正式**：`OnBackInvokedCallback` 成为推荐做法（targetSdk 36 起预测返回默认启用）。
2. **JobScheduler 配额收紧**：作业执行时间配额调整，后台作业可能被延迟。
3. **JobScheduler 并行任务优化**。
4. **自适应图标支持 monochrome**（主题图标）。
5. **更严格的 `openConnection` 校验**。
6. **Rich Ongoing Notifications**（持续通知可显示多媒体内容）。
7. **设备上的 XML 直接打印**（移除打印框架）。
8. **增量式编译优化**。

> 具体清单请以官方发布说明为准。

### 7. AGP / Gradle / JDK 版本对应关系

| AGP | 最低 Gradle | 最低 JDK | 备注 |
| --- | --- | --- | --- |
| 8.0 | 8.0 | 17 | |
| 8.1 | 8.0 | 17 | |
| 8.2 | 8.2 | 17 | |
| 8.3 | 8.4 | 17 | |
| 8.4 | 8.6 | 17 | |
| 8.5 | 8.7 | 17 | **16KB page size 支持** |
| 8.6 | 8.7 | 17 | |
| 8.7 | 8.9 | 17 | |
| 8.8 | 8.10.2 | 17 | |
| 8.9+ | 8.11+ | 17 | 支持 compileSdk 36 |

> **规则**：AGP 版本要求 Gradle 版本，Gradle 版本要求 JDK 版本。**升级时按"JDK → Gradle → AGP → compileSdk"的顺序**。

### 8. 版本适配的标准流程

```text
1. 读官方 Release Notes（Behavior Changes / Changes by API level）
2. 升级 compileSdk + AGP（+ Gradle + JDK）
3. 跑 lint（会报告 NewApi / ExpiredTargetSdkVersion）
4. 逐条处理行为变更（重点：权限、前台服务、后台启动、Edge-to-Edge、存储）
5. 升级 targetSdk
6. 跑全量测试 + 手工回归
7. 灰度发布 + 监控崩溃与 ANR
```

### 9. Edge-to-Edge 适配实战（最常被问到）

```kotlin
// 1. Activity
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()          // androidx.activity:activity-ktx 1.8+
    setContent { MyApp() }
}

// 2. Compose：内容用 safeDrawing padding
@Composable
fun MyApp() {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing
    ) { padding ->
        Column(Modifier.padding(padding)) { /* ... */ }
    }
}
```

```xml
<!-- 3. View 体系 -->
<FrameLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.google.android.material.appbar.AppBarLayout
        android:id="@+id/appBar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:fitsSystemWindows="true" />
</FrameLayout>
```

```kotlin
// 或手动处理 insets
ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
    insets
}
```

### 10. 16KB page size 检查

```bash
# 检查 APK 是否已按 16KB 对齐
zipalign -c -P 16 -v app-release.apk

# 检查 native 库
llvm-readelf -l lib/arm64-v8a/libnative.so | grep LOAD
```

**适配要点**：
- AGP ≥ 8.5.1、NDK ≥ r27、CMake ≥ 3.22.1；
- 未自带 native 库的纯 Kotlin/Java App 通常**无需改动**（只影响 .so 的加载）；
- 第三方 SDK 含 .so 时需确认其已按 16KB 编译。

## 代码示例

### 1. 三个 SDK 版本的完整配置

```kotlin
// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.app"

    // compileSdk：编译期使用的 API 版本（Android 15 = API 35）
    // 它决定"你能调用哪些新 API"，与运行时行为无关
    compileSdk = 35

    defaultConfig {
        // minSdk：最低支持的 Android 版本（Android 7.0 = API 24）
        // 它决定"这台设备能不能装上我的 App"，升级它 = 放弃老设备用户
        minSdk = 24

        // targetSdk：你已适配的目标版本
        // 它决定"系统会对你的 App 启用哪些行为变更"，是最需要测试的一项
        targetSdk = 35

        applicationId = "com.example.app"
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"     // 与正式包共存，便于同时安装
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true            // 开启 R8 混淆
            isShrinkResources = true          // 资源裁剪
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17   // AGP 8.x 要求 JDK 17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 三个版本的一句话总结（面试直接背）：
// compileSdk —— "能不能写这个 API"
// targetSdk  —— "系统怎么对你（行为变更）"
// minSdk     —— "能不能装到这台设备上"
```

### 2. Android 14 前台服务类型：Manifest 声明 + 运行时传类型

```xml
<!-- app/src/main/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Android 14 起：dataSync 类型的前台服务必须声明对应权限 -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

    <application>
        <!-- foregroundServiceType：声明这个前台服务属于哪一类
             系统据此做权限校验与时长配额管控；
             targetSdk 34 起不声明会直接抛 IllegalStateException -->
        <service
            android:name=".sync.SyncService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
    </application>
</manifest>
```

```kotlin
// SyncService.kt —— 运行时启动前台服务时必须带上类型
class SyncService : Service() {

    private val NOTIFICATION_ID = 1001

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ServiceCompat.startForeground 兼容不同 API 等级：
        // API 29+ 走三参数重载（带类型），低版本自动降级为两参数
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,   // 与 Manifest 声明的类型一致
        )

        // Android 15：targetSdk 35 的 dataSync 服务每 24 小时最多跑 6 小时
        // 超时系统会抛 ForegroundServiceDidNotStopInTimeException 并 ANR，
        // 所以要主动记录启动时间，到点主动 stopForeground
        scheduleStopAt(6 * 60 * 60 * 1000L)

        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在同步数据")
            .setSmallIcon(R.drawable.ic_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)   // 前台服务通知必须是低优先级
            .build()

    private fun scheduleStopAt(delayMs: Long) {
        handler.postDelayed({
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, delayMs)
    }
}
```

```bash
# 验证前台服务类型是否正确声明（面试可能让你现场查）
aapt dump badging app-release.apk | grep -i foregroundServiceType
# 或看完整清单
apkanalyzer manifest print app-release.apk | grep -A3 "<service"
```

### 3. Android 15 Edge-to-Edge 适配

```kotlin
// MainActivity.kt —— Compose 版适配
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // enableEdgeToEdge()：透明化系统栏，并做好默认的图标明暗适配
        // 需要 androidx.activity:activity-ktx:1.8+（或 1.9+）
        enableEdgeToEdge()
        setContent { MyApp() }
    }
}

@Composable
fun MyApp() {
    // 方案一：Scaffold 层面统一处理 insets（推荐）
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,   // 安全区 = 状态栏 + 导航栏 + 输入法
        topBar = { TopAppBar(title = { Text("首页") }) },
    ) { padding ->
        // padding 已包含系统栏避让，直接消费即可
        LazyColumn(contentPadding = padding) {
            items(list) { item ->
                Text(item.text, Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
fun DetailScreen() {
    Column(
        // 方案二：局部用 Modifier 扩展，更灵活
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()          // 只加安全区内边距
    ) {
        Text("详情")
    }
}

@Composable
fun FormWithIme() {
    // 键盘弹出时要用 ime() 而不是 safeDrawing（safeDrawing 已含 ime，但组合更精确）
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = with(LocalDensity.current) { imeBottom.toDp() }),
    ) {
        // 输入框
    }
}
```

```kotlin
// View 体系适配：手动处理 WindowInsets
class DetailActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1) 开启 edge-to-edge
        enableEdgeToEdge()
        setContentView(R.layout.activity_detail)

        // 2) 手动消费 insets：把系统栏尺寸转成 padding 施加给根布局
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            // systemBars() = 状态栏 + 导航栏；displayCutout() = 刘海/挖孔
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            // 返回 insets 表示"未消费"，子 View 还能继续拿到
            insets
        }
    }
}
```

```xml
<!-- View 体系的另一种写法：fitsSystemWindows（简单但粒度粗） -->
<FrameLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.google.android.material.appbar.AppBarLayout
        android:id="@+id/appBar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:fitsSystemWindows="true" />
</FrameLayout>
```

```kotlin
// 进阶：WindowInsetsAnimationCompat —— 共享元素随状态栏高度变化
class DetailActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 监听 insets 动画（状态栏展开/收起），让图片高度跟着变
        ViewCompat.setOnApplyWindowInsetsListener(binding.heroImage) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 展开时高度 = 图片原始高度 + 状态栏高度；收起时恢复原高度
            val expanded = insets.isVisible(WindowInsetsCompat.Type.statusBars())
            view.layoutParams.height = if (expanded) {
                resources.getDimensionPixelSize(R.dimen.hero_expanded_height) + bars.top
            } else {
                resources.getDimensionPixelSize(R.dimen.hero_collapsed_height)
            }
            view.requestLayout()
            insets
        }
    }
}
```

### 4. 16KB page size 的打包配置

```kotlin
// app/build.gradle.kts —— 16KB 适配所需配置
android {
    compileSdk = 35

    // ndkVersion 必须 >= r27（r27 是首个默认对齐 16KB 的 NDK 版本）
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 24
        targetSdk = 35

        // 关键：让 AGP 用 16KB 对齐规则打包 native 库
        // AGP 8.5.1+ 默认可用；useLegacyPackaging = false 表示
        // "不把 .so 压缩在 APK 里"（安装时由系统解压并按 16KB 对齐）
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    packaging {
        // jniLibs.useLegacyPackaging = false
        //   false（推荐）：.so 不压缩，安装时对齐到 16KB → 满足 16KB 要求
        //   true（不推荐）：.so 以压缩形式存放在 APK 内，无法保证 16KB 对齐
        jniLibs {
            useLegacyPackaging = false
        }
    }

    externalNativeBuild {
        cmake {
            version = "3.22.1"    // CMake >= 3.22.1
            arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            // 该宏让 NDK 编译出的 so 支持 flexible page size
        }
    }
}
```

```bash
# ===== 16KB 适配的验证命令（面试官很可能让你现场敲）=====

# ① 检查 APK 内所有 .so 是否按 16KB 对齐
$ANDROID_HOME/build-tools/34.0.0/zipalign -c -P 16 -v app-release.apk
# -c：只做检查不修改；-P 16：按 16KB 页大小校验；
# 输出 "Verification Successful" 即通过

# ② 检查单个 .so 的 LOAD 段对齐值（应为 0x4000 = 16KB）
llvm-readelf -l lib/arm64-v8a/libnative.so | grep LOAD
# 若显示 Align 是 0x1000（4KB），说明这个 so 没适配 16KB

# ③ 检查 APK 里有没有原生库（纯 Kotlin/Java App 通常无需处理）
unzip -l app-release.apk | grep -E "\.so$"
# 无输出 = 没有 native 库 = 16KB 问题与你无关

# ④ 官方校验脚本（Google 提供，遍历所有 .so）
# bash google-verify-page-size.sh app-release.apk
```

```text
===== 16KB 适配要点（面试口述）=====
- 是什么：Android 15 QPR1 起部分设备用 16KB 内存页（此前 4KB）
- 影响：只有含 .so 的 App 会因 dlopen 对齐失败而闪退
- 条件：AGP >= 8.5.1、NDK >= r27、CMake >= 3.22.1
- 配置：packaging.jniLibs.useLegacyPackaging = false
- 验证：zipalign -c -P 16 -v app.apk
- 第三方 SDK：确认其已发 16KB 兼容版本，否则等或自己重编
```

### 5. buildTypes 与 productFlavors 多渠道配置

```kotlin
// app/build.gradle.kts
android {
    // 声明维度（可多维，如 "environment" × "tier"）
    flavorDimensions += listOf("environment", "tier")

    productFlavors {
        // 维度一：环境
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"      // 允许与正式包共存
            versionNameSuffix = "-dev"
            buildConfigField("String", "BASE_URL", "\"https://dev-api.example.com\"")
            buildConfigField("boolean", "ENABLE_LOG", "true")
            resValue("string", "app_name", "示例Dev")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "BASE_URL", "\"https://api.example.com\"")
            buildConfigField("boolean", "ENABLE_LOG", "false")
            resValue("string", "app_name", "示例")
        }

        // 维度二：版本层级
        create("free") {
            dimension = "tier"
            // 用 resValue 覆盖资源 + 源码集实现功能裁剪
            sourceSets["main"].res.srcDirs("src/free/res")
        }
        create("paid") {
            dimension = "tier"
            sourceSets["main"].res.srcDirs("src/paid/res")
            buildConfigField("boolean", "PAID", "true")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 签名从 keystore.properties 读，不硬编码在版本库里
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val props = java.util.Properties().apply {
                    keystorePropertiesFile.inputStream().use { load(it) }
                }
                signingConfigs {
                    create("release") {
                        storeFile = file(props.getProperty("storeFile"))
                        storePassword = props.getProperty("storePassword")
                        keyAlias = props.getProperty("keyAlias")
                        keyPassword = props.getProperty("keyPassword")
                    }
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true    // 使用 buildConfigField 必须打开
    }
}

// 变体名 = BuildType × Flavor 各维度的笛卡尔积
// 例：devFreeDebug、devFreeRelease、paidProdRelease …
// 每个变体对应一套独立的编译与打包任务（任务名 = 任务名 + 变体名）
```

```bash
# 查看所有变体与对应任务
./gradlew tasks --group build
# ./gradlew :app:assembleDevFreeDebug
# ./gradlew :app:assembleProdPaidRelease
# ./gradlew :app:installDevFreeDebug
```

```kotlin
// 变体过滤：避免变体数量爆炸（面试加分点）
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variantBuilder ->
        // 只保留一个 debug 变体参与构建，其余禁用 → 任务数骤减
        variantBuilder.enable = variantBuilder.productFlavors
            .none { it.second == "free" }
    }
}
```

### 6. gradle.properties 性能开关

```properties
# gradle.properties —— 构建性能开关（每项都要能说清作用）

# ===== JVM 参数 =====
# 给 Gradle Daemon 分配堆内存与元空间，避免大型项目 OOM
# -Xmx4g：最大堆 4GB（大项目可给 6g~8g）
# -XX:MaxMetaspaceSize=1g：元空间上限（Kotlin DSL 编译会吃元空间）
# -Dfile.encoding=UTF-8：固定编码，避免中文注释乱码
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8

# ===== 并行构建 =====
# 多模块之间无依赖的 Task 并行执行（Gradle Worker 数为 CPU 核数）
org.gradle.parallel=true

# ===== 构建缓存 =====
# 缓存 Task 输出；切分支、CI 上可复用未变更 Task 的产物
# 命中缓存的 Task 显示 "FROM-CACHE"，跳过执行
org.gradle.caching=true

# ===== 配置缓存（收益最大的一项）=====
# 缓存"配置阶段"结果，下次构建直接复用，跳过大部分 build.gradle.kts 的执行
# 首次构建后通常提速 30%~50%
# 注意：脚本里不能读外部可变状态（时间戳、环境变量、网络请求）
org.gradle.configuration-cache=true

# ===== 按需配置 =====
# 只配置本次任务涉及的模块（单模块项目收益有限，多模块明显）
org.gradle.configureondemand=true

# ===== Kotlin 相关 =====
kotlin.code.style=official           # Kotlin 官方代码风格
kotlin.incremental=true              # 增量编译
# 小项目 in-process 更快；大项目 daemon 更稳（避免频繁启停）
kotlin.compiler.execution.strategy=daemon
# 升级 Kotlin 2.0+ 后 K1/K2 前端统一时用 K2
kotlin.useK2=true

# ===== AndroidX / Jetifier =====
android.useAndroidX=true             # 启用 AndroidX（必需）
# 旧 support 库迁移到 AndroidX 的工具。
# 依赖已全部迁完就应该关掉它 —— Jetifier 会扫描每个依赖并改写字节码，明显拖慢构建
android.enableJetifier=false

# ===== R 类与资源 ID =====
# 每个模块只生成自己的 R 类（传递依赖不再生成 R）→ 减少编译产物、加快增量编译
android.nonTransitiveRClass=true
# 资源 ID 编译期不再是 final（配合动态资源/热修复时需要）
android.nonFinalResIds=false

# ===== 构建行为 =====
# 依赖统一在一个模块用注解生成，避免每模块生成重复的 BuildConfig
android.defaults.buildfeatures.buildconfig=false
android.defaults.buildfeatures.resvalues=true
android.defaults.buildfeatures.shaders=false
```

```bash
# 验证配置缓存是否真的生效
./gradlew help --configuration-cache
# 输出 "Configuration cache entry reused" 表示命中（复用成功）
# 输出 "Configuration cache entry stored" 表示本次已写入缓存（第一次会慢一点）

# 用 --configuration-cache-problems=warn 只警告不报错，方便排查不兼容写法
./gradlew assembleDebug --configuration-cache-problems=warn
```

### 7. Lint 对 targetSdk 问题的告警配置

```kotlin
// app/build.gradle.kts
android {
    lint {
        // 只在 release 构建跑 lint（debug 跑太慢）
        checkReleaseBuilds = true

        // 构建中断策略：
        //   true  = 有 error 就中断构建（CI 强制卡质量）
        //   false = 只出报告不中断（本地开发友好）
        abortOnError = true

        // 只在 CI 上 fatal，本地只警告
        val isCi = System.getenv("CI") == "true"
        abortOnError = isCi

        // 致命级别：只有 Error 级别才中断（Warning 不挡构建）
        fatalOnly = true

        // 告警级别：把特定问题升到 Error（targetSdk 相关必配）
        warning += setOf(
            "ExpiredTargetSdkVersion",      // targetSdk 低于 Play 政策要求
            "OldTargetApi",                 // 用了已过时的 targetSdk
            "InlineApi",                    // 危险 API（如 getIdentifier）
        )
        error += setOf(
            "ExpiredTargetSdkVersion",      // 升级为 Error：卡住不合规的发布
            "ForegroundServicePermission",  // Android 14 前台服务类型/权限缺失
            "ForegroundServiceType",        // foregroundServiceType 未声明
            "UnsafeImplicitIntentLaunch",   // 隐式 Intent 启动导出组件
            "BatteryLife",                  // 明显的电量问题（如后台轮询）
        )

        // 关闭不关心的检查（减少噪声）
        disable += setOf(
            "GradleDependency",             // 依赖版本过时（我们用 Catalog 统一管理）
            "OldTargetApi",                 // 与上面的 warning 冲突，取其一
            "TypographyEllipsis",           // UI 文案规范
        )

        // 基线文件：把存量问题记下来，只关注新增问题
        // baseline = file("lint-baseline.xml")
        // baseline += "lint-baseline.xml"
        checkDependencies = true
    }
}
```

```xml
<!-- lint-baseline.xml（片段）—— 已修复/暂时接受的问题记在这里，新问题不会被吞掉 -->
<issues format="6" by="lint 8.7.3">
    <issue
        id="ExpiredTargetSdkVersion"
        severity="warning"
        message="The value of targetSdkVersion (33) is too low.">
        <location
            file="build.gradle.kts"
            line="12"
            column="9" />
    </issue>
</issues>
```

```bash
# 生成报告（HTML 可视化）
./gradlew :app:lintRelease
# 报告位置：app/build/reports/lint-results-release.html

# 只跑与 targetSdk / API 相关的检查
./gradlew :app:lintRelease -Pandroid.experimental.lint.missingBaselineIsEmptyBaseline=true

# CI 里以文本形式输出，便于日志留存
./gradlew :app:lintVitalRelease   # release 上只跑致命级检查（Play 商店上传也会跑）
```

## 常见问题 / 面试要点

**读者问：`compileSdk`、`targetSdk`、`minSdk` 有什么区别？升级哪个会影响用户？**

**AI答：**

1. **各自的作用**：
   - **`minSdk`**：决定**能不能装**到这台设备上。升级它 = **放弃老设备**（影响用户规模）。
   - **`compileSdk`**：决定**编译期能用哪些 API**。升级它**不影响运行时行为**，但如果代码用了新 API 又没做版本判断，在老设备上会崩（`NoSuchMethodError` / `ClassNotFoundException`）。
   - **`targetSdk`**：决定**系统对你启用哪些行为变更**。升级它 = **改变运行时行为**（最需要测试）。
2. **谁影响用户**：
   - 升 `minSdk` → **直接剔除老设备用户**（业务决策，要看用户设备分布）；
   - 升 `targetSdk` → **行为变化**，可能导致功能异常（如权限弹窗变化、后台启动受限）；
   - 升 `compileSdk` → 本身安全，但要**配合 `Build.VERSION.SDK_INT` 判断**使用新 API。
3. **推荐做法**：
   - `compileSdk` 保持**最新**（拿新 API 和新 lint 检查）；
   - `targetSdk` **跟随 Play 政策推进**（每年一次，集中在发版窗口）；
   - `minSdk` **谨慎提升**（先看数据：Google Play 控制台有设备分布统计）。
4. **面试表达**："**compileSdk 决定'能不能写'，targetSdk 决定'系统怎么对你'，minSdk 决定'能不能装'。**"

---

**读者问：Android 15 强制 edge-to-edge 是什么？不改会有什么后果？**

**AI答：**

1. **是什么**：Edge-to-Edge = App 内容**延伸到屏幕边缘**，状态栏和导航栏**透明**，由 App 自己决定如何在安全区域内绘制内容。
2. **触发条件**：`targetSdk = 35`（Android 15）起**强制启用**，系统不再自动为 App 绘制状态栏/导航栏背景。
3. **不改的后果**：
   - **内容被状态栏/导航栏遮挡**（顶部标题看不见、底部按钮点不到）；
   - **沉浸式手势区域冲突**（边缘滑动被系统栏拦截）；
   - 视觉上"上下被切掉"。
4. **正确适配三步**：
   - **Activity**：`enableEdgeToEdge()`（`androidx.activity:activity-ktx:1.8+`）；
   - **Compose**：`Scaffold(contentWindowInsets = WindowInsets.safeDrawing)` 或给内容加 `Modifier.safeDrawingPadding()`；
   - **View 体系**：`fitsSystemWindows="true"` 或 `ViewCompat.setOnApplyWindowInsetsListener` 手动处理 `WindowInsetsCompat.Type.systemBars()`。
5. **进阶（面试加分）**：
   - 用 `WindowInsetsAnimationCompat` 做**共享元素转场**（如图片从列表页展开到详情页时，图片随状态栏高度变化）；
   - 区分 `systemBars()`（状态栏+导航栏）与 `ime()`（键盘）→ 键盘弹出时用 `ime()` 做 padding。
6. **面试表达**："**Edge-to-Edge 不是'把背景色铺满屏幕'，而是'让 App 负责绘制状态栏区域'，因此必须做 inset 处理。**"

---

**读者问：16KB page size 是什么？为什么要适配？我怎么检查？**

**AI答：**

1. **是什么**：Android 从 Android 15 QPR1 开始，**部分设备支持 16KB page size**（此前一直是 4KB）。这是内存管理层面的变化——内核以 16KB 为粒度管理内存。
2. **为什么要适配**：如果 App 里的 **native 库（.so）按 4KB 对齐**，在 16KB 设备上**加载会失败**（`dlopen failed: ... LOAD segment is not aligned`），导致**闪退**。
3. **影响范围**：
   - **纯 Kotlin/Java、无 native 库的 App**：通常**不受影响**；
   - **含 .so 的 App**（第三方 SDK、NDK、Flutter/Unity/React Native、压缩库如 so 加密）**需要处理**。
4. **适配方法**：
   - **AGP ≥ 8.5.1**、**NDK ≥ r27**、**CMake ≥ 3.22.1**；
   - **第三方 SDK**：检查其是否已发布 16KB 兼容版本（没发布 → 只能等或自己重编译）；
   - **Flutter**：升级到支持 16KB 的版本（Flutter 3.35+）。
5. **检查工具**：

```bash
# 检查 APK 中的 so 是否按 16KB 对齐
zipalign -c -P 16 -v app-release.apk

# 检查单个 so 的 LOAD 段对齐
llvm-readelf -l lib/arm64-v8a/libfoo.so | grep LOAD
```

6. **面试表达**："16KB page size 是**内存页粒度**的升级，只有**含 native 库的 App** 才需要适配；适配靠**新版 AGP/NDK 重新打包** + `zipalign -P 16` 校验。"

---

**读者问：Android 14 的前台服务类型（foregroundServiceType）是什么？为什么必须声明？**

**AI答：**

1. **背景**：Android 长期存在"前台服务被滥用做后台保活"的问题（尤其国内 App）。系统从 14 开始要求**前台服务必须声明具体类型**，并做**权限与配额管控**。
2. **必须做的两件事**：
   - **Manifest 声明**：
     ```xml
     <service
         android:name=".SyncService"
         android:foregroundServiceType="dataSync" />
     ```
   - **运行时传类型**（API 29+）：
     ```kotlin
     ServiceCompat.startForeground(
         service, NOTIFICATION_ID, notification,
         ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
     )
     ```
3. **类型枚举**：`camera`、`mediaPlayback`、`mediaProjection`、`microphone`、`phoneCall`、`dataSync`、`mediaProcessing`、`specialUse` 等。
4. **每种类型的权限要求**（例如 `dataSync` 需要 `FOREGROUND_SERVICE_DATA_SYNC` 权限，Android 14 新增）：
   ```xml
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
   ```
5. **Android 15 的额外限制**：`dataSync` 类型**每 24 小时最多运行 6 小时**，超时必须 `stopForeground()`，否则系统抛异常并 ANR。
6. **面试表达**："前台服务类型是 Android 14 引入的**监管机制**，目的是区分'用户可见'（相机、媒体播放）与'纯后台'（数据同步）场景，后者的时长被严格限制。"

---

**读者问：每次 Android 发布新版本，App 需要做哪些适配工作？有没有标准流程？**

**AI答：**

1. **标准流程（六步）**：
   1. **读官方文档**：`Changes by API level` → `Behavior changes` → `Changes in API 35`；
   2. **升级工具链**：JDK → Gradle → AGP → `compileSdk`；
   3. **跑 Lint**：会报 `NewApi`、`ExpiredTargetSdkVersion`、`ForegroundServiceType` 等；
   4. **逐条处理行为变更**（按影响面排序：权限 > 后台启动 > 存储 > 系统栏 > 其他）；
   5. **升级 `targetSdk`**，跑全量测试 + 手工回归；
   6. **灰度发布 + 监控**（崩溃率、ANR、特定 API 崩溃）。
2. **常见需要适配的行为变更类型**：
   - **权限相关**：精确位置（不再请求定位就能用 Wi-Fi 扫描）、媒体读写权限（Android 14）、通知权限（13）、后台定位（后台自动定位需单独授权，14 起更严格）；
   - **后台限制**：后台启动 FGS 需有豁免场景（14）、后台服务启动限制；
   - **存储**：Scoped Storage 限制（10 起）、`MediaStore` 隔离（10）、`READ_MEDIA_*`（13/14）；
   - **系统栏**：Edge-to-Edge（15 强制）；
   - **网络**：`cleartext traffic` 默认禁止（9 起）；
   - **WebView**、**Intent**、**PendingIntent** 的限制。
3. **风险控制**：
   - **不要一次性把 targetSdk 跳到最新**：先升 compileSdk 适配 API，再分批升 targetSdk；
   - **优先用官方兼容库**（`androidx.core` 的 `WindowCompat`、`ActivityResultContracts` 等已做兼容）；
   - **用 `Build.VERSION.SDK_INT` 做版本分支**，配合 `lint` 的 `NewApi` 检查。
4. **面试表达**："版本适配是**持续性工作**，核心流程是'**读行为变更清单 → 升工具链 → lint 扫描 → 分级处理 → 灰度验证**'，而且我习惯把**权限类变更**排在最前面处理，因为它们直接影响核心链路。"

## 关联笔记

- [[Gradle构建系统]]：AGP 在 Gradle 中的位置与配置
- [[../基础工具链/AndroidStudio]]：SDK Manager 与模拟器版本管理
- [[../../06-系统原理与性能优化/系统核心机制/权限机制]]：权限机制的深入
- [[../../08-企业级工程与运维/构建与打包优化/构建加速]]：多渠道打包与包体积优化
- [[../README]]：第 7 部分总览

## 参考资料

- Android 官方 · 行为变更总览：https://developer.android.com/about/versions/15/behavior-changes-all
- Android 官方 · 各版本行为变更：https://developer.android.com/about/versions/14/behavior-changes-14
- Android 官方 · 16KB page size 指南：https://developer.android.com/guide/practices/page-sizes
- Android 官方 · AGP 与 Gradle 版本对应：https://developer.android.com/build/releases/gradle-plugin
- Google Play targetSdk 政策：https://support.google.com/googleplay/android-developer/answer/11926878
