@echo off
rem =============================================================
rem  MediaMTX Windows event hook (equivalent to Linux event-hook.sh)
rem =============================================================
rem
rem  Why this file is needed:
rem    MediaMTX on Windows does exec commands directly without going
rem    through a shell, so writing curl.exe with literal MTX_PATH in
rem    the URL leaves MTX_PATH unexpanded; the backend receives the
rem    literal token MTX_PATH and responds with
rem    MissingServletRequestParameterException: Required request
rem    parameter 'path' is not present (HTTP 500).
rem    This .bat is executed by cmd.exe, which expands MTX_PATH and
rem    other env vars normally.
rem
rem  Caveat: comments CANNOT contain the ampersand char. cmd
rem    pre-processing treats ampersand as a command separator even
rem    inside rem comment lines, causing subsequent lines to be parsed
rem    incorrectly. Use words or line breaks instead of ampersand.
rem
rem  Usage (mediamtx.yml, must wrap with cmd.exe /c explicitly:
rem    Windows CreateProcess does NOT consult file association to
rem    parse .bat):
rem    runOnReady:     cmd.exe /c "E:\pdk\deploy\mediamtx\event-hook.bat" available
rem    runOnNotReady:  cmd.exe /c "E:\pdk\deploy\mediamtx\event-hook.bat" unavailable
rem    runOnRead:      cmd.exe /c "E:\pdk\deploy\mediamtx\event-hook.bat" read
rem    runOnUnread:    cmd.exe /c "E:\pdk\deploy\mediamtx\event-hook.bat" unread
rem
rem  Deps: curl.exe (built-in on Win10+), env var PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN
rem =============================================================
setlocal

set "EVENT=%~1"
if "%EVENT%"=="" (
  echo [mtx-hook] usage: event-hook.bat ^<available^|unavailable^|read^|unread^] 1>&2
  exit /b 2
)

set "TOKEN=%PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN%"
if "%TOKEN%"=="" (
  echo [mtx-hook] PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN is not set 1>&2
  exit /b 1
)

if "%PDK_MEDIAMTX_NODE_CODE%"=="" (set "NODE=mediamtx-local") else (set "NODE=%PDK_MEDIAMTX_NODE_CODE%")
if "%PDK_MEDIAMTX_BACKEND_BASE_URL%"=="" (set "BASE_URL=http://127.0.0.1:8080") else (set "BASE_URL=%PDK_MEDIAMTX_BACKEND_BASE_URL%")

rem path is REQUIRED by the backend for all four events; missing it triggers
rem MissingServletRequestParameterException (HTTP 500, code 50000).
set "URL=%BASE_URL%/api/v1/internal/mediamtx/events/%EVENT%?serviceToken=%TOKEN%&nodeCode=%NODE%&path=%MTX_PATH%"

rem available / unavailable carry sourceId; read carries readerId+readerType; unread carries readerId
set "EXTRA="
if /i "%EVENT%"=="available"   set "EXTRA=&sourceId=%MTX_SOURCE_ID%"
if /i "%EVENT%"=="unavailable" set "EXTRA=&sourceId=%MTX_SOURCE_ID%"
if /i "%EVENT%"=="read"        set "EXTRA=&readerId=%MTX_READER_ID%&readerType=%MTX_READER_TYPE%"
if /i "%EVENT%"=="unread"      set "EXTRA=&readerId=%MTX_READER_ID%"

rem Debug: set PDK_MEDIAMTX_HOOK_DEBUG=1 to log event and params (no token).
rem Default log path is %TEMP%\pdk-mtx-hook.log; override via PDK_MEDIAMTX_HOOK_LOG.
rem IMPORTANT: if-block scoped set vars vanish after the block, so resolve
rem HOOKLOG outside the if-block to keep it visible to the inner echo.
if "%PDK_MEDIAMTX_HOOK_DEBUG%"=="1" (
  if "%PDK_MEDIAMTX_HOOK_LOG%"=="" (set "HOOKLOG=%TEMP%\pdk-mtx-hook.log") else (set "HOOKLOG=%PDK_MEDIAMTX_HOOK_LOG%")
)
if "%PDK_MEDIAMTX_HOOK_DEBUG%"=="1" if defined HOOKLOG echo %date% %time% event=%EVENT% node=%NODE% path=%MTX_PATH% sourceId=%MTX_SOURCE_ID% readerId=%MTX_READER_ID% readerType=%MTX_READER_TYPE%>> "%HOOKLOG%"

curl.exe -fsS -X POST "%URL%%EXTRA%"
exit /b %ERRORLEVEL%