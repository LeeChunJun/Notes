# 教程 06：应用模型与 UIAbility 生命周期

> **目标**：彻底搞懂 Stage 模型的应用运行机制：进程与 Ability 的关系、UIAbility 生命周期、WindowStage、Want 机制、上下文获取、后台任务与多实例。这是面试中考察"是否真正理解鸿蒙架构"的关键部分。
>
> **预计阅读**：55 分钟

---

## 一、Stage 模型的三层运行结构

```
应用进程 (Application Process)
    │
    ├── UIAbility 实例 A        ← 对应一个界面（WindowStage → Window → 页面）
    │     ├── WindowStage
    │     │     └── MainWindow (windowStage.loadContent)
    │     └── Window
    │
    ├── UIAbility 实例 B        ← 多实例（如从不同 Want 启动）
    │
    └── ExtensionAbility 实例
          ├── ServiceExtensionAbility
          ├── FormExtensionAbility
          └── DataShareExtensionAbility
```

**核心概念澄清（面试高频陷阱）**：

| 问题 | 正确答案 |
|---|---|
| 一个应用有几个 UIAbility？ | **取决于 module.json5 的配置**，可以是 1 个或多个 |
| 一个 UIAbility 有几个窗口？ | 一个 UIAbility 绑定一个 **WindowStage**，WindowStage 里通常有 1 个主窗口（可以多） |
| 一个 WindowStage 能加载几个页面？ | `loadContent` 加载的是一个**页面组件树**（含 Navigation 可承载多页面） |
| 进程和 Ability 什么关系？ | **多进程**：一个应用可有多个进程（如 Web 渲染进程、卡片进程），Ability 运行在某个进程中 |
| Ability 销毁了页面一定销毁吗？ | 不一定，Stage 模型解耦了两者 |

> **关键理解**：`WindowStage` 是 Ability 与 UI 之间的中间层。Ability 负责生命周期与业务逻辑，WindowStage 管理窗口，窗口承载页面内容。

---

## 二、UIAbility 生命周期全图

```
onCreate()          ─┐
  │                   │
onWindowStageCreate() ─┤  启动阶段
  │                   │
onForeground()      ─┤
  │                   │
  ↓                   │
onBackground()      ─┤  后台阶段
  │                   │
onForeground()      ─┤  再次回到前台
  │                   │
onWindowStageDestroy()─┤  销毁阶段
  │                   │
onDestroy()         ─┘
```

### 2.1 完整代码骨架

```ts
import { UIAbility, AbilityConstant, Want, AbilityLaunchParam } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { BusinessError } from '@kit.BasicServicesKit';

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityLaunchParam): void {
    // ★ 应用/Ability 创建时调用，整个生命周期只调一次
    // 适合：初始化全局数据、注册全局监听、AppStorage 初始化
    hilog.info(0x0000, 'EntryAbility', 'onCreate');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    // ★ WindowStage 创建后调用，此时还没有 UI
    hilog.info(0x0000, 'EntryAbility', 'onWindowStageCreate');

    // 加载页面内容
    windowStage.loadContent('pages/Index', (err: BusinessError) => {
      if (err.code) {
        hilog.error(0x0000, 'EntryAbility', `loadContent failed: ${err.code}`);
        return;
      }
      hilog.info(0x0000, 'EntryAbility', 'loadContent success');
    });
  }

  onForeground(): void {
    // ★ 进入前台
    hilog.info(0x0000, 'EntryAbility', 'onForeground');
  }

  onBackground(): void {
    // ★ 退到后台（注意：不是销毁！）
    hilog.info(0x0000, 'EntryAbility', 'onBackground');
  }

  onWindowStageDestroy(): void {
    // ★ WindowStage 销毁（UI 没了，但 Ability 可能还在）
    hilog.info(0x0000, 'EntryAbility', 'onWindowStageDestroy');
  }

  onDestroy(): void {
    // ★ Ability 销毁，释放资源
    hilog.info(0x0000, 'EntryAbility', 'onDestroy');
  }

  onNewWant(want: Want): void {
    // ★ 已存在实例时再次被拉起（singleton/multiInstance 场景）
    hilog.info(0x0000, 'EntryAbility', 'onNewWant');
  }

  onConfigurationUpdate(newConfig: Configuration): void {
    // ★ 配置变化（深色模式、语言、屏幕尺寸变化）
    hilog.info(0x0000, 'EntryAbility', 'onConfigurationUpdate');
  }

  onMemoryLevel(level: AbilityConstant.MemoryLevel): void {
    // ★ 内存紧张，系统通知你释放资源
    if (level === AbilityConstant.MemoryLevel.MEMORY_LEVEL_CRITICAL) {
      // 释放图片缓存、非必要数据
    }
  }

  onSaveState(reason: AbilityConstant.StateType, wantParam: Want): AbilityConstant.OnSaveResult {
    // ★ 保存状态（应用被系统回收前）
    return AbilityConstant.OnSaveResult.ALL_AGREE;
  }
}
```

