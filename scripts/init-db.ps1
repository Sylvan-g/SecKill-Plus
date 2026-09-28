# ============================================================
# init-db.ps1 -- Init SecKill-Plus 3 databases: schema + seed
# Ticket 3
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\init-db.ps1
# Requires mysql client in PATH (D:\development\mysql-8.0.31-winx64\bin)
#
# Idempotent by design:
#   1. CREATE DATABASE IF NOT EXISTS
#   2. each schema file DROP+CREATE (rebuild table structure)
#   3. seed.sql uses INSERT ... ON DUPLICATE KEY UPDATE
# NOTE: this DESTROYS existing tables in the 3 seckill_* databases
#       (demo environment only; do NOT run against production)
# ============================================================
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$mysqlUser = 'root'
# 口令绝不入库：只允许环境变量提供，无明文兜底
$mysqlPass = $env:SECKILL_DB_PASSWORD
if (-not $mysqlPass) {
    Write-Error "[init-db] 请先设置环境变量 SECKILL_DB_PASSWORD（数据库口令不入库，禁止明文兜底）。"
    Write-Error "  示例: `$env:SECKILL_DB_PASSWORD='<你的数据库口令>'; powershell -ExecutionPolicy Bypass -File scripts\init-db.ps1"
    exit 1
}

function Invoke-SqlFile([string]$file) {
    if (-not (Test-Path $file)) { throw "SQL file not found: $file" }
    # use "source" so mysql reads the file bytes itself (keeps utf8mb4 intact);
    # PowerShell '<' redirect is unsupported in PS 5.1.
    # NOTE: current repo path (D:\AI_study\SecKill-Plus) contains no spaces;
    #       if the repo is ever relocated to a path WITH spaces, mysql's
    #       client-side "source" can't quote it reliably via --execute=,
    #       so the script would need a different loading strategy (e.g. pipe).
    $src = ($file -replace '\\', '/')
    $mysqlArgs = @("-u$mysqlUser", "-p$mysqlPass", "--default-character-set=utf8mb4", "--execute=source $src")
    & mysql @mysqlArgs
    if ($LASTEXITCODE -ne 0) { throw "SQL failed ($LASTEXITCODE): $file" }
    Write-Output "[init-db] OK $file"
}

$sqlDir = Join-Path $root 'sql'
$schemaUser  = Join-Path $sqlDir 'schema_user.sql'
$schemaGoods = Join-Path $sqlDir 'schema_goods.sql'
$schemaOrder = Join-Path $sqlDir 'schema_order.sql'
$seed        = Join-Path $sqlDir 'seed.sql'

Write-Output "[init-db] init 3 databases (root@localhost) ..."

Invoke-SqlFile $schemaUser
Invoke-SqlFile $schemaGoods
Invoke-SqlFile $schemaOrder
Invoke-SqlFile $seed

Write-Output "[init-db] done. databases: seckill_user / seckill_goods / seckill_order"
Write-Output "[init-db] demo accounts: user_demo / ops_demo / admin_demo (password: 123456)"