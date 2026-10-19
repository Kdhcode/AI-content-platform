# Starts one backend in the foreground (Ctrl+C stops it). Always bound to 127.0.0.1.
#   .\scripts\run\start-backend.ps1 -Target daily
#   .\scripts\run\start-backend.ps1 -Target daily -Ai openai     # paid: needs OPENAI_API_KEY and AI_DAILY_MAX_CALLS
#
# Every setting that decides the environment (profile, login bypass, AI provider, database, address, port) is passed
# as a command-line argument, which Spring Boot ranks above environment variables and config files, so values left
# in this terminal cannot change it. Variables that could still add profiles or config files make the script stop.
# This terminal's environment variables are not modified.
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [ValidateSet('', 'none', 'openai')][string]$Ai = ''
)
. "$PSScriptRoot\common.ps1"
$cfg = Get-RunEnv $Target
if (-not (Test-Path $BackendJar)) { throw "no backend jar: run .\scripts\run\build.ps1 first" }

# Leftovers that command-line arguments cannot fully neutralise (extra profiles / config files / JVM properties)
$risky = @(Get-RiskyEnvNames)
if ($risky.Count -gt 0) {
    throw "these environment variables could change the environment; clear them in this terminal first (Remove-Item Env:NAME): $($risky -join ', ')"
}

$provider = $cfg.Ai
if ($Ai) {
    if ($cfg.Vectors -ne 'real' -or $cfg.Profile) { throw "-Ai is only for the daily environment (real embeddings, login on)" }
    $provider = $Ai
}
$springProfile = if ($cfg.Profile) { $cfg.Profile } elseif ($provider -eq 'openai') { 'live' } else { 'default' }
if ($provider -eq 'openai') {
    if (-not $env:OPENAI_API_KEY) { throw 'OPENAI_API_KEY is not set in this terminal' }
    if (-not $env:AI_DAILY_MAX_CALLS -or [int]$env:AI_DAILY_MAX_CALLS -le 0) { throw 'set AI_DAILY_MAX_CALLS (> 0) before enabling paid AI' }
}

$args2 = @('-jar', $BackendJar,
    "--spring.profiles.active=$springProfile",                  # live (paid AI only) brings the model prices and RSS sources
    "--app.local-verify.login-bypass=$(if ($cfg.Profile -eq 'local-verify') { 'true' } else { 'false' })",
    "--app.ai.provider=$provider",
    "--spring.datasource.url=jdbc:postgresql://127.0.0.1:$DbPort/$($cfg.Db)",
    '--server.address=127.0.0.1',
    "--server.port=$($cfg.BackendPort)",
    '--app.news.scheduler-enabled=false')                 # collection only by hand from the admin screen
if ($provider -ne 'openai') { $args2 += '--app.ai.openai.api-key=' }   # a key left in this terminal is ignored
if (-not $env:DB_PASSWORD) { $args2 += '--spring.datasource.password=local-trust' }   # trust auth ignores it
if ($cfg.NoWorker) { $args2 += '--app.worker.enabled=false' }

# Values with non-ASCII text or JSON go through the child process environment only, restored afterwards
$childEnv = @{}
if ($cfg.Label) { $childEnv['LOCAL_VERIFY_DATA_LABEL'] = $cfg.Label }
if ($Target -eq 'verify-scenario') {
    $fx = (Join-Path $ProjectRoot 'docs\verify\scenario') -replace '\\', '/'
    $sources = (1..3 | ForEach-Object { '{"name":"scenario-round' + $_ + '","type":"FIXTURE","base-url":"' + $fx + '/round' + $_ + '.json","config":{"publisher":"scenario"}}' }) -join ','
    $childEnv['SPRING_APPLICATION_JSON'] = '{"app":{"news":{"sources":[' + $sources + ']}}}'
}
$saved = @{}
foreach ($k in $childEnv.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k, 'Process'); [Environment]::SetEnvironmentVariable($k, $childEnv[$k], 'Process') }

Write-Host "[$Target] DB $($cfg.Db) ($($cfg.Vectors) embeddings), http://127.0.0.1:$($cfg.BackendPort), AI $provider, profile $springProfile$(if ($cfg.Profile -eq 'local-verify') { ', local-verify (no login)' } else { ', admin login required' })"
# Windows PowerShell turns a native program's stderr into errors when output is redirected; do not stop on them
$ErrorActionPreference = 'Continue'
try {
    & $JavaExe @args2
} finally {
    foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k], 'Process') }
}
