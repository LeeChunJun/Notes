---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 中级
created: 2026-11-04
updated: 2026-11-04
related: "[[Dagger2]], [[Hilt]], [[../README]]"
---

# Koin

## 一句话定义

Koin 是一个**基于 Kotlin DSL 的运行时依赖注入框架**：不需要注解处理器，用 `single { }` / `factory { }` 这样的声明式 DSL 在**运行时**完成对象图的装配，特点是**零代码生成、构建快、API 简洁**。

## 核心原理

### 1. Koin 的工作模型

```text
startKoin {
    modules(
        appModule,
        networkModule
    )
}

// 运行时：Koin 内部维护一个 Definitions 容器
// 声明：single { Retrofit.Builder()...build() }   ← 注册"如何创建 Retrofit"
// 解析：val retrofit: Retrofit by inject()         ← 首次调用时才真正创建
```

核心概念：

| 概念 | 说明 |
| --- | --- |
| **Module** | 一组定义的集合（`module { ... }`） |
| **Definition（定义）** | "某个类型如何被创建"的规则（`single`/`factory`/`scoped`） |
| **Container（容器）** | 持有所有 Definition 的运行时仓库（`KoinApplication` / `KoinContext`） |
| **Scope（作用域）** | 一个**独立的作用域容器**，有独立的实例缓存，可手动创建与销毁 |
| **Resolution（解析）** | 运行时根据类型找到 Definition 并创建实例 |

### 2. 三种定义方式

| DSL | 行为 | 用途 |
| --- | --- | --- |
| `single { }` | **单例**：容器内只创建一次，之后复用 | Retrofit、OkHttpClient、Repository |
| `factory { }` | **工厂**：每次解析都创建新实例 | ViewModel、Presenter、Adapter |
| `scoped { }` | **作用域单例**：在当前 scope 内单例 | Activity 级、Session 级对象 |

```kotlin
val appModule = module {
    single { OkHttpClient() }
    single { Retrofit.Builder().client(get()).baseUrl(BASE_URL).build() }
    single<UserRepository> { NetworkUserRepository(get()) }   // 显式指定类型
    factory { UserViewModel(get()) }
}
```

- `get()` 用来在定义内部解析其他依赖（等价于 Dagger 的"参数注入"）。
- `single { }` 的类型由**返回值的类型**推断（Kotlin 反射 `typeOf`），要显式写 `single<UserRepository> { ... }` 时若类型推断失败可加泛型。

### 3. 启动与注入

```kotlin
// Application
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MyApp)     // 提供 androidContext()（注入 Context）
            modules(appModule, networkModule)
        }
    }
}

// 注入
class UserViewModel(private val repo: UserRepository) : ViewModel()

// Activity / Fragment 中
class MainActivity : AppCompatActivity() {
    private val repo: UserRepository by inject()
    private val vm: UserViewModel by viewModel { paramsOf(this) }   // 传参
}
```

### 4. 作用域（Scope）

```kotlin
// 创建一个会话作用域
val sessionScope = scope {
    single { SessionManager(get()) }
}

class MainActivity : AppCompatActivity() {
    private val session: SessionManager by inject(SessionManager::class, scope = sessionScope)
}
```

- `scope { }` 创建一个**独立的 Koin Scope**，拥有自己的实例缓存；
- 作用域的实例在 `close()` 时释放 → 适合"登录会话"、"流程会话"这类有明确生命周期的对象；
- 作用域**不会**自动随 Activity 销毁而销毁，需要手动管理（这是 Koin 常见的泄漏来源）。

### 5. `parametersOf` 传参

```kotlin
// 定义带参的依赖
single { (id: String) -> UserDetailViewModel(id, get()) }

// 注入时传参
private val vm: UserDetailViewModel by viewModel { parametersOf("42") }
```

> 注意：带参的定义**不能用 `by inject()` 直接注入**，必须用 `parametersOf`。

