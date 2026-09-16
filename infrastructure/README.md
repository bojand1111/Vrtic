# infrastructure/

Infrastrukturni artefakti. Projektovana topologija, procena troškova i runbook-ovi su u [docs/INFRASTRUCTURE.md](../docs/INFRASTRUCTURE.md).

| Putanja | Namena | Status |
|---|---|---|
| `nginx/vrtic.conf` | reverse proxy sa istim origin-om: `/` statički admin SPA, `/api` i `/health` → backend; TLS, bezbednosna zaglavlja, CSP, rate limit na auth rutama | predlog, nije testiran na serveru |
| `terraform/` | AWS EU okruženje (VPC, EC2/ECS, RDS PostgreSQL 17, privatni S3, SES, Secrets Manager, KMS, CloudWatch) | planirano – EPIC 18 |
| `runbooks/` | restore drill, rotacija ključeva, incident, break-glass pristup bazi | planirano – EPIC 17/18 |

Pravila:

- Nikakve tajne u ovom folderu. Vrednosti dolaze iz Secrets Manager-a / SSM-a.
- Deploy: jedna immutable Docker slika (`backend/Dockerfile`), jedan `migrate` job pre `serve`, staging pre produkcije, smoke test (`/health/ready`, `POST /api/v1/auth/login` → očekivani status), rollback aplikacije bez slepog vraćanja migracija.
