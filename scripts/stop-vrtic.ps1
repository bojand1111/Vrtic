<#
.SYNOPSIS
  Gasi sve sto je start-vrtic.ps1 pokrenuo: web dev server (:5173), API (:8080) i prenosivi PostgreSQL (:5433).
#>
$tools = "C:\Posao\Vrtic-tools"
function KillPort($port, $name) {
    $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if (-not $conns) { Write-Host "$name ($port): nije pokrenut"; return }
    foreach ($pid in ($conns.OwningProcess | Sort-Object -Unique)) {
        $p = Get-Process -Id $pid -ErrorAction SilentlyContinue
        if ($p -and $p.ProcessName -notmatch "docker|wslrelay") { Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue; Write-Host "$name ($port): ugasen ($($p.ProcessName) $pid)" }
        else { Write-Host "$name ($port): drzi ga $($p.ProcessName), preskacem" }
    }
}
KillPort 5173 "Web"
KillPort 8080 "API"
& "$tools\pgsql\bin\pg_ctl.exe" -D "$tools\pgdata" status *> $null
if ($LASTEXITCODE -eq 0) { & "$tools\pgsql\bin\pg_ctl.exe" -D "$tools\pgdata" stop -m fast | Select-Object -Last 1 } else { Write-Host "PostgreSQL (5433): nije pokrenut" }
Get-Process powershell -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -in @("Vrtic API", "Vrtic Web") } | Stop-Process -Force -ErrorAction SilentlyContinue
Write-Host "Gotovo." -ForegroundColor Green
Start-Sleep -Seconds 2
