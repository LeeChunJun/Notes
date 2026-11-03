# ArkTS 语言概述

## ArkTS 语言的特性

* 静态类型的强制使用
* 禁止在运行时改变对象布局
* 对操作符语义的限制
* 对 UI 框架能力的扩展

---

# 数据及其操作

## 变量与常量

> `let` 变量
> `const` 常量

## 数据类型

### 基本数据类型和包装器类型

* number 和 Number：注意NaN，即非数值，表示本来要返回数值的操作，但未返回数值的情况。判断可使用 `Number.isNaN())`
* boolean 和 Boolean：true 和 false 表示值，严格区分大小写。
* string 和 String：单引号、双引号均可表示字符串，注意转义字符。
* ${表达式}：模板字符串
* slice 和 substring 区别：slice 方法的参数为负数时，从尾部开始计算。substring 方法会从较小的参数指定开始提取到较大的参数。
* concat 拼接字符串，split 将字符串按照分隔符分割成数组。

### 联合类型

> `|` 表示变量具有两种以上数据类型

### 数组

> `[]` 或者 `Array<T>`表示；
> `JSON.stringify()` 用于将各种类型的值转换成 JSON 字符串；
> `for (let|const 标志符 of 可迭代对象) { // 代码块}`便利数组
> `数组名.splice(索引, 删除的元素数量, 添加的元素1, 添加的元素2, ....)` 第二个元素为0 时，表示不删除，只添加；只有第一个元素时，表示一直删除后面所有的
> `join`将数组中所有元素连接成一个字符串，默认为逗号连接；和`split`操作相反
> `concat` 将两个数组连接起来，并返回一个新数组

### 枚举类型

> 数值枚举，值自动递增，可设初始值，不设定初始值时，默认为0；值还可以是其它枚举值计算得到。
> 字符串枚举，每个成员必须用字符串字面量或另一个字符串枚举值进行初始化

### typeof 操作符

> `typeof 操作数` 用于检测给定变量、常量或字面量的数据类型，返回字符串值
> `typeof` 操作符测试 `null` 值时，返回的类型为 `object`。`object` 类型表示所有非原始类型，非原始类型是除 `number`、`string`、`boolean`、`null` 和 `undefined`之外的任何类型。可使用比较运算符进一步确定操作数是否为 `null` 值。
> 对于函数，`typeof` 操作符返回的类型为 `function`

### instanceof 操作数

> 操作数是一个对象，进一步判断对象是否为某一个具体类型。`numbers instanceof Array`

### 类型别名

> `type 类型别名 = 类型名` 类型名 可以为基本类型、联合类型或者任何其他类型。

## 常用操作符

### 算数操作符

> 乘方操作符 `**`

### 比较操作符

> `===`全等于 比较时不会进行类型转换，避免隐式转换带来的意外。
> `!==`不全等于 比较时不会进行类型转换，避免隐式转换带来的意外。
> 数字字符 0-9 Unicode值为 48~57；大写英文字符 A~Z Unicode值为 65~90；小写英文字符 a~z Unicode值为 97~122
> 字符串比较遵循逐字符比较的规则

### 逻辑操作符

> 逻辑`与`运算中，两个操作数有一个不是布尔值时：
> 若左操作数是`假值`，则返回左操作数的值；
> 若左操作数是`真值`，则返回右操作数的值
>
> 逻辑`或`运算中，两个操作数有一个不是布尔值时：
> 若左操作数是`真值`，则返回左操作数的值；
> 若左操作数是`假值`，则返回右操作数的值

### 复合赋值操作符

## 常用数学函数

---

# 流程控制语句

---

# 函数

## 函数的定义和调用

> ArkTS 的函数只能定义在 ets 文件的顶层，并且不允许函数的嵌套定义。

## 函数参数传递

> `参数名?: 参数类型` 可选参数。`param?: T` param 的类型为联合类型 `T | undefined`
> 默认参数在非默认参数前面，并且需要使用默认参数的值时，可以显示调用 `undefined`。例如：`test2(undefined, '4')`
> 函数参数列表最后一个参数为 `剩余参数`，剩余参数可以接受任意数量的实参。例如：`...参数名: 数组类型`
> 值类型参数，参数传递的是值；引用类型参数，参数传递的是实例的引用。

## 箭头函数（lambda 表达式）

* 作为变量值
* 作为函数参数
* 作为函数返回值：用于创建可配置函数，或者在需要延迟函数执行的场合。预先设置函数行为，稍后再确定其参数，常用在事件处理器或回调中。

## 闭包（closure）

