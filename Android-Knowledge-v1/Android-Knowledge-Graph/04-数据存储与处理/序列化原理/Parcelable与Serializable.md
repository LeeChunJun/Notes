---
tags: [android]
part: 第4部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[../README]], [[../数据格式/JSON与ProtoBuf]], [[../数据库/SQLite与Room]]"
---

# Parcelable与Serializable

## 一句话定义

`Serializable` 是 **Java 原生的通用序列化**：靠 `ObjectOutputStream` **反射**遍历对象图并写入流，通用但慢；`Parcelable` 是 **Android 专为进程内传输设计的轻量方案**：开发者手写 `writeToParcel`/`createFromParcel` 把对象**摊平成字节序列**写进 `Parcel`（底层是**共享内存缓冲区**），解码端再按同样顺序读回，**只拷贝一次、比 Serializable 快一个数量级以上**，代价是要手写（或用 `@Parcelize` 生成）代码。

## 核心原理

### 1. 三种"序列化"的定位对比

| 维度 | Java `Serializable` | Android `Parcelable` | JSON / ProtoBuf |
| --- | --- | --- | --- |
| **定位** | 通用对象持久化（文件、socket、RMI） | **进程内 / 跨进程对象传递**（Bundle、Intent、Binder） | **持久化与网络传输** |
| **可读性** | 二进制，不可读 | 二进制，不可读 | JSON 可读；ProtoBuf 二进制 |
| **schema** | 无（靠类结构） | 无（靠类结构） | JSON 无；ProtoBuf 有 |
| **反射** | ✅ 全靠反射 | ❌ 手写/生成代码 | Gson 反射；ProtoBuf 生成 |
| **跨语言** | ❌ 只能 Java 系 | ❌ 只能 JVM 系 | ✅ JSON/ProtoBuf 可跨语言 |
| **持久化存储** | ✅ 可存文件 | ❌ 不适合（无版本兼容） | ✅ |
| **性能** | 慢 | **快** | 中 / 快 |

> 关键认知：**Parcelable 不是"序列化"，是"打包 + 解包"**。它没有"流"的概念，也不保证流的语义（不能 seek、不能先读长度再随机访问）。它本质是**一个有序的字节序列的读写协议**。

### 2. Serializable 的原理：ObjectOutputStream + 反射 + header

Java 的 `Serializable` 走 `ObjectOutputStream`：

```text
   writeObject(obj)
        │
        ▼
   ObjectOutputStream.writeObject0(obj)
        │
        ├─ ① 写 stream header（TC_OBJECT / TC_REFERENCE 等标记）
        ├─ ② 写 class 描述（首次遇到该类时）：类名 + 字段签名 + serialVersionUID
        ├─ ③ 反射遍历该类的所有 serializable 字段
        │     - 跳过 transient / static
        ├─ ④ 对每个字段：
        │     - 基本类型 → writeInt/writeLong/...（直接写字节）
        │     - 引用类型 → 递归 writeObject
        │     - 数组 → 写长度 + 逐元素
        └─ ⑤ 写 TC_ENDBLOCKDATA 结束标记

   读取时对称：
   readObject() → 读 header → 反射创建实例（Unsafe.allocateInstance）→ 反射设置字段
```

关键特征：

| 特征 | 说明 | 后果 |
| --- | --- | --- |
| **写 class 描述** | 每个类只写一次，后续用 `TC_REFERENCE` 引用 | 流**自描述**，读取端不需要类定义完全一致（但要兼容） |
| **反射** | `ObjectStreamClass` 缓存字段元数据 | 慢，且**无法 R8 裁剪**（需要 keep 规则） |
| **递归写对象图** | 循环引用靠 `TC_REFERENCE` 处理 | 深度递归 → **StackOverflowError** |
| **`serialVersionUID`** | 类的版本指纹 | **不匹配会抛 `InvalidClassException`** |
| **transient / static** | 静态字段被跳过；`transient` 字段被跳过 | transient 字段反序列化后是默认值 |
| **writeObject/readObject 自定义** | 可手动写附加逻辑 | 灵活性高 |

**Java 序列化的性能问题**：

1. **反射**：每个字段一次 `Field.get/set`（含装箱）。
2. **递归对象图**：一个简单对象可能牵连几十上百个对象。
3. **每次都写 class 描述**：文件头臃肿。
4. **`serialVersionUID` 校验**：类结构一变就可能不兼容。

> Android 从 **API 33（Android 13）** 起，**禁止通过 `Bundle`/`Intent` 传递实现 `Serializable` 的对象**（Google Play 的目标 API 要求）。这是 Android 官方力推 Parcelable 的直接证据。

### 3. Parcelable 的原理：writeToParcel / createFromParcel

```text
   发送方（App 进程 A）                    接收方（App 进程 B 或 SystemServer）
   ─────────────────────                   ─────────────────────────────────
   intent.putExtra("user", user)           
        │ Parcel parcel = Parcel.obtain()   
        ▼                                  
   parcel.writeParcelable(user, flags)     
        │                                   
        ├─ ① Parcel 申请一块共享内存缓冲区   
        │     （native: ashmem / mmap）     
        │                                   
        ├─ ② 调用 user.writeToParcel(parcel, flags) 
        │     → 按固定顺序写入各字段的字节   
        │                                   
        ├─ ③ Binder.transact() 把 [Parcel fd, 
        │     数据长度] 通过 ioctl 传给对端   
        │                                   
        │                              Binder 驱动收到 fd ──┐
        │                                                     ▼
        │                              对端 Parcel 从共享内存读取
        │                                     │
        │                                     ▼
        │                              user.CREATOR.createFromParcel(p)
        │                              → 按同样顺序读回字段
        ▼
   parcel.recycle()
```

**Parcelable 的接口定义**：

```java
public interface Parcelable {
    int describeContents();                 // 描述内容（0 或 PARCELABLE_CONTENTS_READ_RETURN_VALUE）
    void writeToParcel(Parcel dest, int flags);   // 序列化：写
    interface Creator<T> {
        T createFromParcel(Parcel source);        // 反序列化：读
        T[] newArray(int size);                   // 反射创建数组用
    }
    interface ClassLoaderCreator<T> extends Creator<T> {
        T createFromParcel(Parcel source, ClassLoader loader);  // 自定义 ClassLoader（热修复/插件化）
    }
}
```

