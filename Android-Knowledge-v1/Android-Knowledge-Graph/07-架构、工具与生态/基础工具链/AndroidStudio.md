---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 初级
created: 2026-11-04
updated: 2026-11-04
related: "[[ADB]], [[逆向工具]], [[../构建与版本管理/Gradle构建系统]]"
---

# AndroidStudio

## 一句话定义

Android Studio 是 Google 官方的 Android 集成开发环境（IDE，基于 IntelliJ IDEA 平台），集成了**代码编辑、Gradle 构建、模拟器、性能分析（Profiler）、布局检查、数据库工具**等 Android 开发全链路能力。

## 核心原理

### 1. Android Studio 与 IntelliJ 平台

```text
IntelliJ IDEA Platform（核心框架：索引、代码补全、插件系统）
├── IntelliJ IDEA
├── Android Studio  ← + Android 插件 + SDK 管理 + AVD 模拟器
└── 其他 JetBrains IDE（PyCharm、WebStorm...）
```

- 理解"IDE 是插件宿主"很重要：Android Studio 的很多能力（资源导航、Manifest 合并视图、Lint）其实是**插件**提供的。
- **Kotlin 支持**：Android Studio 内置 Kotlin 插件（与 Kotlin 官方合作开发），2024 年起 **Kotlin 为默认语言**（新建项目可选 Kotlin 或 Java）。

### 2. 核心功能面板

| 面板 | 作用 |
| --- | --- |
| **Project 视图** | 展示项目结构（Android 视图 / Packages 视图 / Project 视图三种模式） |
| **Editor** | 代码编辑、Refactor、Live Edit |
| **Debug** | 断点、变量查看、内存/线程、**Layout Inspector** |
| **Profiler** | CPU / Memory / Energy / Network / **Startup** 分析 |
| **Build** | Gradle 任务执行、构建输出、Build Analyzer |
| **Logcat** | 设备日志查看（支持过滤、包名过滤、保存为文件） |
| **Running Devices** | 设备与模拟器管理 |
| **Emulator** | AVD 管理与模拟器控制 |
| **Device Manager** | 真机连接、无线调试、虚拟设备 |
| **Database** | Room/SQLite 数据库实时查看（通过 App Inspection） |

### 3. 模拟器（Emulator / AVD）

- **AVD（Android Virtual Device）**：包含**系统镜像（System Image）**+ **硬件配置**（分辨率、内存、摄像头、传感器）。
- **系统镜像类型**：
  - **Google APIs**：含 Google 服务的镜像（体积中等）；
  - **Google Play**：含 Play 商店的完整镜像（可测真实应用，但**不能 root**）；
  - **AOSP / Default**：纯净系统（**可 root**，体积小，适合分析）。
- **关键能力**：
  - **快照（Snapshot）**：保存/恢复模拟器状态 → 秒级启动；
  - **冷启动（Cold Boot）**：完全重启，验证首次启动行为；
  - **快速启动（Quick Boot）**：默认行为，用快照恢复；
  - **多设备并行**：多实例同时运行，测试多设备场景。
- **API 级别选择**：要测 **targetSdk 行为变更**，必须用**对应版本或更高的系统镜像**（如测 Android 15 的 edge-to-edge 行为就要用 API 35 镜像）。
- **Google Play 商店镜像不能 root**，需要 root 场景请用 AOSP 镜像。

### 4. Profiler：性能分析四件套

| Profiler | 观察什么 | 关键指标 |
| --- | --- | --- |
| **CPU Profiler** | 主线程/子线程的耗时分布 | 方法火焰图、**帧耗时**、卡顿次数 |
| **Memory Profiler** | 堆内存、对象数、GC | `Allocations`、`Shallow/Retained Size`、**Leak 引用链** |
| **Energy Profiler** | 耗电来源 |  wakelock、CPU、传感器、网络 |
| **Network Profiler** | 网络请求 | 请求瀑布图、协议栈、**payload 大小** |

**其他重要工具**：

- **Startup Profiler**：分析**冷启动/热启动**耗时（`TTID` Time To Initial Display）。
- **Live Edit**（仅布局 Inspector / Compose Preview）：**无需重新编译**即可修改 Compose 代码并实时预览效果。
- **Live Edit（代码热替换）**：修改方法体后直接推送到运行中的 App（不重启进程），调试极快。

