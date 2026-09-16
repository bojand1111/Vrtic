# ADR-0004: Opaque sesije umesto JWT-a

- Status: prihvaćeno (2026-09-16); implementacija u EPIC 02

## Kontekst

Zahtev: trenutna online provera opoziva sesije i članstva, lista uređaja, opoziv pojedinačne sesije, detekcija ponovne upotrebe refresh tokena.

## Odluka

- Access token: 32 nasumična bajta, prefiks `vca_`, 10 minuta, samo sha256 hash u `access_tokens`; svaki zahtev radi lookup po hash-u i proverava istek, opoziv tokena, sesije i status korisnika.
- Refresh token: prefiks `vcr_`, apsolutno 30 dana, idle 7 dana, jedan aktivan po sesiji; `POST /auth/refresh` atomski konzumira i rotira; ponovna upotreba konzumiranog tokena opoziva celu sesiju (`REUSE_DETECTED`).
- Web: HttpOnly/Secure/SameSite kolačići + CSRF token + exact Origin. Mobile: Bearer + Keychain/Keystore.
- MFA (TOTP) obavezna za platform admine, vlasnike i support.

## Odbačeno

- JWT sa potpisom: opoziv zahteva blacklist ili kratak TTL bez liste uređaja; ne daje ništa što nam treba u monolitu sa jednom bazom. Može se uvesti kasnije za servis-servis pozive.

## Posledice

- Jedan indeksirani upit po zahtevu; TTL od 10 minuta ograničava trošak. Keš od nekoliko sekundi sa eksplicitnom invalidacijom je moguć kasnije.
- Sve auth tabele su dostupne samo u `auth_mode` DB kontekstu.
