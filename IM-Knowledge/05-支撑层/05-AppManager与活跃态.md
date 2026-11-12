# 05 · AppManager 与活跃态

> 主要源码：`mars/app/`、`mars/baseevent/src/active_logic.cc`

---

## 1. `AppManager` 的职责

`mars/app/app_manager.h/.cc`。**应用适配层**，给其他层提供"这个 App 是什么"的信息：

| 能力 | 方法 | 说明 |
|---|---|---|
| 账号信息 | `GetAccountInfo()` | uin / username —— **直接影响重连退避** |
| 设备信息 | `GetDeviceType()` | 设备名 / 设备类型 |
| 文件路径 | `GetAppFilePath()` | 策略文件落盘目录（Heartbeat.ini 等） |
| 全局配置 | `GetConfig<T>()` / `SetConfig<T>()` | 任意类型的 KV |
| 客户端版本 | `GetClientVersion()` | **必须与长连帧 client_version 一致** |

---

## 2. `ActiveLogic` —— 活跃态 ★

`mars/baseevent/src/active_logic.h/.cc`。

**这是 Mars 里影响网络策略最核心的"App 状态"抽象。**

### 2.1 接口

```cpp
class ActiveLogic {
 public:
    static ActiveLogic* Instance();

    bool IsActive();                              // ★ App 是否"活跃"
    bool IsForeground();                          // ★ App 是否在前台
    uint64_t LastForegroundChangeTime();          // ★ 上次切前台的时间

    boost::signals2::signal<void(bool)> SignalActive;
    boost::signals2::signal<void(bool)> SignalForeground;
};
```

### 2.2 信号的产生

```cpp
// baseevent/src/active_logic.cc:40
GetSignalOnForeground().connect(&ActiveLogic::OnForeground);
```

```cpp
// baseevent/src/baseprj.cc:57
void OnForeground(bool _isforeground) {
    GetSignalOnForeground()(_isforeground);
}
```

Java 侧触发：`BaseEvent.onForeground(boolean)`（native），由 App 在 `Activity.onResume/onPause` 调用。

### 2.3 两条信号的区别

| 信号 | 含义 | 谁关心 |
|---|---|---|
| `SignalForeground(bool)` | 前台 ↔ 后台 | `LongLinkConnectMonitor::__OnSignalForeground` |
| `SignalActive(bool)` | 活跃 ↔ 不活跃 | `LongLinkConnectMonitor::__OnSignalActive` |

> **`IsActive` 是什么？**
> 表示 App 进程是否处于"可正常工作"的状态。
> 典型场景：App 在后台但仍有网络连接能力 → `IsActive() == true`；
> App 被系统限制（Doze 省电模式）→ `IsActive() == false`。
>
> Mars 用 `ActiveLogic` 里的一个额外标记来区分，具体实现涉及平台差异。

---

## 3. 活跃态如何影响网络策略

### 3.1 重连退避（最重要）

`mars/stn/src/longlink_connect_monitor.cc:122-136`：

```cpp
static int __CurActiveState(const ActiveLogic& _activeLogic) {
    if (!_activeLogic.IsActive())   return kInactive;
    if (!_activeLogic.IsForeground()) return kBackgroundActive;
    if (10 * 60 * 1000 <= ::gettickcount() - _activeLogic.LastForegroundChangeTime()) return kForgroundActive;
    if (60 * 1000 <= ::gettickcount() - _activeLogic.LastForegroundChangeTime()) return kForgroundTenMinute;
    return kForgroundOneMinute;
}
```

```
                    ┌─ 不活跃              → kInactive
                    │
   ActiveLogic ─────┼─ 后台但活跃           → kBackgroundActive
                    │
                    └─ 前台
                       ├─ 切前台 < 1min     → kForgroundOneMinute
                       ├─ 切前台 < 10min    → kForgroundTenMinute
                       └─ 切前台 > 10min    → kForgroundActive
```

**对应的重连间隔**（`longlink_connect_monitor.cc:77-81`）：

| 活跃态 | `kTaskConnect`（任务触发） | `kLongLinkConnect`（自动重连） |
|---|---|---|
| 后台活跃 | 5s | **15s** |
| 前台 1 分钟内 | 10s | **30s** |
| 前台 10 分钟内 | 20s | **120s** |
| 前台 > 10 分钟 | 30s | **300s** |
| 不活跃 | 300s | **600s** |

> **IM 体验的核心权衡**：
> - 用户在前台 → 快速重连（30 秒内）→ 消息秒级恢复
> - 用户在后台很久 → 慢速重连（10 分钟）→ 省电省服务端资源
> - **用户从后台切回前台 → `__OnSignalForeground` 立即触发一次重连评估**
>   → **用户切回前台能很快收到消息**，这是很重要的体验点

### 3.2 智能心跳

`mars/stn/src/smart_heartbeat.cc:278-281`：

```cpp
if (ActiveLogic::Instance()->IsActive()) {
    last_heart_ = MinHeartInterval;      // ★ 活跃时固定 3.5 分钟
    return MinHeartInterval;
}
```

> **App 活跃 → 心跳固定 3.5 分钟**（不用学习值）。
> 学习值只在 **App 不活跃（后台）** 时生效 —— 这正是省电的目的。

### 3.3 Doze 模式判定

`smart_heartbeat.cc:249-269`：

```cpp
void SmartHeartbeat::JudgeDozeStyle() {
    if (ActiveLogic::Instance()->IsActive()) return;
    if (!noop_start_tick_.isValid()) return;
    if (kMobile != ::getNetInfo()) return;      // 只在移动网络下判断

    if (std::abs(noop_start_tick_.gettickspan() - last_heart_) >= 20 * 1000) {
        doze_mode_count_++;                    // 实际间隔远大于预期 → doze
        normal_mode_count_ = std::max(normal_mode_count_ - 1, 0);
    } else {
        normal_mode_count_++;
        doze_mode_count_ = std::max(doze_mode_count_ - 1, 0);
    }
    noop_start_tick_ = tickcount_t(false);
}
```

