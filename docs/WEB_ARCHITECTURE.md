# Web admin arhitektura – Vrtić Connect (apps/admin-web)

> Status: projektni dokument (P0). Izveden iz `docs/requirements/REQUIREMENTS_BRIEF.md`
> (odeljci 4, 7, 11, 21, 23, 24, 25, 27). Ne tvrdi da je bilo koji build, lint ili test izvršen;
> vidi odeljak 16. Kod i identifikatori na engleskom; dokumentacija na srpskom (latinica).

---

## 1. Stack (fiksne odluke)

| Oblast | Odluka |
|---|---|
| UI | React 19, TypeScript `strict: true`, Vite |
| Rutiranje | react-router (data router API) |
| Server state | TanStack Query |
| i18n | i18next + react-i18next; lokali `sr-Latn`, `sr-Cyrl`, `en` |
| Forme | react-hook-form + zod (šema deljena sa validacijom prikaza) |
| Stil | CSS Modules (bez runtime CSS-in-JS); design tokeni u `shared/theme` |
| Isporuka | statički SPA na **istom origin-u** kao API, iza nginx reverse proxy-ja (`/api` → backend, `/` → statika) |
| Auth | HttpOnly/Secure/SameSite=Lax kolačići + CSRF token + tačna provera `Origin` na backend-u; **bez tokena u localStorage** |
| Test | vitest + Testing Library; Playwright za smoke (kasnije) |

Next.js se **ne** koristi za admin panel (ostaje opcija za budući javni sajt). Nema drugog
backend-a ni BFF sloja – sva poslovna logika je u Ktor backend-u.

Pravilo o poslovnoj logici: web **ne** ponavlja pravila (prioritet rasporeda, dozvole, validacije
koje zavise od stanja); backend je izvor istine. Izuzetak su čiste prikazne funkcije koje moraju
biti identične backend-u – npr. **brojači prisutnosti** (`expected = present + departed + absent +
notArrived`, `unscheduledPresent` zasebno) – i one se testiraju istim JSON vektorima kao backend.

---

## 2. Struktura foldera

```
apps/admin-web/
├── index.html
├── vite.config.ts
├── tsconfig.json                  # strict, noUncheckedIndexedAccess, exactOptionalPropertyTypes
├── package.json
├── public/                        # favicon, manifest (bez korisničkih podataka)
└── src/
    ├── main.tsx                   # bootstrap: i18n init, QueryClient, RouterProvider
    ├── app/
    │   ├── router.tsx             # mapa ruta (odeljak 3)
    │   ├── AppShell.tsx           # layout: sidebar, header, tenant switcher, outlet
    │   ├── guards.tsx             # RequireAuth, RequireRole, RequireTenant
    │   └── providers.tsx          # QueryClientProvider, I18nProvider, SessionProvider
    ├── api/
    │   ├── client.ts              # fetch wrapper: kolačići, CSRF, problem+json, If-Match
    │   ├── problem.ts             # ProblemDetails tip + ApiError klase
    │   ├── endpoints/             # po domenu: auth.ts, organizations.ts, children.ts, attendance.ts ...
    │   └── generated/             # (opciono, kasnije) tipovi generisani iz docs/openapi
    ├── features/                  # jedan folder po ekranu/domenu iz brief-a §23
    │   ├── dashboard/
    │   ├── organizations/         # lista + tenant switcher
    │   ├── locations/
    │   ├── groups/
    │   ├── children/
    │   ├── parents/
    │   ├── employees/
    │   ├── attendance/
    │   ├── schedules/
    │   ├── announcements/
    │   ├── calendar/
    │   ├── meals/
    │   ├── photos/
    │   ├── reports/
    │   ├── settings/
    │   ├── billing/
    │   └── auth/                  # login, logout, sesije
    │       ├── LoginPage.tsx
    │       ├── useLogin.ts
    │       └── LoginPage.test.tsx
    ├── shared/
    │   ├── ui/                    # Button, DataTable, Dialog, Form fields, EmptyState, ErrorState, Skeleton
    │   ├── hooks/
    │   ├── domain/                # attendanceCounters.ts (ista formula kao backend), date helpers
    │   ├── session/               # SessionContext: user, memberships, activeOrganizationId
    │   └── theme/
    ├── i18n/
    │   ├── index.ts               # i18next init, detekcija, fallback sr-Latn
    │   └── locales/
    │       ├── sr-Latn/common.json
    │       ├── sr-Cyrl/common.json
    │       └── en/common.json
    └── test/                      # setup, msw handlers, test utils
```

