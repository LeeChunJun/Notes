# 教程 05：页面路由与 Navigation 组件

> **目标**：掌握 HarmonyOS 6 的两套路由方案 —— 传统的 `router` 与现代的 `Navigation` + `NavDestination`，以及 `NavigationTransition`、`NavPathStack` 的完整用法。这是面试的必考点，也是实际开发最常踩坑的地方。
>
> **预计阅读**：50 分钟

---

## 一、两套路由方案对比

| 维度 | `router`（传统） | `Navigation` + `NavDestination`（推荐） |
|---|---|---|
| 页面表示 | 字符串路径 | **Builder 函数**（强类型） |
| 参数传递 | `router.pushUrl({ params })` | `pathStack.pushPathByName(name, param)` 或 `pushDestination` |
| 类型安全 | ❌ 弱（params 是 Object） | ✅ 强（泛型约束） |
| 转场动画 | 简单淡入 | **丰富**（NavigationTransition、sharedTransition） |
| 拦截 | `router.getLength()` 难拦截 | `onWillShow`/`onShown`/`onHidden`/`onWillHide` 拦截齐全 |
| 侧滑返回 | 系统默认 | **可接管**，支持手势返回的动画干预 |
| 导航栏 | 需自己写 | **内置 NavigationBar / Title** |
| 状态保持 | 弱 | 强（NavDestination 有独立生命周期） |
| 官方推荐 | 兼容保留 | ✅ **新项目首选** |

> **面试话术**："HarmonyOS 现在推荐用 `Navigation` + `NavDestination` 替代 `router`。核心优势是参数类型安全、可拦截跳转/返回、能精细控制转场动画与侧滑手势，而且 NavDestination 有独立的生命周期（onAppear/onHidden），比 router 的 pushUrl/pop 更可控。`router` 保留是为了兼容存量代码。"

---

## 二、router 传统路由（复习 + 存量代码维护）

### 2.1 基本用法

```ts
import { router } from '@kit.ArkUI';

// 1. 跳转（URL 需在 main_pages.json 中注册）
router.pushUrl({
  url: 'pages/Detail',
  params: { id: 123, name: 'Tom' }      // 参数
}).then((res) => {
  // push 成功
}).catch((err: BusinessError) => {
  console.error(`路由失败: ${err.code}`);
});

// 2. 返回
router.back();

// 3. 返回指定页面（栈中有多层时）
router.back({ url: 'pages/Index' });

// 4. 清栈返回首页
router.clear();

// 5. 替换当前页面
router.replaceUrl({ url: 'pages/Login' });

// 6. 获取栈长度
const len: number = router.getLength();

// 7. 获取路由栈
router.getState();
```

### 2.2 参数接收

```ts
@Entry
@Component
struct Detail {
  @State id: number = 0;
  @State name: string = '';

  aboutToAppear(): void {
    const params = router.getParams() as DetailParams;   // 类型断言
    if (params) {
      this.id = params.id;
      this.name = params.name;
    }
  }

  build() {
    Column() {
      Text(`ID: ${this.id}, Name: ${this.name}`)
    }
  }
}

interface DetailParams {
  id: number;
  name: string;
}
```

### 2.3 路由拦截（router 的痛点）

```ts
// 在 Ability 中统一拦截
import { router } from '@kit.ArkUI';

export function registerRouterInterceptor(): void {
  router.pushUrl = async (options: router.RouterOptions): Promise<void> => {
    // 自定义判断逻辑
    if (needLogin(options.url)) {
      router.pushUrl({ url: 'pages/Login' });
      return;
    }
    // 调用原始实现
    ...
  };
}
```

> 这种 monkey-patch 方式很脆弱，是 router 方案被诟病的地方，也是 Navigation 方案的重要卖点之一。

---

## 三、Navigation 组件（重点）

### 3.1 基本结构

```
Navigation（导航容器）
   ├── NavDestination（内容区）
   │      └── 你的页面内容
   └── title / navBar（导航栏）
```

