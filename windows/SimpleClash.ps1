#Requires -Version 5.1
<#
    SimpleClash for Windows
    ---------------------------------------------------------------
    基于 mihomo 内核的极简 Windows 客户端（与 Android 端 Clash Simple 配套）。

      - 大开关一键启动/停止，自动设置 Windows 系统代理
      - 分流模式切换：规则 / 全局 / 直连
      - 节点列表 + 一键测速 + 自动选择最快（只挑「测通」的节点）
      - 订阅导入 / 一键更新
      - 内置 IP 检测（跳转 ip.skk.moe）

    界面用浏览器渲染（默认用 Edge 的应用窗口模式打开，看起来就是一个独立程序）。
#>
[CmdletBinding()]
param(
    [int]$HttpPort = 7880,
    [int]$MixedPort = 7890,
    [int]$ApiPort = 9090,
    [string]$ApiSecret = 'simpleclash',
    [string]$TestUrl = 'https://www.gstatic.com/generate_204',
    [switch]$NoBrowser,
    [switch]$SelfTest,
    [switch]$NoSystemProxy
)

$ErrorActionPreference = 'Stop'

# .NET Framework 4.x 默认不一定启用 TLS 1.2，
# 否则访问 GitHub / 订阅链接会报「未能创建 SSL/TLS 安全通道」
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 3072
} catch { }
$Root         = Split-Path -Parent $MyInvocation.MyCommand.Path
$CoreDir      = Join-Path $Root 'core'
$DataDir      = Join-Path $Root 'data'
$CoreExe      = Join-Path $CoreDir 'mihomo.exe'
$UiFile       = Join-Path $Root 'ui.html'
$ConfigFile   = Join-Path $DataDir 'config.yaml'
$ProfileFile  = Join-Path $DataDir 'profile.yaml'
$SettingsFile = Join-Path $DataDir 'settings.json'
$LogFile      = Join-Path $DataDir 'mihomo.log'

$script:MihomoProcess = $null
$script:SystemProxyOn = $false

function Write-Info { param([string]$Message) Write-Host "[SimpleClash] $Message" }

function Ensure-Directory {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Force -Path $Path | Out-Null
    }
}

function Get-Settings {
    if (Test-Path -LiteralPath $SettingsFile) {
        try { return (Get-Content -LiteralPath $SettingsFile -Raw -Encoding UTF8 | ConvertFrom-Json) } catch { }
    }

    return [pscustomobject]@{ profileUrl = ''; profileName = ''; mode = 'rule' }
}

function Save-Settings {
    param($Settings)
    Ensure-Directory $DataDir
    $json = $Settings | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText($SettingsFile, $json, (New-Object System.Text.UTF8Encoding($false)))
}
# ---------------------------------------------------------------- 内核准备

function Ensure-Core {
    if (Test-Path -LiteralPath $CoreExe) { return }

    Write-Info '未找到 mihomo.exe，正在自动下载…'
    Ensure-Directory $CoreDir

    $release = Invoke-RestMethod -Uri 'https://api.github.com/repos/MetaCubeX/mihomo/releases/latest' -Headers @{ 'User-Agent' = 'SimpleClash' }
    $asset = $release.assets | Where-Object { $_.name -like 'mihomo-windows-amd64-compatible-*.zip' } | Select-Object -First 1
    if (-not $asset) {
        $asset = $release.assets | Where-Object { $_.name -like 'mihomo-windows-amd64-*.zip' } | Select-Object -First 1
    }
    if (-not $asset) { throw '找不到 mihomo 的 Windows 版本，请手动把 mihomo.exe 放到 core 目录' }

    $zip = Join-Path $CoreDir 'mihomo.zip'
    Write-Info "下载 $($asset.name)"
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $zip -UseBasicParsing

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $CoreDir, $true)
    Remove-Item -LiteralPath $zip -Force

    $exe = Get-ChildItem -LiteralPath $CoreDir -Filter '*.exe' | Select-Object -First 1
    if (-not $exe) { throw '解压后没有找到可执行文件' }
    if ($exe.FullName -ne $CoreExe) { Move-Item -LiteralPath $exe.FullName -Destination $CoreExe -Force }

    Write-Info '内核准备完成'
}
# ---------------------------------------------------------------- 配置生成

$DefaultProfile = "proxies: []`nproxy-groups:`n  - name: PROXY`n    type: select`n    proxies:`n      - DIRECT`nrules:`n  - MATCH,DIRECT`n"

