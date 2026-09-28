<#
  start-rocketmq.ps1 - Start RocketMQ 5.5.1 (namesrv + broker) with JDK17.
  Idempotent: skips components whose ports are already listening.
  - namesrv: 9876, broker: 10911
  - All store + logback output is rooted under <root>\.rocketmq
    (workspace-local; the sandbox forbids writes to C:\Users\ongin\{store,logs}).
  Logs: <root>\logs\rocketmq-{namesrv,broker}.{out,err}.log
#>
$ErrorActionPreference = 'Stop'

$jdk17 = 'D:\JDK-17'
$javaExe = Join-Path $jdk17 'bin\java.exe'
$rmqHome = 'D:\rocketmq\rocketmq-all-5.5.1-bin-release'
$rmqLib = Join-Path $rmqHome 'lib\*'
$rmqConf = Join-Path $rmqHome 'conf'
$root = Split-Path -Parent $PSScriptRoot            # D:\AI_study\SecKill-Plus
$rmqDataHome = Join-Path $root '.rocketmq'          # workspace-local store/log home
$logDir = Join-Path $root 'logs'
New-Item -ItemType Directory -Force -Path $logDir, $rmqDataHome | Out-Null

if (-not (Test-Path $javaExe)) { Write-Error "JDK17 missing: $javaExe"; exit 1 }
if (-not (Test-Path (Join-Path $rmqHome 'lib'))) { Write-Error "RocketMQ home missing: $rmqHome"; exit 1 }

function Test-Port([int]$p) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue)
}

function Quoted([string]$s) {
    return '"' + ($s -replace '"', '\"') + '"'
}

# Return $true if $port is listened by a process whose command line contains
# $expectedKeyword. Returns $false when free. When the port is taken by an
# unrelated process, that is an error (not a "skip").
function Test-RocketPort([int]$port, [string]$expectedKeyword) {
    $conn = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $conn) { return $false }
    $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($conn.OwningProcess)" -ErrorAction SilentlyContinue
    if ($owner -and $owner.CommandLine -match $expectedKeyword) {
        return $true
    }
    Write-Output "[rocketmq] WARNING: port $port is occupied by process $($conn.OwningProcess) which is not a RocketMQ component - continuing to start anyway."
    return $false
}

function Start-RocketProcess([string]$MainClass, [string]$LogName, [string[]]$ExtraArgs, [int]$Port, [int]$WaitSec) {
    if (Test-RocketPort $Port "$MainClass") {
        Write-Output "[rocketmq] $LogName already on port $Port - skip."
        return $true
    }
    $outLog = Join-Path $logDir "rocketmq-$LogName.out.log"
    $errLog = Join-Path $logDir "rocketmq-$LogName.err.log"

    $parts = New-Object System.Collections.Generic.List[string]
    $parts.Add('-Xms512m'); $parts.Add('-Xmx512m')
    $parts.Add('-XX:MaxDirectMemorySize=768m')
    $parts.Add('-Duser.home=' + $rmqDataHome)
    $parts.Add('-Drmq.logback.configurationFile=' + (Join-Path $rmqConf "rmq.$LogName.logback.xml"))
    $parts.Add('-cp'); $parts.Add($rmqConf + ';' + $rmqLib)
    $parts.Add($MainClass)
    foreach ($a in $ExtraArgs) { $parts.Add($a) }
    $argStr = ($parts | ForEach-Object { Quoted $_ }) -join ' '

    # Deliberately NO -RedirectStandardOutput/-RedirectStandardError here:
    # they would make this parent PowerShell wait on the child's pipes until
    # the daemon exits, hanging the terminal. RocketMQ already logs to
    # <root>\.rocketmq\logs\rocketmqlogs via its own logback config.
    $p = Start-Process -FilePath $javaExe -ArgumentList $argStr `
        -PassThru -WindowStyle Hidden
    Write-Output "[rocketmq] starting $LogName (pid=$($p.Id))... logs: $rmqDataHome\logs\rocketmqlogs"
    for ($i = 0; $i -lt $WaitSec; $i++) {
        Start-Sleep -Seconds 1
        if (Test-RocketPort $Port "$MainClass") {
            Write-Output "[rocketmq] $LogName listening on $Port - OK"
            return $true
        }
        if ($p.HasExited) {
            Write-Output "[rocketmq] $LogName exited early (code=$($p.ExitCode)). see logs under $rmqDataHome\logs\rocketmqlogs"
            Get-Content $errLog -Tail 25 -ErrorAction SilentlyContinue | ForEach-Object { Write-Output "  $_" }
            return $false
        }
    }
    Write-Output "[rocketmq] $LogName did not listen on $Port within ${WaitSec}s. see logs under $rmqDataHome\logs\rocketmqlogs"
    Get-Content $errLog -Tail 25 -ErrorAction SilentlyContinue | ForEach-Object { Write-Output "  $_" }
    return $false
}

$versionOutput = cmd /c "`"$javaExe`" -version 2>&1" | Select-Object -First 1
Write-Output "[rocketmq] using JDK: $versionOutput"

# RocketMQ locates conf/lib via ROCKETMQ_HOME env var at startup.
$env:ROCKETMQ_HOME = $rmqHome
$env:JAVA_HOME = $jdk17
$env:Path = "$jdk17\bin;$env:Path"

$nsOk = Start-RocketProcess 'org.apache.rocketmq.namesrv.NamesrvStartup' 'namesrv' @() 9876 60
if (-not $nsOk) { exit 1 }
Start-Sleep -Seconds 2

$brOk = Start-RocketProcess 'org.apache.rocketmq.broker.BrokerStartup' 'broker' @('-n', 'localhost:9876') 10911 120
if (-not $brOk) { exit 1 }
exit 0