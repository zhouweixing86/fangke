#Requires -Version 5.1
<#
.SYNOPSIS
    把 autopilot 注册成 Windows 计划任务（无需管理员，注册到当前用户）。

.EXAMPLE
    # 每天 09:10 跑一个到期任务，只做检查并出报告（不唤醒代理）
    .\register-schedule.ps1

.EXAMPLE
    # 每天 09:10 跑检查，并按 tasks.json 的 wakePolicy 唤醒代理修复
    .\register-schedule.ps1 -EnableWake

.EXAMPLE
    .\register-schedule.ps1 -Action status
    .\register-schedule.ps1 -Action remove
#>
param(
    [ValidateSet('install', 'remove', 'status')]
    [string]$Action = 'install',
    [string]$Time = '09:10',
    [int]$MaxTasks = 1,
    [switch]$EnableWake,
    [string]$TaskName = 'FangkeAutopilot'
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $PSCommandPath
$runner = Join-Path $scriptDir 'runner.ps1'
if (-not (Test-Path $runner)) { throw "找不到 runner.ps1：$runner" }

switch ($Action) {
    'status' {
        # 任务不存在时 schtasks 会往 stderr 写 ERROR，这里不能让它中断脚本
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $query = & schtasks.exe /Query /TN $TaskName /V /FO LIST 2>&1
        $exists = ($LASTEXITCODE -eq 0)
        $ErrorActionPreference = $previous
        if (-not $exists) {
            Write-Host "未注册计划任务：$TaskName（当前不使用定时调度，需要时再安装）" -ForegroundColor Yellow
            Write-Host "安装：.\register-schedule.ps1 [-EnableWake]" -ForegroundColor DarkGray
            return
        }
        $query | Where-Object { $_ -match '任务名|TaskName|状态|Status|下次运行时间|Next Run Time|要运行的任务|Task To Run|上次运行时间|Last Run Time|上次结果|Last Result' }
        return
    }
    'remove' {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        & schtasks.exe /Delete /TN $TaskName /F | Out-Null
        $deleted = ($LASTEXITCODE -eq 0)
        $ErrorActionPreference = $previous
        if ($deleted) {
            Write-Host "已删除计划任务：$TaskName" -ForegroundColor Green
        } else {
            Write-Host "任务不存在：$TaskName" -ForegroundColor Yellow
        }
        return
    }
}

$arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Mode auto -MaxTasks {1}' -f $runner, $MaxTasks
if ($EnableWake) { $arguments += ' -Wake' }

Write-Host "注册计划任务：$TaskName" -ForegroundColor Cyan
Write-Host "  触发：每天 $Time"
Write-Host "  命令：powershell.exe $arguments"
Write-Host "  唤醒代理：$(if ($EnableWake) { '是（按 wakePolicy）' } else { '否（只出报告，提示词留在 .autopilot\pending）' })"

& schtasks.exe /Create /TN $TaskName /TR "powershell.exe $arguments" /SC DAILY /ST $Time /F | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "schtasks 创建失败（退出码 $LASTEXITCODE）。可尝试以当前用户手动运行：$arguments"
}

Write-Host ""
Write-Host "已创建。验证：" -ForegroundColor Green
Write-Host "  .\register-schedule.ps1 -Action status"
Write-Host "  .\runner.ps1 -Mode plan"
Write-Host ""
Write-Host "提示：计划任务在后台运行，看不到窗口。产物在 .autopilot\runs 与 .autopilot\logs\runner.log。" -ForegroundColor DarkGray
if ($EnableWake) {
    Write-Host "注意：启用唤醒会调用 copilot CLI，需要已完成登录且会消耗额度（每日上限见 tasks.json defaults.maxWakesPerDay）。" -ForegroundColor Yellow
}