> 简单理解为`一个函数及其引用的外部变量的组合`。
> 如果一个箭头函数访问了外部作用域（通常是一个函数）中定义的变量，则称该箭头函数捕获了该变量，箭头函数以及被捕获的变量构成了`闭包`。
> `闭包`允许一个函数访问该函数定义时所在作用域的变量，即使在该作用域外部执行该函数时，也能够做到这一点。闭包“记住”了它被创建时的环境。
> `闭包的核心特性`：当箭头函数被返回并在外部函数的作用域外部被调用时，即便外部函数已经执行结束，箭头函数仍然能够访问外部函数的变量。
> 使用闭包，可以创建具有`私有状态的函数`，在模块化编程和设计模式实现中经常使用；闭包也可供我们`封装和保护变量`，防止函数被外部作用域直接访问或者修改，同时提供了操作变量的方法。

```ts
.onClick((event: ClickEvent) => {
 let tc = testClosure();

 tc();
 tc();
 tc();
})

function testClosure() {
    let x = 1;
    return () => {
        x++;
        console.log(x.toString());
    }
}
```

> 闭包会为`每次函数调用创建独立的上下文环境`，无论闭包捕获的是值类型还是引用类型的变量

```ts
.onClick((event: ClickEvent) => {
 let tc1 = testClosure();
 let tc2 = testClosure();

 tc1();
 tc1();

 tc2();
 tc2();
})

function testClosure() {
    let x = 1;
    return () => {
        x++;
        console.log(x.toString());
    }
}
```

# 面向对象编程

## 类的字段

> `[readonly] 实例字段名 [: 数据类型] [= 初始值]` 实例字段
> `static [readonly] 静态字段 [: 数据类型] [= 初始值]` 静态字段

## 类的方法
```
实例方法名([参数列表]) [: 返回类型] {
    方法体
}

static 静态方法名([参数列表]) [: 返回类型] {
    方法体
}
```

## 对象字面量
```ts
let person: Person; // 使用对象字面量时候，一定要先知道对象的实际类型。即对象字面量需要在编译器可以推断出其类型的上下文中使用

person = {name: 'Mike', age: 23, email: '1772043675@qq.com'}
```

## 封装

> `set xxX(value: yyy)` 设置属性
> `get xxX()` 获取属性

## 继承

> `class 子类 extends 父类 {}`
> `super({参数列表})`

## 多态

## 抽象类

## 接口
```ts
// 将对象字面量作为接口类型的实例

interface Person {
    name: string;
    age: number;
    email?: string;// 可选
}

function printPersonInfo(person: Person) {
    //...
}

function test() {
    printPersonInfo({name: 'Alice', age: 18})
}

// 注意，把一个对象字面量作为接口类型的实例时，接口不能包含方法
```

# 空安全

## 空安全的概念
> `id: number | null = null` 初始值为 null
> `age?: number;` 初始值为 undefined

## 空安全的特性
> 可选链：`user.profile?.address?.city`
> 非空断言操作符：`person.id!.toString`
> 空值合并操作符：`person.age ?? 20`

# 错误处理

## try-catch-finally 语句
> `console.log(message: string, ...arguments: any[]): void` 控制台函数定义。
> 即使 `try 块`或者 `catch 块`中包含 `return 语句`, `finally 块` 仍然会执行。
> 如果 `finally 块` 包含 `return 语句`，那么该语句返回的值会覆盖 `try 块`或者 `catch 块` 中的 `return 语句` 所返回的值。

## 使用 throw 手动抛出错误
> throw 错误类的对象：`throw new RangeError('缓冲区大小必须在 1 到 1024')`
> 继承自 `Error`，创建自定义错误类对象

# 容器

## 数组
> `filter` 筛选元素，返回新数组。`numbers.filter((element, index, array) => {})` filter 的完整参数。
> `map` 转换元素，返回新数组。`numbers.map((element, index, array) => {})`
> `reduce` 将数组中的所有元素归纳为单个值。`numbers.reduce((accumulator, currentValue, index, array) => {}, initialValue)`
> `forEach` 遍历数组中的每一个元素并对每一个元素执行提供的回调函数。`numbers.forEach((element, index, array) => {})`
> `find` 查找数组中满足提供的回调函数条件的第一个元素，没有找到时返回 `undefined`。`numbers.find((element, index, array) => {})`
> `sort` 对数组的元素进行原地排序（直接修改原数组），并返回排序后的数组。接收一个比较函数 `compareFunction` 作为参数，小于0，升序；等于零不变；大于零降序

