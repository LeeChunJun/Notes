## 第1部分：编程语言与基础

- Kotlin（协程、Flow、高阶函数、扩展、委托、DSL）
- Java（泛型、反射、注解、集合框架、JVM 内存模型）
- 跨语言互调与差异

## 第2部分：Android 核心组件

- 四大组件（Activity、Service、BroadcastReceiver、ContentProvider）
- 组件间通信（Intent、Binder、Messenger、AIDL）
- Application 与 Context 详解

## 第3部分：UI 开发与用户交互

- 传统 View 系统（绘制流程 measure/layout/draw、事件分发机制、自定义 View/ViewGroup
- Jetpack Compose（声明式 UI、重组、状态管理、与 View 互操作）
- 布局与主题（ConstraintLayout、Compose Layout、Material Design/You）
- 动画（属性动画、转场动画、Compose 动画）
- 资源与适配（多语言、屏幕适配、深色模式）
- 窗口管理（Window/WindowManager/ViewRootImpl）

## 第4部分：数据存储与处理

- 本地存储（SharedPreferences/MMKV、Room、DataStore、文件存储）
- 数据库（SQLite、Room 原理）
- 数据格式（JSON、ProtoBuf）
- 序列化原理（Parcelable/Serializable）
- 数据共享（ContentProvider、MediaStore）

## 第5部分：网络与并发

- 网络框架（OkHttp、Retrofit、Ktor）
- 网络协议（HTTP/HTTPS、WebSocket、RESTful）
- 异步方案（Thread/Handler/Looper、RxJava、Kotlin 协程）
- Handler 机制原理（Looper、MessageQueue）

## 第6部分：系统原理与性能优化

- 系统核心机制
    - 应用启动流程（Zygote、SystemServer、冷/热启动）
    - Activity 启动流程（AMS、进程创建）
    - Binder 通信机制
    - 资源加载机制（Resources/AssetManager）
    - 内存管理（GC、内存抖动、泄漏）
    - 渲染机制（Vsync、Choreographer、掉帧）
    - 权限机制（检查流程、动态权限）
    - 电源管理（Doze 模式、唤醒机制）
    - 存储分区机制（内部/外部存储底层差异）
- 性能优化专项
    - 内存优化（LeakCanary、Memory Profiler）
    - 卡顿优化（Systrace、帧率监测）
    - 启动优化（异步初始化、启动任务调度）
    - 包体积优化（R8、资源混淆）
    - 电量优化（JobScheduler、WorkManager）
    - 安全基础（混淆、签名、网络安全、反调试）

## 第7部分：架构、工具与生态

- 架构模式（MVC、MVP、MVVM、MVI）
- Jetpack 库（Lifecycle、ViewModel、LiveData/StateFlow、Paging、Navigation、Hilt）
- 依赖注入（Dagger2、Hilt、Koin）
- 测试（单元测试、UI 测试、Compose 测试）
- 构建与版本管理（Gradle、AGP、版本适配 Android 14/15+）
- 基础工具链（Android Studio、ADB、逆向工具）

## 第8部分：企业级工程与运维

- 代码规范与质量（编码规范、Lint、代码审查、Git 工作流）
- 模块化与组件化（模块划分、路由框架、独立调试、依赖管理）
- 构建与打包优化（Gradle 进阶、构建加速、多渠道打包、包体积精简）
- 日志与监控（日志系统、异常捕获、性能监控、远端配置）
- 热修复与动态化（Tinker/Sophix、React Native/Flutter、AAB 动态交付）
- 安全加固（代码混淆进阶、防逆向、Root/模拟器/Hook 检测）
- CI/CD（Jenkins/GitHub Actions、自动构建、自动测试、分发与发布）
- 灰度发布与监控（灰度策略、A/B 测试、线上 APM、回滚机制）
- 文档与协作（技术文档、知识沉淀、团队规范）

## 第9部分：跨平台与混合开发

- Flutter 与原生混合编译
    - Flutter 工程集成（module模式、aar集成、源码依赖）
    - Platform Channel（MethodChannel、EventChannel、BasicMessageChannel）
    - 混合栈管理（FlutterFragment/FlutterActivity、原生与Flutter页面跳转）
    - 性能优化（纹理共享、内存占用、引擎复用）
    - 调试与打包（混合编译的Gradle配置、Dart代码混淆）
