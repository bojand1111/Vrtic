# Infrastruktura, isporuka i troškovi – Vrtić Connect

> Status: projektni dokument. Izveden iz `docs/requirements/REQUIREMENTS_BRIEF.md` (odeljci 14, 25,
> 26, 27) i modela u `docs/database/schema.sql`. SLO/RPO/RTO su **ciljevi koje treba dokazati**, ne
> osobine skeletona. Ništa ovde ne tvrdi da je infrastruktura podignuta, testirana ili da su cene
> obračunate na stvarnom računu. Cene: odeljak 13 sa datumom provere i statusom svake stavke.

---

## 1. Kontekst i fiksne odluke

- Region: **AWS EU, `eu-central-1` (Frankfurt)** – pretpostavka radi blizine Srbiji i GDPR-a; promena
  regiona ne menja topologiju.
- Backend: Kotlin 2.3.21 / Ktor 3.5.2 / JDK 21 / Gradle 9.3.0, JDBC + HikariCP 7.1, Flyway 13.7,
  modularni monolit, **jedan Docker image** sa dve komande: `migrate` (Flyway, `app_owner`) i `serve`
  (API, `app_runtime`; outbox relay unutar istog procesa kao `app_worker` konekcija).
- Baza: PostgreSQL 17, RLS ENABLE+FORCE, tri role (`app_owner` / `app_runtime` / `app_worker`,
  `scripts/db/00_roles.sql`).
- Web: statički SPA na istom origin-u kao API, iza nginx-a.
- **Bez Kubernetes-a, Kafke i Redis-a** dok se ne dokaže potreba. Pilot = 10–50 vrtića; putanja do 500+.

Dimenzionisanje pilota (pretpostavke za sve procene): 50 vrtića × ~60 dece × ~1,5 staratelja ≈
4.500 roditelja + ~400 zaposlenih; vršno opterećenje 7–9 h ujutru (check-in) ≈ 5–20 zahteva/s;
baza < 5 GB u prvoj godini; fotografije (P1) ~ 20–50 GB/god.

---

## 2. Okruženja

| Okruženje | Namena | Gde | Podaci |
|---|---|---|---|
| **DEV** | lokalni razvoj | `docker-compose.yml` (PostgreSQL 17.11 + migrate + api) | sintetički seed (`example.test`); seed odbija `APP_ENV=production` |
| **STAGING** | integracija, smoke, restore drill, testiranje mobilnih build-ova | AWS, isti raspored kao produkcija ali manje instance; poseban AWS nalog | sintetički seed ili anonimizovana kopija; **nikad** produkcijski lični podaci |
| **PRODUCTION** | pilot i dalje | AWS, poseban AWS nalog | stvarni podaci |

- Dva (idealno tri) AWS naloga u jednoj AWS Organization: `vc-staging`, `vc-prod` (+ `vc-shared`
  za CI role/ECR kad zatreba). Odvajanje naloga je najjeftinija tvrda granica (IAM, budžet, blast radius).
- Konfiguracija: identična imena env varijabli kao u `.env.example` (`APP_ENV`, `DB_HOST`,
  `APP_DB_*_PASSWORD`, `APP_WEB_ORIGIN`, `ARGON2_*`); vrednosti dolaze iz Secrets Manager-a u
  runtime-u (odeljak 8), nikad iz repoa.

---

## 3. Compute – opcije i preporuka

| Opcija | Za | Protiv | Procena mesečno (pilot) |
|---|---|---|---|
| **Lightsail instance** (Linux, 2–4 GB) | fiksna cena, uključen transfer, jednostavno | van VPC-a (peering moguć, ali dodatni sloj), slabija integracija sa IAM/Secrets/CloudWatch, nema auto-scaling, snapshot-i ručni | 12–24 USD (provereno) |
| **EC2** (t4g.small/medium, Graviton) | pun VPC, IAM role, SSM Session Manager (bez SSH ključeva), EBS snapshot-i, Savings Plans | sami održavamo OS/patching, nginx i deploy skriptu | ~15–35 USD (nije provereno – procena za Frankfurt) |
| **ECS Fargate** | bez servera, immutable image nativno, lako 2 replike + ALB | ALB je fiksni trošak (~20 USD+), Fargate skuplji po vCPU od EC2, više delova (task def, ALB, target groups) | ~45–80 USD sa ALB-om (nije provereno – procena) |
| **App Runner** | najmanje operacija, auto-scaling, HTTPS bez ALB-a | VPC connector za privatni RDS dodaje složenost; cena provisioned memorije i po vCPU-satu; manje kontrole nad nginx/headerima; nema stabilan IP bez dodatka | ~30–60 USD (provereno po jedinici, ukupno procena) |

