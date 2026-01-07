@echo off
setlocal

cd /d "%~dp0\.."

set "SCOOP_ROOT=%SCOOP%"
if "%SCOOP_ROOT%"=="" set "SCOOP_ROOT=C:\Scoop"

set "JAVA_HOME=%SCOOP_ROOT%\apps\temurin17-jdk\current"
set "MVN=%SCOOP_ROOT%\apps\maven\current\bin\mvn.cmd"
set "JAVA=%JAVA_HOME%\bin\java.exe"

if not exist "%JAVA%" (
  echo Java not found: %JAVA%
  echo Install: scoop install temurin17-jdk
  exit /b 1
)
if not exist "%MVN%" (
  echo Maven not found: %MVN%
  echo Install: scoop install maven
  exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%PATH%"

if "%1"=="--build" (
  call "%MVN%" -DskipTests package
  if errorlevel 1 exit /b %errorlevel%
)

for %%f in ("target\*.jar") do (
  echo Starting: %%f
  "%JAVA%" -jar "%%f"
  exit /b %errorlevel%
)

echo Jar not found under .\target\. Run: scripts\run.cmd --build
exit /b 1

