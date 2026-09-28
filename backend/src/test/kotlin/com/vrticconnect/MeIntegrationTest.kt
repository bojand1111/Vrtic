package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.DbContext
import com.vrticconnect.modules.auth.Argon2idPasswordHasher
import com.vrticconnect.modules.auth.Tokens
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.TestTenants
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** E02-B16 on native PostgreSQL (VRTIC_TEST_DB=1): own profile, locale, password change with session revocation. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class) // the password test revokes the sessions the others use
class MeIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants
    private val tag = UUID.randomUUID().toString().take(8)
    private val email = "me-$tag@example.test"
    private val password = "Me-Test-Password-$tag"
    private val userId = UUID.randomUUID()
    private val sessionA = UUID.randomUUID()
    private val sessionB = UUID.randomUUID()
    private val tokenA = Tokens.generate(Tokens.Kind.ACCESS)
    private val tokenB = Tokens.generate(Tokens.Kind.ACCESS)

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
        tenants.runtimeDb.transactionBlocking(DbContext.Auth()) { c ->
            val chars = password.toCharArray()
            c.prepareStatement("INSERT INTO app.users (id, email, email_verified_at, password_hash, given_name, family_name) VALUES (?, ?, now(), ?, 'Me', 'Test')").use { st ->
                st.setObject(1, userId); st.setString(2, email); st.setString(3, Argon2idPasswordHasher(config.argon2).hash(chars)); st.executeUpdate()
            }
            chars.fill('\u0000')
            for ((sid, tok) in listOf(sessionA to tokenA, sessionB to tokenB)) {
                c.prepareStatement("INSERT INTO app.sessions (id, user_id, client_kind, absolute_expires_at) VALUES (?, ?, 'ANDROID', now() + interval '1 day')").use { st ->
                    st.setObject(1, sid); st.setObject(2, userId); st.executeUpdate()
                }
                c.prepareStatement("INSERT INTO app.access_tokens (session_id, token_hash, expires_at) VALUES (?, ?, now() + interval '10 minutes')").use { st ->
                    st.setObject(1, sid); st.setBytes(2, Tokens.sha256(tok)); st.executeUpdate()
                }
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
            c.prepareStatement("DELETE FROM app.login_attempts WHERE email_hash = ?").use { st -> st.setBytes(1, Tokens.sha256(email)); st.executeUpdate() }
            c.prepareStatement("DELETE FROM app.users WHERE id = ?").use { st -> st.setObject(1, userId); st.executeUpdate() }
        }
        tenants.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = tenants.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String, token: String) =
        client.post(path) { contentType(ContentType.Application.Json); header(HttpHeaders.Authorization, "Bearer $token"); setBody(body) }

    @Test
    @Order(1)
    fun `profile reflects the account and platform admin flag`() = testApplication {
        startApp()
        val me = client.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $tokenA") }
        assertEquals(HttpStatusCode.OK, me.status)
        val body = me.bodyAsText()
        assertTrue(body.contains("\"email\":\"$email\"") && body.contains("\"givenName\":\"Me\"") && body.contains("\"isPlatformAdmin\":false") && body.contains("\"mfaEnabled\":false"), body)
        assertTrue(body.contains("\"preferredLocale\":\"sr-Latn\""), body)
        assertFalse(body.contains("password"), "no hash material in the profile")
        val admin = client.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(tenants.platformAdmin)}") }.bodyAsText()
        assertTrue(admin.contains("\"isPlatformAdmin\":true"), admin)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me").status)
    }

    @Test
    @Order(2)
    fun `locale update validates the enum and is visible immediately`() = testApplication {
        startApp()
        val bad = client.put("/api/v1/me/locale") { contentType(ContentType.Application.Json); header(HttpHeaders.Authorization, "Bearer $tokenA"); setBody("""{"preferredLocale":"de"}""") }
        assertEquals(HttpStatusCode.UnprocessableEntity, bad.status)
        val ok = client.put("/api/v1/me/locale") { contentType(ContentType.Application.Json); header(HttpHeaders.Authorization, "Bearer $tokenA"); setBody("""{"preferredLocale":"sr-Cyrl"}""") }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.bodyAsText().contains("\"preferredLocale\":\"sr-Cyrl\""))
        assertTrue(client.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $tokenA") }.bodyAsText().contains("\"preferredLocale\":\"sr-Cyrl\""))
    }

    @Test
    @Order(3)
    fun `password change needs the current password, obeys the policy and revokes the other sessions only`() = testApplication {
        startApp()
        val newPassword = "Changed-Me-Password-$tag"
        val wrong = postJson("/api/v1/me/password", """{"currentPassword":"nope-$tag","newPassword":"$newPassword"}""", tokenA)
        assertEquals(HttpStatusCode.Forbidden, wrong.status)
        assertTrue(wrong.bodyAsText().contains("CURRENT_PASSWORD_INVALID"))
        val weak = postJson("/api/v1/me/password", """{"currentPassword":"$password","newPassword":"short"}""", tokenA)
        assertEquals(HttpStatusCode.UnprocessableEntity, weak.status)
        val same = postJson("/api/v1/me/password", """{"currentPassword":"$password","newPassword":"$password"}""", tokenA)
        assertEquals(HttpStatusCode.UnprocessableEntity, same.status)
        assertTrue(same.bodyAsText().contains("SAME_AS_CURRENT"))
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $tokenB") }.status, "nothing revoked on failure")

        assertEquals(HttpStatusCode.NoContent, postJson("/api/v1/me/password", """{"currentPassword":"$password","newPassword":"$newPassword"}""", tokenA).status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $tokenA") }.status, "current session survives")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $tokenB") }.status, "other session revoked")

        val oldLogin = client.post("/api/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"email":"$email","password":"$password","clientKind":"IOS"}""") }
        assertEquals(HttpStatusCode.Unauthorized, oldLogin.status)
        val newLogin = client.post("/api/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"email":"$email","password":"$newPassword","clientKind":"IOS"}""") }
        assertEquals(HttpStatusCode.OK, newLogin.status)
        // proving the password counts as re-authentication: logout-all is allowed right away
        assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/auth/logout-all") { header(HttpHeaders.Authorization, "Bearer $tokenA") }.status)
    }
}