**Preporuka za pilot: jedna EC2 instanca (t4g.small ili t4g.medium, Arm64, Amazon Linux 2023)**
sa Docker-om, nginx-om i backend kontejnerom, u privatnom VPC-u sa RDS-om. Razlozi:

1. **Privatna mreža i IAM su prirodni** – RDS bez javnog pristupa, instanca dobija IAM rolu za S3/
   Secrets/KMS/CloudWatch (bez dugotrajnih ključeva), pristup preko SSM Session Manager-a.
2. **Immutable image i dalje važi** – instanca vuče konkretan tag iz ECR-a; ne gradi se ništa na serveru.
3. **Jeftinije od Fargate+ALB** i jednostavnije od App Runner-a za naš zahtev (isti origin, nginx,
   sopstveni headeri, SSE kasnije).
4. Lightsail ostaje legitiman za **staging** kad budžet preteže, ali zbog baze (odeljak 4) i IAM
   integracije ne preporučuje se ni tamo – jednostavnije je imati identičan raspored u oba naloga.

Putanja ka 500+ vrtića: (a) veća instanca (vertikalno), (b) druga instanca + ALB (horizontalno;
backend je stateless – sesije su u bazi, nema in-memory stanja osim keša rate-limita koji ima
DB fallback), (c) tek tada razmotriti ECS Fargate da se ukloni održavanje OS-a. Kubernetes se ne
planira.

Raspored na instanci:

```
nginx (443/80)  ──/api, /health──▶  vrtic-backend:<tag>  (127.0.0.1:8080)
                ──/──────────────▶  /var/www/admin-web  (statika iz CI artefakta)
docker compose (prod profil): api + jednokratni migrate job; logovi → CloudWatch agent
```

---

## 4. Baza podataka – Lightsail managed PostgreSQL vs RDS PostgreSQL 17

Zahtevi iz brief-a: managed, encryption at rest, envelope encryption za osetljiva polja (KMS),
PITR sa RPO ≤ 15 min, privatna mreža, PostgreSQL 17, odvojene role (`app_owner` / `app_runtime` /
`app_worker`, `NOBYPASSRLS`), ekstenzije `pgcrypto`, `citext`, `btree_gist`.

| Kriterijum | Lightsail managed DB | RDS PostgreSQL |
|---|---|---|
| Encryption at rest | Plan od 15 USD (1 GB) je **bez enkripcije**; od 30 USD naviše „encrypted” (provereno na cenovniku 16. 9. 2026) | Uvek dostupno, KMS ključ po izboru (AWS-managed ili CMK) |
| PITR | „point in time restore from the past 7 days” (provereno, Lightsail FAQ); granularnost nije navedena na stranici | Retencija 1–35 dana; transakcioni logovi se šalju u S3 **na svakih 5 minuta** (provereno, RDS User Guide) ⇒ RPO ≤ 15 min ostvariv |
| Privatna mreža | „public mode” može biti isključen, ali baza živi u Lightsail mreži; pristup iz VPC-a ide preko VPC peering-a (FAQ navodi peering za instance; za baze nije eksplicitno potvrđeno – nije provereno) | Nativno u privatnom subnetu VPC-a, security group, bez javnog IP-ja |
| Verzija PostgreSQL 17 | Dostupne verzije **nisu proverene** (stranica dokumentacije se nije učitala); istorijski Lightsail zaostaje za RDS-om po major verzijama | PostgreSQL 17.x dostupan (proverene minor verzije uključuju 17.11; PostgreSQL 18 takođe listiran) |
| Parametri, ekstenzije, role | Ograničena kontrola parametara; nema parameter group-a | Parameter group (npr. `log_min_duration_statement`, `rds.force_ssl`), ekstenzije `pgcrypto`, `citext`, `btree_gist` podržane, kreiranje role bez superuser-a preko `rds_superuser` master naloga |
| Multi-AZ | HA plan (dupla cena) | Multi-AZ instance ili Multi-AZ DB cluster |
| Monitoring | osnovne metrike | CloudWatch metrike, Enhanced Monitoring, Performance Insights (besplatni nivo 7 dana), logovi u CloudWatch Logs |
| Cena (najmanji razumni plan) | 30 USD/mes (2 GB, encrypted, provereno) | db.t4g.small Single-AZ + 20 GB gp3 ≈ 30–40 USD/mes (nije provereno – procena za Frankfurt) |