**手写实现（理解原理必看）**：

```java
public class User implements Parcelable {

    public long id;
    public String name;
    public List<String> tags;

    protected User(Parcel in) {
        id = in.readLong();                    // ⚠️ 顺序必须与 writeToParcel 完全一致
        name = in.readString();
        tags = new ArrayList<>();
        in.readList(tags, String.class.getClassLoader());   // 复杂对象要传 ClassLoader
    }

    public static final Creator<User> CREATOR = new Creator<User>() {
        @Override public User createFromParcel(Parcel in) { return new User(in); }
        @Override public User[] newArray(int size) { return new User[size]; }
    };

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeString(name);
        dest.writeList(tags);
    }
}
```

**铁律**：
1. **`writeToParcel` 的写入顺序与 `createFromParcel` 的读取顺序必须严格一致**。
2. **两边代码要同时改** → 容易漏改 → 这就是 `@Parcelize` 存在的理由。
3. **字段新增要在末尾加**（放在中间会错位）→ 兼容性靠"只追加"。
4. **读对象要传 ClassLoader**：`in.readList(list, User.class.getClassLoader())`，否则在某些进程里会 `ClassNotFoundException`（因为系统进程的 ClassLoader 找不到 App 的类）。

### 4. Parcel 的底层：共享内存缓冲区，一次拷贝

这是 Parcelable **比 Serializable 快的核心**。

```text
   ┌────────────────────────────── 发送方进程 ──────────────────────────────┐
   │  Parcel.obtain()                                                    │
   │      │                                                              │
   │      ▼                                                              │
   │  native Parcel 分配一块共享内存（/dev/ashmem 或 memfd）              │
   │  ┌──────────────────────────────────────────┐                      │
   │  │  [Parcel Header: 数据长度/偏移/文件描述符]  │                      │
   │  ├──────────────────────────────────────────┤                      │
   │  │  序列化后的字节数据（用户对象）             │                      │
   │  ├──────────────────────────────────────────┤                      │
   │  │  File Descriptor 数组（嵌套的 Parcelable）  │ ← 另一个 Parcel    │
   │  └──────────────────────────────────────────┘   传 fd 引用         │
   └──────────────────────────────────────────────────────────────────────┘
                    │  Binder.transact(code, Parcel, flags, reply)
                    ▼
   ┌────────────────────────────── 接收方进程 ──────────────────────────────┐
   │  Binder 驱动收到 [fd, len] → mmap 这个共享内存 → 构造接收端 Parcel     │
   │      │                                                              │
   │      ▼                                                              │
   │  直接从共享内存读取字节 → createFromParcel()                          │
   │  ★ 全程只有「一次」数据拷贝：App 内存 → 共享内存                       │
   └──────────────────────────────────────────────────────────────────────┘
```

关键机制：

| 机制 | 说明 |
| --- | --- |
| **共享内存** | Parcel 的数据区是 **ashmem / memfd**，**跨进程可映射** |
| **一次拷贝** | App 内存 → 共享内存 **拷贝一次**；Binder 传输的只是 **fd + 长度**（几十字节） |
| **Binder 的 payload 限制** | 因为只传 fd，**Binder 缓冲区本身很小**；但共享内存可以很大 |
| **嵌套 Parcelable** | 嵌套对象是**另一个 Parcel**，通过 **FileDescriptor** 传递 → 接收端重建 |
| **Binder 驱动内存压力** | 共享内存页可以 **swap out**；Binder 事务缓冲（1MB）不能 → 这是设计上的折中 |

**Parcel 的 API 与底层**：

```java
// 常用写入
parcel.writeInt(x);          parcel.writeLong(x);
parcel.writeString(s);       // 实际是 writeString → writeValue
parcel.writeList(list, cl);  parcel.writeParcelable(obj, flags);
parcel.writeBundle(b);       // Bundle 内部也是一个 Parcel
parcel.writeTypedList(list); // 高效的 typed list（带类型标记）
parcel.writeMap(map, cl);
parcel.writeParcelableArray(arr, flags);

// 读取
int x = parcel.readInt();
String s = parcel.readString();
parcel.readList(list, cl);
parcel.readParcelable(cl);
Bundle b = parcel.readBundle(cl);
```

> ⚠️ **Parcel 的"隐藏 API"陷阱**：`writeValue()` / `readValue()`（`writeValue` 实际上是 `Parcel.writeValue`）在 API 16 之前是隐藏的，官方不推荐使用。`readValue(ClassLoader)` 有性能与兼容问题。

### 5. Kotlin @Parcelize 插件原理

`@Parcelize` 是 **Kotlin 编译器插件**（不是 APT），它在**编译期生成 `writeToParcel`、`createFromParcel`、`CREATOR`**。

```kotlin
// plugins { kotlin("plugin.parcelize") version "2.0.0" }

@Parcelize
data class User(
    val id: Long,
    val name: String,
    val tags: List<String>,
    val avatar: Bitmap?          // 需要 @RawValue 或自定义
) : Parcelable
```

**插件的工作流程**：

```text
   编译期（Kotlin 编译器插件 kotlin-parcelize）
   ┌───────────────────────────────────────────────────────────┐
   │ 1. 扫描 @Parcelize 标注的类                                 │
   │ 2. 读取主构造函数的参数列表（按声明顺序）                    │
   │ 3. 为主构造函数参数各生成一个 Parceler<T>：                │
   │    - 基本类型 → 内部生成的 Parceler 直接 write/read         │
   │    - String/枚举/Bundle 等 → Parcel 内置方法               │
   │    - Parcelable → writeParcelable / readParcelable        │
   │    - Serializable → 退化处理（不推荐）                      │
   │    - List/Map → 需要 @Parcelize 的元素可序列化              │
   │ 4. 生成 writeToParcel: 按参数顺序依次 parceler.write(...)  │
   │ 5. 生成 createFromParcel: 按相同顺序依次 parceler.read(...)│
   │ 6. 生成 CREATOR / ClassLoaderCreator                      │
   └───────────────────────────────────────────────────────────┘
```

**扩展函数形式**（新版写法）：

