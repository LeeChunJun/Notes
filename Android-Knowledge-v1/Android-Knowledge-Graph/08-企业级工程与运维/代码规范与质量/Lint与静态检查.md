---
tags: [android]
part: 第8部分
status: 待整理
difficulty: 高级
created: 2026-11-19
updated: 2026-11-19
related: "[[编码规范]], [[../README]]"
---

# Lint 与静态检查

## 一句话定义

静态检查（Static Analysis）是在**不运行程序**的前提下，通过语法树/字节码/静态分析规则找出代码中的潜在缺陷与规范问题。Android 生态里由三支力量组成：**Android Lint**（Android 专项）、**detekt**（Kotlin 语义）、**ktlint**（格式），它们共同把"代码质量"从人工 Review 的负担变成**构建流水线上的确定性门禁**。

面试开场：**"静态检查的价值不在于找出 bug，而在于把'风格争论'从 Code Review 里彻底消灭掉，让 Review 只讨论设计。"**

## 核心原理

### 1. Android Lint 的工作原理

Android Lint 是一个**独立的分析工具**，其运行流程如下：

```
┌────────────────────────────────────────────────────────────┐
│  1. 解析阶段（Analysis）                                     │
│     UAST（统一抽象语法树）→ 语义分析（Semantic Analysis）    │
│     ├─ 读取 manifest、解析 resources、读取 class 文件         │
│     ├─ 建立跨文件的引用关系（谁调用了这个方法？资源被谁用了？）│
│     └─ Android SDK 知识库：API level、权限、生命周期语义     │
├────────────────────────────────────────────────────────────┤
│  2. 检查阶段（Check）                                       │
│     IssueDetector 遍历 UAST，调用 IssueRegistry 的 detector  │
│     每个 detector 注册若干 Issue（问题 ID + 严重级别 + 说明） │
├────────────────────────────────────────────────────────────┤
│  3. 报告阶段（Report）                                      │
│     输出 HTML / XML / SARIF / 文本报告                     │
│     定位到具体文件 + 行号 + 列号 + 代码片段                  │
└────────────────────────────────────────────────────────────┘
```

**关键理解**：Lint 的价值来自**语义分析**。它不是文本匹配——它能知道 `textView.setText()` 必须在主线程调用（`WrongThread`），能知道 `ImageView.setImageResource` 在 `onCreate` 里是安全的而在 `onResume` 里可能有问题（`StaticFieldLeak`、`RecyclerView` 误用），能知道某段代码在 API 24 上会崩溃（`NewApi`）。

### 2. 严重级别与门禁策略

Lint 的每个 Issue 都有一个严重级别，它决定了在 CI 中的行为：

| 级别 | 含义 | CI 行为建议 |
| --- | --- | --- |
| **Fatal** | 一定是错误 | **直接构建失败** |
| **Error** | 很可能是错误 | **构建失败**（可按项目微调） |
| **Warning** | 存在疑问 | 记为警告，按数量或新增量卡点 |
| **Informational** | 提示信息 | 仅记录，不阻断 |

```kotlin
// build.gradle.kts（模块级）
android {
    lint {
        // 构建时自动执行 lint
        checkReleaseBuilds = true          // 发布构建也检查
        abortOnError = true                // 有 Error 级别问题就失败
        warningsAsErrors = false           // 视团队成熟度开启
        ignoreTestSources = true
        checkDependencies = true           // 检查依赖库里的问题

        // 只关心增量问题：基线制
        baseline = file("lint-baseline.xml")
        // baseline 不存在时自动创建
        checkBaseline = true

        // 定制严重级别
        severity += "MissingTranslation"     // 缺多语言翻译视为 Error
        severity += "UnusedResources"        // 未用资源视为 Error（包体优化）
    }
}
```

```bash
# 命令行运行方式
./gradlew lint                      # 对所有模块跑
./gradlew :app:lintDebug           # 只跑某个模块的某个 variant
./gradlew lintDebug -Dlint.baselines.continue=true   # 有问题不失败，只更新基线
./gradlew lintVitalRelease          # 只跑 Release 阻塞级检查（Google 官方用法）

# 生成报告
./gradlew :app:lintReport           # 生成 HTML 报告到 app/build/reports/lint-results-debug.html
```

