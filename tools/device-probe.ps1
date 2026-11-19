#Requires -Version 5.1
<#
.SYNOPSIS
    RV1109 系列门禁终端（含 YC-P208）局域网 HTTP 联调探测脚本。

.DESCRIPTION
    只做读取与配置类调用，不做删除操作。对应 docs/08-设备联调与心跳配置指南.md 的 3.0 ~ 3.6 步。

    -Action login     验证连通性与密码
    -Action params    打印设备全部参数（建立基线）
    -Action version   读取固件版本（W0 V3 依赖）
    -Action heartbeat 让设备把心跳推到 -PlatformIp:-PlatformPort
    -Action callback  让设备把通行记录推到 -PlatformIp:-PlatformPort
    -Action open      远程开门（保安兜底验证）
    -Action all       依次执行 login → version → params

.EXAMPLE
    .\device-probe.ps1 -Device 192.168.1.135 -Action params

.EXAMPLE
    .\device-probe.ps1 -Device 192.168.1.135 -Action heartbeat -PlatformIp 192.168.1.50

.NOTES
    设备默认密码 123456、默认端口 8091。协议见 docs/06-设备对接协议-局域网HTTP.md。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Device,

    [string]$Password = '123456',

    [ValidateSet('login', 'params', 'version', 'heartbeat', 'callback', 'open', 'all')]
    [string]$Action = 'login',

    [string]$PlatformIp,

    [int]$PlatformPort = 18080,

    [string]$PathPrefix = '',

    [int]$DevicePort = 8091,

    [int]$TimeoutSec = 10
)

$ErrorActionPreference = 'Stop'
$base = "http://${Device}:${DevicePort}"

function Invoke-DeviceApi {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [hashtable]$Body
    )

    $url = "$base/$Path"
    $json = $Body | ConvertTo-Json -Depth 6 -Compress
    Write-Verbose "POST $url"

    try {
        $resp = Invoke-RestMethod -Uri $url -Method Post -ContentType 'application/json; charset=utf-8' `
            -Body ([Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec $TimeoutSec
    }
    catch {
        Write-Host "  [失败] $Path -> $($_.Exception.Message)" -ForegroundColor Red
        throw
    }

    if ($resp.result -ne 0) {
        Write-Host "  [设备返回错误] $Path -> result=$($resp.result) message=$($resp.message)" -ForegroundColor Yellow
    }
    else {
        Write-Host "  [成功] $Path" -ForegroundColor Green
    }
    return $resp
}

function Assert-PlatformIp {
    if ([string]::IsNullOrWhiteSpace($PlatformIp)) {
        throw '该动作需要 -PlatformIp（接收心跳/记录的那台机器的地址）'
    }
}

Write-Host "设备: $Device`:$DevicePort" -ForegroundColor Cyan

switch ($Action) {
    'login' {
        Invoke-DeviceApi -Path 'deviceLogin' -Body @{ password = $Password } | ConvertTo-Json
    }

    'version' {
        Invoke-DeviceApi -Path 'getDeviceVersion' -Body @{ password = $Password } | ConvertTo-Json -Depth 6
    }

    'params' {
        $r = Invoke-DeviceApi -Path 'getDeviceParameter' -Body @{ password = $Password }
        $r.data | ConvertTo-Json -Depth 6
    }

    'heartbeat' {
        Assert-PlatformIp
        $ip = "http://${PlatformIp}:${PlatformPort}${PathPrefix}/heartBeat"
        Write-Host "心跳目标: $ip（heartBeatEnable=0 为开）" -ForegroundColor Cyan
        $body = @{
            password = $Password
            data     = @{ heartBeatEnable = 0; heartBeatIp = $ip }
        }
        Invoke-DeviceApi -Path 'setDeviceParameter' -Body $body | ConvertTo-Json
    }

    'callback' {
        Assert-PlatformIp
        $ip = "http://${PlatformIp}:${PlatformPort}${PathPrefix}/getrecord/"
        Write-Host "记录回调: $ip（platformEnable=0 为开）" -ForegroundColor Cyan
        $body = @{
            password       = $Password
            platformEnable = 0
            platformIp     = $ip
        }
        Invoke-DeviceApi -Path 'setIdentifyCallBck' -Body $body | ConvertTo-Json
    }

    'open' {
        Invoke-DeviceApi -Path 'setDeviceRemoteOpen' -Body @{ password = $Password } | ConvertTo-Json
    }

    'all' {
        Invoke-DeviceApi -Path 'deviceLogin' -Body @{ password = $Password } | Out-Null
        Invoke-DeviceApi -Path 'getDeviceVersion' -Body @{ password = $Password } | ConvertTo-Json -Depth 6
        $r = Invoke-DeviceApi -Path 'getDeviceParameter' -Body @{ password = $Password }
        $r.data | ConvertTo-Json -Depth 6
    }
}