### 5. Layout Inspector

- 在设备上**查看当前界面的 View 层级 / Compose 层级**。
- **Bounds**（控件位置）、**Attributes**（属性）、**Computed Layout**（计算后的布局）。
- 可**实时修改属性**（宽度、背景、约束）并即时看到效果 → 快速调布局。
- **Compose 支持**：显示 Compose 语义树与 recomposition 情况。

### 6. Build Analyzer

- 展示**每个依赖的传递路径**，可视化"为什么我的 APK 这么大"。
- 按 APK 体积排序 → 找出**最大的依赖**与**意外的传递依赖**。
- **配合 APK Analyzer**（从 APK 里分析 dex、资源、so 体积）→ 包体积优化的第一站。

### 7. 其他实用功能

- **Refactor**：全局重命名、提取函数/常量、Inline、**Replace In Files**（跨文件替换）。
- **Code Inspection + Lint**：实时静态检查 + 批量 Lint 报告。
- **Generate**：一键生成 `Parcelable` 实现、`equals/hashCode`、`Builder`、**Hilt 注入代码**、`Compose Preview`。
- **Split / Install**：APK 分 ABI 安装（减小安装体积）、多设备同时安装。
- **Attach to Android Process**：附加到已在运行的进程（用于分析别人启动的 App）。
- **Device Mirroring / Pairing**：用**无线调试**（Android 11+）连接真机，无需数据线。
- **Apply Changes（Ctrl+Shift+F10）**：只替换改动的类，**不重启 Activity** → 调试快；不支持的方法（如修改方法签名、添加字段）需要 **Restart Activity** 或完整重装。

### 8. 常用快捷键

| 快捷键 | 功能 |
| --- | --- |
| `Ctrl+Shift+F10` | Apply Changes（不重启 Activity） |
| `Ctrl+Shift+F9` | Restart Activity |
| `Alt+Enter` | 快速修复 / 意图操作（生成 override、抽取常量） |
| `Ctrl+Alt+L` | 格式化代码 |
| `Ctrl+Shift+O` | 快速导入 |
| `Ctrl+F12` | 显示文件结构 |
| `Ctrl+H` | 查找用法 |
| `Ctrl+E` | Recent Files |
| `Ctrl+Shift+A` | Search Everywhere |
| `Ctrl+D` | 复制当前行 |
| `Alt+Enter` → "Show Bytecode" | 查看字节码 |
| `Ctrl+Shift+I` | 层次结构（Android Studio 特有的结构视图） |

## 代码示例

### 1. 常用命令行（Studio 内置 Terminal）

```bash
# Gradle
./gradlew assembleDebug
./gradlew installDebug
./gradlew tasks

# 清理
./gradlew clean
rm -rf ~/.gradle/caches   # 极端情况下清理全局缓存

# 查看 APK 体积
apkanalyzer apk summary app/build/outputs/apk/debug/app-debug.apk
apkanalyzer apk files list app/build/outputs/apk/debug/app-debug.apk
```

### 2. Profiler 抓取并分析内存泄漏

```text
1. Debug → Profile 'app' → 选择 Memory 类型的 Live Allocation
2. 操作 App（进入目标页面、返回），观察 Heap
3. 点击 "Dump heap"，在 hprof 文件上右键 → "Find Retained Objects"
4. 找到泄漏对象 → 看 "Retained Objects" 的引用链（谁持有它）
5. 常见结论：
   - Activity 被 Static 字段持有
   - Handler 的 MessageQueue 持有延时消息中的 Activity
   - 单例集合里存了 Activity
   - 匿名内部类持有 Activity
```

### 3. 用 Layout Inspector 定位布局问题

```text
运行 App → 点右上角 "Layout Inspector" 按钮（或工具栏的图标）
→ 选择正在运行的 App 进程
→ 左侧显示 View/Compose 层级树
→ 选中控件：
   - Bounds：实际位置与大小（判断是否被遮挡）
   - Attributes：布局参数
   - Computed Layout：测量后的尺寸
→ 在 "Attributes" 面板直接改值，实时预览
```

### 4. Compose 预览与 Live Edit

```kotlin
@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun UserItemPreview() {
    MyTheme {
        UserItem(User("1", "Tom", "tom@example.com"))
    }
}
```

