# HarmonyOS 6 原生开发教程系列

> 面向**已入门 ArkTS 但知识点需要复习**的开发者，目标是同时达成两个目标：
> 1. **具备 HarmonyOS 6 原生应用的独立开发能力**
> 2. **能应对 HarmonyOS 相关技术面试**
>
> **目标版本**：HarmonyOS 6.0.0（API 20）/ HarmonyOS SDK 26
> **技术栈**：ArkTS + ArkUI（声明式）+ Stage 模型 + DevEco Studio

---

## 📚 教程目录

| # | 教程 | 核心内容 | 时长 |
|---|---|---|---|
| 01 | [架构总览与工程体系](01-架构总览与工程体系.md) | 版本脉络、分层架构、HAP/HSP/HAR、Stage vs FA、工程目录、DevEco 与 hdc | 40min |
| 02 | [ArkTS 语言核心复习](02-ArkTS语言核心复习.md) | ArkTS vs TS 的限制、类型系统、装饰器、TaskPool/Worker、Kit 导入 | 60min |
| 03 | [声明式 UI 与组件体系](03-声明式UI与组件体系.md) | 声明式心智模型、两阶段渲染、自定义组件、三大布局、组件速查、动画 | 60min |
| 04 | [状态管理 V1 与 V2](04-状态管理V1与V2.md) ★ | 六大 V1 装饰器、V2 全体系、`@Observed` vs `@ObservedV2`、存储体系、MVVM | 75min |
| 05 | [页面路由与 Navigation](05-页面路由与Navigation.md) | router vs Navigation、NavPathStack、NavDestination 生命周期、共享元素转场 | 50min |
| 06 | [应用模型与 UIAbility](06-应用模型与UIAbility.md) | Stage 运行结构、完整生命周期、Want、上下文、ExtensionAbility、后台管控 | 55min |
| 07 | [数据管理](07-数据管理.md) | Preferences、RDB、KV Store、分布式数据、文件、DataShare、本地优先策略 | 60min |
| 08 | [网络与后台任务](08-网络与后台任务.md) | HTTP、WebSocket、后台任务三类、网络层封装、性能优化、并发 | 60min |
| 09 | [权限、安全与跨应用](09-权限安全与跨应用.md) | 权限模型三大差异、Want 跨应用、剪贴板分享、沙箱、隐私合规 | 55min |
| 10 | [HarmonyOS 6 新特性与工程化](10-HarmonyOS6新特性与工程化.md) | Ark Agent Framework、Skills、生成式 UI、DevEco Code/CLI、构建签名发布 | 55min |
| 11 | [面试题库与实战路线](11-面试题库与实战路线.md) ★★ | **48 道高频面试题带答案**、3 个实战项目、话术模板、45 条送命题自查 | 90min |

★ = 面试必读　★★ = 核心产出

---

## 🎯 按目标选择路径

### 我要准备面试（时间紧）
1. 先读 **11 章的「第一部分：48 道高频面试题」** —— 这是浓缩后的最小知识集
2. 用 **11 章的「第四部分：45 条送命题自查清单」** 自测，标记不会的
3. 按标记回到对应章节精读
4. 用 **11 章的「第三部分：面试临场话术模板」** 组织语言

### 我要做开发（重实操）
1. 01 → 02 → 03 → 04（打基础，这四章必须动手敲代码）
2. 05 → 06 → 07 → 08 → 09（工程能力）
3. 10（新特性 + 发布流程）
4. 按 **11 章「第二部分：项目实战路线」** 做 3 个递进项目

### 两条一起走（推荐）
按 11 章的 **4 周计划**执行。

---

## 🧠 核心知识速记卡（打印贴在显示器旁）

### 状态管理（最高频）
```
内部状态 → @State(V1) / @Local(V2)
父给子   → @Prop(V1) / @Param(V2)        子给父 → @Link(V1) / @Event(V2)
跨层     → @Provide+@Consume(V1) / @ProvideV2+@ConsumeV2(V2)
对象观察 → @Observed+@ObjectLink(V1) / @ObservedV2+@Trace(V2)
全局     → @StorageLink(V1) / @StorageLinkV2(V2)
监听     → @Watch(V1) / @Monitor(V2)
计算     → getter(V1) / @Computed(V2)
★ V1 与 V2 不能混用；新项目全用 V2
```

### 生命周期
```
onCreate → onWindowStageCreate → (loadContent) → onForeground
   ↓ 切后台
onBackground
   ↓ 回前台
onForeground
   ↓ 销毁
onWindowStageDestroy → onDestroy

★ onBackground ≠ onDestroy：前者还活着、状态保留；后者才是真销毁
```

