---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 中级
created: 2026-11-04
updated: 2026-11-04
related: "[[Dagger2]], [[Koin]], [[../Jetpack库/Lifecycle与ViewModel]]"
---

# Hilt

## 一句话定义

Hilt 是 **Dagger 2 在 Android 上的官方封装**：通过预置的组件层级（Component Hierarchy）与少量魔法注解，把 Dagger 的样板代码压缩到"加一个注解"，让依赖注入在 Android 上开箱即用。

## 核心原理

### 1. Hilt 的组件层级（Component Hierarchy）

Hilt 预定义了一条**组件树**，每个组件对应 Android 的一个生命周期宿主：

```text
Application
└── SingletonComponent                @Singleton
    └── ActivityComponent             @ActivityScoped
        ├── ViewModelComponent        @ViewModelScoped
        │   └── (ViewModel)
        └── FragmentComponent         @FragmentScoped
            ├── ActivityComponent
            └── ViewModelComponent
    └── ServiceComponent              @ServiceScoped
    └── (BroadcastReceiver)
```

关键点：

- **每个 Android 组件（Activity/Fragment/Service/ViewModel）都有对应的 Hilt 组件**，且**自动创建**，不需要你手写 `@Subcomponent`。
- **父组件的依赖对子组件可见**（子组件可以注入父组件作用域的对象，如 `@Singleton` 的 Retrofit）。
- **子组件的依赖对父组件不可见**（`@ActivityScoped` 的对象无法在 `SingletonComponent` 里注入）。

### 2. 五个核心注解

| 注解 | 位置 | 作用 |
| --- | --- | --- |
| `@HiltAndroidApp` | Application 类 | 声明"我是 Hilt 的根"，生成 `Hilt_AppComponent` |
| `@AndroidEntryPoint` | Activity/Fragment/Service/Receiver | 声明"请注入我的依赖"，**基类必须是 `AppCompatActivity`/`Fragment` 等 Hilt 支持的基类** |
| `@HiltViewModel` | ViewModel 类 | 让 ViewModel 可被 `hiltViewModel()` 创建，自动注入 `SavedStateHandle` |
| `@InstallIn(SingletonComponent::class)` | Module 类 | 把这个 Module 安装到指定的组件树位置 |
| `@AndroidEntryPoint` + `@EntryPoint` | 任意类 | "**接口式注入**"：不修改被注入类也能拿到依赖（主要用于 SDK 类） |

### 3. 完整示例

```kotlin
// ---- 1. Application ----
@HiltAndroidApp
class MyApp : Application() {
    @Inject lateinit var config: AppConfig      // 根组件的依赖直接注入字段
}

// ---- 2. Module：提供依赖 ----
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .client(client)
        .baseUrl("https://api.example.com")
        .build()

    @Provides
    @Singleton
    fun provideUserApi(retrofit: Retrofit): UserApi = retrofit.create(UserApi::class.java)
}

// ---- 3. 绑定接口到实现 ----
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: NetworkUserRepository): UserRepository
}

// ---- 4. Activity 注入 ----
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var userApi: UserApi
    @Inject lateinit var repo: UserRepository
}

// ---- 5. Fragment 注入（注意：父 Fragment 要先 @AndroidEntryPoint） ----
@AndroidEntryPoint
class UserFragment : Fragment() {
    @Inject lateinit var repo: UserRepository
}

// ---- 6. ViewModel 注入 ----
@HiltViewModel
class UserViewModel @Inject constructor(
    private val repo: UserRepository
) : ViewModel() {
    val state: StateFlow<UserUiState> = MutableStateFlow(UserUiState())
}

// Compose 中
val vm: UserViewModel = hiltViewModel()
```

### 4. `@EntryPoint`：接口式注入（不改被注入类）

某些类你**无法修改**（第三方 SDK、系统类），但又需要注入依赖：

```kotlin
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AnalyticsEntryPoint {
    fun analyticsTracker(): AnalyticsTracker
}

// 获取
@Inject
lateinit var tracker: AnalyticsTracker
```
```kotlin
// 在任意被 @AndroidEntryPoint 标注的类中获取 EntryPoint
val entryPoint = EntryPointAccessors.fromApplication(context, AnalyticsEntryPoint::class.java)
val tracker = entryPoint.analyticsTracker()
```

- **`EntryPointAccessors.fromApplication(...)`**：拿应用级依赖。
- **`EntryPointAccessors.fromActivity(activity, XxxEntryPoint::class.java)`**：拿 Activity 级依赖。
- **典型用途**：给 `Application` 之外无法加注解的类（如第三方 SDK 初始化）注入依赖。

