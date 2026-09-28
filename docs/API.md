# Vrtić Connect – REST API (ugovor)

> **Status:** projektni dokument. Mašinski čitljiv izvor istine je `docs/openapi.yaml` (OpenAPI 3.1, 167 operacija). Ovaj dokument je njegov pregled na srpskom; identifikatori (path-ovi, polja, kodovi, `operationId`) su na engleskom i moraju se poklapati sa YAML-om. Nazivi polja prate kolone iz `docs/database/schema.sql` u camelCase obliku.
>
> U trenutnom skeleton-u postoje `GET /health/live`, `GET /health/ready` i delimično implementirani `POST /auth/login`, `POST /auth/refresh`, `GET /auth/session` i `POST /auth/logout`; bez važeće sesije session endpoint vraća `401`. Sve ostalo je **projektovano, a nije implementirano** (v. poslednji odeljak).

Sadržaj:

1. [Konvencije](#1-konvencije)
2. [Pregled endpointa po domenu](#2-pregled-endpointa-po-domenu)
3. [Primeri ključnih tokova](#3-primeri-ključnih-tokova)
4. [Šta je projektovano, a nije implementirano](#4-šta-je-projektovano-a-nije-implementirano)

---

## 1. Konvencije

### 1.1 Base path i struktura

- Svi poslovni endpointi su pod `/api/v1`. Verzija je u path-u; nekompatibilne promene idu u `/api/v2`, kompatibilna proširenja (nova opciona polja, nove enum vrednosti tamo gde je to dokumentovano) ne menjaju verziju.
- `GET /health/live` i `GET /health/ready` su **van** `/api/v1` (server `/`) jer ih koristi infrastruktura, ne klijenti.
- Tri opsega:
  - **globalni** – `/auth/*` i `/me/*` (nema tenant konteksta; radi na globalnim tabelama `users`, `sessions`, `notifications`);
  - **platform** – `/platform/*` (SUPER_ADMIN / SUPPORT, `app.platform_mode()`);
  - **tenant** – `/organizations/{organizationId}/...` – svaki zahtev prvo prolazi proveru sesije, pa članstva u organizaciji, pa se tek onda postavlja transaction-local kontekst (`set_config('app.organization_id', …, true)`) i primenjuje RLS. Bez konteksta nema podataka.
- Resursi su imenice u množini, kebab-case (`closure-days`, `pickup-persons`); komande koje nisu čist CRUD su `POST .../{id}/<glagol>` (`publish`, `revoke`, `cancel`, `check-in`).
- JSON polja su camelCase i 1:1 sa kolonama iz šeme (`date_of_birth` → `dateOfBirth`, `is_late_change` → `isLateChange`).

### 1.2 Autentifikacija: web-cookie vs. mobile-bearer

| | Web admin (React SPA) | Mobile (KMP) |
|---|---|---|
| Security scheme | `cookieAuth` (`vc_access` i `vc_refresh`: HttpOnly, Secure, SameSite=Lax; `vc_csrf`: čitljiv, vezan za sesiju) | `bearerAuth` (`Authorization: Bearer <opaque access token>`) |
| Gde žive tokeni | samo u HttpOnly kolačićima, **nikad** u `localStorage` | Keychain / Keystore; nikad u preferences ni u logovima |
| Refresh | `POST /auth/refresh` bez tela (HttpOnly `vc_refresh` kolačić) | `POST /auth/refresh` sa `refreshToken` u telu, single-flight |
| CSRF | obavezan `X-CSRF-Token` + tačna provera `Origin` na svakom mutirajućem zahtevu | nije primenljivo |
| MFA | TOTP obavezan za OWNER, SUPER_ADMIN, SUPPORT | isto |

Sesijska strategija (opaque tokeni): access token traje 10 min i proverava se u bazi pri svakom zahtevu (trenutni opoziv); refresh token je rotirajući (idle 7 dana, apsolutno 30 dana), potroši se i zameni **atomski**; ponovna upotreba potrošenog refresh tokena opoziva celu porodicu sesije (`REUSE_DETECTED`). U bazi su samo hash-evi tokena.

Svaka autentifikovana operacija u YAML-u navodi obe šeme (`cookieAuth` i `bearerAuth`); server prihvata bilo koju. Operacije označene `x-requires-reauthentication` zahtevaju `POST /auth/reauthenticate` u poslednjih 10 min (inače `403 REAUTHENTICATION_REQUIRED`); operacije označene `x-requires-mfa` zahtevaju sesiju sa `mfaVerifiedAt`.

### 1.3 Format grešaka: `application/problem+json`

Sve greške (4xx/5xx) koriste RFC 9457 `Problem` šemu:

```json
{
  "type": "https://api.vrtic.example.test/problems/validation",
  "title": "Unprocessable Content",
  "status": 422,
  "detail": "VALIDATION_FAILED",
  "instance": "/api/v1/organizations/6f2b.../children",
  "errors": [
    { "field": "dateOfBirth", "code": "DATE_IN_FUTURE", "message": "validation.date.future" }
  ],
  "requestId": "01J8Q3Z9V6X5N8J2Q4W7R1T0YF"
}
```

- `detail` je **stabilan kod** (`VALIDATION_FAILED`, `ABSENCE_OVERLAP`, `VERSION_MISMATCH`, …), ne tekst za korisnika.
- `errors[].message` je i18n ključ; klijent prevodi. API ne vraća UI tekst.
- Dodatna polja: `currentVersion` (409 verzija), `conflictingIds` (preklapanja, nedostajuće saglasnosti), `retryAfterSeconds` (429).
- `requestId` je uvek prisutan i isti kao u `X-Request-Id` zaglavlju odgovora i u sanitizovanim logovima.

### 1.4 Paginacija

Sve liste su cursor-paginirane:

- query: `cursor` (opaque string iz prethodnog odgovora; izostaviti za prvu stranu) i `limit` (1–100, podrazumevano 50);
- odgovor: `{ "items": [...], "nextCursor": "..." | null }`.

Offset paginacija ne postoji (nestabilna je pri istovremenim izmenama i skupa na velikim tabelama). Cursor kodira sort ključ + id i važi samo za isti `sort` i iste filtere.

### 1.5 Filtriranje i sortiranje (allowlist)

- Svaki list endpoint navodi **eksplicitne** filtere (query parametre) i njihove tipove; nepoznati query parametri → `422 UNKNOWN_PARAMETER`.
- `sort` je enum po endpointu (npr. `familyName:asc`, `createdAt:desc`); prva vrednost je podrazumevana. Nema slobodnog `sort=<kolona>`.
- Pretraga (`search`) je prefiks po imenu, min. 2 znaka; bez slobodnog full-text upita u MVP-u.
- Opsezi datuma imaju maksimum (npr. 92 dana za raspored, 366 za kalendar) → `422 RANGE_TOO_LARGE`.

### 1.6 Verzionisanje resursa: `ETag` / `If-Match`

Za tabele koje imaju kolonu `version` (`children`, `child_health_profiles`, `schedule_day_overrides`, nedeljni pregled kao agregat, `absences`, `announcements`, `calendar_events`, `menu_days`):

- `GET` vraća `ETag: "<version>"` (npr. `ETag: "3"`);
- mutirajući `PUT`/`PATCH`/`DELETE` i komande koje menjaju stanje (`publish`, `archive`, `cancel`) zahtevaju `If-Match`;
- nema `If-Match` → `428 Precondition Required` (`IF_MATCH_REQUIRED`);
- neslaganje → `409 Conflict` sa `detail: VERSION_MISMATCH` i `currentVersion` – klijent ponovo učitava resurs i prikazuje konflikt; **nema** tihog prepisivanja (no last-write-wins).
- kreiranje resursa koji još ne postoji, a ima verziju (npr. zdravstveni profil, day override): `If-Match: "0"`.

Prisustvo ne koristi `If-Match`, već `expectedVersion` u telu komande (v. 1.7 i 3.3).

### 1.7 Idempotency

- `Idempotency-Key` (UUID, header) je **obavezan** na POST komandama sa spoljnim efektima (notifikacije, e-mail, storage): prijava odsustva, objava obaveštenja, kreiranje deteta/upisa/pozivnice, fotografije, upload autorizacija, saglasnosti, zahtevi za privatnost, platform kreiranja. Na ostalim POST-ovima je opcion.
- Ponovljen zahtev sa istim ključem i istim telom vraća **originalni** odgovor (status + telo iz `idempotency_keys`, čuva se 24 h); isti ključ sa drugim telom → `422 IDEMPOTENCY_KEY_REUSED`. Ključ je vezan za korisnika i opseg (`scope = 'POST /absences'`).
- Poruke koriste `clientMessageId` u telu (unique po konverzaciji); ponovno slanje vraća `200` sa originalnom porukom.
- Komande prisustva koriste `commandId` (UUID generisan na uređaju; `attendance_events.command_id UNIQUE`); replay vraća `200` sa `outcome: DUPLICATE`.

### 1.8 401 / 403 / 404 pravila (bez otkrivanja)

- `401` – nema/istekle/opozvane kredencijale. Mobilni klijent pokušava **jedan** single-flight refresh, pa tek onda vodi korisnika na prijavu.
- `403` – korisnik je autentifikovan i *vidi* resurs, ali operacija nije dozvoljena njegovoj ulozi/dozvoli (npr. vaspitač bez `ANNOUNCEMENT_PUBLISH` pokušava objavu; OWNER bez `CHILD_HEALTH_READ` čita zdravstveni profil; suspendovana organizacija).
- `404` – resurs ne postoji **ili** je van opsega pozivaoca: druga organizacija, dete bez potvrđene guardian veze, grupa van aktivne dodele vaspitača, tuđa sesija. Odgovor je **identičan** u oba slučaja; nikad `403` koji bi otkrio postojanje.
- Prijava i „zaboravljena lozinka” ne otkrivaju da li nalog postoji (generički `401 INVALID_CREDENTIALS`, uvek `202`).

### 1.9 409 / 422 / 429

- `409 Conflict` – poslovni konflikt: preklapanje perioda (`ABSENCE_OVERLAP`, `ENROLLMENT_OVERLAP`, `ASSIGNMENT_OVERLAP`), nedozvoljen prelaz stanja (`ANNOUNCEMENT_NOT_DRAFT`, `NO_OPEN_VISIT`, `ALREADY_CHECKED_IN`), duplikat (`SLUG_TAKEN`, `MENU_DAY_EXISTS`), nedostajuća saglasnost (`CONSENT_MISSING` + `conflictingIds`), verzija (`VERSION_MISMATCH` + `currentVersion`).
- `422 Unprocessable Content` – telo je sintaksno ispravno, ali semantički nevalidno (formati, min/max, obavezna polja, nepoznati parametri). Uvek sa `errors[]`.
- `400` se koristi samo za neparsabilan JSON.
- `429 Too Many Requests` – uvek sa `Retry-After` (sekunde) i `RateLimit-Limit`/`RateLimit-Remaining`.

### 1.10 Rate limiting

Slojevi: po IP, po nalogu (e-mail hash), po tenantu. Strože kante na `/auth/*` (login, forgot-password, resend-verification, preview pozivnice, TOTP verify) i na izdavanje signed URL-ova. Brute-force: `login_attempts` + privremeno zaključavanje (`423 ACCOUNT_LOCKED`). Konkretni pragovi su konfiguracija, ne deo ugovora.

### 1.11 Request id

Svaki odgovor nosi `X-Request-Id`; klijent može poslati svoj (UUID/ULID) i server ga preuzima ako je validan. Isti id je u `Problem.requestId`, u audit logu (`audit_log.request_id`) i u sistemskim logovima – bez toga se incidenti ne mogu korelirati, a logovi ne sadrže tokene, lozinke, signed URL-ove, medicinske podatke ni tela poruka.

### 1.12 Lokalizacija i vreme

- Zahtevi ne nose UI tekst; API vraća kodove i i18n ključeve (`Problem.errors[].message`, `Notification.titleKey` + `titleArgs`). Jedini slobodni tekstovi su korisnički sadržaji (imena, naslovi, tela obaveštenja/poruka, tekst saglasnosti). Bez auto-transliteracije imena.
- Jezik korisnika: `preferredLocale` (`sr-Latn`, `sr-Cyrl`, `en`); `Accept-Language` se ne koristi za poslovnu logiku.
- Vreme: trenuci su RFC 3339 u UTC (`2026-09-16T06:32:10Z`); kalendarski datumi `YYYY-MM-DD`; zidno vreme `HH:MM` interpretirano u IANA zoni organizacije (`organizations.timezone`, podrazumevano `Europe/Belgrade`); zona se šalje kao IANA string. Datum prisustva (`attendanceDate`) se određuje po zoni vrtića; promena zone ne menja istoriju.

### 1.13 Zdravstveni podaci i notifikacije

- Zdravstveni podaci (`child_health_profiles`) se vraćaju **isključivo** kroz `GET/PUT /children/{childId}/health`; ne postoje u listi/detalju deteta, izveštajima, notifikacijama ni logovima. Jedini izuzetak je boolean `hasCriticalHealthAlert` („otvori zdravstveni profil pre postupanja”) bez ikakvog medicinskog sadržaja.
- Čitanje zahteva `CHILD_HEALTH_READ` (ili potvrđenu guardian vezu sa `canViewHealth`) i obavezan `purpose` (query na GET, polje na PUT) koji se upisuje u audit (`HEALTH_READ`, `HEALTH_UPDATED`). Nije dostupno offline.
- Notifikacije sadrže samo `titleKey`, `titleArgs` (id-jevi, brojevi, datumi, ne-osetljiva imena), `refEntityType`/`refEntityId`. Push payload je minimalan/opaque i nije garantovan kanal.

### 1.14 Custom ekstenzije u YAML-u

Svaka operacija ima `x-priority` (P0 pilot, P1 proširenje, P2 kasnije), `x-status` (`designed`, `implemented`, `implemented-stub`), `x-roles` (OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT) i, gde treba, `x-permissions` (CHILD_HEALTH_READ, CHILD_HEALTH_WRITE, ATTENDANCE_CORRECT, ANNOUNCEMENT_PUBLISH, PHOTO_PUBLISH, BILLING_MANAGE, MEMBER_MANAGE, REPORT_EXPORT), `x-requires-reauthentication`, `x-requires-mfa`. Uloge pripadaju **članstvu** u organizaciji; SUPPORT je platform korisnik sa aktivnim `support_access_grants` zapisom (opseg, rok, audit, read-only kad je dovoljno). Feature flag nije autorizacija: server proverava dozvole nezavisno od flaga.

---

## 2. Pregled endpointa po domenu

Ukupno **167** operacija: P0 = 116, P1 = 51; implementirano = 2, stub (501) = 3, projektovano = 162. Kolona „Role/permisije”: uloge iz `x-roles`, `+ PERMISSION` iz `x-permissions`, u zagradama zahtevi `[MFA, reauth, If-Match, Idempotency-Key]`. Vaspitač uvek samo u okviru aktivne dodele grupi, roditelj samo za decu sa potvrđenom guardian vezom (inače `404`).

### Health (van /api/v1)

_Liveness/readiness probe van `/api/v1`; bez autentifikacije, bez tenant konteksta, bez tajni u odgovoru._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/health/live _(server `/`)_` | Liveness proba (proces radi); bez pristupa bazi. (`getHealthLive`) | javno | P0 | implementirano |
| `GET` | `/health/ready _(server `/`)_` | Readiness proba: konekcija ka bazi (runtime kredencijali) i stanje Flyway migracija. (`getHealthReady`) | javno | P0 | implementirano |

### Autentifikacija i sesije (/auth)

_Globalna autentifikacija i sesije. Opaque tokeni, rotirajući refresh sa detekcijom ponovne upotrebe, sesija po uređaju, TOTP MFA za platform admina, vlasnika i support._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/auth/invitations/{invitationToken}` | Pregled pozivnice iz e-mail linka (organizacija, uloga, da li je potrebna registracija). (`previewInvitation`) | javno | P0 | implementirano |
| `POST` | `/auth/register` | Registracija isključivo preko pozivnice; kreira nalog, prihvata pozivnicu, šalje verifikaciju e-maila i otvara sesiju. (`register`) | javno | P0 | implementirano |
| `POST` | `/auth/login` | Prijava e-mailom i lozinkom; mobilni dobija Bearer tokene, a web HttpOnly kolačiće + session-bound CSRF cookie. MFA je sledeći korak. (`login`) | javno | P0 | delimično implementirano |
| `POST` | `/auth/refresh` | Atomska Bearer rotacija refresh tokena; ponovna upotreba potrošenog tokena opoziva celu porodicu sesije. (`refreshSession`) | javno | P0 | delimično implementirano |
| `GET` | `/auth/session` | Bootstrap web sesije: korisnik i aktivna članstva; 401 bez važeće sesije. (`getCurrentSession`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT | P0 | delimično implementirano |
| `POST` | `/auth/logout` | Odjava tekuće sesije (uređaja), opoziv access/refresh tokena i brisanje web kolačića. Cookie režim zahteva CSRF + tačan Origin. (`logout`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT | P0 | delimično implementirano |
| `POST` | `/auth/logout-all` | Odjava sa svih uređaja (opoziv svih sesija korisnika). (`logoutAll`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT [reauth] | P0 | implementirano |
| `GET` | `/auth/sessions` | Lista aktivnih sesija (uređaja) korisnika sa oznakom tekuće. (`listSessions`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT | P0 | implementirano |
| `DELETE` | `/auth/sessions/{sessionId}` | Opoziv jedne sesije (udaljena odjava uređaja). (`revokeSession`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT | P0 | implementirano |
| `POST` | `/auth/verify-email` | Potvrda e-mail adrese tokenom iz linka (24 h, jednokratno). (`verifyEmail`) | javno | P0 | implementirano |
| `POST` | `/auth/resend-verification` | Ponovno slanje verifikacionog linka (rate limit). (`resendVerification`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT | P0 | implementirano |
| `POST` | `/auth/forgot-password` | Zahtev za reset lozinke; odgovor je isti bez obzira na postojanje naloga. (`forgotPassword`) | javno | P0 | implementirano |
| `POST` | `/auth/reset-password` | Postavljanje nove lozinke reset tokenom (15 min); opoziva sve sesije. (`resetPassword`) | javno | P0 | implementirano |
| `POST` | `/auth/reauthenticate` | Ponovna autentifikacija (lozinka + TOTP) pre osetljivih akcija; važi 10 min. (`reauthenticate`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT | P0 | implementirano |
| `POST` | `/auth/mfa/totp/setup` | Početak TOTP upisa (secret prikazan jednom). (`setupTotp`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT [reauth] | P0 | projektovano |
| `POST` | `/auth/mfa/totp/confirm` | Potvrda TOTP upisa prvim kodom; vraća recovery kodove. (`confirmTotp`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT | P0 | projektovano |
| `POST` | `/auth/mfa/totp/verify` | Drugi korak prijave: MFA izazov + TOTP/recovery kod → sesija. (`verifyTotp`) | javno | P0 | projektovano |
| `POST` | `/auth/mfa/recovery-codes/regenerate` | Regeneracija recovery kodova (stari se poništavaju). (`regenerateRecoveryCodes`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT [reauth] | P1 | projektovano |
| `POST` | `/auth/push-tokens` | Registracija FCM/APNs tokena uređaja vezanog za sesiju. (`registerPushToken`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT | P0 | projektovano |
| `DELETE` | `/auth/push-tokens/{pushTokenId}` | Brisanje registracije push tokena. (`deletePushToken`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT | P0 | projektovano |

### Sopstveni nalog (/me)

_Sopstveni profil, jezik, članstva (tenant switcher) i inbox obaveštenja._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/me` | Sopstveni profil korisnika. (`getMe`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT | P0 | implementirano |
| `PUT` | `/me/locale` | Izbor jezika (sr-Latn, sr-Cyrl, en) za UI, e-mail i push. (`updateMyLocale`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT | P0 | implementirano |
| `POST` | `/me/password` | Promena sopstvene lozinke uz trenutnu lozinku (to je reautentifikacija); opoziva sve ostale sesije. (`changeMyPassword`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT | P0 | implementirano |
| `GET` | `/me/memberships` | Lista članstava korisnika po organizacijama (tenant switcher). (`listMyMemberships`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT | P0 | implementirano |
| `POST` | `/me/invitations/accept` | Prihvatanje pozivnice postojećim nalogom (kreira članstvo ili PENDING guardian vezu). (`acceptInvitation`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT | P0 | implementirano |
| `GET` | `/me/notifications` | In-app inbox obaveštenja (samo i18n ključevi, argumenti i ID-jevi entiteta). (`listMyNotifications`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT | P0 | projektovano |
| `POST` | `/me/notifications/mark-read` | Označavanje obaveštenja (ili svih) kao pročitanih. (`markNotificationsRead`) | OWNER, ADMIN, TEACHER, PARENT, SUPER_ADMIN, SUPPORT | P0 | projektovano |

### Platform admin (/platform)

_Operator SaaS platforme (SUPER_ADMIN, delimično SUPPORT). Zahteva aktivan `platform_admins` zapis i MFA sesiju. Bez pristupa podacima dece; podaci tenanta samo kroz vremenski ograničen support grant._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/platform/organizations` | Lista vrtića (tenanta) sa statusom i pretplatom. (`platformListOrganizations`) | SUPER_ADMIN, SUPPORT [MFA] | P0 | implementirano |
| `POST` | `/platform/organizations` | Kreiranje vrtića: podrazumevana podešavanja, TRIAL pretplata, pozivnica za OWNER-a. (`platformCreateOrganization`) | SUPER_ADMIN [MFA, Idempotency-Key] | P0 | projektovano |
| `GET` | `/platform/organizations/{organizationId}` | Detalji vrtića (bez podataka o deci i članovima). (`platformGetOrganization`) | SUPER_ADMIN, SUPPORT [MFA] | P0 | projektovano |
| `PATCH` | `/platform/organizations/{organizationId}` | Izmena naziva, pravnog naziva, vremenske zone, jezika. (`platformUpdateOrganization`) | SUPER_ADMIN [MFA] | P1 | projektovano |
| `POST` | `/platform/organizations/{organizationId}/deactivate` | Suspenzija vrtića (tenant endpointi vraćaju 403 ORGANIZATION_SUSPENDED; podaci se ne brišu). (`platformDeactivateOrganization`) | SUPER_ADMIN [MFA, reauth] | P0 | projektovano |
| `POST` | `/platform/organizations/{organizationId}/reactivate` | Ponovna aktivacija suspendovanog vrtića. (`platformReactivateOrganization`) | SUPER_ADMIN [MFA] | P0 | projektovano |
| `GET` | `/platform/plans` | Katalog planova (STARTER/STANDARD/PRO) sa verzijama. (`platformListPlans`) | SUPER_ADMIN, SUPPORT [MFA] | P0 | projektovano |
| `POST` | `/platform/plans` | Nova verzija plana (cene se nikad ne prepisuju retroaktivno). (`platformCreatePlan`) | SUPER_ADMIN [MFA, Idempotency-Key] | P1 | projektovano |
| `GET` | `/platform/subscriptions` | Pretplate svih tenanta. (`platformListSubscriptions`) | SUPER_ADMIN, SUPPORT [MFA] | P0 | projektovano |
| `GET` | `/platform/organizations/{organizationId}/subscription` | Tekuća pretplata jednog vrtića. (`platformGetSubscription`) | SUPER_ADMIN, SUPPORT [MFA] | P0 | projektovano |
| `POST` | `/platform/organizations/{organizationId}/subscription` | Kreiranje pretplate za vrtić. (`platformCreateSubscription`) | SUPER_ADMIN [MFA, Idempotency-Key] | P0 | projektovano |
| `PATCH` | `/platform/organizations/{organizationId}/subscription` | Promena plana/statusa/perioda pretplate (ručno, bez payment providera); downgrade ne briše podatke. (`platformUpdateSubscription`) | SUPER_ADMIN [MFA] | P0 | projektovano |
| `GET` | `/platform/feature-flags` | Globalni registar feature flagova sa podrazumevanim vrednostima i kill switch-evima. (`platformListFeatureFlags`) | SUPER_ADMIN, SUPPORT [MFA] | P0 | projektovano |
| `PUT` | `/platform/feature-flags/{flagKey}` | Kreiranje/izmena definicije flaga. (`platformUpsertFeatureFlag`) | SUPER_ADMIN [MFA] | P0 | projektovano |
| `POST` | `/platform/feature-flags/{flagKey}/kill-switch` | Hitno isključivanje funkcionalnosti za sve tenante. (`platformSetKillSwitch`) | SUPER_ADMIN [MFA, reauth] | P0 | projektovano |
| `PUT` | `/platform/organizations/{organizationId}/feature-overrides/{flagKey}` | Override flaga za jedan vrtić (nezavisno od plana; kill switch i dalje ima prednost). (`platformSetFeatureOverride`) | SUPER_ADMIN [MFA] | P1 | projektovano |
| `DELETE` | `/platform/organizations/{organizationId}/feature-overrides/{flagKey}` | Uklanjanje tenant override-a (važi entitlement plana / default). (`platformDeleteFeatureOverride`) | SUPER_ADMIN [MFA] | P1 | projektovano |
| `GET` | `/platform/usage` | Agregirana statistika korišćenja (platforma ili jedan vrtić); bez ličnih podataka. (`platformGetUsage`) | SUPER_ADMIN, SUPPORT [MFA] | P1 | projektovano |
| `GET` | `/platform/support-access-grants` | Lista support pristupa (aktivni i istorijski). (`platformListSupportAccessGrants`) | SUPER_ADMIN, SUPPORT [MFA] | P0 | projektovano |
| `POST` | `/platform/support-access-grants` | Vremenski ograničen (≤ 72 h), opsegom ograničen, auditovan support pristup jednom vrtiću. (`platformCreateSupportAccessGrant`) | SUPER_ADMIN [MFA, reauth, Idempotency-Key] | P0 | projektovano |
| `POST` | `/platform/support-access-grants/{grantId}/revoke` | Trenutni opoziv support pristupa. (`platformRevokeSupportAccessGrant`) | SUPER_ADMIN [MFA] | P0 | projektovano |
| `GET` | `/platform/system-logs` | Sanitizovani sistemski logovi (kodovi, request id, statusi; bez PII/tokena). (`platformListSystemLogs`) | SUPER_ADMIN [MFA] | P1 | projektovano |

### Organizacija, podešavanja, dashboard, pretplata, feature flagovi

_Koren tenanta: profil organizacije, tipizirana podešavanja, dashboard, pregled pretplate i efektivni feature flagovi._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/organizations/{organizationId}` | Profil organizacije za članove. (`getOrganization`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | projektovano |
| `PATCH` | `/organizations/{organizationId}` | Izmena profila organizacije (naziv, pravni naziv, vremenska zona, jezik). (`updateOrganization`) | OWNER | P1 | projektovano |
| `GET` | `/organizations/{organizationId}/settings` | Tipizirana podešavanja: rok za izmenu rasporeda, tolerancija kašnjenja, radno vreme, radni dani, offline TTL. (`getOrganizationSettings`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | implementirano |
| `PUT` | `/organizations/{organizationId}/settings` | Zamena podešavanja (puni PUT). (`updateOrganizationSettings`) | OWNER (ORG_SETTINGS_MANAGE) | P0 | implementirano |
| `GET` | `/organizations/{organizationId}/dashboard` | Dashboard za admin web: brojači za datum u zoni vrtića, po objektima i grupama, poslednja obaveštenja, događaji. (`getDashboardSummary`) | OWNER, ADMIN, SUPPORT | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/subscription` | Pregled tekuće pretplate vrtića (read-only). (`getSubscription`) | OWNER, ADMIN, SUPPORT + BILLING_MANAGE | P1 | projektovano |
| `GET` | `/organizations/{organizationId}/feature-flags` | Efektivni feature flagovi za organizaciju (kill switch → override → plan → default). (`getEffectiveFeatureFlags`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | projektovano |

### Objekti i grupe

_Objekti (lokacije) i grupe vrtića._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/organizations/{organizationId}/locations` | Lista objekata. (`listLocations`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/locations` | Kreiranje objekta. (`createLocation`) | OWNER, ADMIN | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/locations/{locationId}` | Detalji objekta. (`getLocation`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | projektovano |
| `PATCH` | `/organizations/{organizationId}/locations/{locationId}` | Izmena objekta. (`updateLocation`) | OWNER, ADMIN | P0 | projektovano |
| `DELETE` | `/organizations/{organizationId}/locations/{locationId}` | Meko brisanje objekta (blokirano ako ima grupe). (`deleteLocation`) | OWNER | P1 | projektovano |
| `GET` | `/organizations/{organizationId}/groups` | Lista grupa sa brojem upisane dece; vaspitač vidi samo dodeljene grupe. (`listGroups`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/groups` | Kreiranje grupe u objektu. (`createGroup`) | OWNER, ADMIN | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/groups/{groupId}` | Detalji grupe. (`getGroup`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | projektovano |
| `PATCH` | `/organizations/{organizationId}/groups/{groupId}` | Izmena grupe. (`updateGroup`) | OWNER, ADMIN | P0 | projektovano |
| `DELETE` | `/organizations/{organizationId}/groups/{groupId}` | Meko brisanje grupe (blokirano dok postoje upisi). (`deleteGroup`) | OWNER | P1 | projektovano |

### Zaposleni, pozivnice, članstva, dozvole, dodele grupa

_Zaposleni, pozivnice, članstva, dodatne dozvole i dodele vaspitač–grupa sa periodom važenja._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/organizations/{organizationId}/employees` | Lista zaposlenih (OWNER/ADMIN/TEACHER članstva). (`listEmployees`) | OWNER, ADMIN, TEACHER, SUPPORT | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/employees/{employeeId}` | Profil zaposlenog sa statusom članstva i dozvolama. (`getEmployee`) | OWNER, ADMIN, TEACHER, SUPPORT | P0 | projektovano |
| `PATCH` | `/organizations/{organizationId}/employees/{employeeId}` | Izmena profila zaposlenog. (`updateEmployee`) | OWNER, ADMIN + MEMBER_MANAGE | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/invitations` | Lista pozivnica (osoblje i staratelji) sa izvedenim statusom. (`listInvitations`) | OWNER, ADMIN, SUPPORT | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/invitations` | Pozivnica za zaposlenog (OWNER-a može pozvati samo OWNER). (`createStaffInvitation`) | OWNER, ADMIN + MEMBER_MANAGE [Idempotency-Key] | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/invitations/{invitationId}/revoke` | Opoziv pozivnice. (`revokeInvitation`) | OWNER, ADMIN + MEMBER_MANAGE | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/memberships` | Lista članstava (npr. `role=PARENT` za ekran „Roditelji”). (`listMemberships`) | OWNER, ADMIN, SUPPORT | P0 | projektovano |
| `PUT` | `/organizations/{organizationId}/memberships/{membershipId}/permissions` | Zamena dodatnih dozvola članstva (CHILD_HEALTH_READ, ATTENDANCE_CORRECT, …). (`replaceMembershipPermissions`) | OWNER, ADMIN + MEMBER_MANAGE [reauth] | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/memberships/{membershipId}/revoke` | Trajni opoziv članstva: gase se dodele grupa, guardian veze, učešće u porukama, push. (`revokeMembership`) | OWNER, ADMIN + MEMBER_MANAGE [reauth] | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/group-teacher-assignments` | Dodele vaspitač–grupa sa periodom važenja. (`listGroupTeacherAssignments`) | OWNER, ADMIN, TEACHER, SUPPORT | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/group-teacher-assignments` | Dodela vaspitača grupi za period. (`createGroupTeacherAssignment`) | OWNER, ADMIN | P0 | projektovano |
| `DELETE` | `/organizations/{organizationId}/group-teacher-assignments/{assignmentId}` | Opoziv dodele (vaspitač gubi pristup grupi). (`revokeGroupTeacherAssignment`) | OWNER, ADMIN | P0 | projektovano |

### Deca, upisi, staratelji, ovlašćeni za preuzimanje

_Profili dece (nikad sa zdravstvenim podacima), upisi, guardian veze i ovlašćene osobe za preuzimanje._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/organizations/{organizationId}/children` | Lista dece sa tekućim upisom; **bez zdravstvenih podataka**; filtriranje po grupi/objektu/datumu. (`listChildren`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/children` | Kreiranje profila deteta (opciono sa početnim upisom). (`createChild`) | OWNER, ADMIN [Idempotency-Key] | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/children/{childId}` | Detalj deteta: upisi, staratelji, ovlašćeni za preuzimanje; **bez zdravstvenih podataka**. (`getChild`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | projektovano |
| `PATCH` | `/organizations/{organizationId}/children/{childId}` | Izmena profila deteta (If-Match). (`updateChild`) | OWNER, ADMIN [If-Match] | P0 | projektovano |
| `DELETE` | `/organizations/{organizationId}/children/{childId}` | Meko brisanje deteta (If-Match, reauth). (`deleteChild`) | OWNER [reauth, If-Match] | P1 | projektovano |
| `POST` | `/organizations/{organizationId}/children/{childId}/enrollments` | Upis deteta u grupu (nepreklapajući periodi, kapacitet). (`createEnrollment`) | OWNER, ADMIN [Idempotency-Key] | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/enrollments/{enrollmentId}/end` | Završetak ili otkazivanje upisa. (`endEnrollment`) | OWNER, ADMIN | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/children/{childId}/guardians/invite` | Pozivnica staratelju za dete (PENDING veza + e-mail). (`inviteGuardian`) | OWNER, ADMIN [Idempotency-Key] | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/guardians/{guardianId}/confirm` | Potvrda guardian veze od strane osoblja (PENDING → CONFIRMED). (`confirmGuardian`) | OWNER, ADMIN [reauth] | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/guardians/{guardianId}/revoke` | Opoziv guardian veze (trenutni gubitak pristupa detetu). (`revokeGuardian`) | OWNER, ADMIN [reauth] | P0 | projektovano |
| `PATCH` | `/organizations/{organizationId}/guardians/{guardianId}` | Izmena odnosa i prava staratelja (raspored, odsustvo, saglasnost, zdravlje). (`updateGuardianFlags`) | OWNER, ADMIN | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/children/{childId}/pickup-persons` | Dodavanje ovlašćene osobe za preuzimanje. (`createPickupPerson`) | OWNER, ADMIN, PARENT | P0 | projektovano |
| `PATCH` | `/organizations/{organizationId}/pickup-persons/{pickupPersonId}` | Izmena ovlašćene osobe. (`updatePickupPerson`) | OWNER, ADMIN, PARENT | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/pickup-persons/{pickupPersonId}/revoke` | Opoziv ovlašćene osobe (istorija ostaje). (`revokePickupPerson`) | OWNER, ADMIN, PARENT | P0 | projektovano |

### Zdravstveni podaci deteta

_Osetljivi zdravstveni profil: zasebna tabela, envelope enkripcija, dodatna dozvola, audit svakog čitanja/izmene sa `purpose`. Ne vraća se nigde drugde._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/organizations/{organizationId}/children/{childId}/health` | Čitanje zdravstvenog profila (alergije, posebne potrebe, napomene); obavezan `purpose`, audit HEALTH_READ. (`getChildHealthProfile`) | OWNER, ADMIN, TEACHER, PARENT + CHILD_HEALTH_READ | P0 | projektovano |
| `PUT` | `/organizations/{organizationId}/children/{childId}/health` | Kreiranje/zamena zdravstvenog profila (If-Match, `purpose`, audit HEALTH_UPDATED). (`updateChildHealthProfile`) | OWNER, ADMIN, TEACHER, PARENT + CHILD_HEALTH_WRITE [reauth, If-Match] | P0 | projektovano |

### Raspored

_Ponavljajući nedeljni šabloni sa datumom važenja, izmene za dan, neradni dani i izračunati dnevni planovi. Prioritet: neradni dan > aktivno odsustvo > izmena za dan > šablon > nema dolaska. Istorija se zamrzava i ne prepisuje._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/organizations/{organizationId}/children/{childId}/schedule/week` | Nedeljni pregled (7 dana) sa izvorom pravila, mogućnošću izmene i verzijom (ETag). (`getWeekSchedule`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | projektovano |
| `PUT` | `/organizations/{organizationId}/children/{childId}/schedule/week` | Atomska izmena više dana u nedelji (If-Match); kasne izmene se označavaju. (`updateWeekSchedule`) | OWNER, ADMIN, TEACHER, PARENT [If-Match] | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/children/{childId}/schedule/templates` | Tekući i istorijski nedeljni šabloni deteta. (`listScheduleTemplates`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/children/{childId}/schedule/templates` | Novi ponavljajući šablon sa `effectiveFrom` (prethodni se zatvara; istorija se ne menja). (`createScheduleTemplate`) | OWNER, ADMIN, TEACHER, PARENT [Idempotency-Key] | P0 | projektovano |
| `PUT` | `/organizations/{organizationId}/children/{childId}/schedule/overrides/{date}` | Izmena za konkretan dan (If-Match; `"0"` ako ne postoji). (`setDayOverride`) | OWNER, ADMIN, TEACHER, PARENT [If-Match] | P0 | projektovano |
| `DELETE` | `/organizations/{organizationId}/children/{childId}/schedule/overrides/{date}` | Uklanjanje izmene za dan (ponovo važi šablon). (`deleteDayOverride`) | OWNER, ADMIN, TEACHER, PARENT [If-Match] | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/children/{childId}/schedule/daily-plans` | Izračunati dnevni planovi za opseg (zamrznuti za prošlost). (`listDailyPlans`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/children/{childId}/schedule/preview` | Pregled efekta predloženih izmena pre primene (ništa se ne čuva). (`previewSchedule`) | OWNER, ADMIN, TEACHER, PARENT | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/closure-days` | Neradni dani organizacije/objekta. (`listClosureDays`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/closure-days` | Kreiranje neradnog dana (planovi se preračunavaju, roditelji obaveštavaju). (`createClosureDay`) | OWNER, ADMIN [Idempotency-Key] | P0 | projektovano |
| `DELETE` | `/organizations/{organizationId}/closure-days/{closureDayId}` | Brisanje budućeg neradnog dana. (`deleteClosureDay`) | OWNER, ADMIN | P0 | projektovano |

### Odsustva

_Prijavljena odsustva (SICK/VACATION/OTHER) za dan ili period; odmah vidljiva vaspitaču; auditovana i notifikovana._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/organizations/{organizationId}/absences` | Lista odsustava u opsegu (dete/grupa/status/vrsta). (`listAbsences`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/absences` | Prijava odsustva SICK/VACATION/OTHER; preklapanje → 409; važi odmah. (`createAbsence`) | OWNER, ADMIN, TEACHER, PARENT [Idempotency-Key] | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/absences/{absenceId}` | Detalji odsustva (ETag). (`getAbsence`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/absences/{absenceId}/cancel` | Otkazivanje odsustva (If-Match). (`cancelAbsence`) | OWNER, ADMIN, TEACHER, PARENT [If-Match] | P0 | projektovano |

### Prisustvo

_Stvarno prisustvo, odvojeno od plana. Nepromenljivi događaji, dnevna projekcija sa `version`, idempotentne komande (`commandId`), zaključavanje reda, offline sync batch. Automat: NOT_ARRIVED → CHECKED_IN → CHECKED_OUT (ponovni check-in otvara novu posetu)._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `POST` | `/organizations/{organizationId}/children/{childId}/attendance/check-in` | Dolazak deteta (nova poseta; neplanirano prisustvo se evidentira); `commandId` + `expectedVersion`. (`checkInChild`) | OWNER, ADMIN, TEACHER | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/children/{childId}/attendance/check-out` | Odlazak deteta (zatvara otvorenu posetu; bez otvorene posete → 409). (`checkOutChild`) | OWNER, ADMIN, TEACHER | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/children/{childId}/attendance/mark-absent` | Oznaka odsustva za dan kao kontekst (ne radi check-out). (`markChildAbsent`) | OWNER, ADMIN, TEACHER | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/children/{childId}/attendance/clear-absence` | Uklanjanje oznake odsustva za dan. (`clearChildAbsence`) | OWNER, ADMIN, TEACHER | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/children/{childId}/attendance/correction` | Korekcija događaja sa obaveznim razlogom (CORRECTION event; original ostaje). (`correctAttendance`) | OWNER, ADMIN, TEACHER + ATTENDANCE_CORRECT | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/attendance/daily-overview` | Dnevni pregled grupe: brojači (expected, present, departed, absent, notArrived, late, unscheduledPresent, physicallyPresent) + lista dece; offline snapshot. (`getDailyOverview`) | OWNER, ADMIN, TEACHER, SUPPORT | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/children/{childId}/attendance/days/{date}` | Stanje prisustva deteta za datum sa posetama (sintetički NOT_ARRIVED ako nema događaja). (`getChildAttendanceDay`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/attendance/events` | Istorija nepromenljivih događaja prisustva za grupu ili dete na datum. (`listAttendanceEvents`) | OWNER, ADMIN, TEACHER, SUPPORT | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/attendance/sync` | Batch reprodukcija offline reda komandi; po komandi ACCEPTED/DUPLICATE/REJECTED/CONFLICT. (`syncAttendanceCommands`) | OWNER, ADMIN, TEACHER | P0 | projektovano |

### Obaveštenja

_Obaveštenja za vrtić, objekat, grupu ili pojedinačne staratelje. Nacrt → objavljeno → arhivirano; snapshot primalaca pri objavi + provera prava pri čitanju; eksplicitne potvrde čitanja (push nije dokaz)._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/organizations/{organizationId}/announcements` | Lista obaveštenja (osoblje: svi statusi; primaoci: objavljena i važeća). (`listAnnouncements`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/announcements` | Kreiranje nacrta obaveštenja sa publikom (vrtić/objekat/grupa/staratelj) i prilozima. (`createAnnouncement`) | OWNER, ADMIN, TEACHER [Idempotency-Key] | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/announcements/{announcementId}` | Detalj obaveštenja (ETag); provera trenutnih prava primaoca. (`getAnnouncement`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P0 | projektovano |
| `PATCH` | `/organizations/{organizationId}/announcements/{announcementId}` | Izmena nacrta (objavljeno: samo `expiresAt`); If-Match. (`updateAnnouncement`) | OWNER, ADMIN, TEACHER [If-Match] | P0 | projektovano |
| `DELETE` | `/organizations/{organizationId}/announcements/{announcementId}` | Brisanje nacrta (If-Match). (`deleteAnnouncement`) | OWNER, ADMIN, TEACHER [If-Match] | P1 | projektovano |
| `POST` | `/organizations/{organizationId}/announcements/{announcementId}/publish` | Objava: snapshot primalaca + notifikacije; If-Match + Idempotency-Key. (`publishAnnouncement`) | OWNER, ADMIN, TEACHER + ANNOUNCEMENT_PUBLISH [If-Match, Idempotency-Key] | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/announcements/{announcementId}/archive` | Arhiviranje objavljenog obaveštenja (If-Match). (`archiveAnnouncement`) | OWNER, ADMIN, TEACHER [If-Match] | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/announcements/{announcementId}/recipients` | Primaoci iz snapshot-a sa vremenom čitanja i trenutnim pravima. (`listAnnouncementRecipients`) | OWNER, ADMIN, TEACHER, SUPPORT | P0 | projektovano |
| `POST` | `/organizations/{organizationId}/announcements/{announcementId}/read` | Eksplicitna potvrda čitanja (push nije dokaz čitanja). (`markAnnouncementRead`) | OWNER, ADMIN, TEACHER, PARENT | P0 | projektovano |

### Kalendar

_Događaji organizacije/objekta/grupe: izleti, fotografisanje, predstave, praznici, sastanci, neradni dani._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/organizations/{organizationId}/calendar-events` | Kalendar događaja u opsegu (izlet, fotografisanje, predstava, praznik, sastanak, neradni dan). (`listCalendarEvents`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P1 | projektovano |
| `POST` | `/organizations/{organizationId}/calendar-events` | Kreiranje događaja (celodnevni ili sa vremenom). (`createCalendarEvent`) | OWNER, ADMIN, TEACHER [Idempotency-Key] | P1 | projektovano |
| `GET` | `/organizations/{organizationId}/calendar-events/{eventId}` | Detalj događaja (ETag). (`getCalendarEvent`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P1 | projektovano |
| `PATCH` | `/organizations/{organizationId}/calendar-events/{eventId}` | Izmena događaja (If-Match). (`updateCalendarEvent`) | OWNER, ADMIN, TEACHER [If-Match] | P1 | projektovano |
| `DELETE` | `/organizations/{organizationId}/calendar-events/{eventId}` | Brisanje događaja (If-Match). (`deleteCalendarEvent`) | OWNER, ADMIN, TEACHER [If-Match] | P1 | projektovano |

### Jelovnici

_Dnevni jelovnici sa obrocima BREAKFAST / SNACK_AM / LUNCH / SNACK_PM. Oznake alergena su informativne, ne medicinski savet._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/organizations/{organizationId}/menu-days` | Jelovnici u opsegu (roditelji: samo objavljeni). (`listMenuDays`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P1 | projektovano |
| `POST` | `/organizations/{organizationId}/menu-days` | Kreiranje dnevnog jelovnika sa stavkama po obroku (BREAKFAST/SNACK_AM/LUNCH/SNACK_PM). (`createMenuDay`) | OWNER, ADMIN [Idempotency-Key] | P1 | projektovano |
| `GET` | `/organizations/{organizationId}/menu-days/{menuDayId}` | Detalj jelovnika (ETag). (`getMenuDay`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P1 | projektovano |
| `PUT` | `/organizations/{organizationId}/menu-days/{menuDayId}` | Zamena stavki jelovnika (If-Match). (`replaceMenuDayItems`) | OWNER, ADMIN [If-Match] | P1 | projektovano |
| `DELETE` | `/organizations/{organizationId}/menu-days/{menuDayId}` | Brisanje jelovnika (If-Match). (`deleteMenuDay`) | OWNER, ADMIN [If-Match] | P1 | projektovano |
| `POST` | `/organizations/{organizationId}/menu-days/{menuDayId}/publish` | Objava jelovnika roditeljima (If-Match). (`publishMenuDay`) | OWNER, ADMIN [If-Match] | P1 | projektovano |

### Fajlovi (privatni storage)

_Privatni storage: autorizacija upload-a → direktan upload → potvrda → karantin/skeniranje → READY. Bez javnih URL-ova; pristup samo kratkotrajnim signed URL-om sa dozvolama vlasničkog entiteta._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `POST` | `/organizations/{organizationId}/files/upload-authorizations` | Autorizacija upload-a: validacija namene/MIME/veličine, vraća pre-signed PUT ka privatnom storage-u. (`createUploadAuthorization`) | OWNER, ADMIN, TEACHER [Idempotency-Key] | P1 | projektovano |
| `POST` | `/organizations/{organizationId}/files/{fileId}/complete` | Potvrda upload-a → karantin, skeniranje, EXIF uklanjanje, thumbnails (asinhrono). (`completeFileUpload`) | OWNER, ADMIN, TEACHER | P1 | projektovano |
| `GET` | `/organizations/{organizationId}/files/{fileId}` | Metapodaci fajla i status obrade. (`getFile`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P1 | projektovano |
| `GET` | `/organizations/{organizationId}/files/{fileId}/access-url` | Kratkotrajni signed URL (~5 min) za original ili varijantu; nikad javni URL. (`getFileAccessUrl`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P1 | projektovano |

### Fotografije

_Fotografije sa razdvojenom publikom (ko sme da vidi) i saglasnošću (da li sme da se distribuira). Ručno označavanje svakog prepoznatljivog deteta; objava zahteva važeću saglasnost za svu označenu decu; povlačenje blokira dalju distribuciju._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/organizations/{organizationId}/photos` | Galerija (roditelji: objavljene fotografije u njihovoj publici sa važećim saglasnostima). (`listPhotos`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P1 | projektovano |
| `POST` | `/organizations/{organizationId}/photos` | Kreiranje nacrta fotografije iz READY fajla. (`createPhoto`) | OWNER, ADMIN, TEACHER [Idempotency-Key] | P1 | projektovano |
| `GET` | `/organizations/{organizationId}/photos/{photoId}` | Detalj fotografije: publika, označena deca, status saglasnosti. (`getPhoto`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P1 | projektovano |
| `PUT` | `/organizations/{organizationId}/photos/{photoId}/tags` | Ručno označavanje prepoznatljive dece (bez face recognition). (`replacePhotoTags`) | OWNER, ADMIN, TEACHER | P1 | projektovano |
| `PUT` | `/organizations/{organizationId}/photos/{photoId}/audience` | Postavljanje publike (grupa / staratelji deteta / pojedinačni staratelj); publika ≠ saglasnost. (`replacePhotoAudience`) | OWNER, ADMIN, TEACHER | P1 | projektovano |
| `POST` | `/organizations/{organizationId}/photos/{photoId}/publish` | Objava: zahteva READY fajl, potvrđene oznake, publiku i važeću saglasnost za SVAKO označeno dete. (`publishPhoto`) | OWNER, ADMIN, TEACHER + PHOTO_PUBLISH [Idempotency-Key] | P1 | projektovano |
| `POST` | `/organizations/{organizationId}/photos/{photoId}/withdraw` | Povlačenje: blokira novu distribuciju (preuzete kopije se ne mogu povući). (`withdrawPhoto`) | OWNER, ADMIN, TEACHER | P1 | projektovano |
| `GET` | `/organizations/{organizationId}/photos/{photoId}/access-url` | Kratkotrajni signed URL fotografije uz proveru publike i saglasnosti. (`getPhotoAccessUrl`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P1 | projektovano |

### Saglasnosti

_Verzionisane politike saglasnosti (namena, jezik, hash teksta) i odluke staratelja sa evidencijom. Nije kvalifikovani e-potpis._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/organizations/{organizationId}/consent-policies` | Verzionisane politike saglasnosti (namena, jezik, tekst, hash). (`listConsentPolicies`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P1 | projektovano |
| `POST` | `/organizations/{organizationId}/consent-policies` | Nova verzija politike (stare odluke postaju OUTDATED). (`createConsentPolicy`) | OWNER [reauth, Idempotency-Key] | P1 | projektovano |
| `GET` | `/organizations/{organizationId}/children/{childId}/consents` | Status saglasnosti deteta po nameni. (`getChildConsents`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P1 | projektovano |
| `POST` | `/organizations/{organizationId}/children/{childId}/consents` | Davanje/odbijanje saglasnosti od strane staratelja (hash teksta mora da se poklopi; evidencija se čuva). (`createConsentDecision`) | PARENT [Idempotency-Key] | P1 | projektovano |
| `POST` | `/organizations/{organizationId}/consent-decisions/{decisionId}/withdraw` | Povlačenje saglasnosti (automatski povlači zavisne objavljene fotografije). (`withdrawConsentDecision`) | PARENT | P1 | projektovano |

### Poruke

_Konverzacije roditelj↔vaspitač i roditelj↔administracija o jednom detetu. Učesnike određuje server iz guardian veza i dodela; idempotentno slanje (`clientMessageId`); nepročitane poruke i pozicija čitanja._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/organizations/{organizationId}/conversations` | Konverzacije korisnika sa brojem nepročitanih. (`listConversations`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P1 | projektovano |
| `POST` | `/organizations/{organizationId}/conversations` | Nova konverzacija o detetu (učesnike određuje server); postojeća otvorena se vraća sa 200. (`createConversation`) | OWNER, ADMIN, TEACHER, PARENT | P1 | projektovano |
| `GET` | `/organizations/{organizationId}/conversations/{conversationId}` | Detalj konverzacije sa učesnicima i pozicijom čitanja. (`getConversation`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P1 | projektovano |
| `GET` | `/organizations/{organizationId}/conversations/{conversationId}/messages` | Poruke (cursor paginacija, oba smera). (`listMessages`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P1 | projektovano |
| `POST` | `/organizations/{organizationId}/conversations/{conversationId}/messages` | Slanje poruke; idempotentno preko `clientMessageId`. (`sendMessage`) | OWNER, ADMIN, TEACHER, PARENT | P1 | projektovano |
| `PUT` | `/organizations/{organizationId}/conversations/{conversationId}/read-position` | Ažuriranje pozicije čitanja (monotono). (`updateReadPosition`) | OWNER, ADMIN, TEACHER, PARENT | P1 | projektovano |

### Audit, izveštaji, privatnost

_Audit log, izveštaji i zahtevi za privatnost (GDPR / zaštita podataka u Srbiji)._

| Method | Path | Namena | Role/permisije | Prioritet | Status |
|---|---|---|---|---|---|
| `GET` | `/organizations/{organizationId}/audit-log` | Audit log tenanta sa allowlist filterima. (`listAuditLog`) | OWNER, ADMIN, SUPPORT | P0 | projektovano |
| `GET` | `/organizations/{organizationId}/reports/attendance-summary` | Izveštaj o prisustvu po grupi/objektu i opsegu datuma (iste definicije kao dnevni pregled). (`getAttendanceSummaryReport`) | OWNER, ADMIN, SUPPORT | P1 | projektovano |
| `GET` | `/organizations/{organizationId}/privacy-requests` | Zahtevi za privatnost (pristup/izvoz/brisanje/ispravka). (`listPrivacyRequests`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P1 | projektovano |
| `POST` | `/organizations/{organizationId}/privacy-requests` | Podnošenje zahteva za privatnost za sebe ili svoje dete. (`createPrivacyRequest`) | OWNER, ADMIN, TEACHER, PARENT [Idempotency-Key] | P1 | projektovano |
| `GET` | `/organizations/{organizationId}/privacy-requests/{privacyRequestId}` | Status zahteva (i ID izvoznog fajla kad je gotov). (`getPrivacyRequest`) | OWNER, ADMIN, TEACHER, PARENT, SUPPORT | P1 | projektovano |

---

## 3. Primeri ključnih tokova

Svi primeri koriste `Content-Type: application/json`; ID-jevi su skraćeni. Mobilni primeri šalju `Authorization: Bearer …`, web primeri kolačić + `X-CSRF-Token`.

### 3.1 login → refresh → logout (mobile, bearer)

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "email": "vaspitac.milica@example.test",
  "password": "********",
  "device": { "clientKind": "ANDROID", "deviceName": "Pixel 8", "deviceId": "d1f2…" }
}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json
X-Request-Id: 01J8Q3ZA0X…

{
  "status": "AUTHENTICATED",
  "sessionId": "5a1c…",
  "tokens": {
    "accessToken": "vca_9K1…",
    "accessTokenExpiresAt": "2026-09-16T06:42:10Z",
    "refreshToken": "vcr_Q7m…",
    "refreshTokenExpiresAt": "2026-09-23T06:32:10Z"
  },
  "csrfToken": null,
  "mfaChallengeToken": null,
  "user": { "id": "u-77…", "email": "vaspitac.milica@example.test", "givenName": "Milica", "familyName": "Petrović", "preferredLocale": "sr-Latn", "status": "ACTIVE", "mfaEnabled": false, "isPlatformAdmin": false, "createdAt": "2026-09-01T08:00:00Z" }
}
```

Za OWNER-a sa MFA odgovor je `{"status":"MFA_REQUIRED","mfaChallengeToken":"vcm_…"}` → `POST /auth/mfa/totp/verify {"mfaChallengeToken":"vcm_…","code":"123456"}` vraća isti `AuthResult` sa tokenima. Web varijanta: telo ima `tokens: null`, `csrfToken: "c9…"`, a `Set-Cookie: vc_access=…; HttpOnly; Secure; SameSite=Lax` i `vc_refresh` kolačić za refresh.

```http
POST /api/v1/auth/refresh
Content-Type: application/json

{ "refreshToken": "vcr_Q7m…" }
```

```http
HTTP/1.1 200 OK
{ "status": "AUTHENTICATED", "sessionId": "5a1c…", "tokens": { "accessToken": "vca_Zt4…", "accessTokenExpiresAt": "2026-09-16T06:52:10Z", "refreshToken": "vcr_B2x…", "refreshTokenExpiresAt": "2026-09-23T06:42:10Z" }, "csrfToken": null, "mfaChallengeToken": null, "user": null }
```

Ako klijent ponovo pošalje `vcr_Q7m…` (već potrošen):

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/problem+json
{ "type": "…/problems/unauthorized", "title": "Unauthorized", "status": 401, "detail": "REFRESH_REUSE_DETECTED", "instance": "/api/v1/auth/refresh", "requestId": "01J8Q3ZB…" }
```

i cela sesija (sve tokene te porodice) je opozvana. Odjava:

```http
POST /api/v1/auth/logout
Authorization: Bearer vca_Zt4…
{ "refreshToken": "vcr_B2x…" }

HTTP/1.1 204 No Content
```

**Pre ove implementacije** skeleton je vraćao za `login` i `refresh`:

```http
HTTP/1.1 501 Not Implemented
Content-Type: application/problem+json
{ "type": "…/problems/not-implemented", "title": "Not Implemented", "status": 501, "detail": "AUTH_NOT_IMPLEMENTED", "instance": "/api/v1/auth/login", "requestId": "01J8Q3Z9…" }
```

### 3.2 Nedeljni raspored sa If-Match (roditelj)

```http
GET /api/v1/organizations/6f2b…/children/9c1d…/schedule/week?weekStart=2026-09-21
Authorization: Bearer …
```

```http
HTTP/1.1 200 OK
ETag: "7"

{
  "childId": "9c1d…", "weekStart": "2026-09-21", "timezone": "Europe/Belgrade", "version": 7, "changeDeadlineHours": 12,
  "days": [
    { "date": "2026-09-21", "weekday": 1, "isExpected": true, "expectedArrival": "07:30", "expectedDeparture": "15:30", "source": "TEMPLATE", "isLateChange": false, "overrideId": null, "absenceId": null, "closureDayId": null, "isFrozen": false, "isEditable": true },
    { "date": "2026-09-22", "weekday": 2, "isExpected": false, "expectedArrival": null, "expectedDeparture": null, "source": "ABSENCE", "isLateChange": false, "overrideId": null, "absenceId": "ab-3…", "closureDayId": null, "isFrozen": false, "isEditable": false },
    { "date": "2026-09-23", "weekday": 3, "isExpected": true, "expectedArrival": "08:00", "expectedDeparture": "16:00", "source": "OVERRIDE", "isLateChange": true, "overrideId": "ov-1…", "absenceId": null, "closureDayId": null, "isFrozen": false, "isEditable": true },
    { "date": "2026-09-24", "weekday": 4, "isExpected": true, "expectedArrival": "07:30", "expectedDeparture": "15:30", "source": "TEMPLATE", "isLateChange": false, "overrideId": null, "absenceId": null, "closureDayId": null, "isFrozen": false, "isEditable": true },
    { "date": "2026-09-25", "weekday": 5, "isExpected": false, "expectedArrival": null, "expectedDeparture": null, "source": "CLOSURE", "isLateChange": false, "overrideId": null, "absenceId": null, "closureDayId": "cl-9…", "isFrozen": false, "isEditable": false },
    { "date": "2026-09-26", "weekday": 6, "isExpected": false, "expectedArrival": null, "expectedDeparture": null, "source": "NONE", "isLateChange": false, "overrideId": null, "absenceId": null, "closureDayId": null, "isFrozen": false, "isEditable": false },
    { "date": "2026-09-27", "weekday": 7, "isExpected": false, "expectedArrival": null, "expectedDeparture": null, "source": "NONE", "isLateChange": false, "overrideId": null, "absenceId": null, "closureDayId": null, "isFrozen": false, "isEditable": false }
  ]
}
```

Atomska izmena ponedeljka i četvrtka:

```http
PUT /api/v1/organizations/6f2b…/children/9c1d…/schedule/week?weekStart=2026-09-21
Authorization: Bearer …
If-Match: "7"
Content-Type: application/json

{ "days": [ { "date": "2026-09-21", "attends": true, "arrivalTime": "08:30", "departureTime": "15:30" }, { "date": "2026-09-24", "attends": false } ], "reason": "lekar" }
```

`200 OK` + `ETag: "9"` sa novim `WeekSchedule` (oba dana `source: OVERRIDE`; ako je ponedeljak unutar roka od 12 h → `isLateChange: true`, upis u `schedule_change_log`). Greške:

- bez `If-Match` → `428 { "detail": "IF_MATCH_REQUIRED" }`;
- `If-Match: "5"` (neko je u međuvremenu menjao) → `409 { "detail": "VERSION_MISMATCH", "currentVersion": 7 }`;
- pokušaj izmene petka (neradni dan) → `409 { "detail": "DAY_NOT_EDITABLE", "errors": [{ "field": "days[1].date", "code": "CLOSURE", "message": "schedule.day.closure" }] }` – **nijedan** dan se ne primenjuje.

`POST …/schedule/preview` sa istim `days` u `proposedOverrides` vraća isti rezultat bez čuvanja (`changedDates`, `lateChangeDates`, `blockedDates`).

### 3.3 Check-in sa `commandId` i oblik 409 konflikta (vaspitač)

```http
POST /api/v1/organizations/6f2b…/children/9c1d…/attendance/check-in
Authorization: Bearer …
Content-Type: application/json

{ "commandId": "2b7e6d4a-1c3f-4e8a-9b2d-5f6a7c8d9e01", "expectedVersion": 0, "occurredAt": "2026-09-21T05:41:12Z", "attendanceDate": "2026-09-21", "deviceId": "d1f2…" }
```

```http
HTTP/1.1 201 Created

{
  "commandId": "2b7e6d4a-1c3f-4e8a-9b2d-5f6a7c8d9e01",
  "outcome": "ACCEPTED",
  "eventId": "ev-10…",
  "attendanceDay": {
    "id": "ad-5…", "childId": "9c1d…", "groupId": "g-bub…", "attendanceDate": "2026-09-21",
    "status": "CHECKED_IN", "absenceKind": null, "isExpected": true, "expectedArrival": "07:30", "expectedDeparture": "15:30",
    "isUnscheduled": false, "isLate": true, "firstCheckInAt": "2026-09-21T05:41:12Z", "lastCheckOutAt": null,
    "openVisitId": "v-1…", "visitsCount": 1,
    "visits": [ { "id": "v-1…", "sequenceNo": 1, "checkInAt": "2026-09-21T05:41:12Z", "checkOutAt": null, "checkInEventId": "ev-10…", "checkOutEventId": null } ],
    "version": 1, "lastEventId": "ev-10…", "updatedAt": "2026-09-21T05:41:12Z"
  }
}
```

Ponovljen isti zahtev (isti `commandId`, npr. posle timeout-a) → `200 OK` sa `outcome: "DUPLICATE"` i istim `attendanceDay`. Ako drugi vaspitač u međuvremenu uradi check-out (verzija postaje 2), a prvi uređaj pošalje novu komandu sa `expectedVersion: 1`:

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{
  "type": "https://api.vrtic.example.test/problems/version-conflict",
  "title": "Conflict",
  "status": 409,
  "detail": "VERSION_MISMATCH",
  "currentVersion": 2,
  "errors": [ { "field": "expectedVersion", "code": "STALE", "message": "attendance.version.stale" } ],
  "instance": "/api/v1/organizations/6f2b…/children/9c1d…/attendance/check-in",
  "requestId": "01J8Q3ZC…"
}
```

Komanda **nije** primenjena; klijent učitava `GET …/attendance/days/2026-09-21` (ili dnevni pregled) i prikazuje stanje. Ostali 409 kodovi: `ALREADY_CHECKED_IN`, `NO_OPEN_VISIT`, `DAY_LOCKED`. Korekcija (`POST …/attendance/correction`, dozvola `ATTENDANCE_CORRECT`) ne menja stari događaj nego dodaje `CORRECTION` sa obaveznim `reason`.

### 3.4 Offline sync batch

```http
POST /api/v1/organizations/6f2b…/attendance/sync
Authorization: Bearer …
Content-Type: application/json

{
  "deviceId": "d1f2…",
  "snapshotGeneratedAt": "2026-09-21T04:55:00Z",
  "commands": [
    { "commandId": "c-01…", "childId": "9c1d…", "type": "CHECK_IN",  "attendanceDate": "2026-09-21", "expectedVersion": 0, "occurredAt": "2026-09-21T05:41:12Z" },
    { "commandId": "c-02…", "childId": "9c1d…", "type": "CHECK_OUT", "attendanceDate": "2026-09-21", "expectedVersion": 1, "occurredAt": "2026-09-21T13:02:40Z" },
    { "commandId": "c-03…", "childId": "7e2a…", "type": "MARK_ABSENT", "attendanceDate": "2026-09-21", "expectedVersion": 0, "occurredAt": "2026-09-21T05:45:00Z", "absenceKind": "SICK" },
    { "commandId": "c-04…", "childId": "3d9f…", "type": "CHECK_IN",  "attendanceDate": "2026-09-21", "expectedVersion": 0, "occurredAt": "2026-09-21T05:50:00Z" }
  ]
}
```

```http
HTTP/1.1 200 OK

{
  "processedAt": "2026-09-21T13:10:02Z",
  "results": [
    { "commandId": "c-01…", "outcome": "DUPLICATE", "code": null, "currentVersion": 1, "attendanceDay": { "…": "…", "version": 1 } },
    { "commandId": "c-02…", "outcome": "ACCEPTED",  "code": null, "currentVersion": 2, "attendanceDay": { "…": "…", "status": "CHECKED_OUT", "version": 2 } },
    { "commandId": "c-03…", "outcome": "CONFLICT",  "code": "VERSION_MISMATCH", "currentVersion": 1, "attendanceDay": { "…": "…", "status": "CHECKED_IN", "version": 1 } },
    { "commandId": "c-04…", "outcome": "REJECTED",  "code": "NOT_FOUND", "currentVersion": null, "attendanceDay": null }
  ]
}
```

Pravila: batch uvek vraća `200`; komande se obrađuju redom po detetu; `DUPLICATE`/`ACCEPTED` → klijent briše komandu iz reda; `CONFLICT` → korisnik rešava ručno (dete je u međuvremenu prijavljeno sa drugog uređaja – nema last-write-wins); `REJECTED` → prava su se promenila (`NOT_FOUND` = dete/grupa van opsega, npr. opozvana dodela; `FORBIDDEN`, `SNAPSHOT_EXPIRED` ako je snapshot stariji od `offlineCacheTtlHours`, `OCCURRED_AT_INVALID`). Zdravstveni podaci i fotografije nisu deo offline snapshot-a.

### 3.5 Dnevni pregled grupe

```http
GET /api/v1/organizations/6f2b…/attendance/daily-overview?groupId=g-bub…&date=2026-09-21
```

```http
HTTP/1.1 200 OK

{
  "groupId": "g-bub…", "date": "2026-09-21", "timezone": "Europe/Belgrade", "isClosure": false, "lateArrivalGraceMinutes": 15,
  "counters": { "expected": 12, "present": 8, "departed": 1, "absent": 2, "notArrived": 1, "late": 1, "unscheduledPresent": 1, "physicallyPresent": 9 },
  "children": [
    { "childId": "9c1d…", "givenName": "Lana", "familyName": "Jović", "photoFileId": "f-2…", "hasCriticalHealthAlert": true, "isExpected": true, "expectedArrival": "07:30", "expectedDeparture": "15:30", "status": "CHECKED_IN", "absenceKind": null, "overviewState": "PRESENT", "isLate": true, "isUnscheduled": false, "firstCheckInAt": "2026-09-21T05:41:12Z", "lastCheckOutAt": null, "version": 1, "pickupPersonsCount": 3 },
    { "childId": "7e2a…", "givenName": "Vuk", "familyName": "Nikolić", "photoFileId": null, "hasCriticalHealthAlert": false, "isExpected": true, "expectedArrival": "08:00", "expectedDeparture": "16:00", "status": "NOT_ARRIVED", "absenceKind": "SICK", "overviewState": "ABSENT", "isLate": false, "isUnscheduled": false, "firstCheckInAt": null, "lastCheckOutAt": null, "version": 1, "pickupPersonsCount": 2 },
    { "childId": "3d9f…", "givenName": "Mia", "familyName": "Ilić", "photoFileId": null, "hasCriticalHealthAlert": false, "isExpected": false, "expectedArrival": null, "expectedDeparture": null, "status": "CHECKED_IN", "absenceKind": null, "overviewState": "UNSCHEDULED_PRESENT", "isLate": false, "isUnscheduled": true, "firstCheckInAt": "2026-09-21T06:10:00Z", "lastCheckOutAt": null, "version": 1, "pickupPersonsCount": 1 }
  ],
  "generatedAt": "2026-09-21T07:00:05Z",
  "snapshotValidUntil": "2026-09-21T19:00:05Z"
}
```

Invarijanta: `expected = present + departed + absent + notArrived` (12 = 8 + 1 + 2 + 1); `late` je podskup; `unscheduledPresent` je zasebno i ulazi samo u `physicallyPresent = present + unscheduledPresent`. Odsustvo za prisutno dete (`mark-absent`) ne radi check-out – dete ostaje u `present`, a `absenceKind` je kontekst. Iste definicije koristi `GET …/dashboard` i izveštaj.

### 3.6 Pozivanje i potvrda staratelja

```http
POST /api/v1/organizations/6f2b…/children/9c1d…/guardians/invite
Cookie: vc_access=…
X-CSRF-Token: c9…
Idempotency-Key: 8f3c7a10-2c4e-4c2b-9a77-0d2b0c6a1e55
Content-Type: application/json

{ "email": "marko.jovic@example.test", "relationship": "FATHER", "isPrimary": false, "canManageSchedule": true, "canReportAbsence": true, "canGiveConsent": true, "canViewHealth": true }
```

```http
HTTP/1.1 201 Created

{
  "guardian": { "id": "gu-4…", "organizationId": "6f2b…", "childId": "9c1d…", "membershipId": "m-88…", "userId": "u-91…", "givenName": "Marko", "familyName": "Jović", "relationship": "FATHER", "status": "PENDING", "isPrimary": false, "canManageSchedule": true, "canReportAbsence": true, "canGiveConsent": true, "canViewHealth": true, "confirmedAt": null, "revokedAt": null, "createdAt": "2026-09-16T09:00:00Z" },
  "invitation": { "id": "inv-6…", "organizationId": "6f2b…", "email": "marko.jovic@example.test", "role": "PARENT", "childId": "9c1d…", "invitedBy": "u-12…", "expiresAt": "2026-09-23T09:00:00Z", "acceptedAt": null, "revokedAt": null, "status": "PENDING", "createdAt": "2026-09-16T09:00:00Z" }
}
```

Tok: roditelj otvara link → `GET /auth/invitations/{token}` → `POST /auth/register` (novi nalog) ili login + `POST /me/invitations/accept` (postojeći nalog; jedan nalog može biti roditelj u više vrtića). Veza ostaje `PENDING` – roditelj još **ne vidi** dete (`GET /children/{childId}` → `404`). Osoblje potvrđuje:

```http
POST /api/v1/organizations/6f2b…/guardians/gu-4…/confirm
Cookie: vc_access=…
X-CSRF-Token: c9…

HTTP/1.1 200 OK
{ "id": "gu-4…", "status": "CONFIRMED", "confirmedAt": "2026-09-17T07:20:00Z", "…": "…" }
```

Zahteva nedavnu reautentifikaciju (`403 REAUTHENTICATION_REQUIRED` inače) i članstvo u statusu ACTIVE (`409 MEMBERSHIP_NOT_ACTIVE`). Roditelj ne može sam sebi povezati dete niti potvrditi tuđu vezu; opoziv (`POST …/guardians/{id}/revoke`) odmah gasi pristup.

### 3.7 Čitanje zdravstvenog profila sa `purpose`

```http
GET /api/v1/organizations/6f2b…/children/9c1d…/health?purpose=DAILY_CARE
Authorization: Bearer …
```

```http
HTTP/1.1 200 OK
ETag: "2"

{
  "childId": "9c1d…", "payloadSchemaVer": 1, "hasCriticalAlert": true,
  "payload": {
    "allergies": [ { "code": "PEANUT", "severity": "SEVERE", "note": "EpiPen u torbi" } ],
    "specialNeeds": [],
    "medicalNotes": "…",
    "emergencyContact": { "fullName": "Jelena Jović", "phone": "+381601234567", "relationship": "MOTHER" }
  },
  "version": 2, "updatedBy": "u-12…", "createdAt": "2026-09-02T10:00:00Z", "updatedAt": "2026-09-10T11:30:00Z"
}
```

Bez `purpose` → `422`; vaspitač bez `CHILD_HEALTH_READ` (ili OWNER bez nje) → `403 PERMISSION_REQUIRED`; dete van opsega → `404`. Svako čitanje upisuje `audit_log` red (`action: HEALTH_READ`, `purpose: DAILY_CARE`, `entity_type: CHILD_HEALTH`). Izmena: `PUT …/health` sa `If-Match: "2"` (ili `"0"` pri kreiranju), telom `{ "purpose": "MEDICAL_UPDATE", "hasCriticalAlert": true, "payload": { … } }`, dozvolom `CHILD_HEALTH_WRITE` i reautentifikacijom.

---

## 4. Šta je projektovano, a nije implementirano

| Stavka | Stanje |
|---|---|
| `GET /health/live`, `GET /health/ready` | **implementirano** u skeleton-u (Ktor bootstrap; readiness proverava bazu i migracije) |
| `POST /auth/login`, `POST /auth/refresh`, `GET /auth/session`, `POST /auth/logout` | **delimično implementirano** – login za verifikovanog korisnika proverava Argon2id i izdaje opaque tokene ili web HttpOnly + CSRF kolačiće; refresh radi atomsku rotaciju, CSRF/Origin proveru i reuse detekciju; session bootstrap vraća minimalni SPA profil i aktivna članstva; logout opoziva tekuću sesiju i briše kolačiće; ne zamenjivati lažnom prijavom ni prečicom koja zaobilazi dozvole |
| `POST /auth/logout-all`, `GET /auth/sessions`, `DELETE /auth/sessions/{sessionId}` | **implementirano** (E02-B08): lista sopstvenih neopozvanih sesija (cursor paginacija, `isCurrent`), opoziv jedne sesije sa njenim access/refresh tokenima (tuđa ili nepoznata sesija → `404`, ponovni opoziv → `204`), odjava sa svih uređaja sa uslovom skorašnje autentifikacije (prijava ili reautentifikacija u poslednjih 5 min, inače `403` `REAUTHENTICATION_REQUIRED`); u cookie režimu obavezan CSRF; `last_seen_at` se ažurira pri refresh-u |
| `GET /auth/invitations/{token}`, `POST /auth/register`, `POST /me/invitations/accept` | **implementirano** (E02-B04): pregled pozivnice (404 za nepoznatu, isteklu, opozvanu ili već prihvaćenu), registracija preko pozivnice (Argon2id, politika lozinke 12–256 znakova + blocklist, `409 EMAIL_ALREADY_REGISTERED`, e-mail se smatra verifikovanim jer je token stigao na to sanduče, kreira ACTIVE članstvo i odmah izdaje sesiju), prihvatanje postojećim nalogom samo za sopstveni e-mail (tuđi → 404). PENDING guardian veza za PARENT pozivnice čeka tabelu `guardians` (EPIC 05) |
| `POST /auth/verify-email`, `POST /auth/resend-verification` | **implementirano** (E02-B05): jednokratan 24 h token (`404 TOKEN_INVALID` posle upotrebe/isteka); resend je **neautentifikovan** `{email}` i uvek `202` (promena ugovora: neverifikovan nalog ne može da ima sesiju) |
| `POST /auth/reauthenticate` | **implementirano** (E02-B10): lozinka (TOTP grana čeka E02-B11) → `sessions.reauthenticated_at`; odgovor `reauthenticatedAt` + `validUntil` (5 min); pogrešna lozinka 403 `INVALID_CREDENTIALS` bez pečata, 5 pokušaja u 15 min; `logout-all` koristi isto pravilo (`RecentAuthentication`) |
| `dev-set-password <email>` (CLI, samo `APP_ENV=dev`) | **implementirano** (E02-D03): Argon2id hash lozinke iz `SEED_DEV_PASSWORD` za seed nalog; seed SQL ne sadrži lozinke; u staging/prod komanda odbija |
| `GET /me`, `PUT /me/locale`, `POST /me/password` | **implementirano** (E02-B16): profil (`mfaEnabled` iz `user_mfa_methods`, `isPlatformAdmin` iz sesije), jezik (422 za nepoznat), promena lozinke: trenutna lozinka obavezna (403 `CURRENT_PASSWORD_INVALID`, 5 pokušaja u 15 min), politika 422, opoziv svih ostalih sesija i tokena (`PASSWORD_CHANGED`), tekuća sesija dobija `reauthenticated_at`, audit. `PATCH /me` (ime) ostaje izostavljen iz ugovora |
| Autorizacija po ruti (`Authorize.require`, `requirePlatformAdmin`, `GroupScope`/`ChildScope`) | **implementirano** (E02-B15): 403 bez detalja kada članstvu nedostaje permisija (uloga + eksplicitno dodeljene `membership_permissions`, samo grantable); platformske rute: tenant korisnik 404, SUPER_ADMIN bez MFA 403 `MFA_REQUIRED`; `GroupScope`/`ChildScope` interfejsi za korak 4 (implementacije u E04/E05) |
| `GET/PUT /organizations/{organizationId}/settings` | **implementirano** (E02-B15 primer): GET svaki ACTIVE član, PUT samo `ORG_SETTINGS_MANAGE` (OWNER; ugovor je sa OWNER+ADMIN sveden na OWNER po matrici dozvola), validacija 422, audit `ORG_SETTINGS_UPDATED` |
| `GET /platform/organizations` | **implementirano** (E02-B15 primer platformskog pipeline-a): filter `status`, `search`, `sort` allowlist, cursor paginacija (samo uz `createdAt:desc`), `DbContext.Platform` |
| Rate limiting i zaključavanje naloga | **implementirano** (E02-B13): `login_attempts` kao trajni izvor, 10 neuspeha u 15 min → `locked_until` + `429 RATE_LIMITED` sa `Retry-After` (isto za nepostojeći nalog); 20 zahteva/IP/min na `/auth/*`; `forgot-password` i `resend-verification` 3 po e-mailu na sat; `APP_TRUST_PROXY` za `X-Forwarded-For`. Odgovor `423` uklonjen iz ugovora za `login` jer bi otkrivao postojanje naloga |
| `POST /auth/forgot-password`, `POST /auth/reset-password` | **implementirano** (E02-B09): uvek `202`, token 15 min, jednokratan; reset postavlja novu lozinku (politika, `422`), opoziva sve sesije (`PASSWORD_CHANGED`) i njihove tokene, briše zaključavanje; audit zapisi |
| `MailSender` | **implementirano** (E02-B20): interfejs + DEV `LoggingMailSender` (link u dedikovani logger samo u `APP_ENV=dev`) + `UnconfiguredMailSender` koji u staging/prod baca grešku umesto tihog gubitka; SES adapter je EPIC 18 |
| `GET /me/memberships` | **implementirano** (E02-B14): lista sopstvenih članstava sa organizacijom, ulogom, statusom, dodatnim permisijama i `employeeId`; filter `status` (ACTIVE podrazumevano, INVITED, SUSPENDED); radi u `User` DB kontekstu preko `self_*` RLS politika (V3) |
| Tenant pipeline (`/organizations/{organizationId}/...`) | **implementirano** (E02-B14): sesija → aktivno članstvo → transakcija sa `app.organization_id`; primer `GET .../ping` (nije u OpenAPI, interni primer) vraća ime organizacije pročitano kroz RLS, ulogu i permisije; tuđi ili nepostojeći vrtić i neispravan id daju `404`, bez kredencijala `401` |
| Sve ostale operacije (145 od 168) | **samo projektovane** – postoje u `docs/openapi.yaml` sa `x-status: designed`; nema koda, nema ruta, nema testova |

Konkretno, nije implementirano ništa od: MFA, push tokena; `/me/notifications`; `/platform/*` osim liste organizacija; objekata, grupa, zaposlenih, pozivnica, dozvola, dodela; dece, upisa, staratelja, ovlašćenih osoba, zdravstvenih profila; rasporeda (šabloni, izmene, neradni dani, preview, zamrzavanje planova); odsustava; prisustva (komande, projekcija, dnevni pregled, offline sync); obaveštenja i notifikacija (outbox, FCM/APNs); kalendara; jelovnika; fajlova i fotografija (upload, karantin, skeniranje, signed URL); saglasnosti; poruka; audit loga; izveštaja; zahteva za privatnost; dashboard-a.

Takođe nije urađeno (i ne tvrdi se): opterećenje/performanse, produkcijska podešavanja rate limitinga, penetracioni test, provere na nativnom PostgreSQL 17, mobilni i web klijenti za ove endpointe. Sledeći korak po roadmap-u je **EPIC 02 – autentifikacija, sesije i dozvole**, čime `login`/`refresh` prelaze iz `implemented-stub` u `implemented`, a zatim EPIC 03 (organizacije i tenant kontekst).

Operacije koje su razmatrane, ali svesno **izostavljene** iz ove verzije ugovora radi obima (mogu se dodati bez lomljenja postojećih): `PATCH /me` (izmena imena; promena lozinke je dodata kao `POST /me/password`), suspenzija/reaktivacija članstva, ponovno slanje pozivnice, brojač nepročitanih notifikacija, istorija prisustva deteta po opsegu, feed izmena rasporeda, zatvaranje konverzacije, retire politike saglasnosti, obrada zahteva za privatnost od strane osoblja, pojedinačni GET/deaktivacija plana i usage po vrtiću kao zaseban endpoint (pokriveno filterom `organizationId` na `/platform/usage`).
