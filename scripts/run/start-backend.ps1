# Starts one backend in the foreground (Ctrl+C stops it). Always bound to 127.0.0.1.
#   .\scripts\run\start-backend.ps1 -Target daily
#   .\scripts\run\start-backend.ps1 -Target daily -Ai openai     # paid: needs OPENAI_API_KEY and AI_DAILY_MAX_CALLS
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [ValidateSet('', 'none', 'openai')][string]$Ai = ''
)
. "$PSScriptRoot\common.ps1"
$cfg = Get-RunEnv $Target
if (-not (Test-Path $BackendJar)) { throw "no backend jar: run .\scripts\run\build.ps1 first" }

$provider = $cfg.Ai
if ($Ai) {
    if ($cfg.Vectors -ne 'real' -or $cfg.Profile) { throw "-Ai is only for the daily environment (real embeddings, login on)" }
    $provider = $Ai
}
if ($provider -eq 'openai') {
    if (-not $env:OPENAI_API_KEY) { throw 'OPENAI_API_KEY is not set in this terminal' }
    if (-not $env:AI_DAILY_MAX_CALLS -or [int]$env:AI_DAILY_MAX_CALLS -le 0) { throw 'set AI_DAILY_MAX_CALLS (> 0) before enabling paid AI' }
} else {
    $env:OPENAI_API_KEY = $null          # a key is never needed without the openai provider
}

$env:AI_PROVIDER = $provider
$env:DB_URL = "jdbc:postgresql://127.0.0.1:55432/$($cfg.Db)"
$env:PORT = "$($cfg.BackendPort)"
if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = 'local-trust' }    # local trust auth ignores it; the setting is required
$env:SPRING_APPLICATION_JSON = $null
$args2 = @('-jar', $BackendJar, '--server.address=127.0.0.1', '--app.news.scheduler-enabled=false')
if ($cfg.Profile) {
    $args2 += "--spring.profiles.active=$($cfg.Profile)"
    $env:LOCAL_VERIFY_DATA_LABEL = $cfg.Label
} else {
    $env:LOCAL_VERIFY_DATA_LABEL = $null
}
if ($cfg.NoWorker) { $args2 += '--app.worker.enabled=false' }
if ($Target -eq 'verify-scenario') {
    $fx = (Join-Path $ProjectRoot 'docs\verify\scenario') -replace '\\', '/'
    $sources = (1..3 | ForEach-Object { '{"name":"scenario-round' + $_ + '","type":"FIXTURE","base-url":"' + $fx + '/round' + $_ + '.json","config":{"publisher":"scenario"}}' }) -join ','
    $env:SPRING_APPLICATION_JSON = '{"app":{"news":{"sources":[' + $sources + ']}}}'
}
Write-Host "[$Target] DB $($cfg.Db) ($($cfg.Vectors) embeddings), http://127.0.0.1:$($cfg.BackendPort), AI $provider$(if ($cfg.Profile) { ', local-verify (no login)' } else { ', admin login required' })"
# Windows PowerShell turns a native program's stderr into errors when output is redirected; do not stop on them
$ErrorActionPreference = 'Continue'
& $JavaExe @args2
