---
tags: [android]
part: 第1部分
status: 待整理
difficulty: 高级
created: 2026-11-19
updated: 2026-11-19
related: "[[集合框架]], "[[泛型与反射]], [[../Kotlin/协程与Flow]], [[../README]]"
---

# JVM内存模型

## 一句话定义

**JVM 内存模型**包含两层含义：**运行时数据区**（内存怎么划分：堆、栈、方法区、程序计数器、本地方法栈——"数据存在哪"）与 **Java Memory Model（JMM）**（并发的可见性、有序性、原子性规则——"多线程下怎么保证正确"）。在 Android 上，Dalvik → ART 的演进让 GC 从"标记-清除、频繁卡顿"变成了"分代 + 并发"的现代形态，但**STW（Stop-The-World）暂停依然存在**，这正是 App 掉帧和 ANR 的重要成因。

## 核心原理

### 1. JVM 运行时数据区

```text
┌───────────────────────────────────────────────────────────────┐
│                     JVM 运行时数据区                            │
├─────────────────┬─────────────────────────────────────────────┤
│  线程私有       │  线程共享                                    │
├─────────────────┼─────────────────────────────────────────────┤
│                 │                                             │
│  程序计数器    │            堆（Heap）                        │
│  (PC Register)  │      ┌──────────────────────────┐            │
│                 │      │  新生代                  │            │
│  虚拟机栈       │      │  ├─ Eden (8)             │            │
│  (Stack)        │      │  ├─ Survivor S0 (2)      │            │
│  ┌──────────┐   │      │  └─ Survivor S1 (2)      │            │
│  │栈帧      │   │      │  老年代 (Old Gen)        │            │
│  │局部变量表 │   │      │  元空间 (Metaspace)      │            │
│  │操作数栈  │   │      └──────────────────────────┘            │
│  │方法引用  │   │                                             │
│  └──────────┘   │            方法区 / 元空间                    │
│                 │      类元数据、运行时常量池、静态变量           │
│  本地方法栈     │                                             │
│  (Native Stack) │                                             │
└─────────────────┴─────────────────────────────────────────────┘
```

**各区域详解**：

| 区域 | 线程私有 | 存储内容 | 异常 |
| --- | --- | --- | --- |
| **程序计数器（PC Register）** | ✅ | 当前线程执行的字节码**行号** | 无（唯一不会 OOM 的区域） |
| **虚拟机栈（Stack）** | ✅ | 每次方法调用创建的**栈帧** | `StackOverflowError` / `OutOfMemoryError` |
| **本地方法栈** | ✅ | native 方法的调用状态 | `OutOfMemoryError` |
| **堆（Heap）** | ❌ | **对象实例、数组**（几乎所有对象） | `OutOfMemoryError` |
| **方法区 / 元空间** | ❌ | **类元数据、运行时常量池、静态变量、即时编译后的代码** | `OutOfMemoryError`（Metaspace） |

**关键细节**：

1. **PC 寄存器为什么不会 OOM**：它是唯一一个**内存占用固定**（4 或 8 字节）的区域。执行 native 方法时它的值是 **undefined**。
2. **堆是唯一"几乎所有对象"所在的地方**——包括 Kotlin 的 `Continuation` 对象（协程挂起时的现场）。
3. **方法区在 Java 8 后被元空间（Metaspace）取代**：
   - **永久代（PermGen）**：在**堆**里的一块固定区域（`-XX:PermSize`），有固定上限，容易 OOM。
   - **元空间**：在**本地内存（Native Memory）**里，默认**无上限**（受限于物理内存），用 `-XX:MaxMetaspaceSize` 限制。
   - → 好处：不再因为"类太多"而堆 OOM；坏处：可能耗尽系统内存导致系统级卡死。

### 2. 栈帧（Stack Frame）结构

```text
┌──────────────────── 栈帧 (Stack Frame) ────────────────────┐
│  ① 局部变量表 (Local Variable Table)                        │
│     slot 0: this                                          │
│     slot 1: 参数 a                                         │
│     slot 2: 参数 b                                         │
│     slot 3: 局部变量 c                                     │
│     ┌──────┬──────┬──────┬──────┐                         │
│     │ ref  │ int  │ long │double│  ← 每个 slot 4/8 字节    │
│     └──────┴──────┴──────┴──────┘                         │
│     分类：8 种基本类型 + 1 种引用（ref）                     │
│     ① int/float/short/byte/char/boolean → 1 slot (4B)       │
│     ② long/double                    → 2 slots (8B)        │
│     ③ reference                       → 1 slot (4B)        │
├────────────────────────────────────────────────────────────┤
│  ② 操作数栈 (Operand Stack)  ← 字节码指令的运算区            │
│     压栈/出栈：iadd、imul、invokevirtual ...                │
├────────────────────────────────────────────────────────────┤
│  ③ 方法引用 (Method Reference)                             │
│     指向常量池中的符号引用（当前类/方法/字段）                │
├────────────────────────────────────────────────────────────┤
│  ④ 异常处理表（部分 JVM 实现）                              │
└────────────────────────────────────────────────────────────┘
```

**面试高频点**：

1. **局部变量表用"槽位（slot）"索引**：一个 slot 是 4 字节（32 位 JVM）或 8 字节（64 位 JVM）。
   - `long`/`double` 占 **2 个 slot**。
   - **基本类型在栈上，引用在栈上但对象在堆上** → 栈帧里只有引用（指针）。

2. **`++i` 和 `i++` 的字节码差别**（经典面试题）：

```text
i++  （先取值再加）：
  iload_1        // 把 i 压栈
  dup            // 复制一份
  iconst_1
  iadd           // i + 1
  istore_1       // 写回 i
  ← 栈顶保留的是【加之前的值】

++i  （先加再取值）：
  iinc 1, 1      // 直接对局部变量 1 加 1
  iload_1        // 取（已加过的）值
```

   → `i++` 用了 `dup`，栈操作多一步；`++i` 用 `iinc` 指令（专门为局部变量自增设计的）。

3. **方法返回时栈帧出栈**，局部变量表的 slot 被**复用**（所以 slot 可以被覆盖）。

### 3. 对象布局与对象头

```text
┌────────────────────────────────────────────────────┐
│  对象头 (Object Header)         12 / 16 字节        │
│  ┌──────────────────┬───────────────────────────┐  │
│  │ Mark Word        │ Klass Pointer (类型指针)  │  │
│  │ 32 / 64 位       │ 32 位 (压缩指针) / 64 位  │  │
│  │ 哈希码、GC年龄   │ 指向元空间中 Class 元数据 │  │
│  │ 锁状态标记、偏向  │                           │  │
│  └──────────────────┴───────────────────────────┘  │
├────────────────────────────────────────────────────┤
│  实例数据 (Instance Data)    ← 成员变量               │
│    int / long / ref ...                            │
│    排列规则：相同类型放一起，父类字段在前            │
├────────────────────────────────────────────────────┤
│  对齐填充 (Padding)          ← 补齐到 8 的倍数       │
└────────────────────────────────────────────────────┘
```

**64 位 JVM 的 Mark Word（8 字节）**：

| 状态 | 存储内容 | 哈希码 | GC 年龄 | 偏向 |
| --- | --- | --- | --- | --- |
| 无锁 | 对象哈希码 | 31 位 | 4 位 | 1 位 |
| 轻量级锁 | 指向锁记录（LR）的指针 | — | — | — |
| 重量级锁 | 指向 `ObjectMonitor` 的指针 | — | — | — |
| GC 标记 | 转发指针 | — | — | — |

**为什么需要 Klass Pointer**：让 JVM 在运行时知道这个对象是**哪个类的实例**，从而调用正确的方法（多态的基础）。

**压缩指针（Compressed Oops）**：
- 64 位 JVM 上，`Klass Pointer` 和对象引用可压缩为 **32 位**。
- 原理：对象地址 = `base + offset * 8`，`offset` 用 32 位存储 → 可寻址 `2^32 * 8 = 32GB`。
- 开启条件：堆大小 < 32GB（`-XX:+UseCompressedOops`，**默认开启**）。
- **对象超过 32GB 堆时会自动关闭压缩指针** → 内存占用显著上升。

**对象大小的计算示例**：

```text
class User {
    int id;          // 4 字节
    long timestamp;  // 8 字节
    String name;     // 4 字节（压缩指针）
    boolean active;  // 1 字节
}

对象大小：
  对象头       16 字节（64 位 JVM：8 Mark Word + 4 Klass + 4 填充）
  实例数据     4 + 8 + 4 + 1 = 17 字节
  填充         对齐到 8 的倍数
  ─────────────────────────────────
  总计         16 + 17 = 33 → 填充到 40 字节
```

### 4. 分代收集与 GC 类型

```text
┌─────────────────────────────────────────────────────┐
│  堆                                                    │
│  ┌──────────────────────┐  ┌──────────────────────┐  │
│  │  新生代 (Young Gen)    │  │  老年代 (Old Gen)      │  │
│  │  ┌────────────────┐   │  │                      │  │
│  │  │ Eden  (80%)    │   │  │  存活久的对象         │  │
│  │  ├────────────────┤   │  │  晋升而来             │  │
│  │  │ S0  (10%)      │   │  │  大对象直接进         │  │
│  │  ├────────────────┤   │  │                      │  │
│  │  │ S1  (10%)      │   │  │                      │  │
│  │  └────────────────┘   │  │                      │  │
│  └──────────────────────┘  └──────────────────────┘  │
│  元空间（Native Memory，不在堆里）                      │
└─────────────────────────────────────────────────────┘
```

**分代假设（Generational Hypothesis）**：

> "**大多数对象都会很快死亡**（朝生夕死），**只有少数对象会长期存活**。"

**对象的生命周期流程**：

```text
对象 new 出来
   ↓ 分配到 Eden
Eden 满了 → Minor GC（Young GC）
   ↓
存活对象通过【复制算法】复制到 Survivor S0 或 S1
   ↓ 每熬过一次 Minor GC，年龄 +1
   ↓
年龄达到阈值（默认 15，-XX:MaxTenuringThreshold）
   ↓
晋升（Promotion）到老年代
   ↓
老年代空间不足 → Major GC / Full GC
   ↓
Major GC 后仍然不足 / 元空间不足 → Full GC（STW，代价极高）
```

**GC 类型对比**：

