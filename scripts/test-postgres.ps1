param(
    [string]$PgBin = 'C:\Program Files\PostgreSQL\17\bin',
    [ValidateRange(1024, 65535)][int]$Port = 55439
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$cluster = Join-Path $workspace '.local/pg-validation'
$marker = Join-Path $cluster 'reviewer-test-cluster'
$logFile = Join-Path $workspace '.local/pg-validation.log'
$pgCtl = Join-Path $PgBin 'pg_ctl.exe'
$psql = Join-Path $PgBin 'psql.exe'
$startedHere = $false
$savedEnvironment = @{}
$environmentNames = @('TEST_DATABASE_URL','TEST_DATABASE_USERNAME','TEST_DATABASE_PASSWORD','TEST_IDENTITY_DATABASE_URL')
foreach ($name in $environmentNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $Executable (exit $LASTEXITCODE)" }
}
try {
    if (-not (Test-Path -LiteralPath $pgCtl)) { throw 'PostgreSQL development tools were not found; supply -PgBin.' }
    New-Item -ItemType Directory -Force (Join-Path $workspace '.local') | Out-Null
    if (-not (Test-Path -LiteralPath $cluster)) {
        Invoke-Checked (Join-Path $PgBin 'initdb.exe') @('-D', $cluster, '-U', 'reviewer_test', '-A', 'trust', '--encoding=UTF8', '--locale=C')
        Set-Content -LiteralPath $marker -Value 'Isolated local AI Reviewer tests only.'
    }
    if (-not (Test-Path -LiteralPath $marker)) { throw 'Refusing to use a database directory not created by this test script.' }
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
} finally {
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    if ($startedHere) { Invoke-Checked $pgCtl @('-D', $cluster, '-m', 'fast', '-w', 'stop') }
}
