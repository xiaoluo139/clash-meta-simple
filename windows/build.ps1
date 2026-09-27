# 编译 ClashSimple.exe
# 使用 Windows 自带的 C# 编译器（.NET Framework 4.x），无需安装 .NET SDK。

$ErrorActionPreference = 'Stop'

$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $csc)) {
    $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe'
}
if (-not (Test-Path -LiteralPath $csc)) { throw '找不到 csc.exe（需要 .NET Framework 4.x）' }

$src = Join-Path $PSScriptRoot 'src\ClashSimple.cs'
$out = Join-Path $PSScriptRoot 'ClashSimple.exe'

& $csc /nologo /target:winexe "/out:$out" `
    /r:System.Web.Extensions.dll `
    /r:System.Windows.Forms.dll `
    /r:System.Drawing.dll `
    /r:System.IO.Compression.FileSystem.dll `
    "$src"

if ($LASTEXITCODE -ne 0) { throw "编译失败（exit $LASTEXITCODE）" }

Write-Host "编译完成: $out"
Get-Item -LiteralPath $out | Select-Object Name, Length