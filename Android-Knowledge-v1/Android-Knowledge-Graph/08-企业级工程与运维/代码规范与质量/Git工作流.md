---
tags: [android]
part: 第8部分
status: 待整理
difficulty: 高级
created: 2026-11-19
updated: 2026-11-19
related: "[[编码规范]], [[../README]]"
---

# Git 工作流

## 一句话定义

Git 工作流回答三个问题：**在哪里开发（分支模型）**、**怎么描述一次改动（提交规范）**、**怎么把改动并回主干（合并策略）**。它不是教 Git 命令，而是**用 Git 的机制去组织团队协作的风险**——核心目标是让主干（main）永远可发布，让任何一次变更都可以被追溯、审查和回滚。

面试开场：**"分支模型不是信仰问题，是风险控制问题。选择分支模型的唯一标准是：你的团队发版频率有多高、允许多久发布一次、以及出了问题你希望以多快的速度回滚。"**

## 核心原理

### 1. 三大分支模型对比

```
┌───────────────────────────────────────────────────────────────────────┐
│ Git Flow（Vincent Driessen, 2010）                                    │
│                                                                       │
│   feature/ ──┐                                                       │
│   release/ ──┼──▶ develop ──▶ feature ──▶ develop ──▶ release ──▶ main│
│   hotfix/  ──┘         ▲                          │         ▲        │
│                         └────────────┬─────────────┘         │        │
│                                      │                       │        │
│                          release 合并回 develop          tag v1.0     │
│                                                                       │
│  特点：分支多（5 种），规范严格，适合有明确"发布窗口"的团队            │
│  代价：合并频繁、冲突多、新人上手难、"develop 长期偏离 main"          │
└───────────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────────┐
│ GitHub Flow（GitHub 官方推荐）                                        │
│                                                                       │
│   feature/ ──▶ main ──▶ deploy ──▶ 观察 ──▶ 下一轮                    │
│                                                                       │
│  特点：只有 main + feature，主干随时可发布                              │
│  适合：Web 应用、每天多次发布、部署自动化                              │
│  局限：Android 有应用商店审核，发布节奏受外部制约                     │
└───────────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────────┐
│ Trunk Based Development（持续交付，Humble & Farley）                  │
│                                                                       │
│        short-lived branch                            │
│      ┌──────┐  ┌──────┐  ┌──────┐  ┌──────┐                         │
│  ────┤ fix1 ├─▶┤ feat ├─▶┤ fix2 ├─▶┤ feat ├─▶──▶ main (持续集成)      │
│      └──────┘  └──────┘  └──────┘  └──────┘   ↑                     │
│                                                 └─────┘               │
│                                          Feature Flag 控制发布时机     │
│                                                                       │
│  特点：分支寿命短（< 1 天）、主干始终可发布、靠测试与 Flag 兜底        │
│  适合：大型团队、测试覆盖率高、有 Feature Flag 体系                    │
│  前提：必须有完善的自动化测试 + Feature Flag + 完善的回滚能力          │
└───────────────────────────────────────────────────────────────────────┘
```

| 维度 | Git Flow | GitHub Flow | Trunk Based |
| --- | --- | --- | --- |
| 分支数量 | 5 种类型 | 2 种 | 1~2 种 |
| 主干可发布性 | develop 可发布，main 只在发布时更新 | 始终可发布 | 始终可发布 |
| 发布节奏 | 周期性（双周/月） | 随时 | 随时 |
| 合并冲突 | 多（develop 长期分叉） | 少 | 最少 |
| 新人上手成本 | 高 | 低 | 中 |
| 适合的团队规模 | 中小、发布节奏固定 | 中小、频繁发布 | 大型、测试与基建成熟 |
| 风险控制 | 分支隔离强 | 中 | 靠自动化测试 + Flag |

### 2. Android 场景的特殊性

Android 无法像 Web 那样"部署即上线"，这改变了几件事：

1. **发布周期被应用商店审核拉长**（Google Play 通常数小时到数天，国内商店更久）。
2. **版本有兼容性约束**：用户不会立刻升级，`targetSdk` 适配需要多版本并存（详见 [[../../07-架构、工具与生态/构建与版本管理/AGP与版本适配]]）。
3. **热修复是唯一的"小步快跑"通道**：普通 Android 团队常用 "**主干开发 + 周期性发版 + 热修复应急**" 的组合，本质上是 Trunk Based + GitHub Flow 的变体。
4. **内测渠道（Firebase App Distribution / 企业分发）可以绕过审核**，是很多团队实际的发版通道：
   ```
   main ──▶ 自动构建 ──▶ 内测渠道（当日多次）
                │
                └──▶ 商店审核（每周/每双周）
   ```
5. **版本号规范**需要严格管理（见 [[../CI-CD/流水线设计]]）。

### 3. Conventional Commits 规范

```
<type>(<scope>): <subject>

[optional body]

[optional footer]
```

**type 的语义划分**（决定这个提交是否影响用户/是否允许进入 release 分支）：