| 类型 | 别名 | 触发 | 回收范围 | 典型 STW 时长 | 频率 |
| --- | --- | --- | --- | --- | --- |
| **Minor GC** | Young GC | Eden 满 | **新生代**（S0/S1 全清） | **几毫秒 ~ 几十毫秒** | 频繁（秒级~分钟级） |
| **Major GC** | Old GC | 老年代满 / 元空间不足 | **老年代 + 新生代** | **几百毫秒 ~ 数秒** | 较少（分钟~小时级） |
| **Full GC** | — | 元空间不足 / `System.gc()` / 显式调用 | **整个堆 + 方法区** | **秒级** | 极少 |

> **重要区分**：很多资料把 "Major GC" 和 "Full GC" 混用，但严格来说：
> - **Major GC** = 回收老年代（通常伴随新生代回收）。
> - **Full GC** = 回收**整个堆 + 方法区**。
> - 在 G1/ZGC 中，Full GC 是最后的退路（并发失败后的 STW 兜底）。

### 5. GC 算法与收集器

#### 5.1 三种基础算法

| 算法 | 原理 | 优点 | 缺点 | 是否产生碎片 |
| --- | --- | --- | --- | --- |
| **标记-清除（Mark-Sweep）** | 标记存活对象 → 清除未标记 | **不需要移动对象**，不改变引用 | ① 标记阶段需要 STW（可达性分析）② 清除阶段产生**内存碎片** | ❌ 产生碎片 |
| **标记-整理（Mark-Compact）** | 标记 → **向一端滑动**存活对象 → 清理边界外内存 | **无碎片** | ① 移动对象有成本（要修正引用）② STW | ✅ 无碎片 |
| **复制（Copying）** | 内存分两半，把存活对象**复制**到另一半 | **无碎片**、分配快（用指针碰撞） | **需要 2 倍空间**、存活率高时浪费 | ✅ 无碎片 |

**分代收集（Generational GC）** = 组合使用：
- **新生代**：**复制算法**（存活率低 ~10%，复制成本低）。
- **老年代**：**标记-清除或标记-整理**（存活率高，复制不划算）。

#### 5.2 三大收集器

| 收集器 | 分区方式 | 算法 | 目标 | 停顿 | 适用 |
| --- | --- | --- | --- | --- | --- |
| **CMS**（Concurrent Mark Sweep，JDK 5-13，**已废弃**） | **老年代分区**（细粒度） | 标记-清除 | **低延迟** | 初始标记/重新标记 STW，其余并发 | 已移除（JDK 14 废弃） |
| **G1**（Garbage First，JDK 9+ **默认**） | **Region 化**（堆分成 2048 个等大小 Region） | 标记-整理（整体） | **可预测的停顿**（`-XX:MaxGCPauseMillis`） | **可预测**（通常 50-200ms） | 服务端 / Android 生产首选 |
| **ZGC**（JDK 11+，**实验 → 生产**） | **分页 + 染色指针** | 标记-整理 | **亚毫秒停顿**（< 1ms） | **极低**（与堆大小无关） | 超大堆 |

**G1 的核心思想（面试重点）**：

```text
G1 把堆划分为 2048 个等大小的 Region（Eden/Survivor/Old/Humongous 动态角色）

"Garbage First"：
  每次 GC 优先收集【垃圾最多的 Region】（Garbage First）→ 优先回收空闲空间最多的区域
  → 用最少的回收成本释放最多的空间

停顿目标：
  -XX:MaxGCPauseMillis=200    // 用户设定的停顿目标（默认 200ms）
  G1 通过【调整年轻代大小】来逼近这个目标 → 停顿【可预测】

Region 的特殊类型：
  Humongous Region（大对象）：对象大小 >  Region 大小的一半 → 直接分配在连续 Region
  → 大数组/大 String 会造成空间浪费和提前 Full GC
```

**CMS 的三个阶段**：

```text
① 初始标记（STW，~几 ms）—— 标记 GC Roots 直接引用的对象
② 并发标记（并发）        —— 追踪可达性分析
③ 重新标记（STW）        —— 处理并发标记期间的引用变化
④ 并发清除（并发）        —— 清除垃圾
```

- **优点**：并发度高，停顿短。
- **缺点**：
  - **产生内存碎片**（标记-清除）。
  - **CMS 初始化/重新标记阶段仍有 STW**。
  - **浮动垃圾（Floating Garbage）**：并发清除期间新产生的垃圾要等下次 GC。
  - **CPU 占用高**（并发标记与用户线程抢 CPU）。
  - **已被 JDK 14 废弃**（`--illegal-access` 等问题）。

**ZGC 的核心（面试加分）**：

| 技术 | 说明 |
| --- | --- |
| **分页（Page）** | 堆被分成 4KB~4MB 的页 |
| **染色指针（Colored Pointer）** | 用指针的**最高两位**标记：可访问/已标记/已终结/已重定位 |
| **读屏障（Load Barrier）** | 配合染色指针实现**并发转移**，无需 STW |
| **停顿** | **< 1ms**，且**与堆大小无关**（与堆大小成正比的是吞吐） |
| **内存开销** | 染色指针占用部分内存；不支持压缩指针（64 位下有额外开销） |

#### 5.3 Android 的 ART GC

**Dalvik vs ART**：

| 维度 | Dalvik（≤ Android 4.4） | ART（≥ Android 5.0） |
| --- | --- | --- |
| **字节码** | **DEX**（Dalvik Executable） | **DEX** → AOT/JIT 编译成 **OAT/ELF** 机器码 |
| **编译时机** | **解释执行 + JIT**（仅热点方法） | **AOT（安装时全量编译）+ JIT** |
| **GC** | **标记-清除**（无分代） | **分代 + 并发 + 增量**（类似 G1 的设计） |
| **GC 停顿** | 长（几十~几百 ms），易卡顿 | **短**（通常几 ms） |
| **堆分代** | ❌ 无 | ✅ 有（不过 Android 的分代是**"soft / strong / weak" 引用分代**？） |
| **堆大小** | 固定（如 16MB/32MB/48MB/72MB） | **可动态调整**（`dalvik.vm.heapgrowthlimit` 等） |
| **调试** | Dalvik 调试器 | 支持 JDWP、Perfetto、heap dump |

**Android ART 的 GC 特点**：

```text
Android 5.0 (Lollipop) 引入 ART：
  使用【并发的、增量的】分代 GC

① 并发标记（Concurrent Marking）—— 大部分标记工作与应用线程并行
② 增量回收（Incremental GC）—— 把 GC 工作切成小块，插在应用线程的空闲间隙
③ 读写屏障（Read/Write Barrier）—— 支持并发移动对象（类似 ZGC 的染色指针思路）
④ 线程本地分配缓冲区（TLAB）—— 减少线程竞争

效果：典型 STW 停顿从 Dalvik 的 100ms+ 降到 ART 的 5-10ms
```

**Android 的堆大小配置**：

```bash
# 每个应用进程的堆上限
adb shell getprop dalvik.vm.heapgrowthlimit        # 通常 192m / 256m / 512m

# 堆的起始大小 / 强制 GC 阈值
adb shell getprop dalvik.vm.heapstartsize
adb shell getprop dalvik.vm.heaptargetutil        # 触发 GC 的目标使用率（0.75）
adb shell getprop dalvik.vm.heapminfree           # GC 后保留的最小空闲
```

**堆内存不足的表现**：

```text
java.lang.OutOfMemoryError: Failed to allocate a 12582912 byte allocation
  with a free of 2516582 bytes and 1854MB until OOM
  (targetSdk 33, pid 12345)
```

**排查工具**：

```bash
adb shell dumpsys meminfo <package>      # 各进程内存分布
adb shell am dumpheap <package> /sdcard/heap.hprof   # 导出 hprof
adb shell setprop dalvik.vm.dumpheap <package>
```

### 6. JMM：Java 内存模型

**JMM 描述的是"一个线程的操作对另一个线程的可见性"**，它定义了一个**抽象的 happens-before 关系**，让开发者可以推理"哪些操作不会被重排序"。

**JMM 的三大问题**：

| 问题 | 含义 | 典型表现 |
| --- | --- | --- |
| **原子性（Atomicity）** | 一个操作不可被分割，中途不会被打断 | `i++` 不是原子的（读-改-写三步） |
| **可见性（Visibility）** | 一个线程的修改能及时被其他线程看到 | 没有 `volatile` 时读线程可能一直读缓存 |
| **有序性（Ordering）** | 指令执行顺序在结果上等价于**串行**顺序 | 编译器/CPU 可能重排序 |

**JMM 的抽象内存模型**：

```text
主内存 (Main Memory)
  ┌───────────────────────────────────────┐
  │  共享变量：heap 中的对象字段、静态变量、  │
  │           数组元素                        │
  └───────────────────────────────────────┘
       ↑↓ read / write
  ┌─────────┐        ┌─────────┐
  │ 线程 A   │        │ 线程 B   │
  │┌───────┐│        │┌───────┐│
  ││工作内存 ││        ││工作内存 ││
  ││(寄存器 ││        ││(寄存器 ││
  ││ 缓存)  ││        ││ 缓存)  ││
  │└───────┘│        │└───────┘│
  └─────────┘        └─────────┘
```

**工作内存**：每个线程私有的缓存区（寄存器和栈），读变量先读工作内存，用完写回主内存。

### 7. happens-before 规则

**8 条规则（记忆版）**：

| # | 规则 | 说明 |
| --- | --- | --- |
| 1 | **程序顺序** | 线程内，前面的操作 happens-before 后面的操作（**编译器不能重排序单线程内的语义**） |
| 2 | **监视器锁** | `unlock(m)` happens-before 后续对 `m` 的 `lock(m)` |
| 3 | **volatile** | 对 volatile 变量的**写** happens-before 后续对它的**读** |
| 4 | **线程启动** | `Thread.start()` happens-before 该线程中的任何操作 |
| 5 | **线程终止** | 线程中的所有操作 happens-before 其他线程从 `Thread.join()` 成功返回 |
| 6 | **中断** | `Thread.interrupt()` happens-before 被中断线程检测到中断事件 |
| 7 | **对象终结** | 对象构造完成 happens-before `finalize()` 开始 |
| 8 | **传递性** | 若 A happens-before B，B happens-before C，则 A happens-before C |

**传递性示例**：

