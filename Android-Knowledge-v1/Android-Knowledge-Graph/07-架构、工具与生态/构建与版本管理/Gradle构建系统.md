---
tags: [android]
part: 第7部分
status: 待整理
difficulty: 中级
created: 2026-11-04
updated: 2026-11-04
related: "[[AGP与版本适配]], [[../依赖注入/Hilt]], [[../08-企业级工程与运维/构建与打包优化]]"
---

# Gradle构建系统

## 一句话定义

Gradle 是一个**基于 Groovy/Kotlin DSL 的通用构建系统**，通过**声明式配置 + 任务（Task）依赖图 + 增量构建 + 插件机制**来组织 Android 项目的编译、测试、打包流程，是现代 Android 工程化（模块化、多渠道、CI/CD）的地基。

## 核心原理

### 1. Gradle 的三个核心概念

| 概念 | 说明 |
| --- | --- |
| **Task（任务）** | 一个**原子动作**（如 `compileDebugKotlin`、`packageRelease`）。Task 有输入（inputs）与输出（outputs），Gradle 靠此做**增量构建** |
| **Dependency Graph（依赖图）** | Task 之间通过 `dependsOn` / 输入输出形成的 **DAG**，Gradle 用**拓扑排序**决定执行顺序，并**并行执行无依赖关系的 Task** |
| **Plugin（插件）** | 一组可复用的构建逻辑（AGP 就是一个插件），通过 `apply plugin` 引入。插件的本质是"约定 + 扩展"（Convention over Configuration） |

**增量构建的核心**：Gradle 记录每个 Task 的**输入快照**和**输出快照**，下一次构建时如果输入没变且输出未被篡改，就**跳过该 Task**（`UP-TO-DATE`）。

### 2. Gradle 项目的两个入口文件

```text
项目根目录/
├── settings.gradle.kts     ← 定义"有哪些模块"、仓库、插件仓库
├── build.gradle.kts        ← 根项目的构建配置
├── gradle/
│   ├── libs.versions.toml  ← Version Catalog（版本目录）
│   └── wrapper/            ← gradle-wrapper.properties（Gradle 版本）
└── app/
    ├── build.gradle.kts    ← 模块的构建配置
    └── src/
```

- **`settings.gradle.kts`**：定义模块包含、仓库、插件管理。
- **`build.gradle.kts`**（模块级）：配置 `android {}`、`dependencies {}`、插件应用。

### 3. Kotlin DSL vs Groovy DSL

| 维度 | Kotlin DSL (`.kts`) | Groovy DSL (`.gradle`) |
| --- | --- | --- |
| 类型安全 | **有**（IDE 补全、编译期检查） | 无 |
| 写法 | `val x: String = "a"` | `def x = 'a'` |
| 脚本缓存 | **支持**（编译为 class 缓存） | 不支持 |
| 学习成本 | 需懂 Kotlin | 语法宽松，上手快 |
| 官方趋势 | ✅ 默认推荐 | 保留兼容 |

> **官方建议**：新项目一律用 `.kts`。

### 4. `settings.gradle.kts` 的标准写法

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)  // 禁止模块里自己声明仓库
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MyApp"

include(":app")
include(":core:network")
include(":core:ui")
include(":feature:login")
```

- **`RepositoriesMode.FAIL_ON_PROJECT_REPOS`**：统一仓库来源，避免"仓库地狱"和依赖冲突。
- **`content { includeGroupByRegex(...) }`**：限定每个仓库的搜索范围 → **大幅加快依赖解析**（先去对的仓库找）。

### 5. Version Catalog（版本目录）—— 现代 Gradle 的核心实践

`gradle/libs.versions.toml`：

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
coreKtx = "1.15.0"
composeBom = "2024.12.01"
hilt = "2.53"
ksp = "2.0.21-1.0.28"
junit = "4.13.2"
mockk = "1.13.13"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version = "2.8.7" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
junit = { module = "junit:junit", version.ref = "junit" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

使用：

```kotlin
// build.gradle.kts (根)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.hilt) apply false
}

// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))   // BOM：统一版本
    implementation(libs.androidx.compose.ui)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
}
```

**BOM（Bill of Materials）**：`platform(libs.androidx.compose.bom)` 一次性锁定 Compose 全家桶版本，避免逐个写版本号导致版本冲突。

**Version Catalog 的价值**：
1. 版本集中管理，一处修改全项目生效；
2. IDE 自动补全；
3. **依赖升级工具友好**（IDE 可一键升级并同步改 Catalog）；
4. 避免"同一个库在不同模块写了不同版本"。

### 6. 模块级 `build.gradle.kts` 关键配置

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") { applicationIdSuffix = ".dev"; buildConfigField("String", "BASE_URL", "\"https://dev...\"") }
        create("prod") { buildConfigField("String", "BASE_URL", "\"https://api...\"") }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)

    implementation(project(":core:network"))
}
```