**Zaključak: Lightsail managed PostgreSQL NIJE adekvatan za produkciju ovog proizvoda.** Razlozi:
(1) najjeftiniji plan nema enkripciju, a viši planovi nemaju KMS ključ pod našom kontrolom;
(2) PITR je ograničen na 7 dana bez dokumentovane granularnosti, što ne daje dokaziv RPO ≤ 15 min i
onemogućava duže istrage incidenata; (3) baza nije u našem VPC-u, pa „bez javne baze” zavisi od
peering-a i Lightsail mrežnog modela; (4) dostupnost PostgreSQL 17 nije potvrđena; (5) nema
parameter group-a za `rds.force_ssl`, spore upite i logovanje. **Preporuka: Amazon RDS for
PostgreSQL 17** (`db.t4g.small` za pilot, gp3 20 GB sa autoscaling-om do 100 GB, Single-AZ na startu,
automatski backup retencija 14 dana, `rds.force_ssl=1`, CMK iz KMS-a, deletion protection uključen).
Multi-AZ se uključuje kada SLO dokaže potrebu (staging ostaje Single-AZ).

Napomena o rolama: RDS master nalog nije superuser; skript `scripts/db/00_roles.sql` se pokreće
kao master (ima `CREATEROLE`/`CREATEDB` kroz `rds_superuser`), a ekstenzije se kreiraju kao master
pre nego što `app_owner` pokrene Flyway (Compose već radi isto sa `postgres` korisnikom).

---

## 5. Object storage (S3)

- Bucket-i po okruženju: `vc-prod-files-eu-central-1`, `vc-staging-files-eu-central-1` (+ zaseban
  `vc-prod-logs` za pristupne logove). **Block Public Access = ON** na nalogu i bucket-u.
- Bucket policy: `Deny` svega što nije preko TLS-a (`aws:SecureTransport=false`), `Deny` upload-a bez
  `x-amz-server-side-encryption: aws:kms` sa našim ključem, pristup samo IAM roli backend instance
  (i CI roli za statiku admin-web ako se koristi S3 hosting).
- **SSE-KMS** sa CMK (`alias/vc-prod-files`); Bucket Keys uključeni (smanjuje KMS pozive).
- Objekti se nikad ne serviraju javno: backend izdaje **kratkotrajne presigned URL-ove** (≤ 5 min,
  vezane za konkretan ključ) ili proxy-je sadržaj; presigned URL-ovi se ne loguju.
- Struktura ključeva: `org/<organizationId>/files/<fileId>/<variant>` – tenant je prefiks, što
  omogućava IAM/policy uslove i brisanje po tenant-u; metapodaci su u `app.files` / `app.file_variants`.
- Upload: klijent traži autorizaciju, dobija presigned PUT ka `quarantine/` prefiksu; backend job
  (MIME provera, EXIF uklanjanje, kompresija, thumbnails, malware scan – P1) premešta u finalni
  prefiks. Ništa iz `quarantine/` nije dostupno za čitanje.
- Lifecycle pravila: `quarantine/` – istek 2 dana; nekompletni multipart upload-i – 1 dan;
  verzionisanje uključeno sa istekom starih verzija posle 30 dana (zaštita od slučajnog brisanja);
  eksport/privacy paketi (`exports/`) – 7 dana; prelazak na S3 Standard-IA posle 90 dana za
  originale fotografija (thumbnails ostaju Standard).
- Brisanje po zahtevu (privacy request) briše objekte i verzije i upisuje `app.erasure_ledger`.

---

## 6. E-mail (SES), push (FCM/APNs)

- **SES** u `eu-central-1`: verifikovan domen (DKIM, SPF, DMARC `p=quarantine` pa `reject`),
  konfiguracioni set sa event destination-om u CloudWatch/SNS za bounce/complaint; backend obrađuje
  bounce-ove i suspenduje slanje na tu adresu. Sandbox režim se ukida zahtevom AWS-u pre pilota.
  Sadržaj mejlova: verifikacija, reset lozinke, pozivnice, sažeci; nikad zdravstveni podaci ni
  sadržaj poruka.
- **FCM**: Firebase projekat po okruženju (staging/prod); service account JSON u Secrets Manager-u,
  backend šalje preko HTTP v1 API-ja.
- **APNs**: token-based auth (`.p8` ključ, Key ID, Team ID) u Secrets Manager-u; bundle id po
  okruženju. Sandbox vs production APNs endpoint prema build-u.
