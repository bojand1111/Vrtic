package com.vrticconnect

import com.vrticconnect.config.Argon2Config
import com.vrticconnect.modules.auth.Argon2idPasswordHasher
import com.vrticconnect.modules.auth.Tokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Argon2idPasswordHasherTest {
    // Small parameters for test speed only; production values come from a server benchmark.
    private val hasher = Argon2idPasswordHasher(Argon2Config(memoryKib = 19_456, iterations = 2, parallelism = 1))

    @Test
    fun `hash verifies and is salted`() {
        val pw = "correct horse battery staple".toCharArray()
        val h1 = hasher.hash(pw)
        val h2 = hasher.hash(pw)
        assertTrue(h1.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
        assertNotEquals(h1, h2, "two hashes of the same password must differ (unique salt)")
        assertTrue(hasher.verify(pw, h1))
        assertTrue(hasher.verify(pw, h2))
        assertFalse(hasher.verify("wrong".toCharArray(), h1))
    }

    @Test
    fun `garbage encoded strings never verify`() {
        assertFalse(hasher.verify("x".toCharArray(), ""))
        assertFalse(hasher.verify("x".toCharArray(), "\$argon2id\$v=19\$m=1,t=1,p=1\$!!\$??"))
        assertFalse(hasher.verify("x".toCharArray(), "plaintext"))
    }

    @Test
    fun `needsRehash detects parameter upgrades`() {
        val old = hasher.hash("pw".toCharArray())
        val stronger = Argon2idPasswordHasher(Argon2Config(memoryKib = 65_536, iterations = 3, parallelism = 1))
        assertFalse(hasher.needsRehash(old))
        assertTrue(stronger.needsRehash(old))
        assertTrue(stronger.verify("pw".toCharArray(), old), "old hashes stay verifiable after a parameter upgrade")
    }

    @Test
    fun `opaque tokens are random, prefixed and hashed consistently`() {
        val a = Tokens.generate(Tokens.Kind.REFRESH)
        val b = Tokens.generate(Tokens.Kind.REFRESH)
        assertTrue(a.startsWith("vcr_") && a.length == 4 + 43)
        assertNotEquals(a, b)
        assertEquals(32, Tokens.sha256(a).size)
        assertTrue(Tokens.sha256(a).contentEquals(Tokens.sha256(a)))
        assertFalse(Tokens.redact(a).contains(a.substring(6, 30)))
        assertTrue(Tokens.isAccessToken(Tokens.generate(Tokens.Kind.ACCESS)))
        assertFalse(Tokens.isAccessToken(a))
        assertTrue(Tokens.isRefreshToken(a))
        assertFalse(Tokens.isRefreshToken(Tokens.generate(Tokens.Kind.ACCESS)))
    }
}