> **面试点**：`lintVitalRelease` 是 Play Store 上架流程的一部分，Google Play 会对上传的 AAB 自动执行这类"致命"检查并可能拒绝上架。

### 3. 常用 Android Lint 规则分类

| 类别 | 代表规则 | 说明 |
| --- | --- | --- |
| **正确性** | `NewApi`、`InlinedApi`、`Recycle`、`StaticFieldLeak`、`WrongThread`、`BadParcelableException` | 真 bug，必须修 |
| **安全** | `MissingPermission`、`UnsafeImplicitIntentLaunch`、`AllowBackup`、`ExportedReceiver`、`SetJavaScriptEnabled` | 安全风险 |
| **性能** | `ObsoleteLayoutParam`、`UselessParent`、`DrawAllocation`、`HandlerLeak`、`WakelockTimeout`、`SlowOperations` | 主线程 IO、内存 |
| **可用性** | `HardcodedText`、`MissingContentDescription`、`SmallSp`、`LabelFor`、`TouchTargetSizeCheck` | 无障碍与国际化 |
| **国际化** | `MissingTranslation`、`ExtraTranslation`、`Typos` | 多语言 |
| **资源** | `UnusedResources`、`Overdraw`、`IconMissingDensityFolder`、`StringFormatInvalid` | 包体与正确性 |
| **代码质量** | `UnusedImport`（Kotlin 不适用）、`Deprecated`、`ObsoleteSdkInt`、`GradleDependency`、`OldTargetApi` | 技术债 |

### 4. 自定义 Lint 规则

自定义 Lint 是**把团队的架构约束写进构建流程**的最优雅方式。比如："禁止在 `feature` 模块直接依赖另一个 `feature` 模块"。

```kotlin
// 自定义 Lint 规则实现（放在独立的 lint 模块中）
package com.example.lint.rules

import com.android.tools.lint.detector.api.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.uast.UCallExpression

/**
 * 禁止 feature 模块之间直接依赖的 Lint 规则。
 *
 * 实现要点：
 * 1. 继承 Detector，声明它关心的 PSI 类型
 * 2. 在 visitXxx 中执行检查
 * 3. 通过 IssueRegistry 注册并声明支持的文件类型
 */
class FeatureDependencyDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UElement::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler =
        object : UElementHandler() {
            override fun visitCallExpression(node: UCallExpression) {
                // 检查 gradle 脚本中的 project(":feature:xxx") 依赖声明
                val file = node.sourcePsi?.containingFile ?: return
                if (!file.name.endsWith("build.gradle.kts")) return

                val text = node.sourcePsi?.text ?: return
                if (text.contains("project(\":feature:") && text.contains("implementation")) {
                    context.report(
                        Incident(
                            message = "feature 模块之间不允许直接依赖，请通过 core 模块或路由通信",
                            location = context.getLocation(node.sourcePsi as PsiElement),
                            issue = ISSUE
                        )
                    )
                }
            }
        }

    companion object {
        private val IMPLEMENTATION = Implementation(
            FeatureDependencyDetector::class.java,
            Scope.JAVA_FILE_SCOPE
        )

        @JvmField
        val ISSUE: Issue = Issue.create(
            id = "FeatureDependencyViolation",
            briefDescription = "Feature 模块直接依赖其他 Feature 模块",
            explanation = """
                组件化架构要求 feature 模块之间保持独立，通过路由或共享 core 模块通信。
                直接依赖会导致模块编译耦合、无法独立发布与单独调试。
            """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 7,
            severity = Severity.ERROR,
            implementation = IMPLEMENTATION
        )
    }
}
```

```kotlin
// 注册规则
package com.example.lint.rules

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.*
import com.android.tools.lint.detector.api.CATEGORY
import com.intellij.psi.PsiElement

class MyLintRegistry : IssueRegistry() {
    override val issues = listOf(FeatureDependencyDetector.ISSUE)

    override val api: Api = Api(
        currentApi = CURRENT_API,
        minApi = 14,
        vendor = Vendor(
            vendorId = "com.example",
            feedbackUrl = "https://github.com/example/lint-rules/issues",
            identifier = "com.example.lint:my-lint-rules:${CURRENT_VERSION}"
        )
    )
}
```

