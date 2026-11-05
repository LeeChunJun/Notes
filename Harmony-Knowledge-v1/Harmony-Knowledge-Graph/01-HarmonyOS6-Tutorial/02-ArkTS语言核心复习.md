# 教程 02：ArkTS 语言核心复习

> **目标**：把"入门时学过但忘了"的 ArkTS 知识重新串成一条线。重点是 ArkTS **相对 TypeScript 的差异**（这是面试最爱追问的），以及并发、类型系统这些实际开发天天用的部分。
>
> **预计阅读**：60 分钟（代码量较大，建议边读边敲）

---

## 一、ArkTS 是什么

ArkTS = **Ark Type Script**，是华为基于 TypeScript（TS）扩展的一门编程语言，构建在 TypeScript 语法基础上，增加了**静态类型检查**和**运行时约束**。

它是 HarmonyOS 的**首选开发语言**，专门为高性能应用（ArkUI）设计。

### 1.1 ArkTS vs TypeScript：核心差异（面试高频）

ArkTS **不是** TS 的超集，它在 TS 基础上有明确限制：

| 维度 | TypeScript | ArkTS | 原因 |
|---|---|---|---|
| 动态特性 | 支持 `any`、动态属性、鸭子类型 | **禁止 `any`/`unknown`**，需要用 `Object`/`Record` 或联合类型 + 显式判别 | 保证静态安全与 AOT 优化 |
| 类型判断 | `typeof` | 支持 `typeof`，但需配合**类型守卫**收窄 | 编译期类型收窄 |
| 对象字面量 | 可随意声明 | 必须**显式声明类型**（不能依赖上下文推断做接口实现） | 消除运行期反射 |
| 类型断言 | `as` | `as` 仍可用，但受限 | — |
| 运算符重载 | 不支持 | **不支持** | — |
| 索引签名 | 支持 | **不支持**（用 `Record<K,V>` 替代） | — |
| 命名空间 | 支持 | **不支持** | — |
| `Symbol` | 支持 | **不支持** | — |
| 动态 `delete` | 支持 | **不支持** | — |
| 运行时反射 | 支持 | **受限**（无 `Reflect`） | 提升性能 |

### 1.2 ArkTS 的关键约束（编译期报错，必须记住）

1. **不使用 `any` / `unknown`**
   ```ts
   // ❌ 编译报错
   let a: any = 1;
   let b: unknown = "x";

   // ✅ 用具体类型或联合类型
   let a: number | string = 1;
   let b: Object = {} as Object;

   // ✅ 未知结构的键值对用 Record
   let dict: Record<string, number> = { "a": 1 };
   ```

2. **对象字面量必须有明确类型**
   ```ts
   interface Person { name: string; age: number }

   // ✅ 声明变量时给类型
   const p: Person = { name: "a", age: 1 };

   // ❌ 没有类型上下文时不允许
   const p = { name: "a", age: 1 };   // 实际上 ArkTS 允许推断简单字面量，
                                      // 但在实现接口/传给泛型时需显式标注
   ```

3. **不使用命名空间、`delete` 运算符、`Symbol`、索引签名**

4. **类型断言受限**：`as` 只能用于有继承关系的类型或明确的对象类型

### 1.3 编译模式：方舟编译器（Ark Compiler）

ArkTS 采用 **AOT（Ahead-Of-Time）预编译 + JIT 混合** 模式：

```
ArkTS 源码
   │
   ├─→ [静态检查: 类型/语法/接口一致性]  ← ArkTS 编译器（IDE 内即时反馈）
   │
   ├─→ [解释执行 / JIT 编译]             ← Ark Runtime（调试期）
   │
   └─→ [AOT 字节码编译]                 ← 打包发布（性能最优，无运行时解析开销）
```

> **面试话术**："ArkTS 通过限制动态特性（禁 any、禁反射、禁动态属性），让方舟编译器能做静态检查和 AOT 优化，从而提升启动速度和运行性能。这是 ArkTS 和 TypeScript 最本质的区别——不是语法糖，而是**为编译优化让路的设计取舍**。"

---

## 二、类型系统

### 2.1 基础类型

```ts
// 数字、字符串、布尔
let num: number = 42;
let hex: number = 0xFF;
let bin: number = 0b1010;
let oct: number = 0o17;
let big: bigint = 123456789012345678901234567890n;  // 大整数
let str: string = "Hello";
let tpl: string = `模板串 ${num}`;
let bool: boolean = true;
let undef: undefined = undefined;
let nul: null = null;
```

**null 与 undefined**：ArkTS 中 `null` 和 `undefined` 都存在。严格模式下若未显式指定，`null`/`undefined` 不可赋值给普通类型，需联合类型 `string | null`。

