package com.vrticconnect.modules.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Opaque token primitives (EPIC 02 builds on these):
 *  - tokens are 32 random bytes, base64url without padding (43 chars), prefixed by kind for log-safety;
 *  - only sha256(token) is ever stored (users can't be impersonated from a DB dump);
 *  - lookups compare the hash, never the raw token.
 */
object Tokens {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    enum class Kind(val prefix: String) { ACCESS("vca_"), REFRESH("vcr_"), RESET("vcp_"), VERIFY("vce_"), INVITE("vci_") }

    fun generate(kind: Kind): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return kind.prefix + encoder.encodeToString(bytes)
    }

    fun sha256(token: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII))

    /** Redacted representation safe for logs and error messages. */
    fun redact(token: String): String = if (token.length <= 8) "***" else token.take(4) + "…" + token.takeLast(2)
}
