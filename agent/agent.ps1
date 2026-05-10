# Агент мониторинга для Windows. Совместим с Windows PowerShell 5.1
# и PowerShell 7+ (используется Get-CimInstance, который есть в обеих
# версиях; устаревший Get-WmiObject отсутствует в PS 7+).
#
# Запуск:
#   .\agent.ps1 -Server http://my-server:8080 -Token agt_xxx -NodeName web-01

param(
    [string]$Server   = "http://localhost:8080",
    [Parameter(Mandatory = $true)]
    [string]$Token,
    [string]$NodeName = $env:COMPUTERNAME,
    [string]$HostAddr = $env:COMPUTERNAME,
    [int]   $Port     = 0,
    [int]   $Interval = 10
)

$ErrorActionPreference = "Continue"

# TLS 1.2 для HTTPS на старых сборках Windows 10.
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

function Invoke-Api {
    param([string]$Path, [string]$Method = "GET", $Body = $null)
    $params = @{
        Uri             = "$Server$Path"
        Method          = $Method
        Headers         = @{ "Content-Type" = "application/json"; "Authorization" = "Bearer $Token" }
        UseBasicParsing = $true
    }
    if ($Body -ne $null) { $params["Body"] = ($Body | ConvertTo-Json -Depth 5 -Compress) }
    $resp = Invoke-WebRequest @params
    return $resp.Content | ConvertFrom-Json
}

function Get-OrRegisterNode {
    foreach ($n in (Invoke-Api -Path "/api/v1/nodes")) {
        if ($n.name -eq $NodeName) {
            Write-Host "  Узел найден: $NodeName ($($n.id))"
            return $n.id
        }
    }
    Write-Host "  Регистрируем узел: $NodeName..." -NoNewline
    $body = @{ name = $NodeName; host = $HostAddr; port = $Port; type = "server"; tags = @{ os = "windows" } }
    $n = Invoke-Api -Path "/api/v1/nodes" -Method "POST" -Body $body
    Write-Host " OK ($($n.id))"
    return $n.id
}

# Состояние для расчёта дельт по сети между двумя замерами.
$script:prevSent = $null
$script:prevRecv = $null
$script:prevTime = $null

function Get-Metrics {
    $m = [System.Collections.Generic.List[hashtable]]::new()

    # CPU
    $cpu = (Get-CimInstance -ClassName Win32_Processor |
            Measure-Object -Property LoadPercentage -Average).Average
    if ($null -eq $cpu) { $cpu = 0 }
    $cores = (Get-CimInstance -ClassName Win32_ComputerSystem).NumberOfLogicalProcessors
    $m.Add(@{ name = "cpu.usage";         value = [double]$cpu;   unit = "percent"; type = "GAUGE" })
    $m.Add(@{ name = "cpu.count_logical"; value = [int]$cores;    unit = "cores";   type = "GAUGE" })

    # RAM
    $os = Get-CimInstance -ClassName Win32_OperatingSystem
    $totalMB = [math]::Round($os.TotalVisibleMemorySize / 1024, 1)
    $freeMB  = [math]::Round($os.FreePhysicalMemory      / 1024, 1)
    $usedMB  = [math]::Round($totalMB - $freeMB, 1)
    $pct = if ($totalMB -gt 0) { [math]::Round($usedMB / $totalMB * 100, 1) } else { 0 }
    $m.Add(@{ name = "memory.used_mb";  value = $usedMB;  unit = "MB";      type = "GAUGE" })
    $m.Add(@{ name = "memory.total_mb"; value = $totalMB; unit = "MB";      type = "GAUGE" })
    $m.Add(@{ name = "memory.percent";  value = $pct;     unit = "percent"; type = "GAUGE" })

    # Disk C:
    try {
        $d = Get-CimInstance -ClassName Win32_LogicalDisk -Filter "DeviceID='C:'"
        if ($d -and $d.Size) {
            $used  = [math]::Round(($d.Size - $d.FreeSpace) / 1GB, 2)
            $total = [math]::Round($d.Size / 1GB, 2)
            $dpct  = if ($total -gt 0) { [math]::Round($used / $total * 100, 1) } else { 0 }
            $m.Add(@{ name = "disk.used_gb";  value = $used;  unit = "GB";      type = "GAUGE" })
            $m.Add(@{ name = "disk.total_gb"; value = $total; unit = "GB";      type = "GAUGE" })
            $m.Add(@{ name = "disk.percent";  value = $dpct;  unit = "percent"; type = "GAUGE" })
        }
    } catch { Write-Host "  [warn] disk: $_" }

    # Сеть: дельта по байтам с прошлого замера.
    try {
        $sent = 0; $recv = 0
        foreach ($s in (Get-NetAdapterStatistics -ErrorAction Stop)) {
            $sent += [double]$s.SentBytes
            $recv += [double]$s.ReceivedBytes
        }
        $now = Get-Date
        if ($null -ne $script:prevTime) {
            $dt = ($now - $script:prevTime).TotalSeconds
            if ($dt -gt 0) {
                $sps = [math]::Max(0, ($sent - $script:prevSent) / $dt)
                $rps = [math]::Max(0, ($recv - $script:prevRecv) / $dt)
                $m.Add(@{ name = "net.bytes_sent_ps"; value = [math]::Round($sps); unit = "bytes/s"; type = "GAUGE" })
                $m.Add(@{ name = "net.bytes_recv_ps"; value = [math]::Round($rps); unit = "bytes/s"; type = "GAUGE" })
            }
        }
        $script:prevSent = $sent; $script:prevRecv = $recv; $script:prevTime = $now
    } catch { Write-Host "  [warn] network: $_" }

    $m.Add(@{ name = "process.count"; value = @(Get-Process).Count; unit = "count"; type = "GAUGE" })
    return $m
}

if (-not $Token.StartsWith("agt_")) { Write-Host "WARN: -Token обычно начинается с 'agt_'" }

Write-Host "=== Monitoring Agent (Windows PowerShell) ==="
Write-Host "  Сервер:    $Server"
Write-Host "  Узел:      $NodeName ($HostAddr)"
Write-Host "  Интервал:  $Interval сек"
Write-Host "  PS:        $($PSVersionTable.PSVersion)"
Write-Host ""

$nodeId = Get-OrRegisterNode

Write-Host "`nCollecting metrics. Press Ctrl+C to stop.`n"

$i = 0
while ($true) {
    try {
        $metrics = Get-Metrics
        $result = Invoke-Api -Path "/api/v1/metrics/batch" -Method "POST" -Body @{ nodeId = $nodeId; metrics = $metrics }
        $i++
        $cpu = ($metrics | Where-Object { $_.name -eq "cpu.usage"     } | Select-Object -First 1).value
        $mem = ($metrics | Where-Object { $_.name -eq "memory.percent" } | Select-Object -First 1).value
        Write-Host ("  [{0,4}] cpu={1}%  mem={2}%  accepted={3}" -f $i, $cpu, $mem, $result.accepted)
    } catch {
        Write-Host "  [ERR] $_"
    }
    Start-Sleep -Seconds $Interval
}
