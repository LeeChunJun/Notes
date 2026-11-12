# 教程 10：HarmonyOS 6 新特性与工程化发布

> **目标**：掌握 HarmonyOS 6（API 20）及 SDK 26 带来的新能力 —— Ark Agent Framework、Skills、生成式 UI、沉浸光感、多设备适配、跨平台框架，以及完整的构建、签名、打包、发布流程。这是面试的"前沿视野"加分项和项目落地的最后一环。
>
> **预计阅读**：55 分钟

---

## 一、HarmonyOS 6 版本脉络（必须准确记住）

| 版本 | API Level | 关键时间 | 状态 |
|---|---|---|---|
| HarmonyOS 4.x | 10 ~ 12 | 2023 ~ 2024 | 兼容 AOSP，已停止 |
| HarmonyOS NEXT / 5.0 | 12 ~ 14 | 2024.10 起公测，2025 正式版 | 纯血鸿蒙首发 |
| **HarmonyOS 6.0** | **20** | 2025.10 正式推送 | **当前主力开发版本** |
| **HarmonyOS SDK 26** | 26 | HDC2026（2026.06）发布 | **最新 SDK**（对应 HarmonyOS 6 后续演进） |

**关键事实（可作为面试谈资）**：
- HarmonyOS 6 正式版升级率已超过 **98%**。
- 过去一年 TOP 应用用户满意度提升超 **50%**。
- 生态已有 **3 万+ 原生应用**，覆盖 18 个主流跨平台框架，帮助 **1.3 万+** 应用鸿蒙化。
- 仓颉（Cangjie）语言已开源，星闪（NearLink）协议栈于 2026 年 7 月全量开源至 OpenHarmony 社区。
- OpenHarmony 社区：3 万+ Star、80 万+ PR、1200+ 设备通过兼容性认证。

---

## 二、HarmonyOS 6 三大核心新能力

### 2.1 Ark Agent Framework（智能体框架）

这是 HarmonyOS 6 最具颠覆性的能力。开发者可以让**自己的应用能力被系统智能体（小艺等）调用**，也可以在应用内**发布 Skills** 供智能体编排。

#### 2.1.1 三层架构回顾

```
感知层（Perception）    统一处理语音/文字/图像多模态输入 → 语义化
        ↓
决策层（Decision）      大模型做意图识别 + 任务规划（Task Planning）
        ↓
执行层（Execution）      调用系统 API 与第三方服务完成任务
```

#### 2.1.2 应用侧接入方式

**方式一：Intents Kit（意图框架）—— 声明式暴露意图**

意图框架允许你把应用内的业务动作注册为"意图"，系统智能体就能理解并调用。

```ts
import { intents } from '@kit.IntentsKit';   // 具体 kit 名以官方为准

// 在 module.json5 中声明意图配置
/*
{
  "module": {
    "abilities": [{
      "name": "EntryAbility",
      "metadata": [{
        "name": "ohos.app.intent",
        "resource": "$profile:intent_config"
      }]
    }]
  }
}
*/

// intent_config.json
{
  "intents": [{
    "name": "BookFlight",
    "description": "预订机票",
    "schema": {
      "parameters": [{
        "name": "from",
        "type": "string",
        "description": "出发城市"
      }, {
        "name": "to",
        "type": "string",
        "description": "到达城市"
      }]
    },
    "actions": [{
      "type": "flight.book",
      "handler": {
        "type": "ability",
        "abilityName": "FlightAbility"
      }
    }]
  }]
}
```

#### 2.1.3 Skills（技能）—— 随应用发布与上架

**HarmonyOS 6 新增了"随应用发布和上架的端侧 Skills"，以及"云端 Skills"**。

- **端侧 Skills**：打包在应用内，随应用上架，用户安装后即可被智能体调用 —— 能力可离线可用。
- **云端 Skills**：部署在云端，动态下发，无需更新应用即可迭代。
- 开发者可用 **装饰器（Decorator）方式**快速接入任务执行类场景（官方有专门文档）。

