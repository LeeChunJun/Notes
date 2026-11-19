---
tags: [android]
part: 第9部分
status: 待整理
difficulty: 高级
created: 2026-11-19
updated: 2026-11-19
related: "[[ReactNative混合开发]], [[./README]]"
---

# React Native 混合开发

## 一句话定义

React Native 的架构经历了两次范式跃迁：**旧架构（Bridge）** 用一个**异步 JSON 序列化的消息桥**连接 JS 与原生，通信有开销且无法同步调用；**新架构（Fabric + TurboModules + JSI）** 引入 **JSI（JavaScript Interface）**——让 JS 引擎**直接持有 C++ 对象的指针**，原生方法变成**同步的 C++ 函数调用**，省掉序列化与线程切换。新架构下 RN 的性能已接近原生。

## 核心原理

### 1. 架构演进全景

```mermaid
graph TB
    subgraph Old["旧架构 (Bridge)"]
        A1[JS / React 组件树]
        A2[原生模块 NativeModule]
        A3[原生视图 Native UI]
        A1 <-->|JSON 序列化<br/>异步消息队列| B1[Bridge (C++)<br/>UIManager / Modules]
        B1 <-->|JSON 序列化| A2
        B1 <-->|View 层级 diff<br/>Mounting| A3
    end
    subgraph New["新架构 (Fabric + TurboModules + JSI)"]
        C1[JS / React 组件树]
        C2[TurboModule<br/>C++ 接口]
        C3[Fabric Component<br/>C++ 渲染管线]
        C1 <-->|JSI: 同步 C++ 调用<br/>零 JSON| C2
        C1 <-->|JSI: 直接调用| C3
        C3 -->|统一 ShadowNode 树<br/>Mounting| C4[原生视图]
        C2 --> C4
    end
    Old -->|演进| New
```

| 代际 | 时间 | 核心特征 | 遗留问题 |
| --- | --- | --- | --- |
| **Bridge 架构** | 2015–2022 | JS 与原生通过 JSON 消息队列通信 | 异步、需序列化、同步调用不支持、列表性能差 |
| **JSI 引入** | 2021 起 | JS 引擎直接访问 C++ 对象 | 需要逐个模块改造 |
| **Fabric + TurboModules** | 2022 起 | 统一渲染管线 + 类型安全的同步模块 | 需要重写第三方库；需 `newArchEnabled=true` |

### 2. 旧 Bridge 架构的原理与瓶颈

**原理**：

```text
JS 线程                         Bridge 线程(C++)                 原生线程
  │                                │                              │
  │─── JSON.stringify(args) ─────►│                              │
  │                                │── 解析 JSON ───────────────►│
  │                                │                              │ 执行原生逻辑
  │◄──── JSON.stringify(result) ───│◄──── 返回结果 ──────────────│
  │                                │                              │
  └─ 所有通信都是【异步】的，JS 无法同步拿到原生返回值
```

**Bridge 的三条通道**：

| 通道 | 用途 |
| --- | --- |
| `js->native` | JS 调用原生模块 |
| `native->js` | 原生调用 JS（如 `DeviceEventEmitter`） |
| `nativeModules` | 原生模块注册表（懒加载） |

**四大性能瓶颈**：

| 瓶颈 | 说明 | 影响 |
| --- | --- | --- |
| **① JSON 序列化** | 每次调用参数都要 `JSON.stringify` + `parse` | 大对象传输成本高（CPU + 内存） |
| **② 异步且无法同步** | JS 调原生必须 `await`，即使原生方法立刻返回 | **不能同步读取原生属性**（如 `Dimensions.get`），无法同步调用原生 UI 逻辑 |
| **③ 批量且延迟的 UI 更新** | Bridge 把 UI 操作**批量**在 JS 帧结束时发给原生 | 动画与手势**最多延迟一帧**（16.7 ms），是列表滑动"不跟手"的根因 |
| **④ 视图层各自实现** | 每个组件有独立的原生视图，`UIManager` 负责 diff 与 mount | 复杂页面原生视图数爆炸，内存与布局开销大 |

> **关键认知**：Bridge 架构**不是"慢"**，而是**"延迟 + 不可同步"**。动画能跑，但**手势响应天然慢半拍**，这在滑动、拖拽、下拉刷新上体感明显。

### 3. JSI 原理

```text
旧架构：
  JS  ──JSON.stringify──►  Bridge(C++)  ──JSON.parse──►  Native
      ^_______________________________________________|
                    异步 + 序列化 + 线程切换

新架构（JSI）：
  JS  ──直接调用 C++ 函数（同步）──►  Native(C++)
      持有的是 C++ 对象的指针，零序列化
```

**JSI 的关键点**：

