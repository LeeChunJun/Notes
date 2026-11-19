---
tags: [android]
part: 第3部分
status: 待整理
difficulty: 中级
created: 2026-11-19
updated: 2026-11-19
related: "[[../README]], [[../传统View系统/绘制流程]], [[../JetpackCompose/声明式UI与重组]]"
---

# Window与WindowManager

## 一句话定义

**Window 是 Android 中"一块显示区域 + 一组属性"的抽象**，Activity、Dialog、悬浮窗本质上都是一个 Window；**WindowManager 是管理 Window 的接口**（`WindowManagerImpl` → `WindowManagerGlobal`），把 View 交给系统时，系统为它创建 **`ViewRootImpl`** 作为"这个窗口与 View 树之间的桥梁"，并通过 **`performTraversals()`** 驱动 measure/layout/draw 真正落到屏幕上。理解 Window 的本质是理解 **"应用怎么把内容交给系统、系统怎么把它画出来"** 这条链路。

## 核心原理

### 1. Window 的抽象与体系

```text
   android.view.Window（抽象类）
        │  持有：WindowManager mWindowManager
        │        WindowManager.LayoutParams mLayoutParams
        │        DecorView mDecor
        │        Callback mCallback（用于把事件回传给 Activity）
        ▼
   PhoneWindow（唯一实现类，在 framework 里）
        │  生成 DecorView：DecorView → ActionBarOverlayLayout → ContentFrameLayout → 你的布局
        ▼
   WindowManagerImpl（ContextWrapper 里的实现）
        │  持有 WindowManagerGlobal 的单例引用
        ▼
   WindowManagerGlobal（进程内单例，真正的跨进程调用点）
        │  mViews: ArrayList<View>       每个 Window 的根 View
        │  mRoots: ArrayList<ViewRootImpl>  每个 Window 的 ViewRootImpl
        │  mParams: ArrayList<LayoutParams>
        ▼
   ViewRootImpl
        │  mWindowSession: IWindowSession（Binder 接口）
        │  mWindowAttributes: WindowManager.LayoutParams
        │  mView: 根 View
        ▼
   Binder → WindowManagerService（system_server）
        │  统一管理所有窗口：层级、焦点、输入分发、动画、绘制
        ▼
   SurfaceFlinger（真正合成上屏）
```

**关键点**：

- `Window` 是**抽象类**，唯一实现是 framework 内部的 **`PhoneWindow`**（App 侧拿不到实例，但能通过 `Activity.getWindow()` 拿到它的接口）。
- `WindowManager` 也是接口，`Context.getSystemService(WINDOW_SERVICE)` 拿到的实现是 **`WindowManagerImpl`**，它内部把操作转给**进程内单例 `WindowManagerGlobal`**。
- **`WindowManagerGlobal` 是进程内单例** → 这就是为什么 `Dialog.getWindow().getWindowManager()` 和 `activity.getWindowManager()` 实际指向同一个对象。

### 2. Window 类型：z-order 与权限门槛

窗口的**层级（z-order）**由 `WindowManager.LayoutParams.type` 决定。数值**越大越靠上**。

| type | 常量 | 层级 | 需要的权限/条件 |
| --- | --- | --- | --- |
| **-2** | `TYPE_APPLICATION` | 基础层 | 无（普通 Activity 窗口） |
| **-1** | `TYPE_APPLICATION_ATTACHED_DIALOG` | 略高于 application | 必须是 Activity 的 Dialog |
| **0** | `TYPE_APPLICATION_OVERLAY` | **高于所有 application 窗口** | **`SYSTEM_ALERT_WINDOW`**（Android 6.0+）或 `SCHEDULE_EXACT_ALARM` 类特殊场景 |
| **1** | `TYPE_SYSTEM_ALERT**` | 更高（已废弃） | — |
| **2** | `TYPE_SYSTEM_OVERLAY**` | 更高（已废弃） | — |
| **3** | `TYPE_SYSTEM_ERROR**` | 更高（已废弃） | — |
| **1000+** | `TYPE_SYSTEM_*`（如 `TYPE_SYSTEM_BOOT_PROGRESS`） | **最高层** | 系统签名权限 |
| **2038 / 2039** | `TYPE_APPLICATION_OVERLAY`（API 26+ 的新定义） | — | `SYSTEM_ALERT_WINDOW` |
| **-1000 / -1001** | `TYPE_INPUT_METHOD` / `TYPE_INPUT_METHOD_DIALOG` | 特殊 | 系统 |

```text
   层级示意（从上到下）
   ┌─────────────────────────────────┐
   │  TYPE_SYSTEM_*      (1000+)     │  系统级（状态栏、Toast、系统对话框）
   ├─────────────────────────────────┤
   │  TYPE_APPLICATION_OVERLAY (0)   │  悬浮窗（需要 SYSTEM_ALERT_WINDOW）
   ├─────────────────────────────────┤
   │  TYPE_APPLICATION  (-2)         │  普通 Activity
   ├─────────────────────────────────┤
   │  TYPE_INPUT_METHOD   (-1000)    │  输入法
   └─────────────────────────────────┘
```

**为什么 Android 8.0 后 `TYPE_APPLICATION_OVERLAY` 变了**：
- Android 8.0 之前，`TYPE_PHONE` / `TYPE_SYSTEM_ALERT` 也能做悬浮窗且**不需要权限**（只要 manifest 声明权限即可）。
- Android 8.0 引入 **`TYPE_APPLICATION_OVERLAY`**，**必须**在运行时申请 `SYSTEM_ALERT_WINDOW` 权限并在设置里授予；老的 `TYPE_PHONE` 等类型**不再被授予**该权限。
- Android 12+ 进一步：**`SYSTEM_ALERT_WINDOW` 是"特殊权限"**，需要 `ACTION_MANAGE_OVERLAY_PERMISSION` 跳设置页由用户手动授予（`requestPermissions` 无效）。

```kotlin
// 判断悬浮窗权限
private fun canDrawOverlay(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)   // API 23+
    } else true
}

// 引导用户去授权
private fun requestOverlayPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }
}
```

### 3. 添加 View 的完整流程：addView → ViewRootImpl → setView → requestLayout

```text
   Activity.onCreate()
        │
        ▼
   setContentView(layoutRes)
        │  Activity → getWindow().setContentView()
        ▼
   PhoneWindow.setContentView()
        │  mDecor = generateDecor()          ← 创建 DecorView（含 ActionBar/Content 容器）
        │  mContentParent = findViewById(android.R.id.content)
        │  mContentParent.removeAllViews()
        │  mContentParent.addView(view)
        ▼
   ViewRootImpl.setView(view, params, hint)
        │
        ├─ ① 检查是否是同一个 View 重复 setView（会抛异常）
        ├─ ② mView = view; mViewAncestor = ...; requestLayout()
        ├─ ③ mWindowAttributes.copyFrom(attrs)   ← 窗口属性（type、token、flags...）
        ├─ ④ 创建 mWindowSession（IWindowSession，Binder 代理）
        └─ ⑤ mAttachInfo = new WindowManager.LayoutParams(...)
        ▼
   ViewRootImpl.requestLayout()
        │  scheduleTraversals()
        ▼
   Choreographer.postCallback(CALLBACK_TRAVERSAL, mTraversalRunnable)
        │
        ▼
   （下一个 Vsync 到来）
   ViewRootImpl.performTraversals()
        ├─ ① relayoutWindow()  → 通过 IWindowSession.relayout() 请求 WMS 重新计算窗口区域
        │      → 得到 WindowFrames（显示区域、可见区域、Insets）
        ├─ ② performMeasure()  → measure 整个 View 树
        ├─ ③ performLayout()   → layout 整个 View 树
        ├─ ④ performDraw()     → draw 整个 View 树 → 录制 DisplayList → 提交 RenderThread
        └─ ⑤ 通过 IWindowSession.finishDraw() 通知 WMS 绘制完成
```

