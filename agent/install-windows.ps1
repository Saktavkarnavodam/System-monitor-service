# =============================================================================
# Установщик агента мониторинга для Windows: ставит python-агента как
# Scheduled Task с триггером AtStartup, запускается от SYSTEM. Эквивалент
# Windows Service по поведению (автозапуск + автоперезапуск), но без
# зависимости от nssm/srvany. После установки агент:
#   - стартует автоматически при загрузке системы (триггер AtStartup)
#   - перезапускается планировщиком при падении (RestartCount, RestartInterval)
#   - читает токен и сервер из защищённого env-файла, не из CLI
#
# Запуск (через визард UI генерируется автоматически):
#   powershell -NoProfile -ExecutionPolicy Bypass -Command "& {
#     $p = Join-Path $env:TEMP 'install-windows.ps1'
#     iwr '<SERVER>/install/install-windows.ps1' -UseBasicParsing -OutFile $p
#     & $p -Server '<SERVER>' -Token '<TOKEN>' -NodeName '<NAME>'
#   }"
#
# Удаление:
#   Unregister-ScheduledTask -TaskName 'monitoring-agent' -Confirm:$false
#   Remove-Item -Recurse -Force "$env:ProgramData\monitoring-agent"
# =============================================================================
param(
  [Parameter(Mandatory=$true)][string]$Server,
  [Parameter(Mandatory=$true)][string]$Token,
  [string]$NodeName = "",
  [int]   $Interval = 10
)

$ErrorActionPreference = 'Stop'
# TLS 1.2 для HTTPS на старых сборках Windows 10.
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

# 0. Проверяем admin-права
$cur = [Security.Principal.WindowsIdentity]::GetCurrent()
$prin = New-Object Security.Principal.WindowsPrincipal($cur)
if (-not $prin.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  Write-Error "Запусти PowerShell от имени администратора. Установка пишет в %ProgramData% и создаёт системную задачу."
  exit 2
}

# 0.1 Зависимости: python
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) {
  Write-Error "Нужен Python 3 в PATH. Установи: https://www.python.org/downloads/"
  exit 3
}

$TaskName   = 'monitoring-agent'
$InstallDir = Join-Path $env:ProgramData 'monitoring-agent'
$AgentPath  = Join-Path $InstallDir 'agent.py'
$EnvPath    = Join-Path $InstallDir 'agent.env'
$LibDir     = Join-Path $InstallDir 'lib'
$LogPath    = Join-Path $InstallDir 'agent.log'
$RunCmdPath = Join-Path $InstallDir 'run.cmd'

Write-Host "=== Установка $TaskName ==="
Write-Host "  Сервер:  $Server"
Write-Host "  Узел:    $(if ($NodeName) { $NodeName } else { '<COMPUTERNAME>' })"
Write-Host "  Каталог: $InstallDir"
Write-Host ""

# 1. Каталог
Write-Host "  [1/5] Создаём $InstallDir..."
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

# 2. Скачиваем agent.py
Write-Host "  [2/5] Скачиваем agent.py..."
Invoke-WebRequest "$Server/install/agent.py" -UseBasicParsing -OutFile $AgentPath

# 3. psutil — ставим РЯДОМ с агентом, в $InstallDir\lib. Это нужно, потому что:
#    а) SYSTEM-аккаунт (под ним крутится Scheduled Task) не видит user-site-packages
#       того админа, что сейчас запускает install-скрипт;
#    б) глобальная установка может конфликтовать с другими python-приложениями;
#    в) при удалении агента (Remove-Item $InstallDir) psutil тоже уходит.
#    Агент потом подхватит библиотеку через PYTHONPATH в run.cmd.
New-Item -ItemType Directory -Force -Path $LibDir | Out-Null
# При переустановке агента не качаем psutil заново, если он уже лежит в lib.
$psutilDir = Join-Path $LibDir 'psutil'
if (Test-Path (Join-Path $psutilDir '__init__.py')) {
  Write-Host "  [3/5] psutil уже установлен в $LibDir — пропускаем."
} else {
  Write-Host "  [3/5] Ставим psutil локально в $LibDir..."
  & python -m pip install --target $LibDir --upgrade psutil
  if ($LASTEXITCODE -ne 0) { Write-Error "Не удалось установить psutil"; exit 4 }
}

# 4. env-файл (ACL: только SYSTEM + Administrators)
Write-Host "  [4/5] Пишем $EnvPath (ACL: только SYSTEM + Administrators)..."
$lines = @(
  "# Сгенерировано install-windows.ps1. Менять руками можно, но при переустановке файл перезапишется.",
  "MONITORING_SERVER=$Server",
  "MONITORING_TOKEN=$Token"
)
if ($NodeName) { $lines += "MONITORING_NODE_NAME=$NodeName" }
$lines += "MONITORING_INTERVAL=$Interval"
# UTF-8 БЕЗ BOM — env-парсер агента (Python open(..., encoding='utf-8'))
# справится и с кириллицей в NodeName, и обычным ASCII. Set-Content -Encoding utf8
# на PS 5.1 пишет с BOM, что ломает простой парсер, поэтому пишем через .NET.
[System.IO.File]::WriteAllText($EnvPath, (($lines -join "`r`n") + "`r`n"), (New-Object System.Text.UTF8Encoding($false)))

