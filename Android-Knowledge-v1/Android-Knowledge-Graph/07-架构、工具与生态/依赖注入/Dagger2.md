---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 高级
created: 2026-11-04
updated: 2026-11-04
related: "[[Hilt]], [[Koin]], [[../架构模式/MVVM]]"
---

# Dagger2

## 一句话定义

Dagger 2 是一个**基于注解处理器的编译期依赖注入框架**：在编译阶段通过注解处理器分析 `@Inject`/`@Module` 声明，**生成 Java/Kotlin 代码**完成对象图的装配，从而在没有反射、没有运行时查找的前提下实现依赖注入。

## 核心原理

### 1. 编译期代码生成（核心机制）

```text
源码: class Car @Inject constructor(engine: Engine)
                │
                ▼
   KAPT / KSP 注解处理器（dagger-compiler）
                │  解析注解 → 构建依赖图 → 拓扑排序
                ▼
   生成: DaggerCar.java  ── implements Car_Factory
         ├─ provideEngine()  → new Engine()
         └─ new Car(new Engine())
                │
                ▼
   编译期完全解析依赖，无反射
```

- **Dagger 本身是 `javax.inject`（JSR-330）标准的实现**，框架无关（Java 项目也能用）。
- 注解处理器由 **KSP**（Kotlin Symbol Processing，新推荐）或 **KAPT**（旧方案）驱动。
- 生成物在 `build/generated/ksp/.../Dagger*.java` 里，**debug 时可以直接看生成代码**。

### 2. 五个核心注解

| 注解 | 作用 |
| --- | --- |
| `@Inject` | 标注构造器（表示"这个类可以被注入"）或字段（表示"这个字段需要注入"） |
| `@Module` | 声明一个"提供依赖的类"，内部方法用 `@Provides` 或 `@Binds` |
| `@Component` | 声明依赖注入的**入口/装配器**，是依赖图的根 |
| `@Provides` | Module 中的方法，提供一个实例 |
| `@Binds` | 接口与其实现之间的绑定声明（比 `@Provides` 零开销） |
| `@Scope` | 定义作用域注解（自定义），标注在 Component 或 `@Provides` 方法上 |
| `@Qualifier` | 区分同一类型的不同实例（如"产品环境 Retrofit" vs "测试环境 Retrofit"） |

### 3. `@Inject` 构造器注入 vs 字段注入

```kotlin
// 构造器注入（推荐）
class Car @Inject constructor(
    private val engine: Engine
) {
    fun start() = engine.start()
}

// 字段注入（Dagger 2.26+ 已不推荐，Google 官方也建议避免）
class Car {
    @Inject lateinit var engine: Engine
}
```

| 维度 | 构造器注入 | 字段注入 |
| --- | --- | --- |
| 依赖可见性 | 明确（参数列表） | 隐式（字段声明） |
| 可空性 | 可用 `Provider<T>`/`Lazy<T>` 延迟 | `lateinit` 必然非空 |
| 单测构造 | 直接 `Car(FakeEngine())` | 需要反射或反射框架 |
| 对象可否在构造中使用 | 可以 | **不可以**（字段注入发生在构造之后） |
| final 类 | 支持 | **不支持**（final 字段无法注入） |

> **结论**：一律用构造器注入。字段注入让依赖"隐形"，且构造器无法使用被注入的字段。

### 4. `@Module` + `@Provides` vs `@Binds`

```kotlin
// @Provides：自己 new
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder().build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder().client(client).baseUrl("https://api.example.com").build()
}

// @Binds：声明"接口由谁实现"，生成代码是强转，无额外开销
interface UserRepository
class NetworkUserRepository @Inject constructor() : UserRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: NetworkUserRepository): UserRepository
}
```

**选择原则**：

- 依赖**第三方类型**（OkHttp、Retrofit、Gson）→ `@Provides`（第三方无法加 `@Inject`）。
- 依赖**自己的接口** → `@Binds`（更简洁、无额外方法调用开销、编译期校验）。
- 依赖**自己的具体类** → 直接 `@Inject constructor` 即可，不需要 Module。

