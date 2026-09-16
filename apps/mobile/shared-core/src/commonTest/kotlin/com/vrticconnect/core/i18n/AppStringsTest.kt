package com.vrticconnect.core.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AppStringsTest {

    @Test
    fun everyKeyHasAllThreeLocales() {
        val missing = AppStrings.missingTranslations()
        assertTrue(missing.isEmpty(), "Missing translations: $missing")
    }

    @Test
    fun noKeyFallsBackToItsName() {
        for (key in StringKey.entries) {
            for (locale in AppLocale.entries) {
                assertNotEquals(key.name, AppStrings.get(key, locale), "$key/$locale falls back to key name")
            }
        }
    }

    @Test
    fun cyrillicLocaleUsesCyrillicScript() {
        val cyrillic = Regex("[\\u0400-\\u04FF]")
        val exempt = setOf(StringKey.APP_NAME) // brand name is not transliterated
        for (key in StringKey.entries - exempt) {
            val value = AppStrings.get(key, AppLocale.SR_CYRL)
            assertTrue(cyrillic.containsMatchIn(value), "$key sr-Cyrl value '$value' contains no Cyrillic")
        }
    }

    @Test
    fun latinLocaleDoesNotUseCyrillicScript() {
        val cyrillic = Regex("[\\u0400-\\u04FF]")
        for (key in StringKey.entries) {
            val value = AppStrings.get(key, AppLocale.SR_LATN)
            assertTrue(!cyrillic.containsMatchIn(value), "$key sr-Latn value '$value' contains Cyrillic")
        }
    }

    @Test
    fun boundAccessorAndTagLookupWork() {
        assertEquals("Today", AppStrings.forLocale(AppLocale.EN)[StringKey.TODAY])
        assertEquals(AppLocale.SR_CYRL, AppLocale.fromTag("sr-cyrl"))
        assertEquals(AppLocale.DEFAULT, AppLocale.fromTag("de"))
    }
}
