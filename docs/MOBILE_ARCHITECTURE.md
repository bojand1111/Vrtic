# Mobilna arhitektura – Vrtić Connect (apps/mobile)

> Status: projektni dokument (P0). Izveden iz `docs/requirements/REQUIREMENTS_BRIEF.md`
> (odeljci 4, 7, 11, 14, 22, 24, 25, 27) i modela u `docs/database/schema.sql`.
> Ništa u ovom dokumentu ne tvrdi da je build ili test izvršen; vidi odeljak 17.
> Kod, nazivi modula i identifikatori su na engleskom; dokumentacija na srpskom (latinica).

---

## 1. Ciljevi i granice

Mobilna aplikacija služi dvema grupama korisnika u P0 pilotu:

- **Vaspitač (TEACHER)** – ekran „Danas” (dnevni pregled prisutnosti), check-in/check-out, označavanje
  odsustva, pregled rasporeda dodeljenih grupa; mora raditi i bez mreže (offline P0).
- **Roditelj/staratelj (PARENT)** – izbor deteta, nedeljni raspored, prijava odsustva, obaveštenja.

Fiksne tehničke odluke (nisu predmet ponovnog odlučivanja):

| Oblast | Odluka |
|---|---|
| Jezik / runtime | Kotlin 2.3.21, Kotlin Multiplatform |
| UI | Compose Multiplatform 1.12.0 (Android + iOS) |
| Android build | AGP 8.13.2, odvojen Gradle build od backend-a (`apps/mobile`) |
| Mreža | Ktor Client 3.5.2, kotlinx.serialization 1.11.0 |
| Konkurentnost | kotlinx.coroutines 1.11.0, `StateFlow` |
| Lokalna baza | SQLDelight 2.x – uvodi se tek sa offline funkcionalnošću (EPIC 08) |
| Slike | Coil 3 – uvodi se tek sa galerijom (EPIC 15) |
| DI | constructor injection; bez DI framework-a u P0 |
| iOS host | SwiftUI aplikacija generisana XcodeGen-om (`project.yml`) |

Šta se **ne** radi u P0: galerija/fotografije offline, zdravstveni podaci offline, WebSocket,
Google/Apple prijava, QR/NFC.

---

## 2. Struktura modula

```
apps/mobile/
├── settings.gradle.kts
├── build.gradle.kts                # samo plugin management + verzije (libs.versions.toml)
├── gradle/libs.versions.toml
├── shared-core/                    # KMP: jvm + iosArm64 + iosSimulatorArm64 (Android koristi jvm artefakt)
│   └── src/
│       ├── commonMain/kotlin/rs/vrticconnect/core/
│       │   ├── api/                # ApiClient, endpoint funkcije, ProblemDetails, AuthCoordinator
│       │   ├── dto/                # @Serializable DTO-i (1:1 sa OpenAPI šemama)
│       │   ├── domain/             # poslovna pravila: AttendanceCounters, ScheduleResolver, validacija
│       │   ├── i18n/               # Strings katalog, Locale, formatiranje datuma
│       │   ├── time/               # OrgClock, DateTimeRules (org tz vs. device tz)
│       │   ├── offline/            # (od EPIC 08) CommandQueue, SyncEngine, Snapshot modeli
│       │   └── platform/           # interfejsi: SecureStorage, CrashReporter, ConnectivityMonitor, PushTokenProvider
│       ├── commonTest/             # testovi pravila (brojači, validacija, konflikt-logika)
│       ├── jvmMain/ jvmTest/       # JVM actual-i (in-memory placeholder-i), CI bez Android SDK-a
│       ├── androidMain/            # Keystore SecureStorage, FCM token provider
│       └── iosMain/                # Keychain SecureStorage, APNs token provider
├── shared-ui/                      # KMP: android + ios (Compose Multiplatform)
│   └── src/
│       ├── commonMain/kotlin/rs/vrticconnect/ui/
│       │   ├── App.kt              # App() composable – koren
│       │   ├── navigation/         # typed rute (sealed), NavHost
│       │   ├── theme/
│       │   ├── components/         # zajedničke komponente (LargeTapButton, PendingBadge, ConflictBadge)
│       │   ├── screens/            # po feature-u: today/, schedule/, absence/, announcements/, auth/
│       │   └── viewmodel/          # ScreenViewModel bazna klasa, UiState klase
│       ├── androidMain/            # Android-specifični composable-i (permission launcher, photo picker)
│       └── iosMain/                # MainViewController(), iOS-specifični adapteri
├── androidApp/                     # Android host: Application, MainActivity, FCM service, DI graf
└── iosApp/                         # SwiftUI host: project.yml (XcodeGen), AppDelegate, APNs, Keychain bridge
```

### 2.1 Zašto baš ova podela