### 2.2 数组与元组

```ts
// 数组：元素类型统一
let arr: number[] = [1, 2, 3];
let arr2: Array<string> = ["a", "b"];   // 等价

// 元组：位置固定、长度固定
let tup: [string, number] = ["hello", 1];
console.log(tup[0]);   // hello
let t0: string = tup[0];  // 类型精确
```

### 2.3 联合类型与类型守卫

```ts
type StringOrNumber = string | number;

function handle(v: StringOrNumber): string {
  // typeof 类型守卫：收窄类型
  if (typeof v === "string") {
    return v.toUpperCase();       // 此块内 v 是 string
  }
  return v.toFixed(2);            // 此块内 v 是 number
}
```

**ArkTS 要求类型守卫**，否则在联合类型上调用属性会报错。常用收窄手段：
- `typeof x === '...'`
- `instanceof Class`
- 属性判别（discriminated union）
- `x !== null && x !== undefined`

```ts
interface Cat { kind: "cat"; meow(): void }
interface Dog { kind: "dog"; bark(): void }
type Animal = Cat | Dog;

function speak(a: Animal): void {
  switch (a.kind) {           // 判别联合，收窄最优雅
    case "cat": a.meow(); break;
    case "dog": a.bark(); break;
  }
}
```

### 2.4 枚举

```ts
// 数字枚举
enum Direction { Up, Down, Left, Right }

// 字符串枚举（推荐，可读性好）
enum Status {
  Idle = "idle",
  Loading = "loading",
  Error = "error"
}

enum Flag {
  None = 0,
  Read = 1 << 0,     // 1
  Write = 1 << 1,    // 2
  All = Read | Write // 3
}
```

### 2.5 类型别名与接口

```ts
// 类型别名：支持联合、交叉、元组
type ID = string | number;
type Point = { x: number; y: number };
type Nullable<T> = T | null;

// 交叉类型
type User = { id: number } & { name: string };

// 接口：可扩展
interface Animal {
  name: string;
  age: number;
  speak(): void;          // 方法声明
  readonly id: string;    // 只读
  optional?: number;      // 可选
  [key: string]: unknown; // ❌ ArkTS 不支持索引签名！
}
```

> **ArkTS 不支持索引签名**。需要动态键的场景用：
> ```ts
> const dict: Record<string, string> = { a: "1", b: "2" };
> ```

### 2.6 泛型

```ts
function first<T>(arr: T[]): T | undefined {
  return arr.length > 0 ? arr[0] : undefined;
}

const s = first<string>(["a", "b"]);

// 约束
interface Named { name: string }
function printAll<T extends Named>(items: T[]): void {
  items.forEach(i => console.log(i.name));
}
```

**ArkTS 泛型特点**：泛型参数不做具体化运行时替换（为了 AOT），但类型检查完整。

### 2.7 类的进阶

```ts
class Animal {
  // 属性
  protected name: string = "animal";
  private age: number = 0;
  public static kind: string = "Animal";   // 静态属性

  // 构造函数
  constructor(name: string, age: number) {
    this.name = name;
    this.age = age;
  }

  // 方法
  speak(): void {
    console.log(`${this.name} speaks`);
  }

  // 属性访问器（getter/setter）—— ArkTS 支持，但不能用装饰器
  get description(): string { return `${this.name}(${this.age})`; }
  set description(value: string) { /* ... */ }

  // 静态方法
  static create(): Animal { return new Animal("x", 0); }
}

class Dog extends Animal {
  constructor(name: string) {
    super(name, 3);   // ★ 必须显式调用父类构造
  }
  speak(): void {     // ★ 重写
    console.log(`${this.name} barks`);
  }
}
```

**注意**：ArkTS **不支持** 属性装饰器（`@observable` 之类的属性级装饰器）——这是与 TS 的重要区别，也是理解状态管理 V2 的关键（见教程 04）。

### 2.8 接口与实现

```ts
interface Storage {
  save(key: string, value: string): void;
  read(key: string): string | undefined;
}

class LocalStorage implements Storage {
  private map: Map<string, string> = new Map();
  save(key: string, value: string): void { this.map.set(key, value); }
  read(key: string): string | undefined { return this.map.get(key); }
}
```

### 2.9 类型守卫函数（自定义）

```ts
interface BusinessError { code: number; message: string }
function isBusinessError(e: Object): e is BusinessError {
  return 'code' in e && 'message' in e;
}

try {
  // ...
} catch (e) {
  const err = e as BusinessError;
  if (err.code === 401) { /* 跳转登录 */ }
}
```

---

