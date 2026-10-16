#requires -Version 7.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'check-dependencies.ps1') -FunctionsOnly
$script:passed = 0
function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Check([string]$Name, [scriptblock]$Body) {
    & $Body
    $script:passed++
    Write-Host ('PASS ' + $Name)
}
function Assert-Throws([scriptblock]$Body, [string]$Message) {
    $threw = $false
    try { & $Body } catch { $threw = $true }
    Assert-True $threw $Message
}
$approved = @('org.example:public:1.2', 'org.example:container:2.0', 'org.example:runtime:3.0')
$fixture = @(
    'The following files have been resolved:',
    ' org.example:public:jar:1.2:compile -- module example.public [auto]',
    ' org.example:container:jar:native:2.0:provided -- module example.container',
    ' org.example:runtime:jar:3.0:runtime',
    ' private.test:fixture:jar:1.0:test'
)
Check 'parse module suffix, classifier, runtime and provided; exclude test' {
    $parsed = @(Read-MavenAuditInventory $fixture $approved)
    Assert-True ($parsed.Count -eq 3) 'Wrong production dependency count.'
    Assert-True ($parsed[1].name -eq 'org.example:container' -and $parsed[1].version -eq '2.0') 'Classifier parsed as version.'
}
Check 'deduplicate the same coordinate' {
    $parsed = @(Read-MavenAuditInventory ($fixture + $fixture[1]) $approved)
    Assert-True ($parsed.Count -eq 3) 'Duplicate coordinate was not removed.'
}
Check 'unknown public status rejected before transmission' {
    Assert-Throws { Read-MavenAuditInventory @('private.company:secret:jar:1.0:compile') $approved } 'Private package accepted.'
    Assert-Throws { Read-MavenAuditInventory @('org.example:public:jar:internal:compile') $approved } 'Unreviewed version accepted.'
}
Check 'empty, malformed and unsupported scope inventories rejected' {
    Assert-Throws { Read-MavenAuditInventory @('The following files have been resolved:') $approved } 'Empty audit accepted.'
    Assert-Throws { Read-MavenAuditInventory @('malformed line') $approved } 'Malformed inventory accepted.'
    Assert-Throws { Read-MavenAuditInventory @('org.example:public:jar:1.2:system') $approved } 'Unsupported scope accepted.'
}
$packages = @(@{ name = 'org.example:public'; version = '1.2'; scope = 'compile' },
    @{ name = 'org.example:container'; version = '2.0'; scope = 'provided' })
