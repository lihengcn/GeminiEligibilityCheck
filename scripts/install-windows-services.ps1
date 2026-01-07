$ErrorActionPreference = "Stop"

Set-Location (Split-Path -Parent $PSScriptRoot)

$winswUrl = "https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe"
$servicesDir = Join-Path (Get-Location) "services"

$pgExe = Join-Path $servicesDir "PostgreSQLScoop.exe"
$pgXml = Join-Path $servicesDir "PostgreSQLScoop.xml"
$appExe = Join-Path $servicesDir "GeminiEligibilityCheck.exe"
$appXml = Join-Path $servicesDir "GeminiEligibilityCheck.xml"

if (!(Test-Path $servicesDir)) { throw "Missing services dir: $servicesDir" }
if (!(Test-Path $pgXml)) { throw "Missing config: $pgXml" }
if (!(Test-Path $appXml)) { throw "Missing config: $appXml" }

Write-Host "Downloading WinSW..."
Invoke-WebRequest -Uri $winswUrl -OutFile $pgExe
Copy-Item -Force $pgExe $appExe

Write-Host "Installing PostgreSQL service..."
& $pgExe install
sc.exe config PostgreSQLScoop start= auto | Out-Null
sc.exe start PostgreSQLScoop | Out-Null

Write-Host "Installing app service..."
& $appExe install
sc.exe config GeminiEligibilityCheck start= auto | Out-Null
sc.exe config GeminiEligibilityCheck depend= PostgreSQLScoop | Out-Null
sc.exe start GeminiEligibilityCheck | Out-Null

sc.exe query PostgreSQLScoop
sc.exe query GeminiEligibilityCheck

