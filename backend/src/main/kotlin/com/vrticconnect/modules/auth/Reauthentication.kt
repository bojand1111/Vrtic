package com.vrticconnect.modules.auth

import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.http.AuthRateLimits
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import com.vrticconnect.http.RateLimiter
import com.vrticconnect.modules.audit.Audit
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.time.Duration
import java.time.Instant

@Serializable data class ReauthenticateRequest(val password: String, val totpCode: String? = null)

/** docs/openapi.yaml `ReauthenticateResult`. */
@Serializable data class ReauthenticateResult(val reauthenticatedAt: String, val validUntil: String)

/**
 * E02-B10: "recent authentication" for sensitive actions (docs/SECURITY.md 2.1). A login or a
 * successful re-authentication counts; the window is [MAX_AGE]. Checked inside the caller's
 * auth-mode transaction so the decision and the action commit together.
 */
object RecentAuthentication {
    val MAX_AGE: Duration = Duration.ofMinutes(5)

    /** Returns the instant of the last proof of identity, or throws 403 REAUTHENTICATION_REQUIRED. */
    fun require(connection: Connection, user: AuthenticatedUser, now: Instant = Instant.now()): Instant {
        val lastAuth = connection.prepareStatement(
            "SELECT COALESCE(reauthenticated_at, created_at) AS last_auth FROM app.sessions WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
        ).use { st ->
            st.setObject(1, user.sessionId)
            st.setObject(2, user.userId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp("last_auth").toInstant() else null }
        } ?: throw ProblemException.unauthenticated()
        if (Duration.between(lastAuth, now) > MAX_AGE) throw ProblemException.reauthenticationRequired()
        return lastAuth
    }
}

/**
 * `POST /auth/reauthenticate`: re-enter the password (and, once E02-B11 lands, the TOTP code when
 * enrolled) to stamp `sessions.reauthenticated_at`. Wrong password answers 403 and is rate-limited
 * per user; nothing is stamped on failure.
 */
class ReauthService(
    private val database: Database,
    private val passwordHasher: PasswordHasher,
    private val rateLimiter: RateLimiter = RateLimiter(),
) {
    suspend fun reauthenticate(call: ApplicationCall, user: AuthenticatedUser) {
        rateLimiter.require("reauth:${user.userId}", ATTEMPTS, AuthRateLimits.FAILED_LOGIN_WINDOW)
        val request = call.receive<ReauthenticateRequest>()
        val chars = request.password.toCharArray()
        try {
            val stampedAt = database.transaction(DbContext.Auth(user.userId)) { c ->
                val (hash, mfaEnrolled) = c.prepareStatement(
                    "SELECT u.password_hash, EXISTS (SELECT 1 FROM app.user_mfa_methods m WHERE m.user_id = u.id AND m.verified_at IS NOT NULL AND m.revoked_at IS NULL) AS mfa " +
                        "FROM app.users u WHERE u.id = ? AND u.status = 'ACTIVE' AND u.deleted_at IS NULL",
                ).use { st ->
                    st.setObject(1, user.userId)
                    st.executeQuery().use { rs -> if (rs.next()) rs.getString("password_hash") to rs.getBoolean("mfa") else null }
                } ?: throw ProblemException.unauthenticated()
                if (hash == null || !passwordHasher.verify(chars, hash)) {
                    Audit.record(c, "REAUTHENTICATION_REJECTED", "SESSION", user.sessionId, actorUserId = user.userId, requestId = call.callId, result = Audit.Result.DENIED)
                    throw ProblemException(status = HttpStatusCode.Forbidden, type = ProblemTypes.FORBIDDEN, title = "Re-authentication failed", detail = "INVALID_CREDENTIALS")
                }
                if (mfaEnrolled) {
                    // TOTP verification arrives with E02-B11; until then an enrolled user cannot exist (no enrollment endpoint).
                    throw ProblemException.notImplemented("TOTP re-authentication")
                }
                val now = Instant.now()
                c.prepareStatement("UPDATE app.sessions SET reauthenticated_at = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL").use { st ->
                    st.setTimestamp(1, java.sql.Timestamp.from(now))
                    st.setObject(2, user.sessionId)
                    st.setObject(3, user.userId)
                    if (st.executeUpdate() != 1) throw ProblemException.unauthenticated()
                }
                Audit.record(c, "REAUTHENTICATED", "SESSION", user.sessionId, actorUserId = user.userId, requestId = call.callId)
                now
            }
            call.respond(ReauthenticateResult(stampedAt.toString(), stampedAt.plus(RecentAuthentication.MAX_AGE).toString()))
        } finally {
            chars.fill('\u0000')
        }
    }

    private companion object {
        const val ATTEMPTS = 5
    }
}
