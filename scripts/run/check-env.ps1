# Read-only check of what this PC needs to build, test and run the project (docs/RUNBOOK.md 3절 "새 PC 준비").
# Prints versions, yes/no and file names only: never the value of a password, API key or other variable.
# Starts, stops, installs and writes nothing (apart from the empty .local/run folder common.ps1 always creates).
#   .\scripts\run\check-env.ps1          # local state only
#   .\scripts\run\check-env.ps1 -Fetch   # first "git fetch --prune origin" (updates remote branches, merges nothing)
# Exit code 1 when something required is missing.
param([switch]$Fetch)
. "$PSScriptRoot\common.ps1"
$ErrorActionPreference = 'Continue'          # see start-backend.ps1
$script:Missing = 0

function Report([string]$State, [string]$Name, [string]$Detail) {
    $color = switch ($State) { 'OK' { 'Green' } 'WARN' { 'Yellow' } 'MISSING' { 'Red' } default { 'Gray' } }
    if ($State -eq 'MISSING') { $script:Missing++ }
    Write-Host ('[{0,-7}] {1,-24} {2}' -f $State, $Name, $Detail) -ForegroundColor $color
}
function Section([string]$Title) { Write-Host ''; Write-Host "== $Title" }
# First output line of a native command (stdout or stderr), or $null when it cannot run
function Get-FirstLine([string]$Exe, [string[]]$Arguments) {
    try { $out = & $Exe @Arguments 2>&1 | Select-Object -First 1; if ($null -eq $out) { return $null }; return "$out".Trim() } catch { return $null }
}
function Get-Version([string]$Text) {
    if ($Text -match '(\d+)\.(\d+)(\.(\d+))?') { return [version]("{0}.{1}.{2}" -f $Matches[1], $Matches[2], $(if ($Matches[4]) { $Matches[4] } else { 0 })) }
    return $null
}
function Test-Port([int]$Port) {
    $tcp = New-Object System.Net.Sockets.TcpClient
    try { return ($tcp.ConnectAsync('127.0.0.1', $Port).Wait(1500) -and $tcp.Connected) } catch { return $false } finally { $tcp.Dispose() }
}
function Test-Var([string]$Name) { return -not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($Name, 'Process')) }

Write-Host "Project: $ProjectRoot"

# ---------------------------------------------------------------- Git
Section 'Git'
$git = Get-Command git -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $git) {
    Report 'MISSING' 'git' 'not on PATH'
} else {
    if ($Fetch) {
        & $git.Source -C $ProjectRoot fetch --prune origin 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { Report 'OK' 'git fetch' 'origin updated (nothing merged)' } else { Report 'WARN' 'git fetch' "failed ($LASTEXITCODE): remote comparison below uses the last fetch" }
    }
    $branch = Get-FirstLine $git.Source @('-C', $ProjectRoot, 'branch', '--show-current')
    $head = Get-FirstLine $git.Source @('-C', $ProjectRoot, 'log', '-1', '--format=%h %ci %s')
    Report 'INFO' 'branch' "$(if ($branch) { $branch } else { '(detached)' }) @ $head"
    $tracking = Get-FirstLine $git.Source @('-C', $ProjectRoot, 'status', '-sb')
    if ($tracking) { Report 'INFO' 'tracking' $tracking }
    $dirty = @(& $git.Source -C $ProjectRoot status --porcelain 2>$null)
    if ($dirty.Count -eq 0) { Report 'OK' 'uncommitted changes' 'none' }
    else { Report 'WARN' 'uncommitted changes' ("{0} file(s): {1}" -f $dirty.Count, (($dirty | Select-Object -First 8 | ForEach-Object { $_.Substring(3) }) -join ', ')) }
    # Remote branches with commits the current branch does not have (work done on another PC, not yet here)
    $remotes = @(& $git.Source -C $ProjectRoot for-each-ref refs/remotes/origin --format='%(refname:short)' 2>$null | Where-Object { $_ -and $_ -ne 'origin/HEAD' -and $_ -ne 'origin' })
    foreach ($r in $remotes) {
        $n = Get-FirstLine $git.Source @('-C', $ProjectRoot, 'rev-list', '--count', "HEAD..$r")
        if ($n -and [int]$n -gt 0) {
            $last = Get-FirstLine $git.Source @('-C', $ProjectRoot, 'log', '-1', '--format=%ci %s', $r)
            Report 'WARN' $r "$n commit(s) not in this branch (last: $last)"
        }
    }
}