| type | 语义 | 例子 | 是否影响发版 |
| --- | --- | --- | --- |
| `feat` | 新功能 | `feat(checkout): 支持优惠券叠加结算` | 是 |
| `fix` | 缺陷修复 | `fix(login): 修复 token 刷新后未跳转的问题` | 是 |
| `docs` | 文档 | `docs(readme): 补充本地运行步骤` | 否 |
| `style` | 格式（不影响逻辑） | `style: 统一 import 顺序` | 否 |
| `refactor` | 重构（既不修 bug 也不加功能） | `refactor(cache): 抽象出 Cache 接口` | 否 |
| `perf` | 性能优化 | `perf(list): 列表改用 DiffUtil` | 是 |
| `test` | 测试 | `test(repo): 补充 UserRepository 单测` | 否 |
| `build` | 构建系统/依赖 | `build(gradle): 升级 AGP 到 8.5` | 否 |
| `ci` | CI 配置 | `ci: 增加 lint 快速门禁 Job` | 否 |
| `chore` | 杂项 | `chore: 升级依赖版本` | 否 |
| `revert` | 回滚 | `revert: feat(checkout): 支持优惠券叠加结算` | 视情况 |

**scope** 用模块名：`feat(login)`, `fix(network)`, `refactor(core-database)`。

**Subject 规则**：
- 使用祈使句（命令式）："增加"而不是"增加了"。
- 结尾不加句号。
- 中文团队可以用中文，但保持一致。
- 长度 ≤ 72 字符（`git log` 单行显示上限）。

**Body** 说明"为什么"和"怎么做"，尤其在有 breaking change 时。

**Footer** 用于关联 Issue 与标记破坏性变更：

```text
feat(api): 用户信息接口返回用户等级字段

服务端在 v2.3.0 起新增 level 字段，客户端需要根据等级展示不同的会员标识。
为兼容旧服务端，level 为空时按普通用户处理。

BREAKING CHANGE: User.level 由 Int 改为 UserLevel 枚举，调用方需相应调整。
Refs: #2341
```

**为什么重要**：Commit message 是**唯一的、永久的、离线的变更上下文**。半年后 `git blame` 出来的这行代码，commit message 是唯一的解释来源。类型化前缀还让 `git log --grep`、自动生成 CHANGELOG、语义化版本号计算都变得可自动化。

### 4. Merge vs Rebase vs Squash

```
场景：feature 分支有 3 个提交，要并入 main

【Merge】保留分叉，产生一个 merge commit
    main ──────────────────────────────▶
              \                       /
               A──B──C──M (merge)  ◀── 保留了完整拓扑
    特点：历史真实反映"分支了、合并了"，但 main 上有 merge 节点

【Rebase】把提交"搬"到 main 最新点，重放一遍
    main ──A'──B'──C'─────────────────▶   （新的 SHA！）
    特点：历史线性，丢失"这是 feature 分支"的语义
    注意：已推送的提交 rebase 后 SHA 变了，会让别人拉取困难

【Squash】把所有提交压成一个
    main ──S──────────────────────────▶
    特点：历史干净，但丢失中间的提交历史
```

| 方式 | 历史线性 | 保留分支语义 | 保留中间提交 | 适用场景 |
| --- | --- | --- | --- | --- |
| Merge | ✘ | ✔ | ✔ | 多人协作的 feature 分支、release 分支 |
| Rebase | ✔ | ✘ | ✔ | 个人分支同步主干、合并前整理自己的提交 |
| Squash | ✔ | ✘ | ✘ | PR 合并（把一个 PR 的多个碎提交压成一个） |

**最佳实践组合**：

```
feature 分支开发中
    → 定期 git fetch origin && git rebase origin/main   （保持线性、减少冲突）

PR 合并时（GitHub 界面选 "Squash and merge"）
    → 压成一个提交，commit message 用 PR 标题 + 描述

release/hotfix 分支合并时
    → 用 Merge（--no-ff），保留"这是一个 release"的语义
```

> **黄金规则**：**已经推送给别人使用的分支，永远不要 rebase**。因为 rebase 会改写历史，导致其他人的本地分支与远端分叉。

### 5. 冲突解决

```bash
# 1. 查看冲突文件
git status
# Unmerged paths:
#   both modified:   app/src/main/java/.../UserRepository.kt

# 2. 打开冲突文件，会看到冲突标记
git checkout --conflict=diff3 app/src/main/java/.../UserRepository.kt
```

```kotlin
<<<<<<< HEAD                      // 当前分支（你所在的分支）的版本
val timeout = 30
=======                           // 分隔线
val timeout = 60                  // 另一分支的版本
>>>>>>> feature/network-timeout   // 对方分支的名称
```

**`diff3` 冲突样式**会额外显示冲突前的公共祖先版本，这是解决冲突的关键：

```kotlin
<<<<<<< HEAD
val timeout = 30
|||||||  merged common ancestors
val timeout = 15                  // 共同祖先的版本，一眼看出双方各自改了什么
=======
val timeout = 60
>>>>>>> feature/network-timeout
```

