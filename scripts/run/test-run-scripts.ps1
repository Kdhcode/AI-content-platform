# Self-check for the run scripts (common.ps1, build.ps1, start-db.ps1, check-env.ps1). Works on Windows PowerShell 5.1
# and PowerShell 7. Runs against a copy in a temporary folder: starts no server, touches no database, needs no Maven,
# Node or PostgreSQL, and leaves the project untouched.
#   .\scripts\run\test-run-scripts.ps1          # exit code 1 when a check fails
$ErrorActionPreference = 'Stop'
$src = (Resolve-Path "$PSScriptRoot\..\..").Path
$root = Join-Path ([IO.Path]::GetTempPath()) ("run-scripts-test-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$shell = (Get-Process -Id $PID).Path                 # the same powershell.exe / pwsh that runs this test
$failures = 0

function Check([string]$Name, [bool]$Ok, [string]$Detail = '') {
    if ($Ok) { Write-Host "PASS  $Name" -ForegroundColor Green }
    else { Write-Host "FAIL  $Name $Detail" -ForegroundColor Red; $script:failures++ }
}
function Get-FreePort {
    $l = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
    $l.Start(); $p = $l.LocalEndpoint.Port; $l.Stop(); return $p
}
function Set-Machine([string]$Text) { Set-Content -Path (Join-Path $root '.local\run\machine.ps1') -Value $Text -Encoding UTF8 }
# Runs a script of the copy in a child shell and returns exit code + all output as one string
function Invoke-Copy([string]$Script, [string[]]$Arguments = @(), [hashtable]$Env = @{}) {
    $saved = @{}
    foreach ($k in $Env.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k, 'Process'); [Environment]::SetEnvironmentVariable($k, $Env[$k], 'Process') }
    # Windows PowerShell 5.1 turns the child's redirected stderr into a terminating error under 'Stop'
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root "scripts\run\$Script") @Arguments 2>&1 | Out-String
        return @{ Code = $LASTEXITCODE; Out = $out }
    } finally {
        foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k], 'Process') }
    }
}