### 2.2 各回调的典型用途（面试答"在哪儿做什么"）

| 回调 | 次数 | 典型用途 | ❌ 不该做 |
|---|---|---|---|
| `onCreate` | 1 次 | 全局初始化、注册 App 级服务 | 长耗时操作（会拖慢启动） |
| `onWindowStageCreate` | 每次窗口创建 | `loadContent` 加载页面 | — |
| `onForeground` | 每次回前台 | 恢复动画、重新请求数据、刷新 UI | — |
| `onBackground` | 每次进后台 | 保存数据、释放占用资源、停止动画 | — |
| `onWindowStageDestroy` | 每次窗口销毁 | 清理 UI 相关资源 | — |
| `onDestroy` | 1 次 | 释放所有资源、注销全局监听 | — |
| `onNewWant` | 多次 | 处理新的启动参数（热启动新请求） | — |
| `onConfigurationUpdate` | 多次 | 响应深色模式/语言变化 | — |
| `onMemoryLevel` | 多次 | 内存紧张时释放缓存 | — |
| `onSaveState` | 多次 | 保存恢复现场数据 | — |

> **面试话术**："区分 `onBackground` 和 `onDestroy` 是关键。`onBackground` 只表示切到后台，Ability 还活着，可以被拉回前台且状态保留；`onDestroy` 才表示实例被真正销毁。所以在 `onBackground` 里要保存易失状态、在 `onDestroy` 里才做彻底清理。"

---

## 三、Want 机制

### 3.1 Want 是什么

Want 是鸿蒙的**消息载体**，用于：
1. **启动组件**：显式（bundleName + abilityName）或隐式（action + entity）
2. **传递参数**：`parameters`
3. **跨应用通信**：`startAbility`、`sendMessage`

```ts
import { Want } from '@kit.AbilityKit';

const want: Want = {
  bundleName: 'com.example.myapp',
  abilityName: 'EntryAbility',
  parameters: {
    userId: 123,
    source: 'home'
  }
};
```

### 3.2 显式 Want 与隐式 Want

```ts
// 显式：明确指定目标
const want: Want = {
  bundleName: 'com.example.myapp',
  abilityName: 'DetailAbility'
};

// 隐式：按 action + entity 匹配（需在 module.json5 的 skills 中声明）
const want: Want = {
  action: 'ohos.want.action.viewData',
  entities: ['entity.system.browsable'],
  uri: 'https://example.com',
  parameters: { key: 'value' }
};
```

**module.json5 中声明 skills（隐式匹配规则）**：
```json5
{
  "name": "EntryAbility",
  "skills": [
    {
      "entities": ["entity.system.home"],
      "actions": ["action.system.home"]
    },
    {
      "entities": ["entity.system.browsable"],
      "actions": ["ohos.want.action.viewData"]
    }
  ]
}
```

### 3.3 参数传递的三种方式

```ts
// 1. 启动时传参
const want: Want = {
  bundleName: 'com.example.myapp',
  abilityName: 'EntryAbility',
  parameters: { userId: 123 }
};

// 2. 接收端读取
onCreate(want: Want, launchParam: AbilityLaunchParam): void {
  const userId = want.parameters?.['userId'] as number;
}

// 3. 已运行时更新参数
onNewWant(want: Want): void {
  const userId = want.parameters?.['userId'] as number;
  // 更新 UI
}
```

**参数类型限制（API 12+）**：
- `parameters` 支持的类型：`number`、`string`、`boolean`、`object`、`array`、`undefined`、`null`。
- **对象类型的嵌套深度有限制**（API 12 起嵌套层数限制为 9 层），且对象内不能是函数/类实例。
- **API 20 起**：`parameters` 支持更多类型。

> **面试要点**："API 12 之后 Want 的参数传递做了类型收紧，只允许可序列化的基础类型、数组和对象，嵌套深度有上限。这是为了避免跨进程传大对象导致的性能和安全问题，大数据应该走文件路径或数据库。"

---

## 四、上下文（Context）与 `getContext()`

### 4.1 上下文类型

