package com.vrticconnect.modules.auth

import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.http.ProblemException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
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
            call.respond(result)
        } finally {
            passwordChars.fill('\u0000')
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
        connection.prepareStatement(
            "INSERT INTO app.refresh_tokens (session_id, token_hash, issued_at, idle_expires_at) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.setBytes(2, Tokens.sha256(token))
            statement.setTimestamp(3, Timestamp.from(issuedAt))
            statement.setTimestamp(4, Timestamp.from(expiresAt))
            statement.executeUpdate()
        }
    }

    private data class LoginUser(
        val id: UUID,
        val passwordHash: String?,
        val status: String,
        val emailVerified: Boolean,
        val lockedUntil: Instant?,
    )

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
