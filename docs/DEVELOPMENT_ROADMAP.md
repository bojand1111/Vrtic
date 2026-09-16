# Vrtić Connect – Razvojni roadmap (DEVELOPMENT_ROADMAP)

> Status: **plan rada**. Ovaj dokument opisuje šta se planira da se izgradi, kojim redosledom i kako se proverava.
> Ništa ovde ne tvrdi da je već izgrađeno ili verifikovano. Stvarno stanje skeleton-a i izvršenih provera vodi se
> isključivo u `docs/VALIDATION_REPORT.md`. Zahtevi: `docs/requirements/REQUIREMENTS_BRIEF.md`; proizvod: `docs/PRODUCT_SPEC.md`;
> model podataka: `docs/database/schema.sql`; API: `docs/API.md`; bezbednost: `docs/SECURITY.md`.
>
> Konvencije identifikatora taskova: `E<epic>-B<nn>` backend, `E<epic>-M<nn>` mobile (KMP/Compose), `E<epic>-W<nn>` web admin
> (React/Vite), `E<epic>-D<nn>` baza (Flyway migracije, RLS, seed). Backend paketi: `com.vrticconnect.<module>`.
> Sve API putanje su relativne u odnosu na `/api/v1`; tenant putanje su pod `/organizations/{organizationId}/...`.

---

## Sadržaj

