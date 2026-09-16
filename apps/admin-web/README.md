# Vrtić Connect – web admin (skeleton)

Statički SPA (React + TypeScript + Vite) za administraciju vrtića. Ovo je **kostur** aplikacije:
providers, router, layout, i18n, API klijent i prazni ekrani. Poslovne funkcije nisu implementirane
i nema lažne prijave – login zove pravi backend i prikazuje njegov odgovor.

## Pokretanje

Zahtevi: Node ≥ 22.12, npm 10.

```bash
cd apps/admin-web
npm ci                # ili npm install (lockfile je obavezan, vidi napomenu ispod)
npm run dev           # http://localhost:5173, /api i /health se proksiraju na http://localhost:8080
```

Ostale skripte:

| Skripta             | Šta radi                                                              |
| ------------------- | --------------------------------------------------------------------- |
| `npm run typecheck` | `tsc --noEmit` za `src/` (tsconfig.app.json) i za Vite config          |
| `npm run lint`      | ESLint (flat config): typescript-eslint strict + react-hooks + refresh |
| `npm test -- --run` | Vitest + Testing Library (jsdom)                                       |
| `npm run build`     | typecheck + `vite build` u `dist/`                                     |
| `npm run preview`   | Servira `dist/` sa istim proxy pravilima                               |

Napomena o instalaciji: npm 10 ima grešku (`Cannot read properties of null (reading 'edgesOut')`)
pri gradnji stabla zavisnosti iz praznog projekta zbog opcionih peer zavisnosti `vitest`-a.
Sa postojećim `package-lock.json` `npm ci`/`npm install` rade bez ikakvih flagova. Ako ikada
budeš regenerisao lockfile od nule, uradi `npm install --legacy-peer-deps` jednom, a zatim
nastavi normalno (svi peer paketi su eksplicitno navedeni u `package.json`).

## Struktura

```
src/
  main.tsx              ulazna tačka: i18n init + <App/>
  app/                  App (providers), router (react-router data API), layout, error boundary
    layout/             AppLayout, HealthWidget (footer), LocaleSwitcher
    routes/             RequireAuth (guard), ProtectedShell, RootErrorBoundary, NotFoundPage
    navigation.ts       lista ekrana i putanja (§23 briefa)
  api/                  fetch klijent, problem+json parser, CSRF hook, auth i health pozivi
  auth/                 SessionProvider + tipovi sesije (bez ikakvog čuvanja tokena)
  tenant/               TenantProvider, TenantSwitcher, qk() graditelj React Query ključeva
  i18n/                 i18next init, locale util, locales/{sr-Latn,sr-Cyrl,en}.json
  components/           mali pristupačni UI delovi (Alert, TextField, EmptyState, PageHeader)
  features/<ime>/       po jedan ekran: dashboard, organizations, locations, groups, children,
                        parents, employees, attendance, schedules, announcements, calendar,
                        meals, photos, reports, settings, billing, auth (LoginPage)
  test/                 vitest setup + renderWithProviders helper
```

## Konvencije

- **TypeScript strict** (`strict`, `noUncheckedIndexedAccess`, `noImplicitOverride`,
  `exactOptionalPropertyTypes`, `verbatimModuleSyntax`). Nema `any` – lint to zabranjuje.
- **Sav tekst kroz `t()`**. Ključevi su tipizirani iz `sr-Latn.json` (`src/i18n/i18next.d.ts`),
  pa nepostojeći ključ ne prolazi typecheck. Podrazumevani jezik je `sr-Latn`; izbor jezika se
  čuva u `localStorage` (to je UI preferenca, ne token). `<html lang>` prati izabrani jezik.
  Imena se **ne** transliteruju automatski – prikazuju se onako kako su uneta.
- **Auth**: sesija živi isključivo u HttpOnly cookie-jima koje postavlja backend. SPA nikada ne čita
  ni ne čuva tokene (ni `localStorage`, ni `sessionStorage`). `SessionProvider` samo pita
  `GET /api/v1/auth/session` "ko sam" (definisan u docs/openapi.yaml kao `getCurrentSession`; do EPIC 02 vraća 401). Svaki `401` kroz klijent
  označava sesiju kao isteklu → `RequireAuth` preusmerava na `/login`.
- **API klijent** (`src/api/client.ts`): `fetch` sa `credentials: 'include'`, `Accept-Language`,
  `X-CSRF-Token` na POST/PUT/PATCH/DELETE (izvor tokena je zamenljiv – `setCsrfTokenSource`),
  ne-2xx odgovori postaju `ApiProblem` (RFC 9457 problem+json), greške mreže `NetworkError`.