```java
// 线程 A
data = 42;            // ① 普通写
flag = true;          // ② volatile 写

// 线程 B
if (flag) {           // ③ volatile 读
    print(data);      // ④ 普通读
}
```

```text
① happens-before ②（程序顺序）
② happens-before ③（volatile 规则）
③ happens-before ④（程序顺序）
⇒ 通过传递性：① happens-before ④
⇒ 线程 B 一定能看到 data == 42 ✅
```

**这条规则是"安全发布（Safe Publication）"的理论基础**。

### 8. volatile 的原理：内存屏障

**volatile 的两个保证**：

1. **可见性**：写操作后，其他线程读能立刻看到。
2. **有序性**：禁止指令重排序（通过内存屏障）。

**volatile 不保证原子性**：

```java
volatile int count = 0;

// ❌ 仍然不安全：read-modify-write 三步
count++;      // 编译成：getfield → iconst_1 → iadd → putfield
```

**内存屏障（Memory Barrier / Fence）**：

```text
写 volatile 变量：
  ① 写屏障（StoreStore + StoreLoad）—— 保证【之前的写】先于【本次写】刷出
     ↑ 防止前面的普通写被重排到 volatile 写之后
  ② 写 volatile 之后：StoreStore 屏障，保证本次写先于后续写刷出

读 volatile 变量：
  ③ 读屏障（LoadLoad + LoadStore）—— 保证【本次读】先于【后续读】执行
  ④ volatile 读之后：LoadStore + StoreLoad 屏障
```

**Happens-before 与内存屏障的对应**：

| happens-before 规则 | 插入的内存屏障 |
| --- | --- |
| 程序顺序 | 编译器屏障（编译器保证） |
| volatile 写 | **StoreStore**（前面所有写先完成） |
| volatile 读 | **LoadLoad**（后续读不能提前） |
| volatile 写 → volatile 读 | **StoreLoad**（最重的屏障，保证写先于读） |

**不同平台上的屏障成本**：

| 屏障 | x86 | ARM |
| --- | --- | --- |
| StoreLoad（MFENCE） | **锁前缀指令**（`lock addl`）——**很贵** | `dmb ish` —— 相对便宜 |
| StoreStore / LoadLoad / LoadStore | **编译器屏障**（CPU 天然保证） | `dmb ishst` / `dmb ishld` |

> **Android 几乎全是 ARM 架构**，且 **ART 对 volatile 的实现与 HotSpot 不同**——ART 用 **Java 内存模型（JMM）** 的规则在解释器/JIT 层实现，volatile 字段的读写插入了对应的内存屏障指令。这在 ARM 这种**弱内存序**架构上至关重要。

**Java 内存屏障 API**（`java.util.concurrent`）：

```java
// 内存屏障（Java 9+ 引入 VarHandle 的底层支持）
VarHandle.fullFence();
VarHandle.acquireFence();
VarHandle.releaseFence();
VarHandle.loadLoadFence();
```

### 9. `synchronized` 与 `ReentrantLock`

**`synchronized` 的实现：对象头 Mark Word + monitor**。

```text
同步代码块：
  synchronized (obj) { ... }
    ↓ 字节码
  monitorenter         // ① 进入同步
  ... 业务代码 ...
  monitorexit          // ② 退出同步（异常时由编译器保证执行）

同步方法：
  public synchronized void foo() { ... }
    ↓ 字节码
  ACC_SYNCHRONIZED 标志位  // 由 JVM 在方法调用时自动加锁
```

**锁升级（无锁 → 偏向 → 轻量级 → 重量级）**：

```text
① 无锁（Unlocked）
   Mark Word 存：对象哈希码（31 位）+ GC 年龄（4 位）+ 偏向标志（1 位）

② 偏向锁（Biased Locking）—— JDK 15 已废弃
   Mark Word 存：偏向线程 ID + 时间戳 + 偏向标志
   特点：只有一个线程访问时【完全无锁开销】（CAS 一次都不做）
   撤销偏向：其他线程访问时触发，触发点在 safepoint

③ 轻量级锁（Lightweight Locking）
   Mark Word 存：指向【锁记录 Lock Record】的指针（在当前线程栈上）
   特点：用 CAS + 栈上锁记录，不涉及内核态
   适用：并发度低、锁持有时间短（"忙等待"自旋）

④ 重量级锁（Heavyweight Locking）
   Mark Word 存：指向【ObjectMonitor】的指针（堆上的 monitor 对象）
   特点：依赖 OS 的 mutex（futex），会【线程阻塞 + 上下文切换】
   触发条件：轻量级锁 CAS 失败且自旋失败
```

**自适应自旋（Adaptive Spinning）**：JDK 6 引入。如果**上一个获取锁的线程很快释放了锁**（通过 `wait` 队列的 `succ` 指针判断），当前线程就自旋等待而不是立刻阻塞，避免上下文切换。

**`synchronized` 的优缺点**：

| 优点 | 缺点 |
| --- | --- |
| ✅ 自动释放锁（异常也会释放） | ❌ 只能锁**一个**对象，不能同时锁多个（死锁风险） |
| ✅ 不会忘记 unlock | ❌ 无法**超时**获取锁 |
| ✅ 不可被中断（拿不到锁时不能响应 `interrupt`） | ❌ 无法**可重入**以外的灵活语义（无法尝试加锁） |
| ✅ JVM 会做锁消除/锁粗化优化 | ❌ 没有公平性保证（新的线程可能插队） |

**`ReentrantLock` 的额外能力**：

```java
private val lock = ReentrantLock()

// ① 可中断获取
lock.lockInterruptibly()

// ② 超时获取
if (lock.tryLock(3, TimeUnit.SECONDS)) { ... }

// ③ 尝试获取（立即返回）
if (lock.tryLock()) { ... }

// ④ 公平锁（按等待顺序）
val fairLock = ReentrantLock(true)

// ⑤ 多个 Condition（可创建多个等待队列）
val notEmpty = lock.newCondition()
val notFull = lock.newCondition()
```

**公平锁 vs 非公平锁**：

| | 非公平锁（默认） | 公平锁 |
| --- | --- | --- |
| 策略 | **刚释放锁的线程可能立刻重新抢到**（"插队"） | 严格按等待顺序 FIFO |
| 吞吐 | ✅ **更高**（减少上下文切换） | ❌ 更低（每次都要唤醒队首） |
| 饥饿 | 可能 | 不会 |

**Android 的实际选择**：

```kotlin
// ✅ Android 上的首选并发工具
private val lock = ReentrantLock()

// ⚠️ Kotlin 的 synchronized 编译成 monitorenter/monitorexit
//    在 ART 上表现良好，但协程挂起时持有锁是危险行为！
synchronized(lock) {
    delay(1000)      // ❌ 挂起期间仍持有锁 → 可能死锁
}
```

> **协程中的正确做法**：用 `Mutex`（`kotlinx.coroutines.sync.Mutex`）代替 `synchronized`，因为 `Mutex` 的 `lock()` 是**挂起函数**，释放时会检查协程是否被取消。

### 10. GC 停顿与 Android 掉帧的关系

**掉帧的物理原因**：

```text
一帧必须在 16.67ms 内完成（60Hz）
   │
   ├─ Vsync 信号到达
   ├─ Choreographer 发起 doFrame
   ├─ 动画计算 → UI 线程（RenderThread 等待）
   ├─ 绘制 → RenderThread
   └─ Buffer 提交给 GPU

如果主线程被阻塞超过 16.67ms → 错过 Vsync → 掉帧
```

**GC 造成掉帧的链路**：

```text
GC STW 暂停（主线程被冻结）
   ↓
主线程无法响应 Vsync 的 doFrame 回调
   ↓
Choreographer 的动画回调被推迟
   ↓
本帧耗时 = 业务耗时 + GC 暂停时间 > 16.67ms
   ↓
掉帧（jank），用户感知为"卡顿"
```

**Android 中 GC 造成掉帧的典型场景**：

| 场景 | 原因 |
| --- | --- |
| **大量小对象分配** | Eden 频繁满 → Minor GC 频繁 |
| **大数组/大 Bitmap** | 直接进老年代或 Humongous Region → 提前 Full GC |
| **内存泄漏** | 老年代持续增长 → Major GC 频繁且无法回收 |
| **大列表**（未分页） | 一次性创建海量对象 → Eden 撑爆 |
| **字符串大量拼接** | 产生大量临时 `String` + `char[]`（Java 9+ 用 compact strings 缓解） |

**优化手段**：

```kotlin
// ✅ 减少临时对象分配
// 1. 复用对象（Object Pool）
// 2. 避免在 onDraw/onBindViewHolder 中分配对象
// 3. 使用 StringBuilder 拼接
// 4. 避免自动装箱（Integer cache 是 -128~127）
// 5. 大量数据用原始类型数组（IntArray vs IntArrayList）

// ✅ 减少内存占用
// 6. 大列表分页（Paging 3）
// 7. 图片用 Glide/Coil 管理
// 8. 及时释放不再需要的对象（用 try-with-resources）

// ✅ 主动触发 GC（慎用）
System.gc()          // ❌ 不推荐：会阻塞主线程，且只是建议
Runtime.getRuntime().gc()   // 同上
```

**监控 GC**：

```bash
# 实时查看 GC 情况
adb shell setprop log.tag.art.gc-log 1
adb logcat -s art

# 各进程的内存与 GC 统计
adb shell dumpsys meminfo <package>

# Perfetto 抓取 GC 与主线程调度
```

### 11. Android 的 ART vs Dalvik 内存管理差异

| 维度 | Dalvik | ART |
| --- | --- | --- |
| **堆大小** | **固定**（16/32/48/72MB，按设备等级） | **动态增长**（`heapgrowthlimit`，通常 192m-512m） |
| **堆分代** | ❌ 无（单代） | ✅ 有（新生代/老年代） |
| **GC 算法** | 标记-清除，**同步阻塞** | **并发 + 增量**，停顿短 |
| **典型 STW** | 100ms+ | 5-10ms |
| **对象移动** | ❌ 不移动 | ✅ 并发移动（读写屏障） |
| **大对象** | 堆外（native） | 堆内 Humongous Region |
| **调试** | 有限 | JDWP、heap dump、Perfetto、`libart.so` |
| **`onTrimMemory` 回调** | ✅ 有（API 14+） | ✅ 有 |

**ART 的额外内存开销**：