1. **JS 引擎（Hermes 或 JSC）中嵌入了一个 C++ 运行时**，JS 代码可以**直接持有 C++ 对象的指针**（`HostObject`）。
2. **方法调用变成普通的 C++ 函数调用**：同步、零序列化、零线程切换。
3. **TurboModule 的接口用 Codegen 生成**：从 TypeScript 接口定义自动生成 **C++ 抽象类**与 **JS 侧的类型安全绑定**。
4. **JS 线程与原生 UI 线程仍然不同**，但因为调用是同步的，**避免了"排队到下一帧"的问题**。
5. **仍然不能阻塞**：JS 线程调用原生时，如果原生方法耗时，仍会卡住 JS 线程——所以 TurboModule 的方法分**同步（sync）**与**异步（async）**两类，同步方法必须是**快速、只读**的。

```kotlin
// TurboModule 的 Kotlin 侧（概念示意）
@ReactModule(name = DeviceInfoModule.NAME)
class DeviceInfoModule(reactContext: ReactApplicationContext) :
    NativeDeviceInfoSpec(reactContext) {

    // 同步方法：JS 直接调用，无 Bridge 开销
    override fun getConstants(): Map<String, Any> =
        mapOf(
            "Dimensions" to mapOf(
                "width" to DisplayMetricsHolder.width,
                "height" to DisplayMetricsHolder.height
            ),
            "fontScale" to context.resources.configuration.fontScale
        )

    // 异步方法：走原生线程池
    override fun getBatteryLevel(promise: Promise) {
        // 在原生侧异步执行，通过 promise 回传
    }

    companion object {
        const val NAME = "DeviceInfo"
    }
}
```

### 4. Fabric：新的渲染管线

```text
旧（Paper）：
  React Element → React ShadowNode → UIManager 批量 diff → 挂载原生 View

新（Fabric）：
  React Element → C++ ShadowNode（统一描述树）
       ↓ 同步（同一棵树在 C++ 与 JS 各有一份）
  Fabric Mounting（原子化提交）→ 原生 View
```

**Fabric 的三个改进**：

| 改进 | 说明 |
| --- | --- |
| **① 统一描述树（ShadowNode）** | 布局计算与挂载分离；**属性变更可以在同一帧内原子提交**，避免"删了旧的、还没挂上新的"中间态 |
| **② 同步属性更新** | 通过 JSI 同步更新属性，**不再需要等 Bridge 批量下发** → 手势响应不再延迟一帧 |
| **③ 更灵活的组件模型** | 支持任意复杂度的原生组件（不限于 View 树），可做 **Fabric Native Component**（自绘组件） |

### 5. Fabric 自定义原生组件

```kotlin
// 自定义 Fabric 组件：直接继承 ViewGroup + 实现 ViewManager
class MyGradientView(context: Context) : View(context) {
    var startColor: Int = Color.TRANSPARENT
    var endColor: Int = Color.TRANSPARENT

    override fun onDraw(canvas: Canvas) {
        val shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            startColor, endColor, Shader.TileMode.CLAMP
        )
        paint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

@ReactModule(name = MyGradientViewManager.NAME)
class MyGradientViewManager : SimpleViewManager<MyGradientView>() {

    // 新架构中 Props 由 Codegen 生成的接口暴露
    @ReactProp(name = "startColor")
    fun setStartColor(view: MyGradientView, color: Int) {
        view.startColor = color
        view.invalidate()
    }

    @ReactProp(name = "endColor")
    fun setEndColor(view: MyGradientView, color: Int) {
        view.endColor = color
        view.invalidate()
    }

    override fun getName(): String = NAME

    companion object {
        const val NAME = "MyGradientView"
    }
}
```

```typescript
// TS 侧：声明 Props（通过 Codegen 生成原生绑定）
import type { ViewProps } from 'react-native';
import type { HostComponent } from 'react-native';
import codegenNativeComponent from 'react-native/Libraries/Utilities/codegenNativeComponent';

interface NativeProps extends ViewProps {
  startColor: string;
  endColor: string;
}

export default codegenNativeComponent<NativeProps>('MyGradientView') as HostComponent<NativeProps>;
```

```tsx
// 使用
<MyGradientView style={{ flex: 1 }} startColor="#FF0000" endColor="#0000FF" />;
```

### 6. TurboModules：类型安全的原生模块

**旧架构 NativeModule 的痛点**：

```javascript
// 旧：完全无类型
const { getUserId } = NativeModules.MyModule;
const id = await getUserId();   // 参数与返回值都没类型
```

**TurboModule 的改进**：

```typescript
// spec 文件：用 Codegen 生成 C++ 抽象类 + JS 绑定
import type {TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export interface Spec extends TurboModule {
  // 同步方法：JS 直接调用
  readonly getConstants: () => {
    fontScale: number;
    statusBarHeight: number;
  };
  // 异步方法：走原生线程
  getBatteryLevel(): Promise<number>;
  openNativePay(orderId: string): Promise<string>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('NativePay');
```

