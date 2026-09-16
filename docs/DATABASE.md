# Model podataka – Vrtić Connect

> Izvor istine za kompletan model je [database/schema.sql](database/schema.sql) (predlog za sve EPIC-e). Izvršne migracije su u `backend/src/main/resources/db/migration/` i uvode se po EPIC-ima kao podskup predloga. **Nikada ne izvršavati `schema.sql` nad bazom na kojoj su već primenjene Flyway migracije.** Šta je stvarno primenjeno i provereno: [VALIDATION_REPORT.md](VALIDATION_REPORT.md).

## 1. Principi

| Princip | Kako je sproveden |
|---|---|
| Jedna baza, shared schema `app` | ništa u `public`; `search_path` fiksiran po roli |
| Tenant kolona | `organization_id uuid NOT NULL` na svakoj tenant tabeli |
| Kompozitni FK | svaka tenant tabela ima `UNIQUE (id, organization_id)`; deca je referenciraju sa `FOREIGN KEY (x_id, organization_id)` → red ne može pokazivati na entitet drugog vrtića čak ni greškom u kodu |
| RLS | `ENABLE` + `FORCE` na svim tenant tabelama; politike po roli (`app_runtime`, `app_worker`, `app_owner`) |
| Tri role | `app_owner` (DDL, migracije), `app_runtime` (API), `app_worker` (relay) – sve `NOBYPASSRLS`, samo owner je vlasnik objekata |
| Identifikatori | `uuid` (`gen_random_uuid()`); `bigint identity` samo za append-only logove |
| Vreme | `timestamptz` (UTC instant), `date`, `time` (lokalno vreme u zoni vrtića) |
| Lifecycle | `deleted_at` (soft delete, samo sakrivanje), `revoked_at`/`status` (opoziv sa istorijom), `withdrawn_at` (saglasnosti) |
| Neprekrivajući periodi | `EXCLUDE USING gist (... daterange(...) WITH &&)` (btree_gist) za upise, dodele vaspitača, šablone rasporeda i odsustva |
| Verzionisanje | `version int` na entitetima koje klijenti menjaju (deca, raspored, odsustva, objave, meni, prisustvo) – `If-Match` |

## 2. Pregled tabela po oblasti

### 2.1 Globalni identitet i autentifikacija (bez `organization_id`)

| Tabela | Namena | Ključna ograničenja |
|---|---|---|
| `users` | nalog (e-mail `citext`, Argon2id hash, locale, status, brojač neuspeha) | `UNIQUE(email)`; `password_hash` NULL dok pozvani ne postavi lozinku |
| `email_verification_tokens`, `password_reset_tokens` | jednokratni tokeni (samo hash) | `token_hash UNIQUE`, `expires_at`, `consumed_at` |
| `sessions` | jedan uređaj/browser = jedna sesija (session family) | `absolute_expires_at`, `revoked_at` + razlog, `mfa_verified_at`, `reauthenticated_at` |
| `refresh_tokens` | rotirajući refresh; lanac `replaced_by_id` | tačno jedan aktivan po sesiji (parcijalni unique), `reuse_detected_at` |
| `access_tokens` | opaque kratkotrajni tokeni | `token_hash UNIQUE`, indeks po `expires_at` za čišćenje |
| `user_mfa_methods`, `user_mfa_recovery_codes` | TOTP (šifrovana tajna, `key_id`) i hash-ovani recovery kodovi | jedan aktivan TOTP po korisniku |
| `login_attempts` | brute-force evidencija po hash-u e-maila i IP-ju | bez sirovog e-maila |
| `platform_admins` | SUPER_ADMIN (globalno, nije članstvo) | `revoked_at` |

### 2.2 Organizacije i članstva

