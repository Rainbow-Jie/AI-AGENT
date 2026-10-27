@echo off
chcp 936 >nul
title Agent Hub - 停止服务
cd /d "%~dp0"

set "PORT=8899"

echo ============================================================
echo   Agent Hub 停止服务
echo ------------------------------------------------------------
echo   监听端口 : %PORT%
echo ============================================================
echo.

set "PID="
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%PORT%" ^| findstr "LISTENING"') do set "PID=%%a"

if "%PID%"=="" goto notrunning

echo [停止] 发现进程 PID=%PID%，正在结束...
taskkill /F /PID %PID% >nul 2>&1
if errorlevel 1 (
    echo [失败] 结束失败。请右键本脚本 -> 以管理员身份运行。
) else (
    echo [成功] 进程已结束，端口 %PORT% 已释放。
)
goto end

:notrunning
echo [提示] 端口 %PORT% 上没有运行中的服务，无需停止。
goto end

:end
echo.
pause
