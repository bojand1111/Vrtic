# Izveštaj o validaciji – foundation skeleton + EPIC 02 auth slice

Dopuna 28. 9. 2026. (E02-B14 tenant pipeline i E02-B08 upravljanje sesijama): provere u redovima 15–25 izvršene na istoj mašini sa prenosivim Temurin JDK 21.0.12.1 i nativnim PostgreSQL 17.11 (EDB zip) u privremenom folderu; Docker daemon toga dana nije bio dostupan (`docker version` bez odgovora), pa Docker provere iz reda 13 nisu ponovljene.

Datum: 16. 9. 2026. Okruženje: Windows 11 Pro, bez sistemskog JDK-a, bez Android SDK-a, bez Xcode-a; Node 22.23.1; Docker Desktop 4.83.0. Backend build i runtime provere izvršene su u Dockeru sa Temurin JDK/JRE 21 i PostgreSQL 17.11.

Ništa od prethodno prijavljenih provera iz ranijeg planiranja nije preuzeto – sve ispod je izvršeno u ovom okruženju.

## 1. Šta je projektovano

- Specifikacija proizvoda, arhitektura, bezbednost, infrastruktura, mobile/web arhitektura, roadmap sa 18 EPIC-a i 254 taska, 8 ADR-ova (`docs/`).
- Kompletan model podataka: 72 tabele, sve tenant tabele sa RLS ENABLE + FORCE, 160 politika (`docs/database/schema.sql`), ER dijagrami.
- REST ugovor: OpenAPI 3.1 sa 167 operacija (P0 116, P1 51), `docs/API.md` sa konvencijama i primerima.
- Auth (opaque sesije, rotacija, MFA), attendance event model, offline algoritam, consent/photo model, billing/feature flag model.

## 2. Šta je stvarno implementirano

| Deo | Implementirano |
|---|---|
| Backend | Ktor bootstrap; `serve` / `migrate` / `benchmark-argon2` komande; env konfiguracija bez default tajni van DEV; Hikari pool-ovi (runtime/owner); `Database.transaction(DbContext)` sa `set_config(..., true)`; Flyway V1+V2 (29 tabela + RLS + grants + feature flagovi + session CSRF hash); `/health/live`, `/health/ready` (baza + migracije); problem+json, request id, sanitizovano logovanje, bezbednosna zaglavlja; Argon2id login, opaque access/refresh rotacija, cookie session bootstrap, session-bound CSRF/Origin provera i logout opoziv; `PermissionMatrix`; **E02-B14**: `MembershipResolver` (aktivno članstvo u `User` kontekstu, pa tek onda `DbContext.Tenant` transakcija), `GET /organizations/{id}/ping` kao testiran primer pipeline-a (401 → 404 → RLS), `GET /me/memberships` po OpenAPI `MyMembership` obliku; Flyway V3 (`self_membership_permissions`, `self_employee`, `organizations_access` za sva neopozvana članstva); **E02-B08**: `GET /auth/sessions` (cursor paginacija, `isCurrent`), `DELETE /auth/sessions/{id}` (opoziv sesije i njenih tokena; tuđa sesija 404; idempotentno), `POST /auth/logout-all` (uslov skorašnje autentifikacije 5 min, inače 403 `REAUTHENTICATION_REQUIRED`), `AuthCookies` zajednički za login/refresh/logout, `last_seen_at` pri refresh-u, generička cursor paginacija (`http/Pagination.kt`), neispravan URL escape → 400 umesto 500; **E02-B04/B05/B09/B20**: `AccountService` (pregled pozivnice, registracija preko pozivnice sa izdavanjem sesije, prihvatanje postojećim nalogom, verifikacija e-maila + resend, forgot/reset lozinke sa opozivom svih sesija), `PasswordPolicy` (12–256, blocklist, ne sme biti e-mail), `Audit.record` (append-only zapisi u istoj transakciji), `MailSender` (DEV log / neconfigurisano), Flyway V4 (auth-mode politike za `invitations`, `organizations`, `organization_memberships`); **E02-B13**: `RateLimiter` (in-memory fixed window po ključu), `login_attempts` upis pri svakoj prijavi, `failed_login_count`/`locked_until` sa eksponencijalnim zaključavanjem, `429 RATE_LIMITED` + `Retry-After` bez otkrivanja naloga, limiti po IP-ju (20/min) i po e-mailu (3/h), `APP_TRUST_PROXY`; **E02-B15**: `Authorize` (require/requireAny/permissionsOf sa grantable extras, `requirePlatformAdmin` sa MFA uslovom), `GroupScope`/`ChildScope` interfejsi, prvi tenant resurs `GET/PUT /organizations/{id}/settings` i platformska lista `GET /platform/organizations`; **E02-B10**: `ReauthService` + `RecentAuthentication` (5 min, deli ga `logout-all`); **E02-D03**: CLI `dev-set-password` (samo DEV, `SEED_DEV_PASSWORD`); **E02-B16**: `MeService` (`GET /me`, `PUT /me/locale`, `POST /me/password` sa proverom trenutne lozinke, politikom, opozivom ostalih sesija i auditom) |
| Baza | `scripts/db/00_roles.sql` (app_owner/app_runtime/app_worker, bez BYPASSRLS), `V1__foundation.sql`, `V2__session_csrf.sql`, `V3__self_read_policies.sql`, seed sa guard-om protiv ne-dev baze, `rls_negative_tests.sql` (37 tvrdnji) |
| Web | React 19 + TS strict + Vite ljuska: login forma koja prikazuje `501` problem, health widget, i18n (sr-Latn, sr-Cyrl, en), tenant switcher sa brisanjem keša, 16 placeholder ekrana, 17 testova |
| Mobile | KMP: `shared-core` (API klijent, problem parser, `DailyCounters`, i18n katalog, auth skeleton bez tokena; 25 testova), `shared-ui` (App ekran, locale switcher, „Check API”), `androidApp`, `iosApp` (XcodeGen) |
| Infra | `docker-compose.yml` (db + profil `app`), `Dockerfile` (multi-stage, non-root), nginx konfiguracija, GitHub Actions CI (backend+PG17, OpenAPI lint, web, mobile shared, Android, iOS gate, scan) |