### 5. `@Component`：依赖图的根

```kotlin
@Component(modules = [NetworkModule::class])
@Singleton
interface AppComponent {
    fun inject(activity: MainActivity)     // 成员注入
    fun userRepository(): UserRepository     // 暴露依赖
}

// 使用
val component = DaggerAppComponent.create()
component.inject(activity)
```

- `@Component` 是**依赖图的根**，Dagger 从它出发**递归解析**所有依赖。
- 依赖图在编译期就会检查：**缺失依赖 → 编译错误**，而不是运行时崩溃。这是 Dagger 相对运行时的最大优势。
- **`@Subcomponent`**：子依赖图，`@Component` 可以安装多个子组件，每个子组件有自己的作用域。Hilt 的 `ActivityComponent` 就是 `Subcomponent`。

### 6. 作用域（Scope）

```kotlin
@Scope
@Retention(AnnotationRetention.RUNTIME)
annotation class Singleton

@Scope
@Retention(AnnotationRetention.RUNTIME)
annotation class ActivityScoped
```

- 作用域标注在 **Component** 上 → 该组件内的被注解实例**只创建一次**，缓存在 Component 内部。
- 作用域标注在 **`@Provides` 方法**上 → 该方法在整个组件内**只调用一次**，结果被缓存。
- **规则**：作用域必须**一致**（同一个 `@Singleton` 依赖不能被 `@ActivityScoped` 组件提供，反之亦然），否则编译报错。
- **常见作用域**：`@Singleton`（App 级）、`@ActivityRetainedScoped`（跨配置变更）、`@ActivityScoped`（Activity 级）、`@ViewModelScoped`（VM 级）、`@FragmentScoped`。

### 7. 延迟与可选依赖

```kotlin
class Repo @Inject constructor(
    private val heavy: Provider<HeavyService>,   // 延迟创建（懒加载）
    private val optional: Lazy<OptionalThing>,    // 第一次用时才创建
    @Nullable private val maybe: MaybeThing?      // 可选依赖
)
```

- `Provider<T>`：每次 `get()` 都是同一个实例，但**创建时机由你控制**（真正的懒加载）。
- `Lazy<T>`：首次访问时创建，之后复用。

### 8. `@Qualifier`：区分同类型的不同实例

```kotlin
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ProductEnv

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class TestEnv

@Module
@InstallIn(SingletonComponent::class)
object EnvModule {
    @Provides
    @Singleton
    @ProductEnv
    fun provideProductRetrofit(): Retrofit = Retrofit.Builder().baseUrl("https://prod").build()

    @Provides
    @Singleton
    @TestEnv
    fun provideTestRetrofit(): Retrofit = Retrofit.Builder().baseUrl("https://test").build()
}

class Repo @Inject constructor(
    @ProductEnv private val prodRetrofit: Retrofit
)
```

### 9. 成员注入（Member Injection）

```kotlin
class MainActivity : AppCompatActivity() {
    @Inject lateinit var presenter: MainPresenter     // 字段注入（Activity 常用）

    override fun onCreate(savedInstanceState: Bundle?) {
        (application as MyApp).appComponent.inject(this)
        super.onCreate(savedInstanceState)
    }
}
```

Dagger 会为 `MainActivity` 生成 `MainActivity_MembersInjector`，在 `inject()` 时逐个注入。

## 代码示例

### 1. 完整的手写 Dagger 配置

