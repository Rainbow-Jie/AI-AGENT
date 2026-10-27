@echo off
chcp 936 >nul
title Agent Hub - 智能体平台
cd /d "%~dp0"

rem ============================================================
rem  本项目需要 JDK 17+（Spring Boot 3.3），本机使用 D:\JDK20
rem  若你的 JAVA_HOME 已指向 JDK 17/20/21，可删掉下面两行
rem ============================================================
if exist "D:\JDK20\bin\java.exe" set "JAVA_HOME=D:\JDK20"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "data" mkdir data

echo ============================================================
echo   Agent Hub 智能体平台
echo ------------------------------------------------------------
echo   访问地址 : http://localhost:8899
echo   初始账号 : admin（新库首次启动会随机生成密码，见下方日志）
echo   数据文件 : data\agent-hub.db
echo   停止服务 : 关闭本窗口 或 Ctrl+C
echo ============================================================
echo.

rem ---------- 首次运行需要准备编译产物与依赖 ----------
if not exist "target\classes" goto build
if not exist "target\lib" goto build
goto run

:build
echo [构建] 首次运行，正在编译并准备依赖，请耐心等待...
call mvn -B compile
call mvn -B dependency:copy-dependencies -DoutputDirectory=target/lib -DincludeScope=runtime
if not exist "target\lib" (
    echo.
    echo [错误] 依赖准备失败，请检查网络或 Maven 配置。
    pause
    exit /b 1
)
echo.

:run
echo 正在启动，看到 "Started AgentHubApplication" 后即可访问...
echo.
"%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -cp "target\classes;target\lib\*" com.goocidata.agent.AgentHubApplication --server.port=8899

echo.
echo 服务已停止。
pause