| Tabela | Namena | Ključna ograničenja |
|---|---|---|
| `organizations` | vrtić kao tenant; `timezone` (IANA), `default_locale`, `status` | `slug UNIQUE` |
| `organization_settings` | tipovana podešavanja 1:1 (rok za promenu rasporeda, tolerancija kašnjenja, radni dani, TTL offline keša) | check ograničenja opsega |
| `organization_memberships` | **rola pripada članstvu** (`OWNER/ADMIN/TEACHER/PARENT`), status `INVITED/ACTIVE/SUSPENDED/REVOKED` | `UNIQUE(organization_id, user_id, role)`, `UNIQUE(id, organization_id)` |
| `membership_permissions` | dodatne permisije (npr. `CHILD_HEALTH_READ`) sa `granted_by`, `revoked_at` | allowlist vrednosti; jedna aktivna po (članstvo, permisija) |
| `invitations` | pozivnice za osoblje i staratelje (staratelj nosi `child_id`) | `CHECK ((role='PARENT') = (child_id IS NOT NULL))` |
| `support_access_grants` | vremenski ograničen support pristup sa razlogom, ticket-om, opsegom, odobrenjem | ≤ 72 h; `EMERGENCY` zahteva obrazloženje, inače `approved_by` |

### 2.3 Struktura vrtića

| Tabela | Namena | Ključna ograničenja |
|---|---|---|
| `locations` | objekti; opcioni override zone | jedinstveno ime po vrtiću (case-insensitive, među neobrisanim) |
| `groups` | grupe u objektu; uzrast, kapacitet | FK `(location_id, organization_id)`; jedinstveno ime po objektu |
| `employees` | profil zaposlenog vezan 1:1 za `OWNER/ADMIN/TEACHER` članstvo | `UNIQUE(membership_id)` |
| `group_teacher_assignments` | dodela vaspitača grupi sa periodom važenja i ulogom (`LEAD/ASSISTANT/SUBSTITUTE`) | EXCLUDE preklapanja po (zaposleni, grupa) među neopozvanim |

### 2.4 Deca, upisi, staratelji, zdravlje

| Tabela | Namena | Ključna ograničenja |
|---|---|---|
| `children` | ime, prezime, datum rođenja, fotografija (FK na `files`), status, **ne-osetljive** napomene, `version` | `date_of_birth > 2000-01-01` |
| `enrollments` | istorija upisa; dete je u tačno jednoj grupi u datom periodu | EXCLUDE preklapanja po detetu za `PLANNED/ACTIVE` |
| `guardians` | veza PARENT članstvo ↔ dete, status `PENDING/CONFIRMED/REVOKED`, prava (`can_manage_schedule`, `can_report_absence`, `can_give_consent`, `can_view_health`), `confirmed_by` (osoblje) | jedinstvena neopozvana veza; CONFIRMED zahteva `confirmed_by/at` |
| `pickup_persons` | ovlašćene osobe za preuzimanje (P0 lista) | period važenja, `status` |
| `child_health_profiles` | **odvojen**, envelope-šifrovan JSON (`payload_enc`, `payload_key_id`), `has_critical_alert` kao jedini nešifrovan indikator, `version` | 1:1 sa detetom; čitanje/izmena → `audit_log` sa `purpose` |

### 2.5 Raspored

| Tabela | Namena | Ključna ograničenja |
|---|---|---|
| `schedule_templates` + `schedule_template_days` | ponavljajući nedeljni šablon sa `effective_from/to`; po danu `attends`, `arrival_time`, `departure_time` | EXCLUDE preklapanja šablona po detetu; `UNIQUE(template_id, weekday)`; vremena obavezna kad `attends` |
| `schedule_day_overrides` | izuzetak za konkretan datum, `is_late_change` | jedan aktivan po (dete, datum) |
| `closure_days` | neradni dani za vrtić ili objekat | parcijalni unique po vrtiću/objektu |
| `daily_plans` | **zamrznuto** očekivanje po detetu i datumu (`is_expected`, vremena, `source` = CLOSURE/ABSENCE/OVERRIDE/TEMPLATE/NONE, `frozen_at`) | `UNIQUE(child_id, plan_date)` |
| `schedule_change_log` | poslovni feed promena (ko, kada, da li kasno) | append-only |