### 7. Variant（变体）模型

```text
Build Type (debug / release) × Product Flavor (dev / prod) = Variant
```

- 常见变体：`devDebug`、`devRelease`、`prodDebug`、`prodRelease`。
- **Task 名 = `任务名 + Variant`**，如 `assembleProdRelease`、`testDevDebugUnitTest`。
- **每个变体可独立配置**：

```kotlin
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.sources.java?.addStaticSourceDirectory("src/releaseExtra/java")
    }
}
```

- **`buildConfigField`** 用于给每个变体注入常量（API 地址、密钥），比 `resValue` 更灵活。
- **常见坑**：变体数量 = BuildType × Flavor，**每加一个 flavor，测试/构建任务数量翻倍** → 构建时间爆炸。要控制变体数量。

### 8. Convention Plugin（约定插件）—— 多模块项目的最佳实践

把重复的模块配置抽到 `build-logic` 独立模块，所有模块复用：

```text
build-logic/
├── settings.gradle.kts
├── convention/
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       ├── AndroidApplicationConventionPlugin.kt
│       ├── AndroidLibraryConventionPlugin.kt
│       └── KotlinAndroidConventionPlugin.kt
```

```kotlin
// AndroidLibraryConventionPlugin.kt
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.library")
            apply("org.jetbrains.kotlin.android")
        }
        extensions.configure<LibraryExtension> {
            compileSdk = 35
            defaultConfig {
                minSdk = 24
                consumerProguardFiles("consumer-rules.pro")
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
}
```

使用：

```kotlin
// core/network/build.gradle.kts
plugins {
    id("convention.android.library")
}
```

**收益**：修改公共配置只需改一处；构建脚本大幅简化；统一了 lint/ktlint 等质量规则。

### 9. 依赖管理

```kotlin
dependencies {
    // 直接依赖
    implementation("com.squareup.retrofit2:retrofit:2.11.0")

    // api vs implementation
    api(project(":core:model"))        // 对外暴露传递依赖（库模块常用）
    implementation(project(":core:ui")) // 不对外暴露

    // 限定传递依赖的版本
    implementation("androidx.core:core-ktx") {
        version { strictly("[1.13.1,)") }   // 严格约束
        because("解决 core-spliterator 冲突")
    }

    // 排除传递依赖
    implementation("com.squareup.okhttp3:okhttp") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    // 特定配置
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
    kaptTest(libs.mockito.kotlin)
    androidTestImplementation(libs.espresso.core)
}
```

**依赖传递与冲突**：Gradle 默认选**最高版本**；可用 `constraints` 统一约束：

```kotlin
dependencies {
    constraints {
        implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    }
}
```

### 10. 构建加速的关键手段

| 手段 | 说明 | 收益 |
| --- | --- | --- |
| **Configuration Cache** | 缓存"配置阶段"的结果，跳过重复配置 | 大型项目首次配置后**提升 30%~50%** |
| **Build Cache** | 缓存 Task 输出（本地/远程） | 切分支/CI 上复用 |
| **并行执行** | `org.gradle.parallel=true` | 多模块并行构建 |
| **按需配置** | `org.gradle.configureondemand=true` | 只配置需要的模块 |
| **减少变体数量** | Flavor 收敛 | Task 数下降 |
| **KSP 代替 KAPT** | KSP 比 KAPT 快 | 通常 2~3 倍 |
| **Baseline Profile** | 预编译 ART 热点 | 启动与运行性能提升 |
| **依赖隔离/模块化** | 减少无关模块重编译 | 增量构建更快 |
| **避免 `allprojects` 配置** | 用 `subprojects` 精确配置 | 减少配置时间 |

```kotlin
// gradle.properties
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.configureondemand=true
kotlin.code.style=official
android.useAndroidX=true
android.nonTransitiveRClass=true
android.nonFinalResIds=false
kotlin.incremental=true
```

### 11. 常用命令

```bash
./gradlew tasks                      # 列出所有任务
./gradlew :app:assembleDebug         # 构建 debug APK
./gradlew :app:installDebug          # 安装到设备
./gradlew :app:assembleProdRelease   # 构建渠道包
./gradlew :app:testDebugUnitTest     # 跑单元测试
./gradlew :app:connectedDebugAndroidTest   # 跑仪器测试
./gradlew :app:lint                  # 跑 lint
./gradlew :app:bundleRelease         # 构建 AAB
./gradlew :app:dependencies          # 查看依赖树
./gradlew :app:dependencyInsight --dependency okhttp   # 分析某个依赖的来源
./gradlew clean build                # 清理 + 全量构建
./gradlew --profile                  # 生成性能报告（HTML）
./gradlew --scan                     # 远程构建缓存（需配置）
```