- Kotlin Multiplatform (KMP)：业务逻辑跨平台、与Android Native集成
- Compose Multiplatform：将Compose扩展到iOS/桌面端
- React Native 混合开发（可选）：Bridge通信、原生模块封装

## 第10部分：软技能与技术领导力

- 技术文档与知识沉淀
    - 架构决策记录（ADR）
    - 设计评审与方案撰写
    - 内部Wiki/知识库建设
- 技术债务管理
    - 识别与量化（代码复杂度、覆盖率、遗留系统）
    - 偿还策略（重构计划、逐步替换）
- 事故响应与复盘
    - On-Call 流程
    - 事故分级与应急处理
    - 根因分析（RCA）与事故复盘报告
- 团队协作与领导力
    - 代码审查最佳实践（建设性反馈、审查清单）
    - 技术选型与决策（权衡短期与长期）
    - 新人指导与知识传递（Pair Programming、内部培训）
    - 跨部门沟通与需求管理
- 个人成长与职业规划
    - 技术社区参与（开源贡献、技术演讲）
    - 学习策略（主题阅读、动手实践、复盘）
    - 面试准备（系统设计、行为问题）

---

## 知识图谱使用建议

### 一、按开发者阶段推进

| 阶段 | 核心目标 | 建议聚焦部分 | 学习策略 |
| --- | --- | --- | --- |
| 初级（0-2年） | 能独立完成常规App开发 |  第1、2、3、4、5部分 以第1-5部分为主 | 掌握语言、组件、UI、数据、网络。每个知识点配合官方文档 + 小Demo实践。 |
| 中级（2-4年） | 解决复杂问题，优化性能 | 第6、7部分 | 深入第6部分系统原理与性能优化（启动、内存、渲染），掌握第7部分架构模式与Jetpack，开始写单元测试。 |
| 高级（4-6年） | 主导项目，设计架构 | 第8、9部分 | 掌握第8部分企业级工程（模块化、CI/CD、监控），以及第9部分跨平台混合开发，具备多端交付能力。 |
| 资深/Leader（6年+） | 技术决策，团队赋能 |  第10部分 | 聚焦第10部分软技能与领导力，同时保持对第6-9部分的深度理解，用于指导团队和技术选型。|

### 二、按目标场景快速定位

| 你的目标 | 优先阅读的部分 | 次要参考 |
| --- | --- | --- |
| 面试准备（大厂Android岗） | 第2（组件通信）、第3（UI原理）、第5（Handler/协程）、第6（系统原理、性能优化）、第7（MVVM、Hilt） | 第8（模块化、CI/CD）、第9（Flutter混合） |
| 接手老旧项目重构 | 第7（架构模式）、第8（模块化、代码规范、热修复） | 第6（性能优化）、第10（技术债务管理） |
| 从零搭建新App | 第7（选MVVM+Jetpack）、第8（Gradle配置、日志监控、CI/CD） | 第4（Room/DataStore）、第5（Retrofit+协程） |
| 提升应用性能 | 第6（渲染、内存、启动优化）、第3（View绘制优化） | 第8（包体积、构建加速） |
| 引入Flutter混合开发 | 第9（Flutter与原生混合编译全部） | 第8（模块化拆分）、第5（网络层统一） |
| 晋升技术Leader | 第10（技术债务、事故响应、团队协作） | 第8（文档与协作）、第7（测试与质量） |

### 三、学习路径建议（由浅入深）

```
第1部分（语言）
  → 第2部分（核心组件）
    → 第3部分（UI基础） + 第4部分（数据存储）
      → 第5部分（网络与并发）
        → 第7部分（架构模式与Jetpack）
          → 第6部分（系统原理与性能优化）
            → 第8部分（工程化）
              → 第9部分（跨平台）
                → 第10部分（软技能）
```

注意：第3部分中的UI原理（绘制、事件分发）和第5部分中的Handler机制建议在第6部分之前掌握，它们属于“原理层”的入门。

### 四、如何利用AI按图谱学习

