$ErrorActionPreference = "Stop"

Set-Location (Split-Path -Parent $PSScriptRoot)
$servicesDir = Join-Path (Get-Location) "services"

$pgExe = Join-Path $servicesDir "PostgreSQLScoop.exe"
$appExe = Join-Path $servicesDir "GeminiEligibilityCheck.exe"

if (Test-Path $appExe) {
  try { & $appExe stop | Out-Null } catch {}
  try { & $appExe uninstall | Out-Null } catch {}
}
if (Test-Path $pgExe) {
  try { & $pgExe stop | Out-Null } catch {}
  try { & $pgExe uninstall | Out-Null } catch {}
}

sc.exe query PostgreSQLScoop 2>$null
sc.exe query GeminiEligibilityCheck 2>$null