**典型用例（面试举例）**：
- 旅行类 App 发布"旅行照片地点历史故事自动回顾"Skill —— 官方案例《时光旅记》用 **10 分钟**就实现了这个功能。
- 电商 App 发布"搜索并下单"Skill，智能体能直接完成比价与下单。

> **面试话术（强烈建议准备）**："HarmonyOS 6 最大的变化是引入了 Ark Agent Framework。开发者不再只是被动接受智能体调用，而是可以把应用能力封装成 **Skills** 发布出去——端侧 Skills 随应用上架、能离线工作，云端 Skills 动态下发、可快速迭代。配合 Intents Kit 声明意图参数，系统智能体就能理解你的业务语义并编排调用。这对做 AI 功能的同学是很大的机会点：业务能力的 AI 化入口从'自己接大模型'变成了'把能力标准化后交给系统智能体编排'。"

#### 2.1.4 生成式 UI（Generative UI）

- 大模型生成结果不再局限于文字，可以**以图文并茂的形式直接呈现**为原生 UI 组件。
- 应用侧可定义"生成式 UI 描述"，由系统把大模型输出渲染成 ArkUI 组件树。
- 意义：AI 输出的**可交互性**大幅提升，不再是死文本。

### 2.2 空间感与沉浸体验

| 能力 | 说明 | 开发者接入 |
|---|---|---|
| **沉浸光感** | 组件随环境光/屏幕内容产生**光影分层**效果 | **仅 1 行代码**即可为界面组件带来光影分层效果（官方《时光旅记》案例） |
| **智感握姿** | 根据握持姿态调整交互 | 传感器 Kit |
| **平行视界** | 全新分屏比例，跨应用平行显示 | — |
| **多设备适配** | 一套代码适配直板/折叠/平板/2in1 | `GridRow` + `breakpoints` + 多设备 Skill |

> **面试加分**："HarmonyOS 6 强调'空间感'——UI 不再是平面的卡片，而是有光影层次。沉浸光感能力接入成本极低（一行代码），但视觉提升明显，是提升应用质感的高性价比能力。"

### 2.3 性能与引擎

- **方舟引擎（Ark Compiler / Ark Runtime）**：
  - 对热点代码做更激进优化（内联、向量化），CPU 密集型任务性能相比 5.0 有明显提升。
  - 智能预加载 + 预编译，**冷启动**显著变快。
- **百米扫码**：超分辨率算法增强远距离模糊二维码识别。
- **120Hz 自适应刷新率**：静态内容降刷新率省电，滚动/游戏拉满。
- **融合渲染 / 桥接原生管线**：在跨平台方案（KMP/CMP）中，DMA 内存下降 **95%**、卡顿率下降 **90%**、冷启动"秒起秒开"，全量编译时间从 4 小时缩短到 50 分钟。

---

## 三、AI 辅助研发工具链（DevEco Code / DevEco CLI）

这是 2026 年的新事物，**几乎没人讲过，面试说出来是巨大加分**。

### 3.1 DevEco Code —— "懂鸿蒙"的编程智能体

- 基于华为 **毕方** 和 **OpenCode** 构建，开箱即用。
- 覆盖**需求设计 → 代码生成 → 测试 → 维护**全流程。
- **支持自定义模型**。
- 通过"编程智能体增强技术"自动调优 Tools 和 Skills，实现快速验证与修复的**自闭环**。

**开放给开发者的 Skills（举例）**：

| Skill | 能力 |
|---|---|
| **多设备开发 Skill** | 一句"帮我适配"，把直板机工程快速适配到折叠屏等多形态，解决避让、旋转、硬件调用问题，**多设备适配效率再提升 50%** |
| **问题定位 Skill** | 一句"帮我定位"，处理内存泄漏、崩溃、闪退，支持**根因定位、自修复、自验证** |

