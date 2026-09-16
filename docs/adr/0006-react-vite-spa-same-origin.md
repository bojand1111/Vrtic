# ADR-0006: Web administracija kao React + Vite SPA na istom origin-u

- Status: prihvaćeno (2026-09-16)

## Kontekst

Zatvoren administrativni panel; bez potrebe za SEO-om ili serverskim renderovanjem. Next.js je razmatran.

## Odluka

React 19 + TypeScript strict + Vite, statički fajlovi servirani preko nginx-a na istom origin-u kao API (`/` SPA, `/api` backend). Auth preko HttpOnly/Secure/SameSite kolačića + CSRF token + exact Origin provera. TanStack Query ključevi vezani za `userId + organizationId`; promena tenant-a briše keš. Nema tokena u `localStorage` (dozvoljena je samo UI preferencija kao što je jezik).

## Odbačeno

- Next.js za admin: uvodi drugi server i iskušenje da se poslovna logika duplira; ostaje opcija za budući javni sajt/marketplace.
- Cross-origin API + Bearer u JS-u: veći XSS rizik, komplikovaniji CORS.

## Posledice

- Jedan deploy artefakt (dist) + nginx konfiguracija; CSP `default-src 'self'`.
- Sve konflikte (`409` + `If-Match`) i stanja (loading/empty/error) rešava klijent bez „tihe” pobede.