- Isporuka ide isključivo kroz transactional outbox (`app.outbox_events` → `app.notification_deliveries`)
  sa retry/backoff-om, dead-letter statusom i uklanjanjem nevažećih tokena (`app.device_push_tokens`).

---

## 7. Mreža

```
VPC 10.20.0.0/16 (eu-central-1)
├── public subnets  (a, b): 10.20.0.0/24, 10.20.1.0/24   – EC2 app (Elastic IP), NAT nije potreban dok je app u public subnetu
├── private subnets (a, b): 10.20.10.0/24, 10.20.11.0/24 – RDS (DB subnet group), bez rute ka internetu
├── Security groups
│   ├── sg-app : ingress 443/80 iz 0.0.0.0/0; egress 5432 → sg-db, 443 → internet (S3/SES/KMS/FCM/APNs)
│   └── sg-db  : ingress 5432 SAMO iz sg-app; egress none
├── VPC endpoints (gateway): S3 – besplatan, drži S3 saobraćaj van interneta
└── SSM Session Manager za pristup instanci (bez otvorenog porta 22, bez SSH ključeva)
```

- **Baza nikada nema javni IP** (`PubliclyAccessible=false`); pristup za administraciju ide kroz
  SSM port-forward preko app instance ili bastion-a koji se gasi kad se ne koristi.
- Kad se pređe na dve app instance + ALB, app instance sele u privatne subnete, a ALB u javne
  (tada je potreban NAT gateway ili interface endpointi – dodatni trošak, planiran u fazi 500+).
- TLS: sertifikat od Let's Encrypt (certbot na instanci) ili ACM ako ispred stoji ALB/CloudFront.
  Samo TLS 1.2+; HSTS (vidi WEB_ARCHITECTURE.md §15).
- DNS: Route 53 hosted zone; `api.`/`admin.` zapisi ka Elastic IP-ju (kasnije ka ALB/CloudFront).

---

## 8. Tajne i ključevi

- **AWS Secrets Manager** za: DB lozinke (`app_owner`, `app_runtime`, `app_worker`), APNs ključ, FCM
  service account, SES SMTP (ako se koristi), pepper za hash tokena. Rotacija DB lozinki: ručna
  procedura kvartalno u P0 (automatska rotacija RDS lozinke preko Lambda-e kasnije).
- **SSM Parameter Store (Standard, besplatno)** za ne-tajnu konfiguraciju (`APP_WEB_ORIGIN`,
  `APP_ENV`, feature flag default-i). Razlog za podelu: Secrets Manager ima cenu po tajni, Parameter
  Store Standard ne; tajne ostaju tamo gde je audit i rotacija.
- Instanca čita tajne pri startu preko IAM role (`secretsmanager:GetSecretValue` ograničen na
  tačne ARN-ove) i prosleđuje ih kontejneru kao env – nikad u image-u, nikad u repou, nikad u
  CloudWatch logovima.
- **KMS**: jedan CMK za RDS, jedan za S3, jedan za **envelope enkripciju aplikacije**
  (`app.child_health_profiles` payload, eventualno druga osetljiva polja): backend generiše data key
  (`GenerateDataKey`), šifruje payload lokalno (AES-256-GCM), čuva šifrovani data key uz red;
  dešifrovanje = `Decrypt` data key-ja + lokalno. Rotacija CMK-a godišnje (automatska); key policy
  dozvoljava upotrebu samo app roli i dekript samo iz našeg VPC-a (`aws:SourceVpc` uslov).
- Ključevi mobilnih app store-ova i CI potpisi žive u GitHub Environments secrets, ne u AWS-u.

---

## 9. Reverse proxy i statički hosting

- Pilot: **nginx na instanci** servira statiku admin-web-a i proxy-ra `/api`, `/health` na backend.
  Isti origin ⇒ kolačići i CSRF rade bez CORS-a. Konfiguracija u `infrastructure/nginx/`.
- CDN: **ne** u pilotu (sav saobraćaj je iz Srbije/regiona, statika je < 2 MB, TLS terminacija na
  instanci je dovoljna). CloudFront + S3 za statiku i CloudFront ispred API-ja uvode se kada (a)
  bude potrebno više app instanci, (b) kad fotografije (P1) budu značajan egress, ili (c) za WAF.
  CloudFront always-free nivo (1 TB/mes, 10 M zahteva – provereno) čini to jeftinim kad zatreba.
- Rate limiting: prvi sloj u nginx-u (`limit_req` po IP-ju za `/api/v1/auth/*`), drugi sloj u
  backend-u (po korisniku/IP-ju, `app.login_attempts`). WAF nije u pilotu.

