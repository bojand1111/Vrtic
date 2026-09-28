package com.vrticconnect.modules.me

import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.http.AuthRateLimits
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import com.vrticconnect.http.RateLimiter
import com.vrticconnect.modules.audit.Audit
import com.vrticconnect.modules.auth.AuthenticatedUser
import com.vrticconnect.modules.auth.PasswordHasher
import com.vrticconnect.modules.auth.PasswordPolicy
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.time.Duration
import java.util.UUID

/** docs/openapi.yaml `User`. */
@Serializable
data class UserProfile(
    val id: String,
    val email: String,
    val emailVerifiedAt: String? = null,
    val givenName: String,
    val familyName: String,
    val preferredLocale: String,
    val status: String,
    val mfaEnabled: Boolean,
    val isPlatformAdmin: Boolean,
    val lastLoginAt: String? = null,
    val createdAt: String,
)

@Serializable data class LocaleUpdate(val preferredLocale: String)
@Serializable data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

/**
 * E02-B16: own profile, locale and password. Everything runs in the auth-mode context bound to the
 * caller's id (users/mfa tables are reachable only there) and touches nothing but the caller's rows.
 */
class MeService(
    private val database: Database,
    private val passwordHasher: PasswordHasher,
    private val rateLimiter: RateLimiter = RateLimiter(),
) {

    suspend fun profile(user: AuthenticatedUser): UserProfile =
        database.transaction(DbContext.Auth(user.userId)) { c -> readProfile(c, user) } ?: throw ProblemException.unauthenticated()

    suspend fun updateLocale(user: AuthenticatedUser, update: LocaleUpdate): UserProfile {
        if (update.preferredLocale !in LOCALES) {
            throw validation(FieldError("preferredLocale", "INVALID_ENUM", LOCALES.joinToString()))
        }
        return database.transaction(DbContext.Auth(user.userId)) { c ->
            c.prepareStatement("UPDATE app.users SET preferred_locale = ? WHERE id = ? AND deleted_at IS NULL").use { st ->
                st.setString(1, update.preferredLocale); st.setObject(2, user.userId); st.executeUpdate()
            }
            readProfile(c, user)
        } ?: throw ProblemException.unauthenticated()
    }

    /**
     * Proving the current password IS the re-authentication for this action. On success the new hash
     * is stored, every OTHER session is revoked (`PASSWORD_CHANGED`) and the current session gets a
     * fresh `reauthenticated_at`. A wrong current password answers 403 and is rate-limited per user.
     */
    suspend fun changePassword(user: AuthenticatedUser, request: ChangePasswordRequest, requestId: String?) {
        rateLimiter.require("password-change:${user.userId}", PASSWORD_CHANGE_ATTEMPTS, PASSWORD_CHANGE_WINDOW)
        val current = request.currentPassword.toCharArray()
        val next = request.newPassword.toCharArray()
        try {
            database.transaction(DbContext.Auth(user.userId)) { c ->
                val (email, hash) = c.prepareStatement("SELECT email, password_hash FROM app.users WHERE id = ? AND status = 'ACTIVE' AND deleted_at IS NULL").use { st ->
                    st.setObject(1, user.userId)
                    st.executeQuery().use { rs -> if (rs.next()) rs.getString("email") to rs.getString("password_hash") else null }
                } ?: throw ProblemException.unauthenticated()
                if (hash == null || !passwordHasher.verify(current, hash)) {
                    Audit.record(c, "PASSWORD_CHANGE_REJECTED", "USER", user.userId, actorUserId = user.userId, requestId = requestId, result = Audit.Result.DENIED)
                    throw ProblemException(status = HttpStatusCode.Forbidden, type = ProblemTypes.FORBIDDEN, title = "Current password is wrong", detail = "CURRENT_PASSWORD_INVALID")
                }
                PasswordPolicy.validateOrThrow(request.newPassword, email, field = "newPassword")
                if (request.newPassword == request.currentPassword) {
                    throw validation(FieldError("newPassword", "SAME_AS_CURRENT", "new password must differ from the current one"))
                }
                c.prepareStatement("UPDATE app.users SET password_hash = ?, password_updated_at = CURRENT_TIMESTAMP WHERE id = ?").use { st ->
                    st.setString(1, passwordHasher.hash(next)); st.setObject(2, user.userId); st.executeUpdate()
                }
                val revoked = c.prepareStatement(
                    "UPDATE app.sessions SET revoked_at = CURRENT_TIMESTAMP, revoke_reason = 'PASSWORD_CHANGED' WHERE user_id = ? AND id <> ? AND revoked_at IS NULL",
                ).use { st -> st.setObject(1, user.userId); st.setObject(2, user.sessionId); st.executeUpdate() }
                c.prepareStatement(
                    "UPDATE app.access_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE revoked_at IS NULL AND session_id IN (SELECT id FROM app.sessions WHERE user_id = ? AND id <> ?)",
                ).use { st -> st.setObject(1, user.userId); st.setObject(2, user.sessionId); st.executeUpdate() }
                c.prepareStatement(
                    "UPDATE app.refresh_tokens SET consumed_at = CURRENT_TIMESTAMP WHERE consumed_at IS NULL AND session_id IN (SELECT id FROM app.sessions WHERE user_id = ? AND id <> ?)",
                ).use { st -> st.setObject(1, user.userId); st.setObject(2, user.sessionId); st.executeUpdate() }
                c.prepareStatement("UPDATE app.sessions SET reauthenticated_at = CURRENT_TIMESTAMP WHERE id = ?").use { st -> st.setObject(1, user.sessionId); st.executeUpdate() }
                Audit.record(c, "PASSWORD_CHANGED", "USER", user.userId, actorUserId = user.userId, requestId = requestId, metadata = mapOf("sessionsRevoked" to revoked.toString()))
            }
        } finally {
            current.fill('\u0000'); next.fill('\u0000')
        }
    }

    private fun readProfile(c: Connection, user: AuthenticatedUser): UserProfile? {
        val mfaEnabled = c.prepareStatement("SELECT 1 FROM app.user_mfa_methods WHERE user_id = ? AND verified_at IS NOT NULL AND revoked_at IS NULL LIMIT 1").use { st ->
            st.setObject(1, user.userId); st.executeQuery().use { it.next() }
        }
        return c.prepareStatement(
            "SELECT id, email, email_verified_at, given_name, family_name, preferred_locale, status, last_login_at, created_at FROM app.users WHERE id = ? AND deleted_at IS NULL",
        ).use { st ->
            st.setObject(1, user.userId)
            st.executeQuery().use { rs ->
                if (!rs.next()) null else UserProfile(
                    id = rs.getObject("id", UUID::class.java).toString(),
                    email = rs.getString("email"),
                    emailVerifiedAt = rs.getTimestamp("email_verified_at")?.toInstant()?.toString(),
                    givenName = rs.getString("given_name"),
                    familyName = rs.getString("family_name"),
                    preferredLocale = rs.getString("preferred_locale"),
                    status = rs.getString("status"),
                    mfaEnabled = mfaEnabled,
                    isPlatformAdmin = user.isPlatformAdmin,
                    lastLoginAt = rs.getTimestamp("last_login_at")?.toInstant()?.toString(),
                    createdAt = rs.getTimestamp("created_at").toInstant().toString(),
                )
            }
        }
    }

    private fun validation(vararg errors: FieldError) =
        ProblemException(status = HttpStatusCode.UnprocessableEntity, type = ProblemTypes.VALIDATION, title = "Validation failed", errors = errors.toList())

    private companion object {
        val LOCALES = setOf("sr-Latn", "sr-Cyrl", "en")
        const val PASSWORD_CHANGE_ATTEMPTS = 5
        val PASSWORD_CHANGE_WINDOW: Duration = AuthRateLimits.FAILED_LOGIN_WINDOW
    }
}