Svaki `features/<x>/` sadrži: `routes.tsx` (rute tog feature-a), `api.ts` (query/mutation hook-ovi),
`components/`, `pages/`, testove. Feature ne importuje drugi feature direktno – zajednički kod ide u
`shared/`.

---

## 3. Mapa ruta (brief §23)

Sve tenant rute su pod `/o/:organizationId/...` da bi URL bio jednoznačan i da bi promena tenant-a
bila promena rute (ne skrivenog stanja).

| Ruta | Ekran | Ko vidi (minimalna rola na članstvu) | Prioritet |
|---|---|---|---|
| `/login` | Prijava | javno | P0 |
| `/logout` | Odjava (POST kroz akciju) | prijavljen | P0 |
| `/select-organization` | Izbor organizacije (tenant switcher – puna strana kad nema aktivnog tenant-a) | prijavljen | P0 |
| `/o/:organizationId` | Dashboard (brojači dana, nedavne najave, upozorenja) | ADMIN, OWNER | P0 |
| `/o/:organizationId/locations` | Objekti (lista, CRUD) | ADMIN, OWNER | P0 |
| `/o/:organizationId/groups` | Grupe (lista, CRUD, dodela vaspitača sa periodom) | ADMIN, OWNER | P0 |
| `/o/:organizationId/children` | Deca (lista, pretraga, upisi) | ADMIN, OWNER | P0 |
| `/o/:organizationId/children/:childId` | Profil deteta (osnovno, upisi, staratelji, ovlašćeni za preuzimanje; zdravstveni tab samo uz dozvolu) | ADMIN, OWNER (+ `CHILD_HEALTH_READ`) | P0 |
| `/o/:organizationId/parents` | Roditelji/staratelji (pozivnice, potvrda guardian veza) | ADMIN, OWNER | P0 |
| `/o/:organizationId/employees` | Zaposleni (članstva, role, dodele) | ADMIN, OWNER | P0 |
| `/o/:organizationId/attendance` | Prisustvo (dnevni pregled po grupi, korekcije sa razlogom) | ADMIN, OWNER | P0 |
| `/o/:organizationId/schedules` | Rasporedi (template, izmene za dan, neradni dani, pregled pre primene) | ADMIN, OWNER | P0 |
| `/o/:organizationId/announcements` | Najave (draft/published/archived, publika, ko je pročitao) | ADMIN, OWNER | P0 |
| `/o/:organizationId/calendar` | Kalendar događaja | ADMIN, OWNER | P1 |
| `/o/:organizationId/meals` | Jelovnik (dnevni/nedeljni) | ADMIN, OWNER | P1 |
| `/o/:organizationId/photos` | Fotografije (upload, tagovanje, saglasnosti, publika) | ADMIN, OWNER | P1 |
| `/o/:organizationId/reports` | Izveštaji (prisustvo, odsustva; izvoz) | ADMIN, OWNER | P1 |
| `/o/:organizationId/settings` | Podešavanja vrtića (tz, rok za promenu rasporeda, tolerancija kašnjenja, jezici) | OWNER, ADMIN (ograničeno) | P0 |
| `/o/:organizationId/billing` | Pretplata i plan (read-only u P0; bez payment providera) | OWNER | P0 (read) |
| `/platform/*` | Platform admin (vrtići, planovi, flagovi, sanitizovani logovi) | SUPER_ADMIN + MFA | P1 |
| `/account/sessions` | Lista sesija, opoziv uređaja | prijavljen | P0 |
| `*` | 404 (bez otkrivanja postojanja resursa) | – | P0 |

Guard lanac: `RequireAuth` → `RequireTenant` (organizationId iz URL-a mora biti u listi članstava
korisnika, inače `/select-organization`) → `RequireRole`. Guardovi su **UX**, ne autorizacija;
server proverava svaki zahtev.

---

## 4. Auth tok (kolačići + CSRF)

