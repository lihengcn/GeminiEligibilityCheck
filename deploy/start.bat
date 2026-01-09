@echo off
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "JAR_FILE=%SCRIPT_DIR%app.jar"

:run
if not exist "%JAR_FILE%" (
  echo No executable jar found. Please place app.jar in the current directory.
  exit /b 1
)

rem Default to SQLite with a local db file in deploy\.
java -Dspring.profiles.active=sqlite -Dspring.datasource.url=jdbc:sqlite:%SCRIPT_DIR%gem.db -jar "%JAR_FILE%" %*
endlocal