```kotlin
// ---- 1. 构造器注入 ----
class Engine @Inject constructor() {
    fun start() = "engine started"
}

class Car @Inject constructor(private val engine: Engine) {
    fun drive(): String = engine.start() + " -> driving"
}

// ---- 2. 接口与实现，用 @Binds 绑定 ----
interface DataSource { fun load(): String }
class RemoteDataSource @Inject constructor() : DataSource {
    override fun load() = "remote"
}

// ---- 3. Module：提供第三方依赖 ----
@Module
object AppModule {
    @Provides
    @Singleton
    fun provideLogger(): Logger = Logger("App")
}

@Module
abstract class DataModule {
    @Binds
    @Singleton
    abstract fun bindDataSource(impl: RemoteDataSource): DataSource
}

// ---- 4. Component：依赖图根 ----
@Singleton
@Component(modules = [AppModule::class, DataModule::class])
interface AppComponent {
    fun car(): Car
    fun dataSource(): DataSource

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance logger: Logger): AppComponent
    }
}

// ---- 5. 使用 ----
class MyApp : Application() {
    val component: AppComponent by lazy {
        DaggerAppComponent.factory().create(Logger("default"))
    }
}
```

### 2. 作用域与子组件

```kotlin
@Scope @Retention(AnnotationRetention.RUNTIME) annotation class ActivityScoped

@Module
@InstallIn(ActivityComponent::class)
class ActivityModule {
    @Provides
    @ActivityScoped
    fun provideDialogHelper(): DialogHelper = DialogHelper()
}

@ActivityScoped
@Subcomponent(modules = [ActivityModule::class])
interface ActivityComponent {
    fun dialogHelper(): DialogHelper
}
```

### 3. 用 KSP 接入 Dagger（非 Hilt）

```kotlin
// 根项目 build.gradle.kts
plugins {
    id("com.google.devtools.ksp") version "2.0.21-1.0.25"
}
dependencies {
    implementation("com.google.dagger:dagger:2.52")
    ksp("com.google.dagger:dagger-compiler:2.52")
}
```

### 4. 自定义 `@BindsInstance`：把外部对象注入进图

```kotlin
@Component
@Singleton
interface AppComponent {
    @Component.Factory
    interface Factory {
        // 运行时才确定的对象（Context、配置、用户信息）
        fun create(@BindsInstance @ApplicationContext val context: Context): AppComponent
    }
}
```

## 常见问题 / 面试要点

**读者问：Dagger 2 的原理是什么？为什么说它是"编译期"的？**

**AI答：**

1. **三步走**：
   - **第 1 步（解析）**：注解处理器（KSP/KAPT）扫描源码中的 `@Inject`、`@Module`、`@Component` 注解，收集"谁能提供什么类型的依赖"。
   - **第 2 步（组装）**：从 `@Component` 出发，**递归构建依赖图**，做拓扑排序（`Car → Engine`，先创建 `Engine` 再创建 `Car`）。
   - **第 3 步（生成代码）**：为每个类型生成 `XxxFactory`（`Provider<Xxx>`），为每个 Component 生成 `DaggerXxxComponent` 实现类，里面全是**手写的 new 调用**。
2. **为什么是"编译期"**：所有依赖解析和对象创建逻辑都被**在编译时展开成普通 Java 代码**，运行时**没有任何反射、没有 `Class.forName`、没有注解扫描**。
3. **带来的三个好处**：
   - **性能**：比反射快一个数量级（反射还有访问检查开销）；
   - **编译期校验**：缺依赖、作用域不匹配、循环依赖 → **编译直接报错**，而不是运行时崩溃；
   - **可追踪**：生成的代码就是普通 Java，可以直接打断点、可以看 APK 体积影响。
4. **与反射式 DI（Koin、Spring）的对比**：
   - 反射式：运行时解析，**出错在运行时**，但**无需注解处理器**，接入成本低；
   - 编译期：出错在编译期，但**构建更慢**（注解处理有成本）。

---

**读者问：Dagger 的 `@Inject`、`@Provides`、`@Binds` 三者有什么区别？什么时候用哪个？**

**AI答：**

按"**谁能提供**"这个问题去分：

1. **`@Inject constructor`**：
   - 用于**你自己写的、没有外部依赖的类**；
   - 声明"我需要这些参数，请框架用构造器创建我"；
   - **优先级最高**：如果一个类同时有 `@Inject` 构造器和 `@Provides` 方法，**`@Inject` 优先**（`@Provides` 会被忽略）。