## 3. Izvršene komande i rezultati

| # | Provera | Komanda (skraćeno) | Rezultat |
|---|---|---|---|
| 1 | Backend build + unit testovi | `backend$ ./gradlew build` | **PROŠLO** – 26 testova: Argon2idPasswordHasherTest 4, AuthClosedRoutesTest 6, HealthRoutesTest 4, PermissionMatrixTest 6, RlsIntegrationTest 6 (bez DB: 6 preskočena; sa DB: 6 prošla) |
| 2 | Fat jar | `./gradlew buildFatJar` | **PROŠLO** – `build/libs/vrtic-backend-all.jar` (27,7 MB) |
| 3 | DB role | `psql -U postgres -f scripts/db/00_roles.sql` (PG 17.11) | **PROŠLO** |
| 4 | Flyway migracija | `java -jar ... migrate` (app_owner) | **PROŠLO** – „Successfully applied 1 migration … now at version v1” |
| 5 | Seed | `psql -U app_owner -f docs/database/seed/dev_seed.sql` | **PROŠLO** – COMMIT; ponovljeno bez `app.environment=dev`: **odbijeno** sa porukom, kako je projektovano |
| 6 | RLS negativni testovi | `psql -U app_runtime -f docs/database/tests/rls_negative_tests.sql` | **PROŠLO** – „37 assertions passed”, exit 0 |
| 7 | Kompletan schema proposal | `psql -U app_owner -d vrtic_proposal -f docs/database/schema.sql` | **PROŠLO** – 72 tabele, 72 sa FORCE RLS, 160 politika, exit 0 |
| 8 | Integracioni testovi (pool reuse) | `VRTIC_TEST_DB=1 ./gradlew test --rerun-tasks` | **PROŠLO** – RlsIntegrationTest 6/6: bez konteksta 0 redova + odbijen upis; tenant A/B izolacija; cross-tenant upis odbijen (42501); nema curenja konteksta posle COMMIT i posle ROLLBACK (pool veličine 1); vlasnik bez maintenance režima vidi 0 redova, sa režimom 2 |
| 9 | API smoke | `java -jar ... serve` + curl | **PROŠLO** – `/health/live` 200; `/health/ready` 200 `{database:UP, migrations:UP}`; `POST /api/v1/auth/login` 501 `application/problem+json` bez tokena/kolačića; `GET /api/v1/auth/session` 401; `GET /organizations/{id}/ping` sa lažnim Bearer-om 401; nepoznata ruta 404 problem+json |
| 10 | OpenAPI lint | `npx @redocly/cli@2 lint docs/openapi.yaml` | **PROŠLO** – 0 grešaka, 0 upozorenja |
| 11 | Web | `npm ci`, `npm run typecheck`, `npm run lint`, `npm run test -- --run`, `npm run build` | **PROŠLO** – 17/17 testova, build OK (izvršio agent u ovom okruženju; napomena: prvi `npm install` je zahtevao `--legacy-peer-deps` zbog npm 10 greške, lockfile posle toga radi sa `npm ci`) |
| 12 | Mobile shared | `apps/mobile$ ./gradlew :shared-core:jvmTest` | **PROŠLO** – 25/25 testova (Kotlin 2.3.21, Gradle 9.3.0) |
| 13 | Docker Compose | `docker compose --profile app up --build -d` | **PROŠLO** – `vrtic-db` healthy, API na `localhost:8080` |
| 15 | (28. 9.) Backend build + svi testovi, integracioni uključeni | `VRTIC_TEST_DB=1 ./gradlew test --rerun-tasks` nad nativnim PG 17.11 | **PROŠLO** – 36 testova, 0 preskočenih: Argon2 4, AuthClosedRoutes 6, HealthRoutes 4, PermissionMatrix 6, RlsIntegration 6, SessionResolverIntegration 5, **TenantContextIntegration 5** (član → 200 sa imenom organizacije pročitanim kroz RLS, ulogom i permisijama; opozvano članstvo, tuđi tenant, nepostojeći i neispravan id, platform admin bez članstva → 404; bez/nevažeći token → 401; multi-tenant korisnik dobija PARENT u A i TEACHER u B; `/me/memberships` sa filterom i 422 za nedozvoljen status) |
| 16 | (28. 9.) Migracije V1..V3 + seed + RLS SQL testovi | `java -jar ... migrate`, seed, `rls_negative_tests.sql` | **PROŠLO** – „Successfully applied 3 migrations … now at version v3”; seed COMMIT; 37 tvrdnji prošlo (uključujući „opozvano članstvo sakriva organizaciju” posle promene politike) |
| 17 | (28. 9.) Kompletan schema proposal sa V3 politikama | `psql -U app_owner -d vrtic_proposal -f docs/database/schema.sql` | **PROŠLO** – 72 tabele, 162 politike |
| 19 | (28. 9.) E02-B08: `./gradlew build --rerun-tasks` sa `VRTIC_TEST_DB=1` nad nativnim PG 17.11 | build + 40 testova | **PROŠLO** – 40 testova, 0 preskočenih, 0 palih; novi **SessionManagementIntegrationTest 4** (lista sesija: redosled, `isCurrent`, paginacija sa cursor-om, 422 za `limit=0` i neispravan cursor, 401 bez tokena; opoziv jedne sesije gasi samo njene tokene, tuđa/nepoznata/neispravna → 404, ponovni opoziv 204; logout-all sa sesijom starijom od 5 min → 403 bez opoziva, sa svežom → 204 i svi tokeni 401; logout-all gasi i refresh tokene, u cookie režimu bez CSRF → 403 bez opoziva) |
| 20 | (28. 9.) API smoke sa fat jar-om + curl | `java -jar ... serve`; `GET /api/v1/auth/%%%`, `GET /auth/sessions?cursor=%%%`, `GET /auth/sessions`, `POST /auth/logout-all`, `DELETE /auth/sessions/{id}`, `GET /me/memberships` bez tokena | **PROŠLO** – neispravan escape u putanji i upitu → `400` problem+json (pre ispravke bio `500`; Ktor `BadRequestException`/`URLDecodeException` sada mapirani), sve zaštićene rute bez tokena → `401`, `/health/ready` 200, 0 neobrađenih grešaka u logu; Ktor test klijent ne može da pošalje `%%%`, zato je ova provera samo curl |
| 21 | (28. 9.) E02-B04/B05/B09: `./gradlew build --rerun-tasks` sa `VRTIC_TEST_DB=1` nad nativnim PG 17.11 posle migracije V4 | build + 45 testova | **PROŠLO** – 45 testova, 0 preskočenih, 0 palih; novi **AccountFlowsIntegrationTest 5**: pregled pozivnice (200 sa org/ulogom, `requiresRegistration` po postojanju naloga; istekla/nepoznata/neispravna → 404), registracija (slaba lozinka 422, istekla pozivnica 404, uspeh 201 sa tokenima, `/organizations/{A}/ping` kao TEACHER, ponovna upotreba 404, druga pozivnica za registrovan e-mail 409, login radi), prihvatanje postojećim nalogom (tuđi e-mail 404, uspeh 201 ADMIN, `ping` sa najvišom ulogom, ponovo 404, bez tokena 401), verifikacija (login neverifikovanog → `EMAIL_VERIFICATION_REQUIRED` bez tokena; resend za nepostojeći e-mail 202 bez mejla; token jednokratan; posle verifikacije login uspeva), reset (nepostojeći e-mail 202 bez mejla; lozinka = e-mail 422 bez opoziva; uspeh 204 opoziva postojeću sesiju i stari refresh; token jednokratan; stara lozinka 401, nova 200); grep test loga: 0 sirovih tokena i linkova |
| 22 | (28. 9.) E02-B13: `./gradlew build --rerun-tasks` sa `VRTIC_TEST_DB=1` nad nativnim PG 17.11 | build + 51 test | **PROŠLO** – 51 test, 0 preskočenih, 0 palih; novi **RateLimiterTest 3** (prozor, Retry-After, udvostručavanje zaključavanja sa gornjom granicom 24 h) i **BruteForceIntegrationTest 3**: 10 pogrešnih lozinki → 401, 11. pokušaj (i sa tačnom lozinkom) → 429 sa `Retry-After`, `failed_login_count = 10` i `locked_until` postavljen; posle isteka zaključavanja i prozora uspešna prijava → 200 i brojač 0; nepostojeći nalog daje isti niz 401 → 429; `forgot-password` 4. put za isti e-mail → 429, 21. zahtev sa iste IP adrese → 429 i deli bucket sa `login`; grep test loga: 0 tokena |
| 23 | (28. 9.) E02-B15: `./gradlew build --rerun-tasks` sa `VRTIC_TEST_DB=1` nad nativnim PG 17.11 | build + 56 testova | **PROŠLO** – 56 testova, 0 preskočenih, 0 palih; novi **AuthorizeTest 2** (uloga + grantable extras, non-grantable se ne može prošvercovati, 403 bez detalja, platform admin bez MFA 403 / tenant korisnik 404) i **AuthorizationIntegrationTest 3**: settings čitaju OWNER/ADMIN/PARENT, nečlan 404, ADMIN PUT 403 bez naziva permisije, OWNER PUT 422 za `dayClosesAt` pre otvaranja pa 200 vidljivo svima, org B netaknut; platformska lista: tenant korisnik 404, admin bez MFA 403 `MFA_REQUIRED`, sa MFA 200 sa cursor paginacijom i 422 za nedozvoljen sort, platform admin bez članstva na tenant resurs 404; suspenzija članstva odmah daje 404 dok sesija ostaje validna; grep test loga: 0 tokena |
| 24 | (28. 9.) E02-B16: `./gradlew build --rerun-tasks` sa `VRTIC_TEST_DB=1` nad nativnim PG 17.11 | build + 59 testova | **PROŠLO** – 59 testova, 0 preskočenih, 0 palih; novi **MeIntegrationTest 3**: profil (e-mail, ime, `isPlatformAdmin` false/true, `mfaEnabled` false, bez hash materijala, 401 bez tokena), jezik (422 za `de`, 200 za `sr-Cyrl` i odmah vidljivo), promena lozinke (pogrešna trenutna 403 `CURRENT_PASSWORD_INVALID`, slaba 422, ista 422 `SAME_AS_CURRENT`, ništa opozvano na grešci; uspeh 204: tekuća sesija radi, druga sesija 401, stara lozinka 401, nova 200, `logout-all` odmah dozvoljen jer je lozinka dokazana); grep test loga: 0 tokena |
| 25 | (28. 9.) E02-B10 + D03: `./gradlew build --rerun-tasks` sa `VRTIC_TEST_DB=1` nad nativnim PG 17.11 | build + 60 testova; `dev-set-password` smoke | **PROŠLO** – 60 testova, 0 preskočenih, 0 palih; novi **ReauthIntegrationTest 1**: sesija stara 30 min → `logout-all` 403 `REAUTHENTICATION_REQUIRED`; 4 pogrešne lozinke → 403 `INVALID_CREDENTIALS` bez pečata; tačna → 200 sa `reauthenticatedAt`/`validUntil` i `sessions.reauthenticated_at` upisan; 6. pokušaj u prozoru → 429; `logout-all` zatim 204; posle opoziva reauth 401. `dev-set-password vlasnik@happykids.example.test` sa `APP_ENV=dev` → hash upisan; sa `APP_ENV=production` → odbijeno (exit 3). Grep test loga: 0 tokena; OpenAPI lint 0 grešaka |
| 18 | (28. 9.) OpenAPI lint posle promene statusa `listMyMemberships`, `logoutAll`, `listSessions`, `revokeSession`, `register`, `previewInvitation`, `acceptInvitation`, `verifyEmail`, `resendVerification`, `forgotPassword`, `resetPassword` | `npx @redocly/cli@2 lint docs/openapi.yaml` | **PROŠLO** – 0 grešaka, 0 upozorenja |
| 14 | Auth API end-to-end | lokalni HTTP smoke: web login → session → CSRF-negative → refresh → logout; Android login → bearer logout | **PROŠLO** – web login `200` + `vc_csrf`; nevažeći CSRF/Origin `403` bez promene sesije; refresh `200`; logout `204`; posle logout access `401`; mobile logout `204` i access `401` |

