# Bezbednost, privatnost i audit – Vrtić Connect

> Ovaj dokument opisuje tehničke mere. Nije pravni savet. Rokovi čuvanja i pravni osnovi su **predlozi** koje mora potvrditi pravnik za Srbiju (ZZPL) i EU (GDPR). Šta je od navedenog implementirano u skeletonu piše u [VALIDATION_REPORT.md](VALIDATION_REPORT.md); sve ostalo je projektovano i raspoređeno po EPIC-ima u [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md).

## 1. Model pretnji (sažeto)

| Akter | Šta pokušava | Glavna kontrola |
|---|---|---|
| Roditelj | da vidi tuđe dete, poveže sebe sa tuđim detetom, potvrdi sebi prava | guardian veza samo od strane osoblja; resource policy po detetu; RLS |
| Vaspitač | da vidi grupe koje mu nisu dodeljene ili podatke van perioda dodele | `group_teacher_assignments` sa `valid_from/valid_to`; policy na svakoj ruti |
| Administracija/vlasnik | da čita zdravstvene podatke bez potrebe, dodeli sebi više prava | `CHILD_HEALTH_*` je odvojena, eksplicitno dodeljena i auditovana permisija; OWNER se ne dodeljuje kroz API |
| Zaposleni drugog vrtića | da pristupi tuđem tenant-u | RLS po `organization_id`; membership provera; 404 semantika |
| Platform admin / support | da pristupi podacima dece bez razloga | nema automatskog pristupa; `support_access_grants` sa rokom, opsegom, odobrenjem, MFA i auditom |
| Napadač sa ukradenim tokenom | replay refresh tokena, dug život sesije | rotacija, detekcija ponovne upotrebe → opoziv cele sesije, kratki access TTL, online provera opoziva |
| Napadač sa dump-om baze | lozinke, tokeni, zdravstveni podaci | Argon2id, samo hash tokena, envelope šifrovanje payload-a, ključevi u KMS-u |
| Insajder sa DB pristupom | menjanje audita | append-only trigger + eksport audita u write-once storage (ne štiti sam po sebi od DBA – vidi §8) |

## 2. Autentifikacija

### 2.1 Strategija: opaque sesije

| Element | Vrednost (početna) | Gde |
|---|---|---|
| Access token | 10 minuta, 32 nasumična bajta, prefiks `vca_` | `access_tokens.token_hash` (sha256) |
| Refresh token | apsolutno 30 dana, idle 7 dana, prefiks `vcr_` | `refresh_tokens`, jedan aktivan po sesiji |
| Reset lozinke | 15 minuta, prefiks `vcp_` | `password_reset_tokens` |
| Verifikacija e-maila | 24 sata, prefiks `vce_` | `email_verification_tokens` |
| Pozivnica | 7 dana (predlog), prefiks `vci_` | `invitations.token_hash` |

- U bazi je **samo sha256 hash** tokena. Sirovi token postoji samo u odgovoru klijentu, odnosno u e-mail linku.
- **Refresh rotation**: `POST /auth/refresh` u jednoj transakciji radi `UPDATE refresh_tokens SET consumed_at = now(), replaced_by_id = <novi> WHERE token_hash = ? AND consumed_at IS NULL` (atomic consume) i izdaje novi par. Ako `UPDATE` pogodi 0 redova, a token postoji sa `consumed_at IS NOT NULL` → **ponovna upotreba**: upisuje se `reuse_detected_at`, cela sesija se opoziva (`sessions.revoked_at`, razlog `REUSE_DETECTED`), svi access tokeni sesije se opozivaju, korisnik dobija bezbednosnu notifikaciju.
- **Online provera opoziva**: svaki zahtev traži access token po hash-u i proverava `expires_at`, `revoked_at`, `sessions.revoked_at`, `sessions.absolute_expires_at`, `users.status`. Nema lokalnog keša tokena u P0.
- Sesije: lista uređaja (`GET /auth/sessions`), opoziv jedne (`DELETE /auth/sessions/{id}`), odjava svih (`POST /auth/logout-all`), promena lozinke opoziva sve ostale sesije.
- **Reauthentication** (`POST /auth/reauthenticate`) osvežava `sessions.reauthenticated_at`; osetljive akcije (promena e-maila/lozinke, opoziv staratelja, čitanje zdravstvenih podataka od strane osoblja, billing) zahtevaju reauth u poslednjih 10 minuta.
- **MFA (TOTP)**: obavezna za platform admine, vlasnike i support pristup; sesija bez `mfa_verified_at` može samo da završi MFA. Tajna je envelope-šifrovana (`user_mfa_methods.secret_enc`, `key_id`); recovery kodovi su hash-ovani, jednokratni.

