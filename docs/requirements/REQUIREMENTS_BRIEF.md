# Zahtevi proizvoda – izvorni brief (Vrtić Connect / kindergarten-platform)

> Ovaj dokument je verbatim zapis zahteva koje je vlasnik proizvoda dostavio 16. 9. 2026.
> On je **izvor istine za zahteve**. Sva ostala dokumentacija (`PRODUCT_SPEC.md`, `ARCHITECTURE.md`, ...)
> izvedena je iz njega i ne sme mu protivrečiti bez zabeležene odluke u `docs/adr/`.
> Kod, nazivi modula, API identifikatori i nazivi tabela su na engleskom; dokumentacija na srpskom (latinica).

---

## 1. Cilj i način rada

Prvo precizna specifikacija i arhitektura, a zatim početni skeleton projekta koji može lokalno da se pokrene. Ne implementirati odmah ceo proizvod.

Redosled rada:

1. Ako postoji repository ili ZIP, prvo pregledati postojeće fajlove i dokumentaciju.
2. Proveriti usklađenost specifikacije, SQL šeme, API ugovora i skeleton-a.
3. Pronaći nedostatke, kontradikcije i sigurnosne probleme.
4. Dopuniti ili popraviti dokumentaciju.
5. Proveriti i osposobiti lokalni skeleton.
6. Pripremiti konkretne razvojne taskove.
7. Poslovne funkcionalnosti implementirati tek u okviru zadatog EPIC-a.

Ako repository nije dostavljen, napraviti dokumentaciju i skeleton na osnovu brief-a i ne tvrditi da su postojeći fajlovi pregledani. Rutinske tehničke odluke donositi stručno i dokumentovati pretpostavke; pitati samo kada odgovor bitno menja proizvod, trošak, bezbednost ili opseg.

## 2. Postojeći kontekst projekta

Ranije planiranje (nije dostavljeno u ovom okruženju) obuhvatalo je: deset Markdown dokumenata, SQL predlog (~67 tabela), OpenAPI (~131 operacija), osam ADR-ova, roadmap sa 18 EPIC-a, Ktor backend skeleton, PostgreSQL foundation migraciju, Docker Compose, KMP/Compose mobilni skeleton, React/Vite admin skeleton, osnovni CI i sintetičke seed podatke. Broj tabela i endpointa nije cilj ni ograničenje.

Prethodno prijavljene provere (ne predstavljati kao sopstvene): web TypeScript/build prošli; OpenAPI i SQL parsing prošli; SQL šema, seed i negativni tenant testovi izvršeni na PostgreSQL WASM runtime-u; backend Gradle build, Docker Compose, native PostgreSQL 17, Android i iOS buildovi nisu bili izvršeni.

Autentifikacija u skeleton-u nije implementirana. Login i refresh namerno ne izdaju tokene. Ne zamenjivati lažnom prijavom ili prečicom koja preskače dozvole.

## 3. Osnovna ideja proizvoda

Aplikacija povezuje roditelje/staratelje, decu, vaspitače, administraciju vrtića, vlasnike/direktore i administratora SaaS platforme.

Struktura: Organization / Kindergarten → Locations → Groups → Children (kroz upise). Vrtić ima više objekata, objekat više grupa, dete u datom periodu pripada jednoj grupi. Dete može imati više staratelja; roditelj više dece. Jedan nalog može biti roditelj u jednom vrtiću, roditelj u drugom, zaposleni u trećem – role pripadaju **članstvu u organizaciji**, ne globalnom polju na korisniku. Dete nema nalog. Ne povezivati automatski profile dece između nezavisnih vrtića.

## 4. Platforme i tehnološke odluke

- **Mobile:** Kotlin Multiplatform + Compose Multiplatform, Android i iOS. Maksimalno deliti business logic, networking, DTO/serialization, auth koordinaciju, state, validaciju, navigaciju gde je praktično, UI. Platformski: Keychain/Keystore, push, sistemske dozvole, kamera/foto, lifecycle, crash reporting. Ne tvrditi da se deli sav kod.
- **Web admin:** React + TypeScript + Vite, statički SPA na istom origin-u sa API-jem preko reverse proxy-ja. Next.js ostaje opcija za budući javni sajt. Bez drugog backend-a i dupliranja poslovne logike.
- **Backend:** Kotlin + Ktor, modularni monolit, JDBC + HikariCP, Flyway, eksplicitni SQL repository-ji. Uporediti sa Spring Boot-om i objasniti kada bi Spring bio bolji.
- **Database:** PostgreSQL. **Storage:** privatni object storage (AWS S3). **Repo:** monorepo, odvojeni Gradle build-ovi za backend i mobile.
- Proveriti aktuelne kompatibilne verzije; ne birati verzije samo zato što su najnovije.

