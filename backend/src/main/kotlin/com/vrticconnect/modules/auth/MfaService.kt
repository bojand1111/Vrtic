package com.vrticconnect.modules.auth

import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.http.AuthRateLimits
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import com.vrticconnect.http.RateLimiter
import com.vrticconnect.http.conflict
import com.vrticconnect.modules.audit.Audit
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** docs/openapi.yaml `TotpSetupResult`. */
@Serializable data class TotpSetupResult(val secretUri: String, val secretBase32: String, val expiresAt: String)

/** docs/openapi.yaml `TotpCodeRequest`. */
@Serializable data class TotpCodeRequest(val code: String? = null)

/**
 * docs/openapi.yaml `TotpVerifyRequest`. Verification is bound to the current (limited) session, so
 * `mfaChallengeToken` is accepted for contract compatibility and ignored (see MfaGate).
 */
@Serializable data class TotpVerifyRequest(val code: String? = null, val mfaChallengeToken: String? = null)

/** docs/openapi.yaml `RecoveryCodes`. */
@Serializable data class RecoveryCodesResult(val codes: List<String>, val generatedAt: String)

/**
 * E02-B11 TOTP enrollment and verification.
 *  - setup: new secret (AES-256-GCM under the data key, bound to the user id), pending for 15 minutes;
 *    requires a recent login/re-authentication; refused while a confirmed method exists.
 *  - confirm: the first code activates the method, marks THIS session as MFA-verified and returns ten
 *    recovery codes once (only sha256 is stored; older unused codes are deleted).
 *  - verify: a TOTP code or one recovery code (consumed) stamps `sessions.mfa_verified_at`.
 *  - regenerate: replaces all unused recovery codes; requires recent authentication.
 * Attempts are limited per user (10 per 15 minutes per operation); an accepted TOTP step cannot be
 * replayed within this process.
 */
