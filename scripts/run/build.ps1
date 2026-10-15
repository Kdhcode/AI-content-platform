# Builds the backend jar (tests skipped: run "mvn test" separately) and copies it to .local/run so a running server
# is not affected by later builds. Stop the backends that use .local/run/platform-backend.jar before rebuilding.
. "$PSScriptRoot\common.ps1"
Push-Location (Join-Path $ProjectRoot 'backend')
$ErrorActionPreference = 'Continue'          # see start-backend.ps1
try {
    & $MavenCmd -B "-Dmaven.repo.local=$MavenRepo" -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "maven build failed ($LASTEXITCODE)" }
} finally { Pop-Location }
Copy-Item (Join-Path $ProjectRoot 'backend\target\platform-backend-0.1.0-SNAPSHOT.jar') $BackendJar -Force
Write-Host "backend jar: $BackendJar"
