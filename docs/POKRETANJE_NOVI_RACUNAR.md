# Vrtić Connect: pokretanje na novom računaru (Windows)

Uputstvo za saradnika koji radi sa svog računara (bilo gde). Aplikacija se pokreće lokalno: baza, API i web rade
na njegovom računaru, podaci su sintetički demo podaci. Proveravano na Windows 11; na novoj mašini ovaj redosled
još nije prošao od nule, pa svaku grešku zabeležite i javite.

## 1. Pristup kodu

Vlasnik repozitorijuma (Bojan) na GitHub-u: `bojand1111/Vrtic` > Settings > Collaborators > Add people, unese
GitHub nalog saradnika. Saradnik prihvati pozivnicu iz e-pošte.

## 2. Programi (jednom)

| Program | Verzija | Napomena |
|---|---|---|
| Git for Windows | najnoviji | uz njega dolazi Git Bash i prijava na GitHub preko pregledača |
| JDK | Temurin 21 (adoptium.net) | pri instalaciji uključiti "Set JAVA_HOME" i "Add to PATH" |
| PostgreSQL | 17 (EDB instalater) | zapamtiti lozinku korisnika `postgres`; port 5432; `C:\Program Files\PostgreSQL\17\bin` dodati u PATH (zbog `psql`) |
| Node.js | 22 LTS | uključuje npm |

Provera u novom PowerShell prozoru: `java -version` (21), `psql --version` (17), `node --version` (22).

## 3. Preuzimanje koda

```powershell
cd C:\
git clone https://github.com/bojand1111/Vrtic.git
cd Vrtic
```

## 4. Prvo podešavanje (jednom)

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev-up.ps1 -SuperPassword "<lozinka korisnika postgres>"
```

Skripta pravi role i bazu `vrtic`, gradi backend (prvi put preuzima Gradle i biblioteke, traje duže), primenjuje
migracije, učitava demo podatke, pokreće test izolacije podataka i postavlja lozinku `Pilot-Lozinka-2026!` za sve
demo naloge. Na kraju ispiše naloge.

Web deo:

```powershell
cd apps\admin-web
npm ci
```

## 5. Svakodnevno pokretanje

Dva PowerShell prozora iz foldera `C:\Vrtic`:

```powershell
$env:APP_ENV="dev"; $env:DB_HOST="127.0.0.1"; $env:DB_PORT="5432"; $env:APP_DB_NAME="vrtic"; $env:APP_DB_RUNTIME_PASSWORD="runtime-pw"; $env:APP_WEB_ORIGIN="http://localhost:5173"
java -jar backend\build\libs\vrtic-backend-all.jar serve
```

```powershell
cd apps\admin-web
npm run dev
```

Zatim u pregledaču `http://localhost:5173`. Nalozi i scenariji: [MANUAL_TEST_DEMO.md](MANUAL_TEST_DEMO.md).
Vlasnik i platformski admin moraju da podese dvostepenu prijavu (aplikacija za autentifikaciju na telefonu);
za brzo testiranje bez toga koristite `admin@happykids.example.test`.

## 6. Posle `git pull`

```powershell
cd backend; .\gradlew.bat buildFatJar; cd ..
$env:APP_ENV="dev"; $env:DB_HOST="127.0.0.1"; $env:DB_PORT="5432"; $env:APP_DB_NAME="vrtic"; $env:APP_DB_OWNER_PASSWORD="owner-pw"
java -jar backend\build\libs\vrtic-backend-all.jar migrate
cd apps\admin-web; npm ci
```

Nove demo podatke (npr. jelovnik tekuće nedelje) donosi ponovno učitavanje seed-a, koje je bezbedno ponoviti:
`psql -h 127.0.0.1 -U app_owner -d vrtic -f docs\database\seed\dev_seed.sql` (lozinka `owner-pw`).

## Napomene

- Lozinke `owner-pw` / `runtime-pw` / `worker-pw` i demo lozinka važe samo za lokalnu razvojnu bazu.
- Portovi: baza 5432, API 8080, web 5173. Ako je nešto zauzeto, ugasite taj program ili promenite port u komandama.
- Umesto instaliranog PostgreSQL-a može Docker Desktop (`psql` je i dalje potreban na PATH-u): `copy .env.example .env`,
  u `.env` staviti `APP_DB_OWNER_PASSWORD=owner-pw`, `APP_DB_RUNTIME_PASSWORD=runtime-pw`, `APP_DB_WORKER_PASSWORD=worker-pw`,
  `docker compose up -d db`, pa `dev-up.ps1 -SuperPassword "<POSTGRES_PASSWORD iz .env>"` (role skripta je ponovljiva).
- Testovi: `cd backend; $env:VRTIC_TEST_DB="1"; .\gradlew.bat test` (uz iste env promenljive kao za `migrate`, plus
  `APP_DB_RUNTIME_PASSWORD`), web: `cd apps\admin-web; npm run typecheck; npm run lint; npm test -- --run`.
