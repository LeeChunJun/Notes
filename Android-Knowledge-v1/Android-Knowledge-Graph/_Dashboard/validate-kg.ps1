#!/usr/bin/env pwsh
# validate-kg.ps1 —— 知识图谱完整性校验脚本
# 用法: pwsh -File _Dashboard/validate-kg.ps1
param([switch]$Quiet)

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$errors = New-Object System.Collections.Generic.List[string]
$warnings = New-Object System.Collections.Generic.List[string]

# ---------- 1. 文件统计 ----------
$chapters = Get-ChildItem -Directory | Where-Object { $_.Name -match '^\d\d' } | Sort-Object Name
$allMd = Get-ChildItem -Recurse -Filter *.md | Where-Object { $_.FullName -notmatch '_Templates' }
# 索引/看板/日记类文件使用简化的 frontmatter，不参与笔记级校验
$indexLike = @('索引.md','知识图谱总览.md')
$noteMd = $allMd | Where-Object {
    $rel = $_.FullName.Replace("$root\", "")
    ($rel -notmatch '^_Dashboard\\') -and ($rel -notmatch '^_Daily\\') -and ($indexLike -notcontains $rel)
}

if (-not $Quiet) { "`n=== 1. 章节与文件统计 ===" }
$total = 0
foreach ($ch in $chapters) {
    $files = Get-ChildItem $ch.FullName -Recurse -Filter *.md
    $lines = ($files | Get-Content | Measure-Object -Line).Lines
    $total += $files.Count
    $hasReadme = Test-Path (Join-Path $ch.FullName "README.md")
    if (-not $hasReadme) { $errors.Add("[$($ch.Name)] 缺少 README.md") }
    if (-not $Quiet) {
        "{0,-30} files={1,3}  lines={2,7}" -f $ch.Name, $files.Count, $lines
    }
}
if (-not $Quiet) { "合计: $($allMd.Count) 个笔记文件（含根级与 Dashboard），$total 个章节笔记" }

# ---------- 2. Frontmatter 校验 ----------
if (-not $Quiet) { "`n=== 2. Frontmatter 校验 ===" }
$fmOk = 0
foreach ($f in $noteMd) {
    $rel = $f.FullName.Replace("$root\", "")
    $head = (Get-Content $f.FullName -TotalCount 12)
    if ($head[0].Trim() -ne '---' -or -not ($head -match '^tags:')) {
        $errors.Add("[$rel] frontmatter 缺失或无 tags")
        continue
    }
    $headText = ($head -join "`n")
    foreach ($k in @('tags:','part:','status:','difficulty:','created:','updated:','related:')) {
        if ($headText -notmatch [regex]::Escape($k)) {
            $warnings.Add("[$rel] frontmatter 缺少字段 $k")
        }
    }
    $fmOk++
}
if (-not $Quiet) { "通过: $fmOk / $($noteMd.Count)" }

# ---------- 3. 知识卡骨架校验 ----------
if (-not $Quiet) { "`n=== 3. 知识卡章节骨架校验 ===" }
$sections = @(
    '## 一句话定义', '## 核心原理', '## 代码示例',
    '## 常见问题 / 面试要点', '## 关联笔记', '## 参考资料'
)
$cardOk = 0; $cards = 0
foreach ($f in $noteMd) {
    if ($f.Name -eq 'README.md') { continue }
    $rel = $f.FullName.Replace("$root\", "")
    $content = Get-Content $f.FullName -Raw
    $cards++
    $missing = @()
    foreach ($s in $sections) { if ($content -notmatch [regex]::Escape($s)) { $missing += $s } }
    if ($missing.Count -gt 0) {
        $errors.Add("[$rel] 缺少章节: $($missing -join ', ')")
    } else { $cardOk++ }
}
if (-not $Quiet) { "通过: $cardOk / $cards 个知识卡" }

# ---------- 4. 面试问答数量 ----------
if (-not $Quiet) { "`n=== 4. 面试问答组数（要求 >= 5）===" }
$lowQ = @()
foreach ($f in $noteMd) {
    if ($f.Name -eq 'README.md') { continue }
    $rel = $f.FullName.Replace("$root\", "")
    $content = Get-Content $f.FullName -Raw
    $q = ([regex]::Matches($content, '\*\*读者问')).Count
    if ($q -lt 5) { $lowQ += "[$rel] 仅 $q 组" }
}
if ($lowQ.Count -eq 0) { "✅ 全部知识卡问答 >= 5 组" } else { $lowQ | ForEach-Object { $warnings.Add($_) } }

# ---------- 5. 双链校验 ----------
if (-not $Quiet) { "`n=== 5. 双链解析校验 ===" }
$totalLinks = 0; $broken = @()
foreach ($f in $allMd) {
    $dir = $f.DirectoryName
    $rel = $f.FullName.Replace("$root\", "")
    $lines = Get-Content $f.FullName
    $inCode = $false
    foreach ($line in $lines) {
        if ($line -match '^\s*```') { $inCode = -not $inCode; continue }
        if ($inCode) { continue }
        foreach ($m in [regex]::Matches($line, '\[\[([^\]\|#]+)')) {
            $link = $m.Groups[1].Value.Trim().TrimEnd('\')
            if ($link -match '^(https?|mailto):') { continue }
            # 排除 Kotlin/JSON 数组字面量、纯省略号等误匹配
            if ($link -notmatch '[/\\]' -and $link -notmatch '^[一-龥]' -and $link -match '^[.,\d\s]+$') { continue }
            if ($link -match '^\.+$') { continue }
            if ($link -notmatch '\.md$') { $link = "$link.md" }
            $totalLinks++
            $t = Join-Path $dir $link
            if (-not (Test-Path $t)) { $broken += "[$rel] -> $link" }
        }
    }
}
if (-not $Quiet) { "共 $totalLinks 条双链，断链 $($broken.Count) 条" }
foreach ($b in $broken) { $errors.Add($b) }

# ---------- 6. 占位内容扫描 ----------
if (-not $Quiet) { "`n=== 6. 占位内容扫描 ===" }
$ph = @()
foreach ($f in $noteMd) {
    $rel = $f.FullName.Replace("$root\", "")
    $c = Get-Content $f.FullName -Raw
    # 排除代码块内部（示例性 TODO()、lint 规则、grep 模式）与 _Daily 清单
    $codeStripped = [regex]::Replace($c, '(?s)```.*?```', '')
    foreach ($p in @('待补充', '待完善', '\{\{title\}\}', 'XXX占位', 'FIXME:', '此处填写', '内容待定')) {
        if ($codeStripped -match $p) { $ph += "[$rel] 含 '$p'" }
    }
}
if ($ph.Count -eq 0) { "✅ 无占位内容" } else { $ph | ForEach-Object { $warnings.Add($_) } }

# ---------- 7. 代码块围栏配对 ----------
if (-not $Quiet) { "`n=== 7. 代码块围栏配对 ===" }
$badFence = @()
foreach ($f in $allMd) {
    $rel = $f.FullName.Replace("$root\", "")
    $n = (Get-Content $f.FullName | Where-Object { $_ -match '^\s*```' }).Count
    if ($n % 2 -ne 0) { $badFence += "[$rel] 围栏数 $n (奇数)" }
}
if ($badFence.Count -eq 0) { "✅ 全部围栏配对" } else { $badFence | ForEach-Object { $errors.Add($_) } }

# ---------- 汇总 ----------
"`n================ 校验汇总 ================"
if ($errors.Count -eq 0) {
    "✅ 无错误"
} else {
    "❌ 错误 $($errors.Count) 条："
    $errors | Select-Object -First 60 | ForEach-Object { "   $_" }
    if ($errors.Count -gt 60) { "   ... 其余 $($errors.Count - 60) 条省略" }
}
if ($warnings.Count -gt 0) {
    "⚠️  警告 $($warnings.Count) 条："
    $warnings | Select-Object -First 40 | ForEach-Object { "   $_" }
    if ($warnings.Count -gt 40) { "   ... 其余 $($warnings.Count - 40) 条省略" }
}
"=========================================="
