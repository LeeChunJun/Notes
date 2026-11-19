---
tags: [android]
part: 第5部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[./README]]"
---

# Handler机制原理

## 一句话定义

Android 的消息机制由三个类构成：**`Looper`（线程的"消息循环"）、`MessageQueue`（消息队列）、`Handler`（消息的收发器）**。它解决的问题是：**把任务投递到指定线程的队列中，由该线程按时间顺序取出执行** —— 本质上是一套**线程内的任务调度器 + 生产者消费者模型**。

## 核心原理

### 1. 三件套的职责

| 类 | 职责 | 一句话 |
| --- | --- | --- |
| **MessageQueue** | 消息队列，**按 `when` 排序**（时间轮），提供阻塞的 `next()` | "**排队的地方**" |
| **Looper** | 持有 `MessageQueue`，不断调用 `queue.next()` 取消息并分发 | "**循环取快递的人**" |
| **Handler** | 持有 `Looper` 引用，负责**投递消息**（`post`/`sendMessage`）和**处理消息**（`handleMessage`） | "**收发室**" |
| **Message** | 消息载体，含 `what` / `arg1` / `arg2` / `obj` / `when` / **`target`** / `callback` | "**包裹**" |

```text
   线程 A（任意线程）                          线程 B（有 Looper 的线程）
        │                                              │
        │  handlerB.post(runnable)                     │
        ├─────────────────────────────────────────────►│
        │                                     MessageQueue.enqueue(msg)
        │                                       ├─ 按 when 插入链表
        │                                       └─ nativeWake() 唤醒阻塞的线程
        │                                              │
        │                                     Looper.loop()
        │                                       ├─ queue.next()  ← 阻塞等待
        │                                       ├─ msg.target.dispatchMessage(msg)
        │                                       └─ handlerB.handleMessage(msg) / msg.callback.run()
        ▼                                              ▼
```

### 2. Looper 的四个关键方法

```java
// ① 为当前线程创建 Looper（每个线程最多一个）
public static void prepare() {
    if (Thread.currentThread().getLooper() != null) {
        throw new RuntimeException("Only one Looper may be created per thread");
    }
    sThreadLocal.set(new Looper(quitAllowed));
}

// ② 开启消息循环（会一直阻塞，直到 quit）
public static void loop() {
    final Looper me = myLooper();
    if (me == null) throw new RuntimeException("No Looper; Looper.prepare() wasn't called on this thread.");
    final MessageQueue queue = me.mQueue;
    for (;;) {
        Message msg = queue.next();          // ★ 阻塞直到有消息
        if (msg == null) continue;           // null 表示"要退出了"
        msg.target.dispatchMessage(msg);     // ★ 分发给 target
        // ... 性能优化：回收无用对象
    }
}

// ③ 获取当前线程的 Looper
public static Looper myLooper() {
    return sThreadLocal.get();
}

// ④ 获取主线程的 Looper（全局唯一）
public static Looper getMainLooper() {
    return sMainLooper;
}
```

**`ThreadLocal` 的作用（面试点）**：**每个线程只能有一个 Looper**，`sThreadLocal` 保证"一个线程一个 Looper"。`prepare()` 里先检查 `getLooper() != null` 就抛异常，正是靠它保证唯一性。**HandlerThread** 也是这个原理（见 [[异步方案/Thread与Handler]]）。

**主线程 Looper 的初始化**：

```text
ActivityThread.main()
   ↓
Looper.prepareMainLooper()      // 创建主线程 Looper → sMainLooper
   ↓
ActivityThread().attach(false, startAsServer)   // 创建 Application、绑定 AMS
   ↓
Looper.loop()                  // ★ 主线程进入消息循环
   ↓
（此后主线程的一切都由 MessageQueue 驱动）
```

**主线程 Looper 的特点**：
- **全局唯一**（`Looper.getMainLooper()`）。
- **永不退出**（`Looper.loop()` 里 `for(;;)` 没有退出条件，除非进程结束）。
- `quit()`/`quitSafely()` 对它**无效**（`prepare()` 传入 `quitAllowed=false`，`MessageQueue.quit()` 会检查这个标志并返回 false）。

### 3. MessageQueue 的内部结构

**不是 PriorityQueue，而是"按时间排序的链表 + 空闲队列 + 同步屏障"。**

```java
public final class MessageQueue {
    private final Message[] mMessages;       // ★ 环形数组，存"待处理消息"
    private int mHead;                      // 读指针
    private int mTail;                      // 写指针
    private int mMessagesSincePoll;         // 连续入队次数（用于扩容判断）
    private int mMessagesSinceIdle;          // 连续空闲次数（用于决定是否 idle）
    private final ArrayList<IdleHandler> mIdleHandlers = new ArrayList<>();  // 空闲处理器
    private int mNextBarrierToken;          // ★ 同步屏障 token
    private static final Object mSyncBarrier = new Object();   // ★ 同步屏障标记
}
```

**插入逻辑（按 `when` 排序）**：

```java
boolean enqueueMessage(Message msg, boolean p) {
    ...
    if (p) {                 // p = true 表示"同步消息"（不可被屏障阻挡）
        msg.when = 0;        // ★ 同步消息的 when 设为 0 → 排到最前面
    }
    synchronized (this) {
        ...
        Message p = mMessages;
        if (p == null) {
            mMessages = msg;                 // 空队列，直接放
        } else {
            Message n = mMessages;
            long when = msg.when;
            if (when < n.when) {             // ★ 新消息比队首还早 → 插到队首
                msg.next = n;
                mMessages = msg;
                needWake = true;             // ★ 唤醒阻塞的线程
            } else {
                Message prev = n;
                while (prev.next != null && prev.next.when <= when) {   // ★ 找插入位置
                    prev = prev.next;
                }
                msg.next = prev.next;
                prev.next = msg;
            }
        }
        ...
    }
    if (needWake) nativeWake();
    return true;
}
```

**关键点**：
1. **消息按 `when` 升序排列**，`next()` 只取**队首**（时间最早的消息）。
2. **新消息比队首更早时插到队首**并 `nativeWake()` 唤醒阻塞的线程。
3. **`when == 0` 的消息会被立即执行**（同步消息）。
4. **`Message` 是链表节点**：`mMessages` 是链表头，每条消息的 `next` 指向下一条。

### 4. MessageQueue.next()：阻塞与唤醒机制

