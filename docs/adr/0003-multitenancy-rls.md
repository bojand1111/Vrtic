# ADR-0003: Multi-tenancy sa jednom bazom, shared schema i Row Level Security

- Status: prihvaćeno (2026-09-16), verifikovano na PostgreSQL 17.11 (VALIDATION_REPORT.md)

## Kontekst

Više nezavisnih vrtića na istoj platformi. Alternativa „baza po tenant-u” je skupa za 500+ vrtića i komplikuje migracije; „šema po tenant-u” ima slične probleme sa alatima i pool-ovima.

## Odluka

1. Jedna baza, šema `app`, `organization_id` na svim tenant tabelama, `UNIQUE (id, organization_id)` + kompozitni FK.
2. RLS `ENABLE` + `FORCE` na svim tenant tabelama; politike `TO app_runtime` čitaju transaction-local GUC-ove (`app.organization_id`, `app.user_id`, `app.auth_mode`, `app.platform_mode`).
3. Tri role: `app_owner` (DDL, Flyway), `app_runtime` (API), `app_worker` (relay); sve `NOBYPASSRLS`, runtime nije vlasnik.
4. FORCE vezuje i vlasnika; vlasnik ulazi u `app.maintenance_mode` eksplicitno po transakciji (seed, ispravke, migracije podataka).
5. Kontekst se postavlja jednim `set_config(..., true)` pozivom sa sve četiri vrednosti, tek posle provere sesije i članstva.
6. Globalne tabele (`users`, `sessions`, tokeni) imaju sopstvene politike (self / auth_mode / platform_mode / član istog tenant-a).

## Posledice

- Greška u aplikativnom SQL-u ne može vratiti tuđe redove; testirano (`RlsIntegrationTest`, `rls_negative_tests.sql`: 37 tvrdnji).
- RLS ne štiti granice unutar vrtića (grupa vaspitača, dete roditelja) – to su aplikativne resource politike.
- Politike sa podupitima (`users`, `organizations`) zahtevaju indekse na `organization_memberships`; performanse se mere u EPIC 03.
- Seed i data-fix skripte moraju eksplicitno uključiti maintenance režim – namerno trenje.
