package com.vrticconnect.modules.auth

import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.http.AuthRateLimits
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.RateLimiter
import com.vrticconnect.http.clientIp
import com.vrticconnect.http.rateLimited
import com.vrticconnect.modules.audit.Audit
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
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
    private val csrfService: CsrfService? = null,
    private val rateLimiter: RateLimiter = RateLimiter(),
    private val trustProxyHeaders: Boolean = false,
) {
    /**
     * E02-B13 brute-force protection, evaluated in this order so nothing reveals whether the account exists:
     *  1. per-IP limiter (in-memory) -> 429;
     *  2. per e-mail-hash failure count from `login_attempts` (durable, last 15 min) or `users.locked_until` -> 429 + Retry-After;
     *  3. Argon2id verification (always executed, dummy hash for unknown accounts);
     *  4. the attempt is recorded and counters updated in a transaction that COMMITS even when the answer is 401.
     */
    suspend fun login(call: ApplicationCall) {
        val request = call.receive<LoginRequest>()
        val email = request.email.trim()
        val password = request.password
        val clientKind = (request.device?.clientKind ?: request.clientKind)?.uppercase()
        if (email.isEmpty() || password.isEmpty() || clientKind == null || clientKind !in CLIENT_KINDS) {
            throw ProblemException.invalidCredentials()
        }
        val ip = call.clientIp(trustProxyHeaders)
        rateLimiter.require("auth-ip:$ip", AuthRateLimits.IP_PER_MINUTE, AuthRateLimits.IP_WINDOW)
        val emailHash = Tokens.sha256(email.lowercase())

        val passwordChars = password.toCharArray()
        try {
            val outcome = database.transaction(DbContext.Auth()) { connection ->
                val now = Instant.now()
                val user = findUser(connection, email)
                val lockedUntil = user?.lockedUntil?.takeIf { it.isAfter(now) }
                    ?: recentFailures(connection, emailHash, now).takeIf { it >= AuthRateLimits.FAILED_LOGINS_BEFORE_LOCK }
                        ?.let { now.plus(AuthRateLimits.FAILED_LOGIN_WINDOW) }
                if (lockedUntil != null) {
                    return@transaction LoginOutcome.Locked(java.time.Duration.between(now, lockedUntil).seconds + 1)
                }

                val candidateHash = user?.passwordHash ?: dummyPasswordHash
                val passwordMatches = passwordHasher.verify(passwordChars, candidateHash)
                val succeeded = user != null && passwordMatches && user.status == "ACTIVE"
                recordAttempt(connection, emailHash, ip, succeeded)

                if (!succeeded) {
                    if (user != null) {
                        val failures = user.failedLoginCount + 1
                        val lock = if (failures % AuthRateLimits.FAILED_LOGINS_BEFORE_LOCK == 0) now.plus(AuthRateLimits.lockDuration(failures)) else null
                        connection.prepareStatement("UPDATE app.users SET failed_login_count = ?, locked_until = ? WHERE id = ?").use { statement ->
                            statement.setInt(1, failures)
                            statement.setTimestamp(2, lock?.let(Timestamp::from))
                            statement.setObject(3, user.id)
                            statement.executeUpdate()
                        }
                        if (lock != null) {
                            Audit.record(connection, "ACCOUNT_LOCKED", "USER", user.id, actorUserId = null, requestId = call.callId, metadata = mapOf("reason" to "FAILED_LOGINS"))
                        }
                    }
                    return@transaction LoginOutcome.Rejected
                }
                if (!user!!.emailVerified) {
                    return@transaction LoginOutcome.Authenticated(AuthResult(status = "EMAIL_VERIFICATION_REQUIRED"))
                }
                connection.prepareStatement("UPDATE app.users SET last_login_at = CURRENT_TIMESTAMP, failed_login_count = 0, locked_until = NULL WHERE id = ?").use { statement ->
                    statement.setObject(1, user.id)
                    statement.executeUpdate()
                }
                LoginOutcome.Authenticated(issueSession(connection, user.id, clientKind, request.device ?: LoginDevice(clientKind)))
            }
            when (outcome) {
                is LoginOutcome.Authenticated -> respondAuth(call, outcome.result, clientKind, io.ktor.http.HttpStatusCode.OK)
                is LoginOutcome.Locked -> throw ProblemException.rateLimited(outcome.retryAfterSeconds)
                LoginOutcome.Rejected -> throw ProblemException.invalidCredentials()
            }
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun recentFailures(connection: Connection, emailHash: ByteArray, now: Instant): Int =
        connection.prepareStatement(
            "SELECT count(*) FROM app.login_attempts WHERE email_hash = ? AND succeeded = false AND occurred_at > ?",
        ).use { statement ->
            statement.setBytes(1, emailHash)
            statement.setTimestamp(2, Timestamp.from(now.minus(AuthRateLimits.FAILED_LOGIN_WINDOW)))
            statement.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    private fun recordAttempt(connection: Connection, emailHash: ByteArray, ip: String, succeeded: Boolean) {
        connection.prepareStatement("INSERT INTO app.login_attempts (email_hash, ip, succeeded) VALUES (?, ?::inet, ?)").use { statement ->
            statement.setBytes(1, emailHash)
            statement.setString(2, ip.takeIf { it.matches(IP_PATTERN) })
            statement.setBoolean(3, succeeded)
            statement.executeUpdate()
        }
    }

    private sealed interface LoginOutcome {
        data class Authenticated(val result: AuthResult) : LoginOutcome
        data class Locked(val retryAfterSeconds: Long) : LoginOutcome
        data object Rejected : LoginOutcome
    }

    suspend fun refresh(call: ApplicationCall) {
        val request = runCatching { call.receive<RefreshRequest>() }.getOrNull()
        val refreshToken = (request?.refreshToken ?: call.request.cookies["vc_refresh"])?.trim()
            ?.takeIf { Tokens.isRefreshToken(it) }
            ?: throw ProblemException.invalidCredentials()
        csrfService?.requireRefresh(call, refreshToken)

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
            // Device list shows activity per session; a refresh is the cheapest reliable signal (every 10 min of use).
            connection.prepareStatement("UPDATE app.sessions SET last_seen_at = ? WHERE id = ?").use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.setObject(2, current.sessionId)
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
                    AuthCookies.append(call, AuthCookies.ACCESS, result.value.tokens.accessToken, 600, AuthCookies.ACCESS_PATH, httpOnly = true, devMode = devMode)
                    AuthCookies.append(call, AuthCookies.REFRESH, result.value.tokens.refreshToken, 604_800, AuthCookies.REFRESH_PATH, httpOnly = true, devMode = devMode)
                    call.respond(result.value.copy(tokens = null))
                } else {
                    call.respond(result.value)
                }
            }
            RefreshOutcome.Reused -> throw ProblemException.refreshReuseDetected()
        }
    }

    suspend fun logout(call: ApplicationCall, authenticated: AuthenticatedUser) {
        database.transaction(DbContext.Auth(authenticated.userId)) { connection ->
            connection.prepareStatement(
                "UPDATE app.sessions SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP), revoke_reason = 'USER_LOGOUT' " +
                    "WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
            ).use { statement ->
                statement.setObject(1, authenticated.sessionId)
                statement.setObject(2, authenticated.userId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "UPDATE app.access_tokens SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP) WHERE session_id = ? AND revoked_at IS NULL",
            ).use { statement ->
                statement.setObject(1, authenticated.sessionId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "UPDATE app.refresh_tokens SET consumed_at = COALESCE(consumed_at, CURRENT_TIMESTAMP) WHERE session_id = ? AND consumed_at IS NULL",
            ).use { statement ->
                statement.setObject(1, authenticated.sessionId)
                statement.executeUpdate()
            }
        }
        AuthCookies.clearAll(call, devMode)
        call.respond(io.ktor.http.HttpStatusCode.NoContent)
    }

    /**
     * Creates a session with its first access/refresh pair (and CSRF token for WEB) inside the
     * caller's auth-mode transaction. Shared by login and invitation registration.
     */
    fun issueSession(connection: Connection, userId: UUID, clientKind: String, device: LoginDevice?): AuthResult {
        require(clientKind in CLIENT_KINDS) { "unsupported client kind" }
        val now = Instant.now()
        val accessExpires = now.plus(10, ChronoUnit.MINUTES)
        val refreshExpires = now.plus(7, ChronoUnit.DAYS)
        val absoluteExpires = now.plus(30, ChronoUnit.DAYS)
        val sessionId = UUID.randomUUID()
        val accessToken = Tokens.generate(Tokens.Kind.ACCESS)
        val refreshToken = Tokens.generate(Tokens.Kind.REFRESH)
        val csrfToken = if (clientKind == "WEB") Tokens.generate(Tokens.Kind.CSRF) else null

        insertSession(connection, sessionId, userId, clientKind, device, absoluteExpires, csrfToken)
        insertAccessToken(connection, sessionId, accessToken, now, accessExpires)
        insertRefreshToken(connection, sessionId, refreshToken, now, refreshExpires)

        return AuthResult(
            status = "AUTHENTICATED",
            sessionId = sessionId.toString(),
            tokens = TokenPair(
                accessToken = accessToken,
                accessTokenExpiresAt = accessExpires.toString(),
                refreshToken = refreshToken,
                refreshTokenExpiresAt = refreshExpires.toString(),
            ),
            csrfToken = csrfToken,
        )
    }

    /** WEB gets HttpOnly cookies and a body without tokens; mobile gets the tokens in the body. */
    suspend fun respondAuth(call: ApplicationCall, result: AuthResult, clientKind: String, status: io.ktor.http.HttpStatusCode) {
        if (clientKind == "WEB" && result.tokens != null) {
            AuthCookies.append(call, AuthCookies.ACCESS, result.tokens.accessToken, 600, AuthCookies.ACCESS_PATH, httpOnly = true, devMode = devMode)
            AuthCookies.append(call, AuthCookies.REFRESH, result.tokens.refreshToken, 604_800, AuthCookies.REFRESH_PATH, httpOnly = true, devMode = devMode)
            result.csrfToken?.let { AuthCookies.append(call, AuthCookies.CSRF, it, 604_800, AuthCookies.CSRF_PATH, httpOnly = false, devMode = devMode) }
            call.respond(status, result.copy(tokens = null, csrfToken = null))
        } else {
            call.respond(status, result)
        }
    }

    private fun findUser(connection: Connection, email: String): LoginUser? =
        connection.prepareStatement(
            "SELECT id, password_hash, status, email_verified_at, locked_until, failed_login_count FROM app.users WHERE email = ?",
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
                    failedLoginCount = result.getInt("failed_login_count"),
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
        csrfToken: String?,
    ) {
        connection.prepareStatement(
            "INSERT INTO app.sessions (id, user_id, client_kind, device_name, device_id, user_agent, absolute_expires_at, csrf_token_hash) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.setObject(2, userId)
            statement.setString(3, clientKind)
            statement.setString(4, device?.deviceName)
            statement.setString(5, device?.deviceId)
            statement.setString(6, null)
            statement.setTimestamp(7, Timestamp.from(absoluteExpires))
            statement.setBytes(8, csrfToken?.let(Tokens::sha256))
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

    private data class LoginUser(
        val id: UUID,
        val passwordHash: String?,
        val status: String,
        val emailVerified: Boolean,
        val lockedUntil: Instant?,
        val failedLoginCount: Int,
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
        val IP_PATTERN = Regex("^[0-9A-Fa-f:.]{2,45}$")
    }
}