# ---------------------------------------------------------------- Java / Maven
Section 'Java / Maven'
$pom = Get-Content (Join-Path $ProjectRoot 'backend\pom.xml') -Raw -ErrorAction SilentlyContinue
$javaRequired = if ($pom -match '<java\.version>(\d+)</java\.version>') { [int]$Matches[1] } else { 21 }
$javaLine = Get-FirstLine $JavaExe @('-version')
$javaVer = if ($javaLine -match 'version "([^"]+)"') { Get-Version $Matches[1] } else { $null }
if (-not $javaVer) { Report 'MISSING' 'java' "not found ($JavaExe); need JDK $javaRequired+ (set JAVA_HOME or `$env:JAVA_HOME in machine.ps1)" }
elseif ($javaVer.Major -lt $javaRequired) { Report 'MISSING' 'java' "$javaVer at $JavaExe; pom.xml needs $javaRequired+" }
else { Report 'OK' 'java' "$javaVer ($JavaExe; pom.xml needs $javaRequired+)" }
if (-not (Test-Var 'JAVA_HOME')) { Report 'WARN' 'JAVA_HOME' 'not set: Maven and the run scripts then use the java on PATH' }
if (Test-Path $MavenCmd) {
    $mvnLine = Get-FirstLine $MavenCmd @('-v')
    if ($mvnLine -match 'Apache Maven') { Report 'OK' 'maven' "$mvnLine ($MavenCmd)" } else { Report 'MISSING' 'maven' "$MavenCmd does not run: $mvnLine" }
} else {
    Report 'MISSING' 'maven' "not found ($MavenCmd, nor mvn on PATH); the repo has no Maven wrapper. Install Maven 3.9+ or set `$MavenCmd in machine.ps1"
}
if (Test-Path $BackendJar) { Report 'OK' 'backend jar' ("{0} ({1:yyyy-MM-dd HH:mm})" -f $BackendJar, (Get-Item $BackendJar).LastWriteTime) }
else { Report 'WARN' 'backend jar' 'not built yet: .\scripts\run\build.ps1' }

# ---------------------------------------------------------------- Node / admin UI
Section 'Node.js / admin-ui'
$nodeMin = [version]'18.17.0'                     # next 14.2 needs Node 18.17 or later
$uiDir = Join-Path $ProjectRoot 'admin-ui'
$nodeExe = if ($NodeDir) { Join-Path $NodeDir 'node.exe' } else { $null }
$npmCmd = if ($NodeDir) { Join-Path $NodeDir 'npm.cmd' } else { $null }
$nodeVer = if ($nodeExe -and (Test-Path $nodeExe)) { Get-Version (Get-FirstLine $nodeExe @('-v')) } else { $null }
if (-not $nodeVer) { Report 'MISSING' 'node' 'not found (PATH or $NodeDir in machine.ps1); install Node.js LTS' }
elseif ($nodeVer -lt $nodeMin) { Report 'MISSING' 'node' "$nodeVer at $nodeExe; need $nodeMin+" }
else { Report 'OK' 'node' "$nodeVer ($nodeExe)" }
if ($npmCmd -and (Test-Path $npmCmd)) { Report 'OK' 'npm' "$(Get-FirstLine $npmCmd @('-v')) ($npmCmd)" }
elseif ($nodeVer) { Report 'MISSING' 'npm' "npm.cmd not found next to node in $NodeDir" }
if (-not (Test-Path (Join-Path $uiDir 'package-lock.json'))) { Report 'MISSING' 'package-lock.json' 'admin-ui has no lock file' }
if (Test-Path (Join-Path $uiDir 'node_modules\next\package.json')) { Report 'OK' 'admin-ui deps' 'node_modules present' }
else { Report 'MISSING' 'admin-ui deps' 'run "npm ci" in admin-ui (uses package-lock.json)' }

