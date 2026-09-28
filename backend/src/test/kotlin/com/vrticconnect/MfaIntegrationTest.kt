package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.DbContext
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.MfaTestSupport
import com.vrticconnect.testing.MfaTestSupport.accessToken
import com.vrticconnect.testing.MfaTestSupport.login
import com.vrticconnect.testing.MfaTestSupport.postJson
import com.vrticconnect.testing.TestTenants
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E02-B11/B12 on native PostgreSQL (VRTIC_TEST_DB=1): TOTP enrollment, per-session verification,
 * single-use recovery codes and the MFA policy for OWNER members and platform admins.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MfaIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants
    private val tag = UUID.randomUUID().toString().take(8)
    private val password = "Mfa-Test-Password-$tag"
    private val ownerId = UUID.randomUUID()
    private val ownerEmail = "mfa-owner-$tag@example.test"
    private val adminId = UUID.randomUUID()
    private val adminEmail = "mfa-admin-$tag@example.test"
    private val teacherId = UUID.randomUUID()
    private val teacherEmail = "mfa-teacher-$tag@example.test"

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
        tenants.runtimeDb.transactionBlocking(DbContext.Auth()) { c ->
            MfaTestSupport.insertPasswordUser(c, config, ownerId, ownerEmail, password, "Owner")
            MfaTestSupport.insertPasswordUser(c, config, adminId, adminEmail, password, "Admin")
            MfaTestSupport.insertPasswordUser(c, config, teacherId, teacherEmail, password, "Teacher")
            c.prepareStatement("INSERT INTO app.platform_admins (user_id, note) VALUES (?, 'mfa test')").use { st -> st.setObject(1, adminId); st.executeUpdate() }
        }
        tenants.runtimeDb.transactionBlocking(DbContext.Tenant(tenants.orgA, tenants.platformAdmin)) { c ->
            for ((user, role) in listOf(ownerId to "OWNER", teacherId to "TEACHER")) {
                c.prepareStatement("INSERT INTO app.organization_memberships (organization_id, user_id, role, status, accepted_at) VALUES (?, ?, ?, 'ACTIVE', now())").use { st ->
                    st.setObject(1, tenants.orgA); st.setObject(2, user); st.setString(3, role); st.executeUpdate()
                }
            }
        }
    }

    @AfterAll
    fun tearDown() {
        if (!enabled) return
        MfaTestSupport.cleanup(config, emptyList(), emptyList(), listOf(ownerId, adminId, teacherId), emptyList())
        tenants.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = tenants.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    private suspend fun ApplicationTestBuilder.getWith(path: String, token: String) =
        client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }

    @Test
    fun `owner enrolls, confirms, verifies later sessions and recovery codes are single use`() = testApplication {
        startApp()
        val settings = "/api/v1/organizations/${tenants.orgA}/settings"

        // 1. OWNER without MFA: login answers enrollment required, the session is limited to /auth and /me
        val first = login(ownerEmail, password)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertTrue(first.bodyAsText().contains("\"status\":\"AUTHENTICATED\"") && first.bodyAsText().contains("\"mfaEnrollmentRequired\":true"), first.bodyAsText())
        val t1 = accessToken(first.bodyAsText())
        val blocked = getWith(settings, t1)
        assertEquals(HttpStatusCode.Forbidden, blocked.status)
        assertTrue(blocked.bodyAsText().contains("MFA_ENROLLMENT_REQUIRED"))
        assertEquals(HttpStatusCode.OK, getWith("/api/v1/me", t1).status)
        val session = getWith("/api/v1/auth/session", t1).bodyAsText()
        assertTrue(session.contains("\"mfaEnrollmentRequired\":true") && session.contains("\"mfaVerified\":false"), session)

        // 2. setup (fresh login counts as recent authentication) -> standard otpauth URI, secret never stored in clear
        val setup = postJson("/api/v1/auth/mfa/totp/setup", t1)
        assertEquals(HttpStatusCode.OK, setup.status, setup.bodyAsText())
        val setupBody = setup.bodyAsText()
        assertTrue(setupBody.contains("otpauth://totp/Vrtic%20Connect:") && setupBody.contains("issuer=Vrtic%20Connect") && setupBody.contains("algorithm=SHA1&digits=6&period=30"), setupBody)
        val secret = MfaTestSupport.secretOf(setupBody)
        val stored = tenants.runtimeDb.transactionBlocking(DbContext.Auth(ownerId)) { c ->
            c.prepareStatement("SELECT secret_enc, key_id, verified_at FROM app.user_mfa_methods WHERE user_id = ? AND revoked_at IS NULL").use { st ->
                st.setObject(1, ownerId); st.executeQuery().use { rs -> rs.next(); Triple(rs.getBytes(1), rs.getString(2), rs.getTimestamp(3)) }
            }
        }
        assertFalse(stored.first.toList().windowed(secret.size).any { it == secret.toList() }, "secret must be encrypted at rest")
        assertEquals("dev-1", stored.second)
        assertEquals(null, stored.third, "pending until confirmed")

        // 3. confirm: malformed 422, wrong code 422 INVALID_CODE, right code -> 10 recovery codes, session verified
        assertEquals(HttpStatusCode.UnprocessableEntity, postJson("/api/v1/auth/mfa/totp/confirm", t1, """{"code":"12ab"}""").status)
        val wrong = postJson("/api/v1/auth/mfa/totp/confirm", t1, """{"code":"${MfaTestSupport.wrongCode(secret)}"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, wrong.status)
        assertTrue(wrong.bodyAsText().contains("INVALID_CODE"))
        assertEquals(HttpStatusCode.Forbidden, getWith(settings, t1).status, "still limited after a wrong code")
        val confirm = postJson("/api/v1/auth/mfa/totp/confirm", t1, """{"code":"${MfaTestSupport.code(secret)}"}""")
        assertEquals(HttpStatusCode.OK, confirm.status, confirm.bodyAsText())
        val codes = MfaTestSupport.recoveryCodes(confirm.bodyAsText())
        assertEquals(10, codes.size, confirm.bodyAsText())
        val hashes = tenants.runtimeDb.transactionBlocking(DbContext.Auth(ownerId)) { c ->
            c.prepareStatement("SELECT count(*) FROM app.user_mfa_recovery_codes WHERE user_id = ? AND used_at IS NULL").use { st ->
                st.setObject(1, ownerId); st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertEquals(10, hashes)
        assertEquals(HttpStatusCode.OK, getWith(settings, t1).status, "confirming proves possession for this session")
        assertEquals(HttpStatusCode.Conflict, postJson("/api/v1/auth/mfa/totp/setup", t1).status, "a second enrollment needs the first removed")

        // 4. a new login is MFA_REQUIRED; the limited session cannot touch tenant data, password or sessions
        val second = login(ownerEmail, password)
        assertTrue(second.bodyAsText().contains("\"status\":\"MFA_REQUIRED\""), second.bodyAsText())
        val t2 = accessToken(second.bodyAsText())
        val limited = getWith(settings, t2)
        assertEquals(HttpStatusCode.Forbidden, limited.status)
        assertTrue(limited.bodyAsText().contains("MFA_REQUIRED"))
        assertEquals(HttpStatusCode.Forbidden, postJson("/api/v1/me/password", t2, """{"currentPassword":"$password","newPassword":"Another-Password-$tag"}""").status)
        assertEquals(HttpStatusCode.Forbidden, postJson("/api/v1/auth/logout-all", t2).status)
        assertEquals(HttpStatusCode.Forbidden, postJson("/api/v1/auth/mfa/totp/setup", t2).status)
        assertEquals(HttpStatusCode.OK, getWith("/api/v1/auth/session", t2).status)
        assertTrue(getWith("/api/v1/auth/session", t2).bodyAsText().contains("\"mfaRequired\":true"))

        // 5. verify with a wrong code fails, a recovery code works once
        assertEquals(HttpStatusCode.UnprocessableEntity, postJson("/api/v1/auth/mfa/totp/verify", t2, """{"code":"${MfaTestSupport.wrongCode(secret)}"}""").status)
        val viaRecovery = postJson("/api/v1/auth/mfa/totp/verify", t2, """{"code":"${codes[0].lowercase()}"}""")
        assertEquals(HttpStatusCode.OK, viaRecovery.status, viaRecovery.bodyAsText())
        assertTrue(viaRecovery.bodyAsText().contains("\"status\":\"AUTHENTICATED\""))
        assertEquals(HttpStatusCode.OK, getWith(settings, t2).status)

        val third = accessToken(login(ownerEmail, password).bodyAsText())
        val reused = postJson("/api/v1/auth/mfa/totp/verify", third, """{"code":"${codes[0]}"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, reused.status, "a recovery code is single use")
        // TOTP of the next step (the confirm step was already consumed: no replay)
        val viaTotp = postJson("/api/v1/auth/mfa/totp/verify", third, """{"code":"${MfaTestSupport.code(secret, 1)}"}""")
        assertEquals(HttpStatusCode.OK, viaTotp.status, viaTotp.bodyAsText())
        assertEquals(HttpStatusCode.OK, getWith(settings, third).status)

        // 6. regenerate: fresh login counts as recent auth; old unused codes stop working
        val regenerated = postJson("/api/v1/auth/mfa/recovery-codes/regenerate", third)
        assertEquals(HttpStatusCode.OK, regenerated.status, regenerated.bodyAsText())
        val newCodes = MfaTestSupport.recoveryCodes(regenerated.bodyAsText())
        assertEquals(10, newCodes.size)
        val fourth = accessToken(login(ownerEmail, password).bodyAsText())
        assertEquals(HttpStatusCode.UnprocessableEntity, postJson("/api/v1/auth/mfa/totp/verify", fourth, """{"code":"${codes[1]}"}""").status)
        assertEquals(HttpStatusCode.OK, postJson("/api/v1/auth/mfa/totp/verify", fourth, """{"code":"${newCodes[0]}"}""").status)

        // 7. audit trail
        val actions = tenants.runtimeDb.transactionBlocking(DbContext.Platform(tenants.platformAdmin)) { c ->
            c.prepareStatement("SELECT action FROM app.audit_log WHERE actor_user_id = ?").use { st ->
                st.setObject(1, ownerId); st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }
        assertTrue(actions.containsAll(listOf("MFA_TOTP_SETUP_STARTED", "MFA_TOTP_ENABLED", "MFA_VERIFIED", "MFA_RECOVERY_CODES_REGENERATED", "MFA_VERIFY_FAILED")), actions.toString())
    }

    @Test
    fun `platform routes need a verified session and non-owners without MFA are not limited`() = testApplication {
        startApp()
        val login = login(adminEmail, password)
        assertTrue(login.bodyAsText().contains("\"mfaEnrollmentRequired\":true"), login.bodyAsText())
        val token = accessToken(login.bodyAsText())
        val denied = getWith("/api/v1/platform/organizations", token)
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertTrue(denied.bodyAsText().contains("MFA_REQUIRED"))

        val secret = MfaTestSupport.secretOf(postJson("/api/v1/auth/mfa/totp/setup", token).bodyAsText())
        assertEquals(HttpStatusCode.OK, postJson("/api/v1/auth/mfa/totp/confirm", token, """{"code":"${MfaTestSupport.code(secret)}"}""").status)
        assertEquals(HttpStatusCode.OK, getWith("/api/v1/platform/organizations", token).status)

        // a new session of the enrolled admin is blocked until verify
        val next = accessToken(login(adminEmail, password).bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, getWith("/api/v1/platform/organizations", next).status)
        assertEquals(HttpStatusCode.OK, postJson("/api/v1/auth/mfa/totp/verify", next, """{"code":"${MfaTestSupport.code(secret, 1)}"}""").status)
        assertEquals(HttpStatusCode.OK, getWith("/api/v1/platform/organizations", next).status)

        // MFA is optional for a TEACHER: no enrollment flag, tenant routes work without it
        val teacher = login(teacherEmail, password)
        assertTrue(teacher.bodyAsText().contains("\"mfaEnrollmentRequired\":false") && teacher.bodyAsText().contains("\"status\":\"AUTHENTICATED\""), teacher.bodyAsText())
        assertEquals(HttpStatusCode.OK, getWith("/api/v1/organizations/${tenants.orgA}/settings", accessToken(teacher.bodyAsText())).status)

        // an OWNER session that has not passed MFA is limited as well (tenant 403, own profile 200)
        tenants.runtimeDb.transactionBlocking(DbContext.Auth()) { c ->
            c.prepareStatement("UPDATE app.sessions SET mfa_verified_at = NULL WHERE user_id = ?").use { st -> st.setObject(1, tenants.userOwner); st.executeUpdate() }
        }
        val fixtureOwner = getWith("/api/v1/organizations/${tenants.orgA}/settings", tenants.tokenOf(tenants.userOwner))
        assertEquals(HttpStatusCode.Forbidden, fixtureOwner.status)
        assertTrue(fixtureOwner.bodyAsText().contains("MFA_ENROLLMENT_REQUIRED"))
        assertEquals(HttpStatusCode.OK, getWith("/api/v1/me", tenants.tokenOf(tenants.userOwner)).status)
        assertEquals(HttpStatusCode.Unauthorized, postJson("/api/v1/auth/mfa/totp/verify", "vca_invalid", """{"code":"123456"}""").status)
    }
}