## 三、ArkTS 装饰器（重点：只用于状态管理）

### 3.1 装饰器是什么

装饰器是**附加在类定义上**的元编程语法，本质是一个函数，在类加载时调用。

**ArkTS 只保留了两类装饰器，且仅用于状态管理**：
1. **状态装饰器**：`@State`、`@Link`、`@Prop`、`@Provide`、`@Consume`、`@Observed`、`@ObjectLink`
2. **V2 状态装饰器**：`@ObservedV2`、`@Trace`、`@Local`、`@Param`、`@Event`、`@ProvideV2`、`@ConsumeV2`、`@Monitor`、`@Require`、`@Builder`、`@Computed`

### 3.2 状态装饰器速查（先看表，教程 04 深入）

| 装饰器 | 作用 | 能否改父组件传入的值 | 能否与父组件同步 |
|---|---|---|---|
| `@State` | 组件内部状态 | — | ❌ |
| `@Link` | 双向绑定父组件状态 | ✅ | ✅ |
| `@Prop` | 单向同步父组件状态 | ❌ | ✅（父改子跟着变） |
| `@Provide` / `@Consume` | 祖先↔后代跨层通信 | ✅ | ✅ |
| `@Observed` + `@ObjectLink` | 观察类对象内部属性变化 | ❌（整体替换） | ❌ |
| `@ObservedV2` + `@Trace` | V2 版深度观察 | ❌ | ❌ |
| `@Local` | V2 版组件内部状态 | — | ❌ |
| `@Param` | V2 版父入子（单向） | ❌ | ✅ |
| `@Event` | V2 版子传父（回调） | — | — |

> **Decorator 本质原理解释（面试加分）**：
> 装饰器本质是函数，在类定义执行时被调用，返回值（如果有）会替换原类。状态装饰器通过修改类的属性元数据（`__proxy` / observed track），让框架在属性 `set` 时插入"通知 UI 更新"的逻辑，从而实现响应式。

```ts
// 装饰器本质伪代码
function State(target: Object, key: string): void {
  // 注册该属性为响应式属性
}
```

---

## 四、并发：TaskPool 与 Worker（重点难点）

ArkTS 的**并发模型**与 JS 的单线程事件循环不同。鸿蒙提供三种并发方式：

| 机制 | 隔离级别 | 适用场景 |
|---|---|---|
| **TaskPool** | 线程池（有隔离） | 计算密集型，可并发多个任务 |
| **Worker** | 线程（独立 VM） | 单个重型任务，需要长期存活 |
| **Sendable** | 可跨线程传递的对象 | 在 TaskPool/Worker 间传递数据 |

### 4.1 Sendable：可跨线程传递的数据类型

普通对象不能跨线程传递（会复制或抛错）。需要标记为 `Sendable`：

```ts
// 方式 1：标记类
@Concurrent
class TaskParams {
  value: number = 0;
  constructor(v: number) { this.value = v; }
}

@Concurrent
function executeTask(params: TaskParams): number {
  return params.value * 2;
}
```

**Sendable 支持的类型**：
- 基础类型：`number`、`string`、`boolean`、`bigint`
- `Sendable` 类实例
- `ArrayBuffer`、`TypedArray`、`DataView`
- `Map`、`Set`、`Array`（**元素也必须是 Sendable**）
- `@Sendable` 装饰器标注的函数

### 4.2 TaskPool 使用

```ts
import { taskpool } from '@kit.ArkTS';

// 1. 在同一个 .ets 文件中定义并发函数（必须加 @Concurrent）
@Concurrent
function add(a: number, b: number): number {
  return a + b;
}

// 2. 使用
async function runTask(): Promise<void> {
  const task = new taskpool.Task(add, 3, 4);
  const result = await taskpool.execute(task, taskpool.Priority.HIGH);
  console.log(`结果: ${result}`);   // 7
}
```

**TaskPool 常用 API**：

```ts
// 串行队列
const queue = taskpool.createSerialTaskPool("myQueue", 4);
await queue.execute(task, taskpool.Priority.LOW);

// 延迟执行
taskpool.executeDelayed(task, 1000, taskpool.Priority.LOW);

// 取消
taskpool.cancel(task);

// 获取 task 对象信息
taskpool.getTaskInfo(taskId);

// 释放（一定要在 finally 中释放，避免内存泄漏）
taskpool.release(task);
```

> **面试要点**：TaskPool 任务执行完**必须 `release`**，否则任务对象不回收，会造成内存泄漏。TaskPool 的任务**不支持跨线程访问 UI 状态，也不支持修改 UI**——结果通过返回值传回 UI 线程再更新。

### 4.3 Worker 使用

