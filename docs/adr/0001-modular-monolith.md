# ADR-0001: Backend kao modularni monolit

- Status: prihvaćeno (2026-09-16)
- Kontekst: pilot sa 10–50 vrtića, mali tim, zahtev da se skalira do 500+ bez velikog prepisivanja.

## Odluka

Jedna Kotlin/Ktor aplikacija podeljena na module po domenu (`auth`, `organizations`, `children`, `schedule`, `attendance`, `absence`, `announcements`, `notifications`, ...). Moduli komuniciraju kroz Kotlin interfejse i transakcioni outbox, nikad kroz mrežu. Iz iste slike se pokreću tri procesa (`serve`, `migrate`, kasnije `worker`) sa odvojenim DB kredencijalima.

## Posledice

- Jednostavan lokalni razvoj, jedna transakcija po zahtevu, bez distribuiranih transakcija.
- Worker (outbox relay, day-freeze, notifikacije) se izdvaja u zaseban proces bez promene koda jer je od početka odvojen po komandi i DB roli.
- Bez Kubernetes/Kafka/Redis dok se ne dokaže potreba (ADR-0007).
- Rizik: disciplina granica modula; mitigacija je pravilo „modul ne dira tuđe tabele direktno” i code review.
