package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.DbContext
import com.vrticconnect.modules.auth.AccountService
import com.vrticconnect.modules.auth.Argon2idPasswordHasher
import com.vrticconnect.modules.auth.CsrfService
import com.vrticconnect.modules.auth.LoginService
import com.vrticconnect.modules.auth.Tokens
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.RecordingMailSender
import com.vrticconnect.testing.TestTenants
import io.ktor.client.request.get
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
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E02-B04/B05/B09/B20 on native PostgreSQL (VRTIC_TEST_DB=1): invitation preview and registration,
 * acceptance by an existing account, e-mail verification, password reset. Mail is captured by
 * [RecordingMailSender]; nothing is logged.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class) // flows build on each other (registered user -> reset)
class AccountFlowsIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants
    private val mail = RecordingMailSender()

    private val tag = UUID.randomUUID().toString().take(8)
    private val newEmail = "invited-$tag@example.test"
    private val strongPassword = "Correct-Horse-Battery-$tag"
    private val invNew = Tokens.generate(Tokens.Kind.INVITE)
    private val invNewAgain = Tokens.generate(Tokens.Kind.INVITE)   // second invitation to the same new e-mail
    private val invExisting = Tokens.generate(Tokens.Kind.INVITE)   // addressed to userMulti (ADMIN in A)
    private val invExpired = Tokens.generate(Tokens.Kind.INVITE)
    private val invOther = Tokens.generate(Tokens.Kind.INVITE)      // addressed to someone else
    private val unverifiedEmail = "unverified-$tag@example.test"
    private val unverifiedPassword = "Unverified-Account-$tag"
    private var registeredAccessToken: String = ""

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
        tenants.runtimeDb.transactionBlocking(DbContext.Tenant(tenants.orgA, tenants.platformAdmin)) { c ->
            for ((token, email, role, expires) in listOf(
                arrayOf(invNew, newEmail, "TEACHER", "now() + interval '7 days'"),
                arrayOf(invNewAgain, newEmail, "TEACHER", "now() + interval '7 days'"),
                arrayOf(invExisting, tenants.emailOf(tenants.userMulti), "ADMIN", "now() + interval '7 days'"),
                arrayOf(invExpired, "expired-$tag@example.test", "TEACHER", "now() - interval '1 hour'"),
                arrayOf(invOther, "other-$tag@example.test", "TEACHER", "now() + interval '7 days'"),
            )) {
                c.prepareStatement(
                    "INSERT INTO app.invitations (organization_id, email, role, token_hash, invited_by, expires_at) VALUES (?, ?, ?, ?, ?, $expires)",
                ).use { st ->
                    st.setObject(1, tenants.orgA); st.setString(2, email); st.setString(3, role)
                    st.setBytes(4, Tokens.sha256(token)); st.setObject(5, tenants.platformAdmin); st.executeUpdate()
                }
            }
        }
        tenants.runtimeDb.transactionBlocking(DbContext.Auth()) { c ->
            val chars = unverifiedPassword.toCharArray()
            c.prepareStatement("INSERT INTO app.users (email, password_hash, given_name, family_name) VALUES (?, ?, 'Un', 'Verified')").use { st ->
                st.setString(1, unverifiedEmail); st.setString(2, Argon2idPasswordHasher(config.argon2).hash(chars)); st.executeUpdate()
            }
            chars.fill('\u0000')
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
            // accepted invitations reference the user (no cascade by design): remove them before the users
            c.prepareStatement("DELETE FROM app.invitations WHERE accepted_user_id IN (SELECT id FROM app.users WHERE email IN (?, ?))").use { st -> st.setString(1, newEmail); st.setString(2, unverifiedEmail); st.executeUpdate() }
            c.prepareStatement("DELETE FROM app.users WHERE email IN (?, ?)").use { st -> st.setString(1, newEmail); st.setString(2, unverifiedEmail); st.executeUpdate() }
        }
        tenants.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application {
            val db = tenants.runtimeDb
            val login = LoginService(db, Argon2idPasswordHasher(config.argon2), config.isDev, CsrfService(db, config))
            module(
                AppDependencies(
                    config = config, database = db, readiness = AlwaysUpProbe,
                    loginService = login, mailSender = mail,
                    accountService = AccountService(db, Argon2idPasswordHasher(config.argon2), login, mail, config),
                ),
            )
        }
    }

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String, token: String? = null) =
        client.post(path) {
            contentType(ContentType.Application.Json)
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }

    @Test
    @Order(1)
    fun `invitation preview reveals org and role only for live tokens`() = testApplication {
        startApp()
        val ok = client.get("/api/v1/auth/invitations/$invNew")
        assertEquals(HttpStatusCode.OK, ok.status)
        val body = ok.bodyAsText()
        assertTrue(body.contains(tenants.orgAName) && body.contains("\"role\":\"TEACHER\"") && body.contains("\"requiresRegistration\":true"), body)
        val existing = client.get("/api/v1/auth/invitations/$invExisting").bodyAsText()
        assertTrue(existing.contains("\"requiresRegistration\":false"), existing)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/auth/invitations/$invExpired").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/auth/invitations/${Tokens.generate(Tokens.Kind.INVITE)}").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/auth/invitations/not-a-token").status)
    }

    @Test
    @Order(2)
    fun `registration through an invitation creates a verified user, an ACTIVE membership and a session`() = testApplication {
        startApp()
        fun body(token: String, password: String) =
            """{"invitationToken":"$token","password":"$password","givenName":"Nova","familyName":"Vaspitačica","preferredLocale":"sr-Latn","device":{"clientKind":"ANDROID","deviceName":"Pixel"}}"""

        val weak = postJson("/api/v1/auth/register", body(invNew, "short"))
        assertEquals(HttpStatusCode.UnprocessableEntity, weak.status)
        assertTrue(weak.bodyAsText().contains("TOO_SHORT"))
        assertEquals(HttpStatusCode.NotFound, postJson("/api/v1/auth/register", body(invExpired, strongPassword)).status)

        val created = postJson("/api/v1/auth/register", body(invNew, strongPassword))
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val createdBody = created.bodyAsText()
        assertTrue(createdBody.contains("\"status\":\"AUTHENTICATED\"") && createdBody.contains("\"accessToken\":\"vca_"), createdBody)
        registeredAccessToken = Regex("\"accessToken\":\"(vca_[^\"]+)\"").find(createdBody)!!.groupValues[1]
        assertTrue(mail.sent.none { it.to == newEmail }, "invitation token proves the mailbox: no verification mail")

        // the new member reaches its tenant with the invited role
        val ping = client.get("/api/v1/organizations/${tenants.orgA}/ping") { header(HttpHeaders.Authorization, "Bearer $registeredAccessToken") }
        assertEquals(HttpStatusCode.OK, ping.status)
        assertTrue(ping.bodyAsText().contains("\"role\":\"TEACHER\""))

        // same token again: consumed -> 404 without effect; second invitation to the now-registered e-mail -> 409
        assertEquals(HttpStatusCode.NotFound, postJson("/api/v1/auth/register", body(invNew, strongPassword)).status)
        val conflict = postJson("/api/v1/auth/register", body(invNewAgain, strongPassword))
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertTrue(conflict.bodyAsText().contains("EMAIL_ALREADY_REGISTERED"))

        // and the registered user can log in normally
        val login = postJson("/api/v1/auth/login", """{"email":"$newEmail","password":"$strongPassword","clientKind":"IOS"}""")
        assertEquals(HttpStatusCode.OK, login.status)
        assertTrue(login.bodyAsText().contains("\"status\":\"AUTHENTICATED\""))
    }

    @Test
    @Order(3)
    fun `existing user accepts an invitation for its own e-mail only`() = testApplication {
        startApp()
        val token = tenants.tokenOf(tenants.userMulti)
        val mismatch = postJson("/api/v1/me/invitations/accept", """{"invitationToken":"$invOther"}""", token)
        assertEquals(HttpStatusCode.NotFound, mismatch.status)

        val accepted = postJson("/api/v1/me/invitations/accept", """{"invitationToken":"$invExisting"}""", token)
        assertEquals(HttpStatusCode.Created, accepted.status, accepted.bodyAsText())
        val body = accepted.bodyAsText()
        assertTrue(body.contains("\"role\":\"ADMIN\"") && body.contains("\"status\":\"ACTIVE\"") && body.contains(tenants.orgAName), body)

        // highest role now scopes tenant requests; the invitation is consumed
        val ping = client.get("/api/v1/organizations/${tenants.orgA}/ping") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertTrue(ping.bodyAsText().contains("\"role\":\"ADMIN\""), ping.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, postJson("/api/v1/me/invitations/accept", """{"invitationToken":"$invExisting"}""", token).status)
        assertEquals(HttpStatusCode.Unauthorized, postJson("/api/v1/me/invitations/accept", """{"invitationToken":"$invOther"}""").status)
    }

    @Test
    @Order(4)
    fun `unverified account cannot log in until the e-mailed token is consumed once`() = testApplication {
        startApp()
        val blocked = postJson("/api/v1/auth/login", """{"email":"$unverifiedEmail","password":"$unverifiedPassword","clientKind":"ANDROID"}""")
        assertEquals(HttpStatusCode.OK, blocked.status)
        assertTrue(blocked.bodyAsText().contains("EMAIL_VERIFICATION_REQUIRED") && !blocked.bodyAsText().contains("vca_"))

        assertEquals(HttpStatusCode.Accepted, postJson("/api/v1/auth/resend-verification", """{"email":"nobody-$tag@example.test"}""").status)
        assertTrue(mail.sent.none { it.to.startsWith("nobody-") })
        assertEquals(HttpStatusCode.Accepted, postJson("/api/v1/auth/resend-verification", """{"email":"$unverifiedEmail"}""").status)
        val verifyToken = mail.lastTokenFor(unverifiedEmail)
        assertNotNull(verifyToken)
        assertTrue(verifyToken.startsWith("vce_"))

        assertEquals(HttpStatusCode.NotFound, postJson("/api/v1/auth/verify-email", """{"token":"${Tokens.generate(Tokens.Kind.VERIFY)}"}""").status)
        assertEquals(HttpStatusCode.NoContent, postJson("/api/v1/auth/verify-email", """{"token":"$verifyToken"}""").status)
        assertEquals(HttpStatusCode.NotFound, postJson("/api/v1/auth/verify-email", """{"token":"$verifyToken"}""").status, "single use")

        val login = postJson("/api/v1/auth/login", """{"email":"$unverifiedEmail","password":"$unverifiedPassword","clientKind":"ANDROID"}""")
        assertTrue(login.bodyAsText().contains("\"status\":\"AUTHENTICATED\""), login.bodyAsText())
        // already verified: resend sends nothing
        val before = mail.sent.size
        assertEquals(HttpStatusCode.Accepted, postJson("/api/v1/auth/resend-verification", """{"email":"$unverifiedEmail"}""").status)
        assertEquals(before, mail.sent.size)
    }

    @Test
    @Order(5)
    fun `password reset is single use, revokes all sessions and enforces the policy`() = testApplication {
        startApp()
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/auth/session") { header(HttpHeaders.Authorization, "Bearer $registeredAccessToken") }.status)

        assertEquals(HttpStatusCode.Accepted, postJson("/api/v1/auth/forgot-password", """{"email":"unknown-$tag@example.test"}""").status)
        assertTrue(mail.sent.none { it.to.startsWith("unknown-") })
        assertEquals(HttpStatusCode.Accepted, postJson("/api/v1/auth/forgot-password", """{"email":"$newEmail"}""").status)
        val resetToken = mail.lastTokenFor(newEmail)
        assertNotNull(resetToken)
        assertTrue(resetToken.startsWith("vcp_"))

        val weak = postJson("/api/v1/auth/reset-password", """{"token":"$resetToken","newPassword":"$newEmail"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, weak.status)
        assertTrue(weak.bodyAsText().contains("MATCHES_EMAIL"))
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/auth/session") { header(HttpHeaders.Authorization, "Bearer $registeredAccessToken") }.status, "failed reset must not revoke")

        val newPassword = "Brand-New-Secret-$tag"
        assertEquals(HttpStatusCode.NoContent, postJson("/api/v1/auth/reset-password", """{"token":"$resetToken","newPassword":"$newPassword"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/session") { header(HttpHeaders.Authorization, "Bearer $registeredAccessToken") }.status, "PASSWORD_CHANGED revokes sessions")
        assertEquals(HttpStatusCode.NotFound, postJson("/api/v1/auth/reset-password", """{"token":"$resetToken","newPassword":"$newPassword"}""").status, "single use")

        val old = postJson("/api/v1/auth/login", """{"email":"$newEmail","password":"$strongPassword","clientKind":"IOS"}""")
        assertEquals(HttpStatusCode.Unauthorized, old.status)
        val fresh = postJson("/api/v1/auth/login", """{"email":"$newEmail","password":"$newPassword","clientKind":"IOS"}""")
        assertEquals(HttpStatusCode.OK, fresh.status)
        assertFalse(mail.sent.any { it.link?.contains("vca_") == true })
    }
}
