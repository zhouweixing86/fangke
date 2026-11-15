param(
    [string]$OutputPath,
    [string]$PreviewDir,
    [double]$TargetTotal = 25000.00,
    [string]$UppercaseTotal = '人民币贰万伍仟元整',
    [string]$QuoteNumber = 'BJ-20261115-03',
    [switch]$SkipPdf
)

$ErrorActionPreference = 'Stop'

# ---------- paths ----------
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$quoteDir = Split-Path -Parent $scriptDir
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    if ([Math]::Abs($TargetTotal - 25000.00) -lt 0.005) {
        $OutputPath = Join-Path $quoteDir '访客管理系统报价单_客户版_20261115_2.5万.xlsx'
    } elseif ([Math]::Abs($TargetTotal - 35000.00) -lt 0.005) {
        $OutputPath = Join-Path $quoteDir '访客管理系统报价单_客户版_20261115.xlsx'
    } else {
        $OutputPath = Join-Path $quoteDir ("访客管理系统报价单_客户版_20261115_{0}.xlsx" -f $TargetTotal.ToString('0.##'))
    }
}
if ([string]::IsNullOrWhiteSpace($PreviewDir)) {
    $PreviewDir = Join-Path $quoteDir '预览'
}
$outDir = Split-Path -Parent $OutputPath
if (-not (Test-Path -LiteralPath $outDir)) {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
}
if (-not (Test-Path -LiteralPath $PreviewDir)) {
    New-Item -ItemType Directory -Force -Path $PreviewDir | Out-Null
}

# ---------- quote parameters ----------
$QuoteDate = '2026-11-15'
$ProjectName = '访客管理系统（入口/出口终端各 1 台 + 定制开发 + 实施服务）'
$HW_TAX = 0.13
$SW_TAX = 0.06
$IMPL_TAX = 0.06
$MaintenanceFee = 5000.00

$Hardware = @(
    [pscustomobject]@{ Name = '智能访客终端（入口/出口各 1 台）'; Spec = '入口 1 台、出口 1 台；入口用于访客刷证/扫码及员工刷脸，出口用于离场刷证/扫码；内置人脸识别、人证核验、二维码识别，含算法授权。'; Unit = '台'; Qty = 2; Price = 2260.00; Note = '' },
    [pscustomobject]@{ Name = '立柱'; Spec = '落地安装支架'; Unit = '根'; Qty = 2; Price = 150.00; Note = '' }
)

$Software = @(
    [pscustomobject]@{ Name = '访客管理系统定制开发'; Spec = '按确认的《功能需求清单》定制开发，含访客端（含现场自助登记/Kiosk）、员工端、保安工作台、管理端、定时任务及接口服务；含双设备下发、动态二维码、访客类型/车牌/同行人、备用审批人。'; Unit = '套'; Qty = 1; Price = 0.00; Note = '' }
)

$Implementation = @(
    [pscustomobject]@{ Name = '系统实施服务'; Spec = '部署上线、基础数据初始化、入口/出口双设备方向配置与名单对账、门卫室 Kiosk 页面部署（客户现有电脑）、现场培训、试运行陪跑；不含网络布线、电源改造。'; Unit = '项'; Qty = 1; Price = 5000.00; Note = '' },
    [pscustomobject]@{ Name = '安装调试'; Spec = '终端现场安装、调试、基础辅材'; Unit = '台'; Qty = 2; Price = 300.00; Note = '' }
)

function Round2([double]$Value) {
    return [Math]::Round($Value, 2, [System.MidpointRounding]::AwayFromZero)
}

$hwNet = Round2 (($Hardware | ForEach-Object { [double]$_.Qty * [double]$_.Price } | Measure-Object -Sum).Sum)
$hwTax = Round2 ($hwNet * $HW_TAX)
$hwGross = Round2 ($hwNet + $hwTax)

$implNet = Round2 (($Implementation | ForEach-Object { [double]$_.Qty * [double]$_.Price } | Measure-Object -Sum).Sum)
$implTax = Round2 ($implNet * $IMPL_TAX)
$implGross = Round2 ($implNet + $implTax)

$swNet = Round2 (($TargetTotal - $hwGross - $implGross) / (1 + $SW_TAX))
$swTax = Round2 ($swNet * $SW_TAX)
$swGross = Round2 ($swNet + $swTax)
$Software[0].Price = $swNet

$sumNet = Round2 ($hwNet + $swNet + $implNet)
$sumTax = Round2 ($hwTax + $swTax + $implTax)
$sumGross = Round2 ($hwGross + $swGross + $implGross)

if ([string]::IsNullOrWhiteSpace($UppercaseTotal)) {
    throw '请提供含税总价大写金额。'
}
if ($swNet -le 0) {
    throw ("目标总价过低：硬件含税 {0:N2} + 实施含税 {1:N2} 已超过目标 {2:N2}" -f $hwGross, $implGross, $TargetTotal)
}
if ([Math]::Abs($sumGross - $TargetTotal) -gt 0.005) {
    throw ("金额平衡失败：含税合计 {0:N2}，目标 {1:N2}" -f $sumGross, $TargetTotal)
}

# ---------- colors ----------
function Get-Color([int]$r, [int]$g, [int]$b) {
    return ($r + ($g * 256) + ($b * 65536))
}

$script:ColorHeader = Get-Color 31 78 121
$script:ColorSection = Get-Color 217 226 243
$script:ColorSubtotal = Get-Color 242 242 242
$script:ColorGross = Get-Color 252 228 214
$script:ColorYellow = Get-Color 255 242 204
$script:ColorNote = Get-Color 248 248 248
$script:ColorBorder = Get-Color 166 166 166
$script:ColorWhite = Get-Color 255 255 255
$script:ColorGray = Get-Color 89 89 89

# ---------- excel helpers ----------
function Format-Range {
    param(
        $Range,
        [double]$Size = 9,
        [bool]$Bold = $false,
        [object]$Fill = $null,
        [object]$FontColor = $null,
        [int]$HAlign = -4131,
        [int]$VAlign = -4108,
        [bool]$Wrap = $true,
        [string]$NumberFormat = '',
        [int]$Indent = 0
    )
    $Range.Font.Name = '微软雅黑'
    $Range.Font.Size = $Size
    $Range.Font.Bold = $Bold
    if ($null -ne $FontColor) { $Range.Font.Color = [int]$FontColor }
    if ($null -ne $Fill) {
        $Range.Interior.Color = [int]$Fill
    } else {
        $Range.Interior.Pattern = -4142
    }
    $Range.HorizontalAlignment = $HAlign
    $Range.VerticalAlignment = $VAlign
    $Range.WrapText = $Wrap
    if ($NumberFormat -ne '') { $Range.NumberFormat = $NumberFormat }
    if ($Indent -gt 0) { $Range.IndentLevel = $Indent }
}

