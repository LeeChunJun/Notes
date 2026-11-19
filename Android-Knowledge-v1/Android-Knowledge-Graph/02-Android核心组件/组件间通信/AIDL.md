---
tags: [android]
part: 第2部分
status: 待整理
difficulty: 高级
created: 2026-11-19
updated: 2026-11-19
related: "[[../README]], [[Binder机制]], [[Intent]], [[../四大组件/Service]]"
---

# AIDL

## 一句话定义

AIDL（**A**ndroid **I**nterface **D**efinition **L**anguage）是 Android 官方定义的**接口描述语言**，用于**声明跨进程通信的接口契约**。它把"方法签名"编译成 Java 代码，自动生成 `Stub`（服务端）与 `Proxy`（客户端代理），从而让你用"**调用本地方法**"的写法完成"**跨进程调用**"。

面试开场白：

> AIDL 解决的是一个很具体的问题：**跨进程只能传"能序列化的数据"，但你希望像调方法一样调另一个进程**。AIDL 就是这套 RPC 契约的声明语言。它生成的 `Stub`/`Proxy` 就是 [[Binder机制]] 中 Proxy 模式的具体落地。注意：**AIDL 本身不提供任何 IPC 能力**，它只是"**把接口描述翻译成 Binder 事务**"。

## 核心原理

### 1. AIDL 是什么 & 它解决什么问题

```text
  没有 AIDL 时你想跨进程调用：
      客户端                              服务端
  ┌──────────────┐                  ┌──────────────┐
  │ Parcel p =   │  Binder         │ onTransact() │
  │ Parcel.obtain│────transact────►│ switch(code) │
  │ p.writeInt(1)│                  │ case 1: ...  │
  │ p.writeString│                  │              │
  └──────────────┘                  └──────────────┘
       ↑ 事务码要自己约定、参数顺序要自己记、Parcel 要自己写 —— 易错

  有 AIDL 时：
  ┌──────────────┐                  ┌──────────────┐
  │ service.add(  │  自动生成的     │ Stub.add()    │
  │    1, 2)     │────Proxy───────►│ 真正的实现    │
  └──────────────┘                  └──────────────┘
       ↑ 编译器帮你生成事务码、Parcel 读写、异常处理
```

**AIDL 的三层职责**：

1. **接口契约**：定义方法名、参数、返回值。
2. **代码生成**：生成 `IXxx` 接口 + `IXxx.Stub` + `IXxx.Stub.Proxy`。
3. **序列化约定**：告诉编译器**每个参数如何写入 `Parcel`**（`in`/`out`/`inout`）。

### 2. AIDL 语法详解

**基本结构**：

```java
// IRemoteData.aidl
package com.example.aidl;

import com.example.aidl.UserParcel;   // 引入 Parcelable 类

interface IRemoteData {
    // 同步方法：有返回值，客户端阻塞等待
    int add(int a, int b);

    // 返回对象
    UserParcel getUser(long userId);

    // oneway：异步，无返回值，客户端立即返回
    oneway void reportEvent(String eventName, int count);

    // 参数方向
    void fillUser(in UserParcel user);      // 只传入
    void updateUser(inout UserParcel user);  // 传入并取回修改后的值
}
```

**语法要点**：

| 语法 | 含义 |
| --- | --- |
| `package` | **必须与生成类的包名一致**，且**每个接口第一行必须写 package**（否则编译报错） |
| `import` | 导入用到的 Parcelable / 其他 AIDL 接口 |
| `interface` | 接口名以 `I` 开头是惯例（不是强制） |
| `oneway` | 修饰方法，表示异步无返回值 |
| `in` / `out` / `inout` | 参数方向，**省略时默认是 `in`** |
| `throws` | **不能写异常**（AIDL 不支持抛出自定义异常） |

**参数方向详解**：

| 方向 | 语义 | Parcel 操作 |
| --- | --- | --- |
| `in`（默认） | 只从客户端传到服务端 | `data.readXxx()` |
| `out` | 只从服务端返回客户端 | `reply.readXxx()`（服务端 `reply.writeXxx()`） |
| `inout` | 双向 | 读入 `data`，写回 `reply` |

> ⚠️ **`out` / `inout` 参数在服务端必须先 `write` 再 `writeNoException`?**——顺序是：**先 `writeNoException()`，再写结果**（这与 `readException()` 的顺序对应）。

**`.aidl` 文件位置**：

```text
app/
  src/main/aidl/com/example/aidl/IRemoteData.aidl
```

**必须与 Java 包名目录结构完全一致**。这是最常见的编译错误来源。

### 3. 编译流程

```text
  IRemoteData.aidl  +  UserParcel.aidl
        │
        │  aidl 工具（Android SDK build-tools/aidl）
        │  读取 import，解析类型
        ▼
  1) 先编译被 import 的 .aidl（生成 Stub/Proxy）
        │
  2) 为每个 .aidl 生成：
        ├─ IRemoteData.java   （接口 + Stub + Proxy）
        └─ IRemoteData.aidl    （为依赖方生成"接口声明副本"）
        │
        ▼
  3) 生成的 .aidl 副本放到 SDK 的 include 目录，供被依赖方使用
        │
        ▼
  4) Gradle：Java 编译器编译生成 .java
        │
        ▼
  5) 打包进 APK 的 classes.dex
```