### 3.2 DevEco CLI —— 面向编程 Agent 的命令行工具

- 面向**编程 Agent** 而非人，是一条"命令行工具链"。
- 支持市面**主流编程 Agent**（Claude Code、Cursor 等）调用。
- 提供鸿蒙开发**全流程工具** + 丰富知识 + 精品 Skills：创建工程 → 语法检查 → 编译构建 → 运行调测，全部**对 Agent 友好**。
- 华为把 **2000 多万字**鸿蒙官方文档转成了**可被 Agent 调用的知识资源**，让第三方主流 Agent 也能"懂鸿蒙"。

> **面试话术（高分答案）**："鸿蒙在 AI 辅助研发上做得比较系统。DevEco Code 是懂鸿蒙的编程智能体，覆盖研发全流程，亮点是多设备适配 Skill 和问题定位 Skill——后者能做内存泄漏、闪退的根因定位和自修复自验证。DevEco CLI 则把整个鸿蒙开发链路的工具、知识、Skills 都做成 Agent 可调用形式，官方还把两千万字文档转成了 Agent 可调用的知识库。华为的思路是让鸿蒙开发范式本身向 **Agentic 工程**跃迁——这对效率提升是结构性的，不只是加个补全插件。"

### 3.3 AI 辅助研发的实践数据（真实案例，可引用）

| 伙伴 | 成果 |
|---|---|
| **抖音鸿蒙版** | AI 智能测试批量应用，主功能点覆盖 100%，部分高频规则明确场景有效成功率 70%，整体验证效率提升 20% |
| **快手** | 构建 Agent Loop 双循环体系，人效提升 **1.7 倍**，开发阶段 AI 代码生成率 **80%**，测试用例直出采纳率 **84%**，运维排障修复建议采纳率 **73%** |
| **快手 × 鸿蒙 Ark Refiner-Sendable Skill** | 性能优化从"两人一周"缩短至**半天**，冷启动整体提升 **16%** |
| **《时光旅记》** | 仅 **14 天**完成开发，上线 20 天用户破万，获 AppGallery 编辑精选；通过 Agent Framework Kit 接入小艺智能体，**10 分钟**实现旅行照片地点历史故事自动回顾 |

---

## 四、跨平台与生态

### 4.1 KMP & CMP 跨端框架（2026 开启首版本 Beta）

- 腾讯视频基于该方案实现**一套代码三端运行**，鸿蒙功能完善度 **95%**，三端代码复用率 **90%**。
- 性能：DMA 内存下降 95%，卡顿率下降 90%，冷启动"秒起秒开"，编译时间 4h → 50min。
- **对纯血鸿蒙开发者的意义**：如果公司有跨端需求，KMP/CMP 是降低迁移成本的重要选项。

### 4.2 仓颉（Cangjie）语言

- 华为自研静态类型语言，2025 年 7 月开源。
- 语法融合 Kotlin / Swift 特性，支持协程、异步。
- 与 HMAF 深度集成，实现 AI 能力方便。
- 现状：资料少、社区不活跃，**面试提及作为技术视野即可**，不建议作为主线。

### 4.3 无障碍能力

- 官方 HDC2026 提到一位视障开发者（视力 0.05）借助鸿蒙**无障碍能力**和 DevEco Studio 从零学会编程，开发了面向视障按摩场景的应用。
- 开发要点：`accessibilityText` / `accessibilityDescription` / `accessibilityLevel` + `groupDefaultStyle`。

---

## 五、工程化：构建、签名、打包、发布

### 5.1 Hvigor 构建体系

**Hvigor** 是鸿蒙的构建工具（类比 Gradle），基于 **Node.js**。

**关键概念**：

| 概念 | 说明 |
|---|---|
| `hvigorfile.ts` | 构建脚本入口（工程级 + 模块级各一个） |
| `hvigor-config.json5` | 构建配置 |
| `oh-package.json5` | 依赖声明 |
| Task | 构建任务（compileArkTS、assembleHap、signHap…） |
| Plugin | 构建插件（`@ohos/hvigor-ohos-plugin`） |
| `hvigorw` | Wrapper 脚本 |