```kotlin
// lint-rules/build.gradle.kts
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
}

dependencies {
    // compileOnly：lint API 由 AGP 提供，不打进产物
    compileOnly("com.android.tools.lint:lint-api:31.7.0")
    compileOnly("com.android.tools.lint:lint-checks:31.7.0")
    testImplementation("com.android.tools.lint:lint-tests:31.7.0")
}
```

```bash
# 使用本地 lint 规则模块
./gradlew :app:lintDebug \
  -Pandroid.experimental.lint.missingBaselineIsEmptyBaseline=true \
  -Pandroid.experimental.lint.version=31.7.0 \
  -Pandroid.lint.useK2Uast=true \
  -Pandroid.lint.jarPath=$(pwd)/lint-rules/build/libs/lint-rules.jar
```

> **面试话术**："自定义 Lint 规则是把'架构约定'变成可执行断言的最佳手段。我们用它守住了 feature 模块不互相依赖这条红线，效果比写在 wiki 里好一百倍。"

### 5. ktlint / detekt / Spotless 职责对比

```
┌─────────────┬──────────────┬──────────────┬──────────────┬──────────────┐
│             │  Android Lint│    detekt    │    ktlint    │   Spotless   │
├─────────────┼──────────────┼──────────────┼──────────────┼──────────────┤
│ 分析对象     │ UAST+Android │ Kotlin PSI   │ Kotlin 文本  │ 调用 ktlint  │
│ 语义深度     │  ★★★★★      │  ★★★★☆       │  ★★☆☆☆       │  代理        │
│ Android 专项 │  ✔✔✔         │  ✘           │  ✘           │  ✘           │
│ 复杂度检测   │  部分        │  ✔✔✔         │  ✘           │  ✘           │
│ 命名规范     │  部分        │  ✔✔✔         │  ✔✔          │  代理        │
│ import 排序  │  ✘           │  ✔           │  ✔✔          │  代理        │
│ 自动修复     │ 部分         │  部分(autoCorrect)│ ✔✔         │  ✔✔✔        │
│ Baseline    │  ✔           │  ✔           │  ✘           │  ✘           │
│ 适用阶段     │ 构建/CI      │ 构建/CI      │ 提交前/CI    │  提交前/CI   │
└─────────────┴──────────────┴──────────────┴──────────────┴──────────────┘
```

**推荐的组合拳**：

```
提交前（pre-commit hook）
    └─ Spotless → ktlint → 自动格式化 + import 排序
构建时（Gradle task）
    └─ detekt → 复杂度/命名/潜在 Bug
    └─ Android Lint → Android 专项问题
CI 时
    └─ 上述全部 + baseline 对比 + 增量卡点
```

### 6. Kotlin 编译器警告与 `@Suppress`

编译器本身的警告分两类：

| 级别 | 处理方式 |
| --- | --- |
| **Warning** | 默认不阻断构建，但会淹没真正重要的信息 |
| **Error** | 编译失败（如 Kotlin 2.0 后部分弃用升级为 error） |

```kotlin
// allWarningsAsErrors：把所有警告升级为错误（严格项目）
kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        freeCompilerArgs.add("-Xexplicit-api=strict")   // 库模块：要求显式 public 与返回类型
    }
}

// 局部抑制（必须带原因，否则是代码异味）
@Suppress("DEPRECATION")                       // 临时：等 API 迁移完成
@SuppressLint("StaticFieldLeak")              // Android Lint 专用抑制
@Suppress("unused", "ReplaceCallWithBinaryOperator")
fun legacy() { }

// 精确到声明级别，比整个文件抑制更安全
@SuppressLint("NotifyDataSetChanged")
override fun onDataSetChanged() { /* ... */ }
```

**`@Suppress` 使用的红线**：
1. **必须写 `reason` 参数**（Kotlin 1.6+ 支持 `@Suppress("id", reason = "...")`）。
2. **范围尽量小**：抑制单个声明，不要整个文件。
3. **必须有替代方案或截止日期**。
4. **Lint 抑制与编译器抑制是两套系统**：`@Suppress` 抑制 Kotlin 编译器，`@SuppressLint` 抑制 Android Lint。