## 常见问题 / 面试要点

**读者问：Gradle 的构建流程是怎样的？配置阶段和执行阶段有什么区别？**

**AI答：**

1. **两大阶段**：
   - **配置阶段（Configuration）**：解析 `settings.gradle.kts` → 确定模块列表 → 解析每个 `build.gradle.kts` → **创建所有 Task 对象**（此时不执行任何编译）。这个阶段会执行**任意 Kotlin 代码**，是构建慢的主要原因。
   - **执行阶段（Execution）**：按依赖图**拓扑排序**执行 Task（编译、打包、测试），无依赖关系的 Task **并行**执行。
2. **Task 的增量机制**：
   - 每个 Task 声明 **inputs**（源文件、类路径、属性）与 **outputs**（class 文件、APK）；
   - Gradle 保存输入/输出的**快照**（hash）；
   - 下次构建时：输入未变且输出未被外部修改 → Task 标记为 **`UP-TO-DATE`**，直接跳过。
3. **Configuration Cache 的意义**：把**配置阶段的结果**序列化缓存下来，下次构建直接复用 → 跳过大部分 `build.gradle.kts` 的执行。**前提是构建脚本没有读取外部可变状态**（如 `System.getenv` 随时间变化、网络请求）——否则需要 `@CacheableTask` 或标注不缓存。
4. **面试表达**："Gradle = **配置阶段建图 + 执行阶段按图执行**。慢通常慢在配置阶段和注解处理（KAPT），所以优化方向是 **Configuration Cache + KSP + 减少变体**。"

---

**读者问：Version Catalog 是什么？为什么推荐用它而不是在 build.gradle 里直接写版本号？**

**AI答：**

1. **是什么**：`gradle/libs.versions.toml` 里的一个**声明式依赖清单**，Gradle 7.4+ / AGP 8.1+ 起作为推荐方式。通过 `libs.xxx` 引用。
2. **解决的问题**：
   - **版本漂移**：同一依赖在 5 个模块写了 5 个版本号 → 运行期才暴露冲突；
   - **升级繁琐**：改版本要改几十处；
   - **缺少类型安全与补全**：Groovy DSL 里写错库名要等到解析失败才发现。
3. **核心能力**：
   - **`[versions]`**：集中定义版本，支持 `version.ref` 引用；
   - **`[libraries]`**：定义依赖坐标，**`alias`（别名）可省略 `module`**（用别名自动推导坐标）：

```toml
[versions]
retrofit = "2.11.0"

[libraries]
retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
retrofit-alias = "com.squareup.retrofit2:retrofit"   # 别名推导：retrofit-alias → retrofit:retrofit
```

   - **`[plugins]`**：插件版本也集中管理。
   - **BOM 支持**：`platform(libs.androidx.compose.bom)`。
4. **升级体验**：IDE 可直接对 Catalog 做 **"Upgrade Dependencies"**，版本号自动同步更新（这是 Catalog 被强推的核心原因）。
5. **面试表达**："Version Catalog 解决的是**依赖版本的一致性与可维护性**，并且让 IDE 的依赖升级工具能安全地批量更新。"

---

**读者问：`api` 和 `implementation` 有什么区别？依赖传递是怎么工作的？**

**AI答：**

1. **`implementation` vs `api`**：
   - `implementation` 的依赖**不会传递给使用者**（编译期可见，运行期在 runtime classpath 里，但**不暴露给 API 检查**）；
   - `api` 的依赖**会传递给使用者**（出现在 POM 的 `compile` scope）。
   - **后果**：库用 `implementation` 引入 OkHttp，使用者如果也用 OkHttp 但版本不同 → **不会有版本冲突警告**，但运行期可能出现 `NoSuchMethodError` → **这是线上事故的常见来源**。
2. **传递依赖规则**：
   - Gradle 默认选择**版本最高**的依赖（"冲突解决"策略）；
   - 若两个版本不兼容（如 OkHttp 3.x vs 4.x），必须**显式约束**。
3. **强制统一版本的做法**：

```kotlin
dependencies {
    constraints {
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
    }
    implementation(platform("com.squareup.okhttp3:okhttp-bom:4.12.0"))  // 用 BOM
}
```

