# 启动本机（Windows）MediaMTX —— 本地开发用
#
# 1) 确保用户级环境变量 PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN 存在（缺失则用密码学随机数生成，
#    与 02-init-infra.sh 的 openssl rand -hex 32 等价），并写入用户环境变量（新进程生效）
# 2) 用该令牌渲染 deploy/mediamtx/mediamtx-windows.example.yml 到 mtx/mediamtx/mediamtx.yml
#    （mtx/ 已 gitignore，含 31MB mediamtx.exe，不进仓库；本脚本让它随时可重建）
# 3) 启动 mediamtx.exe，并打印可用端口
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/start-mediamtx-win.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/start-mediamtx-win.ps1 -Background   # 后台启动

param(
    [switch]$Background
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$template = Join-Path $repoRoot 'deploy/mediamtx/mediamtx-windows.example.yml'
$exe      = Join-Path $repoRoot 'mtx/mediamtx/mediamtx.exe'
$confDir  = Join-Path $repoRoot 'mtx/mediamtx'
$conf     = Join-Path $confDir 'mediamtx.yml'

if (-not (Test-Path $template)) { throw "找不到配置模板：$template" }
if (-not (Test-Path $exe))      { throw "找不到 mediamtx.exe：$exe（把官方发行包解压到 mtx/mediamtx/）" }

# ---------- 1. 令牌 ----------
$token = [Environment]::GetEnvironmentVariable('PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN', 'User')
if (-not $token) {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $token = ([BitConverter]::ToString($bytes).Replace('-', '')).ToLower()
    [Environment]::SetEnvironmentVariable('PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN', $token, 'User')
    Write-Host "已生成并写入用户环境变量 PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN（新开的终端/IDE 才生效）" -ForegroundColor Yellow
} else {
    Write-Host "使用现有 PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN（长度 $($token.Length)）" -ForegroundColor DarkGray
}
# 当前进程也设置一份，保证后端/校验脚本同会话可见
$env:PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN = $token

# 开启钩子调试日志（event-hook.bat 每次触发会记录事件与参数，不含 token；与后端 [mtx-trace] 日志对照用）
$env:PDK_MEDIAMTX_HOOK_DEBUG = '1'
$env:PDK_MEDIAMTX_HOOK_LOG = Join-Path $confDir 'hook.log'

# ---------- 2. 渲染配置 ----------
if (-not (Test-Path $confDir)) { New-Item -ItemType Directory -Path $confDir -Force | Out-Null }
if (Test-Path $conf) {
    Copy-Item $conf "$conf.bak.$(Get-Date -Format yyyyMMddHHmmss)" -Force
}
(Get-Content $template -Raw -Encoding UTF8) -replace '__PDK_MEDIAMTX_TOKEN__', $token |
    Set-Content -Path $conf -Encoding UTF8 -NoNewline:$false
Write-Host "已生成配置：$conf" -ForegroundColor Green

# ---------- 3. 启动 ----------
$running = Get-Process -Name 'mediamtx' -ErrorAction SilentlyContinue
if ($running) {
    Write-Host "mediamtx 已在运行（PID $($running.Id -join ',')），如需重启请先停止" -ForegroundColor Yellow
} else {
    if ($Background) {
        Start-Process -FilePath $exe -ArgumentList $conf -WorkingDirectory $confDir | Out-Null
        Write-Host "mediamtx 已在后台启动" -ForegroundColor Green
    } else {
        Write-Host "前台启动 mediamtx（Ctrl+C 停止）——另开窗口可加 -Background" -ForegroundColor Cyan
        & $exe $conf
    }
}

Write-Host ""
Write-Host "端口：RTMP 1935 / HLS 8888 / API 9997 / metrics 9998（见 $conf）"
Write-Host "钩子调试日志：$confDir\hook.log（与后端 [mtx-trace] 日志对照排查推流事件）"
Write-Host "推流自检：ffmpeg -re -i test.mp4 -c copy -f flv `"rtmp://127.0.0.1:1935/<path>?token=<ticket>`""
Write-Host "注意：本机未检测到 ffmpeg 时需先安装（winget install Gyan.FFmpeg 或用 OBS 推流）"