### 2.2 Web (cookie režim)

- Access i refresh token se šalju kao `HttpOnly; Secure; SameSite=Lax` kolačići sa `Path` ograničenjem (`/api`, refresh samo na `/api/v1/auth/refresh`).
- CSRF: double-submit token u `X-CSRF-Token` zaglavlju koji klijent dobija sa `GET /api/v1/auth/csrf` + **exact `Origin` provera** (poređenje sa `APP_WEB_ORIGIN` bez wildcard-a) za sve mutacije. `SameSite` sam nije dovoljan.
- Nikada tokena u `localStorage`/`sessionStorage`. Dozvoljeno je čuvati samo izabranu lokalizaciju i UI preferencije.
- CSP (predlog za nginx): `default-src 'self'; img-src 'self' blob: data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'`.

### 2.3 Mobile (bearer režim)

- `Authorization: Bearer vca_...`; tokeni samo u Keychain (iOS) / Android Keystore-zaštićenom skladištu. Nikada u SharedPreferences/NSUserDefaults u čistom tekstu, nikada u logovima ili crash izveštajima.
- Single-flight refresh (mutex): paralelni zahtevi čekaju jedno osvežavanje, nema utrke sa rotacijom.
- Device ID (`sessions.device_id`) je nasumičan, generisan u aplikaciji – ne koristi se hardverski identifikator.

### 2.4 Lozinke

- **Argon2id**, verzija 19, jedinstven 16-bajtni salt, 32-bajtni izlaz, PHC format (`$argon2id$v=19$m=...,t=...,p=...$salt$hash`).
- Parametri iz konfiguracije; početno `m=64 MiB, t=3, p=1`; produkcione vrednosti se podešavaju komandom `benchmark-argon2` na ciljnom serveru (cilj 250–500 ms po hešu). `needsRehash` omogućava tihi upgrade pri sledećoj prijavi.
- Politika: minimum 10 znakova, provera protiv liste najčešćih lozinki (planirano), bez obaveznih „specijalnih znakova”.
- **Generičke poruke**: prijava, zaboravljena lozinka i registracija preko pozivnice vraćaju isti odgovor bez obzira na to da li e-mail postoji. Vreme odgovora se izjednačava (hash se računa i za nepostojeći nalog).
- **Brute force**: `login_attempts` (hash e-maila + IP) + Ktor RateLimit: 5 neuspeha po nalogu u 15 min → privremeno zaključavanje (`users.locked_until`, eksponencijalno); 20 zahteva po IP-ju u minuti na auth rutama → `429` sa `Retry-After`.

## 3. Autorizacija

### 3.1 Slojevi

1. **Sesija** (401).
2. **Tenant članstvo**: `organization_memberships` sa `status = 'ACTIVE'` za `{organizationId}` iz putanje; inače 404.
3. **RLS** u bazi: transakcija sa `app.organization_id` – čak i greška u kodu ne može vratiti tuđe redove.
4. **Permisija po roli** (`PermissionMatrix`) + dodatne permisije (`membership_permissions`): 403.
5. **Resource policy** unutar tenant-a: vaspitač → samo grupe iz važećih dodela; roditelj → samo deca iz `guardians` sa `status = 'CONFIRMED'`; zdravstveni podaci → `CHILD_HEALTH_READ` ili roditelj sa `can_view_health`; tuđi resurs → 404.
6. **Feature flag / entitlement**: proverava se posle autorizacije; flag nikad ne zamenjuje dozvolu.