```java
Message next() {
    for (;;) {
        nativePollOnce(this, nextTurn == -1);    // ★ 关键：native 层阻塞
        synchronized (this) {
            // ★ 同步屏障处理（见后文）
            Message msg = mMessages;
            if (msg != null) {
                long now = SystemClock.uptimeMillis();
                if (msg.when <= now) {            // 队首消息已到时间
                    mHead = (mHead + 1) % mMessages.length;
                    mMessages = msg.next;         // 出队
                    if (mMessages == null) {       // 队列空了
                        mMessages = null;         // 顺便标记为 null（next 返回 null → loop 退出）
                    } else {
                        msg.next = null;
                    }
                    return msg;                  // ★ 返回可执行的消息
                }
                // 队首还没到时间 → 计算还差多久
                long nextDelay = msg.when - now;
                if (nextDelay < 0) nextDelay = 0;
                nextTurn = msg.when;             // ★ 记录下一次唤醒的绝对时间
            } else {
                nextTurn = -1;                   // 队列空 → 无限期等待
            }
            // 处理 IdleHandler（队列空闲时执行）
            ...
        }
        // nativePollOnce 返回后重新循环
    }
}
```

**`nativePollOnce` 的机制（native 层）**：

```text
nativePollOnce(messages, nextTurn)
   │
   ├─ nextTurn == -1（队列空，无限期等待）
   │     └─ 调用 epoll/select 阻塞在 MessageQueue 的 fd 上（无超时）
   │        → 被 nativeWake() 唤醒
   │
   ├─ nextTurn != -1（有消息但还没到时间）
   │     └─ epoll_wait(timeout = nextTurn - now)  ← ★ 精确超时
   │        → 到时间自动醒，或被 nativeWake 提前唤醒
   │
   └─ 队列非空且消息已到期
         └─ 直接返回，不阻塞
```

- `MessageQueue` 在 native 层有一个**文件描述符（fd）**，注册在 `epoll` 中。
- `nativeWake()` 往这个 fd 写一个字节 → `epoll_wait` 返回 → `nativePollOnce` 返回 → Java 层重新检查队列。
- **这实现了一个"精确到毫秒的定时器"**：不需要轮询，也不会浪费 CPU。

**`IdleHandler`（空闲处理器，面试加分点）**：

```java
public interface IdleHandler {
    void onIdle();
    boolean queueIdle();   // 返回 true 表示"处理完还想继续处理"
}
```

- 当**消息队列为空**（没有待处理消息）时，`next()` 会执行 `IdleHandler`。
- 典型用途：**延迟初始化**（首帧渲染完成后才做非关键初始化）、**批量合并数据库写入**。
- `Looper.loop()` 里也会注册一个 `IdleHandler` 用于**性能统计**（记录"这个线程有多闲"）。

### 5. Message.target 的设计：一条消息可以被多个 Handler 分发

**这是 Handler 机制最精妙的设计点。**

```java
public final class Message {
    public int what;
    public int arg1;
    public int arg2;
    public Object obj;
    long when;                       // 触发时间（uptimeMillis）
    private Handler target;          // ★ 目标 Handler
    private final Object[] args;     // 备用数组
    private boolean async;           // ★ 是否为异步消息（能否被同步屏障阻挡）
    ...
}
```

**分发流程**：

```java
// Handler.dispatchMessage
void dispatchMessage(Message msg) {
    if (msg.callback != null) {
        handleCallback(msg);          // ① 优先执行 msg.callback（post 的 Runnable）
    } else {
        handleMessage(msg);          // ② 否则调用 handleMessage
    }
}
```

**"一条消息被多个 Handler 分发"是什么意思？**

```java
// 场景：Handler A 和 Handler B 都在主线程
Handler handlerA = new Handler(Looper.getMainLooper());
Handler handlerB = new Handler(Looper.getMainLooper());

Message msg = handlerA.obtainMessage(what);
msg.target = handlerA;      // target 是 Handler A

handlerB.sendMessage(msg);  // ★ Handler B 发送了这条消息
```

**发送时 `sendMessage` 会做什么**：

```java
public final void sendMessage(Message msg) {
    sendMessageDelayed(msg, 0);
}

public final boolean sendMessageDelayed(Message msg, long delayMillis) {
    if (msg.target == null) {
        msg.target = this;      // ★ 只在 target 为 null 时才设置
    }
    return sendMessageAtTime(msg, SystemClock.uptimeMillis() + delayMillis);
}
```

**关键机制**：

```text
消息发出后，msg.target 被固定为"第一个发送它"的 Handler。

之后 MessageQueue 取出消息时，调用的是：
    msg.target.dispatchMessage(msg)

所以：
  ✔ 消息永远由 target Handler 处理
  ✘ Handler B 发送的消息，最终会调用 Handler A 的 handleMessage
```

**为什么这样设计（面试要能说出理由）**：

1. **消息与发送者解耦**：发送方不需要知道消息最终由谁处理。
2. **支持"消息重定向"**：可以把一条已经 target 好的消息，**改 target 后再发**给另一个 Handler：
   ```java
   Message msg = handlerA.obtainMessage(WHAT);
   msg.target = handlerB;              // ★ 手动改 target
   handlerB.sendMessage(msg);
   ```
3. **保证消息的处理者与队列所在线程一致**：`target` 的 `Looper` 决定了消息在哪个线程被处理 —— 因为 `msg.target.dispatchMessage()` 就是在取消息的那个线程上调用的，**Handler 的 Looper 必须是那个线程的**。
4. **这就是"Looper 决定线程，Handler 决定谁处理，Message 只带数据"**的职责分离。

**⚠️ 面试高频陷阱题**：

> **问：一条 Message 为什么会分发到多个 Handler？**
>
> **答：其实不是"分发到多个 Handler"，而是"消息只会被它的 `target` Handler 处理"。**
> `Message.target` 在**第一次发送时**被设为发送方（若为 null）。后续即使被别的 Handler `sendMessage`，`target` 也不会被改（`sendMessageDelayed` 只在 `target == null` 时赋值）。所以队列取出消息时调用 `msg.target.dispatchMessage(msg)` —— **永远是最初的 target**。
>
> 要让不同 Handler 处理同一条消息，**必须手动改 `msg.target`**，或**分别创建消息**。

### 6. Handler 的完整 API 与机制

```java
public class Handler {
    final Looper looper;              // ★ 所属 Looper
    final MessageQueue queue;         // ★ 消息队列
    final Callback mCallback;         // 可选回调

    // 构造：默认使用当前线程的 Looper
    public Handler() {
        this(null, null);
    }
    public Handler(Callback callback) {
        this(null, callback);
    }
    public Handler(Looper looper) {
        this(looper, null);
    }
    public Handler(Looper looper, Callback callback) {
        this.looper = looper == null ? Looper.myLooper() : looper;
        this.queue = looper.queue;
        this.mCallback = callback;
    }
}
```