**Codegen 的产物**：
- **Android**：`NativePaySpec`（抽象类，Kotlin）+ `NativePayModule`（实现）；
- **iOS**：`NativePaySpecJSI`（Objective-C++ 协议）+ `NativePay.h`；
- **C++**：`NativePaySpec`（JSI HostObject 绑定）；
- **类型安全**：方法名、参数类型、返回值类型在编译期确定。

**`TurboModuleRegistry.getEnforcing` vs `get`**：
- `get` → 可能返回 `null`（模块不存在）；
- `getEnforcing` → **模块不存在时直接抛异常**（推荐，能尽早暴露问题）。

### 7. 与原生混合

| 场景 | 做法 |
| --- | --- |
| **嵌入 RN 页面（Activity 级）** | 宿主 `Activity` 继承 `ReactActivity`；或用 `ReactActivityDelegate` 自定义 |
| **嵌入 RN 页面（Fragment 级）** | 用 `ReactRootView` + `ReactFragment` |
| **嵌入 RN 页面（View 级）** | `ReactRootView` 直接放进任意原生布局 |
| **原生模块封装** | 写 `NativeModule`（旧）或 `TurboModule`（新），在 `ReactPackage` 里注册 |
| **原生调 JS** | `DeviceEventEmitter`（旧）/ `EventEmitter`（新）或直接调用 TurboModule 的方法 |

```kotlin
// ReactRootView：把 RN 页面嵌进原生布局
class MixedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val reactRootView = ReactRootView(this)
        reactRootView.startReactApplication(
            reactInstanceManager,   // ReactNativeHost 提供的实例管理器
            "MainComponent",        // 注册的组件名
            initialProperties = Bundle().apply {
                putString("userId", "A1024")
                putBoolean("fromNative", true)
            }
        )

        setContentView(
            FrameLayout(this).apply {
                addView(reactRootView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
        )
    }
}
```

```kotlin
// ReactNativeHost：统一管理 React 实例
class AppReactNativeHost : ReactNativeHost(this) {
    override fun getUseDeveloperSupport(): Boolean = BuildConfig.DEBUG

    override fun getPackages(): List<ReactPackage> = listOf(
        MainReactPackage(),          // 核心包
        MyAppReactPackage()          // 自定义包
    )

    override fun getJSMainModuleName(): String = "index"

    override fun getJSBundleFile(): String? =
        if (BuildConfig.DEBUG) null else "index.android.bundle"

    override fun isNewArchEnabled(): Boolean = BuildConfig.IS_NEW_ARCHITECTURE_ENABLED
    override fun isHermesEnabled(): Boolean = BuildConfig.IS_HERMES_ENABLED
}
```

```kotlin
// 自定义包：注册原生模块与视图
class MyAppReactPackage : ReactPackage {
    override fun createNativeModules(reactContext: ReactApplicationContext) =
        listOf(NativePayModule(reactContext), DeviceInfoModule(reactContext))

    override fun createViewManagers(reactContext: ReactApplicationContext) =
        listOf(MyGradientViewManager())
}
```

### 8. 三种跨端通信模型对比

| 维度 | **RN Bridge（旧）** | **RN JSI / TurboModules（新）** | **Flutter PlatformChannel** | **KMP** |
| --- | --- | --- | --- | --- |
| **通信机制** | JSON 消息队列（异步） | **直接 C++ 调用（可同步）** | 二进制消息通道（异步） | **编译期绑定（静态）** |
| **序列化** | JSON（每次） | **零** | StandardMessageCodec | 无（编译期） |
| **同步调用** | ❌ | ✅ | ❌ | ✅（编译期静态绑定） |
| **线程切换** | 每次调用 | 无（同步直调）/ 异步走原生线程池 | 每次调用（跨到主线程） | 无 |
| **类型安全** | ❌ | ✅（Codegen） | ❌（手写）/ ✅（Pigeon） | ✅（编译期） |
| **UI 渲染** | 原生 View（Paper）/ Fabric | Fabric 统一管线 | **Flutter 自绘** | 各平台原生 |
| **调用开销** | 最高 | 最低 | 中 | 最低（零间接） |

## 代码示例

### 1. 原生模块（旧架构 NativeModule）

```kotlin
package com.example.app.rn

import android.content.Intent
import android.provider.Settings
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableNativeMap

class SettingsModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = NAME

    /** 异步方法：参数由 Bridge 序列化过来，返回值通过 promise 回传 */
    @ReactMethod
    fun openAppSettings(promise: Promise) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", reactApplicationContext.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactApplicationContext.startActivity(intent)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("E_OPEN_SETTINGS", e.message, e)
        }
    }

    /** 返回可序列化的常量 */
    @ReactMethod(isBlockingSynchronousMethod = true)
    fun getConstants(): WritableNativeMap {
        val map = WritableNativeMap()
        map.putString("platform", "android")
        map.putInt("sdkInt", android.os.Build.VERSION.SDK_INT)
        return map
    }

    companion object {
        const val NAME = "SettingsModule"
    }
}
```