**手工执行 aidl 编译（用于理解原理）**：

```bash
# aidl 可执行文件位于 SDK build-tools/<version>/aidl
aidl -I<aidl 根目录> -I<SDK 目录>/platforms/android-34/framework.jar \
     -o<输出目录> \
     app/src/main/aidl/com/example/aidl/IRemoteData.aidl
```

**Kotlin 项目的配置（Gradle）**：

```kotlin
// app/build.gradle.kts
android {
    namespace = "com.example.app"

    defaultConfig {
        minSdk = 24
    }

    // 让 .aidl 文件被 IDE 正确识别
    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

**多个模块共享 AIDL 的做法**：把 `.aidl` 放进一个独立的 `aidl` 模块，通过 `implementation project(":aidl")` 依赖；或用 `buildFeatures.aidl` + `sourceSets` 指定共享目录。

### 4. 同步 vs oneway 调用

```java
// 同步
interface IRemoteService {
    int calculate(int a, int b);
    oneway void notifyChange(String key);
}
```

**运行时的差异**：

| | 同步 | oneway |
| --- | --- | --- |
| 客户端 `transact()` | 阻塞直到服务端处理完 | **立即返回** |
| flags | `0` | `IBinder.FLAG_ONE_SHOT` |
| 服务端 reply | 有 | 无 |
| 返回值 | 有 | 必须 `void` |
| 异常传播 | 可以（`RemoteException`） | **不可以** |
| 调用顺序保证 | 串行调用时保序 | **不保序**（可能被并发处理） |
| 服务端线程 | Binder 线程池线程 | Binder 线程池线程（另一个） |

**oneway 的正确用法**：

```kotlin
// 服务端实现
class RemoteServiceImpl : IRemoteService.Stub() {
    override fun notifyChange(key: String?) {
        // 注意：这里运行在 Binder 线程，不是主线程
        // oneway 的异常不会传回客户端，只会在服务端崩溃
        Log.d(TAG, "notify: $key")
    }
}
```

### 5. AIDL 的类型限制

**可以传递的类型**：

| 类别 | 具体类型 |
| --- | --- |
| **基本类型** | `int`、`long`、`float`、`double`、`boolean`、`byte`、`char`、`short` |
| **String** | `java.lang.String`、`CharSequence` |
| **Parcelable** | 自定义 `Parcelable`、`Parcelable[]`、`List<Parcelable>` |
| **Binder** | `IBinder`（传引用，适合回调） |
| **通用容器** | `Bundle`、`List<T>`、`Map<K,V>`、`Object[]`（受限制） |
| **AIDL 接口** | 其他 AIDL 生成的接口（用于回调） |

**不能传递的类型**：

| 类型 | 原因 | 替代方案 |
| --- | --- | --- |
| **自定义普通类 / POJO** | 无法序列化 | 实现 `Parcelable` 或用 `Parcelable` 包装类 |
| **异常** | AIDL 不支持 `throws` | 用 `Bundle` 返回错误码 |
| **泛型复杂类型**（如 `List<User>`） | 泛型信息在跨进程时丢失 | 定义 `Parcelable` 泛型包装类 `UserList` |
| **Thread / 线程相关** | 不可跨进程 | 用 Binder 回调代替 |
| **数据库游标** | 不可跨进程 | 用 `ContentProvider` 或返回 ID |
| **lambda / Kotlin 函数类型** | 不可跨进程 | 定义 AIDL 回调接口 |

**⚠️ 自定义 Parcelable 必须有对应的 `.aidl` 声明**

这是**最高频的编译错误**：

```text
error: Parcelable class com.example.aidl.UserParcel implements Parcelable
       but does not have a .aidl file
```

**原因**：跨进程时，系统需要通过 `Parcel.readParcelable(classLoader, DESCRIPTOR)` **按类名反射加载**目标进程里的类。为了保证目标进程能加载到，**必须在目标进程里也编译一份这个 Parcelable 类**——AIDL 编译器通过 `.aidl` 声明来生成这些类的 Parcelable 序列化代码。

**两种解决方式**：

**方式 A：为 Parcelable 建一个 `.aidl`（传统做法）**

```java
// UserParcel.aidl
package com.example.aidl;
parcelable UserParcel;
```

**方式 B：用 `@Parcelize` + `aidl` 插件生成**（推荐，AGP 8+）

```kotlin
// build.gradle.kts
plugins {
    id("com.android.library")
    id("kotlin-android")
    id("kotlin-parcelize")
}

