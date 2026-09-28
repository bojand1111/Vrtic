# Ručno testiranje EPIC-a 02 (autentifikacija, sesije, dozvole)

Stanje koda: 28. 9. 2026. Automatski testovi (60) prolaze nad nativnim PostgreSQL-om 17 (vidi [VALIDATION_REPORT.md](VALIDATION_REPORT.md)). Ovaj dokument je za ručnu proveru API-ja pre komita. Web ljuska još nema ekrane za pozivnice, reset i sesije (E02-W02), pa se testira `curl`-om (Windows 11 ima `curl.exe`) ili Postman-om.

## Najkraći put

Na ovoj mašini je sve već postavljeno (28. 9.): prenosivi JDK 21 i PostgreSQL 17 u `C:\Posao\Vrtic-tools` (baza na portu 5433), a na desktopu su prečice **Vrtic Connect** (pokreće bazu, migracije, API na :8080, web na :5173 i otvara browser) i **Vrtic Connect STOP** (gasi sve). Iza prečica stoje `scripts\start-vrtic.ps1` i `scripts\stop-vrtic.ps1`. Docker baza (`vrtic-db` na 5432) je zaseban instanca i ne koristi se za ovaj tok.

Kada radi PostgreSQL 17 (Docker `docker compose up -d db` ili nativni) i `java -version` javlja 21, ceo odeljak 1 i 2 zamenjuje jedan skript iz korena repozitorijuma:

```powershell
.\scripts\dev-up.ps1 -Serve            # role, build, migracije, seed, RLS testovi, dev lozinke, pa API na :8080
.\scripts\dev-up.ps1 -DbPort 5433     # ako PostgreSQL sluša na drugom portu
.\scripts\dev-up.ps1 -SkipBuild -Serve # kad je jar već izgrađen
```

Skript ispiše naloge i lozinku na kraju. Proveren 28. 9. nad nativnim PostgreSQL 17.11 (bez `-Serve`).

## 0. Preduslovi

| Šta | Kako |
|---|---|
| JDK 21 | Temurin 21 (instaliran ili raspakovan zip); `java -version` mora da javi 21 |
| PostgreSQL 17 | ili Docker (`docker compose up -d db`) ili nativni PostgreSQL 17 na portu 5432 |
| Node 22 | samo ako testiraš web login formu (`apps/admin-web`) |

Sve komande ispod su PowerShell, iz korena repozitorijuma `C:\Posao\Vrtic`.

## 1. Baza

**Varijanta A: Docker**

```powershell
Copy-Item .env.example .env   # promeni lozinke u .env ako želiš
docker compose up -d db
```

**Varijanta B: nativni PostgreSQL 17** (superuser `postgres`, lozinke role po tvom izboru, ispod su primeri):

```powershell
psql -v ON_ERROR_STOP=1 -U postgres -d postgres -v db_name=vrtic -v owner_pw=owner-pw -v runtime_pw=runtime-pw -v worker_pw=worker-pw -f scripts/db/00_roles.sql
```

Zatim u oba slučaja (označava bazu kao dev, bez toga seed odbija):

```powershell
psql -U postgres -d postgres -c "ALTER DATABASE vrtic SET app.environment = 'dev';"
```

## 2. Build, migracije, seed, lozinka

```powershell
$env:APP_ENV="dev"; $env:DB_HOST="127.0.0.1"; $env:DB_PORT="5432"; $env:APP_DB_NAME="vrtic"
$env:APP_DB_OWNER_PASSWORD="owner-pw"; $env:APP_DB_RUNTIME_PASSWORD="runtime-pw"
cd backend
.\gradlew.bat buildFatJar
java -jar build\libs\vrtic-backend-all.jar migrate      # očekivano: "now at version v4"
cd ..
$env:PGPASSWORD="owner-pw";   psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U app_owner   -d vrtic -f docs/database/seed/dev_seed.sql        # očekivano: COMMIT
$env:PGPASSWORD="runtime-pw"; psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U app_runtime -d vrtic -f docs/database/tests/rls_negative_tests.sql   # očekivano: "37 assertions passed"
$env:SEED_DEV_PASSWORD="Pilot-Lozinka-2026!"
java -jar backend\build\libs\vrtic-backend-all.jar dev-set-password vlasnik@happykids.example.test     # OWNER Happy Kids
java -jar backend\build\libs\vrtic-backend-all.jar dev-set-password admin@happykids.example.test       # ADMIN Happy Kids
java -jar backend\build\libs\vrtic-backend-all.jar dev-set-password roditelj12@example.test            # PARENT u Happy Kids + TEACHER u Sunčica
java -jar backend\build\libs\vrtic-backend-all.jar dev-set-password platform.admin@example.test        # SUPER_ADMIN
```