Izvršeni backend testovi pokrivaju obavezne scenarije iz brief-a §27: dva tenant-a, korisnik sa članstvima u više tenant-a (SQL test: PARENT u A + TEACHER u B, tenant switcher, opoziv članstva sakriva organizaciju, korisnik ne može sam da menja svoje članstvo), pool leakage posle COMMIT/ROLLBACK, append-only audit, runtime bez DDL/RLS-disable/DELETE privilegija.

## 4. Šta nije moglo da se proveri i zašto

| Provera | Razlog |
|---|---|
| `docker compose up` (db, migrate, api u kontejnerima), Dockerfile build | Docker Desktop 4.83.0 na ovoj mašini pada pri startu: `starting services: initializing Inference manager: listening on unix://…/Docker/run/dockerInference: remove …: The file cannot be accessed by the system` – ne može da kreira AF_UNIX socket fajlove pod `%LOCALAPPDATA%\Docker\run` (i posle preimenovanja starog `run` foldera greška se ponavlja za sveže kreiran fajl). Potrebna je intervencija na mašini (reinstalacija/reset Docker Desktop-a ili provera antivirusa). Umesto toga baza je verifikovana na nativnom PostgreSQL 17.11 – isti SQL, iste role. |
| Android build (`:androidApp:assembleDebug`, `:shared-ui`) | Nema Android SDK-a; moduli se preskaču uz upozorenje. CI job `android` ih gradi. |
| iOS build | Windows host; CI job `ios` (macOS runner) je definisan kao gate, ali nije izvršen. |
| GitHub Actions workflow | Repozitorijum nema remote; workflow nije pokrenut. Koraci su isti kao lokalno izvršene komande. |
| Argon2 produkcioni parametri | `benchmark-argon2` postoji, ali se meri na ciljnom serveru, ne na razvojnoj mašini. |
| nginx konfiguracija | Nije testirana na serveru. |

