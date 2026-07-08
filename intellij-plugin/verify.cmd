@echo off
setlocal
set "GRADLE_BIN=%TEMP%\gradle-8.13\bin\gradle.bat"
if not exist "%GRADLE_BIN%" (
  echo Gradle 8.13 nao encontrado em %TEMP%\gradle-8.13.
  exit /b 1
)
if not defined JAVA_HOME (
  echo JAVA_HOME deve apontar para um JDK 21.
  exit /b 1
)
call "%GRADLE_BIN%" --no-daemon clean buildPlugin verifyPlugin
exit /b %ERRORLEVEL%
