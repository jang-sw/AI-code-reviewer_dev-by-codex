#requires -Version 7.0
param(
    [string]$InventoryFile = (Join-Path $PSScriptRoot '../source/target/dependency-list.txt'),
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
[void](New-Item -ItemType Directory -Path $reportDirectory -Force)
$reportFile = Join-Path $reportDirectory ('osv-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '.json')
$report = [ordered]@{
    status = 'INCOMPLETE'
    startedUtc = [DateTime]::UtcNow.ToString('o')
    includedScopes = @('compile', 'runtime', 'provided')
    packages = @()
    advisoryCount = $null
}
$exitCode = 1
try {
    $approved = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'public-maven-coordinates.txt')
    $packages = @(Read-MavenAuditInventory (Get-Content -LiteralPath $InventoryFile) $approved)
    $report.inventorySha256 = (Get-FileHash -LiteralPath $InventoryFile -Algorithm SHA256).Hash.ToLowerInvariant()
    $report.packages = $packages
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
    Invoke-OsvAudit $packages $transport $report
    $exitCode = if ($report.advisoryCount -eq 0) { 0 } else { 2 }
    Write-Host ("OSV COMPLETE: {0} public packages, {1} advisory IDs." -f $packages.Count, $report.advisoryCount)
    foreach ($advisory in $report.advisories) { Write-Host $advisory.id }
} catch {
    $report.error = $_.Exception.Message
    Write-Host ('OSV INCOMPLETE: ' + $_.Exception.Message)
} finally {
    $report.finishedUtc = [DateTime]::UtcNow.ToString('o')
    ConvertTo-Json -InputObject $report -Depth 100 | Set-Content -LiteralPath $reportFile -Encoding utf8
    Write-Host ('Report: ' + $reportFile)
}
exit $exitCode