2. **`@Provides`**：
   - 用于**第三方类型**（`Retrofit`、`OkHttpClient`、`Gson`）——你改不了它们的源码，不能给它们加 `@Inject`；
   - 用于需要**运行时逻辑**的创建（如根据 `BuildConfig.DEBUG` 决定是否加日志拦截器）。
3. **`@Binds`**：
   - 用于**接口 → 实现类**的绑定；
   - 方法体为空（抽象方法），Dagger 生成的是**类型强转**而不是工厂方法，**运行时零额外开销**；
   - 适合"同一接口有多个实现，用 `@Qualifier` 区分"（如 `PayChannel` 有支付宝/微信）。
4. **决策树**：

```text
需要这个依赖
 ├─ 是我自己的具体类吗？
 │    ├─ 是 → @Inject constructor（最简单）
 │    └─ 否（是接口）
 │         └─ 我有实现类 → @Binds（推荐）
 └─ 是第三方类吗？ → @Provides
```

5. **面试加分**：`@Binds` 和 `@Provides` 都能提供接口，`@Binds` **必须有对应的实现类构造器可注入**（否则编译报错），而 `@Provides` 可以返回任意对象（包括第三方实例）。

---

**读者问：Dagger 的作用域（Scope）是怎么工作的？有哪些常见坑？**

**AI答：**

1. **机制**：
   - 作用域注解**同时标注在 Component 上**和**对应的 `@Provides` 方法上**（或 `@Inject` 类上）；
   - Dagger 在生成的 `DaggerXxxComponent` 中**持有一个字段**缓存该实例，只创建一次；
   - 注解本身**没有任何运行时代码**——它只是给注解处理器看的**标记**。
2. **常见作用域层次**：

```text
Application  → @Singleton
  └─ Activity（跨配置变更）→ @ActivityRetainedScoped
       └─ Activity        → @ActivityScoped
            ├─ Fragment   → @FragmentScoped
            └─ ViewModel  → @ViewModelScoped
```

3. **坑 1：作用域不匹配 → 编译报错**。
   - `@Singleton` 的依赖被 `@ActivityScoped` 的组件依赖 → "…@Singleton may not be referenced by…" 错误。
   - 解法：调整作用域层级（把 `@Singleton` 降到组件级别）。
4. **坑 2：同一 `@Provides` 方法在两个不同作用域的 Module 里重复提供** → 编译报错（Dagger 检测到重复绑定）。
5. **坑 3：`@Provides` 方法带参数时的作用域**。
   - 如果 `@Provides` 方法依赖了组件作用域的对象（如 `@Singleton` 的 Retrofit），该 `@Provides` **必须**与组件同作用域或更宽，否则报错。
   - 反之，若 `@Provides` 方法的参数是组件级依赖，它创建的实例**实际生命周期跟随组件**，方法自身不加作用域注解则**每次调用都新建**。
6. **坑 4：字段注入 + `@Singleton`**：Activity 字段注入的 `@Singleton` 依赖，全局共享（正常）；但 Activity 自身的字段注入要记得 `inject()`。
7. **面试加分**：能说出"**`@Singleton` 意味着在整个 Component 生命周期内只创建一次，Component 何时被创建/销毁决定了实例何时被回收**"，就说明理解了作用域与生命周期的绑定关系。

---

**读者问：Dagger 怎么做到"不写一行 new 代码"？生成代码放在哪里？我能看看吗？**

**AI答：**

1. **生成位置**（KSP）：`<module>/build/generated/ksp/debug/kotlin/com/example/DaggerAppComponent.kt`（KAPT 则是 `build/generated/source/kapt/`）。
2. **可以做的事**（面试加分项）：
   - 直接打开生成的 `DaggerAppComponent` 看实现；
   - 在生成的 `provideXxx()` 上打断点，看依赖是怎么被创建的；
   - 用 IDE 的 **Tools > Generate Component** 预览生成的代码；
   - `DaggerAppComponent.create()` → IDE 提示可替换为 `@Component.Factory` 形式（Builder 模式）。