Prioritet pri izračunu `daily_plans`: 1) `closure_days`, 2) aktivno odsustvo, 3) override za dan, 4) šablon, 5) nema očekivanog dolaska. Redovi sa `frozen_at` se ne preračunavaju → promena šablona nije retroaktivna.

### 2.6 Odsustva i prisustvo

| Tabela | Namena | Ključna ograničenja |
|---|---|---|
| `absences` | `SICK/VACATION/OTHER`, inkluzivan period, `ACTIVE/CANCELLED`, ko je prijavio/otkazao | EXCLUDE preklapanja aktivnih po detetu; ≤ 365 dana |
| `attendance_days` | projekcija stanja po detetu i datumu: `status` (`NOT_ARRIVED/CHECKED_IN/CHECKED_OUT`), `absence_kind` (kontekst, nezavisan od statusa), kopirano očekivanje, `is_unscheduled`, `open_visit_id`, `version` | `UNIQUE(child_id, attendance_date)`; `CHECK ((status='CHECKED_IN') = (open_visit_id IS NOT NULL))` |
| `attendance_visits` | poseta = par dolazak/odlazak; više poseta dnevno | najviše jedna otvorena po danu (parcijalni unique) |
| `attendance_events` | **nepromenljiv** log: `CHECK_IN/CHECK_OUT/CORRECTION/ABSENCE_MARKED/ABSENCE_CLEARED/UNSCHEDULED_PRESENT`, `occurred_at` (poslovno vreme), `recorded_at`, `command_id` (idempotency, `UNIQUE`), `source` (`MOBILE/WEB/OFFLINE_SYNC/SYSTEM`), `resulting_version`, korekcija sa razlogom | append-only trigger |
| `idempotency_keys` | generički store za ostale komande (`Idempotency-Key`), 24 h | `UNIQUE(user_id, scope, key)` |

Prelazi: `NOT_ARRIVED → CHECKED_IN` (nova poseta), `CHECKED_IN → CHECKED_OUT` (zatvara posetu), `CHECKED_OUT → CHECKED_IN` (nova poseta, `sequence_no + 1`). `CHECK_OUT` bez otvorene posete → 409. Odsustvo za prisutno dete postavlja samo `absence_kind`, ne menja `status`.

### 2.7 Obaveštenja i notifikacije

| Tabela | Namena |
|---|---|
| `announcements` | objava (`DRAFT/PUBLISHED/ARCHIVED`, `publish_at`, `expires_at`, `version`) |
| `announcement_audiences` | pravilo publike (vrtić/objekat/grupa/staratelj) – tačno jedan tip po redu (CHECK) |
| `announcement_attachments` | slike i prilozi (FK na `files`) |
| `announcement_recipients` | **snapshot** primalaca pri objavi + `read_at` (eksplicitno čitanje); pri svakom pristupu se dodatno proverava trenutno pravo |
| `notifications` | in-app inbox: `kind`, i18n ključ + argumenti (bez osetljivog sadržaja), referenca na entitet, `dedup_key` |
| `device_push_tokens` | FCM/APNs tokeni po korisniku/sesiji, `invalidated_at` |
| `notification_deliveries` | pokušaji isporuke po kanalu sa retry/backoff, `DEAD` posle N pokušaja |
| `outbox_events` | transakcioni outbox (`PENDING/PROCESSED/DEAD`) |

### 2.8 Poruke, kalendar, jelovnik (P1)

`conversations` (uvek o jednom detetu; `PARENT_TEACHER` ili `PARENT_ADMIN`), `conversation_participants` (server ih upravlja iz guardian veza i dodela; `left_at` kad pravo prestane; `last_read_message_id`), `messages` (`client_message_id` za idempotentno ponovno slanje), `calendar_events` (all-day ili timed, opseg vrtić/objekat/grupa, opciona zahtevana saglasnost), `menu_days` + `menu_items` (`BREAKFAST/SNACK_AM/LUNCH/SNACK_PM`; `allergen_tags` su informativni, nisu medicinska preporuka).