---

## 10. Monitoring i alarmi (bez preklapanja alata)

Jedan alat: **CloudWatch** (metrike, logovi, alarmi, Synthetics za uptime). Bez Grafane/Datadog-a
u pilotu. Backend eksportuje metrike kroz **CloudWatch Embedded Metric Format** u logove
(bez agent-a za metrike) – EMF je jeftiniji od `PutMetricData` po zahtevu i drži sve u logovima.

| Šta | Izvor | Alarm (predlog praga) |
|---|---|---|
| Dostupnost (uptime) | CloudWatch Synthetics canary `GET /health/ready` svakih 5 min iz 2 regiona | 2 uzastopna neuspeha → SNS (e-mail + push na dežurnog) |
| Stopa grešaka 5xx | nginx access log → metric filter | > 2 % u 5 min |
| Latencija p95 `/api` | backend EMF metrika `http.latency` | > 800 ms 10 min |
| DB konekcije | RDS `DatabaseConnections` + HikariCP `pool.pending` (EMF) | > 80 % pool-a ili pending > 0 tokom 5 min |
| Spori upiti | RDS parameter `log_min_duration_statement=500ms` → CloudWatch Logs metric filter | > 20/5 min |
| CPU / storage baze | RDS `CPUUtilization`, `FreeStorageSpace` | CPU > 80 % 15 min; storage < 20 % |
| **Outbox backlog** | backend EMF `outbox.pending_age_seconds` (najstariji PENDING), `outbox.dead_count` | age > 300 s; dead > 0 |
| **Neuspele isporuke** | `notification_deliveries` FAILED/h, SES bounce rate | bounce > 5 %; FAILED > 50/h |
| Refresh reuse detekcije | EMF `auth.refresh_reuse_detected` | > 0 (informativno, sigurnosni signal) |
| Backup uspeh | RDS event subscription (`backup` kategorija) + dnevni check `LatestRestorableTime` | age > 30 min → alarm (RPO signal) |
| Restore drill | ručni/mesečni job upisuje metriku `dr.restore_drill_success` | nema uspeha u 35 dana |
| Disk/memorija instance | CloudWatch agent | disk > 80 %, mem > 85 % |
| Budžet | AWS Budgets | > 80 % mesečnog budžeta |

Logovi: nginx access/error, backend JSON logovi (sanitizovani – bez lozinki, tokena, signed
URL-ova, zdravstvenih podataka, sadržaja poruka, nepotrebnih ID-jeva dece), RDS PostgreSQL log.
Retencija: 30 dana produkcija (audit je u bazi, ne u logovima), 14 dana staging. Dashboards: jedan
„Pilot ops” dashboard (besplatan nivo pokriva 3).

SLO 99,5 % mesečno (≈ 3,6 h nedostupnosti) je **cilj koji se meri Synthetics canary-jem** kroz
error budget; ne tvrdi se pre nego što postoje najmanje dva meseca merenja.

---

## 11. Backup i DR

Ciljevi (za dokazivanje): **RPO ≤ 15 min, RTO ≤ 4 h.**

- **Baza**: RDS automatski backup, retencija 14 dana (35 kad budžet dozvoli), PITR (log upload na 5
  min ⇒ RPO ≤ 15 min ostvariv). Dodatno: nedeljni ručni snapshot kopiran u drugi region
  (`eu-west-1`) sa zasebnim KMS ključem – zaštita od regionalnog incidenta i od greške u nalogu.
  AWS Backup plan može da preuzme oboje kad bude više resursa.
- **S3**: verzionisanje + (za produkciju) Cross-Region Replication u `eu-west-1` bucket sa istim
  bucket policy-jem. Fajlovi su rekonstruktabilni samo iz S3-a, pa je replikacija deo RPO-a.
- **Konfiguracija i tajne**: infrastruktura kao kod (Terraform ili CloudFormation – odluka u ADR,
  predlog Terraform) u `infrastructure/`; tajne u Secrets Manager-u imaju sopstvenu replikaciju
  (multi-region replica secret).
- **Instanca**: nema stanja; RTO zavisi od vremena da se podigne nova instanca iz AMI/user-data i
  povuče image iz ECR-a (< 30 min).

**Procedura restore drill-a (mesečno na staging-u, kvartalno „vežba produkcije” u prod nalogu na
kopiji):**

