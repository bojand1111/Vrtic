# Vrtić Connect: ručno testiranje demo verzije (sve uloge)

Pokretanje: desktop prečica **Vrtić Connect** (`scripts/start-vrtic.ps1`). Skripta podiže PostgreSQL (port 5433),
primenjuje migracije (V1..V5), učitava demo podatke (`docs/database/seed/dev_seed.sql`, idempotentno), prvi put postavi
lozinke demo naloga, pokreće API (:8080) i web (:5173) i otvara pregledač. Gašenje: **Vrtić Connect STOP**.

Lozinka za sve demo naloge: `Pilot-Lozinka-2026!` (samo DEV baza; postavlja je `dev-set-password`, nije u repozitorijumu).
Ako promenite lozinku u aplikaciji, skripta je neće pregaziti. Za ponovno postavljanje obrišite
`C:\Posao\Vrtic-tools\demo-passwords.done` i ponovo pokrenite prečicu.

## Demo nalozi

| Nalog | Uloga | Šta vidi |
|---|---|---|
| `vlasnik@happykids.example.test` | Vlasnik (Happy Kids) | sve, uključujući podešavanja vrtića |
| `admin@happykids.example.test` | Administrator | sve osim izmene podešavanja i naplate |
| `vaspitac1@happykids.example.test` | Vaspitač, grupa Bubamare | svoja grupa: deca, prisustvo, rasporedi, odsustva (čitanje) |
| `vaspitac2@...`, `vaspitac3@happykids.example.test` | Vaspitači, grupa Leptirići | isto za Leptiriće |
| `roditelj01@example.test` | Roditelj (Luka i Andrej Đorđević) | svoja deca, odsustva, raspored, prisustvo, obaveštenja, kalendar, jelovnik |
| `roditelj02` .. `roditelj12@example.test` | Roditelji ostale dece | `roditelj10` ima vezu sa Ivom na čekanju (PENDING) |
| `roditelj12@example.test` | Roditelj u Happy Kids + vaspitač u Sunčici | promena vrtića u zaglavlju |
| `vlasnik@suncica.example.test` | Vlasnik drugog vrtića | ne vidi ništa iz Happy Kids (izolacija) |

Demo podaci: objekat Novi Sad, grupe Bubamare (8 dece) i Leptirići (7 dece), 3 vaspitača, 12 roditelja, rasporedi
pon-pet, Sara Kostić bolesna danas i sutra, dva objavljena obaveštenja i jedan nacrt, tri događaja u kalendaru,
jelovnik za tekuću nedelju (dopunjava se svake nove nedelje pri pokretanju).

## Scenariji

### Vlasnik / administrator
1. Kontrolna tabla: brojači za dan (očekivano, prisutno, otišlo, odsutno, nije stiglo, kasni), po grupama, najnovija obaveštenja i događaji. Promena datuma menja brojače.
2. Objekti: dodaj objekat, izmeni, deaktiviraj (objekat sa aktivnim grupama se ne može deaktivirati ni obrisati: poruka o sukobu).
3. Grupe: dodaj grupu, izmeni kapacitet, dugme "Vaspitači": dodeli vaspitača (glavni/pomoćni/zamena) i završi dodelu.
4. Deca: dodaj dete sa grupom i datumom upisa; otvori detalje: izmena podataka, premeštanje u drugu grupu, roditelji (poveži roditelja, potvrdi vezu na čekanju, izmeni prava, opozovi), osobe za preuzimanje, nedeljni raspored.
5. Roditelji: pregled veza; potvrdi vezu `roditelj10` sa Ivom Simić.
6. Zaposleni: profili, članovi (opoziv traži lozinku), pozivnice. Pozovi vaspitača ili roditelja (za roditelja se bira dete).
   U DEV okruženju mejl se ne šalje: link za prihvatanje se ispisuje u prozoru "Vrtic API" (`DEV MAIL kind=INVITATION ... link=...`).
   Otvori link, registruj nalog (ime, prezime, lozinka), novi vaspitač se pojavljuje u Zaposlenima, novi roditelj ima vezu na čekanju.
7. Prisustvo: izaberi grupu, "Dolazak" / "Odlazak" / "Odsutno" / "Poništi odsustvo"; korekcija (sa razlogom) za vlasnika i administratora.
8. Obaveštenja: novo obaveštenje (ceo vrtić, objekat ili grupa), sačuvaj kao nacrt, objavi, pogledaj primaoce i ko je pročitao, arhiviraj.
9. Kalendar: sledeći mesec, novi događaj (ceo dan ili sa vremenom), izmena, brisanje.
10. Obroci: izmeni dan (4 obroka, alergeni odvojeni zarezom), objavi.
11. Podešavanja: vlasnik menja radno vreme i radne dane; administrator vidi isto samo za čitanje.

### Vaspitač (`vaspitac1`)
1. Kontrolna tabla: moje grupe, obaveštenja, događaji, današnji jelovnik.
2. Prisustvo: samo Bubamare; beleži dolazak i odlazak za danas.
3. Deca / rasporedi / odsustva: samo deca iz njegove grupe, bez izmena.
4. Obaveštenja: otvaranje obaveštenja ga označava kao pročitano (vidi se kod administratora u "Primaoci").

### Roditelj (`roditelj01`)
1. Kontrolna tabla: moja deca, obaveštenja, događaji, jelovnik.
2. Deca: detalji svoje dece, dodavanje osobe za preuzimanje, izmena nedeljnog rasporeda (od danas ili kasnije).
3. Odsustva: prijava odsustva (bolest, odmor, drugo) i otkazivanje svoje prijave; vaspitač i administrator odmah vide odsustvo.
4. Prisustvo: status svoje dece za izabrani dan.
5. Proba izolacije: roditelj ne vidi tuđu decu, a adresa `/employees` prikazuje poruku o zabranjenom pristupu.

### Drugi vrtić (`vlasnik@suncica.example.test`)
Vidi samo Sunčicu (grupa Zvezdice, jedno dete). Nijedan podatak iz Happy Kids nije vidljiv.

## Šta nije deo ove verzije
Fotografije, izveštaji, naplata i platformska administracija su i dalje ekrani-zamene. MFA (TOTP) nije implementiran,
pa `platform.admin@example.test` dobija "MFA_REQUIRED" na platformskim ekranima. Zdravstveni profil deteta, poruke,
saglasnosti, neradni dani i izmene rasporeda za pojedinačni dan nisu implementirani. Mobilna aplikacija nije menjana.
