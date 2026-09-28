package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.DbContext
import com.vrticconnect.modules.auth.Argon2idPasswordHasher
import com.vrticconnect.modules.auth.Tokens
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.RecordingMailSender
import com.vrticconnect.testing.TestTenants
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E02-B13 on native PostgreSQL (VRTIC_TEST_DB=1): account lockout backed by `login_attempts`,
 * identical behaviour for unknown accounts (no enumeration), per-IP and per-e-mail limits.
 * Acceptance criterion 8: the 11th failed login within 15 min answers 429 with Retry-After,
 * `users.locked_until` is set, and a successful login after unlocking resets the counter.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BruteForceIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants
    private val tag = UUID.randomUUID().toString().take(8)
    private val email = "lockout-$tag@example.test"
    private val password = "Lockout-Test-Password-$tag"
    private var userId: UUID = UUID.randomUUID()

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
        tenants.runtimeDb.transactionBlocking(DbContext.Auth()) { c ->
            val chars = password.toCharArray()
            c.prepareStatement("INSERT INTO app.users (id, email, email_verified_at, password_hash, given_name, family_name) VALUES (?, ?, now(), ?, 'Lock', 'Out')").use { st ->
                st.setObject(1, userId); st.setString(2, email); st.setString(3, Argon2idPasswordHasher(config.argon2).hash(chars)); st.executeUpdate()
            }
            chars.fill('\u0000')
        }
    }

    @AfterAll
    fun tearDown() {
        if (!enabled) return
        // the runtime role may not DELETE users or login_attempts (by design): clean up as owner in maintenance mode
        val ownerDb = com.vrticconnect.db.Database(
            com.zaxxer.hikari.HikariDataSource(
                com.zaxxer.hikari.HikariConfig().apply {
                    jdbcUrl = config.jdbcUrl; username = config.dbOwnerUser; password = config.requireOwnerPassword(); maximumPoolSize = 1; isAutoCommit = false
                },
            ),
        )
        ownerDb.transactionBlocking(DbContext.None) { c ->
            c.createStatement().use { it.execute("SELECT set_config('app.maintenance_mode', 'on', true)") }
            c.prepareStatement("DELETE FROM app.login_attempts WHERE email_hash IN (?, ?)").use { st ->
                st.setBytes(1, Tokens.sha256(email)); st.setBytes(2, Tokens.sha256("ghost-$tag@example.test")); st.executeUpdate()
            }
            c.prepareStatement("DELETE FROM app.users WHERE id = ?").use { st -> st.setObject(1, userId); st.executeUpdate() }
        }
        tenants.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = tenants.runtimeDb, readiness = AlwaysUpProbe, mailSender = RecordingMailSender())) }
    }

    private suspend fun ApplicationTestBuilder.login(mail: String, pw: String): HttpResponse =
        client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$mail","password":"$pw","clientKind":"ANDROID"}""")
        }

    @Test
    fun `tenth failure locks the account, eleventh answers 429, unlock resets the counter`() = testApplication {
        startApp()
        repeat(10) { i -> assertEquals(HttpStatusCode.Unauthorized, login(email, "wrong-$i").status, "attempt ${i + 1}") }

        val locked = login(email, password) // correct password, but locked
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        val retryAfter = locked.headers["Retry-After"]
        assertNotNull(retryAfter)
        assertTrue(retryAfter.toLong() in 1..(15 * 60 + 1), "Retry-After=$retryAfter")
        assertTrue(locked.bodyAsText().contains("RATE_LIMITED"))

        val (failedCount, lockedUntilSet) = tenants.runtimeDb.transactionBlocking(DbContext.Auth()) { c ->
            c.prepareStatement("SELECT failed_login_count, locked_until FROM app.users WHERE id = ?").use { st ->
                st.setObject(1, userId)
                st.executeQuery().use { rs -> rs.next(); rs.getInt(1) to (rs.getTimestamp(2) != null) }
            }
        }
        assertEquals(10, failedCount)
        assertTrue(lockedUntilSet, "users.locked_until must be set after the 10th failure")

        // simulate the lock expiring and the 15-minute window passing
        tenants.runtimeDb.transactionBlocking(DbContext.Auth()) { c ->
            c.prepareStatement("UPDATE app.users SET locked_until = now() - interval '1 second' WHERE id = ?").use { st -> st.setObject(1, userId); st.executeUpdate() }
            c.prepareStatement("UPDATE app.login_attempts SET occurred_at = now() - interval '16 minutes' WHERE email_hash = ?").use { st -> st.setBytes(1, Tokens.sha256(email)); st.executeUpdate() }
        }
        assertEquals(HttpStatusCode.OK, login(email, password).status)
        val afterSuccess = tenants.runtimeDb.transactionBlocking(DbContext.Auth()) { c ->
            c.prepareStatement("SELECT failed_login_count, locked_until FROM app.users WHERE id = ?").use { st ->
                st.setObject(1, userId)
                st.executeQuery().use { rs -> rs.next(); rs.getInt(1) to rs.getTimestamp(2) }
            }
        }
        assertEquals(0, afterSuccess.first)
        assertEquals(null, afterSuccess.second)
    }

    @Test
    fun `unknown accounts get the same 401 then 429 sequence so failures reveal nothing`() = testApplication {
        startApp()
        val ghost = "ghost-$tag@example.test"
        repeat(10) { i -> assertEquals(HttpStatusCode.Unauthorized, login(ghost, "wrong-$i").status) }
        val blocked = login(ghost, "wrong-11")
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertNotNull(blocked.headers["Retry-After"])
    }

    @Test
    fun `forgot-password is limited per e-mail and per client ip`() = testApplication {
        startApp()
        suspend fun forgot(mail: String) = client.post("/api/v1/auth/forgot-password") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$mail"}""")
        }
        repeat(3) { assertEquals(HttpStatusCode.Accepted, forgot("nobody-$tag@example.test").status) }
        val perEmail = forgot("nobody-$tag@example.test")
        assertEquals(HttpStatusCode.TooManyRequests, perEmail.status)
        assertNotNull(perEmail.headers["Retry-After"])

        // per IP: 20 requests per minute across the auth surface (4 used above)
        repeat(16) { i -> assertEquals(HttpStatusCode.Accepted, forgot("someone-$i-$tag@example.test").status, "request ${i + 5}") }
        assertEquals(HttpStatusCode.TooManyRequests, forgot("someone-x-$tag@example.test").status)
        assertEquals(HttpStatusCode.TooManyRequests, login(email, password).status, "the IP bucket is shared with login")
    }
}