// 只要 Parcelable 在同一个模块/依赖里被使用，
// AGP 会自动生成 .aidl，不需要手写 parcelable Xxx.aidl
@Parcelize
data class UserParcel(
    val id: Long,
    val name: String,
    val level: Int
) : Parcelable
```

> 在 **AGP 8.x** 中，Kotlin 的 `@Parcelize` 类会自动生成对应的 `aidl` 描述，**不再需要手写 `parcelable UserParcel;`**（AGP 7.x 时代需要手写）。这是版本演进中很值得提的点。

### 6. 回调接口（oneway + Binder）

**跨进程回调的标准做法**：客户端把自己的 `IBinder` 传给服务端，服务端保存并在需要时反向调用。

```java
// ICallback.aidl
package com.example.aidl;

interface IDataCallback {
    // 回调通常用 oneway，避免服务端被客户端阻塞
    oneway void onDataChanged(String key);
    oneway void onError(int code, String message);
}

// IRemoteService.aidl
package com.example.aidl;

import com.example.aidl.ICallback;

interface IRemoteService {
    void registerCallback(in ICallback callback);
    void unregisterCallback(in ICallback callback);
    void startSync();
}
```

**服务端实现**：

```kotlin
class RemoteServiceImpl : IRemoteService.Stub() {

    // 回调列表必须在多线程下安全访问 —— onTransact 在 Binder 线程池线程上执行
    private val callbacks = CopyOnWriteArrayList<ICallback>()

    override fun registerCallback(callback: ICallback?) {
        callback ?: return
        callbacks.add(callback)
        // 监听客户端死亡，避免回调到已死进程
        callback.asBinder().linkToDeath(object : IBinder.DeathRecipient {
            override fun binderDied() {
                callbacks.remove(callback)
            }
        }, 0)
    }

    override fun unregisterCallback(callback: ICallback?) {
        callback ?: return
        callbacks.remove(callback)
    }

    override fun startSync() {
        // 模拟后台任务，触发回调
        callbacks.forEach { cb ->
            try {
                cb.onDataChanged("key_1")
            } catch (e: RemoteException) {
                // 客户端进程可能已死
                callbacks.remove(cb)
            }
        }
    }
}
```

**客户端实现回调**：

```kotlin
class ClientCallback : IDataCallback.Stub() {   // 继承 Stub → 生成的 Proxy 会在服务端侧使用
    override fun onDataChanged(key: String?) {
        // 运行在 Binder 线程
        handler.post { updateUi(key) }
    }
    override fun onError(code: Int, message: String?) { }
}
```

**更简单的写法：直接传 `IBinder`**：

```java
interface IRemoteService {
    void setCallback(in IBinder callback);   // 传原始 Binder
}
```

服务端用 `ICallback.Stub.asInterface(callback)` 转成代理。**传 AIDL 接口类型更清晰、更类型安全**，推荐用前者。

### 7. 注意事项与陷阱

**① 不能抛异常**

```java
// ❌ 编译错误
interface IRemoteService {
    void doSomething() throws IOException;
}
```

**解决方案**：用返回值或 `Bundle` 传递错误：

```java
interface IRemoteService {
    // 方案 1：返回错误码
    int doSomething();   // 0 = 成功，其他为错误码

    // 方案 2：用 Bundle 返回
    Bundle doSomething2();   // bundle 中 putInt("error_code", ...)
}
```

> `RemoteException` 是**框架级异常**，会由 `Parcel.readException()` 在客户端自动抛出（对应服务端的 `reply.writeException(e)`）。

**② Binder 缓冲区 1MB 限制（TransactionTooLargeException）**

- Binder 的**单个事务**（含 data + reply）总大小不能超过约 **1MB**（`IBinder.MAX_IPC_SIZE`）。
- 超出抛 `TransactionTooLargeException`。
- **解决**：传 ID / URI；分页传输；用 `ContentProvider` 的大 Cursor 分批拉取。

**③ 不能传大对象**

```java
// ❌ 危险
void sendAllUsers(List<User> users);   // 每个 User 都要 Parcel 序列化
```

**正确**：传 ID 列表，服务端逐个返回；或用 ContentProvider 的 Cursor 分窗口拉取。

**④ 服务端方法运行在 Binder 线程**

- `onTransact` 及 Stub 的方法实现**不在主线程**；
- 想更新 UI 必须 `handler.post { }` 或用 `runOnUiThread`；
- **共享状态必须线程安全**（Binder 线程池并发调用）。

**⑤ 客户端阻塞**

- 同步调用会阻塞调用线程；
- **主线程调用有 ANR 风险**；
- 用协程 `withContext(Dispatchers.IO)` 包裹。

**⑥ 死循环 / 递归调用**

- 服务端在 `onTransact` 里**回调客户端**，客户端又同步调回服务端 → **跨进程死锁**。
- 回调应该用 `oneway`，避免形成同步调用环。

**⑦ 服务端进程被杀**

- 客户端的 `IBinder` 变成"僵尸"；
- 必须在 `onServiceDisconnected` 里清理并重连；
- 用 `linkToDeath` 感知服务端死亡。

**⑧ `oneway` 不保证顺序**

- 多个 oneway 调用可能被 Binder 线程池**并发、乱序**处理；
- 有顺序依赖时不能用 oneway。

**⑨ AIDL 与 Gradle 多模块**

- `.aidl` 必须放在 `src/main/aidl/<package path>/`；
- 如果在 `library` 模块中被 `app` 模块依赖，**生成的类在 app 模块中也可直接使用**；
- 但 `Stub` 的 `DESCRIPTOR` 必须在**两端完全一致**（同包名），否则 `queryLocalInterface` 永远命中不了，本地 fast path 失效，**同进程调用也会走完整 IPC**。

### 8. AIDL vs Messenger vs 其他方案

| 方案 | 通信模型 | 特点 | 适用场景 |
| --- | --- | --- | --- |
| **AIDL** | **双向、多方法、强类型** | 功能最全，代码量最大 | **复杂跨进程业务接口** |
| **Messenger** | **单向消息**（基于 Handler + Message） | 极简，但只能传 `Message`（Bundle + Object） | 简单的**单向通知**（如 "数据更新了"） |
| **ContentProvider** | **数据 CRUD** | 系统预定义接口，自带权限与变更通知 | **跨 App 数据共享** |
| **Broadcast** | **一对多事件** | 简单但受限（Android 8+ 隐式广播禁令） | 系统级事件、自定义通知 |
| **Intent + extras** | **一次性数据传递** | 最简单，无状态 | Activity/Service 启动传参 |
| **Hilt / `@AndroidEntryPoint`** | **同进程** DI | 不是跨进程方案 | 进程内解耦 |

**Messenger 的原理**：本质是**用 `Handler` 包装 `IBinder`**，消息通过 `Message` 传递：

```kotlin
// 服务端
class MsgService : Service() {
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_HELLO -> msg.replyTo?.send(Message.obtain(null, MSG_REPLY).apply {
                    arg1 = "world"
                })
            }
        }
    }
    override fun onBind(intent: Intent): IBinder = handler.binder
}