function Set-Border {
    param(
        $Range,
        [int]$Color = 0,
        [int]$Weight = 2,
        [int]$Style = 1
    )
    $Range.Borders.LineStyle = $Style
    $Range.Borders.Weight = $Weight
    if ($Color -ne 0) { $Range.Borders.Color = $Color }
}

function Merge-Set {
    param(
        $ws,
        [string]$Address,
        $Value,
        [int]$HAlign = -4131,
        [bool]$Bold = $false,
        [double]$Size = 9,
        [object]$Fill = $null,
        [object]$FontColor = $null,
        [int]$VAlign = -4108,
        [bool]$Wrap = $true,
        [string]$NumberFormat = '',
        [int]$Indent = 0
    )
    $range = $ws.Range($Address)
    if ($Address.Contains(':')) { $range.Merge() | Out-Null }
    $range.Cells.Item(1, 1).Value2 = $Value
    Format-Range -Range $range -HAlign $HAlign -Bold $Bold -Size $Size -Fill $Fill -FontColor $FontColor -VAlign $VAlign -Wrap $Wrap -NumberFormat $NumberFormat -Indent $Indent | Out-Null
    Write-Output -NoEnumerate $range
}

function Set-Cell {
    param(
        $ws,
        [int]$Row,
        [int]$Col,
        $Value,
        [bool]$Formula = $false,
        [int]$HAlign = -4131,
        [bool]$Bold = $false,
        [double]$Size = 9,
        [object]$Fill = $null,
        [object]$FontColor = $null,
        [string]$NumberFormat = '',
        [bool]$Wrap = $true,
        [int]$VAlign = -4108
    )
    $cell = $ws.Cells.Item($Row, $Col)
    try {
        if ($Formula) {
            $cell.Formula = [string]$Value
        } elseif ($null -eq $Value) {
            $cell.Value2 = $null
        } elseif ($Value -is [string]) {
            $cell.Value2 = [string]$Value
        } else {
            $cell.Formula = ([double]$Value).ToString([System.Globalization.CultureInfo]::InvariantCulture)
        }
    } catch {
        $valueType = if ($null -eq $Value) { 'null' } else { $Value.GetType().FullName }
        throw ("Set-Cell 失败：行 {0}，列 {1}，值 '{2}'，类型 {3}；{4}" -f $Row, $Col, $Value, $valueType, $_.Exception.Message)
    }
    Format-Range -Range $cell -HAlign $HAlign -Bold $Bold -Size $Size -Fill $Fill -FontColor $FontColor -VAlign $VAlign -Wrap $Wrap -NumberFormat $NumberFormat | Out-Null
}

function New-SectionHeader {
    param($ws, [int]$Row, [string]$Text)
    $range = Merge-Set -ws $ws -Address "A${Row}:H${Row}" -Value $Text -HAlign -4131 -Bold $true -Size 10 -Fill $script:ColorSection -Indent 1 -Wrap $false
    $range.RowHeight = 22
    $range.Borders.Item(9).LineStyle = 1
    $range.Borders.Item(9).Weight = -4138
    $range.Borders.Item(9).Color = $script:ColorBorder
}

function New-TableHeader {
    param($ws, [int]$Row, [string[]]$Headers)
    for ($i = 1; $i -le 8; $i++) {
        $cell = $ws.Cells.Item($Row, $i)
        $cell.Value2 = $Headers[$i - 1]
        Format-Range -Range $cell -HAlign -4108 -Bold $true -Size 9 -Fill $script:ColorHeader -FontColor $script:ColorWhite -Wrap $true
    }
    $range = $ws.Range("A${Row}:H${Row}")
    $range.RowHeight = 24
    Set-Border -Range $range -Color $script:ColorBorder -Weight 2
}

function New-ItemRow {
    param($ws, [int]$Row, [int]$Seq, $Item, [double]$Height = 26)
    Set-Cell -ws $ws -Row $Row -Col 1 -Value $Seq -HAlign -4108
    Set-Cell -ws $ws -Row $Row -Col 2 -Value $Item.Name -HAlign -4131
    Set-Cell -ws $ws -Row $Row -Col 3 -Value $Item.Spec -HAlign -4131 -Wrap $true -VAlign -4160
    Set-Cell -ws $ws -Row $Row -Col 4 -Value $Item.Unit -HAlign -4108
    Set-Cell -ws $ws -Row $Row -Col 5 -Value $Item.Qty -HAlign -4108 -NumberFormat '0'
    Set-Cell -ws $ws -Row $Row -Col 6 -Value $Item.Price -HAlign -4152 -NumberFormat '#,##0.00'
    Set-Cell -ws $ws -Row $Row -Col 7 -Value ("=E{0}*F{0}" -f $Row) -Formula $true -HAlign -4152 -NumberFormat '#,##0.00'
    Set-Cell -ws $ws -Row $Row -Col 8 -Value $Item.Note -HAlign -4131
    $range = $ws.Range("A${Row}:H${Row}")
    $range.RowHeight = $Height
    Set-Border -Range $range -Color $script:ColorBorder -Weight 2
}

function New-SubtotalRow {
    param($ws, [int]$Row, [string]$Label, [string]$Formula, [object]$Fill = $null, [bool]$Bold = $false)
    Merge-Set -ws $ws -Address "A${Row}:F${Row}" -Value $Label -HAlign -4152 -Bold $Bold -Size 9 -Fill $Fill -Wrap $false | Out-Null
    Set-Cell -ws $ws -Row $Row -Col 7 -Value $Formula -Formula $true -HAlign -4152 -Bold $Bold -Size 9 -Fill $Fill -NumberFormat '#,##0.00'
    Set-Cell -ws $ws -Row $Row -Col 8 -Value '' -Fill $Fill
    $range = $ws.Range("A${Row}:H${Row}")
    Set-Border -Range $range -Color $script:ColorBorder -Weight 2
    $range.RowHeight = 18
}