try {
    foreach ($d in 'scripts\run', 'backend', 'admin-ui', '.local\run') { New-Item -ItemType Directory -Force (Join-Path $root $d) | Out-Null }
    Copy-Item (Join-Path $src 'scripts\run\*.ps1') (Join-Path $root 'scripts\run')
    Copy-Item (Join-Path $src 'backend\pom.xml') (Join-Path $root 'backend')
    Copy-Item (Join-Path $src 'docker-compose.yml') $root
    Copy-Item (Join-Path $src 'admin-ui\package-lock.json') (Join-Path $root 'admin-ui')
    $nowhere = Join-Path $root 'nowhere'
    $closedPort = Get-FreePort

    # 1. machine.ps1 is read after the defaults, so it can replace them
    Set-Machine "`$MavenCmd = '$nowhere\mvn.cmd'`r`n`$PgBin = '$nowhere\pg'`r`n`$PgData = '$nowhere\data'`r`n`$DbPort = 5432"
    $probe = Join-Path $root 'scripts\run\probe.ps1'
    Set-Content $probe -Encoding UTF8 -Value @'
. "$PSScriptRoot\common.ps1"
Write-Output "PGBIN=$PgBin"; Write-Output "PGDATA=$PgData"; Write-Output "PORT=$DbPort"; Write-Output "PORTTYPE=$($DbPort.GetType().Name)"
'@
    $r = Invoke-Copy 'probe.ps1'
    Check 'machine.ps1 overrides PgBin' ($r.Out -match [regex]::Escape("PGBIN=$nowhere\pg")) $r.Out
    Check 'machine.ps1 overrides PgData' ($r.Out -match [regex]::Escape("PGDATA=$nowhere\data")) $r.Out
    Check 'machine.ps1 overrides DbPort (as a number)' ($r.Out -match 'PORT=5432' -and $r.Out -match 'PORTTYPE=Int32') $r.Out

    # 2. default port when machine.ps1 does not set one
    Set-Machine "`$PgBin = '$nowhere\pg'"
    $r = Invoke-Copy 'probe.ps1'
    Check 'default DbPort is 55432' ($r.Out -match 'PORT=55432') $r.Out

    # 3. an invalid port is refused
    Set-Machine "`$DbPort = 'abc'"
    $r = Invoke-Copy 'probe.ps1'
    Check 'invalid DbPort is refused' ($r.Code -ne 0 -and $r.Out -match 'must be a port number') $r.Out
    Set-Machine "`$DbPort = 70000"
    $r = Invoke-Copy 'probe.ps1'
    Check 'out-of-range DbPort is refused' ($r.Code -ne 0 -and $r.Out -match 'must be a port number') $r.Out

    # 4. build.ps1 stops with a clear message when Maven is missing (PATH without mvn)
    Set-Machine "`$MavenCmd = '$nowhere\mvn.cmd'"
    $r = Invoke-Copy 'build.ps1' -Env @{ PATH = $nowhere }
    Check 'build.ps1 explains missing Maven' ($r.Code -ne 0 -and $r.Out -match 'Maven not found') $r.Out

    # 5. start-db.ps1 without PostgreSQL tools
    Set-Machine "`$PgBin = '$nowhere\pg'`r`n`$DbPort = $closedPort"
    $r = Invoke-Copy 'start-db.ps1'
    Check 'start-db.ps1 explains missing PostgreSQL' ($r.Code -ne 0 -and $r.Out -match 'PostgreSQL tools not found' -and $r.Out -match "127\.0\.0\.1:$closedPort") $r.Out
    $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        $openPort = $listener.LocalEndpoint.Port
        Set-Machine "`$PgBin = '$nowhere\pg'`r`n`$DbPort = $openPort"
        $r = Invoke-Copy 'start-db.ps1'
        Check 'start-db.ps1 accepts a DB it did not start (docker)' ($r.Code -eq 0 -and $r.Out -match "Something is listening on 127\.0\.0\.1:$openPort") $r.Out
    } finally { $listener.Stop() }

    # 6. check-env.ps1: reports what is missing and never prints variable values
    Set-Machine "`$PgBin = '$nowhere\pg'`r`n`$MavenCmd = '$nowhere\mvn.cmd'`r`n`$NodeDir = '$nowhere\node'`r`n`$DbPort = $closedPort"
    $secrets = @{ DB_PASSWORD = 'pw-0f3a9c-must-not-show'; OPENAI_API_KEY = 'sk-test-7d1e-must-not-show'; ADMIN_PASSWORD = 'adm-55b2-must-not-show'; SPRING_PROFILES_ACTIVE = 'local-verify' }
    $r = Invoke-Copy 'check-env.ps1' -Env $secrets
    Check 'check-env exit code 1 when something is missing' ($r.Code -eq 1) "code $($r.Code)"
    Check 'check-env reports the missing database server' ($r.Out -match "\[MISSING\] database server\s+nothing listens on 127\.0\.0\.1:$closedPort") $r.Out
    Check 'check-env reports missing admin-ui deps' ($r.Out -match '\[MISSING\] admin-ui deps') $r.Out
    Check 'check-env reports DB_PASSWORD as set' ($r.Out -match '\[OK\s*\] DB_PASSWORD\s+set') $r.Out
    Check 'check-env names blocked variables' ($r.Out -match 'blocked variables.*SPRING_PROFILES_ACTIVE') $r.Out
    $leaked = @($secrets.Keys | Where-Object { $_ -ne 'SPRING_PROFILES_ACTIVE' -and $r.Out.Contains($secrets[$_]) })
    Check 'check-env prints no secret values' ($leaked.Count -eq 0) "leaked: $($leaked -join ', ')"
} finally {
    Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
}
Write-Host ''
if ($failures -gt 0) { Write-Host "$failures check(s) failed" -ForegroundColor Red; exit 1 }
Write-Host 'all checks passed' -ForegroundColor Green
exit 0