### 7. Baseline（问题基线）机制

Baseline 是静态检查落地的**关键工程机制**，它让"存量问题"与"新增问题"分离。

```xml
<!-- lint-baseline.xml（自动生成，务必提交到仓库） -->
<issues format="6" by="lint 31.7.0">
    <issue id="GradleDependency" severity="Warning">
        <ignore path="build.gradle" />
        <ignore regexp="true" />
    </issue>
    <issue id="UnusedResources" severity="Warning">
        <ignore path="src/main/res/values/legacy_strings.xml" />
    </issue>
    <issue id="MissingTranslation" severity="Error">
        <ignore path="src/main/res/values-zh-rCN/strings.xml" />
    </issue>
</issues>
```

```bash
# 生成/更新 baseline（把当前所有问题"豁免"）
./gradlew :app:lintDebug -Dlint.baselines.continue=true

# CI 中校验：baseline 里不应该出现已被修复的问题
# 配合 "lint-baseline.xml 不得增大" 的检查实现"只减不增"
```

```kotlin
// 用脚本在 CI 中校验 baseline 是否增大
// scripts/check-lint-baseline.sh
#!/usr/bin/env bash
set -euo pipefail

BASELINE="app/lint-baseline.xml"
BASE_BRANCH="${1:-origin/main}"

# 统计 baseline 中的 ignore 条目数量
count_entries() {
  grep -c "<ignore" "$1" 2>/dev/null || echo 0
}

CURRENT=$(count_entries "$BASELINE")
BASE=$(git show "$BASE_BRANCH:$BASELINE" 2>/dev/null | grep -c "<ignore" || echo 0)

if [ "$CURRENT" -gt "$BASE" ]; then
  echo "❌ Lint baseline 增大了（$BASE -> $CURRENT），请修复新引入的问题而不是扩大 baseline"
  exit 1
fi
echo "✅ Lint baseline 未增大（$BASE -> $CURRENT）"
```

### 8. 静态检查的 CI 卡点策略

直接开箱即用的"全量阻断"会导致团队在第一次跑 CI 时被几百个存量问题卡死，反而被绕过。成熟的做法：

```yaml
# 阶段式卡点策略
stages:
  - name: baseline-check
    description: "检查静态检查基线未增大（对所有 PR 生效，最容易落地）"
    blocks_merge: true

  - name: strict-on-new-code
    description: "仅对变更文件执行严格规则（基于 diff）"
    blocks_merge: true

  - name: full-lint
    description: "全量扫描并出具报告（不阻断，仅告警）"
    blocks_merge: false
```

**基于 diff 的增量检查**是最高效的方案：

```bash
# 只对本次 PR 变更的 Kotlin 文件跑 detekt
CHANGED=$(git diff --name-only origin/main...HEAD -- '*.kt' | tr '\n' ',' | sed 's/,$//')
if [ -n "$CHANGED" ]; then
  ./gradlew detekt -Pdetekt.baseline=app/detekt-baseline.xml
  # detekt 本身会读取 baseline；配合 --baseline 更新需谨慎
fi
```

## 代码示例

### 1. 统一 lint 配置的 Convention Plugin

```kotlin
// build-logic/convention/src/main/kotlin/QualityConventionPlugin.kt
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")

        extensions.configure<LibraryExtension> {
            lint {
                abortOnError = true
                checkReleaseBuilds = true
                baseline = rootProject.file("config/lint/lint-baseline.xml")
                htmlReport = true
                xmlReport = true
                sarifReport = true          // 供 GitHub Code Scanning 消费
                // 自定义规则模块
                lintPublish / lintPublishProject {
                    lintPublishJars(project(":lint-rules"))
                }
                // 项目定制严重级别
                severity += "MissingTranslation"
                severity += "HardcodedText"
                disable += "GradleDependency"   // 版本升级由 Renovate 统一管理
                disable += "ObsoleteLintCustomCheck"
            }
        }

        extensions.configure<KotlinAndroidProjectExtension> {
            compilerOptions {
                allWarningsAsErrors = false      // 逐步推进，先 false 后 true
                freeCompilerArgs.addAll("-Xjvm-default=all")
            }
        }
    }
}
```