```text
ART 比 Dalvik 多占内存，因为：
  ① AOT 编译产物（OAT/dex2oat 生成的机器码）—— 安装时生成，存放在 /data/dalvik-cache 或 oat 目录
  ② 运行时对象头的 Mark Word 更大（64 位 + 锁状态信息）
  ③ JIT 编译缓存
  → 所以低内存设备上 ART 更耗内存 → 这也是 Android 7.0 引入
    "isLowRamDevice" 和 App 的 lowMemory 配置的原因
```

## 代码示例

### 1. 演示 JMM 的可见性问题

```java
/**
 * 演示可见性问题与 volatile 的修复。
 * 运行方式：java VisibilityDemo
 */
public class VisibilityDemo {

    // ❌ 没有 volatile：其他线程可能永远看不到更新
    private static boolean running = true;

    // ✅ 加 volatile：保证可见性
    private static volatile boolean volatileRunning = true;

    private static int nonVolatileCounter = 0;
    private static volatile int volatileCounter = 0;

    public static void main(String[] args) throws InterruptedException {
        // ① 可见性实验：工作线程改 running，主线程读
        Thread worker = new Thread(() -> {
            try {
                while (running) {          // 没有 volatile，可能读到缓存里的旧值 → 死循环
                    Thread.onSpinWait();
                }
            } finally {
                System.out.println("工作线程结束");
            }
        });
        worker.start();
        Thread.sleep(100);
        running = false;                 // 主线程写
        worker.join(1000);
        System.out.println("线程是否结束: " + !worker.isAlive());

        // ② 原子性实验：volatile 不保证 i++ 的原子性
        Thread t1 = new Thread(() -> { for (int i = 0; i < 100000; i++) volatileCounter++; });
        Thread t2 = new Thread(() -> { for (int i = 0; i < 100000; i++) volatileCounter++; });
        t1.start(); t2.start();
        t1.join(); t2.join();
        System.out.println("volatile 计数结果（期望 200000）: " + volatileCounter);
        // ❌ 结果通常 < 200000 —— volatile 只保证可见性，不保证复合操作的原子性
    }
}
```

### 2. `synchronized` 与 `ReentrantLock` 的对比用法

```java
/**
 * 两种锁的完整用法对比。
 */
public class LockDemo {

    private final Object monitor = new Object();
    private final ReentrantLock reentrantLock = new ReentrantLock();
    private final Condition notEmpty = reentrantLock.newCondition();
    private final Condition notFull = reentrantLock.newCondition();
    private final ArrayDeque<Integer> queue = new ArrayDeque<>();
    private final int maxSize = 10;

    // ① synchronized 版的生产者-消费者
    public synchronized void putSync(int item) throws InterruptedException {
        while (queue.size() == maxSize) {          // ✅ 必须用 while（防止虚假唤醒）
            wait();                                 // 释放锁 + 等待
        }
        queue.add(item);
        notifyAll();                                // 通知消费者
    }

    public synchronized int takeSync() throws InterruptedException {
        while (queue.isEmpty()) {
            wait();
        }
        int item = queue.poll();
        notifyAll();
        return item;
    }

    // ② ReentrantLock 版：可以精确唤醒指定条件
    public void put(int item) throws InterruptedException {
        reentrantLock.lockInterruptibly();          // 可中断
        try {
            while (queue.size() == maxSize) {
                notFull.await();                    // 只等待"非满"条件 ✅
            }
            queue.add(item);
            notEmpty.signal();                      // 只唤醒"非空"等待者 ✅
        } finally {
            reentrantLock.unlock();                 // ✅ 必须在 finally 中释放
        }
    }

    public int take() throws InterruptedException {
        reentrantLock.lock();
        try {
            while (queue.isEmpty()) {
                notEmpty.await();
            }
            int item = queue.poll();
            notFull.signal();
            return item;
        } finally {
            reentrantLock.unlock();
        }
    }

    // ③ 超时获取 + 公平锁
    private final ReentrantLock fairLock = new ReentrantLock(true);

    public boolean tryDoWork(long timeoutMs) throws InterruptedException {
        if (fairLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
            try {
                return true;
            } finally {
                fairLock.unlock();
            }
        }
        return false;
    }
}
```

### 3. Android 中的内存泄漏与 GC 关系

```kotlin
/**
 * 内存泄漏的本质：对象仍然可达 → GC 无法回收 → 老年代增长 → Major GC 频繁 → 掉帧。
 */
class LeakyActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var timer: Timer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ❌ 泄漏 1：非静态内部类持有 Activity
        val listener = object : OnClickListener {
            override fun onClick(v: View?) {
                // 这里隐式持有外部 Activity.this
                title = "点击了"          // ⚠️ 引用了 Activity
            }
        }
        findViewById<Button>(R.id.btn).setOnClickListener(listener)

        // ❌ 泄漏 2：Handler 消息队列中的 Runnable 持有 Activity
        handler.postDelayed({
            title = "延迟更新"             // ⚠️ 引用了 Activity
        }, 10_000)

        // ❌ 泄漏 3：Timer 未取消
        timer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    title = "定时更新"     // ⚠️ 引用了 Activity
                }
            }, 0, 1000)
        }
    }

    override fun onDestroy() {
        // ✅ 正确做法：及时取消所有注册
        handler.removeCallbacksAndMessages(null)
        timer?.cancel()
        timer = null
        super.onDestroy()
    }
}
```

### 4. 内存抖动（Memory Churn）的检测与优化

```kotlin
/**
 * 内存抖动：频繁的小规模 GC，导致主线程频繁暂停 → 掉帧。
 */
class ChurnDemo {

    /**
     * ❌ 内存抖动的典型来源：高频分配临时对象
     */
    fun badApproach(items: List<String>): String {
        return items.joinToString(",") { it.trim().uppercase() }
        // joinToString 内部会创建 StringBuilder、多个临时 String
    }

    /**
     * ✅ 优化：减少中间对象
     */
    fun goodApproach(items: List<String>): String {
        val sb = StringBuilder(items.size * 16)      // 预估容量，避免多次扩容
        for ((index, item) in items.withIndex()) {
            if (index > 0) sb.append(", ")
            sb.append(item.trim().uppercase())
        }
        return sb.toString()
    }

    /**
     * ✅ 优化：用原始类型数组代替包装类型集合
     */
    fun sum(values: List<Int>): Long {
        // ❌ List<Int> 内部是 List<Integer>，每个元素都是堆上的对象
        var sum = 0L
        for (v in values) sum += v
        return sum

        // ✅ 如果数据源可控，直接用 IntArray（零装箱）
        // val arr = IntArray(size)
    }

    /**
     * ✅ 优化：对象池（复用频繁创建的对象）
     */
    private val stringBuilderPool = ArrayPool<StringBuilder>(4)   // androidx.core.util.Pools

    fun renderWithPool(items: List<String>): String {
        val sb = stringBuilderPool.acquire() ?: StringBuilder(256)
        try {
            sb.setLength(0)
            items.forEach { sb.append(it) }
            return sb.toString()
        } finally {
            stringBuilderPool.release(sb)
        }
    }
}

/**
 * androidx.core.util.Pools：AndroidX 提供的通用对象池。
 */
class ArrayPool<T> private constructor(private val maxPoolSize: Int) {

    private val pool = ArrayList<T>(maxPoolSize)

    fun acquire(): T? = synchronized(pool) {
        if (pool.isNotEmpty()) pool.removeAt(pool.size - 1) else null
    }

    fun release(instance: T) {
        synchronized(pool) {
            if (pool.size < maxPoolSize) pool.add(instance)
        }
    }
}
```

### 5. 用 `jmap` / `jcmd` 分析 JVM 内存

```bash
# ① 查看进程信息
jps -l

# ② 查看堆信息与 GC 情况
jstat -gc <pid> 1000          # 每秒打印一次 GC 统计
# 关键列：
#   S0C/S1C/EC/OC  各代容量
#   YGC/YGCT       Young GC 次数 / 总耗时
#   FGC/FGCT       Full GC 次数 / 总耗时

# ③ 导出堆快照（hprof）
jmap -dump:live,format=b,file=heap.hprof <pid>

# ④ 查看类直方图（最实用的排查手段）
jmap -histo:live <pid> | head -40
#   输出示例：
#   num  #instances  #bytes  class name
#   1:    152340   7302.3KB  java.lang.String

# ⑤ 触发 GC
jmap -dump:live       # 触发一次 full GC 后 dump（live 选项会先 GC）

# ⑥ 线程栈
jstack <pid> > thread.txt

# ⑦ 查看 JVM 参数
jcmd <pid> VM.flags
jcmd <pid> GC.heap_info
```

**Android 上的对应工具**：

```bash
# ① 内存概况
adb shell dumpsys meminfo com.example.app

# ② 导出 hprof
adb shell am dumpheap com.example.app /sdcard/app.hprof
adb pull /sdcard/app.hprof

# ③ 用 Android Studio 的 Memory Profiler 打开

# ④ Perfetto 抓取 GC 与帧信息
```

### 6. Kotlin 协程中的锁选择（对比 `synchronized` 与 `Mutex`）

```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ✅ 协程中正确的锁使用方式。
 */
class SafeCounter {

    // ❌ 错误：synchronized + 挂起函数
    private val lockObj = Any()
    private var countBad = 0

    suspend fun incrementBad() {
        synchronized(lockObj) {
            delay(100)              // ⚠️ 挂起期间仍持有锁 → 阻塞其他协程（且无法被取消）
            countBad++
        }
    }

    // ✅ 正确：Mutex（挂起式锁）
    private val mutex = Mutex()
    private var count = 0

    suspend fun increment() {
        mutex.withLock {
            delay(100)              // ✅ 挂起时释放锁；被取消时自动释放
            count++
        }
    }

    // ✅ 多个状态用多个 Mutex（更细粒度）
    private val userLock = Mutex()
    private val configLock = Mutex()

    suspend fun updateUser(id: String) = userLock.withLock {
        // ...
    }

    suspend fun updateConfig() = configLock.withLock {
        // ...
    }

    /**
     * ✅ tryLock：非阻塞尝试
     */
    suspend fun tryIncrement(): Boolean {
        if (!mutex.tryLock()) return false
        try {
            count++
            return true
        } finally {
            mutex.unlock()
        }
    }
}

/**
 * ✅ 原子类：更简单的单变量原子更新
 */
class AtomicCounter {
    private val counter = java.util.concurrent.atomic.AtomicInteger(0)

    fun increment(): Int = counter.incrementAndGet()      // 原子
    fun get(): Int = counter.get()
}

/**
 * ✅ LongAdder：高并发累加场景（比 AtomicLong 少竞争）
 */
class FastCounter {
    private val counter = java.util.concurrent.atomic.LongAdder(0)

    fun increment() { counter.increment() }
    fun get(): Long = counter.sum()
}
```