```kotlin
@Parcelize
data class User(
    val id: Long,
    val name: String,
    val tags: List<String>
) : Parcelable

// 插件生成的是 typealias 风格扩展：
// fun User.writeToParcel(dest: Parcel, flags: Int) { ... }
// fun User.Companion.createFromParcel(source: Parcel): User { ... }
```

**自定义 Parceler（处理特殊类型）**：

```kotlin
// 自定义 Parceler：把 Date 写成 long
object DateParceler : Parceler<Date> {
    override fun create(parcel: Parcel): Date = Date(parcel.readLong())
    override fun Date.write(parcel: Parcel, flags: Int) {
        parcel.writeLong(time)
    }
}

// 自定义 Parceler：Bitmap 很特殊，要用 @RawValue
@Parcelize
data class Photo(
    @RawValue val bitmap: Bitmap       // 标记为"直接走 Parcel 的特殊类型"
) : Parcelable
```

| 场景 | 处理 |
| --- | --- |
| `Bitmap` / `ParcelFileDescriptor` | 用 `@RawValue` |
| `List<T>` / `Map<K,V>` | 元素类型必须本身可 Parcelize |
| `enum` | ✅ 自动支持 |
| `Bundle` | ✅ 自动支持 |
| `Serializable` 的类 | ⚠️ 会退化（走 `writeSerializable`），不推荐 |
| `sealed class` | ❌ 不支持，需手动处理 |
| `lateinit` / 计算属性 | 只序列化**主构造参数** |

> ⚠️ **常见坑**：`@Parcelize` **只处理主构造函数的参数**。写在类体里的普通属性（`var extra: String = ""`）**不会被序列化** → 反序列化后是默认值。

### 6. Bundle 与 Parcel 的关系

```text
   Intent
     │
     ├─ extras: Bundle
     │      │
     │      └─ 内部持有一个 Parcel（mParc）
     │           └─ putParcelable(key, value) → value.writeToParcel(mParc)
     │              putString(key, value)    → mParc.writeString(value)
     │              putInt(key, value)       → mParc.writeInt(value)
     └─ 传递时：整个 Bundle 被"扁平化"成一个 Parcel 再跨进程
```

- `Bundle` 是**惰性**的：`putXxx` 只是**记录一个 action**（`mMap.put(key, action)`），**真正写进 Parcel 是在 `writeToParcel` / `writeBundle` 时**。
- 读取时 `getXxx` 会**触发 parcel 的反序列化**（第一次取值时 parse）。
- `BaseBundle` 内部就是 `mMap<String, Object>` + 一个 `Parcel`。

```kotlin
// Bundle 的惰性求值
val bundle = Bundle()
bundle.putString("key", "value")
// 此时还没写进 Parcel
bundle.putParcelable("user", user)
```

### 7. TransactionTooLargeException：Binder 的 1MB 限制

这是 Parcelable 面试的**必考**点。

```text
   Binder 事务缓冲区（内核里的 binder buffer）
   ┌────────────────────────────────────────────┐
   │  binder_buffer（每个进程约 1MB，映射在内核）  │
   │  ┌──────────────────────────────────────┐  │
   │  │  事务头 (binder_transaction)            │  │
   │  │  ┌────────────────────────────────┐  │  │
   │  │  │  Parcel 数据（扁平化后的字节）    │  │  │
   │  │  └────────────────────────────────┘  │  │
   │  │  + 可能的 FD 数组                        │  │
   │  └──────────────────────────────────────┘  │
   └────────────────────────────────────────────┘

   ★ 限制：单次 Binder 事务的 payload 不能超过 ~1MB
     超出 → TransactionTooLargeException
```

**为什么 Parcelable 也会有大小限制？**

| 场景 | 数据走哪 | 是否受 1MB 限制 |
| --- | --- | --- |
| **同进程内** `Intent.setExtra` | 直接传对象引用（不序列化） | ❌ **不受限** |
| **跨进程** `Intent`（Activity/Service/Broadcast） | 跨进程时要序列化到 Parcel → 走 Binder | ⚠️ **受限于共享内存与 Binder 缓冲** |
| **`startActivity` 同进程** | 一般不序列化 | 不受限 |
| **`ContentProvider` 跨进程查询大量数据** | Cursor 的 `BulkCursor` → 走 Binder | ⚠️ 有限制 |
| **`onTransact` 里直接写大量字节** | `reply.writeXXX` 大量数据 | ✅ **受 1MB 限制** |

> 实际上 Parcel 使用**共享内存**传大数据，所以单纯的 `putParcelable` 大对象**可能不触发 1MB 限制**（共享内存可以很大）。但以下场景会真的触发：
> 1. **`Parcel.writeXXX` 写大量原始字节**（比如 `writeBlob`、`writeIntArray` 大数组）→ 进 Binder 缓冲 → 超限。
> 2. **`Bundle` 里塞大 String** → 进 Binder 缓冲。
> 3. **跨进程 API（如 `ContentProvider`、`ActivityManager` 的返回值）**返回大数组。
> 4. **Binder 事务的 payload 超过 1MB**（无论是数据还是 FD 数组）。

```text
   典型崩溃堆栈：
   android.os.TransactionTooLargeException: data parcel size 2147483647 bytes
       at android.os.Parcel.writeArrayMapInternal(Parcel.java:3396)
       at android.os.Parcel.writeBundleInternal(Parcel.java:869)
       at android.os.Parcel.writeBundle(Parcel.java:2390)
       ...
   原因：Bundle 里塞了超大 Bitmap / 大 List / 大 String，
        跨进程传输时超过 Binder 1MB 限制。
```

**解决方案**：

| 方案 | 做法 |
| --- | --- |
| **传 ID 不传对象** | ✅ 最佳：传 `userId`，接收方自己查数据库 |
| **传文件路径 / URI** | 大数据落文件，传路径（见 [[../本地存储/文件存储]]） |
| **用 `FileProvider` 的 content URI** | 接收方通过 ContentProvider 流式读取，**不占 Binder 缓冲** |
| **拆分成多次事务** | 分页/分片传输 |
| **压缩** | 先压缩再传（但压缩后仍是二进制进 Binder，收益有限） |
| **`ParcelFileDescriptor`** | 用 **Ashmem / 内存文件** 传大数据（`ParcelFileDescriptor.createPipe()` 或 `ashmem`），**走 FD 不走 payload** |

### 8. 性能对比