## 5. Poznati problemi i otvorene odluke

1. Docker Desktop je sada dostupan za lokalni stack; nativni PostgreSQL ili Docker mogu se koristiti prema lokalnom okruženju.
2. `hasCriticalHealthAlert` (boolean) se vraća u listi dece osoblju kao indikator „pročitaj zdravstveni profil”. Brief traži da zdravstveni podaci ne budu u listi; boolean ne nosi medicinski sadržaj, ali je odluka vlasnika proizvoda da li ostaje (lako se uklanja iz `ChildSummary`).
3. Web ljuska poziva `GET /api/v1/auth/session` za bootstrap; sa lokalnim dev seed nalogom može da se testira stvarni login, CSRF refresh i logout tok.
4. `rls_negative_tests.sql` zavisi od seed podataka (fiksni UUID-jevi); CI ih učitava pre testa.
5. `users` RLS politika sa podupitom nad `organization_memberships` – performanse izmeriti u EPIC 03 na realnom broju članova.
6. `retention_policies` rokovi i pravni osnovi su predlozi – potvrditi sa pravnikom.
7. Portable alati (JDK, Gradle, PostgreSQL) žive u privremenom folderu sesije, ne u repozitorijumu; Windows ih je između sesija delimično obrisao (28. 9. su ponovo preuzeti). Developer treba da instalira JDK 21 i PostgreSQL 17/Docker.
8. (28. 9.) Pretpostavka E02-B14: kada korisnik ima više uloga u istom vrtiću (npr. TEACHER i PARENT), tenant zahtev se skopira najprivilegovanijim članstvom (OWNER > ADMIN > TEACHER > PARENT); resource politike po ulozi u E04/E05 moraju to uzeti u obzir. `/me/memberships` podrazumevano vraća samo ACTIVE, a `status=INVITED|SUSPENDED` po izboru; REVOKED se nikad ne vraća.
9. (28. 9.) `logout-all` traži prijavu ili reautentifikaciju u poslednjih 5 minuta; pošto `POST /auth/reauthenticate` (E02-B10) još ne postoji, web korisnik sa starijom sesijom dobija 403 dok se B10 ne uradi. Namerno, bez zaobilaznice.
10. (28. 9.) Odluke u E02-B04/B05: registracija preko pozivnice odmah označava e-mail kao verifikovan (token pozivnice je stigao na to sanduče), pa ne šalje verifikacioni mejl; `resend-verification` je promenjen u neautentifikovan `{email}` jer neverifikovan nalog ne može da dobije sesiju (OpenAPI i API.md ažurirani). PARENT pozivnica kreira samo članstvo; PENDING guardian veza čeka EPIC 05 (`invitations.child_id` ostaje zapisan).
11. (28. 9.) `MailSender` u staging/produkciji je `UnconfiguredMailSender` (baca grešku pri slanju) dok EPIC 18 ne doda SES adapter; u DEV se link loguje u logger `com.vrticconnect.mail.DEV`. Testovi koriste `RecordingMailSender`, pa test log ne sadrži tokene (proveren grep-om).
12. (28. 9.) `PUT /organizations/{id}/settings` je u ugovoru bio OWNER+ADMIN; implementirano kao OWNER (`ORG_SETTINGS_MANAGE`) po matrici dozvola iz SECURITY.md. Ako administracija treba da menja rokove i radno vreme, odluka je da se permisija doda ADMIN-u u matrici, ne da se zaobiđe.
14. (28. 9.) Rok skorašnje autentifikacije usklađen na 5 minuta svuda (roadmap kriterijum 11); ugovor i SECURITY.md su ranije pominjali 10.
13. (28. 9.) Platformske rute traže `mfa_verified_at` na sesiji; dok MFA (E02-B11) ne postoji, SUPER_ADMIN dobija 403 `MFA_REQUIRED`. Namerno, bez prečice; test postavlja flag direktno u bazi.