```bash
# 3. 解决后标记为已解决
git add app/src/main/java/.../UserRepository.kt

# 4. 继续 rebase / merge
git rebase --continue
# 或
git merge --continue

# 5. 中途想放弃
git rebase --abort
git merge --abort
```

**实用技巧**：

```bash
# 只对某个文件用 merge-file 语义三方合并
git merge-file --diff3 ours.kt base.kt theirs.kt

# 交互式 rebase：合并碎提交、修改提交信息、丢弃提交
git rebase -i origin/main
# 编辑器中：
#   pick abc1234 添加登录页
#   pick def5678 修复空指针
#   squash 789abcd0 调整样式      ← 压进上一个提交
#   reword 1112222 更新提示文案    ← 修改提交信息
#   drop 3334444 临时调试代码      ← 丢弃

# 查找哪次提交引入了 bug（二分定位）
git bisect start
git bisect bad                 # 当前 HEAD 有 bug
git bisect good v1.2.0         # 这个版本是好的
# git 会自动在中间提交间切换，测试后标记 good/bad
git bisect reset

# 查看某个文件的历史与逐行归属
git log --follow -p path/to/File.kt
git blame -L 100,120 path/to/File.kt
```

**冲突解决的原则**：
1. **理解双方意图再合并**，不要机械地选一边。冲突通常说明**双方对同一处做了不同假设**——这才是真正需要关注的点。
2. **冲突是设计信号**：同一个文件被频繁冲突，说明模块划分有问题（见 [[../模块化与组件化/模块划分]]）。频繁冲突的目录应当被拆分。
3. **定期同步主干**：分支存活越久，冲突越大。养成每天 `rebase origin/main` 的习惯。
4. **使用 `git rerere`**：记录已解决的冲突，重放时自动应用。

```bash
# 开启 rerere（重用已记录的冲突解决方案）
git config --global rerere.enabled true
```

### 6. Git Hooks 与提交前检查

Git Hooks 是**把规范强制落到提交那一刻**的机制。Android 项目最常用的是 `pre-commit`（跑格式化与快速检查）与 `commit-msg`（校验提交信息格式）。

```bash
# .git/hooks/pre-commit
#!/bin/sh
# 由 husky 管理的钩子会调用 .husky/pre-commit
exec husky
```

```bash
# .husky/pre-commit
#!/usr/bin/env sh
. "$(dirname -- "$0")/_/husky.sh"

echo "🔍 检查中..."

# 1. 只检查本次暂存的 Kotlin 文件（快）
STAGED_FILES=$(git diff --cached --name-only --diff-filter=ACMR | grep '\.kts\?$')
if [ -n "$STAGED_FILES" ]; then
  # ktlint 快速检查暂存文件
  echo "$STAGED_FILES" | xargs ./gradlew ktlintCheck --quiet 2>/dev/null || {
    echo "❌ ktlint 检查失败，请执行 ./gradlew spotlessApply 后重试"
    exit 1
  }
fi

# 2. 校验提交信息格式（Conventional Commits）
BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ "$BRANCH" != "main" ] && [ "$BRANCH" != "develop" ]; then
  COMMIT_MSG=$(git log -1 --pretty=%B)
  PATTERN='^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\(.+\))?!?: .{1,72}$'
  if ! echo "$COMMIT_MSG" | head -1 | grep -qE "$PATTERN"; then
    echo "❌ 提交信息不符合 Conventional Commits 规范"
    echo "   格式: <type>(<scope>): <subject>"
    echo "   类型: feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert"
    exit 1
  fi
fi

echo "✅ 检查通过"
```

```bash
# .husky/commit-msg
#!/usr/bin/env sh
. "$(dirname -- "$0")/_/husky.sh"

# 校验提交信息长度（首行 ≤ 72 字符）
FIRST_LINE=$(head -1 "$1")
if [ ${#FIRST_LINE} -gt 72 ]; then
  echo "❌ 提交信息首行超过 72 字符（当前 ${#FIRST_LINE}）"
  exit 1
fi

# 禁止 WIP 提交进入主干（可在 pre-push hook 检查）
if echo "$FIRST_LINE" | grep -qiE '^(wip|fixup!|squash!)'; then
  echo "❌ 检测到 WIP 提交，请在合并前使用 git rebase -i 整理"
  exit 1
fi
```

```json
// package.json
{
  "devDependencies": {
    "husky": "^9.1.0",
    "lint-staged": "^15.2.10"
  },
  "lint-staged": {
    "*.{kt,kts}": [
      "ktlint --format",
      "git add"
    ],
    "*.{js,ts,tsx}": [
      "eslint --fix",
      "git add"
    ]
  },
  "scripts": {
    "prepare": "husky"
  }
}
```

**Hook 设计的注意事项**：

