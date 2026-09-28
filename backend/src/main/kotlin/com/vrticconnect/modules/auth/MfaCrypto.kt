package com.vrticconnect.modules.auth

import com.vrticconnect.config.DataKey
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** RFC 4648 base32 (upper case, no padding): the manual-entry format authenticator apps expect. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.trim().trimEnd('=').uppercase().replace(" ", "")
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val value = ALPHABET.indexOf(ch)
            require(value >= 0) { "invalid base32 character" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}

/**
 * RFC 6238 TOTP with the parameters every mainstream authenticator app uses by default:
 * HMAC-SHA1, 6 digits, 30 second steps. Verification accepts the current step and one step on
 * either side (clock drift); the caller rejects steps it has already accepted (replay).
 */
object Totp {
    const val DIGITS = 6
    const val PERIOD_SECONDS = 30L
    const val ALGORITHM = "SHA1"
    const val SECRET_BYTES = 20
    private const val WINDOW = 1

    fun newSecret(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(SECRET_BYTES).also(random::nextBytes)

    fun step(at: Instant): Long = Math.floorDiv(at.epochSecond, PERIOD_SECONDS)

    /** RFC 4226 HOTP value for [counter], zero-padded to [DIGITS]. */
    fun code(secret: ByteArray, counter: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return (binary % 1_000_000).toString().padStart(DIGITS, '0')
    }

    /** Returns the matching time step, or null. Comparison is constant-time per candidate. */
    fun verify(secret: ByteArray, code: String, at: Instant = Instant.now()): Long? {
        if (!isCodeShape(code)) return null
        val current = step(at)
        var matched: Long? = null
        for (delta in -WINDOW..WINDOW) {
            val candidate = current + delta
            if (MessageDigest.isEqual(code(secret, candidate).toByteArray(), code.toByteArray())) matched = candidate
        }
        return matched
    }

    fun isCodeShape(code: String): Boolean = code.length == DIGITS && code.all { it in '0'..'9' }

    /**
     * `otpauth://` provisioning URI (Key Uri Format used by Google/Microsoft Authenticator):
     * label `Issuer:account`, explicit issuer, algorithm, digits and period.
     */
    fun provisioningUri(issuer: String, account: String, secretBase32: String): String {
        fun enc(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
        return "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=$secretBase32&issuer=${enc(issuer)}" +
            "&algorithm=$ALGORITHM&digits=$DIGITS&period=$PERIOD_SECONDS"
    }
}

/**
 * AES-256-GCM field encryption with the application data key (envelope layer; a KMS-wrapped key
 * replaces the environment key in production without changing the stored format).
 * Stored value: 12-byte random IV followed by ciphertext+tag. [aad] binds the ciphertext to its
 * owner row, so a secret copied onto another user's row does not decrypt.
 */
class SecretBox(private val key: DataKey) {
    private val random = SecureRandom()

    val keyId: String get() = key.keyId

    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.bytes(), "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    fun open(sealed: ByteArray, keyId: String, aad: ByteArray): ByteArray {
        // Rotation is not implemented yet: a ciphertext under another key is a configuration error, never a silent failure.
        check(keyId == key.keyId) { "ciphertext was sealed with key '$keyId' but the configured data key is '${key.keyId}'" }
        require(sealed.size > IV_BYTES) { "sealed value too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.bytes(), "AES"), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * One-time recovery codes: 16 characters from an unambiguous alphabet (80 bits), shown as
 * `XXXX-XXXX-XXXX-XXXX`. Only sha256 of the normalized code is stored.
 */
object RecoveryCodes {
    const val COUNT = 10
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    fun generate(): List<String> = List(COUNT) {
        val raw = CharArray(16) { ALPHABET[random.nextInt(ALPHABET.length)] }
        raw.concatToString().chunked(4).joinToString("-")
    }

    /** Case, spaces and dashes are ignored so a code typed from paper still matches. */
    fun normalize(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }

    fun looksLikeRecoveryCode(code: String): Boolean = normalize(code).let { n -> n.length == 16 && n.all { it in ALPHABET } }

    fun hash(code: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(normalize(code).toByteArray(Charsets.US_ASCII))
}
