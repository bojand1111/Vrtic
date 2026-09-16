package com.vrticconnect.modules.auth

import com.vrticconnect.config.Argon2Config
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Password hashing contract. Implementations must be slow, salted and produce a self-describing
 * encoded string so parameters can be upgraded over time (`needsRehash`).
 */
interface PasswordHasher {
    fun hash(password: CharArray): String
    fun verify(password: CharArray, encoded: String): Boolean
    fun needsRehash(encoded: String): Boolean
}

/**
 * Argon2id (RFC 9106) via Bouncy Castle, PHC string format:
 *   $argon2id$v=19$m=<KiB>,t=<iterations>,p=<parallelism>$<salt b64>$<hash b64>
 * Salt: 16 random bytes per password. Output: 32 bytes. Comparison is constant-time.
 */
class Argon2idPasswordHasher(
    private val params: Argon2Config,
    private val random: SecureRandom = SecureRandom(),
) : PasswordHasher {

    override fun hash(password: CharArray): String {
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val out = compute(password, salt, params.memoryKib, params.iterations, params.parallelism)
        return "\$argon2id\$v=19\$m=${params.memoryKib},t=${params.iterations},p=${params.parallelism}\$" +
            "${b64.encodeToString(salt)}\$${b64.encodeToString(out)}"
    }

    override fun verify(password: CharArray, encoded: String): Boolean {
        val parsed = Parsed.parse(encoded) ?: return false
        val candidate = compute(password, parsed.salt, parsed.memoryKib, parsed.iterations, parsed.parallelism)
        return MessageDigest.isEqual(candidate, parsed.hash)
    }

    override fun needsRehash(encoded: String): Boolean {
        val parsed = Parsed.parse(encoded) ?: return true
        return parsed.memoryKib != params.memoryKib ||
            parsed.iterations != params.iterations ||
            parsed.parallelism != params.parallelism
    }

    private fun compute(password: CharArray, salt: ByteArray, memoryKib: Int, iterations: Int, parallelism: Int): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build(),
        )
        val out = ByteArray(HASH_LENGTH)
        generator.generateBytes(password, out)
        return out
    }

    private class Parsed(val memoryKib: Int, val iterations: Int, val parallelism: Int, val salt: ByteArray, val hash: ByteArray) {
        companion object {
            private val regex = Regex(
                """^\${'$'}argon2id\${'$'}v=19\${'$'}m=(\d+),t=(\d+),p=(\d+)\${'$'}([A-Za-z0-9+/]+)\${'$'}([A-Za-z0-9+/]+)$""",
            )
            fun parse(encoded: String): Parsed? {
                val m = regex.matchEntire(encoded) ?: return null
                val (mem, t, p, salt, hash) = m.destructured
                return runCatching {
                    Parsed(mem.toInt(), t.toInt(), p.toInt(), b64d.decode(salt), b64d.decode(hash))
                }.getOrNull()
            }
        }
    }

    private companion object {
        const val SALT_LENGTH = 16
        const val HASH_LENGTH = 32
        val b64: Base64.Encoder = Base64.getEncoder().withoutPadding()
        val b64d: Base64.Decoder = Base64.getDecoder()
    }
}