3. **生成的代码大致长这样**：

```java
public final class DaggerAppComponent implements AppComponent {
  private final Provider<Engine> engineProvider;
  private final Provider<Car> carProvider;

  public DaggerAppComponent(Logger logger) {
    this.engineProvider = new Provider<Engine>() {
      @Override public Engine get() { return new Engine(); }
    };
    this.carProvider = new Provider<Car>() {
      @Override public Car get() { return new Car(engineProvider.get()); }
    };
  }

  @Override public Car car() { return carProvider.get(); }
}
```

4. **好处**：对象创建是**硬编码的调用**，性能等同手写 `new`，但依赖关系由框架保证正确。

---

**读者问：手写 Dagger 和用 Hilt，我该选哪个？**

**AI答：**

1. **直接给结论**：**Android 项目默认选 Hilt**。理由：
   - Hilt 本身就是 Dagger2 的**预设封装**，生成逻辑完全一致；
   - Hilt 预置了 `Application`/`Activity`/`Fragment`/`ViewModel`/`Service`/`BroadcastReceiver` 等**常用组件的 Component**，不用自己写 `Subcomponent`；
   - Hilt 提供了 `@HiltAndroidApp`、`@AndroidEntryPoint`、`hiltViewModel()` 等**开箱即用的入口**；
   - 官方文档、Jetpack 教程、面试期望都是 Hilt。
2. **手写 Dagger 仍然值得学的理由**：
   - 理解 Hilt 的底层（`@InstallIn`、`Component`、`@BindsInstance` 都是 Dagger 概念）；
   - 一些**非 Android 的 JVM 项目**（Spring 之外的轻量 DI）用 Dagger 更合适；
   - 面试被问"Dagger 原理"时能答清楚。
3. **Koin 的适用场景**：
   - 需要**极简 DSL**、不想引入注解处理器（**构建更快**，KSP 注解处理对大型项目构建时间影响明显）；
   - **多平台项目**（KMP）里 Dagger/Hilt 支持相对弱，Koin 更自然；
   - 团队规模小、快速原型。

---

**读者问：循环依赖（A 依赖 B，B 依赖 A）Dagger 怎么处理？**

**AI答：**

1. **编译期报错**：`Circular dependency detected between ...`——Dagger 在构建依赖图时做拓扑排序，发现环直接**编译失败**。
2. **合法的"打破循环"手段**：
   - **`Provider<B>` / `Lazy<B>`**：把依赖推迟到使用时才解析，**打破编译期的强依赖**。
     ```kotlin
     class A @Inject constructor(private val b: Provider<B>)
     class B @Inject constructor(private val a: Provider<A>)  // 此时合法
     ```
   - **`@BindsInstance`**：把其中一个对象从外部传入，而不是互相注入。
3. **实践建议**：循环依赖通常是**设计问题**（两个类职责纠缠），优先**拆分职责**而不是用 `Provider` 绕过。
4. **面试表达**："Dagger 在编译期就能检测到循环依赖并报错；可以用 `Provider`/`Lazy` 延迟解析来合法化，但更好的做法是重构消除循环。"

## 关联笔记

- [[Hilt]]：Dagger2 的 Android 标准化封装
- [[Koin]]：运行时 DI 的另一种选择
- [[../架构模式/MVVM]]：依赖注入如何服务于可测试性
- [[../测试/单元测试]]：用 `@TestInstallIn` 替换依赖
- [[../README]]：第 7 部分总览

## 参考资料

- Dagger 官方文档：https://dagger.dev/
- Dagger 官方 · "A Functional Digression"（Scope 的实现原理）：https://dagger.dev/articles/functional-digression
- Google 官方 · Dagger 官方文档：https://developer.android.com/training/dependency-injection
- 《Android 高级编程》第 4 版 · 第 7 章