# ---------------------------------------------------------------- PostgreSQL
Section "PostgreSQL (127.0.0.1:$DbPort)"
$psql = Join-Path $PgBin 'psql.exe'
if (-not (Test-Path $psql)) {
    $p = Get-Command psql -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    $psql = if ($p) { $p.Source } else { $null }
}
if (Test-Path (Join-Path $PgBin 'pg_ctl.exe')) { Report 'OK' 'project PostgreSQL' $PgBin }
else { Report 'INFO' 'project PostgreSQL' "no pg_ctl.exe in $PgBin (fine when the DB runs in docker or as a service)" }
$dbUp = Test-Port $DbPort
if (-not $dbUp) {
    Report 'MISSING' 'database server' "nothing listens on 127.0.0.1:$DbPort (.\scripts\run\start-db.ps1, or docker compose up -d with `$DbPort = 5432)"
} elseif (-not $psql) {
    Report 'WARN' 'database server' "port $DbPort open; psql not found, databases not checked"
} else {
    Report 'OK' 'database server' "port $DbPort open"
    # -w: never prompt. DB_PASSWORD is handed to psql through PGPASSWORD for this call only and never printed.
    $savedPgPassword = $env:PGPASSWORD
    if (-not (Test-Var 'PGPASSWORD') -and (Test-Var 'DB_PASSWORD')) { $env:PGPASSWORD = $env:DB_PASSWORD }
    try {
        $dbs = @(& $psql -w -h 127.0.0.1 -p $DbPort -U aicontent -d postgres -tA -c 'select datname from pg_database' 2>$null)
        if ($LASTEXITCODE -ne 0) {
            Report 'WARN' 'databases' "cannot log in as aicontent (exit $LASTEXITCODE): check the user, or set DB_PASSWORD in this terminal"
        } else {
            $needed = [ordered]@{ 'aicontent_test' = 'mvn test' }
            foreach ($k in $Envs.Keys) { $needed[$Envs[$k].Db] = "-Target $k" }
            foreach ($db in $needed.Keys) {
                if ($dbs -notcontains $db) {
                    Report $(if ($db -eq 'aicontent_test') { 'MISSING' } else { 'WARN' }) $db "missing (needed for $($needed[$db]); docs/DB_SETUP.md)"
                    continue
                }
                $conn = @('-w', '-h', '127.0.0.1', '-p', "$DbPort", '-U', 'aicontent', '-d', $db, '-tA', '-c')
                $vec = Get-FirstLine $psql ($conn + ("select coalesce((select extversion from pg_extension where extname = 'vector'), 'none') || '|' || " +
                    "coalesce((select default_version from pg_available_extensions where name = 'vector'), 'none') || '|' || " +
                    "coalesce(to_regclass('public.flyway_schema_history')::text, '')"))
                $parts = "$vec".Split('|')
                if ($parts.Count -ne 3) { Report 'WARN' $db "could not query: $vec"; continue }
                $schema = 'no tables yet (Flyway creates them on the first backend start or mvn test)'
                if ($parts[2]) {
                    $schema = 'V' + (Get-FirstLine $psql ($conn + "select coalesce(max(version::int)::text, '-') from flyway_schema_history where success and version ~ '^[0-9]+$'"))
                }
                if ($parts[1] -eq 'none') {
                    Report 'MISSING' $db "the server has no pgvector extension (Flyway V1 needs CREATE EXTENSION vector; use the pgvector build in docs/DB_SETUP.md or the docker image), schema $schema ($($needed[$db]))"
                } else {
                    $installed = if ($parts[0] -eq 'none') { 'not created yet' } else { $parts[0] }
                    Report 'OK' $db "pgvector $installed (server has $($parts[1])), schema $schema ($($needed[$db]))"
                }
            }
        }
    } finally { $env:PGPASSWORD = $savedPgPassword }
}
if (Test-Port 6379) { Report 'INFO' 'redis' 'port 6379 open (optional: app.lock.redis-enabled is off by default)' }
else { Report 'INFO' 'redis' 'not running (optional: app.lock.redis-enabled is off by default)' }

