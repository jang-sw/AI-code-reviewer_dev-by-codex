#requires -Version 7.0
param(
    [string]$InventoryFile = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../source/target/dependency-list.txt')),
    [string]$WarFile = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../source/target/ai-code-reviewer.war')),
    [string]$ExpectedWarSha256 = '',
    [string]$PomFile = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../source/pom.xml')),
    [string]$MavenRepository = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2/repository'),
    [string]$PythonCommand = 'python',
    [switch]$FunctionsOnly
)
$ErrorActionPreference = 'Stop'

function Read-MavenAuditInventory([string[]]$Lines, [string[]]$ApprovedCoordinates) {
    $approved = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($line in $ApprovedCoordinates) {
        if ($line.Trim() -and -not $line.Trim().StartsWith('#')) { [void]$approved.Add($line.Trim()) }
    }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $packages = [Collections.Generic.List[object]]::new()
    foreach ($line in $Lines) {
        $value = ($line -replace '^\[INFO\]\s*', '').Trim()
        if (-not $value -or $value -eq 'The following files have been resolved:') { continue }
        $coordinate = ($value -split '\s+--\s+module\s+', 2)[0]
        $parts = $coordinate.Split(':')
        if ($parts.Count -notin 5, 6 -or ($parts | Where-Object { $_ -notmatch '^[A-Za-z0-9_.+\-]+$' })) {
            throw 'Unrecognized Maven inventory line. Regenerate dependency:list output; nothing is sent.'
        }
        $scope = $parts[-1]
        if ($scope -eq 'test') { continue }
        if ($scope -notin 'compile', 'runtime', 'provided') { throw 'Unsupported Maven scope; coverage is incomplete.' }
        $name = $parts[0] + ':' + $parts[1]
        $version = $parts[-2]
        $key = $name + ':' + $version
        # Exact reviewed public versions prevent accidentally uploading future private dependency metadata.
        if (-not $approved.Contains($key)) {
            throw 'Inventory contains an unapproved package/version. Review public provenance and update the allowlist before scanning.'
        }
        if ($seen.Add($key)) { $packages.Add(@{ name = $name; version = $version; scope = $scope }) }
    }
    if ($packages.Count -eq 0) { throw 'No production dependencies found; refusing an empty successful audit.' }
    return $packages.ToArray()
}

function Assert-WarAuditInputHashes([System.Collections.IDictionary]$Coverage, [System.Collections.IDictionary]$InputFiles) {
    foreach ($name in @('warSha256', 'inventorySha256', 'pomSha256', 'allowlistSha256', 'supplementalPolicySha256')) {
        if ($Coverage[$name] -isnot [string] -or $Coverage[$name] -cnotmatch '^[0-9a-f]{64}$' -or
                -not $InputFiles.Contains($name)) { throw 'war-coverage-binding-invalid' }
        try { $current = (Get-FileHash -LiteralPath $InputFiles[$name] -Algorithm SHA256).Hash.ToLowerInvariant() }
        catch { throw 'war-coverage-binding-unreadable' }
        if ($current -cne $Coverage[$name]) { throw 'war-coverage-input-changed' }
    }
}