## 6. Prvi sledeći implementacioni task

**EPIC 02 – Autentifikacija, sesije i dozvole** (`docs/DEVELOPMENT_ROADMAP.md`, taskovi `E02-*`) je u toku: login sa Argon2id, opaque access/refresh tokeni sa rotacijom i detekcijom ponovne upotrebe, web CSRF/Origin, logout tekuće sesije; E02-B14 (`MembershipResolver`, tenant transakcija, `/me/memberships`, `TestTenants` fixture = E02-D02) je urađen 28. 9.; E02-B08 (logout-all, lista i opoziv sesija) je urađen 28. 9.; E02-B04/B05/B09/B20, E02-B13, E02-B15, E02-B16, E02-B10 i E02-D03 su urađeni 28. 9.; preostaju po dogovorenom redosledu: E02-B11–B12 (TOTP MFA i MFA politika), E02-B17–B19 (čišćenje, audit/outbox, dokumentacija), E02-D03 (seed lozinke iz `SEED_DEV_PASSWORD`).

## 7. Dopuna 28. 9. 2026. (uveče): poslovni moduli za demo svih uloga

Na zahtev vlasnika proizvoda urađen je prvi klikabilan krug poslovnih modula (delovi EPIC 03-13 i 16), da bi se aplikacija testirala kao vlasnik, administrator, vaspitač i roditelj. EPIC 02 preostali taskovi (MFA E02-B11/B12, B17-B19) nisu rađeni.