function New-SummaryRow {
    param($ws, [int]$Row, [string]$Label, $Rate, [string]$NetFormula, [string]$TaxFormula, [string]$GrossFormula, [object]$Fill = $null, [bool]$Bold = $false)
    Merge-Set -ws $ws -Address "A${Row}:B${Row}" -Value $Label -HAlign -4131 -Bold $Bold -Size 9 -Fill $Fill | Out-Null
    $rateCell = $ws.Cells.Item($Row, 3)
    if ($null -ne $Rate) { $rateCell.Formula = ([double]$Rate).ToString([System.Globalization.CultureInfo]::InvariantCulture) }
    Format-Range -Range $rateCell -HAlign -4108 -Bold $Bold -Size 9 -Fill $Fill -NumberFormat '0%'
    $netRange = $ws.Range("D${Row}:E${Row}")
    $netRange.Merge() | Out-Null
    $netRange.Cells.Item(1, 1).Formula = $NetFormula
    Format-Range -Range $netRange -HAlign -4152 -Bold $Bold -Size 9 -Fill $Fill -NumberFormat '#,##0.00'
    Set-Cell -ws $ws -Row $Row -Col 6 -Value $TaxFormula -Formula $true -HAlign -4152 -Bold $Bold -Size 9 -Fill $Fill -NumberFormat '#,##0.00'
    $grossRange = $ws.Range("G${Row}:H${Row}")
    $grossRange.Merge() | Out-Null
    $grossRange.Cells.Item(1, 1).Formula = $GrossFormula
    Format-Range -Range $grossRange -HAlign -4152 -Bold $Bold -Size 9 -Fill $Fill -NumberFormat '#,##0.00'
    $range = $ws.Range("A${Row}:H${Row}")
    Set-Border -Range $range -Color $script:ColorBorder -Weight 2
    $range.RowHeight = 20
}

function New-TermRow {
    param($ws, [int]$Row, [string]$Text, [double]$Height = 24)
    $range = Merge-Set -ws $ws -Address "A${Row}:H${Row}" -Value $Text -HAlign -4131 -VAlign -4160 -Wrap $true -Size 9
    $range.RowHeight = $Height
}

function New-MaintRow {
    param($ws, [int]$Row, [string]$Item, [string]$Term, [string]$Fee, [string]$Desc)
    Merge-Set -ws $ws -Address "A${Row}:B${Row}" -Value $Item -HAlign -4131 -Bold $true -Size 9 | Out-Null
    Set-Cell -ws $ws -Row $Row -Col 3 -Value $Term -HAlign -4131 -Size 9
    Merge-Set -ws $ws -Address "D${Row}:E${Row}" -Value $Fee -HAlign -4108 -Size 9 | Out-Null
    Merge-Set -ws $ws -Address "F${Row}:H${Row}" -Value $Desc -HAlign -4131 -Size 9 | Out-Null
    $range = $ws.Range("A${Row}:H${Row}")
    Set-Border -Range $range -Color $script:ColorBorder -Weight 2
    $range.RowHeight = 30
}

function New-ScopeGroup {
    param($ws, [int]$Row, [string]$Title)
    $range = Merge-Set -ws $ws -Address "A${Row}:E${Row}" -Value $Title -HAlign -4131 -Bold $true -Size 10 -Fill $script:ColorSection -Indent 1 -Wrap $false
    $range.RowHeight = 20
    $range.Borders.Item(9).LineStyle = 1
    $range.Borders.Item(9).Weight = -4138
    $range.Borders.Item(9).Color = $script:ColorBorder
}

function New-ScopeRow {
    param($ws, [int]$Row, [int]$Seq, [string]$Module, [string]$Page, [string]$Function, [string]$Desc)
    Set-Cell -ws $ws -Row $Row -Col 1 -Value $Seq -HAlign -4108 -Size 9.0
    Set-Cell -ws $ws -Row $Row -Col 2 -Value $Module -HAlign -4131 -Size 9.0
    Set-Cell -ws $ws -Row $Row -Col 3 -Value $Page -HAlign -4108 -Size 9.0
    Set-Cell -ws $ws -Row $Row -Col 4 -Value $Function -HAlign -4131 -Size 9.0
    Set-Cell -ws $ws -Row $Row -Col 5 -Value $Desc -HAlign -4131 -Size 9.0
    $range = $ws.Range("A${Row}:E${Row}")
    $range.RowHeight = 18
    Set-Border -Range $range -Color $script:ColorBorder -Weight 2
}