**关键理解**：

- **App 侧 `addView` 只是把 View 挂到 DecorView 上**，真正的"添加到屏幕"发生在 `ViewRootImpl.setView` → `IWindowSession.addToDisplayAsUser()` → WMS。
- **`setView` 之后到第一次绘制之间**，App 会等待 WMS 通过 `relayout` 回传的窗口尺寸。
- **`requestLayout()` 之后不是立即绘制**，而是 `scheduleTraversals()` → `Choreographer` → **下一个 Vsync 帧**才执行 `performTraversals()`。这是 Android 16Hz 刷新率下"动画起始延迟一帧"的原因。

### 4. ViewRootImpl 的 performTraversals 与窗口生命周期

```text
   performTraversals() 的主干
   ┌───────────────────────────────────────────────────────────────┐
   │ ① 处理窗口可见性、insets 变化、是否需要重新 relayout             │
   │    boolean relayoutRequested = hostVisibilityChanged || ...   │
   │    if (relayoutRequested) {                                  │
   │        relayoutWindow()  → IWindowSession.relayout()        │
   │        → 得到 mWindowFrames（frame / cutout / insets）        │
   │        → 通知 ViewRootImpl 的 WindowInsetsCallback            │
   │    }                                                         │
   │ ② performMeasure()   ← 只有 layoutRequested 时才执行           │
   │ ③ performLayout()    ← 同上                                   │
   │ ④ performDraw()      ← 总是执行（含 invalidate 的轻量重绘）      │
   │ ⑤ mWindowSession.finishDraw()                                │
   └───────────────────────────────────────────────────────────────┘
```

**"一次 requestLayout 引发两次 measure" 的经典问题**：

- `requestLayout()` → `scheduleTraversals()`。
- `performTraversals()` 中，如果发现 **`mFirst`（首次布局）或尺寸变化** → 会执行 `measureChildWithMargins`。
- **但同时**：`addView` 时 `ViewGroup.requestLayout()` 也会打标记 → `mLayoutRequested = true` → 一次 traverse 内 **measure 被调用两次**（一次来自 `performMeasure`，一次来自 `measureHierarchy` 前的 `forceMeasure`）。
- 更常见的解释：**`relayoutWindow()` 之后窗口尺寸变了 → 需要重新 measure** → 但 `performTraversals` 已经measure 过一次 → **再测一次**。这在 `DecorView` 的第一次布局时特别明显（DecorView 的尺寸由 WMS 决定，首次是 UNSPECIFIED）。

**窗口生命周期回调（Activity ↔ Window 的交互）**：

| 时机 | Activity | Window / ViewRootImpl |
| --- | --- | --- |
| `onCreate` | `setContentView` | `PhoneWindow.setContentView` → `DecorView` 创建 → `ViewRootImpl.setView` |
| `onStart` | — | `Window.onAttachedToWindow` → `ViewRootImpl` 开始接收输入与绘制 |
| `onResume` | — | 窗口变为 visible → `ViewRootImpl.performTraversals` 开始绘制 |
| `onPause` | — | 窗口停止接收输入事件（`mStopped=true`），但仍可见（可能半透明） |
| `onStop` | — | 窗口不可见 → 停止绘制，但 ViewRootImpl 保留 |
| `onDestroy` | — | `WindowManager.removeViewImmediate` → `ViewRootImpl` 与 WMS 断开 |

**View 的窗口回调**（View 级别，非常重要）：

```text
   View.onAttachedToWindow()   ← View 被挂到 Window 上（此时 getWindowToken() 才非空）
   View.onDetachedFromWindow() ← View 从 Window 移除
   View.onWindowVisibilityChanged(visibility)
   View.onWindowFocusChanged(hasWindowFocus)
```

> ⚠️ **经典坑**：在 View 构造中调用 `getWindowToken()` 返回 **null**（还没 attach）；在 `onAttachedToWindow` 之后调用才有效。**Dialog 的 `setCanceledOnTouchOutside` / `setType` 必须在 `show()` 之前设置**，因为 `show()` 才会 `addView` → `attach` → 此时才有 token。

### 5. 窗口层级（z-order）与 token 校验

**z-order 的决定因素**（从高到低）：

1. **`type`**（`LayoutParams.type`）—— 基础层级。
2. **`flags`**：
   - `FLAG_NOT_FOCUSABLE` / `FLAG_NOT_TOUCHABLE` / `FLAG_NOT_TOUCH_MODAL` —— 影响输入分发但不改层级。
   - **`FLAG_SECURE`** —— 禁止截屏/录屏（`WindowManager.LayoutParams.FLAG_SECURE`）。
   - `FLAG_KEEP_SCREEN_ON`、`FLAG_FULLSCREEN`、`FLAG_LAYOUT_NO_LIMITS` 等。
3. **同 type 内**：
   - **是否是 Activity 的窗口**（有 Activity token 的排在前面）。
   - **添加顺序**（后添加的在上）。
   - **是否有焦点**。
4. **`WindowManager.LayoutParams` 的 `token`**：**决定窗口是否合法**，而不是层级。

**token 机制**：

```text
   什么是 token？
   ┌────────────────────────────────────────────────────────────┐
   │ Activity.attach() 时，系统通过 IWindowSession.attachToken() │
   │ 把 Activity 的一个 IBinder（WindowToken）交给 WMS           │
   │                                                            │
   │ ★ 这个 token 绑定了「一个 Activity 实例」                    │
   │ ★ WMS 校验：非 application 类型的窗口必须携带合法 token      │
   │   否则抛：                                                │
   │   android.view.WindowManager$BadTokenException:            │
   │   Adding window with token null or invalid for service      │
   │   ...                                                      │
   │   或：Only the application with a valid token may add a    │
   │        window                                                │
   └────────────────────────────────────────────────────────────┘
```

**token 校验的两种典型错误**：

| 错误 | 原因 | 解决 |
| --- | --- | --- |
| `BadTokenException: token null` | 在 **Service / BroadcastReceiver** 里直接 `windowManager.addView()`，没有 Activity token | 用 `TYPE_APPLICATION_OVERLAY` + `SYSTEM_ALERT_WINDOW` 权限；或从 Activity 上下文创建 |
| `BadTokenException: token for activity is invalid` | Activity 已经 `isFinishing` / `isDestroyed` | 检查生命周期；或用 `Activity.isFinishing` 判断 |
| `Unable to add window -- token null is not for an application` | 同上 | — |

```kotlin
// 正确的悬浮窗写法（Service 中）
val params = WindowManager.LayoutParams(
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.WRAP_CONTENT,
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    },
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
)
params.gravity = Gravity.TOP or Gravity.START
windowManager.addView(view, params)
```

**焦点（Focus）**：
- 一个屏幕上**同一时刻只有一个窗口有输入焦点**。
- `FLAG_NOT_FOCUSABLE` 的窗口不参与焦点竞争（适合纯展示的悬浮窗）。
- `FLAG_NOT_TOUCH_MODAL`：触摸到这个窗口**之外**的区域时，事件仍然传给下层窗口（**透明遮罩**场景的关键）。

### 6. Dialog 与 PopupWindow 的实现

#### 6.1 Dialog