### 2. detekt 的 Type Resolution 与 Baseline

```yaml
# config/detekt/detekt.yml —— 开启类型解析才能检测更多规则
config:
  validation: true
  warningsAsErrors: false
  excludes: '**/build/**,**/generated/**'

# 注释掉的规则默认不启用，需要显式打开
processors:
  active: true

console-reports:
  active: true

output-reports:
  active: true

comments:
  active: true
  UndocumentedPublicClass:
    active: true          # public 类必须有文档
  UndocumentedPublicFunction:
    active: true

complexity:
  active: true
  LongMethod:
    active: true
    threshold: 60
  LongParameterList:
    active: true
    functionThreshold: 6
    ignoreAnnotated: ['Composable']

exceptions:
  TooGenericExceptionCaught:
    active: true
    exceptionNames:
      - 'Exception'
      - 'Throwable'
      - 'RuntimeException'
  SwallowedException:
    active: true             # 吞掉异常必须报警
  PrintStackTrace:
    active: true             # 禁止 printStackTrace

potential-bugs:
  active: true
  UnsafeCallOnNullableType:
    active: true
  CastToNullableType:
    active: true
  ImplicitDefaultLocale:
    active: true             # String.format 不带 Locale 的隐患

performance:
  active: true
  SpreadOperator:
    active: true             # 禁止 spread operator 造成的大数组分配
```

```bash
# detekt 常用命令
./gradlew detekt                              # 全量
./gradlew detekt --auto-correct                # 自动修复可修复项
./gradlew detekt -Pdetekt.baseline=app/detekt-baseline.xml   # 使用 baseline
./gradlew detektBaseline                      # 重新生成 baseline
./gradlew detektBaselineId='legacy'            # 生成带 ID 的 baseline，便于分区治理
```

### 3. 用 Gradle 任务实现"只检查变更文件"

```kotlin
// build.gradle.kts（根项目）
val changedKotlinFiles by tasks.registering {
    description = "获取相对 main 分支变更的 Kotlin 文件列表"
    group = "verification"
    doLast {
        val base = providers.gradleProperty("baseBranch").orElse("origin/main").get()
        val output = exec {
            commandLine("git", "diff", "--name-only", "$base...HEAD", "--", "*.kt")
            isIgnoreExitValue = true
        }.standardOutput.asText().orEmpty()
        val files = output.lines().filter { it.endsWith(".kt") && it.isNotBlank() }
        // 写入文件供 detekt 读取
        file("$buildDir/changed-kotlin-files.txt").writeText(files.joinToString("\n"))
        logger.lifecycle("检测到 ${files.size} 个变更的 Kotlin 文件")
    }
}

tasks.named("detekt") {
    dependsOn(changedKotlinFiles)
    // 只对变更文件应用严格规则
    setProperty("detektConfig", "config/detekt/detekt-strict.yml")
}
```

### 4. Kotlin 编译警告转错误 + 显式 API 模式

```kotlin
// 库模块：要求所有 public 声明显式标注可见性与返回类型
kotlin {
    explicitApi()
}

// 应用模块：全部警告视为错误
kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xcontext-receivers"
        )
    }
}
```

```kotlin
// library 模块中：显式 API 模式强制写成这样
public class UserRepository internal constructor(
    private val api: UserApi
) {
    // 返回类型必须显式声明
    public suspend fun loadUser(id: String): User? = api.fetch(id)

    // 内部可见性不需要显式 public
    internal fun clearCache() { }
}
```

## 常见问题 / 面试要点

**读者问：Android Lint 能发现哪些问题？为什么它比普通静态分析工具更强？**

**AI答：**

1. **Lint 的四类能力**：
   - **Android 专项语义**：知道 `Handler` 持有的 Runnable 会造成泄漏（`HandlerLeak`）、知道 `onCreate` 中 inflate 大量 View 是内存问题（`DrawAllocation`）。
   - **跨文件引用分析**：能发现"这个布局里的 `@string/foo` 在 `values-zh` 里没有对应翻译"（`MissingTranslation`），需要建立资源引用图。
   - **API level 兼容性**：能查 `Build.VERSION.SDK_INT` 判断，识别出在 API 24 上会 `NoSuchMethodError` 的调用（`NewApi`）。
   - **资源与清单校验**：`MissingPermission`、`ExportedReceiver`、`WrongManifestAttribute`、`AllowBackup`。
