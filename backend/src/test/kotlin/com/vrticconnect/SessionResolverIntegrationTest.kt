package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.modules.auth.Argon2idPasswordHasher
import com.vrticconnect.modules.auth.DatabaseSessionResolver
import com.vrticconnect.modules.auth.Tokens
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Native PostgreSQL proof for E02-B01. Skipped unless VRTIC_TEST_DB=1 is explicitly enabled. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionResolverIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var runtimePool: HikariDataSource
    private lateinit var ownerPool: HikariDataSource
    private lateinit var runtimeDb: Database
    private lateinit var ownerDb: Database

    private val userId = UUID.randomUUID()
    private val validSessionId = UUID.randomUUID()
    private val revokedSessionId = UUID.randomUUID()
    private val refreshSessionId = UUID.randomUUID()
    private val validToken = Tokens.generate(Tokens.Kind.ACCESS)
    private val expiredToken = Tokens.generate(Tokens.Kind.ACCESS)
    private val revokedToken = Tokens.generate(Tokens.Kind.ACCESS)
    private val revokedSessionToken = Tokens.generate(Tokens.Kind.ACCESS)
    private val refreshToken = Tokens.generate(Tokens.Kind.REFRESH)
    private val testPassword = "correct horse battery staple"
    private val email = "session-test-${userId}@example.test"

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1 — integration test skipped")
        runtimePool = pool(config.dbRuntimeUser, config.requireRuntimePassword(), "session-runtime")
        ownerPool = pool(config.dbOwnerUser, config.requireOwnerPassword(), "session-owner")
        runtimeDb = Database(runtimePool)
        ownerDb = Database(ownerPool)

        runtimeDb.transactionBlocking(DbContext.Auth()) { connection ->
            val passwordChars = testPassword.toCharArray()
            val passwordHash = try {
                Argon2idPasswordHasher(config.argon2).hash(passwordChars)
            } finally {
                passwordChars.fill('\u0000')
            }
            connection.prepareStatement(
                "INSERT INTO app.users (id, email, password_hash, email_verified_at, given_name, family_name) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, email)
                statement.setString(3, passwordHash)
                statement.setObject(4, java.sql.Timestamp.from(Instant.now()))
                statement.setString(5, "Session")
                statement.setString(6, "Test")
                statement.executeUpdate()
            }
            insertSession(connection, validSessionId, null)
            insertSession(connection, revokedSessionId, "now()")
            insertSession(connection, refreshSessionId, null, "ANDROID")
            insertToken(connection, validSessionId, validToken, "now() + interval '10 minutes'", null)
            insertToken(connection, validSessionId, expiredToken, "now() - interval '1 minute'", null)
            insertToken(connection, validSessionId, revokedToken, "now() + interval '10 minutes'", "now()")
            insertToken(connection, revokedSessionId, revokedSessionToken, "now() + interval '10 minutes'", null)
            insertRefreshToken(connection, refreshSessionId, refreshToken)
        }
    }

    @AfterAll
    fun tearDown() {
        if (!enabled) return
        ownerDb.transactionBlocking(DbContext.None) { connection ->
            connection.createStatement().use { it.execute("SELECT set_config('app.maintenance_mode', 'on', true)") }
            connection.prepareStatement("DELETE FROM app.users WHERE id = ?").use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
        }
        runtimePool.close()
        ownerPool.close()
    }

    @Test
    fun `valid token authenticates while expired or revoked credentials answer 401`() = testApplication {
        application {
            module(
                AppDependencies(
                    config = config,
                    database = runtimeDb,
                    readiness = AlwaysUpProbe,
                    sessionResolver = DatabaseSessionResolver(runtimeDb),
                ),
            )
        }

        val currentSession = requestWith(validToken)
        assertEquals(HttpStatusCode.OK, currentSession.status)
        assertTrue(currentSession.bodyAsText().contains(email))
        assertEquals(HttpStatusCode.Unauthorized, requestWith(expiredToken).status)
        assertEquals(HttpStatusCode.Unauthorized, requestWith(revokedToken).status)
        assertEquals(HttpStatusCode.Unauthorized, requestWith(revokedSessionToken).status)
    }

    @Test
    fun `login issues opaque bearer tokens for a verified user`() = testApplication {
        application {
            module(
                AppDependencies(
                    config = config,
                    database = runtimeDb,
                    readiness = AlwaysUpProbe,
                    sessionResolver = DatabaseSessionResolver(runtimeDb),
                ),
            )
        }

        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"$testPassword","clientKind":"ANDROID"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\":\"AUTHENTICATED\""))
        assertTrue(body.contains("\"accessToken\":\"vca_"))
        assertTrue(body.contains("\"refreshToken\":\"vcr_"))
    }

    @Test
    fun `refresh rotates once and reuse revokes the session`() = testApplication {
        application {
            module(
                AppDependencies(
                    config = config,
                    database = runtimeDb,
                    readiness = AlwaysUpProbe,
                    sessionResolver = DatabaseSessionResolver(runtimeDb),
                ),
            )
        }

        val response = client.post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val rotated = response.bodyAsText()
        assertTrue(rotated.contains("\"status\":\"AUTHENTICATED\""))
        assertTrue(rotated.contains("\"accessToken\":\"vca_"))
        assertTrue(rotated.contains("\"refreshToken\":\"vcr_"))

        val reused = client.post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, reused.status)
    }

    @Test
    fun `web login sets HttpOnly cookies and session bootstrap reads access cookie`() = testApplication {
        application {
            module(
                AppDependencies(
                    config = config,
                    database = runtimeDb,
                    readiness = AlwaysUpProbe,
                    sessionResolver = DatabaseSessionResolver(runtimeDb),
                ),
            )
        }

        val login = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"$testPassword","clientKind":"WEB"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val cookies = login.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        val accessCookie = cookies.first { it.startsWith("vc_access=") }
        assertTrue(accessCookie.contains("HttpOnly"))
        val refreshCookie = cookies.first { it.startsWith("vc_refresh=") }
        assertTrue(refreshCookie.contains("HttpOnly"))
        val csrfCookie = cookies.first { it.startsWith("vc_csrf=") }
        assertFalse(csrfCookie.contains("HttpOnly"))
        val csrfToken = csrfCookie.substringBefore(';').substringAfter('=')
        assertFalse(login.bodyAsText().contains("vca_"))

        val current = client.get("/api/v1/auth/session") {
            header(HttpHeaders.Cookie, accessCookie.substringBefore(';'))
        }
        assertEquals(HttpStatusCode.OK, current.status)
        assertTrue(current.bodyAsText().contains(email))

        val refreshed = client.post("/api/v1/auth/refresh") {
            header(HttpHeaders.Cookie, "${refreshCookie.substringBefore(';')}; ${csrfCookie.substringBefore(';')}")
            header(HttpHeaders.Origin, config.webOrigin)
            header("X-CSRF-Token", csrfToken)
        }
        assertEquals(HttpStatusCode.OK, refreshed.status)
        val refreshedCookies = refreshed.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        val refreshedAccessCookie = refreshedCookies.first { it.startsWith("vc_access=") }
        assertTrue(refreshedCookies.any { it.startsWith("vc_refresh=") && it.contains("HttpOnly") })
        assertFalse(refreshed.bodyAsText().contains("vca_"))

        val refreshedSession = client.get("/api/v1/auth/session") {
            header(HttpHeaders.Cookie, refreshedAccessCookie.substringBefore(';'))
        }
        assertEquals(HttpStatusCode.OK, refreshedSession.status)
        assertTrue(refreshedSession.bodyAsText().contains(email))
    }

    @Test
    fun `web logout requires csrf and revokes the current session`() = testApplication {
        application {
            module(
                AppDependencies(
                    config = config,
                    database = runtimeDb,
                    readiness = AlwaysUpProbe,
                    sessionResolver = DatabaseSessionResolver(runtimeDb),
                ),
            )
        }

        val login = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"$testPassword","clientKind":"WEB"}""")
        }
        val cookies = login.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        val accessCookie = cookies.first { it.startsWith("vc_access=") }.substringBefore(';')
        val csrfCookie = cookies.first { it.startsWith("vc_csrf=") }.substringBefore(';')
        val csrfToken = csrfCookie.substringAfter('=')
        val cookieHeader = "$accessCookie; $csrfCookie"

        val denied = client.post("/api/v1/auth/logout") {
            header(HttpHeaders.Cookie, cookieHeader)
            header(HttpHeaders.Origin, config.webOrigin)
        }
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/auth/session") {
            header(HttpHeaders.Cookie, accessCookie)
        }.status)

        val logout = client.post("/api/v1/auth/logout") {
            header(HttpHeaders.Cookie, cookieHeader)
            header(HttpHeaders.Origin, config.webOrigin)
            header("X-CSRF-Token", csrfToken)
        }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertTrue(logout.headers.getAll(HttpHeaders.SetCookie).orEmpty().all { it.contains("Max-Age=0") })
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/session") {
            header(HttpHeaders.Cookie, accessCookie)
        }.status)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.requestWith(token: String) =
        client.get("/api/v1/auth/session") { header(HttpHeaders.Authorization, "Bearer $token") }

    private fun pool(user: String, password: String, name: String) = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = user
            this.password = password
            maximumPoolSize = 1
            minimumIdle = 1
            poolName = name
            isAutoCommit = false
        },
    )

    private fun insertSession(connection: java.sql.Connection, sessionId: UUID, revokedAt: String?, clientKind: String = "WEB") {
        val revokedExpression = revokedAt ?: "NULL"
        connection.prepareStatement(
            "INSERT INTO app.sessions (id, user_id, client_kind, absolute_expires_at, revoked_at) " +
                "VALUES (?, ?, ?, now() + interval '1 hour', $revokedExpression)",
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.setObject(2, userId)
            statement.setString(3, clientKind)
            statement.executeUpdate()
        }
    }

    private fun insertToken(connection: java.sql.Connection, sessionId: UUID, token: String, expiresAt: String, revokedAt: String?) {
        val revokedExpression = revokedAt ?: "NULL"
        connection.prepareStatement(
            "INSERT INTO app.access_tokens (session_id, token_hash, expires_at, revoked_at) " +
                "VALUES (?, ?, $expiresAt, $revokedExpression)",
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.setBytes(2, Tokens.sha256(token))
            statement.executeUpdate()
        }
    }

    private fun insertRefreshToken(connection: java.sql.Connection, sessionId: UUID, token: String) {
        connection.prepareStatement(
            "INSERT INTO app.refresh_tokens (session_id, token_hash, idle_expires_at) VALUES (?, ?, now() + interval '7 days')",
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.setBytes(2, Tokens.sha256(token))
            statement.executeUpdate()
        }
    }
}