| 原则 | 说明 |
| --- | --- |
| **快** | pre-commit 必须在 5 秒内完成，否则开发者会 `--no-verify` 绕过 |
| **只查暂存文件** | 全量检查放到 CI，pre-commit 只做增量 |
| **不要用重量级工具** | pre-commit 不跑完整编译、detekt 全量（太慢） |
| **可绕过但有代价** | 紧急情况允许 `--no-verify`，但 CI 上会再拦一次 |
| **失败信息要可操作** | 告诉开发者"怎么修"，不是只说"失败" |

### 7. 事故处理：误回滚与历史抢救

```bash
# 场景 1：reset --hard 后想找回
# 关键：reflog 记录了 HEAD 的所有移动，包括已被 reset 掉的提交
git reflog
# 输出示例：
# a1b2c3d HEAD@{0}: reset: moving to HEAD~1
# e4f5g6h HEAD@{1}: commit: feat: 重要功能
# i7j8k9l HEAD@{2}: commit: 另一个提交

# 恢复 HEAD@{1}（不改动当前分支指针，只新建分支）
git branch recover a1b2c3d
# 或者直接把当前分支重置回去
git reset --hard e4f5g6h
```

```bash
# 场景 2：撤销一个已经推送的提交（团队协作安全）
# 使用 revert 生成反向提交，不改写历史
git revert <commit-sha>

# 场景 3：撤销一个 merge commit
git revert -m 1 <merge-commit-sha>
# -m 1 表示保留主线（第一个父提交），生成的反向提交会撤销被合入分支的改动

# 场景 4：找回误删的分支
git branch deleted-branch <reflog-sha>

# 场景 5：恢复误删的文件（未提交时）
git restore path/to/file
# 或从 stash 中恢复
git stash list
git stash pop

# 场景 6：代码已经 commit 但要撤销且不影响别人
git reset --soft HEAD~1     # 撤销提交但保留改动在暂存区
git reset --mixed HEAD~1   # 撤销提交且改动回到工作区（默认）
git reset --hard HEAD~1    # 撤销提交且丢弃改动（危险！）
```

**恢复策略对照表**：

| 命令 | 改写历史 | 保留改动 | 保留提交 | 适用场景 |
| --- | --- | --- | --- | --- |
| `git reset --soft` | ✔ | ✔（暂存区） | ✘（变成未提交） | 想修改提交信息或重新拆分 |
| `git reset --mixed` | ✔ | ✔（工作区） | ✘ | 想撤销提交但保留代码 |
| `git reset --hard` | ✔ | ✘ | ✘ | **危险**，只用于确定要丢弃的场景 |
| `git revert` | ✘ | ✘（反向补丁） | ✔（新增反向提交） | **已推送**的提交要撤销 |
| `git restore` | ✘ | ✔ | ✔ | 只想恢复某个文件 |

**铁律**：

1. **`reset --hard` 只在自己的本地未推送分支上用**。
2. **已推送到共享分支的提交，只能用 `revert` 撤销**，不能 rebase/reset（否则所有人的历史都会分叉）。
3. **`reflog` 是救命稻草**，默认保留 90 天。团队应知道 `git reflog` 这个命令。
4. **重要操作前先备份**：`git branch backup-$(date +%s)`，成本为零。
5. **Android 项目特有的救命通道**：如果代码已经发版且线上出问题，最快的止血手段是**远端配置关开关**（见 [[../日志与监控/远端配置]]）或**热修复**（见 [[../热修复与动态化]]），而不是发新版本。

### 8. 分支保护与常用配置

```bash
# 保护 main 分支（GitHub/GitLab UI 或 API 配置）
# - 禁止直接 push
# - 必须通过 PR
# - 需要至少 1 个 Review 批准
# - 必须通过 CI 检查
# - 禁止 force push
# - 要求线性历史（禁止 merge commit）

# 常用的全局配置
git config --global user.name "Zhang San"
git config --global user.email "zhangsan@example.com"

# 提交时自动修正换行符（跨平台协作必备）
git config --global core.autocrlf input      # Windows/macOS
git config --global core.eol lf

# 中文文件名与路径正常显示
git config --global core.quotepath false

# 增强 diff 显示
git config --global diff.algorithm histogram

# 合并策略：三方合并，冲突时尝试自动合并
git config --global merge.conflictstyle zdiff3

# 别名
git config --global alias.st "status -sb"
git config --global alias.lg "log --oneline --graph --decorate --all"
git config --global alias.last "log -1 HEAD --stat"
git config --global alias.unstage "reset HEAD --"

# 拉取时 rebase 而不是 merge（线性历史）
git config --global pull.rebase true
git config --global rebase.autoSquash true    # 自动识别 fixup!/squash!
git config --global rebase.autoStash true
```

**`.gitignore`（Android 项目标准版）**：

```gitignore
# Gradle
.gradle/
build/
local.properties

# Android Studio / IntelliJ
.idea/
*.iml
captures/
.externalNativeBuild/
.cxx/

# 签名文件（绝对不能提交）
*.jks
*.keystore
keystore.properties
signing.properties

# 密钥与配置
local.defaults.properties
google-services.json    # 视团队策略
secrets.properties

# 日志与临时文件
*.log
*.hprof

# OS
.DS_Store
Thumbs.db
```