1. `GET /api/v1/auth/csrf` → backend postavlja/obnavlja `csrf` kolačić (nije HttpOnly, SameSite=Lax)
   **i** vraća isti token u telu. Klijent ga drži u memoriji (`SessionContext`), nikad u localStorage.
2. `POST /api/v1/auth/login` `{ email, password }` sa headerom `X-CSRF-Token`. Backend proverava
   `Origin` == dozvoljeni origin (tačno poklapanje) i CSRF token; postavlja `access` (10 min) i
   `refresh` (30 d apsolutno / 7 d idle) kolačiće: `HttpOnly; Secure; SameSite=Lax; Path=/api`.
   Refresh kolačić ima `Path=/api/v1/auth/refresh` da se ne šalje uz svaki zahtev.
3. Svaki API zahtev: `credentials: 'same-origin'`, `X-CSRF-Token` na svim non-GET metodama.
4. Na `401` klijent jednom pozove `POST /api/v1/auth/refresh` (single-flight: jedan `Promise` deljen
   među konkurentnim zahtevima), pa ponovi originalni zahtev. Neuspeh refresh-a → čišćenje sesije i
   keša → `/login?next=...`.
5. `POST /api/v1/auth/logout` (ovaj uređaj) / `POST /api/v1/auth/logout-all`.
6. Reautentifikacija za osetljive akcije (promena role, pristup zdravstvenim podacima, opoziv svih
   sesija): backend vraća `403` sa `code: "auth.reauthentication_required"`; klijent otvara modal za
   lozinku i ponavlja zahtev sa `X-Reauth-Token` iz odgovora `POST /api/v1/auth/reauthenticate`.
7. MFA za SUPER_ADMIN/OWNER: login vraća `mfa_required` korak; klijent prikazuje unos koda; nema
   „zapamti uređaj” u P0.

**U skeletonu**: login i refresh na backend-u vraćaju `501 Not Implemented` kao problem+json;
login strana to prikazuje kao poruku iz kataloga („Prijava još nije implementirana”), bez
lažne prijave i bez zaobilaženja.

---

## 5. Tenant switcher i keš po korisniku i organizaciji

- `SessionContext` = `{ user, memberships[], activeOrganizationId }`. `activeOrganizationId` je
  izveden iz URL-a (`/o/:organizationId`), ne iz localStorage.
- **Ključevi TanStack Query keša uvek počinju sa `[userId, organizationId, ...]`**:

```ts
export const qk = {
  children: (s: Scope) => [s.userId, s.organizationId, 'children'] as const,
  child: (s: Scope, childId: string) => [s.userId, s.organizationId, 'children', childId] as const,
  attendanceDay: (s: Scope, groupId: string, date: string) =>
    [s.userId, s.organizationId, 'attendance', groupId, date] as const,
};
```

- Promena tenant-a (`switchOrganization(nextId)`):
  1. `queryClient.cancelQueries()`;
  2. `queryClient.removeQueries({ predicate: q => q.queryKey[1] !== nextId })` – uklanjanje **svih**
     upita prethodnog tenant-a (ne samo invalidacija), da nijedan podatak prethodnog vrtića ne
     ostane u memoriji ni u `placeholderData`;
  3. reset lokalnih stanja formi (unmount kroz promenu `key` na `AppShell`);
  4. `navigate('/o/' + nextId)`.
- Odjava i promena korisnika rade `queryClient.clear()`.
- `persistQueryClient` (perzistencija keša u storage) se **ne** koristi – podaci o deci ne smeju
  ostati u browser storage-u.
- Više tabova: promena tenant-a u jednom tabu ne utiče na drugi jer je tenant u URL-u; sesija je
  deljena kroz kolačiće; `BroadcastChannel('vc-session')` obaveštava ostale tabove o odjavi.

---

## 6. API klijent i problem+json

```ts
export interface ProblemDetails {
  type: string; title: string; status: number; detail?: string; instance?: string;
  code?: string;                                  // stabilan aplikativni kod
  errors?: { field: string; code: string; message?: string }[]; // 422
  currentVersion?: number;                        // 409 version conflict
}

export class ApiError extends Error {
  constructor(public readonly status: number, public readonly problem: ProblemDetails | null,
              public readonly retryAfterSeconds?: number) { super(problem?.title ?? `HTTP ${status}`); }
  get isConflict() { return this.status === 409; }
  get isValidation() { return this.status === 422; }
}
```