function Invoke-WarDependencyGate(
    [string]$Python, [string]$War, [string]$ExpectedSha256, [string]$Inventory,
    [string]$Pom, [string]$ApprovedFile, [string]$SupplementalFile, [string]$Repository
) {
    if ($ExpectedSha256 -cnotmatch '^[A-Fa-f0-9]{64}$') { throw 'expected-war-sha256-required' }
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Python
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @((Join-Path $PSScriptRoot 'check-war-dependencies.py'),
            '--war-file', $War, '--expected-war-sha256', $ExpectedSha256,
            '--inventory-file', $Inventory, '--pom-file', $Pom, '--approved-file', $ApprovedFile,
            '--supplemental-file', $SupplementalFile, '--maven-repository', $Repository)) {
        $start.ArgumentList.Add($argument)
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    $started = $false
    try {
        try { $started = $process.Start() } catch { throw 'war-coverage-python-unavailable' }
        if (-not $started) { throw 'war-coverage-python-unavailable' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(60000)) {
            try { $process.Kill($true); $process.WaitForExit() } catch { }
            throw 'war-coverage-gate-timeout'
        }
        $output = $stdout.GetAwaiter().GetResult()
        [void]$stderr.GetAwaiter().GetResult() # Never echo interpreter errors or local input paths.
        if ($process.ExitCode -ne 0) { throw 'war-coverage-gate-incomplete' }
        if ($output.Length -gt 10 * 1024 * 1024) { throw 'war-coverage-output-too-large' }
        try { $coverage = ConvertFrom-Json -InputObject $output -AsHashtable -Depth 100 }
        catch { throw 'war-coverage-output-invalid' }
        if ($coverage -isnot [System.Collections.IDictionary] -or $coverage['status'] -cne 'COMPLETE' -or
                $coverage['warSha256'] -cne $ExpectedSha256.ToLowerInvariant()) {
            throw 'war-coverage-output-invalid'
        }
        $inputs = @{
            warSha256 = $War; inventorySha256 = $Inventory; pomSha256 = $Pom
            allowlistSha256 = $ApprovedFile; supplementalPolicySha256 = $SupplementalFile
        }
        Assert-WarAuditInputHashes $coverage $inputs
        return $coverage
    } finally {
        if ($started) {
            try { if (-not $process.HasExited) { $process.Kill($true); $process.WaitForExit() } } catch { }
        }
        $process.Dispose()
    }
}

function Invoke-CoveredOsvAudit(
    [scriptblock]$Gate, [string[]]$ApprovedCoordinates, [scriptblock]$Transport,
    [System.Collections.IDictionary]$Report
) {
    $Report.status = 'INCOMPLETE'
    $coverage = & $Gate
    if ($coverage -isnot [System.Collections.IDictionary] -or $coverage['status'] -cne 'COMPLETE' -or
            $coverage['packages'] -isnot [System.Collections.IList] -or $coverage['packages'].Count -eq 0 -or
            $coverage['coverage'] -isnot [System.Collections.IDictionary]) { throw 'war-coverage-output-invalid' }
    foreach ($name in @('warSha256', 'inventorySha256', 'pomSha256', 'allowlistSha256', 'supplementalPolicySha256')) {
        if ($coverage[$name] -isnot [string] -or $coverage[$name] -cnotmatch '^[0-9a-f]{64}$') {
            throw 'war-coverage-binding-invalid'
        }
    }
    foreach ($name in @('nestedJarCount', 'ordinaryJarCount', 'supplementalJarCount', 'inventoryOnlyCount')) {
        $count = $coverage.coverage[$name]
        if ($count -isnot [int] -and $count -isnot [long] -or $count -lt 0) {
            throw 'war-coverage-count-invalid'
        }
    }
    if ($coverage.coverage.nestedJarCount -le 0 -or
            $coverage.coverage.ordinaryJarCount + $coverage.coverage.supplementalJarCount -ne $coverage.coverage.nestedJarCount) {
        throw 'war-coverage-count-invalid'
    }
    $lines = [Collections.Generic.List[string]]::new()
    foreach ($package in $coverage.packages) {
        if ($package -isnot [System.Collections.IDictionary] -or $package.Count -ne 3 -or
                @($package.Keys | Where-Object { $_ -cnotin @('name', 'version', 'scope') }).Count -ne 0 -or
                $package.name -isnot [string] -or $package.name -cnotmatch '^[A-Za-z0-9_.+\-]+:[A-Za-z0-9_.+\-]+$' -or
                $package.version -isnot [string] -or $package.version -cnotmatch '^[A-Za-z0-9_.+\-]+$' -or
                $package.scope -cnotin @('compile', 'runtime', 'provided')) { throw 'war-coverage-package-invalid' }
        $lines.Add($package.name + ':jar:' + $package.version + ':' + $package.scope)
    }
    # Reapply the canonical public-coordinate boundary to the complete helper output,
    # including explicitly approved packaging additions, before any HTTP request.
    $packages = @(Read-MavenAuditInventory $lines.ToArray() $ApprovedCoordinates)
    if ($packages.Count -ne $coverage.packages.Count) { throw 'war-coverage-duplicate-package' }
    $Report.warDependencyCoverage = $coverage
    $Report.inventorySha256 = $coverage.inventorySha256
    $Report.packages = $packages
    Invoke-OsvAudit $packages $Transport $Report
}