### 2. TurboModule（新架构）

```kotlin
package com.example.app.rn

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule

/**
 * TurboModule 实现。
 * 需要在 build.gradle 里开启新架构：
 *   newArchEnabled=true
 * 并在 package.json 里配置 codegenConfig。
 *
 * 基类 NativePaySpec 由 Codegen 从 TS 的 Spec 接口生成。
 */
@ReactModule(name = NativePayModule.NAME)
class NativePayModule(reactContext: ReactApplicationContext) :
    NativePaySpec(reactContext) {

    /**
     * 同步常量：JS 直接读取，零 Bridge 开销。
     * 注意：必须在主线程快速返回，不能做耗时操作。
     */
    override fun getTypedExportedConstants(): MutableMap<String, Any> =
        mutableMapOf(
            "platform" to "android",
            "sdkInt" to android.os.Build.VERSION.SDK_INT
        )

    /** 异步方法：走原生线程，promise 回传 */
    override fun getBatteryLevel(promise: Promise) {
        // 实际实现应放到后台线程
        val level = BatteryHelper.readLevel(reactApplicationContext)
        promise.resolve(level)
    }

    override fun openNativePay(orderId: String, promise: Promise) {
        reactApplicationContext.startActivity(
            android.content.Intent(reactApplicationContext, PayActivity::class.java).apply {
                putExtra("orderId", orderId)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        promise.resolve("opened")
    }

    // TurboModule 需要这两个方法用于事件监听（EventEmitter）
    override fun addListener(eventName: String) { /* 记录 listener 数量 */ }

    override fun removeListeners(count: Double) { /* 移除 listener */ }

    companion object {
        const val NAME = "NativePay"
    }
}
```

```typescript
// specs/NativePay.ts —— Codegen 的输入
import type {TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export interface Spec extends TurboModule {
  readonly getConstants: () => {
    platform: string;
    sdkInt: number;
  };
  getBatteryLevel(): Promise<number>;
  openNativePay(orderId: string): Promise<string>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('NativePay');
```

```json
// package.json —— 开启 Codegen
{
  "name": "MyApp",
  "codegenConfig": {
    "name": "AppSpecs",
    "type": "modules",
    "jsSrcsDir": "specs",
    "android": {
      "javaPackageName": "com.example.app.rn.specs"
    }
  }
}
```

### 3. 旧架构下的事件通信（原生 → JS）

```kotlin
// 原生侧发送事件
class SensorModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private var listenerCount = 0

    override fun addListener(eventName: String?) {
        listenerCount++
        // 真实场景在这里注册传感器
    }

    override fun removeListeners(count: Int) {
        listenerCount -= count
    }

    @ReactMethod
    fun startSensing() {
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            val value = event?.values?.firstOrNull() ?: 0f
            // 通过 DeviceEventEmitter 发出
            reactApplicationContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("SensorChanged", mapOf("value" to value))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
}
```

```typescript
// JS 侧订阅
import { NativeEventEmitter, NativeModules } from 'react-native';

const emitter = new NativeEventEmitter(NativeModules.SensorModule);

useEffect(() => {
  const sub = emitter.addListener('SensorChanged', (event: { value: number }) => {
    console.log('sensor:', event.value);
  });
  return () => sub.remove();   // 必须在卸载时移除，否则内存泄漏
}, []);
```

### 4. 混合栈：原生调 RN

```kotlin
package com.example.app.rn

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.facebook.react.ReactRootView
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class RnHostActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 关键：RN 需要 Activity 生命周期回调（onPause/onResume/onDestroy）
        reactDelegate.onCreate(savedInstanceState)

        val rootView = ReactRootView(this)
        rootView.startReactApplication(
            reactInstanceManager,
            "MainComponent",
            Bundle().apply {
                putString("source", "native")
                putInt("tab", 1)
            }
        )
        setContentView(rootView)
    }

    override fun onResume() {
        super.onResume()
        reactDelegate.onResume()
    }

    override fun onPause() {
        super.onPause()
        reactDelegate.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        reactDelegate.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        reactDelegate.onActivityResult(requestCode, resultCode, data)
    }

    private val reactDelegate: ReactActivityDelegate by lazy {
        DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)
    }

    private val mainComponentName: String = "MainComponent"

    private val reactInstanceManager get() =
        (application as MyApplication).reactNativeHost.reactInstanceManager
}
```

