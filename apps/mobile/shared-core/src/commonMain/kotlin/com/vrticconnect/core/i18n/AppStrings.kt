package com.vrticconnect.core.i18n

/** Locales supported from day one (REQUIREMENTS_BRIEF §24). BCP-47 tags. */
enum class AppLocale(val tag: String, val nativeName: String) {
    SR_LATN("sr-Latn", "Srpski"),
    SR_CYRL("sr-Cyrl", "Српски"),
    EN("en", "English");

    companion object {
        val DEFAULT: AppLocale = SR_LATN
        fun fromTag(tag: String?): AppLocale =
            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: DEFAULT
    }
}

/** Every user-visible string in the app. UI code must reference keys, never literals. */
enum class StringKey {
    APP_NAME,
    TODAY,
    EXPECTED,
    PRESENT,
    DEPARTED,
    ABSENT,
    NOT_ARRIVED,
    LATE,
    UNSCHEDULED_PRESENT,
    CHECK_IN,
    CHECK_OUT,
    PENDING_SYNC,
    CONFLICT,
    LOGIN,
    EMAIL,
    PASSWORD,
    NOT_IMPLEMENTED_YET,
    API_UNAVAILABLE,
    API_AVAILABLE,
    CHECK_API,
    CHECKING,
    LANGUAGE,
}

/**
 * Shared string catalogue. Kept as a plain Kotlin table (instead of platform resource files) so
 * the same texts are used on Android, iOS and in unit tests, and so completeness can be asserted.
 */
object AppStrings {
    private fun entry(key: StringKey, srLatn: String, srCyrl: String, en: String) =
        key to mapOf(AppLocale.SR_LATN to srLatn, AppLocale.SR_CYRL to srCyrl, AppLocale.EN to en)

    private val catalogue: Map<StringKey, Map<AppLocale, String>> = mapOf(
        entry(StringKey.APP_NAME, "Vrtić Connect", "Vrtić Connect", "Vrtić Connect"),
        entry(StringKey.TODAY, "Danas", "Данас", "Today"),
        entry(StringKey.EXPECTED, "Očekivano", "Очекивано", "Expected"),
        entry(StringKey.PRESENT, "Prisutno", "Присутно", "Present"),
        entry(StringKey.DEPARTED, "Otišlo", "Отишло", "Departed"),
        entry(StringKey.ABSENT, "Odsutno", "Одсутно", "Absent"),
        entry(StringKey.NOT_ARRIVED, "Nije stiglo", "Није стигло", "Not arrived"),
        entry(StringKey.LATE, "Kasni", "Касни", "Late"),
        entry(StringKey.UNSCHEDULED_PRESENT, "Neplanirano prisutno", "Непланирано присутно", "Unscheduled present"),
        entry(StringKey.CHECK_IN, "Prijavi dolazak", "Пријави долазак", "Check in"),
        entry(StringKey.CHECK_OUT, "Prijavi odlazak", "Пријави одлазак", "Check out"),
        entry(StringKey.PENDING_SYNC, "Čeka sinhronizaciju", "Чека синхронизацију", "Pending sync"),
        entry(StringKey.CONFLICT, "Konflikt", "Конфликт", "Conflict"),
        entry(StringKey.LOGIN, "Prijava", "Пријава", "Log in"),
        entry(StringKey.EMAIL, "Imejl", "Имејл", "Email"),
        entry(StringKey.PASSWORD, "Lozinka", "Лозинка", "Password"),
        entry(StringKey.NOT_IMPLEMENTED_YET, "Još nije implementirano", "Још није имплементирано", "Not implemented yet"),
        entry(StringKey.API_UNAVAILABLE, "API nije dostupan", "API није доступан", "API unavailable"),
        entry(StringKey.API_AVAILABLE, "API je dostupan", "API је доступан", "API available"),
        entry(StringKey.CHECK_API, "Proveri API", "Провери API", "Check API"),
        entry(StringKey.CHECKING, "Provera…", "Провера…", "Checking…"),
        entry(StringKey.LANGUAGE, "Jezik", "Језик", "Language"),
    )

    /** Returns the translation, falling back to English and finally to the key name. */
    fun get(key: StringKey, locale: AppLocale): String {
        val translations = catalogue[key]
        return translations?.get(locale)
            ?: translations?.get(AppLocale.EN)
            ?: key.name
    }

    /** Bound accessor for a single locale: `strings[StringKey.TODAY]`. */
    fun forLocale(locale: AppLocale): Strings = Strings(locale)

    /** All (key, locale) pairs that have no translation — used by the completeness test. */
    fun missingTranslations(): List<Pair<StringKey, AppLocale>> =
        StringKey.entries.flatMap { key ->
            AppLocale.entries.mapNotNull { locale ->
                val value = catalogue[key]?.get(locale)
                if (value.isNullOrBlank()) key to locale else null
            }
        }
}

class Strings(val locale: AppLocale) {
    operator fun get(key: StringKey): String = AppStrings.get(key, locale)
}
