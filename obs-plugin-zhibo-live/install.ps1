# PDK 智播拉流 OBS 插件一键安装脚本
chcp 65001 >$null 2>&1
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# 确定脚本与插件根目录（多重防空保护）
$scriptDir = $PSScriptRoot
if (-not $scriptDir -and $PSCommandPath) {
    $scriptDir = Split-Path -Parent $PSCommandPath
}
if (-not $scriptDir -and $MyInvocation.MyCommand.Path) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
}
if (-not $scriptDir) {
    $scriptDir = "E:\pdk\obs-plugin-zhibo-live"
}

$pluginDll = Join-Path $scriptDir "build\Release\obs-zhibo-live.dll"
$localeDir = Join-Path $scriptDir "assets\locale"
$scriptFile = Join-Path $scriptDir "install.ps1"

# 检查管理员权限
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "[提示] 正在请求管理员权限以向 Program Files 安装插件..." -ForegroundColor Cyan
    Start-Process powershell -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$scriptFile`"" -WorkingDirectory "`"$scriptDir`"" -Verb RunAs
    exit
}

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "      PDK 智播拉流 OBS 插件一键安装程序 (Windows x64)       " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host ""
Write-Host "插件根目录: $scriptDir" -ForegroundColor Gray

# 检查 OBS 进程
$obsProc = Get-Process obs64 -ErrorAction SilentlyContinue
if ($obsProc) {
    Write-Host "[警告] 检测到 OBS Studio (obs64.exe) 正在运行！" -ForegroundColor Yellow
    Write-Host "请先关闭 OBS Studio，然后再按任意键继续安装..." -ForegroundColor Yellow
    [Console]::ReadKey($true) | Out-Null
}

if (-not (Test-Path $pluginDll)) {
    Write-Host "[错误] 未找到已编译的插件: $pluginDll" -ForegroundColor Red
    Write-Host "请先运行 build.bat 进行编译！" -ForegroundColor Red
    Read-Host "按回车键退出..."
    exit 1
}

$obsPluginsDir = "C:\Program Files\obs-studio\obs-plugins\64bit"
$obsDataDir = "C:\Program Files\obs-studio\data\obs-plugins\obs-zhibo-live\locale"

if (-not (Test-Path $obsPluginsDir)) {
    Write-Host "[错误] 未检测到 OBS 插件目录: $obsPluginsDir" -ForegroundColor Red
    Write-Host "请确认 OBS Studio 64位 是否已正确安装在 C:\Program Files\obs-studio" -ForegroundColor Red
    Read-Host "按回车键退出..."
    exit 1
}

try {
    Write-Host "[1/2] 正在复制插件 DLL 到 OBS 目录..." -ForegroundColor Cyan
    Copy-Item -Path $pluginDll -Destination $obsPluginsDir -Force
    Write-Host "  -> 成功写入: $obsPluginsDir\obs-zhibo-live.dll" -ForegroundColor Green

    Write-Host "[2/2] 正在复制多语言资源文件..." -ForegroundColor Cyan
    if (-not (Test-Path $obsDataDir)) {
        New-Item -ItemType Directory -Force -Path $obsDataDir | Out-Null
    }
    
    $iniFiles = Get-ChildItem -Path "$localeDir\*.ini" -ErrorAction SilentlyContinue
    if ($iniFiles) {
        foreach ($f in $iniFiles) {
            Copy-Item -Path $f.FullName -Destination $obsDataDir -Force
            Write-Host "  -> 成功写入: $obsDataDir\$($f.Name)" -ForegroundColor Green
        }
    }

    Write-Host ""
    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host "  [恭喜] PDK 智播拉流插件已成功安装到 OBS Studio！" -ForegroundColor Green
    Write-Host "  插件路径: $obsPluginsDir\obs-zhibo-live.dll" -ForegroundColor Gray
    Write-Host "  语言路径: $obsDataDir" -ForegroundColor Gray
    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "现在您可以启动 OBS Studio，插件将自动加载并检测激活状态。" -ForegroundColor Cyan
} catch {
    Write-Host "[错误] 安装失败: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ""
Read-Host "安装完成，按回车键退出..."