```tsx
// JS 侧：接收原生传入的 initialProperties
import React, { useEffect } from 'react';
import { NativeEventEmitter, NativeModules } from 'react-native';

export default function App({ source, tab }: { source: string; tab: number }) {
  useEffect(() => {
    // initialProperties 只在挂载时通过 props 传入
    console.log('launched from:', source, 'tab:', tab);
  }, []);

  return (
    <View style={{ flex: 1 }}>
      {/* RN UI */}
    </View>
  );
}
```

### 5. Fabric 自定义原生组件（完整）

```kotlin
package com.example.app.rn

import android.content.Context
import android.graphics.*
import android.view.View
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanewarchitecture.ViewManagerDelegate
import com.facebook.react.uimanewarchitecture.ViewManagerWithGeneratedInterface
import com.facebook.react.uimanewarchitecture.components.ViewManagerInterface
import com.facebook.react.viewmanagers.SimpleViewManager
import com.facebook.react.viewmanagers.ThemedReactContext

/**
 * 自绘渐变组件：Fabric 下由 ShadowNode 描述、原子提交。
 * Props 通过 Codegen 生成的接口接收。
 */
@ReactModule(name = GradientViewManager.NAME)
class GradientViewManager :
    SimpleViewManager<GradientView>(),
    ViewManagerWithGeneratedInterface<GradientView> {

    private val delegate: ViewManagerDelegate<GradientView> =
        GradientViewManagerInterface.setterForStartColor { view, value: Int ->
            view.startColor = value
            view.invalidate()
        }.setterForEndColor { view, value: Int ->
            view.endColor = value
            view.invalidate()
        }

    override fun getDelegate(): ViewManagerDelegate<GradientView> = delegate

    override fun getName(): String = NAME

    override fun createViewInstance(context: ThemedReactContext): GradientView =
        GradientView(context)

    companion object {
        const val NAME = "GradientView"
    }
}

class GradientView(context: Context) : View(context) {
    var startColor: Int = Color.TRANSPARENT
    var endColor: Int = Color.TRANSPARENT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            startColor, endColor, Shader.TileMode.CLAMP
        )
        paint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
```

### 6. 新旧架构的构建配置

```groovy
// android/app/build.gradle
android {
    buildFeatures {
        buildConfig true
    }
    defaultConfig {
        // 开启新架构
        buildConfigField "boolean", "IS_NEW_ARCHITECTURE_ENABLED", "true"
        // Hermes 引擎（JSI 依赖 Hermes 或 JSC）
        buildConfigField "boolean", "IS_HERMES_ENABLED", "true"
    }
}

dependencies {
    implementation("com.facebook.react:react-android")   // 由 RN Gradle 插件自动带入版本
    implementation("com.facebook.react:hermes-android")
}
```

```properties
# android/gradle.properties
newArchEnabled=true
hermesEnabled=true
```

```typescript
// react-native.config.js —— 接入已有原生工程
module.exports = {
  project: {
    android: {
      sourceDir: './android',
      packageImportPath: 'import com.example.app.MainApplication;',
      packageInstance: 'new MainApplication(getApplicationContext(), ReactNativeHost.getDefaultReactHost(getApplicationContext()))',
    },
  },
};
```

```bash
# 启用新架构后需要重新生成原生代码
cd android && ./gradlew clean
cd .. && npx react-native codegen

# 打包
npx react-native bundle --platform android --dev false \
  --entry-file index.js --bundle-output android/app/src/main/assets/index.android.bundle \
  --assets-dest android/app/src/main/res

# 或者用 CLI
npx react-native build-android --mode release
```

## 常见问题 / 面试要点

**读者问：RN 的新旧架构有什么区别？为什么要有 Fabric 和 TurboModules？**

**AI答：**

1. **旧架构（Bridge / Paper）的问题**：
   - **异步且不可同步**：JS 调原生必须 `await`，无法同步读取原生属性；
   - **JSON 序列化开销**：每次调用的参数与返回值都要 `stringify/parse`；
   - **UI 更新延迟一帧**：Bridge 把 UI 操作批量在 JS 帧末下发，**手势响应天然慢半拍**，长列表滑动掉帧；
   - **视图管理分散**：每个组件独立实现原生视图，`UIManager` 负责 diff 与挂载，复杂页面原生视图数爆炸；
   - **无类型安全**：方法名与参数都是运行时约定。
2. **新架构的三根支柱**：
   - **JSI**：JS 引擎内嵌 C++ 运行时，JS 可**直接持有 C++ 对象指针** → **原生方法变成同步的 C++ 函数调用，零 JSON、零线程切换**。
   - **TurboModules**：基于 JSI 的**类型安全原生模块**。用 **Codegen** 从 TS 的 `Spec` 接口自动生成 **C++ 抽象类**、**Android Kotlin 抽象类**、**iOS Objective-C++ 协议** → 方法签名、参数、返回值**编译期确定**。
   - **Fabric**：新的渲染管线。**ShadowNode 树在 C++ 与 JS 各有一份且同步**，属性更新**同步下发**，并**原子提交挂载**（避免中间态）。