1. 选定一个叶子节点，例如“第6部分 → 渲染机制 → Vsync/Choreographer”
2. 向AI提问：“请详细解释Vsync和Choreographer的原理，包括它们如何影响掉帧，并给出检测工具和优化方法。”
3. 要求生成代码示例：“用Kotlin写一个检测掉帧的工具类。”
4. 要求关联其他部分：“这个知识点与第3部分的View绘制流程有什么关联？与第8部分的性能监控如何集成？”

### 五、补充建议

- 不要试图一次性学完10大部分：图谱是“导航地图”，不是“背诵清单”。根据当前工作或学习需要，选择1-2个部分深入。
- 定期回顾与更新：Android每年都有新变化（如Compose、版本适配），建议每半年根据官方Release Notes更新图谱中的“版本适配”条目。
- 动手实践是核心：每学一个知识点（如Room），立刻写一个小demo验证。图谱只提供脉络，编码才能真正内化。

---

## Obsidian目录结构

```
Android-Knowledge-Graph/          # 仓库根目录
│
├── 01-编程语言与基础/            # 第1部分
│   ├── README.md                 # 包含本部分的Mermaid思维导图
│   ├── Kotlin/
│   │   ├── 协程与Flow.md
│   │   ├── 高阶函数与扩展.md
│   │   └── DSL.md
│   ├── Java/
│   │   ├── 泛型与反射.md
│   │   ├── 集合框架.md
│   │   └── JVM内存模型.md
│   └── 跨语言互调.md
│
├── 02-Android核心组件/
│   ├── README.md
│   ├── 四大组件/
│   │   ├── Activity.md
│   │   ├── Service.md
│   │   ├── BroadcastReceiver.md
│   │   └── ContentProvider.md
│   ├── 组件间通信/
│   │   ├── Intent.md
│   │   ├── Binder机制.md
│   │   └── AIDL.md
│   └── Application与Context.md
│
├── 03-UI开发与用户交互/
│   ├── README.md
│   ├── 传统View系统/
│   │   ├── 绘制流程.md
│   │   ├── 事件分发机制.md
│   │   └── 自定义View.md
│   ├── JetpackCompose/
│   │   ├── 声明式UI与重组.md
│   │   ├── 状态管理.md
│   │   └── Compose与View互操作.md
│   ├── 布局与主题/
│   ├── 动画/
│   ├── 资源与适配/
│   └── 窗口管理/
│
├── 04-数据存储与处理/
│   ├── README.md
│   ├── 本地存储/
│   ├── 数据库/
│   ├── 数据格式/
│   ├── 序列化原理/
│   └── 数据共享/
│
├── 05-网络与并发/
│   ├── README.md
│   ├── 网络框架/
│   ├── 网络协议/
│   ├── 异步方案/
│   └── Handler机制原理.md
│
├── 06-系统原理与性能优化/
│   ├── README.md
│   ├── 系统核心机制/
│   │   ├── 应用启动流程.md
│   │   ├── Activity启动流程.md
│   │   ├── Binder通信.md
│   │   ├── 资源加载机制.md
│   │   ├── 内存管理.md
│   │   ├── 渲染机制.md
│   │   ├── 权限机制.md
│   │   ├── 电源管理.md
│   │   └── 存储分区机制.md
│   ├── 性能优化专项/
│   │   ├── 内存优化.md
│   │   ├── 卡顿优化.md
│   │   ├── 启动优化.md
│   │   ├── 包体积优化.md
│   │   └── 电量优化.md
│   └── 安全基础.md
│
├── 07-架构、工具与生态/
│   ├── README.md
│   ├── 架构模式/
│   ├── Jetpack库/
│   ├── 依赖注入/
│   ├── 测试/
│   ├── 构建与版本管理/
│   └── 基础工具链/
│
├── 08-企业级工程与运维/
│   ├── README.md
│   ├── 代码规范与质量/
│   ├── 模块化与组件化/
│   ├── 构建与打包优化/
│   ├── 日志与监控/
│   ├── 热修复与动态化/
│   ├── 安全加固/
│   ├── CI-CD/
│   ├── 灰度发布与监控/
│   └── 文档与协作/
│
├── 09-跨平台与混合开发/
│   ├── README.md
│   ├── Flutter与原生混合编译/
│   │   ├── 工程集成.md
│   │   ├── PlatformChannel.md
│   │   ├── 混合栈管理.md
│   │   ├── 性能优化.md
│   │   └── 调试与打包.md
│   ├── KotlinMultiplatform/
│   ├── ComposeMultiplatform/
│   └── ReactNative混合开发.md
│
├── 10-软技能与技术领导力/
│   ├── README.md
│   ├── 技术文档与知识沉淀/
│   ├── 技术债务管理/
│   ├── 事故响应与复盘/
│   ├── 团队协作与领导力/
│   └── 个人成长与职业规划/
│
├── _Templates/                    # 模板文件夹
│   ├── Android知识卡片.md         # 笔记模板（含YAML frontmatter）
│   └── Mermaid导图模板.md
│
├── _Dashboard/                    # 动态看板（由Dataview自动生成）
│   ├── 待复习队列.md
│   ├── 学习进度总览.md
│   └── 知识关联图谱.md
│
├── _Attachments/                  # 统一存放图片、PDF等附件
│   ├── images/
│   └── files/
│
├── _Archive/                      # 归档过时笔记
│
├── _Daily/                        # 日记（记录每日学习）
│   └── 2026-04-02.md
│
├── 索引.md                        # 总入口，包含10大部分链接
└── 知识图谱总览.md                # 包含完整10大部分的Mermaid思维导图
```