1. Zabeležiti `T0` i `LatestRestorableTime`; izabrati vreme restore-a `T0 – 10 min`.
2. `aws rds restore-db-instance-to-point-in-time` u novu instancu `vc-drill-<datum>` u istom
   DB subnet group-u, sa istim parameter group-om i security group-om (nema javnog pristupa).
3. Podići drugi backend kontejner sa `DB_HOST=<drill endpoint>` na staging instanci (drugi port),
   pokrenuti `migrate` (očekivano „no pending migrations”) i `serve`.
4. Smoke: `GET /health/ready`, prijava test naloga (kad EPIC 02 postoji), lista dece za dva tenant-a,
   negativni tenant test (korisnik A ne vidi tenant B).
5. **Ponovna primena erasure ledger-a**: pokrenuti `backend erasure-replay` komandu koja čita
   `app.erasure_ledger` iz **produkcijske** baze (ili njenog eksporta) za sve zapise sa
   `erased_at <= now()` i `reapplied_at IS NULL` na restore-ovanoj bazi, ponovo izvrši brisanje/
   anonimizaciju za `subject_type`/`subject_id` po `scope`, i upiše `reapplied_at`. Restore nije
   završen dok ovaj korak nije prošao – inače bi vraćanje backup-a vratilo podatke koje smo obrisali
   na zahtev korisnika.
6. Proveriti S3 konzistentnost: uzorak `app.files` redova → objekat postoji u bucket-u (ili replici).
7. Izmeriti trajanje koraka 2–5 i upisati u drill log (`docs/ops/restore-drills.md` – kreira se sa
   prvim drill-om) + metriku `dr.restore_drill_success`. Ako je > 4 h, otvara se task.
8. Obrisati drill instancu i kontejner; potvrditi da nema preostalih snapshot-a sa ličnim podacima
   van politike retencije.

**Failover produkcije (stvarni incident):** isti koraci 1–5 ali sa novim endpointom u Secrets
Manager-u i restartom backend kontejnera; DNS se ne menja (instanca ista). Ako je instanca
izgubljena: nova instanca iz IaC-a, `docker compose pull` konkretnog taga, statika iz poslednjeg CI
artefakta, Elastic IP se prebacuje.

---

## 12. CI/CD i deployment

### 12.1 CI (GitHub Actions, `.github/workflows/`)

| Job | Runner | Sadržaj | Gate |
|---|---|---|---|
| `backend` | ubuntu, JDK 21, service `postgres:17` | `./gradlew build test` (unit + integracioni na pravom PostgreSQL-u: RLS negativni testovi za dva tenant-a, pool reuse posle COMMIT/ROLLBACK, refresh replay, konkurentne attendance komande), `flyway validate` + `migrate` od nule + `migrate` nad prethodnim tagom (expand/contract provera) | obavezan |
| `openapi` | ubuntu | lint `docs/openapi/*.yaml` (Spectral, ruleset u repou), provera da svaki endpoint ima problem+json odgovore i `security` | obavezan |
| `web` | ubuntu, Node 22 | `npm ci`, typecheck, lint, vitest, `vite build`; artefakt `admin-web-dist` | obavezan |
| `mobile-shared` | ubuntu, JDK 21 | `./gradlew :shared-core:jvmTest` (bez Android SDK-a) | obavezan |
| `android` | ubuntu + Android SDK (setup action) | `./gradlew :androidApp:assembleDebug` + unit testovi `shared-ui` | obavezan |
| `ios` | **macos** runner | XcodeGen → `xcodebuild build` (simulator) + `shared-core` iOS testovi | **dokumentovani gate**: job se uključuje kad je macOS runner dostupan/plaćen; do tada je status „nije provereno” i ne sme se tvrditi da iOS build prolazi |
| `security` | ubuntu | gitleaks (secret scan), Dependabot/OWASP dependency check za Gradle i npm, Trivy nad Docker image-om | obavezan (high/critical blokira) |
| `docker` | ubuntu | build backend image-a (`backend/Dockerfile`, multi-arch arm64 zbog Graviton-a), push u ECR sa tagom `git-sha` + `vX.Y.Z` na tag; potpisivanje (cosign) kasnije | na `main` i tagovima |

Pravila: bez tajni u repou (gitleaks + `.env` u `.gitignore`); PR ne može u `main` bez obaveznih
jobova; `main` je uvek deploy-abilan.

### 12.2 Deployment

1. **Immutable image**: isti `vrtic-backend:<git-sha>` ide na staging pa na produkciju; nikad
   `latest`, nikad build na serveru.
