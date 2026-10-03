param(
    [string]$PgBin = 'C:\Program Files\PostgreSQL\17\bin',
    [ValidateRange(1024, 65535)][int]$Port = 55439
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$cluster = Join-Path $workspace '.local/pg-validation'
$marker = Join-Path $cluster 'reviewer-test-cluster'
$pidFile = Join-Path $cluster 'postmaster.pid'
$restoreDatabase = 'reviewer_restore_' + [Guid]::NewGuid().ToString('N')
$createdHere = $false
$psql = Join-Path $PgBin 'psql.exe'
$connection = @('-w','-h','127.0.0.1','-p',"$Port",'-U','reviewer_test')
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $Executable (exit $LASTEXITCODE)" }
}
function Fingerprint([string]$Database, [string]$Table) {
    # Table names come only from the fixed allowlist below, never from command-line input.
    $value = & $psql @connection -X -d $Database -tAc "SELECT count(*)::text || ':' || md5(coalesce(string_agg(row_to_json(t)::text, E'\n' ORDER BY row_to_json(t)::text), '')) FROM $Table t"
    if ($LASTEXITCODE -ne 0) { throw 'Could not compare the isolated restore database.' }
    return "$value".Trim()
}
try {
    if (-not (Test-Path -LiteralPath $marker) -or -not (Test-Path -LiteralPath $pidFile)) {
        throw 'Run test-postgres.ps1 -BackupRestore; this drill requires its running isolated cluster.'
    }
    $pidLines = Get-Content -LiteralPath $pidFile
    if ($pidLines.Count -lt 4 -or [int]$pidLines[3] -ne $Port -or
            [IO.Path]::GetFullPath($pidLines[1]) -ne [IO.Path]::GetFullPath($cluster)) {
        throw 'Refusing to connect: the port/data directory do not identify the isolated test cluster.'
    }
    if ($restoreDatabase -notmatch '^reviewer_restore_[a-f0-9]{32}$') { throw 'Invalid generated restore target.' }
    $serverDirectory = & $psql @connection -X -d postgres -tAc 'SHOW data_directory'
    if ($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath("$serverDirectory".Trim()) -ne [IO.Path]::GetFullPath($cluster)) {
        throw 'Refusing backup/restore: the connected PostgreSQL is not the isolated test cluster.'
    }
    $backupDirectory = Join-Path $workspace '.local/backups'
    New-Item -ItemType Directory -Force -Path $backupDirectory | Out-Null
    $dump = Join-Path $backupDirectory 'reviewer-test.dump'
    Invoke-Checked (Join-Path $PgBin 'pg_dump.exe') ($connection + @('-Fc','--no-owner','--file',$dump,'reviewer_integration'))
    Invoke-Checked (Join-Path $PgBin 'createdb.exe') ($connection + @($restoreDatabase))
    $createdHere = $true
    Invoke-Checked (Join-Path $PgBin 'pg_restore.exe') ($connection + @('--exit-on-error','--no-owner','--dbname',$restoreDatabase,$dump))
    $tables = @('app_user','project','review_request','review_run','reviewed_commit','review_issue','manual_review_file','audit_event','git_author_mapping','flyway_schema_history','auth_attempt_policy','auth_attempt_bucket')
    $verified = @()
    foreach ($table in $tables) {
        $before = Fingerprint 'reviewer_integration' $table
        $after = Fingerprint $restoreDatabase $table
        if ($before -ne $after) { throw "Restore fingerprint mismatch for $table" }
        $verified += $table
    }
    # A restored identity sequence must allow a fresh insert, not collide with restored IDs.
    Invoke-Checked $psql ($connection + @('-X','-v','ON_ERROR_STOP=1','-d',$restoreDatabase,'-c',
        "BEGIN; INSERT INTO audit_event(action,target_type,detail) VALUES ('RESTORE_DRILL','SYSTEM','isolated validation'); ROLLBACK;"))
    # review_request uses the project primary key, not its own identity sequence.
    # Verify fresh user/project identities and the restored queue FK/state constraints together.
    $fixtureSuffix = [Guid]::NewGuid().ToString('N')
    $fixtureRequest = [Guid]::NewGuid().ToString()
    $queueInsert = @"
BEGIN;
WITH restore_user AS (
    INSERT INTO app_user(username,password_hash,git_username,role)
    VALUES ('restore_$fixtureSuffix','non-authenticating-restore-fixture','restore_$fixtureSuffix','USER') RETURNING id
), restore_project AS (
    INSERT INTO project(name,repository_url,provider,repository_host,repository_path,owner_id,status)
    SELECT 'Restore queue fixture','https://github.com/restore/$fixtureSuffix','GITHUB','github.com','restore/$fixtureSuffix',id,'APPROVED'
    FROM restore_user RETURNING id
)
INSERT INTO review_request(project_id,request_id,state,source,requested_at,available_at)
SELECT id,'$fixtureRequest','QUEUED','SCHEDULED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM restore_project;
ROLLBACK;
"@
    Invoke-Checked $psql ($connection + @('-X','-v','ON_ERROR_STOP=1','-d',$restoreDatabase,'-c',$queueInsert))
    $report = [ordered]@{ generatedAt = [DateTimeOffset]::UtcNow.ToString('o'); verifiedTables = $verified;
        dumpSha256 = (Get-FileHash -LiteralPath $dump -Algorithm SHA256).Hash; result = 'PASS';
        freshIdentityInsert = $true; projectKeyedQueueInsert = $true; scope = 'isolated synthetic test database only' }
    $report | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $backupDirectory 'restore-report.json') -Encoding UTF8
    Write-Output "Backup/restore drill passed for $($verified.Count) tables, fresh identities and a project-keyed queue insert."
} finally {
    if ($createdHere -and $restoreDatabase -match '^reviewer_restore_[a-f0-9]{32}$') {
        Invoke-Checked (Join-Path $PgBin 'dropdb.exe') ($connection + @($restoreDatabase))
    }
}