### 5. 作用域与 `@BindsInstance`

- **Hilt 预置作用域**：`@Singleton`、`@ActivityRetainedScoped`、`@ActivityScoped`、`@FragmentScoped`、`@ViewModelScoped`、`@ServiceScoped`、`@ViewScoped`（在 `androidx.hilt:hilt-navigation` 中）。
- **`@BindsInstance`**：把运行时才知道的对象注入组件，通常在 Application 里做：

```kotlin
@HiltAndroidApp
class MyApp : Application() {
    @Inject lateinit var userIdProvider: UserIdProvider
}
```
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideUserIdProvider(@BindsInstance context: Context): UserIdProvider =
        UserIdProvider(context)
}
```

### 6. 单测替换依赖：`@TestInstallIn`

```kotlin
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [NetworkModule::class]     // 整体替换
)
object FakeNetworkModule {
    @Provides
    @Singleton
    fun provideUserApi(): UserApi = FakeUserApi()
}

// 或只替换单个绑定
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [RepositoryModule::class]
)
abstract class FakeRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: FakeUserRepository): UserRepository
}
```

> 这是 Hilt 相对手写 Dagger 的**重要工程价值**：依赖替换有官方方案，单测无需手动构建 Component。

### 7. `hiltViewModel()` 的原理

```kotlin
@Composable
fun UserRoute(vm: UserViewModel = hiltViewModel()) { ... }
```

- `hiltViewModel()` 内部通过 `LocalViewModelStoreOwner` 拿到当前作用域（Navigation Compose 下是 `NavBackStackEntry`），再从 `HiltViewModelFactory`（`@HiltViewModel` 的类由 Hilt 生成 `Hilt_UserViewModel_Factory`）创建实例。
- `@HiltViewModel` 的类**必须**有 `@Inject` 构造器，且**可以有 `SavedStateHandle` 参数**（Hilt 会自动提供）。
- 纯 View 体系中用 `by viewModels()`（Activity/Fragment 是 Hilt 入口后会自动使用 `HiltViewModelFactory`）。

### 8. 多模块的 Hilt 配置

- **每个模块**都要在自己的 `build.gradle.kts` 里加 `plugins { id("com.google.dagger.hilt.android") }` 和 `ksp("com.google.dagger:hilt-compiler")`。
- **跨模块共享 Module**：把 Module 放在 `core` 模块，安装到 `SingletonComponent`，其他模块即可注入。
- **AndroidManifest 合并**：Hilt 依赖 `androidx.startup` 生成的 `Hilt_AppComponent` 初始化项，多模块时需注意 manifest merger。

## 常见问题 / 面试要点

**读者问：Hilt 和 Dagger 是什么关系？Hilt 到底帮我们省掉了什么？**

**AI答：**

1. **关系**：**Hilt = Dagger 2 + Android 预设配置**。Hilt 本身不产生新的依赖注入机制，底层依然是 Dagger 的**注解处理器 + 代码生成**，最终产物仍是 `DaggerAppComponent`。
2. **Hilt 省掉的样板**（手写 Dagger 需要写的）：
   - 不用手写 `@Component` 的所有子组件：`ActivityComponent`、`FragmentComponent`、`ServiceComponent`、`ViewModelComponent` 等**全部预置**；
   - 不用手写 `Subcomponent` 与 `Component` 之间的依赖管理；
   - 不用手写 `ViewModelProvider.Factory`（`@HiltViewModel` 自动处理，含 `SavedStateHandle`）；
   - 不用手写 `Component` 的创建与持有（`@HiltAndroidApp` 自动创建 `SingletonComponent`）；
   - 不用手写成员注入的 `inject()` 调用（`@AndroidEntryPoint` 自动注入）。
3. **Hilt 额外提供的能力**：
   - **`@TestInstallIn`**：单测替换依赖的官方方案；
   - **`@EntryPoint` + `EntryPointAccessors`**：不改被注入类也能取依赖；
   - **预置作用域**：`@ActivityScoped`、`@ViewModelScoped` 等；
   - 与 `Jetpack` 深度集成：`hiltViewModel()`、`hiltAndroidViewModel()`、`@AndroidEntryPoint` 与 Compose 的 `hiltViewModel()` 打通。
4. **面试总结句**："**Hilt 没有改变 Dagger 的原理，只是把 Dagger 在 Android 上的常见配置预置化、并补齐了与 Jetpack 的集成。**"

---

**读者问：Hilt 的组件层级是怎么回事？为什么 Fragment 能注入 Activity 的依赖？**

**AI答：**

1. **组件树结构**（见上文）：`SingletonComponent` 是根，往下挂 `ActivityComponent`、`FragmentComponent`、`ServiceComponent`，`ViewModelComponent` 挂在 Activity/Fragment 之下。
2. **可见性规则**：**子组件可以访问父组件的依赖，反之不可**。
   - 所以 `ActivityComponent` 里能注入 `@Singleton` 的 Retrofit；
   - `@ActivityScoped` 的对象**不能**被 `SingletonComponent` 注入（编译期报错）。
3. **Fragment 为什么能拿到 Activity 的依赖**：
   - `FragmentComponent` 是作为 `ActivityComponent` 的**子组件**创建的（`ActivityComponent` 会为每个 Fragment 创建对应的 `FragmentComponent`）；
   - 所以 `FragmentComponent` 里能注入 `@ActivityScoped` 与 `@Singleton` 的对象。
   - 副作用：**同一个 Activity 内的多个 Fragment 共享 `@ActivityScoped` 实例**。
4. **父子 Fragment**：`FragmentComponent` 自身也是 `ViewModelStoreOwner`，子 Fragment 的 `FragmentComponent` 挂在父 Fragment 的 `FragmentComponent` 下。
5. **面试表达**：能说出"`@ActivityScoped` 在同一 Activity 的多个 Fragment 间共享，在不同 Activity 间不共享"，就说明理解到位。

---

**读者问：`@HiltAndroidApp`、`@AndroidEntryPoint`、`@HiltViewModel` 分别标注在哪里？漏标会怎样？**

**AI答：**

1. **`@HiltAndroidApp`**：
   - 标注在 **`Application` 子类**上（每个进程只有一个 Application）。
   - 它让 Hilt 生成 `Hilt_MyApp`（替换你的 Application）和 `DaggerHilt_MyApp`（依赖图根）。
   - **漏标后果**：编译期报错——其他 `@AndroidEntryPoint` 类找不到 `SingletonComponent`，编译失败（报错信息很明确）。
2. **`@AndroidEntryPoint`**：
   - 标注在 **Activity / Fragment / Service / BroadcastReceiver** 上（以及 `View` 用 `@AndroidEntryPoint` 的变体）。
   - **要求基类必须是 Hilt 支持的**：`AppCompatActivity`（不是 `Activity`！）、`Fragment`（不是 `DialogFragment` 的任意子类都行）、`Service`、`BroadcastReceiver`。
   - **漏标后果**：编译期报错 `Hilt activity must be annotated with @AndroidEntryPoint`；若在运行时才报错，说明基类不支持。
3. **`@HiltViewModel`**：
   - 标注在 **ViewModel 类**上，类必须有 `@Inject` 构造器。
   - 漏标后果：`hiltViewModel()` 无法创建它 → 编译报错。
4. **`@InstallIn`**：
   - 标注在 **Module** 上，指定安装到哪个组件（通常 `SingletonComponent`）。
   - **漏标后果**：Module 不会生效 → 依赖找不到 → 编译报错 "cannot be provided without an @Provides-annotated method"。
5. **记忆口诀**："**一个 App 一个 `@HiltAndroidApp`；每个入口类一个 `@AndroidEntryPoint`；每个 Module 一个 `@InstallIn`；每个 VM 一个 `@HiltViewModel`。**"

---

**读者问：`@EntryPoint` 是什么？和 `@AndroidEntryPoint` 有什么区别？**

**AI答：**

1. **两者方向相反**：
   - **`@AndroidEntryPoint`**：**注入到**你标注的类里（该类变成"注入目标"，Hilt 生成 `MembersInjector`）。
   - **`@EntryPoint`**：**从 Hilt 容器里取出**依赖（该接口变成"依赖的出口"，Hilt 生成 `EntryPointAccessors`）。
2. **`@EntryPoint` 的用途**：给**无法修改的类**（第三方 SDK、系统服务、`Activity` 之外的地方）提供依赖注入。
   - 典型场景：`Application` 之外的初始化、`ContentProvider`、`FirebaseMessagingService`（它可以标 `@AndroidEntryPoint`，但其静态方法拿不到实例）。
3. **使用方式**：

```kotlin
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RepoEntryPoint {
    fun repo(): UserRepository
}

