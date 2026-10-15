# Starts the project PostgreSQL (127.0.0.1:55432, data in .local/pgdata) if it is not running.
# Run it from your own terminal: a database started from a tool session can be stopped when that session ends.
. "$PSScriptRoot\common.ps1"
$ErrorActionPreference = 'Continue'          # see start-backend.ps1
& "$PgBin\pg_isready.exe" -h 127.0.0.1 -p 55432 -U aicontent | Out-Null
if ($LASTEXITCODE -eq 0) { Write-Host 'PostgreSQL is already running on 127.0.0.1:55432'; return }
& "$PgBin\pg_ctl.exe" -D $PgData -l (Join-Path $ProjectRoot '.local\postgres-server.log') -o '-p 55432 -h 127.0.0.1' -w start
& "$PgBin\pg_isready.exe" -h 127.0.0.1 -p 55432 -U aicontent
