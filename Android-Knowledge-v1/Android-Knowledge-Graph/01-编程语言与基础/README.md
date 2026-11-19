---
tags: [android, part]
part: 第1部分
status: 待整理
difficulty: 初级
created: 2026-11-19
updated: 2026-11-19
related: [[../索引]]
---

# 第 1 部分：编程语言与基础

## 概览

本部分是整个知识图谱的**语言地基**。它不解决"怎么写业务"，而是回答"**语言本身是怎么工作的**"——只有理解了协程的挂起机制、扩展函数的编译产物、泛型的类型擦除、`HashMap` 的扩容过程、JVM 的内存布局与 JMM 规则，才能在面试中回答出"**为什么这样写**"和"**换一种写法会带来什么代价**"这三个层级的问题。

本部分包含三条主线：

- **Kotlin 线**：[[Kotlin/协程与Flow]]（编译期状态机 + 结构化并发 + 流式数据）、[[Kotlin/高阶函数与扩展]]（零成本抽象的实现原理）、[[Kotlin/DSL]]（用领域语言表达配置的三大基石）。这三条线互相咬合——Flow 的操作符链本身就是高阶函数，而 DSL 的带接收者 lambda 又是高阶函数的进阶形态。
- **Java 线**：[[Java/泛型与反射]]（编译期类型安全与运行时内省的取舍）、[[Java/集合框架]]（数据结构的性能边界）、[[Java/JVM内存模型]]（运行时数据区 + JMM + GC）。这三条线共同决定了"**一段代码在内存里长什么样、多线程下是否安全、什么时候会被 GC 打断**"。
- **互调线**：[[跨语言互调]]。Kotlin 与 Java 的双向调用、Kotlin 特性如何桥接给 Java、JNI/NDK 的原理与性能代价。

**面试权重**：本部分是**面试官的"基本功审查区"**。初级岗会问"协程和线程有什么区别""`ArrayList` 和 `LinkedList` 怎么选"；中级岗会追问"协程的挂起是怎么实现的""`HashMap` 什么时候树化""`volatile` 为什么保证可见性但不保证原子性"；高级岗则会问"JVM 内存模型与 Android 掉帧的关系""Kotlin DSL 的作用域隔离原理""JNI 的引用管理与崩溃排查"。**这些问题的答案不在背诵里，而在"机制 + 代价 + 场景"三段式回答中。**

> [!tip] 面试主线
> 中级 Android 岗在本部分的高频考点：
> 1. **协程的挂起/恢复机制**：CPS 转换 + `Continuation` + 编译期状态机（为什么协程不阻塞线程）
> 2. **结构化并发与取消的协作性**：父子 Job 的三条规则、`CancellationException` 为什么必须重抛
> 3. **Dispatchers 与线程池复用**：`flowOn` 只影响上游、`IO` 与 `Default` 共享线程池
> 4. **Flow 的冷/热流**：`collect` 两次会发生什么、`StateFlow` / `SharedFlow` / `Channel` 的选型
> 5. **`inline` / `noinline` / `crossinline` / `reified`**：为什么需要它们，各自关闭了什么能力
> 6. **扩展函数的静态派发**：编译产物是什么、为什么成员函数永远优先
> 7. **作用域函数选型**：`let` / `apply` / `run` / `with` / `also` 的返回值与 `this`/`it` 差异
> 8. **类型擦除与 PECS**：`? extends` 为什么不能写、`? super` 为什么不能读
> 9. **APT / kapt / KSP** 的区别与选型，运行时反射的性能代价
> 10. **`HashMap` 的扩容与树化**：为什么容量是 2 的幂、链表长度 8 + 容量 64 的双重条件
> 11. **`ConcurrentHashMap` 的线程安全**：CAS + 桶级锁，与 `synchronizedMap` 的锁粒度差异
> 12. **JVM 内存区域与 JMM**：`volatile` 的内存屏障、happens-before 规则、GC 停顿与掉帧

## 知识图谱（Mermaid）

