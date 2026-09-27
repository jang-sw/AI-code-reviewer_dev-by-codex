param(
    [string]$PgBin = 'C:\Program Files\PostgreSQL\17\bin',
    [ValidateRange(1024, 65535)][int]$Port = 55439,
    [switch]$BackupRestore,
    [switch]$ReviewRestart,
    [ValidateRange(1024, 65535)][int]$ReviewRestartPort = 18089,
    [switch]$ReviewConcurrency,
    [ValidateRange(1024, 65535)][int]$ReviewConcurrencyPortA = 18090,
    [ValidateRange(1024, 65535)][int]$ReviewConcurrencyPortB = 18091,
    [switch]$ReviewDatabaseRecovery,
    [ValidateRange(1024, 65535)][int]$ReviewDatabaseRecoveryPort = 18092
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$cluster = Join-Path $workspace '.local/pg-validation'
$marker = Join-Path $cluster 'reviewer-test-cluster'
$logFile = Join-Path $workspace '.local/pg-validation.log'
$pgCtl = Join-Path $PgBin 'pg_ctl.exe'
$psql = Join-Path $PgBin 'psql.exe'
$startedHere = $false
$ownedPostmaster = $null
. (Join-Path $PSScriptRoot 'test-cluster-safety.ps1')
$savedEnvironment = @{}
$environmentNames = @('TEST_DATABASE_URL','TEST_DATABASE_USERNAME','TEST_DATABASE_PASSWORD','TEST_IDENTITY_DATABASE_URL')
foreach ($name in $environmentNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $Executable (exit $LASTEXITCODE)" }
}
try {
    if ($ReviewConcurrency -and $ReviewConcurrencyPortA -eq $ReviewConcurrencyPortB) { throw 'Concurrent WAR ports must differ.' }
    if (-not (Test-Path -LiteralPath $pgCtl)) { throw 'PostgreSQL development tools were not found; supply -PgBin.' }
    Assert-TestClusterPath -Workspace $workspace -Cluster $cluster
    New-Item -ItemType Directory -Force (Join-Path $workspace '.local') | Out-Null
    if (-not (Test-Path -LiteralPath $cluster)) {
        Invoke-Checked (Join-Path $PgBin 'initdb.exe') @('-D', $cluster, '-U', 'reviewer_test', '-A', 'trust', '--encoding=UTF8', '--locale=C')
        Set-Content -LiteralPath $marker -Value 'Isolated local AI Reviewer tests only.'
    }
    Assert-TestClusterPath -Workspace $workspace -Cluster $cluster -RequireMarker
    & $pgCtl -D $cluster status *> $null
    if ($LASTEXITCODE -eq 0) { throw 'Test cluster is already running. Stop that test run before starting another.' }
    # Trust authentication is limited to this disposable loopback-only cluster. Never use it for production.
    # Do not pipe pg_ctl start: on Windows its server can inherit the pipeline handles
    # and keep PowerShell waiting after pg_ctl has exited. Wait for the parent only.
    $startOut = Join-Path $workspace '.local/pg-start.out.log'
    $startErr = Join-Path $workspace '.local/pg-start.err.log'
    $startProcess = Start-Process -FilePath $pgCtl -ArgumentList @('-D', "`"$cluster`"", '-l', "`"$logFile`"", '-o', "`"-p $Port -h 127.0.0.1`"", '-w', 'start') -WindowStyle Hidden -RedirectStandardOutput $startOut -RedirectStandardError $startErr -PassThru
    $startProcess.WaitForExit()
    if ($startProcess.ExitCode -ne 0) { throw 'Test PostgreSQL failed to start; inspect .local/pg-start.err.log.' }
    $startedHere = $true
    $ownedPostmaster = Get-TestPostmasterIdentity -Workspace $workspace -Cluster $cluster -Port $Port
    foreach ($database in @('reviewer_integration', 'identity_security')) {
        $exists = & $psql -X -w -h 127.0.0.1 -p $Port -U reviewer_test -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$database'"
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect isolated test databases.' }
        if ($exists -ne '1') { Invoke-Checked (Join-Path $PgBin 'createdb.exe') @('-h','127.0.0.1','-p',"$Port",'-U','reviewer_test',$database) }
    }
    $env:TEST_DATABASE_URL = "jdbc:postgresql://127.0.0.1:$Port/reviewer_integration"
    $env:TEST_DATABASE_USERNAME = 'reviewer_test'
    $env:TEST_DATABASE_PASSWORD = ''
    $env:TEST_IDENTITY_DATABASE_URL = "jdbc:postgresql://127.0.0.1:$Port/identity_security"
    Push-Location (Join-Path $workspace 'source')
    try { Invoke-Checked '.\mvnw.cmd' @('-B','-ntp','verify') } finally { Pop-Location }
    if ($BackupRestore) {
        & (Join-Path $PSScriptRoot 'verify-test-backup.ps1') -PgBin $PgBin -Port $Port
    }
    if ($ReviewRestart -or $ReviewConcurrency -or $ReviewDatabaseRecovery) {
        $restartJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
        if (-not (Test-Path -LiteralPath $restartJava)) { throw 'Java for the packaged worker drills was not found.' }
    }
    if ($ReviewRestart) {
        Invoke-Checked 'python' @((Join-Path $PSScriptRoot 'verify-review-restart.py'),
            '--war', (Join-Path $workspace 'source/target/ai-code-reviewer.war'),
            '--java', $restartJava, '--psql', $psql, '--port', "$ReviewRestartPort")
    }
    if ($ReviewConcurrency) {
        Invoke-Checked 'python' @((Join-Path $PSScriptRoot 'verify-review-concurrency.py'),
            '--war', (Join-Path $workspace 'source/target/ai-code-reviewer.war'),
            '--java', $restartJava, '--psql', $psql,
            '--port-a', "$ReviewConcurrencyPortA", '--port-b', "$ReviewConcurrencyPortB")
    }
    if ($ReviewDatabaseRecovery) {
        $parentRunToken = [Guid]::NewGuid().ToString('N')
        $recoveryReport = Join-Path $workspace ".local/review-db-recovery-$parentRunToken.json"
        if (Test-Path -LiteralPath $recoveryReport) { throw 'Refusing an existing DB recovery report path.' }
        try {
            Invoke-Checked 'python' @((Join-Path $PSScriptRoot 'verify-review-db-recovery.py'),
                '--war', (Join-Path $workspace 'source/target/ai-code-reviewer.war'),
                '--java', $restartJava, '--psql', $psql, '--port', "$ReviewDatabaseRecoveryPort",
                '--pg-ctl', $pgCtl, '--cluster-path', $cluster, '--expected-postmaster-pid', "$($ownedPostmaster.Pid)",
                '--expected-postmaster-started-at', "$($ownedPostmaster.StartedAt)",
                '--parent-run-token', $parentRunToken, '--report', $recoveryReport)
        } finally {
            # The child may fail its scenario after safely restoring its owned server.
            # Adopt a replacement only from this invocation's verified ownership handoff.
            if (Test-Path -LiteralPath $recoveryReport) {
                $reportInfo = Get-Item -LiteralPath $recoveryReport -Force
                if (($reportInfo.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $reportInfo.Length -gt 131072) {
                    throw 'Refusing an invalid DB recovery ownership report.'
                }
                try { $handoff = Get-Content -LiteralPath $recoveryReport -Raw | ConvertFrom-Json -ErrorAction Stop }
                catch { throw 'Could not validate the DB recovery ownership report.' }
                if ($handoff.parentRunToken -cne $parentRunToken) { throw 'DB recovery ownership report does not belong to this invocation.' }
                if ($null -ne $handoff.parentOwnedPostmaster) {
                    $newPid = $handoff.parentOwnedPostmaster.pid
                    $newStartedAt = $handoff.parentOwnedPostmaster.startedAt
                    if ("$newPid" -notmatch '^[1-9][0-9]{0,9}$' -or "$newStartedAt" -notmatch '^[1-9][0-9]{0,11}$') {
                        throw 'Invalid DB recovery ownership identity.'
                    }
                    Assert-TestPostmasterIdentity -Workspace $workspace -Cluster $cluster -Port $Port -ExpectedPid $newPid -ExpectedStartedAt $newStartedAt
                    $ownedPostmaster = [pscustomobject]@{ Pid = [int]$newPid; StartedAt = [long]$newStartedAt; Port = $Port }
                }
            }
        }
    }
} finally {
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    if ($startedHere) {
        if ($null -eq $ownedPostmaster) { throw 'Owned PostgreSQL identity was not captured; automatic stop was withheld.' }
        Assert-TestPostmasterIdentity -Workspace $workspace -Cluster $cluster -Port $Port -ExpectedPid $ownedPostmaster.Pid -ExpectedStartedAt $ownedPostmaster.StartedAt
        Invoke-Checked $pgCtl @('-D', $cluster, '-m', 'fast', '-w', 'stop')
    }
}
