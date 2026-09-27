# ------------------------------------------------------------------------------
# Clash Simple - 以 PowerShell 作为宿主启动（原生窗口）
#
# 用途：Windows 11 的「智能应用控制」会拦截未签名的 .exe。
# 这个启动器不运行未签名的可执行文件，而是让微软签名的 powershell.exe
# 把 ClashSimple.dll 加载进进程里运行，因此不会被拦截，界面仍是原生窗口。
# ------------------------------------------------------------------------------

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# .NET Framework 默认不一定启用 TLS 1.2
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 3072
} catch { }

Add-Type -AssemblyName System.Web.Extensions
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression.FileSystem

Add-Type -Path (Join-Path $PSScriptRoot 'ClashSimple.dll')

[ClashSimple.Program]::Run()