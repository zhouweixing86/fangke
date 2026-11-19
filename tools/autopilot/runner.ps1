#Requires -Version 5.1
<#
.SYNOPSIS
    Fangke Autopilot：把现有功能拆成独立任务，定时跑"测试 → 优化 → 迭代"，并可单独唤醒 AI 代理。

.DESCRIPTION
    每次执行：挑任务 → 跑确定性检查（命令/扫描）→ 写报告 → 按策略决定是否唤醒代理 → 更新状态。

    常用：
      runner.ps1 -Mode plan                      # 看哪些任务到期
      runner.ps1 -Mode run -Task T04             # 只跑安全检查
      runner.ps1 -Mode run -Task T04 -Wake       # 跑完并唤醒代理修复
      runner.ps1 -Mode auto -MaxTasks 1 -Wake    # 供计划任务调用：跑到期的第一个任务
      runner.ps1 -Mode report                    # 看最近执行结果

    状态与产物放在仓库根 .autopilot/（已 gitignore）：
      state.json          每个任务上次结果/下次到期/今日唤醒次数
      runs/*.md           每次执行报告（含检查输出与待执行的代理提示词）
      pending/*.prompt.md 待唤醒的详细提示词
      logs/runner.log     运行日志
#>
param(
    [ValidateSet('plan', 'run', 'auto', 'all', 'report')]
    [string]$Mode = 'plan',
    [string]$Task,
    [switch]$Wake,
    [ValidateSet('', 'fail', 'always', 'never')]
    [string]$WakePolicy = '',
    [int]$MaxTasks = 0,
    [int]$MaxWakesPerDay = -1,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$script:ScriptDir = Split-Path -Parent $PSCommandPath
$script:Root = (Resolve-Path (Join-Path $script:ScriptDir '..\..')).Path
$script:StateDir = Join-Path $script:Root '.autopilot'
$script:RunsDir = Join-Path $script:StateDir 'runs'
$script:PendingDir = Join-Path $script:StateDir 'pending'
$script:LogDir = Join-Path $script:StateDir 'logs'
$script:StatePath = Join-Path $script:StateDir 'state.json'
$script:LockPath = Join-Path $script:StateDir 'runner.lock'

foreach ($dir in @($script:StateDir, $script:RunsDir, $script:PendingDir, $script:LogDir)) {
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
}

. (Join-Path $script:ScriptDir 'scanners.ps1')

# ---------------------------------------------------------------- 基础设施
function Write-Log {
    param([string]$Message, [string]$Level = 'INFO')
    $line = "[{0}] [{1}] {2}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Message
    Add-Content -Path (Join-Path $script:LogDir 'runner.log') -Value $line -Encoding UTF8
    if ($Level -eq 'ERROR') { Write-Host $line -ForegroundColor Red }
    elseif ($Level -eq 'WARN') { Write-Host $line -ForegroundColor Yellow }
}

function Get-Config {
    $path = Join-Path $script:ScriptDir 'tasks.json'
    if (-not (Test-Path $path)) { throw "找不到任务配置：$path" }
    $raw = Get-Content -Path $path -Raw -Encoding UTF8
    return $raw | ConvertFrom-Json
}

function Get-State {
    if (-not (Test-Path $script:StatePath)) {
        return [pscustomobject]@{ tasks = [pscustomobject]@{}; wake = [pscustomobject]@{ date = ''; count = 0 } }
    }
    $raw = Get-Content -Path $script:StatePath -Raw -Encoding UTF8
    if ([string]::IsNullOrWhiteSpace($raw)) {
        return [pscustomobject]@{ tasks = [pscustomobject]@{}; wake = [pscustomobject]@{ date = ''; count = 0 } }
    }
    return $raw | ConvertFrom-Json
}

function Save-State {
    param($State)
    $State | ConvertTo-Json -Depth 8 | Set-Content -Path $script:StatePath -Encoding UTF8
}

function Get-TaskState {
    param($State, [string]$TaskId)
    $prop = $State.tasks.PSObject.Properties | Where-Object { $_.Name -eq $TaskId }
    if ($null -eq $prop) {
        return [pscustomobject]@{
            lastRunAt  = ''
            lastStatus = ''
            failStreak = 0
            nextDueAt  = ''
            lastReport = ''
        }
    }
    return $prop.Value
}

function Set-TaskState {
    param($State, [string]$TaskId, $TaskState)
    $existing = $State.tasks.PSObject.Properties | Where-Object { $_.Name -eq $TaskId }
    if ($null -eq $existing) {
        $State.tasks | Add-Member -NotePropertyName $TaskId -NotePropertyValue $TaskState -Force
    } else {
        $existing.Value = $TaskState
    }
}

function Test-Due {
    param($TaskDef, $TaskState)
    if ([string]::IsNullOrWhiteSpace($TaskState.nextDueAt)) { return $true }
    try {
        $due = [datetime]::Parse($TaskState.nextDueAt)
    } catch {
        return $true
    }
    return ((Get-Date) -ge $due)
}

function Get-NextDue {
    param($TaskDef, [datetime]$From)
    $interval = 1
    if ($null -ne $TaskDef.intervalDays) { $interval = [int]$TaskDef.intervalDays }
    if ($interval -lt 1) { $interval = 1 }
    return $From.AddDays($interval)
}

# ---------------------------------------------------------------- 命令配方
function Resolve-Maven {
    if ($env:FANGKE_MVN -and (Test-Path $env:FANGKE_MVN)) { return $env:FANGKE_MVN }
    $candidate = Join-Path $env:LOCALAPPDATA 'fangke-tools\apache-maven-3.9.16\bin\mvn.cmd'
    if (Test-Path $candidate) { return $candidate }
    $cmd = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($null -ne $cmd) { return $cmd.Source }
    return 'mvn'
}

function Resolve-MavenSettings {
    if ($env:FANGKE_MVN_SETTINGS -and (Test-Path $env:FANGKE_MVN_SETTINGS)) { return $env:FANGKE_MVN_SETTINGS }
    $candidate = Join-Path $env:LOCALAPPDATA 'fangke-tools\settings.xml'
    if (Test-Path $candidate) { return $candidate }
    return $null
}

function Get-MavenArgs {
    param([string[]]$GoalArgs)
    $args = @()
    $settings = Resolve-MavenSettings
    if ($settings) { $args += @('-s', $settings) }
    $args += @('-B')
    $args += $GoalArgs
    return $args
}

function Resolve-CheckCommand {
    param($Check)
    $recipe = [string]$Check.run
    switch -Regex ($recipe) {
        '^mvn-test$' {
            return @{ Exe = (Resolve-Maven); Args = (Get-MavenArgs @('test')) }
        }
        '^mvn-device-tests$' {
            return @{ Exe = (Resolve-Maven); Args = (Get-MavenArgs @('-Dtest=Device*Test,LanHttpAdapterTest,ReconcileServiceTest,OnlineCompareServiceTest,OpenApi*Test,MockDeviceAdapterTest', 'test')) }
        }
        '^mvn-approval-tests$' {
            return @{ Exe = (Resolve-Maven); Args = (Get-MavenArgs @('-Dtest=ApprovalServiceTest,DispatchServiceTest,GuardServiceTest', 'test')) }
        }
        '^mvn-qr-tests$' {
            return @{ Exe = (Resolve-Maven); Args = (Get-MavenArgs @('-Dtest=Qr*Test,OnlineCompareServiceTest', 'test')) }
        }
        '^mvn-notify-tests$' {
            return @{ Exe = (Resolve-Maven); Args = (Get-MavenArgs @('-Dtest=NotifyServiceTest,MaintenanceServiceTest,ApprovalReminderServiceTest', 'test')) }
        }
        '^mvn-report-tests$' {
            return @{ Exe = (Resolve-Maven); Args = (Get-MavenArgs @('-Dtest=ReportServiceTest,UserAdminServiceTest,BlacklistAdminServiceTest,GlobalExceptionHandlerTest', 'test')) }
        }
        '^npm\s' {
            $npm = (Get-Command npm.cmd -ErrorAction SilentlyContinue)
            $exe = if ($null -ne $npm) { $npm.Source } else { 'npm' }
            $rest = $recipe.Substring(4).Split(' ', [System.StringSplitOptions]::RemoveEmptyEntries)
            return @{ Exe = $exe; Args = @($rest) }
        }
        default {
            # 原样命令：交给 powershell -Command 执行，方便写组合命令
            return @{ Exe = 'powershell.exe'; Args = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $recipe) }
        }
    }
}

# ---------------------------------------------------------------- 执行
function Invoke-External {
    param([string]$Exe, [string[]]$Arguments, [string]$WorkDir, [int]$TimeoutSec)

    $started = Get-Date

    # .cmd/.bat 必须经由 cmd.exe 启动（Start-Process / ProcessStartInfo 都不能直接执行批处理）
    $fileName = $Exe
    $argList = @($Arguments)
    if ($Exe -match '\.(cmd|bat)$') {
        $fileName = $env:ComSpec
        $argList = @('/c', $Exe) + $argList
    }

    $quoted = foreach ($a in $argList) {
        $text = [string]$a
        if ($text -match '[\s"]') { '"' + ($text -replace '"', '\"') + '"' } else { $text }
    }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $fileName
    $psi.Arguments = ($quoted -join ' ')
    $psi.WorkingDirectory = $WorkDir
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    $psi.CreateNoWindow = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi
    [void]$process.Start()
    $outTask = $process.StandardOutput.ReadToEndAsync()
    $errTask = $process.StandardError.ReadToEndAsync()

    $finished = $process.WaitForExit($TimeoutSec * 1000)
    if (-not $finished) {
        try { $process.Kill() } catch { }
    }
    $out = ''
    $err = ''
    try { $out = $outTask.Result } catch { }
    try { $err = $errTask.Result } catch { }
    if (-not $finished) {
        $out = "$out`n[autopilot] 超时 $TimeoutSec 秒，已终止"
    }
    $exitCode = 124
    if ($finished) {
        $exitCode = $process.ExitCode
    }
    $process.Dispose()

    return @{
        ExitCode    = $exitCode
        Output      = ("$out`n$err").Trim()
        DurationSec = [int]((Get-Date) - $started).TotalSeconds
    }
}

function Invoke-TaskCheck {
    param($TaskDef, $Check, $Defaults)

    $timeout = 1200
    if ($null -ne $Defaults.checkTimeoutSec) { $timeout = [int]$Defaults.checkTimeoutSec }
    if ($null -ne $Check.timeoutSec) { $timeout = [int]$Check.timeoutSec }

    if ([string]$Check.type -eq 'scan') {
        try {
            $result = Invoke-Scanner -Name ([string]$Check.scanner) -Root $script:Root
            return @{
                Name        = [string]$Check.Name
                Status      = [string]$result.Status
                Summary     = [string]$result.Summary
                Details     = @($result.Details)
                DurationSec = 0
            }
        } catch {
            return @{
                Name        = [string]$Check.Name
                Status      = 'FAIL'
                Summary     = "扫描器异常：$($_.Exception.Message)"
                Details     = @()
                DurationSec = 0
            }
        }
    }

    $commandInfo = Resolve-CheckCommand -Check $Check
    $workDir = $script:Root
    if ($Check.cwd) { $workDir = Join-Path $script:Root ([string]$Check.cwd) }
    if (-not (Test-Path $workDir)) {
        return @{
            Name        = [string]$Check.Name
            Status      = 'FAIL'
            Summary     = "工作目录不存在：$workDir"
            Details     = @()
            DurationSec = 0
        }
    }

    Write-Log "执行检查 $($TaskDef.id)/$($Check.Name)：$($commandInfo.Exe) $($commandInfo.Args -join ' ')"
    $run = Invoke-External -Exe $commandInfo.Exe -Arguments $commandInfo.Args -WorkDir $workDir -TimeoutSec $timeout

    $status = 'PASS'
    if ($run.ExitCode -ne 0) { $status = 'FAIL' }

    $output = [string]$run.Output
    $tailChars = 4000
    if ($null -ne $Defaults.outputTailChars) { $tailChars = [int]$Defaults.outputTailChars }
    if ($output.Length -gt $tailChars) { $output = $output.Substring($output.Length - $tailChars) }

    $summary = "退出码 $($run.ExitCode)，耗时 $($run.DurationSec)s"
    $testMatches = [regex]::Matches($output, 'Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+),\s*Skipped:\s*(\d+)')
    if ($status -eq 'PASS' -and $testMatches.Count -gt 0) {
        $last = $testMatches[$testMatches.Count - 1]
        $summary = "通过 $($last.Groups[1].Value) 个用例（失败 $($last.Groups[2].Value)），耗时 $($run.DurationSec)s"
    }

    return @{
        Name        = [string]$Check.Name
        Status      = $status
        Summary     = $summary
        Details     = @()
        Output      = $output
        DurationSec = $run.DurationSec
    }
}

# ---------------------------------------------------------------- 报告
function Write-TaskReport {
    param($TaskDef, $Results, [string]$Status, [datetime]$StartedAt, [datetime]$FinishedAt)

    $stamp = $StartedAt.ToString('yyyyMMdd-HHmmss')
    $fileName = "$stamp-$($TaskDef.id).md"
    $path = Join-Path $script:RunsDir $fileName

    $lines = New-Object System.Collections.ArrayList
    [void]$lines.Add("# $($TaskDef.id) $($TaskDef.name)")
    [void]$lines.Add('')
    [void]$lines.Add("- 区域：``$($TaskDef.area)``")
    [void]$lines.Add("- 结论：**$Status**")
    [void]$lines.Add("- 开始：$($StartedAt.ToString('yyyy-MM-dd HH:mm:ss'))")
    [void]$lines.Add("- 结束：$($FinishedAt.ToString('yyyy-MM-dd HH:mm:ss'))")
    [void]$lines.Add("- 耗时：$([int]($FinishedAt - $StartedAt).TotalSeconds) 秒")
    [void]$lines.Add('')
    [void]$lines.Add('## 检查项')
    [void]$lines.Add('')
    [void]$lines.Add('| 检查 | 结果 | 说明 | 耗时 |')
    [void]$lines.Add('|---|---|---|---|')
    foreach ($r in $Results) {
        [void]$lines.Add("| $($r.Name) | $($r.Status) | $($r.Summary) | $($r.DurationSec)s |")
    }
    [void]$lines.Add('')

    foreach ($r in $Results) {
        if ($r.Details -and $r.Details.Count -gt 0) {
            [void]$lines.Add("### $($r.Name) 明细")
            [void]$lines.Add('')
            foreach ($d in $r.Details) { [void]$lines.Add("- $d") }
            [void]$lines.Add('')
        }
        if ($r.Output) {
            [void]$lines.Add("### $($r.Name) 输出（末尾）")
            [void]$lines.Add('')
            [void]$lines.Add('```text')
            [void]$lines.Add([string]$r.Output)
            [void]$lines.Add('```')
            [void]$lines.Add('')
        }
    }

    Set-Content -Path $path -Value ($lines -join "`r`n") -Encoding UTF8
    return $path
}

function New-AgentPrompt {
    param($TaskDef, $Results, [string]$Status, [string]$ReportPath, [string]$PromptPath)

    $lines = New-Object System.Collections.ArrayList
    [void]$lines.Add("# 待执行任务：$($TaskDef.id) $($TaskDef.name)")
    [void]$lines.Add('')
    [void]$lines.Add("检查结论：**$Status**")
    [void]$lines.Add("详细报告：$ReportPath")
    [void]$lines.Add('')
    [void]$lines.Add('## 检查结果')
    [void]$lines.Add('')
    foreach ($r in $Results) { [void]$lines.Add("- [$($r.Status)] $($r.Name)：$($r.Summary)") }
    [void]$lines.Add('')
    [void]$lines.Add('## 你的任务')
    [void]$lines.Add('')
    [void]$lines.Add([string]$TaskDef.prompt)
    [void]$lines.Add('')
    [void]$lines.Add('## 通用约束（必须遵守）')
    [void]$lines.Add('')
    [void]$lines.Add('1. 先读报告与相关源码，再动手；不确定就去验证，不要猜。')
    [void]$lines.Add('2. 只改与本任务相关的内容，不顺手重构无关代码。')
    [void]$lines.Add('3. 任何改动后必须跑最小验证（后端 mvn test、前端 npm run build 中相关的那一项）。')
    [void]$lines.Add('4. 涉及接口/表结构/业务口径的改动，同步更新 docs/09、docs/10、docs/11 对应章节。')
    [void]$lines.Add('5. 结束时在下方「执行记录」追加：改了什么、验证命令与结果、遗留问题。')
    [void]$lines.Add('')
    [void]$lines.Add('## 执行记录')
    [void]$lines.Add('')
    [void]$lines.Add('- （待填写）')

    Set-Content -Path $PromptPath -Value ($lines -join "`r`n") -Encoding UTF8
    return $PromptPath
}

function Invoke-AgentWake {
    param($TaskDef, [string]$PromptPath, [string]$ReportPath, $Defaults)

    $agent = 'copilot'
    if ($Defaults.wakeAgent) { $agent = [string]$Defaults.wakeAgent }
    $command = Get-Command $agent -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        Write-Log "未找到代理命令 $agent，跳过唤醒（提示词已生成）" 'WARN'
        return @{ Woke = $false; Reason = "代理命令 $agent 不存在"; ExitCode = -1 }
    }

    $shortPrompt = "按文件 $PromptPath 中的任务说明与检查结果执行修复与验证。报告在 $ReportPath。完成后在提示词文件的『执行记录』里追加结论。"
    $template = @('-p', '{prompt}', '--allow-all-tools', '--no-color')
    if ($Defaults.wakeArgs) { $template = @($Defaults.wakeArgs) }
    $argList = @()
    foreach ($item in $template) {
        $argList += ([string]$item).Replace('{prompt}', $shortPrompt)
    }

    if ($DryRun) {
        Write-Log "DryRun：跳过真实唤醒，命令为 $agent $($argList -join ' ')" 'WARN'
        return @{ Woke = $false; Reason = 'DryRun'; ExitCode = 0 }
    }

    Write-Log "唤醒代理：$agent（任务 $($TaskDef.id)）"
    $exe = $command.Source
    $finalArgs = $argList
    if ($exe -match '\.ps1$') {
        # copilot 在 Windows 上是 .ps1 包装脚本，必须用 powershell 启动
        $finalArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $exe) + $argList
        $exe = 'powershell.exe'
    }
    $run = Invoke-External -Exe $exe -Arguments $finalArgs -WorkDir $script:Root -TimeoutSec 3600
    Write-Log "代理退出码 $($run.ExitCode)（任务 $($TaskDef.id)）"
    Add-Content -Path $ReportPath -Value "`r`n## 代理执行`r`n`r`n退出码：$($run.ExitCode)`r`n`r`n``````text`r`n$($run.Output)`r`n``````" -Encoding UTF8
    return @{ Woke = $true; Reason = ''; ExitCode = $run.ExitCode }
}

# ---------------------------------------------------------------- 主流程
function Invoke-Task {
    param($Config, $State, $TaskDef)

    $startedAt = Get-Date
    Write-Log "开始任务 $($TaskDef.id) $($TaskDef.name)"

    $results = New-Object System.Collections.ArrayList
    foreach ($check in $TaskDef.checks) {
        try {
            $r = Invoke-TaskCheck -TaskDef $TaskDef -Check $check -Defaults $Config.defaults
        } catch {
            $r = @{ Name = [string]$check.Name; Status = 'FAIL'; Summary = "检查异常：$($_.Exception.Message)"; Details = @(); DurationSec = 0 }
        }
        [void]$results.Add([pscustomobject]$r)
        if ($r.Output) {
            $hint = ($r.Output -split "`r?`n" |
                Where-Object { $_ -match '\[ERROR\]|BUILD FAILURE|<<< FAILURE!|Tests run: \d+, Failures: [1-9]' } |
                Select-Object -First 3) -join ' | '
            if ($hint) { Write-Log "  失败线索：$hint" 'WARN' }
        }
        Write-Log "  检查 $($r.Name) → $($r.Status)（$($r.Summary)）"
    }

    $status = 'PASS'
    if ($results | Where-Object { $_.Status -eq 'FAIL' }) { $status = 'FAIL' }
    elseif ($results | Where-Object { $_.Status -eq 'WARN' }) { $status = 'WARN' }

    $finishedAt = Get-Date
    $reportPath = Write-TaskReport -TaskDef $TaskDef -Results $results -Status $status -StartedAt $startedAt -FinishedAt $finishedAt

    $taskState = Get-TaskState -State $State -TaskId $TaskDef.id
    $failStreak = 0
    if ($status -eq 'FAIL') { $failStreak = [int]$taskState.failStreak + 1 }

    $newState = [pscustomobject]@{
        lastRunAt  = $startedAt.ToString('o')
        lastStatus = $status
        failStreak = $failStreak
        nextDueAt  = (Get-NextDue -TaskDef $TaskDef -From $finishedAt).ToString('o')
        lastReport = $reportPath
    }
    Set-TaskState -State $State -TaskId $TaskDef.id -TaskState $newState
    Save-State -State $State

    Write-Host ""
    Write-Host ("[{0}] {1} {2} → {3}" -f $TaskDef.id, $TaskDef.name, ('-' * 10), $status) -ForegroundColor $(if ($status -eq 'PASS') { 'Green' } elseif ($status -eq 'WARN') { 'Yellow' } else { 'Red' })
    foreach ($r in $results) {
        $color = if ($r.Status -eq 'PASS') { 'Green' } elseif ($r.Status -eq 'WARN') { 'Yellow' } else { 'Red' }
        Write-Host ("  - {0,-28} {1,-5} {2}" -f $r.Name, $r.Status, $r.Summary) -ForegroundColor $color
    }
    Write-Host "  报告：$reportPath" -ForegroundColor DarkGray

    # 是否唤醒
    $policy = $TaskDef.wakePolicy
    if ($WakePolicy) { $policy = $WakePolicy }
    if (-not $policy) { $policy = 'fail' }

    $shouldWake = $false
    if ($Wake) {
        if ($policy -eq 'always') { $shouldWake = $true }
        elseif ($policy -eq 'fail' -and $status -ne 'PASS') { $shouldWake = $true }
    }

    if ($shouldWake) {
        $limit = 3
        if ($Config.defaults.maxWakesPerDay) { $limit = [int]$Config.defaults.maxWakesPerDay }
        if ($MaxWakesPerDay -ge 0) { $limit = $MaxWakesPerDay }
        $today = (Get-Date).ToString('yyyy-MM-dd')
        if ($null -eq $State.wake) {
            $State | Add-Member -NotePropertyName wake -NotePropertyValue ([pscustomobject]@{ date = $today; count = 0 }) -Force
        }
        if ($State.wake.date -ne $today) {
            $State.wake.date = $today
            $State.wake.count = 0
        }
        if ([int]$State.wake.count -ge $limit) {
            Write-Host "  今日唤醒已达上限（$limit 次），仅保留提示词" -ForegroundColor Yellow
            Write-Log "跳过唤醒：今日已达上限 $limit" 'WARN'
        } else {
            $promptPath = Join-Path $script:PendingDir ("{0}-{1}.prompt.md" -f $startedAt.ToString('yyyyMMdd-HHmmss'), $TaskDef.id)
            New-AgentPrompt -TaskDef $TaskDef -Results $results -Status $status -ReportPath $reportPath -PromptPath $promptPath | Out-Null
            Write-Host "  提示词：$promptPath" -ForegroundColor DarkGray
            $wakeResult = Invoke-AgentWake -TaskDef $TaskDef -PromptPath $promptPath -ReportPath $reportPath -Defaults $Config.defaults
            if ($wakeResult.Woke) {
                $State.wake.count = [int]$State.wake.count + 1
                Save-State -State $State
            }
        }
    } elseif ($Wake) {
        Write-Host "  按策略（$policy）不唤醒" -ForegroundColor DarkGray
    }

    return $status
}

function Show-Plan {
    param($Config, $State)
    Write-Host ""
    Write-Host "Fangke Autopilot 任务计划（仓库：$script:Root）" -ForegroundColor Cyan
    Write-Host ""
    $fmt = "{0,-5} {1,-32} {2,-9} {3,5} {4,-6} {5,-8} {6}"
    Write-Host ($fmt -f 'ID', '任务', '区域', '周期d', '状态', '到期', '上次执行')
    Write-Host ('-' * 110)
    foreach ($t in ($Config.tasks | Sort-Object priority)) {
        $st = Get-TaskState -State $State -TaskId $t.id
        $due = Test-Due -TaskDef $t -TaskState $st
        $dueText = if ($due) { '现在' } else {
            try { ([datetime]::Parse($st.nextDueAt)).ToString('MM-dd HH:mm') } catch { '-' }
        }
        $lastText = if ($st.lastRunAt) { ([datetime]::Parse($st.lastRunAt)).ToString('MM-dd HH:mm') } else { '从未' }
        $statusText = if ($st.lastStatus) { $st.lastStatus } else { '-' }
        $color = if ($due) { 'Yellow' } else { 'Gray' }
        Write-Host ($fmt -f $t.id, $t.name, $t.area, $t.intervalDays, $statusText, $dueText, $lastText) -ForegroundColor $color
    }
    Write-Host ""
    $dueCount = 0
    foreach ($t in $Config.tasks) {
        if (Test-Due -TaskDef $t -TaskState (Get-TaskState -State $State -TaskId $t.id)) { $dueCount++ }
    }
    Write-Host "到期任务：$dueCount / $($Config.tasks.Count)" -ForegroundColor Cyan
    Write-Host "执行单个任务：.\runner.ps1 -Mode run -Task T04 [-Wake]" -ForegroundColor DarkGray
    Write-Host "自动执行到期任务：.\runner.ps1 -Mode auto -MaxTasks 1 [-Wake]" -ForegroundColor DarkGray
}

function Show-Report {
    $files = Get-ChildItem -Path $script:RunsDir -Filter '*.md' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 12
    if ($null -eq $files -or $files.Count -eq 0) {
        Write-Host "还没有执行记录。" -ForegroundColor Yellow
        return
    }
    Write-Host ""
    Write-Host "最近执行记录" -ForegroundColor Cyan
    foreach ($file in $files) {
        $first = Get-Content -Path $file.FullName -TotalCount 6 -Encoding UTF8 -ErrorAction SilentlyContinue
        $title = ($first | Where-Object { $_ -match '^# ' } | Select-Object -First 1) -replace '^# ', ''
        $conclusion = ($first | Where-Object { $_ -match '结论' } | Select-Object -First 1) -replace '- 结论：', ''
        Write-Host ("  {0}  {1,-40} {2}" -f $file.LastWriteTime.ToString('MM-dd HH:mm'), $title, $conclusion)
    }
    Write-Host ""
    Write-Host "完整报告目录：$script:RunsDir" -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- 入口
$config = Get-Config
$state = Get-State

if ($Mode -eq 'plan') {
    Show-Plan -Config $config -State $state
    return
}
if ($Mode -eq 'report') {
    Show-Report
    return
}

# 防并发：锁文件 2 小时视为过期
if (Test-Path $script:LockPath) {
    $lockAge = (Get-Date) - (Get-Item $script:LockPath).LastWriteTime
    if ($lockAge.TotalHours -lt 2) {
        Write-Host "已有 autopilot 在运行（锁：$script:LockPath，$([int]$lockAge.TotalMinutes) 分钟前），本次退出。" -ForegroundColor Yellow
        return
    }
    Write-Log "发现过期锁，接管执行" 'WARN'
    Remove-Item $script:LockPath -Force -ErrorAction SilentlyContinue
}
Set-Content -Path $script:LockPath -Value ("pid={0}; at={1}" -f $PID, (Get-Date -Format 'o')) -Encoding UTF8

try {
    if ($Mode -eq 'run') {
        if (-not $Task) { throw "需要 -Task，例如 -Task T04（可用 -Mode plan 查看任务列表）" }
        $taskDef = $config.tasks | Where-Object { $_.id -eq $Task }
        if ($null -eq $taskDef) { throw "未知任务：$Task" }
        Invoke-Task -Config $config -State $state -TaskDef $taskDef | Out-Null
        return
    }

    if ($Mode -eq 'auto') {
        $due = New-Object System.Collections.ArrayList
        foreach ($t in ($config.tasks | Sort-Object priority)) {
            if (Test-Due -TaskDef $t -TaskState (Get-TaskState -State $state -TaskId $t.id)) { [void]$due.Add($t) }
        }
        if ($due.Count -eq 0) {
            Write-Host "没有到期任务。" -ForegroundColor Green
            Write-Log "no due tasks"
            return
        }
        $limit = 1
        if ($MaxTasks -gt 0) { $limit = $MaxTasks }
        $take = [Math]::Min($limit, $due.Count)
        Write-Host "到期 $($due.Count) 个，执行前 $take 个。" -ForegroundColor Cyan
        for ($i = 0; $i -lt $take; $i++) {
            Invoke-Task -Config $config -State $state -TaskDef $due[$i] | Out-Null
        }
        return
    }

    if ($Mode -eq 'all') {
        $ordered = @($config.tasks | Sort-Object priority)
        if ($MaxTasks -gt 0 -and $MaxTasks -lt $ordered.Count) { $ordered = $ordered[0..($MaxTasks - 1)] }
        Write-Host ""
        Write-Host "一次性执行全部 $($ordered.Count) 个任务（忽略到期时间）" -ForegroundColor Cyan
        Write-Log "run-all start tasks=$($ordered.Count)"

        $summary = New-Object System.Collections.ArrayList
        $startedAt = Get-Date
        foreach ($t in $ordered) {
            $status = Invoke-Task -Config $config -State $state -TaskDef $t
            [void]$summary.Add([pscustomobject]@{ Id = $t.id; Name = $t.name; Area = $t.area; Status = $status })
        }
        $finishedAt = Get-Date

        Write-Host ""
        Write-Host "===== 汇总 =====" -ForegroundColor Cyan
        foreach ($s in $summary) {
            $color = if ($s.Status -eq 'PASS') { 'Green' } elseif ($s.Status -eq 'WARN') { 'Yellow' } else { 'Red' }
            Write-Host ("  {0,-5} {1,-34} {2,-9} {3}" -f $s.Id, $s.Name, $s.Area, $s.Status) -ForegroundColor $color
        }
        $failed = @($summary | Where-Object { $_.Status -eq 'FAIL' }).Count
        $warned = @($summary | Where-Object { $_.Status -eq 'WARN' }).Count
        Write-Host ("  合计：{0} 个任务，FAIL {1}，WARN {2}，耗时 {3} 秒" -f $summary.Count, $failed, $warned,
            [int]($finishedAt - $startedAt).TotalSeconds) -ForegroundColor Cyan

        $lines = New-Object System.Collections.ArrayList
        [void]$lines.Add("# 一次性执行汇总（$($startedAt.ToString('yyyy-MM-dd HH:mm:ss'))）")
        [void]$lines.Add('')
        [void]$lines.Add('| 任务 | 名称 | 区域 | 结论 |')
        [void]$lines.Add('|---|---|---|---|')
        foreach ($s in $summary) { [void]$lines.Add("| $($s.Id) | $($s.Name) | $($s.Area) | $($s.Status) |") }
        [void]$lines.Add('')
        [void]$lines.Add("耗时 $([int]($finishedAt - $startedAt).TotalSeconds) 秒；FAIL $failed；WARN $warned。")
        $summaryPath = Join-Path $script:RunsDir ("{0}-ALL.md" -f $startedAt.ToString('yyyyMMdd-HHmmss'))
        Set-Content -Path $summaryPath -Value ($lines -join "`r`n") -Encoding UTF8
        Write-Host "  汇总报告：$summaryPath" -ForegroundColor DarkGray
        Write-Log "run-all finished failed=$failed warned=$warned"
        return
    }
} finally {
    Remove-Item $script:LockPath -Force -ErrorAction SilentlyContinue
}