```mermaid
mindmap
  root（（编程语言与基础））
    Kotlin
      协程与Flow
        协程三要素
          Continuation 现场与恢复
          CoroutineContext 上下文
          suspend 挂起函数
        状态机原理
          CPS 转换
          label 状态标签
          COROUTINE_SUSPENDED
        结构化并发
          作用域 Scope
          父子 Job 层级
          取消的协作性
          SupervisorJob
        Dispatchers
          Main Main.immediate
          IO 阻塞 IO
          Default CPU 密集
          线程池复用
        Flow
          冷流 热流
          collect 收集语义
          操作符 map filter flowOn
          catch flatMapConcat
          背压 buffer conflate
          SharedFlow StateFlow Channel
        高频API
          runBlocking withContext
          launch async await
          CoroutineExceptionHandler
          suspendCancellableCoroutine
      高阶函数与扩展
        函数类型 FunctionN
        inline 内联
          非局部返回
          noinline 当值传递
          crossinline 禁止跳出
          reified 类型具体化
        扩展函数
          静态派发 static 方法
          扩展属性
          成员扩展函数
          成员函数优先级
        作用域函数
          let apply also
          run with
          takeIf takeUnless
        Lambda 与SAM
          函数引用 ::
          SAM 转换
        操作符重载
          invoke get set
          compareTo contains
        委托
          by lazy 延迟初始化
          接口委托
          Delegates observable
        Sequence 惰性求值
      DSL
        内部 DSL 外部 DSL
        接收者 lambda
        with apply 嵌套
        label 标签
        DslMarker 作用域隔离
        type-safe accessors
        View DSL
        Compose-like DSL
        Gradle Kotlin DSL
    Java
      泛型与反射
        类型擦除
          擦除规则
          checkcast
          桥接方法
        通配符 PECS
          extends 只读
          super 只写
        泛型类与方法
        类型参数约束
        反射 API
          Class Constructor
          Method Field
          setAccessible
          性能开销
        注解
          Retention 策略
          Target 位置
          APT kapt KSP
      集合框架
        接口体系
          Collection List Set Queue
          Map
        List 实现
          ArrayList 扩容 1.5 倍
          LinkedList 链表
          CopyOnWriteArrayList 写时复制
        Queue 实现
          ArrayDeque 环形数组
          PriorityQueue 二叉堆
        Map 实现
          HashMap 数组 链表 红黑树
            hash 扰动
            2 的幂取模
            扩容 2 倍
            树化阈值 8
          LinkedHashMap LRU
          TreeMap 红黑树有序
          ConcurrentHashMap
            CAS 桶级 synchronized
            分段锁 对比
            CounterCell 计数
        Android 集合
          SparseArray 免装箱
          ArrayMap 并行数组
    JVM内存模型
      运行时数据区
        程序计数器
        虚拟机栈 栈帧
        本地方法栈
        堆
        方法区 元空间
      对象布局
        Mark Word
        Klass Pointer
        实例数据
        对齐填充
        压缩指针
      分代收集
        Eden S0 S1
        老年代 晋升
        Minor Major Full GC
      GC 算法
        标记-清除
        标记-整理
        复制算法
      收集器
        CMS 并发标记
        G1 Region 可预测
        ZGC 染色指针 亚毫秒
        ART 并发增量
      JMM
        原子性 可见性 有序性
        happens-before 八条规则
        volatile 内存屏障
        synchronized 锁升级
        ReentrantLock
      掉帧与 GC
    跨语言互调
      Kotlin 与 Java
        平台类型 String!
        默认参数
        命名参数
      桥接注解
        JvmStatic
        JvmField
        JvmOverloads
        JvmName
        Throws
      value class
        JvmInline 内联展开
        装箱限制
      NDK 与 JNI
        JavaVM JNIEnv
        静态注册 动态注册
        局部引用表 溢出
        全局引用 泄漏
        性能问题与优化
```

## 子目录导航

| 子目录 | 说明 | 主要笔记 |
| --- | --- | --- |
| [[./Kotlin/协程与Flow\|Kotlin]] | Kotlin 的并发原语与响应式数据流 | [[./Kotlin/协程与Flow]]、[[./Kotlin/高阶函数与扩展]]、[[./Kotlin/DSL]] |
| [[./Java/泛型与反射\|Java]] | Java 的类型系统与运行时内省 | [[./Java/泛型与反射]]、[[./Java/集合框架]]、[[./Java/JVM内存模型]] |
| [[./跨语言互调\|跨语言互调]] | Kotlin / Java / C++ 之间的桥接 | [[./跨语言互调]] |

## 笔记列表

### Kotlin

- [[Kotlin/协程与Flow]]
- [[Kotlin/高阶函数与扩展]]
- [[Kotlin/DSL]]

### Java

- [[Java/泛型与反射]]
- [[Java/集合框架]]
- [[Java/JVM内存模型]]

### 跨语言互调

- [[跨语言互调]]

## 关联部分

- 前置：无（本部分是最底层的基础知识，建议从 [[./README]] 开始）
- 延伸：[[../02-Android核心组件/README]]（语言基础是理解组件生命周期与线程模型的前提）
- 延伸：[[../03-UI开发与用户交互/README]]（Compose DSL 与 Android 自定义 View 的绘制机制）
- 延伸：[[../05-网络与并发/异步方案/Kotlin协程]]（协程与线程模型在网络层与并发编程中的落地）
- 延伸：[[../06-系统原理与性能优化/README]]（JVM 内存模型与 GC 停顿直接决定卡顿与掉帧）
- 延伸：[[../07-架构、工具与生态/README]]（MVVM + StateFlow 的组合是协程最重要的应用场景；Gradle Kotlin DSL 是本部分 DSL 知识的最强工业实践）