```text
   Dialog.show()
        │
        ▼
   Dialog.getWindow() → PhoneWindow
        │
        ▼
   WindowManager.addView(decorView, layoutParams)
        │  ★ token 来自 Dialog 的 Context（Activity）→ 合法
        ▼
   ViewRootImpl.setView → performTraversals → 显示
        │
        ▼
   Dialog.dismiss()
        │
        ▼
   WindowManager.removeViewImmediate(decorView, true)
```

- Dialog 的 `LayoutParams.type` 默认是 **`TYPE_APPLICATION`**（-2）。
- `Dialog.setType(TYPE_APPLICATION_OVERLAY)` + `SYSTEM_ALERT_WINDOW` → 可以作为**后台悬浮对话框**（Activity 之外也能弹）。
- Dialog 的 `show()` 必须在**主线程**调用（内部有 `mHandler` 断言）。

#### 6.2 PopupWindow

```text
   PopupWindow.showAtLocation / showAsDropDown
        │
        ▼
   PopupWindow 通过【外部传入的 WindowManager】添加 View
        │  ★ 关键：PopupWindow 自己没有 Context，
        │    必须传入 Activity 的 windowManager
        ▼
   WindowManager.addView(popupDecorView, params)
        │
        ▼
   ViewRootImpl.setView → 独立的一个窗口 → 独立绘制层级
```

**PopupWindow 与 Dialog 的本质区别**：

| 维度 | Dialog | PopupWindow |
| --- | --- | --- |
| **是不是独立 Window** | ✅ 是（有自己的 DecorView + ViewRootImpl） | ✅ **也是**（很多误解） |
| **token 来源** | Dialog 的 Context（Activity） | **外部传入的 WindowManager 的拥有者**（Activity） |
| **能否在 Activity 之外弹出** | 需 `TYPE_APPLICATION_OVERLAY` | 同样需要（因为它也是 addView） |
| **是否有背景遮罩** | 默认有（`dimAmount`） | **没有**（要自己加） |
| **是否阻塞下层触摸** | 是（默认 `FLAG_NOT_TOUCH_MODAL` 未设置） | 由 `flags` 决定 |

> ⚠️ **PopupWindow 必须在 `show()` 之前设置 `width/height`**，否则是 0 尺寸 → 不显示。

**PopupWindow 的内存泄漏**（面试高频）：

```text
   泄漏原因
   ┌──────────────────────────────────────────────────────────────┐
   │ PopupWindow 内部持有一个 View（contentView）                  │
   │ 该 View 持有 Context（Activity）                              │
   │                                                              │
   │ 如果：                                                      │
   │  ① popupWindow.dismiss() 没有调用                            │
   │  ② 或者 dismiss 了但 contentView 里的监听器/Adapter 仍持有    │
   │     Activity 引用                                            │
   │  ③ 或者这个 PopupWindow 被一个静态字段/长生命周期对象持有      │
   │                                                              │
   │ → Activity 无法被 GC → 内存泄漏                              │
   └──────────────────────────────────────────────────────────────┘
```

**正确释放**：

```kotlin
class SafePopupWindow(private val activity: Activity) {

    private var popupWindow: PopupWindow? = null

    fun show(anchor: View) {
        val content = LayoutInflater.from(activity).inflate(R.layout.popup, null)
        popupWindow = PopupWindow(
            content,                 // ⚠️ contentView 持有 Activity
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            contentView.findViewById<View>(R.id.btn)?.setOnClickListener {
                dismiss()
            }
        }
        popupWindow?.showAsDropDown(anchor)
    }

    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null      // ★ 断开引用，避免持有 Activity
    }
}

// 在 Activity 的 onDestroy 中调用
override fun onDestroy() {
    safePopup.dismiss()
    super.onDestroy()
}
```

**更彻底的方案**：让 PopupWindow 的 contentView **不持有 Activity**：
```kotlin
// 用 applicationContext 创建 LayoutInflater，但布局里不要引用 Activity 成员
val inflater = LayoutInflater.from(activity.applicationContext)
```
（但注意：这样会导致主题丢失，通常不可取；实践中以"及时 dismiss + 及时置空"为主。）

### 7. 悬浮窗权限与自启权限

| 权限 | 类型 | 申请方式 | 说明 |
| --- | --- | --- | --- |
| `SYSTEM_ALERT_WINDOW` | **特殊权限（Special / AppOps）** | **不能** `requestPermissions`；必须跳 `ACTION_MANAGE_OVERLAY_PERMISSION` 设置页，用户手动开启 | 悬浮窗的基础 |
| `WRITE_SETTINGS` | 特殊权限 | `ACTION_MANAGE_WRITE_SETTINGS` | 修改系统设置（如修改亮度） |
| `REQUEST_INSTALL_PACKAGES` | 特殊权限 | `ACTION_MANAGE_UNKNOWN_APP_SOURCES` | 安装 APK |
| `PACKAGE_USAGE_STATS` | 特殊权限 | `ACTION_USAGE_ACCESS_SETTINGS` | 读取应用使用情况 |
| `SCHEDULE_EXACT_ALARM` | 特殊权限 | `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` | 精确闹钟 |
| **自启权限** | **厂商 ROM 自定义** | 需跳转厂商设置页（小米/华为/OPPO/vivo） | 部分厂商 ROM 会杀掉后台服务 |

```kotlin
// 悬浮窗权限检查与引导
object OverlayPermissionHelper {

    fun canDraw(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun request(activity: Activity, onResult: (Boolean) -> Unit) {
        if (canDraw(activity)) { onResult(true); return }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        // 回调：onResume 时重新检查
        activity.startActivity(intent)
    }
}
```

**自启权限（厂商 ROM）**：

- **问题**：MIUI / EMUI / ColorOS / OriginOS 等 ROM 会在后台清理时**直接杀死进程**，导致悬浮窗和前台服务被杀掉。
- **解决**：引导用户到**厂商专有的设置页**（如小米"自启动管理"、华为"应用启动管理"）。
- **厂商跳转 Intent**（各家不同，需要 try-catch 兜底）：

  ```kotlin
  fun openAutoStartSettings(context: Context) {
      val candidates = listOf(
          // 小米
          ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
          // 华为 / 荣耀
          ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
          ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
          // OPPO
          ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
          // vivo
          ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
          // 魅族
          ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC")
      )
      for (component in candidates) {
          try {
              context.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              return
          } catch (_: Exception) { /* 试下一个 */ }
      }
      // 兜底：跳应用详情页
      context.startActivity(
          Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.fromParts("package", context.packageName, null))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
  }
  ```

> ⚠️ **面试点**：**`SYSTEM_ALERT_WINDOW` 是特殊权限，不能用 `requestPermissions` 申请**。这是与普通运行时权限最大的区别——很多人写错。

### 8. Edge-to-edge 与 Insets

#### 8.1 Edge-to-edge

```text
   传统模式（默认）
   ┌───────────────────────────────────┐
   │  StatusBar (系统绘制，不透明)        │  ← App 内容在下方
   ├───────────────────────────────────┤
   │                                   │
   │           App 内容区                │
   │                                   │
   ├───────────────────────────────────┤
   │  NavigationBar                    │
   └───────────────────────────────────┘
   → App 拿不到状态栏区域，沉浸感差

   Edge-to-edge 模式
   ┌───────────────────────────────────┐
   │  StatusBar（App 内容延伸到其下方）    │
   │  ┌─────────────────────────────┐  │
   │  │  App 内容（含刘海/挖孔区域）   │  │
   │  └─────────────────────────────┘  │
   │  NavigationBar                    │
   └───────────────────────────────────┘
   → App 需自己用 Insets 避让
```

