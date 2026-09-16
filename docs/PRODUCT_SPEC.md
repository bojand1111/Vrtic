# Vrtić Connect – Specifikacija proizvoda (PRODUCT_SPEC)

> Status: **radna specifikacija, izvedena iz `docs/requirements/REQUIREMENTS_BRIEF.md`** (izvor istine za zahteve) i
> `docs/database/schema.sql` (izvor istine za model podataka). U slučaju neslaganja važi brief, a odstupanje se beleži
> odlukom u `docs/adr/`. Ovaj dokument opisuje **planirano** ponašanje proizvoda; ništa ovde ne tvrdi da je već
> implementirano ili verifikovano (stanje implementacije prati `docs/VALIDATION_REPORT.md` i `docs/DEVELOPMENT_ROADMAP.md`).
>
> Konvencije: dokumentacija je na srpskom (latinica); identifikatori u kodu, nazivi tabela, API putanje i enum vrednosti
> ostaju na engleskom. Nazivi tabela navedeni su kao u šemi `app.*`. API putanje se odnose na `docs/API.md`.

---

## Sadržaj

1. [Uvod i cilj proizvoda](#1-uvod-i-cilj-proizvoda)
2. [Rečnik pojmova](#2-rečnik-pojmova)
3. [Korisnici i uloge](#3-korisnici-i-uloge)
4. [Organizaciona struktura i multi-tenancy](#4-organizaciona-struktura-i-multi-tenancy)
5. [Funkcionalni zahtevi po domenu](#5-funkcionalni-zahtevi-po-domenu)
   - 5.1 [Autentifikacija i nalozi](#51-autentifikacija-i-nalozi)
   - 5.2 [Organizacija, objekti, grupe](#52-organizacija-objekti-grupe)
   - 5.3 [Zaposleni i dodele](#53-zaposleni-i-dodele)
   - 5.4 [Deca, upisi, staratelji, ovlašćene osobe](#54-deca-upisi-staratelji-ovlašćene-osobe)
   - 5.5 [Zdravstveni profil](#55-zdravstveni-profil)
   - 5.6 [Raspored](#56-raspored)
   - 5.7 [Odsustva](#57-odsustva)
   - 5.8 [Evidencija prisustva](#58-evidencija-prisustva)
   - 5.9 [Dnevni pregled za vaspitača](#59-dnevni-pregled-za-vaspitača)
   - 5.10 [Obaveštenja (announcements)](#510-obaveštenja-announcements)
   - 5.11 [Notifikacije i realtime](#511-notifikacije-i-realtime)
   - 5.12 [Poruke (P1)](#512-poruke-p1)
   - 5.13 [Kalendar (P1)](#513-kalendar-p1)
   - 5.14 [Jelovnik (P1)](#514-jelovnik-p1)
   - 5.15 [Fotografije i saglasnosti (P1)](#515-fotografije-i-saglasnosti-p1)
   - 5.16 [Dokumenti (P2)](#516-dokumenti-p2)
   - 5.17 [Pretplata i feature flagovi](#517-pretplata-i-feature-flagovi)
   - 5.18 [Privatnost i GDPR](#518-privatnost-i-gdpr)
   - 5.19 [Offline rad vaspitača](#519-offline-rad-vaspitača)
6. [Lokalizacija i vreme](#6-lokalizacija-i-vreme)
7. [Nefunkcionalni zahtevi](#7-nefunkcionalni-zahtevi)
8. [Opseg verzija: P0, P1, P2](#8-opseg-verzija-p0-p1-p2)
9. [Otvorena pitanja za vlasnika proizvoda](#9-otvorena-pitanja-za-vlasnika-proizvoda)

---

## 1. Uvod i cilj proizvoda

### 1.1 Šta je Vrtić Connect

Vrtić Connect je SaaS platforma za **privatne vrtiće u Srbiji**, sa planiranim širenjem na region (Hrvatska, BiH,
Slovenija, Severna Makedonija) i dalje na EU tržišta (nemačko i švedsko govorno područje su prvi kandidati zbog
lokalizacije planirane u briefu). Platforma povezuje pet vrsta učesnika:

| Učesnik | Šta dobija |
|---|---|
| Roditelji / staratelji | pregled rasporeda i prisustva svoje dece, prijava odsustva, obaveštenja iz vrtića, (kasnije) poruke, kalendar, jelovnik, fotografije uz saglasnost |
| Vaspitači | dnevni pregled grupe, evidencija dolazaka i odlazaka koja radi i bez interneta, lista odsustava, obaveštenja |
| Administracija vrtića | vođenje objekata, grupa, dece, staratelja, zaposlenih, obaveštenja i izveštaja |
| Vlasnici / direktori | sve što i administracija plus pretplata, statistika i upravljanje članstvima |
| Administrator SaaS platforme | vođenje vrtića kao klijenata, planova, pretplata, feature flagova i agregirane statistike – **bez** automatskog pristupa podacima dece |

### 1.2 Inspiracija i granice

Proizvod je funkcionalno inspirisan švedskim rešenjem Tempus Hemma (radni tok „raspored → prisustvo → dnevni pregled
vaspitača”). **Ne kopira se kod, brend, vizuelni identitet, nazivi ni dizajn** tog proizvoda; koriste se isključivo
opšti koncepti koje deli većina rešenja u ovoj kategoriji, prilagođeni srpskom tržištu (pravni okvir, jezik, ćirilica/latinica,
lokalni način rada vrtića).

### 1.3 Poslovni model

- Roditelji koriste aplikaciju **besplatno**.
- Vrtić (organizacija) plaća mesečnu pretplatu na nivou organizacije (planovi `STARTER`, `STANDARD`, `PRO`).
- U pilotu nema integracije sa platnim provajderom; pretplate se vode ručno (`subscriptions.provider = 'MANUAL'`).

### 1.4 Ciljevi pilota (P0)

1. Jedan ili više pilot vrtića (referentni seed: „Happy Kids”, Novi Sad, objekat sa grupama „Bubamare” i „Leptirići”)
   koriste platformu u svakodnevnom radu za raspored, prisustvo i obaveštenja.
2. Vaspitač može da evidentira prisustvo i kada tablet nema internet, bez gubitka i bez „poslednji piše pobeđuje” konflikata.
3. Podaci dece su strogo izolovani po vrtiću (tehničke mere opisane u odeljku 4 i 7).
4. Sistem je merljivo pouzdan (ciljevi: SLO 99,5 %, RPO 15 min, RTO 4 h – ciljevi koji se **dokazuju**, ne obećanja).

### 1.5 Šta proizvod NIJE

- Nije zamena za pedagošku dokumentaciju ni za zvanične evidencije koje zakon propisuje (može ih dopuniti izvozima).
- Nije medicinski sistem: zdravstveni profil i alergenske oznake su informativni, nisu medicinski savet.
- Nije sistem kvalifikovanog elektronskog potpisa (saglasnosti i dokumenti su „click-through” sa dokazima).
- Nije javna društvena mreža: nema javnih linkova ka fotografijama i sadržaju.

---

## 2. Rečnik pojmova

| Pojam (EN) | Srpski | Definicija | Tabela |
|---|---|---|---|
| **Organization** | Organizacija / vrtić | Zakupac (tenant) platforme. Pravno lice koje vodi jedan ili više objekata. Svi tenant podaci nose `organization_id`. | `organizations`, `organization_settings` |
| **Location** | Objekat | Fizička lokacija vrtića (adresa). Organizacija ima jedan ili više objekata. Može imati sopstvenu vremensku zonu (opciono). | `locations` |
| **Group** | Grupa | Vaspitna grupa unutar objekta (npr. „Bubamare”). Ima opcioni uzrasni raspon i kapacitet. | `groups` |
| **Child** | Dete | Osoba o kojoj se vode podaci. Dete **nema korisnički nalog**. Profil deteta postoji samo unutar jedne organizacije; ne povezuje se automatski između vrtića. | `children` |
| **Enrollment** | Upis | Period u kome dete pripada tačno jednoj grupi (`valid_from`–`valid_to`). Istorija upisa se čuva kao nepreklapajući periodi. | `enrollments` |
| **Guardian** | Staratelj | Veza između PARENT članstva i deteta, potvrđena od strane osoblja. Nosi pojedinačna ovlašćenja (raspored, odsustvo, saglasnost, zdravlje). | `guardians` |
| **Pickup person** | Ovlašćena osoba za preuzimanje | Osoba (bez naloga) koja sme da preuzme dete; lista se vodi po detetu. Digitalna verifikacija preuzimanja nije u pilotu. | `pickup_persons` |
| **User** | Korisnik / nalog | Globalni nalog (email + lozinka). Jedan korisnik može imati različite uloge u različitim organizacijama. | `users` |
| **Membership** | Članstvo | Veza korisnika i organizacije sa tačno jednom **ulogom**. Uloge pripadaju članstvu, nikada globalno korisniku. | `organization_memberships` |
| **Role** | Uloga | `OWNER`, `ADMIN`, `TEACHER`, `PARENT` na članstvu; `SUPER_ADMIN` je globalna (tabela `platform_admins`), nije članstvo. | `organization_memberships.role`, `platform_admins` |
| **Permission** | Dodatna dozvola | Fino-granularno pravo dodeljeno članstvu preko uloge (npr. `CHILD_HEALTH_READ`). Sposobnosti samih uloga definisane su u kodu (permission matrix u `docs/SECURITY.md`). | `membership_permissions` |
| **Employee** | Zaposleni | Kadrovski profil vezan 1:1 za OWNER/ADMIN/TEACHER članstvo. | `employees` |
| **Teacher assignment** | Dodela vaspitača | Dodela zaposlenog grupi sa periodom važenja i ulogom `LEAD`/`ASSISTANT`/`SUBSTITUTE`. Vaspitač vidi grupu samo unutar perioda. | `group_teacher_assignments` |
| **Invitation** | Pozivnica | Tokenizovani poziv u organizaciju (osoblje ili staratelj uz dete). | `invitations` |
| **Support access grant** | Odobrenje support pristupa | Vremenski ograničen, auditovan pristup platformskog osoblja jednoj organizaciji uz razlog, tiket, opseg i odobrenje. | `support_access_grants` |
| **Session** | Sesija | Jedna prijava na jednom uređaju/pregledaču („session family” za rotaciju refresh tokena). | `sessions` |
| **Access token** | Pristupni token | Kratkotrajni (10 min) neprozirni token; u bazi samo hash; proverava se na svakom zahtevu. | `access_tokens` |
| **Refresh token** | Token za osvežavanje | Rotirajući token; tačno jedan aktivan po sesiji; ponovna upotreba opoziva celu sesiju. | `refresh_tokens` |
| **Schedule template** | Nedeljni šablon rasporeda | Ponavljajući nedeljni plan dolazaka deteta koji važi od `effective_from`. | `schedule_templates`, `schedule_template_days` |
| **Day override** | Izmena za dan | Izuzetak za jedan konkretan datum koji nadjačava šablon. | `schedule_day_overrides` |
| **Closure day** | Neradni dan | Dan kada organizacija ili objekat ne radi. | `closure_days` |
| **Daily plan** | Dnevni plan | „Zamrznuto” očekivanje po detetu i datumu (da li se očekuje, kada). Istorijski redovi se ne preračunavaju. | `daily_plans` |
| **Late change** | Kasna izmena | Izmena rasporeda unesena posle roka koji vrtić podesi (`schedule_change_deadline_hours`). | `schedule_day_overrides.is_late_change`, `schedule_change_log` |
| **Absence** | Odsustvo | Prijavljeni izostanak deteta (`SICK`, `VACATION`, `OTHER`) za dan ili period. | `absences` |
| **Attendance day** | Dan prisustva | Projekcija stanja prisustva jednog deteta za jedan datum (`NOT_ARRIVED` / `CHECKED_IN` / `CHECKED_OUT`) sa verzijom. | `attendance_days` |
| **Attendance visit** | Poseta | Jedan boravak deteta u vrtiću (check-in → check-out). Dete može imati više poseta u danu. | `attendance_visits` |
| **Attendance event** | Događaj prisustva | Nepromenljiv zapis komande (`CHECK_IN`, `CHECK_OUT`, `CORRECTION`, ...). Izvor istine; projekcije se izvode iz njega. | `attendance_events` |
| **Command id / Idempotency key** | Identifikator komande | UUID koji klijent generiše; ponovljeno slanje iste komande ne pravi dupli efekat. | `attendance_events.command_id`, `idempotency_keys` |
| **Daily overview** | Dnevni pregled | Ekran vaspitača sa brojačima i listom dece grupe za jedan dan. | (izvedeno iz `daily_plans` + `attendance_days`) |
| **Announcement** | Obaveštenje | Objava administracije/vaspitača za publiku (organizacija, objekat, grupa, staratelj) sa praćenjem čitanja. | `announcements`, `announcement_audiences`, `announcement_recipients` |
| **Audience** | Publika | Pravilo ko sme da vidi sadržaj (obaveštenje, fotografiju). Nije isto što i saglasnost. | `announcement_audiences`, `photo_audiences` |
| **Notification** | Notifikacija | Zapis u in-app inbox-u korisnika; može biti isporučen i push/email kanalom. | `notifications`, `notification_deliveries` |
| **Outbox event** | Outbox događaj | Zapis o domenskom događaju upisan u istoj transakciji kao poslovna promena; radnik ga kasnije obrađuje. | `outbox_events` |
| **Conversation / Message** | Razgovor / poruka | Nit razgovora o jednom detetu (roditelj↔vaspitač ili roditelj↔administracija). Učesnike upravlja server. | `conversations`, `conversation_participants`, `messages` |
| **Calendar event** | Kalendarski događaj | Izlet, fotografisanje, predstava, praznik, sastanak, neradni dan; celodnevni ili sa vremenom. | `calendar_events` |
| **Menu day / item** | Jelovnik | Dnevni jelovnik sa obrocima `BREAKFAST`, `SNACK_AM`, `LUNCH`, `SNACK_PM`. | `menu_days`, `menu_items` |
| **File** | Fajl | Metapodaci o fajlu u privatnom object storage-u; bajtovi nikad nisu u bazi; nema javnih URL-ova. | `files`, `file_variants` |
| **Photo** | Fotografija | Objavljena slika sa publikom i označenom decom. | `photos`, `photo_audiences`, `photo_tagged_children` |
| **Consent policy** | Politika saglasnosti | Verzionisani tekst saglasnosti po svrsi i jeziku, sa hash-om teksta. | `consent_policies` |
| **Consent decision** | Odluka o saglasnosti | Odluka staratelja (`GRANTED`/`DECLINED`) za dete i politiku, sa dokazima i mogućnošću povlačenja. | `consent_decisions` |
| **Document / Document request / response** | Dokument / zahtev / odgovor | Verzionisani dokument i click-through prihvatanje sa hash-om verzije (P2). | `documents`, `document_versions`, `document_requests`, `document_responses` |
| **Plan** | Plan pretplate | Verzionisan katalog (`STARTER`/`STANDARD`/`PRO`) sa cenom u minor jedinicama, entitlement-ima i limitima. | `plans` |
| **Subscription** | Pretplata | Stanje pretplate organizacije (`TRIAL`, `ACTIVE`, `PAST_DUE`, `CANCELLED`). | `subscriptions`, `subscription_events` |
| **Entitlement** | Pravo iz plana | Funkcionalnost koju plan uključuje (npr. `photos_enabled`). Nije autorizacija korisnika. | `plans.entitlements` |
| **Feature flag** | Feature flag | Globalni prekidač funkcionalnosti sa podrazumevanom vrednošću, kill switch-em i tenant override-om. | `feature_flags`, `organization_feature_overrides` |
| **Audit log** | Audit zapis | Append-only zapis ko je šta uradio (ili pokušao) nad kojim entitetom. | `audit_log` |
| **Privacy request** | Zahtev za privatnost | Zahtev za pristup/izvoz/brisanje/ispravku podataka (GDPR/ZZPL). | `privacy_requests`, `data_exports`, `erasure_ledger` |
| **Tenant context** | Kontekst zakupca | Transaction-local vrednosti (`app.organization_id`, `app.user_id`, ...) koje RLS politike čitaju. Postavlja se tek posle provere sesije i članstva. | (funkcije `app.current_organization_id()` i dr.) |

---

## 3. Korisnici i uloge

### 3.1 Princip: uloga pripada članstvu

Uloga se nikada ne vodi kao globalno polje korisnika. Jedan nalog (npr. `ana@example.test`) može istovremeno biti:

- `PARENT` u organizaciji A (svoje dete),
- `PARENT` u organizaciji B (drugo dete, drugi vrtić),
- `TEACHER` u organizaciji C (radno mesto).

Svaki od tih odnosa je poseban red u `organization_memberships` sa sopstvenim statusom (`INVITED` → `ACTIVE` →
`SUSPENDED`/`REVOKED`). Isti korisnik može imati i **više uloga u istoj organizaciji** (npr. vaspitač čije dete ide u isti
vrtić ima i `TEACHER` i `PARENT` članstvo – jedinstvenost je na `(organization_id, user_id, role)`). Klijent uvek radi u
kontekstu **jednog izabranog članstva** (tenant switcher), i sva prava se izvode iz tog članstva.

`SUPER_ADMIN` nije članstvo već globalni zapis u `platform_admins` i **ne daje** nikakav automatski pristup podacima
dece. Platformski režim (`app.platform_mode()`) uključuje se samo posle provere aktivnog `platform_admins` reda i MFA.

### 3.2 Pregled uloga

| Uloga (proizvod) | Tehnički identifikator | Opseg | Ključne mogućnosti | Izričito NE može |
|---|---|---|---|---|
| **SUPER_ADMIN** (administrator platforme) | `platform_admins` + `app.platform_mode()` | cela platforma | kreiranje/suspenzija organizacija, planovi, pretplate, feature flagovi i kill switch, agregirana (anonimizovana) statistika, sanitizovani logovi, odobravanje support pristupa | čitanje podataka dece, staratelja, zdravstvenih profila, poruka i fotografija bez važećeg `support_access_grants` reda |
| **KINDERGARTEN_OWNER** (vlasnik/direktor) | `organization_memberships.role = 'OWNER'` | svoja organizacija | sve iz ADMIN + upravljanje pretplatom, dodela/opoziv OWNER i ADMIN članstava, odobravanje support pristupa, podešavanja organizacije, statistika | čitanje zdravstvenog profila bez `CHILD_HEALTH_READ` dozvole; pristup drugim organizacijama |
| **ADMIN** (administracija vrtića) | `role = 'ADMIN'` | svoja organizacija | objekti, grupe, zaposleni, dodele vaspitača, deca, upisi, staratelji, ovlašćene osobe, rasporedi, odsustva, korekcije prisustva, obaveštenja, kalendar, jelovnik, izveštaji | dodela OWNER ili SUPER_ADMIN prava; upravljanje pretplatom (osim uz `BILLING_MANAGE`); zdravstveni profil bez `CHILD_HEALTH_*` |
| **TEACHER** (vaspitač) | `role = 'TEACHER'` + aktivna `group_teacher_assignments` | dodeljene grupe u periodu važenja | dnevni pregled, check-in/check-out, evidencija neplaniranog prisustva, pregled rasporeda i odsustava svoje grupe, čitanje obaveštenja, (P1) poruke sa starateljima svoje grupe, (P1) upload fotografija | podaci dece van dodeljenih grupa ili van perioda dodele; menjanje rasporeda umesto roditelja (osim ako vrtić to dozvoli – otvoreno pitanje); zdravstveni profil bez `CHILD_HEALTH_READ` |
| **PARENT** (roditelj/staratelj) | `role = 'PARENT'` + `guardians.status = 'CONFIRMED'` | samo sopstvena deca | pregled profila deteta, raspored (uz `can_manage_schedule`), prijava odsustva (uz `can_report_absence`), saglasnosti (uz `can_give_consent`), zdravstveni profil svog deteta (uz `can_view_health`), obaveštenja, notifikacije, (P1) poruke, kalendar, jelovnik, fotografije | povezivanje deteta sam sebi; potvrda prava drugog staratelja; pristup deci koja nisu njegova; pristup listi korisnika |
| **NANNY / BABYSITTER** (budućnost) | nije modelovano u MVP | – | – | Zahteva novi permission/scope model (pristup ograničen na određenu decu i vremenske prozore); nije u P0/P1. |

### 3.3 Dodatne dozvole (`membership_permissions`)

Uloge daju osnovna prava; sledeće dozvole se dodeljuju pojedinačnim članstvima (uz audit ko je dodelio):

| Dozvola | Značenje | Tipično ko je ima |
|---|---|---|
| `CHILD_HEALTH_READ` | čitanje zdravstvenog profila dece u svom opsegu | vaspitač, medicinska sestra (kao ADMIN/TEACHER), owner po potrebi |
| `CHILD_HEALTH_WRITE` | izmena zdravstvenog profila | administracija sa obrazloženom potrebom |
| `ATTENDANCE_CORRECT` | naknadna korekcija prisustva sa razlogom | administracija, glavni vaspitač |
| `ANNOUNCEMENT_PUBLISH` | objavljivanje obaveštenja (draft mogu svi u osoblju) | administracija, vaspitači po odluci vrtića |
| `PHOTO_PUBLISH` | objava fotografija (upload je moguć bez toga) | administracija |
| `BILLING_MANAGE` | pregled i upravljanje pretplatom | owner (podrazumevano), izabrani admin |
| `MEMBER_MANAGE` | pozivanje i suspenzija članstava | owner (podrazumevano), izabrani admin |
| `REPORT_EXPORT` | izvoz izveštaja (CSV/PDF) | owner, administracija |

Pravilo: **feature flag nije autorizacija** – i kada je funkcija uključena za organizaciju, korisnik mora imati ulogu/dozvolu.

### 3.4 Pravila support pristupa (platformsko osoblje)

Support pristup jednoj organizaciji je moguć samo kroz `support_access_grants`:

1. Obavezan **razlog** i **referenca tiketa** (`reason`, `ticket_ref`).
2. Obavezan **opseg** kao allowlist (`scope`, npr. `ORG_STRUCTURE`, `ATTENDANCE_READ`); podrazumevano **read-only**.
3. **Odobrenje vlasnika** (`approval_kind = 'OWNER_APPROVED'`, `approved_by`) ili **hitni postupak**
   (`EMERGENCY` sa obaveznim `emergency_justification`; vlasnik se obaveštava naknadno).
4. **Kratak rok**: najviše 72 h (`expires_at <= starts_at + 72 h`), sa mogućnošću ranijeg opoziva.
5. **MFA** na sesiji support korisnika je obavezan.
6. Svaki pristup se **audituje** (`audit_log.purpose` obavezan), a vlasnik može da vidi listu pristupa svojoj organizaciji.
7. Zdravstveni podaci nisu deo nijednog support opsega osim uz posebnu odluku (otvoreno pitanje 9.4).

### 3.5 Kako korisnik doživljava uloge

- Posle prijave korisnik vidi **listu svojih članstava** (organizacija + uloga). Ako ima samo jedno, ulazi direktno.
- Promena organizacije (tenant switch) briše keširane podatke prethodne organizacije na klijentu.
- Roditelj vidi samo svoju decu i grupe „kroz” decu (naziv grupe, vaspitači), ne listu sve dece.
- Vaspitač vidi samo grupe iz važećih dodela; kada dodela istekne, grupa nestaje iz aplikacije (i iz offline keša pri sledećoj sinhronizaciji).

---

## 4. Organizaciona struktura i multi-tenancy

### 4.1 Hijerarhija iz ugla korisnika

```
Organization (vrtić „Happy Kids”)
 ├── Settings (vremenska zona, radni dani, rok za izmenu rasporeda, tolerancija kašnjenja...)
 ├── Memberships (owner, admini, vaspitači, roditelji)
 ├── Location „Novi Sad – Liman”
 │    ├── Group „Bubamare”  ← Enrollments (deca u periodu)  ← Teacher assignments (vaspitači u periodu)
 │    └── Group „Leptirići”
 └── Location „Novi Sad – Grbavica”
      └── Group „Pčelice”
```

- Vrtić ima **jedan ili više objekata**; objekat ima **jednu ili više grupa**.
- Dete u datom periodu pripada **tačno jednoj grupi** (upisi su nepreklapajući periodi; promena grupe = zatvaranje starog i otvaranje novog upisa).
- Dete može imati **više staratelja**; roditelj može imati **više dece** (u istoj ili različitim organizacijama).
- Profili dece se **ne povezuju** između nezavisnih vrtića, čak ni kada isti roditelj koristi oba.

### 4.2 Šta korisnik vidi kao „svoj vrtić”

| Uloga | Vidi |
|---|---|
| Owner/Admin | celu organizaciju: sve objekte, grupe, decu, osoblje, podešavanja |
| Teacher | svoje grupe (po dodeli), decu u njima, njihove staratelje (ime i kontakt), obaveštenja |
| Parent | svoju decu, njihove grupe i vaspitače, obaveštenja koja su mu upućena |

### 4.3 Tehnička izolacija (sažeto; detalji u `docs/ARCHITECTURE.md` i `docs/SECURITY.md`)

- Jedna PostgreSQL baza, deljena šema `app`, kolona `organization_id` na svakoj tenant tabeli i **kompozitni strani ključevi**
  `(id, organization_id)` koji sprečavaju „prišivanje” zapisa iz druge organizacije.
- **Row Level Security** sa `ENABLE` i `FORCE` na svakoj tenant tabeli; politika `tenant_isolation` propušta samo redove čiji
  `organization_id` jednak `app.current_organization_id()`. Bez postavljenog konteksta upit vraća **nula redova**.
- Runtime DB korisnik (`app_runtime`) nije vlasnik objekata, nije superuser i nema `BYPASSRLS`; migracije izvodi odvojeni
  `app_owner`; pozadinski radnik koristi `app_worker` (`scripts/db/00_roles.sql`).
- Tenant kontekst se postavlja `set_config(..., true)` (transaction-local) **tek posle** provere sesije i aktivnog članstva,
  i nestaje na COMMIT/ROLLBACK, tako da ponovna upotreba pooled konekcije ne može da „iscuri” kontekst (obavezan test).
- RLS štiti **granicu vrtića**; aplikativna politika dodatno proverava grupu (vaspitač), dete (roditelj) i osetljiva polja (zdravlje).
- Globalne tabele (`users`, `sessions`, `platform_admins`, `plans`, `feature_flags`) imaju zaseban pristupni model; tenant korisnik
  ne može da dobije globalnu listu korisnika – vidi samo sebe i članove sopstvene organizacije (za prikaz imena u listama).

### 4.4 Životni ciklus organizacije

| Status | Značenje | Šta korisnici mogu |
|---|---|---|
| `ACTIVE` | normalan rad | sve po ulogama |
| `SUSPENDED` | privremeno obustavljeno (npr. dugo neplaćanje, bezbednosni incident) | prijava moguća, prikaz poruke; komande odbijene sa `403`; podaci se ne brišu |
| `ARCHIVED` | organizacija je zatvorila nalog | samo izvoz podataka po zahtevu i retencija; podaci se brišu po politici retencije |

---

## 5. Funkcionalni zahtevi po domenu

Svaki domen sadrži: opis, korisničke priče, poslovna pravila, ekrane/tokove, ograničenja i šta NIJE u opsegu.
Prioritet svake priče je označen sa **[P0]**, **[P1]** ili **[P2]**.

---

### 5.1 Autentifikacija i nalozi

#### Opis

Globalni nalog sa email adresom i lozinkom, verifikacijom email-a, resetom lozinke, sesijama po uređaju i MFA za
privilegovane uloge. Strategija je **neprozirni (opaque) tokeni** koje server proverava na svakom zahtevu (trenutni opoziv),
a ne samopotpisani JWT-ovi. Registracija je moguća samo kroz **pozivnicu** (osoblje i staratelji) ili kroz kreiranje
organizacije (owner) – ne postoji „otvorena” registracija koja bi nekome dala pristup podacima.

#### Korisničke priče

- **[P0]** Kao **pozvani korisnik** želim da otvorim link iz pozivnice, postavim lozinku i potvrdim email, da bih dobio pristup tačno onoj organizaciji i ulozi na koju sam pozvan.
- **[P0]** Kao **korisnik** želim da se prijavim email-om i lozinkom na telefonu i u web pregledaču, da bih koristio aplikaciju na više uređaja.
- **[P0]** Kao **korisnik** želim da vidim listu svojih aktivnih sesija (uređaj, vreme, približna lokacija po IP) i da opozovem bilo koju ili sve, da bih zaštitio nalog ako izgubim telefon.
- **[P0]** Kao **korisnik** želim da resetujem zaboravljenu lozinku preko linka koji brzo ističe, da bih povratio pristup bez pomoći administracije.
- **[P0]** Kao **korisnik sa više članstava** želim da izaberem organizaciju u kojoj radim, da bih video samo relevantne podatke.
- **[P0]** Kao **vlasnik vrtića / platformski admin** želim da mi MFA (TOTP) bude obavezan, da bih zaštitio privilegovane akcije.
- **[P0]** Kao **korisnik** želim da pre osetljive akcije (promena email-a, lozinke, opoziv svih sesija, isključivanje MFA, izmena staratelja) ponovo unesem lozinku ili MFA kod, da bi neko ko mi je uzeo otključan telefon bio ograničen.
- **[P1]** Kao **korisnik** želim da promenim email adresu uz potvrdu na novoj adresi.
- **[P2]** Kao **korisnik** želim prijavu preko Google/Apple naloga ili telefonskog broja (nije u početnom opsegu).

#### Poslovna pravila

| # | Pravilo |
|---|---|
| A1 | Lozinke se čuvaju isključivo kao Argon2id PHC string (`users.password_hash`) sa jedinstvenom solju; parametri se biraju benchmark-om (cilj ~250–500 ms na produkcionom hardveru) i beleže u `docs/SECURITY.md`. Minimalna dužina 10 znakova; provera protiv liste najčešćih lozinki; bez obaveznih „specijalnih znakova”. |
| A2 | Email mora biti verifikovan pre prve prijave (`users.email_verified_at`). Verifikacioni link važi 24 h; token se čuva samo kao hash (`email_verification_tokens.token_hash`). |
| A3 | Prijava izdaje **sesiju** (`sessions`) + **access token** (10 min) + **refresh token** (idle 7 dana, absolutno 30 dana od kreiranja sesije). U bazi su samo hash-evi; sirovi tokeni se nikada ne loguju. |
| A4 | Refresh je **atomičan consume+rotate**: stari token se označava `consumed_at` i vezuje `replaced_by_id`, novi postaje jedini aktivan (`refresh_tokens_one_active_per_session`). Pokušaj upotrebe već potrošenog tokena = **detekcija ponovne upotrebe** → cela sesija se opoziva (`revoke_reason = 'REUSE_DETECTED'`), svi njeni access tokeni postaju nevažeći, korisnik dobija bezbednosnu notifikaciju. |
| A5 | Svaki API zahtev proverava access token u bazi (hash lookup) – opoziv je trenutan, nema „grace” perioda. |
| A6 | Logout opoziva tekuću sesiju; „logout sa svih uređaja” opoziva sve sesije korisnika (`LOGOUT_ALL`). Promena lozinke opoziva sve **ostale** sesije (`PASSWORD_CHANGED`). |
| A7 | Reset lozinke: link važi 15 min, jednokratan, hash u bazi; odgovor na zahtev je uvek isti bez obzira da li email postoji (bez otkrivanja naloga). |
| A8 | Generičke poruke o grešci pri prijavi („Neispravni podaci za prijavu”) – bez razlike između nepostojećeg naloga, pogrešne lozinke i neverifikovanog email-a u telu odgovora (neverifikovan email se rešava ponovnim slanjem linka na unetu adresu). |
| A9 | Rate limiting po email-hash-u i IP adresi na osnovu `login_attempts` (npr. 10 neuspešnih u 15 min → privremeno zaključavanje `users.locked_until`, eksponencijalno). Odgovor `429` sa `Retry-After`. Tačni pragovi su konfiguracija. |
| A10 | **Web** klijent: tokeni u `HttpOnly; Secure; SameSite=Lax` (ili `Strict`) kolačićima, CSRF zaštita sa proverom **tačnog** `Origin` zaglavlja na svakoj mutaciji, nikada u `localStorage`. **Mobile** klijent: `Authorization: Bearer`, tokeni samo u Keychain/Keystore, single-flight refresh (jedan refresh istovremeno), nikada u preferences ili logovima. |
| A11 | MFA (TOTP, RFC 6238) obavezan za `platform_admins`, `OWNER` članstva i support naloge; opciono za ostale. Sesija bez `mfa_verified_at` za takvog korisnika može da pristupi samo `/auth/*` i `/me/mfa/*`. Recovery kodovi (10 jednokratnih, hash u bazi). |
| A12 | Reautentifikacija: osetljive akcije zahtevaju `sessions.reauthenticated_at` novije od 5 min; u suprotnom `403` sa `problem+json` tipom `reauthentication-required`. |
| A13 | Nalozi imaju status `ACTIVE`/`LOCKED`/`DISABLED`; `DISABLED` nalog ne može da se prijavi ni da osveži token; postojeće sesije se opozivaju odmah. |
| A14 | Svaki auth događaj (prijava uspešna/neuspešna, refresh, reuse detected, logout, reset, MFA enable/disable, promena lozinke) piše `audit_log` zapis bez lozinke ili tokena u metapodacima. |

#### Ekrani / tokovi

| Ekran | Platforma | Sadržaj |
|---|---|---|
| Prijava | mobile, web | email, lozinka, „zaboravljena lozinka”, jezik |
| Prihvatanje pozivnice | mobile (deep link), web | prikaz organizacije i uloge, ime/prezime, lozinka, jezik, prihvatanje uslova |
| Verifikacija email-a | web (link) + mobile deep link | rezultat verifikacije i dugme „nastavi” |
| MFA podešavanje | web, mobile | QR/ključ, potvrda koda, recovery kodovi (jednom prikazani) |
| MFA izazov | web, mobile | unos koda ili recovery koda |
| Izbor organizacije | mobile, web | lista članstava, poslednje korišćeno |
| Moj nalog | mobile, web | ime, jezik, promena lozinke, sesije (lista + opoziv), MFA |
| Reautentifikacija (modal) | mobile, web | lozinka ili TOTP |

Tok prijave (mobile): unos → `POST /api/v1/auth/login` → (ako MFA) `POST /api/v1/auth/mfa/verify` → `GET /api/v1/me/memberships` → izbor → glavni ekran.
Tok refresh-a: pre isteka access tokena ili na `401` → `POST /api/v1/auth/refresh` (single-flight) → nastavak; na `401` posle refresh-a → odjava i brisanje lokalnih podataka.

#### Ograničenja i van opsega

- Nema društvene prijave, telefonskog OTP-a ni passkey-a u P0/P1 (P2).
- Nema „zapamti me” bez limita – absolutni rok sesije je 30 dana.
- Nema samostalne registracije bez pozivnice (osim kreiranja nove organizacije, koje u pilotu radi platformski admin).

---

### 5.2 Organizacija, objekti, grupe

#### Opis

Administracija vodi osnovnu strukturu vrtića: podatke organizacije, podešavanja, objekte i grupe. Grupe moraju postojati
pre upisa dece (zavisnost EPIC 06 → EPIC 05 upisi).

#### Korisničke priče

- **[P0]** Kao **platformski admin** želim da kreiram organizaciju sa prvim vlasnikom (pozivnica), da bih pilot vrtiću dao nalog.
- **[P0]** Kao **vlasnik** želim da podesim vremensku zonu, radne dane, radno vreme, rok za izmenu rasporeda i toleranciju kašnjenja, da bi sistem računao očekivanja po pravilima mog vrtića.
- **[P0]** Kao **admin** želim da dodam objekat sa adresom i grupe sa uzrastom i kapacitetom, da bih mogao da upisujem decu.
- **[P0]** Kao **admin** želim da deaktiviram grupu koja više ne postoji, a da istorija upisa i prisustva ostane sačuvana.
- **[P1]** Kao **admin** želim da unesem neradne dane (praznike) za ceo vrtić ili jedan objekat.

#### Poslovna pravila

| # | Pravilo |
|---|---|
| O1 | Organizacija ima jedinstveni `slug` (za URL i tenant switcher), naziv, zemlju (`RS` podrazumevano), IANA vremensku zonu (`Europe/Belgrade` podrazumevano) i podrazumevani jezik. |
| O2 | Podešavanja (`organization_settings`) se kreiraju automatski sa organizacijom: `schedule_change_deadline_hours` (0–168, podrazumevano 12), `late_arrival_grace_minutes` (0–180, podrazumevano 15), `day_opens_at`/`day_closes_at` (06:00–18:00), `working_weekdays` (ISO 1–7, podrazumevano pon–pet), `offline_cache_ttl_hours` (1–48, podrazumevano 12). |
| O3 | Naziv objekta je jedinstven u organizaciji (bez obzira na velika/mala slova, među neobrisanim); naziv grupe jedinstven u objektu. |
| O4 | Objekat može imati sopstvenu vremensku zonu (`locations.timezone`); ako je `NULL`, važi zona organizacije. Promena zone ne menja istorijske zapise. |
| O5 | Grupa ima opcioni uzrasni raspon (`age_from_months <= age_to_months`) i kapacitet (`> 0`). Kapacitet je **upozorenje**, ne tvrda blokada upisa (admin može da premaši uz potvrdu). |
| O6 | Brisanje je meko (`deleted_at`) i moguće samo ako nema aktivnih upisa/dodela; u suprotnom se koristi status `INACTIVE`. |
| O7 | Sve promene strukture pišu `audit_log` (npr. `GROUP_CREATED`, `LOCATION_UPDATED`) i koriste `If-Match`/`version` gde entitet ima `version`. |

#### Ekrani / tokovi

- Web: **Settings → Organizacija** (osnovni podaci, zona, jezik), **Settings → Pravila rasporeda** (rok, tolerancija, radno vreme, radni dani), **Locations** (lista, forma), **Groups** (lista po objektu, forma, kartica grupe sa vaspitačima i decom).
- Mobile: samo pregled (naziv grupe, objekat) u kontekstu ostalih ekrana.

#### Van opsega

- Više organizacija pod jednim „lancem” vrtića (konsolidovani pregled) – P2.
- Samostalna registracija vrtića (self-service onboarding) – P2; u pilotu organizaciju kreira platformski admin.

---

### 5.3 Zaposleni i dodele

#### Opis

Kadrovski profil zaposlenog (`employees`) vezan je 1:1 za OWNER/ADMIN/TEACHER članstvo. Vaspitači se dodeljuju grupama
sa periodom važenja (`group_teacher_assignments`), što određuje njihov opseg podataka.

#### Korisničke priče

- **[P0]** Kao **admin** želim da pozovem vaspitača email-om, da bi dobio nalog sa ulogom TEACHER u mom vrtiću.
- **[P0]** Kao **admin** želim da dodelim vaspitača grupi od datuma do datuma (ili neograničeno) kao glavnog, pomoćnog ili zamenu, da bi video samo tu grupu i samo u tom periodu.
- **[P0]** Kao **admin** želim da prekinem dodelu ili suspendujem članstvo bivšeg zaposlenog, da bi odmah izgubio pristup.
- **[P0]** Kao **vaspitač** želim da vidim koje su mi grupe dodeljene danas.
- **[P1]** Kao **admin** želim da vidim istoriju dodela vaspitača po grupi (ko je kada vodio grupu).

#### Poslovna pravila

| # | Pravilo |
|---|---|
| E1 | Pozivnica (`invitations`) nosi ulogu, ističe (podrazumevano 7 dana), jednokratna, token samo kao hash; prihvatanjem nastaje `ACTIVE` članstvo i `employees` red. |
| E2 | Zaposleni ne može imati dve preklapajuće aktivne dodele istoj grupi (EXCLUDE ograničenje); može biti dodeljen više grupa istovremeno. |
| E3 | Vaspitač ima pristup grupi samo za datume `valid_from <= d <= valid_to` (ili otvoreno) i dok `revoked_at IS NULL`; pristup istorijskim danima van perioda dodele nije dozvoljen. |
| E4 | Suspenzija/opoziv članstva odmah opoziva sve sesije tog korisnika **u kontekstu te organizacije** (tokeni ostaju važeći za druga članstva; server na svakom zahtevu proverava status članstva). |
| E5 | Samo OWNER može dodeliti/opozvati OWNER ili ADMIN ulogu; ADMIN može pozivati TEACHER i PARENT članstva (ili uz `MEMBER_MANAGE`). Niko unutar organizacije ne može dodeliti SUPER_ADMIN. |
| E6 | Poslednji aktivni OWNER ne može biti opozvan ni suspendovan (mora postojati bar jedan). |
| E7 | Kontakt podaci zaposlenog vidljivi su osoblju; roditelj vidi samo ime vaspitača svoje grupe (i, ako vrtić odluči, službeni telefon/email – podešavanje P1). |

#### Ekrani / tokovi

- Web: **Employees** (lista sa statusom članstva, forma, pozivnica, dodele po grupi sa periodima), **Groups → kartica grupe → vaspitači**.
- Mobile (vaspitač): izbor grupe ako ih ima više; bez ekrana za upravljanje.

#### Van opsega

- Evidencija radnog vremena zaposlenih, obračun plata, smene – nije u opsegu.
- Uloge NANNY/BABYSITTER – P2 uz novi model opsega.

---

### 5.4 Deca, upisi, staratelji, ovlašćene osobe

#### Opis

Profil deteta, istorija upisa u grupe, veze sa starateljima (potvrđuje ih osoblje) i lista osoba ovlašćenih za preuzimanje.

#### Korisničke priče

- **[P0]** Kao **admin** želim da unesem dete (ime, prezime, datum rođenja, opciona fotografija, opšte napomene) i upišem ga u grupu od datuma, da bi se pojavilo vaspitaču i roditeljima.
- **[P0]** Kao **admin** želim da pozovem roditelja email-om uz konkretno dete i vrstu odnosa, da bi po prihvatanju dobio PARENT članstvo i potvrđenu vezu sa detetom.
- **[P0]** Kao **admin** želim da potvrdim ili opozovem vezu staratelja i deteta i da podesim njegova ovlašćenja (raspored, odsustvo, saglasnosti, zdravlje), da bih ispoštovao porodičnu situaciju (npr. sudske odluke).
- **[P0]** Kao **admin** želim da premestim dete u drugu grupu od datuma, a da istorija ostane tačna.
- **[P0]** Kao **roditelj** želim da vidim profil svog deteta i listu ovlašćenih osoba za preuzimanje, i da predložim novu osobu.
- **[P0]** Kao **vaspitač** želim da vidim ko sme da preuzme dete, da bih proverio pri predaji.
- **[P0]** Kao **admin** želim da ispišem dete (kraj upisa sa razlogom) i označim ga neaktivnim, uz zadržavanje istorije prema retenciji.
- **[P1]** Kao **roditelj** želim da ažuriram fotografiju profila deteta (uz odobrenje vrtića – otvoreno pitanje).

#### Poslovna pravila

| # | Pravilo |
|---|---|
| C1 | Dete ima `given_name`, `family_name`, `date_of_birth` (posle 2000-01-01), status `ACTIVE`/`INACTIVE`, opcionu profil fotografiju (`photo_file_id`) i `general_notes` **samo za neosetljive** napomene (npr. „donosi svoje ćebe”). Alergije, posebne potrebe i medicinske napomene idu isključivo u zdravstveni profil (5.5). |
| C2 | **Bez JMBG-a i bez fotografija dokumenata** u P0/P1; ako se ikad uvedu, zahtevaju obrazloženu potrebu, šifrovanje i posebnu dozvolu (otvoreno pitanje 9.2). |
| C3 | Upis (`enrollments`) ima period, status `PLANNED`/`ACTIVE`/`ENDED`/`CANCELLED`; za jedno dete se ne mogu preklapati `PLANNED`/`ACTIVE` upisi (EXCLUDE). Premeštaj = zatvaranje starog upisa danom pre i kreiranje novog – **atomski u jednoj transakciji**. |
| C4 | Veza staratelja (`guardians`) nastaje kao `PENDING` i postaje `CONFIRMED` samo akcijom osoblja (`confirmed_by`, `confirmed_at`). Roditelj **ne može** sam sebi da poveže dete niti da potvrdi drugog staratelja. Opoziv (`REVOKED`) odmah uklanja pristup detetu. |
| C5 | Ovlašćenja staratelja su pojedinačna: `can_manage_schedule`, `can_report_absence`, `can_give_consent`, `can_view_health`; `is_primary` označava primarni kontakt. Bar jedan potvrđeni staratelj se očekuje za aktivno dete (upozorenje, ne blokada). |
| C6 | Ovlašćene osobe za preuzimanje (`pickup_persons`) su lista bez naloga: ime, odnos, telefon, napomena, opcioni period važenja. Predlog roditelja nastaje kao `ACTIVE` odmah (roditelj je ovlašćen da odluči ko preuzima njegovo dete), uz audit; vrtić može da opozove. **Digitalna verifikacija preuzimanja (PIN/QR) nije u pilotu.** |
| C7 | Lista dece (`GET .../children`) **nikada** ne sadrži zdravstvene podatke; sadrži samo `has_critical_alert` indikator (boolean) za osoblje sa dozvolom pregleda liste. |
| C8 | Roditelj vidi samo decu sa `CONFIRMED` vezom; vaspitač samo decu sa aktivnim upisom u njegovim grupama za traženi datum. |
| C9 | Profil deteta ima `version` – izmene zahtevaju `If-Match`; konflikt vraća `409` sa trenutnim stanjem. |
| C10 | Deca se ne povezuju između organizacija; ako roditelj ima decu u dva vrtića, to su dva nezavisna profila. |

#### Ekrani / tokovi

- Web: **Children** (lista sa filterima po objektu/grupi/statusu, pretraga po imenu), **Kartica deteta** (tabovi: Profil, Upisi, Staratelji, Preuzimanje, Zdravlje [uz dozvolu], Raspored, Prisustvo, Odsustva), **Parents** (lista PARENT članstava sa decom, pozivnice).
- Mobile (roditelj): **Moja deca** → **Dete** (profil, staratelji, ovlašćene osobe, raspored, odsustva).
- Mobile (vaspitač): **Dete** iz liste grupe (ime, fotografija, ovlašćene osobe, staratelji – ime i telefon, indikator zdravstvenog upozorenja).

#### Van opsega

- Upisna procedura sa listom čekanja i ugovorima (enrollment workflow) – P2.
- Automatsko spajanje duplikata dece – nije u opsegu.

---

### 5.5 Zdravstveni profil

#### Opis

Zdravstveni podaci (alergije, posebne potrebe, medicinske napomene, kontakt za hitne slučajeve) čuvaju se **odvojeno**
(`child_health_profiles`), šifrovano (envelope encryption), sa posebnim dozvolama i auditom svakog čitanja i izmene.

#### Korisničke priče

- **[P0]** Kao **roditelj** želim da unesem alergije i posebne potrebe svog deteta, da bi vrtić bio informisan.
- **[P0]** Kao **vaspitač sa dozvolom** želim da vidim zdravstveni profil deteta iz svoje grupe, da bih reagovao ispravno (npr. alergija na kikiriki).
- **[P0]** Kao **vaspitač bez dozvole** želim da vidim bar indikator „postoji kritično upozorenje – pitaj administraciju”, da ne bih propustio nešto važno.
- **[P0]** Kao **vlasnik** želim da vidim ko je i kada čitao zdravstvene podatke dece, da bih dokazao odgovornu obradu.

#### Poslovna pravila

| # | Pravilo |
|---|---|
| H1 | Podaci se čuvaju kao šifrovan JSON (`payload_enc`, `payload_key_id`, `payload_schema_ver`); ključevi u KMS-u; dešifrovanje samo u aplikaciji pri ovlašćenom čitanju. |
| H2 | Pristup: roditelj sa `CONFIRMED` vezom i `can_view_health = true` (čitanje i izmena za svoje dete); osoblje samo uz `CHILD_HEALTH_READ` / `CHILD_HEALTH_WRITE` i unutar svog opsega (vaspitač: svoje grupe). Uloga OWNER **sama po sebi ne daje** pristup. |
| H3 | Svako čitanje i izmena piše `audit_log` sa `action = 'HEALTH_READ'`/`'HEALTH_UPDATED'`, `entity_type = 'CHILD_HEALTH'` i obaveznim `purpose`. |
| H4 | Zdravstveni podaci se **nikada** ne pojavljuju u listi dece, u push porukama, u notifikacionim `title_args`, u logovima ni u izvozima bez posebne svrhe. |
| H5 | `has_critical_alert` je jedini nešifrovani indikator (boolean) koji osoblje vidi bez dozvole; ne otkriva sadržaj. |
| H6 | Profil ima `version`; izmene sa `If-Match`. |
| H7 | Zdravstveni profil nije dostupan offline (5.19). |
| H8 | Sadržaj je informativan; aplikacija ne daje medicinske preporuke. |

#### Ekrani / tokovi

- Web: **Kartica deteta → Zdravlje** (vidljivo samo uz dozvolu; forma sa sekcijama alergije / posebne potrebe / napomene / hitni kontakt; obavezan unos svrhe pri prvom otvaranju u sesiji – podešavanje vrtića).
- Mobile (roditelj): **Dete → Zdravlje**; Mobile (vaspitač sa dozvolom): **Dete → Zdravlje** (samo online).

#### Van opsega

- Evidencija davanja lekova, terapija, razvojni izveštaji – P2.
- Deljenje sa zdravstvenim ustanovama – nije u opsegu.

---

### 5.6 Raspored

#### Opis

Raspored opisuje **kada se dete očekuje** u vrtiću: ponavljajući nedeljni šablon sa datumom važenja, izmene za pojedinačne
dane, neradni dani i odsustva. Iz toga se izvodi **dnevni plan** po detetu i datumu, koji se zamrzava i ne menja retroaktivno.

#### Korisničke priče

- **[P0]** Kao **roditelj** želim da unesem nedeljni raspored deteta (dolazak/odlazak po danu, ili „ne dolazi”) koji važi od datuma, da bi vrtić znao kad da nas očekuje.
- **[P0]** Kao **roditelj** želim da promenim jedan konkretan dan (drugo vreme ili ne dolazi), da bih prijavio izuzetak bez menjanja celog šablona.
- **[P0]** Kao **roditelj** želim da vidim pregled kako će raspored izgledati pre nego što potvrdim, da ne bih napravio grešku.
- **[P0]** Kao **roditelj** želim da ažuriram celu nedelju odjednom i da ili sve prođe ili ništa, da ne bih ostao sa polovičnim rasporedom.
- **[P0]** Kao **vaspitač** želim da vidim ko se očekuje sutra i u koje vreme, i koje su izmene unete kasno.
- **[P0]** Kao **admin** želim da podesim rok do kog roditelj može da menja raspored (npr. 12 h pre), a da se kasne izmene jasno označe.
- **[P0]** Kao **admin** želim da unesem neradne dane, da bi se ti dani automatski isključili iz očekivanja.
- **[P1]** Kao **admin** želim da unesem raspored umesto roditelja (npr. po telefonu), uz zabelešku ko je uneo.

#### Poslovna pravila

**Prioritet pri izračunavanju dnevnog plana** (za dete i datum; prvo pravilo koje se primeni je konačno):

| Prioritet | Izvor | `daily_plans.source` | Rezultat |
|---|---|---|---|
| 1 | Neradni dan organizacije ili objekta deteta (`closure_days`) | `CLOSURE` | ne očekuje se |
| 2 | Aktivno odsustvo koje pokriva datum (`absences.status = 'ACTIVE'`) | `ABSENCE` | ne očekuje se, `absence_id` popunjen |
| 3 | Izmena za taj dan (`schedule_day_overrides`, `deleted_at IS NULL`) | `OVERRIDE` | očekuje se / ne, po izmeni |
| 4 | Šablon važeći na datum (`schedule_templates` gde `effective_from <= d <= effective_to`) | `TEMPLATE` | po danu u nedelji |
| 5 | Ništa od navedenog | `NONE` | nema očekivanog dolaska |

Dodatna pravila:

| # | Pravilo |
|---|---|
| S1 | Šablon (`schedule_templates`) važi od `effective_from`; novi šablon zatvara prethodni (`effective_to = novi.effective_from - 1`). Periodi se ne preklapaju (EXCLUDE). Šablon ima do 7 redova (`schedule_template_days`) – po jedan za ISO dan 1–7, `attends` + vreme dolaska < odlaska (lokalno vreme organizacije) ili bez vremena ako ne dolazi. |
| S2 | Izmena za dan (`schedule_day_overrides`) je jedinstvena po (dete, datum) među neobrisanim; ima `reason`, `version`, i `is_late_change`. |
| S3 | **Rok za promenu**: promena (šablon ili izmena) koja utiče na datum `d` je „kasna” ako je uneta posle `početak dana d u zoni org − schedule_change_deadline_hours` (početak dana = `day_opens_at`, ili 00:00 ako vrtić izabere – tačna referenca je otvoreno pitanje 9.6). Kasna promena se **ne odbija**, već označava (`is_late_change = true`) i pojavljuje vaspitaču u feed-u „kasne izmene”. Vrtić može podesiti da se kasne izmene odbijaju (P1 podešavanje). |
| S4 | **Nedeljna atomska izmena**: `PUT .../schedules/children/{childId}/week?weekStart=YYYY-MM-DD` prihvata 7 dana i primenjuje ih kao izmene za dan u jednoj transakciji; validacija svih dana pre upisa; `If-Match` na agregat (verzija = max verzija postojećih izmena + šablona); pri konfliktu `409` sa trenutnim stanjem cele nedelje. |
| S5 | **Bez retroaktivne izmene istorije**: `daily_plans` red za datum koji je prošao (ili je zamrznut, `frozen_at IS NOT NULL`) se ne preračunava; promene šablona važe od danas/budućnosti. Promena rasporeda za prošli datum je odbijena (`422`). Zamrzavanje radi pozadinski posao na početku dana u zoni organizacije (`day_opens_at`) – do tada se plan za taj dan računa „na zahtev”. |
| S6 | **Odsustvo uvek odmah**: prijava odsustva nema rok i uvek se primenjuje (prioritet 2), uključujući i tekući dan. |
| S7 | Pregled pre primene: `POST .../schedules/children/{childId}/preview` vraća izračunati plan za period bez upisa. |
| S8 | Optimistic concurrency: šabloni i izmene imaju `version`; klijent šalje `If-Match`; konflikt je jasan (ko, kada, koja vrednost). |
| S9 | Svaka promena piše `schedule_change_log` (`TEMPLATE_REPLACED`, `OVERRIDE_SET`, `OVERRIDE_REMOVED`, `WEEK_UPDATED`) sa `before_state`/`after_state` i `is_late_change`; vaspitač i admin vide feed „izmene rasporeda” (posebno kasne). |
| S10 | Vreme dolaska/odlaska mora biti unutar `day_opens_at`–`day_closes_at` organizacije (validacija `422`); dani van `working_weekdays` se u šablonu mogu uneti samo kao „ne dolazi”. |
| S11 | Raspored menja roditelj sa `can_manage_schedule`; admin uvek može (P1 – uz obaveznu napomenu). Vaspitač ne menja raspored (otvoreno pitanje 9.7). |
| S12 | Promena rasporeda emituje notifikaciju (`SCHEDULE_CHANGE`) vaspitačima grupe kada je kasna ili utiče na današnji/sutrašnji dan. |

#### Ekrani / tokovi

- Mobile (roditelj): **Raspored deteta** – nedeljni prikaz (pon–ned) sa vremenima; dugme „Izmeni nedelju” (uređivanje svih 7 dana, pregled, potvrda); „Promeni dan” (jedan datum); „Nedeljni šablon” (važi od datuma); jasno upozorenje „kasna izmena – vrtić će je videti kao kasnu”.
- Web (admin): **Schedules** – pregled po grupi i datumu (tabela dece sa očekivanjima i izvorom), feed kasnih izmena, neradni dani, unos rasporeda za dete umesto roditelja (P1).
- Mobile (vaspitač): **Sutra / Nedelja** – ko se očekuje sa vremenima; oznaka „kasna izmena”.

#### Van opsega

- Automatsko predlaganje rasporeda, naplata po satima boravka, prekovremeni boravak – nije u opsegu.
- Rasporedi zaposlenih (smene) – nije u opsegu.

---

### 5.7 Odsustva

#### Opis

Roditelj (ili osoblje) prijavljuje da dete ne dolazi zbog bolesti, odmora ili drugog razloga, za jedan dan ili period.
Odsustvo ima najviši prioritet posle neradnog dana u rasporedu i vidljivo je vaspitaču odmah.

#### Korisničke priče

- **[P0]** Kao **roditelj** želim da prijavim da je dete bolesno od danas do petka, da vrtić ne bi čekao dete i da vaspitač odmah zna.
- **[P0]** Kao **roditelj** želim da otkažem odsustvo ako se dete oporavilo ranije, da bi ponovo bilo očekivano.
- **[P0]** Kao **vaspitač** želim da na dnevnom pregledu vidim ko je odsutan i zašto (vrsta, ne dijagnoza).
- **[P0]** Kao **admin** želim da unesem odsustvo umesto roditelja (telefonski poziv), uz zabelešku.
- **[P1]** Kao **admin** želim izveštaj odsustava po grupi i periodu.

#### Poslovna pravila

| # | Pravilo |
|---|---|
| B1 | Vrste: `SICK`, `VACATION`, `OTHER`. `note` je opciona i **nemedicinska** (bez dijagnoza – UI to jasno kaže; nema obaveznog medicinskog objašnjenja). |
| B2 | Period `date_from`–`date_to` (uključivo), najviše 365 dana; jedan dan = `date_from = date_to`. |
| B3 | Preklapanje: dva aktivna odsustva istog deteta ne mogu se preklapati (EXCLUDE). Nova prijava koja se preklapa vraća `409` sa postojećim odsustvom; klijent nudi „produži postojeće” ili „otkaži i unesi novo”. |
| B4 | Otkazivanje postavlja `status = 'CANCELLED'`, `cancelled_at`, `cancelled_by_membership_id`; ne briše red. Otkazivanje za prošle dane menja plan samo za buduće dane (istorija zamrznuta). |
| B5 | Prijava/otkazivanje odmah preračunava `daily_plans` za nezamrznute dane perioda i emituje outbox događaj → notifikacija `ABSENCE` vaspitačima grupe. |
| B6 | **Odsustvo ne radi automatski check-out**: ako je dete već fizički prisutno (`CHECKED_IN`) i stigne prijava odsustva za danas, dete ostaje `CHECKED_IN`; `attendance_days.absence_kind` se puni kao kontekst, a vaspitač vidi upozorenje. |
| B7 | Roditelj prijavljuje samo uz `can_report_absence`; odsustvo vidi vaspitač grupe (u periodu dodele) i admin. |
| B8 | Idempotentno kreiranje (`Idempotency-Key`); `version` + `If-Match` za otkazivanje. Audit `ABSENCE_REPORTED`/`ABSENCE_CANCELLED`. |

#### Ekrani / tokovi

- Mobile (roditelj): **Dete → Prijavi odsustvo** (vrsta, od–do, napomena) → potvrda → prikaz u kalendaru deteta; lista odsustava sa „Otkaži”.
- Mobile (vaspitač): u dnevnom pregledu status „Odsutan (bolest)”; lista odsustava grupe za nedelju.
- Web: **Attendance/Absences** – lista i unos umesto roditelja; filter po grupi i periodu.

#### Van opsega

- Prilaganje lekarskih potvrda (fajlovi) – P2 kroz Dokumente.
- Obračun umanjenja cene za odsustvo – nije u opsegu.

---

### 5.8 Evidencija prisustva

#### Opis

Stvarno prisustvo je odvojeno od plana. Vaspitač evidentira **dolazak** (check-in) i **odlazak** (check-out); dete može
imati više poseta u danu; sve komande su **nepromenljivi događaji** iz kojih se izvodi projekcija stanja; korekcije se rade
novim događajem sa razlogom, nikad menjanjem istorije.

#### Korisničke priče

- **[P0]** Kao **vaspitač** želim jednim dodirom da evidentiram dolazak deteta, da bih to uradio dok držim dete za ruku.
- **[P0]** Kao **vaspitač** želim da evidentiram odlazak i vidim ko ga je preuzeo (izbor iz liste ovlašćenih, opciono), da bi evidencija bila potpuna.
- **[P0]** Kao **vaspitač** želim da dete koje je otišlo (npr. kod lekara) i vratilo se ponovo prijavim, da bi obe posete bile zabeležene.
- **[P0]** Kao **vaspitač** želim da evidentiram dete koje je došlo iako nije bilo očekivano, da bi bilo vidljivo kao prisutno.
- **[P0]** Kao **admin (ili vaspitač sa dozvolom)** želim da ispravim pogrešno vreme dolaska uz razlog, a da originalni zapis ostane vidljiv.
- **[P0]** Kao **roditelj** želim da vidim kada je dete došlo i otišlo danas i istorijski.
- **[P0]** Kao **vaspitač** želim da ista komanda poslata dva puta (npr. loš signal) ne napravi dva dolaska.
- **[P2]** Kao **vrtić** želim QR/NFC/kiosk prijavu, koja koristi isti domen prisustva.

#### Poslovna pravila

**Mašina stanja po detetu i datumu** (`attendance_days.status`):

```
NOT_ARRIVED ──CHECK_IN──▶ CHECKED_IN ──CHECK_OUT──▶ CHECKED_OUT
                              ▲                          │
                              └────────CHECK_IN──────────┘   (nova poseta, sequence_no + 1)
```

| # | Pravilo |
|---|---|
| T1 | Prelazi: `CHECK_IN` dozvoljen iz `NOT_ARRIVED` i `CHECKED_OUT` (otvara novu `attendance_visits` posetu); `CHECK_OUT` dozvoljen samo iz `CHECKED_IN` (zatvara otvorenu posetu). `CHECK_OUT` bez otvorene posete → `409` (`attendance-invalid-transition`); `CHECK_IN` dok je već `CHECKED_IN` → `409`. |
| T2 | Više poseta u danu: svaka poseta ima `sequence_no`; najviše jedna otvorena po danu (`attendance_visits_one_open`). |
| T3 | **Događaji su nepromenljivi** (`attendance_events`, trigger zabranjuje UPDATE/DELETE). Projekcija `attendance_days` (`status`, `first_check_in_at`, `last_check_out_at`, `visits_count`, `open_visit_id`, `version`) se ažurira u istoj transakciji i može se rekonstruisati iz događaja. |
| T4 | **Idempotencija**: svaka komanda nosi `command_id` (UUID generisan na uređaju); `attendance_events.command_id` je UNIQUE. Ponovljena komanda vraća isti rezultat (`200` sa postojećim stanjem), ne pravi novi događaj. |
| T5 | **Optimistic concurrency + zaključavanje**: komanda nosi `expectedVersion`; server radi `SELECT ... FOR UPDATE` na `attendance_days` (ili kreira red), poredi verziju; neslaganje → `409` sa trenutnom projekcijom i poslednjim događajem, klijent prikazuje konflikt (ne prepisuje). |
| T6 | `occurred_at` je poslovno vreme (može biti ranije od `recorded_at` kod offline sinhronizacije); `source` ∈ `MOBILE`, `WEB`, `OFFLINE_SYNC`, `SYSTEM`; `device_id` za mobilne. `occurred_at` ne sme biti u budućnosti (tolerancija 5 min) ni pre `day_opens_at − 2 h`. |
| T7 | **Korekcija**: događaj `CORRECTION` sa `correction_of_event_id`, obaveznim `correction_reason` i payload-om nove vrednosti (npr. ispravljeno vreme). Zahteva `ATTENDANCE_CORRECT` dozvolu ili ulogu ADMIN/OWNER. Korekcija projekciju preračunava; originalni događaj ostaje. |
| T8 | **Neplanirano prisutno**: check-in deteta koje po `daily_plans` nije očekivano (`is_expected = false`) automatski postavlja `is_unscheduled = true` i piše dodatni događaj `UNSCHEDULED_PRESENT` (informativno; ne menja raspored). |
| T9 | **Odsustvo kao kontekst**: `absence_kind` na danu prisustva je nezavisan od `status`; odsustvo ne pravi auto check-out (5.7 B6). Događaji `ABSENCE_MARKED`/`ABSENCE_CLEARED` se pišu sistemski kada se odsustvo prijavi/otkaže za dan koji već ima red prisustva. |
| T10 | Datum prisustva je datum u **vremenskoj zoni organizacije** (odnosno objekta ako ima override); poseta koja pređe ponoć zatvara se ručno (nema auto check-out u P0; podsetnik vaspitaču pri otvaranju sledećeg dana – otvoreno pitanje 9.8). |
| T11 | Opseg: vaspitač šalje komande samo za decu upisanu u grupu iz važeće dodele, za današnji ili jučerašnji datum (jučerašnji samo check-out zaostale posete i korekcije uz dozvolu). Admin za bilo koji datum uz korekciju. Roditelj samo čita. |
| T12 | Svaka komanda piše `audit_log` (`ATTENDANCE_CHECK_IN`, `ATTENDANCE_CHECK_OUT`, `ATTENDANCE_CORRECTED`) i outbox događaj (za notifikaciju roditelju `ATTENDANCE` – podešavanje vrtića da li roditelj dobija push „dete je stiglo”). |
| T13 | Check-out može opciono da nosi `pickup_person_id` ili `guardian_id` (ko je preuzeo) u payload-u; bez verifikacije u P0. |

#### Ekrani / tokovi

- Mobile (vaspitač): **Dnevni pregled** (5.9) → dodir na dete → velika dugmad „Dolazak” / „Odlazak” (kontekstualno po stanju) → opciono „ko preuzima” → potvrda; **Dete → Istorija danas** (posete sa vremenima); **Korekcija** (samo uz dozvolu): izbor događaja, novo vreme, razlog.
- Web: **Attendance** – tabela po grupi i datumu sa statusima, posetama, korekcijama; unos korekcije; izvoz (P1).
- Mobile (roditelj): **Dete → Prisustvo** (danas + istorija po danima).

#### Van opsega

- QR/NFC/kiosk, digitalni potpis preuzimanja, geolokacija – P2 (kroz isti domen događaja).
- Automatski check-out na kraju radnog vremena – nije u P0 (otvoreno pitanje 9.8).

---

### 5.9 Dnevni pregled za vaspitača

#### Opis

Glavni ekran vaspitača: za izabranu grupu i datum prikazuje brojače i listu dece sa statusom i brzim akcijama. Ista
definicija brojača mora važiti na backend-u, mobilnoj aplikaciji (uključujući offline) i web-u.

#### Korisničke priče

- **[P0]** Kao **vaspitač** želim da odmah vidim koliko dece očekujem, koliko je prisutno, otišlo, odsutno i još nije stiglo, da bih znao stanje grupe bez brojanja.
- **[P0]** Kao **vaspitač** želim da vidim ko kasni u odnosu na očekivano vreme, da bih mogao da pozovem roditelja.
- **[P0]** Kao **vaspitač** želim da deca koja su došla iako nisu bila očekivana budu jasno izdvojena.
- **[P0]** Kao **vaspitač** želim da vidim koje moje akcije još nisu sinhronizovane i gde postoji konflikt.
- **[P0]** Kao **vaspitač** želim da radim sa velikim dugmadima i čitljivim tekstom, jer koristim tablet u pokretu.

#### Poslovna pravila – definicije brojača

Za grupu `G` i datum `d`, skup dece je: deca sa upisom u `G` koji pokriva `d`.

| Brojač | Definicija |
|---|---|
| **Expected** (Očekivano) | broj dece sa `daily_plans.is_expected = true` za `d` |
| **Present** (Prisutno) | očekivana deca sa `attendance_days.status = 'CHECKED_IN'` |
| **Departed** (Otišlo) | očekivana deca sa `status = 'CHECKED_OUT'` |
| **Absent** (Odsutno) | očekivana deca sa aktivnim odsustvom za `d` (ili `absence_kind` popunjen) i `status = 'NOT_ARRIVED'`. Napomena: ako je odsustvo prijavljeno pošto je dete već `CHECKED_IN`, dete se broji kao Present (fizičko stanje ima prednost), a odsustvo se prikazuje kao oznaka. |
| **Not arrived** (Nije stiglo) | očekivana deca sa `status = 'NOT_ARRIVED'` i bez odsustva |
| **Late** (Kasni) | podskup **Not arrived** gde je `sada (zona org) > expected_arrival + late_arrival_grace_minutes`; plus deca koja su stigla posle `expected_arrival + grace` (oznaka „stiglo kasno” – informativno, ne ulazi u brojač Late nakon dolaska; otvoreno pitanje 9.9) |
| **Unscheduled present** (Neplanirano prisutno) | deca sa `is_expected = false` i `status ∈ {CHECKED_IN, CHECKED_OUT}` – prikazuju se **zasebno** i **ne ulaze** u Expected |
| **Physically present** (Fizički prisutno, za evakuacionu listu) | sva deca sa `status = 'CHECKED_IN'`, uključujući neplanirano prisutnu |

**Invarijanta koja se testira**: `Expected = Present + Departed + Absent + NotArrived` (svako očekivano dete je u tačno jednoj od četiri kategorije). `Late ⊆ NotArrived`. `Unscheduled present` je van jednakosti.

Napomena: dete koje je očekivano, prijavljeno odsutno i ipak došlo (`CHECKED_IN`) ulazi u Present, ne u Absent – invarijanta ostaje tačna jer su kategorije određene redosledom: (1) status `CHECKED_IN` → Present, (2) `CHECKED_OUT` → Departed, (3) `NOT_ARRIVED` + odsustvo → Absent, (4) ostalo → Not arrived.

#### Sadržaj stavke liste

| Polje | Izvor |
|---|---|
| Ime i prezime deteta, fotografija (ako postoji) | `children` |
| Očekivano vreme dolaska i odlaska (ili „nije očekivano”, „odsutno – bolest”, „neradni dan”) | `daily_plans` |
| Trenutni status (Nije stiglo / Prisutno od HH:MM / Otišlo u HH:MM / Kasni) | `attendance_days` |
| Oznake: „kasna izmena rasporeda”, „neplanirano”, „odsustvo prijavljeno tokom boravka”, „kritično zdravstveno upozorenje” (samo indikator) | `schedule_day_overrides.is_late_change`, `is_unscheduled`, `absence_kind`, `has_critical_alert` |
| Brza akcija: **Dolazak** / **Odlazak** (jedno dugme prema stanju) | – |
| Status sinhronizacije: „čeka slanje” (pending), „konflikt – pogledaj” | lokalni red komandi (5.19) |

#### Ekran / UI zahtevi

- Tap površine najmanje 48×48 dp; primarne akcije ≥ 56 dp visine; tekst najmanje 16 sp; kontrast po WCAG AA.
- Brojači na vrhu ekrana kao „čipovi” koji filtriraju listu (dodir na „Nije stiglo” prikazuje samo tu decu).
- Lista sortirana: prvo Not arrived (kasni na vrhu), zatim Present, Departed, Absent; neplanirano prisutni u posebnoj sekciji.
- Jasan izbor datuma (danas podrazumevano; juče i sutra dostupni) i grupe (ako vaspitač ima više).
- Pull-to-refresh i automatsko osvežavanje (polling + push invalidacija, 5.11).
- Offline indikator i broj pending komandi uvek vidljivi.

#### Van opsega

- Beleške po detetu za dan (dnevnik: spavanje, obroci) – P2.
- Dnevni pregled za roditelja (agregat grupe) – nije u opsegu (roditelj vidi samo svoje dete).

---

### 5.10 Obaveštenja (announcements)

#### Opis

Objave administracije (i vaspitača, po dozvoli) prema publici: cela organizacija, objekat, grupa ili pojedinačni staratelj.
Sistem beleži ko je i kada **otvorio** obaveštenje.

#### Korisničke priče

- **[P0]** Kao **admin** želim da napišem obaveštenje (naslov, tekst) za celu organizaciju ili jednu grupu, sačuvam kao nacrt i objavim odmah ili u zakazano vreme, da bih informisao roditelje.
- **[P0]** Kao **roditelj** želim da vidim obaveštenja koja se tiču mene i moje dece i da mi nepročitana budu istaknuta.
- **[P0]** Kao **admin** želim da vidim ko je pročitao obaveštenje (npr. 12 od 18 staratelja), da bih znao koga da pozovem.
- **[P0]** Kao **admin** želim da arhiviram ili postavim datum isteka obaveštenja, da lista ne bi bila zatrpana.
- **[P1]** Kao **admin** želim da priložim slike i PDF fajlove (nakon što postoji infrastruktura fajlova).
- **[P1]** Kao **vaspitač sa dozvolom** želim da objavim obaveštenje za svoju grupu.

#### Poslovna pravila

| # | Pravilo |
|---|---|
| N1 | Polja: `title` (≤ 200), `body` (čist tekst / ograničeni markdown; renderuje se **escape-ovano**, bez HTML-a), `publish_at`, `expires_at` (> `publish_at`), status `DRAFT` → `PUBLISHED` → `ARCHIVED`. |
| N2 | Publika (`announcement_audiences`): jedan ili više redova tipa `ORGANIZATION`, `LOCATION`, `GROUP`, `GUARDIAN`. Vaspitač može ciljati samo svoje grupe; admin bilo šta. |
| N3 | **Snapshot pri objavi**: u trenutku objave (ili u `publish_at`) sistem izračuna primaoce (staratelji dece iz ciljanih grupa/objekata, svi PARENT i osoblje za `ORGANIZATION`) i upiše `announcement_recipients` (`snapshot_at`). |
| N4 | **Provera trenutnih prava pri pristupu**: pri svakom čitanju server proverava da li korisnik i dalje ima pravo (aktivno članstvo, potvrđena veza sa detetom u ciljanoj grupi ili trenutno članstvo u organizaciji). Korisnik koji je u snapshot-u ali je izgubio prava ne vidi obaveštenje; korisnik koji je stigao posle objave ga **ne vidi** automatski (nije u snapshot-u) – admin može ručno „osvežiti primaoce” (P1). |
| N5 | Čitanje je **eksplicitna** akcija klijenta (`POST .../announcements/{id}/read` kad korisnik otvori sadržaj) → `read_at`. Push isporuka **nije** dokaz čitanja. |
| N6 | Objava emituje outbox događaj → notifikacija `ANNOUNCEMENT` (i push) svim primaocima iz snapshot-a; `dedup_key = announcement:{id}`. |
| N7 | Objavljivanje zahteva ulogu ADMIN/OWNER ili dozvolu `ANNOUNCEMENT_PUBLISH`. Izmena objavljenog obaveštenja je dozvoljena (verzija raste, `If-Match`), ali se ne šalje nova notifikacija osim ako autor to izabere. |
| N8 | Isteklo (`expires_at < now`) se ne prikazuje u podrazumevanoj listi, ali ostaje dostupno kroz „arhiva”. |
| N9 | Audit: `ANNOUNCEMENT_PUBLISHED`, `ANNOUNCEMENT_ARCHIVED`; statistika čitanja vidljiva osoblju kao broj i lista imena (bez izvoza u P0). |

#### Ekrani / tokovi

- Web: **Announcements** (lista: nacrti / objavljena / arhivirana; forma sa izborom publike; pregled čitanja).
- Mobile (roditelj i vaspitač): **Obaveštenja** (lista sa nepročitanim, detalj, oznaka pročitano pri otvaranju).

#### Van opsega

- Ankete/glasanja unutar obaveštenja – P2 (surveys).
- Slanje obaveštenja SMS-om ili email-om roditeljima bez naloga – nije u opsegu pilota.

---

### 5.11 Notifikacije i realtime

#### Opis

Generički sistem obaveštavanja korisnika: **in-app inbox** (izvor istine), plus **push** (FCM/APNs) i **email** kao
nepouzdani kanali. Domenski događaji se pišu u **transactional outbox** u istoj transakciji, a radnik ih obrađuje sa
retry/backoff, dedup-om i dead-letter statusom.

#### Korisničke priče

- **[P0]** Kao **korisnik** želim inbox sa svim notifikacijama i brojem nepročitanih, da ništa ne propustim čak i ako push ne stigne.
- **[P0]** Kao **roditelj** želim push kada stigne novo obaveštenje ili (ako vrtić uključi) kada dete stigne u vrtić.
- **[P0]** Kao **vaspitač** želim push kada roditelj prijavi odsustvo ili kasno promeni raspored za sutra.
- **[P0]** Kao **korisnik** želim da push poruka ne otkriva osetljive podatke na zaključanom ekranu.
- **[P1]** Kao **korisnik** želim da podesim koje notifikacije primam.

#### Poslovna pravila

| # | Pravilo |
|---|---|
| R1 | Vrste (`notifications.kind`): `ANNOUNCEMENT`, `SCHEDULE_CHANGE`, `ABSENCE`, `ATTENDANCE`, `CALENDAR_EVENT`, `URGENT`, `CONSENT_REQUEST`, `MESSAGE`, `SECURITY`, `SYSTEM`. |
| R2 | Sadržaj notifikacije je **i18n ključ + argumenti** (`title_key`, `title_args`); klijent renderuje na jeziku korisnika. `title_args` nikada ne sadrži zdravstvene podatke, tekst poruka ni nepotrebne identifikatore. |
| R3 | **Push payload je minimalan i neproziran**: tip, `ref_entity_type`, `ref_entity_id`, i generički tekst (npr. „Novo obaveštenje iz vrtića”); detalji se dobijaju pozivom API-ja posle otključavanja. |
| R4 | **Outbox**: poslovna transakcija upisuje `outbox_events`; radnik (`app_worker`) čita `PENDING`, kreira `notifications` i `notification_deliveries`, šalje, i označava `PROCESSED`; greške → `attempts++`, `next_attempt_at` eksponencijalno (1 min, 5 min, 30 min, 2 h, 12 h), posle N pokušaja `DEAD` (alarm). |
| R5 | **Dedup**: `notifications` ima jedinstveni `(recipient_user_id, dedup_key)`; ponovljeni outbox događaj ne pravi dupli inbox zapis. |
| R6 | Nevažeći push tokeni (odgovor provajdera `Unregistered`/`InvalidRegistration`) se označavaju `invalidated_at` i ne koriste se ponovo. Token je vezan za sesiju; logout uređaja poništava token. |
| R7 | **Push nije garantovan kanal** – nijedna funkcionalnost ne sme zavisiti od isporuke push-a; inbox i polling su oslonac. |
| R8 | Realtime za pilot: **polling** (npr. dnevni pregled svakih 30–60 s dok je ekran aktivan) + **push invalidacija** („osveži dnevni pregled”); SSE kasnije (P1/P2); REST za sve komande; **bez WebSocket-a** dok ne postoji jasna potreba. |
| R9 | Bez tokena u URL-u (ni za SSE); posle ponovnog povezivanja klijent radi pun resync; opozvana sesija odmah prekida stream/polling sa `401`. |
| R10 | Bezbednosne notifikacije (`SECURITY`: nova prijava, reuse detected, promena lozinke) imaju `organization_id = NULL` (globalne) i šalju se i email-om. |
| R11 | Email kanal (SES) za: verifikaciju, reset, pozivnice, bezbednosne događaje; opciono sažetak obaveštenja (P1). |

#### Ekrani / tokovi

- Mobile: **Inbox** (lista, nepročitano, dodir vodi na entitet), sistemska dozvola za push tražena tek posle prve prijave uz objašnjenje.
- Web: zvonce sa brojem nepročitanih i lista.

#### Van opsega

- SMS kanal, Viber/WhatsApp – nije u opsegu.
- Garantovana isporuka push-a – nemoguće; ne obećava se.

---

### 5.12 Poruke (P1)

#### Opis

Razgovori roditelja sa vaspitačima grupe ili sa administracijom, uvek o **jednom detetu**. Učesnike određuje server na
osnovu veza staratelja i dodela vaspitača; klijent ne bira učesnike.

#### Korisničke priče

- **[P1]** Kao **roditelj** želim da pošaljem poruku vaspitačima grupe mog deteta (npr. „danas ga preuzima baka”), da ne bih zvao telefonom.
- **[P1]** Kao **vaspitač** želim da vidim razgovore za decu iz mojih grupa i broj nepročitanih.
- **[P1]** Kao **roditelj** želim da pišem administraciji (upisi, plaćanja).
- **[P1]** Kao **korisnik** želim da poruka poslata pri lošem signalu ne bude duplirana.
- **[P1]** Kao **admin** želim da zatvorim razgovor koji više nije aktuelan.

#### Poslovna pravila

| # | Pravilo |
|---|---|
| M1 | Vrste: `PARENT_TEACHER` (učesnici: potvrđeni staratelji deteta + vaspitači sa važećom dodelom grupi deteta) i `PARENT_ADMIN` (staratelji + ADMIN/OWNER članstva). |
| M2 | `conversation_participants` se održava serverski: kada dodela vaspitača istekne ili se veza staratelja opozove, učesnik dobija `left_at` i više ne vidi nove poruke (istorija do tog trenutka po odluci vrtića – otvoreno pitanje 9.10). Novi vaspitač grupe se dodaje automatski. |
| M3 | Poruka: tekst 1–4000 znakova, bez fajlova u P1 (fajlovi P2), `client_message_id` za idempotentno ponovno slanje (jedinstveno po razgovoru). |
| M4 | Nepročitano se računa iz `last_read_message_id`/`last_read_at` po učesniku. |
| M5 | Pristup razgovoru zahteva: aktivno članstvo + učešće (`left_at IS NULL`) + i dalje važeće pravo nad detetom. |
| M6 | Nova poruka → outbox → notifikacija `MESSAGE` ostalim učesnicima (push bez teksta poruke). |
| M7 | Brisanje poruke je meko (`deleted_at`) i vidljivo kao „poruka obrisana”; izmena beleži `edited_at` (rok 15 min). |
| M8 | Sadržaj poruka se ne loguje i ne izvozi bez privacy zahteva. Feature flag `messaging` mora biti uključen za organizaciju. |

#### Ekrani

- Mobile: **Poruke** (lista razgovora sa nepročitanim, razgovor, novi razgovor: izbor deteta + „vaspitači” ili „uprava”).
- Web: **Messages** za administraciju (inbox PARENT_ADMIN razgovora).

#### Van opsega

- Grupni chat roditelja, WhatsApp/Viber integracija – nije u opsegu.
- Slanje fajlova/slika u porukama – P2.

---

### 5.13 Kalendar (P1)

#### Opis

Događaji vrtića: izlet, fotografisanje, predstava, praznik, roditeljski sastanak, neradni dan i drugo; celodnevni ili sa
vremenom; za celu organizaciju, objekat ili grupu.

#### Korisničke priče

- **[P1]** Kao **admin** želim da unesem izlet za grupu sa datumom i vremenom, da roditelji vide na vreme.
- **[P1]** Kao **roditelj** želim mesečni pregled događaja koji se tiču mog deteta.
- **[P1]** Kao **admin** želim da događaj tipa „izlet” zahteva saglasnost staratelja (vezano za politiku saglasnosti).
- **[P1]** Kao **admin** želim da neradni dan iz kalendara automatski utiče na raspored.

#### Poslovna pravila

| # | Pravilo |
|---|---|
| K1 | Vrste: `TRIP`, `PHOTO_DAY`, `PERFORMANCE`, `HOLIDAY`, `PARENT_MEETING`, `CLOSURE`, `OTHER`. Celodnevni događaji koriste `starts_on`/`ends_on`; vremenski `starts_at`/`ends_at` (UTC) + `timezone` za prikaz. |
| K2 | Opseg: `location_id` i `group_id` opcioni (NULL = šire); roditelj vidi događaje organizacije, objekta i grupe svog deteta; vaspitač svoje grupe i šire. |
| K3 | Događaj tipa `CLOSURE` kreira odgovarajući `closure_days` red (i obrnuto brisanje) – jedna istina za neradne dane. |
| K4 | `requires_consent_policy_id` (npr. `TRIP_PARTICIPATION`) → sistem šalje `CONSENT_REQUEST` starateljima i prikazuje status saglasnosti po detetu. |
| K5 | Verzionisanje `version`/`If-Match`; izmena datuma emituje `CALENDAR_EVENT` notifikaciju. |

#### Van opsega

- Sinhronizacija sa Google/Apple kalendarom (ICS export) – P2.
- Prijava učešća (RSVP) mimo saglasnosti – P2.

---

### 5.14 Jelovnik (P1)

#### Opis

Dnevni/nedeljni jelovnik po organizaciji ili objektu sa četiri obroka. Informativan – nije medicinska ni nutritivna preporuka.

#### Korisničke priče

- **[P1]** Kao **admin** želim da unesem jelovnik za nedelju (doručak, užina pre podne, ručak, užina posle podne) i objavim ga.
- **[P1]** Kao **roditelj** želim da vidim šta dete jede danas i ove nedelje, sa oznakama alergena.
- **[P2]** Kao **admin** želim nutritivne vrednosti po obroku.

#### Poslovna pravila

| # | Pravilo |
|---|---|
| J1 | `menu_days` jedinstven po (organizacija, datum) ili (objekat, datum); `is_published` kontroliše vidljivost roditeljima. |
| J2 | Obroci `menu_items.meal_slot`: `BREAKFAST`, `SNACK_AM`, `LUNCH`, `SNACK_PM` (dve različite užine). Više stavki po obroku (`sort_order`). |
| J3 | `allergen_tags` su informativne oznake (npr. `GLUTEN`, `MILK`, `NUTS`); sistem **ne** upoređuje sa zdravstvenim profilom deteta automatski u P1 (moguće P2 uz posebnu analizu, jer bi to bilo izvođenje iz zdravstvenih podataka). |
| J4 | Kopiranje jelovnika iz prethodne nedelje; verzionisanje `version`. Feature flag `meals`. |

#### Van opsega

- Naručivanje obroka, naplata, dijete po detetu – nije u opsegu.

---

### 5.15 Fotografije i saglasnosti (P1)

#### Opis

Deljenje fotografija sa roditeljima preko **privatnog storage-a** uz **publiku** (ko sme da vidi) i **saglasnosti**
(da li uopšte sme da se distribuira). Ova dva pojma su strogo odvojena.

#### Korisničke priče

- **[P1]** Kao **vaspitač** želim da otpremim fotografije sa aktivnosti i označim decu koja se prepoznaju, da bi ih roditelji videli.
- **[P1]** Kao **roditelj** želim da dam ili uskratim saglasnost za fotografisanje/deljenje, i da je povučem.
- **[P1]** Kao **roditelj** želim da vidim fotografije svog deteta i njegove grupe (ako je to publika).
- **[P1]** Kao **admin** želim da objava bude blokirana ako neko označeno dete nema važeću saglasnost.
- **[P1]** Kao **admin** želim da povučem fotografiju, čime prestaje svaka nova distribucija.

#### Poslovna pravila – fajlovi

| # | Pravilo |
|---|---|
| F1 | **Bez javnih URL-ova**. Bajtovi u privatnom S3 bucket-u; u bazi samo metapodaci (`files`). Pristup samo preko kratkotrajnog signed URL-a (≤ 5 min) ili proxy endpointa uz proveru prava **na svaki zahtev**. |
| F2 | Upload tok: `POST .../files` (autorizacija, `purpose`, `declared_mime`, veličina) → server vraća pre-signed PUT ka `quarantine` prefiksu → klijent otpremi → `POST .../files/{id}/complete` → status `QUARANTINED` → radnik: MIME sniffing (`detected_mime`), limit veličine, malware scan, EXIF uklanjanje, kompresija, generisanje `file_variants` (`THUMB_SM`, `THUMB_MD`, `WEB`) → `READY` ili `REJECTED` (razlog). |
| F3 | Limiti: slika ≤ 15 MB, dozvoljeni tipovi `image/jpeg`, `image/png`, `image/heic` (konvertuje se), prilog ≤ 20 MB (`application/pdf`); po organizaciji ukupna kvota iz plana (`plans.limits`). |
| F4 | Thumbnails i varijante imaju **iste dozvole** kao original (uvek se razrešavaju preko `files`). |
| F5 | Preuzeta fotografija (na uređaju korisnika) tehnički **ne može** da se povuče; proizvod to ne obećava. Bez offline galerije (ne kešira se). |

#### Poslovna pravila – publika i saglasnost

| # | Pravilo |
|---|---|
| P1 | **Publika** (`photo_audiences`): `GROUP` (staratelji dece grupe + vaspitači), `CHILD` (staratelji tog deteta), `GUARDIAN` (konkretan staratelj). **Saglasnost** (`consent_decisions`) je nezavisna: publika kaže ko vidi, saglasnost kaže da li sme da se deli. |
| P2 | **Pravilo grupne fotografije**: osoblje mora označiti **svako prepoznatljivo dete** (`photo_tagged_children`). Objava (`DRAFT → PUBLISHED`) prolazi samo ako **sva** označena deca imaju važeću `GRANTED` odluku za odgovarajuću svrhu (`PHOTO_INTERNAL_GALLERY` za publiku `CHILD`/`GUARDIAN`; `PHOTO_GROUP_SHARING` za publiku `GROUP`). U suprotnom `422` sa listom dece bez saglasnosti. **Bez prepoznavanja lica** – označavanje je ručno. |
| P3 | Politika saglasnosti (`consent_policies`): svrha, verzija, jezik, tekst i `wording_hash`; nova verzija ne poništava stare odluke automatski, ali vrtić može da zatraži ponovnu saglasnost; `retired_at` politika više ne važi za nove objave. |
| P4 | Odluka (`consent_decisions`): staratelj sa `can_give_consent`, `GRANTED`/`DECLINED`, `evidence` (IP, user agent, session, wording hash), `withdrawn_at` za povlačenje. Važeća = poslednja nepovučena `GRANTED` na nepovučenoj politici. Ako dva staratelja daju suprotne odluke, važi **restriktivnija** (DECLINED) – otvoreno pitanje 9.11. |
| P5 | **Povlačenje saglasnosti**: blokira novu distribuciju – fotografije sa tim detetom prelaze u `WITHDRAWN` za publiku širu od staratelja tog deteta (ili u celini – podešavanje), thumbnails prestaju da se serviraju, ne šalju se nove notifikacije. Već preuzete kopije nisu obuhvaćene (F5). |
| P6 | Objavljivanje zahteva `PHOTO_PUBLISH` ili ADMIN/OWNER; upload mogu vaspitači za svoje grupe. Feature flag `photos`. |
| P7 | Audit: `PHOTO_PUBLISHED`, `PHOTO_WITHDRAWN`, `CONSENT_GRANTED`, `CONSENT_WITHDRAWN`, `FILE_ACCESSED` (uzorkovano/agregirano da ne bi eksplodirao log – odluka u SECURITY.md). |

#### Ekrani

- Mobile (vaspitač): **Fotografije → Otpremi** (kamera/galerija, izbor grupe, označavanje dece, publika) → status obrade → objava (ako ima dozvolu) ili „poslato na odobrenje”.
- Mobile (roditelj): **Fotografije** (po detetu/grupi), **Saglasnosti** (lista politika, odluka, povlačenje).
- Web: **Photos** (moderacija, objava, povlačenje), **Settings → Saglasnosti** (politike i verzije).

#### Van opsega

- Prepoznavanje lica, automatsko označavanje – nije u opsegu (zahteva posebnu analizu).
- Javne galerije, deljenje na društvene mreže, download u punoj rezoluciji „na klik” – nije u opsegu.

---

### 5.16 Dokumenti (P2)

#### Opis

Verzionisani dokumenti vrtića (ugovori, pravilnici, obrasci saglasnosti) sa zahtevom za odgovor staratelja i click-through
prihvatanjem uz dokaze. Modelovano u šemi (`documents`, `document_versions`, `document_requests`, `document_responses`),
**nije u početnoj implementaciji**.

#### Poslovna pravila (nacrt)

- Svaka verzija ima `content_hash` fajla; odgovor beleži hash verzije koja je **stvarno prikazana**.
- Odgovor je jednokratan po zahtevu (`UNIQUE (request_id)`), append-only, `ACCEPT`/`DECLINE` sa `evidence`.
- **Nije kvalifikovani elektronski potpis**; proizvod to jasno saopštava.
- Zahtevi imaju rok (`due_at`) i status `OPEN`/`RESPONDED`/`EXPIRED`/`CANCELLED`.

#### Van opsega

- Kvalifikovani e-potpis, integracija sa eID-om – nije planirano.

---

### 5.17 Pretplata i feature flagovi

#### Opis

Vrtić plaća pretplatu po planu; platforma upravlja planovima, pretplatama i feature flagovima. U pilotu nema platnog
provajdera – stanje pretplate vodi platformski admin ručno.

#### Korisničke priče

- **[P0]** Kao **platformski admin** želim da dodelim vrtiću plan i trial period, i da menjam status pretplate ručno.
- **[P0]** Kao **vlasnik** želim da vidim svoj plan, status i period, i šta plan uključuje.
- **[P0]** Kao **platformski admin** želim da uključim/isključim funkcionalnost (fotografije, poruke, jelovnik) za konkretan vrtić ili globalno (kill switch).
- **[P2]** Kao **vlasnik** želim da platim karticom i vidim fakture (payment provider).

#### Poslovna pravila

| # | Pravilo |
|---|---|
| L1 | Planovi `STARTER`/`STANDARD`/`PRO` su verzionisani (`plans.code + version`); promena cene je nova verzija; postojeće pretplate ostaju na svojoj verziji do promene. Cena u **minor jedinicama** (`monthly_price_minor`, `RSD` podrazumevano), nikada float. |
| L2 | `plans.entitlements` (npr. `photos_enabled`, `messaging_enabled`, `meals_enabled`) i `plans.limits` (npr. `max_children`, `max_locations`, `storage_gb`). |
| L3 | Pretplata: `TRIAL` → `ACTIVE` → (`PAST_DUE`) → `CANCELLED`; najviše jedna tekuća po organizaciji; `billing period` (`current_period_start/end`), `cancel_at_period_end`; `provider` ∈ `MANUAL`, `STRIPE`, `LOCAL_INVOICE` (adapter – samo `MANUAL` u pilotu). Svaka promena piše `subscription_events`. |
| L4 | **Downgrade ili kašnjenje ne brišu podatke**: `PAST_DUE` prikazuje upozorenje vlasniku; posle konfigurisanog roka (npr. 30 dana) organizacija može biti `SUSPENDED` (read-only), ali se ništa ne briše dok ne istekne retencija posle `CANCELLED`. |
| L5 | Limiti iz plana su meke blokade sa jasnom porukom (`422` `plan-limit-exceeded`), ne tihi gubici. |
| L6 | **Feature flag**: `effective(flag, org) = NOT feature_flags.kill_switch AND COALESCE(organization_feature_overrides.enabled (ako nije istekao), plan.entitlements[flag], feature_flags.default_enabled)`. Četiri sloja su razdvojena: plan entitlement, tenant override (sa razlogom i rokom), kill switch (hitno globalno gašenje), korisničke dozvole. |
| L7 | **Flag nije autorizacija**: isključen flag vraća `404`/`403` na endpointima funkcije; uključen flag ne zaobilazi uloge/dozvole. |
| L8 | Početni flagovi: `photos`, `messaging`, `meals`, `payments` (isključeni podrazumevano), `calendar`. |
| L9 | Klijent dobija efektivne flagove preko `GET /api/v1/organizations/{organizationId}/feature-flags` i sakriva funkcije, ali server je jedini autoritet. |

#### Ekrani

- Web (platformski admin): **Platform → Organizations** (lista, kreiranje, status), **Plans**, **Subscriptions**, **Feature flags** (globalno + override po organizaciji).
- Web (vlasnik): **Billing** (plan, status, period, limiti/iskorišćenost).

#### Van opsega

- Naplata karticom, fakturisanje, PDV logika – P2 (kroz provider adapter).
- Marketplace, dodaci (add-ons) – P2.

---

### 5.18 Privatnost i GDPR

> Ovaj odeljak opisuje **tehničke mere** koje proizvod planira. Nije pravni savet; rokovi čuvanja su **predlozi** koje treba
> potvrditi sa pravnim savetnikom (Zakon o zaštiti podataka o ličnosti Srbije i GDPR za EU tržišta).

#### Korisničke priče

- **[P0]** Kao **roditelj** želim da zatražim izvoz podataka o svom detetu i sebi, da bih ostvario pravo pristupa.
- **[P0]** Kao **vlasnik** želim da obradim zahtev za brisanje bivšeg korisnika uz proveru identiteta.
- **[P0]** Kao **vlasnik** želim da vidim ko je pristupao osetljivim podacima (audit).
- **[P1]** Kao **platformski admin** želim da podesim podrazumevane rokove čuvanja, a vrtić da ih prilagodi.

#### Tehničke mere

| Oblast | Mera |
|---|---|
| Minimizacija | bez JMBG-a, bez fotografija dokumenata, zdravstveni podaci samo kad je potrebno i odvojeno; push bez sadržaja; logovi bez PII. |
| Pristup | RLS + aplikativna autorizacija po grupi/detetu; posebne dozvole za zdravlje; support pristup vremenski ograničen i auditovan. |
| Šifrovanje | TLS 1.2+ u prenosu; šifrovanje at rest (RDS, S3); envelope encryption za zdravstvene profile i MFA tajne (KMS ključevi, `key_id` u redu); tajne van repozitorijuma. |
| Zahtevi lica (`privacy_requests`) | vrste `ACCESS`, `EXPORT`, `ERASURE`, `RECTIFICATION`; statusi `RECEIVED` → `IDENTITY_PENDING` → `IN_PROGRESS` → `COMPLETED`/`REJECTED`; provera identiteta beleži ko i kada. |
| Izvoz (`data_exports`) | privatni fajl (`files.purpose = 'DATA_EXPORT'`) sa rokom isteka; opseg jasno opisan; **nikada** ne uključuje podatke druge dece ni privatne podatke drugog staratelja. |
| Brisanje (`erasure_ledger`) | brisanje/anonimizacija po subjektu; ledger čuva samo ID i opseg da bi se brisanje **ponovo primenilo posle vraćanja iz backup-a** (`reapplied_at`). Obavezni zapisi (npr. finansijski, audit) se anonimizuju umesto brisanja. |
| Retencija (`retention_policies`) | predlozi: prisustvo 3 godine (ili prema propisu), poruke 12 meseci posle završetka upisa, fotografije 12 meseci posle ispisa deteta, audit log 5 godina, sesije/tokeni 90 dana posle isteka, `login_attempts` 90 dana. Platformski podrazumevano + override po organizaciji. |
| Legal hold (`legal_holds`) | zaustavlja retenciju za konkretan entitet (spor, istraga) uz razlog i audit. |
| Audit | `audit_log` append-only (trigger), akcije: prijave, članstva, staratelji, zdravlje (čitanje!), korekcije prisustva, objave, saglasnosti, support pristup, izvozi, promene podešavanja. **Append-only ne štiti od DBA** – za dokaznu vrednost log se šalje i u write-once eksterni storage (SECURITY.md). |
| Logovanje | nikad: lozinke, tokeni, signed URL-ovi, medicinski podaci, tekst poruka, nepotrebni ID-jevi dece. `request_id` u svakom logu i audit zapisu. |
| Obaveštavanje o incidentu | proces i kontakt tačke opisani u SECURITY.md (van opsega proizvoda samog). |
| Transparentnost | politika privatnosti i uslovi prikazani pri prihvatanju pozivnice (verzija se beleži uz prihvatanje). |

#### Van opsega

- DPIA (procena uticaja) kao dokument – posao vlasnika proizvoda / pravnika; proizvod daje ulaze.
- Automatsko brisanje bez ljudske potvrde za zahteve `ERASURE` – nije u P0 (potvrđuje vlasnik/admin).

---

### 5.19 Offline rad vaspitača

#### Opis

Vaspitač mora da može da koristi dnevni pregled i evidentira prisustvo i bez interneta. P0 offline obuhvata **čitanje**
(lista dece, dnevni raspored, dnevno prisustvo) i **red pending komandi prisustva**. Bez „last-write-wins”: svaki konflikt se
prikazuje i rešava eksplicitno.

#### Korisničke priče

- **[P0]** Kao **vaspitač** želim da dnevni pregled radi kada u dvorištu nema signala, da bih evidentirao dolaske i odlaske u trenutku kada se dese.
- **[P0]** Kao **vaspitač** želim da vidim koje akcije čekaju slanje i da se same pošalju kada se veza vrati.
- **[P0]** Kao **vaspitač** želim jasno upozorenje ako je koleginica u međuvremenu već evidentirala isto dete, umesto da moja akcija tiho prepiše njenu.
- **[P0]** Kao **vlasnik** želim da offline podaci na tabletu budu šifrovani i da nestanu posle 12 h ili odjave.

#### Algoritam (10 koraka)

| Korak | Ponašanje |
|---|---|
| 1. Snapshot | Pri svakom online otvaranju grupe/datuma klijent preuzima snapshot: deca grupe, `daily_plans`, `attendance_days` (sa `version`), ovlašćene osobe, verzije i `snapshotAt`. |
| 2. Šifrovana lokalna baza | Snapshot se čuva u SQLDelight bazi šifrovanoj ključem iz Keychain/Keystore, **odvojeno po korisniku i tenant-u** (promena korisnika/tenant-a = druga baza; odjava = brisanje). |
| 3. Rok važenja | Snapshot važi `offline_cache_ttl_hours` (podrazumevano **12 h**); posle isteka ekran prelazi u „samo pregled, podaci možda zastareli” i komande se ne primaju dok se ne osveži. |
| 4. Komanda | Svaka akcija vaspitača kreira komandu: `commandId` (UUID), `childId`, `date`, `type` (`CHECK_IN`/`CHECK_OUT`), `expectedVersion` (verzija `attendance_days` iz snapshot-a + lokalno primenjene komande), `occurredAt` (vreme na uređaju, UTC), `deviceId`. |
| 5. Pending oznaka | Komanda se odmah primenjuje na **lokalnu projekciju** (optimistički) i stavka dobija oznaku „čeka slanje”; brojači se računaju istom formulom kao na serveru. |
| 6. Reconnect – provera prava | Po povratku veze klijent prvo osveži sesiju (refresh) i proveri članstvo/dodele (`GET /me/memberships`, `GET .../groups/{id}/assignments/me`); ako je pristup opozvan, red se **odbacuje** i podaci brišu (opoziv na offline uređaju nije garantovan dok je uređaj offline – to je poznato ograničenje). |
| 7. Idempotentno slanje | Komande se šalju **redom** (FIFO po detetu) sa `Idempotency-Key = commandId` i `source = OFFLINE_SYNC`; ponovni pokušaji su bezbedni (T4). |
| 8. Odgovor | Server vraća: **potvrda** (`200/201`, nova verzija) → komanda primenjena; **odbijanje** (`422`, npr. dete više nije u grupi) → stavka označena greškom sa porukom; **konflikt** (`409` sa serverskim stanjem) → stavka označena „konflikt”. |
| 9. Uklanjanje iz reda | Komanda se uklanja iz lokalnog reda **tek posle** potvrde ili eksplicitne odluke vaspitača za odbijene/konfliktne (ne automatski). |
| 10. Tombstones i resync | Server vraća i „tombstone” zapise (dete ispisano, dodela istekla) da klijent ukloni lokalne podatke; posle sinhronizacije klijent radi pun resync snapshot-a. |

#### Rešavanje konflikata (bez last-write-wins)

| Situacija | Ponašanje klijenta |
|---|---|
| Ista akcija već evidentirana na serveru (npr. koleginica prijavila dolazak u 7:58, ja offline u 8:01) | Server: `409` sa stanjem `CHECKED_IN` – klijent nudi „prihvati serversko stanje” (podrazumevano) ili „unesi korekciju vremena” (ako ima dozvolu). |
| Serversko stanje omogućava moju akciju iako verzija ne odgovara (npr. dete `CHECKED_IN` na serveru, moja komanda je `CHECK_OUT` sa starom verzijom) | Server odbija zbog verzije (`409`); klijent prikazuje razliku i nudi „ponovi na trenutnoj verziji” – vaspitač potvrđuje. Nikad automatski. |
| Dete više nije u grupi / dodela istekla | `422` ili `403` – stavka „nije moguće”, vaspitač obaveštava administraciju. |
| Snapshot istekao pre slanja | Komande se i dalje šalju (imaju `occurredAt`), ali se novi unosi ne primaju do osvežavanja. |

#### Ograničenja

- **Nema offline pristupa** zdravstvenim profilima, fotografijama, porukama ni obaveštenjima (samo ono što je već renderovano u sesiji).
- Nema offline izmene rasporeda ni odsustava (samo prisustvo).
- Web admin nema offline režim.
- Opoziv prava dok je uređaj offline stupa na snagu tek pri sledećem povezivanju (rizik prihvaćen i dokumentovan; TTL 12 h ga ograničava).

---

## 6. Lokalizacija i vreme

### 6.1 Jezici

| Faza | Jezici | Napomena |
|---|---|---|
| P0 | `sr-Latn` (srpski latinica), `sr-Cyrl` (srpski ćirilica), `en` | sva tri od početka; podrazumevani jezik organizacije `sr-Latn`; korisnik bira svoj (`users.preferred_locale`) |
| kasnije | `hr`, `bs`, `sl`, `mk`, `de`, `sv` | dodaju se kao resursi bez izmene koda; enum u bazi se proširuje migracijom |

Pravila:

- **Bez hardkodovanog teksta** u klijentima i backend porukama (i `problem+json` `title`/`detail` koriste ključeve ili su na engleskom uz `code` koji klijent prevodi).
- **Bez automatske transliteracije imena**: ime deteta/korisnika se prikazuje tačno kako je uneto, bez obzira na izabrano pismo interfejsa. Vrtić može uneti ime i na latinici i na ćirilici kao dva polja (P1, otvoreno pitanje 9.12); u P0 jedno polje.
- Sortiranje po imenu koristi lokalnu kolaciju (`sr` – npr. „Č”, „Ć”, „Š” na pravom mestu); pretraga je neosetljiva na dijakritike i pismo (sr-Latn ↔ sr-Cyrl mapiranje pri pretrazi je P1).
- Notifikacije se čuvaju kao i18n ključ + argumenti i renderuju na jeziku primaoca.
- Formati datuma/vremena/brojeva po lokalu (npr. `16. 9. 2026.` u `sr`, 24-časovni format).

### 6.2 Vreme i vremenske zone

| Podatak | Tip | Pravilo |
|---|---|---|
| Događaji (prijava, check-in, objava, audit) | `timestamptz` (UTC instant) | prikaz u zoni organizacije/uređaja; nikad se ne menja naknadno |
| Datum rođenja, datumi upisa, odsustva, planova | `date` | kalendarski datum bez zone |
| Raspored (dolazak/odlazak) | `time` (lokalno zidno vreme) + IANA zona organizacije/objekta | „08:00” znači 08:00 po zidnom satu vrtića bez obzira na DST |
| Datum prisustva (`attendance_date`) | `date` u zoni vrtića | određuje se iz `occurred_at` konvertovanog u zonu organizacije (objekta) |
| Kalendarski događaji sa vremenom | `timestamptz` + `timezone` polje | za ispravan prikaz i posle promene zone |

- Organizacija ima IANA zonu (`Europe/Belgrade` podrazumevano); objekat opciono drugačiju.
- DST prelazi: rasporedi ostaju na zidnom vremenu; poređenje „kasni” se radi konverzijom očekivanog zidnog vremena u instant za taj datum.
- **Promena vremenske zone organizacije ne menja istoriju**: zamrznuti `daily_plans` i `attendance_days` ostaju kakvi su; nova zona važi za buduće datume.
- Server i baza rade u UTC; uređaji šalju `occurredAt` kao UTC instant; sumnjivo odstupanje sata uređaja (> 5 min od servera) se prijavljuje korisniku.

---

## 7. Nefunkcionalni zahtevi

### 7.1 Bezbednost

| Zahtev | Mera |
|---|---|
| Transport | TLS 1.2+ (preferirano 1.3), HSTS, bez mešanog sadržaja |
| Identitet | Argon2id, opaque tokeni sa hash-om u bazi, rotacija refresh-a, detekcija ponovne upotrebe, MFA za privilegovane, reautentifikacija |
| Web | HttpOnly/Secure/SameSite kolačići, CSRF sa tačnim Origin-om, CSP bez `unsafe-inline`, bez tokena u localStorage, escape-ovanje svakog korisničkog sadržaja |
| Mobile | Keychain/Keystore, bez tokena u logovima/preferences, certificate pinning razmotriti (P1), šifrovana offline baza |
| Izolacija | RLS ENABLE+FORCE, kompozitni FK, runtime bez BYPASSRLS, odvojeni migracioni i worker kredencijali, transaction-local kontekst |
| Autorizacija | uloge po članstvu + dozvole + opseg (grupa/dete/period); server je jedini autoritet; deny-by-default |
| Rate limiting | prijava, reset, pozivnice, upload, slanje poruka – `429` + `Retry-After` |
| Tajne | u secret manageru, rotacija, nikada u repozitorijumu; `.env.example` bez vrednosti |
| Fajlovi | privatni bucket, quarantine, MIME sniffing, malware scan, EXIF strip, signed URL ≤ 5 min |
| Skeniranje | dependency scan i secret scan u CI; SAST po mogućnosti; penetraciono testiranje pre produkcije (ciljano) |
| Audit | append-only + eksterna kopija; lista auditovanih akcija u SECURITY.md |
| Backup/DR | dnevni šifrovani backup + PITR; RPO 15 min; RTO 4 h; vežba vraćanja pre pilota (dokumentovana) |

### 7.2 Performanse i kapacitet (ciljevi za validaciju, ne garancije)

| Metrika | Cilj |
|---|---|
| Dnevni pregled grupe (30 dece) | p95 < 400 ms server-side; < 1 s na uređaju online |
| Check-in/out komanda | p95 < 300 ms server-side; lokalno trenutna (optimistička) |
| Lista dece organizacije (300 dece) | p95 < 500 ms sa cursor paginacijom (≤ 50 po strani) |
| Prijava (Argon2id) | 250–500 ms namerno (cena hash-a) |
| Kapacitet pilota | 10–50 vrtića, do ~5.000 dece, ~15.000 korisnika; kasnije 500+ vrtića uz horizontalno skaliranje aplikacije i vertikalno baze |
| Push isporuka | 95 % unutar 60 s od događaja (mereno; nije garancija) |
| Veličina snapshot-a za offline | < 200 KB po grupi/danu |

### 7.3 Dostupnost i pouzdanost

- **SLO 99,5 %** mesečno za API u pilotu (cilj koji se meri i dokazuje, ~3,6 h nedostupnosti mesečno dozvoljeno).
- **RPO 15 min**, **RTO 4 h** – ciljevi za dokazivanje vežbom oporavka.
- Health endpointi: `/health/live` (proces živ) i `/health/ready` (baza dostupna, migracije primenjene).
- Graceful shutdown, idempotentne komande omogućavaju bezbedan retry klijenata.
- Migracije: expand/contract, jedan migracioni job, rollback aplikacije bez slepog vraćanja migracija.

### 7.4 Pristupačnost (accessibility)

- Mobile: tap površine ≥ 48×48 dp (primarne akcije vaspitača ≥ 56 dp), podrška za dinamičku veličinu fonta, TalkBack/VoiceOver oznake na svim kontrolama, kontrast ≥ 4,5:1, bez informacija prenetih samo bojom (statusi imaju ikonu + tekst).
- Web: WCAG 2.1 AA kao cilj; navigacija tastaturom, fokus indikatori, ARIA oznake, tabele sa zaglavljima, forme sa jasnim greškama.
- Podrška za oba pisma bez gubitka čitljivosti (fontovi sa ćiriličnim glifovima).

### 7.5 Održivost i kvalitet

- Modularni monolit (`com.vrticconnect.<module>`), eksplicitni SQL repozitorijumi, bez poslovne logike u klijentima koja bi dublirala server (osim formule brojača, koja mora biti identična i testirana na obe strane).
- OpenAPI ugovor kao izvor za klijente; `problem+json` greške; cursor paginacija; `If-Match`/`ETag`; `Idempotency-Key`.
- CI: build, testovi, migracija na nativnom PostgreSQL-u, negativni RLS testovi, OpenAPI validacija, web typecheck/build, mobile shared testovi, skeniranje zavisnosti i tajni.
- Monitoring: metrike (latencija, greške, saturacija pool-a, outbox zaostatak, dead-letter), strukturirani logovi sa `request_id`, alarmi (5xx, outbox DEAD, refresh reuse, RLS odbijanja, backup neuspeh).

### 7.6 Troškovi i infrastruktura (sažeto)

- AWS EU region; jedan app server (ECS/Lightsail – proveriti), managed PostgreSQL sa šifrovanjem, privatni S3, SES, FCM/APNs, CloudWatch, statički hosting web-a iza istog origin-a kroz reverse proxy.
- **Bez Kubernetes-a, Kafke i Redis-a** dok ne postoji dokazana potreba (outbox i polling ih zamenjuju u pilotu).
- Ranija procena budžeta (76–194 USD produkcija + 25–60 USD staging mesečno) je **stara** i mora se ponovo izračunati u `docs/INFRASTRUCTURE.md`.

---

## 8. Opseg verzija: P0, P1, P2

### 8.1 P0 – Pilot

| Domen | Obuhvat u P0 | Napomena |
|---|---|---|
| Autentifikacija | pozivnice, verifikacija, login, refresh rotacija, sesije, logout (svi), reset, reautentifikacija, MFA (TOTP) za owner/platform/support, rate limiting, audit | web cookie + mobile bearer |
| Organizacija | organizacija + podešavanja, objekti, grupe | platformski admin kreira organizaciju |
| Zaposleni | pozivnice osoblja, profil, dodele grupama sa periodom | |
| Deca i roditelji | profil deteta, upisi (istorija), pozivnice staratelja, potvrda veza i ovlašćenja, ovlašćene osobe (lista) | bez JMBG-a |
| Zdravstveni profil | šifrovan, posebne dozvole, audit čitanja, indikator | samo online |
| Raspored | šablon sa effective date, izmene za dan, nedeljna atomska izmena, pregled, rok + kasna oznaka, neradni dani, dnevni plan i zamrzavanje | |
| Odsustva | SICK/VACATION/OTHER, period, preklapanje, otkazivanje, notifikacija | |
| Prisustvo | check-in/out, više poseta, neplanirano, korekcija sa razlogom, idempotencija, verzije, audit | |
| Dnevni pregled | brojači po formuli, lista, brze akcije, pending/konflikt oznake | |
| Obaveštenja | tekstualna, publika org/objekat/grupa/staratelj, draft/published/archived, snapshot + provera prava, čitanje | slike/prilozi P1 |
| Notifikacije | inbox, outbox, push (FCM/APNs), email (SES), polling + push invalidacija | |
| Pretplata | planovi, ručne pretplate, feature flagovi (plan/override/kill switch) | bez plaćanja |
| Privatnost | audit, privacy zahtevi (ručna obrada), izvoz, brisanje sa ledger-om, retencija (predlog) | |
| Offline | čitanje snapshot-a + red pending komandi prisustva, 12 h TTL, konflikti | |
| Web admin | Dashboard, Organizations/switcher, Locations, Groups, Children, Parents, Employees, Attendance, Schedules, Announcements, Settings, Billing (pregled), platformski ekrani | |
| Mobile | roditelj: deca, raspored, odsustva, prisustvo, obaveštenja, inbox, nalog; vaspitač: dnevni pregled, prisustvo, raspored grupe, odsustva, obaveštenja, inbox | KMP/Compose, Android + iOS |
| Lokalizacija | sr-Latn, sr-Cyrl, en | |
| Seed | Happy Kids (Novi Sad, Bubamare + Leptirići, 3 vaspitača, 15 dece, ~12 roditelja, owner + admin) + drugi tenant; sintetička imena `example.test`; odbija produkciju | bez lažnih kredencijala dok auth nije implementiran |

### 8.2 P1 – Proširenje

| Domen | Obuhvat |
|---|---|
| Kalendar | događaji (svi tipovi), opseg org/objekat/grupa, veza sa neradnim danima, zahtev saglasnosti za izlet |
| Jelovnik | dnevni/nedeljni, četiri obroka, alergenske oznake, objava |
| Poruke | PARENT_TEACHER i PARENT_ADMIN, serverski učesnici, nepročitano, idempotentno slanje |
| Fotografije i saglasnosti | privatni fajlovi (quarantine, scan, varijante), publika, označavanje dece, politike i odluke, povlačenje |
| Obaveštenja | slike i prilozi, osvežavanje primalaca, vaspitač objavljuje za svoju grupu |
| Izveštaji | prisustvo po periodu/grupi, odsustva, čitanost obaveštenja; CSV/PDF izvoz uz `REPORT_EXPORT` |
| Raspored | admin unosi umesto roditelja, opcija odbijanja kasnih izmena |
| Notifikacije | podešavanja po korisniku, email sažetak, SSE umesto pollinga (po potrebi) |
| Lokalizacija | pretraga neosetljiva na pismo; dvojno ime (opciono) |
| Mobile | certificate pinning, Coil za slike |

### 8.3 P2 – Kasnije

| Domen | Obuhvat |
|---|---|
| Plaćanja | provider adapter (Stripe/lokalni), fakture, PDV |
| Dokumenti | verzije, zahtevi, click-through odgovori sa dokazima |
| QR/NFC/kiosk | prijava/odjava na ulazu kroz isti domen prisustva; digitalna verifikacija preuzimanja |
| Upisni proces | lista čekanja, prijava, ugovori |
| Marketplace | dodaci, partneri |
| NANNY/BABYSITTER | novi model opsega (deca + vremenski prozori) |
| Ankete | glasanja i ankete za roditelje |
| Razvojni izveštaji | pedagoške beleške i izveštaji po detetu |
| Lekovi | evidencija davanja lekova (dodatne zdravstvene dozvole) |
| AI / prevođenje | pomoć u pisanju obaveštenja, prevod poruka – uz posebnu analizu privatnosti |
| Društvena prijava | Google/Apple/telefon |
| Dodatni jezici | hr, bs, sl, mk, de, sv |
| Prepoznavanje lica | **nije planirano bez posebne analize** |

---

## 9. Otvorena pitanja za vlasnika proizvoda

Samo pitanja čiji odgovor bitno menja proizvod, trošak, bezbednost ili opseg. Sve ostale odluke su donete stručno i
dokumentovane kao pretpostavke (ADR ili napomene iznad).

| # | Pitanje | Zašto je važno | Radna pretpostavka dok nema odgovora |
|---|---|---|---|
| 9.1 | Da li roditelj dobija push „dete je stiglo / otišlo” pri svakom check-in/out-u, ili samo na zahtev? | volumen push-a i troškovi; privatnost (svaki vaspitačev klik generiše poruku) | podešavanje po organizaciji, podrazumevano **isključeno** u pilotu |
| 9.2 | Da li vrtići u Srbiji zahtevaju JMBG deteta/roditelja za svoje zakonske evidencije, i da li bi to platforma morala da čuva? | značajno povećava rizik i obaveze (šifrovanje, pristup, retencija) | **ne čuva se**; izvozi imaju prazno polje koje vrtić popunjava van sistema |
| 9.3 | Ko u pilotu kreira organizaciju: samo platformski admin (ručno), ili self-service registracija vlasnika? | onboarding tok, anti-abuse mere, verifikacija pravnog lica | samo platformski admin |
| 9.4 | Da li support opseg ikada sme da uključi zdravstvene profile (npr. za otklanjanje greške u šifrovanju)? | bezbednosni model i audit; možda zahteva odvojeni „break-glass” postupak | **ne**; problemi se rešavaju bez dešifrovanja (metapodaci, verzije) |
| 9.5 | Da li vrtić plaća pretplatu po organizaciji bez obzira na broj objekata/dece, ili po detetu / po objektu? | dizajn `plans.limits`, izveštaji, budući billing provider | po organizaciji sa limitima u planu |
| 9.6 | Referentna tačka roka za izmenu rasporeda: „N sati pre početka radnog dana (`day_opens_at`)” ili „N sati pre očekivanog dolaska deteta”? | direktno utiče na to šta se označava kao kasno i kako vaspitači planiraju | pre `day_opens_at` datuma na koji se izmena odnosi |
| 9.7 | Sme li vaspitač da menja raspored deteta (npr. roditelj usmeno javi na vratima) ili to radi samo administracija? | opseg dozvola vaspitača; audit „ko je izmenio” | vaspitač **ne** menja; admin da (P1) |
| 9.8 | Šta raditi sa detetom koje ostane `CHECKED_IN` posle zatvaranja vrtića (zaboravljen check-out)? Automatski check-out u `day_closes_at` (označen kao SYSTEM), ili obavezna ručna korekcija sledećeg dana? | tačnost izveštaja o boravku; eventualna naplata po satima | bez auto check-out-a; podsetnik vaspitaču i adminu, ručno zatvaranje kao korekcija |
| 9.9 | Da li „kasno stiglo” (došlo posle tolerancije) treba da bude poseban brojač u dnevnom pregledu ili samo oznaka na stavci? | UI prostor i definicija brojača koja mora biti ista na svim klijentima | oznaka na stavci; brojač Late samo za decu koja još nisu stigla |
| 9.10 | Kada vaspitač napusti grupu (ili roditelju prestane pravo), da li zadržava pristup istoriji razgovora do tog datuma? | privatnost vs. kontinuitet; složenost pravila pristupa porukama | gubi pristup celom razgovoru; istoriju čuva vrtić |
| 9.11 | Kada dva staratelja daju suprotne odluke o saglasnosti za fotografije, važi li restriktivnija (DECLINED) ili odluka primarnog staratelja? | pravni rizik; složenost UI-ja | restriktivnija |
| 9.12 | Da li vrtići žele da unose ime deteta i na latinici i na ćirilici (dva polja), s obzirom na to da nema automatske transliteracije? | model podataka, izveštaji, pretraga | jedno polje u P0; dvojno polje kao P1 opcija |
| 9.13 | Koji su zakonski rokovi čuvanja evidencije prisustva i zdravstvenih podataka za privatne vrtiće u Srbiji (potvrda pravnika)? | retencija, brisanje, troškovi skladištenja | predlozi iz 5.18 dok se ne potvrde |
| 9.14 | Da li je u pilotu potreban iOS build od prvog dana (macOS CI runner ili ručni build), ili Android tablet za vaspitače + iOS za roditelje kasnije? | trošak CI-ja i vreme do pilota | Android + web u prvom pilot koraku; iOS odmah za roditelje ako vrtić to traži |
| 9.15 | Da li se prihvata ograničenje da opoziv prava na uređaju koji je offline stupa na snagu tek pri sledećem povezivanju (do 12 h TTL)? | bezbednosni rizik vs. upotrebljivost offline režima | prihvata se uz TTL 12 h i šifrovanu bazu |