### 6. Koin 与 Android 生命周期集成

```kotlin
// Application
startKoin {
    androidLogger()
    androidContext(this@MyApp)
    modules(appModule)
}

// Activity 中注入 ViewModel（Koin 提供 viewModel() delegate）
class MainActivity : AppCompatActivity() {
    private val vm: MainViewModel by viewModel()
}
```

- Koin 的 `viewModel()` delegate 会自动使用 `ViewModelProvider`，并处理 `SavedStateHandle`（Koin 4.x 支持 `CreationExtras`）。
- **Fragment 中**同样用 `by viewModel()`，作用域是 Fragment 的 `ViewModelStore`。

### 7. 声明式模块与插件

```kotlin
// Koin Annotations（可选）：用注解声明，由 KSP 生成 DSL 代码
// @Module @ComponentScan("com.example.feature")
// class MyModule
```

- **Koin Annotations** 走 KSP 代码生成（**不是运行时反射**），保留了"注解式"体验，同时避免 KAPT 的构建开销；
- 但它仍然比 Hilt 多一套概念体系，**官方首推仍是纯 DSL**。

### 8. 单元测试

```kotlin
class UserRepoTest {
    private val fakeRepo = FakeUserRepository()

    @Before
    fun setup() {
        startKoin {
            modules(module {
                single<UserRepository> { fakeRepo }
            })
        }
    }

    @After
    fun tearDown() = stopKoin()

    @Test
    fun `returns users`() {
        val repo: UserRepository = get()
        assertEquals(2, repo.getUsers().size)
    }
}
```

- Koin 的测试替换**极其简单**（比 Hilt 的 `@TestInstallIn` 更直观）——这也是 Koin 在小团队中受欢迎的原因之一；
- 缺点是**替换是全局的**，测试间要记得 `stopKoin()` 避免相互污染。

## 代码示例

### 1. 完整模块定义

```kotlin
// di/AppModule.kt
val appModule = module {

    // 单例：第三方客户端
    single {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    single {
        Retrofit.Builder()
            .client(get())
            .baseUrl(BuildConfig.API_BASE_URL)
            .build()
    }

    single { get<Retrofit>().create(UserApi::class.java) }

    // 接口 -> 实现
    single<UserRepository> { NetworkUserRepository(get()) }

    // 工厂：ViewModel
    factory { UserListViewModel(get()) }
    factory { (id: String) -> UserDetailViewModel(id, get()) }
}

// di/DatabaseModule.kt
val databaseModule = module {
    single {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, "app.db")
            .fallbackToDestructiveMigration()
            .build()
    }
    single { get<AppDatabase>().userDao() }
}
```

### 2. 在 Android 中启动

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@MyApp)
            modules(appModule, databaseModule)
        }
    }
}
```

### 3. 注入到 Activity / ViewModel

```kotlin
class MainActivity : AppCompatActivity() {
    private val repo: UserRepository by inject()
    private val vm: UserListViewModel by viewModel()
}

@HiltViewModel  // 如果同时用 Hilt，可互不冲突（各自容器）
class UserListViewModel(private val repo: UserRepository) : ViewModel()
```

### 4. 作用域：登录会话

```kotlin
class LoginSession(private val token: String)

class AuthManager {
    var sessionScope: Scope? = null

    fun login(token: String) {
        sessionScope = scope {
            single { LoginSession(token) }
        }
    }

