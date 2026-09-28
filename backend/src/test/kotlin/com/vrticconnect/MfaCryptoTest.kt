package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.config.DataKey
import com.vrticconnect.modules.auth.Base32
import com.vrticconnect.modules.auth.RecoveryCodes
import com.vrticconnect.modules.auth.SecretBox
import com.vrticconnect.modules.auth.Totp
import java.time.Instant
import java.util.Base64
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TOTP against the RFC 6238 SHA1 vectors, base32, envelope encryption and APP_DATA_KEY configuration. */
class MfaCryptoTest {
    private val rfcSecret = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    @Test
    fun `RFC 6238 SHA1 test vectors (last six digits)`() {
        val vectors = mapOf(59L to "287082", 1111111109L to "081804", 1111111111L to "050471", 1234567890L to "005924", 2000000000L to "279037")
        for ((seconds, expected) in vectors) {
            assertEquals(expected, Totp.code(rfcSecret, Totp.step(Instant.ofEpochSecond(seconds))), "T=$seconds")
        }
    }

    @Test
    fun `verify accepts one step of drift and nothing further`() {
        val now = Instant.ofEpochSecond(1_700_000_000)
        val step = Totp.step(now)
        assertEquals(step - 1, Totp.verify(rfcSecret, Totp.code(rfcSecret, step - 1), now))
        assertEquals(step + 1, Totp.verify(rfcSecret, Totp.code(rfcSecret, step + 1), now))
        val far = Totp.code(rfcSecret, step + 3)
        if ((step - 1..step + 1).none { Totp.code(rfcSecret, it) == far }) assertNull(Totp.verify(rfcSecret, far, now))
        assertNull(Totp.verify(rfcSecret, "12345", now))
        assertNull(Totp.verify(rfcSecret, "abcdef", now))
    }

    @Test
    fun `base32 round trip and provisioning uri`() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Base32.encode(rfcSecret))
        assertContentEquals(rfcSecret, Base32.decode("gezd gnbv gy3t qojq gezd gnbv gy3t qojq"))
        val uri = Totp.provisioningUri("Vrtic Connect", "owner+x@example.test", "ABC")
        assertEquals("otpauth://totp/Vrtic%20Connect:owner%2Bx%40example.test?secret=ABC&issuer=Vrtic%20Connect&algorithm=SHA1&digits=6&period=30", uri)
    }

    @Test
    fun `secret box is bound to key and owner`() {
        val box = SecretBox(DataKey.dev())
        val sealed = box.seal(rfcSecret, "user:1".toByteArray())
        assertContentEquals(rfcSecret, box.open(sealed, DataKey.DEV_KEY_ID, "user:1".toByteArray()))
        assertFailsWith<AEADBadTagException> { box.open(sealed, DataKey.DEV_KEY_ID, "user:2".toByteArray()) }
        assertFailsWith<IllegalStateException> { box.open(sealed, "other-key", "user:1".toByteArray()) }
    }

    @Test
    fun `recovery codes are 10 distinct normalized codes`() {
        val codes = RecoveryCodes.generate()
        assertEquals(10, codes.toSet().size)
        assertTrue(codes.all { RecoveryCodes.looksLikeRecoveryCode(it) && it.length == 19 })
        assertContentEquals(RecoveryCodes.hash(codes[0]), RecoveryCodes.hash(codes[0].lowercase().replace("-", " ")))
    }

    @Test
    fun `APP_DATA_KEY is optional in dev and required elsewhere`() {
        assertEquals(DataKey.DEV_KEY_ID, AppConfig.fromEnvironment(mapOf("APP_ENV" to "dev")).requireDataKey().keyId)
        val prod = AppConfig.fromEnvironment(mapOf("APP_ENV" to "production", "APP_DB_OWNER_PASSWORD" to "x", "APP_DB_RUNTIME_PASSWORD" to "y"))
        assertFailsWith<IllegalStateException> { prod.requireDataKey() }
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        assertEquals("k7", AppConfig.fromEnvironment(mapOf("APP_ENV" to "production", "APP_DATA_KEY" to key, "APP_DATA_KEY_ID" to "k7")).requireDataKey().keyId)
        assertFailsWith<IllegalArgumentException> { AppConfig.fromEnvironment(mapOf("APP_ENV" to "production", "APP_DATA_KEY" to "c2hvcnQ=")) }
    }
}