class MfaService(
    private val database: Database,
    private val secretBox: SecretBox,
    private val rateLimiter: RateLimiter = RateLimiter(),
) {
    private val lastAcceptedStep = ConcurrentHashMap<UUID, Long>()

    suspend fun setup(user: AuthenticatedUser, requestId: String?): TotpSetupResult =
        database.transaction(DbContext.Auth(user.userId)) { c ->
            RecentAuthentication.require(c, user)
            if (activeMethod(c, user.userId, confirmed = true) != null) throw conflict("MFA_ALREADY_ENABLED")
            val email = c.prepareStatement("SELECT email FROM app.users WHERE id = ? AND status = 'ACTIVE' AND deleted_at IS NULL").use { st ->
                st.setObject(1, user.userId)
                st.executeQuery().use { rs -> if (rs.next()) rs.getString("email") else null }
            } ?: throw ProblemException.unauthenticated()
            // A previous unconfirmed setup is abandoned (the partial unique index allows one live method per type).
            c.prepareStatement("UPDATE app.user_mfa_methods SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND type = 'TOTP' AND verified_at IS NULL AND revoked_at IS NULL").use { st ->
                st.setObject(1, user.userId); st.executeUpdate()
            }
            val secret = Totp.newSecret()
            val now = Instant.now()
            val methodId = UUID.randomUUID()
            c.prepareStatement("INSERT INTO app.user_mfa_methods (id, user_id, type, secret_enc, key_id, created_at) VALUES (?, ?, 'TOTP', ?, ?, ?)").use { st ->
                st.setObject(1, methodId); st.setObject(2, user.userId)
                st.setBytes(3, secretBox.seal(secret, aad(user.userId))); st.setString(4, secretBox.keyId)
                st.setTimestamp(5, Timestamp.from(now))
                st.executeUpdate()
            }
            Audit.record(c, "MFA_TOTP_SETUP_STARTED", "USER", user.userId, actorUserId = user.userId, requestId = requestId)
            val base32 = Base32.encode(secret)
            secret.fill(0)
            TotpSetupResult(
                secretUri = Totp.provisioningUri(ISSUER, email, base32),
                secretBase32 = base32,
                expiresAt = now.plus(SETUP_VALIDITY).toString(),
            )
        }

    suspend fun confirm(user: AuthenticatedUser, request: TotpCodeRequest, requestId: String?): RecoveryCodesResult {
        val code = requireTotpShape(request.code)
        rateLimiter.require("mfa-confirm:${user.userId}", ATTEMPTS, AuthRateLimits.FAILED_LOGIN_WINDOW)
        val outcome = database.transaction(DbContext.Auth(user.userId)) { c ->
            if (activeMethod(c, user.userId, confirmed = true) != null) throw conflict("MFA_ALREADY_ENABLED")
            val pending = activeMethod(c, user.userId, confirmed = false) ?: throw conflict("MFA_SETUP_NOT_STARTED")
            if (pending.createdAt.plus(SETUP_VALIDITY).isBefore(Instant.now())) throw conflict("MFA_SETUP_EXPIRED")
            val step = verifyTotp(user.userId, pending, code)
            if (step == null) {
                Audit.record(c, "MFA_TOTP_CONFIRM_FAILED", "USER", user.userId, actorUserId = user.userId, requestId = requestId, result = Audit.Result.DENIED)
                return@transaction null
            }
            c.prepareStatement("UPDATE app.user_mfa_methods SET verified_at = CURRENT_TIMESTAMP WHERE id = ?").use { st -> st.setObject(1, pending.id); st.executeUpdate() }
            // Possession was just proven in this session.
            markSessionVerified(c, user)
            val result = replaceRecoveryCodes(c, user.userId)
            Audit.record(c, "MFA_TOTP_ENABLED", "USER", user.userId, actorUserId = user.userId, requestId = requestId)
            result
        } ?: throw invalidCode()
        return outcome
    }

    suspend fun verify(user: AuthenticatedUser, request: TotpVerifyRequest, requestId: String?): AuthResult {
        val raw = request.code?.trim().orEmpty()
        if (raw.length !in 6..24) throw invalidCode(validation = true)
        rateLimiter.require("mfa-verify:${user.userId}", ATTEMPTS, AuthRateLimits.FAILED_LOGIN_WINDOW)
        val ok = database.transaction(DbContext.Auth(user.userId)) { c ->
            val method = activeMethod(c, user.userId, confirmed = true) ?: throw conflict("MFA_NOT_ENABLED")
            val via = when {
                Totp.isCodeShape(raw) -> if (verifyTotp(user.userId, method, raw) != null) "TOTP" else null
                RecoveryCodes.looksLikeRecoveryCode(raw) -> if (consumeRecoveryCode(c, user.userId, raw)) "RECOVERY_CODE" else null
                else -> null
            }
            if (via == null) {
                Audit.record(c, "MFA_VERIFY_FAILED", "SESSION", user.sessionId, actorUserId = user.userId, requestId = requestId, result = Audit.Result.DENIED)
                return@transaction false
            }
            markSessionVerified(c, user)
            Audit.record(c, "MFA_VERIFIED", "SESSION", user.sessionId, actorUserId = user.userId, requestId = requestId, metadata = mapOf("reason" to via))
            true
        }
        if (!ok) throw invalidCode()
        return AuthResult(status = "AUTHENTICATED", sessionId = user.sessionId.toString())
    }

    suspend fun regenerateRecoveryCodes(user: AuthenticatedUser, requestId: String?): RecoveryCodesResult =
        database.transaction(DbContext.Auth(user.userId)) { c ->
            RecentAuthentication.require(c, user)
            activeMethod(c, user.userId, confirmed = true) ?: throw conflict("MFA_NOT_ENABLED")
            val result = replaceRecoveryCodes(c, user.userId)
            Audit.record(c, "MFA_RECOVERY_CODES_REGENERATED", "USER", user.userId, actorUserId = user.userId, requestId = requestId)
            result
        }

    /**
     * Used by `POST /auth/reauthenticate` when a TOTP code is supplied: true when [code] matches the
     * user's confirmed method (false when there is no method). Runs inside the caller's auth transaction.
     */
    fun checkTotp(c: Connection, userId: UUID, code: String): Boolean {
        val method = activeMethod(c, userId, confirmed = true) ?: return false
        return Totp.isCodeShape(code) && verifyTotp(userId, method, code) != null
    }

    private data class Method(val id: UUID, val secretEnc: ByteArray, val keyId: String, val createdAt: Instant)

    private fun activeMethod(c: Connection, userId: UUID, confirmed: Boolean): Method? =
        c.prepareStatement(
            "SELECT id, secret_enc, key_id, created_at FROM app.user_mfa_methods WHERE user_id = ? AND type = 'TOTP' AND revoked_at IS NULL AND " +
                (if (confirmed) "verified_at IS NOT NULL" else "verified_at IS NULL") + " ORDER BY created_at DESC LIMIT 1",
        ).use { st ->
            st.setObject(1, userId)
            st.executeQuery().use { rs ->
                if (!rs.next()) null else Method(
                    rs.getObject("id", UUID::class.java), rs.getBytes("secret_enc"), rs.getString("key_id"), rs.getTimestamp("created_at").toInstant(),
                )
            }
        }

    private fun verifyTotp(userId: UUID, method: Method, code: String): Long? {
        val secret = secretBox.open(method.secretEnc, method.keyId, aad(userId))
        try {
            val step = Totp.verify(secret, code) ?: return null
            // Replay protection: a step may be used once per user (per process; the DB schema has no column for it).
            var accepted = false
            lastAcceptedStep.compute(userId) { _, previous ->
                if (previous != null && step <= previous) previous else { accepted = true; step }
            }
            return if (accepted) step else null
        } finally {
            secret.fill(0)
        }
    }

    private fun consumeRecoveryCode(c: Connection, userId: UUID, code: String): Boolean =
        c.prepareStatement(
            // Row lock + re-check of used_at: two concurrent requests cannot both consume the same code.
            "UPDATE app.user_mfa_recovery_codes SET used_at = CURRENT_TIMESTAMP WHERE user_id = ? AND code_hash = ? AND used_at IS NULL",
        ).use { st ->
            st.setObject(1, userId); st.setBytes(2, RecoveryCodes.hash(code))
            st.executeUpdate() >= 1
        }

    private fun replaceRecoveryCodes(c: Connection, userId: UUID): RecoveryCodesResult {
        c.prepareStatement("DELETE FROM app.user_mfa_recovery_codes WHERE user_id = ? AND used_at IS NULL").use { st -> st.setObject(1, userId); st.executeUpdate() }
        val codes = RecoveryCodes.generate()
        val now = Instant.now()
        c.prepareStatement("INSERT INTO app.user_mfa_recovery_codes (user_id, code_hash, created_at) VALUES (?, ?, ?)").use { st ->
            for (code in codes) {
                st.setObject(1, userId); st.setBytes(2, RecoveryCodes.hash(code)); st.setTimestamp(3, Timestamp.from(now))
                st.addBatch()
            }
            st.executeBatch()
        }
        return RecoveryCodesResult(codes, now.toString())
    }

    private fun markSessionVerified(c: Connection, user: AuthenticatedUser) {
        c.prepareStatement("UPDATE app.sessions SET mfa_verified_at = CURRENT_TIMESTAMP WHERE id = ? AND user_id = ? AND revoked_at IS NULL").use { st ->
            st.setObject(1, user.sessionId); st.setObject(2, user.userId)
            if (st.executeUpdate() != 1) throw ProblemException.unauthenticated()
        }
    }

    private fun requireTotpShape(code: String?): String {
        val trimmed = code?.trim().orEmpty()
        if (!Totp.isCodeShape(trimmed)) throw invalidCode(validation = true)
        return trimmed
    }

    private fun invalidCode(validation: Boolean = false) = ProblemException(
        status = HttpStatusCode.UnprocessableEntity, type = ProblemTypes.VALIDATION, title = "Validation failed",
        errors = listOf(
            if (validation) FieldError("code", "INVALID_FORMAT", "6-digit code or recovery code")
            else FieldError("code", "INVALID_CODE", "the code is wrong or expired"),
        ),
    )

    private fun aad(userId: UUID): ByteArray = "user_mfa_methods:$userId".toByteArray(Charsets.US_ASCII)

    companion object {
        const val ISSUER = "Vrtic Connect"
        val SETUP_VALIDITY: Duration = Duration.ofMinutes(15)
        /** Attempts (successful or not) per user and operation in 15 minutes, via the shared RateLimiter. */
        private const val ATTEMPTS = 10
    }
}