// 客户端
val messenger = Messenger(service)
messenger.send(Message.obtain(null, MSG_HELLO).apply {
    replyTo = myMessenger
})
```

**面试话术**："Messenger 是'**AIDL 的极简版**'——它本质是把 `Handler` 塞进 `IBinder` 传输，客户端发的 `Message` 会被投递到服务端的 `Handler` 队列里。好处是**不需要写 .aidl**；代价是'**只能传 Message**'，没有强类型、多方法、异常语义。做复杂接口，AIDL 完胜。"

### 9. AIDL 何时该用

**用 AIDL 的信号**：

1. **需要跨进程调用复杂业务逻辑**（不只是查数据）；
2. **需要强类型**（不想用 `Bundle` 传 `Int` 来区分方法）；
3. **需要双向回调**；
4. **需要多 App 共享同一套底层能力**（如 SDK 提供的服务）；
5. **需要 Service 的"长期连接 + 高频调用"**（比反复 startActivity 传数据高效得多）。

**不该用 AIDL 的信号**：

| 场景 | 更好的方案 |
| --- | --- |
| 只是传数据给下一个页面 | `Intent` extras |
| 只是查询/修改数据 | `ContentProvider` |
| 只是通知"数据变了" | `ContentObserver` / `SharedFlow` / 广播 |
| 只需要单向通知 | `Messenger` |
| 同进程内的解耦 | **Hilt + 接口**（根本不需要 IPC） |
| 一次性任务 | `WorkManager` |

**面试话术**："AIDL 是'**重武器**'。它的成本是：多一套 .aidl 契约、多一层 Stub/Proxy、跨进程调试更难。所以**默认不要用**——先用 Intent/Provider/广播；只有在'**跨进程 + 复杂接口 + 强类型 + 回调**'四个条件同时成立时才上 AIDL。"

**现代 Android 的补充建议**：

- **App 内部**：不要用 AIDL，用 **Hilt + Kotlin 接口**（进程内直接方法调用，比 AIDL 还快，因为没有 Parcel 开销）；
- **跨 App 开放能力**：优先考虑 **ContentProvider**（数据）或 **AIDL**（逻辑）；
- **跨设备**：AIDL 做不到，用 **gRPC / MQTT / 自定义 TCP**。

## 代码示例

### 1. AIDL 接口定义

```java
// app/src/main/aidl/com/example/aidl/IRemoteService.aidl
package com.example.aidl;

import com.example.aidl.UserParcel;
import com.example.aidl.ICallback;

// ICallback.aidl
package com.example.aidl;

interface ICallback {
    oneway void onEvent(String eventName, int code);
}
```

### 2. Parcelable 数据类（@Parcelize）

```kotlin
// app/src/main/aidl/com/example/aidl/UserParcel.aidl  （AGP 8+ 用 @Parcelize 时可省略此文件）
package com.example.aidl;
parcelable UserParcel;
```

```kotlin
// app/src/main/java/com/example/aidl/UserParcel.kt
package com.example.aidl

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class UserParcel(
    val id: Long,
    val name: String,
    val level: Int,
    val tags: List<String>
) : Parcelable
```

### 3. 服务端实现

```kotlin
// app/src/main/java/com/example/aidl/RemoteServiceImpl.kt
package com.example.aidl

