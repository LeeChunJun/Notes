---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 中级
created: 2026-11-04
updated: 2026-11-04
related: "[[MVVM]], [[MVI]], [[../README]]"
---

# MVC与MVP

## 一句话定义

MVC 把对象按职责切成 **Model（数据）/ View（界面）/ Controller（调度）**；MVP 进一步把 Controller 改造成不依赖具体控件的 **Presenter（展示器）**，通过接口与 View 通信，使业务逻辑第一次可以被纯 JVM 单测覆盖。

## 核心原理

### 1. MVC：各司其职，但边界模糊

```
用户操作 → View(Android Activity) ──回调──→ Controller ──调用──→ Model
                 ↑                                  │
                 └────  更新 UI ←── setContentView ┘
```

Android 上的"原生 MVC"其实更接近 **MVC + Activity 即 Controller** 的变体：

- **Model**：`User` 数据类、`UserRepository`、数据库/网络层。
- **View**：`activity_main.xml` + `MainActivity`（Android 把 View 和 Controller 揉在了一起）。
- **Controller**：`MainActivity` 里的点击监听、校验逻辑、跳转逻辑。

问题所在：

| 问题 | 表现 |
| --- | --- |
| Activity 职责膨胀 | 变成"上帝类"，几千行代码混杂布局、逻辑、请求 |
| 逻辑不可测 | 逻辑依赖控件、依赖 Context，必须跑真机/模拟器 |
| 复用困难 | 想把"登录校验"搬到另一个页面只能复制粘贴 |
| 状态易错 | 横竖屏/进程重建后字段丢失，屏幕旋转即崩 |

### 2. MVP：Presenter 持有 View 接口

```
View(实现 LoginView)  ←── 接口回调 ──  Presenter ──依赖──→ Model(Repository)
      ↑                      ▲
      └── showLoading()      └── 接口回调（依赖倒置）
```

三个关键改造点：

1. **接口抽象**：`interface LoginView { fun showLoading(); fun showError(msg: String) }`，Presenter 只面向接口编程。
2. **依赖倒置**：Presenter 持有的是 `LoginRepository` 抽象，而不是具体的 `RetrofitUserRepository`。
3. **View 被动化**：View 不再自己拉数据、算逻辑，只负责渲染和把用户事件转交 Presenter。

> MVP 通常还要额外引入一个 **Model 层（数据/业务）**，把"业务规则"（比如"连续输错 3 次锁定 5 分钟"）从 Presenter 挪进 Model，Presenter 只负责"展示逻辑"。

### 3. 两种实现风格的差别

| 风格 | Presenter 持有 View | 生命周期 | 优点 | 缺点 |
| --- | --- | --- | --- | --- |
| MVP（Java/Rx 时代主流） | 持有强引用，在 `onDestroy` 里 `presenter.detachView()` | 跟随 View 生命周期 | 简单直接 | 忘记 detach 会内存泄漏；View 重建要重新 attach |
| MVP（Presenter 独立） | 不持有 View，用回调或 `WeakReference` | 可比 View 更长 | Presenter 可跨配置变更保留 | 写法更繁琐 |

## 代码示例

### 1. MVP 接口与 Presenter

```kotlin
// ---- View 契约（Presenter 只认识这个接口）----
interface LoginView {
    fun showLoading(show: Boolean)
    fun showError(message: String)
    fun showUserName(name: String)
}

// ---- Model 抽象（依赖倒置，便于 mock）----
interface UserRepository {
    fun login(username: String, password: String, callback: (Result<String>) -> Unit)
}

class NetworkUserRepository : UserRepository {
    override fun login(username: String, password: String, callback: (Result<String>) -> Unit) {
        // 真实实现：Retrofit + Coroutine
        callback(Result.success("$username"))
    }
}

// ---- Presenter：纯 Kotlin，无 Android 依赖，可直接单测 ----
class LoginPresenter(
    private val repository: UserRepository
) {
    private var view: LoginView? = null
    private var lastRequest: Pair<String, String>? = null

    fun attachView(view: LoginView) {
        this.view = view
    }

    fun detachView() {
        view = null
    }

    fun onLoginClicked(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            view?.showError("用户名和密码不能为空")
            return
        }
        lastRequest = username to password
        view?.showLoading(true)
        repository.login(username, password) { result ->
            view?.showLoading(false)
            result.onSuccess { view?.showUserName(it) }
                   .onFailure { view?.showError(it.message ?: "登录失败") }
        }
    }
}
```

### 2. Activity 只做"转发 + 渲染"

