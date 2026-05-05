# Monitoring agent for Windows (PowerShell)
# Collects real machine metrics and sends to the monitoring server.
#
# Usage:
#   .\agent.ps1
#   .\agent.ps1 -Server "http://my-server:8080" -Username "alice" -Password "secret" -NodeName "web-01"

param(
    [string]$Server   = "http://localhost:8080",
    [string]$Username = "admin",
    [string]$Password = "admin123",
    [string]$NodeName = $env:COMPUTERNAME,
    [string]$HostAddr = $env:COMPUTERNAME,
    [int]   $Port     = 0,
    [int]   $Interval = 10
)

$ErrorActionPreference = "Continue"

function Invoke-Api {
    param(
        [string]$Path,
        [string]$Method = "GET",
        $Body = $null,
        [string]$Token = ""
    )
    $uri = "$Server$Path"
    $headers = @{ "Content-Type" = "application/json" }
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }

    $params = @{
        Uri             = $uri
        Method          = $Method
        Headers         = $headers
        UseBasicParsing = $true
    }
    if ($Body -ne $null) {
        $params["Body"] = ($Body | ConvertTo-Json -Depth 5 -Compress)
    }

    $resp = Invoke-WebRequest @params
    return $resp.Content | ConvertFrom-Json
}

# -- Auth ---------------------------------------------------------------------
function Get-AuthToken {
    Write-Host "  Logging in as $Username..." -NoNewline
    try {
        $r = Invoke-Api -Path "/api/v1/auth/login" -Method "POST" `
             -Body @{ username = $Username; password = $Password }
        Write-Host " OK"
        return $r.token
    } catch {
        Write-Host " no account, registering..." -NoNewline
    }

    $r = Invoke-Api -Path "/api/v1/auth/register" -Method "POST" `
         -Body @{ username = $Username; password = $Password }
    Write-Host " OK"
    return $r.token
}

# -- Node registration --------------------------------------------------------
function Get-OrRegisterNode {
    param([string]$Token)

    $nodes = Invoke-Api -Path "/api/v1/nodes" -Method "GET" -Token $Token
    foreach ($n in $nodes) {
        if ($n.name -eq $NodeName) {
            Write-Host "  Found existing node: $NodeName ($($n.id))"
            return $n.id
        }
    }

    Write-Host "  Registering new node: $NodeName..." -NoNewline
    $body = @{
        name = $NodeName
        host = $HostAddr
        port = $Port
        type = "server"
        tags = @{ os = "windows" }
    }
    $n = Invoke-Api -Path "/api/v1/nodes" -Method "POST" -Body $body -Token $Token
    Write-Host " OK ($($n.id))"
    return $n.id
}

# -- Collect metrics ----------------------------------------------------------
function Get-Metrics {
    $metrics = [System.Collections.Generic.List[hashtable]]::new()

    # CPU
    $cpu = [double](Get-WmiObject -Class Win32_Processor |
                    Measure-Object -Property LoadPercentage -Average).Average
    $logicalCores = [int](Get-WmiObject -Class Win32_ComputerSystem).NumberOfLogicalProcessors
    $metrics.Add(@{ name = "cpu.usage";         value = $cpu;         unit = "percent"; type = "GAUGE" })
    $metrics.Add(@{ name = "cpu.count_logical"; value = $logicalCores; unit = "cores";   type = "GAUGE" })

    # RAM
    $os = Get-WmiObject -Class Win32_OperatingSystem
    $totalMB = [math]::Round($os.TotalVisibleMemorySize / 1024, 1)
    $freeMB  = [math]::Round($os.FreePhysicalMemory      / 1024, 1)
    $usedMB  = [math]::Round($totalMB - $freeMB, 1)
    $memPct  = [math]::Round($usedMB / $totalMB * 100, 1)
    $metrics.Add(@{ name = "memory.used_mb";  value = $usedMB;  unit = "MB";      type = "GAUGE" })
    $metrics.Add(@{ name = "memory.total_mb"; value = $totalMB; unit = "MB";      type = "GAUGE" })
    $metrics.Add(@{ name = "memory.percent";  value = $memPct;  unit = "percent"; type = "GAUGE" })

    # Disk C:\
    try {
        $disk    = Get-WmiObject -Class Win32_LogicalDisk -Filter "DeviceID='C:'"
        $usedGB  = [math]::Round(($disk.Size - $disk.FreeSpace) / 1GB, 2)
        $totalGB = [math]::Round($disk.Size / 1GB, 2)
        $diskPct = [math]::Round($usedGB / $totalGB * 100, 1)
        $metrics.Add(@{ name = "disk.used_gb";  value = $usedGB;  unit = "GB";      type = "GAUGE" })
        $metrics.Add(@{ name = "disk.total_gb"; value = $totalGB; unit = "GB";      type = "GAUGE" })
        $metrics.Add(@{ name = "disk.percent";  value = $diskPct; unit = "percent"; type = "GAUGE" })
    } catch {
        Write-Host "  [warn] disk: $_"
    }

    # Network bytes/sec (from performance counters)
    try {
        $netStats = Get-WmiObject -Class Win32_PerfFormattedData_Tcpip_NetworkInterface
        $sent = 0; $recv = 0
        foreach ($nic in $netStats) {
            $sent += [double]$nic.BytesSentPersec
            $recv += [double]$nic.BytesReceivedPersec
        }
        $metrics.Add(@{ name = "net.bytes_sent_ps"; value = [math]::Round($sent); unit = "bytes/s"; type = "GAUGE" })
        $metrics.Add(@{ name = "net.bytes_recv_ps"; value = [math]::Round($recv); unit = "bytes/s"; type = "GAUGE" })
    } catch {
        Write-Host "  [warn] network: $_"
    }

    # Process count
    $procCount = (Get-Process).Count
    $metrics.Add(@{ name = "process.count"; value = $procCount; unit = "count"; type = "GAUGE" })

    return $metrics
}

# -- Main loop ----------------------------------------------------------------
Write-Host "=== Monitoring Agent (Windows PowerShell) ==="
Write-Host "  Server:   $Server"
Write-Host "  Node:     $NodeName ($HostAddr)"
Write-Host "  Interval: $Interval sec"
Write-Host ""

$token  = Get-AuthToken
$nodeId = Get-OrRegisterNode -Token $token

Write-Host ""
Write-Host "Collecting metrics. Press Ctrl+C to stop."
Write-Host ""

$i = 0
while ($true) {
    try {
        $metrics = Get-Metrics
        $body    = @{ nodeId = $nodeId; metrics = $metrics }
        $result  = Invoke-Api -Path "/api/v1/metrics/batch" -Method "POST" -Body $body -Token $token

        $i++
        $cpuVal = ($metrics | Where-Object { $_.name -eq "cpu.usage" }     | Select-Object -First 1).value
        $memVal = ($metrics | Where-Object { $_.name -eq "memory.percent" } | Select-Object -First 1).value
        Write-Host ("  [{0,4}] cpu={1}%  mem={2}%  sent={3}" -f $i, $cpuVal, $memVal, $result.accepted)

    } catch {
        Write-Host "  [ERR] $_"
        # Try to refresh token on auth error
        if ($_ -match "401|403|Unauthorized") {
            try { $token = Get-AuthToken } catch {}
        }
    }

    Start-Sleep -Seconds $Interval
}
