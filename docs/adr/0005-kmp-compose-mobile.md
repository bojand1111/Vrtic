# ADR-0005: Kotlin Multiplatform + Compose Multiplatform za mobilne aplikacije

- Status: prihvaćeno (2026-09-16); iOS build gate obavezan od EPIC 01

## Kontekst

Jedan tim, dve platforme, isti Kotlin jezik kao backend. Alternative: Flutter (Dart, zreo na iOS-u), React Native, dve native aplikacije.

## Odluka

KMP + Compose Multiplatform 1.12.0 (Kotlin 2.3.21, AGP 8.13.2, Gradle 9.3.0). Moduli: `shared-core` (jvm + ios; DTO, validacija, poslovna pravila, i18n, API klijent – testira se bez Android SDK-a), `shared-ui` (android + ios; Compose ekrani), `androidApp`, `iosApp` (SwiftUI host, XcodeGen). Android moduli se uključuju u build samo kada je SDK prisutan, da `shared-core` testovi rade na svakom host-u i u backend-lakom CI-ju.

## Šta se ne deli

Keychain/Keystore, push (FCM/APNs), sistemske dozvole, kamera/izbor fotografija, lifecycle, crash reporting – platformski `expect/actual` ili Swift kod.

## Posledice

- Poslovna pravila (npr. brojači dnevnog pregleda) žive na jednom mestu i testiraju se JVM testovima.
- Rizik: CMP na iOS-u je mlađi od SwiftUI-ja; obavezni su iOS build na macOS runner-u i test na pravim uređajima u pilotu. Ako se pokaže neprihvatljiv kvalitet na iOS-u, `shared-core` ostaje, a UI se piše u SwiftUI (izlazna strategija bez bacanja logike).
- Ne tvrdimo da se deli sav kod.
