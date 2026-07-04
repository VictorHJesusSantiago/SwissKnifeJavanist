@echo off
if not exist "%TEMP%\apache-maven-3.9.11\bin\mvn.cmd" (
  echo Maven 3.9.11 deve estar disponivel em %%TEMP%%\apache-maven-3.9.11.
  exit /b 1
)
call "%TEMP%\apache-maven-3.9.11\bin\mvn.cmd" test
exit /b %ERRORLEVEL%
