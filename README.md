# Vrtić Connect (kindergarten-platform)

SaaS platforma za privatne vrtiće: mobilna aplikacija za roditelje i vaspitače (Kotlin Multiplatform + Compose), web administracija (React + Vite) i jedan Ktor backend nad PostgreSQL-om sa Row Level Security izolacijom po vrtiću.

> **Stanje:** foundation skeleton (EPIC 01) plus EPIC 02 Bearer login, refresh rotacija i session bootstrap: izdaju se opaque access/refresh tokeni, web dobija HttpOnly kolačiće, resolver proverava validnost i opoziv, a reuse refresh tokena opoziva sesiju. CSRF mutacije i poslovni moduli još nisu implementirani. Šta je stvarno provereno piše u [docs/VALIDATION_REPORT.md](docs/VALIDATION_REPORT.md). Sledeći korak je nastavak [EPIC 02](docs/DEVELOPMENT_ROADMAP.md).

## Dokumentacija

| Dokument | Sadržaj |
|---|---|
| [docs/PRODUCT_SPEC.md](docs/PRODUCT_SPEC.md) | specifikacija proizvoda, uloge, poslovna pravila, opseg P0/P1/P2 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | sistemska arhitektura, backend struktura, pipeline zahteva, Ktor vs Spring |
| [docs/DATABASE.md](docs/DATABASE.md) + [docs/database/schema.sql](docs/database/schema.sql) | model podataka (72 tabele), RLS, garancije baze vs aplikacije, ER dijagrami u [docs/diagrams/](docs/diagrams/) |
| [docs/API.md](docs/API.md) + [docs/openapi.yaml](docs/openapi.yaml) | REST ugovor `/api/v1` (167 operacija), konvencije, primeri |
| [docs/SECURITY.md](docs/SECURITY.md) | auth, dozvole (matrica), multi-tenancy, osetljivi podaci, audit, privatnost |
| [docs/INFRASTRUCTURE.md](docs/INFRASTRUCTURE.md) | AWS topologija, backup/DR, CI/CD, monitoring, procena troškova |
| [docs/MOBILE_ARCHITECTURE.md](docs/MOBILE_ARCHITECTURE.md) | KMP moduli, offline algoritam, ekran „Danas” |
| [docs/WEB_ARCHITECTURE.md](docs/WEB_ARCHITECTURE.md) | React SPA, cookie auth, tenant switcher, keš |
| [docs/DEVELOPMENT_ROADMAP.md](docs/DEVELOPMENT_ROADMAP.md) | 18 EPIC-a sa konkretnim taskovima, kriterijumima i testovima |
| [docs/adr/](docs/adr/) | 8 arhitekturnih odluka |
| [docs/requirements/REQUIREMENTS_BRIEF.md](docs/requirements/REQUIREMENTS_BRIEF.md) | izvorni zahtevi vlasnika proizvoda |

## Struktura repozitorijuma

```
backend/            Ktor 3.5 / Kotlin 2.3 / JDK 21, Flyway migracije, sopstveni Gradle build
apps/mobile/        KMP + Compose Multiplatform (shared-core, shared-ui, androidApp, iosApp)
apps/admin-web/     React 19 + TypeScript + Vite
infrastructure/     nginx konfiguracija, (kasnije) Terraform i runbook-ovi
docs/               dokumentacija, šema, OpenAPI, seed, RLS testovi, ADR
scripts/db/         kreiranje DB rola (van migracija)
docker-compose.yml  lokalni PostgreSQL 17 (+ profil `app` za migrate/api u kontejneru)
.github/workflows/  CI
```

## Lokalno pokretanje

Preduslovi: JDK 21, Docker (ili lokalni PostgreSQL 17), Node 22. Za Android build i Android SDK; za iOS macOS + Xcode + XcodeGen.

```bash
cp .env.example .env            # izmeni lozinke (samo lokalno)
docker compose up -d db         # PostgreSQL 17 + role app_owner/app_runtime/app_worker
```

Backend (migracija se pokreće kao poseban job, nikad iz API procesa):

```bash
cd backend
./gradlew buildFatJar
set -a; source ../.env; set +a
java -jar build/libs/vrtic-backend-all.jar migrate
java -jar build/libs/vrtic-backend-all.jar serve      # http://localhost:8080
```

Provera: `GET /health/live`, `GET /health/ready` (proverava bazu i migracije), DB-backed `POST /api/v1/auth/login` → Bearer tokene za mobilne klijente ili HttpOnly kolačiće za web, `POST /api/v1/auth/refresh` → rotiran par tokena, `GET /api/v1/auth/session` → trenutni korisnik i članstva, `GET /api/v1/organizations/{id}/ping` → `401` bez sesije.

Seed (sintetički podaci; odbija bazu koja nije označena kao `dev`):

```bash
psql -h localhost -U postgres -d postgres -c "ALTER DATABASE vrtic SET app.environment = 'dev';"
psql -h localhost -U app_owner -d vrtic -f docs/database/seed/dev_seed.sql
psql -h localhost -U app_runtime -d vrtic -f docs/database/tests/rls_negative_tests.sql
```

Backend testovi (integracioni RLS testovi se uključuju promenljivom):

```bash
cd backend && VRTIC_TEST_DB=1 ./gradlew test
```

Web admin:

```bash
cd apps/admin-web && npm ci && npm run dev        # http://localhost:5173, proxy /api → 8080
```

Mobile (deljeni testovi rade bez Android SDK-a):

```bash
cd apps/mobile && ./gradlew :shared-core:jvmTest
./gradlew :androidApp:assembleDebug               # zahteva Android SDK
```

Bez PostgreSQL-a u Docker-u? Bilo koji PostgreSQL 17: prvo `scripts/db/00_roles.sql` kao superuser (vidi zaglavlje fajla za psql promenljive), zatim koraci iznad sa `DB_HOST`/`DB_PORT`.

## Bezbednosna pravila skeletona

- Nema razvojne prijave ni prečica koje preskaču dozvole. Auth rute su zatvorene (`501`), zaštićene rute vraćaju `401`.
- Runtime DB rola nije vlasnik tabela, nema `BYPASSRLS`; RLS je `ENABLE + FORCE`; vlasnik ulazi u maintenance režim eksplicitno.
- Tajne samo kroz env/.env (git-ignorisan); `.env.example` nema prave vrednosti.
- Logovi bez tokena, lozinki i PII (`requestId` za korelaciju).

## Verzije (zaključane 16. 9. 2026)

Kotlin 2.3.21 · Ktor 3.5.2 · Gradle 9.3.0 · JDK 21 · PostgreSQL 17.11 · Flyway 13.7.0 · HikariCP 7.1.0 · Compose Multiplatform 1.12.0 · AGP 8.13.2 · React 19.3 · Vite 8.3 · TypeScript 6.0. Obrazloženje u `backend/gradle/libs.versions.toml` i [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