3. **收益**：
   - **手势响应不再延迟一帧**（动画、拖拽、滑动跟手）；
   - **通信开销大幅下降**（同步 C++ 调用 vs JSON 消息）；
   - **类型安全 + 更好的 IDE 补全**；
   - **支持任意复杂度的原生组件**（可自绘）；
   - **为 React Server Components 等未来特性铺路**（同步调用是必要条件）。
4. **代价与现状**：
   - **需要改造第三方库**（老库不支持新架构）；
   - **`newArchEnabled=true` 才能启用**；
   - **同步方法必须快速、只读**——同步调用会阻塞 JS 线程，不能做 IO；
   - **iOS 侧需要重新编译 Pods**（`RCT_NEW_ARCH_ENABLED=1`）。
5. **面试话术**：**"RN 新架构的本质改进是什么？"** —— 一句话：**把"跨语言的异步消息传递"变成"同语言的同步函数调用"**。JSI 让 JS 引擎直接能调 C++，于是 TurboModule 的同步方法、Fabric 的同步属性更新都成为可能。**旧架构的延迟和序列化开销，本质上都是"跨语言边界"的代价；新架构把这个边界消除了。**

---

**读者问：Bridge 架构为什么是异步的？为什么 RN 早期不能同步调用原生？**

**AI答：**

1. **根本原因：Bridge 是一个独立线程上的消息队列**。
   - JS 运行在 **JS 线程**；
   - 原生 UI 操作必须在 **UI 主线程**；
   - Bridge 作为一个**中间层线程**，负责把 JS 的调用**排队**并转发到主线程，执行完再把结果排回 JS 线程。
2. **如果做成同步会怎样**：
   - JS 线程调用原生 → **必须阻塞等待主线程执行完** → 主线程此时若反过来要调 JS（比如动画回调、触摸事件分发），就形成**死锁**；
   - 加上 Bridge 线程的存在就是为了**避免主线程与 JS 线程互相等待**。
3. **异步带来的具体限制**：
   - **不能同步读取原生属性**：`Dimensions.get('window')` 在旧架构里是异步的（只能监听 `Dimensions` 变化事件）；
   - **不能同步执行原生 UI 操作**（如同步调用原生动画）；
   - **每次调用都是"排队 + 线程切换 + JSON 序列化"** → 开销。
4. **JSI 如何解决**：因为 **JS 引擎内部就能调用 C++ 函数**，调用发生在 **JS 线程自己身上**，**不需要跨到主线程**（只要原生方法本身不碰 UI 且足够快）。这就消除了"必须异步"的架构约束。
5. **面试话术**：**"RN 新架构为什么能支持同步调用？旧的不行？"** —— 旧架构的调用必须**从 JS 线程跨到主线程**，而两个线程互相同步等待会死锁，所以只能异步；JSI 让 JS **在自己的线程内直接调 C++**，不涉及跨线程等待，因此同步调用在架构上是安全的（前提是原生方法足够快、不触碰 UI）。

---

**读者问：JSI 的原理是什么？和普通 FFI 有什么区别？**

**AI答：**

1. **JSI（JavaScript Interface）**：一个**嵌入到 JS 引擎中的 C++ 运行时**，让 JS 代码可以**直接持有并操作 C++ 对象**。
2. **工作方式**：
   - JS 侧拿到一个 `HostObject` 包装（`jsi::Object`），它内部持有一个 `void*` 指针（`HostObject::get()`）；
   - JS 调用其方法时，**直接进入 C++ 函数**；
   - C++ 可以把任意值（字符串、数字、对象、数组）**零拷贝**地暴露给 JS（`jsi::Value`、`jsi::Function`、`jsi::Array`）。
3. **与普通 FFI（如 Node 的 N-API、Python 的 Cython）的区别**：
   - **运行环境**：JSI 嵌在**移动端的 JS 引擎**（Hermes / JSC）里，是**运行时的一部分**，不是外挂；
   - **对象生命周期**：JS 持有的 C++ 指针由 `HostObject` 的析构函数管理（`invalidate()` 时释放），需正确处理；
   - **线程约束**：JSI 对象**绑定创建它的 JS 线程**，跨线程使用需要额外的同步机制；
   - **GC 交互**：C++ 侧持有 JS 引用需要 `Runtime::global()` 之类的机制管理生命周期。