2. **Staging prvo** (automatski na `main`): (a) `migrate` job – **jedan** kontejner, pokreće Flyway kao
   `app_owner`, završava; (b) `serve` restart sa novim tagom; (c) smoke test (health, login test
   naloga, jedan tenant read, negativan tenant read).
3. **Produkcija** (ručno odobrenje u GitHub Environment `production`, na git tag): isti koraci;
   deploy skripta na instanci (`infrastructure/scripts/deploy.sh`) radi `docker compose pull` →
   `migrate` → `up -d api` → smoke; nginx statiku menja atomično (`dist` u novi folder + symlink).
4. **Expand/contract migracije**: svaka promena šeme se radi u dva koraka (dodaj kolonu/tabelu
   nullable + kod koji piše u oba; kasnije ukloni staro) tako da stara verzija aplikacije radi nad
   novom šemom. To omogućava…
5. **Rollback aplikacije bez slepog rollback-a migracija**: rollback = pokreni prethodni image tag.
   Flyway `undo` se **ne** koristi; ako migracija mora nazad, piše se nova „forward” migracija posle
   analize. Migracije koje nisu unazad kompatibilne moraju biti označene u PR-u i deploy-ovane u
   prozoru sa najavom.
6. Downtime pri deploy-u: sekunde (restart kontejnera); zero-downtime dolazi sa drugom instancom + ALB.
7. Seed: `backend seed` odbija `APP_ENV=production` (postojeće pravilo); staging seed se pokreće
   ručno.

---

## 13. Procena troškova

**Datum provere cena: 16. 9. 2026.** Izvori: zvanične AWS stranice cena dohvaćene tog dana
(`aws.amazon.com/<servis>/pricing`), AWS dokumentacija (RDS PITR, RDS verzije), Apple Developer
Program stranica i Google Play Console pomoć. Kod nekih AWS stranica regionalne tabele se učitavaju
dinamički i **nisu bile vidljive** – te stavke su označene „nije provereno – procena”. Sve cene su
USD, bez PDV-a, on-demand (bez Savings Plans/Reserved).

**Napomena o starim brojkama:** ranije pominjani opsezi **76–194 USD/mes za produkciju i 25–60 USD/mes
za staging bili su planske procene iz prethodnog ciklusa, ne provereni iznosi**; ovaj odeljak ih
zamenjuje i ne treba ih citirati kao verifikovane.

### 13.1 Mala produkcija (pilot, 10–50 vrtića)

| Stavka | Pretpostavka | Procena / mes | Status |
|---|---|---|---|
| Compute – EC2 t4g.small (2 vCPU, 2 GiB) + 30 GB gp3 + Elastic IP | 730 h; Frankfurt | 15–22 | nije provereno – procena (Frankfurt tabela nije bila vidljiva; specifikacija instance proverena) |
| Compute – alternativa t4g.medium (4 GiB) ako Argon2id + JVM traže više | 730 h | 30–40 | nije provereno – procena |
| Baza – RDS PostgreSQL 17 db.t4g.small Single-AZ | 730 h | 25–35 | nije provereno – procena |
| Baza – gp3 storage 20 GB | ~0,115–0,137 USD/GB-mes | 2–3 | nije provereno – procena |
| Baza – backup storage | do 100 % veličine baze besplatno; iznad ~0,095 USD/GB | 0–3 | nije provereno – procena |
| Storage – S3 Standard 50 GB (P1 fotografije) + 200 k zahteva | ~0,0245 USD/GB-mes Frankfurt | 2–4 | nije provereno – procena (Frankfurt tabela nije bila vidljiva) |
| Egress | < 100 GB/mes iz instance + S3 | 0–5 | prvih 100 GB/mes besplatno (provereno, S3 stranica); iznad – procena |
| Backup – cross-region snapshot kopija 10 GB + S3 CRR | | 1–3 | nije provereno – procena |
| E-mail – SES | 20 k mejlova/mes | 2–4 | provereno: 0,10 USD/1 000 (à la carte) do 0,16 USD/1 000 (Essentials); bez dedicated IP-ja |
| Push – FCM / APNs | | 0 | besplatno (usluge platformi) |
| Monitoring – CloudWatch | 30 custom metrika (EMF), 15 alarma, 5 GB logova, 1 canary/5 min ≈ 8,6 k runova | 12–20 | delimično provereno: 0,30 USD/metrika, 0,10 USD/alarm, 0,50 USD/GB ingest, 0,03 USD/GB storage (provereno); cena canary runa nije bila vidljiva – procena |
| Tajne – Secrets Manager 8 tajni | 0,40 USD/tajna + 0,05 USD/10 k poziva | 3–4 | provereno |
| KMS – 3 CMK + envelope pozivi | 1 USD/ključ; 0,03 USD/10 k zahteva iznad 20 k besplatnih | 3–5 | provereno |
| DNS – Route 53 1 zona + 1 M upita | 0,50 USD/zona; 0,40 USD/M upita | 1–2 | provereno |
| CDN – CloudFront | ne koristi se u pilotu; always-free 1 TB/10 M zahteva kad se uvede | 0 | provereno (free tier) |
| TLS sertifikat | Let's Encrypt | 0 | – |
| **Ukupno mala produkcija** | | **≈ 70–115 USD/mes** (t4g.small) / **≈ 85–135** (t4g.medium) | mešovito – vidi statuse |

