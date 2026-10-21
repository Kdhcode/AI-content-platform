# Starts the project PostgreSQL (127.0.0.1:$DbPort, default 55432, data in .local/pgdata) if it is not running.
# Run it from your own terminal: a database started from a tool session can be stopped when that session ends.
# With docker compose instead (set $DbPort = 5432 in .local/run/machine.ps1) start it with "docker compose up -d".
. "$PSScriptRoot\common.ps1"
$ErrorActionPreference = 'Continue'          # see start-backend.ps1
$pgIsReady = Join-Path $PgBin 'pg_isready.exe'
$pgCtl = Join-Path $PgBin 'pg_ctl.exe'
if (-not (Test-Path $pgCtl) -or -not (Test-Path $pgIsReady)) {
    $open = $false
    $tcp = New-Object System.Net.Sockets.TcpClient
    try { $open = $tcp.ConnectAsync('127.0.0.1', $DbPort).Wait(2000) -and $tcp.Connected } catch { $open = $false } finally { $tcp.Dispose() }
    if ($open) { Write-Host "Something is listening on 127.0.0.1:$DbPort (not started by this script; PostgreSQL tools not found in $PgBin)"; return }
    throw ("PostgreSQL tools not found in $PgBin and nothing listens on 127.0.0.1:$DbPort. " +
        'Set up the project DB (docs/DB_SETUP.md) or use docker compose with $DbPort = 5432 in .local/run/machine.ps1, ' +
        'then check with .\scripts\run\check-env.ps1')
}
& $pgIsReady -h 127.0.0.1 -p $DbPort -U aicontent | Out-Null
if ($LASTEXITCODE -eq 0) { Write-Host "PostgreSQL is already running on 127.0.0.1:$DbPort"; return }
if (-not (Test-Path (Join-Path $PgData 'PG_VERSION'))) {
    throw "no database cluster in ${PgData}: create it once with initdb and createdb (docs/DB_SETUP.md)"
}
& $pgCtl -D $PgData -l (Join-Path $ProjectRoot '.local\postgres-server.log') -o "-p $DbPort -h 127.0.0.1" -w start
& $pgIsReady -h 127.0.0.1 -p $DbPort -U aicontent
