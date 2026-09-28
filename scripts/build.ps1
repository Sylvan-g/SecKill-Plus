<#
  build.ps1 - Build the whole Maven project with JDK17.
  Tolerant: prints a note and exits 0 when the Maven root (pom.xml) has not
  been created yet (expected before Ticket 2 adds the multi-module skeleton).
  JDK is switched to D:\JDK-17 for the Maven process only, global JAVA_HOME untouched.
#>
$ErrorActionPreference = 'Stop'

# 终端输出统一 UTF-8，避免 GBK 代码页渲染 Maven 中文日志乱码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$jdk17 = 'D:\JDK-17'
$root = Split-Path -Parent $PSScriptRoot   # D:\AI_study\SecKill-Plus
Set-Item -Path Env:JAVA_HOME -Value $jdk17
$env:Path = "$jdk17\bin;$env:Path"

Write-Output "== build.ps1 (root=$root) =="
# java -version writes to stderr; run via cmd to capture stdout+stderr as plain text
cmd /c "`"$jdk17\bin\java.exe`" -version 2>&1" | ForEach-Object { Write-Output "  $_" }

if (-not (Test-Path (Join-Path $root 'pom.xml'))) {
    Write-Output "[build] Maven root not created yet (pom.xml missing) - nothing to compile. Skip (Ticket 2 will create it)."
    exit 0
}

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvn) {
    Write-Error "mvn not found on PATH"
    exit 1
}

Push-Location $root
try {
    Write-Output "[build] running: mvn -q compile"
    # 重定向 Maven 本地仓库到项目内 .\.mvn-repo：
    # 默认仓库 D:\apache-maven-3.9.9-bin\...\mvn-repo 在受限会话沙箱中不可写；
    # 同时使构建仓库自包含于项目，构建行为可复现。
    & mvn -q compile -Dmaven.repo.local="$root\.mvn-repo"
    if ($LASTEXITCODE -ne 0) {
        Write-Error "mvn compile failed (exit=$LASTEXITCODE)"
        exit 1
    }
    Write-Output "[build] compile OK"
} finally {
    Pop-Location
}
exit 0