```text
   序列化一个含 20 个字段的对象（典型 Model）
   ┌──────────────────┬──────────────┬───────────────┐
   │ 方案              │ 相对耗时      │ 说明           │
   ├──────────────────┼──────────────┼───────────────┤
   │ Parcelable       │ 1x (基准)     │ 手写/生成代码  │
   │ @Parcelize       │ ≈1x           │ 编译期生成      │
   │ Serializable     │ ~5x - 20x     │ 反射 + 对象图   │
   │ JSON (Gson)      │ ~3x - 10x     │ 词法分析        │
   │ JSON (kotlinx)   │ ~2x - 5x      │ 生成代码        │
   │ ProtoBuf         │ ~1x - 3x      │ 二进制          │
   └──────────────────┴──────────────┴───────────────┘
   ★ 数量级示意，实际取决于对象复杂度与设备
```

**Parcelable vs Serializable 差距为什么这么大**：

| 因素 | Serializable | Parcelable |
| --- | --- | --- |
| 反射写字段 | 每字段一次 `Field.set` | 直接 `dest.writeLong()` |
| 对象图 | 递归遍历整个引用图 | **只写你指定的字段** |
| 类描述 | 首次写入 class 元数据 | **完全不需要** |
| 装箱 | 大量装箱 | `writeLong` 无装箱 |
| 内存分配 | 创建 `ObjectStreamClass` + 临时缓冲 | 复用 Parcel 缓冲区 |

> ⚠️ **注意**：Serializable 的"递归对象图"是它最大的坑——一个 `User` 里有个 `List<Order>`，每个 `Order` 里有 `List<Item>`，序列化一个 User 可能连带序列化**上千个对象**，而 Parcelable 里你只写 `userId`。

### 9. Parcelable 的适用边界

| 场景 | 推荐 |
| --- | --- |
| Intent 传对象（同进程） | Parcelable（也可直接传引用） |
| Intent 传对象（跨进程） | Parcelable，**且控制大小** |
| `onSaveInstanceState` 存状态 | Parcelable 或基本类型 |
| `SavedStateHandle` | 基本类型 + Parcelable |
| 持久化到磁盘 | ❌ 用 **JSON / ProtoBuf**（有版本兼容、可读） |
| 网络传输 | ❌ 用 **JSON / ProtoBuf**（跨语言） |
| 跨进程长期共享 | ❌ 用 **ContentProvider + 数据库**（有事务、有查询） |
| `Bundle` 存状态 | ✅ Parcelable |

> **Parcelable 没有版本兼容机制**：你改了字段顺序，老版本进程反序列化会**读出乱数据**（不是抛异常，是**静默错误**）→ 所以 Parcelable **只能用于"发送方和接收方版本基本同步"的场景**（同一次 App 运行内的进程间通信）。

## 代码示例

### 1. 手写 Parcelable（理解原理）

```java
public class User implements Parcelable {

    public long id;
    public String name;
    public int age;
    public List<String> tags;

    // 1. 反序列化构造：必须与 writeToParcel 的写入顺序严格一致
    protected User(Parcel in) {
        id = in.readLong();
        name = in.readString();
        age = in.readInt();
        tags = new ArrayList<>();
        // ⚠️ 读取集合必须传 ClassLoader，否则在别的进程会 ClassNotFoundException
        in.readList(tags, String.class.getClassLoader());
    }

    // 2. CREATOR：框架通过它创建实例
    public static final Creator<User> CREATOR = new Creator<User>() {
        @Override
        public User createFromParcel(Parcel in) {
            return new User(in);
        }

        @Override
        public User[] newArray(int size) {
            return new User[size];
        }
    };

    // 3. 序列化：写入顺序必须与构造器读取顺序一致
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeString(name);
        dest.writeInt(age);
        dest.writeList(tags);
    }

    @Override
    public int describeContents() {
        return 0;   // 一般返回 0
    }
}
```

```java
// 4. 使用：跨进程传对象
Intent intent = new Intent(this, DetailActivity.class);
intent.putExtra("user", user);          // Parcelable
intent.putExtra("user_list", userList);  // Parcelable 列表
startActivity(intent);

// 接收
val user = intent.getParcelableExtra<User>("user")
```

### 2. Kotlin @Parcelize（推荐写法）

```kotlin
// build.gradle.kts
// plugins { kotlin("plugin.parcelize") version "2.0.0" }

@Parcelize
data class User(
    val id: Long,
    val name: String,
    val age: Int,
    val tags: List<String> = emptyList(),
    val profile: Profile? = null
) : Parcelable

@Parcelize
data class Profile(
    val city: String,
    val signature: String?
) : Parcelable

// ---------- 使用 ----------
val intent = Intent(this, DetailActivity::class.java).apply {
    putExtra(EXTRA_USER, user)                       // Parcelable
    putExtra(EXTRA_IDS, longArrayOf(1L, 2L, 3L))    // 基本类型数组
}

// 接收（API 33+ 推荐用 class loader 参数的重载）
val user = intent.getParcelableExtra(EXTRA_USER, User::class.java)
val ids = intent.getLongArrayExtra(EXTRA_IDS)
```

### 3. 自定义 Parceler：处理 Bitmap、Date 等特殊类型

```kotlin
// ---------- Bitmap：用 @RawValue ----------
@Parcelize
data class Photo(
    @RawValue val bitmap: Bitmap
) : Parcelable

// ---------- Date：自定义 Parceler ----------
object DateParceler : Parceler<Date> {
    override fun create(parcel: Parcel): Date = Date(parcel.readLong())
    override fun Date.write(parcel: Parcel, flags: Int) {
        parcel.writeLong(time)
    }
}

@Parcelize
data class Order(
    val id: Long,
    @Parcelize(with = DateParceler::class) val createdAt: Date
) : Parcelable
```

### 4. 避免 TransactionTooLargeException

```kotlin
// ❌ 错误：直接传大对象，跨进程时可能超过 Binder 1MB
intent.putExtra("bitmap", hugeBitmap)
intent.putExtra("list", thousandsOfItems)

// ✅ 正确 1：传 ID，接收方自己查
intent.putExtra("user_id", user.id)

// ✅ 正确 2：大数据落文件，传 URI（配合 FileProvider）
val file = File(cacheDir, "export.json")
file.writeText(jsonString)
val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
intent.putExtra("file_uri", uri)

// ✅ 正确 3：用 ParcelFileDescriptor 传大数据（走 FD，不占 Binder payload）
val pfd = ParcelFileDescriptor.createPipe()
Thread {
    ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
        out.write(bigData)
    }
}.start()
intent.putExtra("data_fd", pfd)

// ✅ 正确 4：分片传输（大列表分页）
intent.putExtra("page", 0)
```

