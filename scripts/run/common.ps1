# Shared settings for the local run scripts (docs/RUNBOOK.md). Dot-sourced by the other scripts.
# Machine-specific paths go in .local/run/machine.ps1 (not in Git), for example:
#   $env:JAVA_HOME = 'C:\Users\me\.jdks\temurin-25'
#   $NodeDir = 'C:\path\to\node'          # folder with node.exe and npm.cmd
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
$RunDir = Join-Path $ProjectRoot '.local\run'
New-Item -ItemType Directory -Force $RunDir | Out-Null
$NodeDir = $null
$MachineFile = Join-Path $RunDir 'machine.ps1'
if (Test-Path $MachineFile) { . $MachineFile }

$PgBin = Join-Path $ProjectRoot '.local\pg16\pgsql\bin'
$PgData = Join-Path $ProjectRoot '.local\pgdata'
$MavenCmd = Join-Path $ProjectRoot '.local\apache-maven-3.9.9\bin\mvn.cmd'
$MavenRepo = Join-Path $ProjectRoot '.local\m2'
if (-not $NodeDir) {
    $node = Get-Command node -ErrorAction SilentlyContinue
    if ($node) { $NodeDir = Split-Path $node.Source }
}
$JavaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$BackendJar = Join-Path $RunDir 'platform-backend.jar'

# One entry per environment. Real-AI data and stub data never share a DB:
#   daily            real OpenAI embeddings, admin login, AI off unless -Ai openai is given
#   dev              stub embeddings only, admin login
#   verify-scenario  local-verify (no login, loopback, stub), scenario fixtures
#   verify-real      local-verify (no login, loopback, AI off, worker off), copy of real data
#   verify-content   local-verify (login optional, loopback, stub, worker off), content screens (A7) on a scenario copy
$Envs = @{
    'daily'           = @{ Db = 'aicontent_main';            BackendPort = 8080;  UiPort = 3000;  Ai = 'none'; Vectors = 'real'; Profile = '' }
    'dev'             = @{ Db = 'aicontent';                 BackendPort = 8081;  UiPort = 3001;  Ai = 'stub'; Vectors = 'stub'; Profile = '' }
    'verify-scenario' = @{ Db = 'aicontent_verify_scenario'; BackendPort = 18091; UiPort = 13091; Ai = 'stub'; Vectors = 'stub'; Profile = 'local-verify'; Label = '시나리오 데이터 (가상 기사, aicontent_verify_scenario)' }
    'verify-real'     = @{ Db = 'aicontent_verify_real';     BackendPort = 18092; UiPort = 13092; Ai = 'none'; Vectors = 'real'; Profile = 'local-verify'; Label = '실제 기사 복사본 (2026-10-14 수집, aicontent_verify_real)'; NoWorker = $true }
    'verify-content'  = @{ Db = 'aicontent_verify_content';  BackendPort = 18093; UiPort = 13093; Ai = 'stub'; Vectors = 'stub'; Profile = 'local-verify'; Label = '콘텐츠 화면 검증용 데이터 (시나리오 복사본, aicontent_verify_content)'; NoWorker = $true }
}

function Get-RunEnv([string]$Name) {
    if (-not $Envs.ContainsKey($Name)) { throw "unknown environment '$Name' (daily, dev, verify-scenario, verify-real, verify-content)" }
    return $Envs[$Name]
}