```ts
@Entry
@Component
struct Index {
  // 路径栈：核心！
  private pageStack: NavPathStack = new NavPathStack();

  build() {
    Navigation(this.pageStack) {
      // ★ 这里是 NavDestination 的构建区
      // 内容由 NavDestination 决定，初始显示第一个
    }
    .title('首页')              // 内置标题栏
    .navDestination(this.PageMap)
    .mode(NavigationMode.Stack)  // Stack(栈) / Auto / Split(分栏)
    .hideNavBar(false)
  }

  // ★ NavDestination 路由表
  @Builder
  PageMap(name: string, param: Object) {
    if (name === 'Detail') {
      DetailPage({ itemId: param as number })
    } else if (name === 'Settings') {
      SettingsPage()
    }
  }
}
```

### 3.2 跳转与返回

```ts
// 1. 按名字跳转（需要 navDestination 匹配）
this.pageStack.pushPathByName('Detail', 123);

// 2. 直接构建跳转（推荐，类型安全）
this.pageStack.pushDestination(new DetailPage({ itemId: 123 }));

// 3. 返回
this.pageStack.pop();
this.pageStack.popToName('Home');
this.pageStack.popToIndex(0);
this.pageStack.clear();

// 4. 替换
this.pageStack.replacePathByName('Detail', 123);
```

**返回结果处理**：
```ts
// 跳转时注册回调，接收 pop 时的结果
this.pageStack.pushPathByName('Detail', 123, (result: PopInfo) => {
  if (result.result) {
    console.info(`返回结果: ${JSON.stringify(result.result)}`);
  }
});

// 或者在 Detail 中
this.pageStack.pop({ result: { ok: true, data: 'xxx' } });
```

> **注意**：`NavPathStack` 的 `pop()` 可以带参数（`NavigationOptions`），返回结果需要目标页在 `pop` 时通过 `result` 字段回传。

### 3.3 NavDestination 页面写法

```ts
@Component
struct DetailPage {
  itemId: number = 0;

  // NavDestination 的生命周期回调（比普通组件丰富）
  onWillShow(): void {
    console.info('DetailPage will show');
  }
  onShown(): void {
    console.info('DetailPage shown');
  }
  onWillHide(): void {
    console.info('DetailPage will hide');
  }
  onHidden(): void {
    console.info('DetailPage hidden');
  }
  // 返回拦截
  onBackPressed(): boolean {
    console.info('返回被拦截');
    return true;   // ★ 返回 true 阻止返回
  }

  build() {
    Column() {
      Text(`详情 ${this.itemId}`)
      Button('返回').onClick(() => { this.getUIContext().getRouter().pop(); })
    }
  }
}
```

**NavDestination 的 4 个生命周期回调**：

| 回调 | 时机 | 典型用途 |
|---|---|---|
| `onWillShow` | 显示前（可拦截） | 数据准备、权限检查 |
| `onShown` | 已显示 | 开启动画、埋点曝光 |
| `onWillHide` | 隐藏前 | 保存数据 |
| `onHidden` | 已隐藏 | 停止动画、释放资源 |

### 3.4 Navigation 常用属性

```ts
Navigation(this.pageStack) {
  // content
}
.title('标题')                        // 标题
.titleMode(NavigationTitleMode.Mini)  // Full / Mini / Free
.hideTitleBar(false)
.hideToolBar(false)
.toolbar(this.ToolBarBuilder)
.mode(NavigationMode.Stack)           // Stack / Split / Auto
.navBarWidth('100%')
.navBarPosition(NavBarPosition.End)   // Start / End
.splitViewMode(SplitViewMode.Static)  // 分栏时
.navDestination(this.PageMap)         // ★ 路由表
.onNavigationModeChange(...)
```

### 3.5 完整实战示例

```ts
interface DetailParam {
  id: number;
  from: string;
}

@Entry
@Component
struct Index {
  @Local list: string[] = ['A', 'B', 'C'];
  private pageStack: NavPathStack = new NavPathStack();

  build() {
    Navigation(this.pageStack) {
      Column() {
        ForEach(this.list, (item: string) => {
          ListItem() {
            Text(item)
              .onClick(() => {
                this.pageStack.pushPathByName('Detail', { id: 1, from: 'home' });
              })
          }
        }, (item: string) => item)
      }
    }
    .title('首页')
    .mode(NavigationMode.Stack)
    .navDestination(this.PageMap)
  }

  @Builder
  PageMap(name: string, param: Object) {
    if (name === 'Detail') {
      DetailPage({ param: param as DetailParam })
    }
  }
}

@Component
struct DetailPage {
  param: DetailParam = { id: 0, from: '' };

  build() {
    Column() {
      Text(`ID=${this.param.id}, From=${this.param.from}`)
      Button('返回首页').onClick(() => {
        this.getUIContext().getRouter().pop();
      })
    }
  }
}
```