import android.os.Binder
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

class RemoteServiceImpl : IRemoteService.Stub() {

    companion object {
        private const val TAG = "RemoteServiceImpl"
    }

    // 用 CopyOnWriteArrayList：onTransact 在 Binder 线程池线程上并发执行
    private val callbacks = CopyOnWriteArrayList<ICallback>()

    /**
     * 同步方法：运行在 Binder 线程池线程上，不是主线程。
     * 客户端会阻塞等待返回值。
     */
    override fun add(a: Int, b: Int): Int {
        Log.d(TAG, "add called on ${Thread.currentThread().name}")
        return a + b
    }

    /**
     * 返回 Parcelable 对象。
     */
    override fun getUser(userId: Long): UserParcel? {
        // 实际项目中这里查数据库
        return UserParcel(userId, "user_$userId", 5, listOf("vip", "new"))
    }

    /**
     * oneway：立即返回，服务端稍后处理；异常不会传回客户端。
     */
    override fun reportEvent(eventName: String?, count: Int) {
        Log.d(TAG, "reportEvent: $eventName x$count on ${Thread.currentThread().name}")
        // 模拟：处理完通知客户端
        callbacks.forEach { cb ->
            try {
                cb.onEvent(eventName ?: "", 0)
            } catch (e: android.os.RemoteException) {
                // 客户端进程可能已死，移除
                callbacks.remove(cb)
            }
        }
    }

    /**
     * out 参数：服务端写入 reply，客户端读取。
     */
    override fun fillLevel(user: UserParcel?, level: Int) {
        // 注意：out/inout 参数在服务端这边其实是"in"，值通过 reply 传回
        Log.d(TAG, "fillLevel: ${user?.name} -> $level")
        replyHolder = level
    }

    private var replyHolder: Int = 0

    override fun registerCallback(callback: ICallback?) {
        callback ?: return
        callbacks.add(callback)
        // 感知客户端进程死亡
        callback.asBinder().linkToDeath(object : IBinder.DeathRecipient {
            override fun binderDied() {
                Log.w(TAG, "client died, remove callback")
                callbacks.remove(callback)
            }
        }, 0)
    }

    override fun unregisterCallback(callback: ICallback?) {
        callback ?: return
        callbacks.remove(callback)
    }
}
```

### 4. Service 暴露 AIDL

```kotlin
// app/src/main/java/com/example/aidl/RemoteService.kt
package com.example.aidl

import android.app.Service
import android.content.Intent
import android.os.IBinder

class RemoteService : Service() {

    private val binder = RemoteServiceImpl()

    override fun onBind(intent: Intent): IBinder = binder

    /**
     * 服务端运行在独立进程时，在 onCreate 里初始化进程级资源
     */
    override fun onCreate() {
        super.onCreate()
    }
}
```

Manifest（让 Service 运行在独立进程）：

```xml
<service
    android:name=".aidl.RemoteService"
    android:exported="true"
    android:process=":remote" />
```

### 5. 客户端调用（协程封装 + 回调）

```kotlin
// app/src/main/java/com/example/aidl/RemoteClient.kt
package com.example.aidl

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RemoteClient(private val context: Context) {

    private var remote: IRemoteService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            // asInterface：同进程返回 Stub 本体，跨进程返回 Proxy
            remote = IRemoteService.Stub.asInterface(service)
            onConnected?.invoke()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            // 服务端进程死亡，需要重连
            remote = null
            onDisconnected?.invoke()
        }
    }

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    fun bind() {
        val intent = Intent(context, RemoteService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        runCatching { context.unbindService(connection) }
    }

    /** 同步 Binder 调用会阻塞线程，必须放到 IO 线程 */
    suspend fun add(a: Int, b: Int): Int = withContext(Dispatchers.IO) {
        remote?.add(a, b) ?: error("service not connected")
    }

    suspend fun getUser(id: Long): UserParcel? = withContext(Dispatchers.IO) {
        remote?.getUser(id)
    }

    /** oneway 调用：立即返回，可安全在主线程调用 */
    fun report(eventName: String, count: Int) {
        remote?.reportEvent(eventName, count)
    }

    /** 注册回调 */
    fun registerCallback(callback: ICallback) {
        remote?.registerCallback(callback)
    }

    /** 实现回调接口 */
    fun newCallback(onEvent: (String, Int) -> Unit): ICallback =
        object : ICallback.Stub() {
            override fun onEvent(eventName: String?, code: Int) {
                // 回调运行在 Binder 线程，需要切回主线程更新 UI
                mainHandler.post { onEvent(eventName.orEmpty(), code) }
            }
        }
}
```

### 6. 客户端使用示例

```kotlin
class MainActivity : AppCompatActivity() {