2. **比"文本匹配"强的原因**：Lint 基于 **UAST（Unified AST）**，把 Kotlin/Java 都统一成同一套 AST，再叠加 **Android SDK 的语义模型**（哪些 API 需要什么权限、哪些方法必须在主线程、哪些资源在哪个 configuration 下存在）。所以它能理解"语义上的错"，而不只是"写法上的错"。
3. **举例说明"文本匹配做不到"**：
   - `HardcodedText` 要知道这个字符串来自 `android:text` 而非 `@string`，且要排除 `tools:text`（预览用）→ 需要理解 XML 语义。
   - `UnusedResources` 要知道这个资源在**运行时**（通过 `getIdentifier` 动态获取）可能仍被使用，所以要提示 `tools:ignore` → 需要分析代码引用。
   - `WrongThread` 要理解 `@MainThread` 注解和调用链 → 需要 PSI 语义。
4. **局限**：Lint 无法做**跨模块的数据流分析**，也无法替代单元测试和集成测试；它的价值是"编译期发现人类容易漏掉的问题"。

---

**读者问：怎么自定义一个 Lint 规则？举一个实际例子。**

**AI答：**

1. **四步**：
   - **Step 1**：确定要检测的问题类型 → 选择合适的 `Detector` 子类（`Detector` + `SourceCodeScanner`、`Detector` + `UastScanner`、`Detector` + `GradleScanner` 等）。
   - **Step 2**：实现检测逻辑，在 `visitXxx` 中用 `context.report()` 上报 `Incident`。
   - **Step 3**：定义 `Issue`（id、描述、分类、严重级别、实现类）和 `IssueRegistry`。
   - **Step 4**：打成 jar，在模块的 `lintPublish` 中发布，CI 中配置 `lint.jarPath` 或发布到 Maven 后通过 `lintPublishJars` 引入。
2. **实战例子（我的项目里用过的）**："**禁止在 `feature` 模块中直接依赖其他 `feature` 模块**"。
   - 检测方式：扫描 `build.gradle.kts` 的 UAST，找 `project(":feature:xxx")` 出现在 `implementation`/`api` 配置中。
   - 上报级别：`Severity.ERROR`，`Category.CORRECTNESS`。
   - 效果：把架构红线从"文档约定"变成"构建期断言"。
3. **更简单的例子**："禁止使用 `android.util.Log`，统一用 Timber"。
   - 实现：继承 `Detector`，`getApplicableUastTypes()` 返回 `UCallExpression`，检查 `resolve()` 后的方法所属类是否为 `android.util.Log`。
4. **写自定义规则的注意事项**：
   - **必须有测试**：用 `lint-tests` 的 `LintDetectorTest` + `lint()` 断言。
   - **报告位置要准**：`context.getLocation(node)` 指向具体表达式，用户才能一键跳转。
   - **别制造噪音**：误报率高的规则没人愿意开。可以先设 `Severity.WARNING` 观察一段时间。
   - **注意性能**：Lint 在每次 IDE 增量分析时都会跑，规则里不要做 IO。

```kotlin
// Lint 规则的单元测试
class LogUsageDetectorTest {
    @Test
    fun `detects android util log usage`() {
        lint().files(
            kotlin("""
                package test.pkg
                import android.util.Log
                fun f() { Log.d("TAG", "msg") }
            """).indented()
        ).run()
            .expect("Don't use android.util.Log, use Timber instead")
            .check(TestResult.Result.FAILURE)
    }
}
```

---

**读者问：静态检查应该用"全量阻断"还是"增量阻断"？存量问题怎么管？**

**AI答：**