Seed nalozi (svi `@example.test`): `vlasnik@happykids`, `admin@happykids`, `vaspitac1..3@happykids`, `roditelj01..12`, `vlasnik@suncica`, `vaspitac@suncica`, `roditelj@suncica`, `platform.admin`. ID Happy Kids: `22222222-0000-0000-0000-000000000001`, Sunčica: `22222222-0000-0000-0000-000000000002`.

## 3. Pokretanje API-ja

```powershell
java -jar backend\build\libs\vrtic-backend-all.jar serve
curl.exe -s http://127.0.0.1:8080/health/ready
```

Očekivano: `{"status":"UP","checks":{"database":"UP","migrations":"UP"}}`.

## 4. Tokovi za proveru (mobilni, Bearer režim)

Skraćenica: `$B = "http://127.0.0.1:8080/api/v1"`. Odgovori sa greškom su uvek `application/problem+json` sa `requestId`.

### 4.1 Prijava i profil

```powershell
curl.exe -s -X POST $B/auth/login -H "Content-Type: application/json" -d '{"email":"vlasnik@happykids.example.test","password":"Pilot-Lozinka-2026!","clientKind":"ANDROID"}'
```

Očekivano: 200, `status: AUTHENTICATED`, `tokens.accessToken` počinje sa `vca_`, `refreshToken` sa `vcr_`. Sačuvaj oba: `$A="vca_..."`, `$R="vcr_..."`.