# ---------------------------------------------------------------- Docker
Section 'Docker'
$compose = Get-Content (Join-Path $ProjectRoot 'docker-compose.yml') -Raw -ErrorAction SilentlyContinue
$services = @()
if ($compose -match '(?ms)^services:[ \t]*\r?\n(.*?)^\S') { $services = @([regex]::Matches($Matches[1], '(?m)^  ([A-Za-z0-9_.-]+):') | ForEach-Object { $_.Groups[1].Value }) }
Report 'INFO' 'docker-compose.yml' "services: $($services -join ', ') (postgres on host port 5432, needs DB_PASSWORD)"
$docker = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
$dockerNeeded = ($DbPort -eq 5432)
if (-not $docker) {
    Report $(if ($dockerNeeded) { 'MISSING' } else { 'INFO' }) 'docker' "not installed$(if ($dockerNeeded) { ' (machine.ps1 sets $DbPort = 5432 = docker compose)' } else { ' (not needed with the project PostgreSQL)' })"
} else {
    $engine = Get-FirstLine $docker.Source @('version', '--format', '{{.Server.Version}}')
    if ($LASTEXITCODE -eq 0 -and $engine) {
        Report 'OK' 'docker engine' $engine
        $project = (Split-Path $ProjectRoot -Leaf).ToLower()
        $running = @(& $docker.Source ps --filter "label=com.docker.compose.project=$project" --format '{{.Names}} {{.Status}}' 2>$null)
        Report 'INFO' 'compose containers' $(if ($running.Count) { $running -join '; ' } else { "none running for project '$project'" })
    } else {
        Report $(if ($dockerNeeded) { 'MISSING' } else { 'WARN' }) 'docker engine' 'docker is installed but the engine is not running (start Docker Desktop)'
    }
}

# ---------------------------------------------------------------- Settings (names and yes/no only)
Section 'Environment variables in this terminal (set / not set only)'
if (Test-Var 'DB_PASSWORD') { Report 'OK' 'DB_PASSWORD' 'set' }
elseif ($DbPort -eq 5432) { Report 'WARN' 'DB_PASSWORD' 'not set: docker compose, mvn test and start-backend.ps1 all need the password the postgres container was created with' }
else { Report 'WARN' 'DB_PASSWORD' 'not set: "mvn test" needs it (any value for the trust-auth project DB); start-backend.ps1 works without it on that DB' }
foreach ($v in 'ADMIN_USERNAME', 'ADMIN_PASSWORD') { Report 'INFO' $v $(if (Test-Var $v) { 'set (creates that admin once if the name is new)' } else { 'not set (only needed to create a first admin account)' }) }
if (Test-Var 'OPENAI_API_KEY') { Report 'WARN' 'OPENAI_API_KEY' 'set in this terminal: only used with start-backend.ps1 -Ai openai (paid); clear it when not needed' }
else { Report 'INFO' 'OPENAI_API_KEY' 'not set (fine: AI is none/stub unless -Ai openai)' }
$risky = @(Get-RiskyEnvNames)
if ($risky.Count) { Report 'WARN' 'blocked variables' "start-backend.ps1 will refuse to start until these are removed: $($risky -join ', ')" }
else { Report 'OK' 'blocked variables' 'none' }

Section 'Local files (Git-ignored; existence only)'
$files = [ordered]@{
    '.local\run\machine.ps1' = 'PC-specific paths'
    'backend\src\main\resources\application-local.yml' = 'optional local Spring settings'
    'admin-ui\.env.local' = 'optional (BACKEND_URL; the run scripts set it themselves)'
}
foreach ($f in $files.Keys) { Report 'INFO' $f "$(if (Test-Path (Join-Path $ProjectRoot $f)) { 'present' } else { 'absent' }) - $($files[$f])" }

# ---------------------------------------------------------------- Running servers
Section 'Servers listening on 127.0.0.1'
$anyServer = $false
foreach ($k in $Envs.Keys) {
    $c = $Envs[$k]
    $b = Test-Port $c.BackendPort
    $u = Test-Port $c.UiPort
    if (-not $b -and -not $u) { continue }
    $anyServer = $true
    $health = ''
    if ($b) {
        try {
            $r = Invoke-RestMethod -TimeoutSec 3 "http://127.0.0.1:$($c.BackendPort)/actuator/health"
            if ($r -is [byte[]]) { $r = [Text.Encoding]::UTF8.GetString($r) }
            if ($r -is [string]) { $r = $r | ConvertFrom-Json }
            $health = " health $($r.status)"
        } catch { $health = ' health: no answer' }
    }
    Report 'INFO' $k ("backend http://127.0.0.1:{0} {1}{2} | admin http://127.0.0.1:{3}/ {4}" -f $c.BackendPort, $(if ($b) { 'up' } else { 'down' }), $health, $c.UiPort, $(if ($u) { 'up' } else { 'down' }))
}

if (-not $anyServer) { Report 'INFO' 'servers' 'none of the run environments (daily, dev, verify-*) is up' }

Write-Host ''
if ($script:Missing -gt 0) { Write-Host "$($script:Missing) required item(s) missing (see [MISSING] above)." -ForegroundColor Red; exit 1 }
Write-Host 'Nothing required is missing.' -ForegroundColor Green
exit 0