1. **结论：增量阻断 + 基线只减不增**。这是唯一能在真实项目里长期活下来的策略。
2. **为什么不能全量阻断**：项目初期有 800 个告警，第一天全开 → 所有 PR 都红 → 团队开始用 `--continue`、改 CI 配置、或者本地跳过 → **工具被绕过，等于没有**。这是静态检查落地失败最常见的原因。
3. **落地路径（推荐）**：
   - **第 1 周**：全量跑一次，生成 baseline，提交入库。CI 只做"baseline 不增大"检查。
   - **第 2~4 周**：对**新增/变更文件**跑严格规则（基于 diff），存量文件仍用 baseline。
   - **第 2 个月**：核心目录（如 `feature/`）开启全量严格，legacy 目录保持 baseline。
   - **持续**：每次修复问题，从 baseline 里删掉对应条目，baseline 逐渐缩小。
4. **Baseline 的正确用法**：
   - baseline 文件**必须提交到 Git**（否则 CI 上每个人的 baseline 不同，问题被"藏"起来）。
   - baseline 应该**带注释分组**，说明每块遗留的是什么，便于后续认领。
   - 定期检查"baseline 里还有多少条"，这个数字就是技术债的量化指标，可以拿它讲故事、争取排期。
5. **配套机制**：
   - **告警配额**：每个迭代允许新增 N 条存量问题，超过则要还债。
   - **Code Owner**：`CODEOWNERS` 文件让每个目录有明确的规范责任人。
   - **PR 模板勾选**：作者自己声明"本次变更不涉及规范变更"或"涉及，已在 baseline 中"。

---

**读者问：Lint 误报太多怎么办？`@SuppressLint` 滥用有什么风险？**

**AI答：**

1. **误报处理优先级**：
   - **首选**：调整规则配置（`disable` / `severity` / `ignore` 特定路径），而不是逐行 suppress。
   - **其次**：检查是不是规则本身不适用于你的场景（比如 `UnusedResources` 对动态 `getIdentifier` 误报）。
   - **最后**：才是 `@SuppressLint`，且**必须最小范围**。
2. **抑制的风险**：
   - **抑制范围失控**：整个文件或整个类加 `@SuppressLint` → 把这个文件里所有同类问题都关了，包括未来新引入的。
   - **抑制项被遗忘**：代码改了，抑制项还在，真正的问题被永久隐藏。**Review 时要检查抑制项是否还有效**。
   - **团队习惯**：一旦发现"加个注解就不报了"，会有人无脑加。
3. **推荐实践**：
   - 抑制注解加**注释说明原因**：
     ```kotlin
     @SuppressLint("NotifyDataSetChanged")
     // 列表项数量固定（最多 3 项），无需 DiffUtil
     override fun notifyDataSetChanged() { ... }
     ```
   - 用 `lint-baseline.xml` 的**按路径忽略**代替大范围 suppress：
     ```xml
     <issue id="NotifyDataSetChanged" severity="Error">
         <ignore path="src/main/java/com/example/legacy/LegacyAdapter.kt" />
     </issue>
     ```
   - **CI 中监控抑制项数量**，防止无声增长。
4. **Lint 本身误报的常见来源**：
   - **反射调用**：Lint 看不到反射 → 误报"未使用"（`UnusedResources`）。
   - **KSP/APT 生成的代码**：Lint 分析不到生成代码 → 需要在 `lint.xml` 中排除生成目录。
   - **多模块资源合并**：跨模块引用时 Lint 上下文不全。

---

**读者问：detekt 和 Kotlin 官方的 K2 编译器、Compose 编译器插件怎么配合？**

**AI答：**

1. **K2（Kotlin 2.0 的新前端）** 对静态分析工具的影响：
   - detekt 从 1.23 开始支持 **K2 模式**（`-Pdetekt.useK2=true` 或在 `gradle.properties` 中配置），在 Kotlin 2.0+ 上建议开启。
   - K2 的 PSI（Kotlin Analysis API）更精确，尤其在**推断类型**、**智能转换**、**上下文接收者**上。
   - 迁移期要注意：老版本 detekt 在 Kotlin 2.0 上可能出现 `NoSuchMethodError`，需要同步升级。
2. **Compose 编译器插件**：
   - Compose 编译器从 1.5 起**作为 Kotlin 编译器插件**发布（`org.jetbrains.kotlin.plugin.compose`），不再需要独立的 `composeOptions.kotlinCompilerExtensionVersion`。
   - 这带来了 **Composable 函数的编译期检查**：调用 `@Composable` 函数必须处于 `@Composable` 上下文 → 编译期直接报错，这是编译器插件提供的"静态检查"，比 Lint 更强。
   - detekt 的 `ignoreAnnotated: ['Composable']` 是为了避免把 Compose 函数的小写命名误报为命名违规。