```ts
// 利用 reduce 来实现更复杂数据结构的转换

interface Product {
    name: string;
    quantity: number;
}

function test() {
    const items: Product[] = [
        { name: 'Apple', quantity: 2 },
        { name: 'Banana', quantity: 5 },
        { name: 'Orange', quantity: 3 },
    ];

    const itemMap = items.reduce((accumulator, item) => {
        accumulator[item.name] = item.quantity;
        return accumulator;
    }, new Map<string, number>());

    // 输出：['Apple': 2, 'Banana': 5, 'Orange': 3]
    console.log(JSON.stringify(itemMap));
}
```

## 元组
> let numbers: [number, ...string[]] = [1, 'one', 'two', 'three']; 允许存储固定数量的元素，且这些元素的类型不必相同

## Set
> 存储唯一值的无序集合。JSON.stringify 无法将一个 Set 对象转换成 JSON 字符串

```ts
const numSet = new Set([1, 2, 3, 4, 5]) // 使用数组来初始化

// 输出：[1, 2, 3, 4, 5]
console.log(JSON.stringify(Array.from(numSet))) // 通过 Array.from 先将 Set 对象转换为数组，再使用 JSON.stringify 转换为 JSON 字符串
```

## Map
> 存储键值对。JSON.stringify 无法将一个 Map 对象转换成 JSON 字符串

```ts
const fruitMap = new Map<string, number>([
    ['apple', 2],
    ['banana', 3],
    ['cherry', 4],
]) // 使用二维数组来初始化

// 输出：[['apple', 2], ['banana', 3], ['cherry', 4]]
console.log(JSON.stringify(Array.from(fruitMap)))

// 注意 forEach 的回调函数接收的两个参数，分别是当前 `值和键`
fruitMap.forEach((value, key) => {
    console.log(`${key}: ${value}`);
})
```

## Record
> 与 `Map` 类似，可以`存储键值对`，但是有以下限制：
- `Map` 可以存储任意类型的键值对，而 `Record` 的键只能时 `string` 或 `number` 类型，例如，`枚举`类型。
- `Map` 不可以作为对象字面量的类型，而 `Record` 可以
- `Map` 可以在运行时动态地添加、修改或删除键值对，而 `Record` 不可以，确定后不能修改。

## 其余容器类
> `线性`：
- ArrayList: 构造动态数组
- List: 构造单向列表
- LinkedList: 构造双向列表
- Queue: 构造单端队列
- Stack: 构造栈

> `非线性`
- HashMap: 基于哈希表的 Map
- HashSet: 基于哈希表的 Set
- TreeMap: 基于红黑树的 Map
- TreeSet: 基于红黑树的 Set
- LightWeightMap: 构造轻量级的 Map
- LightWeightSet: 构造轻量级的 Set

# 泛型

## 泛型函数
> 一个函数顶一个一个或多个类型形参

## 泛型类型
- 泛型类：一个类定义了一个或多个类型形参
- 泛型接口：一个接口定义了一个或多个类型形参

# 导出和导入

## 顶层声明的导出和导入

> 想要在一个 `ets` 文件中访问另一个 `ets` 文件的顶层声明，就必须要使用关键字 `export` 显示地导出这些顶层声明，然后在其它 `ets` 文件中使用 `import` 语句来导入它们。包含 `export` 语句或者 `import` 语句的 `ets` 文件，被视为一个模块。
> `import` 语句前面不能有任何非 `import` 语句。
> `import { 顶层声明1， 顶层声明2， 顶层声明3，... } from 模块的路径`
> `import { 顶层声明 as 新名称 } from 模块的路径` 使用重命名应对不同模块中相同名字的顶层声明
> `import * as 模块的新名称 from 模块的新路径` 使用 `模块的新名称.顶层声明` 的格式访问
> 每个模块都可以使用关键字 `export` 和 `default` 默认导出`一个`顶层声明。注意：导入时不能将其放入花括号中。
```ts
// 文件名 Test.ets
export default interface Identifiable {
    id: number;
}

// Test 中只有一个 `export default`, 因此不用花括号。
import Idable fomr '../Test' 

import 顶层声明的原名称或新名称 from 模块的路径 // 导入时不能将其放入花括号中
```

## 导入 SDK 的开放能力

> 导入 Kit 下单个模块的接口能力
`import { AbilityState } from '@kit.AbilityState'`

> 导入 Kit 下多个模块的接口能力
`import { AbilityState, UIExtensionAbility } from '@kit.AbilityState'`

> 导入 Kit 包含的所有模块的接口能力（会导入过多无用模块，导致编译后的 HAP 包过大，谨慎使用）
`import * as 所有模块的别名 from  '@kit.AbilityState'`