### 5. 自定义 Parcelable 的"版本兼容"实践

```java
public class User implements Parcelable {
    public long id;
    public String name;
    // 新增字段必须追加在末尾，并且在读取时容错
    public String nickname;   // v2 新增

    protected User(Parcel in) {
        id = in.readLong();
        name = in.readString();
        // 容错：Parcel 里可能没有这段数据（老版本写入的数据）
        // Parcel.readString() 越界会抛异常，这里用 available 检查
        nickname = in.dataAvail() > 0 ? in.readString() : null;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeString(name);
        dest.writeString(nickname);   // 追加在末尾
    }
}
```

> ⚠️ 这个"容错"技巧只在**同进程内**的版本不一致场景有意义（如热修复），跨进程不同版本 App 通信时**不要依赖它**，因为两边代码顺序不同会直接错位。**跨版本通信应该用带 schema 的 JSON/ProtoBuf。**

### 6. Serializable 的基本用法（对照理解）

```kotlin
data class User(
    val id: Long,
    val name: String,
    @Transient val cache: Any? = null   // transient 字段不会被序列化
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L   // 显式声明版本
    }
}

// 序列化到文件
val fos = FileOutputStream(File(cacheDir, "user.bin"))
ObjectOutputStream(fos).use { it.writeObject(user) }

// 反序列化
val ois = ObjectInputStream(FileInputStream(File(cacheDir, "user.bin")))
val restored = ois.use { it.readObject() as User }
```

```text
   ⚠️ Serializable 在 Android 上的问题：
   - Android 13 (API 33) 起，通过 Bundle/Intent 传 Serializable 对象被 Google Play 目标 API 要求禁止
   - 反射 → R8 混淆后字段名变化会导致反序列化失败（需 keep 规则）
   - 递归对象图 → StackOverflowError / 性能灾难
   - 无版本兼容 → 改字段后 InvalidClassException
```

## 常见问题 / 面试要点

**读者问：Parcelable 和 Serializable 性能差多少？为什么？**

**AI答：**

1. **量级**：一般说 **Serializable 比 Parcelable 慢 5~20 倍**，但更准确的说法是"**慢一个数量级级别**"，取决于对象复杂度。
2. **三个根本原因**：

   | 原因 | Serializable | Parcelable |
   | --- | --- | --- |
   | **反射** | 每个字段一次 `Field.get/set`（含装箱） | 直接 `dest.writeLong()`，**零反射** |
   | **对象图** | **递归遍历整个引用图** | **只写你手写的字段** |
   | **元数据** | 首次要写 class 描述、读时要反射建对象 | **无任何元数据** |

3. **最容易被忽略的一点**：**Serializable 的"递归对象图"才是大头**。
   - 一个 `User` 有 `List<Order>`，每个 `Order` 有 `List<Item>` → 序列化一个 User 可能连带序列化**几千个对象**。
   - Parcelable 里你只写 `writeLong(id)` + `writeString(name)` → **性能与对象图大小无关**。
   - 这一点在**面试中说出**会显著加分。
4. **实测方法**（面试可能让你现场分析）：
   ```kotlin
   val start = System.nanoTime()
   repeat(1000) { parcel.writeParcelable(user, 0) }
   val parcelTime = (System.nanoTime() - start) / 1_000_000
   ```
   同样用 `ByteArrayOutputStream` + `ObjectOutputStream` 测 Serializable，对比即可。
5. **面试话术**：
   > "差距主要来自三点：**反射写字段**、**递归序列化整个对象图**、**额外的 class 元数据**。最关键的是第二点——Parcelable 只处理你显式写的那几个字段，性能与对象复杂度无关；Serializable 则会把整棵引用树都写进去。Android 13 起 Google Play 甚至禁止通过 Bundle 传 Serializable 对象，等于官方表态 Parcelable 是唯一正解。"

---

**读者问：Parcelable 的底层原理是什么？为什么说它"只拷贝一次"？**

**AI答：**

1. **Parcel 的本质**：Android 的 `Parcel` 在 native 层对应 **`Parcel`（native class）**，它的数据区是**一块共享内存**（早期 `/dev/ashmem`，现在 `memfd`），通过 `mmap` 映射到进程地址空间。
2. **完整链路**：

   ```text
   发送进程                                    接收进程
   ─────────                                  ─────────
   Parcel.obtain()
     → native 分配共享内存 (memfd/ashmem)
   user.writeToParcel(parcel, 0)
     → 把字节写进共享内存                ← ① 唯一的一次拷贝
   parcel.writeToParcel / writeBundle
     → Binder.transact()
        ├─ payload: [fd, length, offsets]  ← ② 只传 fd 和元数据（几十字节）
        └─ Binder 驱动把目标进程的 binder 缓冲 mmap 到发送进程的共享内存
   ──────────────────────────────────────────────────────────────
   接收端：Binder 收到 fd → mmap → createFromParcel 直接读
   ```

3. **"一次拷贝"的准确含义**：
   - **App 内存 → 共享内存**：1 次 `memcpy`（由 `Parcel.write*` 完成）。
   - **共享内存 → 接收端**：**0 次拷贝**（因为是 **mmap 映射**，接收端直接读写同一块物理页）。
   - **Binder 传输的内容**：只是 **fd 编号 + 偏移 + 长度**（几十字节），**不是数据本身**。
4. **对比 Serializable**：
   - `ObjectOutputStream` 写到 `ByteArrayOutputStream` → 再 `toByteArray()` → **至少 2 次拷贝**（写缓冲 + 取数组）。
   - 若是文件流 → **数据要从用户态拷到内核页缓存**，又是 1 次拷贝。
   - 所以 Parcelable 至少省掉 1~2 次全量拷贝。
5. **嵌套 Parcelable 的处理**：
   - 嵌套对象是**另一个 Parcel**（另一个共享内存块），在父 Parcel 中**只记录一个 FileDescriptor**。
   - 跨进程时 FD 数组通过 Binder 传递 → 接收端 `dup` 出新 FD → 重建嵌套 Parcel。