## 5. Multi-tenancy i izolacija

Jedna PostgreSQL baza, shared schema, `organization_id` na tenant tabelama, kompozitni FK sa `organization_id`, RLS sa ENABLE i FORCE, runtime DB korisnik nije vlasnik/superuser/BYPASSRLS, odvojeni migration credentials. Tenant kontekst transaction-local (`set_config(..., true)`) tek posle provere sesije i članstva. Bez konteksta pristup mora biti odbijen. Testirati ponovnu upotrebu pooled konekcija posle COMMIT/ROLLBACK. RLS štiti granicu vrtića; aplikativni policy proverava grupu, dete i osetljiva polja. Globalne tabele (users, sessions) imaju zaseban pristupni model; bez globalne liste korisnika za tenant korisnike.

## 6. Role i dozvole

- **SUPER_ADMIN:** vrtići, planovi, pretplate, agregirana statistika, feature flagovi, sanitizovani logovi. Bez automatskog pristupa podacima dece. Support pristup: razlog/ticket, opseg, odobrenje vlasnika ili hitni postupak, kratak rok, MFA, audit, read-only kad je dovoljno.
- **KINDERGARTEN_OWNER:** svoj vrtić, objekti, grupe, zaposleni, deca i veze, pretplata, statistika. Vlasništvo ne daje neograničen pristup zdravstvenim podacima.
- **ADMIN:** administracija vrtića; ne može dodeljivati SUPER_ADMIN/OWNER prava mimo posebnog postupka.
- **TEACHER:** samo dodeljene grupe u periodu važenja dodele; prisustvo, raspored, funkcije u svom opsegu.
- **PARENT:** samo svoja deca preko potvrđenih guardian veza; raspored, odsustvo, saglasnosti uz ovlašćenje. Ne može sam sebi povezati dete niti potvrditi prava drugog staratelja.
- **Buduće:** NANNY/BABYSITTER nisu MVP; zahtevaju novi permission/scope model.

## 7. Authentication

Email+password, verifikacija emaila, reset, refresh rotation, logout uređaja / svih uređaja, lista sesija, opoziv sesije, reauthentication za osetljive akcije, MFA za platform admina, vlasnika i support.

Opaque session strategija: kratkotrajni access token, kriptografski nasumični tokeni, samo hash u bazi, rotirajući refresh, atomic consume+rotate, detekcija ponovne upotrebe → opoziv session family, online provera opoziva.

Početne vrednosti: access 10 min; refresh absolute 30 dana; refresh idle 7 dana; reset link 15 min; verification link 24 h.

Web: HttpOnly/Secure/SameSite cookies, CSRF + exact Origin, bez tokena u localStorage. Mobile: Keychain/Keystore, single-flight refresh, bez tokena u preferences/logovima. Passwords: Argon2id, parametri po benchmark-u, unique salt, generičke poruke, rate limiting. Google/Apple/phone login kasnije.

## 8. Profil deteta

Ime, prezime, datum rođenja, opciona fotografija, status, trenutni upis, istorija upisa, staratelji, ovlašćene osobe za preuzimanje, alergije, napomene, posebne potrebe. Zdravstveni podaci: odvojena tabela/DTO, dodatne dozvole, šifrovanje, audit čitanja/izmene, ne u listi dece, ne u push porukama. Bez JMBG/fotografija dokumenata bez obrazložene potrebe. Lista ovlašćenih za preuzimanje je P0; digitalna verifikacija preuzimanja kasnije.

## 9. Schedule

Dolazak/odlazak za dan, nedelja, ponavljajući nedeljni raspored, dan bez dolaska. Effective date za template, day overrides, pregled pre primene, atomski weekly update, optimistic concurrency, jasni konflikti.

Prioritet: 1) neradni dan, 2) aktivno odsustvo, 3) izmena za dan, 4) ponavljajući raspored, 5) nema očekivanog dolaska. Bez retroaktivne izmene istorije. Rok za promenu je podešavanje vrtića; kasna promena označena; odsustvo uvek odmah.

## 10. Attendance

Odvojiti plan od stvarnog prisustva: expected arrival/departure, actual check-in/out, absent/sick/vacation kao kontekst, više poseta dnevno, korekcija sa razlogom, audit. NOT_ARRIVED → CHECKED_IN → CHECKED_OUT; ponovni check-in otvara novu posetu; check-out bez otvorene posete ne prolazi. Immutable events, projekcija stanja, version, idempotency key, row locking. QR/NFC/kiosk kasnije preko istog domena.

## 11. Daily overview za vaspitača

