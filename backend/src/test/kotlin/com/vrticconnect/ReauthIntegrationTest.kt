package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.DbContext
import com.vrticconnect.modules.auth.Argon2idPasswordHasher
import com.vrticconnect.modules.auth.Tokens
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.TestTenants
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import kotlin.test.assertTrue

/** E02-B10 on native PostgreSQL (VRTIC_TEST_DB=1): re-authentication unlocks sensitive actions for 5 minutes. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReauthIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants
    private val tag = UUID.randomUUID().toString().take(8)
    private val email = "reauth-$tag@example.test"
    private val password = "Reauth-Test-Password-$tag"
    private val userId = UUID.randomUUID()
    private val staleSession = UUID.randomUUID()
    private val staleToken = Tokens.generate(Tokens.Kind.ACCESS)

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
        tenants.runtimeDb.transactionBlocking(DbContext.Auth()) { c ->
            val chars = password.toCharArray()
            c.prepareStatement("INSERT INTO app.users (id, email, email_verified_at, password_hash, given_name, family_name) VALUES (?, ?, now(), ?, 'Re', 'Auth')").use { st ->
                st.setObject(1, userId); st.setString(2, email); st.setString(3, Argon2idPasswordHasher(config.argon2).hash(chars)); st.executeUpdate()
            }
            chars.fill('\u0000')
            // logged in 30 minutes ago: outside the recent-authentication window
            c.prepareStatement("INSERT INTO app.sessions (id, user_id, client_kind, created_at, last_seen_at, absolute_expires_at) VALUES (?, ?, 'ANDROID', now() - interval '30 minutes', now(), now() + interval '1 day')").use { st ->
                st.setObject(1, staleSession); st.setObject(2, userId); st.executeUpdate()
            }
            c.prepareStatement("INSERT INTO app.access_tokens (session_id, token_hash, expires_at) VALUES (?, ?, now() + interval '10 minutes')").use { st ->
                st.setObject(1, staleSession); st.setBytes(2, Tokens.sha256(staleToken)); st.executeUpdate()
            }
        }
    }

    @AfterAll
    fun tearDown() {
        if (!enabled) return
        val ownerDb = com.vrticconnect.db.Database(
            com.zaxxer.hikari.HikariDataSource(
                com.zaxxer.hikari.HikariConfig().apply {
                    jdbcUrl = config.jdbcUrl; username = config.dbOwnerUser; password = config.requireOwnerPassword(); maximumPoolSize = 1; isAutoCommit = false
                },
            ),
        )
        ownerDb.transactionBlocking(DbContext.None) { c ->
            c.createStatement().use { it.execute("SELECT set_config('app.maintenance_mode', 'on', true)") }
            c.prepareStatement("DELETE FROM app.users WHERE id = ?").use { st -> st.setObject(1, userId); st.executeUpdate() }
        }
        tenants.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = tenants.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    private suspend fun ApplicationTestBuilder.reauth(pw: String) =
        client.post("/api/v1/auth/reauthenticate") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $staleToken")
            setBody("""{"password":"$pw"}""")
        }

    @Test
    fun `stale session must re-authenticate before logout-all, wrong password stamps nothing and is limited`() = testApplication {
        startApp()
        val denied = client.post("/api/v1/auth/logout-all") { header(HttpHeaders.Authorization, "Bearer $staleToken") }
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertTrue(denied.bodyAsText().contains("REAUTHENTICATION_REQUIRED"))

        repeat(4) { i ->
            val wrong = reauth("wrong-$i")
            assertEquals(HttpStatusCode.Forbidden, wrong.status)
            assertTrue(wrong.bodyAsText().contains("INVALID_CREDENTIALS"))
        }
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/auth/logout-all") { header(HttpHeaders.Authorization, "Bearer $staleToken") }.status, "still stale after failures")

        val ok = reauth(password)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val body = ok.bodyAsText()
        assertTrue(body.contains("\"reauthenticatedAt\":\"") && body.contains("\"validUntil\":\""), body)
        val stampedAt = tenants.runtimeDb.transactionBlocking(DbContext.Auth()) { c ->
            c.prepareStatement("SELECT reauthenticated_at FROM app.sessions WHERE id = ?").use { st ->
                st.setObject(1, staleSession); st.executeQuery().use { rs -> rs.next(); rs.getTimestamp(1) }
            }
        }
        assertTrue(stampedAt != null, "reauthenticated_at must be stamped")

        // the 6th attempt in the window is limited (5 allowed), even a correct one
        val limited = reauth(password)
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertTrue(limited.headers["Retry-After"] != null)

        // sensitive action now allowed
        assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/auth/logout-all") { header(HttpHeaders.Authorization, "Bearer $staleToken") }.status)
        assertEquals(HttpStatusCode.Unauthorized, reauth(password).status, "session revoked by logout-all")
    }
}