### 3.2 Matrica dozvola (izvor istine: `backend/src/main/kotlin/com/vrticconnect/authz/Permissions.kt`)

| Permisija | OWNER | ADMIN | TEACHER | PARENT | Napomena |
|---|:-:|:-:|:-:|:-:|---|
| ORG_SETTINGS_MANAGE | ✔ | – | – | – | |
| LOCATION_MANAGE, GROUP_MANAGE | ✔ | ✔ | – | – | |
| MEMBER_INVITE / MANAGE / REVOKE, TEACHER_ASSIGN | ✔ | ✔ | – | – | ADMIN ne može dodeliti ADMIN/OWNER; OWNER se nikad ne dodeljuje kroz API |
| CHILD_READ | ✔ | ✔ | ✔ (svoje grupe) | ✔ (svoja deca) | lista dece nikad ne sadrži zdravstvene podatke |
| CHILD_MANAGE, GUARDIAN_MANAGE | ✔ | ✔ | – | – | |
| PICKUP_PERSON_READ | ✔ | ✔ | ✔ | ✔ | |
| PICKUP_PERSON_MANAGE | ✔ | ✔ | – | ✔ (svoje dete) | |
| CHILD_HEALTH_READ / WRITE | ✱ | ✱ | ✱ | ✔ (svoje dete, `can_view_health`) | ✱ = samo eksplicitno dodeljena permisija, auditovana sa `purpose` |
| SCHEDULE_READ | ✔ | ✔ | ✔ | ✔ | |
| SCHEDULE_MANAGE | ✔ | ✔ | – | ✔ (`can_manage_schedule`) | |
| ABSENCE_READ | ✔ | ✔ | ✔ | ✔ | |
| ABSENCE_REPORT | ✔ | ✔ | – | ✔ (`can_report_absence`) | |
| ATTENDANCE_READ | ✔ | ✔ | ✔ | ✔ (svoje dete) | |
| ATTENDANCE_RECORD | ✔ | ✔ | ✔ | – | |
| ATTENDANCE_CORRECT | ✔ | ✔ | ✱ | – | korekcija zahteva razlog |
| ANNOUNCEMENT_READ | ✔ | ✔ | ✔ | ✔ | |
| ANNOUNCEMENT_MANAGE | ✔ | ✔ | – | – | |
| ANNOUNCEMENT_PUBLISH | ✔ | ✔ | ✱ | – | |
| CALENDAR_READ / MENU_READ | ✔ | ✔ | ✔ | ✔ | |
| CALENDAR_MANAGE / MENU_MANAGE | ✔ | ✔ | – | – | |
| PHOTO_VIEW | ✔ | ✔ | ✔ | ✔ (audience + consent) | |
| PHOTO_UPLOAD | ✔ | ✔ | ✔ | – | |
| PHOTO_PUBLISH | ✔ | ✔ | ✱ | – | zahteva saglasnosti za svu označenu decu |
| CONSENT_GIVE | – | – | – | ✔ (`can_give_consent`) | |
| CONSENT_MANAGE_POLICIES | ✔ | ✔ | – | – | |
| MESSAGE_SEND | ✔ | ✔ | ✔ | ✔ | učesnike određuje server |
| BILLING_VIEW | ✔ | – | – | – | |
| BILLING_MANAGE | ✔ | ✱ | – | – | |
| REPORT_VIEW | ✔ | ✔ | – | – | |
| REPORT_EXPORT | ✔ | ✱ | – | – | |
| AUDIT_READ | ✔ | ✔ | – | – | |

SUPER_ADMIN (globalna tabela `platform_admins`) ima **samo** platformske operacije (`/platform/*`): vrtići, planovi, pretplate, agregirana statistika, feature flagovi, sanitizovani logovi. Nema tenant permisija.

### 3.3 Support pristup