- **`shared-core` ima JVM target.** Sve što je „čista logika” (DTO, validacija, brojači,
  i18n katalog, API klijent, sync algoritam) testira se `./gradlew :shared-core:jvmTest` na bilo kom
  CI runner-u, bez Android SDK-a i bez macOS-a. To je najjeftiniji i najbrži feedback loop.
- **`shared-ui` nema JVM target** (samo android + ios) jer Compose Multiplatform UI ne vredi testirati
  na JVM-u za ovaj projekat u P0; view modeli su tanki i delegiraju logiku u `shared-core`.
- **`androidApp` / `iosApp` su tanki host-ovi**: sastavljaju graf zavisnosti (DI), registruju
  push servise, crash reporting i sistemske dozvole, i pozivaju `App()`.
- `shared-core` **ne zavisi** od `shared-ui`; `shared-ui` zavisi od `shared-core`. Obrnuta zavisnost
  je zabranjena (Gradle konfiguracija to sprečava jer `shared-core` nema Compose plugin).

### 2.2 Šta se deli, a šta ne (obavezno pošteno)

Ne deli se sav kod. Tabela je izvor istine za granicu:

| Oblast | Zajedničko (`commonMain`) | Platformsko (`androidMain` / `iosMain` / host) |
|---|---|---|
| DTO, serializacija, validacija | da | – |
| Poslovna pravila (brojači, prioritet rasporeda, konflikt) | da | – |
| API klijent, refresh koordinacija | da (Ktor common) | engine: OkHttp (Android), Darwin (iOS) |
| Sigurno skladište tokena | interfejs `SecureStorage` | **Android Keystore** (AES-GCM ključ u Keystore-u, podaci u privatnom fajlu), **iOS Keychain** (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`) |
| Push | interfejs `PushTokenProvider`, obrada payload-a | **FCM** (`FirebaseMessagingService`), **APNs** (`UNUserNotificationCenter`, `didRegisterForRemoteNotifications`) |
| Sistemske dozvole (notifikacije, kamera) | interfejs `PermissionGateway` | Android runtime permissions / iOS authorization API-ji |
| Kamera / foto picker | interfejs `MediaPicker` (P1) | `PickVisualMedia` (Android) / `PHPickerViewController` (iOS) |
| Lifecycle (foreground/background, mreža) | interfejs `AppLifecycle`, `ConnectivityMonitor` | `ProcessLifecycleOwner` + `ConnectivityManager` / `UIApplication` notifikacije + `NWPathMonitor` |
| Crash reporting | interfejs `CrashReporter` | adapter ka izabranom alatu (odeljak 16); nikada PII |
| Šifrovana lokalna baza | SQLDelight šema i upiti | driver: `AndroidSqliteDriver` (+ SQLCipher) / `NativeSqliteDriver` (+ SQLCipher) |
| UI (Compose) | ekrani, navigacija, tema | host prozor, status bar, safe-area, back gesture |
| Deep link / notification tap | rutiranje | registracija intent filtera / Universal Links |

Pravilo: platformski kod se piše **iza interfejsa deklarisanog u `shared-core`** (`expect`/`actual`
ili običan interfejs + platformska implementacija). View modeli i ekrani ne smeju importovati
`android.*` ni `platform.*` pakete.

---

## 3. Dependency injection

- **Constructor injection, bez framework-a u P0.** Svaka klasa deklariše zavisnosti kroz konstruktor.
- Sastavljanje grafa radi jedan objekat po host-u: `AppGraph` (`androidApp`) i `IosAppGraph`
  (`iosApp`, preko Kotlin klase u `shared-ui/iosMain`). Graf je ručno napisan Kotlin kod:

```kotlin
class AppGraph(
    platform: PlatformServices,          // SecureStorage, CrashReporter, ConnectivityMonitor, PushTokenProvider
    config: AppConfig,                   // baseUrl, appVersion, buildType, defaultLocale
) {
    val strings = StringCatalog(config.defaultLocale)
    val tokenStore = TokenStore(platform.secureStorage)
    val apiClient = ApiClient(config.baseUrl, tokenStore, platform.crashReporter)
    val authCoordinator = AuthCoordinator(apiClient.authApi, tokenStore)
    val attendanceRepository = AttendanceRepository(apiClient /*, localDb od EPIC 08 */)
    fun todayViewModel(groupId: GroupId, orgTimeZone: TimeZone) =
        TodayViewModel(attendanceRepository, OrgClock(orgTimeZone), strings)
}
```

- Scoping: singleton-i žive u `AppGraph`; view modeli se kreiraju po ekranu (factory funkcije u grafu).
- Kada graf preraste ~40 klasa ili se pojavi potreba za scope-ovima po tenant-u, razmatra se Koin
  (jedini KMP-native kandidat); to je odluka za ADR, ne P0.

---

## 4. Upravljanje stanjem

- **Unidirekcioni tok podataka (UDF):** `UiEvent → ViewModel → StateFlow<UiState> → Composable`.
- Svaki ekran ima **jednu immutable `data class` stanja** i **sealed `UiEvent`**. Nema mutable
  polja u composable-u osim lokalnog UI stanja (scroll, focus).
- Bazna klasa (u `shared-ui`):

```kotlin
abstract class ScreenViewModel<S : Any, E : Any>(initial: S) {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<S> = _state.asStateFlow()
    protected fun update(reduce: (S) -> S) = _state.update(reduce)
    abstract fun onEvent(event: E)
    open fun clear() = scope.cancel()
}
```

- Jednokratni efekti (navigacija, snackbar) idu kroz `Channel<UiEffect>` konzumiran u composable-u
  (ne kroz `UiState`).
- Životni vek view modela vezuje se za navigacioni entry (Android: `ViewModelStoreOwner` adapter;
  iOS: čuvanje u navigacionom stacku). Rotacija ekrana ne sme resetovati stanje.
- Primer stanja ekrana „Danas”:

```kotlin
data class TodayUiState(
    val date: LocalDate,
    val group: GroupSummary?,
    val rows: List<TodayRow>,                 // ime, očekivano vreme, status, pending, conflict
    val counters: AttendanceCounters,         // iz shared-core, ista formula kao backend
    val isLoading: Boolean = false,
    val isOffline: Boolean = false,
    val snapshotAgeMinutes: Int? = null,
    val pendingCount: Int = 0,
    val conflictCount: Int = 0,
    val error: UiError? = null,
)
```

---

## 5. Mreža (Ktor Client)

### 5.1 Konfiguracija

- Jedan `HttpClient` po aplikaciji; engine se bira u platformskom kodu (OkHttp / Darwin).
- Plugin-i: `ContentNegotiation(json)`, `HttpTimeout` (connect 10 s, request 30 s),
  `Logging` **samo u debug build-u** i sa sanitizerom headera (`Authorization`, `Cookie`,
  `Set-Cookie` se nikad ne loguju; body se ne loguje za `/auth/*`).
- `defaultRequest { url(baseUrl); header("Accept", "application/json, application/problem+json") }`.
- Svaki zahtev nosi `X-Request-Id` (UUID) radi korelacije sa backend logovima.
- Base URL je deo build konfiguracije (`BuildKonfig` / `Info.plist`), nikad hardkodovan u kodu.
- `ApiClient` je tanak sloj nad Ktor-om koji vraća `ApiResult<T>` (odeljak 6) i nikada ne baca
  izuzetke za HTTP greške.

### 5.2 Tokeni i refresh (single-flight)

Tokeni (opaque access 10 min; refresh 30 d apsolutno / 7 d idle; rotacija; detekcija ponovne
upotrebe) su definisani u brief-u §7. Mobilna strana:

- **Tokeni se čuvaju isključivo u `SecureStorage`** (Keystore/Keychain). Nikad u
  `SharedPreferences`/`NSUserDefaults`, nikad u logovima, nikad u crash izveštajima, nikad u URL-u.
- `TokenStore` drži in-memory kopiju za brzinu; pri hladnom startu čita iz `SecureStorage`.
- `AuthCoordinator` implementira refresh kao **single-flight** – jedan `Mutex` štiti odluku „da li
  je refresh već u toku”, a svi konkurentni pozivi čekaju isti `Deferred`:

```kotlin
class AuthCoordinator(private val api: AuthApi, private val store: TokenStore, scope: CoroutineScope) {
    private val mutex = Mutex()
    private var inFlight: Deferred<RefreshOutcome>? = null
    private val refreshScope = scope

    suspend fun refresh(failedAccessToken: String): RefreshOutcome {
        val job = mutex.withLock {
            // token je već rotiran dok smo čekali lock -> ne pokreći novi refresh
            if (store.accessToken() != failedAccessToken) return RefreshOutcome.AlreadyRefreshed
            inFlight?.takeIf { it.isActive } ?: refreshScope.async { doRefresh() }.also { inFlight = it }
        }
        return job.await()
    }

    private suspend fun doRefresh(): RefreshOutcome { /* POST /auth/refresh, atomična zamena para tokena */ }
}
```

- Tok pri `401`: zahtev → 401 → `AuthCoordinator.refresh(tokenKojiJePao)` → uspeh: ponovi zahtev
  jednom; neuspeh sa `invalid_grant` / reuse-detected: obriši oba tokena, emituj `SessionRevoked`,
  navigiraj na login (i obriši lokalnu bazu za tog korisnika, odeljak 10).
- Refresh nikad ne šalje više paralelnih zahteva; prekid mreže u toku refresh-a ostavlja stari
  refresh token netaknut (server rotira atomično, klijent zamenjuje tek po uspešnom odgovoru).
- Ktor `Auth` plugin se **ne** koristi za bearer jer njegov `refreshTokens` ne garantuje ovakvo
  single-flight ponašanje; umesto toga koristi se sopstveni `HttpSend` interceptor koji poziva
  `AuthCoordinator`.

---

## 6. Mapiranje grešaka (problem+json)

Backend vraća `application/problem+json` (brief §21). Klijent parsira u:

```kotlin
@Serializable
data class ProblemDetails(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
    val code: String? = null,                   // stabilan aplikativni kod, npr. "attendance.version_conflict"
    val errors: List<FieldError> = emptyList(), // 422
    val currentVersion: Int? = null,            // 409 kod optimistic concurrency
)

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T, val etag: String?) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