> **重要**：`.gitignore` 只能防止**未跟踪文件**被提交。已经被跟踪的敏感文件（如误提交的 `keystore.properties`），需要 `git rm --cached <file>` 并**轮换密钥**——从 Git 历史中彻底清除需要 `git filter-repo` 或 BFG，且所有协作者必须重新 clone。

## 代码示例

### 1. 一个团队推荐的完整工作流脚本

```bash
#!/usr/bin/env bash
# scripts/git-start-task.sh
# 开始一个新任务：切分支、同步主干、清理本地分支
set -euo pipefail

TASK_ID="${1:?用法: ./scripts/git-start-task.sh PROJ-1234}"
TASK_DESC="${2:-feature}"

MAIN_BRANCH="origin/main"
CURRENT=$(git branch --show-current)

if [ "$CURRENT" = "main" ]; then
  echo "❌ 不要在 main 分支上开发，请先切出功能分支"
  exit 1
fi

# 1. 同步主干
git fetch origin --prune
git rebase "$MAIN_BRANCH"

# 2. 创建新分支
BRANCH="${TASK_DESC}/${TASK_ID}-$(git config user.name | tr ' ' '-')"
git checkout -b "$BRANCH"
echo "✅ 已创建分支: $BRANCH"

# 3. 清理已合并的本地分支
git branch --merged "$MAIN_BRANCH" | grep -vE '(^\*|main|develop)' | xargs -r git branch -d
```

### 2. CI 中自动生成 CHANGELOG

```bash
# scripts/generate-changelog.sh
# 依据 Conventional Commits 生成 CHANGELOG.md
set -euo pipefail

BASE_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "v0.0.0")
CURRENT_TAG=$1

{
  echo "## [${CURRENT_TAG}] - $(date +%Y-%m-%d)"
  echo ""

  # 破坏性变更
  BREAKING=$(git log "${BASE_TAG}..HEAD" --pretty=format:"%s %b" | grep -iE "BREAKING CHANGE" || true)
  if [ -n "$BREAKING" ]; then
    echo "### ⚠️ Breaking Changes"
    echo "$BREAKING" | sed 's/^/- /'
    echo ""
  fi

  # 新功能
  echo "### ✨ Features"
  git log "${BASE_TAG}..HEAD" --pretty=format:"%s" | grep -E "^feat(\(.+\))?:" | sed 's/^/- /' || echo "- 无"
  echo ""

  # 修复
  echo "### 🐛 Bug Fixes"
  git log "${BASE_TAG}..HEAD" --pretty=format:"%s" | grep -E "^fix(\(.+\))?:" | sed 's/^/- /' || echo "- 无"
  echo ""

  # 其他
  echo "### 🔧 Others"
  git log "${BASE_TAG}..HEAD" --pretty=format:"%s" \
    | grep -vE "^(feat|fix|BREAKING)" | sed 's/^/- /' | head -50 || echo "- 无"
} > CHANGELOG.md

echo "✅ CHANGELOG.md 已生成（对比 ${BASE_TAG} → ${CURRENT_TAG}）"
```

### 3. 追踪特定文件的变更历史

```bash
# 某个文件的完整演进史
git log --follow --stat -- path/to/ImportantModule.kt

# 谁在什么时候改了这一段代码（逐行）
git blame -w -C -C --line-porcelain path/to/ImportantModule.kt | \
  grep -E '^(author |author-time |summary )' | head -40

# 找出一个方法被删除的时间点（考古）
git log -S "fun legacyMethod()" --oneline --all

# 二分定位引入 bug 的提交
git bisect start HEAD~50
git bisect good v2.1.0
# 自动在中点 checkout，手动测试后：
git bisect good    # 或 git bisect bad
git bisect reset

# 查看某个文件在某个 tag 时的内容
git show v2.1.0:app/src/main/java/.../ImportantModule.kt
```

### 4. 多模块仓库的稀疏检出

```bash
# 大型多模块仓库：只检出需要的模块（加速 CI 与本地）
git sparse-checkout init --cone
git sparse-checkout set app core/network feature/login
git sparse-checkout list

# 关闭
git sparse-checkout disable
```

## 常见问题 / 面试要点

**读者问：团队应该用哪种分支模型？怎么选？**

**AI答：**

1. **选型决策树**：

```
发布频率如何？
├─ 每天多次发布（Web / 内部工具）
│   └─ Trunk Based + Feature Flag（若无高自动化）或 GitHub Flow
├─ 每周 / 双周发布（典型 App）
│   └─ GitHub Flow 变体：main + 短期 feature 分支 + 自动发版
├─ 每月 / 每季度发布（企业级、强合规）
│   └─ Git Flow（release 分支隔离 + 充分回归）
└─ 特殊：多版本长期并行（如同时维护 v1/v2 客户端）
   └─ Git Flow + 多 release 分支
```