**Implementirano (kod):**
- Flyway `V5__core_business.sql`: 17 tabela doslovno iz `docs/database/schema.sql` (children, enrollments, guardians, pickup_persons, schedule_templates/_days, absences, attendance_days/_visits/_events, announcements/_audiences/_recipients, consent_policies samo kao FK cilj, calendar_events, menu_days/_items) + RLS ENABLE/FORCE, `tenant_isolation`, `owner_maintenance`, grantovi.
- Zajednički backend: `TenantApi` (tenantRead/tenantWrite sa CSRF, tenant transakcija, audit), `authz/Scopes.kt` (vaspitač: dodeljene grupe; roditelj: potvrđene veze; 404 van opsega), `db/Jdbc.kt`, `http/Validation.kt`, globalno mapiranje SQL grešaka ograničenja (unique/exclusion 409, FK/check 422).
- Moduli: objekti, grupe i dodele vaspitača, zaposleni, članstva (opoziv sa reautentifikacijom), pozivnice (kreiranje, link u DEV logu, opoziv; prihvatanje kreira profil zaposlenog ili PENDING vezu roditelja), deca, upisi, veze roditelja, osobe za preuzimanje, nedeljni raspored, odsustva, prisustvo (event log + projekcija, idempotentno po `commandId`, korekcije), obaveštenja (nacrt, objava sa snimkom primalaca, čitanje, arhiva), kalendar, jelovnik, kontrolna tabla (`GET /dashboard`).
- Web: svi navedeni ekrani sa izmenom podataka, prikaz po ulozi (meni sakriva ono što uloga ne može), javne stranice `/invite`, `/verify-email`, `/forgot-password`, `/reset-password`.
- Demo podaci u `dev_seed.sql` (odeljak V5) i lozinke za sve demo naloge (`dev-set-password` prima više adresa); `start-vrtic.ps1` učitava seed pri svakom startu i lozinke samo prvi put. Uputstvo: `docs/MANUAL_TEST_DEMO.md`.

