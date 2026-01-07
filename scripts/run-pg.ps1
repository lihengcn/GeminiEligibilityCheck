$ErrorActionPreference = "Stop"

Set-Location (Split-Path -Parent $PSScriptRoot)

# Ensure PostgreSQL is running (Scoop install).
if (Test-Path ".\\scripts\\pg.ps1") {
  try { & .\\scripts\\pg.ps1 start | Out-Null } catch { }
}

$scoopRoot = if ($env:SCOOP) { $env:SCOOP } else { "C:\\Scoop" }
$javaHome = Join-Path $scoopRoot "apps\\temurin17-jdk\\current"
$mvn = Join-Path $scoopRoot "apps\\maven\\current\\bin\\mvn.cmd"
$java = Join-Path $javaHome "bin\\java.exe"

if (!(Test-Path $java)) { throw "Java not found: $java (install via: scoop install temurin17-jdk)" }
if (!(Test-Path $mvn)) { throw "Maven not found: $mvn (install via: scoop install maven)" }

$env:JAVA_HOME = $javaHome
$env:PATH = (Join-Path $javaHome "bin") + ";" + $env:PATH

if ($args -contains "--build") {
  & $mvn -DskipTests package
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$jar = Get-ChildItem -Path "target" -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jar) { throw "Jar not found under .\\target\\. Run: .\\scripts\\run-pg.ps1 --build" }

Write-Host "Starting with PostgreSQL profile: $($jar.FullName)"
& $java -jar $jar.FullName --spring.profiles.active=postgres
