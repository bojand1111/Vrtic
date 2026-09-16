# Izveštaj o validaciji – foundation skeleton (EPIC 01)

Datum: 16. 9. 2026. Okruženje: Windows 11 Pro, bez sistemskog JDK-a, bez Android SDK-a, bez Xcode-a; Node 22.23.1; Docker Desktop 4.83.0 instaliran ali **ne radi** (vidi §5). Za provere je korišćen prenosivi Temurin JDK 21.0.12.1, Gradle 9.3.0 i **nativni PostgreSQL 17.11** (EDB binarni paket) u privremenom folderu van repozitorijuma.

Ništa od prethodno prijavljenih provera iz ranijeg planiranja nije preuzeto – sve ispod je izvršeno u ovom okruženju.

## 1. Šta je projektovano

- Specifikacija proizvoda, arhitektura, bezbednost, infrastruktura, mobile/web arhitektura, roadmap sa 18 EPIC-a i 254 taska, 8 ADR-ova (`docs/`).
- Kompletan model podataka: 72 tabele, sve tenant tabele sa RLS ENABLE + FORCE, 160 politika (`docs/database/schema.sql`), ER dijagrami.
- REST ugovor: OpenAPI 3.1 sa 167 operacija (P0 116, P1 51), `docs/API.md` sa konvencijama i primerima.
- Auth (opaque sesije, rotacija, MFA), attendance event model, offline algoritam, consent/photo model, billing/feature flag model.

## 2. Šta je stvarno implementirano

| Deo | Implementirano |
|---|---|
| Backend | Ktor bootstrap; `serve` / `migrate` / `benchmark-argon2` komande; env konfiguracija bez default tajni van DEV; Hikari pool-ovi (runtime/owner); `Database.transaction(DbContext)` sa `set_config(..., true)`; Flyway V1 (29 tabela + RLS + grants + feature flagovi); `/health/live`, `/health/ready` (baza + migracije); problem+json, request id, sanitizovano logovanje, bezbednosna zaglavlja; auth rute zatvorene (`501`), `GET /auth/session` i tenant/platform rute → `401`; Argon2id hasher, generator/hash tokena; `PermissionMatrix` |
| Baza | `scripts/db/00_roles.sql` (app_owner/app_runtime/app_worker, bez BYPASSRLS), `V1__foundation.sql`, seed sa guard-om protiv ne-dev baze, `rls_negative_tests.sql` (37 tvrdnji) |
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
| 13 | Docker Compose | `docker compose up -d db` | **NIJE IZVRŠENO** – Docker daemon nedostupan (§5) |

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

1. **Docker Desktop neispravan na razvojnoj mašini** (gore). Do popravke koristiti nativni PostgreSQL 17 sa `scripts/db/00_roles.sql`.
2. `hasCriticalHealthAlert` (boolean) se vraća u listi dece osoblju kao indikator „pročitaj zdravstveni profil”. Brief traži da zdravstveni podaci ne budu u listi; boolean ne nosi medicinski sadržaj, ali je odluka vlasnika proizvoda da li ostaje (lako se uklanja iz `ChildSummary`).
3. Web ljuska poziva `GET /api/v1/auth/session` za bootstrap; do EPIC 02 uvek dobija 401 pa prikazuje samo login (ispravno, bez prečica).
4. `rls_negative_tests.sql` zavisi od seed podataka (fiksni UUID-jevi); CI ih učitava pre testa.
5. `users` RLS politika sa podupitom nad `organization_memberships` – performanse izmeriti u EPIC 03 na realnom broju članova.
6. `retention_policies` rokovi i pravni osnovi su predlozi – potvrditi sa pravnikom.
7. Portable alati (JDK, Gradle, PostgreSQL) žive u privremenom folderu sesije, ne u repozitorijumu; developer treba da instalira JDK 21 i PostgreSQL 17/Docker.

## 6. Prvi sledeći implementacioni task

**EPIC 02 – Autentifikacija, sesije i dozvole** (`docs/DEVELOPMENT_ROADMAP.md`, taskovi `E02-*`): registracija preko pozivnice, verifikacija e-maila, login sa Argon2id, opaque access/refresh tokeni sa rotacijom i detekcijom ponovne upotrebe, logout/sesije/opoziv, reset lozinke, reauth, MFA (TOTP), cookie režim + CSRF za web, bearer za mobile, rate limiting, audit, `SessionResolver` + `MembershipResolver` u pipeline-u i obavezni testovi (refresh replay, opoziv, multi-tenant korisnik, pool leakage). Prvi konkretan task: `E02-B01` – `SessionResolver` nad `access_tokens` sa `auth_mode` transakcijom i testom koji dokazuje da istekao/opozvan token daje 401.