Expected, Present, Departed, Absent, Not arrived, Late, Unscheduled present. Formula: Expected = Present + Departed + Absent + NotArrived. Neplanirano prisutna deca zasebno, uključena u fizički prisutne. Lista: ime, očekivano vreme, status, brza akcija, pending sync, konflikt. Velike tap površine. Odsustvo za prisutno dete ne radi automatski check-out.

## 12. Absence

SICK/VACATION/OTHER; jedan dan ili period; preklapanje; otkazivanje; vidljivo vaspitaču; audit i notification event; bez obaveznog medicinskog objašnjenja.

## 13. Announcements

Publika: vrtić, objekat, grupa, staratelj. Title, text, images, attachments, publish/expiration date, draft/published/archived. Praćenje ko/kada je pročitao; push nije dokaz čitanja. Snapshot publike pri objavi + provera trenutnih prava pri pristupu.

## 14. Notifications i realtime

Generički sistem; in-app inbox; FCM/APNs; transactional outbox; retry/backoff; dedup; dead-letter; uklanjanje nevažećih tokena. Push payload minimalan/opaque. Push nije garantovan kanal. Realtime: polling + push invalidacija za pilot; SSE kasnije; REST za komande; bez WebSocket-a bez potrebe; bez tokena u URL; reconnect resync; poštovanje revoke-a.

## 15. Messaging

Parent↔Teacher, Parent↔Administration. Conversations, server-managed participants, text, timestamps, unread, last-read, idempotentno slanje. Bez WhatsApp-a u MVP-u. Pristup zavisi od deteta, učešća i ovlašćenja.

## 16. Calendar i Meals

Događaji: izlet, fotografisanje, predstava, praznik, sastanak, neradni dan; all-day i timed. Obroci: breakfast, snack AM, lunch, snack PM (različiti identifikatori); dnevni/nedeljni meni; nutritivno kasnije; nije medicinska preporuka.

## 17. Photos i privatni fajlovi

Bez public URL-a. Private storage, upload authorization, quarantine, MIME provera, limiti, kompresija, thumbnails, EXIF uklanjanje, malware scan, kratkotrajni signed URL ili proxy. Fajlovi van baze. Audience: grupa/dete/staratelji. Audience ≠ consent. Grupna fotografija: evidentirana prepoznatljiva deca, važeće saglasnosti, bez face recognition, povlačenje blokira novu distribuciju, thumbnails iste dozvole. Consent: policy version, wording hash, purpose, guardian, decision, timestamp, withdrawal. Ograničenje: preuzeta fotografija se ne može povući. Bez offline galerije.

## 18. Documents i buduće saglasnosti

Document version, content hash, request, guardian response, accept/decline, timestamp, evidence. Nije kvalifikovani e-potpis. Nije u početnoj implementaciji.

## 19. Billing i feature flags

Roditelji besplatno; vrtić plaća pretplatu na organization. STARTER/STANDARD/PRO. Bez payment providera. Trial/active/past due/cancelled, billing period, plan version, currency, minor units, entitlements, provider adapter. Feature flags: photos/messaging/meals/payments. Razdvojiti plan entitlement, tenant override, kill switch, korisničke dozvole. Flag nije authorization. Downgrade/kašnjenje ne briše podatke.

## 20–21. Database i REST API deliverable

Kompletan PostgreSQL schema proposal sa svim ograničenjima, indeksima, lifecycle-om; nepreklapajući periodi; DB vs application garancije; ER dijagrami; proposal odvojen od Flyway migracija.

API `/api/v1`, tenant scope `/api/v1/organizations/{organizationId}/...`; global auth i platform admin odvojeno. Za svaki endpoint: method/path, namena, request/response, status codes, permissions, validacija, pagination, idempotency, versioning, prioritet. OpenAPI, problem+json, cursor pagination, allowlist filtera, 401/403/404 bez otkrivanja, 409, 422, 429+Retry-After, If-Match.

## 22. Mobile arhitektura i offline

Ktor Client, kotlinx.serialization, coroutines/StateFlow, constructor injection, SQLDelight kada dođe offline, secure storage, shared localization, typed navigation, Coil kasnije, CrashReporter adapter. Clean Architecture samo gde vredi.

Offline P0: lista dece, dnevni raspored, dnevna prisutnost, red pending komandi. Algoritam sa 10 koraka (snapshot, šifrovana baza po user/tenant, 12 h važenja, command UUID/expectedVersion/occurredAt, pending oznaka, reconnect proverava prava, idempotentno slanje, potvrda/odbijanje/konflikt, uklanjanje tek posle potvrde, tombstones). Bez last-write-wins. Bez health/fotografija offline. Opoziv na offline uređaju nije garantovan.

## 23. Web admin