**命令行构建**：
```bash
# 全流程构建
hvigorw clean assembleHap --mode module -p product=default

# 只构建 release
hvigorw assembleApp --mode module -p product=default -p buildMode=release

# 查看任务
hvigorw --mode module -p product=default tasks
```

### 5.2 工程配置：build-profile.json5

```json5
{
  "app": {
    "signingConfigs": [
      {
        "name": "default",
        "type": "local",
        "material": {
          "storeFile": "C:/Users/xxx/.ohos/config/default.p12",
          "storePassword": "123456",
          "keyAlias": "debugKey",
          "keyPassword": "123456",
          "signAlg": "SHA256withECDSA",
          "profile": "C:/Users/xxx/.ohos/config/default.p7b"
        }
      }
    ],
    "products": [
      {
        "name": "default",
        "signingConfig": "default",
        "compatibleSdkVersion": "5.0.0(12)",
        "runtimeOS": "HarmonyOS"
      }
    ],
    "buildModeSet": [
      { "name": "debug" },
      { "name": "release" }
    ]
  },
  "modules": [
    {
      "name": "entry",
      "srcPath": "./entry",
      "targets": [
        {
          "name": "default",
          "applyToProducts": ["default"]
        }
      ]
    }
  ]
}
```

**关键字段**：
- `compatibleSdkVersion` —— 最低兼容版本（决定能装到老设备上）。
- `targetSdkVersion` —— 目标版本（在 module.json5 或 hvigor 配置）。
- `runtimeOS` —— HarmonyOS / OpenHarmony。
- `signingConfig` —— 签名配置名。

### 5.3 签名体系（理解这四件套）

```
.cer    证书（开发者身份）
.p12    私钥（PKCS#12，含证书 + 私钥）
.p7b    证书 Profile（描述哪个 bundleName、哪些设备、哪个证书）
.keystore  密钥库
```

**流程**：
1. 在 AppGallery Connect 创建**开发者证书**（.cer）。
2. 创建**密钥**（生成 .p12 + 密码）。
3. 创建**Profile**（.p7b），选择证书 + 设备（调试需要设备 UDID）。
4. 在 `build-profile.json5` 里配置 `signingConfigs`。
5. 发布需向 AGC 申请 **发布证书**（自动签名可用云端自动签名）。

> **面试话术**："鸿蒙的签名体系是四件套：证书 cer 是身份，p12 是私钥，p7b 是 Profile（把证书、bundleName、设备 UDID 绑定起来的授权文件）。调试包和发布包用不同的证书，发布包需要向 AppGallery Connect 申请发布证书，上传 .p7b 到后台。"

### 5.4 打包产物

```
build/
├── outputs/
│   ├── default/
│   │   ├── entry-default-unsigned.hap        未签名
│   │   ├── entry-default-signed.hap          已签名（可安装）
│   │   └── ...
│   └── default/
├── intermediate/     中间产物
└── ...
```

**安装命令**：
```bash
hdc install -r entry-default-signed.hap
```

### 5.5 多模块与共享

| 场景 | 方案 |
|---|---|
| 应用内多 HAP 共享代码/资源 | **HSP**（Harmony Shared Package） |
| 纯逻辑模块，无上下文依赖 | **HAR**（Harmony Archive） |
| 原生 C/C++ 代码 | **Native .so** + `oh-package.json5` 声明 |
| 跨应用共享（对外分发） | HSP 的 SharedPackage 形态 |

**HSP 示例**：
```json5
// entry/oh-package.json5
{
  "dependencies": {
    "shared": "file:../shared"
  }
}
```

### 5.6 性能检测工具

