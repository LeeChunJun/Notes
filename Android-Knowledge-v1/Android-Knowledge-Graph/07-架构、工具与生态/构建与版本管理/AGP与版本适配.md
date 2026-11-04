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
- [[../06-系统原理与性能优化/权限机制]]：权限机制的深入
- [[../08-企业级工程与运维/构建与打包优化]]：多渠道打包与包体积优化
- [[../README]]：第 7 部分总览

## 参考资料

- Android 官方 · 行为变更总览：https://developer.android.com/about/versions/15/behavior-changes-all
- Android 官方 · 各版本行为变更：https://developer.android.com/about/versions/14/behavior-changes-14
- Android 官方 · 16KB page size 指南：https://developer.android.com/guide/practices/page-sizes
- Android 官方 · AGP 与 Gradle 版本对应：https://developer.android.com/build/releases/gradle-plugin
- Google Play targetSdk 政策：https://support.google.com/googleplay/android-developer/answer/11926878