### 3.6 NavPathStack 常用 API

```ts
// 路径信息
pageStack.size              // 当前栈深度
pageStack.getAllPathName()  // 所有路径名数组
pageStack.getPathByIndex(i) // 某层的路径信息
pageStack.getPathName()     // 当前路径名
pageStack.getParamByIndex(i)// 某层参数
pageStack.getIndexByName(name)

// 状态
pageStack.getState()        // 返回 NavigationPathStack 状态
pageStack.isEmpty()

// 操作
pageStack.pushPathByName(name, param, animated?)
pageStack.pushPath(info, animated?)
pageStack.pushDestination(destination, animated?)
pageStack.replacePathByName(...)
pageStack.replacePath(...)
pageStack.pop(result?)
pageStack.popToName(name)
pageStack.popToIndex(index)
pageStack.clear()
pageStack.moveToTop(name)
```

---

## 四、转场动画

### 4.1 NavigationTransition（页面间转场）

```ts
Navigation(this.pageStack) {
  // content
}
.navDestination(this.PageMap)
.transitionEffect({
  // 进入效果
  inEffect: {
    type: RouteEffectType.Fade,
    duration: 300,
    curve: Curve.EaseInOut
  },
  // 退出效果
  outEffect: {
    type: RouteEffectType.Fade,
    duration: 200
  },
  // 底层页面效果
  hiddenEffect: {
    type: RouteEffectType.Fade,
    duration: 200
  }
})
```

**RouteEffectType 常用值**：
- `Fade` —— 淡入淡出（默认）
- `Slide` —— 滑动
- `Push` —— 推入（类似 iOS）
- `Pop` —— 弹出
- `Transition` —— 自定义

### 4.2 共享元素转场（sharedTransition）

```ts
// 列表页
ListItem() {
  Image($r('app.media.pic'))
    .sharedTransition('pic_' + item.id)    // ★ 相同 key 的组件之间做共享元素动画
}

// 详情页
Image($r('app.media.pic'))
  .sharedTransition('pic_' + this.id)
```

> 共享元素转场是 HarmonyOS 6 的亮点之一：列表滑到详情页时图片位置平滑移动，视觉上"无缝"。这需要两侧使用**相同的 sharedTransition key**，且**必须在同一个 Navigation 内**。

### 4.3 单组件 transition

```ts
if (this.showDetail) {
  Text('详情')
    .transition(TransitionEffect.OPACITY.combine(TransitionEffect.scale({ x: 0.9 })))
}
```

---

## 五、侧滑返回手势接管

HarmonyOS 6 支持接管系统侧滑返回手势，实现自定义返回动画：

```ts
Navigation(this.pageStack) {
  // content
}
.navDestination(this.PageMap)
.onNavigationModeChange(...)
// 系统侧滑返回手势处理
.gesture(Gesture.SwipeGesture? )   // 通常配合 gesturePriority
```

实际项目里更常用的做法是配合 `pageStack` 的拦截：

```ts
// 拦截返回，做二次确认
pageStack.pop((result: PopInfo) => {
  // 处理返回结果
});
```

**`onBackPressed` 拦截返回**：
```ts
@Component
struct DetailPage {
  onBackPressed(): boolean {
    AlertDialog.show({
      title: '确认退出',
      message: '确定要离开吗？',
      primaryButton: { value: '取消', action: () => {} },
      secondaryButton: { value: '确定', action: () => { this.getUIContext().getRouter().pop(); } }
    });
    return true;   // ★ 阻止默认返回
  }
}
```

---

## 六、多端适配：分栏模式（Split Mode）

HarmonyOS 6 强调平板/折叠屏适配，`Navigation` 支持分栏：

```ts
Navigation(this.pageStack) {
  // 主页面
}
.mode(NavigationMode.Auto)      // ★ Auto：窗口宽度足够时自动分栏
.navDestination(this.PageMap)
.navBarWidth(360)               // 侧边栏宽度
.splitViewMode(SplitViewMode.Static)   // Static 固定 / Dynamic 动态
```

**配合 `GridRow` + `breakpoints` 做更细粒度的响应式**（见教程 03）。

---

## 七、页面级 API：`getUIContext()`