    private val client = RemoteClient(this)
    private val scope = lifecycleScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        client.onConnected = {
            // 绑定成功，开始工作
            scope.launch {
                val sum = client.add(1, 2)          // 协程里是 IO 线程，安全
                val user = client.getUser(1001L)
                withContext(Dispatchers.Main) {
                    binding.sum.text = "sum = $sum"
                    binding.user.text = "user = ${user?.name}"
                }
            }
            // 注册回调
            client.registerCallback(client.newCallback { name, code ->
                binding.event.text = "event: $name ($code)"
            })
        }

        client.bind()
    }

    override fun onDestroy() {
        client.unbind()
        super.onDestroy()
    }
}
```

### 7. 手动实现 Parcelable（不用 @Parcelize）

```kotlin
package com.example.aidl

import android.os.Parcel
import android.os.Parcelable

class UserManual(
    val id: Long,
    val name: String,
    val level: Int
) : Parcelable {

    // 必须提供从 Parcel 构造的构造函数
    constructor(parcel: Parcel) : this(
        parcel.readLong(),
        parcel.readString() ?: "",
        parcel.readInt()
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        // 写入顺序必须与 CREATOR 的读取顺序严格一致
        dest.writeLong(id)
        dest.writeString(name)
        dest.writeInt(level)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<UserManual> {
        override fun createFromParcel(parcel: Parcel): UserManual = UserManual(parcel)
        override fun newArray(size: Int): Array<UserManual?> = arrayOfNulls(size)
    }
}
```

## 常见问题 / 面试要点

**读者问：AIDL 是什么？它和普通接口有什么区别？**

**AI答：**

1. **AIDL 是什么**：Android Interface Definition Language，Android 官方的**接口描述语言**。它的产物是**接口契约 + 自动生成的 Binder 代理代码**。
2. **和普通 Java/Kotlin 接口的本质区别**：
   - 普通接口：方法调用是**进程内的直接调用**（JVM 虚方法表分派）；
   - AIDL 接口：方法调用会**被编译成 Binder 事务**——参数序列化 → 跨进程 → 反序列化 → 目标方法执行 → 结果回传。
   - 所以 AIDL 接口的方法**不能有默认实现**、**不能有异常**、**参数必须是可序列化的**。
3. **AIDL 的三层价值**：
   - **省代码**：事务码、Parcel 读写、异常处理都由编译器生成；
   - **强类型**：编译期检查，跨进程也不怕类型丢失（对比 `Bundle` + `getInt("code")` 的"**字符串协议**"）；
   - **可跨进程、可被其他语言实现**（任何能实现该接口的东西都能接入）。
4. **面试话术**："AIDL 是'**用编译器写 RPC 框架**'。你只写接口，编译器负责事务码分配、参数编解码、代理对象生成。相比手写 `Bundle` + `switch (code)` 的'土办法'，AIDL 解决了'**易错、难维护、无法类型检查**'三个问题。"
5. **补充**：AIDL 本身**不提供** IPC 能力，IPC 由 [[Binder机制]] 提供；AIDL 只是"**把接口描述翻译成 Binder 事务**"。

---

**读者问：AIDL 有哪些类型限制？自定义 Parcelable 为什么要写 `.aidl`？**

**AI答：**

1. **可传类型**：基本类型、`String`、`CharSequence`、`Bundle`、`List<T>`、`Map`、`Parcelable`、`IBinder`、其他 AIDL 接口。
2. **不可传类型**：普通 POJO、异常、复杂泛型、线程/游标、lambda、Kotlin 函数类型。
3. **自定义 Parcelable 为什么必须写 `.aidl`**：
   - **技术原因**：跨进程反序列化时，系统用 `Parcel.readParcelable(classLoader, DESCRIPTOR)` **按类名反射加载**目标进程里的类。要让目标进程能加载到，就必须**在目标进程里也编译一份**这个类。`.aidl` 文件（`parcelable UserParcel;`）就是让 aidl 编译器**在依赖方也生成/包含该类**的声明，从而把它打进双方的 APK。
   - **报错原文**：`Parcelable class X implements Parcelable but does not have a .aidl file`。
4. **现代解法**：
   - **AGP 8.x + Kotlin `@Parcelize`**：自动生成对应的 aidl 描述，**不用手写 `parcelable Xxx.aidl`**；
   - 传统做法：手写 `UserParcel.aidl`，内容只有 `package ...; parcelable UserParcel;`。
5. **面试话术**："Parcelable 的 `.aidl` 不是'**接口声明**'，而是'**类存在性声明**'。它的作用是让 aidl 编译器知道'**这个 Parcelable 类需要在每个依赖方里都存在**'，否则跨进程反序列化时目标进程找不到类。"
6. **常见坑**：Parcelable 类放在 `library` 模块，但 `.aidl` 放错目录（必须在 `src/main/aidl/<包路径>/`）→ 编译期报"does not have a .aidl file"。

---

**读者问：AIDL 的同步调用和 oneway 有什么区别？什么时候用 oneway？**

**AI答：**

1. **语义**：
   - **同步**：客户端 `transact()` **阻塞**直到服务端处理完；有返回值；**异常可传播**（客户端 `readException()` 会抛出 `RemoteException`）。
   - **oneway**：`transact()` **立即返回**；**无返回值**（必须 `void`）；**异常无法传回客户端**；**多个 oneway 调用不保证顺序**。
2. **实现**：
   ```java
   interface IRemoteService {
       int calculate(int a, int b);        // 同步
       oneway void reportEvent(String e); // 异步
   }
   ```
   客户端生成的 Proxy 会传 `IBinder.FLAG_ONE_SHOT`。
3. **适用场景**：
   - "**通知型**"：埋点上报、日志、"数据已更新，请重新拉取"、"帮我预热缓存"；
   - 任何"**我不在乎结果，也不想被阻塞**"的调用。
4. **禁用场景**：
   - 需要返回值；
   - 需要知道成功与否；
   - **有顺序要求**（oneway 可能被 Binder 线程池并发、乱序执行）；
   - 需要异常语义。
5. **面试话术**："oneway 是'**发后不理**'的语义。它牺牲了返回值、异常和顺序保证，换来的是'**不阻塞调用方**'。所以判断标准很简单：**这个调用的结果我需不需要知道？** 需要就不能 oneway。"
6. **补充**：oneway 也不能滥用——大量 oneway 会**占满服务端的 Binder 线程池**，反而拖慢整体。

---

**读者问：AIDL 的方法能抛异常吗？跨进程调用失败了客户端怎么知道？**

**AI答：**

1. **不能抛异常**：AIDL 语法**不支持 `throws`**（除框架自动处理的 `RemoteException`）。
   - 编译期报错：`Syntax error on token "throws", ... expected instead`。
2. **原因**：异常是**进程内的控制流**；跨进程时，服务端的异常栈在客户端**完全不可用**（客户端拿到的只是一串字节）。
3. **错误传递的三种标准做法**：

   **① 返回错误码（最常用）**：
   ```java
   interface IRemoteService {
       int doSomething();     // 0 = 成功，其他为业务错误码
   }
   ```
   ```kotlin
   override fun doSomething(): Int {
       return try {
           realWork()
           ERROR_OK
       } catch (e: Exception) {
           Log.e(TAG, "failed", e)
           ERROR_INTERNAL
       }
   }
   ```

   **② 用 `Bundle` 返回结构化结果**：
   ```java
   interface IRemoteService {
       Bundle doSomething();
   }
   ```
   ```kotlin
   override fun doSomething(): Bundle = Bundle().apply {
       putInt("error_code", if (ok) 0 else -1)
       putString("error_msg", if (ok) "" else "网络异常")
   }
   ```

   **③ 定义一个"错误 Parcelable"**：
   ```java
   parcelable Result {   // 用 .aidl 声明或 @Parcelize
       int code;
       String message;
   }
   interface IRemoteService {
       Result doSomething();
   }
   ```

   **④ 框架级异常自动传播**：服务端在 `onTransact` 中 `reply.writeException(e)`，客户端 `reply.readException()` 会自动抛出 `RemoteException`（这是**框架**帮你做的，不是你手写的异常）。
4. **面试话术**："跨进程的异常必须'**降级成数据**'。因为异常携带的是调用栈，而调用栈跨进程无意义。所以要么用错误码，要么用 Bundle 传结构化错误——**这是所有 RPC 框架的通用问题**（gRPC 用 status，REST 用 HTTP 状态码）"。"

---

**读者问：AIDL 传大数据会怎样？有什么限制？**

**AI答：**

1. **限制**：Binder 单个事务（data + reply 合计）**不能超过约 1MB**（`IBinder.MAX_IPC_SIZE`），超出抛 **`TransactionTooLargeException`**。
2. **为什么**：Binder 用 **mmap 共享内存**，但用户空间的 `Parcel` 缓冲区是有限的；框架层面做了硬限制。
3. **更隐蔽的问题**：即使没超 1MB，
   - 序列化/反序列化**很慢**（大对象图的 Parcel 化开销）；
   - **占内存**（客户端 + 服务端各一份）；
   - **阻塞**调用线程（同步调用）。
4. **正确做法**：
   - **传 ID**：服务端按 ID 查数据（**首选**）；
   - **传 URI**：文件用 `FileProvider` 的 `content://` Uri，内容**惰性**读取，不占事务空间；
   - **分页/分批**：用 ContentProvider 的 Cursor（**窗口机制**——只传一个窗口的数据）；
   - **传 Binder 引用**：传"能拿到数据的句柄"，不传数据本身；
   - **自定义 Parcelable 只带 ID + 元信息**，大字段（图片、JSON）走 URI。
5. **面试话术**："AIDL 的定位是'**传指令和少量数据**'，不是'**传数据**'。真要传大数据，正确的抽象是'**传地址**'——ContentProvider 的 Uri 或者一个能按需拉取的 Binder 接口。"
6. **加分点**：可以提到 `TransactionTooLargeException` 在**跨进程传递大 Bundle/Intent extras** 时也会出现——同一个根因。

---

**读者问：AIDL 和 Messenger 怎么选？和 Hilt 呢？什么时候根本不该用 AIDL？**

**AI答：**

1. **AIDL vs Messenger**：

   | 维度 | AIDL | Messenger |
   | --- | --- | --- |
   | 模型 | **双向、多方法、强类型** | **单向消息** |
   | 实现 | 需写 `.aidl`，编译器生成 Stub/Proxy | 基于 `Handler` + `IBinder`，**无需 .aidl** |
   | 回调 | **原生支持**（传 IBinder / AIDL 接口） | 需自己实现 `replyTo` Messenger 机制 |
   | 异常 | 可通过 `writeException` 传播 | 不支持 |
   | 代码量 | 大 | **极小** |
   | 适用 | 复杂业务接口 | "数据变了"这类**单向通知** |

2. **AIDL vs Hilt（同进程）**：
   - **Hilt** 是**进程内**的依赖注入——直接方法调用，**零序列化开销**；
   - **AIDL** 是**跨进程**的 RPC——有 Parcel 开销、线程阻塞、生命周期管理成本；
   - **同进程绝对不要用 AIDL**，用接口 + Hilt 注入即可，性能差距是数量级的。

3. **根本不该用 AIDL 的场景**：
   | 需求 | 首选方案 |
   | --- | --- |
   | 传数据给下一个页面 | `Intent` extras |
   | 跨 App 读写数据 | `ContentProvider` |
   | 通知"数据变了" | `ContentObserver` / `SharedFlow` / `Messenger` |
   | 同进程解耦 | **Hilt + 接口** |
   | 一次性可延迟任务 | `WorkManager` |
   | 跨设备 | **gRPC / 自定义网络协议** |

4. **该用 AIDL 的信号**（四条同时成立）：
   - **跨进程**；
   - **复杂业务逻辑**（不只是查数据）；
   - **需要强类型**（不想用 Bundle 传 magic number）；
   - **需要双向回调**。
5. **面试话术**："AIDL 是'**跨进程场景下的强类型 RPC**'。它的成本是：多一套 .aidl 契约、多一层代理、调试链路长。所以**默认用更轻的方案**——进程内用 Hilt，跨 App 传数据用 ContentProvider，单向通知用 Messenger。只有当'**跨进程 + 复杂接口 + 强类型 + 回调**'四个条件同时满足时，AIDL 才是最优解。"

---

**读者问：AIDL 生成的 Stub 是运行在哪个线程？服务端需要注意什么？**

**AI答：**

1. **线程**：`onTransact()` 及 Stub 的所有方法实现，**运行在服务端的 Binder 线程池线程上**，**不是主线程**。
2. **必须注意的三点**：
   - **① 共享状态线程安全**：同一个 Binder 对象的 `onTransact` **可能被多线程并发调用**。用 `CopyOnWriteArrayList`、`synchronized`、`ConcurrentHashMap` 或 `@Volatile`。
   - **② 更新 UI 要切回主线程**：`Handler(Looper.getMainLooper()).post { }`。
   - **③ 耗时操作会占用 Binder 线程池**：线程池有上限（默认通常 15），耗尽后**新事务排队**，调用方（可能是主线程）被阻塞 → **ANR**。
3. **客户端也要注意**：
   - **同步调用会阻塞调用线程** → 主线程调用有 ANR 风险 → 用协程 `withContext(Dispatchers.IO)`；
   - 服务端进程死亡后 `onServiceDisconnected` 要**清理并重连**；
   - 用 `linkToDeath` 感知服务端死亡，避免拿到"僵尸 Binder"。
4. **面试话术**："AIDL 服务端的心智模型是'**我是多线程服务器**'，不是'**我是主线程**'。这和写 Handler 回调（串行）的直觉完全相反，是很多 Android 开发者的认知盲区。"
5. **加分点**：可以对比 Handler/Looper 的**串行**模型与 Binder 的**并行**模型，并说明这为什么让"**Binder 线程池耗尽**"成为系统级 ANR 的常见根因（AMS 被占满 → 主线程等 AMS → ANR）。

## 关联笔记

- [[../README]]：第 2 部分总览
- [[Binder机制]]：AIDL 生成的 Stub/Proxy 的底层原理
- [[Intent]]：跨进程传参的另一种方式（更轻量）
- [[../四大组件/Service]]：AIDL 服务的宿主
- [[../四大组件/ContentProvider]]：跨进程数据共享的官方方案
- [[../四大组件/BroadcastReceiver]]：另一种跨进程通信方式及其限制
- [[../Application与Context]]：跨进程时 Application 的独立性
- [[../../04-数据存储与处理/序列化原理/Parcelable与Serializable]]：Parcelable 的原理与实现

## 参考资料

- Android 官方文档 · AIDL 概览：https://developer.android.com/guide/topics/connectivity-binder
- Android 官方文档 · AIDL 参考（语法）：https://developer.android.com/reference/android/os/Parcel
- AOSP 源码 · `frameworks/native/aidl/`：aidl 编译器实现
- Android 官方文档 · Messenger：https://developer.android.com/reference/android/os/Messenger
- 《Android 系统内部揭秘》第 4 版 · 第 4 章（AIDL 与 Binder）