# ---------- scope data ----------
$ScopeGroups = @(
    [pscustomobject]@{
        Title = '访客端（P-V）'
        Module = '访客端'
        Rows = @(
            [pscustomobject]@{ Page = 'P-V-01'; Function = '访客首页'; Desc = '预约入口、进行中预约状态、到访须知' },
            [pscustomobject]@{ Page = 'P-V-02'; Function = '个人信息告知与同意'; Desc = '告知书展示、同意勾选与时间戳留痕' },
            [pscustomobject]@{ Page = 'P-V-03'; Function = '预约表单'; Desc = '访客/被访/来访信息、访客类型、车牌选填、同行人、提交校验' },
            [pscustomobject]@{ Page = 'P-V-04'; Function = '提交结果'; Desc = '预约单号、关键信息与后续指引' },
            [pscustomobject]@{ Page = 'P-V-05'; Function = '我的预约'; Desc = '状态筛选、预约列表与空态' },
            [pscustomobject]@{ Page = 'P-V-06'; Function = '预约详情与通行码'; Desc = '动态二维码、审批进度、撤回/取消' },
            [pscustomobject]@{ Page = 'P-V-07'; Function = '现场自助登记'; Desc = '门口扫码/门卫室 Kiosk 自助填表、实时下发、催办' }
        )
    },
    [pscustomobject]@{
        Title = '员工端（P-E）'
        Module = '员工端'
        Rows = @(
            [pscustomobject]@{ Page = 'P-E-01'; Function = '员工首页'; Desc = '待办角标、快捷入口、消息设置' },
            [pscustomobject]@{ Page = 'P-E-02'; Function = '待我审批'; Desc = '待办列表、筛选、等待时长' },
            [pscustomobject]@{ Page = 'P-E-03'; Function = '审批详情'; Desc = '通过/驳回/转办/加签、审批链' },
            [pscustomobject]@{ Page = 'P-E-04'; Function = '邀请访客'; Desc = '代邀表单、有效期与告知确认' },
            [pscustomobject]@{ Page = 'P-E-05'; Function = '我的访客'; Desc = '将访/在访/历史' },
            [pscustomobject]@{ Page = 'P-E-06'; Function = '电脑端扫码登录'; Desc = '一次性票据、扫码确认、登录态' },
            [pscustomobject]@{ Page = 'P-E-07'; Function = '电脑端待办'; Desc = '待办列表与审批详情联动' }
        )
    },
    [pscustomobject]@{
        Title = '保安工作台（P-F）'
        Module = '保安工作台'
        Rows = @(
            [pscustomobject]@{ Page = 'P-F-02'; Function = '登记进度看板'; Desc = '入口/出口授权进度、催办、放行结论' },
            [pscustomobject]@{ Page = 'P-F-03'; Function = '在访列表'; Desc = '在访/离场/超时、异常离场、签离、远程开门' },
            [pscustomobject]@{ Page = 'P-F-04'; Function = '人工开门'; Desc = '员工刷脸失败/访客凭证不可用、授权校验、记录' },
            [pscustomobject]@{ Page = 'P-F-05'; Function = '自助登记待处理'; Desc = '待处理列表、等待时长、催办、查看详情' }
        )
    },
    [pscustomobject]@{
        Title = '管理端（P-M）'
        Module = '管理端'
        Rows = @(
            [pscustomobject]@{ Page = 'P-M-01'; Function = '设备状态看板'; Desc = '入口/出口在线状态、心跳、名单一致性、远程操作' },
            [pscustomobject]@{ Page = 'P-M-02'; Function = '员工列表'; Desc = '员工查询、照片/授权状态、筛选' },
            [pscustomobject]@{ Page = 'P-M-03'; Function = '员工编辑'; Desc = '员工信息、人脸照片、保存并下发' },
            [pscustomobject]@{ Page = 'P-M-04'; Function = '员工批量导入'; Desc = 'Excel+照片包、逐行校验、预览导入' },
            [pscustomobject]@{ Page = 'P-M-05'; Function = '审批配置'; Desc = '审批级数、审批人、备用审批人、催办/代邀参数' },
            [pscustomobject]@{ Page = 'P-M-06'; Function = '预约查询'; Desc = '多条件查询、访客类型/车牌筛选、详情、撤销、导出' },
            [pscustomobject]@{ Page = 'P-M-07'; Function = '通行记录'; Desc = '通行流水、进出方向、异常离场、抓拍/证件照、导出' },
            [pscustomobject]@{ Page = 'P-M-08'; Function = '黑名单'; Desc = '证件/手机号、原因、生效期' },
            [pscustomobject]@{ Page = 'P-M-09'; Function = '报表'; Desc = '访客/审批/代邀/通行/设备报表与导出' },
            [pscustomobject]@{ Page = 'P-M-10'; Function = '审计日志'; Desc = '操作审计、原始快照、不可修改' },
            [pscustomobject]@{ Page = 'P-M-11'; Function = '账号与角色'; Desc = '用户、角色、权限分配、保安菜单隔离' },
            [pscustomobject]@{ Page = 'P-M-12'; Function = '系统配置'; Desc = '同行人身份证开关、访客类型、二维码/预约/数据/设备参数' }
        )
    },
    [pscustomobject]@{
        Title = '定时任务'
        Module = '定时任务'
        Rows = @(
            [pscustomobject]@{ Page = 'JOB-01~03'; Function = '预约过期、异常离场、审批催办'; Desc = '按配置频率执行' },
            [pscustomobject]@{ Page = 'JOB-04~06'; Function = '名单对账、过期名单、照片清理'; Desc = '每日定时执行' },
            [pscustomobject]@{ Page = 'JOB-07~09'; Function = '有效期提醒、记录归档、设备校时'; Desc = '每日/每月定时执行' }
        )
    },
    [pscustomobject]@{
        Title = '接口服务'
        Module = '接口服务'
        Rows = @(
            [pscustomobject]@{ Page = 'API'; Function = '平台接口'; Desc = '预约、审批、通行、设备状态' },
            [pscustomobject]@{ Page = 'DEVICE'; Function = '设备接口'; Desc = '入口/出口双设备对接、授权下发与对账' },
            [pscustomobject]@{ Page = 'THIRD'; Function = '第三方接口'; Desc = '微信小程序/企业微信/门禁等（按需，另行报价）' }
        )
    }
)

# ---------- excel ----------
$excel = $null
$wb = $null
$ws = $null
$ws2 = $null
$wbCheck = $null