6. **面试话术**：
   > "Parcel 的数据区是一块 **mmap 共享内存**。序列化时 App 把字节写进这块内存——这是**唯一一次拷贝**；Binder 事务里只传 **fd + 长度**，接收端 mmap 同一块内存直接读，**零拷贝**。所以 Parcelable 的开销主要是这一次 memcpy，而 Serializable 至少要写缓冲再取数组，**外加反射和整个对象图的遍历**。"

---

**读者问：Binder 传对象有 1MB 限制吗？Parcelable 传大对象会怎样？**

**AI答：**

1. **限制的准确表述**：
   - **Binder 的事务缓冲区**（`binder_buffer`）每个进程映射约 **1MB**，单次事务的 **payload（数据 + FD 数组）不能超过这个上限**。
   - 超出 → `android.os.TransactionTooLargeException`（服务端是 `TransactionTooLargeException`，客户端可能是 `RemoteException`）。
2. **Parcelable 是否受这个限制？—— 取决于数据走哪条路**：

   | 场景 | 数据路径 | 受 1MB 限制？ |
   | --- | --- | --- |
   | **同进程内** `putExtra` | 直接传对象引用，**根本不序列化** | ❌ 完全不受限 |
   | **跨进程 Intent**（大 Parcelable） | 数据在**共享内存**，Binder 只传 fd | ⚠️ **通常不受**（共享内存可以 > 1MB），但 **Bundle 里的原始字节**（大 String、大 int 数组、`writeBlob`）走 Binder payload → **受** |
   | **`onTransact` 里 `reply.writeXXX` 大量数据** | 直接写进 Binder 缓冲 | ✅ **受** |
   | **`ContentProvider` 返回大 Cursor** | `BulkCursor` 的数据走 Binder | ✅ 受（除非用 `ParcelFileDescriptor`） |
   | **`startActivity` 传 `Bundle`** | Bundle 会**扁平化**成 Parcel 写进 Binder | ✅ 受 |

   > ⚠️ **常见误解**："Parcelable 有共享内存所以没有大小限制" —— **不准确**。**一旦内容需要真正写进 Binder 事务缓冲区**（大 String、大数组、嵌套 Bundle 的原始数据），1MB 限制立刻生效。
3. **典型崩溃**：

   ```text
   android.os.TransactionTooLargeException: data parcel size 2147483647 bytes
       at android.os.Parcel.writeArrayMapInternal(Parcel.java:3396)
       at android.os.Parcel.writeBundleInternal(Parcel.java:869)
       at android.os.Parcel.writeBundle(Parcel.java:2390)
       at android.os.BaseBundle.writeToParcelInner(BaseBundle.java:320)
       at android.content.Intent.writeToParcel(Intent.java:1044)
   ```

   常见诱因：**Intent 里塞了大 Bitmap**（Bitmap 的 `writeToParcel` 在某些路径下会内联像素数据）、**大 List、大 String、嵌套 Bundle**。
4. **解决方案（按优先级）**：

   | 方案 | 做法 | 适用 |
   | --- | --- | --- |
   | ✅ **传 ID 不传对象** | `putExtra("user_id", id)`，接收方查库 | **首选** |
   | ✅ **落文件 + FileProvider** | 大数据写内部存储，传 `content://` URI，接收方用 `openInputStream` 流式读 | 大文件、图片 |
   | ✅ **`ParcelFileDescriptor` + 管道** | `createPipe()` → 写入线程写 → 接收方从 FD 读 | 大字节流，**完全绕开 Binder payload** |
   | ✅ **分片/分页** | 多页多次传输 | 列表 |
   | ⚠️ 压缩 | 压缩后体积小 | 只对文本有效，且增加 CPU |

5. **面试话术**：
   > "Binder 的单次事务 payload 限制是 **1MB**，超了就是 `TransactionTooLargeException`。但要注意：**Parcelable 的数据走共享内存，Binder 里只传 fd**，所以单纯的 `putParcelable` 大对象通常不触发；**真正触发的是需要写进 Binder payload 的东西**——大 String、大 int 数组、大 Bundle。我的处理原则是：**能传 ID 就传 ID，大数据落文件走 FileProvider 用 FD 传，绝不把大对象塞进 Intent。**"

---

**读者问：`@Parcelize` 的原理是什么？为什么它比手写 Parcelable 更可靠？**

**AI答：**

1. **本质**：它是 **Kotlin 编译器插件**（`kotlin-parcelize`），在**编译期**为 `@Parcelize` 类生成 `writeToParcel` / `createFromParcel` / `CREATOR`。
2. **工作流程**：

   ```text
   1. 插件扫描 @Parcelize 标注的类
   2. 读取主构造函数的参数列表（按声明顺序）
   3. 为每个参数类型匹配/生成一个 Parceler<T>：
      - Int/Long/String/Boolean 等 → 内部 Parceler（writeInt/readInt...）
      - enum → writeSerializable? 不，是 writeInt（ordinal/name）
      - Parcelable → writeParcelable/readParcelable
      - List<T> → writeList/readList（元素必须 Parcelable）
      - Map<K,V> → writeMap/readMap
      - Bundle → writeBundle/readBundle
      - 其他 → 报错（编译期）
   4. 生成 writeToParcel：按参数顺序 parceler.write(dest, value, flags)
   5. 生成 createFromParcel：按相同顺序 parceler.read(source)
   6. 生成 CREATOR 与 ClassLoaderCreator
   ```
3. **比手写更可靠的三个点**：
   - **顺序自动一致**：读写由**同一份参数列表**生成，**不可能错位**（手写最常见的 bug 就是加了字段只改了一边）。
   - **类型检查前置**：字段类型不支持 → **编译期报错**，而不是运行时 `ClassCastException`。
   - **集合类型自动推导**：手写 `readList(list, cl)` 常常忘记传 ClassLoader → 跨进程 `ClassNotFoundException`；插件生成的代码会带上正确的 ClassLoader。