HarmonyOS 6 推荐通过 `UIContext` 获取路由/弹窗能力，而不是直接用全局 `router`：

```ts
// 在组件中
this.getUIContext().getRouter().pushUrl({ url: 'pages/Detail' });

// 弹窗
this.getUIContext().showAlertDialog({ title: '提示', message: '内容' });
this.getUIContext().showActionSheet({ title: '选择', buttons: [...] });

// 提示
this.getUIContext().getPromptAction().showToast({ message: '成功' });

// 上下文
this.getUIContext().getHostContext() as common.UIAbilityContext;
```

> **面试加分点**：说明为什么要用 `UIContext` 而不是全局单例 `router`——因为 `UIContext` 绑定了组件所在的上下文（窗口/Ability），能正确处理多窗口、多实例场景，也便于测试和 mock。这是 HarmonyOS 6 的 API 演进方向。

---

## 八、路由常见坑

| 坑 | 现象 | 原因 | 解决 |
|---|---|---|---|
| router 跳转白屏 | 页面没注册 | `main_pages.json` 漏了 | 添加页面路径 |
| `pushPathByName` 无效 | 名字不匹配 | `navDestination` 的 Builder 没匹配该 name | 检查 name 一致 |
| 参数拿不到 | 类型断言错 | `router.getParams()` 返回 `Object` | 断言成自己的 interface |
| 状态被清空 | 重建了组件 | 跳转方式不当 | 用 `@LocalStorageLink` / AppStorage 保存状态 |
| 路由栈混乱 | 混用 router 和 Navigation | 两套体系并存 | 统一用一套 |
| 返回后数据丢失 | 依赖页面状态 | 状态在页面里 | 状态提到 AppStorage 或 VM |

---

## 九、本章自测题

1. `router` 和 `Navigation` 的核心差异有哪些？各自适用什么场景？
2. `NavPathStack` 的作用是什么？它和 `Navigation` 是什么关系？
3. `NavDestination` 有哪几个生命周期回调？分别用来做什么？
4. 如何实现"跳转到一个页面并接收返回结果"？
5. 共享元素转场（sharedTransition）怎么实现？有什么前提条件？
6. 如何拦截系统的返回手势/返回键？
7. `getUIContext()` 相比直接用全局 `router` 有什么优势？
8. `NavigationMode.Auto` 解决什么场景？
9. `router.pushUrl` 和 `pageStack.pushPathByName` 的参数传递方式有什么不同？

---

## 十、参考答案要点

1. router 用字符串路径、弱类型参数、转场单一、无完整拦截；Navigation 用 Builder 强类型、参数泛型约束、转场丰富、拦截齐全（onWillShow/onBackPressed）、内置导航栏。router 兼容存量，Navigation 为新项目首选。
2. NavPathStack 是导航路径栈，管理所有 NavDestination 的层级、参数与转场状态；Navigation 接收一个 NavPathStack 实例作为其路由状态容器。
3. onWillShow（显示前，可拦截）、onShown（已显示，开启动画/埋点）、onWillHide（隐藏前，保存数据）、onHidden（已隐藏，释放资源）。
4. 跳转时 `pushPathByName(name, param, callback)` 注册回调，或用 `pushPathByName` + 在目标页 `pop({result: ...})` 回传；PopInfo.result 拿到返回值。
5. 列表项与详情页使用相同的 sharedTransition key（字符串），且处于同一个 Navigation 容器内，系统会在页面切换时对相同 key 的组件做位置/尺寸插值动画。
6. 在 NavDestination 中实现 `onBackPressed()` 返回 true 阻止默认返回并自行处理；也可接管侧滑手势。
7. UIContext 绑定组件所在的窗口/Ability 上下文，支持多窗口与多实例场景，避免全局单例的隐式耦合，便于测试与 mock。
8. 平板/折叠屏展开时宽度足够，自动切换为左右分栏（主页面 + 内容区），提升大屏体验。
9. router.pushUrl 的 params 是无类型约束的 Object，接收端需手动断言；pushPathByName 的 param 可配合泛型约束（如 `NavPathStack<DetailParam>`）做到编译期类型安全。

---

**上一章**：[教程 04：状态管理 V1 / V2](04-状态管理V1与V2.md)
**下一章**：[教程 06：应用模型与 UIAbility 生命周期](06-应用模型与UIAbility.md)