4. **对 RN 的意义**：
   - **TurboModule**：C++ 侧实现 `Spec` 抽象类，通过 JSI 暴露给 JS；同步方法零开销；
   - **Fabric**：ShadowNode 树在 C++ 与 JS 各一份，通过 JSI 保持同步；
   - **同步能力是前提**——没有 JSI，React Server Components、并发渲染等特性都无法实现。
5. **面试话术**：**"JSI 解决了什么本质问题？"** —— 它让 **JS 与原生不再是"两个通过消息队列通信的系统"，而是"同一个进程里可以互相调用的两套运行时"**。序列化与跨线程这两个最大的开销来源被彻底消除。**代价是**：JS 与原生的内存模型要在 C++ 层显式管理（指针生命周期、线程约束），复杂度从 JS 侧转移到了 C++ 侧。

---

**读者问：RN 混合开发怎么在原生页面里嵌入 RN？**

**AI答：**

1. **三种嵌入层级**：
   - **Activity 级**：继承 `ReactActivity`（最简单，整个 Activity 是 RN）；或用 `ReactActivityDelegate` 自定义（保留原生的 `onCreate` 等逻辑）。
   - **Fragment 级**：用 `ReactRootView` + `Fragment`，可与原生 Fragment 混排。
   - **View 级**：`ReactRootView` 放进任意原生布局（LinearLayout / CoordinatorLayout / 甚至 Compose 的 `AndroidView`），灵活度最高。
2. **`ReactRootView` 的关键 API**：
   ```kotlin
   val rootView = ReactRootView(context)
   rootView.startReactApplication(
       reactInstanceManager,   // ReactNativeHost 提供
       "MainComponent",        // 注册的组件名
       initialProperties       // 传给 JS 的初始参数
   )
   ```
3. **必须处理 Activity 生命周期**：`ReactActivity` 通过 `ReactActivityDelegate` 把 `onResume/onPause/onDestroy/onActivityResult/onNewIntent` 转发给 RN。**用 `ReactRootView` 手动嵌入时也必须自己转发**，否则：
   - RN 不会暂停/恢复（浪费 CPU、后台仍跑 JS）；
   - `ActivityResult`（相机、支付）收不到；
   - 内存泄漏。
4. **通信**：
   - **原生 → JS**：`initialProperties`（启动参数）或 `DeviceEventEmitter`（运行期事件）；
   - **JS → 原生**：调用原生模块（NativeModule / TurboModule）；
   - **JS 侧接收 `initialProperties`**：作为根组件的 `props`。
5. **新架构下的注意事项**：
   - 必须 `newArchEnabled=true`；
   - `ReactInstanceManager` 与 Fabric 渲染器要正确初始化（`DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)`）；
   - 第三方组件若不支持新架构需降级或换实现。
6. **面试话术**：**"在原生 Activity 里嵌 RN，最容易忘什么？"** —— **生命周期转发**。很多 App 表现为"从 RN 页返回后，RN 还在后台跑定时器/定位/推送"，或者"拍照返回拿不到结果"，根源就是 `onPause`/`onActivityResult` 没转发给 `ReactActivityDelegate`。

---

**读者问：RN 和 Flutter 怎么选？**

**AI答：**

1. **先看两个硬约束**：
   - **团队技术栈**：有没有现成的 RN/React 团队？有没有 Kotlin/Dart 人才？
   - **iOS 是核心市场吗**？→ 决定要不要为"UI 一致性"付代价。
2. **逐维度对比**：

| 维度 | **React Native** | **Flutter** |
| --- | --- | --- |
| **语言** | TS/JS（团队易招） | Dart（要重新培养） |
| **UI 一致性** | 高（Fabric 自绘） | **极高**（完全自绘） |
| **性能** | 新架构接近原生；旧架构有延迟 | **稳定 60/120 fps** |
| **首屏启动** | 一般（要加载 JS Bundle，Hermes 预编译后尚可） | 较好（AOT 快照） |
| **热重载** | ✅ 成熟（Fast Refresh） | ✅ 成熟 |
| **生态** | **巨大**（npm + 社区） | **大**（pub.dev） |
| **原生集成** | 需写原生模块（Java/Kotlin/ObjC） | 需写 Platform Channel（Dart） |
| **平台能力（IME/无障碍/推送）** | **较成熟**（复用原生） | 需自己接（生态已补齐） |
| **包体** | 较小（Hermes 约 10–20 MB） | 较大（引擎 10–20 MB） |
| **Web** | ✅ React Native Web | ✅ Flutter Web |
| **热更新（CodePush）** | ✅ 成熟 | ⚠️ 需自建（iOS 政策限制） |
| **招聘与协作** | 前后端统一语言 | 独立技术栈 |
3. **选 RN**：
   - **团队已有 React/TS 能力**（前端团队可直接转）；
   - **需要 CodePush 热更新**（不发版就能修 JS Bug——这是 RN 的独特优势，虽然 iOS 上政策有收紧）；
   - **依赖大量 npm 生态**；
   - **深度复用原生能力**（原生团队维护的模块很多）。