**Izvršene provere (28. 9., ova mašina, Temurin 21 + nativni PostgreSQL 17.11 na portu 5433):**

| Provera | Rezultat |
|---|---|
| `VRTIC_TEST_DB=1 ./gradlew build --rerun-tasks` | PROŠLO: 85 testova, 0 palih, 0 preskočenih (novi integracioni testovi: Locations, Groups, Staff, Children, Absences, Schedules, Attendance, Announcement, Calendar, Menu, Dashboard) |
| `java -jar ... migrate` | PROŠLO: "Successfully applied 1 migration ... now at version v5" |
| Seed `dev_seed.sql` kao app_owner | PROŠLO: COMMIT (16 dece, 22 veze roditelja, rasporedi, odsustvo, obaveštenja, događaji, jelovnik) |
| Web `tsc --noEmit`, `eslint src`, `vitest run` | PROŠLO: 10 fajlova, 36 testova |
| Ručni klik-test u pregledaču (fat jar + vite dev) | PROŠLO za: prijavu vlasnika, roditelja i vaspitača; svi ekrani iz menija se iscrtavaju bez greške u konzoli; vlasnik zabeležio dolazak (brojači se ažurirali); roditelj prijavio odsustvo; vaspitač vidi samo svoju grupu, roditelj samo svoju decu; API log bez ERROR linija |
| `rls_negative_tests.sql` | PALO na tvrdnji "tenant A: sessions visible": test pretpostavlja da seed admin nema sesiju, a u dev bazi postoji njegova sesija od ručne prijave (politika `sessions_self`). Nije posledica V5; test treba da ne zavisi od postojećih sesija. |

**Nije izvršeno / poznata odstupanja:**
- `docs/openapi.yaml` nije ažuriran za dodatke: `POST /employees`, `GET /parents`, `POST /children/{id}/guardians`, `GET /children/{id}/enrollments`, `GET /children/{id}/pickup-persons`, `GET /schedules/expected`, PUT alias rute, proširenje tela pozivnice (PARENT + `childId`). OpenAPI lint zato nije ponovo pokretan.
- Idempotency-Key se prima ali ne čuva; liste vraćaju do `limit` stavki sa `nextCursor: null`; `sort` se u delu lista ignoriše; null polja se izostavljaju iz JSON-a.
- Nisu urađeni: `/attendance/sync` (offline mobilni paket), brisanje jelovnika, prilozi obaveštenja, zdravstveni profil, izmene rasporeda po danu, neradni dani, `daily_plans`, fotografije, poruke, saglasnosti, izveštaji, naplata, MFA. Vaspitač ne može da kreira obaveštenja ni događaje (matrica dozvola to ne daje, iako ugovor pominje).
- Potvrda i opoziv veze roditelja ne traže reautentifikaciju (ugovor ima `x-requires-reauthentication`), jer web još nema taj dijalog na tom mestu.
- Kontrolna tabla broji "kasni" samo za decu koja nisu stigla; ekran prisustva u "kasni" broji i decu koja su stigla sa zakašnjenjem (definicija iz ugovora). Brojke se zato mogu razlikovati.
- Predlozi za šemu (nisu rađeni): DELETE na `attendance_visits` za čisto poništavanje dolaska; `deleted_at` ili DELETE na `menu_days`; trigger koji proverava da je veza roditelja vezana za PARENT članstvo (sada samo u aplikaciji).
- Android/iOS nisu menjani ni građeni.

## 8. Dopuna 28. 9. 2026. (kasno uveče): drugi krug