## 常见问题 / 面试要点

**读者问：JVM 内存区域有哪些？`StackOverflowError` 和 `OutOfMemoryError` 分别是什么原因？**

**AI答：**

1. **五大区域**：

| 区域 | 线程私有 | 内容 |
| --- | --- | --- |
| **程序计数器** | ✅ | 当前线程执行的字节码行号（native 方法时为 undefined） |
| **虚拟机栈** | ✅ | 方法调用创建的栈帧（局部变量表 + 操作数栈 + 方法引用） |
| **本地方法栈** | ✅ | native 方法调用状态 |
| **堆** | ❌ | **对象实例、数组**（几乎所有对象） |
| **方法区（元空间）** | ❌ | 类元信息、运行时常量池、静态变量 |

2. **`StackOverflowError`——栈溢出**：

```text
原因：线程请求的栈深度超过 JVM 允许的最大值
典型场景：
  ① 无限递归（最常见）
  ② 循环引用导致的序列化/equals 递归
  ③ 复杂的正则回溯
特点：只影响【当前线程】，其他线程正常；通常可以 catch
```

```java
// 无限递归 → StackOverflowError
int depth = 0;
void recurse() { depth++; recurse(); }
```

   **处理**：调大栈 `-Xss`（默认 512KB-1MB，每个线程）；改成循环或迭代。

3. **`OutOfMemoryError`——内存不足**（多个子类）：

| 子类 | 原因 | 排查方向 |
| --- | --- | --- |
| `Java heap space` | **堆**不足 | 内存泄漏、对象过多、堆太小（`-Xmx`） |
| `GC overhead limit exceeded` | GC **时间占比 > 98%** 且回收量 < 2% | 几乎确定是**内存泄漏** |
| `Metaspace` | **元空间**不足 | 类加载过多（动态代理/热部署/反复创建 ClassLoader） |
| `Direct buffer memory` | **堆外内存**不足 | NIO `DirectByteBuffer` 未释放（`Cleaner`） |
| `Unable to create native thread` | **线程数**过多 | 线程泄漏、内存不足导致线程栈无法分配 |
| `Requested array size exceeds VM limit` | 数组超过 JVM 限制（默认 `Integer.MAX_VALUE - 2`） | 试图分配超大数组 |

4. **排查流程**：

```bash
jmap -histo:live <pid> | head -30     # 1. 找占用最多的类
jstat -gc <pid> 1000                  # 2. 看 GC 频率与耗时
jmap -dump:live,format=b,file=h.hprof <pid>   # 3. 导出堆快照，用 MAT 分析
```

5. **面试话术**："栈溢出是**线程私有栈**不够，通常是无限递归；OOM 是**共享区域**不够，堆、元空间、堆外内存、线程数各有各的子类。排查先用 `jmap -histo` 找内存大头，再看 GC 频率——`GC overhead limit exceeded` 基本就是内存泄漏的信号。"

---

**读者问：对象的内存布局是什么样的？为什么对象头里要放哈希码？**

**AI答：**

1. **三段式布局**：

```text
┌──────────────────────────────────────────────┐
│ 对象头 Object Header        12B(32位) / 16B(64位) │
│  ├─ Mark Word       8B（64位 JVM）           │
│  └─ Klass Pointer  4B（压缩）/ 8B            │
├──────────────────────────────────────────────┤
│ 实例数据 Instance Data   ← 成员变量            │
│  排列：父类字段在前，同类型字段聚在一起        │
├──────────────────────────────────────────────┤
│ 对齐填充 Padding        ← 补齐到 8 字节的倍数  │
└──────────────────────────────────────────────┘
```

2. **Mark Word 里存什么（64 位）**：

| 锁状态 | 存储内容 |
| --- | --- |
| 无锁 | **对象哈希码（31 位）** + GC 年龄（4 位） + 偏向标志（1 位） |
| 轻量级锁 | 指向栈上**锁记录**的指针 |
| 重量级锁 | 指向堆上 **ObjectMonitor** 的指针 |
| GC 标记 | **转发指针**（指向其他对象） |

3. **为什么要存哈希码**：
   - **HashMap 需要**：`HashMap` 把 key 的哈希码缓存到 `Node.hash` 里，避免每次 `get` 都重算 `hashCode()`（`String.hashCode()` 要遍历整个字符数组）。
   - **为什么不放在对象里单独一个字段**：那样每个对象都要多占 4 字节，而**大多数对象根本不会被放进 HashMap**（哈希码只在被哈希使用时才有价值）。放在 Mark Word 里**零额外开销**。
   - **Mark Word 是"按需复用"的**：对象哈希码计算后写入 Mark Word，**哈希码之后终身不变**（finalize 之前），所以可以安全缓存。

4. **Klass Pointer 的作用**：指向**元空间**中的 `Class` 元数据 → JVM 据此知道对象的实际类型，从而支持**多态方法派发**和 `instanceof`/类型转换。

5. **压缩指针（Compressed Oops）**：
   - 64 位 JVM 上把对象引用压缩成 32 位：地址 = `堆基址 + offset × 8`。
   - 优点：引用只占 4 字节 → 引用数组、指针密集的数据结构省一半内存。
   - 条件：堆 ≤ 32GB（`-XX:+UseCompressedOops`，**默认开**）。
   - 超过 32GB 自动关闭 → **内存占用突然上升**（这是大内存服务要规划的坑）。

6. **面试话术**："对象头包含 **Mark Word**（哈希码/GC 年龄/锁状态）和 **Klass Pointer**（类型元数据指针）。哈希码放 Mark Word 是为了**零成本缓存**——HashMap 需要它，但大多数对象不需要，单独开字段会浪费 4 字节。Mark Word 是按状态复用的：无锁存哈希码，上了锁就存锁指针。"

---

**读者问：分代收集是怎么回事？Minor GC、Major GC、Full GC 有什么区别？**

**AI答：**

1. **分代假说**："**大部分对象朝生夕死，少数对象长期存活**。" 分代收集就是**针对不同生命周期的对象用不同算法**：
   - **新生代**（Eden + S0/S1）：存活率低 → 用**复制算法**（快、无碎片）。
   - **老年代**：存活率高 → 用**标记-清除/整理**。

2. **对象流转**：

```text
new → Eden
  ↓ Eden 满 → Minor GC
存活 → S0 / S1（年龄 +1）
  ↓ 年龄 ≥ MaxTenuringThreshold（默认 15）
老年代
  ↓ 或大对象直接进老年代
  ↓ 老年代满 → Major GC
  ↓ 仍不足 / 元空间不足 → Full GC
```

3. **三种 GC 的区别**：

| | Minor GC | Major GC | Full GC |
| --- | --- | --- | --- |
| **回收范围** | 新生代 | 老年代（+新生代） | **整个堆 + 方法区** |
| **触发** | Eden 满 | 老年代满 / 元空间不足 | 元空间不足 / `System.gc()` / 显式 `GC.gc()` |
| **算法** | 复制 | 标记-清除 | 标记-清除/整理 |
| **STW** | ✅（但很快） | ✅ | ✅ **最重** |
| **耗时** | **几 ms ~ 几十 ms** | 几百 ms ~ 数秒 | **秒级** |
| **频率** | **非常频繁** | 较少 | 极少 |
| **影响** | 频繁但可接受 | 明显卡顿 | **严重卡顿 / 假死** |

4. **Android 的特殊之处**：
   - ART 的 GC **不使用 Java 的"新生代/老年代"分代方式**，而是根据**引用的强弱**划分：**soft / strong / weak / phantom 引用**的回收时机不同（soft 引用在内存紧张时才回收，LRU 缓存常用）。
   - ART 的堆仍是分区的，但 GC 是**并发 + 增量**的：标记工作与应用线程并行、GC 工作被切小块插入空闲间隙。
   - **STW 依然存在**（初始标记、线程挂起、最终引用处理等阶段），但通常只有几 ms。

5. **常见误解澄清**：
   - "Major GC" 和 "Full GC" **不是一回事**，但很多资料混用。面试时说清"Full GC 是整个堆"更严谨。
   - **CMS 的"并发模式失败"会退化成 Full GC**（这正是 CMS 被废弃的原因之一）。
   - **G1 的"Mixed GC"** 是混合回收年轻代 + 部分老年代 Region，**不是** Major GC。

6. **面试话术**："分代收集基于'**多数对象很快死**'的假设：新生代用**复制算法**（无碎片、快），老年代用**标记-清除**。Minor GC 只收新生代，频繁但只有几毫秒；Major GC 收老年代，几百毫秒；Full GC 收整个堆，是秒级的灾难。Android 的 ART 用的是**并发+增量** GC，STW 只有几毫秒，但频繁 Full GC 依然是掉帧的主因。"

---

**读者问：CMS、G1、ZGC 的区别是什么？Android 用的是哪个？**

**AI答：**

1. **三者的定位**：

| | CMS | G1 | ZGC |
| --- | --- | --- | --- |
| **分区方式** | 老年代**分块**（细粒度） | **Region 化**（2048 个等大 Region） | **分页**（4KB~4MB Page）+ 染色指针 |
| **算法** | 标记-清除 | 标记-整理 | 标记-整理 |
| **目标** | 低延迟 | **可预测的停顿** | **亚毫秒停顿** |
| **典型 STW** | 初始标记/重新标记（几 ms ~ 几十 ms） | **50~200ms 可配置** | **< 1ms** |
| **停顿与堆大小** | 相关 | 相关（但可预测） | **无关** ⭐ |
| **碎片** | ❌ 产生 | ✅ 无 | ✅ 无 |
| **状态** | JDK 5-13，**JDK 14 已废弃** | **JDK 9+ 默认** | JDK 11+（JDK 15 商业特性） |

2. **G1 的核心机制**：