**四种发送方式**：

| 方法 | 作用 |
| --- | --- |
| `post(Runnable)` | 把 Runnable 包装成 Message 投递（`msg.callback = r`） |
| `postDelayed(Runnable, delay)` | 延迟投递（`msg.when = uptime + delay`） |
| `sendMessage(Message)` | 投递自定义 Message |
| `sendEmptyMessage(int what)` | 投递一个只有 `what` 的 Message |

**`post` 的实现**：

```java
public final boolean post(Runnable r) {
    return sendMessageDelayed(getPostMessage(r), 0);
}

private static Message getPostMessage(Runnable r) {
    Message m = new Message();
    m.callback = r;         // ★ Runnable 存在 callback 字段
    return m;
}
```

**`handleMessage` 与 `handleCallback` 的优先级**：

```java
void dispatchMessage(Message msg) {
    if (msg.callback != null) {
        handleCallback(msg);        // ★ 优先：执行 msg.callback.run()
    } else {
        handleMessage(msg);        // ★ 次之：调用子类的 handleMessage
    }
}
```

> **注意**：`post` 的 Runnable **不会**走 `handleMessage`。所以 `handler.post{}` 和 `handler.handleMessage` 是两条不同的路径。

**`Handler.Callback` 接口**（在 `handleMessage` 之前触发）：

```java
public interface Callback {
    boolean handleMessage(Message msg);
}
```

### 7. 同步屏障（Sync Barrier）—— 高频面试题

**这是 Handler 机制最难的知识点，也是面试官最爱问的。**

**问题背景**：Android 的 **VSync** 信号通过 `Choreographer` 驱动 UI 刷新。理想情况是：**每帧（16.67ms）先把"绘制相关的消息"处理完，再去处理其他消息**。这样输入事件等消息就不会抢占绘制时机，导致掉帧。

**解决方案**：**同步屏障（Synchronous Barrier）** —— 让**异步消息（async message）**在屏障存在时**被阻塞**，而**同步消息（sync message）畅通无阻**。

```text
Message 的两个标记：
   ✔ async = true   → 异步消息（可以被屏障阻挡）★ 默认值
   ✔ async = false  → 同步消息（不可被屏障阻挡）

Handler.sendMessageAtTime()  → msg.async = false  → 同步消息
Handler.post() / postDelayed() → msg.async = true   → 异步消息 ★
```

**屏障的设置与移除**：

```java
// Choreographer 里（简化）
private void scheduleTraversals() {
    if (!mTraversalScheduled) {
        mTraversalScheduled = true;
        // ★ 插入同步屏障
        mHandler.getLooper().getQueue().postSyncBarrier();
        // 用同步消息（sendMessageAtTime → async=false）请求下一帧
        mHandler.sendMessageAtTime(mTraversalRunnable, SystemClock.uptimeMillis());
    }
}

void doFrame(...) {
    ...
    mHandler.removeCallbacksAndMessages(mTraversalBarrier);  // ★ 移除屏障
    ...
}
```

**`postSyncBarrier` / `removeSyncBarrier`（MessageQueue 内部）**：

```java
int postSyncBarrier() {
    synchronized (this) {
        int token = ++mNextBarrierToken;
        mNextBarrierToken = token;
        mSyncBarrier = new SyncBarrier(token);        // ★ 同步屏障消息
        Message m = obtainMessage();                   // ★ 复用 mMessages[0] 槽位
        m.when = 0;                                   // ★ when = 0 → 排到队首
        m.target = null;                              // ★ target = null → 不是真实消息
        m.arg1 = token;                               // ★ 存 token
        next = mSyncBarrier;
        return token;
    }
}

private void removeSyncBarrier(int token) {
    synchronized (this) {
        if (mSyncBarrier != null && mSyncBarrier.getTarget() == token) {
            Message prev = mMessages;
            if (prev != null && prev.arg1 == token) {
                mMessages = prev.next;                 // ★ 移除队首的屏障
            } else {
                // 遍历链表移除
            }
            prev = mMessages;
            while (prev != null) {
                if (next == null) break;
                if (next.arg1 == token) {
                    prev.next = next.next;
                    break;
                }
                prev = next;
            }
            mSyncBarrier = null;
        }
    }
}
```

**`next()` 里的屏障处理（核心）**：

```java
Message next() {
    for (;;) {
        nativePollOnce(this, nextTurn == -1);
        synchronized (this) {
            // ★★★ 同步屏障的核心逻辑 ★★★
            if (mSyncBarrier != null) {
                long now = SystemClock.uptimeMillis();
                Message msg = mMessages;
                if (msg != null && msg.target == null) {     // ★ 队首是屏障消息
                    if (msg.arg1 != mNextBarrierToken) {     // 不是当前的屏障 → 跳过
                        mMessages = msg.next;
                        continue;
                    } else {                                // ★ 是当前屏障
                        // 找到屏障之后的第一条异步消息
                        Message prev = msg;
                        msg = msg.next;
                        while (msg != null) {
                            if (msg.target != null) break;  // 同步消息 → 停
                            prev = msg;
                            msg = msg.next;                  // 跳过异步消息
                        }
                        mMessages = prev;                    // ★ 把异步消息挡在屏障之后
                        if (msg == null) {
                            // 没有同步消息 → 无限等待，直到屏障被移除
                            nextTurn = -1;
                            continue;
                        } else {
                            long when = msg.when;
                            if (when > now) {                // 同步消息还没到时间
                                nextTurn = when;
                            } else {
                                mHead = (mHead + 1) % mMessages.length;
                                mMessages = msg.next;
                                msg.next = null;
                                return msg;                 // ★ 返回同步消息
                            }
                        }
                    }
                }
            }
            // ... 正常取消息逻辑
        }
    }
}
```

**完整时序图**：

```text
时间轴 ──────────────────────────────────────────────────────►

[VSync 信号到达]
   │
   ├─ Choreographer.scheduleTraversals()
   │     ├─ postSyncBarrier()          ← ★ 插入同步屏障
   │     └─ mHandler.sendMessageAtTime(traversalRunnable)  ← 同步消息（async=false）
   │
   ├─ Looper.loop() → queue.next()
   │     ├─ 发现队首是屏障（target == null）
   │     ├─ 跳过所有异步消息（post 进来的）
   │     └─ 返回那条同步消息（VSync 的 traversal Runnable）
   │
   ├─ Choreographer.doFrame()
   │     ├─ 执行遍历：测量、布局、绘制  ★★ 优先，保证不掉帧
   │     └─ removeCallbacksAndMessages(barrier)  ← ★ 移除屏障
   │
   └─ 屏障移除后 → 被挡住的异步消息（输入事件、post 的任务）才依次执行
```

