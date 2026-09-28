<#
  start-redis.ps1 - Start local Redis (3.2.100) if not already running.
  Idempotent: exits 0 with a message when port 6379 is already listening.
  Logs: <root>/logs/redis.{out,err}.log
#>
$ErrorActionPreference = 'Stop'

$redisExe = 'D:\Redis-x64-3.2.100\redis-server.exe'
$port = 6379
$logDir = Join-Path $PSScriptRoot '..\logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Test-Port([int]$p) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue)
}

if (Test-Port $port) {
    Write-Output "[redis] already running on port $port - skip start."
    exit 0
}

if (-not (Test-Path $redisExe)) {
    Write-Error "[redis] executable not found: $redisExe"
    exit 1
}

$outLog = Join-Path $logDir 'redis.out.log'
$errLog = Join-Path $logDir 'redis.err.log'
# Keep logs via redis' own --logfile (avoids Start-Process -Redirect*, which
# would make this parent PowerShell hang until the daemon exits).
$p = Start-Process -FilePath $redisExe -ArgumentList @('--logfile', $outLog) -PassThru -WindowStyle Hidden
Write-Output "[redis] starting (pid=$($p.Id))..."

for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 1
    if (Test-Port $port) {
        Write-Output "[redis] listening on $port - OK"
        exit 0
    }
}
Write-Error "[redis] failed to listen on $port within 30s. Check $errLog"
exit 1