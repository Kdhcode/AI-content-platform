# Shared settings for the local run scripts (docs/RUNBOOK.md). Dot-sourced by the other scripts.
# Machine-specific paths go in .local/run/machine.ps1 (not in Git). It is read after the defaults below, so any of
# them can be replaced there, for example:
#   $env:JAVA_HOME = 'C:\Users\me\.jdks\temurin-25'
#   $NodeDir  = 'C:\path\to\node'                     # folder with node.exe and npm.cmd
#   $MavenCmd = 'C:\tools\apache-maven-3.9.9\bin\mvn.cmd'
#   $PgBin    = 'C:\tools\pgsql\bin'                  # folder with pg_ctl.exe, pg_isready.exe, psql.exe
#   $PgData   = 'C:\tools\pgdata'
#   $DbPort   = 5432                                  # docker compose (docker-compose.yml) instead of the project DB
# Check the result with .\scripts\run\check-env.ps1.
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
$RunDir = Join-Path $ProjectRoot '.local\run'
New-Item -ItemType Directory -Force $RunDir | Out-Null

# Defaults: the project-local PostgreSQL and Maven described in docs/DB_SETUP.md
$NodeDir = $null
$PgBin = Join-Path $ProjectRoot '.local\pg16\pgsql\bin'
$PgData = Join-Path $ProjectRoot '.local\pgdata'
$MavenCmd = Join-Path $ProjectRoot '.local\apache-maven-3.9.9\bin\mvn.cmd'
$MavenRepo = Join-Path $ProjectRoot '.local\m2'
$DbPort = 55432                                       # the database is always reached on 127.0.0.1

$MachineFile = Join-Path $RunDir 'machine.ps1'
if (Test-Path $MachineFile) { . $MachineFile }

$DbPortValue = 0
if (-not [int]::TryParse([string]$DbPort, [ref]$DbPortValue) -or $DbPortValue -lt 1 -or $DbPortValue -gt 65535) {
    throw "DbPort in $MachineFile must be a port number (1-65535), got '$DbPort'"
}
$DbPort = $DbPortValue
if (-not (Test-Path $MavenCmd)) {                     # not the configured path: fall back to Maven on PATH
    $mvn = Get-Command mvn.cmd, mvn -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($mvn) { $MavenCmd = $mvn.Source }
}
if (-not $NodeDir) {
    $node = Get-Command node -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
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

# Environment variables that command-line arguments cannot fully neutralise (extra profiles / config files / JVM
# properties). start-backend.ps1 refuses to start while any is set; check-env.ps1 lists them. Names only, never values.
function Get-RiskyEnvNames {
    Get-ChildItem Env: | Where-Object {
        $_.Name -match '^(SPRING_PROFILES_|SPRING_CONFIG_|SPRING_APPLICATION_JSON$|SPRING_DATASOURCE_|SERVER_ADDRESS$|APP_LOCAL_?VERIFY|APP_AI_PROVIDER$|APP_AI_OPENAI_|APP_AI_EMBEDDING_?GUARD)' -or
        ($_.Name -match '^(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS)$' -and $_.Value -match '-D')
    } | ForEach-Object { $_.Name }
}

function Get-RunEnv([string]$Name) {
    if (-not $Envs.ContainsKey($Name)) { throw "unknown environment '$Name' (daily, dev, verify-scenario, verify-real, verify-content)" }
    return $Envs[$Name]
}
