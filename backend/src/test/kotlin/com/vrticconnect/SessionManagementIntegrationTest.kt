package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.DbContext
import com.vrticconnect.modules.auth.Tokens
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.TestTenants
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E02-B08 on native PostgreSQL (VRTIC_TEST_DB=1): session list with cursor pagination, remote
 * revocation of one device (own vs. foreign session), logout from all devices with the
 * recent-authentication requirement, and cookie-mode CSRF enforcement.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class) // later tests revoke sessions the earlier ones list
class SessionManagementIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants

    /** userA: current WEB session from the fixture + two extra sessions created here. */
    private val extraSession1 = UUID.randomUUID()
    private val extraSession2 = UUID.randomUUID()
    private val extraToken1 = Tokens.generate(Tokens.Kind.ACCESS)
    private val extraToken2 = Tokens.generate(Tokens.Kind.ACCESS)
    private val extraRefresh2 = Tokens.generate(Tokens.Kind.REFRESH)

    /** userB: a session logged in "long ago" (recent-auth window expired) and a fresh one. */
    private val staleSession = UUID.randomUUID()
    private val staleToken = Tokens.generate(Tokens.Kind.ACCESS)
    private val freshSession = UUID.randomUUID()
    private val freshToken = Tokens.generate(Tokens.Kind.ACCESS)
    private val csrfToken = Tokens.generate(Tokens.Kind.CSRF)

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
        tenants.runtimeDb.transactionBlocking(DbContext.Auth()) { c ->
            insertSession(c, extraSession1, tenants.userA, "ANDROID", "Pixel 8", "now() - interval '2 hours'", null)
            insertSession(c, extraSession2, tenants.userA, "IOS", "iPhone", "now() - interval '1 hour'", null)
            insertToken(c, extraSession1, extraToken1)
            insertToken(c, extraSession2, extraToken2)
            insertRefresh(c, extraSession2, extraRefresh2)
            insertSession(c, staleSession, tenants.userB, "WEB", "Old laptop", "now() - interval '30 minutes'", csrfToken)
            insertSession(c, freshSession, tenants.userB, "WEB", "New laptop", "now()", csrfToken)
            insertToken(c, staleSession, staleToken)
            insertToken(c, freshSession, freshToken)
        }
    }

    @AfterAll
    fun tearDown() {
        if (enabled) tenants.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = tenants.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    private suspend fun ApplicationTestBuilder.sessionStatus(token: String) =
        client.get("/api/v1/auth/session") { header(HttpHeaders.Authorization, "Bearer $token") }.status

    @Test
    @Order(1)
    fun `session list shows own non-revoked sessions newest first, flags the current one and paginates`() = testApplication {
        startApp()
        val token = tenants.tokenOf(tenants.userA)
        val page1 = client.get("/api/v1/auth/sessions?limit=2") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, page1.status)
        val body1 = page1.bodyAsText()
        // fixture session is the newest (created last), then iPhone, then Pixel
        assertTrue(body1.indexOf("\"isCurrent\":true") < body1.indexOf("iPhone"), body1)
        assertFalse(body1.contains("Pixel 8"), body1)
        assertTrue(body1.contains("\"nextCursor\":\"") , body1)
        val cursor = Regex("\"nextCursor\":\"([^\"]+)\"").find(body1)!!.groupValues[1]

        val page2 = client.get("/api/v1/auth/sessions?limit=2&cursor=$cursor") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, page2.status)
        val body2 = page2.bodyAsText()
        assertTrue(body2.contains("Pixel 8") && body2.contains("\"clientKind\":\"ANDROID\""), body2)
        assertTrue(body2.contains("\"nextCursor\":null"), body2)
        assertFalse(body2.contains("\"isCurrent\":true"), body2)

        // other users' sessions never appear
        assertFalse(body1.contains("laptop") || body2.contains("laptop"))
        assertEquals(HttpStatusCode.UnprocessableEntity, client.get("/api/v1/auth/sessions?limit=0") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.get("/api/v1/auth/sessions?cursor=bad-cursor") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        // malformed percent-encoding (%%%) cannot be sent by the Ktor test client; covered by the curl smoke in VALIDATION_REPORT.md
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/sessions").status)
    }

    @Test
    @Order(2)
    fun `revoking one own session kills its tokens only, foreign sessions answer 404`() = testApplication {
        startApp()
        val token = tenants.tokenOf(tenants.userA)
        assertEquals(HttpStatusCode.OK, sessionStatus(extraToken1))

        val revoked = client.delete("/api/v1/auth/sessions/$extraSession1") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.NoContent, revoked.status)
        assertEquals(HttpStatusCode.Unauthorized, sessionStatus(extraToken1))
        assertEquals(HttpStatusCode.OK, sessionStatus(token), "current session must stay valid")
        assertEquals(HttpStatusCode.OK, sessionStatus(extraToken2), "other own session must stay valid")
        // idempotent
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/auth/sessions/$extraSession1") { header(HttpHeaders.Authorization, "Bearer $token") }.status)

        // userB cannot revoke userA's session (404, not 403) nor an unknown/malformed id
        val foreign = client.delete("/api/v1/auth/sessions/$extraSession2") { header(HttpHeaders.Authorization, "Bearer $freshToken") }
        assertEquals(HttpStatusCode.NotFound, foreign.status)
        assertEquals(HttpStatusCode.OK, sessionStatus(extraToken2))
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/auth/sessions/${UUID.randomUUID()}") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/auth/sessions/nope") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/auth/sessions/$extraSession2").status)
    }

    @Test
    @Order(3)
    fun `logout-all requires recent authentication, then revokes every session and refresh token`() = testApplication {
        startApp()
        // stale session (login 30 min ago, no reauthentication) -> 403 and nothing revoked
        val denied = client.post("/api/v1/auth/logout-all") { header(HttpHeaders.Authorization, "Bearer $staleToken") }
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertTrue(denied.bodyAsText().contains("REAUTHENTICATION_REQUIRED"))
        assertEquals(HttpStatusCode.OK, sessionStatus(staleToken))
        assertEquals(HttpStatusCode.OK, sessionStatus(freshToken))

        // fresh session -> 204, both userB sessions gone, userA untouched
        val ok = client.post("/api/v1/auth/logout-all") { header(HttpHeaders.Authorization, "Bearer $freshToken") }
        assertEquals(HttpStatusCode.NoContent, ok.status)
        assertEquals(HttpStatusCode.Unauthorized, sessionStatus(freshToken))
        assertEquals(HttpStatusCode.Unauthorized, sessionStatus(staleToken))
        assertEquals(HttpStatusCode.OK, sessionStatus(extraToken2))
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/logout-all").status)
    }

    @Test
    @Order(4)
    fun `logout-all revokes refresh tokens too and cookie mode needs csrf`() = testApplication {
        startApp()
        // userA's fixture session is fresh (created in setUp) -> allowed; its iPhone session has a refresh token
        val token = tenants.tokenOf(tenants.userA)
        val ok = client.post("/api/v1/auth/logout-all") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.NoContent, ok.status)
        assertTrue(ok.headers.getAll(HttpHeaders.SetCookie).orEmpty().all { it.contains("Max-Age=0") })
        assertEquals(HttpStatusCode.Unauthorized, sessionStatus(extraToken2))
        val reused = client.post("/api/v1/auth/refresh") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"refreshToken":"$extraRefresh2"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, reused.status, "refresh token of a revoked session must not rotate")

        // cookie mode without CSRF header (valid access cookie of another user): 403 before any revocation
        val cookieOnly = client.post("/api/v1/auth/logout-all") {
            header(HttpHeaders.Cookie, "vc_access=${tenants.tokenOf(tenants.userMulti)}; vc_csrf=$csrfToken")
            header(HttpHeaders.Origin, config.webOrigin)
        }
        assertEquals(HttpStatusCode.Forbidden, cookieOnly.status)
        assertEquals(HttpStatusCode.OK, sessionStatus(tenants.tokenOf(tenants.userMulti)), "nothing revoked on CSRF failure")
    }

    // -- helpers ------------------------------------------------------------------------------

    private fun insertSession(c: Connection, id: UUID, userId: UUID, kind: String, device: String, createdAt: String, csrf: String?) {
        c.prepareStatement(
            "INSERT INTO app.sessions (id, user_id, client_kind, device_name, created_at, last_seen_at, absolute_expires_at, csrf_token_hash) " +
                "VALUES (?, ?, ?, ?, $createdAt, $createdAt, now() + interval '1 day', ?)",
        ).use { st ->
            st.setObject(1, id); st.setObject(2, userId); st.setString(3, kind); st.setString(4, device)
            st.setBytes(5, csrf?.let(Tokens::sha256)); st.executeUpdate()
        }
    }

    private fun insertToken(c: Connection, sessionId: UUID, token: String) {
        c.prepareStatement("INSERT INTO app.access_tokens (session_id, token_hash, expires_at) VALUES (?, ?, now() + interval '10 minutes')").use { st ->
            st.setObject(1, sessionId); st.setBytes(2, Tokens.sha256(token)); st.executeUpdate()
        }
    }

    private fun insertRefresh(c: Connection, sessionId: UUID, token: String) {
        c.prepareStatement("INSERT INTO app.refresh_tokens (session_id, token_hash, idle_expires_at) VALUES (?, ?, now() + interval '7 days')").use { st ->
            st.setObject(1, sessionId); st.setBytes(2, Tokens.sha256(token)); st.executeUpdate()
        }
    }
}
