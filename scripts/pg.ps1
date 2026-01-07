$ErrorActionPreference = "Stop"

$scoopRoot = if ($env:SCOOP) { $env:SCOOP } else { "C:\\Scoop" }
$pgRoot = Join-Path $scoopRoot "apps\\postgresql\\current"
$bin = Join-Path $pgRoot "bin"
$data = Join-Path $pgRoot "data"
$log = Join-Path $data "postgres.log"

$pgCtl = Join-Path $bin "pg_ctl.exe"
$psql = Join-Path $bin "psql.exe"
$isReady = Join-Path $bin "pg_isready.exe"

if (!(Test-Path $pgCtl)) { throw "PostgreSQL not found: $pgCtl (install via: scoop install postgresql)" }

$action = if ($args.Count -gt 0) { $args[0] } else { "status" }

switch ($action) {
  "start" {
    & $pgCtl -D $data status | Out-Null
    if ($LASTEXITCODE -ne 0) {
      & $pgCtl -D $data -l $log start
    }
    & $isReady -h 127.0.0.1 -p 5432
  }
  "stop" {
    & $pgCtl -D $data stop
  }
  "status" {
    & $pgCtl -D $data status
    if ($LASTEXITCODE -ne 0) { exit 0 }
    & $isReady -h 127.0.0.1 -p 5432
  }
  "createdb" {
    $db = if ($args.Count -gt 1) { $args[1] } else { "gem" }
    & $psql -U postgres -h 127.0.0.1 -p 5432 -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$db'" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "psql failed; is postgres running?" }
    $exists = & $psql -U postgres -h 127.0.0.1 -p 5432 -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$db'"
    if (-not $exists) {
      & $psql -U postgres -h 127.0.0.1 -p 5432 -d postgres -c "CREATE DATABASE $db"
    } else {
      "Database $db already exists"
    }
  }
  default {
    throw "Usage: .\\scripts\\pg.ps1 [start|stop|status|createdb [name]]"
  }
}
