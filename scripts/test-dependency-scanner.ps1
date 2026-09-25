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
Write-Host ("Dependency scanner offline checks: {0} passed; no network or Maven invoked." -f $script:passed)
