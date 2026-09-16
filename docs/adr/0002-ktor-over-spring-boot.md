# ADR-0002: Ktor umesto Spring Boot-a

- Status: prihvaćeno (2026-09-16), preispitati na kraju EPIC 02

## Kontekst

Backend je Kotlin. Dve realne opcije: Ktor 3.x (Kotlin-native, coroutines, eksplicitan) i Spring Boot 3.x (najveći ekosistem, Spring Security, više developera ga zna).

## Odluka

Ktor 3.5.2 sa Kotlin 2.3.21 (verzija sa kojom je Ktor građen), JDBC + HikariCP, Flyway, eksplicitni SQL repository-ji.

## Razlozi

- Auth strategija je ionako custom (opaque sesije, hash tokena u bazi, RLS kontekst po transakciji) – Spring Security ne bi doneo veliku uštedu.
- Manja potrošnja memorije i brži start na malim instancama u pilotu.
- Eksplicitnost: bez auto-konfiguracije i refleksije, lakše je dokazati šta se dešava na svakom zahtevu (bitno za bezbednosni pregled).

## Kada bi Spring Boot bio bolji

- Tim ima Spring iskustvo, a nema Kotlin/Ktor iskustvo.
- Planiraju se OAuth2/OIDC prijave (Google/Apple) uz proveren okvir – Spring Security ih nudi gotove.
- Potrebno je mnogo integracija sa gotovim starter-ima.

Ako se u EPIC 02 pokaže da je ručna implementacija sesija/CSRF-a previše rizična za tim, prelazak na Spring Boot je najjeftiniji pre pisanja poslovnih modula.