2. **Android 的特殊约束**（必须提）：**Android 无法"部署即上线"**，应用商店审核让发布成为**周期性事件**而非连续事件。因此：
   - 不要照搬 Web 的 Trunk Based 然后抱怨"改完不能上线"。
   - Android 更现实的做法是 **"主干开发 + 周期性发版 + 内测渠道快速验证 + 热修复应急"**。
   - 如果业务是游戏/工具类高频迭代，也可以用 **Play Feature Delivery**（动态交付模块）来获得类 Web 的体验。
3. **不推荐 Git Flow 的理由**（面试加分项）：
   - `develop` 分支长期存在，导致主干（main）与真实线上版本的差异随时间扩大，"main 是可发布的"这个假设失效。
   - 合并冲突多、Review 难（一次 release 合并涉及几十个 feature）。
   - 新人上手成本高，分支多到没人记得该从哪切。
   - **正确的做法**：如果是周期性发布，用 **GitHub Flow + release 分支（只在发布前创建）** 的混合模型。
4. **我的推荐（面试答案）**：
   > "对于 Android 团队，我推荐 **GitHub Flow 的简化版**：主干开发（Trunk Based）+ 短期 feature 分支（存活 < 3 天）+ PR 评审 + 自动构建 + 内测渠道 + 周期性商店发版 + Feature Flag 做开关 + 远端配置做参数调整。
   > 关键前提是团队必须有两样东西：**自动化的 CI** 和 **Feature Flag / 远端配置体系**。没有这两样，Trunk Based 的风险是无法兜底的。"

---

**读者问：rebase 和 merge 的取舍是什么？什么时候绝对不能用 rebase？**

**AI答：**

1. **本质区别**：
   - **merge**：**保留分叉**，两条历史线汇合。历史真实反映协作过程，但主干上会有大量 merge 节点，log 变噪。
   - **rebase**：**重写历史**，把你的提交"摘下来"接到别处，生成新 SHA。历史线性，但抹掉了"曾经分叉"的事实。
2. **取舍原则**：
   - **自己的、未推送的分支** → rebase（保持线性，减少后续合并冲突）。
   - **已推送到共享仓库的分支** → merge（不能改写别人的历史）。
   - **PR 合并时** → squash（把碎提交压成一个，主干干净）。
   - **release / hotfix 合并** → merge --no-ff（保留语义标记）。
3. **绝对不能 rebase 的场景**：
   - **已经推送且别人可能基于它工作的分支**。rebase 会改写 SHA，别人的本地分支立刻与远端分叉，必须重新 reset/merge。
   - **公共仓库（如开源项目）的 PR 分支**。上游维护者可能已基于你的分支做 cherry-pick。
   - **在多人共用的 `develop` / `main` 上操作**。
4. **rebase 的隐藏成本**：
   - **冲突要一个个解**（不像 merge 一次解完）。
   - **中途放弃要 `git rebase --abort`**。
   - **commit 的 SHA 全部变了**，导致之前的 PR 链接、CI 链接、评论定位全部失效。
5. **实用建议**：
   - rebase 前先 `git fetch && git rebase origin/main`（**基于最新的 origin 而不是本地 main**）。
   - 用 `git rebase --onto` 处理"分支落后太多"的场景。
   - 记住 `git reflog`，rebase 搞砸了可以回到 rebase 前的状态。

---

**读者问：提交信息规范为什么重要？团队不遵守怎么办？**

**AI答：**

1. **为什么重要**（四个层次）：
   - **代码考古**：半年后 `git blame` 出来的代码，commit message 是唯一解释。`fix: 修复 bug` 毫无价值，`fix(login): 修复 token 刷新后未跳转的问题，因为 onTokenExpired 未清除缓存的 userInfo` 才有价值。
   - **自动化**：类型化前缀让 CHANGELOG 生成、语义化版本号计算、`git log --grep` 检索成为可能。
   - **筛选**：支持 `git log --grep="^fix"` 快速找出所有 bug 修复，`git log --grep="^feat"` 找出所有新功能——这对生成"版本更新说明"和"回归测试范围"极其有用。
   - **可追溯**：Issue 号通过 `Refs: #1234` 关联，形成"需求 → 提交 → 发布"的完整链路。
2. **团队不遵守的解决路径**：
   - **不要靠自觉**——靠工具强制：
     - `commit-msg` hook（本地即时反馈）。
     - CI 中的 commit lint（GitHub 有 `commitlint` action，GitLab 有 `Commit Message` 模板）。
     - **服务端 hook**（`pre-receive`）阻止不合规提交进入仓库——这是最硬的约束。
   - **降低执行成本**：提供 `git commit -m` 的模板（`git config commit.template .gitmessage`），或者用 Commitizen 交互式选择 type。
3. **推行策略**：
   - **不要一次性强制全量**：先在**新功能分支**上试行，老仓库渐进迁移。
   - **允许例外但要有记录**：`revert:` 和 `Merge branch` 这类自动生成的提交信息不受检。
   - **在 PR 模板里加一栏**"提交信息是否符合规范"，让 Reviewer 有据可依。