1. [Pregled faza i redosled](#1-pregled-faza-i-redosled)
2. [Zajednička pravila za sve EPIC-e](#2-zajednička-pravila-za-sve-epic-e)
3. [EPIC 01 – Project Foundation](#epic-01--project-foundation)
4. [EPIC 02 – Authentication, Sessions & Permissions](#epic-02--authentication-sessions--permissions)
5. [EPIC 03 – Organizations](#epic-03--organizations)
6. [EPIC 04 – Employees](#epic-04--employees)
7. [EPIC 05 – Children & Parents](#epic-05--children--parents)
8. [EPIC 06 – Groups](#epic-06--groups)
9. [EPIC 07 – Schedule](#epic-07--schedule)
10. [EPIC 08 – Attendance](#epic-08--attendance)
11. [EPIC 09 – Absence](#epic-09--absence)
12. [EPIC 10 – Announcements](#epic-10--announcements)
13. [EPIC 11 – Notifications](#epic-11--notifications)
14. [EPIC 12 – Calendar](#epic-12--calendar)
15. [EPIC 13 – Meals](#epic-13--meals)
16. [EPIC 14 – Messaging](#epic-14--messaging)
17. [EPIC 15 – Photos & Consent](#epic-15--photos--consent)
18. [EPIC 16 – Admin Dashboard & Reports](#epic-16--admin-dashboard--reports)
19. [EPIC 17 – Audit & Security](#epic-17--audit--security)
20. [EPIC 18 – Production Deployment](#epic-18--production-deployment)
21. [Mapa obaveznih testnih scenarija](#mapa-obaveznih-testnih-scenarija)
22. [Prvi sledeći implementacioni task](#prvi-sledeći-implementacioni-task)

---

## 1. Pregled faza i redosled

### 1.1 Faze

| Faza | Cilj | EPIC-i |
|---|---|---|
| **P0 – Pilot** | vrtić svakodnevno koristi raspored, prisustvo, dnevni pregled, obaveštenja i notifikacije; podaci izolovani; offline prisustvo radi | 01, 02, 03, 04, 06, 05, 07, 09, 08, 10, 11, 16 (P0 deo), 17 (P0 deo), 18 (P0 deo) |
| **P1 – Proširenje** | kalendar, jelovnik, poruke, fotografije i saglasnosti, izveštaji | 12, 13, 14, 15, 16 (izveštaji), 10 (prilozi), 11 (SSE, podešavanja) |
| **P2 – Kasnije** | plaćanja, dokumenti, QR/NFC, upisni proces, marketplace, nannies, ankete, razvojni izveštaji, lekovi, AI/prevod | van 18 EPIC-a; modelovano u šemi (`documents*`), planira se posebno |

### 1.2 Redosled (brojevi EPIC-a nisu strogi redosled)

```
E01 Foundation
 └─ E02 Authentication ─┬─ E03 Organizations ─┬─ E04 Employees ─┐
                         │                     └─ E06 Groups ────┼─ E05 Children & Parents ─┬─ E07 Schedule ─┬─ E09 Absence
                         │                                       │                          │                └─ E08 Attendance (koristi daily_plans iz E07 i odsustva iz E09)
                         │                                       │                          └─ E10 Announcements ─ E11 Notifications
                         │                                       │
                         │                                       └─ (P1) E12 Calendar, E13 Meals, E14 Messaging, E15 Photos & Consent
                         └─ E16 Admin Dashboard (raste sa svakim EPIC-om)
E17 Audit & Security  ── proteže se kroz SVE faze (svaki EPIC ima svoj audit/RLS deo + završna konsolidacija)
E18 Production Deployment ── staging od E02, produkcija pre pilota (posle E11), doterivanje posle
```

Ključne zavisnosti koje odstupaju od numeracije:

- **Grupe (E06) pre upisa dece (E05)** – tabela `groups` je već u V1, ali upis (`enrollments`) zahteva postojeće grupe i endpointe za njih.
- **Odsustva (E09) pre potpune evidencije prisustva (E08)** – dnevni pregled i `daily_plans.source = 'ABSENCE'` zavise od odsustava; E08 se može započeti paralelno, ali se završava posle E09.
- **Notifikacije (E11) odmah posle obaveštenja (E10)** – outbox postoji od V1; radnik i push se uvode u E11 i retroaktivno „prikače” na događaje iz E07–E10.
- **Audit & Security (E17)** nije završna faza već stalni deo svakog EPIC-a (RLS politike, audit akcije, negativni testovi), sa završnom konsolidacijom.

### 1.3 Rezime faza po platformama

| Platforma | P0 | P1 |
|---|---|---|
| Backend (Ktor) | auth, tenant kontekst, organizacije, zaposleni, deca, grupe, raspored, odsustva, prisustvo, obaveštenja, notifikacije, feature flagovi, audit, privacy osnove | kalendar, jelovnik, poruke, fajlovi/fotografije/saglasnosti, izveštaji, SSE |
| Mobile (KMP/Compose) | prijava/MFA, izbor organizacije, roditelj (deca, raspored, odsustva, prisustvo, obaveštenja, inbox), vaspitač (dnevni pregled, prisustvo, offline red), nalog | poruke, kalendar, jelovnik, fotografije, saglasnosti |
| Web admin (React/Vite) | prijava/MFA, tenant switcher, Settings, Locations, Groups, Employees, Children, Parents, Schedules, Attendance, Announcements, Dashboard, Billing pregled, platformski ekrani | Calendar, Meals, Messages, Photos, Reports |
| Baza | V1 foundation + migracije za decu, raspored, prisustvo, odsustva, obaveštenja, notifikacije, privacy | migracije za kalendar, jelovnik, poruke, fajlove/fotografije/saglasnosti |

---

## 2. Zajednička pravila za sve EPIC-e

Ova pravila važe za svaki task i ne ponavljaju se u svakom EPIC-u.

| Oblast | Pravilo |
|---|---|
| Arhitektura | modularni monolit; svaki modul `com.vrticconnect.<module>` ima `api` (Ktor rute + DTO), `application` (servisi/komande), `domain` (model, pravila), `infrastructure` (SQL repozitorijumi, adapteri). Bez poslovne logike u rutama. Bez ORM-a: eksplicitni SQL preko JDBC + HikariCP. |
| Tenant kontekst | svaka tenant ruta prolazi kroz `TenantContextPlugin` (E02): sesija → aktivno članstvo za `{organizationId}` → otvaranje transakcije → `set_config('app.organization_id', ..., true)`, `set_config('app.user_id', ..., true)` → handler → COMMIT/ROLLBACK. Repozitorijumi nikada ne primaju `organizationId` „iz URL-a” već iz konteksta. |
| Autorizacija | deny-by-default; `Permission` enum + `PermissionMatrix` u `com.vrticconnect.auth.authz`; provera opsega (grupa/dete/period) u aplikacionom sloju; RLS je poslednja linija. |
| API | `problem+json` greške sa `type`, `title`, `status`, `detail`, `instance`, `code`; `401`/`403`/`404` bez otkrivanja postojanja resursa iz druge organizacije (uvek `404`); `409` za verzije i stanja; `422` za validaciju; `429` + `Retry-After`. `If-Match`/`ETag` na svim entitetima sa `version`. `Idempotency-Key` obavezan na komandama koje kreiraju resurse (`idempotency_keys`). Cursor paginacija (`cursor`, `limit ≤ 100`), allowlist filtera i sortiranja. OpenAPI u `docs/openapi/` se ažurira u istom PR-u. |
| Audit | svaki EPIC definiše svoje `audit_log.action` vrednosti i piše ih u istoj transakciji kroz `com.vrticconnect.audit.AuditWriter`. |
| Outbox | svaka promena koja treba da obavesti korisnike upisuje `outbox_events` u istoj transakciji (`aggregate_type`, `event_type`, payload samo sa ID-jevima). |
| Migracije | Flyway, `backend/src/main/resources/db/migration/V<n>__<naziv>.sql`; svaki EPIC uvodi **podskup** `docs/database/schema.sql` (identična definicija tabela, RLS politika i grantova za te tabele); expand/contract; migracije se ne menjaju posle merge-a. Broj `<n>` određuje redosled stvarnog merge-a, a ne broj EPIC-a. |
| Testovi | unit (domen), integration (Testcontainers PostgreSQL 17 sa `app_owner`/`app_runtime`/`app_worker` ulogama iz `scripts/db/00_roles.sql`), **RLS negativni** (dva tenant-a; upit bez konteksta vraća 0 redova; upis sa pogrešnim `organization_id` odbijen), API contract (OpenAPI), e2e (web Playwright, mobile shared testovi + instrumentirani po potrebi). |
| Lokalizacija | bez hardkodovanog teksta; ključevi u `shared/i18n` (mobile) i `src/i18n` (web) za `sr-Latn`, `sr-Cyrl`, `en`. |
| Logovi | strukturirani JSON, `request_id`, bez PII/tokena/health podataka. |
| Definition of Done (opšte) | kod + testovi prolaze u CI; OpenAPI ažuriran; migracija verifikovana na nativnom PostgreSQL-u u CI; RLS negativni test za nove tabele; audit akcije dokumentovane u `docs/SECURITY.md`; `docs/API.md` i `docs/DATABASE.md` usklađeni; bez tajni u repou; PR pregledan. |

---

## EPIC 01 – Project Foundation

**Cilj:** Lokalno pokretljiv skeleton monorepo-a koji fiksira arhitekturne odluke (Ktor modularni monolit, PostgreSQL sa
RLS i odvojenim kredencijalima, Flyway, KMP/Compose mobile, React/Vite web, Docker Compose, CI) bez ijedne poslovne
funkcije i bez lažne autentifikacije.

**Prioritet:** P0
**Dependencies:** nema (početni EPIC).

### Šta skeleton treba da pruži (planirani sadržaj)

| Deo | Sadržaj |
|---|---|
| `backend/` | Ktor bootstrap (`com.vrticconnect.Application`), konfiguracija iz env-a (`APP_ENV`, `DB_HOST`, `APP_DB_RUNTIME_PASSWORD`, `APP_DB_OWNER_PASSWORD`...), dva HikariCP pool-a (runtime/worker) i odvojen Flyway DataSource za `app_owner`, komande `serve` i `migrate`, `GET /health/live` i `GET /health/ready` (proverava konekciju runtime pool-a i da je Flyway `current` verzija primenjena), `problem+json` handler, `request_id` middleware, strukturirani logovi, auth interfejsi sa **bezbedno zatvorenim rutama** (`/api/v1/auth/login` i `/refresh` vraćaju `501 Not Implemented` sa `problem+json`, ne izdaju tokene). |
| Flyway V1 | `V1__foundation.sql` sa: ekstenzije i funkcije (`app.current_organization_id`, `app.current_user_id`, `app.auth_mode`, `app.platform_mode`, `app.touch_updated_at`, `app.deny_modification`), tabele `users`, `email_verification_tokens`, `password_reset_tokens`, `sessions`, `refresh_tokens`, `access_tokens`, `user_mfa_methods`, `user_mfa_recovery_codes`, `login_attempts`, `platform_admins`, `organizations`, `organization_settings`, `organization_memberships`, `membership_permissions`, `invitations` (bez `child_id` FK – dodaje se u E05), `support_access_grants`, `locations`, `groups`, `employees`, `group_teacher_assignments`, `audit_log`, `outbox_events`, `idempotency_keys`, `feature_flags`, `organization_feature_overrides`, `plans`, `subscriptions`, `subscription_events` + RLS politike (ENABLE + FORCE) i grantovi za te tabele. |
| Kredencijali baze | `scripts/db/00_roles.sql` + `00_roles.sh` (Docker init): `app_owner` (vlasnik, Flyway), `app_runtime` (API, NOBYPASSRLS), `app_worker` (radnik). Aplikacija nikada ne koristi `postgres` superuser-a. |
| `docker-compose.yml` | servis `db` (postgres:17), `migrate` (profil `app`, Flyway kao `app_owner`), `api` (profil `app`); `.env.example` bez vrednosti tajni. |
| CI | GitHub Actions: backend build + testovi + Flyway validacija na nativnom PostgreSQL 17 servisu, RLS negativni test skeletona (prazan kontekst → 0 redova), OpenAPI lint, web `tsc --noEmit` + `vite build`, mobile `shared` testovi + Android assemble, iOS build na macOS runner-u ili dokumentovan gate, dependency + secret scan. |
| `apps/admin-web/` | React + TypeScript (strict) + Vite shell: router, layout, i18n (sr-Latn, sr-Cyrl, en), API klijent sa `problem+json` parsiranjem i `credentials: 'include'`, ekran prijave koji poziva zatvoreni endpoint i prikazuje grešku; bez tokena u localStorage. |
| `apps/mobile/` | KMP + Compose Multiplatform shell: `shared` (networking Ktor Client, kotlinx.serialization, DI konstruktorom, navigacija, i18n resursi za tri jezika, `SecureStorage` expect/actual, `CrashReporter` adapter), `androidApp`, `iosApp`; ekran prijave koji poziva zatvoreni endpoint. |
| `docs/` | svi dokumenti iz brief-a §30, `docs/openapi/openapi.yaml`, `docs/database/schema.sql`, seed skripta (`docs/database/seed/`) koja **odbija** `APP_ENV=production` i ne sadrži kredencijale, `docs/adr/`. |

> **Stanje verifikacije:** koje od navedenih komandi/buildova su stvarno izvršeni i sa kojim rezultatom zapisano je u
> `docs/VALIDATION_REPORT.md`. Ovaj roadmap ne tvrdi da je bilo šta od navedenog verifikovano.

### Taskovi

**Backend**
- `E01-B01` Gradle projekat `backend/` (Kotlin JVM, Ktor server, kotlinx.serialization, HikariCP, Flyway, PostgreSQL JDBC, Logback JSON, Testcontainers) sa verzionim katalogom; verzije proverene na kompatibilnost (ne „najnovije po svaku cenu”).
- `E01-B02` `com.vrticconnect.core.config` – učitavanje konfiguracije iz env-a, validacija obaveznih vrednosti pri startu, fail-fast.
- `E01-B03` `com.vrticconnect.core.db` – `RuntimeDataSource` (`app_runtime`), `WorkerDataSource` (`app_worker`), `MigrationDataSource` (`app_owner`, samo za `migrate`); `Transactions.inTransaction { }` helper koji garantuje COMMIT/ROLLBACK i `DISCARD`/`RESET` nije potreban jer se koristi `set_config(..., true)`.
- `E01-B04` `com.vrticconnect.core.http` – `problem+json` `StatusPages`, `request_id` plugin, CORS samo za `APP_WEB_ORIGIN`, bezbednosna zaglavlja.
- `E01-B05` `com.vrticconnect.health` – `/health/live`, `/health/ready`.
- `E01-B06` `com.vrticconnect.auth` – interfejsi (`SessionResolver`, `TokenService`, `PasswordHasher`) i **zatvorene** rute `/api/v1/auth/*` (`501`).
- `E01-B07` CLI ulazne tačke `serve` / `migrate` + Dockerfile (multi-stage, non-root user).
- `E01-B08` Integracioni test skelet: Testcontainers PostgreSQL 17 sa izvršenim `00_roles.sql`, Flyway migracija kao `app_owner`, test koji kao `app_runtime` bez konteksta čita `organizations` i dobija 0 redova.

**Mobile**
- `E01-M01` KMP projekat `apps/mobile/` (Gradle odvojen od backend-a): moduli `shared`, `androidApp`, `iosApp`; Compose Multiplatform.
- `E01-M02` `shared/network` – Ktor Client sa JSON, `problem+json` parser, `ApiClient` sa injektovanim `TokenProvider` (bez implementacije tokena).
- `E01-M03` `shared/i18n` – resursi za `sr-Latn`, `sr-Cyrl`, `en`; `Strings` API bez hardkodovanog teksta.
- `E01-M04` `SecureStorage` expect/actual (Android Keystore/EncryptedSharedPreferences, iOS Keychain) – interfejs i implementacija bez upotrebe u auth-u još.
- `E01-M05` Ekran prijave (UI) koji poziva zatvoreni endpoint i prikazuje `problem+json` poruku; navigacija (typed routes).
- `E01-M06` `CrashReporter` adapter interfejs (no-op implementacija).

**Web**
- `E01-W01` Vite + React + TypeScript strict projekat `apps/admin-web/`, ESLint/Prettier, router, layout shell.
- `E01-W02` i18n (react-i18next ili ekvivalent) sa tri jezika i prebacivanjem pisma.
- `E01-W03` `src/api/client.ts` – fetch wrapper (`credentials: 'include'`, `problem+json`, `If-Match`/`ETag`, `Idempotency-Key` helper).
- `E01-W04` Ekran prijave koji poziva zatvoreni endpoint i prikazuje grešku; dev proxy `/api` → backend.

**Database**
- `E01-D01` `V1__foundation.sql` (sadržaj gore) kao tačan podskup `docs/database/schema.sql`.
- `E01-D02` `scripts/db/00_roles.sql` + `00_roles.sh` (Docker init) – uloge, baza, ekstenzije, `search_path`.
- `E01-D03` Seed skripta (`docs/database/seed/seed_dev.sql` ili Kotlin task) – organizacija „Happy Kids” (Novi Sad, objekat, grupe „Bubamare” i „Leptirići”), 3 vaspitača, 15 dece, ~12 roditelja, owner i admin, drugi tenant; sintetička imena, `example.test`; odbija `production`; **bez lozinki** (nalozi sa `password_hash = NULL` do E02). Napomena: deca i staratelji se dodaju u seed tek kada E05 uvede tabele – do tada seed sadrži organizacije, članstva, objekte, grupe i zaposlene.

### Acceptance criteria
1. `docker compose up -d db` podiže PostgreSQL sa tri aplikativne uloge; `./gradlew :backend:run` (ili `migrate` + `serve`) primenjuje V1 kao `app_owner` i servira `/health/ready = 200`.
2. `POST /api/v1/auth/login` vraća `501` `problem+json` i ne izdaje nikakav token.
3. Kao `app_runtime` bez postavljenog konteksta, `SELECT count(*) FROM app.organizations` vraća 0 iako seed postoji.
4. `app_runtime` ne može `CREATE TABLE`, `ALTER TABLE` ni `DELETE FROM app.organizations`.
5. Web `vite build` i `tsc --noEmit` prolaze; mobile `shared` testovi prolaze; Android assemble prolazi u CI; iOS build ili dokumentovan gate.
6. Seed se odbija ako je `APP_ENV=production`.

### Potrebni testovi
- Unit: konfiguracija (fail-fast), `problem+json` mapiranje.
- Integration: Flyway migracija na nativnom PG 17; health ready sa/bez baze.
- RLS negativni: prazan kontekst → 0 redova; `INSERT` u tenant tabelu bez konteksta → odbijen.
- E2E: web shell se učitava na tri jezika.

### Definition of Done
Sve iz §2 + `README.md` sa uputstvom za lokalno pokretanje + `docs/VALIDATION_REPORT.md` popunjen stvarnim rezultatima komandi (šta je izvršeno, šta nije i zašto).

---

## EPIC 02 – Authentication, Sessions & Permissions

**Cilj:** Kompletna autentifikacija i autorizacija: registracija kroz pozivnicu, verifikacija email-a, prijava sa Argon2id,
opaque access tokeni (10 min) + rotirajući refresh tokeni (30 d absolutno / 7 d idle) sa atomskim consume+rotate i
detekcijom ponovne upotrebe, sesije (lista/opoziv/logout-all), reset lozinke (15 min), reautentifikacija, MFA (TOTP) za
platformske admine/vlasnike/support, web cookie režim (HttpOnly/Secure/SameSite + CSRF sa tačnim Origin-om), mobile bearer
režim, rate limiting, generičke poruke, audit, **razrešavanje tenant konteksta** (sesija → članstvo → `set_config` u
transakciji) i sprovođenje matrice dozvola. Ovo je **sledeći implementacioni korak** i najdetaljniji EPIC.

**Prioritet:** P0
**Dependencies:** E01.

### 2.1 Modeli i pravila (sažetak; detalji u PRODUCT_SPEC §5.1 i SECURITY.md)

| Element | Vrednost |
|---|---|
| Access token | 32 bajta CSPRNG, base64url; u bazi `access_tokens.token_hash = sha256(token)`; `expires_at = issued_at + 10 min`; provera na svakom zahtevu (hash lookup + `revoked_at IS NULL` + sesija nije opozvana + `absolute_expires_at > now()`) |
| Refresh token | 32 bajta CSPRNG; `refresh_tokens.token_hash`; `idle_expires_at = issued_at + 7 d`; sesija `absolute_expires_at = created_at + 30 d`; tačno jedan `consumed_at IS NULL` po sesiji |
| Rotacija | jedna transakcija: `SELECT ... FOR UPDATE` na refresh tokenu po hash-u → ako `consumed_at IS NOT NULL` → **reuse** → `sessions.revoked_at = now(), revoke_reason = 'REUSE_DETECTED'`, `refresh_tokens.reuse_detected_at`, opoziv svih access tokena sesije, audit `AUTH_REFRESH_REUSE_DETECTED`, outbox `SECURITY` notifikacija; inače → `consumed_at = now()`, insert novog tokena, `replaced_by_id`, novi access token, `sessions.last_seen_at` |
| Lozinka | Argon2id (`password_hash` PHC string), parametri iz benchmark-a (cilj 250–500 ms; početno npr. m=64 MiB, t=3, p=1 – potvrditi benchmark-om u CI job-u), min 10 znakova, provera protiv liste čestih lozinki |
| Verifikacija email-a | `email_verification_tokens` 24 h; `new_email` za promenu adrese (P1) |
| Reset | `password_reset_tokens` 15 min, jednokratan, `requested_ip`; posle reseta opoziv svih sesija (`PASSWORD_CHANGED`) |
| MFA | TOTP (RFC 6238, 30 s, 6 cifara, ±1 korak), tajna envelope-šifrovana (`user_mfa_methods.secret_enc`, `key_id`), 10 recovery kodova (`user_mfa_recovery_codes.code_hash`); obavezan za `platform_admins`, `OWNER` članstva i support naloge; `sessions.mfa_verified_at` |
| Reautentifikacija | `sessions.reauthenticated_at` mora biti unutar 5 min za osetljive akcije (`RequiresRecentAuth` anotacija/plugin) |
| Web režim | kolačići `vc_at` (access) i `vc_rt` (refresh, `Path=/api/v1/auth/refresh`) sa `HttpOnly; Secure; SameSite=Lax`; CSRF: svaka mutacija zahteva `Origin` **tačno jednak** `APP_WEB_ORIGIN` (bez sufiksnog/prefiksnog poređenja) + custom zaglavlje `X-Requested-With: VrticConnect`; klijent nikad ne vidi token |
| Mobile režim | `Authorization: Bearer <access>`; refresh u telu zahteva; `X-Client-Kind: ANDROID|IOS`; tokeni samo u Keychain/Keystore; single-flight refresh |
| Rate limiting | `login_attempts` (email_hash + IP): > 10 neuspešnih / 15 min po email-u ili > 50 / 15 min po IP → `429` + `Retry-After`; `users.failed_login_count`, `locked_until` (eksponencijalno: 1, 5, 15, 60 min); reset/verify/invitation endpointi: 5 / h po IP+email |
| Generičke poruke | login: `401` `code = AUTH_INVALID_CREDENTIALS` za nepostojeći email, pogrešnu lozinku i zaključan nalog (zaključan dodatno šalje email korisniku); forgot-password uvek `202` |
| Audit akcije | `AUTH_LOGIN_SUCCEEDED`, `AUTH_LOGIN_FAILED`, `AUTH_LOGOUT`, `AUTH_LOGOUT_ALL`, `AUTH_SESSION_REVOKED`, `AUTH_REFRESH_ROTATED` (uzorkovano ili samo metrika – odluka u SECURITY.md), `AUTH_REFRESH_REUSE_DETECTED`, `AUTH_PASSWORD_RESET_REQUESTED`, `AUTH_PASSWORD_RESET_COMPLETED`, `AUTH_PASSWORD_CHANGED`, `AUTH_EMAIL_VERIFIED`, `AUTH_MFA_ENABLED`, `AUTH_MFA_DISABLED`, `AUTH_MFA_FAILED`, `AUTH_REAUTHENTICATED`, `INVITATION_ACCEPTED`, `MEMBERSHIP_CONTEXT_DENIED` |

### 2.2 Tenant kontekst i dozvole

Tok za svaku tenant rutu `/api/v1/organizations/{organizationId}/...`:

1. `AuthenticationPlugin`: izvuče token (kolačić ili Bearer) → hash → `access_tokens` JOIN `sessions` JOIN `users` uz `set_config('app.auth_mode','on',true)` u **posebnoj kratkoj transakciji** auth modula → `Principal(userId, sessionId, mfaVerified, reauthAt, clientKind)`; neuspeh → `401`.
2. `TenantContextPlugin`: učita aktivno članstvo `organization_memberships WHERE user_id = ? AND organization_id = ? AND status = 'ACTIVE'` (RLS politika `self_memberships` to dozvoljava) → ako ne postoji → `404` (ne `403`, da se ne otkrije postojanje organizacije); ako organizacija nije `ACTIVE` → `403` `ORGANIZATION_SUSPENDED` (samo čitanje dozvoljeno za `SUSPENDED` po odluci); ako je korisniku obavezan MFA a `mfa_verified_at IS NULL` → `403` `MFA_REQUIRED`.
3. Otvaranje transakcije handlera: `set_config('app.organization_id', orgId, true)`, `set_config('app.user_id', userId, true)`; `TenantContext(membershipId, role, permissions[], organizationId, userId)` u `call.attributes`.
4. Handler → `Authorize.require(Permission.X)` (matrica: role → dozvole; `membership_permissions` dodaju) + provera opsega (E04–E08 dodaju `GroupScope`, `ChildScope`).
5. COMMIT/ROLLBACK; `set_config(..., true)` nestaje sa transakcijom – konekcija se vraća u pool bez konteksta.

Platformske rute `/api/v1/platform/*`: `PlatformContextPlugin` proverava `platform_admins` (aktivan) + `mfa_verified_at` → `set_config('app.platform_mode','on',true)`; nikada `app.organization_id`. Support pristup organizaciji (`support_access_grants`) ulazi kroz tenant rutu sa zaglavljem `X-Support-Grant: {grantId}` – plugin proverava grant (nije istekao, nije opozvan, MFA) i postavlja tenant kontekst sa `role = SUPPORT` i ograničenim `scope` (E03 dovršava).

Matrica dozvola (`PermissionMatrix.kt`, izvor za `docs/SECURITY.md`) – početni skup:

| Permission | OWNER | ADMIN | TEACHER | PARENT |
|---|---|---|---|---|
| `ORG_SETTINGS_WRITE` | ✔ | ✔ | – | – |
| `MEMBER_INVITE_STAFF` | ✔ | ✔ (TEACHER samo; ADMIN uz `MEMBER_MANAGE`) | – | – |
| `MEMBER_INVITE_OWNER_ADMIN` | ✔ | – | – | – |
| `MEMBER_SUSPEND` | ✔ | ✔ (ne OWNER) | – | – |
| `LOCATION_WRITE`, `GROUP_WRITE` | ✔ | ✔ | – | – |
| `CHILD_READ` | ✔ | ✔ | ✔ (opseg: grupe) | ✔ (opseg: svoja deca) |
| `CHILD_WRITE` | ✔ | ✔ | – | – |
| `CHILD_HEALTH_READ/WRITE` | samo uz dodatnu dozvolu | samo uz dodatnu dozvolu | samo uz dodatnu dozvolu | ✔ za svoju decu uz `can_view_health` |
| `SCHEDULE_READ` | ✔ | ✔ | ✔ (grupe) | ✔ (deca) |
| `SCHEDULE_WRITE` | ✔ | ✔ | – | ✔ uz `can_manage_schedule` |
| `ABSENCE_WRITE` | ✔ | ✔ | – | ✔ uz `can_report_absence` |
| `ATTENDANCE_COMMAND` | ✔ | ✔ | ✔ (grupe, period) | – |
| `ATTENDANCE_CORRECT` | ✔ | ✔ | uz dodatnu dozvolu | – |
| `ANNOUNCEMENT_PUBLISH` | ✔ | ✔ | uz dodatnu dozvolu | – |
| `BILLING_READ` | ✔ | uz `BILLING_MANAGE` | – | – |
| `AUDIT_READ` | ✔ | ✔ | – | – |

### Taskovi

**Backend (`com.vrticconnect.auth`, `com.vrticconnect.membership`, `com.vrticconnect.core.security`)**
- `E02-B01` `PasswordHasher` (Argon2id preko `bouncycastle` ili `argon2-jvm`), benchmark test koji beleži trajanje; `PasswordPolicy` (dužina, lista čestih lozinki).
- `E02-B02` `TokenGenerator` (CSPRNG 32 B, base64url) + `TokenHasher` (sha256); nikada ne loguje sirovi token.
- `E02-B03` Repozitorijumi (eksplicitni SQL): `UserRepository`, `SessionRepository`, `RefreshTokenRepository`, `AccessTokenRepository`, `EmailVerificationTokenRepository`, `PasswordResetTokenRepository`, `MfaRepository`, `LoginAttemptRepository`, `PlatformAdminRepository`, `InvitationRepository`, `MembershipRepository`; svi rade unutar `authTransaction { set_config('app.auth_mode','on',true) }`.
- `E02-B04` `POST /auth/invitations/{token}/accept` – prihvatanje pozivnice: validacija hash-a, isteka, opoziva; kreiranje `users` (ako ne postoji za email) sa `password_hash`, `given_name`, `family_name`, `preferred_locale`; `organization_memberships` → `ACTIVE`, `accepted_at`; `invitations.accepted_at/accepted_user_id`; verifikacioni email ako je email nov; audit. Ako korisnik već postoji, zahteva prijavu pa prihvatanje (`POST /me/invitations/{token}/accept`).
- `E02-B05` `POST /auth/verify-email` (`{token}`) i `POST /auth/resend-verification` (`{email}`, uvek `202`).
- `E02-B06` `POST /auth/login` – rate limit → lookup → Argon2 verify (uvek se izvrši i za nepostojeći nalog, „dummy hash”, da vreme odgovora ne otkriva nalog) → provere statusa/verifikacije → kreiranje `sessions` (client_kind, device_name, user_agent, ip) + access + refresh → odgovor po režimu (Set-Cookie ili JSON `{accessToken, refreshToken, expiresIn, mfaRequired}`) → `login_attempts` + audit.
- `E02-B07` `POST /auth/refresh` – atomic consume+rotate (2.1) + reuse detection; web: iz kolačića; mobile: iz tela.
- `E02-B08` `POST /auth/logout` (tekuća sesija), `POST /auth/logout-all` (sve sesije; zahteva reauth), `GET /auth/sessions` (lista sopstvenih sesija: client_kind, device_name, created_at, last_seen_at, ip_created, `current: true`), `DELETE /auth/sessions/{sessionId}` (opoziv sopstvene sesije). Opoziv = `sessions.revoked_at` + `access_tokens.revoked_at` za sve tokene sesije.
- `E02-B09` `POST /auth/forgot-password` (`202` uvek, token 15 min, email preko `MailSender` adaptera – SES u produkciji, log/fake u dev), `POST /auth/reset-password` (`{token, newPassword}` → hash, `password_updated_at`, opoziv svih sesija `PASSWORD_CHANGED`, audit).
- `E02-B10` `POST /auth/reauthenticate` (`{password}` ili `{totpCode}`) → `sessions.reauthenticated_at`; plugin `RequiresRecentAuth(maxAge = 5 min)` koji vraća `403` `REAUTHENTICATION_REQUIRED`.
- `E02-B11` MFA: `POST /me/mfa/totp/setup` (generiše tajnu, envelope-šifruje kroz `KeyManagement` adapter – KMS u prod, lokalni ključ u dev; vraća otpauth URI), `POST /me/mfa/totp/confirm` (`{code}` → `verified_at`, generiše recovery kodove, vraća ih jednom), `POST /auth/mfa/verify` (`{code}` ili `{recoveryCode}` → `sessions.mfa_verified_at`), `DELETE /me/mfa/totp` (zahteva reauth; zabranjeno ako je MFA obavezan za korisnika), `POST /me/mfa/recovery-codes/regenerate`.
- `E02-B12` `MfaPolicy`: obavezan MFA za `platform_admins`, OWNER članstva, support; login odgovara `mfaRequired = true` i izdaje sesiju sa ograničenim opsegom (samo `/auth/mfa/*`, `/auth/logout`, `/me` čitanje) dok `mfa_verified_at` nije postavljen.
- `E02-B13` `AuthenticationPlugin` (kolačić ili Bearer; `X-Client-Kind`), `CsrfPlugin` (tačan `Origin` + custom zaglavlje za web mutacije), `RateLimitPlugin` (in-memory token bucket + `login_attempts` kao durabilni izvor), bezbednosna zaglavlja.
- `E02-B14` `TenantContextPlugin` + `TenantTransaction` (2.2) i `PlatformContextPlugin`; `TenantContext` u `call.attributes`; garancija da se `set_config(..., true)` poziva **posle** `BEGIN` i da se konekcija vraća u pool tek posle COMMIT/ROLLBACK.
- `E02-B15` `com.vrticconnect.auth.authz` – `Permission` enum, `PermissionMatrix`, `Authorize.require(...)`, `ScopeCheck` interfejsi (`GroupScope`, `ChildScope` implementiraju E04/E05); `403` `problem+json` bez detalja.
- `E02-B16` `GET /me` (profil), `PATCH /me` (ime, jezik; `If-Match`), `POST /me/password` (stara + nova; reauth; opoziv ostalih sesija), `GET /me/memberships` (organizacija: id, slug, name, role, status – za tenant switcher).
- `E02-B17` Održavanje: scheduled job (worker) koji briše istekle `access_tokens`, potrošene `refresh_tokens` starije od 30 d, istekle verify/reset tokene, `login_attempts` starije od 90 d (retencija).
- `E02-B18` Audit i outbox događaji za sve akcije iz 2.1; `SECURITY` notifikacija kao outbox (`NEW_LOGIN`, `REFRESH_REUSE`, `PASSWORD_CHANGED`) – isporuka u E11, do tada samo zapis.
- `E02-B19` OpenAPI za sve `/auth/*` i `/me/*` operacije; `docs/SECURITY.md` matrica dozvola i parametri tokena usklađeni sa kodom.
- `E02-B20` Dev `MailSender` koji piše link u log samo u `APP_ENV=dev` (nikad u staging/prod); SES adapter iza interfejsa.

**Mobile (`shared/auth`, `shared/session`)**
- `E02-M01` `AuthRepository` (login, refresh, logout, MFA verify, reauth, forgot/reset) sa DTO-ovima; `X-Client-Kind` zaglavlje.
- `E02-M02` `TokenStore` nad `SecureStorage` (Keychain/Keystore); nikad u preferences/logovima; brisanje pri logout-u i `401` posle refresh-a.
- `E02-M03` `AuthInterceptor` – dodaje Bearer; na `401` radi **single-flight refresh** (Mutex), ponavlja zahtev jednom; na neuspeh → odjava.
- `E02-M04` Ekrani: prijava, prihvatanje pozivnice (deep link `vrticconnect://invite/{token}` + universal link), verifikacija email-a (deep link), MFA izazov, MFA podešavanje (QR + recovery kodovi), zaboravljena lozinka / reset (deep link), reautentifikacija (modal), izbor organizacije (`/me/memberships`), moj nalog (sesije + opoziv, promena lozinke, jezik).
- `E02-M05` `SessionState` (StateFlow): `SignedOut`, `MfaPending`, `SelectingTenant`, `Active(tenant)`; promena tenant-a briše keš prethodnog.
- `E02-M06` Shared testovi: refresh single-flight (100 paralelnih poziva → 1 refresh), odjava briše storage, `401` posle refresh-a vodi u `SignedOut`.

**Web (`src/features/auth`)**
- `E02-W01` API klijent u cookie režimu: `credentials: 'include'`, `X-Requested-With`, automatski `POST /auth/refresh` na `401` (single-flight), redirect na prijavu na neuspeh; **bez** čuvanja tokena.
- `E02-W02` Ekrani: prijava, MFA izazov, MFA podešavanje, prihvatanje pozivnice, verifikacija, zaboravljena lozinka/reset, reautentifikacija (modal), tenant switcher (`/me/memberships`), moj nalog (sesije + opoziv, lozinka, jezik).
- `E02-W03` `AuthProvider` + route guard (`RequireAuth`, `RequireMfa`, `RequireTenant`, `RequirePermission`); keš (React Query) sa ključem `[userId, organizationId]` i čišćenjem pri promeni tenant-a.
- `E02-W04` Playwright e2e: prijava → MFA → izbor organizacije → odjava; nevažeći CSRF (`Origin`) → `403`.

**Database**
- `E02-D01` Nema novih tabela (sve auth tabele su u V1). Ako benchmark ili profilisanje pokaže potrebu: `V<n>__auth_indexes.sql` (npr. `access_tokens (token_hash) INCLUDE (session_id, expires_at, revoked_at)`), inače prazno.
- `E02-D02` Integration fixture: `TestTenants` (dva tenant-a, korisnik sa članstvom u oba, korisnik samo u jednom, platformski admin) koje koriste svi kasniji EPIC-i.
- `E02-D03` Seed dopuna (dev): nalozi sa **Argon2id hash-om generisanim pri pokretanju seed-a iz env promenljive** `SEED_DEV_PASSWORD` (nikad hardkodovano), samo u `dev`.

### Acceptance criteria (testabilne izjave)
1. Prihvatanje važeće pozivnice kreira korisnika i `ACTIVE` članstvo; ponovna upotreba istog tokena vraća `410`/`404` bez efekta; istekla pozivnica se odbija.
2. Prijava neverifikovanim email-om vraća isti `401` kao pogrešna lozinka; po verifikaciji prijava uspeva.
3. Uspešna prijava (mobile) vraća access + refresh; access token važi 10 min; posle 10 min zahtev vraća `401`; refresh vraća nov par; stari refresh token u bazi ima `consumed_at` i `replaced_by_id`.
4. **Refresh replay**: druga upotreba istog refresh tokena vraća `401`, sesija dobija `revoked_at` + `REUSE_DETECTED`, i **novi** (legitimni) refresh token te sesije više ne radi; audit zapis postoji.
5. Refresh posle 7 dana neaktivnosti vraća `401` (idle); refresh posle 30 dana od kreiranja sesije vraća `401` (absolutno) iako je idle prozor svež.
6. `DELETE /auth/sessions/{id}` na drugoj sesiji istog korisnika odmah čini njen access token nevažećim (sledeći zahtev `401` unutar iste sekunde); logout-all opoziva sve; promena lozinke opoziva sve ostale.
7. Reset link ističe posle 15 min i jednokratan je; `forgot-password` vraća `202` i za nepostojeći email, u istom vremenskom opsegu (±20 %).
8. 11. neuspešna prijava u 15 min za isti email vraća `429` sa `Retry-After`; `users.locked_until` postavljen; uspešna prijava posle otključavanja resetuje brojač.
9. Web: mutacija bez `Origin` ili sa `Origin = https://evil.example` vraća `403`; kolačići imaju `HttpOnly; Secure; SameSite`; `document.cookie` ne sadrži token; nema tokena u `localStorage`.
10. Vlasnik bez MFA posle prijave dobija `mfaRequired`, može samo `/auth/mfa/*`; posle uspešnog TOTP-a sve rute rade; pogrešan kod 5 puta → `429`.
11. `POST /auth/logout-all` bez reautentifikacije u poslednjih 5 min vraća `403` `REAUTHENTICATION_REQUIRED`.
12. **Multi-tenant korisnik**: korisnik sa PARENT članstvom u org A i TEACHER u org B: `GET /organizations/{A}/...` radi po PARENT pravilima; `GET /organizations/{B}/...` po TEACHER pravilima; `GET /organizations/{C}/...` (bez članstva) vraća `404`.
13. Suspendovano članstvo → `404`/`403` odmah na sledećem zahtevu (bez čekanja isteka tokena).
14. **Pool context leakage**: posle zahteva za org A (COMMIT) i zahteva koji se završi greškom (ROLLBACK), nova transakcija na istoj fizičkoj konekciji bez konteksta vraća 0 redova iz tenant tabela; `current_setting('app.organization_id', true)` je prazan.
15. Tenant korisnik ne može dobiti listu svih korisnika: `users` vidi samo sebe i članove svoje organizacije (RLS test).
16. Nijedan log zapis tokom celog test seta ne sadrži lozinku, sirovi token ni reset link (grep u CI nad log izlazom testova).
17. `platform_admins` korisnik bez MFA ne može na `/platform/*`; sa MFA može, ali `GET /organizations/{A}/children` bez support granta vraća `404`.

### Potrebni testovi
- **Unit**: Argon2 verify/hash; TOTP (RFC 6238 test vektori); token hash; `PermissionMatrix` (tabela iznad kao parametrizovani test); CSRF Origin poređenje (tačno, ne `startsWith`).
- **Integration (Testcontainers, `app_runtime`)**: svi acceptance kriterijumi 1–13, 15, 17; konkurentni refresh (10 paralelnih sa istim tokenom → tačno jedan uspeh, ostali `401`, sesija opozvana ako je detektovan reuse posle uspeha); job čišćenja tokena.
- **RLS negativni**: auth tabele nedostupne bez `app.auth_mode`; `sessions_self` dozvoljava samo sopstvene sesije; `self_memberships`; `users_access`.
- **Pool leakage test** (obavezan, kriterijum 14) sa HikariCP `maximumPoolSize = 1` da se garantuje ista konekcija.
- **E2E**: web (Playwright) i mobile shared (E02-M06).
- **Load/benchmark**: Argon2 parametri (izveštaj u SECURITY.md); login p95 sa hash cenom.

### Definition of Done
Sve iz §2 + `docs/SECURITY.md` sadrži konačne parametre tokena/Argon2/rate limita i matricu dozvola identičnu `PermissionMatrix.kt`; `docs/API.md` i OpenAPI pokrivaju sve `/auth/*`, `/me/*` operacije; nijedna „prečica” (test-only login, hardkodovani korisnik) ne postoji u produkcionom kodu; `docs/VALIDATION_REPORT.md` ažuriran rezultatima stvarnog izvršenja testova.

---

## EPIC 03 – Organizations

**Cilj:** Platformsko upravljanje organizacijama (kreiranje, status, plan/pretplata, feature override), podešavanja
organizacije, objekti (`locations`), support pristup i tenant switcher.

**Prioritet:** P0
**Dependencies:** E02.

### Taskovi

**Backend (`com.vrticconnect.organization`, `com.vrticconnect.platform`, `com.vrticconnect.billing`, `com.vrticconnect.featureflag`)**
- `E03-B01` Platform: `POST /platform/organizations` (slug, name, legal_name, country, timezone, locale, plan code, trial dana, email prvog vlasnika → `invitations` OWNER) – kreira `organizations`, `organization_settings` (podrazumevane vrednosti), `subscriptions` (`TRIAL`), `subscription_events`; `GET /platform/organizations` (cursor, filter status), `GET /platform/organizations/{id}`, `PATCH /platform/organizations/{id}` (status `ACTIVE`/`SUSPENDED`/`ARCHIVED`, `If-Match`), audit `ORGANIZATION_CREATED`/`ORGANIZATION_STATUS_CHANGED`.
- `E03-B02` Platform: `GET/POST /platform/plans` (verzionisan katalog), `PATCH /platform/organizations/{id}/subscription` (plan, status, period; `subscription_events`), `GET/PUT /platform/feature-flags`, `PUT/DELETE /platform/organizations/{id}/feature-overrides/{key}`, `GET /platform/stats` (agregati: broj organizacija po statusu, broj članstava – bez podataka dece).
- `E03-B03` Tenant: `GET /organizations/{organizationId}` (profil + settings), `PATCH /organizations/{organizationId}` (name, legal_name, timezone, default_locale; `ORG_SETTINGS_WRITE`; `If-Match`), `GET/PUT /organizations/{organizationId}/settings` (`schedule_change_deadline_hours`, `late_arrival_grace_minutes`, `day_opens_at`, `day_closes_at`, `working_weekdays`, `offline_cache_ttl_hours`; validacija po CHECK ograničenjima), audit `ORG_SETTINGS_UPDATED`.
- `E03-B04` Locations: `GET/POST /organizations/{organizationId}/locations`, `GET/PATCH/DELETE /.../locations/{locationId}` (meko brisanje samo bez aktivnih grupa; jedinstven naziv; opciona `timezone`), audit.
- `E03-B05` `GET /organizations/{organizationId}/subscription` (owner ili `BILLING_MANAGE`: plan, status, period, limiti i iskorišćenost), `GET /organizations/{organizationId}/feature-flags` (efektivni flagovi po formuli `NOT kill_switch AND COALESCE(override, entitlement, default)`), `FeatureGate.require("photos")` helper → `404` kad je isključen.
- `E03-B06` Support pristup: `POST /platform/organizations/{id}/support-grants` (reason, ticket_ref, scope[], read_only, approval_kind; za `OWNER_APPROVED` kreira zahtev vlasniku), `POST /organizations/{organizationId}/support-grants/{grantId}/approve` (OWNER, reauth), `DELETE .../support-grants/{grantId}` (opoziv od obe strane), `GET /organizations/{organizationId}/support-grants` (owner vidi istoriju); `SupportContext` u `TenantContextPlugin` (`X-Support-Grant`), audit `SUPPORT_ACCESS_GRANTED/USED/REVOKED` sa `purpose`.
- `E03-B07` Plan limiti: `PlanLimits.check(organization, "max_locations")` → `422` `PLAN_LIMIT_EXCEEDED`.

**Mobile**
- `E03-M01` Tenant switcher (koristi `GET /me/memberships`), prikaz naziva organizacije i statusa `SUSPENDED` (read-only banner).
- `E03-M02` Učitavanje `settings` i `feature-flags` u `TenantSession` (vremenska zona, grace, flagovi) – kešira se po tenant-u.

**Web**
- `E03-W01` Platform ekrani: **Organizations** (lista, kreiranje, status, pretplata, feature overrides), **Plans**, **Feature flags**, **Support grants** (kreiranje sa razlogom/tiketom/opsegom).
- `E03-W02` Tenant ekrani: **Settings → Organizacija**, **Settings → Pravila rasporeda**, **Locations** (lista/forma), **Billing** (pregled), **Settings → Support pristup** (odobravanje/opoziv, istorija).
- `E03-W03` Tenant switcher u zaglavlju; čišćenje keša pri promeni.

**Database**
- `E03-D01` Nema novih tabela (`organizations`, `organization_settings`, `locations`, `support_access_grants`, `plans`, `subscriptions`, `subscription_events`, `feature_flags`, `organization_feature_overrides` su u V1). Seed: inicijalni `plans` (STARTER/STANDARD/PRO v1, RSD) i `feature_flags` (`photos`, `messaging`, `meals`, `payments`, `calendar`) kao **repeatable** Flyway migracija `R__reference_data.sql` (idempotentna).

### Acceptance criteria
1. Platformski admin sa MFA kreira organizaciju; nastaje `organization_settings` sa podrazumevanim vrednostima i `TRIAL` pretplata; pozvani vlasnik prihvata pozivnicu (E02) i vidi svoju organizaciju.
2. Owner menja `late_arrival_grace_minutes = 200` → `422`; `= 20` → `200` + audit.
3. Dva tenant-a: owner org A ne može `GET /organizations/{B}` (→ `404`), niti `GET /organizations/{B}/locations`.
4. Flag `photos`: plan bez entitlement-a → `GET .../feature-flags` vraća `photos: false`; override `true` → `true`; kill switch → `false` bez obzira na override.
5. Support grant: bez granta platformski admin dobija `404` na tenant rute; sa važećim grantom i opsegom `ORG_STRUCTURE` može `GET .../locations`, ali `GET .../children` → `403`; posle isteka (72 h max) → `404`; svaki pristup ima audit sa `purpose`.
6. Objekat sa aktivnom grupom se ne može obrisati (`409`).
7. Organizacija `SUSPENDED`: čitanje radi, komande vraćaju `403` `ORGANIZATION_SUSPENDED`; podaci netaknuti.

### Potrebni testovi
- Unit: formula feature flagova (svi slojevi), validacija settings.
- Integration: sve acceptance stavke; support grant životni ciklus.
- RLS negativni: `organizations_access` (switcher vidi samo svoje), `organizations_insert_platform` (tenant korisnik ne može INSERT).
- E2E web: kreiranje organizacije → pozivnica → podešavanja.

### Definition of Done
§2 + repeatable referentni podaci + `docs/SECURITY.md` odeljak o support pristupu usklađen sa implementacijom.

---

## EPIC 04 – Employees

**Cilj:** Pozivanje osoblja, kadrovski profili, dodele vaspitača grupama sa periodom, opseg vaspitača (`GroupScope`).

**Prioritet:** P0
**Dependencies:** E02, E03, E06 (grupe moraju postojati za dodele).

### Taskovi

**Backend (`com.vrticconnect.employee`)**
- `E04-B01` `POST /organizations/{organizationId}/invitations` (email, role ∈ OWNER/ADMIN/TEACHER; PARENT ide kroz E05 sa `child_id`), `GET .../invitations` (otvorene), `DELETE .../invitations/{id}` (opoziv); pravila: OWNER/ADMIN samo OWNER poziva; email sa linkom; audit `INVITATION_CREATED/REVOKED`; rate limit.
- `E04-B02` `GET/POST /organizations/{organizationId}/employees` (profil se kreira automatski pri prihvatanju pozivnice; POST služi za dopunu postojećeg članstva), `GET/PATCH .../employees/{employeeId}` (display_name, job_title, phone, primary_location_id, started_at/ended_at), lista sa statusom članstva i ulogom.
- `E04-B03` Članstva: `GET /organizations/{organizationId}/memberships` (filter role/status), `POST .../memberships/{id}/suspend`, `POST .../memberships/{id}/reactivate`, `POST .../memberships/{id}/revoke` (reauth; poslednji OWNER zabranjen → `409`); suspenzija/opoziv → `MembershipRevocationHook` koji odmah čini tenant kontekst nevažećim (provera statusa na svakom zahtevu je već u E02) i briše `device_push_tokens` vezane za sesije? (ne – tokeni su po korisniku; samo se prestaje slati za tu organizaciju); audit `MEMBERSHIP_SUSPENDED/REVOKED`.
- `E04-B04` Dozvole: `GET/PUT /organizations/{organizationId}/memberships/{id}/permissions` (OWNER; ADMIN uz `MEMBER_MANAGE` ali ne može dodeliti `BILLING_MANAGE`/`MEMBER_MANAGE`), `membership_permissions` sa `granted_by`/`revoked_at`; audit `PERMISSION_GRANTED/REVOKED`.
- `E04-B05` Dodele: `GET/POST /organizations/{organizationId}/groups/{groupId}/teacher-assignments`, `PATCH/DELETE .../teacher-assignments/{id}` (skraćivanje `valid_to`, opoziv `revoked_at`), `GET .../employees/{employeeId}/assignments`; EXCLUDE preklapanje → `409`; audit `TEACHER_ASSIGNED/UNASSIGNED`.
- `E04-B06` `GroupScope` implementacija za `ScopeCheck` (E02-B15): `teacherHasGroupOn(membershipId, groupId, date)` = postoji aktivna (`revoked_at IS NULL`) dodela sa `valid_from <= date <= COALESCE(valid_to, 'infinity')`; `GET /organizations/{organizationId}/me/groups?date=` vraća grupe vaspitača za datum.

**Mobile**
- `E04-M01` Vaspitač: učitavanje „mojih grupa za datum” i izbor grupe (ako ih je više); prazno stanje „nemate dodeljene grupe”.
- `E04-M02` Deep link prihvatanja pozivnice za osoblje (deli tok sa E02-M04).

**Web**
- `E04-W01` **Employees** ekran: lista (ime, uloga, status, objekat), pozivnica (email + uloga), profil, dozvole (checkbox lista iz `membership_permissions` enuma), suspenzija/opoziv sa potvrdom i reauth modalom.
- `E04-W02` Dodele vaspitača: forma na kartici grupe (izbor zaposlenog, uloga `LEAD/ASSISTANT/SUBSTITUTE`, period) + istorija dodela; na profilu zaposlenog lista dodela.

**Database**
- `E04-D01` Nema novih tabela (`employees`, `group_teacher_assignments`, `invitations`, `membership_permissions` u V1). Seed dopuna: 3 vaspitača sa dodelama (jedan sa istekom u prošlosti za test perioda).

### Acceptance criteria
1. ADMIN ne može poslati pozivnicu za OWNER/ADMIN (`403`); OWNER može.
2. Prihvatanje TEACHER pozivnice kreira `employees` red vezan za članstvo.
3. Preklapajuća dodela istog vaspitača istoj grupi → `409`.
4. **Teacher period**: vaspitač sa dodelom `valid_to = juče` dobija `403` na `GET .../groups/{g}/daily-overview?date=danas` i `200` za `date=juče`; posle `revoke` → `403` za sve datume.
5. Poslednji OWNER ne može biti opozvan (`409`).
6. Suspendovano članstvo: sledeći zahtev tog korisnika u toj organizaciji → `404`; u drugoj organizaciji radi normalno.
7. ADMIN uz `MEMBER_MANAGE` ne može dodeliti `BILLING_MANAGE` (`403`).

### Potrebni testovi
- Unit: `GroupScope` granice datuma (uključivo), poslednji OWNER pravilo.
- Integration: acceptance 1–7.
- RLS negativni: `employees` i `group_teacher_assignments` nevidljivi iz drugog tenant-a; INSERT sa tuđim `organization_id` odbijen.
- E2E web: pozivnica → dodela → suspenzija.

### Definition of Done
§2 + matrica dozvola u SECURITY.md proširena `MEMBER_*` pravilima.

---

## EPIC 05 – Children & Parents

**Cilj:** Profil deteta, upisi (istorija po grupama), pozivnice i potvrđivanje staratelja sa ovlašćenjima, ovlašćene
osobe za preuzimanje, šifrovan zdravstveni profil sa auditom čitanja, `ChildScope`.

**Prioritet:** P0
**Dependencies:** E02, E03, E04, **E06 (grupe pre upisa)**.

### Taskovi

**Backend (`com.vrticconnect.child`, `com.vrticconnect.guardian`, `com.vrticconnect.health`)**
- `E05-B01` `GET/POST /organizations/{organizationId}/children` (lista: cursor, filter `locationId`, `groupId`, `status`, pretraga po imenu; **bez** zdravstvenih polja; `has_critical_alert` samo za osoblje), `GET/PATCH .../children/{childId}` (`If-Match` na `version`), `POST .../children/{childId}/deactivate`; audit `CHILD_CREATED/UPDATED/DEACTIVATED`; validacija `date_of_birth`.
- `E05-B02` Upisi: `GET/POST .../children/{childId}/enrollments`, `PATCH .../enrollments/{id}` (kraj upisa, `end_reason`, status), `POST .../children/{childId}/transfer` (`{toGroupId, effectiveFrom}` – atomski zatvara tekući i otvara novi upis); EXCLUDE preklapanje → `409`; upozorenje kapaciteta u odgovoru (`warnings: [GROUP_CAPACITY_EXCEEDED]`), audit `ENROLLMENT_*`.
- `E05-B03` Pozivnica staratelja: `POST .../children/{childId}/guardian-invitations` (email, relationship, ovlašćenja) → `invitations` sa `child_id` + `guardians` red `PENDING` po prihvatanju (ili odmah ako korisnik već ima PARENT članstvo); prihvatanje (E02-B04) kreira PARENT članstvo + `guardians` `PENDING`; `POST .../guardians/{id}/confirm` (osoblje; `confirmed_by/at`), `PATCH .../guardians/{id}` (ovlašćenja, `is_primary`), `POST .../guardians/{id}/revoke` (reauth); `GET .../children/{childId}/guardians`, `GET .../guardians?membershipId=`; audit `GUARDIAN_INVITED/CONFIRMED/UPDATED/REVOKED`.
- `E05-B04` `ChildScope` za `ScopeCheck`: PARENT → `guardians.status = 'CONFIRMED'` za dete; TEACHER → dete ima upis u grupu iz važeće dodele na datum; ADMIN/OWNER → cela organizacija. `GET /organizations/{organizationId}/me/children` (roditelj: svoja deca sa grupom i vaspitačima; vaspitač: deca svojih grupa za datum).
- `E05-B05` Ovlašćene osobe: `GET/POST .../children/{childId}/pickup-persons`, `PATCH/DELETE (revoke) .../pickup-persons/{id}`; roditelj (CONFIRMED) i osoblje mogu dodavati; audit `PICKUP_PERSON_ADDED/REVOKED`.
- `E05-B06` Zdravstveni profil: `GET/PUT /organizations/{organizationId}/children/{childId}/health` – `HealthPayload` DTO (allergies[], specialNeeds[], medicalNotes, emergencyContact), envelope šifrovanje kroz `KeyManagement` (`payload_enc`, `payload_key_id`, `payload_schema_ver`), `has_critical_alert` izveden iz payload-a (ili eksplicitan), `version` + `If-Match`; dozvole: PARENT (CONFIRMED + `can_view_health`), osoblje uz `CHILD_HEALTH_READ/WRITE` + opseg; **obavezan `purpose`** (query/telo) za osoblje; audit `HEALTH_READ`/`HEALTH_UPDATED` **na svaki poziv**; nikad u listama/notifikacijama/logovima.
- `E05-B07` Parents lista za admin: `GET /organizations/{organizationId}/guardians/overview` (PARENT članstva sa decom i statusom veze).
- `E05-B08` Outbox događaji: `GUARDIAN_CONFIRMED` (notifikacija roditelju), `ENROLLMENT_CHANGED` (vaspitačima grupe).

**Mobile**
- `E05-M01` Roditelj: **Moja deca** (lista), **Dete** (profil, grupa, vaspitači, staratelji, ovlašćene osobe + dodavanje).
- `E05-M02` Roditelj: **Zdravlje** (prikaz/izmena; samo online; jasna napomena o svrsi); vaspitač sa dozvolom: prikaz (online, sa unosom svrhe).
- `E05-M03` Vaspitač: **Dete** iz liste grupe (ime, fotografija placeholder, ovlašćene osobe, staratelji – ime i telefon, indikator kritičnog upozorenja).
- `E05-M04` Deep link za pozivnicu staratelja (prikaz deteta i uloge pre prihvatanja).

**Web**
- `E05-W01` **Children**: lista sa filterima i pretragom; **Kartica deteta** sa tabovima Profil / Upisi (istorija + premeštaj) / Staratelji (pozivnica, potvrda, ovlašćenja, opoziv) / Preuzimanje / Zdravlje (uz dozvolu, sa svrhom).
- `E05-W02` **Parents**: lista PARENT članstava sa decom, otvorene pozivnice, statusi veza.

**Database**
- `E05-D01` `V<n>__children.sql`: tabele `children`, `enrollments`, `guardians`, `pickup_persons`, `child_health_profiles`; `ALTER TABLE app.invitations ADD CONSTRAINT invitations_child_fk`; RLS `tenant_isolation` za svih pet tabela; grantovi; indeksi kao u šemi. (`children.photo_file_id` ostaje bez FK do E15.)
- `E05-D02` Trigger/provera da `guardians.membership_id` pokazuje na PARENT članstvo (trigger u migraciji + aplikaciona provera).
- `E05-D03` Seed dopuna: 15 dece u dve grupe, ~12 roditelja, deca sa dva staratelja, roditelj sa više dece, dete sa zdravstvenim profilom (sintetički payload šifrovan dev ključem), drugi tenant sa svojom decom.

### Acceptance criteria
1. **Nepovezana deca**: roditelj sa CONFIRMED vezom za dete X dobija `200` na `GET .../children/X`, a `404` na dete Y iz iste organizacije; roditelj ne može `POST .../children/Y/guardian-invitations` (`403`).
2. Roditelj ne može sam kreirati ni potvrditi `guardians` red (`403`); `PENDING` veza ne daje pristup detetu.
3. Opoziv veze → sledeći zahtev roditelja za to dete `404`.
4. Preklapajući upis → `409`; premeštaj je atomski (stari upis `valid_to = effectiveFrom − 1`, novi `ACTIVE`).
5. `GET .../children` nikada ne sadrži alergije/napomene (schema test odgovora); `has_critical_alert` prisutan samo za osoblje.
6. `GET .../children/{id}/health` od TEACHER-a bez `CHILD_HEALTH_READ` → `403` i audit `HEALTH_READ` sa `result = DENIED`; sa dozvolom → `200` i audit `SUCCESS` sa `purpose`.
7. OWNER bez dozvole → `403` na health.
8. Payload u bazi je šifrovan (`payload_enc` nije čitljiv JSON); dešifrovanje sa pogrešnim `key_id` pada kontrolisano.
9. **Dva tenant-a**: dete iz org B nevidljivo iz org A (`404`); `INSERT` u `enrollments` sa `group_id` iz org B odbijen kompozitnim FK.

### Potrebni testovi
- Unit: `ChildScope`; premeštaj datuma; health DTO serijalizacija/verzija šeme.
- Integration: acceptance 1–9; šifrovanje/dešifrovanje sa test KMS-om.
- RLS negativni: pet tabela; kompozitni FK preko tenant-a.
- E2E web: dete → upis → pozivnica roditelja → potvrda; mobile shared: roditelj vidi samo svoju decu (mock server).

### Definition of Done
§2 + `docs/SECURITY.md` lista audit akcija za zdravlje + potvrda da nijedan log ne sadrži health payload (CI grep).

---

## EPIC 06 – Groups

**Cilj:** CRUD grupa po objektu, kartica grupe (vaspitači, deca po datumu), kapacitet, deaktivacija. Preduslov za upise (E05) i dodele (E04).

**Prioritet:** P0
**Dependencies:** E02, E03.

### Taskovi

**Backend (`com.vrticconnect.group`)**
- `E06-B01` `GET/POST /organizations/{organizationId}/groups` (filter `locationId`, `status`), `GET/PATCH/DELETE .../groups/{groupId}` (meko brisanje samo bez aktivnih/planiranih upisa i dodela → inače `409`), validacija uzrasta i kapaciteta, jedinstven naziv u objektu; audit `GROUP_*`.
- `E06-B02` `GET .../groups/{groupId}/children?date=` (deca sa upisom na datum – koristi E05; do tada prazna lista), `GET .../groups/{groupId}/teachers?date=` (E04).
- `E06-B03` `GET .../groups/{groupId}/summary?date=` (broj upisane dece, kapacitet, vaspitači) za dashboard.
- `E06-B04` Opseg: TEACHER vidi samo svoje grupe (`GroupScope`), PARENT vidi grupu kroz dete (naziv, vaspitači) – bez liste dece.

**Mobile**
- `E06-M01` Prikaz naziva grupe/objekta u ekranima roditelja i vaspitača; vaspitač: lista dece grupe za datum (osnova dnevnog pregleda).

**Web**
- `E06-W01` **Groups**: lista po objektu, forma (naziv, uzrast, kapacitet), kartica grupe (vaspitači sa periodima, deca na datum, popunjenost), deaktivacija.

**Database**
- `E06-D01` Nema novih tabela (`groups` u V1). Seed: „Bubamare” i „Leptirići” u objektu Novi Sad.

### Acceptance criteria
1. Dva objekta mogu imati grupu istog naziva; isti objekat ne može (`409`).
2. Grupa sa aktivnim upisom ne može biti obrisana (`409`), može biti `INACTIVE`.
3. TEACHER bez dodele → `404` na grupu; PARENT vidi grupu svog deteta bez liste dece (`GET .../groups/{g}/children` → `403`).
4. Grupa iz org B nevidljiva iz org A.

### Potrebni testovi
- Unit: validacija uzrasta/kapaciteta.
- Integration + RLS negativni + E2E web (kreiranje grupe pre upisa).

### Definition of Done
§2.

---

## EPIC 07 – Schedule

**Cilj:** Nedeljni šabloni sa datumom važenja, izmene za dan, neradni dani, dnevni plan sa prioritetom
(neradni dan > odsustvo > izmena > šablon > ništa), pregled pre primene, atomska nedeljna izmena, rok i oznaka kasne izmene,
zamrzavanje istorije, feed izmena.

**Prioritet:** P0
**Dependencies:** E03 (settings), E05 (deca, upisi, `ChildScope`), E06; E09 (odsustva) za izvor `ABSENCE` – do E09 se taj korak preskače.

### Taskovi

**Backend (`com.vrticconnect.schedule`)**
- `E07-B01` Šabloni: `GET .../children/{childId}/schedule/templates` (istorija), `POST .../children/{childId}/schedule/templates` (`effectiveFrom`, 7 dana; zatvara prethodni `effective_to = effectiveFrom − 1`; `effectiveFrom >= danas`), `GET .../schedule/templates/{id}`; validacija vremena unutar `day_opens_at`–`day_closes_at`, dani van `working_weekdays` samo `attends = false`; `schedule_change_log` `TEMPLATE_REPLACED` sa before/after; audit `SCHEDULE_TEMPLATE_REPLACED`.
- `E07-B02` Izmene za dan: `GET .../children/{childId}/schedule/days?from=&to=` (efektivni plan po danu sa `source`), `PUT .../children/{childId}/schedule/days/{date}` (kreira/menja override; `If-Match`; `date >= danas`; `is_late_change` po `DeadlinePolicy`), `DELETE .../schedule/days/{date}` (meko; `OVERRIDE_REMOVED`).
- `E07-B03` Nedeljna atomska izmena: `PUT .../children/{childId}/schedule/week?weekStart=` (7 stavki; sve validacije pre upisa; jedna transakcija; agregatna verzija u `ETag`; `409` sa celim trenutnim stanjem nedelje; `WEEK_UPDATED`).
- `E07-B04` Pregled: `POST .../children/{childId}/schedule/preview` (predloženi šablon/izmene + period → izračunati plan bez upisa; označava kasne dane).
- `E07-B05` `DeadlinePolicy`: `isLate(changeAt, targetDate) = changeAt > (targetDate at day_opens_at in orgTz) − schedule_change_deadline_hours`; `late_change_mode` (`FLAG` P0; `REJECT` P1 podešavanje).
- `E07-B06` `DailyPlanCalculator` (čist domen, bez I/O): ulaz (closure?, absence?, override?, template day?) → `DailyPlan(isExpected, arrival, departure, source)` po prioritetu 1–5; koristi se i za preview i za zamrzavanje; **identična logika se ne duplira na klijentu** (klijent prikazuje ono što server vrati; offline snapshot nosi izračunate planove).
- `E07-B07` `daily_plans` materijalizacija: `DailyPlanService.recompute(childId, dateRange)` samo za `frozen_at IS NULL`; poziva se pri svakoj promeni šablona/izmene/neradnog dana/odsustva/upisa; `DayFreezeJob` (worker, po organizaciji u `day_opens_at` lokalno) postavlja `frozen_at` za današnji datum i popunjava `group_id` iz upisa.
- `E07-B08` Neradni dani: `GET/POST/DELETE /organizations/{organizationId}/closure-days` (org ili objekat; buduće datume; recompute plana za tu decu), audit `CLOSURE_DAY_*`.
- `E07-B09` Pregled za osoblje: `GET .../groups/{groupId}/schedule?date=` (deca grupe sa očekivanjima i `source`), `GET .../schedule/changes?from=&to=&lateOnly=` (feed iz `schedule_change_log` za osoblje; vaspitač samo za svoje grupe).
- `E07-B10` Outbox `SCHEDULE_CHANGED` (payload: childId, dates, isLate) → notifikacija vaspitačima (E11) kada je kasna ili za danas/sutra.
- `E07-B11` Dozvole: PARENT uz `can_manage_schedule` samo za svoju decu; ADMIN/OWNER uvek (P1: obavezna napomena); TEACHER samo čitanje.

**Mobile**
- `E07-M01` Roditelj: **Raspored deteta** (nedeljni prikaz sa navigacijom po nedeljama, `source` oznake: šablon / izmena / odsustvo / neradni dan).
- `E07-M02` Roditelj: **Izmeni nedelju** (7 dana, time picker sa granicama radnog vremena, pregled → potvrda; `409` prikazuje konflikt i nudi ponovno učitavanje), **Promeni dan**, **Nedeljni šablon** (važi od datuma).
- `E07-M03` Upozorenje „kasna izmena” pre potvrde (klijent koristi `preview` odgovor, ne računa sam).
- `E07-M04` Vaspitač: **Sutra / Nedelja** – ko se očekuje, oznaka kasnih izmena; feed izmena za grupu.

**Web**
- `E07-W01` **Schedules**: pregled po grupi i datumu/nedelji; feed kasnih izmena; unos rasporeda za dete (P1 dozvola admina); neradni dani (kalendar unos).
- `E07-W02` Kartica deteta → tab Raspored (istorija šablona, izmene).

**Database**
- `E07-D01` `V<n>__schedule.sql`: `schedule_templates`, `schedule_template_days`, `schedule_day_overrides`, `closure_days`, `daily_plans` (kolona `absence_id` bez FK do E09), `schedule_change_log` (append-only trigger); RLS + grantovi (uključujući `DELETE` na `schedule_template_days`).
- `E07-D02` Seed: šabloni za svu decu (raznovrsni: pet dana, tri dana, jedan bez dolaska petkom), par izmena, jedan neradni dan.

### Acceptance criteria
1. Prioritet: za dete sa šablonom (pon 08–16), izmenom za pon (09–15), aktivnim odsustvom pon i neradnim danom pon → plan `CLOSURE`; bez neradnog dana → `ABSENCE`; bez odsustva → `OVERRIDE` 09–15; bez izmene → `TEMPLATE` 08–16; bez šablona → `NONE` (parametrizovani test svih 5 nivoa).
2. Novi šablon sa `effectiveFrom = sledeći ponedeljak` ne menja `daily_plans` za prošle datume ni za zamrznute; menja buduće.
3. Izmena za jučerašnji datum → `422`.
4. Nedeljna izmena sa jednim nevalidnim danom (dolazak > odlazak) → `422` i **nijedan** dan nije upisan.
5. Dve paralelne nedeljne izmene sa istim `If-Match` → jedna `200`, druga `409` sa trenutnim stanjem.
6. Rok 12 h, `day_opens_at = 06:00`: izmena za sutra uneta danas u 17:00 (Europe/Belgrade) → `is_late_change = false`; uneta u 19:00 → `true`; zapis u `schedule_change_log` i outbox događaj.
7. Preview ne upisuje ništa (broj redova nepromenjen).
8. Roditelj bez `can_manage_schedule` → `403`; roditelj drugog deteta → `404`; vaspitač → `403` na PUT.
9. DST: raspored 08:00 na dan promene vremena i dalje se prikazuje kao 08:00; poređenje kašnjenja koristi ispravan instant.

### Potrebni testovi
- Unit: `DailyPlanCalculator` (svi prioriteti), `DeadlinePolicy` (granice, DST), validacije šablona.
- Integration: acceptance 1–9; freeze job; recompute posle promene upisa.
- RLS negativni: šest tabela.
- E2E: roditelj menja nedelju (mobile shared sa mock serverom), admin vidi kasnu izmenu (web).

### Definition of Done
§2 + dokumentovana referentna tačka roka (PRODUCT_SPEC 9.6 pretpostavka) u ADR-u.

---

## EPIC 08 – Attendance

**Cilj:** Evidencija stvarnog prisustva: nepromenljivi događaji, projekcija `attendance_days`/`attendance_visits`,
mašina stanja, više poseta, korekcije sa razlogom, idempotencija (`command_id`), optimistic concurrency + row locking,
neplanirano prisustvo, dnevni pregled sa brojačima, offline red komandi na mobilnom.

**Prioritet:** P0
**Dependencies:** E04 (`GroupScope`), E05, E06, E07 (`daily_plans`), E09 (odsustvo kao kontekst – može paralelno, završava se posle E09).

### Taskovi

**Backend (`com.vrticconnect.attendance`)**
- `E08-B01` Komande: `POST /organizations/{organizationId}/attendance/commands` sa telom `{commandId, childId, date, type: CHECK_IN|CHECK_OUT, occurredAt, expectedVersion, deviceId?, pickupPersonId?|guardianId?, source}`; `Idempotency-Key = commandId`; obrada: `SELECT ... FOR UPDATE` na `attendance_days` (INSERT ako ne postoji, kopira `is_expected`/očekivana vremena iz `daily_plans`), provera `expectedVersion` (`409` `ATTENDANCE_VERSION_CONFLICT` + trenutna projekcija), prelaz stanja (`409` `ATTENDANCE_INVALID_TRANSITION`), insert `attendance_events` (UNIQUE `command_id` → ponovljena komanda vraća `200` sa postojećim stanjem), ažuriranje `attendance_visits` (nova poseta `sequence_no + 1` ili zatvaranje otvorene), projekcija (`status`, `first_check_in_at`, `last_check_out_at`, `open_visit_id`, `visits_count`, `version + 1`, `last_event_id`), `UNSCHEDULED_PRESENT` događaj ako `is_expected = false`; audit; outbox `ATTENDANCE_CHANGED`.
- `E08-B02` Validacije: `occurredAt` ≤ now + 5 min i ≥ `day_opens_at − 2 h`; `date` ∈ {danas, juče} za TEACHER (juče samo `CHECK_OUT` zaostale posete), bilo koji za ADMIN uz `ATTENDANCE_CORRECT`; dete mora imati upis u grupu vaspitača na `date`.
- `E08-B03` Korekcije: `POST .../attendance/corrections` `{commandId, correctionOfEventId, reason, newOccurredAt?, newType?}` → `CORRECTION` događaj + rekalkulacija projekcije **iz svih događaja dana** (`AttendanceProjector.rebuild(dayId)`), dozvola `ATTENDANCE_CORRECT`/ADMIN/OWNER; audit `ATTENDANCE_CORRECTED`.
- `E08-B04` Čitanje: `GET .../attendance/days?groupId=&date=` (projekcije + posete), `GET .../children/{childId}/attendance?from=&to=` (roditelj: svoje dete), `GET .../attendance/days/{dayId}/events` (istorija sa korekcijama).
- `E08-B05` Dnevni pregled: `GET .../groups/{groupId}/daily-overview?date=` → `{counters: {expected, present, departed, absent, notArrived, late, unscheduledPresent, physicallyPresent}, items: [{childId, name, photoFileId?, expectedArrival, expectedDeparture, planSource, status, firstCheckInAt, lastCheckOutAt, isLate, isUnscheduled, absenceKind, isLateScheduleChange, hasCriticalAlert (samo osoblje), attendanceVersion, availableAction: CHECK_IN|CHECK_OUT}], snapshotAt, groupVersion}`; `DailyOverviewCalculator` (čist domen) implementira definicije iz PRODUCT_SPEC §5.9 sa invarijantom `expected = present + departed + absent + notArrived`; Late = `NOT_ARRIVED` bez odsustva i `now > expectedArrival + late_arrival_grace_minutes` (u zoni org/objekta).
- `E08-B06` Sync endpoint za offline: `POST .../attendance/sync` (batch komandi u redosledu; svaka se obrađuje nezavisno u sopstvenoj transakciji; odgovor po komandi: `APPLIED{version}` / `DUPLICATE{version}` / `CONFLICT{currentState}` / `REJECTED{code}`; plus `tombstones` (deca bez upisa, grupe bez dodele) i svež `daily-overview`).
- `E08-B07` Sistemski događaji `ABSENCE_MARKED`/`ABSENCE_CLEARED` (poziva E09 kada odsustvo utiče na dan koji već ima red) – **bez** promene `status`; `absence_kind` se puni/prazni.
- `E08-B08` Podsetnik (worker): deca `CHECKED_IN` posle `day_closes_at + 1 h` → outbox `ATTENDANCE_OPEN_VISIT_REMINDER` vaspitačima i adminu (bez auto check-out-a).
- `E08-B09` Metrike: broj komandi po izvoru, konflikti, duplikati, latencija.

**Mobile (`shared/attendance`, `shared/offline`)**
- `E08-M01` **Dnevni pregled** ekran: brojači-čipovi (filter), lista (sortiranje po statusu), veliki dugmad (≥ 56 dp), datum/grupa izbor, offline indikator, pending/konflikt oznake, pull-to-refresh, polling 30–60 s dok je aktivan.
- `E08-M02` **Akcija** (Dolazak/Odlazak) sa opcionim izborom „ko preuzima” (lista ovlašćenih + staratelja) i potvrdom; **Istorija danas** za dete; **Korekcija** (uz dozvolu).
- `E08-M03` `DailyOverviewCalculator` u `shared` (ista formula; koristi se **samo** za lokalnu projekciju u offline režimu) + testovi parnosti sa serverom (isti JSON fixture-i u oba repoa: `docs/database/tests/daily_overview_fixtures.json`).
- `E08-M04` Offline (PRODUCT_SPEC §5.19): SQLDelight šifrovana baza po (userId, organizationId); `SnapshotStore` (deca, planovi, dani sa verzijama, ovlašćene osobe, `snapshotAt`, TTL iz settings – 12 h); `CommandQueue` (FIFO po detetu, `commandId`, `expectedVersion`, `occurredAt`, status `PENDING/SENDING/APPLIED/CONFLICT/REJECTED`); `SyncEngine` (reconnect → refresh sesije → provera dodela → `POST /attendance/sync` → primena odgovora → uklanjanje samo `APPLIED`/`DUPLICATE`; konflikti ostaju do odluke vaspitača; tombstones brišu lokalne podatke; resync).
- `E08-M05` UI za konflikt: prikaz serverskog stanja vs. moja komanda; opcije „prihvati serversko” / „ponovi na trenutnoj verziji” (eksplicitna potvrda) / „unesi korekciju” (uz dozvolu). **Nikad automatski.**
- `E08-M06` Brisanje offline baze pri odjavi, promeni tenant-a, opozivu i isteku TTL-a (podaci se ne prikazuju posle isteka; komande ostaju do slanja).
- `E08-M07` Roditelj: **Dete → Prisustvo** (danas + istorija).

**Web**
- `E08-W01` **Attendance**: tabela po grupi/datumu (status, posete, kašnjenje, neplanirano), detalji dana sa događajima i korekcijama, unos korekcije (razlog obavezan), dnevni pregled sa **istim** brojačima (koristi server odgovor).
- `E08-W02` Kartica deteta → tab Prisustvo.

**Database**
- `E08-D01` `V<n>__attendance.sql`: `attendance_days`, `attendance_visits`, `attendance_events` (append-only trigger), deferred FK-ovi (`attendance_days_open_visit_fk`, `attendance_days_last_event_fk`, visit↔event FK), RLS + grantovi; indeksi kao u šemi.
- `E08-D02` Testni fixture-i dnevnog pregleda (`docs/database/tests/daily_overview_fixtures.json`) deljeni sa mobile testovima.

### Acceptance criteria
1. Mašina stanja: `CHECK_OUT` iz `NOT_ARRIVED` → `409`; `CHECK_IN` iz `CHECKED_IN` → `409`; `CHECK_IN` iz `CHECKED_OUT` otvara posetu `sequence_no = 2`; `visits_count = 2`.
2. Ista komanda (isti `commandId`) poslata dva puta → jedan događaj, drugi odgovor `200` sa istim stanjem.
3. **Konkurentne komande**: 20 paralelnih `CHECK_IN` za isto dete sa istim `expectedVersion` i različitim `commandId` → tačno jedan `201`, ostali `409` (`FOR UPDATE` + verzija); projekcija ima `version = 1` i `visits_count = 1`.
4. Korekcija vremena dolaska ostavlja originalni događaj; projekcija odražava novo vreme; `UPDATE attendance_events` direktno → greška `insufficient_privilege` (trigger).
5. Dete bez očekivanja (`is_expected = false`) → check-in daje `is_unscheduled = true`, `UNSCHEDULED_PRESENT` događaj, ulazi u `unscheduledPresent` i `physicallyPresent`, ne u `expected`.
6. Odsustvo prijavljeno dok je dete `CHECKED_IN` → status ostaje `CHECKED_IN`, `absence_kind = SICK`, dnevni pregled ga broji u Present.
7. Brojači: fixture sa 12 dece (5 present, 2 departed, 2 absent, 3 not arrived od kojih 1 late, +2 unscheduled) → `expected = 12`, invarijanta tačna, `late = 1`, `unscheduledPresent = 2`, `physicallyPresent = 7`; **isti rezultat** u backend i mobile testu.
8. Vaspitač bez dodele na datum → `403`; dete van grupe → `422`; roditelj → `403` na komande.
9. Sync batch sa [APPLIED, DUPLICATE, CONFLICT, REJECTED] mešavinom vraća tačne statuse po komandi; konfliktna komanda ne blokira sledeće za drugu decu.
10. **Offline konflikt** (mobile shared test): snapshot v0 → offline `CHECK_IN` → server u međuvremenu `CHECK_IN` (v1) → sync vraća `CONFLICT` → komanda ostaje u redu sa statusom `CONFLICT`, lokalna projekcija prikazuje serversko stanje, nema automatske primene; „prihvati serversko” uklanja komandu.
11. Istekli snapshot (TTL) blokira nove komande, ali postojeće pending komande se šalju.
12. Dva tenant-a: komanda sa `childId` iz org B u kontekstu org A → `404`; `attendance_events` INSERT sa tuđim `organization_id` odbijen.

### Potrebni testovi
- Unit: mašina stanja, `AttendanceProjector.rebuild`, `DailyOverviewCalculator` (backend i mobile, deljeni fixture-i), validacija `occurredAt`.
- Integration: acceptance 1–9, 12; konkurentnost (kriterijum 3) sa pravim PostgreSQL-om.
- RLS negativni: tri tabele; append-only trigger.
- Mobile shared: `CommandQueue`, `SyncEngine` (kriterijumi 10–11), brisanje baze pri odjavi.
- E2E: vaspitač check-in/out na Androidu (instrumentirani test, po mogućnosti), web korekcija.

### Definition of Done
§2 + fixture-i parnosti brojača u repou + ADR o odluci „bez auto check-out-a” (PRODUCT_SPEC 9.8).

---

## EPIC 09 – Absence

**Cilj:** Prijava i otkazivanje odsustava (SICK/VACATION/OTHER), preklapanje, uticaj na dnevni plan i dan prisustva, vidljivost vaspitaču, audit i notifikacija.

**Prioritet:** P0
**Dependencies:** E05, E07; integracija sa E08 (kontekst).

### Taskovi

**Backend (`com.vrticconnect.absence`)**
- `E09-B01` `POST /organizations/{organizationId}/children/{childId}/absences` (`kind`, `dateFrom`, `dateTo`, `note?`; `Idempotency-Key`; ≤ 365 dana; `dateTo >= dateFrom`; PARENT uz `can_report_absence` ili osoblje), `GET .../children/{childId}/absences`, `GET .../absences?groupId=&from=&to=` (osoblje; vaspitač samo svoje grupe), `POST .../absences/{id}/cancel` (`If-Match`; `cancelled_by/at`).
- `E09-B02` Preklapanje: EXCLUDE greška → `409` `ABSENCE_OVERLAP` sa postojećim odsustvom u `detail`; opcija `?extend=true` (produžava postojeće umesto novog – P1).
- `E09-B03` Integracija sa rasporedom: posle kreiranja/otkazivanja poziv `DailyPlanService.recompute(childId, [max(dateFrom, today), dateTo])`; za dan sa postojećim `attendance_days` redom → `AttendanceService.markAbsence(...)`/`clearAbsence(...)` (E08-B07) bez promene statusa.
- `E09-B04` Outbox `ABSENCE_REPORTED`/`ABSENCE_CANCELLED` (childId, groupId, dates, kind) → notifikacija vaspitačima grupe; audit `ABSENCE_REPORTED/CANCELLED`.
- `E09-B05` UI poruka „bez medicinskih detalja” kao i18n ključ; `note` se ne šalje u push.

**Mobile**
- `E09-M01` Roditelj: **Prijavi odsustvo** (vrsta, od–do, napomena sa napomenom o nemedicinskom sadržaju), lista odsustava sa „Otkaži”, `409` preklapanje sa prikazom postojećeg.
- `E09-M02` Vaspitač: lista odsustava grupe (danas/nedelja); u dnevnom pregledu status „Odsutno (bolest)”.

**Web**
- `E09-W01` **Attendance → Absences**: lista/filter, unos umesto roditelja (sa zabeleškom „uneo admin”), otkazivanje.

**Database**
- `E09-D01` `V<n>__absences.sql`: `absences` (EXCLUDE, CHECK-ovi) + `ALTER TABLE app.daily_plans ADD CONSTRAINT daily_plans_absence_fk ... ON DELETE SET NULL`; RLS + grantovi.
- `E09-D02` Seed: dva aktivna odsustva (jedno tekuće, jedno buduće), jedno otkazano.

### Acceptance criteria
1. Preklapajuća prijava → `409` sa ID-jem postojećeg; posle otkazivanja prvog, druga prolazi.
2. Prijava za danas odmah menja `daily_plans` današnjeg (nezamrznutog) i budućih dana na `ABSENCE`; prošli dani netaknuti.
3. Prijava dok je dete `CHECKED_IN` ne menja status; `absence_kind` popunjen; događaj `ABSENCE_MARKED` u `attendance_events`.
4. Roditelj bez `can_report_absence` → `403`; vaspitač → `403` na POST, `200` na GET za svoju grupu, `403` za tuđu.
5. Otkazivanje sa zastarelim `If-Match` → `409`.
6. Outbox događaj postoji posle prijave; `note` nije u payload-u.
7. Odsustvo iz org B nevidljivo iz org A.

### Potrebni testovi
- Unit: validacija perioda; recompute opseg.
- Integration: 1–7; RLS negativni; E2E mobile shared (prijava → `409` → otkazivanje).

### Definition of Done
§2.

---

## EPIC 10 – Announcements

**Cilj:** Obaveštenja sa publikom (organizacija/objekat/grupa/staratelj), statusima draft/published/archived, snapshot-om
primalaca pri objavi, proverom trenutnih prava pri čitanju i praćenjem čitanja. Slike/prilozi u P1 (posle E15).

**Prioritet:** P0 (tekst), P1 (prilozi)
**Dependencies:** E03, E05, E06; E11 za isporuku notifikacija; E15 za priloge.

### Taskovi

**Backend (`com.vrticconnect.announcement`)**
- `E10-B01` `GET/POST /organizations/{organizationId}/announcements` (osoblje: sve po statusu; lista za primaoce vidi samo `PUBLISHED` u snapshot-u + trenutna prava), `GET/PATCH/DELETE .../announcements/{id}` (`If-Match`; DELETE = meko), `POST .../announcements/{id}/publish` (odmah ili `publish_at`), `POST .../announcements/{id}/archive`; `PUT .../announcements/{id}/audiences` (lista pravila; TEACHER samo `GROUP` iz svojih grupa).
- `E10-B02` `AudienceResolver`: pravila → skup `membership_id` (ORGANIZATION: sva ACTIVE članstva; LOCATION: staratelji dece upisane u grupe objekta na dan objave + osoblje objekta; GROUP: staratelji dece grupe + vaspitači grupe; GUARDIAN: konkretno članstvo) → `announcement_recipients` snapshot (`snapshot_at`); `PublishJob` (worker) za zakazane `publish_at`.
- `E10-B03` `CurrentRightsCheck` pri čitanju: primalac mora biti u snapshot-u **i** i dalje imati aktivno članstvo i (za GROUP/LOCATION/GUARDIAN) važeće pravo (potvrđena veza sa detetom u grupi / dodela); inače `404`.
- `E10-B04` `POST .../announcements/{id}/read` (eksplicitno; `read_at`), `GET .../announcements/{id}/recipients` (osoblje: ko je pročitao/nije; broj), `GET .../announcements/unread-count`.
- `E10-B05` Renderovanje: `body` čist tekst / ograničeni markdown → server vraća plain + klijenti escape-uju; nikakav HTML ne prolazi (sanitizacija na unosu).
- `E10-B06` Outbox `ANNOUNCEMENT_PUBLISHED` (announcementId, recipient membershipIds) → E11 notifikacije sa `dedup_key = announcement:{id}`; audit `ANNOUNCEMENT_PUBLISHED/ARCHIVED/UPDATED`.
- `E10-B07` (P1) Prilozi: `PUT .../announcements/{id}/attachments` (`file_id` iz E15, `kind` IMAGE/ATTACHMENT), `POST .../announcements/{id}/recipients/refresh` (dodaje nove primaoce po trenutnim pravilima).

**Mobile**
- `E10-M01` **Obaveštenja**: lista (nepročitano istaknuto), detalj (poziva `/read` pri otvaranju), prazno stanje; (P1) slike/prilozi kroz signed URL.
- `E10-M02` Vaspitač (P1, uz dozvolu): kreiranje obaveštenja za svoju grupu.

**Web**
- `E10-W01` **Announcements**: lista po statusu, editor (naslov, tekst, publika sa izborom objekata/grupa/staratelja, `publish_at`, `expires_at`), pregled čitanja (X od Y + lista), arhiviranje; (P1) prilozi.

**Database**
- `E10-D01` `V<n>__announcements.sql`: `announcements`, `announcement_audiences`, `announcement_recipients`; RLS + grantovi (`DELETE` na `announcement_audiences`). (`announcement_attachments` u E15 jer zavisi od `files`.)
- `E10-D02` Seed: dva objavljena obaveštenja (org + grupa), jedan nacrt.

### Acceptance criteria
1. Objava za GROUP „Bubamare” pravi snapshot samo staratelja te dece i vaspitača grupe; roditelj iz „Leptirića” dobija `404`.
2. Roditelj kome je veza opozvana posle objave → `404` iako je u snapshot-u; roditelj potvrđen posle objave → `404` dok se ne pozove refresh (P1).
3. `/read` postavlja `read_at`; ponovni poziv ne menja vreme; push isporuka ne menja `read_at`.
4. TEACHER može ciljati samo svoje grupe (`403` za tuđu); objava zahteva `ANNOUNCEMENT_PUBLISH` ili ADMIN/OWNER.
5. Telo sa `<script>` se čuva escape-ovano i vraća kao tekst; web render ne izvršava skript (e2e).
6. Zakazana objava se objavljuje u `publish_at` ± 1 min (worker test sa pomerenim satom).
7. Dva tenant-a: obaveštenje org B nevidljivo iz org A.

### Potrebni testovi
- Unit: `AudienceResolver`, `CurrentRightsCheck`, sanitizacija.
- Integration: 1–7; RLS negativni; E2E web (objava → čitanje na mobile shared mock-u → statistika).

### Definition of Done
§2.

---

## EPIC 11 – Notifications

**Cilj:** In-app inbox, outbox radnik, push (FCM/APNs), email (SES), retry/backoff, dedup, dead-letter, uklanjanje
nevažećih tokena, minimalan/opaque push payload, polling + push invalidacija; SSE kasnije.

**Prioritet:** P0 (inbox, outbox, push, email), P1 (podešavanja, SSE)
**Dependencies:** E02 (sesije za push tokene), E07–E10 (izvori događaja).

### Taskovi

**Backend (`com.vrticconnect.notification`, `com.vrticconnect.outbox`)**
- `E11-B01` `OutboxRelay` (worker proces/korutina sa `app_worker` pool-om): `SELECT ... FOR UPDATE SKIP LOCKED` nad `outbox_events WHERE status='PENDING' AND next_attempt_at <= now()`, dispatch po `event_type` ka `NotificationComposer`-ima; uspeh → `PROCESSED`; greška → `attempts++`, backoff (1, 5, 30 min, 2 h, 12 h), posle 6 → `DEAD` + alarm metrika.
- `E11-B02` `NotificationComposer` po događaju (ANNOUNCEMENT_PUBLISHED, SCHEDULE_CHANGED, ABSENCE_REPORTED/CANCELLED, ATTENDANCE_CHANGED [ako org podešavanje uključeno], SECURITY_*, GUARDIAN_CONFIRMED, ...) → `notifications` (`title_key`, `title_args` bez PII/health/teksta, `ref_entity_*`, `dedup_key`) uz ON CONFLICT DO NOTHING na `(recipient_user_id, dedup_key)` → `notification_deliveries` za svaki aktivan `device_push_tokens` (PUSH) i po pravilu EMAIL.
- `E11-B03` `PushSender` adapteri: FCM (HTTP v1) i APNs (token-based); payload `{type, refEntityType, refEntityId, organizationId, collapseKey}` + generički lokalizovani naslov; obrada odgovora: `Unregistered`/`BadDeviceToken` → `invalidated_at`; retry/backoff kroz `notification_deliveries`.
- `E11-B04` `EmailSender` (SES) za verifikaciju/reset/pozivnice (E02 već koristi interfejs) + SECURITY notifikacije; template-i na tri jezika; bez PII u subject-u.
- `E11-B05` API: `GET /me/notifications` (cursor), `POST /me/notifications/{id}/read`, `POST /me/notifications/read-all`, `GET /me/notifications/unread-count`, `PUT /me/devices/push-token` (`platform`, `token`, `appVersion`; vezuje za sesiju), `DELETE /me/devices/push-token`; logout uređaja invalidira token sesije.
- `E11-B06` Push invalidacija: događaji nose `collapseKey` (npr. `daily-overview:{groupId}:{date}`) da klijent osveži ekran; bez sadržaja.
- `E11-B07` (P1) `GET /api/v1/organizations/{organizationId}/events/stream` (SSE; auth kolačić/Bearer u zaglavlju, **nikad token u URL-u**; heartbeat; klijent radi resync posle reconnect-a; opoziv sesije zatvara stream).
- `E11-B08` (P1) `GET/PUT /me/notification-preferences` (po vrsti: push/email on/off; SECURITY se ne može isključiti).
- `E11-B09` Metrike i alarmi: outbox zaostatak, `DEAD` broj, push greške po kodu, vreme od događaja do `SENT`.

**Mobile**
- `E11-M01` Registracija push tokena posle prijave (FCM/APNs platformski adapter), traženje sistemske dozvole uz objašnjenje; brisanje pri odjavi.
- `E11-M02` **Inbox** ekran (lista, nepročitano, dodir → navigacija na entitet), badge broja.
- `E11-M03` Obrada push-a: samo invalidacija/keš refresh + navigacija posle otključavanja; nikakav sadržaj se ne prikazuje iz payload-a osim generičkog naslova.
- `E11-M04` Polling strategija (dnevni pregled 30–60 s aktivan ekran; inbox 60 s) + reaguje na `collapseKey`.

**Web**
- `E11-W01` Zvonce sa brojem nepročitanih, lista, oznaka pročitano; polling 60 s (P1: SSE).

**Database**
- `E11-D01` `V<n>__notifications.sql`: `notifications`, `device_push_tokens`, `notification_deliveries`; RLS politike (`notifications_recipient`, `device_tokens_owner`, worker politike `notifications_worker`, `device_tokens_worker`, `deliveries_worker`, `outbox_worker`, `users_worker_read`, `memberships_worker_read`) + grantovi (`DELETE` za `device_push_tokens` runtime-u; `outbox_events`, `notification_deliveries` worker-u).

### Acceptance criteria
1. Objava obaveštenja → `outbox_events` red u istoj transakciji (rollback poslovne transakcije → nema outbox reda).
2. Relay kreira tačno jedan `notifications` red po primaocu; ponovna obrada istog događaja (simulirani crash posle inserta) ne pravi duplikat (`dedup_key`).
3. Push slanje sa simuliranim `Unregistered` odgovorom → token `invalidated_at`; sledeći događaj ne pravi delivery za taj token.
4. Simulirana 5xx greška provajdera → `FAILED` sa `next_attempt_at` po backoff-u; posle 6 pokušaja `DEAD`.
5. Push payload ne sadrži ime deteta, tekst obaveštenja ni health podatke (assert nad payload-om u testu).
6. Korisnik vidi samo svoje notifikacije (RLS `notifications_recipient`); tuđi `id` → `404`.
7. Odjava uređaja → njegov push token invalidiran; `logout-all` invalidira sve.
8. (P1) SSE: zahtev sa tokenom u query stringu → `401`; opoziv sesije prekida stream ≤ 5 s.

### Potrebni testovi
- Unit: composer-i (i18n ključevi, args bez PII), backoff.
- Integration: 1–7 sa fake push/email adapterima; worker pod `app_worker` ulogom (RLS worker politike).
- RLS negativni: `notifications` tuđi korisnik; `outbox_events` runtime ne može SELECT/UPDATE.
- E2E: push registracija (instrumentirano po mogućnosti), inbox tok.

### Definition of Done
§2 + runbook za `DEAD` događaje u `docs/INFRASTRUCTURE.md`.

---

## EPIC 12 – Calendar

**Cilj:** Kalendarski događaji (izlet, fotografisanje, predstava, praznik, sastanak, neradni dan, ostalo), celodnevni i sa vremenom, opseg org/objekat/grupa, veza sa `closure_days`, (sa E15) zahtev saglasnosti za izlet.

**Prioritet:** P1
**Dependencies:** E03, E06, E07 (neradni dani), E11; E15 za saglasnosti.

### Taskovi

**Backend (`com.vrticconnect.calendar`)**
- `E12-B01` `GET /organizations/{organizationId}/calendar-events?from=&to=&groupId=&locationId=` (opseg po ulozi: roditelj vidi org + objekat + grupe svoje dece), `POST`, `GET/PATCH/DELETE .../calendar-events/{id}` (`If-Match`), validacija (`all_day` vs `starts_at/ends_at`, `timezone`), audit.
- `E12-B02` `CLOSURE` sinhronizacija: kreiranje/brisanje događaja tipa `CLOSURE` kreira/briše `closure_days` za opseg datuma (org ili objekat) i pokreće recompute plana (E07).
- `E12-B03` Outbox `CALENDAR_EVENT_CREATED/UPDATED` → notifikacija primaocima po opsegu (E11).
- `E12-B04` (sa E15) `requires_consent_policy_id` → `CONSENT_REQUEST` notifikacije starateljima dece u opsegu; `GET .../calendar-events/{id}/consent-status` (po detetu, osoblje).
- `E12-B05` `FeatureGate("calendar")`.

**Mobile**
- `E12-M01` **Kalendar** (mesečni + lista), detalj događaja, (sa E15) dugme „Daj saglasnost za izlet”.

**Web**
- `E12-W01` **Calendar**: mesečni prikaz, forma (tip, opseg, celodnevni/vreme, zona), status saglasnosti po detetu.

**Database**
- `E12-D01` `V<n>__calendar.sql`: `calendar_events` (kolona `requires_consent_policy_id` bez FK do E15); RLS + grantovi.

### Acceptance criteria
1. Događaj sa `all_day = false` bez `starts_at` → `422`.
2. `CLOSURE` događaj 2 dana za objekat → dva `closure_days` reda; `daily_plans` dece tog objekta → `CLOSURE`; brisanje događaja vraća planove.
3. Roditelj vidi događaj svoje grupe, ne vidi događaj druge grupe (`404` na detalj).
4. Flag `calendar` isključen → `404` na sve rute.
5. Dva tenant-a izolovana.

### Potrebni testovi
- Unit: validacija, mapiranje CLOSURE → datumi.
- Integration + RLS negativni + E2E web.

### Definition of Done
§2.

---

## EPIC 13 – Meals

**Cilj:** Dnevni/nedeljni jelovnik po organizaciji ili objektu sa četiri obroka i informativnim alergenskim oznakama.

**Prioritet:** P1
**Dependencies:** E03, E11 (opciono).

### Taskovi

**Backend (`com.vrticconnect.meal`)**
- `E13-B01` `GET /organizations/{organizationId}/menus?from=&to=&locationId=` (roditelj: samo `is_published`; objekat deteta), `PUT .../menus/{date}` (upsert dana sa stavkama po `meal_slot`; `If-Match`; `locationId?`), `POST .../menus/{date}/publish`, `POST .../menus/copy-week` (`fromWeekStart`, `toWeekStart`), audit.
- `E13-B02` Validacija `meal_slot` ∈ BREAKFAST/SNACK_AM/LUNCH/SNACK_PM; `allergen_tags` iz allowlist-e (informativno; UI napomena „nije medicinska preporuka”).
- `E13-B03` `FeatureGate("meals")`; opciona nedeljna notifikacija „objavljen jelovnik” (outbox).

**Mobile**
- `E13-M01` **Jelovnik** (danas + nedelja, po obroku, alergenske oznake).

**Web**
- `E13-W01` **Meals**: nedeljna tabela (dan × obrok), stavke, alergeni, kopiranje nedelje, objava.

**Database**
- `E13-D01` `V<n>__meals.sql`: `menu_days`, `menu_items`; RLS + grantovi (`DELETE` na `menu_items`).

### Acceptance criteria
1. Dva različita snack slota se nezavisno čuvaju i prikazuju.
2. Neobjavljen jelovnik nevidljiv roditelju; objavljen vidljiv.
3. Jelovnik objekta A nevidljiv roditelju deteta iz objekta B (isti tenant).
4. Kopiranje nedelje pravi nove redove sa `is_published = false`.

### Potrebni testovi
Unit validacija; integration; RLS negativni; E2E web.

### Definition of Done
§2.

---

## EPIC 14 – Messaging

**Cilj:** Razgovori roditelj↔vaspitači i roditelj↔administracija o jednom detetu, serverski upravljani učesnici, nepročitano, idempotentno slanje, pristup zavisan od deteta i učešća.

**Prioritet:** P1
**Dependencies:** E04, E05, E11.

### Taskovi

**Backend (`com.vrticconnect.messaging`)**
- `E14-B01` `POST /organizations/{organizationId}/conversations` (`kind`, `childId`, `subject?`; server izračuna učesnike: PARENT_TEACHER = potvrđeni staratelji + vaspitači sa važećom dodelom grupe deteta; PARENT_ADMIN = staratelji + ADMIN/OWNER), `GET .../conversations` (samo gde sam učesnik sa `left_at IS NULL`; sortirano po `last_message_at`; unread), `GET .../conversations/{id}`, `POST .../conversations/{id}/close` (osoblje).
- `E14-B02` `POST .../conversations/{id}/messages` (`clientMessageId`, `body` 1–4000; UNIQUE → ponovno slanje vraća postojeću poruku), `GET .../conversations/{id}/messages` (cursor unazad), `PATCH .../messages/{id}` (izmena ≤ 15 min, `edited_at`), `DELETE` (meko), `POST .../conversations/{id}/read` (`last_read_message_id`).
- `E14-B03` `ParticipantMaintenance` (reaguje na outbox događaje `TEACHER_ASSIGNED/UNASSIGNED`, `GUARDIAN_REVOKED`, `ENROLLMENT_CHANGED`): postavlja `left_at` / dodaje nove učesnike; pristup uvek proverava i trenutno pravo nad detetom (`ChildScope`).
- `E14-B04` Outbox `MESSAGE_SENT` → notifikacija `MESSAGE` ostalim učesnicima (bez teksta poruke); `FeatureGate("messaging")`; rate limit slanja; audit samo metapodaci (`CONVERSATION_CREATED`, `MESSAGE_DELETED`) – **tekst poruka se ne loguje**.

**Mobile**
- `E14-M01` **Poruke**: lista razgovora (unread), razgovor (paginacija, slanje sa optimističkim prikazom i `clientMessageId`, retry), novi razgovor (izbor deteta + vaspitači/uprava).

**Web**
- `E14-W01` **Messages** (administracija): inbox PARENT_ADMIN razgovora, odgovor, zatvaranje.

**Database**
- `E14-D01` `V<n>__messaging.sql`: `conversations`, `conversation_participants`, `messages`, FK `conversation_participants_last_read_fk`; RLS + grantovi.

### Acceptance criteria
1. Klijent ne može zadati učesnike (polje se ignoriše/`422`); učesnici odgovaraju pravilima.
2. Ista poruka sa istim `clientMessageId` dva puta → jedna poruka, oba odgovora `201/200` sa istim `id`.
3. Vaspitač čija dodela istekne → `left_at` postavljen; `GET` razgovora → `404`; roditelj sa opozvanom vezom isto.
4. Roditelj deteta X ne vidi razgovor o detetu Y (`404`).
5. Notifikacija `MESSAGE` nema `body` u `title_args`; logovi ne sadrže tekst poruke.
6. Flag `messaging` isključen → `404`.

### Potrebni testovi
Unit (učesnici, izmena rok); integration 1–6; RLS negativni; E2E mobile shared.

### Definition of Done
§2.

---

## EPIC 15 – Photos & Consent

**Cilj:** Privatni fajlovi (upload autorizacija, quarantine, MIME provera, limiti, kompresija, thumbnails, EXIF uklanjanje,
malware scan, kratkotrajni signed URL/proxy), fotografije sa publikom, obavezno označavanje prepoznatljive dece, politike i
odluke o saglasnosti sa dokazima i povlačenjem, profil fotografija deteta i prilozi obaveštenja.

**Prioritet:** P1
**Dependencies:** E05, E06, E10, E11; infrastruktura S3 (E18 staging).

### Taskovi

**Backend (`com.vrticconnect.file`, `com.vrticconnect.photo`, `com.vrticconnect.consent`)**
- `E15-B01` Files: `POST /organizations/{organizationId}/files` (`purpose`, `declaredMime`, `sizeBytes`, `originalFilename` sanitizovan) → `files` `PENDING_UPLOAD` + pre-signed PUT (privatni bucket, prefiks `org/{org}/quarantine/{uuid}`, ≤ 5 min, content-length-range), `POST .../files/{id}/complete` → `QUARANTINED` + outbox `FILE_UPLOADED`; `GET .../files/{id}` (metapodaci), `GET .../files/{id}/content?variant=` → provera prava po `purpose` (profil: `ChildScope`; galerija: publika + status; prilog: primalac obaveštenja) → redirect na signed GET (≤ 5 min) ili proxy stream; `DELETE` (meko + storage lifecycle).
- `E15-B02` `FileProcessingWorker` (`app_worker`): `SCANNING` → MIME sniffing (`detected_mime` mora odgovarati allowlist-i), limit veličine, malware scan (ClamAV ili servis – adapter), EXIF strip, HEIC→JPEG, kompresija, `file_variants` (`THUMB_SM` 200 px, `THUMB_MD` 600 px, `WEB` 1600 px) u `org/{org}/ready/...` → `READY` + `sha256` ili `REJECTED` (razlog). Kvota organizacije iz `plans.limits.storage_gb`.
- `E15-B03` Photos: `POST .../photos` (`fileId` READY sa `purpose = GALLERY_PHOTO`, `caption`, `takenOn`, `groupId?`) → `DRAFT`; `PUT .../photos/{id}/audiences` (GROUP/CHILD/GUARDIAN); `PUT .../photos/{id}/tagged-children` (osoblje označava **svu** prepoznatljivu decu); `POST .../photos/{id}/publish` → `ConsentGate` proverava za **svako** označeno dete važeću `GRANTED` odluku za svrhu (`PHOTO_GROUP_SHARING` ako publika sadrži GROUP; inače `PHOTO_INTERNAL_GALLERY`) → `422` `CONSENT_MISSING` sa listom `childId`; zahteva `PHOTO_PUBLISH`/ADMIN/OWNER; `POST .../photos/{id}/withdraw` (`reason`); `GET .../photos?groupId=&childId=` (po publici + trenutna prava + status `PUBLISHED`); outbox `PHOTO_PUBLISHED` → notifikacija starateljima publike; audit `PHOTO_PUBLISHED/WITHDRAWN`.
- `E15-B04` Consent: `GET/POST /organizations/{organizationId}/consent-policies` (svrha, verzija, jezik, tekst → `wording_hash = sha256`; `retired_at`), `GET .../children/{childId}/consents` (stanje po svrsi), `POST .../children/{childId}/consents` (`policyId`, `decision`; staratelj CONFIRMED + `can_give_consent`; `evidence` {ip, userAgent, sessionId, wordingHash}), `POST .../consents/{id}/withdraw` → `ConsentWithdrawalHandler`: sve `PUBLISHED` fotografije sa tim detetom označenim → `WITHDRAWN` (podešavanje: cela fotografija ili samo šira publika – ADR), varijante prestaju da se serviraju, outbox `CONSENT_WITHDRAWN`; konflikt dva staratelja → restriktivnije (PRODUCT_SPEC 9.11).
- `E15-B05` Profil fotografija deteta: `PUT .../children/{childId}/photo` (`fileId` purpose `CHILD_PROFILE_PHOTO`); prilozi obaveštenja (E10-B07).
- `E15-B06` `FeatureGate("photos")`; audit `FILE_ACCESSED` agregirano (dnevni brojač po korisniku/fajlu) da se ne preplavi log; **signed URL-ovi se ne loguju**.

**Mobile**
- `E15-M01` Vaspitač: **Otpremi fotografije** (kamera/galerija, kompresija na uređaju pre upload-a, izbor grupe, označavanje dece iz liste grupe – bez prepoznavanja lica, publika, status obrade, objava ili „čeka odobrenje”).
- `E15-M02` Roditelj: **Fotografije** (grid po detetu/grupi preko signed URL-ova sa kratkim kešom u memoriji; **bez offline galerije**), **Saglasnosti** (lista politika sa tekstom, odluka, povlačenje sa upozorenjem o ograničenju već preuzetih kopija).

**Web**
- `E15-W01` **Photos**: moderacija nacrta, označavanje, publika, objava (prikaz dece bez saglasnosti), povlačenje; **Settings → Saglasnosti** (politike, verzije, tekst po jeziku, statistika odluka).

**Database**
- `E15-D01` `V<n>__files_photos_consent.sql`: `files`, `file_variants`, `photos`, `photo_audiences`, `photo_tagged_children`, `consent_policies`, `consent_decisions`, `announcement_attachments`; `ALTER TABLE app.children ADD CONSTRAINT children_photo_fk`; `ALTER TABLE app.announcement_attachments ADD CONSTRAINT announcement_attachments_file_fk`; `ALTER TABLE app.calendar_events ADD FOREIGN KEY (requires_consent_policy_id, organization_id)` (ako je E12 već merge-ovan); RLS + grantovi (`DELETE` na `photo_audiences`).
- `E15-D02` Seed: politike saglasnosti (tri jezika), odluke za deo dece (uključujući jedno bez saglasnosti za test blokade).

### Acceptance criteria
1. Fajl nikada nema javni URL: bucket policy blokira public read (infra test); `GET .../files/{id}/content` bez prava → `404`; signed URL ističe ≤ 5 min.
2. Upload sa `declared_mime = image/jpeg` ali sadržajem PDF-a → `REJECTED`; EXIF GPS uklonjen iz `READY` varijante (assert na bajtovima).
3. **Photo audience/consent**: fotografija sa publikom GROUP i označeno dete bez `PHOTO_GROUP_SHARING` saglasnosti → publish `422` sa tim `childId`; posle `GRANTED` odluke → `200`; roditelj deteta iz grupe vidi fotografiju; roditelj iz druge grupe → `404`; roditelj deteta koje **nije** označeno ali jeste u publici GROUP vidi fotografiju (publika ≠ saglasnost).
4. Povlačenje saglasnosti → fotografija `WITHDRAWN`, `GET .../files/{id}/content?variant=THUMB_SM` → `404` za sve, nema novih notifikacija.
5. Thumbnails imaju iste dozvole kao original (test na sve tri varijante).
6. Odluka sadrži `evidence.wordingHash` jednak `consent_policies.wording_hash`; povučena politika (`retired_at`) ne važi za novu objavu.
7. Dva tenant-a: fajl/fotografija org B nedostupni iz org A; `storage_key` prefiks uvek nosi `org/{org}`.
8. Flag `photos` isključen → `404`.

### Potrebni testovi
- Unit: `ConsentGate`, mapiranje publika→svrha, sanitizacija imena fajla.
- Integration: 1–8 sa lokalnim S3-kompatibilnim servisom (MinIO u Testcontainers) i fake scanner-om.
- RLS negativni: osam tabela.
- E2E: upload → obrada → označavanje → blokada → saglasnost → objava (web + mobile shared).

### Definition of Done
§2 + `docs/SECURITY.md` odeljak o fajlovima (bucket policy, lifecycle, scan) + ADR o obimu povlačenja.

---

## EPIC 16 – Admin Dashboard & Reports

**Cilj:** Web dashboard sa ključnim brojevima (ista definicija brojača kao backend), tenant switcher, ekrani po brief-u §23,
izveštaji (P1) sa izvozom uz `REPORT_EXPORT`.

**Prioritet:** P0 (dashboard, navigacija, stanja), P1 (izveštaji, izvoz)
**Dependencies:** raste sa E03–E15.

### Taskovi

**Backend (`com.vrticconnect.report`)**
- `E16-B01` `GET /organizations/{organizationId}/dashboard?date=` → po objektu/grupi: očekivano/prisutno/otišlo/odsutno/nije stiglo/kasni/neplanirano (koristi `DailyOverviewCalculator` iz E08 – **ista definicija**), broj aktivne dece, otvorenih pozivnica, nepotvrđenih staratelja, kasnih izmena danas, nepročitanih obaveštenja (agregat).
- `E16-B02` (P1) Izveštaji: `GET .../reports/attendance?from=&to=&groupId=` (dani, posete, ukupno sati boravka po detetu), `GET .../reports/absences`, `GET .../reports/announcements/read-rate`, `GET .../reports/schedule-changes?lateOnly=`; svi sa cursor paginacijom; `POST .../reports/{kind}/export` (CSV/PDF kao `files` `DATA_EXPORT`, signed URL, `REPORT_EXPORT`, audit `REPORT_EXPORTED` sa `purpose`); **bez zdravstvenih podataka** u bilo kom izveštaju.
- `E16-B03` Platform: `GET /platform/stats` (agregati bez PII: organizacije po statusu, ukupno dece/članstava, aktivnost po danu).

**Web**
- `E16-W01` **Dashboard** (kartice brojača po objektu/grupi, upozorenja: nepotvrđeni staratelji, otvorene pozivnice, kasne izmene, `DEAD` notifikacije za owner-a nije – to je platformski), brzi linkovi.
- `E16-W02` Zajednička infrastruktura: tabele sa cursor paginacijom, filteri iz allowlist-e, stanja (loading/empty/error/conflict `409` sa „osveži i ponovi”), forme sa validacijom identičnom serverskoj (`422` mapiranje na polja), `If-Match` handling, `Idempotency-Key` na kreiranju, keš po `[userId, organizationId]` sa čišćenjem pri promeni tenant-a, bez `dangerouslySetInnerHTML`.
- `E16-W03` (P1) **Reports**: prisustvo, odsustva, čitanost, kasne izmene; izvoz CSV/PDF.
- `E16-W04` Accessibility prolaz (WCAG 2.1 AA: tastatura, fokus, ARIA, kontrast) i responsivnost za tablet.
- `E16-W05` Playwright e2e paket koji pokriva glavne tokove svih ekrana (prijava → tenant → svaki ekran renderuje se bez grešaka na tri jezika).

**Mobile**
- `E16-M01` Nema posebnih taskova (admin funkcije su web); opciono kartica „danas u vrtiću” za owner-a na mobilnom (P2).

**Database**
- `E16-D01` Nema novih tabela; po potrebi indeksi za izveštaje (`attendance_days (organization_id, attendance_date)` već postoji kroz grupu; dodati `(organization_id, child_id, attendance_date)` ako profilisanje pokaže) u `V<n>__report_indexes.sql`.

### Acceptance criteria
1. Dashboard brojači za grupu jednaki `daily-overview` brojačima za istu grupu i datum (test parnosti).
2. Izveštaj prisustva za 30 dece × 30 dana vraća se < 2 s (cilj) i ne sadrži health polja.
3. Izvoz zahteva `REPORT_EXPORT`; fajl je privatan i ističe; audit sa `purpose`.
4. Promena tenant-a u switcher-u briše sve keširane upite prethodnog tenant-a (test: podaci org A se ne pojavljuju u org B ekranu ni na tren).
5. Svi ekrani prolaze axe-core provere bez kritičnih nalaza.

### Potrebni testovi
Unit (mapiranje `422` na polja, keš ključevi); integration (izveštaji, parnost brojača); E2E Playwright (svi ekrani, tri jezika, a11y).

### Definition of Done
§2 + `docs/WEB_ARCHITECTURE.md` usklađen (keš, stanja, bezbednost).

---

## EPIC 17 – Audit & Security

**Cilj:** Konsolidacija svega što svaki EPIC već radi u svom delu: kompletna lista audit akcija, pregled audit loga,
eksterna write-once kopija, privacy zahtevi (pristup/izvoz/brisanje/ispravka), erasure ledger i ponovna primena posle
restore-a, retencija i legal hold, sanitizovani logovi, skeniranje, penetracioni test, sigurnosni pregled RLS politika.
**Proteže se kroz sve faze** – svaki EPIC ima svoje RLS/audit taskove; ovde su prečni (cross-cutting) taskovi.

**Prioritet:** P0 (audit pregled, privacy osnove, RLS test paket, skeniranje), P1 (retencija job, eksterna kopija loga, pen-test), stalno.
**Dependencies:** E02 (nosilac), zatim svaki EPIC.

### Taskovi

**Backend (`com.vrticconnect.audit`, `com.vrticconnect.privacy`)**
- `E17-B01` `AuditWriter` (E02 uvodi; ovde finalizacija): allowlist `metadata` ključeva po akciji, obavezan `purpose` za `HEALTH_READ`, `SUPPORT_ACCESS_*`, `REPORT_EXPORTED`, `DATA_EXPORT_*`; `request_id`; `result` SUCCESS/DENIED/FAILED (DENIED se piše i za `403`).
- `E17-B02` `GET /organizations/{organizationId}/audit-log?from=&to=&action=&entityType=&entityId=&actorUserId=` (OWNER/ADMIN; cursor; `metadata` filtrirano), `GET /platform/audit-log` (platformske akcije), `GET .../children/{childId}/audit-log` (ko je pristupao detetu – uključujući health).
- `E17-B03` `AuditShipper` (worker): stream novih `audit_log` redova u write-once eksterni storage (S3 Object Lock ili ekvivalent) sa hash lancem (`prev_hash`) – tamper-evidence za privilegovane aktere; dokumentovano da append-only trigger **ne** štiti od DBA.
- `E17-B04` Privacy: `POST /organizations/{organizationId}/privacy-requests` (staratelj za sebe/svoje dete; osoblje u ime lica), `GET .../privacy-requests`, `POST .../privacy-requests/{id}/verify-identity`, `POST .../privacy-requests/{id}/complete|reject`; `DataExportService` (JSON/CSV paket: korisnik, deca za koje je CONFIRMED staratelj, njihovi rasporedi/prisustvo/odsustva/saglasnosti; **bez** podataka druge dece i privatnih podataka drugog staratelja; `files` `DATA_EXPORT` sa rokom); `ErasureService` (anonimizacija/brisanje po subjektu uz zadržavanje obaveznih zapisa; upis u `erasure_ledger`; provera `legal_holds`); `ErasureReplayJob` (posle restore-a ponovo primenjuje ledger; `reapplied_at`).
- `E17-B05` Retencija: `GET/PUT /platform/retention-policies` (podrazumevane) i `GET/PUT /organizations/{organizationId}/retention-policies` (override); `RetentionJob` (worker, dnevno): tokeni/sesije, `login_attempts`, `idempotency_keys`, notifikacije, poruke, fotografije, prisustvo – po `entity_type` uz `legal_holds` proveru; **suvi režim** (dry-run) izveštaj pre prvog produkcionog pokretanja; `POST/DELETE .../legal-holds`.
- `E17-B06` Sanitizacija logova: Logback filter/maskiranje (Authorization, Cookie, Set-Cookie, token polja, `password`, signed URL query, health payload), CI test koji pokreće ceo test set i grep-uje log izlaz za obrasce tokena/lozinki/health fixture-a.
- `E17-B07` Bezbednosni testni paket (`backend/src/test/kotlin/security/`): dva tenant-a nad **svakom** tenant tabelom (generisan iz liste u šemi), prazan kontekst, pogrešan `organization_id` u INSERT/UPDATE, pool leakage (`maximumPoolSize = 1`), worker uloga ne može čitati tenant tabele bez politike, `app_runtime` ne može DDL/DELETE gde nije dozvoljeno, `deny_modification` trigger-i.
- `E17-B08` Reauth i MFA pokrivenost: lista osetljivih endpointa (SECURITY.md) i test da svaki zahteva `RequiresRecentAuth`.
- `E17-B09` Rate limiting matrica (login, reset, pozivnice, upload, poruke, komande) i `429` testovi.
- `E17-B10` Dependency scan (OWASP/Gradle dependency-check ili Snyk), secret scan (gitleaks) u CI kao blokirajući koraci; SAST (Semgrep) po mogućnosti; SBOM.
- `E17-B11` Penetracioni test (eksterni ili interni, pre produkcije): opseg auth, tenant izolacija, fajlovi, IDOR; nalazi i ispravke dokumentovani.
- `E17-B12` Incident runbook (SECURITY.md): opoziv sesija svih korisnika organizacije, rotacija ključeva (KMS), kill switch funkcija, obaveštavanje.

**Mobile**
- `E17-M01` Provera: bez tokena/PII u logovima (lint pravilo + test), `CrashReporter` scrubber, screenshot zaštita na osetljivim ekranima (`FLAG_SECURE` / iOS ekvivalent) za zdravlje i saglasnosti, brisanje offline baze na opoziv.
- `E17-M02` (P1) Certificate pinning sa planom rotacije; root/jailbreak detekcija samo kao upozorenje.

**Web**
- `E17-W01` CSP (bez `unsafe-inline`), `Referrer-Policy`, `frame-ancestors 'none'`; audit da nema `dangerouslySetInnerHTML`; ESLint pravila za escape; dependency scan za npm.
- `E17-W02` **Settings → Audit log** (pregled/filter), **Settings → Privatnost** (privacy zahtevi, izvozi, brisanja, legal hold), **Platform → Retention**.

**Database**
- `E17-D01` `V<n>__privacy.sql`: `privacy_requests`, `data_exports`, `erasure_ledger` (append-only), `retention_policies`, `legal_holds`; RLS politike iz šeme (`privacy_requests_access`, `data_exports_access`, `erasure_ledger_access`, `retention_read/write`) + grantovi; podrazumevane `retention_policies` (organization NULL) kao repeatable referentni podaci (**predlozi**, potvrditi pravno).
- `E17-D02` Skripta za proveru RLS pokrivenosti (`docs/database/tests/rls_coverage.sql`): svaka tabela sa `organization_id` mora imati `ENABLE + FORCE` i bar jednu politiku; pokreće se u CI posle migracija.
- `E17-D03` (P2, van 18 EPIC-a) `documents`, `document_versions`, `document_requests`, `document_responses` – planirano posebno; ne uvodi se ovde.

### Acceptance criteria
1. `rls_coverage.sql` prijavljuje 0 tabela bez RLS-a posle svih migracija.
2. Bezbednosni paket (E17-B07) prolazi za sve tenant tabele; dodavanje nove tabele bez RLS-a obara CI.
3. Audit pregled ne vraća `metadata` van allowlist-e; `HEALTH_READ` bez `purpose` nemoguć (`422`).
4. Privacy izvoz za staratelja ne sadrži nijedan podatak deteta za koje nije CONFIRMED (test sa dva staratelja i dva deteta).
5. Brisanje korisnika → `erasure_ledger` red; simulirani restore backup-a + `ErasureReplayJob` → podaci ponovo obrisani, `reapplied_at` postavljen.
6. Retencija u dry-run režimu izveštava tačne brojeve; sa `legal_holds` entitet preskočen.
7. CI grep logova nad celim test setom: 0 pogodaka za tokene/lozinke/health payload.
8. Secret scan i dependency scan blokiraju merge na kritične nalaze.

### Potrebni testovi
Sve iz E17-B07; integration za privacy/retenciju; CI provere (B06, B10, D02); pen-test izveštaj (P1).

### Definition of Done
§2 + `docs/SECURITY.md` kompletan (audit lista, osetljivi endpointi, rate limiti, incident runbook, ograničenja append-only) + ADR o eksternoj kopiji audit loga.

---

## EPIC 18 – Production Deployment

**Cilj:** DEV/STAGING/PRODUCTION okruženja na AWS EU, immutable image, jedan migracioni job, staging pre produkcije, smoke
testovi, expand/contract, rollback bez slepog vraćanja migracija, backup/PITR sa vežbom oporavka (RPO 15 min, RTO 4 h),
monitoring i alarmi, bez tajni u repou, troškovna procena.

**Prioritet:** P0 (staging od E02, produkcija pre pilota), stalno.
**Dependencies:** E01; staging paralelno sa E02+; produkcija posle E11 i E17 (P0 deo).

### Taskovi

**Backend / Infrastruktura (`infrastructure/`)**
- `E18-B01` Dockerfile (multi-stage, non-root, distroless/JRE minimal, healthcheck); image tagovan git SHA-om; SBOM.
- `E18-B02` IaC (Terraform ili CDK – odluka u INFRASTRUCTURE.md/ADR): VPC (privatne podmreže za bazu), app servis (ECS Fargate ili Lightsail container – proveriti cenu i ograničenja; **bez Kubernetes-a**), RDS PostgreSQL 17 (šifrovan, Multi-AZ za prod, automatski backup + PITR, `app_owner/app_runtime/app_worker` uloge kroz `00_roles.sql` job), privatni S3 (block public access, Object Lock bucket za audit kopiju, lifecycle za quarantine), KMS ključevi (health, MFA, S3), SES (domen verifikovan, DKIM), Secrets Manager/SSM za tajne, CloudWatch logovi/metrike/alarmi, ALB/reverse proxy sa TLS (ACM) koji servira SPA (`/`) i API (`/api`) sa istog origin-a, CloudFront po potrebi za statiku.
- `E18-B03` Migracioni job: odvojen ECS task/komanda `migrate` sa `app_owner` kredencijalima; **tačno jedan** job po deploy-u (lock), aplikacija se pokreće tek posle uspeha; aplikacija koristi samo `app_runtime`/`app_worker`.
- `E18-B04` Deploy pipeline: CI (E01) → build image → push (ECR) → deploy staging → migracija → smoke (`/health/ready`, prijava test naloga na staging sa sintetičkim podacima, `GET /me`, dnevni pregled) → ručno odobrenje → deploy production (isti image) → migracija → smoke → posmatranje.
- `E18-B05` Rollback strategija: expand/contract migracije (nova kolona nullable → backfill → switch → uklanjanje u kasnijoj verziji); rollback aplikacije = prethodni image; migracije se **ne** vraćaju slepo (dokumentovan postupak za contract fazu).
- `E18-B06` Backup/DR: RDS automatski backup (retention ≥ 7 d) + PITR; nedeljni snapshot; S3 versioning + replikacija za `ready` prefiks; **vežba oporavka** na staging-u (restore u novu instancu, `ErasureReplayJob`, merenje RTO) dokumentovana pre pilota; ciljevi RPO 15 min / RTO 4 h se **dokazuju** vežbom.
- `E18-B07` Monitoring i alarmi: latencija p95 po ruti, 5xx stopa, saturacija HikariCP pool-ova, outbox zaostatak i `DEAD`, push greške, RLS odbijanja (metrika iz `DENIED` audit zapisa), refresh reuse događaji, backup neuspeh, disk/CPU baze, sertifikati; on-call kanal; SLO 99,5 % dashboard sa error budget-om.
- `E18-B08` Okruženja: `dev` (Docker Compose), `staging` (kao prod, manje resurse, sintetički seed dozvoljen), `production` (seed odbijen; `APP_ENV=production` isključuje dev `MailSender` i debug rute).
- `E18-B09` Troškovna procena za 10–50 vrtića (i skica za 500+) u `docs/INFRASTRUCTURE.md` – zamena stare procene (76–194 USD + 25–60 USD staging) stvarnim cenama izabranih servisa (uključujući Lightsail poređenje).
- `E18-B10` Statički web hosting: build SPA u CI, upload na S3/CloudFront ili servira reverse proxy; cache-busting; CSP zaglavlja na proxy-ju.
- `E18-B11` Mobile release: Android (Play Console interni track; potpisivanje ključem van repoa; FCM konfiguracija po okruženju), iOS (TestFlight; APNs ključevi; macOS CI runner ili dokumentovan gate „iOS build se radi ručno na X”), `APP_ENV` build varijante sa različitim API origin-om.

**Mobile**
- `E18-M01` Build varijante `dev/staging/prod`; crash reporting adapter povezan (bez PII); verzija aplikacije u `X-App-Version`; minimalna podržana verzija (server može vratiti `426 Upgrade Required`).

**Web**
- `E18-W01` Produkcioni build sa env konfiguracijom (API origin isti), source maps van javnog hosting-a, `robots` noindex za admin.

**Database**
- `E18-D01` Skripta pokretanja `00_roles.sql` na RDS-u kroz jednokratni job sa superuser-om iz Secrets Manager-a (nikad iz repoa); rotacija lozinki uloga; `pg_stat_statements` uključen; parametri (`work_mem`, `max_connections` usklađen sa pool-ovima).
- `E18-D02` Restore test skripta (`scripts/db/restore_drill.sh`): restore snapshot-a u novu instancu, verifikacija Flyway istorije, `rls_coverage.sql`, `ErasureReplayJob`, merenje vremena.

### Acceptance criteria
1. Isti image (SHA) deploy-ovan na staging pa produkciju; produkcija nikada ne dobija image koji nije prošao staging smoke.
2. Migracija se izvršava tačno jednom po deploy-u; paralelno pokretanje drugog job-a čeka/odustaje (lock test na staging-u).
3. Aplikacioni kontejner nema `app_owner` kredencijale (inspekcija env-a); `app_runtime` ne može DDL (test na staging-u).
4. S3 bucket-i: public access blokiran (AWS config rule); `GET` objekta bez potpisa → `403`.
5. Vežba oporavka na staging-u izvršena i dokumentovana: izmereni RPO i RTO u odnosu na ciljeve 15 min / 4 h; `ErasureReplayJob` ponovo primenio brisanja.
6. Alarmi testirani sintetičkim greškama (5xx, outbox DEAD, backup fail) i stigli na on-call kanal.
7. Nijedna tajna u repou (gitleaks čist); `.env.example` bez vrednosti.
8. Smoke test posle deploy-a prolazi (`/health/ready`, prijava, dnevni pregled) na staging i produkciji.
9. Seed pokušan sa `APP_ENV=production` → odbijen.

### Potrebni testovi
Smoke paket (automatizovan), restore drill (polu-automatizovan), IaC validacija (`terraform validate/plan` u CI), config rules (public access, encryption), load test osnovnih ruta na staging-u (ciljevi iz PRODUCT_SPEC §7.2).

### Definition of Done
§2 + `docs/INFRASTRUCTURE.md` (arhitektura, troškovi, runbook-ovi: deploy, rollback, restore, incident, rotacija tajni) + `docs/VALIDATION_REPORT.md` sa rezultatima stvarnih vežbi; **bez tvrdnje „production-ready”** – navodi se šta je dokazano, šta nije i zašto.

---

## Mapa obaveznih testnih scenarija

Brief §27 propisuje obavezne scenarije. Tabela mapira svaki na EPIC koji ga implementira i na mesto gde test živi.

| # | Scenario (brief §27) | Šta se dokazuje | EPIC (primarni) | EPIC (dodatno) | Test lokacija (planirano) |
|---|---|---|---|---|---|
| 1 | **Dva tenant-a** | korisnik org A nikada ne vidi ni ne menja podatke org B; INSERT/UPDATE sa tuđim `organization_id` odbijen; `404` bez otkrivanja | E17 (generički paket nad svim tenant tabelama) | E03, E05, E06, E07, E08, E09, E10, E12–E15 (po tabeli) | `backend/src/test/kotlin/security/TenantIsolationTest.kt` (parametrizovan listom tabela) |
| 2 | **Nepovezana deca** | roditelj vidi samo decu sa `CONFIRMED` vezom; `PENDING`/`REVOKED` ne daju pristup; roditelj ne može sam da se poveže | E05 | E07, E08, E09, E14, E15 (opseg deteta u svakom domenu) | `child/GuardianScopeTest.kt` |
| 3 | **Multi-tenant korisnik** | isti nalog sa različitim ulogama u A i B dobija prava tačno po članstvu u tekućem kontekstu; bez članstva `404` | E02 | E03 (switcher), E16 (čišćenje keša na web-u) | `auth/TenantContextTest.kt`, web Playwright `tenant-switch.spec.ts` |
| 4 | **Opoziv** (sesije, članstva, veze, dodele) | opoziv sesije trenutno invalidira access token; suspenzija članstva odmah blokira; opoziv veze/dodele odmah uklanja pristup; offline uređaj – pri sledećem povezivanju | E02 | E04, E05, E08 (offline), E11 (push token) | `auth/RevocationTest.kt`, `employee/MembershipRevocationTest.kt`, mobile `SyncEngineRevocationTest.kt` |
| 5 | **Teacher period** | vaspitač vidi grupu samo za datume unutar `[valid_from, valid_to]` i dok nije `revoked`; istorijski datumi van perioda odbijeni | E04 | E06, E07, E08, E09, E14 | `employee/GroupScopeTest.kt`, `attendance/TeacherPeriodTest.kt` |
| 6 | **Refresh replay** | ponovna upotreba potrošenog refresh tokena opoziva celu sesiju uključujući legitimni novi token; konkurentni refresh daje tačno jedan uspeh | E02 | – | `auth/RefreshRotationTest.kt` (uključujući paralelni test) |
| 7 | **Konkurentne attendance komande** | N paralelnih komandi za isto dete → tačno jedna primenjena, ostale `409`; idempotentni duplikati `200`; projekcija konzistentna sa događajima | E08 | – | `attendance/ConcurrentCommandsTest.kt`, `attendance/IdempotencyTest.kt` |
| 8 | **Offline konflikt** | offline komanda čija verzija ne odgovara → `CONFLICT`, ostaje u redu, nikakav automatski prepis; TTL blokira nove unose; tombstones brišu lokalne podatke | E08 | E02 (refresh pri reconnect-u), E04 (provera dodela) | mobile `shared/src/commonTest/offline/SyncEngineTest.kt` + backend `attendance/SyncBatchTest.kt` |
| 9 | **Photo audience / consent** | publika ≠ saglasnost: objava blokirana bez saglasnosti svakog označenog deteta; roditelj van publike `404`; povlačenje zaustavlja serviranje svih varijanti | E15 | E10 (prilozi), E12 (saglasnost za izlet) | `photo/ConsentGateTest.kt`, `file/FileAccessTest.kt` |
| 10 | **Pool context leakage** | posle COMMIT i posle ROLLBACK ista fizička konekcija nema tenant kontekst; upit bez konteksta vraća 0 redova | E02 | E17 (ponavlja se u paketu) | `core/db/PoolContextLeakageTest.kt` (`maximumPoolSize = 1`) |

Dodatni obavezni testovi koji proizilaze iz brief-a (nisu u §27 listi, ali su preduslov P0):

| Scenario | EPIC | Dokazuje |
|---|---|---|
| Invarijanta dnevnog pregleda `Expected = Present + Departed + Absent + NotArrived` i parnost backend/mobile | E08, E16 | ista definicija brojača na svim klijentima |
| Prioritet rasporeda 1–5 i bez retroaktivne izmene | E07, E09 | ispravno očekivanje po danu |
| Zdravstveni podaci: nikad u listi/push/logu; audit svakog čitanja; OWNER bez dozvole odbijen | E05, E11, E17 | odvojeni pristupni model |
| Announcement snapshot + trenutna prava | E10 | bivši član ne vidi, novi ne vidi bez refresh-a |
| Seed odbija produkciju; nema tajni u repou | E01, E18 | bezbedan deploy |
| Logovi bez lozinki/tokena/health/poruka (CI grep) | E02, E17 | sanitizacija |

---

## Prvi sledeći implementacioni task

**EPIC 02 – Authentication, Sessions & Permissions.**

Konkretan početni redosled unutar EPIC-a (svaki korak sa svojim testovima pre prelaska na sledeći):

1. `E02-B01`–`E02-B03` (hasher, tokeni, repozitorijumi u `auth_mode` transakcijama) + `E02-D02` (test fixture sa dva tenant-a).
2. `E02-B14` (`TenantContextPlugin` + `TenantTransaction`) i **odmah** `PoolContextLeakageTest` (§27 scenario 10) i `TenantContextTest` (scenario 3) – pre bilo kog poslovnog endpointa.
3. `E02-B06`, `E02-B07`, `E02-B08` (login, refresh sa atomskom rotacijom i reuse detekcijom, sesije/logout) + `RefreshRotationTest` (scenario 6) i `RevocationTest` (scenario 4).
4. `E02-B04`, `E02-B05`, `E02-B09` (pozivnica, verifikacija, reset) + `E02-B20` (dev mail).
5. `E02-B13` (auth plugin, CSRF, rate limiting) + `E02-B15` (matrica dozvola) + `E02-B16` (`/me/*`).
6. `E02-B10`–`E02-B12` (reauth, MFA, MFA politika).
7. `E02-B17`–`E02-B19` (čišćenje, audit/outbox, OpenAPI + SECURITY.md).
8. Paralelno: `E02-W01`–`E02-W04` (web cookie režim) i `E02-M01`–`E02-M06` (mobile bearer režim).

Uslov za početak: `docs/VALIDATION_REPORT.md` potvrđuje da skeleton iz EPIC 01 (V1 migracija na nativnom PostgreSQL-u, uloge, `/health/ready`, zatvorene auth rute) stvarno radi lokalno; ako nije potvrđeno, prvo se zatvara ta praznina (E01), pa tek onda E02.

Uslov za završetak: svi acceptance kriterijumi EPIC 02 (1–17) pokriveni automatizovanim testovima koji prolaze u CI na nativnom PostgreSQL 17, bez ijedne test-only prečice u produkcionom kodu, i sa `docs/VALIDATION_REPORT.md` ažuriranim stvarnim rezultatima.