function Invoke-OsvAudit([object[]]$Packages, [scriptblock]$Transport, [System.Collections.IDictionary]$Report) {
    $pending = [Collections.Generic.List[object]]::new()
    $tokens = @{}
    for ($i = 0; $i -lt $Packages.Count; $i++) {
        $pending.Add(@{ index = $i; token = $null })
        $tokens[$i] = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    }
    $ids = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $pages = [Collections.Generic.List[object]]::new()
    $findings = [Collections.Generic.List[object]]::new()
    $advisories = [Collections.Generic.List[object]]::new()
    # Lists remain in the report even if a subsequent page/detail request fails.
    $Report.pages = $pages
    $Report.matches = $findings
    $Report.advisories = $advisories
    $round = 0
    while ($pending.Count -gt 0) {
        if (++$round -gt 100) { throw 'OSV pagination exceeded its safety limit; audit incomplete.' }
        $next = [Collections.Generic.List[object]]::new()
        for ($offset = 0; $offset -lt $pending.Count; $offset += 100) {
            $count = [Math]::Min(100, $pending.Count - $offset)
            $batch = $pending.GetRange($offset, $count)
            $queries = @($batch | ForEach-Object {
                $package = $Packages[$_.index]
                $query = @{ package = @{ ecosystem = 'Maven'; name = $package.name }; version = $package.version }
                if ($_.token) { $query.page_token = $_.token }
                $query
            })
            $body = @{ queries = $queries }
            $response = & $Transport 'POST' 'https://api.osv.dev/v1/querybatch' $body
            $pages.Add(@{ request = $body; response = $response })
            if ($response -isnot [System.Collections.IDictionary] -or $response.Contains('error') -or
                    $response['results'] -isnot [System.Collections.IList] -or
                    $response['results'].Count -ne $count) {
                throw 'OSV result count/shape does not match the request; audit incomplete.'
            }
            for ($i = 0; $i -lt $count; $i++) {
                $result = $response['results'][$i]
                if ($result -isnot [System.Collections.IDictionary]) { throw 'Invalid OSV result object.' }
                if (@($result.Keys | Where-Object { $_ -cnotin @('vulns', 'next_page_token') }).Count -gt 0) {
                    throw 'OSV returned an unknown per-package field or error; audit incomplete.'
                }
                if ($result.Contains('vulns') -and $result['vulns'] -isnot [System.Collections.IList]) {
                    throw 'Invalid OSV vulnerability list.'
                }
                foreach ($vulnerability in $result['vulns']) {
                    if ($vulnerability -isnot [System.Collections.IDictionary] -or
                            $vulnerability['id'] -isnot [string] -or $vulnerability['id'] -notmatch '^[A-Za-z0-9_.-]+$') {
                        throw 'Invalid OSV vulnerability identifier.'
                    }
                    $id = $vulnerability['id']
                    [void]$ids.Add($id)
                    $package = $Packages[$batch[$i].index]
                    $findings.Add(@{ name = $package.name; version = $package.version; id = $id })
                }
                $token = $result['next_page_token']
                if ($null -ne $token -and $token -isnot [string]) { throw 'Invalid OSV pagination token.' }
                if ($token) {
                    if (-not $tokens[$batch[$i].index].Add($token)) { throw 'Repeated OSV pagination token; audit incomplete.' }
                    $next.Add(@{ index = $batch[$i].index; token = $token })
                }
            }
        }
        $pending = $next
    }
    foreach ($id in ($ids | Sort-Object)) {
        $advisory = & $Transport 'GET' ('https://api.osv.dev/v1/vulns/' + [Uri]::EscapeDataString($id)) $null
        if ($advisory -isnot [System.Collections.IDictionary] -or $advisory['id'] -cne $id) {
            throw 'OSV advisory detail is missing or mismatched; audit incomplete.'
        }
        $advisories.Add($advisory)
    }
    $Report.advisoryCount = $ids.Count
    $Report.status = 'COMPLETE'
}

