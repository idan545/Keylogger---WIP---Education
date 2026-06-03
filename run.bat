@echo off
:: Run KeyLogger using the bundled JDK 11
if not exist keylogger.jar (
    echo keylogger.jar not found -- run build.bat first.
    pause & exit /b 1
)

set "JAVA_BIN=%~dp0jdk\jdk-11.0.31+11\bin\java.exe"
if not exist "%JAVA_BIN%" set "JAVA_BIN=java"

"%JAVA_BIN%" -jar "%~dp0keylogger.jar"
