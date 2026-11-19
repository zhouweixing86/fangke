#Requires -Version 5.1
<#
.SYNOPSIS
    确定性扫描器：给 autopilot 任务提供"不依赖数据库、不依赖凭据"的自检能力。
.DESCRIPTION
    每个 Invoke-XxxScan 返回 @{ Status; Summary; Details }：
      Status = PASS | WARN | FAIL
      Summary = 一行结论（写进报告表格）
      Details = 明细行数组（写进报告正文）
    所有扫描只读仓库文件，不启动服务、不连数据库、不修改任何内容。
#>

$script:SourceExtensions = @('.java', '.yml', '.yaml', '.sql', '.ts', '.vue', '.js', '.md', '.json', '.xml', '.ps1', '.py')
$script:ExcludePattern = '\\(node_modules|target|dist|\.git|\.autopilot|autopilot|\.vite|\.playwright-mcp|coverage)\\'

function Get-Allowlist {
    param([string]$Kind)
    $path = Join-Path $PSScriptRoot 'allowlist.json'
    if (-not (Test-Path $path)) { return @() }
    try {
        $json = Get-Content -Path $path -Raw -Encoding UTF8 | ConvertFrom-Json
        $entries = $json.$Kind
        if ($null -eq $entries) { return @() }
        return @($entries)
    } catch {
        return @()
    }
}

