# Read-only guards for the isolated local PostgreSQL test cluster. No process or DB operations.

function ConvertTo-TestClusterAbsolutePath {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -match '[\x00-\x1f\x7f]' -or
        $Value.StartsWith('\\') -or $Value.StartsWith('//') -or
        ($Value -split '[\\/]' | Where-Object { $_ -eq '..' }).Count -gt 0) {
        throw 'Invalid test cluster path.'
    }
    $onWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
    if ($onWindows) {
        if ($Value -notmatch '^[A-Za-z]:[\\/]' -or $Value.Substring(2).Contains(':')) {
            throw 'Invalid test cluster path.'
        }
        foreach ($component in ($Value.Substring(3) -split '[\\/]')) {
            if ($component -match '[. ]$') { throw 'Invalid test cluster path.' }
        }
    } elseif (-not $Value.StartsWith('/') -or $Value.Contains('\')) {
        throw 'Invalid test cluster path.'
    }
    $absolute = [IO.Path]::GetFullPath($Value)
    $rootPath = [IO.Path]::GetPathRoot($absolute)
    while ($absolute.Length -gt $rootPath.Length -and ($absolute.EndsWith('/') -or $absolute.EndsWith('\'))) {
        $absolute = $absolute.Substring(0, $absolute.Length - 1)
    }
    return $absolute
}

function Test-TestClusterPathEqual {
    param([string]$Left, [string]$Right)
    $comparison = [StringComparison]::Ordinal
    if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        $comparison = [StringComparison]::OrdinalIgnoreCase
    }
    return [string]::Equals($Left, $Right, $comparison)
}

function Assert-TestClusterNoLinks {
    param([string]$Path, [switch]$AllowMissing, [switch]$LeafIsFile)
    $parts = [Collections.Generic.List[string]]::new()
    $current = $Path
    while (-not [string]::IsNullOrEmpty($current)) {
        $parts.Insert(0, $current)
        $parentPath = [IO.Path]::GetDirectoryName($current)
        if ($parentPath -eq $current) { break }
        $current = $parentPath
    }
    foreach ($part in $parts) {
        try {
            $item = Get-Item -LiteralPath $part -Force -ErrorAction Stop
        } catch [System.Management.Automation.ItemNotFoundException] {
            if ($AllowMissing) { return }
            throw 'Required test cluster path is missing.'
        }
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
            ($item.PSObject.Properties['LinkType'] -and -not [string]::IsNullOrEmpty($item.LinkType))) {
            throw 'Linked test cluster paths are forbidden.'
        }
        $isLeaf = Test-TestClusterPathEqual $part $Path
        if ($isLeaf -and $LeafIsFile) {
            if ($item.PSIsContainer) { throw 'Test cluster evidence must be a regular file.' }
        } elseif (-not $item.PSIsContainer) {
            throw 'Test cluster ancestors must be directories.'
        }
    }
}

function Read-TestClusterEvidence {
    param([string]$Path)
    Assert-TestClusterNoLinks -Path $Path -LeafIsFile
    $stream = $null
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        if ($stream.Length -gt 16384) { throw 'Test cluster evidence exceeds its size limit.' }
        $buffer = [byte[]]::new(16385)
        $total = 0
        while ($total -lt $buffer.Length) {
            $readCount = $stream.Read($buffer, $total, $buffer.Length - $total)
            if ($readCount -eq 0) { break }
            $total += $readCount
        }
        if ($total -gt 16384) { throw 'Test cluster evidence exceeds its size limit.' }
        $decoded = [Text.UTF8Encoding]::new($false, $true).GetString($buffer, 0, $total)
        if ($decoded.Length -gt 0 -and $decoded[0] -eq [char]0xfeff) { $decoded = $decoded.Substring(1) }
        return $decoded
    } finally {
        if ($null -ne $stream) { $stream.Dispose() }
    }
}