---

## Android 知识图谱文件模板

### 模版1: Android知识卡片

```markdown
---
tags: [android]
part: 
status: 待整理
difficulty: 初级
created: 
updated: 
related: 
---

# {{title}}

## 一句话定义

## 核心原理

## 代码示例

```kotlin
```

## 常见问题 / 面试要点

## 关联笔记

## 参考资料
```

### 模版2: 部分README

```markdown
---
tags: [android, part]
part: 
---

# 第 X 部分：{{part-name}}

## 概览

## 知识图谱（Mermaid）

```mermaid
mindmap
  root（（{{part-name}}））
```

## 笔记列表

- [[笔记1]]
- [[笔记2]]

## 关联部分

- 前置：[[../XX-xxx/README]]
- 延伸：[[../YY-yyy/README]]
```

### 模版3: 总索引模版

```markdown
---
tags: [index, dashboard]
---

# 🗺️ Android 开发知识图谱

## 📖 十大知识领域

### 1️⃣ [[01-编程语言与基础/README|编程语言与基础]]
Kotlin · Java · 跨语言互调

### 2️⃣ [[02-Android核心组件/README|Android核心组件]]
四大组件 · Binder · Context

### 3️⃣ [[03-UI开发与用户交互/README|UI开发与用户交互]]
View系统 · Compose · 动画 · 适配

### 4️⃣ [[04-数据存储与处理/README|数据存储与处理]]
Room · DataStore · 序列化

### 5️⃣ [[05-网络与并发/README|网络与并发]]
OkHttp · 协程 · Handler

### 6️⃣ [[06-系统原理与性能优化/README|系统原理与性能优化]]
启动流程 · 内存/卡顿优化 · 渲染

### 7️⃣ [[07-架构、工具与生态/README|架构、工具与生态]]
MVVM · Jetpack · 依赖注入 · 测试

### 8️⃣ [[08-企业级工程与运维/README|企业级工程与运维]]
模块化 · CI/CD · 热修复 · 监控

### 9️⃣ [[09-跨平台与混合开发/README|跨平台与混合开发]]
Flutter混合 · KMP · Compose Multiplatform

### 🔟 [[10-软技能与技术领导力/README|软技能与技术领导力]]
技术债务 · 事故响应 · 团队协作

## 🎯 当前聚焦

```dataview
TABLE status, difficulty
FROM #android AND -"Templates"
WHERE status = "进行中" OR status = "待整理"
SORT created DESC
LIMIT 5
```

## 📊 进度总览

```dataview
TABLE length(rows) as "笔记数量"
FROM #android AND -"Templates"
GROUP BY part
SORT part ASC
```