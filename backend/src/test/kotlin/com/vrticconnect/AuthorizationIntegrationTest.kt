package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.DbContext
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.TestTenants
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E02-B15 on native PostgreSQL (VRTIC_TEST_DB=1): permission checks on a real tenant resource,
 * the platform pipeline (acceptance 17) and immediate effect of membership suspension (acceptance 13).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class) // the suspension test changes userB's membership
class AuthorizationIntegrationTest {

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

    private val validUpdate = """{"scheduleChangeDeadlineHours":24,"lateArrivalGraceMinutes":10,"dayOpensAt":"06:30","dayClosesAt":"17:30","workingWeekdays":[1,2,3,4,5,6],"offlineCacheTtlHours":8}"""

    private suspend fun ApplicationTestBuilder.putSettings(token: String, body: String, org: String = tenants.orgA.toString()) =
        client.put("/api/v1/organizations/$org/settings") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(body)
        }

    @Test
    @Order(1)
    fun `every active member reads settings, only ORG_SETTINGS_MANAGE may replace them`() = testApplication {
        startApp()
        for (user in listOf(tenants.userOwner, tenants.userA, tenants.userMulti)) {
            val r = client.get("/api/v1/organizations/${tenants.orgA}/settings") { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(user)}") }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("\"scheduleChangeDeadlineHours\":12"), r.bodyAsText())
        }
        // non-member: 404, never 403
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/organizations/${tenants.orgA}/settings") { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(tenants.userB)}") }.status)

        // ADMIN lacks ORG_SETTINGS_MANAGE -> 403 without detail; nothing changed
        val forbidden = putSettings(tenants.tokenOf(tenants.userA), validUpdate)
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertFalse(forbidden.bodyAsText().contains("ORG_SETTINGS_MANAGE"))
        assertEquals(HttpStatusCode.NotFound, putSettings(tenants.tokenOf(tenants.userB), validUpdate).status, "non-member stays 404 on write")

        // OWNER: validation first, then the replacement is visible to everyone
        val invalid = putSettings(tenants.tokenOf(tenants.userOwner), validUpdate.replace("\"dayClosesAt\":\"17:30\"", "\"dayClosesAt\":\"06:00\""))
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        assertTrue(invalid.bodyAsText().contains("MUST_BE_AFTER_OPENS"))
        val ok = putSettings(tenants.tokenOf(tenants.userOwner), validUpdate)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertTrue(ok.bodyAsText().contains("\"dayOpensAt\":\"06:30\"") && ok.bodyAsText().contains("\"workingWeekdays\":[1,2,3,4,5,6]"), ok.bodyAsText())
        val seenByAdmin = client.get("/api/v1/organizations/${tenants.orgA}/settings") { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(tenants.userA)}") }.bodyAsText()
        assertTrue(seenByAdmin.contains("\"scheduleChangeDeadlineHours\":24"), seenByAdmin)
        // org B untouched
        val b = client.get("/api/v1/organizations/${tenants.orgB}/settings") { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(tenants.userB)}") }.bodyAsText()
        assertTrue(b.contains("\"scheduleChangeDeadlineHours\":12"), b)
    }

    @Test
    @Order(2)
    fun `platform list is 404 for tenant users, 403 without MFA, 200 with MFA and never a tenant right`() = testApplication {
        startApp()
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/platform/organizations") { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(tenants.userOwner)}") }.status)
        val noMfa = client.get("/api/v1/platform/organizations") { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(tenants.platformAdmin)}") }
        assertEquals(HttpStatusCode.Forbidden, noMfa.status)
        assertTrue(noMfa.bodyAsText().contains("MFA_REQUIRED"))

        // MFA is EPIC 02-B11; until then the session flag is set directly to exercise the pipeline
        tenants.runtimeDb.transactionBlocking(DbContext.Auth()) { c ->
            c.prepareStatement("UPDATE app.sessions SET mfa_verified_at = now() WHERE user_id = ?").use { st -> st.setObject(1, tenants.platformAdmin); st.executeUpdate() }
        }
        val list = client.get("/api/v1/platform/organizations?search=fixture&limit=1") { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(tenants.platformAdmin)}") }
        assertEquals(HttpStatusCode.OK, list.status, list.bodyAsText())
        val body = list.bodyAsText()
        // both fixture orgs share one created_at (same transaction), so the page split is by id: one per page
        val firstHasA = body.contains(tenants.orgAName)
        assertTrue(firstHasA xor body.contains(tenants.orgBName), "limit 1 must yield exactly one org: $body")
        val cursor = Regex("\"nextCursor\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        val page2 = client.get("/api/v1/platform/organizations?search=fixture&limit=1&cursor=$cursor") { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(tenants.platformAdmin)}") }.bodyAsText()
        assertTrue(page2.contains(if (firstHasA) tenants.orgBName else tenants.orgAName), page2)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.get("/api/v1/platform/organizations?sort=evil") { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(tenants.platformAdmin)}") }.status)

        // platform mode is not a tenant right: tenant resources stay 404 without a membership / support grant
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/organizations/${tenants.orgA}/settings") { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(tenants.platformAdmin)}") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/platform/organizations").status)
    }

    @Test
    @Order(3)
    fun `suspending a membership takes effect on the next request`() = testApplication {
        startApp()
        val token = tenants.tokenOf(tenants.userB)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/organizations/${tenants.orgB}/settings") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        tenants.runtimeDb.transactionBlocking(DbContext.Tenant(tenants.orgB, tenants.platformAdmin)) { c ->
            c.prepareStatement("UPDATE app.organization_memberships SET status = 'SUSPENDED', suspended_at = now() WHERE id = ?").use { st -> st.setObject(1, tenants.membershipBTeacher); st.executeUpdate() }
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/organizations/${tenants.orgB}/settings") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        // the session itself is still valid: only the tenant is gone
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/auth/session") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
    }
}