function Test-Allowlisted {
    param([string]$RelativePath, [string]$Rule, $Entries)
    $normalized = $RelativePath.Replace('\', '/')
    foreach ($entry in $Entries) {
        $pattern = ([string]$entry.path).Replace('\', '/')
        $pathHit = $false
        if ($pattern.EndsWith('/')) { $pathHit = $normalized.StartsWith($pattern) }
        else { $pathHit = ($normalized -eq $pattern) }
        if (-not $pathHit) { continue }
        if ([string]$entry.rule -eq '*' -or [string]$entry.rule -eq $Rule) { return [string]$entry.reason }
    }
    return $null
}

function Get-RepoFiles {
    param(
        [string]$Root,
        [string[]]$Extensions = $script:SourceExtensions,
        [string]$PathFilter = '.*'
    )
    $files = Get-ChildItem -Path $Root -Recurse -File -ErrorAction SilentlyContinue
    foreach ($file in $files) {
        if ($file.FullName -match $script:ExcludePattern) { continue }
        if ($Extensions -notcontains $file.Extension.ToLowerInvariant()) { continue }
        if ($file.FullName -notmatch $PathFilter) { continue }
        $file
    }
}

function New-ScanResult {
    param([string]$Status, [string]$Summary, [string[]]$Details = @())
    [pscustomobject]@{ Status = $Status; Summary = $Summary; Details = $Details }
}

# ---------------------------------------------------------------- 密钥扫描
function Invoke-SecretScan {
    param([string]$Root)

    $rules = @(
        @{ Name = '测试库口令泄漏'; Pattern = 'misaya1(314)?' },
        @{ Name = '硬编码口令赋值'; Pattern = 'password\s*[:=]\s*["''](?!\$\{)[^"'']{6,}["'']' },
        @{ Name = 'AWS AccessKey'; Pattern = 'AKIA[0-9A-Z]{16}' },
        @{ Name = '私钥文件内容'; Pattern = 'BEGIN (RSA|EC|OPENSSH) PRIVATE KEY' }
    )
    $allow = Get-Allowlist -Kind 'secret'
    $hits = New-Object System.Collections.ArrayList
    $exempt = New-Object System.Collections.ArrayList
    $privateIp = New-Object System.Collections.ArrayList
    $files = Get-RepoFiles -Root $Root

    foreach ($file in $files) {
        $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ([string]::IsNullOrEmpty($content)) { continue }
        $relative = $file.FullName.Substring($Root.Length).TrimStart('\')

        foreach ($rule in $rules) {
            if ($content -notmatch $rule.Pattern) { continue }
            $lineNo = 0
            $lines = $content -split "`r?`n"
            for ($i = 0; $i -lt $lines.Length; $i++) {
                if ($lines[$i] -match $rule.Pattern) { $lineNo = $i + 1; break }
            }
            $reason = Test-Allowlisted -RelativePath $relative -Rule $rule.Name -Entries $allow
            $text = "$relative`:$lineNo  $($rule.Name)"
            if ($reason) { [void]$exempt.Add("$text（豁免：$reason）") }
            else { [void]$hits.Add($text) }
        }

        if ($file.Extension -in @('.java', '.yml', '.ps1')) {
            if ($content -match '192\.168\.\d{1,3}\.\d{1,3}') { [void]$privateIp.Add($relative) }
        }
    }

    if ($hits.Count -gt 0) {
        $details = @($hits) + @($exempt | ForEach-Object { "已豁免：$_" })
        return New-ScanResult 'FAIL' "发现 $($hits.Count) 处高危凭据痕迹" $details
    }

    $details2 = @("扫描文件 $($files.Count) 个；豁免 $($exempt.Count) 处（理由见 allowlist.json）")
    if ($exempt.Count -gt 0) { foreach ($e in $exempt) { $details2 += "  · $e" } }
    if ($privateIp.Count -gt 0) {
        # 内网 IP 是联调环境事实（设备/笔记本地址），不是凭据，只作提示不改变结论
        $details2 += "提示：$($privateIp.Count) 个文件含内网 IP（联调/测试用，非凭据）：$($privateIp -join '、')"
    }
    return New-ScanResult 'PASS' '无未豁免凭据' $details2
}

# ---------------------------------------------------------------- 迁移扫描
function Invoke-MigrationScan {
    param([string]$Root)

    $dir = Join-Path $Root 'server\src\main\resources\db\migration'
    if (-not (Test-Path $dir)) { return New-ScanResult 'FAIL' '迁移目录不存在' @($dir) }

    $files = Get-ChildItem -Path $dir -Filter 'V*__*.sql' -File | Sort-Object Name
    if ($files.Count -eq 0) { return New-ScanResult 'FAIL' '没有迁移脚本' @() }

    $versions = New-Object System.Collections.ArrayList
    $details = New-Object System.Collections.ArrayList
    foreach ($file in $files) {
        if ($file.Name -match '^V(\d+)__') {
            [void]$versions.Add([int]$Matches[1])
            [void]$details.Add("$($file.Name)")
        }
    }

    $duplicates = $versions | Group-Object | Where-Object { $_.Count -gt 1 }
    $sorted = $versions | Sort-Object
    $gaps = New-Object System.Collections.ArrayList
    for ($i = 1; $i -lt $sorted.Count; $i++) {
        if ($sorted[$i] -ne ($sorted[$i - 1] + 1)) { [void]$gaps.Add("$($sorted[$i - 1]) → $($sorted[$i])") }
    }
    if ($sorted.Count -gt 0 -and $sorted[0] -ne 1) { [void]$gaps.Add("起始版本不是 1（首个为 $($sorted[0])）") }

    # 通过的迁移脚本正文里统计建表
    $created = New-Object System.Collections.ArrayList
    foreach ($file in $files) {
        $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ([string]::IsNullOrEmpty($content)) { continue }
        foreach ($m in [regex]::Matches($content, 'CREATE TABLE\s+(IF NOT EXISTS\s+)?`?([a-z_][a-z0-9_]*)`?', 'IgnoreCase')) {
            [void]$created.Add($m.Groups[2].Value)
        }
    }

    $details.Add("版本序列：$($sorted -join ', ')")
    $details.Add("建表语句累计 $($created.Count) 条，去重后 $(($created | Sort-Object -Unique).Count) 张")
    $details.Add("最后一张表：$((($created | Sort-Object -Unique) | Select-Object -Last 5) -join '、')")

    if ($duplicates.Count -gt 0) {
        return New-ScanResult 'FAIL' "迁移版本重复：$(($duplicates.Name) -join ',')" $details
    }
    if ($gaps.Count -gt 0) {
        $details.Add("缺号：$($gaps -join '；')")
        return New-ScanResult 'FAIL' "迁移版本不连续：$($gaps -join '；')" $details
    }
    return New-ScanResult 'PASS' "$($sorted.Count) 个迁移，版本 1..$($sorted[$sorted.Count - 1]) 连续" $details
}

# ---------------------------------------------------------------- 端点扫描
function Invoke-EndpointScan {
    param([string]$Root)

    $javaRoot = Join-Path $Root 'server\src\main\java'
    if (-not (Test-Path $javaRoot)) { return New-ScanResult 'FAIL' 'java 源码目录不存在' @($javaRoot) }

    $files = Get-ChildItem -Path $javaRoot -Recurse -Filter '*Controller.java' -File
    $endpoints = New-Object System.Collections.ArrayList

    foreach ($file in $files) {
        $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ([string]::IsNullOrEmpty($content)) { continue }

        $prefix = ''
        $classMatch = [regex]::Match($content, '@RequestMapping\("([^"]*)"\)')
        if ($classMatch.Success) { $prefix = $classMatch.Groups[1].Value }

        foreach ($m in [regex]::Matches($content, '@(Get|Post|Put|Delete|Patch)Mapping(\("([^"]*)"\))?')) {
            $verb = $m.Groups[1].Value.ToUpperInvariant()
            $path = ''
            if ($m.Groups.Count -ge 4) { $path = $m.Groups[3].Value }
            if ([string]::IsNullOrEmpty($path)) { continue }
            $full = ($prefix + $path)
            [void]$endpoints.Add([pscustomobject]@{ Verb = $verb; Path = $full; File = $file.Name })
        }
    }

    $details = New-Object System.Collections.ArrayList
    $groups = $endpoints | Group-Object { 
        $p = $_.Path
        if ($p -match '^/api/(admin|guard|app|device)') { $Matches[1] } elseif ($p -match '^/openapi/v1') { 'openapi(设备协议)' } else { 'other' }
    }
    foreach ($g in ($groups | Sort-Object Name)) {
        [void]$details.Add("/api/$($g.Name)：$($g.Count) 个端点")
    }
    [void]$details.Add("总计 $($endpoints.Count) 个端点，来自 $($files.Count) 个 Controller")

    $docsDir = Join-Path $Root 'docs'
    if (Test-Path $docsDir) {
        # 契约可能分散在多份文档（11 平台接口、14 设备协议），一起扫，避免新增协议文档后误报
        $docPaths = @()
        foreach ($docFile in (Get-ChildItem -Path $docsDir -Filter '*.md' -File)) {
            $docContent = Get-Content -Path $docFile.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            if ([string]::IsNullOrEmpty($docContent)) { continue }
            $docPaths += [regex]::Matches($docContent, '/(api|openapi)/[A-Za-z0-9/\{\}\-_\.]+') |
                ForEach-Object { $_.Value.TrimEnd('.') }
        }
        $docPaths = $docPaths | Sort-Object -Unique
        [void]$details.Add("docs/*.md 中出现的路径 $($docPaths.Count) 条")
        $missing = @()
        foreach ($ep in $endpoints) {
            $normalized = $ep.Path -replace '\{[^}]+\}', '{}'
            $found = $false
            foreach ($dp in $docPaths) {
                if ((($dp -replace '\{[^}]+\}', '{}')) -eq $normalized) { $found = $true; break }
            }
            if (-not $found) { $missing += "$($ep.Verb) $($ep.Path)" }
        }
        if ($missing.Count -gt 0) {
            [void]$details.Add("文档未覆盖（前 20 条）：")
            foreach ($m in ($missing | Select-Object -First 20)) { [void]$details.Add("  - $m") }
        }
        if ($missing.Count -gt 0) {
            return New-ScanResult 'WARN' "代码 $($endpoints.Count) 个端点，$($missing.Count) 个未在 docs 出现" $details
        }
    }

    if ($endpoints.Count -eq 0) { return New-ScanResult 'FAIL' '未解析到任何端点' $details }
    return New-ScanResult 'PASS' "代码 $($endpoints.Count) 个端点与文档一致" $details
}

# ---------------------------------------------------------------- 鉴权扫描
function Invoke-AuthzScan {
    param([string]$Root)

    $javaRoot = Join-Path $Root 'server\src\main\java'
    $details = New-Object System.Collections.ArrayList
    $problems = New-Object System.Collections.ArrayList

    $configPath = Join-Path $javaRoot 'com\fangke\visitor\config\WebConfig.java'
    if (Test-Path $configPath) {
        $config = Get-Content -Path $configPath -Raw -Encoding UTF8
        foreach ($m in [regex]::Matches($config, 'addPathPatterns\(([^)]*)\)')) {
            [void]$details.Add("拦截器覆盖：$($m.Groups[1].Value)")
        }
    }

    $appInterceptor = Join-Path $javaRoot 'com\fangke\visitor\security\AppAuthInterceptor.java'
    if (Test-Path $appInterceptor) {
        $content = Get-Content -Path $appInterceptor -Raw -Encoding UTF8
        $block = [regex]::Match($content, 'isPublic\(.*?\n    \}', 'Singleline')
        if ($block.Success) {
            foreach ($m in [regex]::Matches($block.Value, '"/api/app/[^"]*"')) {
                [void]$details.Add("App 公开白名单：$($m.Value)")
            }
        }
    }

    # 首选：集中式规则（拦截器统一拦 /api/admin/**）
    $centralGate = $false
    $authInterceptor = Join-Path $javaRoot 'com\fangke\visitor\security\AuthInterceptor.java'
    if (Test-Path $authInterceptor) {
        $content = Get-Content -Path $authInterceptor -Raw -Encoding UTF8
        $hasAdminPath = $content -match 'startsWith\("/api/admin'
        $hasForbidden = $content -match 'ErrorCode\.FORBIDDEN'
        if ($hasAdminPath -and $hasForbidden) {
            $centralGate = $true
            [void]$details.Add('集中式授权：/api/admin/** 要求 ADMIN（例外 /api/admin/user/me/**）')
        }
    }

    # 逐控制器统计（无论是否有集中规则，都作为可见性信息）
    $adminControllers = Get-ChildItem -Path $javaRoot -Recurse -Filter '*.java' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\admin\\' }
    foreach ($file in $adminControllers) {
        $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ([string]::IsNullOrEmpty($content)) { continue }
        $mappingCount = [regex]::Matches($content, '@(Get|Post|Put|Delete)Mapping').Count
        if ($mappingCount -eq 0) { continue }
        $guardCount = [regex]::Matches($content, 'requireAdmin|requireGuardOrAdmin').Count
        [void]$details.Add("$($file.Name)：$mappingCount 个端点，$guardCount 处显式角色校验")
        if (-not $centralGate -and $guardCount -eq 0) {
            [void]$problems.Add("$($file.Name) 没有角色校验，且拦截器没有集中规则")
        }
    }

    # 移动端控制器：属于 /api/app 但完全没引用 AppAuthInterceptor 的，需要确认是否全公开
    $appControllers = Get-ChildItem -Path $javaRoot -Recurse -Filter '*.java' -File -ErrorAction SilentlyContinue
    foreach ($file in $appControllers) {
        $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ([string]::IsNullOrEmpty($content)) { continue }
        if ($file.Name -match 'AppAuthController') { continue }
        if ($content -notmatch '@RequestMapping\("/api/app') { continue }
        if ($content -notmatch 'AppAuthInterceptor') {
            [void]$problems.Add("$($file.Name) 属于 /api/app 但未引用 AppAuthInterceptor（需确认是否全公开）")
        }
    }

    if ($problems.Count -gt 0) {
        foreach ($p in $problems) { [void]$details.Add("待确认：$p") }
        return New-ScanResult 'WARN' "$($problems.Count) 处需要人工确认" $details
    }
    if ($centralGate) { return New-ScanResult 'PASS' '集中式管理端授权 + App 白名单可解释' $details }
    return New-ScanResult 'PASS' '鉴权覆盖可解释' $details
}

# ---------------------------------------------------------------- TODO 扫描
function Invoke-TodoScan {
    param([string]$Root)

    $files = Get-RepoFiles -Root $Root -Extensions @('.java', '.ts', '.vue', '.sql', '.md')
    $hits = New-Object System.Collections.ArrayList
    $total = 0

    foreach ($file in $files) {
        $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ([string]::IsNullOrEmpty($content)) { continue }
        $count = ([regex]::Matches($content, 'TODO|FIXME|待实现|待补')).Count
        if ($count -gt 0) {
            $relative = $file.FullName.Substring($Root.Length).TrimStart('\')
            [void]$hits.Add([pscustomobject]@{ File = $relative; Count = $count })
            $total += $count
        }
    }

    $details = New-Object System.Collections.ArrayList
    foreach ($h in ($hits | Sort-Object Count -Descending | Select-Object -First 15)) {
        [void]$details.Add("$($h.File)：$($h.Count) 处")
    }
    if ($total -eq 0) { return New-ScanResult 'PASS' '无 TODO/FIXME 标记' $details }
    if ($total -gt 40) { return New-ScanResult 'WARN' "$total 处待办标记（建议收敛）" $details }
    return New-ScanResult 'PASS' "$total 处待办标记" $details
}

# ---------------------------------------------------------------- 二维码扫描
function Invoke-QrScan {
    param([string]$Root)

    $details = New-Object System.Collections.ArrayList
    $problems = New-Object System.Collections.ArrayList

    $migrationDir = Join-Path $Root 'server\src\main\resources\db\migration'
    $qrFiles = Get-ChildItem -Path $migrationDir -Filter '*.sql' -File | Sort-Object Name

    # 只看"最终生效"的定义：遇到 DROP TABLE qr_ticket 就把之前的定义清空（历史迁移不算当前状态）
    $effective = New-Object System.Collections.ArrayList
    foreach ($file in $qrFiles) {
        $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ([string]::IsNullOrEmpty($content)) { continue }
        if ($content -notmatch 'qr_ticket') { continue }
        if ($content -match 'DROP TABLE\s+(IF EXISTS\s+)?`?qr_ticket') { $effective.Clear() }
        if ($content -match 'CREATE TABLE\s+(IF NOT EXISTS\s+)?`?qr_ticket') { [void]$effective.Add($file) }
    }

    foreach ($file in $effective) {
        $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8
        if ($content -match '(?m)^\s*code\s+VARCHAR') {
            [void]$problems.Add("$($file.Name) 仍存在明文 code 列")
        }
        if ($content -match '(?m)^\s*code_hash\s+CHAR\(64\)') {
            [void]$details.Add("$($file.Name)：使用 code_hash CHAR(64)")
        }
        if ($content -match 'use_count') { [void]$details.Add("$($file.Name)：保留 use_count（BR-V-020 可重复使用）") }
    }
    [void]$details.Add("生效定义来自：$(($effective | ForEach-Object { $_.Name }) -join '、')（历史迁移已 DROP，不计入）")

    $repoPath = Join-Path $Root 'server\src\main\java\com\fangke\visitor\qr\repository\JdbcQrTicketRepository.java'
    if (Test-Path $repoPath) {
        $content = Get-Content -Path $repoPath -Raw -Encoding UTF8
        if ($content -match 'claim\(') { [void]$problems.Add('JdbcQrTicketRepository 仍存在 claim()（一次性消费语义，违反 BR-V-020）') }
        if ($content -match 'recordUse') { [void]$details.Add('仓储使用 recordUse() 累加次数') }
    }

    $svcPath = Join-Path $Root 'server\src\main\java\com\fangke\visitor\qr\service\QrTicketService.java'
    if (Test-Path $svcPath) {
        $content = Get-Content -Path $svcPath -Raw -Encoding UTF8
        foreach ($kw in 'INVALID_FORMAT', 'BAD_SIGNATURE', 'EXPIRED', 'NO_GRANT', 'OUT_OF_WINDOW', 'GATE_MISMATCH') {
            if ($content -match $kw) { [void]$details.Add("拒绝路径覆盖：$kw") }
        }
    }

    if ($problems.Count -gt 0) {
        foreach ($p in $problems) { [void]$details.Add("问题：$p") }
        return New-ScanResult 'FAIL' "$($problems.Count) 个二维码安全隐患" $details
    }
    return New-ScanResult 'PASS' '无明文码、无可复用语义回退' $details
}

# ---------------------------------------------------------------- 依赖扫描
function Invoke-DepsScan {
    param([string]$Root)

    $details = New-Object System.Collections.ArrayList
    $pom = Join-Path $Root 'server\pom.xml'
    if (Test-Path $pom) {
        $content = Get-Content -Path $pom -Raw -Encoding UTF8
        $boot = [regex]::Match($content, '<artifactId>spring-boot-starter-parent</artifactId>\s*<version>([^<]+)</version>')
        if ($boot.Success) { [void]$details.Add("Spring Boot $($boot.Groups[1].Value)") }
        $java = [regex]::Match($content, '<java.version>([^<]+)</java.version>')
        if ($java.Success) { [void]$details.Add("Java $($java.Groups[1].Value)") }
        $deps = [regex]::Matches($content, '<dependency>').Count
        [void]$details.Add("Maven 依赖 $deps 个")
    }

    foreach ($pkg in @('web', 'h5')) {
        $path = Join-Path $Root "$pkg\package.json"
        if (-not (Test-Path $path)) { continue }
        $json = Get-Content -Path $path -Raw -Encoding UTF8 | ConvertFrom-Json
        $depCount = ($json.dependencies.PSObject.Properties | Measure-Object).Count
        $devCount = ($json.devDependencies.PSObject.Properties | Measure-Object).Count
        [void]$details.Add("$pkg：运行依赖 $depCount，开发依赖 $devCount")
        foreach ($name in @('vue', 'element-plus', 'vant', 'vite', 'typescript')) {
            $prop = $json.dependencies.PSObject.Properties | Where-Object { $_.Name -eq $name }
            if ($null -eq $prop) { $prop = $json.devDependencies.PSObject.Properties | Where-Object { $_.Name -eq $name } }
            if ($null -ne $prop) { [void]$details.Add("  $pkg/$name = $($prop.Value)") }
        }
    }

    if ($details.Count -eq 0) { return New-ScanResult 'FAIL' '未找到依赖清单' @() }
    return New-ScanResult 'PASS' '依赖清单可读' $details
}

# ---------------------------------------------------------------- 索引扫描
function Invoke-IndexScan {
    param([string]$Root)

    $migrationDir = Join-Path $Root 'server\src\main\resources\db\migration'
    $files = Get-ChildItem -Path $migrationDir -Filter '*.sql' -File
    $tables = @{}
    $details = New-Object System.Collections.ArrayList

    foreach ($file in $files) {
        $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ([string]::IsNullOrEmpty($content)) { continue }
        foreach ($m in [regex]::Matches($content, 'CREATE TABLE\s+(IF NOT EXISTS\s+)?`?([a-z_][a-z0-9_]*)`?\s*\((.*?)\)\s*ENGINE', 'Singleline, IgnoreCase')) {
            $name = $m.Groups[2].Value
            $body = $m.Groups[3].Value
            $keys = ([regex]::Matches($body, '(?m)^\s*(PRIMARY KEY|UNIQUE KEY|KEY|INDEX)\s')).Count
            $tables[$name] = $keys
        }
    }

    $noIndex = @()
    foreach ($name in ($tables.Keys | Sort-Object)) {
        if ($tables[$name] -le 1) { $noIndex += $name }
    }

    [void]$details.Add("解析到 $($tables.Count) 张表")
    if ($noIndex.Count -gt 0) {
        [void]$details.Add("只有主键、无二级索引：$($noIndex -join '、')")
        return New-ScanResult 'WARN' "$($noIndex.Count) 张表缺少二级索引" $details
    }
    return New-ScanResult 'PASS' '各表均有二级索引' $details
}

# ---------------------------------------------------------------- 文档进度扫描
function Invoke-DocProgressScan {
    param([string]$Root)

    $details = New-Object System.Collections.ArrayList
    $warnings = 0

    $doc09 = Join-Path $Root 'docs\09-开发计划与技术架构.md'
    if (Test-Path $doc09) {
        $content = Get-Content -Path $doc09 -Raw -Encoding UTF8
        $done = ([regex]::Matches($content, '✅')).Count
        $todo = ([regex]::Matches($content, '⬜')).Count
        [void]$details.Add("docs/09：$done 个 ✅，$todo 个 ⬜（待设备/客户依赖项属正常）")
    }

    $docReadme = Join-Path $Root 'docs\README.md'
    if (Test-Path $docReadme) {
        $content = Get-Content -Path $docReadme -Raw -Encoding UTF8
        $version = [regex]::Match($content, '版本 (v[0-9.]+)')
        if ($version.Success) { [void]$details.Add("docs/README 版本 $($version.Groups[1].Value)") }
    }

    # 实际用例数：优先读 surefire 报告（与 CI 一致），没有报告再退化为数 @Test
    $actualCount = 0
    $countSource = ''
    $surefireDir = Join-Path $Root 'server\target\surefire-reports'
    if (Test-Path $surefireDir) {
        foreach ($xml in (Get-ChildItem -Path $surefireDir -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue)) {
            $head = Get-Content -Path $xml.FullName -TotalCount 3 -Encoding UTF8 -ErrorAction SilentlyContinue
            foreach ($line in $head) {
                $m = [regex]::Match($line, 'tests="(\d+)"')
                if ($m.Success) { $actualCount += [int]$m.Groups[1].Value; break }
            }
        }
        if ($actualCount -gt 0) { $countSource = 'surefire 报告' }
    }
    if ($actualCount -eq 0) {
        $testFiles = Get-ChildItem -Path (Join-Path $Root 'server\src\test') -Recurse -Filter '*Test.java' -File -ErrorAction SilentlyContinue
        foreach ($file in $testFiles) {
            $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            if ([string]::IsNullOrEmpty($content)) { continue }
            $actualCount += ([regex]::Matches($content, '@Test')).Count
        }
        $countSource = '@Test 注解（未找到 surefire 报告，先跑一次 T01）'
    }
    [void]$details.Add("实际用例数 $actualCount（来源：$countSource）")

    $serverReadme = Join-Path $Root 'server\README.md'
    if (Test-Path $serverReadme) {
        $content = Get-Content -Path $serverReadme -Raw -Encoding UTF8
        $declared = [regex]::Match($content, '当前 \*\*(\d+) 个用例\*\*')
        if ($declared.Success) {
            $declaredCount = [int]$declared.Groups[1].Value
            [void]$details.Add("server/README 声明 $declaredCount 个用例")
            if ($declaredCount -ne $actualCount) {
                [void]$details.Add("不一致：README 写 $declaredCount，实际 $actualCount")
                $warnings++
            }
        }
    }

    $webViews = (Get-ChildItem -Path (Join-Path $Root 'web\src\views') -Filter '*.vue' -File -ErrorAction SilentlyContinue).Count
    $uniPagesJson = Join-Path $Root 'uniapp\src\pages.json'
    $uniViews = 0
    if (Test-Path $uniPagesJson) {
        $pagesContent = Get-Content -Path $uniPagesJson -Raw -Encoding UTF8
        $uniViews = ([regex]::Matches($pagesContent, '"path"\s*:')).Count
    }
    [void]$details.Add("页面数：管理端 web $webViews，移动端 uniapp $uniViews（pages.json 注册数）")

    if ($warnings -gt 0) { return New-ScanResult 'WARN' "$warnings 处文档与实现对不上" $details }
    return New-ScanResult 'PASS' '文档进度与实现一致' $details
}

function Invoke-Scanner {
    param([string]$Name, [string]$Root)
    switch ($Name) {
        'secret' { return Invoke-SecretScan -Root $Root }
        'migration' { return Invoke-MigrationScan -Root $Root }
        'endpoint' { return Invoke-EndpointScan -Root $Root }
        'authz' { return Invoke-AuthzScan -Root $Root }
        'todo' { return Invoke-TodoScan -Root $Root }
        'qr' { return Invoke-QrScan -Root $Root }
        'deps' { return Invoke-DepsScan -Root $Root }
        'index' { return Invoke-IndexScan -Root $Root }
        'docprogress' { return Invoke-DocProgressScan -Root $Root }
        default { return New-ScanResult 'FAIL' "未知扫描器：$Name" @() }
    }
}