| 上下文 | 获取方式 | 用途 |
|---|---|---|
| **ApplicationContext** | `this.context.getApplicationContext()` | 全局级操作：获取 bundleInfo、创建模块上下文 |
| **UIAbilityContext** | `this.context` (在 Ability 中) | Ability 级操作：startAbility、请求权限、获取资源 |
| **UIContext** | `this.getUIContext()` (在组件中) | UI 级操作：路由、弹窗、Toast |
| **Context**（通用） | `getContext(this)` | 组件内通用上下文 |

### 4.2 常用 Context API

```ts
// 在 Ability 中
export default class EntryAbility extends UIAbility {
  someMethod(): void {
    const ctx = this.context;

    // 获取资源
    const res = ctx.resourceManager.getStringSync($r('app.string.app_name'));

    // 获取应用信息
    const bundleInfo = ctx.applicationInfo;

    // 获取 AbilityInfo
    const abilityInfo = ctx.abilityInfo;

    // 获取当前 UIContext（Ability 中）
    const uiCtx = ctx.getUIContext?.();
  }
}
```

### 4.3 `getContext(this)` 的用法

```ts
import { common } from '@kit.AbilityKit';

@Component
struct Comp {
  build() {
    Text('hi').onClick(() => {
      const ctx = getContext(this) as common.UIAbilityContext;
      const ability = ctx.ability;   // 获取 UIAbility 实例
    })
  }
}
```

> **面试话术**："鸿蒙提供多级上下文：ApplicationContext 管全局、UIAbilityContext 管 Ability 级能力、UIContext 管 UI 操作。选对上下文层级是权限和资源访问正确的前提，也是架构解耦的体现。"

---

## 五、ExtensionAbility 家族

### 5.1 ServiceExtensionAbility（后台服务）

```ts
import { ServiceExtensionAbility } from '@kit.AbilityKit';

export default class BackgroundService extends ServiceExtensionAbility {
  onCreate(want: Want): void {
    // 后台服务启动
  }
  onRequest(want: Want, startId: number): void {
    // 处理来自其他应用的请求
  }
  onDestroy(): void {
  }
}
```

**特点**：
- 无界面，长期在后台运行。
- 有**启动配额限制**（系统管控，不能无限常驻）。
- HarmonyOS 6 强化了后台管控，建议配合 **后台任务（BackgroundTasks）** 或**长时任务（Long Term Task）**。

### 5.2 FormExtensionAbility（服务卡片）

```ts
import { FormExtensionAbility } from '@kit.AbilityKit';

export default class FormAbility extends FormExtensionAbility {
  onCreateForm(want: Want): void {
    // 创建卡片
  }
  onUpdateForm(want: Want): void {
    // 更新卡片内容（系统定时或被调用）
  }
  onConfigurationUpdate(newConfig: Configuration): void {
  }
  onFormEvent(formId: string, clicked: FormComponentEvent): void {
    // 卡片上按钮点击事件
  }
  onAcquireFormState(): FormState {
    return FormState.FORM_STATE_USABLE;
  }
}
```

**卡片形态状态**：
- `FORM_STATE_UNKNOWN`
- `FORM_STATE_USABLE` —— 可用
- `FORM_STATE_DISABLED` —— 禁用（如应用后台受限）
- `FORM_STATE_UPDATE_REASON` —— 需要更新

### 5.3 DataShareExtensionAbility（数据共享）

```ts
import { DataShareExtensionAbility, DataShareData, DataShareOperationOptions } from '@kit.DataShareKit';

export default class DataShareAbility extends DataShareExtensionAbility {
  onCreate(want: Want, las: DataShareOperationOptions): void {
  }

  onQuery(dataShareData: DataShareData,
           callback: (dataShareData: DataShareData) => void): void {
    // 处理查询
    const result: DataShareData = {
      recordCount: 1,
      records: [{ key: 'name', value: 'Tom' }]
    };
    callback(result);
  }

  onInsert(dataShareData: DataShareData,
           callback: (dataShareData: DataShareData) => void): void {
  }

  onUpdate(dataShareData: DataShareData,
           callback: (dataShareData: DataShareData) => void): void {
  }

  onDelete(dataShareData: DataShareData,
           callback: (dataShareData: DataShareData) => void): void {
  }
}
```

**用途**：跨应用共享数据（如通讯录读取、剪贴板共享）。

### 5.4 其他 Extension

| 类型 | 用途 |
|---|---|
| `InputMethodExtensionAbility` | 输入法 |
| `NotificationExtensionAbility` | 通知服务（本地通知点击处理） |
| `UIExtensionAbility` | 悬浮窗、选单面板、密码输入面板 |
| `AppControlExtensionAbility` | 管控类 |

---

## 六、启动模式与多实例

### 6.1 module.json5 的 launchType