try {
    $excel = New-Object -ComObject Excel.Application
    $excel.Visible = $false
    $excel.DisplayAlerts = $false
    $excel.ScreenUpdating = $false

    $wb = $excel.Workbooks.Add()
    $excel.Calculation = -4105
    while ($wb.Worksheets.Count -gt 1) {
        $wb.Worksheets.Item($wb.Worksheets.Count).Delete()
    }
    $ws = $wb.Worksheets.Item(1)
    $ws.Name = '报价单'
    $ws.Cells.Font.Name = '微软雅黑'
    $ws.Cells.Font.Size = 9
    $ws.Tab.Color = $script:ColorHeader

    # title
    $titleRange = Merge-Set -ws $ws -Address 'A1:H1' -Value '访客管理系统报价单' -HAlign -4108 -Bold $true -Size 18 -Fill $script:ColorHeader -FontColor $script:ColorWhite -Wrap $false
    $titleRange.RowHeight = 38

    # header info
    Merge-Set -ws $ws -Address 'A2:B2' -Value '报价方：' -HAlign -4152 -Bold $true -Size 9 | Out-Null
    Merge-Set -ws $ws -Address 'C2:E2' -Value '' -HAlign -4131 -Size 9 -Fill $script:ColorYellow | Out-Null
    Set-Cell -ws $ws -Row 2 -Col 6 -Value '报价单编号：' -HAlign -4152 -Bold $true -Size 9 -Wrap $false
    Merge-Set -ws $ws -Address 'G2:H2' -Value $QuoteNumber -HAlign -4131 -Size 9 | Out-Null

    Merge-Set -ws $ws -Address 'A3:B3' -Value '客户名称：' -HAlign -4152 -Bold $true -Size 9 | Out-Null
    Merge-Set -ws $ws -Address 'C3:E3' -Value '' -HAlign -4131 -Size 9 -Fill $script:ColorYellow | Out-Null
    Set-Cell -ws $ws -Row 3 -Col 6 -Value '报价日期：' -HAlign -4152 -Bold $true -Size 9 -Wrap $false
    Merge-Set -ws $ws -Address 'G3:H3' -Value $QuoteDate -HAlign -4131 -Size 9 | Out-Null

    Merge-Set -ws $ws -Address 'A4:B4' -Value '项目名称：' -HAlign -4152 -Bold $true -Size 9 | Out-Null
    Merge-Set -ws $ws -Address 'C4:E4' -Value $ProjectName -HAlign -4131 -Size 9 | Out-Null
    Set-Cell -ws $ws -Row 4 -Col 6 -Value '有效期：' -HAlign -4152 -Bold $true -Size 9 -Wrap $false
    Merge-Set -ws $ws -Address 'G4:H4' -Value '30 天' -HAlign -4131 -Size 9 | Out-Null

    Merge-Set -ws $ws -Address 'A5:B5' -Value '联系人 / 电话：' -HAlign -4152 -Bold $true -Size 9 | Out-Null
    Merge-Set -ws $ws -Address 'C5:E5' -Value '' -HAlign -4131 -Size 9 -Fill $script:ColorYellow | Out-Null
    Set-Cell -ws $ws -Row 5 -Col 6 -Value '币种：' -HAlign -4152 -Bold $true -Size 9 -Wrap $false
    Merge-Set -ws $ws -Address 'G5:H5' -Value '人民币（元）' -HAlign -4131 -Size 9 | Out-Null

    for ($r = 2; $r -le 5; $r++) { $ws.Rows.Item($r).RowHeight = 20 }
    $ws.Rows.Item(6).RowHeight = 8

    # hardware
    New-SectionHeader -ws $ws -Row 7 -Text '一、硬件部分（税率 13%）'
    New-TableHeader -ws $ws -Row 8 -Headers @('序号', '名称', '规格/说明', '单位', '数量', '单价（不含税）', '金额（不含税）', '备注')
    $row = 9
    $seq = 1
    foreach ($item in $Hardware) {
        New-ItemRow -ws $ws -Row $row -Seq $seq -Item $item -Height 30
        $row++
        $seq++
    }
    $ws.Rows.Item(9).RowHeight = 54
    New-SubtotalRow -ws $ws -Row 11 -Label '硬件小计（不含税）' -Formula '=SUM(G9:G10)' -Fill $script:ColorSubtotal -Bold $true
    New-SubtotalRow -ws $ws -Row 12 -Label '税金（13%）' -Formula '=ROUND(G11*0.13,2)'
    New-SubtotalRow -ws $ws -Row 13 -Label '硬件含税合计' -Formula '=G11+G12' -Fill $script:ColorGross -Bold $true
    $ws.Rows.Item(14).RowHeight = 8

    # software
    New-SectionHeader -ws $ws -Row 15 -Text '二、软件部分（税率 6%）'
    New-TableHeader -ws $ws -Row 16 -Headers @('序号', '名称', '规格/说明', '单位', '数量', '单价（不含税）', '金额（不含税）', '备注')
    New-ItemRow -ws $ws -Row 17 -Seq 1 -Item $Software[0] -Height 72
    New-SubtotalRow -ws $ws -Row 18 -Label '软件小计（不含税）' -Formula '=SUM(G17:G17)' -Fill $script:ColorSubtotal -Bold $true
    New-SubtotalRow -ws $ws -Row 19 -Label '税金（6%）' -Formula '=ROUND(G18*0.06,2)'
    New-SubtotalRow -ws $ws -Row 20 -Label '软件含税合计' -Formula '=G18+G19' -Fill $script:ColorGross -Bold $true
    $ws.Rows.Item(21).RowHeight = 8

    # implementation
    New-SectionHeader -ws $ws -Row 22 -Text '三、实施服务（税率 6%）'
    New-TableHeader -ws $ws -Row 23 -Headers @('序号', '名称', '规格/说明', '单位', '数量', '单价（不含税）', '金额（不含税）', '备注')
    $row = 24
    $seq = 1
    foreach ($item in $Implementation) {
        New-ItemRow -ws $ws -Row $row -Seq $seq -Item $item -Height 28
        $row++
        $seq++
    }
    $ws.Rows.Item(24).RowHeight = 60
    New-SubtotalRow -ws $ws -Row 26 -Label '实施小计（不含税）' -Formula '=SUM(G24:G25)' -Fill $script:ColorSubtotal -Bold $true
    New-SubtotalRow -ws $ws -Row 27 -Label '税金（6%）' -Formula '=ROUND(G26*0.06,2)'
    New-SubtotalRow -ws $ws -Row 28 -Label '实施含税合计' -Formula '=G26+G27' -Fill $script:ColorGross -Bold $true
    $ws.Rows.Item(29).RowHeight = 8

    # summary
    New-SectionHeader -ws $ws -Row 30 -Text '四、报价汇总'
    Merge-Set -ws $ws -Address 'A31:B31' -Value '项目' -HAlign -4108 -Bold $true -Fill $script:ColorHeader -FontColor $script:ColorWhite | Out-Null
    Set-Cell -ws $ws -Row 31 -Col 3 -Value '税率' -HAlign -4108 -Bold $true -Fill $script:ColorHeader -FontColor $script:ColorWhite
    Merge-Set -ws $ws -Address 'D31:E31' -Value '不含税金额' -HAlign -4108 -Bold $true -Fill $script:ColorHeader -FontColor $script:ColorWhite | Out-Null
    Set-Cell -ws $ws -Row 31 -Col 6 -Value '税额' -HAlign -4108 -Bold $true -Fill $script:ColorHeader -FontColor $script:ColorWhite
    Merge-Set -ws $ws -Address 'G31:H31' -Value '含税金额' -HAlign -4108 -Bold $true -Fill $script:ColorHeader -FontColor $script:ColorWhite | Out-Null
    $ws.Range('A31:H31').RowHeight = 24
    Set-Border -Range $ws.Range('A31:H31') -Color $script:ColorBorder -Weight 2

    New-SummaryRow -ws $ws -Row 32 -Label '硬件' -Rate 0.13 -NetFormula '=G11' -TaxFormula '=G12' -GrossFormula '=G13'
    New-SummaryRow -ws $ws -Row 33 -Label '软件（定制开发）' -Rate 0.06 -NetFormula '=G18' -TaxFormula '=G19' -GrossFormula '=G20'
    New-SummaryRow -ws $ws -Row 34 -Label '实施服务（含安装调试）' -Rate 0.06 -NetFormula '=G26' -TaxFormula '=G27' -GrossFormula '=G28'
    New-SummaryRow -ws $ws -Row 35 -Label '合计' -Rate $null -NetFormula '=SUM(D32:D34)' -TaxFormula '=SUM(F32:F34)' -GrossFormula '=SUM(G32:G34)' -Fill $script:ColorGross -Bold $true

    Merge-Set -ws $ws -Address 'A36:B36' -Value '含税总价大写' -HAlign -4152 -Bold $true -Size 9 -Fill $script:ColorYellow | Out-Null
    $upperRange = Merge-Set -ws $ws -Address 'C36:H36' -Value $UppercaseTotal -HAlign -4131 -Bold $true -Size 11 -Fill $script:ColorYellow
    $upperRange.RowHeight = 26
    Set-Border -Range $ws.Range('A36:H36') -Color $script:ColorBorder -Weight 2
    $ws.Rows.Item(37).RowHeight = 8

    # maintenance
    New-SectionHeader -ws $ws -Row 38 -Text '五、质保与维护（不含在本次报价内）'
    Merge-Set -ws $ws -Address 'A39:B39' -Value '项目' -HAlign -4108 -Bold $true -Fill $script:ColorHeader -FontColor $script:ColorWhite | Out-Null
    Set-Cell -ws $ws -Row 39 -Col 3 -Value '期限' -HAlign -4108 -Bold $true -Fill $script:ColorHeader -FontColor $script:ColorWhite
    Merge-Set -ws $ws -Address 'D39:E39' -Value '费用' -HAlign -4108 -Bold $true -Fill $script:ColorHeader -FontColor $script:ColorWhite | Out-Null
    Merge-Set -ws $ws -Address 'F39:H39' -Value '说明' -HAlign -4108 -Bold $true -Fill $script:ColorHeader -FontColor $script:ColorWhite | Out-Null
    $ws.Range('A39:H39').RowHeight = 24
    Set-Border -Range $ws.Range('A39:H39') -Color $script:ColorBorder -Weight 2

    New-MaintRow -ws $ws -Row 40 -Item '硬件质保' -Term '验收合格之日起 24 个月' -Fee '已含' -Desc '非人为损坏免费维修/更换；人为损坏、自然灾害、断电及外力因素除外'
    New-MaintRow -ws $ws -Row 41 -Item '软件维护（第 1 年）' -Term '验收合格之日起 12 个月' -Fee '免费' -Desc '含系统故障排查、远程技术支持'
    New-MaintRow -ws $ws -Row 42 -Item '软件维护（第 2 年起）' -Term '按年' -Fee '5,000 元/年' -Desc '含税；可选，客户按年续签；不含新增功能开发'

    $maintNoteText = '注：5,000 元/年按含税价计（税率 6%，不含税 4,716.98 元，税额 283.02 元）；维护费不计入本次 {0:N0} 元报价。硬件 2 年质保期内免费，质保期后维保可并入年度维护或另行报价。' -f $TargetTotal
    $maintNote = Merge-Set -ws $ws -Address 'A43:H43' -Value $maintNoteText -HAlign -4131 -VAlign -4160 -Wrap $true -Size 8.5 -Fill $script:ColorNote -FontColor $script:ColorGray
    $maintNote.RowHeight = 34
    $ws.Rows.Item(44).RowHeight = 8

    # terms
    New-SectionHeader -ws $ws -Row 45 -Text '六、商务条款'
    $terms1 = @(
        '1. 报价含：硬件设备、定制软件开发、安装调试、实施服务、1 次现场培训、首年软件维护。',
        '2. 定制开发范围以双方确认的《功能需求清单》为准，超出范围的变更按变更单另行报价。',
        '3. 不含：闸机/电梯/道闸、访客人脸采集、短信/邮件、人事/办公系统对接、实体访客卡、物品/车辆登记、多租户；网络布线、电源改造及第三方接口费/授权费/年费由客户承担。',
        '4. 验收依据：按确认的功能需求清单逐项验收。'
    )
    $row = 46
    foreach ($t in $terms1) {
        New-TermRow -ws $ws -Row $row -Text $t -Height 24
        $row++
    }
    $ws.Rows.Item(48).RowHeight = 36

    Merge-Set -ws $ws -Address 'A50:D50' -Value '5. 交付期：合同签订并收到预付款后' -HAlign -4131 -VAlign -4160 -Wrap $true -Size 9 | Out-Null
    Merge-Set -ws $ws -Address 'E50:F50' -Value '' -HAlign -4108 -VAlign -4108 -Fill $script:ColorYellow | Out-Null
    Merge-Set -ws $ws -Address 'G50:H50' -Value '个工作日内' -HAlign -4131 -VAlign -4160 -Wrap $true -Size 9 | Out-Null
    $ws.Range('A50:H50').RowHeight = 24

    $terms2 = @(
        '6. 付款方式：预付 50%，验收合格后付 50%。',
        '7. 发票：硬件 13% 增值税专用发票；软件、实施服务、维护费 6% 增值税发票。',
        '8. 报价有效期：30 天。',
        '9. 本报价按硬件 13%、软件及服务 6% 计税；如税务口径调整，双方另行协商。'
    )
    $row = 51
    foreach ($t in $terms2) {
        New-TermRow -ws $ws -Row $row -Text $t -Height 24
        $row++
    }
    $ws.Rows.Item(55).RowHeight = 8

    # signature
    Merge-Set -ws $ws -Address 'A56:C56' -Value '报价方（盖章）：' -HAlign -4152 -Bold $true -Size 9 | Out-Null
    Merge-Set -ws $ws -Address 'D56:H56' -Value '' -HAlign -4131 -Fill $script:ColorYellow | Out-Null
    Set-Border -Range $ws.Range('D56:H56') -Color $script:ColorBorder -Weight 2
    $ws.Range('A56:H56').RowHeight = 34

    Merge-Set -ws $ws -Address 'A57:C57' -Value '联系人 / 电话：' -HAlign -4152 -Bold $true -Size 9 | Out-Null
    Merge-Set -ws $ws -Address 'D57:F57' -Value '' -HAlign -4131 -Fill $script:ColorYellow | Out-Null
    Set-Border -Range $ws.Range('D57:F57') -Color $script:ColorBorder -Weight 2
    Set-Cell -ws $ws -Row 57 -Col 7 -Value '日期：' -HAlign -4152 -Bold $true -Size 9 -Wrap $false
    Set-Cell -ws $ws -Row 57 -Col 8 -Value '' -HAlign -4108 -Fill $script:ColorYellow
    Set-Border -Range $ws.Range('H57:H57') -Color $script:ColorBorder -Weight 2
    $ws.Range('A57:H57').RowHeight = 28

    # widths and names
    $ws.Columns.Item('A').ColumnWidth = 5
    $ws.Columns.Item('B').ColumnWidth = 18
    $ws.Columns.Item('C').ColumnWidth = 34
    $ws.Columns.Item('D').ColumnWidth = 6
    $ws.Columns.Item('E').ColumnWidth = 6
    $ws.Columns.Item('F').ColumnWidth = 13
    $ws.Columns.Item('G').ColumnWidth = 13
    $ws.Columns.Item('H').ColumnWidth = 9

    $ws.Range('G11').Name = 'HW_Subtotal'
    $ws.Range('G12').Name = 'HW_Tax'
    $ws.Range('G13').Name = 'HW_Gross'
    $ws.Range('G18').Name = 'SW_Subtotal'
    $ws.Range('G19').Name = 'SW_Tax'
    $ws.Range('G20').Name = 'SW_Gross'
    $ws.Range('G26').Name = 'IMPL_Subtotal'
    $ws.Range('G27').Name = 'IMPL_Tax'
    $ws.Range('G28').Name = 'IMPL_Gross'
    $ws.Range('G35').Name = 'GrandTotal'

    # quote page setup
    $ws.Activate()
    $excel.ActiveWindow.DisplayGridlines = $false
    $ws.PageSetup.Orientation = 1
    $ws.PageSetup.PaperSize = 9
    $ws.PageSetup.Zoom = $false
    $ws.PageSetup.FitToPagesWide = 1
    $ws.PageSetup.FitToPagesTall = $false
    $ws.PageSetup.LeftMargin = $excel.CentimetersToPoints(1.4)
    $ws.PageSetup.RightMargin = $excel.CentimetersToPoints(1.4)
    $ws.PageSetup.TopMargin = $excel.CentimetersToPoints(1.3)
    $ws.PageSetup.BottomMargin = $excel.CentimetersToPoints(1.3)
    $ws.PageSetup.HeaderMargin = $excel.CentimetersToPoints(0.5)
    $ws.PageSetup.FooterMargin = $excel.CentimetersToPoints(0.6)
    $ws.PageSetup.CenterHorizontally = $true
    $ws.PageSetup.PrintGridlines = $false
    $ws.PageSetup.PrintArea = 'A1:H57'
    $ws.PageSetup.PrintTitleRows = '$1:$1'
    $ws.PageSetup.LeftFooter = '&"微软雅黑"&8访客管理系统报价单'
    $ws.PageSetup.RightFooter = '&"微软雅黑"&8第 &P 页 / 共 &N 页'
    $ws.ResetAllPageBreaks()
    try { $ws.Rows.Item(38).PageBreak = 1 } catch { Write-Warning "报价单手动分页失败：$($_.Exception.Message)" }

    # scope sheet
    $ws2 = $wb.Worksheets.Add()
    $ws2.Name = '软件功能范围'
    $ws2.Move([System.Reflection.Missing]::Value, $ws)
    $ws2.Cells.Font.Name = '微软雅黑'
    $ws2.Cells.Font.Size = 9.0
    $ws2.Tab.Color = Get-Color 68 114 196

    $scopeTitle = Merge-Set -ws $ws2 -Address 'A1:E1' -Value '软件功能范围（定制开发报价附件）' -HAlign -4108 -Bold $true -Size 16 -Fill $script:ColorHeader -FontColor $script:ColorWhite -Wrap $false
    $scopeTitle.RowHeight = 32

    $scopeNote = Merge-Set -ws $ws2 -Address 'A2:E2' -Value '对应报价单中“访客管理系统定制开发”一项；具体范围以双方确认的《功能需求清单》为准，超出范围的新增需求按变更单另行报价。' -HAlign -4131 -VAlign -4160 -Wrap $true -Size 9 -Fill $script:ColorYellow
    $scopeNote.RowHeight = 34
    $ws2.Rows.Item(3).RowHeight = 8

    $scopeHeaders = @('序号', '端/模块', '页面编号', '功能', '说明')
    for ($i = 1; $i -le 5; $i++) {
        $cell = $ws2.Cells.Item(4, $i)
        $cell.Value2 = $scopeHeaders[$i - 1]
        Format-Range -Range $cell -HAlign -4108 -Bold $true -Size 9.5 -Fill $script:ColorHeader -FontColor $script:ColorWhite -Wrap $true
    }
    $ws2.Range('A4:E4').RowHeight = 26
    Set-Border -Range $ws2.Range('A4:E4') -Color $script:ColorBorder -Weight 2

    $row = 5
    $seq = 1
    foreach ($group in $ScopeGroups) {
        New-ScopeGroup -ws $ws2 -Row $row -Title $group.Title
        $row++
        foreach ($item in $group.Rows) {
            New-ScopeRow -ws $ws2 -Row $row -Seq $seq -Module $group.Module -Page $item.Page -Function $item.Function -Desc $item.Desc
            $row++
            $seq++
        }
    }
    $lastScopeRow = $row - 1
    $scopeFooter = Merge-Set -ws $ws2 -Address "A$($lastScopeRow + 1):E$($lastScopeRow + 1)" -Value '注：以上为报价所含定制开发范围；具体页面与规则以双方确认的《功能需求清单》为准，新增需求按变更单另行报价。不含闸机/电梯/道闸、访客人脸采集、短信/邮件、人事/办公系统对接、实体访客卡、物品登记、多租户；车牌仅作预约表单选填留痕，不含车辆放行/车辆登记；保安工作台仅含查看、引导、催办与人工开门，不含代录/代审批；门卫室 Kiosk 使用客户现有电脑与网络，如需专用自助终端另行报价。' -HAlign -4131 -VAlign -4160 -Wrap $true -Size 8.5 -Fill $script:ColorNote -FontColor $script:ColorGray
    $scopeFooter.RowHeight = 54

    $ws2.Columns.Item('A').ColumnWidth = 6
    $ws2.Columns.Item('B').ColumnWidth = 16
    $ws2.Columns.Item('C').ColumnWidth = 14
    $ws2.Columns.Item('D').ColumnWidth = 28
    $ws2.Columns.Item('E').ColumnWidth = 62

    $ws2.Activate()
    $excel.ActiveWindow.DisplayGridlines = $false
    $ws2.PageSetup.Orientation = 2
    $ws2.PageSetup.PaperSize = 9
    $ws2.PageSetup.Zoom = $false
    $ws2.PageSetup.FitToPagesWide = 1
    $ws2.PageSetup.FitToPagesTall = $false
    $ws2.PageSetup.LeftMargin = $excel.CentimetersToPoints(1.2)
    $ws2.PageSetup.RightMargin = $excel.CentimetersToPoints(1.2)
    $ws2.PageSetup.TopMargin = $excel.CentimetersToPoints(1.0)
    $ws2.PageSetup.BottomMargin = $excel.CentimetersToPoints(1.0)
    $ws2.PageSetup.HeaderMargin = $excel.CentimetersToPoints(0.5)
    $ws2.PageSetup.FooterMargin = $excel.CentimetersToPoints(0.6)
    $ws2.PageSetup.CenterHorizontally = $true
    $ws2.PageSetup.PrintGridlines = $false
    $ws2.PageSetup.PrintArea = "A1:E$($lastScopeRow + 1)"
    $ws2.PageSetup.PrintTitleRows = '$1:$4'
    $ws2.PageSetup.LeftFooter = '&"微软雅黑"&8软件功能范围（报价附件）'
    $ws2.PageSetup.RightFooter = '&"微软雅黑"&8第 &P 页 / 共 &N 页'
    $ws2.ResetAllPageBreaks()
    try { $ws2.Rows.Item(26).PageBreak = 1 } catch { Write-Warning "功能范围手动分页失败：$($_.Exception.Message)" }

    # calculate and verify
    $excel.Calculation = -4105
    $excel.CalculateFullRebuild()
    Start-Sleep -Milliseconds 300

    $checks = @(
        @{ Cell = 'G11'; Expected = 4820.00; Label = '硬件小计' },
        @{ Cell = 'G12'; Expected = 626.60; Label = '硬件税额' },
        @{ Cell = 'G13'; Expected = 5446.60; Label = '硬件含税' },
        @{ Cell = 'G18'; Expected = $swNet; Label = '软件小计' },
        @{ Cell = 'G19'; Expected = $swTax; Label = '软件税额' },
        @{ Cell = 'G20'; Expected = $swGross; Label = '软件含税' },
        @{ Cell = 'G26'; Expected = 5600.00; Label = '实施小计' },
        @{ Cell = 'G27'; Expected = 336.00; Label = '实施税额' },
        @{ Cell = 'G28'; Expected = 5936.00; Label = '实施含税' },
        @{ Cell = 'D35'; Expected = $sumNet; Label = '不含税合计' },
        @{ Cell = 'F35'; Expected = $sumTax; Label = '税额合计' },
        @{ Cell = 'G35'; Expected = $TargetTotal; Label = '含税总价' }
    )
    foreach ($check in $checks) {
        $cell = $ws.Range($check.Cell)
        if ($null -eq $cell.Value2) { throw "校验失败：$($check.Label) ($($check.Cell)) 为空" }
        $value = [double]$cell.Value2
        if ([Math]::Abs($value - $check.Expected) -gt 0.005) {
            throw ("校验失败：{0} ({1}) = {2:N2}，应为 {3:N2}" -f $check.Label, $check.Cell, $value, $check.Expected)
        }
    }

    foreach ($cell in $ws.UsedRange.Cells) {
        $text = [string]$cell.Text
        if ($text -match '^#(REF!|VALUE!|NAME\?|DIV/0!|N/A|NUM!|NULL!)') {
            throw "发现公式错误：$($cell.Address()) $text"
        }
    }

    # save
    if (Test-Path -LiteralPath $OutputPath) {
        Copy-Item -LiteralPath $OutputPath -Destination "$OutputPath.bak" -Force
    }
    $wb.SaveAs($OutputPath, 51)

    # export preview
    if (-not $SkipPdf) {
        $pdfQuote = Join-Path $PreviewDir '报价单_预览.pdf'
        $pdfScope = Join-Path $PreviewDir '软件功能范围_预览.pdf'
        $ws.ExportAsFixedFormat(0, $pdfQuote)
        $ws2.ExportAsFixedFormat(0, $pdfScope)
    }

    $quotePages = $null
    $scopePages = $null
    try { $quotePages = $ws.PageSetup.Pages.Count } catch { }
    try { $scopePages = $ws2.PageSetup.Pages.Count } catch { }

    $wb.Close($false)
    $wb = $null

    # reopen to confirm persistence
    $wbCheck = $excel.Workbooks.Open($OutputPath)
    if ($wbCheck.Worksheets.Count -ne 2) {
        throw "工作簿工作表数量不正确：$($wbCheck.Worksheets.Count)"
    }
    $checkQuote = $wbCheck.Worksheets.Item('报价单')
    $persistedTotal = [double]$checkQuote.Range('G35').Value2
    if ([Math]::Abs($persistedTotal - $TargetTotal) -gt 0.005) {
        throw ("重新打开校验失败：G35 = {0:N2}，目标 {1:N2}" -f $persistedTotal, $TargetTotal)
    }
    $wbCheck.Close($false)
    $wbCheck = $null

    Write-Output "OK: $OutputPath"
    Write-Output ("硬件：不含税 {0:N2}，税额 {1:N2}，含税 {2:N2}" -f $hwNet, $hwTax, $hwGross)
    Write-Output ("软件：不含税 {0:N2}，税额 {1:N2}，含税 {2:N2}" -f $swNet, $swTax, $swGross)
    Write-Output ("实施：不含税 {0:N2}，税额 {1:N2}，含税 {2:N2}" -f $implNet, $implTax, $implGross)
    Write-Output ("合计：不含税 {0:N2}，税额 {1:N2}，含税 {2:N2}" -f $sumNet, $sumTax, $sumGross)
    Write-Output ("工作表：报价单 / 软件功能范围；报价单 {0} 页，功能范围 {1} 页" -f $quotePages, $scopePages)
    if (-not $SkipPdf) {
        Write-Output "预览 PDF：$PreviewDir"
    }
} finally {
    if ($null -ne $wbCheck) { try { $wbCheck.Close($false) } catch { } }
    if ($null -ne $wb) { try { $wb.Close($false) } catch { } }
    if ($null -ne $excel) { try { $excel.Quit() } catch { } }
    if ($null -ne $ws2) { try { [System.Runtime.InteropServices.Marshal]::ReleaseComObject($ws2) | Out-Null } catch { } }
    if ($null -ne $ws) { try { [System.Runtime.InteropServices.Marshal]::ReleaseComObject($ws) | Out-Null } catch { } }
    if ($null -ne $wb) { try { [System.Runtime.InteropServices.Marshal]::ReleaseComObject($wb) | Out-Null } catch { } }
    if ($null -ne $excel) { try { [System.Runtime.InteropServices.Marshal]::ReleaseComObject($excel) | Out-Null } catch { } }
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