- `@Preview` 多维度预览：不同尺寸、深色模式、语言、长文本。
- **Preview Inspector**：可切换 Interactive Mode（可点击）。
- **Live Edit**：改代码 → 预览**实时刷新**（Compose Live Edit 需要对应版本支持）。

## 常见问题 / 面试要点

**读者问：Android Studio 常用但容易被忽略的高效功能有哪些？**

**AI答：**

按"**提效最大**"排序给：

1. **Apply Changes（Ctrl+Shift+F10）**：改代码后**不重启 Activity**，只替换改动的类 → 调试循环从"十几秒"降到"1 秒内"。适合改布局、改方法体、改资源；改方法签名/加字段则需 Restart。
2. **Live Edit**：Compose 代码修改**实时推送到运行中的 App**（不用重新编译）→ 调 Compose 布局效率极高。
3. **Layout Inspector + 属性实时修改**：不用反复编译就能调约束、改边距。
4. **Refactor > Replace in Files**：跨文件批量替换（比 Find & Replace 更安全，支持预览）。
5. **Alt+Enter**：几乎所有 IDE 智能操作都藏在这里（生成 override、提取常量、替换 with、添加缺失的 override、创建测试）。
6. **Build Analyzer / APK Analyzer**：查依赖与包体积的利器。
7. **Attach to Android Process**：分析**别人启动**的进程（如从桌面图标点开的 App）。
8. **Profiler 的 Live Edit + Allocation Tracking**：定位内存泄漏最快的方式。
9. **无线调试（Pairing）**：不用数据线调试真机。
10. **Split APK per ABI**：安装时只装对应 ABI 的 so → 安装包体积小、启动快。

---

**读者问：模拟器（AVD）和真机调试各有什么优缺点？怎么选？**

**AI答：**

1. **模拟器优势**：
   - **可随意模拟**：不同 API 版本、不同屏幕尺寸、不同语言、不同网络条件（无信号/限速/丢包）、模拟传感器、模拟电量与温度；
   - **可 root**（AOSP 镜像）→ 便于分析系统行为；
   - **可快速创建多实例**并行测试；
   - **可模拟异常场景**：来电、短信、GPS 轨迹、进程被杀（`adb shell am kill`）。
2. **模拟器劣势**：
   - **性能不如真机**（x86 镜像较慢，ARM 镜像更慢）；
   - **不反映真实硬件特性**：相机质量、GPU 表现、耗电、发热、传感器精度；
   - **不能替代真机的性能测试**（尤其启动速度、帧率、内存）；
   - **Google Play 镜像不能 root**。
3. **真机优势**：真实性能、真实硬件、真实电量/发热、真实传感器；
4. **真机劣势**：设备碎片化、调试环境干扰（通知、其它 App）、无法随意模拟场景。
5. **推荐策略（工程实践）**：
   - **日常开发/功能调试**：模拟器（快、可控）；
   - **性能测试、相机、传感器、耗电、启动速度**：**真机**（且要用中低端机，不能只测旗舰）；
   - **新系统行为验证**：用**对应 API 级别的模拟器镜像**（测 targetSdk 行为变更）；
   - **上线前回归**：至少 2~3 台真机 + 主力模拟器镜像。

---

**读者问：Android Studio 的 Profiler 有哪些常用场景？怎么用？**

**AI答：**

按四个高频场景给"操作路径 + 看什么"：

1. **内存泄漏排查**：
   - 路径：Memory Profiler → Live Allocation → 操作页面 → **Dump heap** → 右键 hprof → **Find Retained Objects**；
   - 看什么：**Retained Size** 大的对象、**引用链**（谁持有它）→ 定位到"静态字段 / Handler / 单例集合 / 匿名内部类"。
   - 配合 **LeakCanary** 在生产环境持续监控（见 [[../06-系统原理与性能优化/内存优化]]）。
2. **卡顿/掉帧分析**：
   - 路径：CPU Profiler → 录制 → 看 **Frames / Frame Timeline**；
   - 看什么：主线程是否有**长任务（>16ms）**、卡顿是否集中在某个方法、是否在主线程做了 IO。
3. **启动速度优化**：
   - 路径：**Startup Profiler** → 选择冷启动/热启动 → 看各阶段耗时；
   - 看什么：`Application.onCreate` 耗时、`Activity` 创建耗时、首帧时间（TTID）、哪些初始化应该延迟。