```ts
// 在新文件中创建 worker
import { worker } from '@kit.ArkTS';

const workerInstance = new worker.ThreadWorker('pages/worker/Worker.ts');

const message: worker.ThreadMessage = {
  cmd: 'execute',
  args: { value: 100 }
};
workerInstance.postMessage(message);

// 接收结果
workerInstance.onmessage = (e: worker.MessageEvents): void => {
  console.log(`收到: ${e.data}`);
  workerInstance.terminate();   // ★ 用完必须 terminate
};
```

**Worker 的能力**：
- 可通过 `postMessage` 收发消息
- 支持 `onmessage` / `onerror` / `onmessageerror`
- 可以加载其他文件（`importScripts` 类似机制，用 import 语句）
- Worker 内**不能直接操作 UI**

### 4.4 TaskPool vs Worker 如何选（经典面试题）

| 维度 | TaskPool | Worker |
|---|---|---|
| 并发度 | ✅ 可同时跑多个任务 | ❌ 一个 Worker 一个线程 |
| 通信方式 | 返回值 / 函数参数 | postMessage 消息 |
| 适用 | 多个独立计算任务（图片处理、批处理） | 单个长耗时或需持续通信的任务 |
| 创建成本 | 低（复用线程池） | 高（新建线程 + VM） |
| 传输数据 | Sendable 参数 | 结构化克隆对象 |

**话术**："如果是一批彼此独立的计算任务，用 TaskPool 拿到真正的并行；如果是单个需要长期运行、频繁交互的重任务，用 Worker，因为它的消息机制更自然。"

---

## 五、集合与工具类

### 5.1 常用集合

```ts
// Map / Set
const m: Map<string, number> = new Map();
m.set('a', 1);
const v = m.get('a');

const s: Set<number> = new Set([1, 2, 3]);

// 遍历
m.forEach((value: number, key: string) => {
  console.log(`${key} = ${value}`);
});
for (const [k, val] of m.entries()) { /* ... */ }
```

### 5.2 工具类

```ts
import { util } from '@kit.ArkTS';

// 对象深拷贝（ArkTS 推荐方式）
const copy = util.JSON.parse(JSON.stringify(original));

// 字符串处理
util.TextUtils.isEmpty('');
util.TextUtils.getTimeFromDate();

// 类型判断
util.types.isUndefined(v);
util.types.isNull(v);
util.types.isObject(v);
util.types.isString(v);
util.types.isNumber(v);
```

### 5.3 系统 API 的三种导入方式（必考）

HarmonyOS 6 推荐使用 **Kit 聚合式导入**：

```ts
// ✅ 推荐（API 12+ 新 Kit 架构）
import { abilityAccessCtrl, common } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { preferences } from '@kit.ArkData';
import { http } from '@kit.NetworkKit';
import { BusinessError } from '@kit.BasicServicesKit';
```

**旧写法（仍可用，但新代码不用）**：
```ts
import abilityAccessCtrl from '@ohos.abilityAccessCtrl';
import window from '@ohos.window';
```

> **面试话术**："HarmonyOS 6 引入了 Kit 聚合导入，把分散在各 `@ohos.xxx` 模块下的 API 按能力域聚合，比如 AbilityKit 管应用框架、ArkUI 管 UI、ArkData 管数据。这样 import 更简洁，依赖关系也更清晰。"

**常用 Kit 对照表**：

| Kit | 覆盖能力 |
|---|---|
| `@kit.AbilityKit` | UIAbility、Want、权限、后台任务 |
| `@kit.ArkUI` | 窗口、UI 组件、路由 |
| `@kit.ArkData` | Preferences、关系型数据库、KV、分布式数据 |
| `@kit.NetworkKit` | HTTP、WebSocket、Socket、NetStack |
| `@kit.BasicServicesKit` | 公共基础库（util、Bundle、hiTrace） |
| `@kit.PerformanceAnalysisKit` | hilog、hiTrace、hiperf |
| `@kit.MediaLibraryKit` | 媒体、相机 |
| `@kit.LocationKit` | 位置服务 |
| `@kit.SensorServiceKit` | 传感器 |
| `@kit.PaymentKit` | 支付（Kit 化后） |
| `@kit.PushKit` | 推送 |
| `@kit.NotificationKit` | 通知 |
| `@kit.CameraKit` | 相机 |

---

## 六、异常处理与错误码

```ts
// try/catch/finally
try {
  JSON.parse(str);
} catch (error) {
  const err = error as BusinessError;
  console.error(`code=${err.code}, message=${err.message}`);
} finally {
  console.log("always runs");
}

// 抛出自定义错误
class MyError extends Error {
  constructor(code: number, message: string) {
    super(message);
    this.code = code;
  }
}
```