- `api.request<T>(method, path, { body, etag, idempotencyKey, signal })`:
  - dodaje `X-CSRF-Token`, `Accept: application/json, application/problem+json`, `X-Request-Id`;
  - `If-Match: <etag>` kad je prosleđen; `Idempotency-Key` za komande koje se mogu ponoviti;
  - parsira `application/problem+json` u `ApiError`; `429` čita `Retry-After`;
  - vraća `{ data, etag }` (ETag iz odgovora se čuva uz entitet u kešu).
- Mapiranje statusa u UX: `401` → refresh/login; `403` i `404` → ista generička poruka „Nije pronađeno
  ili nemate pristup” (bez otkrivanja postojanja); `409` → konflikt UX (odeljak 7); `422` → greške po
  polju; `429` → poruka sa odbrojavanjem; `5xx` → ErrorState sa „Pokušaj ponovo” i request id-jem.
- Cursor paginacija: `useInfiniteQuery` sa `nextCursor` iz odgovora; filteri su allowlist-ovani na
  serveru, klijent ne šalje proizvoljne parametre.

---

## 7. If-Match i konflikt UX

- Svaki entitet koji se uređuje (raspored, profil deteta, najava, podešavanja) nosi `etag`/`version`.
  Mutacija šalje `If-Match`. Odgovor `409` sa `code: "*.version_conflict"` i `currentVersion`:
  1. mutacija se **ne** ponavlja automatski (bez last-write-wins);
  2. klijent osveži entitet (`invalidateQueries`) i prikaže `ConflictDialog` sa: „Neko je u
     međuvremenu izmenio ovaj zapis” + pregled trenutnih vrednosti + dugmad „Učitaj novo (odbaci
     moje izmene)” i „Primeni moje izmene na novu verziju” (forma ostaje popunjena, `If-Match` se
     ažurira na novi ETag, korisnik svesno ponovo šalje).
- Nedeljni raspored: atomsko ažuriranje cele nedelje + pregled pre primene (`POST .../preview` pa
  `PUT` sa `If-Match`), konflikt prikazuje diff po danu.
- Korekcija prisustva: obavezno polje razlog; UI ne dozvoljava check-out bez otvorene posete
  (i server to odbija).

---

## 8. Forme i validacija

- `react-hook-form` + `zod` šeme u `features/<x>/schema.ts`. Šema pokriva format i obavezna polja;
  poslovna pravila zavisna od stanja (preklapanje perioda, rok za promenu) validira server, a `422`
  greške se mapiraju na polja (`setError(field, ...)`) i na formu (globalne).
- Datumi: unos kao `LocalDate` string (`YYYY-MM-DD`), vremena kao `HH:mm`; nikad `Date` objekat sa
  zonom za raspored. Prikaz u zoni organizacije (`organization.timeZone`), sa oznakom kad se
  razlikuje od zone browsera.
- Dugme „Sačuvaj” je onemogućeno dok je mutacija u toku; dvostruki klik ne pravi dva zahteva
  (`Idempotency-Key` gde je primenljivo).
- Nema auto-transliteracije imena; polje se čuva kako je uneseno.

---

## 9. Stanja: učitavanje, prazno, greška

Svaka lista/strana ima četiri eksplicitna stanja i komponente u `shared/ui`:

| Stanje | Komponenta | Pravilo |
|---|---|---|
| Učitavanje | `Skeleton` (iste dimenzije kao sadržaj) | bez spinera preko cele strane posle prvog učitavanja; `isFetching` prikazan diskretno |
| Prazno | `EmptyState` sa objašnjenjem i primarnom akcijom | razlikovati „nema podataka” od „filter nije dao rezultate” |
| Greška | `ErrorState` sa porukom iz kataloga, request id-jem i „Pokušaj ponovo” | 403/404 ne otkrivaju postojanje |
| Delimično (offline/stale) | `StaleBanner` | web nema offline režim; samo obaveštenje da je mreža nedostupna i `retry` |

Mutacije: optimistic update samo za jednostavne toggle-ove (npr. „pročitano”); za prisustvo i
raspored **nema** optimistic update-a – čeka se server (zbog verzija i konflikata).