    fun logout() {
        sessionScope?.close()   // 释放会话级实例
        sessionScope = null
    }
}
```

### 5. Compose 中使用

```kotlin
// 直接通过 koin 扩展获取
@Composable
fun UserScreen() {
    val repo: UserRepository = koinInject()
    // ...
}
```

## 常见问题 / 面试要点

**读者问：Koin 和 Hilt/Dagger 怎么选？各自的优缺点？**

**AI答：**

按"**本质差异 → 优缺点 → 选型结论**"答：

1. **本质差异**：
   - **Hilt/Dagger**：**编译期**解析依赖图并生成代码，运行时零反射；
   - **Koin**：**运行时**通过 Kotlin 反射（读取 `typeOf` / 构造器签名）解析依赖。
2. **对比表**：

| 维度 | Hilt | Koin |
| --- | --- | --- |
| 解析时机 | 编译期 | 运行时 |
| 反射开销 | 无 | 有（仅在首次解析时） |
| 错误暴露时机 | **编译期** | **运行时**（首次 `inject()`） |
| 构建速度 | 慢（注解处理） | **快**（无注解处理） |
| 上手成本 | 需理解 Component/Scope/Module | **低**（DSL 一眼懂） |
| 样板代码 | 多（需声明 Module/Binds） | 少 |
| 多模块共享 | 需 `@InstallIn` + 依赖传递 | 需手动传递 module 或 `loadKoinModules` |
| KMP 支持 | 较弱 | **好** |
| 官方推荐 | ✅ Google 官方 | 社区（Koin 官方与 Spring 团队） |
| 测试替换 | `@TestInstallIn`（较繁琐） | **简单直观** |
3. **选型结论**：
   - **中大型 Android 项目、需要长期维护、多人协作** → **Hilt**（官方生态、编译期安全、IDE 支持好）。
   - **KMP / 多平台项目** → **Koin**（Dagger/Hilt 的 KMP 支持仍不完整）。
   - **小团队、快速原型、构建速度敏感（大型项目注解处理很慢）** → **Koin**。
   - **已经在用 Hilt** → 没有强理由迁移；混用两套 DI 只会带来混乱。
4. **面试加分**：能提到"**Hilt 的主要成本是构建时间**（注解处理），在百个模块的大型项目里可能达到分钟级，Koin 没有这个问题"，说明理解过工程影响。

---

**读者问：Koin 是运行时反射，性能会不会有问题？**

**AI答：**

1. **有反射，但开销可控且一次性**：
   - Koin 在**模块定义时**（`startKoin` 时）就通过 Kotlin 的 `typeOf` 反射**推断出类型**，建立 `KClass → Definition` 的索引；
   - 真正的对象创建走 Kotlin 的**构造函数引用**（`::MyClass` / lambda），Koin 内部尽量使用**构造器引用**而非反射调用；
   - 所以**每次 `inject()` 的开销主要是一次 Map 查找**，而不是完整的反射扫描。
2. **真实成本**：
   - 首次解析有一次性开销（类加载 + lambda 初始化）；
   - **每次 `inject()` 的成本远低于**"反射扫描 + 注解查找"的想象；
   - 但相比 Hilt 的"直接调用生成的工厂方法"，**Koin 的解析确实多了一层间接**。
3. **性能敏感场景的建议**：
   - 关键路径（如启动过程）**不要在 `single` 的创建里做重活**，和 Hilt 一样；
   - 高频调用的路径（如列表 item 的 ViewHolder）**不要在 `factory` 里做复杂解析**，尽量提前注入。
4. **一句话结论**："Koin 的性能劣势**存在但通常不构成瓶颈**；真正的瓶颈几乎总是**业务代码本身（IO、主线程操作、过度创建）**。"

---

**读者问：`single`、`factory`、`scoped` 有什么区别？该怎么选？**

**AI答：**

1. **`single`**：容器内**只创建一次**，之后所有注入点拿到**同一实例**。
   - 用于：**无状态或需要共享状态**的对象（Retrofit、OkHttpClient、Repository、Cache）。
   - 陷阱：单例持有 Activity/Context 会泄漏 → **绝不 `single` 持有 Activity 的东西**；要用 Context，用 `androidContext()`（Application 级）。
2. **`factory`**：**每次解析创建新实例**。
   - 用于：**有状态、有生命周期**的对象（ViewModel、Presenter、Adapter、一次性任务）。
   - 陷阱：`factory { ViewModel() }` + `by inject()` 每次调用都新建 → **ViewModel 必须用 `by viewModel()`（走 ViewModelProvider），不是 `by inject()`**。
3. **`scoped`**：在**指定 Scope 内**单例。
   - 用于：会话级/流程级对象（登录会话、上传任务队列、多步骤表单的上下文）。
   - 陷阱：Scope **不会自动销毁**，必须手动 `close()`，否则实例泄漏。
4. **决策口诀**：
   - **无状态/需共享 → `single`**
   - **有状态/一次性 → `factory`**
   - **有明确起止的会话 → `scoped`**
5. **最重要的一条**："**判断标准是生命周期，而不是类型**。生命周期 = Application → `single`；= Activity/Fragment → 用 `by viewModel()`；= 一段业务流程 → `scoped`。"

---

**读者问：Koin 的作用域（Scope）是什么？和 Hilt 的作用域有什么区别？**

**AI答：**

1. **Koin 的 Scope**：
   - 是一个**独立的实例容器**（`Scope` 接口），有自己的 Definition 缓存；
   - 通过 `scope { single { ... } }` 创建，**手动 `close()`** 释放；
   - 作用域内的实例**不会**泄漏到根容器，反之根容器的实例在作用域内可见。
2. **与 Hilt 的本质区别**：
   - **Hilt 的作用域 = 组件树上的位置**（`@Singleton`/`@ActivityScoped`…），**由 Android 生命周期自动创建与销毁**（`@AndroidEntryPoint` 的类被销毁时，ActivityComponent 随之销毁，ActivityScoped 实例被释放）；
   - **Koin 的作用域 = 手动创建的独立容器**，**需要你手动管理生命周期**。
3. **实践建议**：
   - Koin 用 Scope 时，务必在宿主销毁时 `close()`（如 `onDestroy` 里），否则**实例泄漏**；
   - 尽量用 `androidContext()` 拿 Application Context 而不是 Activity Context；
   - 忘记 close 是 Koin 最常见的泄漏来源。
4. **面试表达**："**Hilt 的作用域是框架托管的，和组件树绑定；Koin 的作用域是手动沙盒，灵活但要自己负责释放。**"

---

**读者问：Koin 和 Hilt 能在一个项目里混用吗？**

**AI答：**

1. **技术上可以，但强烈不建议**：
   - 两套容器各自管理自己的对象图，会出现**同一类型存在两个实例**（Hilt 一个、Koin 一个），状态不一致；
   - 依赖来源难以追踪（新人不知道该用哪个）；
   - 测试时两套替换逻辑都要写。
2. **如果必须混用**（如历史遗留）：
   - **严格划界**：例如"ViewModel 层用 Hilt，纯 Kotlin 的工具模块用 Koin"；
   - **明确文档**：在 README/ADR 里写清楚边界；
   - **避免同一类型跨容器共享**（不要把 Hilt 的 Repository 传给 Koin 的类，反之亦然）。
3. **更好的做法**：**选一个，然后统一迁移**。如果是从 Hilt 迁 Koin，建议**从叶子模块开始**（新模块先用 Koin），而不是一次性重构。
4. **面试表达**："混用是**技术债**，短期可解，长期会让依赖来源不可追踪。生产项目应该统一。"

## 关联笔记

- [[Dagger2]]：编译期 DI 的原理
- [[Hilt]]：Android 上的官方 DI 方案
- [[../架构模式/MVVM]]：DI 与可测试性的关系
- [[../测试/单元测试]]：Koin 的测试替换
- [[../README]]：第 7 部分总览

## 参考资料

- Koin 官方文档：https://insert-koin.io/
- Koin DSL 参考：https://insert-koin.io/docs/reference/koin-core/modules
- Koin GitHub：https://github.com/InsertKoinIO/koin