// 获取
val repo = EntryPointAccessors.fromApplication(appContext, RepoEntryPoint::class.java).repo()
```

4. **关键限制**：`@EntryPoint` 接口的方法**不能有参数**（只能无参获取），且返回类型必须能在该组件作用域内解析。
5. **面试表达**："`@AndroidEntryPoint` 是**往里注入**，`@EntryPoint` 是**往外获取**。后者是为了解决'不能修改目标类'的场景。"

---

**读者问：Hilt 的单测怎么替换依赖？为什么需要 `@TestInstallIn`？**

**AI答：**

1. **手写 Dagger 的痛点**：单测里想替换 `NetworkUserRepository` 为 Fake，必须**手动构建整个 Component 图**（`DaggerTestAppComponent.builder().fakeRepo(fake).build()`），样板多且容易漏。
2. **`@TestInstallIn` 的做法**：声明一个测试专用 Module，声明 `replaces = [生产Module::class]`，测试时 Hilt 自动用测试 Module 替换生产 Module。

```kotlin
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [RepositoryModule::class]
)
abstract class FakeRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: FakeUserRepository): UserRepository
}
```

3. **前提条件**：使用 `@TestInstallIn` 的测试必须是 **`HiltTestApplication`**（或自定义的 `@CustomTestApplication`）：

```kotlin
@HiltAndroidTest
class UserRepoTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repo: UserRepository

    @Before fun setup() { hiltRule.inject() }
}
```

4. **单测（纯 JVM，不启动 Android）** 也可以用 `@TestInstallIn` + `HiltTestApplication`，此时运行的是 **Robolectric**（因为需要 Android 环境）；如果被测对象完全不依赖 Android，可以直接构造 `DaggerXxxComponent` 手搓。
5. **面试加分**：能说出"`@TestInstallIn` 的 `replaces` 是**整体替换**，而用 `@BindValue` 可以**只替换单个绑定**（在测试类里 `@BindValue val fake: UserRepository = FakeUserRepository()`）"，说明用得比较深。

---

**读者问：Hilt 的初始化时机是什么？会影响启动速度吗？**

**AI答：**

1. **初始化时机**：`@HiltAndroidApp` 标注的 Application 在 **`onCreate`** 中由 `Hilt_MyApp` 触发 `SingletonComponent` 的创建（Hilt 通过 `androidx.startup` 的 `InitializationProvider` 或生成的 `Hilt_MyApp.onCreate` 实现）。
2. **做了什么**：解析依赖图、创建 `@Singleton` 实例（如 OkHttpClient、Retrofit）。这些对象的创建是**同步**的，理论上会拖慢启动。
3. **优化手段**：
   - **不要在 `@Singleton` 的 `@Provides` 里做耗时操作**（如初始化数据库、大量磁盘 IO）——把耗时初始化改成**懒加载**（用 `Provider<T>`/`Lazy<T>`）或放到后台线程；
   - **把非必要初始化延后**到真正使用时；
   - **把初始化移到 WorkManager / App Startup 的独立任务**中（androidx.startup 提供 `Initializer` 机制，可配置 `androidx.startup.InitializationProvider` 的 meta-data 做延迟初始化）。
4. **启动优化的通用原则**（详见 [[../06-系统原理与性能优化/启动优化]]）：
   - **三段式初始化**：主线程只做"必须的最小初始化"，其余放到空闲时；
   - 使用 **Baseline Profile** 提前编译热点；
   - 用 `Systrace` / `Perfetto` 测量启动各阶段耗时。
5. **面试表达**："Hilt 的组件创建在 Application.onCreate，注入的 `@Singleton` 初始化是同步的，所以**不要在 `@Provides` 里做重活**，这是 DI 框架影响启动的常见坑。"

## 关联笔记

- [[Dagger2]]：Hilt 的底层原理
- [[Koin]]：另一种 DI 方案的取舍
- [[../Jetpack库/Lifecycle与ViewModel]]：`hiltViewModel()` 与 ViewModel 作用域
- [[../架构模式/MVVM]]：依赖注入服务可测试性
- [[../测试/单元测试]]：`@TestInstallIn` 替换依赖
- [[../README]]：第 7 部分总览

## 参考资料

- Android 官方 · Hilt 指南：https://developer.android.com/training/dependency-injection/hilt-android
- Android 官方 · Hilt 的组件与作用域：https://developer.android.com/training/dependency-injection/hilt-android#components
- Hilt GitHub：https://github.com/google/dagger
- 《Android 高级编程》第 4 版 · 第 7 章