```powershell
curl.exe -s $B/me -H "Authorization: Bearer $A"                 # 200, email, isPlatformAdmin:false, mfaEnabled:false
curl.exe -s $B/me/memberships -H "Authorization: Bearer $A"     # 200, Happy Kids, role OWNER
curl.exe -s $B/organizations/22222222-0000-0000-0000-000000000001/ping -H "Authorization: Bearer $A"   # 200, organizationName Happy Kids, role OWNER
curl.exe -s -o NUL -w "%{http_code}`n" $B/organizations/22222222-0000-0000-0000-000000000002/ping -H "Authorization: Bearer $A"   # 404 (nije član Sunčice)
curl.exe -s -o NUL -w "%{http_code}`n" $B/me                    # 401 bez tokena
```

### 4.2 Pogrešna lozinka i zaključavanje (kriterijum 8)

Pošalji 10 puta prijavu sa pogrešnom lozinkom za `admin@happykids.example.test` (očekivano 401 svaki put), pa 11. put sa tačnom:

```powershell
1..10 | % { curl.exe -s -o NUL -w "%{http_code} " -X POST $B/auth/login -H "Content-Type: application/json" -d '{"email":"admin@happykids.example.test","password":"pogresna-lozinka-x","clientKind":"ANDROID"}' }
curl.exe -s -i -X POST $B/auth/login -H "Content-Type: application/json" -d '{"email":"admin@happykids.example.test","password":"Pilot-Lozinka-2026!","clientKind":"ANDROID"}'
```

Očekivano: 429 sa zaglavljem `Retry-After` i `detail: RATE_LIMITED`. Isto važi za nepostojeći e-mail (probaj `niko@example.test`). Otključavanje bez čekanja 15 minuta: ponovo `dev-set-password admin@happykids.example.test` (briše `locked_until` i brojač), a `login_attempts` ostaju, pa sačekaj 15 minuta ili ih obriši kao `app_owner` u maintenance režimu:

```powershell
$env:PGPASSWORD="owner-pw"; psql -h 127.0.0.1 -U app_owner -d vrtic -c "SELECT set_config('app.maintenance_mode','on',false); DELETE FROM app.login_attempts;"
```

### 4.3 Refresh, ponovna upotreba, sesije

```powershell
curl.exe -s -X POST $B/auth/refresh -H "Content-Type: application/json" -d "{\"refreshToken\":\"$R\"}"    # 200, novi par tokena -> sačuvaj kao $A2, $R2
curl.exe -s -i -X POST $B/auth/refresh -H "Content-Type: application/json" -d "{\"refreshToken\":\"$R\"}" # 401 REFRESH_REUSE_DETECTED (stari token ponovo)
curl.exe -s -o NUL -w "%{http_code}`n" $B/me -H "Authorization: Bearer $A2"                                # 401: reuse je opozvao celu sesiju
```

Prijavi se ponovo (novi `$A`), pa:

```powershell
curl.exe -s $B/auth/sessions -H "Authorization: Bearer $A"      # lista sesija, tekuća ima isCurrent:true
curl.exe -s -o NUL -w "%{http_code}`n" -X DELETE $B/auth/sessions/<id-druge-sesije> -H "Authorization: Bearer $A"   # 204; token te sesije posle daje 401
curl.exe -s -o NUL -w "%{http_code}`n" -X POST $B/auth/logout-all -H "Authorization: Bearer $A"                     # 204 odmah posle prijave (sveža autentifikacija)
```

### 4.4 Reautentifikacija (kriterijum 11)

Prijavi se, sačekaj više od 5 minuta (ili u bazi pomeri `sessions.created_at` unazad), pa:

```powershell
curl.exe -s -i -X POST $B/auth/logout-all -H "Authorization: Bearer $A"                # 403 REAUTHENTICATION_REQUIRED
curl.exe -s -X POST $B/auth/reauthenticate -H "Authorization: Bearer $A" -H "Content-Type: application/json" -d '{"password":"Pilot-Lozinka-2026!"}'   # 200, validUntil = +5 min
curl.exe -s -o NUL -w "%{http_code}`n" -X POST $B/auth/logout-all -H "Authorization: Bearer $A"   # 204
```

### 4.5 Promena lozinke

```powershell
curl.exe -s -i -X POST $B/me/password -H "Authorization: Bearer $A" -H "Content-Type: application/json" -d '{"currentPassword":"pogresna","newPassword":"Nova-Lozinka-2026!"}'     # 403 CURRENT_PASSWORD_INVALID
curl.exe -s -i -X POST $B/me/password -H "Authorization: Bearer $A" -H "Content-Type: application/json" -d '{"currentPassword":"Pilot-Lozinka-2026!","newPassword":"kratka"}'     # 422 TOO_SHORT
curl.exe -s -o NUL -w "%{http_code}`n" -X POST $B/me/password -H "Authorization: Bearer $A" -H "Content-Type: application/json" -d '{"currentPassword":"Pilot-Lozinka-2026!","newPassword":"Nova-Lozinka-2026!"}'   # 204; druge sesije 401, ova radi
```

### 4.6 Zaboravljena lozinka i reset

```powershell
curl.exe -s -o NUL -w "%{http_code}`n" -X POST $B/auth/forgot-password -H "Content-Type: application/json" -d '{"email":"roditelj12@example.test"}'   # 202
```

Link je u konzoli API-ja (logger `com.vrticconnect.mail.DEV`, samo u dev): `...reset-password?token=vcp_...`. Zatim:

```powershell
curl.exe -s -o NUL -w "%{http_code}`n" -X POST $B/auth/reset-password -H "Content-Type: application/json" -d '{"token":"vcp_...","newPassword":"Reset-Lozinka-2026!"}'   # 204; isti token ponovo -> 404 TOKEN_INVALID
```

Četvrti `forgot-password` za isti e-mail u istom satu daje 429. Nepostojeći e-mail daje 202 bez mejla u logu.

### 4.7 Pozivnica i registracija

Endpoint za kreiranje pozivnice je EPIC 04, pa se pozivnica ubacuje SQL-om. Token mora imati oblik `vci_` + 43 base64url znaka; primer generisanja i upisa (hash je sha256 tokena):

```powershell
$token = "vci_" + (-join ((48..57 + 65..90 + 97..122) | Get-Random -Count 43 | % {[char]$_}))
$hash = [System.Security.Cryptography.SHA256]::HashData([Text.Encoding]::ASCII.GetBytes($token)); $hex = ($hash | % { $_.ToString("x2") }) -join ""
$env:PGPASSWORD="owner-pw"; psql -h 127.0.0.1 -U app_owner -d vrtic -c "SELECT set_config('app.maintenance_mode','on',false); INSERT INTO app.invitations (organization_id, email, role, token_hash, invited_by, expires_at) VALUES ('22222222-0000-0000-0000-000000000001','nova.vaspitacica@example.test','TEACHER', decode('$hex','hex'), '11111111-0000-0000-0000-000000000010', now() + interval '7 days');"
curl.exe -s $B/auth/invitations/$token                                                    # 200, Happy Kids, role TEACHER, requiresRegistration:true
curl.exe -s -X POST $B/auth/register -H "Content-Type: application/json" -d "{\"invitationToken\":\"$token\",\"password\":\"Nova-Vaspitacica-2026!\",\"givenName\":\"Nova\",\"familyName\":\"Vaspitačica\",\"device\":{\"clientKind\":\"ANDROID\"}}"   # 201 sa tokenima
curl.exe -s -o NUL -w "%{http_code}`n" $B/auth/invitations/$token                        # 404 (potrošena)
```

### 4.8 Dozvole i platforma

Sa tokenom ADMIN-a (`admin@happykids`) i OWNER-a:

```powershell
curl.exe -s $B/organizations/22222222-0000-0000-0000-000000000001/settings -H "Authorization: Bearer $ADMIN"    # 200
curl.exe -s -o NUL -w "%{http_code}`n" -X PUT $B/organizations/22222222-0000-0000-0000-000000000001/settings -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" -d '{"scheduleChangeDeadlineHours":24,"lateArrivalGraceMinutes":10,"dayOpensAt":"06:30","dayClosesAt":"17:30","workingWeekdays":[1,2,3,4,5],"offlineCacheTtlHours":8}'   # 403 (ADMIN nema ORG_SETTINGS_MANAGE)
# isto sa $OWNER -> 200
curl.exe -s -o NUL -w "%{http_code}`n" $B/platform/organizations -H "Authorization: Bearer $OWNER"      # 404 (nije platform admin)
curl.exe -s -i $B/platform/organizations -H "Authorization: Bearer $PLATFORM"                            # 403 MFA_REQUIRED (MFA je E02-B11)
```

Da vidiš platformsku listu pre MFA, privremeno u bazi: `UPDATE app.sessions SET mfa_verified_at = now() WHERE user_id = '11111111-0000-0000-0000-000000000001';` (kao `app_owner` u maintenance režimu), pa ponovi poziv: 200 sa Happy Kids i Sunčica.

## 5. Web (cookie režim)

```powershell
cd apps\admin-web; npm ci; npm run dev     # http://localhost:5173, proxy ka 8080
```

Prijava sa `vlasnik@happykids.example.test` i dev lozinkom: očekivano je da forma prođe (backend vraća 200 i postavlja `vc_access`, `vc_refresh` HttpOnly i `vc_csrf` kolačić), a SPA posle bootstrap-a (`GET /api/v1/auth/session`) prikaže ljusku sa tenant switcher-om. U DevTools: `document.cookie` sadrži samo `vc_csrf`, `localStorage` nema tokena. Mutacija bez `X-CSRF-Token` (npr. ručno `fetch('/api/v1/auth/logout',{method:'POST',credentials:'include'})`) daje 403.

## 6. Šta NIJE deo ovog testa

MFA (E02-B11/B12), lista pozivnica i njihovo kreiranje kroz API (E04), guardian veze (E05), čišćenje isteklih tokena (E02-B17), outbox/notifikacije (E02-B18/E11), mobilna aplikacija (E02-M01 do M06), Docker Compose profil `app` (nije proveren na ovoj mašini).

## 7. Šta prijaviti nazad

Za svaki korak koji ne da očekivani status: tačna komanda, dobijeni status, `requestId` iz odgovora i odgovarajući red iz konzole API-ja (bez tokena i lozinki).
