# Builds (first time, or with -Rebuild) and starts the admin UI + newsroom for one environment, bound to 127.0.0.1.
# The backend address is fixed at build time, so each environment gets its own copy under .local/run/ui-<env>.
#   .\scripts\run\start-ui.ps1 -Target daily
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [switch]$Rebuild
)
. "$PSScriptRoot\common.ps1"
$cfg = Get-RunEnv $Target
if (-not $NodeDir) { throw 'node not found: put $NodeDir in .local/run/machine.ps1' }
$src = Join-Path $ProjectRoot 'admin-ui'
$dst = Join-Path $RunDir "ui-$Target"
if ($Rebuild -or -not (Test-Path (Join-Path $dst '.next'))) {
    robocopy $src $dst /MIR /XD node_modules .next /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "copy failed ($LASTEXITCODE)" }
    if (-not (Test-Path (Join-Path $dst 'node_modules'))) {
        if (-not (Test-Path (Join-Path $src 'node_modules'))) { throw "run 'npm install' in admin-ui first" }
        New-Item -ItemType Junction -Path (Join-Path $dst 'node_modules') -Target (Join-Path $src 'node_modules') | Out-Null
    }
    $savedBackendUrl = $env:BACKEND_URL                # restored below: this terminal's variables stay as they were
    $env:BACKEND_URL = "http://127.0.0.1:$($cfg.BackendPort)"
    Push-Location $dst
    $ErrorActionPreference = 'Continue'      # see start-backend.ps1
    try {
        & (Join-Path $NodeDir 'npm.cmd') run build
        if ($LASTEXITCODE -ne 0) { throw "UI build failed ($LASTEXITCODE)" }
    } finally { Pop-Location; $env:BACKEND_URL = $savedBackendUrl }
}
Write-Host "[$Target] admin http://127.0.0.1:$($cfg.UiPort)/  newsroom http://127.0.0.1:$($cfg.UiPort)/news  -> backend 127.0.0.1:$($cfg.BackendPort)"
$ErrorActionPreference = 'Continue'
Push-Location $dst
try { & (Join-Path $NodeDir 'node.exe') node_modules\next\dist\bin\next start -H 127.0.0.1 -p $cfg.UiPort } finally { Pop-Location }
