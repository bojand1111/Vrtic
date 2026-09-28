package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.modules.health.AlwaysUpProbe
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
 * E02-B14 on native PostgreSQL (VRTIC_TEST_DB=1): session -> membership -> RLS transaction.
 * Covers brief section 27 scenarios: two tenants, user with memberships in several tenants,
 * revoked membership, 401/404 ordering that never reveals another tenant's organization.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantContextIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
    }

    @AfterAll
    fun tearDown() {
        if (enabled) tenants.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = tenants.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    private suspend fun ApplicationTestBuilder.ping(org: String, token: String?) =
        client.get("/api/v1/organizations/$org/ping") { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    @Test
    fun `member reaches the tenant transaction and sees only its organization`() = testApplication {
        startApp()
        val response = ping(tenants.orgA.toString(), tenants.tokenOf(tenants.userA))
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"organizationName\":\"${tenants.orgAName}\""), body)
        assertTrue(body.contains("\"role\":\"ADMIN\""), body)
        assertTrue(body.contains("\"membershipId\":\"${tenants.membershipAAdmin}\""), body)
        assertTrue(body.contains("CHILD_HEALTH_READ"), body)
    }

    @Test
    fun `non-member, revoked member, unknown and malformed organization all answer 404`() = testApplication {
        startApp()
        assertEquals(HttpStatusCode.NotFound, ping(tenants.orgB.toString(), tenants.tokenOf(tenants.userA)).status, "revoked membership")
        assertEquals(HttpStatusCode.NotFound, ping(tenants.orgA.toString(), tenants.tokenOf(tenants.userB)).status, "other tenant")
        assertEquals(HttpStatusCode.NotFound, ping(UUID.randomUUID().toString(), tenants.tokenOf(tenants.userA)).status, "unknown org")
        assertEquals(HttpStatusCode.NotFound, ping("not-a-uuid", tenants.tokenOf(tenants.userA)).status, "malformed id")
        // platform admin has no membership: platform mode is a separate pipeline, not an implicit tenant right
        assertEquals(HttpStatusCode.NotFound, ping(tenants.orgA.toString(), tenants.tokenOf(tenants.platformAdmin)).status, "platform admin")
    }

    @Test
    fun `missing or invalid credentials answer 401 before any organization lookup`() = testApplication {
        startApp()
        assertEquals(HttpStatusCode.Unauthorized, ping(tenants.orgA.toString(), null).status)
        assertEquals(HttpStatusCode.Unauthorized, ping("not-a-uuid", "vca_" + "x".repeat(43)).status)
    }

    @Test
    fun `multi-tenant user gets the role of each organization separately`() = testApplication {
        startApp()
        val a = ping(tenants.orgA.toString(), tenants.tokenOf(tenants.userMulti))
        val b = ping(tenants.orgB.toString(), tenants.tokenOf(tenants.userMulti))
        assertEquals(HttpStatusCode.OK, a.status)
        assertEquals(HttpStatusCode.OK, b.status)
        assertTrue(a.bodyAsText().contains("\"role\":\"PARENT\""))
        assertTrue(b.bodyAsText().contains("\"role\":\"TEACHER\""))
        assertFalse(a.bodyAsText().contains(tenants.orgBName))
    }

    @Test
    fun `me memberships lists active memberships with organization, permissions and status filter`() = testApplication {
        startApp()
        val multi = client.get("/api/v1/me/memberships") { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(tenants.userMulti)}") }
        assertEquals(HttpStatusCode.OK, multi.status)
        val multiBody = multi.bodyAsText()
        assertTrue(multiBody.contains(tenants.orgAName) && multiBody.contains(tenants.orgBName), multiBody)
        assertEquals(2, Regex("\"role\":\"").findAll(multiBody).count(), multiBody)

        val a = client.get("/api/v1/me/memberships") { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(tenants.userA)}") }
        val aBody = a.bodyAsText()
        assertTrue(aBody.contains(tenants.orgAName), aBody)
        assertFalse(aBody.contains(tenants.orgBName), "revoked membership must not be listed: $aBody")
        assertTrue(aBody.contains("\"permissions\":[\"CHILD_HEALTH_READ\"]"), aBody)
        assertTrue(aBody.contains("\"status\":\"ACTIVE\""), aBody)

        val suspended = client.get("/api/v1/me/memberships?status=SUSPENDED") { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(tenants.userA)}") }
        assertEquals(HttpStatusCode.OK, suspended.status)
        assertEquals("{\"items\":[]}", suspended.bodyAsText())

        val invalid = client.get("/api/v1/me/memberships?status=REVOKED") { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(tenants.userA)}") }
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me/memberships").status)
    }
}
