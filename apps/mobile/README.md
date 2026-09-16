# Vrtić Connect – mobilna aplikacija (Kotlin Multiplatform + Compose Multiplatform)

Skelet mobilne aplikacije za Android i iOS. Deli se poslovna logika, networking, DTO/serijalizacija,
auth koordinacija, stanje i lokalizacija; UI je Compose Multiplatform. Platformski delovi
(Keychain/Keystore, push, dozvole, kamera, crash reporting) namerno **nisu** deo skeleta – planirani su u
kasnijim epicima (vidi dno).

## Struktura

| Modul | Šta je | Targeti |
|---|---|---|
| `:shared-core` | Kotlin biblioteka bez UI-ja: API klijent (Ktor), `ApiProblem` (problem+json), `AuthApi`, `SecureTokenStore`, `AuthCoordinator`, katalog stringova (sr-Latn / sr-Cyrl / en), dnevni brojači prisustva | `jvm`, `iosArm64`, `iosSimulatorArm64` (bez Android targeta – Android koristi jvm varijantu) |
| `:shared-ui` | Compose Multiplatform biblioteka: `App()` composable (naslov, izbor jezika, dugme „Proveri API“, onemogućeno dugme „Prijava“), `MainViewController()` za iOS | `android`, `iosArm64`, `iosSimulatorArm64` |
| `:androidApp` | Android host aplikacija (`com.vrticconnect.app`), jedna Compose aktivnost | Android |
| `iosApp/` | SwiftUI host; Xcode projekat se generiše XcodeGen-om iz `project.yml` | iOS 16+ |

Zajednički kod je u paketima `com.vrticconnect.core.*` (`:shared-core`) i `com.vrticconnect.ui` (`:shared-ui`).

## Preduslovi

- **JDK 21** (`JAVA_HOME` mora da pokazuje na njega; Gradle toolchain je zakucan na 21).
- **Gradle wrapper** (`./gradlew`, verzija 9.3.0) – ne treba instalirati Gradle.
- **Android SDK** (compileSdk 36) – potreban **samo** za `:shared-ui` i `:androidApp`.
- **macOS + Xcode 16 + XcodeGen** – potrebni **samo** za iOS build.
- Internet pri prvom build-u (Gradle preuzima zavisnosti).

## Kako radi uslovno uključivanje Android modula

`settings.gradle.kts` uvek uključuje `:shared-core`. Moduli `:shared-ui` i `:androidApp` se uključuju
**samo** ako se pronađe Android SDK, redom:

1. promenljiva okruženja `ANDROID_HOME`,
2. promenljiva okruženja `ANDROID_SDK_ROOT`,
3. `sdk.dir` u `apps/mobile/local.properties` (fajl je u `.gitignore`).

Ako SDK nije pronađen, Gradle ispisuje upozorenje:

```
WARNING: Android SDK not found — :shared-ui and :androidApp are skipped; :shared-core still builds and tests.
```

i build nastavlja samo sa `:shared-core`. Tako CI ili mašina bez Android SDK-a (npr. Windows bez Android
Studija ili iOS-only Mac) i dalje može da pokrene unit testove zajedničke logike.

Napomena: na Windows/Linux hostu iOS targeti (`iosArm64`/`iosSimulatorArm64`) su onemogućeni – kompajliraju
se samo na macOS-u. Kotlin plugin može da ispiše upozorenje o tome ako se zatraži neki iOS task; `jvmTest`
prolazi bez toga i to je očekivano.

## Komande

Sve komande se pokreću iz `apps/mobile/`.

```bash
# unit testovi zajedničke logike (radi na bilo kom hostu, bez Android SDK-a)
./gradlew :shared-core:jvmTest

# Android debug APK (zahteva Android SDK; inače modul nije ni uključen)
./gradlew :androidApp:assembleDebug

# iOS (samo macOS): generisanje Xcode projekta pa otvaranje
cd iosApp && xcodegen generate && open iosApp.xcodeproj
```

Xcode projekat u fazi „Compile Kotlin Framework“ poziva `./gradlew :shared-ui:embedAndSignAppleFrameworkForXcode`,
koji gradi statički framework `SharedUi` i ubacuje ga u aplikaciju. Podesi `DEVELOPMENT_TEAM` lokalno
(ne komituje se).

Lokalni backend: Android emulator koristi `http://10.0.2.2:8080/` (cleartext je dozvoljen samo u
debug build-u kroz `androidApp/src/debug/res/xml/network_security_config.xml`), iOS simulator koristi
`http://localhost:8080/` (`NSAllowsLocalNetworking`).

## Zakucane verzije (`gradle/libs.versions.toml`)

Kotlin 2.3.21 · Compose Multiplatform 1.12.0 · Compose compiler plugin 2.3.21 · AGP 8.13.2 · Gradle 9.3.0 ·
kotlinx-serialization 1.11.0 · kotlinx-coroutines 1.11.0 · Ktor 3.5.2 · JDK 21 · compileSdk/targetSdk 36 · minSdk 26.
Ne menjati bez dokumentovanog razloga (proverena kompatibilnost).

## Šta je placeholder (namerno nedovršeno)

- **`InMemorySecureTokenStore`** – tokeni žive samo u memoriji procesa. *NIJE ZA PRODUKCIJU.* Keychain (iOS) i
  Keystore (Android) actual implementacije dolaze u EPIC 02. Ništa se ne upisuje u preferences/fajlove/logove.
- **`AuthCoordinator`** – ima oblik single-flight refresh-a (`Mutex`), ali ne izdaje tokene; backend vraća 501
  za `auth/login` i `auth/refresh`, što se mapira u `ApiProblem` (`isNotImplemented`).
- **Dugme „Prijava“** u UI-ju je onemogućeno i označeno sa „Još nije implementirano“.
- **Stringovi** su u Kotlin katalogu (`AppStrings`) umesto u platformskim resursima, da bi isti tekst išao na
  obe platforme i da bi test mogao da proveri kompletnost sva tri jezika. Bez hardkodovanog teksta u UI-ju.
- **Bez offline sloja** (SQLDelight, red pending komandi) – dolazi u posebnom epicu prema §22 brief-a.

## Platformski rad planiran za kasnije epike

Keychain/Keystore (sigurno skladište tokena), push notifikacije, kamera/foto, sistemske dozvole,
crash reporting adapter i lifecycle integracija su **platformski** delovi i eksplicitno nisu deo ovog
skeleta. Ne tvrdi se da je sav kod deljen.

## Test pokrivenost u skeletu (`:shared-core:jvmTest`)

- `DailyCountersTest` – invarijanta `expected = present + departed + absent + notArrived` (iscrpno po svim
  kombinacijama ulaza), `physicallyPresent = present + unscheduledPresent`, neplanirano prisutna deca,
  odsustvo za prisutno dete ne radi check-out.
- `ParseProblemTest` – parsiranje `application/problem+json`, fallback za prazan/ne-JSON body, nikad ne baca.
- `ApiClientTest` – `HealthApi` (OK / greška / transport), `DefaultAuthApi` mapira 501 u `ApiProblem` (Ktor MockEngine).
- `AppStringsTest` – svaki ključ ima sva tri jezika; ćirilična lokala koristi ćirilicu, latinična ne.
- `AuthCoordinatorTest` – početno `Unauthenticated`, login sa 501 ne izdaje tokene, redigovan `toString()` tokena.