> **Doze 判定**：移动网络下，**实际心跳间隔比预期大 20 秒以上** → 说明 Android 把 CPU 挂起了。
> 需要连续 2 次且占比 > 2/3 才判定为 Doze。

---

## 4. `AppLogic`（Java 侧）

`com/tencent/mars/app/AppLogic.java`：

```java
public class AppLogic {
    public static void setCallBack(ICallBack);

    public interface ICallBack {
        String getAppFilePath();        // ★ Heartbeat.ini 等策略文件的落盘目录
        AccountInfo getAccountInfo();   // ★ 账号信息（影响重连退避）
        int getClientVersion();         // ★ 与长连帧 client_version 一致
        DeviceInfo getDeviceType();
    }

    public static class AccountInfo {
        public String uin;
        public String userName;
    }
    public static class DeviceInfo {
        public String devicename;
        public String devicetype;
    }
}
```

### 4.1 `getAccountInfo()` 的连锁影响 ★

```cpp
// longlink_connect_monitor.cc:146-148
if (!_activelogic.IsActive() && _context->GetManager<AppManager>()->GetAccountInfo().username.empty()) {
    interval = kNoAccountInfoInactiveInterval;   // ★ 7 天
}
```

```cpp
// longlink_connect_monitor.cc:151-153
else if (_context->GetManager<AppManager>()->GetAccountInfo().username.empty()) {
    interval = interval * kNoAccountInfoSaltRate + kNoAccountInfoSaltRise;   // ×2 + 300
}
```

| 条件 | 重连间隔影响 |
|---|---|
| **username 为空 + 不活跃** | **7 天一次** |
| **username 为空 + 活跃** | ×2 + 300 秒 |
| **username 非空** | 正常（+ 0~20 秒抖动） |

> **IM 落地意义**：
> **未登录用户不应该占用 IM 长连接。**
> 登录成功后才建立长连；退出登录时清理长连。
>
> ⚠️ **常见坑**：`getAccountInfo()` 返回空但 `isLogoned()` 返回 true（或反之），
> 会导致策略不一致。**两者必须一致。**

### 4.2 `getAppFilePath()`

决定 `Heartbeat.ini`（智能心跳的学习结果）、`SpecialINI` 等策略文件的落盘位置。

> **必须返回可写目录**。返回空或不可写目录 → 智能心跳无法持久化 → 每次启动都要重新学习。

### 4.3 `getClientVersion()`

```java
// MarsServiceStub.java:307-309
@Override public int getClientVersion() { return clientVersion; }   // = 200
```

> **必须与长连帧的 `client_version` 一致，也必须与服务端一致**。
> 三处不一致 → 长连完全不可用。

---

## 5. `GetConfig<T>` / `SetConfig<T>`

`app_manager.h:49-72`。**任意类型的全局 KV**。

```cpp
template<typename T>
T GetConfig();
template<typename T>
void SetConfig(const T& _value);
```

用途：给 Mars 传递 App 特有的配置（比如自定义的日志路径、自定义的重连参数）。

---

## 6. IM 落地建议

### 6.1 账号切换的正确处理

```java
// 登录成功
public void onLoginSuccess(String uin, String userName) {
    accountInfo.uin = uin;
    accountInfo.userName = userName;
    service.setAccountInfo(uin, userName);   // 通知 :marsservice 进程
    StnLogic.makesureLongLinkConnected();    // 建立长连
}

// 退出登录
public void onLogout() {
    StnLogic.clearTask();     // 清除所有任务
    StnLogic.reset();         // 重建 NetCore，断开长连
    accountInfo.uin = "";
    accountInfo.userName = "";
}
```

> ⚠️ **不调 `reset()` 的话，会残留一条僵尸长连**（占着服务端资源，且账号信息还是旧的）。

### 6.2 前后台切换的正确处理

```java
@Override protected void onResume() {
    super.onResume();
    BaseEvent.onForeground(true);    // ★ 必须
}
@Override protected void onPause() {
    super.onPause();
    BaseEvent.onForeground(false);
}
```

> **不调的后果**：Mars 一直认为 App 不活跃 → 重连退到 10 分钟 → 用户切回前台收不到消息。

### 6.3 `isLogoned()` 与 `getAccountInfo()` 保持一致

```java
@Override public boolean isLogoned() {
    return accountInfo != null && !accountInfo.userName.isEmpty();
}
@Override public AccountInfo getAccountInfo() {
    return accountInfo;
}
```

---

## 7. 分层依赖关系回顾

```
baseevent（信号总线）
    │  OnForeground / OnNetworkChange / OnCreate / OnDestroy
    ▼
app（AppManager / ActiveLogic）
    │  提供账号、活跃态
    ▼
stn（NetCore / LongLinkTaskManager / LongLinkConnectMonitor / SmartHeartbeat）
    │  依赖 app 层做策略决策
    ▼
comm（socket / messagequeue / autobuffer / dns / http）
    ▼
xlog（底层日志）
```

> **依赖方向是单向的**（上层依赖下层）。
> `baseevent` 作为横切层提供信号，**避免了下层直接依赖上层**（依赖倒置）。
>
> **这是 Mars 架构上最值得学的一点**：
> 用 `boost::signals2` 的全局信号总线解耦，
> 比直接函数调用或者回调指针更灵活：
> - 任何层都可以订阅任何信号
> - 支持优先级（`connect(priority, slot)`）
> - 订阅者析构自动退订