**为什么要这样设计**：
- **保证 16.67ms 的帧预算优先用于渲染** → 降低掉帧率。
- **VSync 的消息是同步消息**（`sendMessageAtTime` → `async = false`），能穿透屏障。
- **业务 `post` 的任务默认是异步消息** → 会被屏障挡住。

**⚠️ 面试陷阱**：
- 如果业务代码**必须立即执行**（如用户点击的响应），可能被屏障延迟到本帧之后。
- **同步屏障是一个"内部机制"，业务代码不应该依赖它**。
- **异步消息不一定是"耗时的"** —— 屏障期间的延迟通常只有几毫秒。

> 面试话术：**"同步屏障是 Android 为保证渲染优先引入的机制。Choreographer 在每帧开始时 post 一个 target=null、when=0 的屏障消息，MessageQueue.next() 遇到屏障时会跳过所有 async=true 的消息，只放行同步消息（VSync 的 traversal Runnable 就是同步消息）。帧渲染结束后 removeSyncBarrier，被挡住的异步消息才执行。效果是让绘制优先，保证不掉帧。"**

### 8. Looper 的退出

```java
public void quit() {
    mQueue.quit(false);       // ★ 不处理队列中剩余的消息，立即退出
}

public boolean quitSafely() {
    mQueue.quit(true);        // ★ 处理完队列中已到期的消息再退出
}
```

**`MessageQueue.quit(boolean safe)`**：

```java
void quit(boolean safe) {
    synchronized (this) {
        if (mQuit) return;
        mQuit = true;
        if (safe) {
            // ★ 安全退出：把"还没到期"的消息的 when 设为 0（立即到期）
            //   这样它们会被处理，但不再等待延迟
            int n = mMessages.size();
            for (int i = 0; i < n; i++) {
                Message m = mMessages.get(i);
                if (m != null) m.when = 0;
            }
            mMessages.clear();
        }
        // ★ 通知 native 层，让 nativePollOnce 立即返回
        nativeWakeAndRemove();
    }
}
```

**返回值**：`quit()` 返回 `true` 表示 Looper 成功退出；**主线程的 Looper 返回 `false`**（因为 `prepareMainLooper` 时 `quitAllowed = false`，禁止退出）。

**为什么需要 `quitSafely`**：
- `quit()` 会**丢弃**队列中还没执行的消息 → 数据丢失。
- `quitSafely()` 会**把延迟消息的 `when` 改成 0**（立即到期）→ 它们仍会被执行，只是不再等待延迟 → **既退出又不丢数据**。
- 典型场景：`HandlerThread` 停止时，需要把队列里已投递的任务跑完。

**`Looper.loop()` 的退出条件**：

```java
for (;;) {
    Message msg = queue.next();
    if (msg == null) {
        // ★ next() 返回 null 表示队列已 quit
        break;                       // 退出循环
    }
    msg.target.dispatchMessage(msg);
}
```

### 9. ThreadLocal 的作用

```java
// Looper 内部
private static final ThreadLocal<Looper> sThreadLocal = new ThreadLocal<>();
private static Looper sMainLooper = null;
```

**作用：保证"一个线程一个 Looper"。**

```text
Thread A ──→ sThreadLocal.get() → Looper A
Thread B ──→ sThreadLocal.get() → Looper B
Thread C ──→ sThreadLocal.get() → Looper C
```

- `prepare()` 时 `sThreadLocal.set(new Looper(...))`。
- `myLooper()` 时 `sThreadLocal.get()`。
- `loop()` 结束后 `sThreadLocal.remove()`（`Looper` 的 `run()` 结束时）。
- **`ThreadLocal` 的性能**：每个线程有自己的 `ThreadLocalMap`（`Thread` 持有的 `threadLocals` 字段），**读写无锁**，比 `synchronized` 快得多。
- **⚠️ 内存泄漏**：`ThreadLocalMap` 的 **key 是弱引用**，**value 是强引用**。如果线程长期存活（线程池）而 value 一直不被清理 → **内存泄漏**。Android 框架内部会小心处理（如 `View` 的 `mAttachInfo` 会在 detach 时清理）。

### 10. 主线程 Looper 与 ANR

**ANR 的判定标准（系统层面）**：

| 场景 | 超时时间 |
| --- | --- |
| **输入事件**（按键、触摸）无响应 | **5 秒** |
| **前台 Service**（`onStartCommand`）无响应 | **20 秒** |
| **广播接收**（`onReceive`）无响应 | **10 秒**（前台）/ 60 秒（后台） |
| **CPU 占用 > 90% 持续 5 秒** | 5 秒 |

**为什么主线程不能做耗时操作**：
1. 主线程的消息循环**是串行的** —— `next()` 取一条、执行完才取下一条。**耗时任务会阻塞整条队列**。
2. 所有 UI 更新（`invalidate` → `requestLayout` → `scheduleTraversals` → VSync）都要**排队**等待这条消息被处理完。
3. 主线程还负责**输入事件的响应** → 超过 5 秒就是 ANR。

**ANR 发生时系统做了什么**：
1. 向 App 进程发送 **ANR 弹窗请求**（`ApplicationNotRespondingDialog`）。
2. **dump traces** 到 `/data/anr/traces.txt`（包含所有线程的栈）。
3. 用户选择"等待"或"关闭应用"；系统可能**杀掉进程**。

**排查手段**：

```bash
# 查看 ANR 记录
adb shell dumpsys activity processes | grep -i anr

# 应用退出原因（API 30+，线上归因标准方案）
adb shell dumpsys activity exit-info <packageName>
```

```kotlin
// 代码获取历史 ANR 原因
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    val reasons = ActivityManager.getExitReasons(packageName, 0)
    reasons.forEach { reason ->
        if (reason.reason == ApplicationExitInfo.REASON_ANR) {
            // reason.description, reason.traceInputStream 可读 traces
        }
    }
}
```

**主线程初始化优化（与 ANR 相关）**：
- `Application.onCreate` 里**不要做耗时操作**（初始化 SDK、读配置、加载数据库）。
- 用 `App Startup`（AndroidX Startup）做**异步初始化 + 延迟初始化**。
- 第三方 SDK 提供 `initLater` 能力。

