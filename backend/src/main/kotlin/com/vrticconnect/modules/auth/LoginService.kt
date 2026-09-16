package com.vrticconnect.modules.auth

import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.http.ProblemException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveOrNull
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Serializable
data class LoginDevice(
    val clientKind: String,
    val deviceName: String? = null,
    val deviceId: String? = null,
)

/** Accepts the current web client's flat clientKind and the documented nested device form. */
@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val clientKind: String? = null,
    val device: LoginDevice? = null,
)

@Serializable
data class TokenPair(
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: String,
)

@Serializable
data class RefreshRequest(val refreshToken: String? = null)

@Serializable
data class AuthResult(
    val status: String,
    val sessionId: String? = null,
    val tokens: TokenPair? = null,
    val csrfToken: String? = null,
    val mfaChallengeToken: String? = null,
)

/** First usable login slice: password verification and bearer token/session issuance. */
class LoginService(
    private val database: Database,
    private val passwordHasher: PasswordHasher,
    private val devMode: Boolean,
) {
    suspend fun login(call: ApplicationCall) {
        val request = call.receive<LoginRequest>()
        val email = request.email.trim()
        val password = request.password
        val clientKind = (request.device?.clientKind ?: request.clientKind)?.uppercase()
        if (email.isEmpty() || password.isEmpty() || clientKind == null || clientKind !in CLIENT_KINDS) {
            throw ProblemException.invalidCredentials()
        }

        val passwordChars = password.toCharArray()
        try {
            val result = database.transaction(DbContext.Auth()) { connection ->
                val user = findUser(connection, email)
                val candidateHash = user?.passwordHash ?: dummyPasswordHash
                val passwordMatches = passwordHasher.verify(passwordChars, candidateHash)
                if (user == null || !passwordMatches || user.status != "ACTIVE" || user.lockedUntil?.isAfter(Instant.now()) == true) {
                    throw ProblemException.invalidCredentials()
                }
                if (!user.emailVerified) {
                    return@transaction AuthResult(status = "EMAIL_VERIFICATION_REQUIRED")
                }

                val now = Instant.now()
                val accessExpires = now.plus(10, ChronoUnit.MINUTES)
                val refreshExpires = now.plus(7, ChronoUnit.DAYS)
                val absoluteExpires = now.plus(30, ChronoUnit.DAYS)
                val sessionId = UUID.randomUUID()
                val accessToken = Tokens.generate(Tokens.Kind.ACCESS)
                val refreshToken = Tokens.generate(Tokens.Kind.REFRESH)

                insertSession(connection, sessionId, user.id, clientKind, request.device, absoluteExpires)
                insertAccessToken(connection, sessionId, accessToken, now, accessExpires)
                insertRefreshToken(connection, sessionId, refreshToken, now, refreshExpires)

                AuthResult(
                    status = "AUTHENTICATED",
                    sessionId = sessionId.toString(),
                    tokens = TokenPair(
                        accessToken = accessToken,
                        accessTokenExpiresAt = accessExpires.toString(),
                        refreshToken = refreshToken,
                        refreshTokenExpiresAt = refreshExpires.toString(),
                    ),
                )
            }
            if (clientKind == "WEB" && result.tokens != null) {
                appendTokenCookie(call, "vc_access", result.tokens.accessToken, 600, "/api")
                appendTokenCookie(call, "vc_refresh", result.tokens.refreshToken, 604_800, "/api/v1/auth/refresh")
                call.respond(result.copy(tokens = null))
            } else {
                call.respond(result)
            }
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    suspend fun refresh(call: ApplicationCall) {
        val request = call.receiveOrNull<RefreshRequest>()
        val refreshToken = (request?.refreshToken ?: call.request.cookies["vc_refresh"])?.trim()
            ?.takeIf { Tokens.isRefreshToken(it) }
            ?: throw ProblemException.invalidCredentials()

        val result = database.transaction(DbContext.Auth()) { connection ->
            val current = findRefreshToken(connection, refreshToken)
                ?: throw ProblemException.invalidCredentials()
            if (current.consumedAt != null) {
                markRefreshReuse(connection, current.id, current.sessionId)
                return@transaction RefreshOutcome.Reused
            }

            val now = Instant.now()
            if (current.idleExpiresAt <= now || current.sessionRevokedAt != null ||
                current.absoluteExpiresAt <= now || current.userStatus != "ACTIVE"
            ) {
                throw ProblemException.invalidCredentials()
            }

            val newRefreshId = UUID.randomUUID()
            val accessToken = Tokens.generate(Tokens.Kind.ACCESS)
            val newRefreshToken = Tokens.generate(Tokens.Kind.REFRESH)
            val accessExpires = minOf(now.plus(10, ChronoUnit.MINUTES), current.absoluteExpiresAt)
            val refreshExpires = minOf(now.plus(7, ChronoUnit.DAYS), current.absoluteExpiresAt)

            val consumed = connection.prepareStatement(
                "UPDATE app.refresh_tokens SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL",
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.setObject(2, current.id)
                statement.executeUpdate()
            }
            if (consumed != 1) throw ProblemException.invalidCredentials()

            insertAccessToken(connection, current.sessionId, accessToken, now, accessExpires)
            insertRefreshToken(connection, newRefreshId, current.sessionId, newRefreshToken, now, refreshExpires)
            connection.prepareStatement("UPDATE app.refresh_tokens SET replaced_by_id = ? WHERE id = ?").use { statement ->
                statement.setObject(1, newRefreshId)
                statement.setObject(2, current.id)
                statement.executeUpdate()
            }

            RefreshOutcome.Issued(
                AuthResult(
                    status = "AUTHENTICATED",
                    sessionId = current.sessionId.toString(),
                    tokens = TokenPair(
                        accessToken = accessToken,
                        accessTokenExpiresAt = accessExpires.toString(),
                        refreshToken = newRefreshToken,
                        refreshTokenExpiresAt = refreshExpires.toString(),
                    ),
                ),
                current.clientKind == "WEB",
            )
        }
        when (result) {
            is RefreshOutcome.Issued -> {
                if (result.web && result.value.tokens != null) {
                    appendTokenCookie(call, "vc_access", result.value.tokens.accessToken, 600, "/api")
                    appendTokenCookie(call, "vc_refresh", result.value.tokens.refreshToken, 604_800, "/api/v1/auth/refresh")
                    call.respond(result.value.copy(tokens = null))
                } else {
                    call.respond(result.value)
                }
            }
            RefreshOutcome.Reused -> throw ProblemException.refreshReuseDetected()
        }
    }

    private fun findUser(connection: Connection, email: String): LoginUser? =
        connection.prepareStatement(
            "SELECT id, password_hash, status, email_verified_at, locked_until FROM app.users WHERE email = ?",
        ).use { statement ->
            statement.setString(1, email)
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                LoginUser(
                    id = result.getObject("id", UUID::class.java),
                    passwordHash = result.getString("password_hash"),
                    status = result.getString("status"),
                    emailVerified = result.getTimestamp("email_verified_at") != null,
                    lockedUntil = result.getTimestamp("locked_until")?.toInstant(),
                )
            }
        }

    private fun insertSession(
        connection: Connection,
        sessionId: UUID,
        userId: UUID,
        clientKind: String,
        device: LoginDevice?,
        absoluteExpires: Instant,
    ) {
        connection.prepareStatement(
            "INSERT INTO app.sessions (id, user_id, client_kind, device_name, device_id, user_agent, absolute_expires_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.setObject(2, userId)
            statement.setString(3, clientKind)
            statement.setString(4, device?.deviceName)
            statement.setString(5, device?.deviceId)
            statement.setString(6, null)
            statement.setTimestamp(7, Timestamp.from(absoluteExpires))
            statement.executeUpdate()
        }
    }

    private fun insertAccessToken(connection: Connection, sessionId: UUID, token: String, issuedAt: Instant, expiresAt: Instant) {
        connection.prepareStatement(
            "INSERT INTO app.access_tokens (session_id, token_hash, issued_at, expires_at) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.setBytes(2, Tokens.sha256(token))
            statement.setTimestamp(3, Timestamp.from(issuedAt))
            statement.setTimestamp(4, Timestamp.from(expiresAt))
            statement.executeUpdate()
        }
    }

    private fun insertRefreshToken(connection: Connection, sessionId: UUID, token: String, issuedAt: Instant, expiresAt: Instant) {
        insertRefreshToken(connection, UUID.randomUUID(), sessionId, token, issuedAt, expiresAt)
    }

    private fun insertRefreshToken(
        connection: Connection,
        refreshId: UUID,
        sessionId: UUID,
        token: String,
        issuedAt: Instant,
        expiresAt: Instant,
    ) {
        connection.prepareStatement(
            "INSERT INTO app.refresh_tokens (id, session_id, token_hash, issued_at, idle_expires_at) VALUES (?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setObject(1, refreshId)
            statement.setObject(2, sessionId)
            statement.setBytes(3, Tokens.sha256(token))
            statement.setTimestamp(4, Timestamp.from(issuedAt))
            statement.setTimestamp(5, Timestamp.from(expiresAt))
            statement.executeUpdate()
        }
    }

    private fun findRefreshToken(connection: Connection, token: String): RefreshRecord? =
        connection.prepareStatement(
            "SELECT rt.id, rt.session_id, rt.consumed_at, rt.idle_expires_at, s.client_kind, " +
                "s.revoked_at AS session_revoked_at, s.absolute_expires_at, u.status AS user_status " +
                "FROM app.refresh_tokens rt " +
                "JOIN app.sessions s ON s.id = rt.session_id " +
                "JOIN app.users u ON u.id = s.user_id " +
                "WHERE rt.token_hash = ? AND u.deleted_at IS NULL FOR UPDATE",
        ).use { statement ->
            statement.setBytes(1, Tokens.sha256(token))
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                RefreshRecord(
                    id = result.getObject("id", UUID::class.java),
                    sessionId = result.getObject("session_id", UUID::class.java),
                    consumedAt = result.getTimestamp("consumed_at")?.toInstant(),
                    idleExpiresAt = result.getTimestamp("idle_expires_at").toInstant(),
                    clientKind = result.getString("client_kind"),
                    sessionRevokedAt = result.getTimestamp("session_revoked_at")?.toInstant(),
                    absoluteExpiresAt = result.getTimestamp("absolute_expires_at").toInstant(),
                    userStatus = result.getString("user_status"),
                )
            }
        }

    private fun markRefreshReuse(connection: Connection, refreshId: UUID, sessionId: UUID) {
        connection.prepareStatement(
            "UPDATE app.refresh_tokens SET reuse_detected_at = COALESCE(reuse_detected_at, CURRENT_TIMESTAMP) WHERE id = ?",
        ).use { statement ->
            statement.setObject(1, refreshId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "UPDATE app.sessions SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP), revoke_reason = 'REUSE_DETECTED' WHERE id = ?",
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "UPDATE app.access_tokens SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP) WHERE session_id = ? AND revoked_at IS NULL",
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.executeUpdate()
        }
    }

    private fun appendTokenCookie(call: ApplicationCall, name: String, value: String, maxAgeSeconds: Int, path: String) {
        val secure = if (devMode) "" else "; Secure"
        call.response.headers.append(
            HttpHeaders.SetCookie,
            "$name=$value; Max-Age=$maxAgeSeconds; Path=$path; HttpOnly; SameSite=Lax$secure",
        )
    }

    private data class LoginUser(
        val id: UUID,
        val passwordHash: String?,
        val status: String,
        val emailVerified: Boolean,
        val lockedUntil: Instant?,
    )

    private data class RefreshRecord(
        val id: UUID,
        val sessionId: UUID,
        val consumedAt: Instant?,
        val idleExpiresAt: Instant,
        val clientKind: String,
        val sessionRevokedAt: Instant?,
        val absoluteExpiresAt: Instant,
        val userStatus: String,
    )

    private sealed interface RefreshOutcome {
        data class Issued(val value: AuthResult, val web: Boolean) : RefreshOutcome
        data object Reused : RefreshOutcome
    }

    private val dummyPasswordHash: String by lazy {
        val dummy = "vrtic-login-dummy-password".toCharArray()
        try {
            passwordHasher.hash(dummy)
        } finally {
            dummy.fill('\u0000')
        }
    }

    private companion object {
        val CLIENT_KINDS = setOf("WEB", "ANDROID", "IOS")
    }
}