if ($FunctionsOnly) { return }
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$reportDirectory = Join-Path $workspace '.local/security'
$reportFile = Join-Path $reportDirectory ('osv-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N') + '.json')
$reportStream = $null
$report = [ordered]@{
    status = 'INCOMPLETE'
    startedUtc = [DateTime]::UtcNow.ToString('o')
    includedScopes = @('compile', 'runtime', 'provided')
    packages = @()
    advisoryCount = $null
}
$exitCode = 1
try {
    # Refuse existing links/junctions in the report ancestry, and never overwrite
    # an earlier report even when two audits start in the same millisecond.
    $ancestor = [IO.DirectoryInfo]::new($reportDirectory)
    while ($null -ne $ancestor) {
        if ($ancestor.Exists -and ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'report-ancestry-link-refused'
        }
        $ancestor = $ancestor.Parent
    }
    [void][IO.Directory]::CreateDirectory($reportDirectory)
    $ancestor = [IO.DirectoryInfo]::new($reportDirectory)
    while ($null -ne $ancestor) {
        if ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'report-ancestry-link-refused' }
        $ancestor = $ancestor.Parent
    }
    $reportStream = [IO.FileStream]::new($reportFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $approvedFile = Join-Path $PSScriptRoot 'public-maven-coordinates.txt'
    $supplementalFile = Join-Path $PSScriptRoot 'packaged-maven-artifacts.json'
    $approved = Get-Content -LiteralPath $approvedFile
    $gate = {
        Invoke-WarDependencyGate $PythonCommand $WarFile $ExpectedWarSha256 $InventoryFile $PomFile $approvedFile $supplementalFile $MavenRepository
    }
    $transport = {
        param($method, $uri, $body)
        $parameters = @{
            Method = $method; Uri = $uri; TimeoutSec = 60; MaximumRedirection = 0
            Headers = @{ Accept = 'application/json' }
        }
        if ($null -ne $body) {
            $parameters.ContentType = 'application/json'
            $parameters.Body = ConvertTo-Json -InputObject $body -Depth 30 -Compress
        }
        $response = Invoke-WebRequest @parameters
        return ConvertFrom-Json -InputObject $response.Content -AsHashtable -Depth 100
    }
    Invoke-CoveredOsvAudit $gate $approved $transport $report
    Assert-WarAuditInputHashes $report.warDependencyCoverage @{
        warSha256 = $WarFile; inventorySha256 = $InventoryFile; pomSha256 = $PomFile
        allowlistSha256 = $approvedFile; supplementalPolicySha256 = $supplementalFile
    }
    $exitCode = if ($report.advisoryCount -eq 0) { 0 } else { 2 }
    Write-Host ("OSV COMPLETE: {0} public packages, {1} advisory IDs; verified WAR coverage." -f $report.packages.Count, $report.advisoryCount)
    foreach ($advisory in $report.advisories) { Write-Host $advisory.id }
} catch {
    $exitCode = 1
    $report.status = 'INCOMPLETE'
    $report.error = 'Local WAR coverage or OSV request/response verification failed; no complete audit is asserted.'
    $localCategories = @('expected-war-sha256-required', 'war-coverage-python-unavailable',
        'war-coverage-gate-timeout', 'war-coverage-gate-incomplete', 'war-coverage-output-too-large',
        'war-coverage-output-invalid', 'war-coverage-binding-invalid', 'war-coverage-binding-unreadable',
        'war-coverage-input-changed', 'war-coverage-count-invalid', 'war-coverage-package-invalid',
        'war-coverage-duplicate-package', 'report-ancestry-link-refused')
    $report.errorCode = if ($_.Exception.Message -cin $localCategories) { $_.Exception.Message } else { 'dependency-audit-incomplete' }
    Write-Host ('OSV INCOMPLETE: ' + $report.error)
} finally {
    $report.finishedUtc = [DateTime]::UtcNow.ToString('o')
    if ($null -ne $reportStream) {
        try {
            $bytes = [Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -InputObject $report -Depth 100) + "`n")
            $reportStream.Write($bytes, 0, $bytes.Length)
            $reportStream.Flush($true)
            Write-Host ('Report: ' + $reportFile)
        } finally { $reportStream.Dispose() }
    } else { Write-Host 'Report unavailable: the safe output directory or exclusive file could not be created.' }
}
exit $exitCode