4. **注意事项**：
   - 规范要**简明**：类型不要超过 12 个，否则没人记得住。
   - **支持中文**：团队是中文沟通就统一用中文，别搞两套标准。
   - **不要在 commit message 里写"为什么"以外的废话**（如"修改了代码"）。

---

**读者问：误操作 `reset --hard` 之后怎么恢复？线上已经发布的版本出问题了怎么办？**

**AI答：**

1. **`reset --hard` 恢复**：
   - **第一反应**：`git reflog`，它记录了 HEAD 的每一次移动（包括被 reset 掉的提交），默认保留 90 天。
   - 找到目标 SHA 后：`git reset --hard <sha>` 或更安全的 `git branch recover <sha>`（只新建分支，不动当前分支指针）。
   - **如果 reflog 也没了**：只能靠 IDE 的本地历史（IDEA 的 Local History）、Jenkins/远程仓库的其他分支、以及 `git fsck --lost-found` 找回悬空对象。
   - **预防**：`git branch backup-$(date +%s)`，或开启 `git config --global alias.save '!git branch backup-$(date +%s)'`。
2. **已推送的提交要撤销**：
   - **只能用 `git revert`**（生成反向提交，不改写历史）。团队协作分支上永远不要用 reset 改写历史。
   - 撤销 merge commit：`git revert -m 1 <merge-sha>`。
3. **线上已发布版本出问题的止血顺序**（Android 场景，按优先级）：
   - **① 远端配置关开关**：秒级生效，无需发版。前提是出问题的功能被 Feature Flag 包裹（见 [[../日志与监控/远端配置]]）。**这是最应该被建设的能力。**
   - **② 热修复**：小时级，见 [[../热修复与动态化]]。
   - **③ 加急发版**：天级，且需过商店审核（国内商店可能更久）。
   - **④ 强制下架**：兜底手段，代价最大。
4. **根本解法（架构层）**：
   - **任何可能出问题的功能都要有开关**（Feature Flag），且开关默认状态在发布前评审。
   - **分级发布**：内部测试 → 灰度 1% → 灰度 10% → 全量，每级观察期至少一天。
   - **监控先行**：没有 Crash 率监控的线上问题，等于没有刹车（见 [[../灰度发布与监控]]）。
5. **面试话术**："我的原则是：**未推送用 reset，已推送用 revert，线上问题先看远端配置能不能关**。这三句话能覆盖 95% 的事故场景。"

---

**读者问：Git Hooks 会不会拖慢提交？团队总是 `--no-verify` 怎么办？**

**AI答：**

1. **Hook 必须快**：**5 秒原则**。超过 5 秒，开发者会养成 `--no-verify` 的肌肉记忆。
   - `pre-commit` **只检查暂存文件**（`git diff --cached --name-only`），不要跑全量。
   - 不要在 pre-commit 里跑完整编译、detekt 全量、跑单测——这些放 CI。
   - 重量级检查（lint 全量、依赖漏洞扫描）放 **pre-push** 或 CI。
2. **分层设计**：

   | Hook | 时机 | 内容 | 耗时目标 |
   | --- | --- | --- | --- |
   | `pre-commit` | 提交前 | 格式化（spotless/ktlint）、commit-msg 校验 | < 3s |
   | `pre-push` | 推送前 | detekt 增量检查、依赖漏洞扫描 | < 15s |
   | CI | 推送后 | 全量 lint、单测、构建、集成测试 | 分钟级 |

3. **团队绕过 `--no-verify` 的应对**：
   - **不要靠封堵**：无法在技术上彻底禁止（本地 hook 随时可删）。**靠服务端补位**——GitHub 的 Required Status Checks 会在服务端拦住不合规的提交，绕过本地 hook 依然过不了 CI。
   - **让检查有价值**：如果 hook 总是在报误报，开发者一定会绕过。**误报率是决定 hook 生命周期的关键指标**。
   - **给出明确的修复指引**：`❌ ktlint 检查失败，请执行 ./gradlew spotlessApply 后重试`——比"格式错误"有用得多。
4. **husky 的价值**：管理 `.husky/` 目录、跨机器一致、可以在 `core.hooksPath` 中配置一次全局生效。
5. **注意 Windows 环境**：hook 脚本用 `#!/usr/bin/env sh`（husky 自带 Git Bash 环境），路径处理注意 `dirname -- "$0"`。

---

**读者问：什么是 `git bisect`？怎么用它快速定位一个线上 bug？**

**AI答：**

1. **原理**：`git bisect` 用**二分查找**在提交历史中定位引入 bug 的提交。给定一个"好"版本和一个"坏"版本，它每次把范围缩小一半，`log2(N)` 次就能定位。
2. **使用步骤**：
   ```bash
   git bisect start                 # 开始
   git bisect bad                   # 当前 HEAD 有 bug
   git bisect good v2.1.0           # 这个 tag 是好的
   # Git 会自动 checkout 到中间某个提交
   # → 你手动测试该版本（或跑自动化测试脚本）
   git bisect good    # 或 git bisect bad
   # 重复直到定位到具体提交
   git bisect reset                  # 结束，回到原分支
   ```
