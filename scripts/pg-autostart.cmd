@echo off
setlocal

set "SCOOP_ROOT=%SCOOP%"
if "%SCOOP_ROOT%"=="" set "SCOOP_ROOT=C:\Scoop"

set "PG_CTL=%SCOOP_ROOT%\apps\postgresql\current\bin\pg_ctl.exe"
set "DATA=%SCOOP_ROOT%\apps\postgresql\current\data"
set "LOG=%DATA%\postgres.log"

if not exist "%PG_CTL%" exit /b 0

"%PG_CTL%" -D "%DATA%" -l "%LOG%" status >nul 2>&1
if errorlevel 1 (
  "%PG_CTL%" -D "%DATA%" -l "%LOG%" start >nul 2>&1
)

exit /b 0