function Assert-TestClusterPath {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Workspace, [Parameter(Mandatory)][string]$Cluster, [switch]$RequireMarker)
    try {
        $workspacePath = ConvertTo-TestClusterAbsolutePath $Workspace
        $clusterPath = ConvertTo-TestClusterAbsolutePath $Cluster
        $expected = [IO.Path]::Combine([IO.Path]::Combine($workspacePath, '.local'), 'pg-validation')
        if (-not (Test-TestClusterPathEqual $clusterPath $expected)) { throw 'Unexpected test cluster path.' }
        Assert-TestClusterNoLinks -Path $workspacePath
        Assert-TestClusterNoLinks -Path $clusterPath -AllowMissing:(-not $RequireMarker)
        if ($RequireMarker) {
            $markerText = Read-TestClusterEvidence ([IO.Path]::Combine($clusterPath, 'reviewer-test-cluster'))
            $versionText = Read-TestClusterEvidence ([IO.Path]::Combine($clusterPath, 'PG_VERSION'))
            if ($markerText.Trim() -cne 'Isolated local AI Reviewer tests only.' -or $versionText.Trim() -cne '17') {
                throw 'Unexpected test cluster marker or version.'
            }
        }
    } catch {
        throw 'Test cluster path or ownership marker validation failed.'
    }
}

function Get-TestPostmasterIdentity {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Workspace, [Parameter(Mandatory)][string]$Cluster,
        [Parameter(Mandatory)][int]$Port)
    try {
        if ($Port -lt 1 -or $Port -gt 65535) { throw 'Invalid port.' }
        Assert-TestClusterPath -Workspace $Workspace -Cluster $Cluster -RequireMarker
        $clusterPath = ConvertTo-TestClusterAbsolutePath $Cluster
        $pidText = Read-TestClusterEvidence ([IO.Path]::Combine($clusterPath, 'postmaster.pid'))
        $lines = $pidText -split '\r?\n'
        if ($lines.Count -lt 6 -or $lines[0] -notmatch '^[0-9]+$' -or
            $lines[2] -notmatch '^[0-9]+$' -or $lines[3] -notmatch '^[0-9]+$') { throw 'Invalid PID evidence.' }
        $postmasterPidValue = 0
        $startedValue = [long]0
        $portValue = 0
        if (-not [int]::TryParse($lines[0], [ref]$postmasterPidValue) -or $postmasterPidValue -le 0 -or
            -not [long]::TryParse($lines[2], [ref]$startedValue) -or $startedValue -le 0 -or
            -not [int]::TryParse($lines[3], [ref]$portValue) -or $portValue -ne $Port -or
            $lines[5] -cne '127.0.0.1') { throw 'Invalid PID evidence.' }
        $recordedDirectory = ConvertTo-TestClusterAbsolutePath $lines[1]
        if (-not (Test-TestClusterPathEqual $recordedDirectory $clusterPath)) { throw 'Unexpected data directory.' }
        return [pscustomobject]@{ Pid = [int]$postmasterPidValue; StartedAt = [long]$startedValue; Port = [int]$portValue }
    } catch {
        throw 'Test postmaster identity validation failed.'
    }
}

function Assert-TestPostmasterIdentity {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Workspace, [Parameter(Mandatory)][string]$Cluster,
        [Parameter(Mandatory)][int]$Port, [Parameter(Mandatory)][int]$ExpectedPid,
        [Parameter(Mandatory)][long]$ExpectedStartedAt)
    try {
        if ($ExpectedPid -le 0 -or $ExpectedStartedAt -le 0) { throw 'Invalid expected identity.' }
        $identity = Get-TestPostmasterIdentity -Workspace $Workspace -Cluster $Cluster -Port $Port
        if ($identity.Pid -ne $ExpectedPid -or $identity.StartedAt -ne $ExpectedStartedAt) { throw 'Changed identity.' }
    } catch {
        throw 'Test postmaster ownership changed; refusing cluster control.'
    }
}