### 13.2 Staging

| Stavka | Pretpostavka | Procena / mes | Status |
|---|---|---|---|
| EC2 t4g.small (gasiti noću/vikendom → ~40 % vremena) | 300 h | 6–10 | nije provereno – procena |
| RDS db.t4g.micro Single-AZ, 20 GB, backup 7 dana (gasiti kad se ne koristi; RDS dozvoljava stop do 7 dana) | 300–730 h | 12–25 | nije provereno – procena |
| S3 10 GB, SES sandbox, CloudWatch minimalno, 4 tajne, 2 KMS ključa, Route 53 (isti zona ili poddomen) | | 6–10 | mešovito |
| **Ukupno staging** | | **≈ 25–45 USD/mes** | procena |

### 13.3 Jednokratni / godišnji troškovi van AWS-a

| Stavka | Iznos | Status |
|---|---|---|
| Apple Developer Program | **99 USD/godišnje** | provereno (developer.apple.com/programs, 16. 9. 2026) |
| Google Play developer nalog | **25 USD jednokratno** | provereno (Google Play Console pomoć, 16. 9. 2026) |
| GitHub Actions macOS minuti (iOS job) | zavisi od plana; macOS minuti su višestruko skuplji od Linux minuta | nije provereno – procena; alternativa: self-hosted Mac |
| Domen | ~10–20 USD/god | nije provereno – procena |

### 13.4 Šta menja procenu

- Multi-AZ RDS: približno duplira cenu baze (+25–35 USD) – tek kad SLO merenja pokažu potrebu.
- Druga app instanca + ALB: +~35–50 USD (ALB fiksno + LCU; nije provereno – procena).
- Fotografije (P1) sa 500+ vrtića: storage i egress postaju dominantni; tada CloudFront.
- Savings Plans (1 god, no upfront) mogu smanjiti EC2/RDS deo za dvocifreni procenat („do 72 %”
  navodi AWS za neke konfiguracije – provereno kao marketinški maksimum, ne kao naša ušteda);
  odluka posle 3 meseca stabilnog rada.

---

## 14. Ciljevi koje treba dokazati (ne osobine skeletona)

| Cilj | Kako se dokazuje | Kada |
|---|---|---|
| SLO 99,5 % dostupnosti mesečno | Synthetics canary + error budget izveštaj | posle 2 meseca produkcijskih merenja |
| RPO ≤ 15 min | RDS `LatestRestorableTime` alarm (< 30 min age) + restore drill koji vraća stanje 10 min pre T0 | prvi drill na staging-u pre pilota |
| RTO ≤ 4 h | izmereno trajanje koraka 2–5 restore drill-a uključujući erasure replay | mesečni drill |
| Bez javne baze | AWS Config pravilo `rds-instance-public-access-check` + security group review | pri svakoj IaC promeni |
| Bez tajni u repou | gitleaks u CI + pre-commit hook | svaki PR |

Skeleton (Docker Compose, Ktor bootstrap, migracije) ne implementira ništa od navedenog; ovaj
dokument je plan i kriterijum prihvatanja za EPIC 18 – Production Deployment.

---

## 15. Otvorene odluke (za ADR)

- IaC alat: Terraform vs CloudFormation (predlog: Terraform, state u S3 sa lock-om).
- Instance size na startu (t4g.small vs t4g.medium) – odlučiti posle Argon2id benchmark-a i JVM
  heap merenja na staging-u.
- Kada uvesti Multi-AZ i drugu app instancu (prag: SLO merenja ili > 150 vrtića).
- macOS runner za iOS: GitHub-hosted vs self-hosted.
- Cross-region DR region (`eu-west-1` predlog) i da li je „warm standby” potreban pre 500+ vrtića.