```kotlin
class LoginActivity : AppCompatActivity(), LoginView {

    private val repository = NetworkUserRepository()
    private lateinit var presenter: LoginPresenter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        presenter = LoginPresenter(repository)
        presenter.attachView(this)

        findViewById<Button>(R.id.btn_login).setOnClickListener {
            presenter.onLoginClicked(
                findViewById<EditText>(R.id.et_name).text.toString(),
                findViewById<EditText>(R.id.et_pwd).text.toString()
            )
        }
    }

    override fun onDestroy() {
        presenter.detachView()   // 关键：断开 View 引用，避免泄漏
        super.onDestroy()
    }

    override fun showLoading(show: Boolean) {
        findViewById<ProgressBar>(R.id.pb).visibility = if (show) View.VISIBLE else View.GONE
    }

    override fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun showUserName(name: String) {
        findViewById<TextView>(R.id.tv_welcome).text = "欢迎 $name"
    }
}
```

### 3. Presenter 的单元测试（不需要模拟器）

```kotlin
class LoginPresenterTest {
    private val fakeRepo = object : UserRepository {
        var called = false
        override fun login(u: String, p: String, callback: (Result<String>) -> Unit) {
            called = true
            callback(Result.success("Tom"))
        }
    }
    private val view = object : LoginView {
        var loading: Boolean? = null
        var error: String? = null
        var userName: String? = null
        override fun showLoading(show: Boolean) { loading = show }
        override fun showError(message: String) { error = message }
        override fun showUserName(name: String) { userName = name }
    }

    @Test
    fun `empty input shows error`() {
        val presenter = LoginPresenter(fakeRepo).apply { attachView(view) }
        presenter.onLoginClicked("", "")
        assertEquals("用户名和密码不能为空", view.error)
        assertFalse(fakeRepo.called)
    }

    @Test
    fun `success shows user name`() {
        val presenter = LoginPresenter(fakeRepo).apply { attachView(view) }
        presenter.onLoginClicked("Tom", "123456")
        assertEquals("Tom", view.userName)
        assertEquals(false, view.loading)
    }
}
```

## 常见问题 / 面试要点

**读者问：MVC、MVP、MVVM 到底差在哪？一句话能说清吗？**

**AI答：**

一句话：**差别在于"谁持有状态、谁来做决策、View 是主动还是被动"。**

1. **MVC**：Controller 决策，View 被动但**无接口约束**，Controller 常与 Android 控件绑死（Activity 既当 View 又当 Controller），逻辑无法单测。
2. **MVP**：Presenter 决策，但通过 `LoginView` **接口**与 View 通信，Presenter 不认识具体控件，可单测。
3. **MVVM**：ViewModel 决策且**不认识 View**（不认识控件、不认识生命周期），View 订阅数据流被动更新，配置变更自动保留状态。
4. **MVI**：在 MVVM 上再加一层"**单一状态 + 纯函数 Reducer**"，所有状态变更都必须经过一个可预测的入口。

记忆法：**MVC→MVP 是"给 View 加接口"，MVP→MVVM 是"把 Presenter 从 View 生命周期里摘出来"，MVVM→MVI 是"把状态收敛成不可变对象"。**

---

**读者问：Presenter 持有 View 引用为什么会内存泄漏？怎么避免？**

**AI答：**

1. **根因**：Activity 被系统销毁时，只要 Presenter 还持有它的引用，Activity 就无法回收 → 泄漏。典型场景：Presenter 里注册了一个**长生命周期**的回调（`Handler.postDelayed`、`GlobalScope.launch`、`RxJava` 的 `subscribe`、单例注册的监听器），只要那个回调活着，Presenter 就活着，View 就活着。
2. **标准解法**（三选一）：
   - 在 `onDestroy()` 里 `presenter.detachView()` 清空引用（最常见、最直白）；
   - 用 `WeakReference<LoginView>` 持有（防御性兜底）；
   - 用 **RxJava 的 `AutoDispose`/`CompositeDisposable`** 或 **协程的 `viewModelScope`** 跟随生命周期自动取消。
3. **更彻底的做法**：干脆**不要持有 View**。Presenter 只暴露"结果回调"，由 View 订阅数据流（Rx 的 `Observable` / 协程的 `Flow`），订阅随生命周期自动解除。这就是从 MVP 演进到 MVVM 的关键一步。

> 面试加分：能主动说"我现在项目里用的是 MVVM，所以没有 Presenter 持有 View 的问题，因为 ViewModel 不持有 View，只持有 LiveData/StateFlow，View 主动订阅"，说明你理解的是**动机**而不是背定义。