| 工具 | 用途 |
|---|---|
| **SmartPerf** | CPU/内存/FPS/温度 实时监控 |
| **ArkTS Profiler** | 性能分析，函数耗时、内存分配 |
| **LeakInspector** | 内存快照，定位泄漏对象 |
| **Layout Inspector** | UI 层级树、属性查看 |
| **Network Inspector** | 网络抓包分析 |
| **HiTrace** | 分布式链路追踪（跨设备） |
| **hiperf** | 打点埋点 |
| **hilog** | 日志 |

### 5.7 发布流程

```
1. 开发者中心实名认证
2. 创建应用（申请包名 bundleName）
3. 开发 + 自测
4. 上传 HAP 到 AGC（版本管理）
5. 配置应用信息（图标、截图、隐私政策、权限说明）
6. 提交审核（AppGallery 人工 + 自动化检测）
7. 审核通过 → 上架
```

**上架审核重点（面试可能问）**：
- 隐私政策弹窗合规（时机、内容、勾选方式）。
- 权限最小化 + reason 与实际调用一致。
- 应用功能完整、无明显崩溃。
- 元服务需要单独审核。

---

## 六、常见工程规范（体现专业度）

### 6.1 目录分层（推荐）

```
entry/src/main/ets/
├── entryability/       # 入口
├── pages/              # 页面
├── components/         # 可复用组件
├── viewmodel/          # ViewModel
├── model/              # 数据模型
├── repository/         # 数据仓库（网络/DB）
├── service/             # 业务服务
├── utils/              # 工具
└── constants/          # 常量
```

### 6.2 命名约定

| 类型 | 约定 | 示例 |
|---|---|---|
| 组件文件 | PascalCase | `TodoList.ets` |
| 工具/服务文件 | camelCase | `httpClient.ets` |
| 常量 | UPPER_SNAKE | `MAX_RETRY_COUNT` |
| 接口 | I + PascalCase | `ITodoRepository` |
| 私有成员 | `_` 前缀 | `_privateField` |

### 6.3 错误处理约定

```ts
// 统一错误类型
export class AppError extends Error {
  readonly code: number;
  constructor(code: number, message: string) {
    super(message);
    this.code = code;
  }
}

// 统一错误映射
export function toAppError(e: BusinessError): AppError {
  const map: Map<number, string> = new Map([
    [16000001, '网络不可用'],
    [16000004, '请求超时'],
    [23000001, '数据保存失败']
  ]);
  return new AppError(e.code, map.get(e.code) ?? '未知错误');
}
```

### 6.4 日志规范

```ts
import { hilog } from '@kit.PerformanceAnalysisKit';

const TAG = 'MyApp';
const DOMAIN = 0x0000;

hilog.info(DOMAIN, TAG, 'user login, id=%{public}d', userId);
hilog.warn(DOMAIN, TAG, 'request failed: %{public}s', JSON.stringify(err));
hilog.error(DOMAIN, TAG, 'crash point');
```

> **注意**：**正式包里的日志会被自动关闭**（`hilog.isLoggable()` 返回 false），不要依赖日志做业务逻辑。

---

## 七、性能优化的系统方法论（面试项目亮点）

### 7.1 启动优化

```
冷启动 = 进程创建 + Ability.onCreate + WindowStage创建 + loadContent + 首帧渲染
```

| 阶段 | 优化手段 |
|---|---|
| onCreate | 只做必要初始化；非关键服务延迟/异步 |
| loadContent | 首屏结构简单；数据本地优先 |
| 首帧 | 减少组件层级；图片异步加载 |
| 全局 | AppStorage 预热；避免首屏复杂计算 |

### 7.2 渲染优化

1. **状态下沉**：把状态放到真正需要它的最小作用域，减少刷新范围。
2. **列表优化**：`ForEach` + 稳定 key + `lazyForEach` + 合理 `cachedCount`。
3. **减少 build() 逻辑**：build() 只做 UI 描述。
4. **避免深嵌套**：用 `GridRow` 替代多层 Row/Column。
5. **图片**：必设宽高（触发解码优化）、用 `objectFit`、用 `Image` 的缓存能力。