# ACL: убираем наследование, разрешаем только SYSTEM и Administrators.
# Используем well-known SIDs (одинаковые на любой локали Windows) —
# строковые имена "BUILTIN\Administrators" на русской Windows вызывают
# IdentityNotMappedException.
$acl = Get-Acl $EnvPath
$acl.SetAccessRuleProtection($true, $false)
$acl.Access | ForEach-Object { [void]$acl.RemoveAccessRule($_) }
$wellKnown = @(
  (New-Object System.Security.Principal.SecurityIdentifier 'S-1-5-18'),     # NT AUTHORITY\SYSTEM
  (New-Object System.Security.Principal.SecurityIdentifier 'S-1-5-32-544')  # BUILTIN\Administrators
)
foreach ($sid in $wellKnown) {
  $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
    $sid, 'FullControl', 'Allow')
  $acl.AddAccessRule($rule)
}
Set-Acl -Path $EnvPath -AclObject $acl

# 5. run.cmd-обёртка + Scheduled Task.
# Запускаем не python.exe напрямую, а .cmd-обёртку, которая:
#   а) подставляет PYTHONPATH с нашей локальной папкой lib (psutil),
#   б) редиректит stdout/stderr в agent.log — без этого диагностировать
#      падение под SYSTEM нечем (Task Scheduler хранит только exit-код),
#   в) использует ПОЛНЫЙ путь к python.exe (под SYSTEM в PATH питона нет).
Write-Host "  [5/5] Пишем run.cmd и регистрируем Scheduled Task '$TaskName'..."

$pythonExe = $python.Source
$runCmdContent = @"
@echo off
setlocal
set PYTHONPATH=%~dp0lib
set PYTHONIOENCODING=utf-8
echo [%date% %time%] === agent start === >> "%~dp0agent.log"
"$pythonExe" "%~dp0agent.py" --env-file "%~dp0agent.env" >> "%~dp0agent.log" 2>&1
echo [%date% %time%] === agent exit %ERRORLEVEL% === >> "%~dp0agent.log"
exit /b %ERRORLEVEL%
"@
[System.IO.File]::WriteAllText($RunCmdPath, $runCmdContent, (New-Object System.Text.UTF8Encoding($false)))

# Удалить старую таску, если уже была
Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue

# Убиваем осиротевшие процессы агента от прошлых установок: перерегистрация
# задачи НЕ завершает уже запущенный python, и он продолжает слать метрики на
# свой (возможно уже удалённый) node_id, засоряя лог бесконечными 404.
Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -and $_.CommandLine -like '*\monitoring-agent\agent.py*' } |
  ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue } catch {} }

$action = New-ScheduledTaskAction `
  -Execute $RunCmdPath `
  -WorkingDirectory $InstallDir

$trigger = New-ScheduledTaskTrigger -AtStartup

$settings = New-ScheduledTaskSettingsSet `
  -AllowStartIfOnBatteries `
  -DontStopIfGoingOnBatteries `
  -StartWhenAvailable `
  -RestartCount 999 `
  -RestartInterval (New-TimeSpan -Minutes 1) `
  -ExecutionTimeLimit (New-TimeSpan -Days 0) `
  -MultipleInstances IgnoreNew

$principal = New-ScheduledTaskPrincipal `
  -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest

Register-ScheduledTask `
  -TaskName $TaskName `
  -Action $action -Trigger $trigger -Settings $settings -Principal $principal `
  -Description "Distributed Monitoring Agent — $Server" | Out-Null

Start-ScheduledTask -TaskName $TaskName

Write-Host ""
Write-Host "=== Готово ==="
Write-Host "  Управление:  Get-ScheduledTask -TaskName $TaskName"
Write-Host "  Состояние:   Get-ScheduledTaskInfo -TaskName $TaskName | Select State, LastTaskResult, LastRunTime"
Write-Host "  Лог агента:  Get-Content '$LogPath' -Tail 50 -Wait"
Write-Host "  Остановить:  Stop-ScheduledTask -TaskName $TaskName"
Write-Host "  Удалить:     Unregister-ScheduledTask -TaskName $TaskName -Confirm:`$false; Remove-Item -Recurse -Force '$InstallDir'"
Write-Host ""
Start-Sleep -Seconds 4
Get-ScheduledTaskInfo -TaskName $TaskName | Select-Object State, LastTaskResult, LastRunTime
if (Test-Path $LogPath) {
  Write-Host ""
  Write-Host "=== Первые строки лога ==="
  Get-Content $LogPath -Tail 20
}