---

## 10. Pristupačnost (a11y)

- Cilj: WCAG 2.1 AA za sve P0 ekrane.
- Semantički HTML; tabele sa `<th scope>`; forme sa `<label for>`, `aria-describedby` za greške,
  `aria-invalid`; dijalozi sa focus trap-om i `Escape`; fokus se vraća na okidač.
- Sve akcije dostupne tastaturom; vidljiv fokus (`:focus-visible`); tap/klik površine ≥ 44×44 px.
- Kontrast ≥ 4.5:1; status prisustva nikad samo bojom (ikona + tekst).
- `lang` atribut prati izabrani lokal (`sr-Latn`, `sr-Cyrl`, `en`) radi čitača ekrana.
- Live region (`aria-live="polite"`) za rezultat mutacija.
- CI: `eslint-plugin-jsx-a11y` (error nivo) + `vitest-axe` na ključnim stranama.

---

## 11. XSS i sigurnosna pravila front-enda

- **Zabranjeno `dangerouslySetInnerHTML` za korisnički sadržaj** (najave, poruke, imena, napomene).
  ESLint pravilo `react/no-danger` na `error`; jedini dozvoljeni izuzetak bi bio statički sadržaj iz
  repoa, uz eksplicitan `// eslint-disable-next-line` i code review.
- Najave sa formatiranjem: čuvaju se kao ograničeni Markdown, render preko biblioteke koja
  proizvodi React elemente (bez raw HTML-a) i sa allowlist-om elemenata; linkovi dobijaju
  `rel="noopener noreferrer"`.
- Bez `eval`, bez `new Function`, bez inline event handlera u HTML-u (CSP to i sprečava).
- URL-ovi iz podataka se validiraju (`http(s):` samo) pre `href`.
- Signed URL-ovi za fajlove se ne loguju i ne čuvaju; `<img>` koristi kratkotrajni URL dobijen po
  potrebi (ili proxy `/api/v1/.../content`).
- Nema tokena u localStorage/sessionStorage; kolačići su HttpOnly (odeljak 4).
- Konzola: u produkcijskom build-u `console.*` ne sadrži podatke (uklanja se `vite-plugin`/`esbuild
  drop`).

---

## 12. i18n

- i18next sa `resources` učitanim iz `src/i18n/locales/<locale>/common.json` (bundle po lokalu,
  lazy `import()` po potrebi). Namespace-i po feature-u kad katalog poraste.
- Detekcija: `?lng=` → sačuvano podešavanje korisnika (server, profil) → `navigator.languages` →
  fallback `sr-Latn`.
- **Bez hardkodovanog teksta** u JSX-u: ESLint `i18next/no-literal-string` u `error` režimu za
  `src/features` i `src/shared/ui`.
- Ključevi su stabilni identifikatori (`children.list.title`), ne engleski tekst.
- Pluralizacija i formati datuma/brojeva preko `Intl` sa lokalom (`sr-Latn-RS`, `sr-Cyrl-RS`, `en`).
- Ćirilica: poseban katalog koji održava prevodilac; **nema automatske transliteracije** ni
  kataloga ni imena.
- CI test: sva tri kataloga imaju identičan skup ključeva (vitest test nad JSON fajlovima).

---

## 13. Build i CI

Skripte u `package.json`:

```
typecheck : tsc --noEmit
lint      : eslint . --max-warnings 0
test      : vitest run
build     : vite build
```

CI job `web` (Ubuntu, Node 22, `npm ci`): `typecheck` → `lint` → `test` → `build`; artefakt `dist/`.
`vite build` koristi `base: '/'`, izlaz sa hash-ovanim fajlovima (dugotrajni cache), `index.html`
bez keširanja. Dodatno: `npm audit --audit-level=high` (u okviru dependency scan-a u INFRASTRUCTURE.md).

Env varijable u build-u (`VITE_*`) su samo ne-tajne stvari (naziv okruženja, verzija). API base je
relativno `/api` – nema per-environment URL-a jer je isti origin.

---

## 14. Isporuka: isti origin preko nginx-a