```json5
{
  "abilities": [{
    "name": "EntryAbility",
    "launchType": "singleton",   // ★ 关键配置
  }]
}
```

| launchType | 行为 | 使用场景 |
|---|---|---|
| `singleton` | 单例，已存在则复用并触发 `onNewWant` | 普通应用（默认） |
| `multiton` | 多实例，每个请求新建实例 | 多任务、需要区分会话的场景（如会议） |
| `specified` | 指定实例，需指定 `instanceKey` | 复杂场景 |

### 6.2 多实例代码

```ts
const want: Want = {
  bundleName: 'com.example.myapp',
  abilityName: 'EntryAbility',
  parameters: { instanceKey: 'session_' + Date.now() }
};

// 配置 launchType: "multiton" 后
await this.context.startAbility(want);
```

---

## 七、应用启动流程（面试高频）

```
用户点击桌面图标
      │
      ▼
系统解析 module.json5，找到 mainElement 指定的 Ability
      │
      ▼
创建 Ability 实例 → onCreate(want, launchParam)
      │
      ▼
创建 WindowStage → onWindowStageCreate(windowStage)
      │
      ▼
windowStage.loadContent('pages/Index')
      │  → 创建 @Entry 组件 → build() → 渲染
      ▼
onForeground()
      │
      ▼
显示窗口（onShown 属于窗口级回调）
```

**冷启动 vs 热启动**：
- **冷启动**：进程不存在 → 走完整流程。
- **热启动**：进程存在、Ability 存在 → 只走 `onNewWant` → `onForeground`。
- **热启动（进程存在但 Ability 被回收）**：重建 Ability → `onCreate` → `onWindowStageCreate` → `onForeground`。

> **面试话术**："理解冷热启动的区别对做启动优化很重要。冷启动耗时要优化 `onCreate` 里的初始化逻辑，常用手段是延迟初始化非关键服务、把初始化放到 `onForeground` 之后异步做。"

---

## 八、后台任务与长时任务

### 8.1 三类后台任务

| 类型 | API | 场景 | 有效期 |
|---|---|---|---|
| **短时任务** | `backgroundTaskManager.requestShortTimeAccess` | 短暂后台工作（几秒~几分钟） | 短 |
| **长时任务** | `backgroundTaskManager.startLongRunningTask` | 播放音乐、导航、下载等 | 长（用户可在设置中查看） |
| **代理提醒** | `reminderAgentManager` | 定时提醒（闹钟类） | 可定时 |

### 8.2 长时任务示例

```ts
import { backgroundTaskManager } from '@kit.BackgroundTasksKit';
import { BusinessError } from '@kit.BasicServicesKit';

export default class EntryAbility extends UIAbility {
  onCreate(): void {
    this.startLongTask();
  }

  private startLongTask(): void {
    backgroundTaskManager.startLongRunningTask(
      { type: 'audioPlayback', notificationId: 1001 },
      (context: BackgroundTaskContext) => {
        // 任务被系统终止前的回调
        this.stopLongTask();
      },
      (err: BusinessError) => {
        if (err.code) {
          console.error(`长时任务启动失败: ${err.code}`);
          return;
        }
        console.info('长时任务已启动');
      }
    );
  }

  private stopLongTask(): void {
    backgroundTaskManager.stopLongRunningTask(1001);
  }
}
```

**长时任务类型（HarmonyOS 6 支持）**：`dataTransfer`、`audioPlayback`、`audioRecording`、`location`、`dataSync`、`videoPlayback`、`CPU_FOREGROUND` 等。

---

## 九、后台管控与合规（HarmonyOS 6 重点）

HarmonyOS（尤其 5.0 之后）对后台做了**严格管控**：

### 9.1 管控维度

1. **后台启动限制**：应用退到后台后，不能随意启动其他 Ability 或 ExtensionAbility。
2. **后台任务配额**：后台任务数量、时长受限。
3. **豁免规则**：以下行为被允许（部分）：
   - 音乐/视频播放（需申请长时任务）
   - 定位（需申请长时任务）
   - 数据同步（短时任务）
   - 定时提醒（代理提醒）
   - 用户主动触发（如点击通知）

### 9.2 权限申请

```ts
import { abilityAccessCtrl, common } from '@kit.AbilityKit';

async function requestBackgroundPermission(): Promise<boolean> {
  const atManager = abilityAccessCtrl.createAtManager();
  const context = getContext(this) as common.UIAbilityContext;

  try {
    const result = await atManager.requestPermissionsFromUser(
      context,
      ['ohos.permission.KEEP_BACKGROUND_RUNNING']   // 后台运行权限
    );
    return result.authResults[0] === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
  } catch (err) {
    console.error(`权限申请失败: ${(err as BusinessError).code}`);
    return false;
  }
}
```