`support_access_grants`: razlog + ticket, allowlist opsega, `read_only` podrazumevano, `approval_kind = OWNER_APPROVED` (vlasnik odobrava u web panelu) ili `EMERGENCY` sa obaveznim obrazloženjem i naknadnim obaveštenjem vlasnika, rok ≤ 72 h, MFA/reauth pre svakog ulaska, svaki zahtev auditovan sa `purpose`. Tokom support sesije baza radi u tenant kontekstu tog vrtića, ne u `platform_mode`.

## 4. Multi-tenancy u bazi

- Runtime rola `app_runtime`: `NOSUPERUSER NOBYPASSRLS`, nije vlasnik ni jednog objekta, nema DDL, nema `DELETE` osim na tabelama tokena/idempotentnosti.
- Sve tenant tabele: `ENABLE` + `FORCE ROW LEVEL SECURITY`, politika `organization_id = app.current_organization_id()` za `USING` i `WITH CHECK`. NULL kontekst → 0 redova i odbijen upis.
- Vlasnik tabela (`app_owner`) je takođe vezan (FORCE) i mora eksplicitno ući u `app.maintenance_mode` po transakciji (migracije, seed, ispravke) – i to je vidljivo u DB logu.
- Globalne tabele: `users` (self, član istog tenant-a, `auth_mode`, `platform_mode`), `sessions` i tokeni (`auth_mode`, ili vlasnik sesije za listu/opoziv), `audit_log` (insert uvek; čitanje po tenant-u/platformi), `notifications`/`device_push_tokens` (primalac).
- Test pokrivenosti: `RlsIntegrationTest` (dva tenant-a, bez konteksta, cross-tenant upis, ponovna upotreba konekcije posle COMMIT i ROLLBACK, owner bez maintenance režima) + `docs/database/tests/rls_negative_tests.sql`.

## 5. Zaštita osetljivih podataka

| Podatak | Mera |
|---|---|
| Zdravstveni profil deteta | odvojena tabela `child_health_profiles`, JSON payload šifrovan AES-256-GCM data ključem, data ključ umotan KMS ključem (envelope); `payload_key_id` omogućava rotaciju; čitanje i izmena upisuju `audit_log` sa `purpose`; nikad u listi dece, nikad u push porukama, nikad offline |
| TOTP tajne | envelope šifrovanje kao gore |
| Lozinke | Argon2id |
| Tokeni | samo sha256 hash |
| Fotografije i dokumenti | privatni S3 bucket (block public access), SSE-KMS, `files.status` quarantine → scanning → ready, provera stvarnog MIME tipa, limit veličine (10 MB slika, 25 MB dokument – predlog), EXIF uklanjanje, thumbnails sa istim dozvolama, kratkotrajni signed URL (≤ 5 min) ili autentifikovani streaming proxy |
| JMBG, fotografije dokumenata | ne prikupljaju se u P0/P1; eventualno uvođenje zahteva zasebnu DPIA |
| Logovi | bez lozinki, tokena, signed URL-ova, medicinskih podataka, sadržaja poruka, nepotrebnih ID-jeva dece; `CallLogging` loguje samo metod, putanju i status, uz `requestId` |

Šifrovanje u mirovanju na nivou diska (RDS/EBS/S3) je obavezno, ali nije zamena za šifrovanje polja: dump baze ili backup ne sme otkriti zdravstvene podatke bez KMS ključa.

## 6. Fotografije: publika i saglasnost su dve provere

1. **Audience** (`photo_audiences`): ko sme da vidi (grupa, dete, konkretni staratelj).
2. **Consent** (`consent_decisions` nad `consent_policies` sa verzijom i hash-om teksta): da li sme da se distribuira. Za grupnu fotografiju osoblje mora označiti svu prepoznatljivu decu (`photo_tagged_children`); objavljivanje zahteva važeću `GRANTED` odluku za **svako** označeno dete na aktuelnoj verziji politike. Bez prepoznavanja lica.
3. Povlačenje saglasnosti (`withdrawn_at`) blokira novu distribuciju: fotografija prelazi u `WITHDRAWN`, signed URL-ovi se više ne izdaju, thumbnails takođe.
4. **Ograničenje**: već preuzeta fotografija ili screenshot na tuđem uređaju ne može se povući, a već izdati signed URL važi do isteka (zato je kratak). Ovo se komunicira roditeljima u tekstu saglasnosti.