function Get-ProfileText {
    if (Test-Path -LiteralPath $ProfileFile) {
        return (Get-Content -LiteralPath $ProfileFile -Raw -Encoding UTF8)
    }

    return $DefaultProfile
}

# 订阅自带的端口/控制器字段要剔除，否则与我们的覆盖值重复会导致 YAML 解析失败
$OverrideKeys = @(
    'mixed-port', 'port', 'socks-port', 'redir-port', 'tproxy-port',
    'external-controller', 'external-controller-tls', 'external-controller-cors',
    'secret', 'allow-lan', 'bind-address',
    'log-level'
)

function New-MihomoConfig {
    Ensure-Directory $DataDir
    $lines = (Get-ProfileText) -split "`r?`n"
    $kept = New-Object System.Collections.Generic.List[string]

    foreach ($line in $lines) {
        $isOverride = $false
        foreach ($key in $OverrideKeys) {
            if ($line -match ('^' + [regex]::Escape($key) + '\s*:')) { $isOverride = $true; break }
        }
        if (-not $isOverride) { $kept.Add($line) }
    }

    $header = @(
        '# 由 SimpleClash 生成，请勿手动修改（订阅原文保存在 profile.yaml）'
        "mixed-port: $MixedPort"
        'allow-lan: false'
        "external-controller: 127.0.0.1:$ApiPort"
        "secret: `"$ApiSecret`""
        'log-level: warning'
        ''
    )

    $text = ($header + $kept) -join "`r`n"
    [System.IO.File]::WriteAllText($ConfigFile, $text, (New-Object System.Text.UTF8Encoding($false)))
}
# ---------------------------------------------------------------- 内核进程

function Test-MihomoRunning {
    if ($null -eq $script:MihomoProcess) { return $false }

    try { return -not $script:MihomoProcess.HasExited } catch { return $false }
}

function Test-ApiAlive {
    try {
        Invoke-MihomoApi -Method GET -Path '/version' | Out-Null
        return $true
    } catch {
        return $false
    }
}

function Start-Mihomo {
    if (Test-MihomoRunning) { return }

    # 已经有内核在跑（例如上次没退干净）就直接复用，避免端口冲突
    if (Test-ApiAlive) {
        Write-Info '检测到内核已在运行，直接复用'
        return
    }

    Ensure-Core
    New-MihomoConfig

    $mihomoArgs = @('-d', $DataDir, '-f', $ConfigFile, '-ext-ctl', "127.0.0.1:$ApiPort", '-secret', $ApiSecret)
    Write-Info ('启动内核: mihomo.exe ' + ($mihomoArgs -join ' '))

    Remove-Item -LiteralPath $LogFile -Force -ErrorAction SilentlyContinue

    $script:MihomoProcess = Start-Process -FilePath $CoreExe -ArgumentList $mihomoArgs -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $LogFile -RedirectStandardError "$LogFile.err"

    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 250

        if (-not (Test-MihomoRunning)) {
            $detail = ''

            foreach ($f in @("$LogFile.err", $LogFile)) {
                if (Test-Path -LiteralPath $f) {
                    $detail = ((Get-Content -LiteralPath $f -Tail 6) -join ' ').Trim()
                    if ($detail) { break }
                }
            }

            if (-not $detail) { $detail = '没有输出，可能是配置文件有问题' }

            throw "内核启动失败：$detail"
        }

        try { Invoke-MihomoApi -Method GET -Path '/version' | Out-Null; break } catch { }
    }

    if (-not (Test-ApiAlive)) { throw '内核启动超时' }

    # 应用上次保存的分流模式
    $settings = Get-Settings
    if ($settings.mode) {
        try { Set-Mode -Mode $settings.mode } catch { }
    }
}

function Stop-Mihomo {
    Disable-SystemProxy

    if (Test-MihomoRunning) {
        Write-Info '停止内核'
        try { $script:MihomoProcess.Kill() } catch { }
    }

    $script:MihomoProcess = $null
}

# ---------------------------------------------------------------- 系统代理

$InternetSettingsKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'

function Set-SystemProxyRegistry {
    param([bool]$Enabled)

    if ($Enabled) {
        Set-ItemProperty -Path $InternetSettingsKey -Name ProxyEnable -Value 1 -Type DWord
        Set-ItemProperty -Path $InternetSettingsKey -Name ProxyServer -Value "127.0.0.1:$MixedPort" -Type String
        Set-ItemProperty -Path $InternetSettingsKey -Name ProxyOverride -Value '<local>' -Type String
    } else {
        Set-ItemProperty -Path $InternetSettingsKey -Name ProxyEnable -Value 0 -Type DWord
    }
}

function Restore-LeftoverSystemProxy {
    if ($NoSystemProxy) { return }

    try {
        $k = Get-ItemProperty -Path $InternetSettingsKey -ErrorAction Stop

        if ($k.ProxyEnable -eq 1 -and $k.ProxyServer -eq "127.0.0.1:$MixedPort") {
            Write-Info '检测到上次残留的系统代理，正在还原'
            Set-SystemProxyRegistry -Enabled $false
        }
    } catch { }
}
function Enable-SystemProxy {
    if ($NoSystemProxy) {
        Write-Info '（-NoSystemProxy：本次不修改系统代理）'
        return
    }

    if ($script:SystemProxyOn) { return }

    Set-SystemProxyRegistry -Enabled $true
    $script:SystemProxyOn = $true
}

function Disable-SystemProxy {
    if (-not $script:SystemProxyOn) { return }

    Set-SystemProxyRegistry -Enabled $false
    $script:SystemProxyOn = $false
}
# ---------------------------------------------------------------- mihomo API

function Invoke-MihomoApi {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null
    )

    # 注意：不能用 Invoke-RestMethod —— Windows PowerShell 5.1 在响应头没有
    # charset 时会按 Latin-1 解码 JSON，导致中文节点名变成乱码。
    # 这里手工读取字节流并按 UTF-8 解码。
    $uri = "http://127.0.0.1:$ApiPort$Path"
    $request = [System.Net.HttpWebRequest]::Create($uri)
    $request.Method = $Method
    $request.Headers.Add('Authorization', "Bearer $ApiSecret")
    $request.Timeout = 60000
    $request.ReadWriteTimeout = 60000

    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 8 -Compress
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        $request.ContentType = 'application/json; charset=utf-8'
        $request.ContentLength = $bytes.Length
        $stream = $request.GetRequestStream()
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Close()
    }

    $response = $request.GetResponse()
    $reader = New-Object System.IO.StreamReader($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
    $text = $reader.ReadToEnd()
    $reader.Close()
    $response.Close()

    if ([string]::IsNullOrWhiteSpace($text)) { return $null }

    return ($text | ConvertFrom-Json)
}

function Get-AllProxies {
    $all = Invoke-MihomoApi -Method GET -Path '/proxies'
    return $all.proxies
}

function Get-Groups {
    $proxies = Get-AllProxies
    $groups = @()

    foreach ($prop in $proxies.PSObject.Properties) {
        $p = $prop.Value
        if ($null -eq $p.all) { continue }
        if ($p.all.Count -eq 0) { continue }
        if ($p.type -notin @('Selector', 'URLTest', 'Fallback', 'LoadBalance', 'Relay')) { continue }

        $groups += [pscustomobject]@{
            name  = $p.name
            type  = $p.type
            now   = $p.now
            count = $p.all.Count
        }
    }

    return $groups
}

function Get-CurrentGroup {
    param([string]$Preferred)

    $groups = @(Get-Groups)
    if ($groups.Count -eq 0) { return $null }

    if ($Preferred) {
        $hit = $groups | Where-Object { $_.name -eq $Preferred } | Select-Object -First 1
        if ($hit) { return $hit }
    }

    $global = $groups | Where-Object { $_.name -eq 'GLOBAL' } | Select-Object -First 1
    if ($global) { return $global }

    $selector = $groups | Where-Object { $_.type -eq 'Selector' } | Select-Object -First 1
    if ($selector) { return $selector }

    return $groups[0]
}

function Get-GroupNodes {
    param([string]$Group)

    $proxies = Get-AllProxies
    $groupProxy = $proxies.$Group
    if ($null -eq $groupProxy) { return @() }

    $nodes = @()
    foreach ($name in $groupProxy.all) {
        $p = $proxies.$name
        if ($null -eq $p) { continue }

        $delay = 0
        if ($p.history -and $p.history.Count -gt 0) { $delay = [int]$p.history[-1].delay }

        $isGroup = ($null -ne $p.all) -and ($p.all.Count -gt 0)

        $nodes += [pscustomobject]@{
            name    = $p.name
            type    = $p.type
            delay   = $delay
            isGroup = $isGroup
        }
    }

    return $nodes
}

function Get-State {
    param([string]$Group)

    $settings = Get-Settings
    $running = (Test-MihomoRunning) -or (Test-ApiAlive)

    $state = [ordered]@{
        running     = $running
        mode        = $settings.mode
        profileName = $settings.profileName
        profileUrl  = $settings.profileUrl
        mixedPort   = $MixedPort
        apiPort     = $ApiPort
        version     = ''
        group       = ''
        now         = ''
        nodeDelay   = 0
        systemProxy = $script:SystemProxyOn
        groups      = @()
        nodes       = @()
    }

    if (-not $running) { return $state }

    try {
        $version = Invoke-MihomoApi -Method GET -Path '/version'
        $state.version = $version.version
    } catch { }

    try {
        $configs = Invoke-MihomoApi -Method GET -Path '/configs'
        if ($configs.mode) { $state.mode = $configs.mode }
    } catch { }

    try {
        $groups = @(Get-Groups)
        $state.groups = $groups

        $current = Get-CurrentGroup -Preferred $Group
        if ($current) {
            $state.group = $current.name
            $state.now = $current.now
            $state.nodes = @(Get-GroupNodes -Group $current.name)

            $nowNode = $state.nodes | Where-Object { $_.name -eq $state.now } | Select-Object -First 1
            if ($nowNode) { $state.nodeDelay = [int]$nowNode.delay }
        }
    } catch { }

    return $state
}

function Test-GroupDelay {
    param([string]$Group)

    $query = "/group/$([uri]::EscapeDataString($Group))/delay?url=$([uri]::EscapeDataString($TestUrl))&timeout=3000"

    # 该接口只返回「测通」的节点（失败的不会出现在结果里）
    return Invoke-MihomoApi -Method GET -Path $query
}

function Select-Node {
    param([string]$Group, [string]$Name)

    Invoke-MihomoApi -Method PUT -Path "/proxies/$([uri]::EscapeDataString($Group))" -Body @{ name = $Name } | Out-Null
}

function Set-Mode {
    param([string]$Mode)

    Invoke-MihomoApi -Method PATCH -Path '/configs' -Body @{ mode = $Mode } | Out-Null

    $settings = Get-Settings
    $settings.mode = $Mode
    Save-Settings $settings
}

function Select-FastestNode {
    param([string]$Group)

    $delays = Test-GroupDelay -Group $Group

    $best = ''
    $bestDelay = 0
    foreach ($prop in $delays.PSObject.Properties) {
        $delay = [int]$prop.Value
        if ($delay -le 0) { continue }
        if ($best -eq '' -or $delay -lt $bestDelay) {
            $best = $prop.Name
            $bestDelay = $delay
        }
    }

    if ($best -eq '') { return $null }

    Select-Node -Group $Group -Name $best

    return [pscustomobject]@{ name = $best; delay = $bestDelay }
}

function Save-Subscription {
    param([string]$Url, [string]$Name)

    Ensure-Directory $DataDir
    Write-Info "下载订阅: $Url"
    $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 120 -Headers @{ 'User-Agent' = 'ClashforWindows/0.20.39' }

    $content = $response.Content
    if ($content -is [byte[]]) { $content = [System.Text.Encoding]::UTF8.GetString($content) }

    [System.IO.File]::WriteAllText($ProfileFile, $content, (New-Object System.Text.UTF8Encoding($false)))

    $settings = Get-Settings
    $settings.profileUrl = $Url
    if ($Name) { $settings.profileName = $Name } elseif (-not $settings.profileName) { $settings.profileName = '订阅' }
    Save-Settings $settings

    return $settings.profileName
}
# ---------------------------------------------------------------- HTTP 服务

function Send-Response {
    param($Stream, [int]$Status, [string]$ContentType, [byte[]]$Body)

    $reason = 'OK'
    if ($Status -eq 400) { $reason = 'Bad Request' }
    if ($Status -eq 404) { $reason = 'Not Found' }
    if ($Status -eq 500) { $reason = 'Internal Server Error' }

    $header = "HTTP/1.1 $Status $reason`r`nContent-Type: $ContentType`r`nContent-Length: $($Body.Length)`r`nConnection: close`r`nCache-Control: no-store`r`n`r`n"
    $hb = [System.Text.Encoding]::ASCII.GetBytes($header)
    $Stream.Write($hb, 0, $hb.Length)
    if ($Body.Length -gt 0) { $Stream.Write($Body, 0, $Body.Length) }
    $Stream.Flush()
}

function Send-Json {
    param($Stream, $Data, [int]$Status = 200)

    $json = $Data | ConvertTo-Json -Depth 10 -Compress
    Send-Response -Stream $Stream -Status $Status -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($json))
}

function Send-Error {
    param($Stream, [string]$Message, [int]$Status = 500)

    Send-Json -Stream $Stream -Status $Status -Data @{ ok = $false; error = $Message }
}

function Send-Ui {
    param($Stream)

    if (-not (Test-Path -LiteralPath $UiFile)) {
        Send-Response -Stream $Stream -Status 404 -ContentType 'text/plain; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes('ui.html missing'))
        return
    }

    $bytes = [System.IO.File]::ReadAllBytes($UiFile)
    Send-Response -Stream $Stream -Status 200 -ContentType 'text/html; charset=utf-8' -Body $bytes
}
function Read-Request {
    param($Stream)

    $reader = New-Object System.IO.StreamReader($Stream, [System.Text.Encoding]::UTF8)

    $requestLine = $reader.ReadLine()
    if (-not $requestLine) { return $null }

    $parts = $requestLine -split ' '
    $contentLength = 0

    while ($true) {
        $line = $reader.ReadLine()
        if ($null -eq $line -or $line -eq '') { break }
        if ($line -match '^Content-Length:\s*(\d+)') { $contentLength = [int]$Matches[1] }
    }

    $body = ''
    if ($contentLength -gt 0) {
        $buffer = New-Object char[] $contentLength
        $read = 0
        while ($read -lt $contentLength) {
            $n = $reader.Read($buffer, $read, $contentLength - $read)
            if ($n -le 0) { break }
            $read += $n
        }
        if ($read -gt 0) { $body = -join $buffer[0..($read - 1)] }
    }

    $rawPath = $parts[1]
    $path = $rawPath
    $query = @{}

    $qIndex = $rawPath.IndexOf('?')
    if ($qIndex -ge 0) {
        $path = $rawPath.Substring(0, $qIndex)
        foreach ($pair in ($rawPath.Substring($qIndex + 1) -split '&')) {
            if (-not $pair) { continue }
            $kv = $pair -split '=', 2
            $key = [uri]::UnescapeDataString($kv[0])
            $value = ''
            if ($kv.Count -gt 1) { $value = [uri]::UnescapeDataString($kv[1]) }
            $query[$key] = $value
        }
    }

    $json = $null
    if ($body) { try { $json = $body | ConvertFrom-Json } catch { } }

    return [pscustomobject]@{
        method = $parts[0]
        path   = $path
        query  = $query
        json   = $json
    }
}
function Handle-Request {
    param($Stream, $Request)

    $group = $Request.query['group']
    if (-not $group -and $Request.json) { $group = $Request.json.group }

    switch ($Request.path) {
        '/'           { Send-Ui -Stream $Stream; return }
        '/index.html' { Send-Ui -Stream $Stream; return }

        '/api/state' {
            Send-Json -Stream $Stream -Data (Get-State -Group $group)
            return
        }

        '/api/start' {
            Start-Mihomo
            Enable-SystemProxy
            Send-Json -Stream $Stream -Data (Get-State -Group $group)
            return
        }

        '/api/stop' {
            Stop-Mihomo
            Send-Json -Stream $Stream -Data (Get-State)
            return
        }

        '/api/mode' {
            Set-Mode -Mode $Request.json.mode
            Send-Json -Stream $Stream -Data @{ ok = $true; mode = $Request.json.mode }
            return
        }        '/api/test' {
            Send-Json -Stream $Stream -Data @{ ok = $true; delays = (Test-GroupDelay -Group $group) }
            return
        }

        '/api/select' {
            $name = $Request.query['name']
            if (-not $name) { $name = $Request.json.name }
            Select-Node -Group $group -Name $name
            Send-Json -Stream $Stream -Data @{ ok = $true; name = $name }
            return
        }

        '/api/auto' {
            $best = Select-FastestNode -Group $group
            if ($null -eq $best) {
                Send-Json -Stream $Stream -Data @{ ok = $false; error = '没有测通的节点，请稍后重试' }
            } else {
                Send-Json -Stream $Stream -Data @{ ok = $true; name = $best.name; delay = $best.delay }
            }
            return
        }        '/api/profile' {
            $name = Save-Subscription -Url $Request.json.url -Name $Request.json.name
            New-MihomoConfig
            if (Test-MihomoRunning) { Stop-Mihomo; Start-Mihomo; Enable-SystemProxy }
            Send-Json -Stream $Stream -Data @{ ok = $true; profileName = $name }
            return
        }

        '/api/update' {
            $settings = Get-Settings
            if (-not $settings.profileUrl) { throw 'no subscription url' }
            Save-Subscription -Url $settings.profileUrl -Name $settings.profileName | Out-Null
            New-MihomoConfig
            if (Test-MihomoRunning) { Stop-Mihomo; Start-Mihomo; Enable-SystemProxy }
            Send-Json -Stream $Stream -Data @{ ok = $true }
            return
        }

        '/api/quit' {
            Send-Json -Stream $Stream -Data @{ ok = $true }
            $script:Running = $false
            return
        }

        default {
            Send-Response -Stream $Stream -Status 404 -ContentType 'text/plain; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes('not found'))
            return
        }
    }
}
# ---------------------------------------------------------------- 打开界面

function Start-Ui {
    $url = "http://127.0.0.1:$HttpPort/"

    $edgePaths = @(
        (Join-Path $env:ProgramFiles 'Microsoft\Edge\Application\msedge.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Microsoft\Edge\Application\msedge.exe')
    )

    foreach ($edge in $edgePaths) {
        if ($edge -and (Test-Path -LiteralPath $edge)) {
            Start-Process -FilePath $edge -ArgumentList "--app=$url" | Out-Null
            return
        }
    }

    try {
        [System.Diagnostics.Process]::Start($url) | Out-Null
    } catch {
        Write-Info "请手动在浏览器打开: $url"
    }
}
# ---------------------------------------------------------------- 主流程

function Invoke-SelfTest {
    Write-Info '自检：启动内核 -> 状态 -> 切模式 -> 测速 -> 停止'

    Start-Mihomo

    $state = Get-State
    Write-Info ('内核版本: ' + $state.version)
    Write-Info ('分组数: ' + @($state.groups).Count)
    Write-Info ('节点数: ' + @($state.nodes).Count)
    Write-Info ('当前分组: ' + $state.group)

    Set-Mode -Mode 'global'
    $globalState = Get-State
    Write-Info ('切换后模式: ' + $globalState.mode)

    try {
        $delays = Test-GroupDelay -Group $globalState.group
        Write-Info ('测速通过节点数: ' + @($delays.PSObject.Properties).Count)
    } catch {
        Write-Info ('测速无结果（默认配置没有可用代理时属正常）: ' + $_.Exception.Message)
    }

    Stop-Mihomo
    Write-Info '自检完成'
}

Ensure-Directory $DataDir
$script:Running = $true

Restore-LeftoverSystemProxy
if ($SelfTest) {
    Invoke-SelfTest
    exit 0
}

$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $HttpPort)
$listener.Start()

Write-Info "服务已启动: http://127.0.0.1:$HttpPort/"
Write-Info '关闭本窗口即停止代理'

if (-not $NoBrowser) { Start-Ui }

try {
    while ($script:Running) {
        # 看门狗：已开启系统代理但内核失联（异常退出/卡死）时立即还原，避免本机断网
        if ($script:SystemProxyOn) {
            if (Test-ApiAlive) {
                $script:ApiMissCount = 0
            } else {
                $script:ApiMissCount = [int]$script:ApiMissCount + 1

                if ($script:ApiMissCount -ge 3) {
                    Write-Info '内核已失联，正在还原系统代理'
                    Disable-SystemProxy
                    $script:ApiMissCount = 0
                }
            }
        }

        if (-not $listener.Pending()) {
            Start-Sleep -Milliseconds 60
            continue
        }

        $client = $listener.AcceptTcpClient()

        try {
            $stream = $client.GetStream()
            $request = Read-Request -Stream $stream
            if ($request) { Handle-Request -Stream $stream -Request $request }
        } catch {
            try { Send-Error -Stream $client.GetStream() -Message $_.Exception.Message } catch { }
        } finally {
            $client.Close()
        }
    }
} finally {
    Stop-Mihomo
    $listener.Stop()
    Write-Info '已退出'
}