4. **限制与坑**：

   | 限制 | 说明 |
   | --- | --- |
   | **只处理主构造参数** | 类体里的普通属性**不会被序列化** → 反序列化后是默认值 |
   | **`lateinit` 属性不支持** | 必须放在主构造函数 |
   | **sealed class 不支持** | 需要自己实现 |
   | **`Serializable` 字段会退化** | 插件会用 `writeSerializable`，不推荐 |
   | **`Bitmap` 需要 `@RawValue`** | 或自定义 Parceler |
   | **插件版本必须匹配 Kotlin 版本** | 否则编译失败 |

5. **自定义 Parceler**（插件支持的扩展点）：
   ```kotlin
   object DateParceler : Parceler<Date> {
       override fun create(parcel: Parcel): Date = Date(parcel.readLong())
       override fun Date.write(parcel: Parcel, flags: Int) { parcel.writeLong(time) }
   }
   ```
6. **面试话术**：
   > "`@Parcelize` 是 Kotlin 编译器插件，编译期为类生成 `writeToParcel`/`createFromParcel`。它的价值不在于'省几行代码'，而在于**读写顺序由同一份构造参数列表生成，不可能错位**——这正是手写 Parcelable 最大的 bug 来源。而且类型不支持会在编译期报错。"

---

**读者问：Bundle 是什么？它和 Parcel 是什么关系？Intent 传数据是怎么走 Parcel 的？**

**AI答：**

1. **Bundle 的本质**：`Bundle` 内部持有 `mMap<String, Object>` + **一个 Parcel（`mParc`）**。它是一个**惰性容器**。
2. **惰性求值**：

   ```kotlin
   val bundle = Bundle()
   bundle.putString("k", "v")
   // 此刻：只往 mMap 里放了一个 "action"（Parcelable.Action），还没写进 Parcel
   bundle.putParcelable("u", user)
   // 同样只是记录 action

   // 真正序列化发生在：
   bundle.writeToParcel(parcel, flags)  // 内部遍历 mMap，执行每个 action
   ```
   → `Bundle` **是"延迟执行的序列化"**：只有真正要跨进程时，字段才被写入字节流。
3. **读取时也是惰性的**：`bundle.getString("k")` → 内部发现 Parcel 还没反序列化 → **先 `readFromParcel` 整个 Bundle**，再从 `mMap` 取值。
4. **Intent 传数据的完整链路**：

   ```text
   intent.putExtra("user", user)
        │
        ▼
   ActivityThread.handleLaunchActivity
        │ 收集 intent → 传给 AMS
        ▼
   Binder.transact(START_ACTIVITY)
        │  Intent 被 writeToParcel → mParc（Intent 自己的 Parcel）
        │  Intent.extras（Bundle）被 writeBundle → 扁平化进同一个 Parcel
        ▼
   AMS (system_server) 收到 → 解析 Intent → 找到目标进程
        ▼
   目标进程 ActivityThread → 重建 Intent → 重新解析 Bundle
        ▼
   你在 onCreate 里 getParcelableExtra("user") → 反序列化
   ```

   **注意**：`Intent` 的 `mExtras` 在 `Intent.writeToParcel` 时会**把 Bundle 扁平化**（`Bundle.writeToParcelInner` 把 `mMap` 里所有 action 执行写入 `mParc`）→ **Bundle 里的原始字节数据就真的进了 Binder payload → 受 1MB 限制**。
5. **Bundle 的性能陷阱**：
   - **同一个 Intent 里反复 `putExtra` 大对象** → Bundle 会在真正传输时才序列化 → **崩溃点在传输时，不在 put 时** → 难排查。
   - **`getXxx` 每次调用都走 `mMap`**，第一次会触发整体反序列化 → **不要在循环里频繁取**。
6. **面试话术**：
   > "Bundle 内部就是一个 `Map` 加一个 `Parcel`，而且是**惰性的**：`putXxx` 只是记录一个 action，真正写进字节流是在跨进程传输那一刻。这也意味着**大对象的崩溃点不在 put 的时候，而在传输的时候**——排查这类问题要记住这一点。另外 Bundle 扁平化后原始数据是要进 Binder payload 的，所以 1MB 限制对它是实打实的。"

---

**读者问：Parcelable 有版本兼容性吗？改了字段顺序会怎样？**

**AI答：**

1. **Parcelable 没有版本兼容机制**——这是它和 JSON/ProtoBuf 最大的差别。
2. **改了字段顺序的后果**：
   - **同进程、同版本**：无影响。
   - **跨进程、不同版本**（如 App 升级过程中旧进程还在后台，新 Activity 启动新进程）：**静默读出乱数据**。因为 `readLong()` 会读到原本属于 `name` 的字节 → **不抛异常，直接是错的**。
3. **Parcelable 的适用前提**：
   > Parcelable 适合"**同一次 App 运行内**"的进程间通信（Activity ↔ Service、Fragment ↔ Activity），此时两端代码必然是同一版本。
4. **需要跨版本兼容的场景 → 用什么**：

   | 场景 | 方案 |
   | --- | --- |
   | 跨版本进程间通信 | **JSON / ProtoBuf**（有 schema、有兼容规则） |
   | 持久化到磁盘 | **JSON / ProtoBuf / Room** |
   | 网络传输 | **JSON / ProtoBuf**（还要跨语言） |
   | 跨进程共享可查询数据 | **ContentProvider + 数据库**（有事务、有查询） |

5. **常见的"半个兼容"技巧及其局限**：
   - **在末尾追加字段 + `dataAvail()` 判断**：
     ```java
     protected User(Parcel in) {
         id = in.readLong();
         name = in.readString();
         // 老版本写的数据没有这一段
         nickname = in.dataAvail() > 0 ? in.readString() : null;
     }
     ```
     ⚠️ 这只能应对"**新代码读旧数据**"；"**旧代码读新数据**"仍然会错位（旧代码读完了新代码写的 nickname 字节，多出来的字节被忽略 → **恰好能用**）。
     ⚠️ 但**改变已有字段的顺序/类型依然会坏**。
   - **结论**：这是**缓解手段，不是方案**。真要跨版本兼容 → 用带 schema 的格式。
6. **面试话术**：
   > "Parcelable **没有版本兼容机制**——它只保证'同一次运行内'的进程间通信，因为两端必然是同一份代码。字段顺序改了，旧进程读到的是**静默的乱数据**，不报错。所以只要涉及**跨版本或持久化**，我就换 JSON 或 ProtoBuf，它们有 schema、有明确的兼容规则。"

