<#
  run-service.ps1 - Run one service jar with JDK17.
  Usage: .\scripts\run-service.ps1 <serviceName>
         serviceName in: gateway | user | goods | order | scheduler
  Tolerant: prints a note and exits 0 when the jar is not built yet.
  JDK is switched to D:\JDK-17 for this process only.
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('gateway', 'user', 'goods', 'order', 'scheduler')]
    [string]$Service
)

$ErrorActionPreference = 'Stop'

$jdk17 = 'D:\JDK-17'
$root = Split-Path -Parent $PSScriptRoot
Set-Item -Path Env:JAVA_HOME -Value $jdk17
$env:Path = "$jdk17\bin;$env:Path"

$module = "seckill-$Service"
$jarDir = Join-Path $root (Join-Path $module 'target')

Write-Output "== run-service.ps1 (service=$Service) =="
# java -version writes to stderr; run via cmd to capture stdout+stderr as plain text
cmd /c "`"$jdk17\bin\java.exe`" -version 2>&1" | ForEach-Object { Write-Output "  $_" }

$jar = Get-ChildItem -Path $jarDir -Filter "seckill-$Service-*.jar" -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' -and $_.Name -notlike '*.original' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $jar) {
    Write-Output "[run] jar not found under $jarDir - not built yet. Build it first, then rerun."
    exit 0
}

Write-Output "[run] executing: java -jar $($jar.FullName)"
Push-Location $root
try {
    & "$jdk17\bin\java.exe" -jar $jar.FullName
    exit $LASTEXITCODE
} finally {
    Pop-Location
}