**Android 15（API 35）的重大变化**：**edge-to-edge 强制启用**——所有 App 的内容都会延伸到系统栏后面，**不再自动 inset**。所有基于 `fitsSystemWindows` 的旧代码都会失效。

```kotlin
// ---------- 传统 View 系统的 Edge-to-edge ----------
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ① API 30+：让内容延伸到系统栏后面
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }

        // ② 设置系统栏图标为深色/浅色
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false   // 浅色背景用深色图标
            isAppearanceLightNavigationBars = false
        }

        setContentView(R.layout.activity_main)
    }
}
```

```xml
<!-- 让 View 消费 Insets，避免重复处理 -->
<FrameLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fitsSystemWindows="true">
    <!-- 系统会自动给这个 View 加 padding -->
</FrameLayout>
```

#### 8.2 WindowInsets 与 Insets 类型

| Insets 类型 | API | 含义 | 典型用途 |
| --- | --- | --- | --- |
| `systemBars()` | 30 | 状态栏 + 导航栏 | 整体避让 |
| `statusBars()` | 30 | 状态栏 | 顶部 padding |
| `navigationBars()` | 30 | 导航栏 | 底部 padding |
| `displayCutout()` | 28 | 刘海 / 挖孔 | 避让摄像头区域 |
| `ime()` | 30 | 软键盘 | 键盘弹出时上推 |
| `systemGestures()` | 29 | 手势区域（全面屏底部） | 避让返回手势 |
| `mandatorySystemGestures()` | 29 | 必须避让的手势区 | — |
| `imeInsets()` | 30 | 键盘 + 导航栏组合 | — |
| `captionBar()` | 30 | 悬浮窗标题栏（投影/车载） | — |
| `windowInsetsAnimation()` | 30 | Insets 动画回调 | 同步动画 |
| `systemGestureExclusionRects()` | 29 | 系统手势排除区 | 横向滑动视频 |

```kotlin
// 手动处理 Insets（View 系统）
ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
    val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
    val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

    view.setPadding(
        bars.left, maxOf(bars.top, cutout.top),
        bars.right, maxOf(bars.bottom, ime.bottom)
    )
    WindowInsetsCompat.CONSUMED     // 标记已消费，避免继续分发
}
```

```kotlin
// Compose 中的 Insets
@Composable
fun MyScreen() {
    val padding = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)   // 合并两类安全区
        .asPaddingValues()

    Column(Modifier.padding(padding)) {
        Text("内容")
    }
}

// 只消费状态栏
Modifier.windowInsetsPadding(WindowInsets.statusBars)

// 键盘弹出时的 imePadding
Modifier.imePadding()
```

**Insets 的分发机制**：

```text
   WMS 计算窗口可见区域
        │
        ▼
   IWindowSession.relayout() 返回 WindowFrames（含 insets）
        │
        ▼
   ViewRootImpl.dispatchApplyWindowInsets(insets)
        │
        ├─ ① 先给 View 自己（mView.onApplyWindowInsets）
        ├─ ② 返回未消费的部分 → 继续分发给子 View（dispatchApplyWindowInsets）
        │     └─ 顺序是"父 → 子"，子 View 优先消费
        ├─ ③ 若有 OnApplyWindowInsetsListener → 优先调用它
        └─ ④ 都不消费 → 默认行为：
             - 没有任何 View 消费 → 整个 View 树会被 padding 系统栏区域
             - 某个 View 消费了 → 该 View 之外的内容可以延伸
```

> 面试点：**`fitsSystemWindows="true"` 的效果是"系统自动给这个 View 加 padding"**，前提是**没有子 View 消费掉 Insets**。这是很多"我设了 fitsSystemWindows 但没效果"的原因——子布局消费了 Insets。

**API 30+ 的 WindowInsetsController**（替代 `systemUiVisibility`）：

```kotlin
val controller = WindowInsetsControllerCompat(window, window.decorView)
controller.show(WindowInsetsCompat.Type.statusBars())        // 显示状态栏
controller.hide(WindowInsetsCompat.Type.navigationBars())   // 隐藏导航栏
controller.systemBarsBehavior =
    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE  // 滑动可恢复
controller.isAppearanceLightStatusBars = true
```

**Immersive 沉浸式（完全隐藏系统栏）**：

```kotlin
// 旧 API
window.decorView.systemUiVisibility = (
    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    or View.SYSTEM_UI_FLAG_FULLSCREEN
    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
)
// 新 API
WindowInsetsControllerCompat(window, window.decorView).apply {
    hide(WindowInsetsCompat.Type.systemBars())
    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}
```

## 代码示例

### 1. 自定义 Window 封装（集中管理 LayoutParams）

```kotlin
class OverlayWindow(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null

    fun show() {
        if (rootView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            throw SecurityException("需要 SYSTEM_ALERT_WINDOW 权限")
        }

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_view, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY   // API 26+
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16; y = 200
        }

        rootView = view
        windowManager.addView(view, params)
    }

    fun dismiss() {
        rootView?.let { windowManager.removeView(it) }
        rootView = null
    }
}
```

### 2. Dialog 的类型设置（后台弹窗）

```kotlin
fun showOverlayDialog(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !Settings.canDrawOverlays(context)) {
        // 引导去设置页授权
        context.startActivity(Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }

    val dialog = Dialog(context).apply {
        setContentView(R.layout.dialog_overlay)
        // ⚠️ 必须在 show() 之前设置 type
        window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
        )
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }
    dialog.show()
}
```

### 3. PopupWindow 的完整实现与防泄漏

```kotlin
class BottomPopup(private val activity: Activity) {

    private var popupWindow: PopupWindow? = null

    fun show(anchor: View) {
        if (popupWindow?.isShowing == true) return

        val content = LayoutInflater.from(activity).inflate(R.layout.popup_content, null)
        val popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.MATCH_PARENT,     // ⚠️ 必须显式设置宽高
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true                                      // focusable
        ).apply {
            isOutsideTouchable = true
            // 圆角背景
            backgroundDrawable = ContextCompat.getDrawable(activity, R.drawable.bg_popup)
            setOnDismissListener { popupWindow = null }
        }

        content.findViewById<View>(R.id.btn_close).setOnClickListener {
            popup.dismiss()
        }

        popupWindow = popup
        popup.showAsDropDown(anchor)   // 或 showAtLocation(anchor, Gravity.CENTER, 0, 0)
    }

    /** ★ 必须在 Activity 销毁时调用，避免持有 Activity 造成泄漏 */
    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
    }
}

// Activity 中
private val popup by lazy { BottomPopup(this) }
override fun onDestroy() {
    popup.dismiss()
    super.onDestroy()
}
```

### 4. Edge-to-edge + Insets 完整处理（View 系统）

```kotlin
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ① 允许内容延伸到系统栏后面
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // ② 系统栏图标样式
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true      // 深色图标（浅色背景）
            isAppearanceLightNavigationBars = true
        }

        // ③ 手动处理 Insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(top = maxOf(bars.top, cutout.top))
            insets   // 返回未消费的部分，继续分发
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // 键盘弹出时用 ime 高度，隐藏时用导航栏高度
            view.updatePadding(bottom = maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }
}
```