---

**读者问：既然有了 MVVM，MVP 还有什么价值？为什么很多老项目还在用？**

**AI答：**

MVP 仍有三类现实价值：

1. **存量系统**：2015-2019 年的 Android 项目大量是 MVP + RxJava + ButterKnife/Dagger2，重构成本高，团队会继续沿用而不是推翻。
2. **RxJava 生态的天然契合**：Rx 的 `Observable` + `Disposable` 组合非常适合"Presenter 订阅数据、View 订阅 Presenter"的**拉取式**结构；而 MVVM 的 `Flow` 更适合**推送式**单流。MVP 在多数据源合并（`merge`）场景下写起来更自然。
3. **心智模型简单**：MVP 的"接口 = 契约"非常适合 **Java 团队**和**非 Android 背景**的新人，培训成本低。

但要清楚它的**天花板**：Presenter 的生命周期通常等于 View 生命周期，屏幕旋转时 Presenter 被重建，状态照样丢失；而且 Presenter 里的"多个可变字段"很容易演变成第二个 ViewModel 的状态混乱。

---

**读者问：Presenter 和 ViewModel 到底有什么区别？我面试时该怎么说？**

**AI答：**

从 **4 个维度**回答，条理最清楚：

| 维度 | Presenter (MVP) | ViewModel (MVVM) |
| --- | --- | --- |
| 与 View 的关系 | **持有** View 接口（单向调用 View） | **不持有** View；View 订阅 ViewModel 暴露的数据流 |
| 生命周期 | 通常 = Activity/Fragment | = Activity/Fragment 的 **onCreate 到 onDestroy**（配置变更保留，进程死亡不保留） |
| 状态恢复 | 需手动存 `savedInstanceState` | 用 `SavedStateHandle` 自动存 |
| 可测试性 | 好（Presenter 是纯 Kotlin） | 更好（ViewModel 也是纯 Kotlin，且能拿到 `SavedStateHandle`） |
| 职责边界 | 负责"展示逻辑"，业务规则常在 Model | 负责"UI 状态"（一个 State 数据类），副作用（导航、Toast）走 Channel/事件 |

再补一句**演进逻辑**：MVP 里 Presenter 是"View 的经纪人"，所以它必须认识 View；MVVM 的核心主张是 **"把 View 变成一个纯粹的渲染函数"**（`UI = f(State)`），ViewModel 一旦认识 View，这个主张就破了，所以 MVVM 必须让 ViewModel 单向依赖、由 View 主动订阅。

---

**读者问：Android 里 Activity 到底算 View 还是 Controller？面试官这么问是想考什么？**

**AI答：**

考的是你**知不知道 Android 的历史包袱**，以及能不能说清"理想模型 vs 现实模型"。

1. **理想 MVC 映射**：View = XML + `View` 类，Model = 数据层，Controller = Activity。
2. **现实**：Android 早期（API 1 时代）确实提供过 `android.app.Activity` 作为 Controller，但官方随后承认这不好用，废弃了 `ActivityController`/`Intent` 那套 Controller 体系。
3. **今天的现实**：官方推荐 Activity 只做"**事件入口 + 渲染**"，即 Activity 同时扮演 View 和 Controller 两个角色——这就是"胖 Activity"问题的根源，也是 MVP/MVVM 存在的直接原因。
4. **Compose 时代的变化**：Compose 里 Activity 变成了"**纯粹的生命周期与副作用宿主**"，真正的 UI 是 `@Composable` 函数，天然更接近理想 MVC/MVVM。

> 一句话答案："Android 没有官方 MVC，Activity 事实上既是 View 又是 Controller；Compose 把 UI 从 Activity 里抽离后，才第一次让 Activity 回归到理想模型中的 Controller 角色。"

## 关联笔记

- [[MVVM]]：Presenter 的进化形态
- [[MVI]]：在 MVVM 上引入不可变 State 与 Reducer
- [[架构模式选型]]：四种模式怎么选
- [[../Jetpack库/Lifecycle与ViewModel]]：ViewModel 的生命周期实现
- [[../测试/单元测试]]：Presenter/ViewModel 如何被测试
- [[../README]]：第 7 部分总览

## 参考资料

- Google 官方架构指南（MVC/MVP/MVVM 三种模式对比）：https://developer.android.com/guide/topics/architecture
- Google "Android Basics: Compose" 架构系列课程
- 《Android 高级编程》第 4 版 · 第 7 章"架构与组件化"
