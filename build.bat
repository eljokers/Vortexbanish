@echo off
chcp 65001 >nul
title VortexMC-banish Builder

echo ==========================================
echo       VortexMC-banish - Build
echo ==========================================
echo.

where mvn >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Maven is not installed or not in PATH.
    echo Install Apache Maven and make sure "mvn" works in Command Prompt.
    echo.
    pause
    exit /b 1
)

call mvn clean package
if errorlevel 1 (
    echo.
    echo [ERROR] Build failed. Read the Maven error above.
    pause
    exit /b 1
)

if not exist "target\VortexMC-banish.jar" (
    echo [ERROR] JAR was not created in target.
    pause
    exit /b 1
)

copy /Y "target\VortexMC-banish.jar" "VortexMC-banish.jar" >nul
echo.
echo [SUCCESS] Created: %CD%\VortexMC-banish.jar
echo Copy this JAR into your server's plugins folder, then restart.
echo.
pause