## 代码示例

### 1. Handler 的标准用法

```kotlin
class MainThreadHelper {

    /** 主线程 Handler：绑定主线程 Looper */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 切到主线程（已在主线程则直接执行，不排队） */
    fun post(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    /** 延迟执行：内部是 when = uptime + delay，不是 sleep */
    fun postDelayed(delayMs: Long, action: () -> Unit) {
        mainHandler.postDelayed(action, delayMs)
    }

    /** 取消：必须传同一个 Runnable 实例 */
    fun cancel(action: Runnable) {
        mainHandler.removeCallbacks(action)
    }
}
```

### 2. 用 what 区分消息类型

```kotlin
class MessageHandler : Handler(Looper.getMainLooper()) {

    companion object {
        const val MSG_REFRESH = 1
        const val MSG_SCROLL_TO = 2
    }

    override fun handleMessage(msg: Message) {
        when (msg.what) {
            MSG_REFRESH -> refresh()
            MSG_SCROLL_TO -> scrollTo(msg.arg1)
            else -> super.handleMessage(msg)
        }
    }

    private fun refresh() { /* ... */ }
    private fun scrollTo(position: Int) { /* ... */ }
}

// 使用
handler.sendEmptyMessage(MessageHandler.MSG_REFRESH)
handler.sendMessage(Message.obtain(MessageHandler.MSG_SCROLL_TO, position))
```

### 3. HandlerThread：带 Looper 的后台线程

```kotlin
class SerialTaskRunner(private val name: String = "serial-runner") {

    private val thread = HandlerThread(name).apply { start() }
    private val handler = Handler(thread.looper)

    /** 按投递顺序在后台线程串行执行 */
    fun submit(task: () -> Unit) {
        handler.post(task)
    }

    fun submitDelayed(delayMs: Long, task: () -> Unit) {
        handler.postDelayed(task, delayMs)
    }

    /** 优雅关闭：先处理完队列中的消息再退出 */
    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}
```

### 4. 自定义 Looper 线程 + Callback

```kotlin
/** 手动管理 Looper 生命周期 */
class CustomLooperThread(name: String) : Thread(name) {

    private val handler: Handler

    init {
        Looper.prepare()                       // ① 为当前线程创建 Looper
        handler = Handler(Looper.myLooper(), object : Handler.Callback {
            override fun handleMessage(msg: Message): Boolean {
                when (msg.what) {
                    1 -> { doWork(); return true }    // true = 已处理，不再调用 handleMessage
                    else -> return false
                }
            }
        })
    }

    fun post(task: () -> Unit) {
        handler.post(task)
    }

    override fun run() {
        Looper.loop()                          // ② 开启消息循环
        // loop 返回说明 quit 被调用
        Looper.myLooper()?.queue?.removeMessages(this)  // 清理
    }

    fun quit() {
        Looper.myLooper()?.quit()              // ③ 退出循环
    }
}
```

### 5. 观察 Message.target 的分发行为（面试演示）

```kotlin
fun demoTargetSemantics() {
    val looper = Looper.getMainLooper()
    val handlerA = Handler(looper)
    val handlerB = Handler(looper)

    // 消息的 target 第一次发送时被固定
    val msg = Message.obtain(handlerA, 100)   // ★ 用 handlerA.obtainMessage
    Log.d("TAG", "target = ${msg.target}")    // handlerA

    // handlerB 发送这条消息，但 target 不会改变（因为 target != null）
    handlerB.sendMessage(msg)

    // 分发时调用的是 msg.target.dispatchMessage → handlerA.handleMessage
}
```

```kotlin
/** 把消息重定向给另一个 Handler（必须手动改 target） */
fun redirect() {
    val handlerA = Handler(Looper.getMainLooper())
    val handlerB = Handler(Looper.getMainLooper())

    val msg = Message.obtain(handlerA, 1)
    msg.target = handlerB          // ★ 手动改 target
    handlerB.sendMessage(msg)      // 现在由 handlerB 处理
}
```

### 6. 定时任务与生命周期安全

```kotlin
class PollingViewModel : ViewModel() {

    private val handler = Handler(Looper.getMainLooper())
    private val pollTask = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 30_000)   // 每 30 秒轮询
        }
    }

    fun startPolling() {
        handler.removeCallbacks(pollTask)     // ★ 先移除，防止重复
        handler.postDelayed(pollTask, 0)
    }

    fun stopPolling() {
        handler.removeCallbacks(pollTask)
    }

    override fun onCleared() {
        handler.removeCallbacksAndMessages(null)
        super.onCleared()
    }
}
```

### 7. 用 IdleHandler 做延迟初始化（性能优化）

```kotlin
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // ★ 延迟到主线程空闲时初始化（非关键路径）
        Looper.getMainLooper().queue.addIdleHandler {
            AppInitializer.initNonCritical(this)
            false       // 返回 false = 只执行一次，不再重复
        }
    }
}
```

```kotlin
/** 批量合并写入：利用 IdleHandler 攒够一批再写 */
class BatchWriter(private val dao: Dao) {

    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<Item>()
    private val idleHandler = MessageQueue.IdleHandler {
        if (pending.isNotEmpty()) {
            dao.insertAll(pending.toList())   // ★ 批量写
            pending.clear()
        }
        true     // 返回 true = 还想继续处理
    }

    fun add(item: Item) {
        pending.add(item)
        if (pending.size >= 100) {
            handler.removeCallbacksAndMessages(idleHandler)
            dao.insertAll(pending.toList())
            pending.clear()
        } else {
            handler.post(idleHandler)   // 投递 IdleHandler
        }
    }
}
```

### 8. 消息屏障的观察（了解原理用）

```kotlin
/**
 * 反射查看当前是否处于同步屏障状态（仅用于学习/排查，勿用于生产）
 */
fun hasSyncBarrier(looper: Looper): Boolean {
    val queueField = Looper::class.java.getDeclaredField("mQueue")
    queueField.isAccessible = true
    val queue = queueField.get(looper) as MessageQueue

    val barrierField = MessageQueue::class.java.getDeclaredField("mSyncBarrier")
    barrierField.isAccessible = true
    return barrierField.get(queue) != null
}
```

## 常见问题 / 面试要点

**读者问：一条 Message 为什么会分发到多个 Handler？**

**AI答：**