---

**读者问：什么场景该用 Parcelable，什么场景该用 JSON / ProtoBuf？**

**AI答：**

1. **决策表**：

   | 场景 | 方案 | 理由 |
   | --- | --- | --- |
   | `Intent` 传对象（同进程） | Parcelable（也可直接引用） | 零拷贝、快 |
   | `Intent` 传对象（跨进程） | **Parcelable + 控制大小** | 系统标准做法 |
   | `onSaveInstanceState` | Parcelable / 基本类型 | 框架要求 |
   | `SavedStateHandle` | 基本类型 + Parcelable | 框架要求 |
   | `Bundle` 传数据 | Parcelable | 同上 |
   | **持久化到磁盘** | **JSON / ProtoBuf** | 要版本兼容、要可读 |
   | **网络传输** | **JSON / ProtoBuf** | 要跨语言、要可调试 |
   | **跨进程共享可查询数据** | **ContentProvider + Room** | 要事务、要查询能力 |
   | **配置项** | **DataStore**（Proto DataStore 底层就是 ProtoBuf） | 类型安全 + 原子写 |
   | **热修复/插件化** | 自定义 `ClassLoaderCreator` | 支持动态 ClassLoader |

2. **本质区别**：
   - **Parcelable = 进程内"打包协议"**：快、但**绑定具体类结构**、**无 schema**。
   - **JSON / ProtoBuf = 序列化格式**：慢一些、但**有 schema**、**有兼容规则**、**跨语言**、**可调试**。
3. **一个实践建议**：
   > **跨越"版本边界"就用 JSON/ProtoBuf，跨越"进程边界但同版本"就用 Parcelable。**
4. **面试话术**：
   > "我的判断标准是**边界**：只在进程内传就用 Parcelable；一旦要**落盘、要上网、要跨版本、要跨语言**，就换成 JSON 或 ProtoBuf，因为那三个场景的核心诉求是**兼容性和可调试性**，而那正是 Parcelable 缺的。"

---

**读者问：Java 的 `Serializable` 到底做了什么？为什么说它"慢"？能否自己实现一个更快的？**

**AI答：**

1. **`ObjectOutputStream` 的完整流程**：

   ```text
   writeObject(obj)
     ① 写 TC_OBJECT 标记
     ② 首次遇到该类 → 写 class 描述：
        - className（UTF）
        - serialVersionUID
        - flags（SC_SERIALIZABLE / SC_WRITE_METHOD / SC_EXTERNALIZABLE）
        - 所有非 transient 非 static 字段的签名（类型 + 名字）
     ③ 反射遍历字段（ObjectStreamClass 缓存了 Field[]）
     ④ 逐字段写：
        - 基本类型 → writeInt/writeLong...
        - 引用类型 → 递归 writeObject
        - 数组 → 写长度 + 元素
        - transient → 跳过
     ⑤ 写 TC_ENDBLOCKDATA
   ```
2. **慢在哪**：
   - **反射读写**（`Field.get` / `Field.set`）+ **装箱**。
   - **递归整个对象图**（这是最大的问题）。
   - **每次写 class 描述**（首次）。
   - **`ObjectStreamClass` 的查表**。
   - **不可读的中间缓冲**（写进 `BlockDataOutputStream`，再拷到目标流）。
3. **能不能更快？—— 能，但方向不同**：
   - **Kryo**（Java 库）：用**缓存的 FieldAccessor + 生成字节码**，通常**比 Java 原生 Serializable 快 2~3 倍**，支持"引用/循环"处理。缺点：Android 上体积较大。
   - **手写序列化**（类似 Parcelable 的思路）：最快，但要自己维护。
   - **Fresco/Okio 的 `BufferedSink`**：只是 IO 层优化，不改序列化机制。
   - **ProtoBuf / FlatBuffers**：本质是"生成代码 + 二进制"，走的是"编译期确定布局"的路。
4. **Android 上的额外问题**：
   - **API 33+ 起，Google Play 目标 API 要求禁止通过 Bundle/Intent 传 `Serializable` 对象**。
   - **R8 混淆**：`Serializable` 需要 keep 规则（`-keepclassmembers class * implements java.io.Serializable { ... }`），否则字段名变了 → 反序列化得到 null。
   - **`serialVersionUID`**：改类结构后 UID 不匹配 → `InvalidClassException`。
   - **StackOverflowError**：深层对象图递归序列化。
5. **面试话术**：
   > "Java `Serializable` 慢在三点：**反射读写字段**、**递归遍历整个对象图**、**额外的 class 元数据**。第三条 Android 已经用政策否掉了——API 33 起 Play 要求禁止在 Bundle/Intent 里传 Serializable。如果一定要在 JVM 上做得更快，可以用 Kryo；但在 Android 上我的选择是**直接用 Parcelable**，没有中间层。"

## 关联笔记

- [[../README]]：第 4 部分总览与序列化选型主线
- [[../数据格式/JSON与ProtoBuf]]：跨版本、跨语言、持久化场景的替代方案
- [[../本地存储/文件存储]]：大数据落文件 + FileProvider 传 URI，绕开 Binder 1MB 限制
- [[../数据库/SQLite与Room]]：跨进程共享可查询数据的正确姿势
- [[../数据共享/MediaStore与数据共享]]：ContentProvider 共享数据时的序列化选择
- [[../本地存储/DataStore]]：配置项持久化，底层用 ProtoBuf 而非 Parcelable
- [[../../05-网络与并发/README]]：网络传输用的是 JSON/ProtoBuf，不是 Parcelable
- [[../../06-系统原理与性能优化/README]]：Binder 通信机制与内存管理是本文的系统级底座

## 参考资料

- Android 官方 Parcelable 文档：https://developer.android.com/reference/android/os/Parcelable
- Android 官方 Bundle 文档：https://developer.android.com/reference/android/os/Bundle
- Android 官方 Parcel 文档（含 `dataAvail`、共享内存说明）：https://developer.android.com/reference/android/os/Parcel
- Kotlin 官方 `@Parcelize` 文档：https://kotlinlang.org/docs/parcelize.html
- AOSP `frameworks/base/core/java/android/os/Parcel.java` 与 native `Parcel.cpp`（共享内存与 FD 数组的实现）