### 7.3 内存优化

1. 及时 `close()` ResultSet、`destroy()` Http、释放 TaskPool 任务。
2. 长列表用懒加载。
3. 监听 `onMemoryLevel` 释放缓存。
4. 大图片用 `PixelMap` 压缩 + 缩放。
5. 定期用 LeakInspector 检查。

### 7.4 电量优化

1. 后台任务用声明式（WorkScheduler），不常驻。
2. 定位用低频/低精度模式。
3. 定时刷新用系统唤醒而非轮询。
4. 动画在后台停止。

---

## 八、本章自测题

1. HarmonyOS 6 对应哪个 API Level？SDK 26 是什么？
2. Ark Agent Framework 的三层架构是什么？端侧 Skills 与云端 Skills 的区别？
3. 什么是生成式 UI？它解决了什么体验问题？
4. DevEco Code 和 DevEco CLI 各自解决什么问题？
5. 快手和抖音的 AI 辅助研发数据分别是什么？（考察是否真的关注行业）
6. 沉浸光感是什么？接入成本如何？
7. HarmonyOS 的签名四件套是什么？各自的含义？
8. `compatibleSdkVersion` 和 `targetSdkVersion` 的区别？
9. HAP / HSP / HAR 分别在什么场景使用？
10. 性能优化中，列表和启动各有哪些关键手段？

---

## 九、参考答案要点

1. HarmonyOS 6.0 = API 20；SDK 26 是 HDC2026（2026.06）发布的最新 SDK，开放互联、智能、空间感、性能等能力。
2. 感知层（多模态输入语义化）→ 决策层（大模型意图识别与任务规划）→ 执行层（调用系统 API 与第三方服务）。端侧 Skills 随应用发布上架、可离线；云端 Skills 动态下发、可快速迭代无需更新应用。
3. 让大模型输出从纯文本升级为可交互的原生 UI（图文并茂、可以直接呈现为 ArkUI 组件），提升 AI 输出的可交互性与视觉表现。
4. DevEco Code 是"懂鸿蒙"的编程智能体，覆盖需求设计到维护全流程，含多设备适配 Skill、问题定位 Skill（根因定位+自修复+自验证）；DevEco CLI 是面向编程 Agent 的命令行工具链，把工程创建、语法检查、构建、运行调测全部做成 Agent 可调用形式，并提供 2000 多万字文档转化的知识资源。
5. 快手：人效提升 1.7 倍，AI 代码生成率 80%，测试用例直出采纳率 84%，排障建议采纳率 73%，冷启动提升 16%；抖音：主功能点覆盖 100%，部分场景有效成功率 70%，验证效率提升 20%。
6. 沉浸光感让界面组件产生光影分层效果（随环境/内容变化），官方案例提到仅需 1 行代码即可接入。
7. .cer 证书（开发者身份）、.p12 私钥、.p7b Profile（绑定证书+bundleName+设备）、.keystore 密钥库。
8. compatibleSdkVersion 是最低可安装的系统版本（向下兼容）；targetSdkVersion 是适配的目标版本（决定行为按哪套规则执行）。compatible 低、target 高 = 只在支持新特性的设备上启用。
9. HAP 独立应用包（entry）；HSP 应用内多个 HAP 共享代码与资源；HAR 纯静态库，适合无上下文依赖的逻辑模块（编译期链接）。
10. 列表：ForEach + 稳定唯一 key + lazyForEach + cachedCount + 简化 item 层级 + @Reuse 保状态；启动：onCreate 轻量化、延迟初始化非关键服务、本地优先渲染、减少首屏层级、AppStorage 预热、复杂计算放 TaskPool。

---

**上一章**：[教程 09：权限、安全与跨应用](09-权限安全与跨应用.md)
**下一章**：[面试题库与项目实战路线](11-面试题库与实战路线.md)