sealed interface ApiError {
    data object Network : ApiError                                // IOException, timeout – kandidat za retry/offline
    data object Unauthorized : ApiError                           // 401 posle neuspešnog refresh-a
    data class Forbidden(val problem: ProblemDetails) : ApiError
    data class NotFound(val problem: ProblemDetails) : ApiError   // 404 se ne razlikuje od "nemaš pravo"
    data class Conflict(val problem: ProblemDetails) : ApiError   // 409 (version / state)
    data class Validation(val problem: ProblemDetails) : ApiError // 422
    data class RateLimited(val retryAfterSeconds: Int?) : ApiError // 429 + Retry-After
    data class NotImplemented(val problem: ProblemDetails) : ApiError // 501 – skeleton auth
    data class Server(val status: Int, val problem: ProblemDetails?) : ApiError
}
```

- Mapiranje u korisničku poruku radi `ErrorMessages(strings)` u `shared-core/i18n`: prvo po
  `code`, pa po `status`; `detail` sa servera se **ne prikazuje sirovo** korisniku (može sadržati
  tehničke detalje), već se loguje sanitizovano.
- `ProblemDetails.detail` i `instance` se ne šalju u crash reporter.

---

## 7. Tipizovana navigacija

- Rute su `@Serializable sealed interface Route` u `shared-ui/navigation`; parametri su tipizovani
  (`ChildId`, `LocalDate`), ne slobodni stringovi.
- Dva grafa prema ulozi članstva (role je na članstvu, ne na korisniku – brief §3): `TeacherGraph`
  i `ParentGraph`; korisnik sa više članstava bira organizaciju na `OrganizationPicker` ekranu.

```kotlin
@Serializable sealed interface Route {
    @Serializable data object Login : Route
    @Serializable data object OrganizationPicker : Route
    @Serializable data class TeacherToday(val groupId: String, val date: String) : Route
    @Serializable data class ChildDetails(val childId: String) : Route
    @Serializable data class ParentHome(val childId: String?) : Route
    @Serializable data class WeekSchedule(val childId: String, val weekStart: String) : Route
    @Serializable data class ReportAbsence(val childId: String) : Route
    @Serializable data object Announcements : Route
    @Serializable data class AnnouncementDetails(val announcementId: String) : Route
}
```

- Deep link iz push notifikacije nosi samo opaque `notificationId`; aplikacija prvo dohvati
  notifikaciju sa servera (provera prava), pa tek onda navigira. Push payload nikad ne sadrži ime
  deteta ni sadržaj poruke (brief §14).
- Promena tenant-a (organizacije) **resetuje ceo back stack** i sve keš/repozitorijum instance
  vezane za prethodni tenant.

---

## 8. Lokalizacija

- Tri lokala od početka: `sr-Latn`, `sr-Cyrl`, `en` (brief §24); kasnije hr, bs, sl, mk, de, sv.
- **Zajednički katalog u `shared-core/i18n`**: jedan izvor (`strings/<locale>.json` u resursima
  ili Kotlin mape generisane u build-u), API `Strings.get(key, vararg args)`, plural pravila po lokalu.
  Ne koriste se Android `strings.xml` ni iOS `Localizable.strings` kao izvor istine – oni se, ako
  su potrebni sistemu (npr. opisi dozvola u `Info.plist`), generišu iz zajedničkog kataloga.
- **Bez hardkodovanog teksta** u composable-ima; lint pravilo (detekt custom rule ili jednostavan
  CI grep za `Text("`) sprečava regresiju.
- **Bez automatske transliteracije imena.** Imena dece, roditelja, grupa i vrtića prikazuju se
  onako kako su unesena; ćirilični UI ne transliteruje latinično ime i obrnuto. Ćirilični katalog
  sistemskih stringova održava prevodilac ručno.
- Izbor jezika: podešavanje u aplikaciji, podrazumevano iz sistema ako je podržan, inače `sr-Latn`.
- Formatiranje datuma/vremena i brojeva: preko `kotlinx-datetime` + sopstveni formatteri po lokalu
  (bez zavisnosti od platformskih `DateFormat` klasa radi konzistentnosti Android/iOS).

---

## 9. Datum i vreme

Pravila (brief §24):

| Podatak | Tip | Vremenska zona |
|---|---|---|
| Događaj (check-in, poruka, audit) | `Instant` (UTC) | prikaz: vidi ispod |
| Datum rođenja | `LocalDate` | nema zone |
| Raspored, prisustvo, „danas” | `LocalDate` + `LocalTime` | **zona organizacije** (npr. `Europe/Belgrade`), IANA id stiže sa `OrganizationSummary` |

- `OrgClock(orgTimeZone)` je jedini izvor za „koji je danas datum” na ekranu „Danas” i za računanje
  „kasni”. Uređaj vaspitača na putu u drugoj zoni i dalje vidi dan vrtića.
- **Prisustvo** se prikazuje u zoni organizacije; `occurredAt` komande se generiše kao `Instant`
  (`Clock.System.now()`), a `attendanceDate` se izračunava iz tog instanta u org zoni.
- **Chat i notifikacije**: vremena se prikazuju u zoni uređaja, uz pravilo: ako se zona uređaja
  razlikuje od zone organizacije, prikazati sufiks sa skraćenicom zone (npr. „14:05 CEST”) da bi
  roditelj u inostranstvu razumeo kontekst.
- DST: sve računice rade preko `TimeZone.toInstant/toLocalDateTime`; nikad ručno dodavanje sati.
- Promena zone organizacije ne menja istoriju – klijent uvek koristi zonu koju server vrati uz
  konkretan dan/plan (`dailyPlan.timeZone`), ne trenutnu.
- Sat na uređaju može biti pogrešan: klijent beleži `serverTimeOffset` iz `Date` headera odgovora i
  pokazuje upozorenje ako je odstupanje > 5 min; `occurredAt` se i dalje šalje kao vreme uređaja
  (server ga čuva kao poslovno vreme uz `recordedAt` sa serverske strane).

---

## 10. Offline P0 – dizajn

Opseg offline-a u P0 (brief §22): **lista dece grupe, dnevni raspored, dnevna prisutnost i red
pending komandi**. Ništa drugo. Zdravstveni podaci i fotografije se **nikada** ne keširaju lokalno.

### 10.1 Algoritam u 10 koraka

1. **Snapshot.** Kada je uređaj online i vaspitač otvori „Danas” za grupu, klijent preuzme snapshot:
   `GET /api/v1/organizations/{organizationId}/groups/{groupId}/attendance/days/{date}` (deca,
   očekivana vremena, status, `version` po detetu, `dailyPlan.timeZone`) + `ETag`. Snapshot se čuva
   lokalno sa `fetchedAt`.
2. **Šifrovana lokalna baza po korisniku i tenant-u.** SQLDelight baza je fajl
   `vc_<userIdHash>_<organizationIdHash>.db`, šifrovan SQLCipher-om; ključ baze je nasumičan 256-bit,
   čuvan u `SecureStorage`. Promena korisnika ili tenant-a otvara drugu bazu; odjava
   (ili opoziv sesije) briše bazu tog korisnika.
3. **Rok važenja 12 h.** Snapshot stariji od 12 h (`now - fetchedAt > 12h`) se **ne prikazuje kao
   važeći**: ekran pokazuje „Podaci su zastareli – povežite se”, akcije su onemogućene.
   Bez snapshota nema offline rada (nema „praznog” dana).
4. **Komanda.** Svaka akcija offline (check-in, check-out, absence-marked, unscheduled-present) je
   red u tabeli `pending_commands`:
   `commandId: UUID (klijent generiše)`, `type`, `childId`, `attendanceDate`,
   `expectedVersion` (verzija projekcije iz snapshota **ili** iz poslednje lokalno primenjene komande),
   `occurredAt: Instant`, `payload`, `createdAt`, `attempts`,
   `state (QUEUED | SENDING | ACCEPTED | REJECTED | CONFLICT)`.
5. **Pending oznaka.** Lokalna projekcija se ažurira optimistički i red deteta nosi vizuelnu oznaku
   `pending` dok server ne potvrdi. Brojači se računaju iz lokalne projekcije, ali UI jasno kaže
   „uključuje N nepotvrđenih”.
6. **Reconnect proverava prava.** Po povratku mreže, pre slanja komandi, klijent poziva
   `GET /api/v1/auth/session` (prolazi kroz refresh ako treba). Ako je sesija opozvana, članstvo
   ukinuto ili dodela grupe istekla (401/403), komande se **ne šalju**, obeležavaju se `REJECTED`
   sa razlogom, korisnik vidi listu, a baza se briše pri odjavi.
7. **Idempotentno slanje.** Komande se šalju redom po `createdAt`, jedna po jedna,
   `POST .../attendance/commands` sa `commandId` u telu i kao `Idempotency-Key` header.
   Ponovno slanje istog `commandId` je bezbedno – server ima `UNIQUE (command_id)` na
   `app.attendance_events` i vraća isti rezultat.
8. **Odgovor servera** (jedan od):
   - `ACCEPTED` – server je primenio komandu; vraća novu `version` i projekciju; lokalna projekcija
     se zamenjuje serverskom.
   - `DUPLICATE` – `commandId` je već primenjen (ranije slanje je prošlo, a odgovor se izgubio);
     tretira se kao `ACCEPTED`.
   - `REJECTED` – poslovna greška (npr. check-out bez otvorene posete, dete više nije u grupi,
     nema prava). Komanda se označava, korisniku se prikazuje razlog, lokalna projekcija se vraća
     na serversku.
   - `CONFLICT` – `expectedVersion` ≠ trenutna verzija na serveru (neko drugi je menjao stanje).
     Komanda se **ne primenjuje automatski**. UI pokazuje konflikt (odeljak 11) sa serverskim stanjem
     i nudi: „Primeni ponovo na trenutno stanje” (kreira **novu** komandu sa novim `commandId` i
     novom `expectedVersion`) ili „Odbaci”. **Nema last-write-wins.**
9. **Uklanjanje tek posle potvrde.** Komanda se briše iz reda samo kada je u stanju `ACCEPTED` ili
   `DUPLICATE`, a i tada se čuva još 24 h kao istorija (`archived_at`) radi dijagnostike.
   `REJECTED`/`CONFLICT` ostaju dok korisnik ne reaguje.
10. **Tombstones.** Ako server u novom snapshotu više ne vraća dete (ispisano, premešteno, povučena
    dodela), lokalni red se označava `tombstone` (ne briše se odmah) da bi se pending komande za to
    dete mogle prikazati kao odbijene sa razumljivim razlogom; tombstone-i se čiste pri sledećem
    uspešnom snapshotu.

### 10.2 Dodatna pravila

- Redosled lokalnih komandi za isto dete se čuva; `expectedVersion` sledeće komande je
  `prethodna.expectedVersion + 1` dok se ne dobije potvrda – ako prva padne kao CONFLICT, sve
  naredne za to dete automatski prelaze u CONFLICT (nikad se ne šalju „preko” neuspešne).
- **Nema offline:** zdravstveni profili, fotografije, poruke, ovlašćene osobe (P0 lista se čita samo online).
- **Opoziv na offline uređaju nije garantovan.** Ako je sesija opozvana dok je uređaj bez mreže,
  lokalni snapshot ostaje vidljiv najviše do isteka 12 h; ne postoji tehnički način da server
  „dohvati” podatke sa uređaja koji nije na mreži. Mitigacije: 12 h TTL, šifrovana baza, brisanje pri
  prvom kontaktu sa serverom, minimalan skup podataka u snapshotu (bez zdravstvenih polja, bez
  kontakata roditelja). Ovo ograničenje mora biti napisano u SECURITY.md i saopšteno vrtiću.
- SQLDelight šema živi u `shared-core/src/commonMain/sqldelight/`; migracije su verzionisane i
  testiraju se u `jvmTest` sa in-memory JDBC driverom.

---

## 11. Ekran „Danas” (vaspitač)

- **Brojači** se računaju u `shared-core/domain/AttendanceCounters.kt`, istom formulom kao backend
  i web (brief §11): `expected = present + departed + absent + notArrived`;
  `unscheduledPresent` se prikazuje zasebno i ulazi u `physicallyPresent = present + unscheduledPresent`.
  Ta funkcija je čista (`fun compute(rows: List<AttendanceRow>): AttendanceCounters`) i pokrivena
  `commonTest`-om istim primerima koje koristi backend test (delimo test vektore kao JSON fajl u
  `docs/database/tests/` ili `shared-core/src/commonTest/resources/`).
- **Lista**: ime i prezime, očekivano vreme dolaska/odlaska, status (`NOT_ARRIVED / CHECKED_IN /
  CHECKED_OUT`), kontekst odsustva (`SICK / VACATION / OTHER`), oznaka „kasni” (očekivano + tolerancija
  vrtića < sada, još nije došao), **brza akcija** (jedno dugme koje menja značenje: „Došao” →
  „Otišao” → „Ponovo došao”), oznaka `pending`, oznaka `conflict`.
- **Velike tap površine**: minimalna visina reda 64 dp, dugme akcije ≥ 56×56 dp, razmak ≥ 8 dp;
  potvrda samo za destruktivne stvari (korekcija), ne za check-in.
- Odsustvo za prisutno dete **ne radi automatski check-out** – UI to eksplicitno kaže.
- Ponovni check-in posle check-out-a otvara novu posetu; check-out bez otvorene posete je
  onemogućen u UI-ju **i** odbijen na serveru.
- Indikatori: traka na vrhu „Offline – podaci od HH:mm” + broj pending komandi; crveni badge za
  konflikte sa ulazom u listu konflikata.
- Filter: grupa (samo dodeljene u periodu važenja), datum (danas ± 7 dana; retroaktivne izmene su
  korekcije sa razlogom i idu samo online).

---

## 12. Ekrani roditelja

- **Child switcher**: gornji chip-ovi sa decom iz potvrđenih guardian veza (server vraća listu;
  klijent ne može dodati dete). Jedno dete – switcher se ne prikazuje.
- **Nedeljni raspored**: 5–7 kolona, po danu: očekivano vreme ili „nema dolaska / neradni dan /
  odsustvo / izmena za dan”, prioritet kao u brief-u §9 (server već računa efektivni plan; klijent
  samo prikazuje `effectiveSource`). Izmena rasporeda pre roka; posle roka oznaka „kasna promena”.
- **Odsustvo**: tip (SICK/VACATION/OTHER), jedan dan ili period, opciona napomena (bez obaveznog
  medicinskog objašnjenja), pregled preklapanja pre slanja, otkazivanje. Slanje je idempotentno
  (`Idempotency-Key`).
- **Obaveštenja**: inbox (in-app), detalji obaveštenja/najave; otvaranje = eksplicitno „pročitano”
  (push nije dokaz čitanja).
- Roditeljski ekrani u P0 **nemaju** offline režim (samo keš poslednjeg odgovora u memoriji radi
  brzine; nestaje pri gašenju aplikacije).

---

## 13. Sigurno skladište i sigurnosna pravila klijenta

- `SecureStorage` interfejs: `put(key, bytes)`, `get(key)`, `remove(key)`, `clear()`.
  - Android: ključ u Android Keystore (AES-256-GCM, bez zahteva za otključavanje jer refresh mora
    raditi u pozadini), podaci u privatnom fajlu aplikacije.
  - iOS: Keychain, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, bez iCloud sinhronizacije.
  - JVM (testovi / skeleton): `InMemorySecureStorage` – **NOT FOR PRODUCTION**, jasno označen.
- Screenshot/recents: ekran „Danas” i profil deteta postavljaju `FLAG_SECURE` (Android) i
  zamagljivanje u app switcher-u (iOS) – podesivo po vrtiću kasnije.
- Logovi: `Logger` u `shared-core` ima nivo i sanitizer; u release build-u se loguje samo `WARN+`,
  bez tela zahteva, bez ID-jeva dece.
- Certificate pinning se **ne** uvodi u P0 (operativni rizik pri rotaciji sertifikata); TLS 1.2+ sa
  sistemskim trust store-om. Odluka za ADR ako pilot zahteva.
- Root/jailbreak detekcija: nije u P0; navesti u SECURITY.md kao poznati rizik.

---

## 14. Push (FCM / APNs)

- Registracija tokena: `PushTokenProvider.currentToken()` → `PUT /api/v1/me/devices/{deviceId}` sa
  platformom i tokenom; ponavlja se pri svakoj promeni tokena i pri prijavi. Odjava briše uređaj.
- Payload je minimalan i opaque: `{ "notificationId": "...", "kind": "ANNOUNCEMENT|ABSENCE|..." }`.
  Bez imena, bez teksta poruke, bez ID-jeva dece (brief §14).
- Push služi za **invalidaciju**: aplikacija po prijemu osvežava inbox/ekran (ako je u prednjem
  planu) ili prikazuje generički naslov iz kataloga („Nova najava u vrtiću”) i po otvaranju dohvata
  sadržaj sa servera uz proveru prava.
- Realtime u pilotu = polling (30–60 s dok je ekran aktivan) + push invalidacija; SSE kasnije;
  WebSocket se ne uvodi bez potrebe. Pri reconnect-u se radi pun resync ekrana, ne inkrementalno.

---

## 15. Strategija testiranja

| Nivo | Modul / source set | Gde se izvršava | Šta pokriva |
|---|---|---|---|
| Pravila i DTO | `shared-core/commonTest` (izvršava se kao `jvmTest`) | svaki CI runner, bez Android SDK | brojači (deljeni JSON vektori sa backend-om), prioritet rasporeda, validacija, mapiranje problem+json, `AuthCoordinator` single-flight (sa `MockEngine`), sync algoritam (ACCEPTED/DUPLICATE/REJECTED/CONFLICT, tombstones), SQLDelight migracije (in-memory JDBC) |
| View modeli | `shared-ui` Android unit test (bez emulatora) | Ubuntu runner sa Android SDK | UDF prelazi stanja, efekti navigacije |
| Android instrumentirani | `androidApp/androidTest` | kasnije (emulator u CI je spor); ručno pre release-a | Keystore storage, FCM handling, deep link |
| iOS | `iosApp` XCTest + `shared-core` iOS testovi | macOS runner (dokumentovani gate) | Keychain storage, build i osnovni smoke |
| Screenshot testovi | – | nije u P0 | – |

- Obavezni scenariji iz brief-a §27 koji pripadaju mobilnom: refresh replay (drugi refresh sa
  istim tokenom → opoziv porodice, klijent ide na login), konkurentne attendance komande
  (CONFLICT put), offline konflikt, opoziv teacher dodele po periodu (403 posle reconnect-a).
- Test dubleri: `MockEngine` za Ktor, `FakeSecureStorage`, `FakeClock` (kontrola „sada” i zone).
- Pravilo: nova poslovna funkcija u `shared-core` bez `commonTest`-a se ne merge-uje.

---

## 16. Release i crash reporting

- **`CrashReporter` adapter** u `shared-core/platform`:
  `recordNonFatal(throwable, context: Map<String, String>)`, `setUserScope(hashedUserId?)`,
  `log(breadcrumb)`. Implementacija po platformi bira konkretan alat (Firebase Crashlytics je
  kandidat jer FCM već uvodi Firebase na Androidu; Sentry je alternativa sa boljom KMP podrškom).
  Izbor alata je ADR; skeleton ima `NoopCrashReporter` i `LoggingCrashReporter`.
- Zabranjeno u crash izveštajima: tokeni, e-mail, imena i ID-jevi dece, tela zahteva, signed URL-ovi.
  Korisnički scope je samo hash ID-ja korisnika.
- Build varijante: `debug` (logovanje, dev backend), `staging` (staging backend, crash reporting
  uključen), `release` (production). Base URL po varijanti.
- Verzionisanje: `versionName` semver, `versionCode` / `CFBundleVersion` iz CI broja build-a.
- Distribucija: Android – Play Console interni test track pa zatvoreno testiranje za pilot vrtiće;
  iOS – TestFlight. Potpisivanje: Android upload key u CI secret storage-u (nikad u repou);
  iOS sertifikati/profili na macOS runner-u preko App Store Connect API ključa.
- Minimalne verzije OS-a: predlog Android 8.0 (API 26) zbog Keystore AES-GCM i `PickVisualMedia`
  backport-a; iOS prema zahtevu Compose Multiplatform 1.12.0 (proveriti pri postavljanju projekta;
  nije provereno u ovom dokumentu).

---

## 17. Šta skeleton sadrži (samo struktura, bez poslovnih funkcija)

Skeleton u `apps/mobile` sadrži **samo**:

1. Gradle projekat sa modulima `shared-core`, `shared-ui`, `androidApp` i `iosApp/project.yml`
   (XcodeGen), verzije u `gradle/libs.versions.toml` prema tabeli u odeljku 1.
2. `App()` composable u `shared-ui` koji prikazuje naziv aplikacije, izbor jezika (tri lokala) i
   „Health” karticu.
3. **String katalog** u `shared-core/i18n` sa istim ključevima za `sr-Latn`, `sr-Cyrl`, `en` i test
   koji proverava da svi lokali imaju iste ključeve.
4. **`ApiClient` stub** sa Ktor klijentom, problem+json parserom i jednim pozivom:
   `GET /health/ready` (prikaz statusa na kartici). Nema drugih endpointa.
5. **`SecureStorage` interfejs** i `InMemorySecureStorage` placeholder označen
   `// NOT FOR PRODUCTION – replaced by Keystore/Keychain implementation in EPIC 02`.
   Keystore/Keychain implementacije **nisu** u skeletonu.
6. `CrashReporter` interfejs sa `NoopCrashReporter`.
7. `commonTest` sa testom brojača (`AttendanceCounters`) i testom kataloga stringova.
8. CI job `mobile-shared-jvm-tests` (`./gradlew :shared-core:jvmTest`) i `android-build`
   (`./gradlew :androidApp:assembleDebug`); iOS job na macOS runner-u je dokumentovan gate koji se
   uključuje kada macOS runner bude dostupan.

Skeleton **ne sadrži** login, tokene, offline bazu, push, navigacione grafove po ulozi ni ekrane
„Danas”/roditelj. Login i refresh na backend-u namerno vraćaju `501` Problem dok EPIC 02 ne bude
implementiran; mobilni skeleton to ne zaobilazi lažnom prijavom.

**Ovaj dokument ne tvrdi da je bilo koji build (Gradle, Android, Xcode) ili test izvršen.**
Status provera se vodi u validacionom izveštaju projekta, ne ovde.

---

## 18. Otvorene odluke (za ADR)

- Izbor crash reporting alata (Crashlytics vs Sentry).
- Uvođenje Koin-a kada ručni graf preraste.
- Certificate pinning i root/jailbreak detekcija.
- Minimalne OS verzije nakon provere zahteva Compose Multiplatform 1.12.0.
- SQLCipher distribucija na iOS-u (CocoaPods vs SPM) pri uvođenju offline-a.