1. **先纠正问题本身**：**实际上，一条 Message 只会由一个 Handler 处理 —— 它的 `target`。** 说"分发到多个 Handler"是不准确的。
2. **机制**：
   - `Message` 有一个 `target` 字段，指向"负责处理它的 Handler"。
   - `Handler.sendMessageDelayed(msg, delay)` 里：
     ```java
     if (msg.target == null) { msg.target = this; }   // ★ 只在 null 时才赋值
     ```
   - 所以**第一次发送**这条消息的 Handler 决定了 `target`，之后**无论谁再 `sendMessage` 这条消息，`target` 都不会变**。
   - `MessageQueue.next()` 取出消息后调用 `msg.target.dispatchMessage(msg)` → 最终执行的是**最初 target** 的 `handleMessage`。
3. **为什么要这样设计**：
   - **消息与发送者解耦**：发送方只管投递，不需要知道谁处理。
   - **支持消息重定向**：可以手动改 `msg.target` 把消息交给另一个 Handler。
   - **保证处理线程正确**：`dispatchMessage` 是在取消息的线程上调用的，Handler 的 Looper 必须就是那个线程的 Looper。
4. **如果真的想让多个 Handler 处理同一条消息**，只有两种办法：
   - 分别创建多条消息，各自 send。
   - 手动改 `msg.target` 后再发送（**注意线程安全性**）。
5. **⚠️ 陷阱**：`handlerB.sendMessage(handlerA.obtainMessage(1))` 会导致**消息由 handlerA 处理**，很多人会以为由 handlerB 处理 → 排查线上问题时容易懵。
6. **面试话术**：**"Message 只会被 target Handler 处理。target 在第一次发送时确定，后续发送不会改变它，所以 MessageQueue 取出后调 msg.target.dispatchMessage。要让别的 Handler 处理，得手动改 msg.target。"**

---

**读者问：MessageQueue 为什么用时间轮？为什么不是 PriorityQueue？**

**AI答：**

1. **MessageQueue 的实际结构**：**不是 PriorityQueue，也不是纯粹的时间轮**，而是一个**按 `when` 升序排列的链表**（`Message.next` 组成链表，`mMessages` 是头指针），插入时**从头遍历找到正确位置**。
2. **和 PriorityQueue 的区别**：
   - `PriorityQueue` 是**二叉堆**，插入 O(log n)、取最小 O(log n)，但**不支持"删除任意元素"**，也不适合"定时到期"语义。
   - MessageQueue 需要**频繁地取最早的消息并出队**，且消息通常**按时间顺序到达**（延迟任务的 `when` 递增）。
3. **为什么链表够用（时间轮思想）**：
   - **Android 消息量级不大**：主线程队列通常只有几十条消息，链表遍历成本可忽略。
   - **插入是 O(n)**：但因为消息基本按时间顺序到达，**大多数情况插在队尾** → 实际接近 O(1)。
   - **出队是 O(1)**：永远取队首（时间最早），出队只需改头指针。
   - **优势**：**没有额外的对象分配**（`PriorityQueue` 内部是 `Object[]` 数组 + `siftUp/siftDown` 操作）；**链表节点就是 Message 本身**（`Message.next` 就是链表指针），**内存开销最小**。
4. **"时间轮"的说法从何而来**：
   - Android 的 AOSP 历史上确实讨论过 **timing wheel** 方案（用于 `MessageQueue` 的定时消息），但最终 AOSP 实现采用**有序链表 + 精确 `nativePollOnce` 阻塞**。
   - **根本原因**：`nativePollOnce` 用 `epoll_wait` 的**精确超时**实现定时器，**不需要轮询**，所以不需要时间轮那种"分桶扫描"的粗粒度设计。
   - 时间轮的优势在于**海量定时器**（如 Netty、Linux 定时器）的 O(1) 插入；Android 主线程消息量远达不到那个量级。
5. **对比表**：
   | 维度 | 有序链表（Android） | PriorityQueue | 时间轮 |
   | --- | --- | --- | --- |
   | 插入 | O(n)（实际接近 O(1)） | O(log n) | O(1) |
   | 取最早 | **O(1)** | O(log n) | O(1) |
   | 内存 | **零额外分配**（复用 Message） | 额外数组 | 桶数组 |
   | 定时精度 | **精确（epoll 超时）** | 需额外定时器 | 粗粒度（需扫描） |
   | 适用 | **消息量小** | 中等 | 海量定时器 |
6. **面试话术**：**"MessageQueue 用的是按 when 排序的有序链表，节点就是 Message 本身，零额外内存分配，出队 O(1)。之所以不用 PriorityQueue，是因为消息量小、插入通常在队尾、且 Message 复用很关键。时间轮适合海量定时器，而 Android 用 epoll 精确超时替代了轮询，精度更高。"**

---

**读者问：同步屏障（Sync Barrier）的原理是什么？为什么要有它？**

**AI答：**

1. **要解决的问题**：Android 每 16.67ms 有一个 VSync 帧。如果此时队列里堆着各种消息（输入事件、业务任务），**渲染消息可能被排在后面** → 掉帧。**同步屏障让"渲染相关消息"优先执行**。
2. **机制（三个要点）**：
   - **① 屏障消息本身不是真实消息**：它是 `MessageQueue.mMessages` 数组**第 0 个槽位**复用的一个 `Message`，特征是 **`target == null`** 且 **`arg1 = token`**。
   - **② 屏障会阻挡"异步消息"**：`Message.async` 字段区分两类消息 —— `post()`/`postDelayed()` 产生的消息 **`async = true`（默认）**；`sendMessageAtTime()` 产生的消息 **`async = false`（同步）**。
   - **③ `next()` 的处理逻辑**：遇到屏障时，**跳过所有 `target == null` 之后的异步消息**，只返回**同步消息**；如果没有同步消息，就**无限期等待**（`nextTurn = -1`），直到屏障被移除。
3. **谁在用**：
   - **`Choreographer`**：每帧开始 `scheduleTraversals()` → `postSyncBarrier()` → 用**同步消息**（`sendMessageAtTime`）请求 traversal。
   - 帧渲染完成 → `removeCallbacksAndMessages(barrierToken)` → **移除屏障** → 被挡住的异步消息恢复执行。
4. **API 层面**：
   - `MessageQueue.postSyncBarrier()` / `removeSyncBarrier(token)` 是 **`@hide`** 的内部 API。
   - `ViewRootImpl.scheduleTraversals()` 会先 post 屏障再发同步消息 —— 这就是"**View 的 requestLayout/invalidate 会优先于普通 post 任务执行**"的根本原因。