### 2.9 Fajlovi, fotografije, saglasnosti (P1)

`files` (metapodaci; `status` PENDING_UPLOAD→QUARANTINED→SCANNING→READY/REJECTED; `detected_mime`, `sha256`; bajtovi samo u S3), `file_variants` (thumbnails, iste dozvole), `photos` (`DRAFT/PUBLISHED/WITHDRAWN`), `photo_audiences` (ko sme da vidi), `photo_tagged_children` (prepoznatljiva deca), `consent_policies` (svrha, verzija, locale, tekst + `wording_hash`, `retired_at`), `consent_decisions` (`GRANTED/DECLINED`, `withdrawn_at`, `evidence`). Aktuelna saglasnost = poslednja nepovučena `GRANTED` odluka na nepovučenoj verziji politike.

### 2.10 Dokumenti (P2), pretplate, feature flagovi, audit, privatnost

`documents`/`document_versions` (`content_hash`)/`document_requests`/`document_responses` (klik-prihvatanje sa dokazima; **nije** kvalifikovani e-potpis); `plans` (verzionisan katalog, `monthly_price_minor` u minor jedinicama), `subscriptions` (jedna aktuelna po vrtiću), `subscription_events`; `feature_flags` (`default_enabled`, `kill_switch`) + `organization_feature_overrides`; `audit_log` (append-only), `privacy_requests`, `data_exports`, `erasure_ledger`, `retention_policies` (predlog rokova), `legal_holds`.

Efektivni flag = `NOT kill_switch AND COALESCE(override, entitlement plana, default)`. Flag nije autorizacija.

## 3. Šta garantuje baza, a šta aplikaciona transakcija

| Garancija | Baza | Aplikacija |
|---|---|---|
| Red pripada tenant-u iz konteksta | RLS `USING`/`WITH CHECK` | postavljanje konteksta tek posle provere članstva |
| Dete ne može referencirati grupu drugog vrtića | kompozitni FK | – |
| Nema dva preklapajuća upisa/odsustva/dodele | EXCLUDE ograničenja | poruka 409 sa objašnjenjem |
| Jedan aktivan refresh token po sesiji | parcijalni unique | atomic consume + rotate u jednoj transakciji, detekcija reuse-a |
| Attendance komanda se primenjuje tačno jednom | `UNIQUE(command_id)` | `SELECT ... FOR UPDATE` na `attendance_days`, provera `expectedVersion`, upis događaja + projekcije u istoj transakciji |
| Najviše jedna otvorena poseta | parcijalni unique | prelaz stanja u servisu, 409 za nedozvoljen prelaz |
| Audit se ne menja | trigger + bez DELETE/UPDATE privilegija | eksport u write-once storage (ne štiti od DBA) |
| Zdravstveni payload nečitljiv iz dump-a | – (samo bytea) | envelope šifrovanje sa KMS ključem |
| Saglasnost za svu označenu decu pre objave | – | provera u servisu pre `PUBLISHED`; povlačenje → `WITHDRAWN` |
| Vaspitač vidi samo dodeljene grupe u periodu | – (RLS je po vrtiću) | resource policy nad `group_teacher_assignments` |
| Roditelj vidi samo svoju decu | – | resource policy nad `guardians` `CONFIRMED` |
| Kontekst ne curi kroz pool | `set_config(..., true)` resetuje na kraju transakcije | uvek se postavljaju sve četiri vrednosti eksplicitno |

## 4. RLS politike – sažetak