### 9.3 违规后果

- 应用可能被**限制后台活动**。
- 严重时**下架**（上架审核会检查）。
- `console` 输出的后台违规日志会被系统采集。

> **面试话术**："HarmonyOS 的后台管控是产品设计的核心，不是简单的限制。开发者要转变思路：从'进程常驻'转为'事件驱动'——用后台任务框架声明任务意图，系统按需调度，而不是靠常驻保活。这既是合规要求，也是续航优化的方向。"

---

## 十、Ability 生命周期与页面生命周期的关系

```
Ability 生命周期                          页面/组件生命周期
─────────────────────────────────────────────────────────────
onCreate()          ──┐
onWindowStageCreate() │  aboutToAppear()  → build() → onAppear()
                      │
onForeground()      ──┤  onPageShow()
                      │
onBackground()      ──┤  onPageHide()
                      │
onWindowStageDestroy() │  onDisappear() → aboutToDisappear()
onDestroy()         ──┘
```

**关键点**：
- `onPageShow` / `onPageHide` 是**页面级**回调，在 `@Entry` 组件中定义。
- 页面重建（配置变化、深色模式切换）时，Ability **不重启**，但页面会重新 build。

**深色模式切换的实际影响**：
```
onConfigurationUpdate(newConfig)
  → 触发页面重新创建（onPageHide → aboutToDisappear → onPageShow → aboutToAppear → build）
```
> 所以深色模式下尽量避免在 `aboutToAppear` 里做重初始化（如重新请求网络），否则切换主题就会重新拉数据。应该在 `onConfigurationUpdate` 里**只更新颜色资源**，用 `this.getUIContext().getHostContext()?.resourceManager` 重新读取。

---

## 十一、本章自测题

1. Stage 模型中，Ability、WindowStage、Window、页面四者的关系是什么？
2. `onBackground` 和 `onDestroy` 的区别是什么？各自该做什么？
3. `onNewWant` 什么时候触发？解决什么问题？
4. Want 的显式和隐式写法分别是什么？隐式匹配依赖什么配置？
5. Want 的 parameters 支持哪些类型？有什么限制？为什么这么限制？
6. ApplicationContext / UIAbilityContext / UIContext 各自的定位？
7. `loadContent` 应该放在哪个回调里？为什么不放 onCreate？
8. 冷启动和热启动的流程差异？
9. 长时任务和短时任务的区别？举几个长时任务类型。
10. HarmonyOS 6 的后台管控有哪些要求？开发者应该如何应对？

---

## 十二、参考答案要点

1. Ability 持有 WindowStage，WindowStage 管理 Window，Window 通过 loadContent 承载页面组件树。Ability 生命周期与 UI 生命周期解耦。
2. onBackground 只是切到后台，实例仍存活、状态保留，适合保存易失状态、释放占用资源；onDestroy 是实例真正销毁，要彻底释放资源、注销监听。
3. 应用已在运行（singleton 模式）时再次被拉起触发，用于处理新的启动参数，实现"热启动带新请求"（如消息跳转）。
4. 显式：bundleName + abilityName；隐式：action + entities（+ uri）。隐式匹配依赖 module.json5 中 ability 的 skills 声明。
5. 支持 number/string/boolean/object/array/null/undefined；对象嵌套有层数上限，且不含函数和类实例。限制原因：跨进程传递大对象性能和安全性差，大数据应走文件或数据库。
6. ApplicationContext 应用级（bundleInfo、应用资源）；UIAbilityContext Ability 级（启动组件、权限、资源）；UIContext UI 级（路由、弹窗、Toast）。
7. 放在 onWindowStageCreate。此时 WindowStage 已就绪，可以 loadContent；放 onCreate 时还没有 WindowStage，无法加载内容。
8. 冷启动进程不存在，走 onCreate → onWindowStageCreate → loadContent → onForeground 全流程；热启动进程和实例都在，只走 onNewWant → onForeground。
9. 长时任务用于需要持续运行的场景（播放、导航、下载、定位），有对应类型枚举（如 audioPlayback、location）；短时任务用于短时间的后台工作，时长受限。
10. 限制后台自启动、后台任务配额、后台常驻；需声明长时任务类型与权限。应对思路从"常驻保活"转为"事件驱动 + 声明式后台任务"，按需调度。

---

**上一章**：[教程 05：页面路由与 Navigation](05-页面路由与Navigation.md)
**下一章**：[教程 07：数据管理](07-数据管理.md)