4. **查看依赖冲突**：

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
./gradlew :app:dependencyInsight --dependency okhttp --configuration releaseRuntimeClasspath
```

   `dependencyInsight` 会显示"哪个模块引入了它、为什么选了这个版本" → **排查依赖冲突的标准工具**。
5. **排错口诀**："**看到 NoSuchMethodError，先跑 dependencyInsight 看版本树，再决定用 constraints 还是 exclude。**"

---

**读者问：`buildType` 和 `productFlavor` 有什么区别？变体过多有什么问题？**

**AI答：**

1. **区别**：
   - **`buildType`（构建类型）**：描述**构建方式**（debug/release，debuggable、签名、混淆），由 AGP 预定义 `debug` 与 `release`。
   - **`productFlavor`（产品风味）**：描述**产品版本**（`dev`/`prod`/`mock`），用于同一代码库产出**多个产品**（免费版/付费版、国内版/海外版）。
   - **`flavorDimensions`（维度）**：风味可以多维（如 `environment` × `tier`），变体数 = 各维度风味数**相乘** × buildType 数。
2. **变体过多的问题**：
   - **构建时间爆炸**：变体数 × Task 数，每个变体都要编译 + 跑测试；
   - **CI 矩阵爆炸**：N 个变体 = N 套测试；
   - **配置复杂度上升**（`variantFilter` 之类的 hack 增多）。
3. **优化手段**：
   - **用 `variantFilter` / `androidComponents.beforeVariants` 过滤不需要的变体**：

```kotlin
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variantBuilder ->
        variantBuilder.enable = false     // 不构建 debug 的某些 flavor
    }
}
```

   - **把"环境差异"从 Flavor 改为运行时配置**（远端配置/开关），只保留必要的 Flavor；
   - **CI 上只对关键变体跑测试**（`gradleManagedDevices` + 变体过滤）。
4. **常见约定**：
   - `buildType` 用于**技术维度**（是否混淆、是否可调试）；
   - `productFlavor` 用于**业务维度**（产品线、地区）；
   - 不要用 Flavor 表达"签名不同"（那是 buildType + signingConfig 的事）。

---

**读者问：Gradle 构建太慢怎么优化？**

**AI答：**

按"**先测量，再优化**"的顺序给方案：

1. **先定位瓶颈**：
   ```bash
   ./gradlew assembleDebug --profile      # 生成 HTML 报告
   ./gradlew assembleDebug --scan         # 上传扫描（需账号）
   ```
   报告会告诉你：配置阶段多久、每个 Task 多久、是否频繁 UP-TO-DATE 失效。
2. **配置阶段**：
   - 开启 **`org.gradle.configuration-cache=true`**（通常提升最大）；
   - `org.gradle.configureondemand=true`；
   - **避免在 `build.gradle.kts` 顶层写复杂逻辑**（网络请求、读大文件、遍历目录）；
   - 用 **Convention Plugin** 替代重复配置（配置也需要执行，重复配置同样耗时）。
3. **依赖解析**：
   - `repositories { content { includeGroupByRegex(...) } }` 限定搜索范围；
   - 减少仓库数量与 `mavenLocal()`（本地仓库会遍历所有文件）。
4. **注解处理**：
   - **KSP 替代 KAPT**（Dagger、Room、Hilt 都支持 KSP）→ 通常快 2~3 倍；
   - 减少 `kapt` 的 `correctErrorTypes` 等参数。
5. **构建执行**：
   - `org.gradle.parallel=true` + `org.gradle.caching=true`；
   - **增大 `org.gradle.jvmargs`**（`-Xmx4g` 是常见起点）；
   - **减少变体数量**（见上一题）；
   - **模块化**（改一个模块只重编一个模块）。
6. **Kotlin 编译**：
   - 开启 **`kotlin.incremental=true`**；
   - `kotlin.compiler.execution.strategy=in-process`（小项目更快）/ `daemon`（大项目更稳）；
   - 避免 `allWarningsAsErrors` 带来的额外检查。
7. **CI 层面**：
   - 用 **Build Cache**（本地或远程 `~/.gradle/caches/build-cache-1`）跨分支复用；
   - **按需构建**（`assembleDevDebug` 而不是 `assemble`）。
8. **面试表达**："**先跑 `--profile` 找瓶颈，再对症下药：配置阶段 → Configuration Cache；注解处理 → KSP；重复编译 → 模块化 + 增量。**"

## 关联笔记

- [[AGP与版本适配]]：AGP / Gradle / JDK 版本对应关系与 targetSdk 适配
- [[../依赖注入/Hilt]]：KSP 的典型使用场景
- [[../08-企业级工程与运维/构建与打包优化]]：构建加速与打包优化的工程化落地
- [[../08-企业级工程与运维/模块化与组件化]]：模块划分与 Convention Plugin
- [[../README]]：第 7 部分总览

## 参考资料

- Gradle 官方文档（中文）：https://docs.gradle.org/current/userguide/
- Android 官方 · 版本目录：https://developer.android.com/build/migrate-to-catalogs
- Android 官方 · 构建配置 DSL：https://developer.android.com/build/kotlin-dsl
- Gradle 配置缓存：https://docs.gradle.org/current/userguide/configuration_cache.html