| Tabela / grupa | Politika za `app_runtime` |
|---|---|
| sve tenant tabele | `organization_id = app.current_organization_id()` (USING + WITH CHECK) |
| `organization_memberships` | + SELECT sopstvenih članstava (`user_id = app.current_user_id()`) |
| `organizations` | SELECT: tekući tenant, ili aktivno članstvo korisnika, ili `platform_mode`; UPDATE: tekući tenant/platforma; INSERT: samo platforma |
| `users` | SELECT: sebe, `auth_mode`, `platform_mode`, ili član tekućeg tenant-a; UPDATE: sebe/auth/platforma; INSERT: auth/platforma |
| tokeni, MFA, `login_attempts`, `platform_admins` | samo `auth_mode` |
| `sessions` | `auth_mode`, ili vlasnik sesije (SELECT/UPDATE za opoziv) |
| `notifications`, `device_push_tokens` | primalac/vlasnik |
| `idempotency_keys` | vlasnik |
| `audit_log` | INSERT uvek; SELECT po tenant-u ili platforma |
| `plans`, `feature_flags` | SELECT svi; izmena samo platforma |
| `outbox_events` | runtime samo INSERT; `app_worker` sve |
| sve RLS tabele | `app_owner` samo uz `app.maintenance_mode()` (opt-in po transakciji) |

Vlasnik (`app_owner`) bez maintenance režima ne vidi ni jedan tenant red – to je namerno i testirano.

## 5. Migracije

- Flyway, `schemas=app`, `defaultSchema=app`, placeholder zamena isključena (SQL sadrži `$$`).
- `V1__foundation.sql` (EPIC 01): funkcije konteksta, identitet/auth, organizacije, članstva, permisije, pozivnice, support pristup, objekti/grupe/zaposleni/dodele, `idempotency_keys`, `outbox_events`, `audit_log`, `plans`/`subscriptions`/`subscription_events`, `feature_flags`/`organization_feature_overrides`, RLS politike, `owner_maintenance` politike, privilegije, referentni feature flagovi.
- Plan sledećih migracija po EPIC-ima je u [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md) (npr. V2 = children/enrollments/guardians/pickup/health + FK `invitations.child_id`).
- Pravila: samo expand/contract (dodaj kolonu → migriraj podatke → ukloni staru u kasnijoj verziji), bez `DROP` u istoj verziji sa deploy-em koji ga još koristi; migracija se ne vraća unazad – aplikacija se vraća na prethodnu sliku koja mora raditi sa novom šemom.

## 6. Seed i testovi

- `docs/database/seed/dev_seed.sql`: Happy Kids (Novi Sad; Bubamare, Leptirići), drugi tenant za izolaciju, sintetička imena i `example.test` adrese, **odbija** izvršavanje ako je baza označena kao produkciona (`app.environment` GUC ≠ `dev`). Bez lozinki: `password_hash` je NULL dok EPIC 02 ne uvede hash iz lokalne konfiguracije.
- `docs/database/tests/rls_negative_tests.sql`: psql skripta koja mora da završi bez greške – proverava da runtime bez konteksta ne vidi ništa, da tenant A ne vidi B, da cross-tenant upis pada, da owner bez maintenance režima ne vidi tenant redove.
- `backend/.../RlsIntegrationTest.kt`: isto kroz Hikari pool veličine 1 (ponovna upotreba konekcije posle COMMIT/ROLLBACK).

## 7. Indeksi (najvažniji)

- `organization_memberships (user_id) WHERE status='ACTIVE'` – tenant switcher i RLS podupiti.
- `attendance_days (organization_id, group_id, attendance_date)` – dnevni pregled.
- `daily_plans (organization_id, group_id, plan_date)` – očekivana deca po grupi.
- `absences (organization_id, date_from, date_to) WHERE status='ACTIVE'` – preklapanja i pregled.
- `announcement_recipients (membership_id, read_at)` – nepročitano.
- `outbox_events (status, next_attempt_at, id) WHERE status='PENDING'` – relay.
- `access_tokens (token_hash)` unique – lookup na svakom zahtevu.

ER dijagrami: [diagrams/er-core.md](diagrams/er-core.md), [diagrams/er-schedule-attendance.md](diagrams/er-schedule-attendance.md), [diagrams/er-content.md](diagrams/er-content.md).
