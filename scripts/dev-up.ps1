<#
.SYNOPSIS
  Podiže lokalno DEV okruženje Vrtić Connect backenda u jednom koraku:
  role u bazi -> build -> migracije -> seed -> dev lozinke -> (opciono) pokretanje API-ja.

.EXAMPLE
  .\scripts\dev-up.ps1 -Serve
  .\scripts\dev-up.ps1 -DbPort 5433 -SkipRoles

.NOTES
  Zahteva: java 21 na PATH-u (ili JAVA_HOME), psql na PATH-u, PostgreSQL 17 koji radi.
  Lozinke role su DEV vrednosti; produkcija nikad ne koristi ovaj skript.
#>
[CmdletBinding()]
param(
    [string]$DbHost = "127.0.0.1",
    [int]$DbPort = 5432,
    [string]$DbName = "vrtic",
    [string]$SuperUser = "postgres",
    [string]$SuperPassword = "",
    [string]$OwnerPassword = "owner-pw",
    [string]$RuntimePassword = "runtime-pw",
    [string]$WorkerPassword = "worker-pw",
    [string]$DevPassword = "Pilot-Lozinka-2026!",
    [switch]$SkipRoles,
    [switch]$SkipBuild,
    [switch]$Serve
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

function Step($msg) { Write-Host "`n== $msg" -ForegroundColor Cyan }
function Fail($msg) { Write-Host "GRESKA: $msg" -ForegroundColor Red; exit 1 }

Step "Provera alata"
$javaVersion = (& java -version 2>&1 | Select-Object -First 1)
if (-not $javaVersion -or $javaVersion -notmatch '"21') { Fail "java 21 nije na PATH-u (dobijeno: $javaVersion). Postavi JAVA_HOME na Temurin 21." }
if (-not (Get-Command psql -ErrorAction SilentlyContinue)) { Fail "psql nije na PATH-u (PostgreSQL 17 bin folder)." }
Write-Host "java: $javaVersion"

if (-not $SkipRoles) {
    Step "Role i baza ($DbName na ${DbHost}:$DbPort)"
    if ($SuperPassword) { $env:PGPASSWORD = $SuperPassword } else { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
    & psql -v ON_ERROR_STOP=1 -h $DbHost -p $DbPort -U $SuperUser -d postgres `
        -v db_name=$DbName -v owner_pw=$OwnerPassword -v runtime_pw=$RuntimePassword -v worker_pw=$WorkerPassword `
        -f scripts/db/00_roles.sql
    if ($LASTEXITCODE -ne 0) { Fail "00_roles.sql nije prosao (superuser pristup? -SuperPassword)." }
    & psql -v ON_ERROR_STOP=1 -h $DbHost -p $DbPort -U $SuperUser -d postgres -c "ALTER DATABASE $DbName SET app.environment = 'dev';"
    if ($LASTEXITCODE -ne 0) { Fail "Oznaka dev baze nije prosla." }
}

$env:APP_ENV = "dev"; $env:DB_HOST = $DbHost; $env:DB_PORT = "$DbPort"; $env:APP_DB_NAME = $DbName
$env:APP_DB_OWNER_PASSWORD = $OwnerPassword; $env:APP_DB_RUNTIME_PASSWORD = $RuntimePassword
$jar = Join-Path $root "backend\build\libs\vrtic-backend-all.jar"

if (-not $SkipBuild) {
    Step "Build (gradle buildFatJar)"
    Push-Location backend
    & .\gradlew.bat buildFatJar --no-daemon --console=plain
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { Fail "Build nije prosao." }
}
if (-not (Test-Path $jar)) { Fail "Nema $jar (pokreni bez -SkipBuild)." }

Step "Migracije (Flyway, app_owner)"
& java -jar $jar migrate
if ($LASTEXITCODE -ne 0) { Fail "Migracije nisu prosle." }

Step "Seed (sinteticki podaci)"
$env:PGPASSWORD = $OwnerPassword
& psql -v ON_ERROR_STOP=1 -h $DbHost -p $DbPort -U app_owner -d $DbName -f docs/database/seed/dev_seed.sql | Select-Object -Last 1
if ($LASTEXITCODE -ne 0) { Fail "Seed nije prosao (baza mora biti oznacena kao dev)." }

Step "RLS negativni testovi (app_runtime)"
$env:PGPASSWORD = $RuntimePassword
& psql -v ON_ERROR_STOP=1 -h $DbHost -p $DbPort -U app_runtime -d $DbName -f docs/database/tests/rls_negative_tests.sql 2>&1 | Select-String "assertions passed|ERROR"
if ($LASTEXITCODE -ne 0) { Fail "RLS testovi nisu prosli." }
Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue

Step "Dev lozinke za seed naloge (SEED_DEV_PASSWORD)"
$env:SEED_DEV_PASSWORD = $DevPassword
foreach ($email in @("vlasnik@happykids.example.test", "admin@happykids.example.test", "vaspitac1@happykids.example.test", "roditelj12@example.test", "platform.admin@example.test")) {
    & java -jar $jar dev-set-password $email 2>&1 | Select-String "dev-set-password|No such|violates"
    if ($LASTEXITCODE -ne 0) { Fail "dev-set-password nije prosao za $email" }
}
Remove-Item Env:SEED_DEV_PASSWORD -ErrorAction SilentlyContinue

Write-Host "`nSpremno. Nalozi (lozinka: $DevPassword):" -ForegroundColor Green
Write-Host "  vlasnik@happykids.example.test   OWNER  Happy Kids"
Write-Host "  admin@happykids.example.test     ADMIN  Happy Kids"
Write-Host "  vaspitac1@happykids.example.test TEACHER Happy Kids"
Write-Host "  roditelj12@example.test          PARENT Happy Kids + TEACHER Suncica"
Write-Host "  platform.admin@example.test      SUPER_ADMIN (platforma trazi MFA, jos nije implementiran)"
Write-Host "Happy Kids id: 22222222-0000-0000-0000-000000000001   Suncica id: 22222222-0000-0000-0000-000000000002"
Write-Host "Tokovi za testiranje: docs\MANUAL_TEST_EPIC02.md"

if ($Serve) {
    Step "API na http://127.0.0.1:8080 (Ctrl+C za prekid)"
    & java -jar $jar serve
} else {
    Write-Host "`nPokretanje API-ja:  java -jar backend\build\libs\vrtic-backend-all.jar serve   (ili ponovi skript sa -Serve)"
}
