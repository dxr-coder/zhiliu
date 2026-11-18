# SmartFlow one-click start: Redis + Java + Python AI + Vite
# Usage: powershell -ExecutionPolicy Bypass -File start-all.ps1
# Run from zhiliu/server/. MySQL must already listen on 3306.
$ErrorActionPreference = "Stop"
$serverRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $serverRoot
$pyRoot = Join-Path $repoRoot "backend"
$webRoot = Join-Path $repoRoot "web"
$env:JAVA_HOME = "D:/java/jdk21"
$env:Path = "D:/java/jdk21/bin;D:/java/maven/apache-maven-3.10.0/bin;" + $env:Path

Write-Host "[0/4] Redis :6379..." -ForegroundColor Cyan
$redisOk = Test-NetConnection -ComputerName 127.0.0.1 -Port 6379 -InformationLevel Quiet -WarningAction SilentlyContinue
if (-not $redisOk) {
  $redisExe = "D:/Redis/redis-server.exe"
  if (Test-Path $redisExe) {
    Start-Process -FilePath $redisExe -ArgumentList "--port","6379" -WindowStyle Minimized
    Write-Host "  started redis-server" -ForegroundColor Green
  } else {
    Write-Warning "Redis not running and exe not found (rate-limit will fail-open)"
  }
} else {
  Write-Host "  Redis already on 6379" -ForegroundColor Green
}

Write-Host "[1/4] MySQL :3306 check..." -ForegroundColor Cyan
$mysqlOk = Test-NetConnection -ComputerName 127.0.0.1 -Port 3306 -InformationLevel Quiet -WarningAction SilentlyContinue
if (-not $mysqlOk) { Write-Warning "3306 not open, start MySQL first" }

Write-Host "[2/4] Java :8081 ..." -ForegroundColor Cyan
$javaCmd = "cd '" + $serverRoot + "'; $env:JAVA_HOME='D:/java/jdk21'; $env:Path='D:/java/jdk21/bin;D:/java/maven/apache-maven-3.10.0/bin;' + $env:Path; mvn -DskipTests spring-boot:run"
Start-Process -FilePath "powershell" -ArgumentList "-NoExit","-Command",$javaCmd -WindowStyle Minimized

Write-Host "[3/4] Python AI :8000 ..." -ForegroundColor Cyan
$pyCmd = "cd '" + $pyRoot + "'; & 'D:/Anaconda/envs/ResearchFlow/python.exe' -m uvicorn app.main:app --host 127.0.0.1 --port 8000"
Start-Process -FilePath "powershell" -ArgumentList "-NoExit","-Command",$pyCmd -WindowStyle Minimized

Write-Host "[4/4] Vite :5173 ..." -ForegroundColor Cyan
$webCmd = "cd '" + $webRoot + "'; npm run dev -- --port 5173 --host 127.0.0.1"
Start-Process -FilePath "powershell" -ArgumentList "-NoExit","-Command",$webCmd -WindowStyle Minimized

Write-Host "Started 3 windows. Open http://127.0.0.1:5173 when ready." -ForegroundColor Green
Write-Host "Health: Java :8081/actuator/health  Python :8000/health"