```text
Region 化：把堆划分为 2048 个等大小 Region
  每个 Region 动态扮演 Eden / Survivor / Old / Humongous

"Garbage First"：
  优先回收【垃圾最多的 Region】→ 用最小成本释放最多空间

停顿控制：
  -XX:MaxGCPauseMillis=200    // 用户指定停顿目标
  G1 通过动态调整【年轻代大小】来逼近目标 → 停顿可预测 ✅

Remembered Set（记忆集）：
  记录老年代 Region 指向年轻代 Region 的引用
  → 回收年轻代时不必扫描整个老年代
```

3. **ZGC 的核心技术**：

| 技术 | 说明 |
| --- | --- |
| **分页（Page）** | 堆分成 4KB~4MB 的页 |
| **染色指针（Colored Pointer）** | 指针最高两位标记：可访问/已标记/已终结/已重定位 |
| **读屏障（Load Barrier）** | 配合染色指针实现**并发转移**（对象可以在 GC 期间被移动） |
| **STW** | 只在几个极短的点（根扫描切换、引用处理） |
| **代价** | 染色指针占内存；**不支持压缩指针**（64 位下需额外空间） |

4. **Android 用哪个？**：
   - **Android 都不是**——ART 有**自己的 GC 实现**（基于 ART runtime 的并发标记 + 读写屏障 + 增量回收），设计思路接近 **ZGC 的并发转移**。
   - 但在 **Android 13（API 33）** 起，ART 引入了一个实验性的 **"Concurrent Mark and Sweep (CMS)"** GC（`dalvik.vm.cms`）——**名字叫 CMS，但和 HotSpot 的 CMS 不是一回事**（它是 ART 的实验性并发标记清除实现）。
   - 默认仍是 ART 的**分代并发 GC**（Concurrent + Incremental）。

5. **面试话术**："CMS 是并发标记-清除，停顿短但有碎片且 JDK 14 已废弃；**G1 把堆 Region 化，按'垃圾最多优先'回收，能逼近设定的停顿目标，是 JDK 9+ 的默认**；**ZGC 用分页 + 染色指针 + 读屏障实现并发转移，停顿 < 1ms 且与堆大小无关**。Android 的 ART 有自己的并发+增量 GC，思路接近 ZGC——**名字叫 CMS 的那个是 ART 自己的实现，跟 HotSpot 的 CMS 无关**。"

---

**读者问：什么是 JMM？它解决了什么问题？**

**AI答：**

1. **定义**：JMM（Java Memory Model）是**一个抽象规范**，规定：
   - 一个线程的写操作何时对其他线程**可见**（**可见性**）。
   - 读操作能否读到**最新值**（**可见性**）。
   - 多个操作的顺序能否被**重排序**（**有序性**）。
   - 单个操作是否**不可分割**（**原子性**）。

2. **解决的问题**：多线程编程中，**编译器、CPU、内存**都可能进行重排序，导致"看起来不按代码顺序执行"。JMM 给出一个**最小保证**：只要遵守 happens-before 规则，程序结果就与串行执行等价。

3. **JMM 的抽象模型**：

```text
主内存（共享变量）
   ↑↓ read/write
线程 A 的工作内存  ←→  线程 B 的工作内存
（寄存器、CPU 缓存）
```

   - 每个线程的读**先读工作内存**（可能是过期的缓存）。
   - 写**先写工作内存**，**何时刷到主内存不确定** → 这就是可见性问题的根源。
   - **JMM 允许的不确定性**，被 `volatile`/`synchronized`/原子类等同步手段约束。

4. **三大问题的典型体现**：

| 问题 | 例子 |
| --- | --- |
| **原子性** | `i++` = 读-改-写 三步 → 并发下丢失更新 |
| **可见性** | 一个线程改了 `flag`，另一个线程读不到 → 死循环 |
| **有序性** | 编译器和 CPU 重排序 → 单线程语义被破坏（`DCL` 单例问题） |

5. **happens-before 是 JMM 的核心工具**（8 条规则，详见核心原理第 7 节）。它是**判断并发代码是否安全**的"编译器"，比死记硬背规则更实用：
   - 建立 A → B 的 happens-before 链 → A 的写对 B 可见。
   - **传递性**让规则可以组合。

6. **面试话术**："JMM 是一套**并发可见性的规范**：它定义了一个线程的写什么时候对另一个线程可见、指令能不能重排、以及操作是否原子。它用 **happens-before 规则**作为推理工具——只要有 A happens-before B，A 的写就能被 B 安全看到。`volatile` 通过**内存屏障**实现可见性和有序性，`synchronized` 通过 **monitor** 同时实现三个特性，但都**不保证**复合操作的原子性。"

---

**读者问：`volatile` 的原理是什么？为什么它能保证可见性但不保证原子性？**

**AI答：**

1. **两个保证**：
   - **可见性**：一个线程对 volatile 变量的写，其他线程立刻能看到。
   - **有序性**：禁止相关的指令重排序（通过**内存屏障**）。
   - **不保证原子性**：复合操作（`++`、检查后执行）仍然不安全。

2. **为什么 `volatile` 不保证原子性**：

```java
volatile int count = 0;
count++;    // 编译成三步：
            //   ① getfield  读 count
            //   ② iconst_1 / iadd  加 1
            //   ③ putfield  写回 count
```

   - volatile 只保证**单次读**和**单次写**的可见性与顺序性。
   - 读-改-写**三步之间**没有原子性 → 两个线程可能都读到 5，都写回 6 → 丢失更新。
   - **解法**：`AtomicInteger`（CAS）、`LongAdder`、`synchronized`。

3. **内存屏障机制**：

```text
写 volatile：
  ① StoreStore 屏障 —— 保证【之前的所有普通写】先于本次 volatile 写完成
     （防止普通写被重排到 volatile 写之后）
  ② 写 volatile 本身
  ③ StoreLoad 屏障 —— 保证本次写先于后续的 volatile 读（最重的屏障）

读 volatile：
  ④ LoadLoad 屏障 —— 保证【后续的普通读】不能提前
  ⑤ 读 volatile 本身
```

   → 核心是 **StoreLoad 屏障**：它建立了"**写 → 读**"的顺序保证（写必须先刷出缓存，读才能看到）。

4. **平台差异**：

| 屏障 | x86 | ARM |
| --- | --- | --- |
| StoreLoad | **MFENCE / `lock addl`** —— 昂贵 | `dmb ish` —— 相对便宜 |
| 其他三个 | 只需**编译器屏障**（CPU 天然有序） | 需要 `dmb ishst` 等硬件屏障 |

   > **Android 几乎全是 ARM 架构**（弱内存序），所以 volatile 的实现必须插入真正的硬件屏障。

5. **happens-before 对应**：

```text
volatile 写 happens-before 后续 volatile 读
  ⇒ 可见性 ✅ + 有序性 ✅
  ⇒ 但不蕴含【复合操作】的原子性 ❌
```

6. **典型使用场景**：

| 场景 | 说明 |
| --- | --- |
| **状态标志位** | `volatile boolean running` —— 优雅关闭线程的标准写法 |
| **单例的双检锁（DCL）** | `volatile` 保证**实例化顺序**（否则别的线程可能拿到半初始化的对象） |
| **`Double-Checked Locking`** | 配合 `synchronized` + `volatile` |
| **读写锁的读写状态** | `ReentrantReadWriteLock` 内部 |
| **线程池的状态标记** | `ThreadPoolExecutor` 的 `ctl`（用 `AtomicInteger`） |

7. **面试话术**："`volatile` 通过**内存屏障**实现：写 volatile 前插 StoreStore（保证之前的写先刷出）、写后插 StoreLoad（保证写先于后续读）、读前插 LoadLoad（保证后续读不提前）。它给出的是'**写-读**'的顺序保证，所以可见性和有序性都有；但 `i++` 是读-改-写三步，volatile 只保证单次读写，**不保证复合操作原子**——那个要用 CAS 或锁。"

---

**读者问：`synchronized` 的底层原理是什么？锁是怎么升级的？**

**AI答：**

1. **两种实现路径**：

```text
同步代码块：
  monitorenter / monitorexit（字节码指令）→ 关联到对象的 ObjectMonitor

同步方法：
  ACC_SYNCHRONIZED 标志位 → 由 JVM 在方法调用/返回时自动加锁/解锁
```

2. **ObjectMonitor 的结构（JDK 8）**：

```text
ObjectMonitor
  ├─ owner        —— 持有锁的线程
  ├─ entryList   —— 等待获取锁的线程（阻塞队列）
  ├─ waitSet     —— 调用 wait() 后等待的线程
  ├─ waiterCount —— waitSet 中的线程数
  └─ _recursions —— 重入次数（可重入的来源）
```

   - **`wait()`**：释放锁 + 加入 `waitSet`（**必须先释放锁**，否则死锁）。
   - **`notify()`**：把 `waitSet` 中的线程移到 `entryList`（**不释放锁**）。
   - **`notifyAll()`**：全部移动（虚假唤醒问题 → 等待方必须用 `while` 循环检查条件）。

3. **锁升级路径**：

```text
① 无锁（Unlocked）
   Mark Word：对象哈希码 + GC 年龄 + 偏向标志
   → 每次访问都要检查是否需要升级（有 CAS 开销）

② 偏向锁（Biased Locking）—— JDK 15 已废弃（JDK 18 移除）
   Mark Word：偏向线程 ID + 时间戳 + 偏向标志
   → 只有一个线程访问时【完全无锁开销】（连 CAS 都不做）
   → 其他线程访问 → 触发【撤销偏向】→ 升级

③ 轻量级锁（Lightweight Locking）
   Mark Word：指向【锁记录 Lock Record】的指针（在线程自己的栈上）
   → 用 CAS 把栈上的锁记录指向 Mark Word，再让 Mark Word 指向锁记录
   → 适用：并发度低、锁持有时间短

④ 重量级锁（Heavyweight Locking）
   Mark Word：指向堆上 ObjectMonitor 的指针
   → 依赖 OS mutex（futex），线程【阻塞 + 上下文切换】
   → 触发条件：轻量级锁 CAS 失败 + 自旋失败
```

4. **自适应自旋（Adaptive Spinning）**：如果**上一个释放锁的线程**很快放锁（通过 `wait` 队列的 `succ` 指针判断），当前线程就**自旋等待**而不是立刻阻塞——省掉一次上下文切换。

5. **锁消除（Lock Elimination）**：逃逸分析确定锁对象不会被其他线程访问 → JIT **直接去掉同步**。