5. **实际影响**：
   - 业务 `post {}` 的任务在**有绘制发生时可能被推迟到下一帧**（通常只有几毫秒）。
   - **`Handler(Looper.getMainLooper(), callback)` 的 Callback 机制也是同步的**（`sendMessageAtTime` → 同步消息）→ 能穿透屏障。
6. **面试话术**：**"同步屏障是 Choreographer 每帧插入的一个 target=null 的特殊消息。MessageQueue.next() 遇到屏障时会跳过所有 async=true 的消息，只放行同步消息。VSync 的 traversal Runnable 就是同步消息，所以渲染优先。帧结束后移除屏障，其他消息恢复。效果是保证 16.67ms 帧预算优先给绘制，降低掉帧。"**

---

**读者问：为什么不能在主线程做耗时操作？ANR 是怎么判定的？**

**AI答：**

1. **主线程在做什么**：主线程的消息循环**串行处理所有任务** —— 输入事件、生命周期回调、绘制（`scheduleTraversals`）、业务 `post` 任务，**全部排在同一条队列里**。
2. **耗时操作的后果**：
   - 一条耗时消息会**阻塞整条队列** → 后续的输入事件、绘制全部延迟。
   - **绘制延迟 = 掉帧**。
   - **输入事件延迟超过 5 秒 = ANR**。
3. **ANR 的判定标准（系统层面）**：
   | 场景 | 超时 |
   | --- | --- |
   | **输入事件**（触摸、按键）无响应 | **5 秒** |
   | **前台 Service**（`onStartCommand`）无响应 | **20 秒** |
   | **广播接收**（`onReceive`）无响应 | **10 秒**（前台）/ 60 秒（后台） |
   | **CPU 占用 > 90% 持续 5 秒** | 5 秒 |
4. **ANR 发生时的系统行为**：
   - dump **traces** 到 `/data/anr/traces.txt`（所有线程的栈）。
   - 弹出"应用无响应"对话框；用户可选择等待或关闭（系统可能杀进程）。
5. **排查手段**：
   - **Android Studio Profiler**：抓 main thread 栈。
   - **`adb shell dumpsys activity processes | grep -i anr`**。
   - **线上**：`ActivityManager.getExitReasons()`（API 30+）拿到 `REASON_ANR` 和 traces —— **这是线上 ANR 归因的标准方案**。
6. **实践建议**：
   - 网络、磁盘、数据库 → 子线程 / `Dispatchers.IO`。
   - **主线程初始化**：`Application.onCreate` 里不要做耗时操作（用 `App Startup` 异步/延迟初始化）。
   - **StrictMode**（debug）可开启 `detectDiskReads/Writes/Network` 主动暴露问题。
7. **面试话术**：**"主线程串行处理所有消息，耗时操作会阻塞整个队列，导致绘制延迟（掉帧）和输入事件超时。系统按输入 5 秒、前台服务 20 秒、广播 10 秒判定 ANR。线上用 ApplicationExitInfo 归因，debug 用 StrictMode 和 Profiler 排查。"**

---

**读者问：Looper 的 prepare()、loop()、myLooper() 分别做什么？quit 和 quitSafely 有什么区别？**

**AI答：**

1. **`prepare()`**：
   - 为**当前线程**创建 `Looper`（内部创建 `MessageQueue`），存入 `sThreadLocal`。
   - **一个线程只能调一次**，第二次会抛 `RuntimeException("Only one Looper may be created per thread")`。
2. **`loop()`**：
   - 不断 `queue.next()` 取消息 → `msg.target.dispatchMessage(msg)`。
   - **阻塞式**：`next()` 内部调 `nativePollOnce`，无消息时在 native 层挂起（`epoll_wait`），**不消耗 CPU**。
   - **直到 `next()` 返回 null**（队列 quit）才退出。
   - **必须先 `prepare()`**，否则抛 `No Looper; Looper.prepare() wasn't called on this thread`。
3. **`myLooper()`**：返回**当前线程**的 Looper（`sThreadLocal.get()`），没有则返回 null。
4. **`getMainLooper()`**：返回**主线程**的 Looper（`sMainLooper`，全局唯一）。
5. **`quit()` vs `quitSafely()`**：
   | 维度 | `quit()` | `quitSafely()` |
   | --- | --- | --- |
   | **队列中未到期消息** | **直接丢弃** | **把 `when` 改为 0（立即到期）→ 仍会执行** |
   | 已到期消息 | 不再处理 | 正常处理 |
   | 适用 | 立即停止，不关心剩余任务 | **优雅退出，不丢数据** |
6. **主线程 Looper 不能退出**：`prepareMainLooper()` 传入 `quitAllowed = false`，`quit()` 会返回 `false`。
7. **典型用法（HandlerThread）**：
   ```kotlin
   handlerThread.quitSafely()   // ★ 处理完队列里的消息再停
   ```
8. **面试话术**：**"prepare 给当前线程建 Looper（一个线程只能一个）；loop 是阻塞的消息循环，用 nativePollOnce 挂起不耗 CPU；quit 立即退出丢弃消息，quitSafely 把延迟消息置为立即到期，保证不丢数据。主线程 Looper 禁止退出。"**

---

**读者问：Handler 的 post 和 sendMessage 有什么区别？`msg.callback` 是什么？**

**AI答：**

1. **`post(Runnable)`**：
   ```java
   public final boolean post(Runnable r) {
       return sendMessageDelayed(getPostMessage(r), 0);
   }
   private static Message getPostMessage(Runnable r) {
       Message m = new Message();
       m.callback = r;         // ★ Runnable 存进 callback 字段
       return m;
   }
   ```
   → **消息是异步的（`async = true`）** → **会被同步屏障阻挡**。
2. **`sendMessage(Message)`**：
   - 消息的 `what`/`arg1`/`arg2`/`obj` 由你自己填。
   - **消息是同步的（`async = false`）** → **能穿透同步屏障** → 在有绘制需求时优先执行。
3. **分发时的优先级（`Handler.dispatchMessage`）**：
   ```java
   void dispatchMessage(Message msg) {
       if (msg.callback != null) {
           handleCallback(msg);      // ① 优先：执行 msg.callback.run()
       } else {
           handleMessage(msg);      // ② 否则：调用子类的 handleMessage
   }
   ```
   → **`post` 的 Runnable 走 `callback` 路径，永远不会进 `handleMessage`**。
4. **`Handler.Callback` 接口**：
   - 构造时传入 `Handler(looper, callback)`。
   - `handleCallback` 会**先**调 `mCallback.handleMessage(msg)`，**返回 true 表示已处理完，不再调用 `handleMessage`**。
   - Callback 消息是**同步消息** → 能穿透同步屏障 → 适合"需要立即响应"的场景。