Check 'complete zero-finding response' {
    $report = @{ status = 'INCOMPLETE' }
    Invoke-OsvAudit $packages { @{ results = @(@{}, @{}) } } $report
    Assert-True ($report.status -eq 'COMPLETE' -and $report.advisoryCount -eq 0) 'Empty matches not handled.'
}
Check 'per-package pagination including empty page, details and deduplication' {
    $report = @{ status = 'INCOMPLETE' }
    $state = @{ calls = 0 }
    $transport = {
        param($method, $uri, $body)
        $state.calls++
        switch ($state.calls) {
            1 { return @{ results = @(@{ next_page_token = 'p2' }, @{ vulns = @(@{ id = 'GHSA-test-id' }) }) } }
            2 {
                Assert-True ($body.queries.Count -eq 1) 'Already complete query repeated.'
                Assert-True ($body.queries[0].page_token -eq 'p2') 'Page token omitted.'
                Assert-True ($body.queries[0].package.name -eq 'org.example:public') 'Token applied to wrong package.'
                return @{ results = @(@{ vulns = @(@{ id = 'GHSA-test-id' }) }) }
            }
            3 {
                Assert-True ($method -eq 'GET' -and $uri -eq 'https://api.osv.dev/v1/vulns/GHSA-test-id') 'Wrong detail request.'
                return @{ id = 'GHSA-test-id'; summary = 'Synthetic offline fixture' }
            }
            default { throw 'Unexpected extra request.' }
        }
    }
    Invoke-OsvAudit $packages $transport $report
    Assert-True ($state.calls -eq 3 -and $report.matches.Count -eq 2 -and $report.advisoryCount -eq 1) 'Incorrect pagination counts.'
}
Check 'repeated pagination token fails closed' {
    $report = @{ status = 'INCOMPLETE' }
    Assert-Throws { Invoke-OsvAudit @($packages[0]) { @{ results = @(@{ next_page_token = 'loop' }) } } $report } 'Token loop accepted.'
    Assert-True ($report.status -eq 'INCOMPLETE') 'Incomplete loop marked complete.'
}
Check 'network failure fails closed' {
    $report = @{ status = 'INCOMPLETE' }
    Assert-Throws { Invoke-OsvAudit $packages { throw 'Synthetic network failure' } $report } 'Network failure accepted.'
    Assert-True ($report.status -eq 'INCOMPLETE') 'Network failure marked complete.'
}
Check 'result count mismatch fails closed' {
    Assert-Throws { Invoke-OsvAudit $packages { @{ results = @(@{}) } } @{ status = 'INCOMPLETE' } } 'Missing result accepted.'
}
Check 'invalid result shape and per-package errors fail closed' {
    Assert-Throws { Invoke-OsvAudit @($packages[0]) { @{ results = @(@{ error = 'upstream failed' }) } } @{} } 'Error treated as zero matches.'
    Assert-Throws { Invoke-OsvAudit @($packages[0]) { @{ results = @(@{ unexpected = 'unknown shape' }) } } @{} } 'Unknown object treated as zero matches.'
    Assert-Throws { Invoke-OsvAudit @($packages[0]) { @{ results = @(@{ vulns = 'not-an-array' }) } } @{} } 'Invalid list accepted.'
}
Check 'advisory detail failure preserves partial evidence' {
    $report = @{ status = 'INCOMPLETE' }
    $transport = {
        param($method, $uri, $body)
        if ($method -eq 'GET') { throw 'Synthetic detail failure' }
        return @{ results = @(@{ vulns = @(@{ id = 'GHSA-test-id' }) }) }
    }
    Assert-Throws { Invoke-OsvAudit @($packages[0]) $transport $report } 'Detail failure accepted.'
    Assert-True ($report.status -eq 'INCOMPLETE' -and $report.matches.Count -eq 1 -and $report.pages.Count -eq 1) 'Partial evidence lost.'
}
Check 'wrong advisory ID fails closed' {
    $transport = {
        param($method, $uri, $body)
        if ($method -eq 'GET') { return @{ id = 'wrong-id' } }
        return @{ results = @(@{ vulns = @(@{ id = 'GHSA-test-id' }) }) }
    }
    Assert-Throws { Invoke-OsvAudit @($packages[0]) $transport @{} } 'Wrong detail accepted.'
}

