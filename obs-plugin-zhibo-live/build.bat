@echo off
setlocal enabledelayedexpansion

echo =======================================================
echo   PDK Zhibo Live OBS Plugin Build Script (VS2022 x64)
echo =======================================================

set BUILD_DIR=build
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"

set CMAKE_GEN="Visual Studio 17 2022"
set CMAKE_ARCH=x64

echo.
echo [1/2] Configuring CMake with Visual Studio 2022...
cmake -G %CMAKE_GEN% -A %CMAKE_ARCH% -B %BUILD_DIR% -S .
if %errorlevel% neq 0 (
    echo.
    echo [ERROR] CMake configuration failed!
    echo Please make sure Visual Studio 2022 is installed with C++ desktop workload.
    pause
    exit /b 1
)

echo.
echo [2/2] Building Release configuration with MSVC...
cmake --build %BUILD_DIR% --config Release --parallel
if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Build failed!
    pause
    exit /b 1
)

echo.
echo =======================================================
echo   [SUCCESS] Build completed!
echo   Plugin DLL: %BUILD_DIR%\Release\obs-zhibo-live.dll
echo =======================================================
echo.
echo Installation Guide:
echo - Double-click install.bat to auto-install to OBS Studio!
echo - Or manually copy:
echo   1. %BUILD_DIR%\Release\obs-zhibo-live.dll to:
echo      "C:\Program Files\obs-studio\obs-plugins\64bit\"
echo   2. assets\locale to:
echo      "C:\Program Files\obs-studio\data\obs-plugins\obs-zhibo-live\locale\"
echo.
pause