### 存储选型
```
少量配置/token → 首选项（单key 8KB，总 1MB，必须 flush）
结构化业务数据 → 关系型数据库 RDB（支持事务、SecurityLevel S1~S4）
高性能缓存/二进制 → KV Store
大文件 → 文件 + fs
跨设备同步 → DistributedKVStore（最终一致）
跨应用共享 → DataShare
用户文件 → Picker 用户授权（无存储权限）
```

### 后台任务
```
短时任务 → 立即、几秒~几分钟
长时任务 → 声明类型（播放/导航/下载），用户可见
延迟任务 → WorkScheduler，系统调度，不保证准时
代理提醒 → ReminderAgent，定时，等同闹钟
```

### 权限
```
user_granted  → 动态申请（相机/麦克风/位置/媒体/通讯录）
system_granted → 需 reason + usedScene
★ 拒绝后永久记住 → PERMANENTLY_DENIED → 引导去设置
★ 隐私弹窗必须在「首次获取个人信息之前」
★ 网络权限不需要申请
```

### 鸿蒙 6 新能力
```
① Ark Agent Framework：感知/决策/执行三层，端侧 Skills + 云端 Skills，生成式 UI
② 空间感：沉浸光感（1 行代码光影分层）、智感握姿、平行视界
③ 工具链 Agent 化：DevEco Code（编程智能体）、DevEco CLI（面向 Agent 的命令行）
④ 性能：方舟引擎热点优化、智能预加载、120Hz 自适应
⑤ 跨端：KMP/CMP Beta（复用率 90%）
```

---

## 🛠️ 环境准备

本机检测结果：**未安装 DevEco Studio**。如需实操，按以下步骤：

1. 下载安装 **DevEco Studio 6.x**（https://developer.huawei.com/consumer/cn/deveco-studio/）
2. 安装 **HarmonyOS SDK（API 20）**
3. 安装 **JDK 17**
4. 安装 **Node.js**（hvigor 构建依赖）
5. 开启开发者模式：设置 → 关于手机 → 连续点击"版本号"7 次
6. 用 `hdc list targets` 验证连接

**常用命令**：
```bash
hdc list targets                       # 列出设备
hdc install -r xxx-signed.hap          # 安装
hdc shell aa start -a EntryAbility -b com.example.myapp
hdc shell hilog | grep MyApp           # 查看日志
hdc shell snapshot_display -f /data/local/tmp/s.jpeg
```

---

## 📁 目录结构

```
HarmonyOS6-Tutorial/
├── README.md                          ← 本文件，学习入口
├── 01-架构总览与工程体系.md
├── 02-ArkTS语言核心复习.md
├── 03-声明式UI与组件体系.md
├── 04-状态管理V1与V2.md
├── 05-页面路由与Navigation.md
├── 06-应用模型与UIAbility.md
├── 07-数据管理.md
├── 08-网络与后台任务.md
├── 09-权限安全与跨应用.md
├── 10-HarmonyOS6新特性与工程化.md
└── 11-面试题库与实战路线.md
```

---

## 📌 每章的结构

所有教程统一采用：

1. **开头的知识框** —— 说明这章解决什么问题、预计多久
2. **正文** —— 概念 + 代码 + 对比表 + 面试话术
3. **本章自测题** —— 检验是否真掌握
4. **参考答案要点** —— 对照检查

**建议**：先独立回答自测题，再看答案；答案在的章节重读。

---

## 🔗 官方资源

- [鸿蒙开发者官网文档](https://developer.huawei.com/consumer/cn/doc/)
- [HarmonyOS 版本概览（API 20）](https://developer.huawei.com/consumer/cn/doc/doccenter-release-notes/overview-600)
- [OS 新增和增强特性 6.0.0(20)](https://developer.huawei.com/consumer/cn/doc/doccenter-release-notes/os-new-feature-600)
- [Intents Kit（意图框架）接入方案](https://developer.huawei.com/consumer/cn/doc/harmonyOS-Guides/intents-skill-all-rec-decorator-overview)
- [HdsNavDestination（导航子页面容器）](https://developer.huawei.com/consumer/cn/doc/doccenter-references/api/ui-design-hdsnavdestination?docScope=all)
- [OpenHarmony 社区](https://www.openharmony.cn/)
- [华为开发者论坛](https://developer.huawei.com/consumer/cn/forum/)

---

## ⚡ 三条最重要的建议

1. **状态管理必须动手写** —— 这是鸿蒙面试的第一分水岭。看十遍不如写一遍，特别是 `@Prop` 传对象的坑要亲自踩一次。

2. **做一个完整项目并能讲清楚** —— 面试官不在乎你用过多少 API，在乎你能不能讲清楚"为什么这么设计、遇到什么问题、怎么解决的"。11 章的三个项目就是为此准备的。

3. **用最新的东西加分** —— Ark Agent Framework、DevEco Code/CLI、沉浸光感、KMP/CMP，这些几乎没人讲过，说出来就是差异化优势。