Ekrani: Dashboard, Organizations/tenant switcher, Locations, Groups, Children, Parents, Employees, Attendance, Schedules, Announcements, Calendar, Meals, Photos, Reports, Settings, Billing. Ista definicija brojača kao backend. TS strict, accessibility, validacija, stanja, konflikti, cache po user+tenant, čišćenje pri promeni tenant-a, bez HTML injection.

## 24. Lokalizacija i datum/vreme

sr-Latn, sr-Cyrl, en od početka; kasnije hr, bs, sl, mk, de, sv. Bez hardcode teksta; bez auto-transliteracije imena. UTC instant za događaje, date za rođenje, local date/time + IANA tz za raspored, org tz (Europe/Belgrade), prisustvo po tz vrtića, DST, promena tz ne menja istoriju.

## 25. Security, privatnost i audit

TLS, encryption at rest, envelope encryption, secrets, least privilege, rate limiting, revocation, tenant/child authorization, privatni storage, backup, DR, sanitizovani logovi, scanning. Nikad ne logovati lozinke, tokene, signed URL-ove, medicinske podatke, poruke, nepotrebne ID-jeve dece. Audit polja i lista auditovanih akcija. Append-only ne štiti od DBA. GDPR/Srbija tehničke mere; rokovi čuvanja su predlog.

## 26. Infrastruktura i troškovi

DEV/STAGING/PRODUCTION; 10–50 vrtića, kasnije 500+. AWS EU, app server, managed encrypted PostgreSQL, private S3, SES, FCM/APNs, monitoring, static hosting/proxy, CDN gde ima smisla. Proveriti Lightsail. Stari budžet 76–194 USD + 25–60 USD staging je stara procena. Bez Kubernetes/Kafka/Redis bez potrebe. Ciljevi: SLO 99,5 %, RPO 15 min, RTO 4 h – ciljevi za dokazivanje.

## 27. CI/CD, monitoring i testovi

CI: backend build/tests, migration validation, native PG integration, RLS negative, OpenAPI, web typecheck/build, mobile shared tests, Android, iOS (macOS runner ili dokumentovan gate), dependency/secret scan. Deployment: immutable image, jedan migration job, staging pre production, smoke, expand/contract, rollback bez slepog vraćanja migracija, bez secrets u repo. Monitoring lista. Obavezni test scenariji (dva tenant-a, nepovezana deca, multi-tenant korisnik, opoziv, teacher period, refresh replay, konkurentne attendance komande, offline konflikt, photo audience/consent, pool leakage).

## 28. Seed podaci

Happy Kids, Novi Sad, Bubamare i Leptirići, 3 vaspitača, 15 dece, ~12 roditelja, owner i admin, deca sa dva staratelja, roditelji sa više dece, drugi tenant. Sintetička imena, example.test. Seed odbija production. Bez lažnih kredencijala dok auth nije implementiran.

## 29. Prioriteti i roadmap

P0 pilot; P1 proširenje (calendar, meals, messaging, photos, reports); P2 (payments, documents, QR/NFC, enrollment, marketplace, nannies, surveys, development reports, medication, AI, translation). Bez face recognition bez analize.

EPIC-i 01–18: Foundation, Authentication, Organizations, Employees, Children & Parents, Groups, Schedule, Attendance, Absence, Announcements, Notifications, Calendar, Meals, Messaging, Photos, Admin Dashboard, Audit & Security, Production Deployment. Za svaki: cilj, prioritet, dependencies, backend/mobile/web/database tasks, acceptance criteria, testovi, DoD.

## 30. Očekivani fajlovi

PRODUCT_SPEC, ARCHITECTURE, DATABASE, API, SECURITY, INFRASTRUCTURE, MOBILE_ARCHITECTURE, WEB_ARCHITECTURE, DEVELOPMENT_ROADMAP, README; docs/openapi, docs/database/schema.sql, dijagrami, docs/adr/, seed, validation report; struktura backend/, apps/mobile/, apps/admin-web/, infrastructure/, docs/, scripts/, docker-compose.yml, .env.example, CI. Skeleton: Ktor bootstrap, PostgreSQL, Flyway, owner/runtime credentials, health/live i ready, auth interfejsi sa bezbedno zatvorenim rutama, KMP shared UI + hostovi, React shell, tri jezika, Docker Compose, CI, uputstvo.

## 31. Završni kriterijumi

Ne tvrditi „production-ready”. Na kraju: šta je projektovano, šta implementirano, koje komande/testovi izvršeni, šta prošlo, šta nije provereno i zašto, poznati problemi, prvi sledeći task. Ne izmišljati prolazne rezultate. Sledeći veliki korak: EPIC 02 – autentifikacija, sesije i dozvole.