3. **KSP 对静态分析的影响**：
   - KSP（Kotlin Symbol Processing）替代 kapt 后，**生成的代码不进 Kotlin PSI**（KSP 是独立的编译器插件），所以 **detekt/Lint 都看不到 KSP 生成的代码**。
   - 后果：Room 生成的 DAO、Hilt 生成的组件在 detekt 里不参与分析（这其实是好事，规则不会误报生成代码）。
   - 但如果你想对生成代码做检查，需要在 detekt 配置中单独引入生成目录。
4. **实践建议**：
   - 规则集里对 `Composable` 函数放宽命名与复杂度限制（Compose 函数天然偏长）。
   - 开启 `allWarningsAsErrors` 前，先跑几个迭代收集"警告噪声"，把无意义的警告加进 `freeCompilerArgs` 的 `-Xsuppress-warning` 或逐个处理。

---

**读者问：Lint 在 CI 上跑一次要多久？会不会拖慢流水线？**

**AI答：**

1. **耗时构成**：
   - **首次全量**：大项目 3~10 分钟（需要完整分析所有源文件 + 资源 + 依赖）。
   - **增量**：CI 上是全量（无缓存），本地 IDE 里是增量（秒级）。
2. **加速手段**：
   - **缓存**：把 `~/.gradle/caches/modules-2`、Lint 的分析缓存目录用 `actions/cache` 缓存。
   - **并行**：Lint 本身支持多线程，Gradle 的 `org.gradle.parallel=true` 也能并行跑多个模块的 lint 任务。
   - **拆分**：CI 上分成两个并行 Job——一个跑"快速门禁"（只跑变更文件的严格检查），一个跑"全量扫描 + 报告"。快速门禁先返回结果给开发者，全量报告作为异步检查。
   - **`lintVitalRelease`**：只在打 Release 包时跑致命检查，日常 PR 不跑。
3. **并行流水线设计**：

```yaml
jobs:
  lint-fast:
    name: "Lint 快速门禁（变更文件）"
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: gradle/actions/setup-gradle@v3
      - run: ./gradlew :app:lintDebug --continue -PbaseBranch=origin/main
        env:
          ANDROID_HOME: ${{ steps.setup-android.outputs.ANDROID_HOME }}

  lint-full:
    name: "Lint 全量扫描"
    runs-on: ubuntu-latest
    timeout-minutes: 20
    needs: lint-fast   # 快速门禁失败就不浪费资源
    steps:
      - run: ./gradlew lintReport
      - uses: actions/upload-artifact@v4
        with:
          name: lint-report
          path: app/build/reports/lint-results-*.html
```

4. **实战数据参考**：中大型项目（300+ 模块、百万行）全量 Lint 在 GitHub Actions 上约 8~15 分钟，用缓存后可降到 5~8 分钟。**关键是把它放在并行 Job 里，不要让它挡住开发者**。

## 关联笔记

- [[编码规范]]：规范的分层落地与工具链选型
- [[代码审查]]：机器检查不到的部分由人工 Review 兜底
- [[../CI-CD/流水线设计]]：静态检查在流水线中的卡点位置
- [[../构建与打包优化/构建加速]]：Lint 任务耗时与 Gradle 性能优化
- [[../模块化与组件化/模块划分]]：自定义 Lint 规则守护模块依赖方向
- [[../../07-架构、工具与生态/构建与版本管理/Gradle构建系统]]：Gradle 任务与 Convention Plugin
- [[../../07-架构、工具与生态/测试/单元测试]]：静态检查与测试的职责边界
- [[../README]]：第 8 部分总览

## 参考资料

- Android Lint 官方文档：https://developer.android.com/studio/write/lint
- 自定义 Lint 检查规则：https://developer.android.com/studio/write/custom-lint-checks
- detekt 官方文档：https://detekt.dev/docs/intro
- ktlint 官方仓库：https://github.com/pinterest/ktlint
- Kotlin Coding Conventions：https://kotlinlang.org/docs/coding-conventions.html