```xml
<!-- activity_main.xml -->
<androidx.coordinatorlayout.widget.CoordinatorLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.google.android.material.appbar.AppBarLayout
        android:id="@+id/toolbar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content" />

    <FrameLayout
        android:id="@+id/content"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:layout_behavior="@string/appbar_scrolling_view_behavior" />

    <LinearLayout
        android:id="@+id/bottomBar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:orientation="horizontal" />
</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

### 5. Compose 中的 Window / Insets

```kotlin
@Composable
fun MainScreen() {
    // 系统栏 + 刘海 的合并安全区
    val safePadding = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .asPaddingValues()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("标题") },
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
            )
        },
        bottomBar = {
            BottomBar(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(safePadding)
        ) {
            Text("内容")
        }
    }
}

// 沉浸式：隐藏系统栏
@Composable
fun ImmersiveMode() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, view)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
```

### 6. 自定义 View 的窗口回调（验证 attach 时机）

```kotlin
class MyCustomView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        // ⚠️ 此时还没 attach，getWindowToken() 返回 null
        Log.d("MyView", "init token = ${getWindowToken()}")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // ✅ 此时才在窗口里，token 有效
        val token = getWindowToken()
        Log.d("MyView", "attached token = $token")
        // 可以在这里做需要窗口环境的初始化（如弹 Toast 需要 token）
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 释放资源，避免泄漏
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // 可用于暂停/恢复动画（不可见时停止动画省电）
    }
}
```

## 常见问题 / 面试要点

**读者问：Window 和 WindowManager 分别是什么？`WindowManagerImpl` 和 `WindowManagerGlobal` 是什么关系？**

**AI答：**

1. **Window 是抽象**：表示"一块显示区域 + 一组属性（type/flags/token/size/格式）"。**Activity、Dialog、悬浮窗、Toast、输入法本质上都是一个 Window**。唯一实现是 framework 内部的 `PhoneWindow`。
2. **WindowManager 是接口**：负责**添加/移除/更新窗口**。`Context.getSystemService(WINDOW_SERVICE)` 拿到的实现是 `WindowManagerImpl`（在 `ContextImpl` 里创建）。
3. **`WindowManagerImpl` 本身几乎什么都不做**，它把操作**转发给 `WindowManagerGlobal`** —— 一个**进程内单例**（`WindowManagerGlobal.getInstance()`），持有：
   - `mViews`：每个窗口的根 View 列表
   - `mRoots`：每个窗口的 `ViewRootImpl` 列表
   - `mParams`：每个窗口的 `LayoutParams`
   - `mWindowSession`：`IWindowSession`（Binder 代理）
4. **为什么要 Global 单例**：因为窗口是**进程级的资源**（一个进程可以有多个窗口），需要一个统一的注册表来管理"我这个进程开了哪些窗口"。
5. **最终落到 WMS**：所有跨进程操作都通过 `IWindowSession`（Binder）交给 `system_server` 的 **`WindowManagerService`（WMS）**，由它统一管理**所有进程的窗口**：层级、焦点、输入事件分发、动画、Insets 计算。
6. **面试话术**：
   > "`Window` 是应用和系统之间的边界抽象——App 说"我要一个窗口，类型是 application，尺寸这么大"，系统说"好，我给你在屏幕上留这块区域"。`WindowManagerImpl` 只是转发，真正的状态管理在**进程内的 `WindowManagerGlobal` 单例**，真正的窗口管理在 **system_server 的 WMS**。所以窗口的层级、焦点这些全局状态，只有 WMS 能决定。"

---

**读者问：把 View 加到 Window 上的完整流程是什么？`addView` 到屏幕上显示经历了什么？**

**AI答：**

1. **完整链路**：

   ```text
   setContentView(layoutRes)
     → PhoneWindow.setContentView
       → generateDecor() 创建 DecorView
       → findViewById(android.R.id.content) 得到 ContentFrameLayout
       → contentParent.addView(你的布局)
   （此时 View 只在 DecorView 树里，还没上屏）

   ActivityThread.handleResumeActivity
     → ViewRootImpl.setView(decorView, params)
       ├─ mAttachInfo = new WindowManager.LayoutParams()
       ├─ requestLayout() → scheduleTraversals()
       └─ mWindowSession（Binder）→ WMS.addWindow()
   （窗口已在 WMS 注册，但还没画）

   下一个 Vsync：
   Choreographer → performTraversals()
     ├─ relayoutWindow()  → IWindowSession.relayout() → WMS 计算区域/Insets 回传
     ├─ performMeasure()  → measure 整棵树
     ├─ performLayout()   → layout 整棵树
     ├─ performDraw()     → 录制 DisplayList → RenderThread 绘制 → SurfaceFlinger 合成
     └─ mWindowSession.finishDraw()
   ```
2. **三个关键节点的区别**：
   - `setContentView`：**只改了内存中的 View 树**，屏幕上什么都没变。
   - `ViewRootImpl.setView`：**窗口在 WMS 注册了**，但尺寸未定（还没 relayout）。
   - `performTraversals`：**第一次真正画出来**。
3. **`requestLayout()` 为什么不是立即执行**：`requestLayout` → `scheduleTraversals()` → `Choreographer.postCallback(CALLBACK_TRAVERSAL, ...)` → **等下一个 Vsync 帧**才执行。
   - 这就是为什么"连续调用 `requestLayout()` 多次，一帧内只会 measure 一次"。
   - 也解释了低端设备上 `requestLayout` 后**画面延迟一帧**才更新。
4. **`addView` 到 `ViewRootImpl` 的路径**：`ViewRootImpl` 不是 `addView` 触发的，而是 **`Window.setContentView` → `PhoneWindow` → `DecorView`**，而 `ViewRootImpl` 是在 **`Activity.onCreate` 之后由 `ActivityThread.performLaunchActivity` → `handleResumeActivity`** 时创建的（`WindowManagerGlobal.addView` 内部会为 DecorView 找到/创建 ViewRootImpl）。
5. **面试话术**：
   > "链路是：`setContentView` 只是把 View 挂到 DecorView 上（还没上屏）→ `ViewRootImpl.setView` 把窗口注册到 WMS → 下一个 Vsync，`performTraversals` 里先 `relayout` 拿到窗口尺寸，再 measure/layout/draw。所以**`requestLayout` 不是立刻执行的**，它只是给 Choreographer 打了个标记，真正干活要等下一帧——这也是为什么动画起始会有一帧延迟。"

---

**读者问：`performTraversals` 里为什么会 measure 两次？**

**AI答：**

1. **两个不同的触发源**：
   - `performMeasure()` → `measureHierarchy()` → `measureChildWithMargins()`：把 View 树**从上到下**量一遍。
   - `measureHierarchy()` 内部若发现 **`MeasureSpec` 需要 FORCE_LAYOUT**，会调用 `child.measure(...)` 再次测量。
   - 更常见的原因：**`relayoutWindow()` 返回的新尺寸与上次不同** → `ViewRootImpl` 需要**用新的 MeasureSpec 再测一次**（因为 `relayout` 之前测的尺寸是基于旧窗口尺寸）。
2. **最经典的具体场景**：**首次布局**。
   - `DecorView` 的尺寸由 WMS 决定。第一次 `relayout` 前，View 树还没有尺寸 → `measure` 一次；`relayout` 回来拿到真实尺寸 → **再 measure 一次**。
   - 这就是 `ViewRootImpl` 里 `mFirst` 标志的用途。
3. **另一个来源**：`ViewGroup.addView()` 内部会调 `requestLayout()` → `mLayoutRequested = true`；同时 `setView` 也调了 `requestLayout()` → 一次 traverse 里可能既走 `performMeasure` 又走 `forceMeasure`。
4. **实际影响**：
   - 首次布局多一次 measure → **启动时多几百微秒** → 一般感知不到。
   - 但如果是**频繁的尺寸变化**（如旋转、折叠屏展开、键盘弹出）→ 重复 measure 会**放大掉帧**。
5. **优化手段**：
   - **减少布局层级** → 一次 measure 的成本降下来（见 [[../布局与主题/ConstraintLayout]]）。
   - **避免在 `onMeasure` 里做重活**（如文本测量、复杂计算）→ 重复 measure 会放大它。
   - 用 `ConstraintLayout` 代替嵌套 `LinearLayout`。
6. **面试话术**：
   > "最常见的原因是**首次布局**：`relayoutWindow()` 从 WMS 拿到真实窗口尺寸之前，View 树已经量过一次，拿到新尺寸后必须再量一次。另外 `addView` 和 `setView` 都会打 `requestLayout` 标记，也会造成重复 measure。影响主要在**布局层级深**或**旋转/折叠屏切换**时——所以性能优化里'减少层级'是第一条。"

---

**读者问：Dialog 为什么会 ANR？**

**AI答：**

1. **ANR 的本质**：**主线程被阻塞超过 5 秒**（输入事件）/ **10 秒**（Service 等）→ 系统弹出 ANR 框。
2. **Dialog 场景下的常见原因**：

   | 原因 | 说明 | 解决 |
   | --- | --- | --- |
   | **在主线程做耗时操作** | `dialog.show()` 会触发 `addView` → `ViewRootImpl.setView` → `relayout`（**跨进程 Binder 调用 WMS**）→ 主线程等 WMS 返回 | show() 之前把准备工作做完 |
   | **Dialog 的 contentView 里有重活** | `LayoutInflater.inflate` 会同步创建整棵 View 树 → 若布局里有耗时自定义 View（复杂绘制、加载同步数据） | 预创建 View / 用 ViewStub 延迟 inflate |
   | **在 Dialog 里做同步网络/DB** | 主线程阻塞 | 异步加载 + 进度条 |
   | **主线程死锁** | 持有锁的同时调用 `show()`，而 show 的 Binder 调用又需要别的线程持有的锁 | **绝不要在持锁时调 UI 相关 API** |
   | **自定义 View 的 `onDraw` 极慢** | 首次绘制阻塞 | 预绘制 / 简化绘制 |
   | **在 `onCreate` 中同步读取大量 SP / DB** | 阻塞 | 异步 |

3. **`dialog.show()` 到底做了什么**（会阻塞主线程的环节）：

   ```text
   Dialog.show()
     → mWindowManager.addView(decor, params)
       → WindowManagerGlobal.addView()
         → ViewRootImpl.setView()
           → mWindowSession.addToDisplayAsUser()  ★ Binder 跨进程调用 WMS（阻塞！）
           → requestLayout() → scheduleTraversals()
     → 之后才是异步的 performTraversals
   ```
   - **`addToDisplayAsUser` 是同步 Binder 调用** → 如果 WMS 忙（系统卡顿、大量窗口动画中），主线程会等。
   - 正常情况下这个调用是**毫秒级**的，**不会 ANR**；ANR 几乎总是因为**别的原因把主线程堵住了**（比如你在 show() 前做了一堆耗时操作）。
4. **排查建议**：
   - 抓 **ANR trace**（`/data/anr/traces.txt` 或 `adb shell kill -3 <pid>`），看**主线程栈卡在哪**。
   - 常见栈：`SharedPreferencesImpl.getString`（首次加载 XML）、`SQLiteDatabase.query`、网络 socket read、自定义 View 的 `onMeasure`。
   - 如果栈里有 `WindowManager.addView` → 说明是 **WMS 侧慢**（系统级问题）；如果栈里有你自己的耗时方法 → 是你的问题。
5. **预防**：
   - `show()` 前完成所有数据准备（用 `suspend` 加载完再弹）。
   - 用 `Dialog` 的 `setContentView` 传**已创建好的 View**（避免 inflate 开销）。
   - `ViewStub` 延迟加载。
   - 绝不在主线程做 IO。
6. **面试话术**：
   > "严格说 `dialog.show()` 本身不会 ANR——它只是同步调了一次 Binder（`addToDisplayAsUser`），正常是毫秒级。真正的问题几乎都是**主线程在 show 之前被别的东西堵住了**：同步网络、同步读库、inflate 一个超重的布局、或者在持锁时调 UI API。排查要看 ANR trace 的主线程栈：如果栈里有 `WindowManager.addView`，那可能是系统 WMS 忙；如果有你自己的方法，那就是代码问题。"

---

**读者问：悬浮窗需要什么权限？`SYSTEM_ALERT_WINDOW` 怎么申请？为什么 `requestPermissions` 不行？**

**AI答：**

1. **权限类型**：`SYSTEM_ALERT_WINDOW` 是 **`manifest.permission.SYSTEM_ALERT_WINDOW`**，在 Android 的权限分类里属于 **"特殊权限 / AppOps 权限"（Special Permission）**，**不是运行时权限（Runtime Permission）**。
2. **为什么 `requestPermissions` 不行**：
   - 运行时权限（`CAMERA`、`READ_CONTACTS` 等）由系统的 `PermissionManager` 在**进程内**直接授予/拒绝，应用自己弹框即可。
   - `SYSTEM_ALERT_WINDOW` 属于 **AppOps（AppOpsManager）** 的范畴，**系统不会在应用进程内直接改权限位**；它必须跳到**系统设置页**，由用户在系统 UI 里手动开启（这是一个"设置开关"，不是一个弹窗授权）。
   - 所以 `requestPermissions(Manifest.permission.SYSTEM_ALERT_WINDOW, ...)` → **直接返回 denied，无任何效果**。
3. **正确申请流程**：

   ```kotlin
   // ① 检查
   fun canDrawOverlays(context: Context): Boolean =
       Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

   // ② 引导授权
   fun requestOverlayPermission(activity: Activity) {
       if (canDrawOverlays(activity)) return
       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
           activity.startActivity(
               Intent(
                   Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                   Uri.parse("package:${activity.packageName}")
               )
           )
       }
   }

   // ③ onResume 时重新检查（用户可能已开启或取消）
   override fun onResume() {
       super.onResume()
       if (canDrawOverlays(this)) startFloatingService()
   }
   ```
4. **声明**：
   ```xml
   <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
   ```
5. **版本差异**：

   | API | 允许的 window type | 需要的权限 |
   | --- | --- | --- |
   | ≤ 25 | `TYPE_PHONE` / `TYPE_SYSTEM_ALERT` 等 | manifest 声明即可 |
   | 26~32 | **`TYPE_APPLICATION_OVERLAY`** | **运行时通过设置页授予 `SYSTEM_ALERT_WINDOW`** |
   | 33+ | `TYPE_APPLICATION_OVERLAY` | 同上；**且 Android 14+ 对后台启动悬浮窗更严格** |

   - Android 8.0 之前，`TYPE_PHONE` 等类型**只要 manifest 声明就自动获得**该权限 → 恶意 App 泛滥。
   - Android 8.0 起，**只有 `TYPE_APPLICATION_OVERLAY` 类型**能被授予 `SYSTEM_ALERT_WINDOW`，老类型全部失效。
6. **悬浮窗的其他坑**：
   - **token**：在 Service / Receiver 里用 `TYPE_APPLICATION` 会抛 `BadTokenException` → 必须用 `TYPE_APPLICATION_OVERLAY`。
   - **后台限制**：Android 10+ 后台启动悬浮窗受限（必须先有前台服务或 Activity 在前台）。
   - **厂商 ROM**：MIUI/EMUI 等会额外要求**自启动权限**和**后台弹出界面权限**（`Settings.ACTION_APPLICATION_DETAILS_SETTINGS` 里也有开关）。
   - **Android 12+ 抽屉式（notification drawer）**：可以只申请"在通知抽屉中显示"，不申请完整悬浮窗权限。
7. **面试话术**：
   > "`SYSTEM_ALERT_WINDOW` 是**特殊权限（AppOps）**，不是运行时权限——所以 `requestPermissions` 对它完全无效，必须跳 `ACTION_MANAGE_OVERLAY_PERMISSION` 设置页让用户手动开。另外有个版本分水岭：**Android 8.0 之后只有 `TYPE_APPLICATION_OVERLAY` 能拿到这个权限**，老的 `TYPE_PHONE` 全废了。还有厂商 ROM 的自启动权限，那是另一套。"

---

**读者问：PopupWindow 会内存泄漏吗？为什么？怎么解决？**

**AI答：**

1. **会泄漏，而且很常见**。原因链：

   ```text
   PopupWindow
     └─ 持有 contentView（View）
          └─ View 持有 mContext（Activity）
               └─ Activity 持有 DecorView / ViewRootImpl
   ```
   - 只要 **PopupWindow 对象本身**还被任何**长生命周期对象**引用（静态字段、单例、View 成员变量、Handler 消息、监听器），**Activity 就无法回收**。
   - 更隐蔽的一种：**`dismiss()` 了但 contentView 上的监听器/Adapter 仍持有 Activity**（比如内部用了 `activity as Context` 的 Adapter，回调闭包捕获了 Activity）。
2. **典型泄漏场景**：

   | 场景 | 原因 |
   | --- | --- |
   | `dismiss()` 忘记调用 | PopupWindow 仍被持有 |
   | PopupWindow 存在 Activity 的成员变量里，但 Activity 销毁时没清空 | 成员变量随 Activity 一起回收，其实还好；**但如果 PopupWindow 被提升为静态/单例** | 静态持有 → 泄漏 |
   | contentView 里的 `RecyclerView.Adapter` 用了 `activity` 强引用 | 回调闭包捕获 Activity |
   | 用 `LayoutInflater.from(activity)` inflate 的 View 内部引用了 Activity | 整个 View 树都是 Activity 上下文 |
   | Dialog/PopupWindow 在 `onDismiss` 里又弹新的 | 循环引用 |
3. **解决方案**：

   ```kotlin
   class BottomPopup(private val activity: Activity) {

       private var popupWindow: PopupWindow? = null

       fun show(anchor: View) {
           val content = LayoutInflater.from(activity).inflate(R.layout.popup_content, null)
           val popup = PopupWindow(content, /* 宽 */, /* 高 */, true).apply {
               isOutsideTouchable = true
               setOnDismissListener {
                   // ★ 在 dismiss 时清空 contentView 的引用
                   content.setOnClickListener(null)
                   popupWindow = null
               }
           }
           popupWindow = popup
           popup.showAsDropDown(anchor)
       }

       fun dismiss() {
           popupWindow?.dismiss()
           popupWindow = null      // ★ 立即断开引用
       }
   }

   // ★ Activity 销毁时务必调用
   override fun onDestroy() {
       popup.dismiss()
       super.onDestroy()
   }
   ```
4. **更彻底的方案**：
   - **让 contentView 不持有 Activity**：用 `LayoutInflater.from(appContext)` inflate（代价：丢失 Activity 主题，需手动 `ContextThemeWrapper`）。
   - **把 Activity 引用改为弱引用**：`WeakReference<Activity>`。
   - **用 Compose 的 `Popup`** 替代（Compose 的 Popup 走 Composition 管理，生命周期更安全）。
   - **用 DialogFragment** 替代（Fragment 有自己的生命周期管理，配置变更不泄漏）。
5. **检测泄漏**：
   - Android Studio 的 **Memory Profiler → "Find Leaked Instances"**。
   - LeakCanary（`leakcanary-android`）→ 自动给出泄漏链。
   - 泄漏链里出现 `PopupWindow → DecorView → ... → Activity` 就是它。
6. **面试话术**：
   > "会。PopupWindow 持有 contentView，contentView 持有 Activity 的 Context，**只要 PopupWindow 还被任何长生命周期对象引用，Activity 就回收不掉**。三个防线：**一、及时 dismiss 并把成员置 null；二、在 Activity 的 `onDestroy` 里强制 dismiss；三、contentView 里的 Adapter/回调不要用 Activity 强引用**。如果用 Compose，我会直接用 `Popup`，它的生命周期跟 Composition 绑定，比手工管理安全。"

---

**读者问：窗口的 z-order 是怎么决定的？`type` 和 `flags` 分别起什么作用？**

**AI答：**

1. **`type` 决定基础层级**（数值越大越靠上）：
   - `TYPE_APPLICATION`（-2）< `TYPE_APPLICATION_OVERLAY`（0）< `TYPE_SYSTEM_*`（1000+）。
   - 同一 `type` 内：**Activity 窗口 > 非 Activity 窗口**（WMS 给 Activity 窗口加了"输入优先"标记）；再按**添加顺序**（后加的在上）。
2. **`flags` 主要影响输入分发，不改层级**：

   | flag | 作用 |
   | --- | --- |
   | `FLAG_NOT_FOCUSABLE` | 不参与焦点竞争（不接收键盘输入） |
   | `FLAG_NOT_TOUCHABLE` | 触摸事件直接穿过该窗口 |
   | `FLAG_NOT_TOUCH_MODAL` | 触摸窗口**外部**时，事件继续传给下层窗口 |
   | `FLAG_DIM_BEHIND` | 该窗口后面的内容变暗（Dialog 默认有） |
   | `FLAG_SECURE` | **禁止截屏/录屏**（支付页常用） |
   | `FLAG_LAYOUT_NO_LIMITS` | 允许窗口超出屏幕边界 |
   | `FLAG_KEEP_SCREEN_ON` | 保持屏幕常亮 |
   | `FLAG_FULLSCREEN` | 隐藏状态栏 |
   | `FLAG_LAYOUT_IN_SCREEN` / `FLAG_LAYOUT_NO_LIMITS` | 控制布局区域是否包含系统栏 |

3. **`token` 决定窗口是否合法**（不是层级）：
   - 非 `TYPE_APPLICATION` 的窗口必须携带**有效的 Activity token**（来自 `Activity.attach()`）。
   - 没有 token → `BadTokenException: token null is not for an application`。
   - Service / Receiver 里做悬浮窗 → 必须用 `TYPE_APPLICATION_OVERLAY`（配 `SYSTEM_ALERT_WINDOW`）。
4. **特殊层级场景**：
   - **Toast**：内部用 `TYPE_TOAST`（在 `WMS` 里特殊处理，不占用常规层级）。
   - **输入法**：`TYPE_INPUT_METHOD`（-1000），是**特殊的"高于一切"窗口**，由系统管理。
   - **悬浮窗 vs Dialog**：悬浮窗（`TYPE_APPLICATION_OVERLAY`）会**盖在 Dialog 之上**（Dialog 是 `TYPE_APPLICATION`）→ 这是"弹窗上再盖悬浮窗"能实现的原因。
5. **面试话术**：
   > "三层概念要分清：**`type` 决定基础层级**（数值越大越靠上）；**`flags` 主要管输入分发**（是否接收触摸、是否抢焦点）；**`token` 决定这个窗口合不合法**（非 application 窗口必须有 Activity token）。所以 `BadTokenException` 跟层级无关，是合法性校验失败。"

---

**读者问：Edge-to-edge 是什么？`setDecorFitsSystemWindows` 和 `WindowInsets` 怎么配合？**

**AI答：**

1. **Edge-to-edge**：App 内容**延伸到系统栏（状态栏/导航栏）后面**，由 App 自己负责避让。
2. **触发与演进**：

   | 版本 | 状态 |
   | --- | --- |
   | Android 10（API 29） | 提供 `WindowInsetsController` 等 API，可选启用 |
   | Android 15（API 35） | **强制 edge-to-edge**——所有 App 的内容都会延伸到系统栏后面，**系统不再自动 inset** |
   | Android 16 | 进一步强调"edge-to-edge opt-out 即将移除" |

   > ⚠️ **Android 15 的破坏性影响**：所有依赖 `fitsSystemWindows="true"` 或系统自动 inset 的旧代码都会**出现内容被系统栏遮挡**的问题。这是升级时最常见的 UI bug。
3. **开启方式**：

   ```kotlin
   // 方式 1：API 30+
   window.setDecorFitsSystemWindows(false)
   // 兼容写法（推荐）
   WindowCompat.setDecorFitsSystemWindows(window, false)

   // 方式 2：旧 API
   @Suppress("DEPRECATION")
   window.decorView.systemUiVisibility =
       View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
       View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
       View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
   ```
4. **Insets 的类型与用途**：

   | 类型 | API | 用途 |
   | --- | --- | --- |
   | `systemBars()` | 30 | 状态栏 + 导航栏 |
   | `statusBars()` / `navigationBars()` | 30 | 分别避让 |
   | `displayCutout()` | 28 | 刘海 / 挖孔 |
   | `ime()` | 30 | 软键盘 |
   | `systemGestures()` | 29 | 手势区（全面屏底部） |

5. **Insets 的分发机制**（面试细节）：
   - 从 `ViewRootImpl` 出发，**自顶向下**分发。
   - **子 View 优先消费**（`onApplyWindowInsets` 先问 View 自己，再问 children）。
   - `OnApplyWindowInsetsListener` 优先于 `View.onApplyWindowInsets`。
   - **如果没有任何 View 消费** → 系统会给整个 DecorView **自动加 padding**（这就是 `fitsSystemWindows` 的底层）。
   - **一旦有 View 消费了** → 系统的自动 padding 就**不再生效** → 这就是"我设了 fitsSystemWindows 但没效果"的根因（通常是被子布局消费了）。
6. **正确用法**：

   ```kotlin
   WindowCompat.setDecorFitsSystemWindows(window, false)
   ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
       val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
       val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
       v.setPadding(bars.left, maxOf(bars.top, cutout.top), bars.right, bars.bottom)
       WindowInsetsCompat.CONSUMED
   }
   ```
7. **Compose**：
   ```kotlin
   val padding = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
   ```
8. **面试话术**：
   > "Edge-to-edge 就是内容画到系统栏底下，App 自己用 `WindowInsets` 避让。**Android 15 起强制开启**，这是很多 App 升级后 UI 被遮挡的根因。Insets 的关键机制是**消费制**：`fitsSystemWindows` 的自动 padding 只在**没有任何子 View 消费 Insets** 时生效——所以一旦你在子布局里处理了 Insets，父布局的 `fitsSystemWindows` 就失效了，这个坑非常常见。"

---

**读者问：`Window` 和 `Activity` 是什么关系？Activity 生命周期与 Window 生命周期怎么对应？**

**AI答：**

1. **关系**：**一个 Activity 持有一个 `Window`（PhoneWindow）**。Activity 通过 `getWindow()` 拿到它，通过 `setContentView()` 往它的 `DecorView` 里塞内容。
2. **对应关系**：

   | Activity 回调 | Window / ViewRootImpl 侧 | 说明 |
   | --- | --- | --- |
   | `onCreate` | `setContentView` → DecorView 创建 → **`ViewRootImpl.setView`**（在 `onCreate` 之后由 ActivityThread 处理） | 窗口已注册到 WMS，但还没绘制 |
   | `onStart` | `Window.onAttachedToWindow` → 开始接收输入 | |
   | `onResume` | 窗口变为 visible → `performTraversals` **首次绘制** | 用户能看到界面 |
   | `onPause` | `mStopped = true` → **停止接收输入事件**（触摸无效），但仍可见 | Dialog 弹出时底层 Activity 就是这个状态 |
   | `onStop` | 窗口不可见 → 停止绘制（ViewRootImpl 保留） | |
   | `onDestroy` | `WindowManager.removeViewImmediate` → ViewRootImpl 与 WMS 断开 | View 的 `onDetachedFromWindow` |

3. **两个容易混淆的点**：
   - **`onPause` 不等于不可见**：透明 Dialog 弹出时，下层 Activity 走 `onPause` 但**仍然可见**。
   - **`onStop` 才停止绘制**：但资源（View 树、Bitmap）**仍然占内存** → 这就是 `onStop` 里做资源释放、而不是 `onDestroy` 的原因（Android 8+ `onDestroy` 不再保证调用）。
4. **Dialog 的特殊之处**：
   - Dialog 是一个**独立的 Window**，但它**依附于 Activity**（共享 token）。
   - Dialog 弹出 → 下层 Activity `onPause`（**但不会 `onStop`**，因为 Dialog 是透明的、底层仍可见）。
   - Dialog `dismiss()` → 下层 Activity `onResume`。
5. **面试话术**：
   > "Activity 和 Window 是**一对一**的：一个 Activity 持有一个 PhoneWindow，Window 里装 DecorView，DecorView 里装你的内容。生命周期上，`setContentView` 时 DecorView 创建、ActivityThread 随后创建 ViewRootImpl 并注册到 WMS；`onResume` 才第一次真正绘制；`onPause` 只是**停止接收输入**（透明 Dialog 弹出时就是这样），`onStop` 才停止绘制；`onDestroy` 才真正从 WMS 摘除窗口。"

## 关联笔记

- [[../README]]：第 3 部分总览与窗口管理在 UI 体系中的位置
- [[../传统View系统/绘制流程]]：`ViewRootImpl.performTraversals` 驱动的 measure/layout/draw 三段式
- [[../传统View系统/事件分发机制]]：窗口层级与输入焦点如何影响事件分发
- [[../传统View系统/自定义View]]：`onAttachedToWindow` / `onWindowFocusChanged` 的使用时机
- [[../JetpackCompose/声明式UI与重组]]：Compose 的 `Popup` / `Dialog` 与 Window 体系的关系
- [[../布局与主题/ConstraintLayout]]：减少布局层级可降低重复 measure 的成本
- [[../资源与适配/屏幕适配]]：折叠屏、分屏与窗口尺寸变化对布局的影响
- [[../../02-Android核心组件/README]]：Activity 与四大组件的窗口与生命周期
- [[../../06-系统原理与性能优化/README]]：WMS、Choreographer、RenderThread 的系统级原理

## 参考资料

- Android 官方 Window 文档：https://developer.android.com/reference/android/view/Window
- Android 官方 WindowManager.LayoutParams 文档（type/flags/token）：https://developer.android.com/reference/android/view/WindowManager.LayoutParams
- Android 官方 WindowInsets 与 Edge-to-edge 指南：https://developer.android.com/develop/ui/views/layout/edge-to-edge
- Android 官方 Manage overlay permissions（特殊权限说明）：https://developer.android.com/guide/topics/appops/special-permissions
- AOSP `frameworks/base/core/java/android/view/ViewRootImpl.java`（`performTraversals` 与 `scheduleTraversals` 实现）
