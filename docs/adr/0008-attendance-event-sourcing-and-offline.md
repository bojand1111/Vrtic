# ADR-0008: Prisustvo kao nepromenljivi događaji + projekcija, sa idempotentnim komandama i offline redom

- Status: prihvaćeno (2026-09-16); implementacija u EPIC 08, offline u EPIC 08/01 mobile

## Kontekst

Vaspitači rade sa nestabilnim internetom; dva vaspitača mogu evidentirati isto dete; korekcije moraju ostaviti trag; QR/NFC/kiosk kasnije koriste isti domen.

## Odluka

1. `attendance_events` je append-only log (`CHECK_IN`, `CHECK_OUT`, `CORRECTION`, `ABSENCE_MARKED`, ...) sa `command_id` (UUID generisan na uređaju, `UNIQUE`), `occurred_at` (poslovno vreme) i `recorded_at`.
2. `attendance_days` je projekcija sa `version`; `attendance_visits` čuva parove dolazak/odlazak (više poseta dnevno, najviše jedna otvorena).
3. Komanda: `SELECT ... FOR UPDATE` na danu → provera `expectedVersion` → validan prelaz stanja → upis događaja + projekcije u istoj transakciji. Ishodi: `ACCEPTED`, `DUPLICATE` (isti `command_id`), `REJECTED`, `CONFLICT` (`VERSION_MISMATCH`, `NO_OPEN_VISIT`, `ALREADY_CHECKED_IN`).
4. Offline: uređaj čuva red komandi (`commandId`, `expectedVersion`, `occurredAt`) i šalje ih kroz `POST /attendance/sync`; server odgovara po komandi; klijent uklanja komandu tek posle potvrde i prikazuje konflikt korisniku. **Bez last-write-wins.**
5. Odsustvo za prisutno dete menja samo `absence_kind`, nikad ne radi automatski check-out.

## Posledice

- Potpuna istorija i mogućnost ponovne izgradnje projekcije.
- Brojači dnevnog pregleda imaju jednu definiciju (backend i `shared-core`): `Expected = Present + Departed + Absent + NotArrived`; neplanirano prisutna deca zasebno.
- Opoziv prava na offline uređaju nije garantovan trenutno – TTL keša (12 h) i provera prava pri reconnect-u ograničavaju rizik (dokumentovano u MOBILE_ARCHITECTURE.md).