3. **自动化版本**（最实用的形态）：
   ```bash
   # 用一个能自动判定的脚本代替人工测试
   git bisect start HEAD~100 v2.1.0
   git bisect run ./scripts/check-crash.sh
   # 脚本返回 0 = good（无 bug），1~127 = bad，125 = skip（无法测试）
   ```
   `check-crash.sh` 可以：安装 APK、启动、用 adb 检查 logcat 是否有特定异常、退出码作为判定结果。
4. **自动化测试版的 bisect**：
   ```bash
   git bisect start
   git bisect bad HEAD
   git bisect good v2.0.0
   git bisect run ./gradlew :app:testStabilityTest
   ```
5. **Android 场景的注意点**：
   - **每个提交都必须能构建且可测**——这要求提交粒度小且自洽（"每个 commit 都能编译"），这也是 [[../代码规范与质量/代码审查]] 里要求"拆分 PR"的根本原因。
   - **二分会构建很多次**，慢。用 `git bisect run` 自动化，或先用日志/堆栈做初筛缩小范围。
   - **崩溃日志（stacktrace）本身就能缩小范围**：按堆栈中的类名 `git log -S "ClassName"` 先找到候选提交区间，再 bisect。
6. **面试话术**："`git bisect` 的前提是**每个提交都是可构建、可验证的**。这就把'提交粒度'和'可调试性'联系起来了——所以我们要求每个 commit 都能编译通过。"

---

**读者问：多模块/多仓库（monorepo vs polyrepo）怎么选？**

**AI答：**

1. **Monorepo（单仓多模块）**：
   - **优点**：一次提交跨模块、跨模块重构不用开多个 PR、依赖版本统一、CI 可共享缓存、代码搜索方便。
   - **缺点**：仓库体积大（clone 慢）、CI 需要"变更范围检测"（受影响模块图）来避免全量构建、权限难以细分、任何人能改任何模块。
   - **Android 场景**：**主流选择**。多模块 Gradle 项目 + `includeBuild` 处理插件依赖。
2. **Polyrepo（多仓）**：
   - **优点**：权限隔离、团队自治、每个库独立发版（适合对外 SDK）。
   - **缺点**：跨仓同步版本地狱（改接口要发多个仓的 PR）、依赖发布协调成本高。
   - **Android 场景**：**对外 SDK 的必要形态**（独立仓库 + Maven 发布，见 [[../CI-CD/流水线设计]]）。
3. **Android 的常见混合形态**：
   ```
   repo: myapp-android (monorepo)
     ├── app/
     ├── feature/*/
     ├── core/*/
     └── build-logic/

   repo: myapp-sdk (独立仓库)
     └── 发布到 Maven：com.example:sdk:1.2.0
   ```
4. **Monorepo 的 CI 关键工程问题**：**受影响范围检测（affected scope）**。
   ```bash
   # scripts/affected-modules.sh
   # 找出受本次变更影响的模块（正向依赖图）
   BASE=origin/main
   CHANGED=$(git diff --name-only "$BASE...HEAD" -- '*/build.gradle.kts' | sed 's|/build.gradle.kts||')
   # 找出依赖了这些模块的所有模块
   AFFECTED=""
   for m in $CHANGED; do
     DEP=$(grep -rl "project(\":$m\")\|projects.$m" --include="build.gradle.kts" . | sed 's|/build.gradle.kts||')
     AFFECTED="$AFFECTED $DEP"
   done
   echo "$AFFECTED" | tr ' ' '\n' | sort -u | grep -v '^$'
   ```
   然后只对这些模块及其依赖方跑构建和测试，**把 CI 时间从 20 分钟降到 5 分钟**。
5. **Google 的 monorepo 经验**（常被面试引用）：Google 内部有超过 1000 万个文件的 monorepo，靠 **Blaze/Chromium 构建系统 + 依赖图 + 自动化重构工具**支撑。核心启示是：**monorepo 的关键不是"放在一起"，而是"让跨仓变更的成本足够低"**。

## 关联笔记

- [[编码规范]]：commit message 是规范落地的一部分
- [[代码审查]]：分支策略直接影响 Review 效率
- [[Lint与静态检查]]：Git Hooks 与 CI 中的静态检查
- [[../CI-CD/流水线设计]]：分支策略与流水线触发、分支模型选型
- [[../灰度发布与监控]]：分支、Tag 与发布流程的衔接
- [[../热修复与动态化]]：hotfix 分支与线上问题的止血
- [[../模块化与组件化/模块划分]]：频繁冲突的目录提示模块划分问题
- [[../README]]：第 8 部分总览

## 参考资料

- Pro Git 官方书籍（中文版）：https://git-scm.com/book/zh/v2
- Conventional Commits 规范：https://www.conventionalcommits.org/zh-hans/
- GitHub Flow 官方文档：https://docs.github.com/en/get-started/using-github/github-flow
- Google Trunk Based Development 论文：https://trunkbaseddevelopment.com/
- husky 官方文档：https://typicode.github.io/husky/
