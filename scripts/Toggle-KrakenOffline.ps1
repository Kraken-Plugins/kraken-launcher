<#
.SYNOPSIS
    Blocks or unblocks the Kraken hosts in the Windows hosts file to test offline launches.

.DESCRIPTION
    on       Points the Kraken hosts at 127.0.0.1 / ::1 so connections are refused immediately.
    timeout  Points them at 10.255.255.1 / 100::1, unroutable addresses, so connections hang until they time out.
    off      Removes the entries this script added.
    status   Shows whether the block is on and what a connection to each Kraken host does right now.

    Only the lines between this script's marker comments are added or removed. Before a block is added to a hosts
    file without one, the file is copied to hosts.kraken-bak. Changing the hosts file needs administrator rights,
    so on, timeout and off relaunch the script elevated when it is not already.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\Toggle-KrakenOffline.ps1 on
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('on', 'timeout', 'off', 'status')]
    [string]$Action = 'status',

    [switch]$Elevated
)

$ErrorActionPreference = 'Stop'

$HostsPath = Join-Path $env:windir 'System32\drivers\etc\hosts'
$BackupPath = "$HostsPath.kraken-bak"
$BeginMarker = '# >>> kraken-offline-test'
$EndMarker = '# <<< kraken-offline-test'
$KrakenHosts = @('kraken-plugins.com', 'seaweed.kraken-plugins.com')
$Targets = @{
    on      = @('127.0.0.1', '::1')
    timeout = @('10.255.255.1', '100::1')
}

function Test-Admin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal $identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-HostsLines {
    , [IO.File]::ReadAllLines($HostsPath)
}

function Save-HostsLines([string[]]$lines) {
    if ((Get-Item $HostsPath).IsReadOnly) {
        throw "$HostsPath is read-only. Clear the attribute with 'attrib -r' and run again."
    }

    # Keep the byte order mark when the file has one, so removing the block restores the file byte for byte.
    $bytes = [IO.File]::ReadAllBytes($HostsPath)
    $hasBom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
    [IO.File]::WriteAllLines($HostsPath, $lines, (New-Object Text.UTF8Encoding $hasBom))
    ipconfig /flushdns | Out-Null
    Write-Host 'Flushed the Windows DNS cache. Java caches lookups for about 30s, so restart the client or wait before testing.'
}

function Find-KrakenBlock([string[]]$lines) {
    $begin = [Array]::IndexOf($lines, $BeginMarker)
    if ($begin -lt 0) {
        return $null
    }

    $end = [Array]::IndexOf($lines, $EndMarker, $begin)
    if ($end -lt 0) {
        throw "Found '$BeginMarker' in $HostsPath without '$EndMarker'. Fix the file by hand."
    }

    @{ Begin = $begin; End = $end }
}

function Remove-KrakenBlock([string[]]$lines) {
    $block = Find-KrakenBlock $lines
    if (-not $block) {
        return , $lines
    }

    $kept = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($i -lt $block.Begin -or $i -gt $block.End) {
            $kept.Add($lines[$i])
        }
    }
    , $kept.ToArray()
}

function Set-KrakenBlock([string[]]$addresses) {
    $lines = Get-HostsLines
    if (-not (Find-KrakenBlock $lines)) {
        Copy-Item $HostsPath $BackupPath -Force
    }

    $block = @($BeginMarker)
    foreach ($name in $KrakenHosts) {
        foreach ($address in $addresses) {
            $block += "$address $name"
        }
    }
    $block += $EndMarker

    Save-HostsLines ((Remove-KrakenBlock $lines) + $block)
    Write-Host "Kraken hosts now point at $($addresses -join ' / ')."
}

function Clear-KrakenBlock {
    $lines = Get-HostsLines
    if (-not (Find-KrakenBlock $lines)) {
        Write-Host 'No Kraken hosts block to remove.'
        return
    }

    Save-HostsLines (Remove-KrakenBlock $lines)
    Write-Host 'Removed the Kraken hosts block.'
}

function Test-KrakenHost([string]$name) {
    try {
        $addresses = [Net.Dns]::GetHostAddresses($name)
    } catch {
        return "DNS lookup failed: $($_.Exception.GetBaseException().Message)"
    }

    # Java prefers IPv4, so test the address it would connect to.
    $ipv4 = $addresses | Where-Object AddressFamily -eq 'InterNetwork' | Select-Object -First 1
    $target = if ($ipv4) { $ipv4 } else { $addresses[0] }
    $client = New-Object Net.Sockets.TcpClient($target.AddressFamily)
    try {
        $result = if ($client.ConnectAsync($target, 443).Wait(3000)) { 'port 443 reachable' } else { 'port 443 timed out after 3s' }
    } catch {
        $result = "port 443 failed: $($_.Exception.GetBaseException().Message)"
    } finally {
        $client.Close()
    }

    "$(($addresses | ForEach-Object { $_.IPAddressToString }) -join ', ') -> $result"
}

function Show-Status {
    $lines = Get-HostsLines
    $block = Find-KrakenBlock $lines

    Write-Host ''
    if ($block) {
        Write-Host "Kraken hosts block is ON in $HostsPath" -ForegroundColor Yellow
        if ($block.End - $block.Begin -gt 1) {
            $lines[($block.Begin + 1)..($block.End - 1)] | ForEach-Object { Write-Host "  $_" }
        }
    } else {
        Write-Host 'Kraken hosts block is OFF' -ForegroundColor Green
    }

    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($block -and $i -ge $block.Begin -and $i -le $block.End) {
            continue
        }

        $tokens = ($lines[$i] -replace '#.*$', '').Trim() -split '\s+'
        if ($tokens | Where-Object { $KrakenHosts -contains $_ }) {
            Write-Host "  Line $($i + 1) maps a Kraken host outside this script's block: $($lines[$i])" -ForegroundColor Red
        }
    }

    Write-Host ''
    foreach ($name in $KrakenHosts) {
        Write-Host ('  {0,-28} {1}' -f $name, (Test-KrakenHost $name))
    }
}

if ($Action -ne 'status' -and -not (Test-Admin)) {
    $hostExe = (Get-Process -Id $PID).Path
    $arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" $Action -Elevated"
    try {
        Start-Process $hostExe -Verb RunAs -ArgumentList $arguments
    } catch {
        Write-Host "Administrator rights are needed to change $HostsPath." -ForegroundColor Red
    }
    return
}

try {
    switch ($Action) {
        'off' { Clear-KrakenBlock }
        'status' { }
        default { Set-KrakenBlock $Targets[$Action] }
    }
    Show-Status
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
} finally {
    if ($Elevated) {
        Read-Host 'Press Enter to close' | Out-Null
    }
}