```java
// 局部对象，逃逸分析后锁被消除
public void method() {
    Object o = new Object();
    synchronized (o) { ... }   // ← 无竞争，JIT 去掉锁
}
```

6. **锁粗化（Lock Coarsening）**：连续对**同一个对象**的加锁解锁 → 合并成一次。

```java
// 锁粗化后
synchronized (o) { a(); b(); c(); }   // 原本是 3 次加解锁
```

7. **与 `ReentrantLock` 的对比**：

| 维度 | `synchronized` | `ReentrantLock` |
| --- | --- | --- |
| **释放锁** | ✅ 自动（异常也释放） | ❌ 必须在 `finally` 手动 `unlock()` |
| **可中断** | ❌ | ✅ `lockInterruptibly()` |
| **超时** | ❌ | ✅ `tryLock(timeout)` |
| **公平性** | ❌（非公平） | ✅ 可选（`new ReentrantLock(true)`） |
| **多个条件变量** | ❌ 只有一个 `waitSet` | ✅ 多个 `Condition` |
| **额外开销** | 无（VM 内置） | 有（`AQS` + `Node` 队列） |
| **JDK 优化** | ✅ 锁消除/粗化 | — |

8. **面试话术**："`synchronized` 靠 **ObjectMonitor**（owner / entryList / waitSet）和 **Mark Word 的锁状态**实现。锁会按'无锁 → 偏向 → 轻量级 → 重量级'升级：偏向锁让单线程访问零开销（已废弃），轻量级锁用 CAS + 栈上锁记录避免线程切换，重量级锁才真正阻塞线程。JIT 还会做**锁消除和锁粗化**。相比 `ReentrantLock` 少了可中断/超时/公平/多 Condition，胜在自动释放。"

---

**读者问：GC 停顿和 Android 掉帧有什么关系？怎么优化？**

**AI答：**

1. **因果链路**：

```text
GC STW 暂停（主线程冻结 5~100ms）
   ↓
主线程无法在 16.67ms 内完成 Choreographer 的 doFrame
   ↓
错过 Vsync → 本帧耗时超标 → 【掉帧 jank】
   ↓
若主线程阻塞 > 5s → 【ANR】（Application Not Responding）
```

   **关键点**：GC 暂停期间**主线程完全冻结**，不只是"变慢"。

2. **Android 的 GC 表现**：

| GC 场景 | STW 时长 | 掉帧影响 |
| --- | --- | --- |
| ART Minor GC（并发标记） | 通常 < 5ms | 大多无感 |
| ART 并发标记的 STW 点 | ~1-3ms | 轻微 |
| **Full GC / 内存不足** | **50ms ~ 数秒** | **明显掉帧 / ANR** |
| Dalvik 时代的 GC | **100ms+** | 严重卡顿（旧机型） |

3. **掉帧的四大根因与 GC 的对应**：

| 根因 | GC 关系 | 表现 |
| --- | --- | --- |
| **内存泄漏** | 老年代持续增长 → Major GC 频繁且回收不掉 | 越用越卡，最终 OOM |
| **内存抖动（Churn）** | 频繁 Minor GC | **持续性轻微掉帧**（GC 次数多） |
| **大对象** | 直接进老年代/Humongous → 提前 Full GC | 周期性严重卡顿 |
| **大列表** | 一次性创建海量对象 → Eden 撑爆 | 首次展示严重卡顿 |

4. **优化手段（按优先级）**：

```kotlin
// ① 消除内存泄漏（最优先）
//    → LeakCanary 自动检测；ViewModel + LiveData/StateFlow 避免持有 View

// ② 减少内存抖动 —— 减少临时对象分配
//    → 避免在 onDraw / onBindViewHolder / 高频回调中创建对象
//    → StringBuilder 预分配容量
//    → 使用对象池（Pools）

// ③ 用原始类型替代包装类型集合
val ids = IntArray(100)        // ✅ 而非 ArrayList<Int>

// ④ 大列表分页
//    → Paging 3

// ⑤ 图片管理
//    → Glide / Coil，按屏幕尺寸解码，避免 OOM

// ⑥ 主动降低内存占用
//    → onTrimMemory 响应，清理缓存
//    → 及时释放不需要的大对象

// ⑦ 【不要】手动 System.gc()
System.gc()   // ❌ 会阻塞主线程，且只是"建议"，反而可能触发 Full GC
```

5. **监控与定位**：

```bash
# ① GC 日志
adb shell setprop log.tag.art.gc-log 1
adb logcat -s art | grep -i gc

# ② 内存概况
adb shell dumpsys meminfo com.example.app

# ③ 帧率与卡顿
adb shell dumpsys gfxinfo com.example.app framestats

# ④ Perfetto 抓取（推荐）
#    追踪类别：sched（调度）、freq、gc、frame
```

6. **面试话术**："GC 停顿会**冻结主线程**，直接导致错过 Vsync 掉帧。Android 上 ART 用并发+增量 GC 把常规 STW 压到几毫秒，但**内存泄漏**（老年代涨满 → Major GC 频繁）、**内存抖动**（频繁 Minor GC）、**大对象/大列表**（提前 Full GC）仍会明显掉帧。优化的核心是**消除泄漏 + 减少临时对象分配 + 分页**，而不是手动 `System.gc()`——那反而会制造卡顿。"

---

**读者问：Android 的 ART 和 Dalvik 有什么区别？内存管理上有什么差异？**

**AI答：**

1. **核心对比**：

| 维度 | Dalvik（≤ Android 4.4） | ART（≥ Android 5.0） |
| --- | --- | --- |
| **字节码/编译** | DEX **解释执行 + JIT**（只编译热点） | DEX → **AOT**（安装时全量编译为机器码）+ **JIT** |
| **首次运行速度** | 慢（需要 JIT 预热） | **快**（AOT 已在安装时完成） |
| **安装耗时** | 快 | **慢**（安装时编译，且需要额外空间） |
| **GC** | **标记-清除，单代，同步阻塞** | **分代 + 并发 + 增量** |
| **STW 时长** | **100ms+** | **通常 5~10ms** |
| **堆大小** | **固定**（16/32/48/72MB，按设备等级） | **动态增长**（`heapgrowthlimit`，通常 192m~512m） |
| **大对象** | 部分走 native 堆 | **堆内 Humongous Region** |
| **调试** | 有限 | JDWP、heap dump、Perfetto、`libart.so` |
| **内存占用** | 低 | **更高**（AOT 产物 + 更大的对象头 + JIT 缓存） |

2. **内存管理差异详解**：

```text
Dalvik：
  固定小堆（32MB 常见）
  → 稍大的图片/列表就 OOM
  → GC 标记-清除，STW 长（100ms+）→ 明显卡顿
  → 开发者必须严格控制内存

ART：
  堆可动态增长（默认 192m-512m）
  → 容错空间大
  → GC 并发+增量，STW 短
  → 但 AOT 产物占用额外磁盘空间，且内存占用更高
  → 低内存设备上需要更严格的内存管理（这是 Android 7.0 引入
     isLowRamDevice 的原因）
```

3. **ART 的 GC 技术**：

| 技术 | 说明 |
| --- | --- |
| **并发标记（Concurrent Marking）** | 标记工作与应用线程并行 |
| **增量回收（Incremental GC）** | GC 工作切成小块，插入应用线程的空闲间隙 |
| **读写屏障（Read/Write Barrier）** | 支持并发移动对象（类似 ZGC 染色指针思路） |
| **TLAB** | 线程本地分配缓冲区，减少分配竞争 |

4. **软引用分代（Android 特有）**：ART 按**引用强度**划分对象区域——`soft` 引用在**内存紧张时**才被回收（这是 Android LruCache 的基础）、`weak` 引用随时可回收、`phantom` 引用用于配合 `ReferenceQueue` 做资源清理。

5. **从 Dalvik 迁移到 ART 时踩的坑**：
   - **内存占用增加**（AOT 产物 + 更大堆）→ 低端机需要更激进的优化。
   - **Class 数量问题**（Java 8 的 lambda / 混淆 / 多 dex）→ Metaspace 增长。
   - **反射被禁止访问隐藏 API**（Android 9 起的黑名单）。

6. **面试话术**："Dalvik 是 DEX 解释执行 + JIT，堆固定且很小，GC 是单代标记-清除、STW 能到 100ms 以上，所以老设备特别卡。ART 从 Android 5.0 引入，**AOT 预编译 + 并发增量 GC + 动态堆**，STW 降到几毫秒。代价是安装变慢、AOT 产物占空间、内存占用更高。Android 9 起 ART 还禁止反射访问隐藏 API，这是很多老库崩溃的原因。"

---

**读者问：Android 上怎么排查内存泄漏？LeakCanary 的原理是什么？**

**AI答：**

1. **泄漏的本质**：**对象仍然可达**（存在一条 GC Root → 泄漏对象的引用路径）→ GC 无法回收 → 老年代持续增长 → Major GC 频繁 → 掉帧，最终 OOM。

```text
GC Root（GC Roots）：
  ① 线程栈上的局部变量
  ② 已加载的类的静态字段（static）
  ③ JNI 引用
  ④ JVM 内部持有的对象（Class 对象、ClassLoader）
```

2. **Android 上的四大泄漏源**：

| 泄漏源 | 原因 | 典型场景 |
| --- | --- | --- |
| **非静态内部类** | 隐式持有外部 Activity | `object : OnClickListener { title = "..." }` |
| **Handler 消息队列** | 队列里的 Runnable/MESSAGE 持有 Activity | `handler.postDelayed { ... }` 未 remove |
| **匿名内部类 / Lambda** | 同样隐式持有外部 this | `setOnClickListener { ... }`（Lambda 有优化，但引用捕获仍在） |
| **未取消的异步任务/订阅** | 回调持有 Activity | `Timer`、`Observable.subscribe`、协程 `GlobalScope` |

3. **LeakCanary 的检测原理（四步）**：

```text
① 触发 GC
   LeakCanary 在特定时机（Activity 销毁后 + 延时）主动触发 GC
   → Runtime.getRuntime().gc() + System.runFinalization()

② 弱引用检测
   被检测对象被放入【WeakReference】
   → GC 后如果 WeakReference 的 referent 仍不为 null
   → 说明【对象还没有被回收】→ 可疑泄漏

③ 计算 GC Root 路径（Retaining Path）
   LeakCanary 从 Android 的 JNI 层（debuggerd / ART 内部）拿到
   【谁还持有这个对象】的引用链
   → 这一步是 LeakCanary 的核心难点

④ 排除假阳性
   排除 JNI 全局引用、类的静态字段等【合理持有】
   → 剩下的就是真正的泄漏
```

