package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.DbContext
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.MfaTestSupport
import com.vrticconnect.testing.MfaTestSupport.postJson
import com.vrticconnect.testing.RecordingMailSender
import com.vrticconnect.testing.TestTenants
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Platform administration and the tenant billing view on native PostgreSQL (VRTIC_TEST_DB=1):
 * organization creation with OWNER invitation, suspension, subscription changes, feature overrides,
 * and who may read `/organizations/{id}/subscription` and `/feature-flags`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PlatformAdminIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants
    private val mail = RecordingMailSender()
    private val tag = UUID.randomUUID().toString().take(8)
    private val planId = UUID.randomUUID()
    private val inactivePlanId = UUID.randomUUID()
    private val slug = "platform-test-$tag"
    private val ownerEmail = "platform-owner-$tag@example.test"
    private var createdOrg: UUID? = null

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
        val version = Random.nextInt(100_000, Int.MAX_VALUE - 1)
        tenants.runtimeDb.transactionBlocking(DbContext.Platform(tenants.platformAdmin)) { c ->
            c.prepareStatement(
                "INSERT INTO app.plans (id, code, version, name, monthly_price_minor, entitlements, limits, is_active) VALUES " +
                    "(?, 'STARTER', ?, 'Test $tag', 1500000, '{\"photos_enabled\": true}', '{\"max_children\": 40}', true), " +
                    "(?, 'STARTER', ?, 'Old $tag', 1000000, '{}', '{}', false)",
            ).use { st -> st.setObject(1, planId); st.setInt(2, version); st.setObject(3, inactivePlanId); st.setInt(4, version + 1); st.executeUpdate() }
        }
    }

    @AfterAll
    fun tearDown() {
        if (!enabled) return
        val created = listOfNotNull(createdOrg)
        val registered = tenants.runtimeDb.transactionBlocking(DbContext.Auth()) { c ->
            c.prepareStatement("SELECT id FROM app.users WHERE email = ?").use { st ->
                st.setString(1, ownerEmail); st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
            }
        }
        MfaTestSupport.cleanup(config, created + tenants.orgA + tenants.orgB, created, registered, emptyList())
        tenants.destroy()
        MfaTestSupport.cleanup(config, emptyList(), emptyList(), emptyList(), listOf(planId, inactivePlanId))
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = tenants.runtimeDb, readiness = AlwaysUpProbe, mailSender = mail)) }
    }

    private val admin get() = tenants.tokenOf(tenants.platformAdmin)

    private suspend fun ApplicationTestBuilder.getWith(path: String, token: String) =
        client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun ApplicationTestBuilder.send(method: String, path: String, token: String, body: String) = when (method) {
        "PATCH" -> client.patch(path) { contentType(ContentType.Application.Json); header(HttpHeaders.Authorization, "Bearer $token"); setBody(body) }
        "PUT" -> client.put(path) { contentType(ContentType.Application.Json); header(HttpHeaders.Authorization, "Bearer $token"); setBody(body) }
        else -> error(method)
    }

    private fun markMfaVerified(user: UUID, verified: Boolean = true) {
        tenants.runtimeDb.transactionBlocking(DbContext.Auth()) { c ->
            c.prepareStatement("UPDATE app.sessions SET mfa_verified_at = CASE WHEN ? THEN now() END WHERE user_id = ?").use { st ->
                st.setBoolean(1, verified); st.setObject(2, user); st.executeUpdate()
            }
        }
    }

    private fun createBody(s: String = slug, plan: String = planId.toString(), email: String = ownerEmail) =
        """{"slug":"$s","name":"Platform Test $tag","timezone":"Europe/Belgrade","defaultLocale":"sr-Latn","ownerEmail":"$email","planId":"$plan"}"""

    @Test
    @Order(1)
    fun `create organization sends the OWNER invitation, validates and refuses duplicates`() = testApplication {
        startApp()
        // not a platform admin: 404; platform admin without MFA: 403
        markMfaVerified(tenants.platformAdmin, verified = false)
        assertEquals(HttpStatusCode.NotFound, postJson("/api/v1/platform/organizations", tenants.tokenOf(tenants.userA), createBody()).status)
        val noMfa = postJson("/api/v1/platform/organizations", admin, createBody())
        assertEquals(HttpStatusCode.Forbidden, noMfa.status)
        assertTrue(noMfa.bodyAsText().contains("MFA_REQUIRED"))
        markMfaVerified(tenants.platformAdmin)

        val invalid = postJson("/api/v1/platform/organizations", admin, """{"slug":"Bad Slug","name":"X","ownerEmail":"nope","planId":"x"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        val invalidBody = invalid.bodyAsText()
        assertTrue(listOf("\"slug\"", "\"name\"", "\"ownerEmail\"", "\"planId\"").all { invalidBody.contains(it) }, invalidBody)
        assertTrue(postJson("/api/v1/platform/organizations", admin, createBody(plan = inactivePlanId.toString())).bodyAsText().contains("INACTIVE"))
        assertTrue(postJson("/api/v1/platform/organizations", admin, createBody(plan = UUID.randomUUID().toString())).bodyAsText().contains("NOT_FOUND"))

        val created = postJson("/api/v1/platform/organizations", admin, createBody())
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val body = created.bodyAsText()
        assertTrue(body.contains("\"slug\":\"$slug\"") && body.contains("\"status\":\"ACTIVE\"") && body.contains("\"countryCode\":\"RS\""), body)
        val orgId = UUID.fromString(Regex("\"id\":\"([0-9a-f-]{36})\"").find(body)!!.groupValues[1])
        createdOrg = orgId
        val invitation = mail.sent.last { it.to == ownerEmail }
        assertTrue(invitation.link!!.startsWith("${config.webOrigin}/invite?token=vci_"), invitation.link)
        assertEquals("OWNER", invitation.args["role"])

        val duplicate = postJson("/api/v1/platform/organizations", admin, createBody(email = "other-$tag@example.test"))
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertTrue(duplicate.bodyAsText().contains("SLUG_TAKEN"))

        assertEquals(HttpStatusCode.OK, getWith("/api/v1/platform/organizations/$orgId", admin).status)
        assertEquals(HttpStatusCode.NotFound, getWith("/api/v1/platform/organizations/${UUID.randomUUID()}", admin).status)
        assertTrue(getWith("/api/v1/platform/organizations?search=$slug", admin).bodyAsText().contains(orgId.toString()))
        val plans = getWith("/api/v1/platform/plans?code=STARTER", admin).bodyAsText()
        assertTrue(plans.contains(planId.toString()) && !plans.contains(inactivePlanId.toString()), plans)
        assertTrue(getWith("/api/v1/platform/plans?activeOnly=false", admin).bodyAsText().contains(inactivePlanId.toString()))

        // the invited owner registers and is immediately asked to enroll MFA
        val token = mail.lastTokenFor(ownerEmail)!!
        val registered = postJson(
            "/api/v1/auth/register", "vca_none",
            """{"invitationToken":"$token","password":"Owner-Password-$tag","givenName":"New","familyName":"Owner","device":{"clientKind":"ANDROID"}}""",
        )
        assertEquals(HttpStatusCode.Created, registered.status, registered.bodyAsText())
        assertTrue(registered.bodyAsText().contains("\"mfaEnrollmentRequired\":true"), registered.bodyAsText())
        val ownerToken = MfaTestSupport.accessToken(registered.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, getWith("/api/v1/organizations/$orgId/settings", ownerToken).status)
        assertTrue(getWith("/api/v1/me/memberships", ownerToken).bodyAsText().contains(orgId.toString()))
    }

    @Test
    @Order(2)
    fun `subscription and feature overrides of a tenant`() = testApplication {
        startApp()
        markMfaVerified(tenants.platformAdmin)
        val org = createdOrg!!
        val sub = getWith("/api/v1/platform/organizations/$org/subscription", admin)
        assertEquals(HttpStatusCode.OK, sub.status, sub.bodyAsText())
        assertTrue(sub.bodyAsText().contains("\"status\":\"TRIAL\"") && sub.bodyAsText().contains(planId.toString()), sub.bodyAsText())

        assertEquals(HttpStatusCode.UnprocessableEntity, send("PATCH", "/api/v1/platform/organizations/$org/subscription", admin, """{"status":"ACTIVE"}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, send("PATCH", "/api/v1/platform/organizations/$org/subscription", admin, """{"reason":"nothing"}""").status)
        val active = send("PATCH", "/api/v1/platform/organizations/$org/subscription", admin, """{"status":"ACTIVE","cancelAtPeriodEnd":true,"reason":"paid by invoice"}""")
        assertEquals(HttpStatusCode.OK, active.status, active.bodyAsText())
        assertTrue(active.bodyAsText().contains("\"status\":\"ACTIVE\"") && active.bodyAsText().contains("\"cancelAtPeriodEnd\":true"), active.bodyAsText())
        val events = tenants.runtimeDb.transactionBlocking(DbContext.Tenant(org, tenants.platformAdmin)) { c ->
            c.prepareStatement("SELECT count(*) FROM app.subscription_events").use { st -> st.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
        }
        assertEquals(2, events)
        assertEquals(HttpStatusCode.Conflict, postJson("/api/v1/platform/organizations/$org/subscription", admin, """{"planId":"$planId","status":"ACTIVE","currentPeriodStart":"2026-01-01T00:00:00Z","currentPeriodEnd":"2026-02-01T00:00:00Z"}""").status)

        val flags = getWith("/api/v1/platform/feature-flags", admin).bodyAsText()
        assertTrue(flags.contains("\"key\":\"photos_enabled\""), flags)
        assertTrue(getWith("/api/v1/platform/organizations/$org/feature-flags", admin).bodyAsText().contains("{\"key\":\"photos_enabled\",\"enabled\":true,\"source\":\"PLAN_ENTITLEMENT\"}"))
        val override = send("PUT", "/api/v1/platform/organizations/$org/feature-overrides/photos_enabled", admin, """{"enabled":false,"reason":"customer request"}""")
        assertEquals(HttpStatusCode.OK, override.status, override.bodyAsText())
        assertTrue(getWith("/api/v1/platform/organizations/$org/feature-flags", admin).bodyAsText().contains("{\"key\":\"photos_enabled\",\"enabled\":false,\"source\":\"TENANT_OVERRIDE\"}"))
        assertEquals(HttpStatusCode.NotFound, send("PUT", "/api/v1/platform/organizations/$org/feature-overrides/no_such_flag", admin, """{"enabled":true,"reason":"test"}""").status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/platform/organizations/$org/feature-overrides/photos_enabled") { header(HttpHeaders.Authorization, "Bearer $admin") }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/platform/organizations/$org/feature-overrides/photos_enabled") { header(HttpHeaders.Authorization, "Bearer $admin") }.status)
        assertTrue(getWith("/api/v1/platform/organizations/$org/feature-flags", admin).bodyAsText().contains("{\"key\":\"photos_enabled\",\"enabled\":true,\"source\":\"PLAN_ENTITLEMENT\"}"))
    }

    @Test
    @Order(3)
    fun `suspension blocks members of that organization only and is reversible`() = testApplication {
        startApp()
        markMfaVerified(tenants.platformAdmin)
        val path = "/api/v1/organizations/${tenants.orgB}/settings"
        assertEquals(HttpStatusCode.UnprocessableEntity, postJson("/api/v1/platform/organizations/${tenants.orgB}/deactivate", admin, """{"reason":""}""").status)
        val suspended = postJson("/api/v1/platform/organizations/${tenants.orgB}/deactivate", admin, """{"reason":"unpaid invoices"}""")
        assertEquals(HttpStatusCode.OK, suspended.status, suspended.bodyAsText())
        assertTrue(suspended.bodyAsText().contains("\"status\":\"SUSPENDED\""))
        assertEquals(HttpStatusCode.Conflict, postJson("/api/v1/platform/organizations/${tenants.orgB}/deactivate", admin, """{"reason":"again"}""").status)
        val member = getWith(path, tenants.tokenOf(tenants.userB))
        assertEquals(HttpStatusCode.Forbidden, member.status)
        assertTrue(member.bodyAsText().contains("ORGANIZATION_SUSPENDED"))
        assertEquals(HttpStatusCode.NotFound, getWith(path, tenants.tokenOf(tenants.userA)).status, "non-members still get 404")
        assertEquals(HttpStatusCode.OK, getWith("/api/v1/auth/session", tenants.tokenOf(tenants.userB)).status, "members can still sign in")

        val reactivated = postJson("/api/v1/platform/organizations/${tenants.orgB}/reactivate", admin, """{"reason":"paid"}""")
        assertEquals(HttpStatusCode.OK, reactivated.status, reactivated.bodyAsText())
        assertEquals(HttpStatusCode.OK, getWith(path, tenants.tokenOf(tenants.userB)).status)
        val audit = tenants.runtimeDb.transactionBlocking(DbContext.Platform(tenants.platformAdmin)) { c ->
            c.prepareStatement("SELECT action FROM app.audit_log WHERE actor_user_id = ? ORDER BY id").use { st ->
                st.setObject(1, tenants.platformAdmin); st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }
        assertTrue(
            audit.containsAll(listOf("PLATFORM_ORGANIZATION_CREATED", "INVITATION_CREATED", "PLATFORM_SUBSCRIPTION_UPDATED", "PLATFORM_FEATURE_OVERRIDE_SET", "PLATFORM_ORGANIZATION_SUSPENDED", "PLATFORM_ORGANIZATION_REACTIVATED")),
            audit.toString(),
        )
    }

    @Test
    @Order(4)
    fun `billing view is for OWNER only, feature flags for every member`() = testApplication {
        startApp()
        markMfaVerified(tenants.platformAdmin)
        val create = postJson(
            "/api/v1/platform/organizations/${tenants.orgA}/subscription", admin,
            """{"planId":"$planId","status":"TRIAL","trialEndsAt":"2030-01-31T00:00:00Z","currentPeriodStart":"2030-01-01T00:00:00Z","currentPeriodEnd":"2030-01-31T00:00:00Z"}""",
        )
        assertEquals(HttpStatusCode.Created, create.status, create.bodyAsText())
        val subscription = "/api/v1/organizations/${tenants.orgA}/subscription"
        // an OWNER session must pass MFA first (E02-B12)
        markMfaVerified(tenants.userOwner, verified = false)
        assertEquals(HttpStatusCode.Forbidden, getWith(subscription, tenants.tokenOf(tenants.userOwner)).status)
        markMfaVerified(tenants.userOwner)
        val owner = getWith(subscription, tenants.tokenOf(tenants.userOwner))
        assertEquals(HttpStatusCode.OK, owner.status, owner.bodyAsText())
        assertTrue(owner.bodyAsText().contains("\"status\":\"TRIAL\"") && owner.bodyAsText().contains("\"max_children\":40"), owner.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, getWith(subscription, tenants.tokenOf(tenants.userA)).status, "ADMIN without BILLING_MANAGE")
        assertEquals(HttpStatusCode.Forbidden, getWith(subscription, tenants.tokenOf(tenants.userMulti)).status, "PARENT")
        assertEquals(HttpStatusCode.NotFound, getWith(subscription, tenants.tokenOf(tenants.userB)).status, "other tenant")
        val flags = getWith("/api/v1/organizations/${tenants.orgA}/feature-flags", tenants.tokenOf(tenants.userMulti))
        assertEquals(HttpStatusCode.OK, flags.status)
        assertTrue(flags.bodyAsText().contains("{\"key\":\"photos_enabled\",\"enabled\":true,\"source\":\"PLAN_ENTITLEMENT\"}"), flags.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, getWith("/api/v1/organizations/${tenants.orgA}/feature-flags", tenants.tokenOf(tenants.userB)).status)
    }
}
