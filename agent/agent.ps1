# Monitoring agent for Windows (PowerShell)
# Collects real machine metrics and sends to the monitoring server.
#
# Recommended usage (agent token):
#   .\agent.ps1 -Server "http://my-server:8080" -Token "agt_xxx" -NodeName "web-01"
#
# Legacy usage (username/password — kept for backward compatibility):
#   .\agent.ps1 -Server "http://my-server:8080" -Username "alice" -Password "secret" -NodeName "web-01"
#
# Совместимость: Windows PowerShell 5.1 и PowerShell 7+ (Core).
# Используется Get-CimInstance вместо Get-WmiObject, так как последний
# полностью удалён в PowerShell 7+.

param(
    [string]$Server   = "http://localhost:8080",
    [string]$Token    = "",
    [string]$Username = "",
    [string]$Password = "",
    [string]$NodeName = $env:COMPUTERNAME,
    [string]$HostAddr = $env:COMPUTERNAME,
    [int]   $Port     = 0,
    [int]   $Interval = 10
)

$ErrorActionPreference = "Continue"

# TLS 1.2 нужен для https-серверов на старом Windows PowerShell 5.1
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

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
# Состояние для расчёта дельт по сети между двумя замерами.
$script:prevNetSent = $null
$script:prevNetRecv = $null
$script:prevNetTime = $null

