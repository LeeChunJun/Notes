#!/usr/bin/env pwsh
# fix-links.ps1 —— 修复 Obsidian 双链的路径层级错误
#
# 处理三类问题：
#   A. 多了一层 "../"        e.g. ../../07-xxx/README  →  ../07-xxx/README
#   B. 少了一层 "../"        e.g. ../07-xxx/README    →  ../../07-xxx/README
#   C. 指向目录而非文件        e.g. ../08-xxx/日志与监控   →  ../08-xxx/日志与监控/性能监控.md
#
# 用法: pwsh -File _Dashboard/fix-links.ps1 [-DryRun]
param([switch]$DryRun)

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# 建立全库文件名 -> 完整路径 的索引（排除模板）
$index = @{}
Get-ChildItem $root -Recurse -Filter *.md -File |
    Where-Object { $_.FullName -notmatch '_Templates' } |
    ForEach-Object { $index[$_.BaseName] = $_ }

$total = 0
$changedFiles = 0
$skipped = 0

Get-ChildItem $root -Recurse -Filter *.md -File |
    Where-Object { $_.FullName -notmatch '_Templates' } |
    ForEach-Object {
    $file = $_
    $dir = $file.DirectoryName
    $lines = Get-Content $file.FullName
    $dirty = $false
    $inCode = $false

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -match '^\s*```') { $inCode = -not $inCode; continue }
        if ($inCode) { continue }

        $newLine = $line
        foreach ($m in [regex]::Matches($line, '\[\[([^\]\|#]+)')) {
            $raw = $m.Groups[1].Value
            $l = $raw.Trim().TrimEnd('\')
            if ($l -match '^[.,\d\s]+$') { continue }
            $l2 = if ($l -match '\.md$') { $l } else { "$l.md" }

            if (Test-Path (Join-Path $dir $l2)) { continue }

            $fixed = $null

            # A/B：调整 ../ 前缀层级（保持指向同一目标）
            $parts = $l2 -split '[\\/]'
            $name = $parts[-1]
            $prefix = @()
            $rest = @()
            foreach ($p in $parts) {
                if ($p -eq '..') { $prefix += '..' } else { $rest += $p }
            }
            $rel = ($rest -join '/')

            if ($prefix.Count -gt 0) {
                # 逐个尝试不同的 ../ 层数
                for ($k = 0; $k -le ($prefix.Count + 2); $k++) {
                    $pre = if ($k -eq 0) { '' } else { ('../' * $k) }
                    $cand = if ($pre) { "$pre/$rel" } else { $rel }
                    if (Test-Path (Join-Path $dir $cand)) { $fixed = $cand; break }
                }
            }

            # C：链接指向的是目录 -> 换成该目录下的 README.md
            if (-not $fixed -and (Test-Path (Join-Path $dir $l2))) {
                $dirPath = Join-Path $dir $l2
                $readme = Join-Path $dirPath 'README.md'
                if (Test-Path $readme) {
                    $fixed = [System.IO.Path]::GetRelativePath($dir, $readme).Replace('\', '/')
                }
            }

            # D：按文件名在全库查找（裸文件名 / 错误的相对路径）
            if (-not $fixed -and $index.ContainsKey([System.IO.Path]::GetFileNameWithoutExtension($l2))) {
                $target = $index[[System.IO.Path]::GetFileNameWithoutExtension($l2)]
                $fixed = [System.IO.Path]::GetRelativePath($dir, $target.FullName).Replace('\', '/')
            }

            if ($fixed) {
                $newRaw = if ($raw -match '\.md$') { $fixed } else { $fixed -replace '\.md$', '' }
                $newLine = $newLine.Replace("[[$raw]]", "[[$newRaw]]")
                $total++
                $dirty = $true
            }
        }
        $lines[$i] = $newLine
    }

    if ($dirty) {
        $changedFiles++
        if ($DryRun) {
            "DRY-RUN 会修改: $($file.FullName.Replace("$root\", ''))"
        } else {
            Set-Content -Path $file.FullName -Value $lines -Encoding UTF8
            "已修复: $($file.FullName.Replace("$root\", ''))"
        }
    }
}

if ($DryRun) {
    "`n[DRY-RUN] 共发现 $total 处问题，涉及 $changedFiles 个文件。去掉 -DryRun 执行修复。"
} else {
    "`n共修复 $total 处问题，涉及 $changedFiles 个文件。"
}