**HarmonyOS 错误码规则（面试常问）**：

```
格式： 5 位数字
  ┌──┬──┬──┬──┐
  │  │  │  └── 具体的错误序号
  │  │  └───── 服务/模块类型
  │  └──────── 保留位
  └─────────── 错误级别（0=系统通用，1=系统服务，2=第三方应用）
```

**常见错误码**：
- `16000001` — 获取 Token 失败
- `16000004` — 网络连接超时
- `23000001` — 数据库操作失败
- `33000001` — 参数错误
- `100600001` 等 — UIAbility 相关
- `16000104` — HTTP 请求失败

---

## 七、代码风格与常见错误（真实项目踩坑）

### 7.1 类型检查三处不一致（ArkTS 头号大坑）

任何时候，以下三者必须一致，否则 ArkTS 会报"接口实现不一致"：

1. **接口声明**（`interface`）
2. **类的 `implements` 实现**
3. **传给 ArkUI 组件的属性**（如 `ListItem` 的 `onClick` 签名）

```ts
interface Item {
  id: number;
  name: string;
}

@Component
struct ItemComp {
  @Prop item: Item = { id: 0, name: '' };   // ← 这里的对象字面量类型必须匹配

  build() {
    ListItem() {
      Text(this.item.name)
    }
    .onClick((event: ClickEvent) => { /* ... */ })   // ← 签名必须匹配
  }
}
```

### 7.2 `ForEach` 的 keyGenerator（性能与正确性）

```ts
ForEach(this.items, (item: Item) => {
  ListItem() { Text(item.name) }
}, (item: Item) => item.id)    // ★ keyGenerator 必须提供，且唯一
```

- 缺少 keyGenerator → 列表复用错乱。
- key 必须是**稳定唯一**的，不要用数组索引（增删会错位）。

### 7.3 常见反模式

```ts
// ❌ 在 build() 里做副作用（每次刷新都执行）
build() {
  console.log("build");          // 会高频调用，拖慢性能
  this.loadData();
}

// ❌ 在 @Builder 里写 if 之外的复杂逻辑
@Builder
function item() { ... }

// ❌ 用 @State 保存大对象（每次 set 都触发深比较，昂贵）
@State data: Array<any> = [];   // 改用 @Observed + @ObjectLink 或 @ObservedV2

// ✅ 用 @Link 双向绑定子组件
```

---

## 八、本章自测题

1. ArkTS 相比 TypeScript 最重要的 5 个限制是什么？
2. `@Concurrent` 装饰器的作用是什么？什么函数需要它？
3. TaskPool 和 Worker 分别在什么场景使用？TaskPool 的任务执行完必须做什么？
4. `import { window } from '@kit.ArkUI'` 与 `import window from '@ohos.window'` 有什么区别？
5. ArkTS 错误码 `23000001` 中各字段代表什么？
6. 为什么 ArkTS 禁止索引签名？替代方案是什么？
7. `ForEach` 的第三个参数为什么必须提供？

---

## 九、参考答案要点

1. 禁 `any`/`unknown`；禁命名空间；禁 `delete`/`Symbol`；禁索引签名；对象字面量需显式类型；不支持运算符重载；运行时反射受限。目的是让方舟编译器能做静态检查与 AOT 优化。
2. `@Concurrent` 标记的函数可被投递到 TaskPool 线程池执行（并发执行，避免阻塞 UI 线程）。TaskPool 入口函数、Sendable 类必须加。
3. TaskPool 用于多个独立计算任务（真并行、复用线程池）；Worker 用于单个长耗时或需消息通信的任务。TaskPool 用完必须 `taskpool.release(task)` 释放。
4. Kit 聚合导入是 API 12+ 的新方式，按能力域聚合（ArkUI = 窗口+UI+路由）；`@ohos.xxx` 是旧的单模块导入方式，功能等价但组织性差。
5. 第 1 位错误级别（2 = 应用级），第 2-3 位服务类型，第 4-5 位具体序号；`23000001` = 应用级 / ArkData(30) / 数据库操作失败(001)。
6. 索引签名依赖运行期反射，ArkTS 为 AOT 优化禁止；替代用 `Record<string, T>`。
7. ForEach 需要 keyGenerator 来标识 item 的唯一性，以支持 diff 与节点复用；缺失会导致列表更新异常，索引作 key 在增删时错位。

---

**上一章**：[教程 01：架构总览与工程体系](01-架构总览与工程体系.md)
**下一章**：[教程 03：声明式 UI 与组件体系](03-声明式UI与组件体系.md)