## 7. Audit

`audit_log` (append-only trigger; runtime ima samo INSERT/SELECT) sadrži: `actor_user_id`, `actor_membership_id`, `organization_id`, `action`, `entity_type`, `entity_id`, `request_id`, `result`, `purpose`, `metadata` (samo allowlisted ključevi, nikad payload). Obavezno se audituju: izmene deteta, grant/revoke staratelja, promene dozvola i rola, korekcije rasporeda i prisustva, čitanje/izmena zdravstvenih podataka, eksport, support pristup, odluke o saglasnosti, prijave/odjave/opozivi sesija, MFA promene.

**Ograničenje**: append-only tabela štiti od aplikativnih grešaka i običnih korisnika, ne od privilegovanog DBA ili napadača sa superuser pristupom. Za dokaznu vrednost: periodičan eksport u write-once storage (S3 Object Lock) sa hash lancem, pristup superuser-u samo preko break-glass procedure sa logovanjem na nivou infrastrukture.

## 8. Privatnost (GDPR / ZZPL) – tehničke mere

- **Minimizacija**: samo polja iz specifikacije; bez JMBG-a; opciona fotografija deteta.
- **Svrha obrade**: evidentirana po tipu podatka (dokument u pripremi uz pravnika); `consent_policies.purpose` za slučajeve gde je osnov saglasnost.
- **Prava lica**: `privacy_requests` (pristup, eksport, brisanje, ispravka) sa verifikacijom identiteta (`identity_verified_at`), rokom i statusom; eksport (`data_exports`) je privatan fajl sa rokom i **ne sme** sadržati tuđe dete niti privatne podatke drugog staratelja (poruke drugog staratelja sa vrtićem se ne izvoze).
- **Brisanje ≠ soft delete**: `deleted_at` samo sakriva; pravo brisanje anonimizuje/uklanja lične podatke i upisuje `erasure_ledger`, koji se ponovo primenjuje posle restore-a backup-a.
- **Retention** (predlog, potvrditi): sesije/tokeni 30 dana posle isteka; `login_attempts` 90 dana; attendance 3 godine (evidencija za vrtić); poruke 1 godina; fotografije do kraja upisa + 90 dana; audit 5 godina; backup 35 dana. `legal_holds` zamrzava brisanje kada je opravdano.
- **EU hosting sam po sebi nije usklađenost**: potrebni su ugovor o obradi sa vrtićem (vrtić je rukovalac, platforma obrađivač), evidencija obrade, DPIA za zdravstvene podatke i fotografije dece.

## 9. Infrastrukturne mere (detalji u INFRASTRUCTURE.md)

TLS 1.2+ svuda; baza u privatnoj mreži bez javnog IP-ja; tajne u Secrets Manager/SSM, nikad u repozitorijumu (`.env` je git-ignorisan, `.env.example` bez vrednosti); least privilege IAM; backup sa PITR; dependency i secret scanning u CI; immutable slike; odvojene DB role po procesu.

## 10. Šta je u skeletonu, a šta nije

| Mera | Status |
|---|---|
| Odvojene DB role, RLS ENABLE+FORCE, maintenance režim vlasnika | implementirano (V1 migracija) – provera u VALIDATION_REPORT.md |
| Transakcioni tenant kontekst (`set_config(..., true)`) | implementirano (`Database.kt`) + integracioni test |
| problem+json, request id, sanitizovano logovanje, bezbednosna zaglavlja | implementirano |
| Argon2id hasher, generator tokena, sha256 | implementirano + testovi |
| Matrica dozvola | implementirana u kodu + test; provera na rutama tek u EPIC 02/03 |
| Auth rute | zatvorene (501), bez tokena |
| Sesije, refresh, CSRF, MFA, rate limit | projektovano – EPIC 02 |
| Envelope šifrovanje, S3, audit servis, privacy tokovi | projektovano – EPIC 05/15/17 |