**Implementirano (kod):**
- Flyway `V6__schedule_exceptions_messaging_notifications.sql` (doslovno iz schema.sql): schedule_day_overrides, closure_days, schedule_change_log (append-only), notifications (RLS samo primalac), device_push_tokens, notification_deliveries (radnik), conversations, conversation_participants, messages.
- E02-B11/B12: TOTP MFA (setup/confirm/verify, 10 kodova za oporavak, AES-256-GCM tajna sa `APP_DATA_KEY`; u DEV se koristi dokumentovani dev ključ, van DEV bez ključa aplikacija ne startuje), politika: OWNER i platformski admin moraju da podese MFA; sesija bez MFA radi samo MFA i osnovne /me rute.
- Moj nalog (profil, lozinka, MFA, uređaji), platformska administracija (novi vrtić sa pozivnicom vlasniku, suspenzija, pretplata, funkcije), naplata za vlasnika (samo pregled).
- Poruke roditelj-vaspitači / roditelj-uprava (učesnici se računaju iz potvrđenih veza i dodela), in-app notifikacije sa zvoncem (obaveštenja, odsustva, potvrđena veza, nova poruka).
- Neradni dani, izmena rasporeda za jedan dan sa oznakom kasne izmene, dnevnik izmena, prioritet neradni dan > odsustvo > izmena > šablon u rasporedu, prisustvu i kontrolnoj tabli; izveštaj o prisustvu sa CSV izvozom (UTF-8 BOM, `;`), dnevnik aktivnosti (audit log).
- `docs/openapi.yaml` usklađen sa kodom (179 operacija, 137 `implemented`), `docs/API.md` 1.15 "Stanje implementacije"; `rls_negative_tests.sql` proširen na V5/V6 tabele i više ne zavisi od postojećih sesija.

**Izvršene provere (28. 9., ova mašina):**

| Provera | Rezultat |
|---|---|
| `java -jar ... migrate` | PROŠLO: V6 primenjena ("now at version v6") |
| `VRTIC_TEST_DB=1 ./gradlew build --rerun-tasks` | PROŠLO: 108 testova, 0 palih, 0 preskočenih (novi: Mfa, MfaCrypto sa RFC 6238 vektorima, PlatformAdmin, Messaging, Notifications, ScheduleExceptions, Reports) |
| `rls_negative_tests.sql` kao app_runtime | PROŠLO: 49 tvrdnji |
| Web `tsc`, `eslint src`, `vitest run` | PROŠLO: 15 fajlova, 62 testa |
| `npx @redocly/cli@2 lint docs/openapi.yaml` | PROŠLO: 0 grešaka, 0 upozorenja |
| Ručni klik-test u pregledaču | PROŠLO: vlasnik se posle lozinke upućuje na MFA; na nalogu vlasnika Sunčice: QR prikazan, kod izračunat po RFC 6238 iz prikazanog ključa prihvaćen, 10 kodova za oporavak, zatim svi ekrani iz menija bez greške u konzoli; roditelj poslao poruku vaspitačima (učesnici: oba roditelja i vaspitačica), vaspitačica vidi 1 nepročitanu notifikaciju u zvoncu i otvara razgovor; API log bez ERROR linija. MFA test-nalog je posle toga vraćen u stanje bez MFA. |

**Nije izvršeno / otvoreno:**
- QR kod nije skeniran pravim telefonom (kod je izračunat iz ključa sa ekrana).
- Stvarno slanje e-pošte i push notifikacija (nema provajdera), fotografije i saglasnosti, zdravstveni profil deteta, plaćanje, mobilna aplikacija.
- Mogući problemi pronađeni pri usklađivanju ugovora (nisu menjani): `GET /auth/session` vraća ulogu `KINDERGARTEN_OWNER` (web je normalizuje); pregled pozivnice vraća pun e-mail i `childGivenName` null; RELATIONSHIP_TAKEN je 422 pri povezivanju a 409 pri potvrdi; potvrda i opoziv veze roditelja ne traže reautentifikaciju; If-Match nije obavezan na izmeni dana i otkazivanju odsustva; Idempotency-Key se ne čuva; `applyNow` na pretplati se ignoriše; platformsko kreiranje vrtića vraća grešku ako slanje e-pošte padne posle upisa.
- Korisnik sa dve uloge u istom vrtiću (npr. vaspitač i roditelj) radi sa višom ulogom, pa ne vidi razgovore svoje roditeljske uloge.
- U deljenoj dev bazi ostala su dva test-vrtića (`fixture-a-0e20f737`, `fixture-b-0e20f737`) i nekoliko `extra-*`/`fixture-*` korisnika iz jednog prekinutog testa; vidljivi su samo platformskom adminu.