function Get-Metrics {
    $metrics = [System.Collections.Generic.List[hashtable]]::new()

    # CPU. Win32_Processor.LoadPercentage — мгновенный замер за последний период (~1 сек).
    try {
        $cpuAvg = (Get-CimInstance -ClassName Win32_Processor |
                   Measure-Object -Property LoadPercentage -Average).Average
        if ($null -eq $cpuAvg) { $cpuAvg = 0 }
        $logicalCores = (Get-CimInstance -ClassName Win32_ComputerSystem).NumberOfLogicalProcessors
        $metrics.Add(@{ name = "cpu.usage";         value = [double]$cpuAvg;       unit = "percent"; type = "GAUGE" })
        $metrics.Add(@{ name = "cpu.count_logical"; value = [int]$logicalCores;    unit = "cores";   type = "GAUGE" })
    } catch {
        Write-Host "  [warn] cpu: $_"
    }

    # RAM
    try {
        $os = Get-CimInstance -ClassName Win32_OperatingSystem
        $totalMB = [math]::Round($os.TotalVisibleMemorySize / 1024, 1)
        $freeMB  = [math]::Round($os.FreePhysicalMemory      / 1024, 1)
        $usedMB  = [math]::Round($totalMB - $freeMB, 1)
        $memPct  = if ($totalMB -gt 0) { [math]::Round($usedMB / $totalMB * 100, 1) } else { 0 }
        $metrics.Add(@{ name = "memory.used_mb";  value = $usedMB;  unit = "MB";      type = "GAUGE" })
        $metrics.Add(@{ name = "memory.total_mb"; value = $totalMB; unit = "MB";      type = "GAUGE" })
        $metrics.Add(@{ name = "memory.percent";  value = $memPct;  unit = "percent"; type = "GAUGE" })
    } catch {
        Write-Host "  [warn] memory: $_"
    }

    # Disk C:\ (DriveType=3 — фиксированный диск)
    try {
        $disk = Get-CimInstance -ClassName Win32_LogicalDisk -Filter "DeviceID='C:'"
        if ($disk -and $disk.Size) {
            $usedGB  = [math]::Round(($disk.Size - $disk.FreeSpace) / 1GB, 2)
            $totalGB = [math]::Round($disk.Size / 1GB, 2)
            $diskPct = if ($totalGB -gt 0) { [math]::Round($usedGB / $totalGB * 100, 1) } else { 0 }
            $metrics.Add(@{ name = "disk.used_gb";  value = $usedGB;  unit = "GB";      type = "GAUGE" })
            $metrics.Add(@{ name = "disk.total_gb"; value = $totalGB; unit = "GB";      type = "GAUGE" })
            $metrics.Add(@{ name = "disk.percent";  value = $diskPct; unit = "percent"; type = "GAUGE" })
        }
    } catch {
        Write-Host "  [warn] disk: $_"
    }

    # Сеть: сначала пытаемся через Get-NetAdapterStatistics (PS 5.1+, не требует прав)
    # — это считает суммарные байты и работает стабильнее, чем Win32_PerfFormattedData_*.
    # Для байт/сек считаем дельту между текущим и предыдущим замером.
    try {
        $totalSent = 0; $totalRecv = 0
        $stats = Get-NetAdapterStatistics -ErrorAction Stop
        foreach ($s in $stats) {
            $totalSent += [double]$s.SentBytes
            $totalRecv += [double]$s.ReceivedBytes
        }
        $now = Get-Date
        if ($null -ne $script:prevNetTime) {
            $dt = ($now - $script:prevNetTime).TotalSeconds
            if ($dt -gt 0) {
                $sentPs = [math]::Max(0, ($totalSent - $script:prevNetSent) / $dt)
                $recvPs = [math]::Max(0, ($totalRecv - $script:prevNetRecv) / $dt)
                $metrics.Add(@{ name = "net.bytes_sent_ps"; value = [math]::Round($sentPs); unit = "bytes/s"; type = "GAUGE" })
                $metrics.Add(@{ name = "net.bytes_recv_ps"; value = [math]::Round($recvPs); unit = "bytes/s"; type = "GAUGE" })
            }
        }
        $script:prevNetSent = $totalSent
        $script:prevNetRecv = $totalRecv
        $script:prevNetTime = $now
    } catch {
        # Fallback: WMI-счётчик «байт в секунду». Может вернуть 0 при отсутствии активности.
        try {
            $netStats = Get-CimInstance -ClassName Win32_PerfFormattedData_Tcpip_NetworkInterface
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
    }

    # Process count
    try {
        $procCount = @(Get-Process).Count
        $metrics.Add(@{ name = "process.count"; value = [int]$procCount; unit = "count"; type = "GAUGE" })
    } catch {
        Write-Host "  [warn] processes: $_"
    }

    return $metrics
}

# -- Main loop ----------------------------------------------------------------
Write-Host "=== Monitoring Agent (Windows PowerShell) ==="
Write-Host "  Server:   $Server"
Write-Host "  Node:     $NodeName ($HostAddr)"
Write-Host "  Interval: $Interval sec"
Write-Host "  PSVersion: $($PSVersionTable.PSVersion)"
Write-Host ""

# Auth: prefer agent token over username/password
if ($Token) {
    if (-not $Token.StartsWith("agt_")) {
        Write-Host "WARN: -Token usually starts with 'agt_'. Did you pass a JWT?"
    }
    $authToken = $Token
    $tokenKind = "agent-token"
} elseif ($Username -and $Password) {
    $authToken = Get-AuthToken
    $tokenKind = "jwt"
} else {
    Write-Host "ERROR: provide either -Token, or -Username + -Password"
    exit 2
}
Write-Host "  Auth: $tokenKind"
Write-Host ""

$nodeId = Get-OrRegisterNode -Token $authToken

Write-Host ""
Write-Host "Collecting metrics. Press Ctrl+C to stop."
Write-Host ""

$i = 0
while ($true) {
    try {
        $metrics = Get-Metrics
        $body    = @{ nodeId = $nodeId; metrics = $metrics }
        $result  = Invoke-Api -Path "/api/v1/metrics/batch" -Method "POST" -Body $body -Token $authToken

        $i++
        $cpuVal = ($metrics | Where-Object { $_.name -eq "cpu.usage" }     | Select-Object -First 1).value
        $memVal = ($metrics | Where-Object { $_.name -eq "memory.percent" } | Select-Object -First 1).value
        Write-Host ("  [{0,4}] cpu={1}%  mem={2}%  sent={3}" -f $i, $cpuVal, $memVal, $result.accepted)

    } catch {
        Write-Host "  [ERR] $_"
        # Refresh JWT on auth error (only if we logged in via username/password)
        if ($tokenKind -eq "jwt" -and $_ -match "401|403|Unauthorized") {
            try { $authToken = Get-AuthToken } catch {}
        }
    }

    Start-Sleep -Seconds $Interval
}