```
server {
    listen 443 ssl http2;
    server_name admin.<domen>;

    root /var/www/admin-web;               # dist/ iz CI artefakta
    index index.html;

    location /api/ {
        proxy_pass         http://127.0.0.1:8080;   # Ktor backend
        proxy_http_version 1.1;
        proxy_set_header   Host $host;
        proxy_set_header   X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto https;
        proxy_read_timeout 60s;
        client_max_body_size 20m;                    # upload fotografija (P1)
    }

    location /health/ { proxy_pass http://127.0.0.1:8080; }

    location /assets/ { expires 1y; add_header Cache-Control "public, immutable"; }
    location / {
        try_files $uri /index.html;                 # SPA fallback
        add_header Cache-Control "no-store";
    }
}
```

- Backend čita `X-Forwarded-Proto` da bi `Secure` kolačići i `Origin` provera radili ispravno.
- Isti origin ⇒ nema CORS-a u produkciji; u razvoju Vite `server.proxy` prosleđuje `/api` na
  `localhost:8080` (i tada je origin `http://localhost:5173`, što je `APP_WEB_ORIGIN` u `.env.example`).
- Kada bude potreban CDN za statiku (500+ vrtića), statika ide na S3+CloudFront pod istim domenom
  sa CloudFront behavior-om `/api/*` → origin backend; odluka u INFRASTRUCTURE.md.

---

## 15. Preporučeni sigurnosni headeri (nginx)

```
Content-Security-Policy:
  default-src 'self';
  script-src 'self';
  style-src 'self' 'unsafe-inline';      # ukloniti 'unsafe-inline' kad CSS Modules pokriju sve stilove
  img-src 'self' data: blob:;            # blob: za pregled pre upload-a; signed S3 URL-ovi idu preko proxy-ja ili se dodaje njihov host
  font-src 'self';
  connect-src 'self';
  frame-ancestors 'none';
  form-action 'self';
  base-uri 'self';
  object-src 'none';
  upgrade-insecure-requests
Strict-Transport-Security: max-age=31536000; includeSubDomains
X-Content-Type-Options: nosniff
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: camera=(), microphone=(), geolocation=()
X-Frame-Options: DENY
```

Ako se signed S3 URL-ovi serviraju direktno (bez proxy-ja), `img-src` dobija tačan host bucket-a
(`https://<bucket>.s3.eu-central-1.amazonaws.com`); nikad wildcard. CSP se uvodi u `report-only`
režimu na staging-u pre uključivanja na produkciji.

---

## 16. Šta skeleton sadrži

Skeleton u `apps/admin-web` sadrži **samo**:

1. Vite + React 19 + TypeScript strict projekat, ESLint (uključujući `react/no-danger`,
   `jsx-a11y`, `i18next/no-literal-string`), vitest.
2. `AppShell` sa navigacijom (sidebar sa stavkama iz mape ruta, sve vode na placeholder strane
   „U pripremi”) i izborom jezika.
3. `LoginPage` sa formom (email, lozinka, zod validacija) koja poziva `POST /api/v1/auth/login` i
   **prikazuje `501` Problem** kao poruku iz kataloga; bez lažne prijave, bez skrivanja odgovora.
4. `HealthWidget` koji poziva `GET /health/ready` i prikazuje status (ok / degraded / nedostupno).
5. `api/client.ts` sa problem+json parsiranjem, CSRF headerom i `If-Match` podrškom (nekorišćeno
   dok nema mutacija).
6. i18n sa tri kataloga i testom jednakosti ključeva.
7. Vite dev proxy `/api` → `http://localhost:8080`; nginx primer konfiguracije u `infrastructure/`.
8. CI job `web` (typecheck, lint, test, build).

Skeleton **ne sadrži** tenant switcher sa stvarnim članstvima, guardove sa serverskim ulogama,
poslovne ekrane ni mutacije. **Ovaj dokument ne tvrdi da su typecheck, lint, test ili build
izvršeni** – status provera je u validacionom izveštaju projekta.

---

## 17. Otvorene odluke (za ADR)

- Generisanje TS tipova iz OpenAPI (openapi-typescript) vs ručni tipovi – predlog: generisanje čim
  OpenAPI bude stabilan (EPIC 03).
- Markdown biblioteka za najave i allowlist elemenata.
- Playwright smoke u CI (posle EPIC 02).
- CDN za statiku (vezano za INFRASTRUCTURE.md, tek na 500+ vrtića).
