package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.DbContext
import com.vrticconnect.db.update
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E16 dashboard on native PostgreSQL (VRTIC_TEST_DB=1): counters follow DailyOverviewCounters definitions,
 * only REPORT_VIEW (OWNER/ADMIN) may read, other tenants see 404.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants

    // A Wednesday in the past: deterministic weekday, no "late" computation (only for today).
    private val day: LocalDate = LocalDate.of(2026, 9, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY))

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
        val org = tenants.orgA
        tenants.runtimeDb.transactionBlocking(DbContext.Tenant(org, tenants.platformAdmin)) { c ->
            val location = UUID.randomUUID()
            val group = UUID.randomUUID()
            c.update("INSERT INTO app.locations (id, organization_id, name) VALUES (?, ?, 'Loc')", location, org)
            c.update("INSERT INTO app.groups (id, organization_id, location_id, name) VALUES (?, ?, ?, 'G1')", group, org, location)
            // 4 children: present, checked out, absent (sick), not expected (template: no Wednesday) but present
            val kids = List(4) { UUID.randomUUID() }
            kids.forEachIndexed { i, id ->
                c.update("INSERT INTO app.children (id, organization_id, given_name, family_name, date_of_birth) VALUES (?, ?, ?, 'Test', DATE '2022-01-01')", id, org, "Kid$i")
                c.update("INSERT INTO app.enrollments (organization_id, child_id, group_id, valid_from) VALUES (?, ?, ?, DATE '2025-09-01')", org, id, group)
            }
            val template = UUID.randomUUID()
            c.update(
                "INSERT INTO app.schedule_templates (id, organization_id, child_id, effective_from, created_by_membership_id) VALUES (?, ?, ?, DATE '2025-09-01', ?)",
                template, org, kids[3], tenants.membershipAAdmin,
            )
            c.update("INSERT INTO app.schedule_template_days (organization_id, template_id, weekday, attends) VALUES (?, ?, 3, false)", org, template)
            c.update(
                "INSERT INTO app.absences (organization_id, child_id, kind, date_from, date_to, reported_by_membership_id) VALUES (?, ?, 'SICK', ?, ?, ?)",
                org, kids[2], day, day, tenants.membershipAAdmin,
            )
            // Projection rows only (the dashboard reads attendance_days.status); visits/events are the attendance module's job.
            for ((index, status) in listOf(0 to "CHECKED_IN", 1 to "CHECKED_OUT", 3 to "CHECKED_IN")) {
                val dayId = UUID.randomUUID()
                val eventId = UUID.randomUUID()
                val visitId = UUID.randomUUID()
                c.update(
                    "INSERT INTO app.attendance_days (id, organization_id, child_id, group_id, attendance_date, status, open_visit_id, version, last_event_id) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)",
                    dayId, org, kids[index], group, day, status, if (status == "CHECKED_IN") visitId else null, eventId,
                )
                c.update(
                    "INSERT INTO app.attendance_events (id, organization_id, attendance_day_id, child_id, event_type, occurred_at, actor_membership_id, source, command_id, resulting_version) " +
                        "VALUES (?, ?, ?, ?, 'CHECK_IN', now(), ?, 'WEB', ?, 1)",
                    eventId, org, dayId, kids[index], tenants.membershipAAdmin, UUID.randomUUID(),
                )
                c.update(
                    "INSERT INTO app.attendance_visits (id, organization_id, attendance_day_id, sequence_no, check_in_at, check_out_at, check_in_event_id) VALUES (?, ?, ?, 1, now(), ?, ?)",
                    visitId, org, dayId, if (status == "CHECKED_OUT") java.time.Instant.now().plusSeconds(60) else null, eventId,
                )
            }
        }
    }

    @AfterAll
    fun tearDown() {
        if (enabled) tenants.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = tenants.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    private suspend fun ApplicationTestBuilder.dashboard(user: UUID, org: UUID = tenants.orgA) =
        client.get("/api/v1/organizations/$org/dashboard?date=$day") { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(user)}") }

    @Test
    fun `counters follow the daily overview definitions and only managers may read`() = testApplication {
        startApp()
        val ok = dashboard(tenants.userA)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val body = ok.bodyAsText()
        assertTrue(
            body.contains("\"counters\":{\"expected\":3,\"present\":1,\"departed\":1,\"absent\":1,\"notArrived\":0,\"late\":0,\"unscheduledPresent\":1,\"physicallyPresent\":2}"),
            body,
        )
        assertTrue(body.contains("\"childrenActive\":4") && body.contains("\"absencesToday\":1"), body)
        assertTrue(body.contains("\"name\":\"G1\""), body)
        assertEquals(HttpStatusCode.OK, dashboard(tenants.userOwner).status)
        // PARENT has no REPORT_VIEW -> 403; a member of another tenant only -> 404
        assertEquals(HttpStatusCode.Forbidden, dashboard(tenants.userMulti).status)
        assertEquals(HttpStatusCode.NotFound, dashboard(tenants.userB).status)
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            client.get("/api/v1/organizations/${tenants.orgA}/dashboard?date=31-12-2026") { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(tenants.userA)}") }.status,
        )
    }
}