4. **手动检测泄漏的基本原理（不依赖 LeakCanary）**：

```kotlin
class LeakyActivity : Activity() {

    private var leakedRef: WeakReference<MyObject>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val obj = MyObject()
        leakedRef = WeakReference(obj)
        // 故意让 obj 不可达
    }

    override fun onDestroy() {
        super.onDestroy()
        // 延时后检查：obj 若仍未被回收 → 说明有人持有它
        Handler(Looper.getMainLooper()).postDelayed({
            System.gc()
            System.runFinalization()
            val stillAlive = leakedRef?.get() != null
            Log.d("LeakCheck", "是否泄漏 = $stillAlive")
        }, 5000)
    }
}
```

5. **Android Studio 的 Memory Profiler 用法**：

```text
① 选 Debuggable 变体运行
② View → Tool Windows → Memory Profiler
③ 点 "Dump the heap"（或 adb shell am dumpheap <pkg> /sdcard/x.hprof）
④ 在 Memory Profiler 里：
   - 看【Instances】视图，按 Class 分组，找数量异常的类
   - 看【References to > XxxClass】找到持有者
   - 用 "Allocation Tracking" 定位分配热点
⑤ 多次 dump 对比，找出【只增不减】的对象 → 泄漏
```

6. **手动触发 Heap Dump**：

```bash
adb shell am dumpheap com.example.app /sdcard/app.hprof
adb pull /sdcard/app.hprof
# 或者在 debug 模式下：
adb shell setprop dalvik.vm.dumpheap com.example.app
```

7. **预防胜于治疗**：

```kotlin
// ✅ 1. 用 ViewModel + StateFlow 持有数据（不持有 View）
class MyVM : ViewModel() {
    val state = MutableStateFlow<UiState>(UiState.Idle)
}

// ✅ 2. 生命周期感知的收集：页面不可见时自动取消
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        vm.state.collect { ... }
    }
}

// ✅ 3. 在 onDestroy 清理注册
override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    subscription.dispose()
    super.onDestroy()
}

// ✅ 4. 用 applicationContext 而不是 Activity context
```

8. **面试话术**："Android 内存泄漏的本质是**对象仍然可达**，GC Root 到它的引用没断。四大来源是：**非静态内部类、Handler 消息队列、匿名内部类/Lambda、未取消的异步任务**。LeakCanary 的原理是：**主动触发 GC → 用 `WeakReference` 检查对象是否被回收 → 从 JNI 层反查 GC Root 到它的引用链（Retaining Path）→ 排除假阳性**。排查时用 Memory Profiler 的 hprof 找数量异常、只增不减的类。预防上首选 **ViewModel + 生命周期感知的收集**。"

---

**读者问：`happens-before` 规则有哪几条？实际开发中怎么用它判断并发代码是否安全？**

**AI答：**

1. **八条规则（完整版）**：

| # | 规则 | 表述 |
| --- | --- | --- |
| 1 | **程序顺序** | 同一线程内，前面的操作 happens-before 后面的操作（若存在数据依赖） |
| 2 | **监视器锁** | `unlock(m)` happens-before 后续对 `m` 的 `lock(m)` |
| 3 | **volatile** | 对 volatile 变量的**写** happens-before 后续对它的**读** |
| 4 | **线程启动** | `Thread.start()` happens-before 该线程中的任何操作 |
| 5 | **线程终止** | 线程中的所有操作 happens-before 其他线程从 `Thread.join()` 成功返回 |
| 6 | **中断** | `Thread.interrupt()` happens-before 被中断线程检测到中断事件 |
| 7 | **对象终结** | 对象构造完成 happens-before `finalize()` 开始 |
| 8 | **传递性** | 若 A happens-before B 且 B happens-before C，则 A happens-before C |

2. **实际使用的三个判断步骤**：

```text
① 找出所有 happens-before 边（用上面 8 条规则）
② 检查数据竞争：
   · 同一个变量被多个线程访问
   · 至少一个是【写】
   · 且【写】与【读/写】之间【没有】happens-before 边
   → 存在数据竞争 → 不安全
③ 不安全的话，选择同步手段建立 hb 边：
   volatile / synchronized / Atomic / Lock / 线程安全容器
```

3. **实例 1——安全发布（Safe Publication）**：

```java
// 线程 A
data = 42;            // ① 普通写
ready = true;         // ② volatile 写

// 线程 B
if (ready) {          // ③ volatile 读
    print(data);      // ④ 普通读
}
```

```text
① hb ②（程序顺序）
② hb ③（volatile 规则）
③ hb ④（程序顺序）
⇒ 传递性 ⇒ ① hb ④
⇒ 线程 B 一定读到 data == 42 ✅
```

   → **这是"非 volatile 变量可以被 volatile 变量'捎带'发布"的原理**（《Java Concurrency in Practice》称为"**通过 volatile 变量的安全发布**"）。

4. **实例 2——双重检查锁（DCL）为什么需要 volatile**：

```java
public class Singleton {
    private static volatile Singleton instance;   // ✅ 必须 volatile

    private Singleton() {}

    public static Singleton getInstance() {
        if (instance == null) {                   // ① 第一次检查（无锁读）
            synchronized (Singleton.class) {
                if (instance == null) {           // ② 第二次检查
                    instance = new Singleton();    // ③ 构造 + 发布
                }
            }
        }
        return instance;
    }
}
```

```text
没有 volatile 时的问题：
  线程 A 执行 ③：分配内存 → 调构造方法 → 【赋值给 instance】（这三步可能被重排）
  线程 B 在 ① 处看到 instance != null（因为赋值先于构造完成）
  → 线程 B 拿到【半初始化】的对象 → instance.someField 是 null/default 💥

volatile 的作用：
  禁止 ③ 内部的指令重排（写 instance 之前的构造步骤必须完成）
  → 保证要么看到 null，要么看到完全初始化的对象 ✅
```

5. **实例 3——线程安全的懒加载单例**：

```java
// ✅ 推荐：静态内部类（利用 JVM 的类初始化锁 + happens-before 规则 4/5）
public class Singleton {
    private static class Holder {
        static final Singleton INSTANCE = new Singleton();
    }
    public static Singleton getInstance() { return Holder.INSTANCE; }
}
```

```text
为什么线程安全？
  JVM 保证类的【静态初始化】是线程安全的（类初始化锁）
  → 线程 A 初始化 Holder 类时，线程 B 会阻塞等待
  → B 看到 Holder.INSTANCE 时，一定是初始化完成的
  → 这背后就是 happens-before 规则 4（类初始化 happens-before 其他线程首次访问）
```

6. **实例 4——`ConcurrentHashMap` 的可见性**：

```java
// CHM 的 put 和 get 之间是否有 hb 关系？
map.put(k, v);     // 线程 A
map.get(k);        // 线程 B
```

```text
  CHM 的 get 是【volatile 读】（getObjectVolatile）
  volatile 写 hb volatile 读
  → put 内部对 value 的写是 volatile 写
  → 所以 put 后另一个线程的 get 一定能看到最新值 ✅
```

7. **常见误区**：

```text
❌ 误区 1："用了 synchronized 就绝对安全"
   → synchronized 只保护【同一把锁】保护的临界区；不同锁之间没有 hb 关系

❌ 误区 2："Thread.sleep 能保证可见性"
   → sleep 只保证【时间上】等待，不建立任何 hb 关系

❌ 误区 3："volatile 能保证 i++ 的原子性"
   → volatile 只保证单次读/写的可见性，复合操作仍会丢失更新

❌ 误区 4："final 变量是线程安全的"
   → final 字段【初始化后】是安全发布的（写 final 字段 happens-before 其他线程读到它）
     但这不意味着包含 final 字段的对象的其他可变字段也是安全的
```

8. **面试话术**："happens-before 有八条规则，最常用的是**程序顺序、监视器锁、volatile、线程启动/终止、传递性**。用它判断并发安全的方法是：**找到所有 hb 边，检查是否存在'一个线程写、另一个线程读、且两者之间无 hb 边'的数据竞争**。实际中最典型的三条链：**volatile 安全发布**（写普通变量 → 写 volatile → 读 volatile → 读普通变量，靠传递性保证）、**DCL 需要 volatile**（防止构造与赋值的重排）、**静态内部类单例**（依赖 JVM 类初始化锁 + 规则 4）。"

## 关联笔记

- [[集合框架]]：集合的内存占用与 GC 的关系；`ConcurrentHashMap` 的 CAS 与 JMM
- [[泛型与反射]]：类型擦除在字节码层面的体现；`Class` 对象与元空间
- [[../Kotlin/协程与Flow]]：协程挂起时 `Continuation` 对象存放在**堆**上；`Mutex` vs `synchronized`
- [[../Kotlin/高阶函数与扩展]]：`inline` 函数减少对象分配（对 GC 有正向影响）
- [[../跨语言互调]]：`@JvmStatic` 生成的静态方法与字节码细节
- [[../README]]：第 1 部分总览
- [[../../06-系统原理与性能优化/README]]：Android 内存优化的完整方案（LeakCanary、Memory Profiler、Profiler）
- [[../../07-架构、工具与生态/测试/单元测试]]：JVM 单元测试与内存/JIT 相关的注意事项

## 参考资料

- Java 虚拟机规范（JVMS）· 第 2 章"结构化 Java 虚拟机"（https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-2.html）
- Java 语言规范（JLS）· 第 17 章"内存模型"（https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html）
- OpenJDK 源码 · `java.util.concurrent.locks`（https://github.com/openjdk/jdk/tree/master/src/java.base/share/classes/java/util/concurrent/locks）
- Android 官方文档 · ART 与 Dalvik（https://developer.android.com/art）
- Android 官方文档 · 内存管理（https://developer.android.com/topic/performance/memory-overview）
- 《深入理解 Java 虚拟机》（周志明）· 第 2、3、6 章
- 《Java 内存模型 · Java Concurrency in Practice》（Brian Goetz）· 第 3、5、8 章
- 《深入理解 Java 虚拟机》（周志明）· 第 6 章"Java 内存模型与线程"