5. **选择建议**：
   | 场景 | 用法 |
   | --- | --- |
   | 简单的 UI 回调 | `post {}` |
   | 需要携带数据、区分类型 | `sendMessage` + `what` |
   | 需要在绘制前执行（如立即刷新动画） | `Handler(looper, callback)` 或 `sendMessageAtTime` |
6. **面试话术**：**"post 会把 Runnable 放进 msg.callback，dispatchMessage 优先执行 callback，走不到 handleMessage；sendMessage 则走 handleMessage。另一个区别是 post 的消息是异步的会被同步屏障挡住，sendMessage 是同步的能穿透屏障。"**

---

**读者问：主线程 Looper 是怎么创建的？`Looper.getMainLooper()` 什么时候可用？**

**AI答：**

1. **创建时机**：`ActivityThread.main()` 里：
   ```java
   public static void main(String[] args) {
       Looper.prepareMainLooper();               // ① 创建主线程 Looper
       ActivityThread thread = new ActivityThread();
       thread.attach(false, startAsServer);      // ② 创建 Application，绑定 AMS
       Looper.loop();                            // ③ 进入消息循环
       ...
   }
   ```
   - `prepareMainLooper()` 创建 Looper 并赋给 `sMainLooper`（**全局唯一**）。
   - `attach()` 里会创建 `Application` 并调用 `Application.onCreate()` —— **此时主线程 Looper 已存在**。
2. **`getMainLooper()` 的可用性**：
   - 在 `Application.onCreate()` 及之后**一直可用**。
   - **在 `attach()` 之前不可用**（如某些 native 初始化阶段），此时 `getMainLooper()` 返回 null → NPE。
   - **ContentProvider 的 `onCreate()` 早于 `Application.onCreate()`**（顺序：`attach` → `installContentProviders` → `Application.onCreate`），但**仍在主线程 Looper 准备好之后** → 可用。
3. **静态初始化时机**：很多 SDK 用 `ContentProvider` 做**自动初始化**（如 `androidx.startup.InitializationProvider`），这样能保证在 `Application.onCreate()` 之前完成初始化。
4. **`Looper.prepare()` 在子线程**：
   ```kotlin
   val t = Thread {
       Looper.prepare()          // ★ 必须手动 prepare
       val handler = Handler(Looper.myLooper())
       handler.post { /* 在这个线程 */ }
       Looper.loop()             // ★ 必须 loop 才会执行
   }
   t.start()
   ```
   - **不调 `loop()` 的话，消息会入队但永远不执行**。
   - **`HandlerThread` 就是把这套逻辑封装好了**（推荐）。
5. **面试话术**：**"主线程 Looper 在 ActivityThread.main() 的 prepareMainLooper() 里创建，然后 attach 创建 Application，最后 loop() 进入循环。所以 Application.onCreate 时 Looper 已就绪。子线程要自己 prepare + loop，或者直接用 HandlerThread。"**

---

**读者问：Handler 机制和协程、LiveData 是什么关系？现代 Android 还用 Handler 吗？**

**AI答：**

1. **Handler 是底层机制，其它都是它的上层封装**：
   ```text
   Handler / Looper / MessageQueue（Framework 底层）
        ↑
   ├─ AsyncTask（已废弃，内部用 Handler + 线程池）
   ├─ LiveData.setValue / postValue
   │     ├─ setValue → 必须在主线程 → 直接分发
   │     └─ postValue → 任意线程 → post 到主线程（用 ArchTaskExecutor + Handler）
   ├─ StateFlow / SharedFlow → collect 在主线程时用 Handler 切回
   ├─ Compose → 内部用 Choreographer + Handler 驱动重组
   └─ Dispatchers.Main → ★ 内部就是 Handler(Looper.getMainLooper())
   ```
2. **`Dispatchers.Main` 的实现**：
   ```kotlin
   // kotlinx-coroutines-android
   public actual object Dispatchers {
       public actual val Main: MainCoroutineDispatcher get() = HandlerContext
   }
   internal object HandlerContext : MainCoroutineDispatcher(), Delay {
       private val handler = Handler(Looper.getMainLooper())
       override fun dispatch(context, block) { handler.post(block) }
       override fun scheduleResumeAfterDelay(timeMillis, block) {
           handler.postDelayed(block, timeMillis)
       }
   }
   ```
   → **`Dispatchers.Main` 底层就是一个主线程 Handler**。
3. **现代用法建议**：
   - **业务逻辑**：用**协程**（`viewModelScope` / `lifecycleScope`），不要直接用 Handler。
   - **UI 更新**：Compose 用 `mutableStateOf` / `StateFlow`；传统 View 用 `LiveData` / `StateFlow`。
   - **Handler 仍需直接使用的场景**：
     - **跨进程通信**（`Messenger` / `Binder`）。
     - **需要"同步消息"（穿透同步屏障）** 的场景。
     - **自己实现 Looper 循环**（如游戏引擎、串行任务队列）。
     - **`HandlerThread` 的串行后台任务**。
4. **面试话术**：**"Handler 是底层调度机制，Dispatchers.Main 就是主线程 Handler 的封装，LiveData.postValue 也是 post 到主线程。业务代码现在应该用协程 + StateFlow，Handler 主要用于跨进程通信和需要同步消息的特殊场景。"**

## 关联笔记

- [[异步方案/Thread与Handler]]：线程池、HandlerThread、AsyncTask 的原理与废弃原因
- [[异步方案/Kotlin协程]]：`Dispatchers.Main` 底层就是主线程 Handler
- [[异步方案/RxJava]]：响应式方案与线程调度
- [[网络框架/OkHttp]]：网络回调切回主线程
- [[网络协议/WebSocket]]：心跳定时任务与 Handler 的关系
- [[./README]]：第 5 部分总览

## 参考资料

- AOSP 源码 `MessageQueue.java` / `Looper.java` / `Handler.java`：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/os/MessageQueue.java
- AOSP `android/os/Message.java`（`target` 字段设计）：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/os/Message.java
- AOSP `android/view/Choreographer.java`（同步屏障的使用）：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/view/Choreographer.java
- Android 开发者文档 "Scheduling background work"：https://developer.android.com/guide/background-work
- 《Android 高级编程》第 4 版 · 消息机制与线程章节
- 深入理解 Android 的 Looper、MessageQueue、Handler（业界经典剖析文章系列）
