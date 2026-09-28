<#
.SYNOPSIS
  Dvoklik-start lokalnog Vrtić Connect okruženja (desktop prečica "Vrtić Connect").
  Prenosivi alati: C:\Posao\Vrtic-tools (jdk-21, pgsql, pgdata na portu 5433).
  Redosled: PostgreSQL -> migracije -> API (:8080) -> web dev server (:5173) -> browser.
  Svaki servis ide u svoj prozor; zatvaranje prozora gasi servis. Za sve odjednom: scripts\stop-vrtic.ps1.
#>
$ErrorActionPreference = "Stop"
$root  = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$tools = "C:\Posao\Vrtic-tools"
$pgBin = "$tools\pgsql\bin"; $pgData = "$tools\pgdata"; $dbPort = 5433
$jar   = "$root\backend\build\libs\vrtic-backend-all.jar"

function Step($m) { Write-Host "== $m" -ForegroundColor Cyan }
function Fail($m) { Write-Host "GRESKA: $m" -ForegroundColor Red; Read-Host "Enter za izlaz" | Out-Null; exit 1 }
function PortBusy($p) { $null -ne (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue) }

foreach ($path in @("$tools\jdk-21\bin\java.exe", "$pgBin\pg_ctl.exe", $pgData, $jar)) {
    if (-not (Test-Path $path)) { Fail "Nedostaje $path. Pokreni prvo: scripts\dev-up.ps1 -DbPort 5433" }
}
$env:JAVA_HOME = "$tools\jdk-21"; $env:Path = "$tools\jdk-21\bin;$pgBin;$env:Path"
$env:APP_ENV = "dev"; $env:DB_HOST = "127.0.0.1"; $env:DB_PORT = "$dbPort"; $env:APP_DB_NAME = "vrtic"
$env:APP_DB_OWNER_PASSWORD = "owner-pw"; $env:APP_DB_RUNTIME_PASSWORD = "runtime-pw"; $env:APP_WEB_ORIGIN = "http://localhost:5173"

Step "PostgreSQL 17 (port $dbPort)"
& "$pgBin\pg_ctl.exe" -D $pgData status *> $null
if ($LASTEXITCODE -ne 0) {
    & "$pgBin\pg_ctl.exe" -D $pgData -l "$tools\pg.log" -w start *> "$tools\pgstart.log"
    if ($LASTEXITCODE -ne 0) { Fail "PostgreSQL nije startovao, vidi $tools\pg.log" }
    Write-Host "startovan"
} else { Write-Host "vec radi" }

Step "Migracije"
& java -jar $jar migrate 2>&1 | Select-String "Migrations applied|Exception" | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { Fail "Migracije nisu prosle." }

Step "API na http://127.0.0.1:8080"
if (PortBusy 8080) {
    # Docker Compose profil `app` drzi 8080; lokalni jar je merodavan za razvoj
    if (Get-Command docker -ErrorAction SilentlyContinue) { docker stop vrtic-connect-api-1 *> $null }
    Start-Sleep -Seconds 2
    if (PortBusy 8080) { Fail "Port 8080 je zauzet drugim procesom." }
}
$apiCmd = "`$Host.UI.RawUI.WindowTitle='Vrtic API'; `$env:JAVA_HOME='$tools\jdk-21'; `$env:Path='$tools\jdk-21\bin;'+`$env:Path; " +
          "`$env:APP_ENV='dev'; `$env:DB_HOST='127.0.0.1'; `$env:DB_PORT='$dbPort'; `$env:APP_DB_NAME='vrtic'; `$env:APP_DB_RUNTIME_PASSWORD='runtime-pw'; `$env:APP_WEB_ORIGIN='http://localhost:5173'; " +
          "java -jar '$jar' serve"
Start-Process powershell -ArgumentList "-NoExit", "-Command", $apiCmd | Out-Null
$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    try { $r = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:8080/health/ready" -TimeoutSec 2; if ($r.StatusCode -eq 200) { $ready = $true; break } } catch { Start-Sleep -Milliseconds 750 }
}
if (-not $ready) { Fail "API se nije javio na /health/ready (vidi prozor 'Vrtic API')." }
Write-Host "spreman"

Step "Web na http://localhost:5173"
if (-not (Test-Path "$root\apps\admin-web\node_modules")) { Fail "apps\admin-web\node_modules ne postoji: pokreni 'npm ci' u apps\admin-web." }
if (-not (PortBusy 5173)) {
    Start-Process powershell -ArgumentList "-NoExit", "-Command", "`$Host.UI.RawUI.WindowTitle='Vrtic Web'; Set-Location '$root\apps\admin-web'; npm run dev" | Out-Null
    for ($i = 0; $i -lt 40; $i++) { if (PortBusy 5173) { break }; Start-Sleep -Milliseconds 500 }
} else { Write-Host "vec radi" }

Start-Process "http://localhost:5173"
Write-Host "`nOtvoreno. Nalog za probu: vlasnik@happykids.example.test / Pilot-Lozinka-2026!" -ForegroundColor Green
Write-Host "Tokovi za testiranje: docs\MANUAL_TEST_EPIC02.md. Gasenje svega: scripts\stop-vrtic.ps1"
Start-Sleep -Seconds 4