4. **选 Flutter**：
   - **要求 UI/动画跨端完全一致**（复杂动效、自定义绘制）；
   - **性能是硬指标**（动画密集、60/120 fps 稳定）；
   - **愿意承担 Dart 的学习成本**；
   - **UI 复用率要求极高**。
5. **不建议的判断依据**："哪个性能更好"（新架构下 RN 已接近原生）；应该看**团队能力、UI 一致性要求、热更新需求**。
6. **面试话术**：**"RN 和 Flutter 你会怎么选？"** —— 三个问题决定：**① 团队是什么技术栈**（有 React 团队 → RN；愿意学 Dart → Flutter）；**② UI 一致性是不是硬需求**（是 → Flutter）；**③ 需不需要热更新**（是 → RN 明显占优）。如果这三点都不极端，我会建议**看长期维护成本**：RN 的第三方库在新架构下适配进度参差不齐，Flutter 的自绘引擎一致性更可控。

---

**读者问：RN 的性能问题主要出在哪里？新架构完全解决了吗？**

**AI答：**

1. **旧架构的性能问题**：
   - **通信开销**：JSON 序列化 + 线程切换 + 消息队列排队；
   - **UI 更新延迟一帧**：Bridge 批量下发 UI 操作，手势响应慢半拍；
   - **原生视图数膨胀**：复杂页面有大量原生 View，内存与布局开销大；
   - **列表性能**：ScrollView 里放几十个复杂卡片会明显卡顿。
2. **新架构改善了什么**：
   - **JSI 同步调用**：通信开销大幅下降；
   - **Fabric 原子提交 + 同步属性更新**：消除"延迟一帧"；
   - **扁平化原生视图**：Fabric 组件内部可用单个原生 View 自绘复杂内容。
3. **新架构没有完全解决的**：
   - **JS 线程仍是单线程**：任何同步的 JS 计算都会阻塞 UI（`newArch` 也救不了）；
   - **长列表仍需优化**：复杂 item 仍应扁平化、避免在 `render` 里做重计算、用 `FlatList` 的 `getItemLayout` 与 `removeClippedSubviews`；
   - **图片加载**：仍依赖 `react-native-image` / `FastImage` 等三方方案；
   - **第三方库适配参差**：不支持新架构的库会退回旧路径，反而引入**双架构的复杂度**；
   - **启动时间**：仍需加载 JS Bundle（Hermes 预编译 + Bundle 内联可缓解）。
4. **优化清单**：
   - 开启 **Hermes 引擎**（更快启动、更低内存）；
   - **JS Bundle 内联到 APK**（`bundleInRelease`），避免首次启动下载；
   - 列表用 `FlatList`/`SectionList` + `getItemLayout` + `keyExtractor` 稳定 key；
   - `render` 里**不要做数据转换**，用 `useMemo`；
   - 图片用合适尺寸 + 缓存；
   - **避免大列表里嵌 `ScrollView`**。
5. **面试话术**：**"新架构后 RN 性能问题解决了吗？"** —— **通信和 UI 提交这块解决了**（这是旧架构最大的两个坑），但**JS 单线程的本质没变**，所以"把重计算搬到异步"这件事在新架构下同样重要。另外**新架构带来迁移成本**——不支持新架构的三方库会走兼容层，反而可能更复杂。

## 关联笔记

- [[./Flutter与原生混合编译/工程集成]]：Flutter add-to-app 的集成方式与引擎管理
- [[./Flutter与原生混合编译/PlatformChannel]]：Flutter 的通信模型（对比 RN Bridge / JSI）
- [[./Flutter与原生混合编译/混合栈管理]]：Flutter 混合栈的页面承载与路由
- [[./KotlinMultiplatform/KMP与原生集成]]：KMP 走编译期共享逻辑的路线
- [[./ComposeMultiplatform/ComposeMultiplatform]]：Compose Multiplatform 的跨平台方案
- [[../07-架构、工具与生态/架构模式/MVVM]]：跨端 MVVM 的组织方式
- [[./README]]：第 9 部分总览

## 参考资料

- React Native 新架构文档：https://reactnative.dev/docs/new-architecture-intro
- React Native TurboModule 文档：https://reactnative.dev/docs/turbo-native-modules-introduction
- React Native Fabric 文档：https://reactnative.dev/docs/fabric-native-components-introduction
- React Native 源码仓库：https://github.com/facebook/react-native
- Hermes 引擎文档：https://reactnative.dev/docs/hermes
- JSI 仓库（JS 引擎侧 C++ 运行时）：https://github.com/facebook/react-native/tree/main/packages/react-native/ReactCommon/jsi