4. **电量/发热**：
   - 路径：Energy Profiler → 看 **wakelock、CPU、后台活动**；
   - 看什么：是谁持有 WakeLock、哪个后台任务在跑、AlarmManager/JobScheduler 的唤醒频率。

补充：**Network Profiler** 看接口耗时与 payload 大小（排查流量问题）；**adb + Perfetto/Systrace** 做更细的时间线分析（见 [[../06-系统原理与性能优化/卡顿优化]]）。

---

**读者问：面试时问你"日常怎么用 Android Studio 调试"，你该怎么答？**

**AI答：**

给一个**有层次**的回答（体现你既会用日常功能，也懂原理）：

1. **日常循环**：
   - **Apply Changes** 改代码 → 秒级生效，不重启 Activity（改签名/字段时才 Restart）；
   - **Logcat** 按包名过滤（`package:mine`）+ Tag 过滤；关键日志打点；
   - **Debug 断点**：条件断点（conditional breakpoint）、**方法断点**、**字段观察**（watch）、**Evaluate Expression**（断点处执行表达式）。
2. **界面问题**：
   - **Layout Inspector** 看层级与 Bounds，改属性实时预览；
   - **Compose Preview + Live Edit** 调 Compose 布局。
3. **性能问题**：
   - **Profiler** 四件套（CPU / Memory / Energy / Network）+ **Startup Profiler**；
   - **Allocation Tracking** 定位泄漏，**Dump heap + Find Retained Objects** 看引用链。
4. **包体与依赖**：
   - **Build Analyzer** 看依赖来源，**APK Analyzer** 看 dex/资源/so 分布。
5. **设备管理**：
   - **无线调试**（Pairing）连接真机；
   - **多设备并行**、**API 级别矩阵**（覆盖不同系统版本）。
6. **加一句工程化**：这些能力最终都会**沉淀到 CI 上**（单元测试、lint、Profiler 基线对比），不能只靠本地手动点。

---

**读者问：`Apply Changes` 和完整重装有什么区别？什么时候必须重装？**

**AI答：**

1. **`Apply Changes` 的原理**：通过 **JVMTI（Java 虚拟机工具接口）的类重定义（retransform）**，把新编译的 class 文件**热替换**到运行中的进程里。
2. **它能做什么**：
   - 修改**方法体**（包括添加/删除方法、修改 lambda）→ 可以；
   - 修改**资源**（布局、字符串、drawable）→ 可以（资源重打包 + 增量更新）；
   - 修改 **Compose 代码**（配合 Live Edit）→ 几乎实时。
3. **它不能做什么**（必须重启）：
   - **修改类的字段结构**（增删字段、改类型）→ 破坏对象内存布局；
   - **修改方法签名**（参数、返回值）；
   - **修改类的继承结构 / 实现接口**；
   - **修改 `AndroidManifest`**（组件增删、权限）→ 必须完整重装；
   - **修改原生库（.so）** → 必须重新安装；
   - **修改 `Application` 类的关键初始化逻辑** → 建议 Restart。
4. **三档操作的取舍**：
   - **Apply Changes**（最快）：改方法体、改资源；
   - **Restart Activity**：改了 Activity 的字段或生命周期逻辑 → 重建 Activity 但不重启进程；
   - **Stop & Rerun / 完整安装**：改了 Manifest、Application、so → 彻底重来。
5. **原理层面的原因**：类重定义**只能在不改变类结构的前提下替换方法体**，因为 JVM 已经在堆上按原结构分配了对象实例。

## 关联笔记

- [[ADB]]：命令行调试，是 IDE 的补充
- [[逆向工具]]：APK 分析与反编译工具链
- [[../构建与版本管理/Gradle构建系统]]：IDE 的构建面板与命令行等价物
- [[../06-系统原理与性能优化/内存优化]]：Profiler 与 LeakCanary 的配合
- [[../README]]：第 7 部分总览

## 参考资料

- Android Studio 官方文档：https://developer.android.com/studio
- Android Studio 快捷键参考：https://developer.android.com/studio/keyboard-shortcuts
- Android Profiler 指南：https://developer.android.com/studio/profile
- Android Studio 下载与版本说明：https://developer.android.com/studio/releases