function New-CoverageFixture {
    return @{
        status = 'COMPLETE'
        packages = @(@{ name = 'org.example:public'; version = '1.2'; scope = 'compile' },
            @{ name = 'org.example:container'; version = '2.0'; scope = 'provided' })
        warSha256 = 'a' * 64; inventorySha256 = 'b' * 64; pomSha256 = 'c' * 64
        allowlistSha256 = 'd' * 64; supplementalPolicySha256 = 'e' * 64
        coverage = @{ nestedJarCount = 2; ordinaryJarCount = 1; supplementalJarCount = 1; inventoryOnlyCount = 0 }
    }
}
Check 'FunctionsOnly remains usable without a WAR digest or native process' {
    $LASTEXITCODE = 93
    $output = @(. (Join-Path $PSScriptRoot 'check-dependencies.ps1') -FunctionsOnly -ExpectedWarSha256 '')
    Assert-True ($output.Count -eq 0 -and $LASTEXITCODE -eq 93) 'FunctionsOnly invoked a scanner or changed native exit state.'
    Assert-True ($null -ne (Get-Command Invoke-CoveredOsvAudit -CommandType Function)) 'Covered audit function unavailable.'
}
Check 'missing expected digest rejected before starting Python' {
    Assert-Throws { Invoke-WarDependencyGate 'nonexistent-synthetic-python-command' 'unused' '' 'unused' 'unused' 'unused' 'unused' 'unused' } 'Missing independent WAR digest accepted.'
}
Check 'local WAR gate failure prevents all OSV transport' {
    $state = @{ calls = 0 }
    $transport = { $state.calls++; throw 'Transport must not run.' }
    $report = @{ status = 'INCOMPLETE' }
    Assert-Throws { Invoke-CoveredOsvAudit { throw 'Synthetic missing WAR or cache mismatch.' } $approved $transport $report } 'Failed WAR coverage accepted.'
    Assert-True ($state.calls -eq 0 -and $report.status -eq 'INCOMPLETE') 'Local failure reached OSV.'
}
Check 'invalid or incomplete gate output prevents all OSV transport' {
    foreach ($invalid in @($null, 'not-an-object', @{ status = 'INCOMPLETE' }, @{ status = 'COMPLETE'; packages = @() })) {
        $state = @{ calls = 0 }
        Assert-Throws { Invoke-CoveredOsvAudit { $invalid } $approved { $state.calls++ } @{} } 'Invalid coverage output accepted.'
        Assert-True ($state.calls -eq 0) 'Invalid coverage output reached OSV.'
    }
}
Check 'helper packages are reapproved before transmission' {
    $coverage = New-CoverageFixture
    $coverage.packages[1].name = 'private.company:secret'
    $state = @{ calls = 0 }
    Assert-Throws { Invoke-CoveredOsvAudit { $coverage } $approved { $state.calls++ } @{} } 'Unapproved helper coordinate accepted.'
    Assert-True ($state.calls -eq 0) 'Private metadata reached OSV.'
}
Check 'malformed helper package shape scope and duplicates fail before transmission' {
    foreach ($kind in @('extra-field', 'wrong-name', 'unsupported-scope', 'duplicate')) {
        $coverage = New-CoverageFixture
        switch ($kind) {
            'extra-field' { $coverage.packages[0].unexpected = 'not allowed' }
            'wrong-name' { $coverage.packages[0].name = 'org.example:public:extra' }
            'unsupported-scope' { $coverage.packages[0].scope = 'test' }
            'duplicate' { $coverage.packages[1] = $coverage.packages[0] }
        }
        $state = @{ calls = 0 }
        Assert-Throws { Invoke-CoveredOsvAudit { $coverage } $approved { $state.calls++ } @{} } 'Malformed helper package accepted.'
        Assert-True ($state.calls -eq 0) 'Malformed helper package reached OSV.'
    }
}
Check 'missing binding hashes and inconsistent or noninteger counts fail locally' {
    foreach ($kind in @('missing-hash', 'negative-count', 'string-count', 'fraction-count', 'inconsistent-count', 'empty-war')) {
        $coverage = New-CoverageFixture
        switch ($kind) {
            'missing-hash' { $coverage.Remove('pomSha256') }
            'negative-count' { $coverage.coverage.inventoryOnlyCount = -1 }
            'string-count' { $coverage.coverage.ordinaryJarCount = '1' }
            'fraction-count' { $coverage.coverage.ordinaryJarCount = 1.5 }
            'inconsistent-count' { $coverage.coverage.nestedJarCount = 3 }
            'empty-war' { $coverage.coverage.nestedJarCount = 0; $coverage.coverage.ordinaryJarCount = 0; $coverage.coverage.supplementalJarCount = 0 }
        }
        $state = @{ calls = 0 }
        Assert-Throws { Invoke-CoveredOsvAudit { $coverage } $approved { $state.calls++ } @{} } 'Invalid binding or counts accepted.'
        Assert-True ($state.calls -eq 0) 'Invalid binding or counts reached OSV.'
    }
}
Check 'covered audit includes provided and supplemental packages and retains complete binding' {
    $coverage = New-CoverageFixture
    $state = @{ calls = 0 }
    $report = @{ status = 'INCOMPLETE' }
    $transport = {
        param($method, $uri, $body)
        $state.calls++
        Assert-True ($body.queries.Count -eq 2 -and $body.queries[1].package.name -eq 'org.example:container') 'Provided or supplemental package omitted.'
        return @{ results = @(@{}, @{}) }
    }
    Invoke-CoveredOsvAudit { $coverage } $approved $transport $report
    Assert-True ($state.calls -eq 1 -and $report.status -eq 'COMPLETE' -and $report.advisoryCount -eq 0) 'Covered zero-match audit failed.'
    Assert-True ($report.warDependencyCoverage -eq $coverage -and $report.inventorySha256 -eq 'b' * 64) 'Full WAR binding was not retained.'
}
Check 'covered audit preserves findings and partial response when details fail' {
    $coverage = New-CoverageFixture
    $report = @{ status = 'INCOMPLETE' }
    $transport = {
        param($method, $uri, $body)
        if ($method -eq 'GET') { throw 'Synthetic detail outage.' }
        return @{ results = @(@{}, @{ vulns = @(@{ id = 'GHSA-covered-test' }) }) }
    }
    Assert-Throws { Invoke-CoveredOsvAudit { $coverage } $approved $transport $report } 'Incomplete covered audit accepted.'
    Assert-True ($report.status -eq 'INCOMPLETE' -and $report.matches.Count -eq 1 -and $report.pages.Count -eq 1 -and
        $report.warDependencyCoverage -eq $coverage) 'Covered partial evidence lost.'
}
Write-Host ("Dependency scanner offline checks: {0} passed; no network or Maven invoked." -f $script:passed)
