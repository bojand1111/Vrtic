# ADR-0007: Bez Kubernetes-a, Kafke i Redis-a dok se ne dokaže potreba

- Status: prihvaćeno (2026-09-16)

## Kontekst

Pilot 10–50 vrtića, mali tim, budžet reda 100–200 USD mesečno za produkciju. Svaka dodatna infrastrukturna komponenta košta vreme i pažnju.

## Odluka

| Potreba | Rešenje u P0 | Kada uvesti nešto teže |
|---|---|---|
| Orkestracija | jedna VM/instanca sa Docker slikom, nginx, systemd/compose; kasnije ECS Fargate | kada je potrebno više od 2–3 instance sa auto-skaliranjem |
| Poruke/eventi | transakcioni outbox u PostgreSQL-u + worker sa `SELECT ... FOR UPDATE SKIP LOCKED` | kada backlog premaši desetine hiljada događaja/min ili treba više potrošača |
| Keš/sesije | PostgreSQL (opaque tokeni), Hikari pool, kratki TTL | kada lookup sesije postane usko grlo dokazano merenjem |
| Rate limiting | Ktor RateLimit + `login_attempts` u bazi | više instanci sa strogim globalnim limitom |
| Realtime | polling + push invalidacija; SSE kasnije | WebSocket samo uz jasnu potrebu |

## Posledice

- Manje pokretnih delova, jednostavniji DR (jedna baza sa PITR).
- Sve komponente su zamenjive jer su iza interfejsa (`OutboxRelay`, `SessionStore`, `RateLimiter`).