- **React Query ključevi** se prave **samo** kroz `qk(userId, organizationId, ...parts)` ili
  `useScopedQueryKey()`. Ključ uvek počinje sa `['user', <id>, 'org', <id>]`, pa keš nikada ne
  može da procuri između korisnika ili vrtića. Promena tenanta zove `queryClient.clear()` pre
  nego što se novi tenant renderuje; `<Outlet>` je keyed po `organizationId` da bi se ekran
  remountovao.
- **Bez HTML injekcije**: `dangerouslySetInnerHTML`, `innerHTML` i `outerHTML` su zabranjeni
  ESLint pravilom; React eskejpuje sve stringove; i18next ima `escapeValue: false` upravo zato
  što bi dupli escape pokvario imena. Poruke grešaka sa servera se prikazuju kao tekst.
- **Pristupačnost**: semantički HTML, `label`+`htmlFor`, `aria-invalid`/`aria-describedby`
  za greške polja, `role="alert"` za greške, `role="status"` za informativne poruke, skip link,
  vidljiv `:focus-visible`, navigacija tastaturom bez custom widgeta.

## Šta je placeholder

- Svi ekrani u `features/*` osim `auth/LoginPage` prikazuju samo lokalizovan naslov i
  „Još nema podataka“ – nema lažnih podataka.
- `GET /api/v1/auth/session` i oblik `SessionUser` (`src/auth/types.ts`) su pretpostavke koje treba
  uskladiti sa backend ugovorom čim postoji.
- CSRF: ime cookie-ja `vc_csrf` i double-submit šema su pretpostavka (`src/api/csrf.ts`).
- Login: backend skeleton vraća `501 application/problem+json`; stranica prikazuje `title`/`detail`
  i `requestId` u alertu. Uspešan put još ne postoji, pa se po uspehu samo ponovo čita sesija.
- Health widget prikazuje isključivo „API: OK / nedostupno“ – telo `/health/ready` se nikad ne čita.
- Dok backend nema session endpoint, zaštićeni deo aplikacije nije dostupan u browseru
  (guard je namerno pravi, ne postoji dev bypass). Ekrani su pokriveni testovima kroz
  `renderWithProviders` sa test sesijom.

## Testovi

`npm test -- --run` pokreće:

- `api/problem.test.ts` – parsiranje problem+json i fallback ponašanje
- `tenant/queryKeys.test.ts` – scoping ključeva po korisniku i tenantu
- `tenant/TenantProvider.test.tsx` – `queryClient.clear()` pri promeni tenanta
- `features/auth/LoginPage.test.tsx` – 501 problem+json u `role="alert"`, mrežna greška, validacija
- `features/placeholder/PlaceholderPage.test.tsx` – naslov + empty state u sva tri jezika

## Deploy: isti origin, CSP, nginx

SPA se servira sa **istog origina** kao API (reverse proxy), pa cookie-ji rade bez CORS-a, a
backend može da proverava tačan `Origin`. Vite dev/preview proxy simulira to lokalno
(`changeOrigin: false` – backend u dev-u mora da prihvati `Origin: http://localhost:5173`).

Preporučeni headeri (nginx primer):

```nginx
location / {
  root /srv/admin-web/dist;
  try_files $uri /index.html;
  add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'; upgrade-insecure-requests" always;
  add_header X-Content-Type-Options nosniff always;
  add_header Referrer-Policy strict-origin-when-cross-origin always;
  add_header X-Frame-Options DENY always;
  add_header Permissions-Policy "camera=(), microphone=(), geolocation=()" always;
  add_header Cross-Origin-Opener-Policy same-origin always;
}

location /api/    { proxy_pass http://backend:8080; proxy_set_header Host $host; proxy_set_header X-Forwarded-Proto $scheme; }
location /health/ { proxy_pass http://backend:8080; }
```

Napomene uz CSP:

- Build ne koristi inline skripte ni inline stilove (CSS je u zasebnom fajlu), pa `'unsafe-inline'`
  nije potreban. Ako se ikada doda inline `<style>`, koristiti nonce, ne `'unsafe-inline'`.
- `connect-src 'self'` pokriva `/api` i `/health`; potpisani URL-ovi za fotografije (S3) će zahtevati
  eksplicitan `img-src` host kad se ta funkcija implementira.
- `index.html` postavlja `referrer` meta i `color-scheme`; keš za `index.html` treba da bude
  `no-cache`, a za hash-ovane asset-e `immutable`.
- Dev server (`vite`) neizbežno koristi inline skripte za HMR – CSP iznad važi za produkciju.
