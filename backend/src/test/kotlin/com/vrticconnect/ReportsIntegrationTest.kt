package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.DbContext
import com.vrticconnect.db.update
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.TestTenants
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Attendance summary on a small fixture week (Mon..Fri three weeks ago), CSV export permission/format and
 * the audit log listing (VRTIC_TEST_DB=1).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportsIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants
    private lateinit var s: ChildrenTestSupport
    private lateinit var group: UUID
    private lateinit var monday: LocalDate
    private val zone = ZoneId.of("Europe/Belgrade")

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
        s = ChildrenTestSupport(tenants)
        val org = tenants.orgA
        group = s.group(org, s.location(org), "Leptirići")
        monday = s.today.with(DayOfWeek.MONDAY).minusWeeks(3)
        val kid1 = s.child(org, "Đorđe", "Šaković", group)
        val kid2 = s.child(org, "Ana", "Bolesna", group)
        val kid3 = s.child(org, "Treći", "=Cmd", group)
        val (tue, wed, thu, fri) = listOf(1L, 2L, 3L, 4L).map { monday.plusDays(it) }
        fun at(d: LocalDate, h: Int, m: Int) = d.atTime(LocalTime.of(h, m)).atZone(zone).toOffsetDateTime()
        tenants.runtimeDb.transactionBlocking(DbContext.Tenant(org, tenants.platformAdmin)) { c ->
            // kid1: template Mon-Fri 08:00-16:00; present Mon, late Tue (09:00), not arrived Wed, present Fri
            val tpl = UUID.randomUUID()
            c.update(
                "INSERT INTO app.schedule_templates (id, organization_id, child_id, effective_from, created_by_membership_id) VALUES (?, ?, ?, ?, ?)",
                tpl, org, kid1, monday.minusDays(30), tenants.membershipAAdmin,
            )
            for (w in 1..5) {
                c.update(
                    "INSERT INTO app.schedule_template_days (organization_id, template_id, weekday, attends, arrival_time, departure_time) VALUES (?, ?, ?, true, TIME '08:00', TIME '16:00')",
                    org, tpl, w,
                )
            }
            for ((d, h) in listOf(monday to 8, tue to 9, fri to 7)) {
                c.update(
                    "INSERT INTO app.attendance_days (organization_id, child_id, group_id, attendance_date, status, is_expected, expected_arrival, expected_departure, first_check_in_at, last_check_out_at, visits_count) " +
                        "VALUES (?, ?, ?, ?, 'CHECKED_OUT', true, TIME '08:00', TIME '16:00', ?, ?, 1)",
                    org, kid1, group, d, at(d, h, 5), at(d, 15, 0),
                )
            }
            // kid2 (no template: working weekdays): sick Mon-Tue
            c.update(
                "INSERT INTO app.absences (organization_id, child_id, kind, date_from, date_to, reported_by_membership_id) VALUES (?, ?, 'SICK', ?, ?, ?)",
                org, kid2, monday, tue, tenants.membershipAAdmin,
            )
            // kid3: override "not attending" on Wednesday but came (unscheduled)
            c.update(
                "INSERT INTO app.schedule_day_overrides (organization_id, child_id, override_date, attends, created_by_membership_id) VALUES (?, ?, ?, false, ?)",
                org, kid3, wed, tenants.membershipAAdmin,
            )
            c.update(
                "INSERT INTO app.attendance_days (organization_id, child_id, group_id, attendance_date, status, is_expected, is_unscheduled, first_check_in_at, last_check_out_at, visits_count) " +
                    "VALUES (?, ?, ?, ?, 'CHECKED_OUT', false, true, ?, ?, 1)",
                org, kid3, group, wed, at(wed, 8, 0), at(wed, 12, 0),
            )
            // organization-wide closure on Thursday
            c.update("INSERT INTO app.closure_days (organization_id, closure_date, name) VALUES (?, ?, 'Praznik')", org, thu)
            // audit entry with a metadata key outside the allowlist (must not be returned)
            c.update(
                "INSERT INTO app.audit_log (actor_user_id, organization_id, action, entity_type, result, metadata) VALUES (?, ?, 'TEST_METADATA', 'TEST', 'SUCCESS', '{\"role\":\"ADMIN\",\"secret\":\"x\"}'::jsonb)",
                tenants.userA, org,
            )
        }
    }

    @AfterAll
    fun tearDown() {
        if (enabled) tenants.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = tenants.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    private suspend fun ApplicationTestBuilder.getWith(user: UUID, path: String, accept: String? = null) =
        client.get(path) {
            header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(user)}")
            accept?.let { header(HttpHeaders.Accept, it) }
        }

    @Test
    fun `attendance summary counts child-days by the daily overview definitions`() = testApplication {
        startApp()
        with(s) {
            val a = base(tenants.orgA)
            val url = "$a/reports/attendance-summary?from=$monday&to=${monday.plusDays(4)}"
            val r = client.getAs(tenants.userA, url)
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val body = r.json()
            val totals = body.jsonObject.getValue("totals")
            // Thursday closed -> 4 working days; kid1 4 expected / 3 present / 1 late / 1 not arrived;
            // kid2 4 expected / 2 absent / 2 not arrived; kid3 3 expected / 3 not arrived / 1 unscheduled
            assertEquals("4", totals.str("workingDays"))
            assertEquals("11", totals.str("expectedChildDays"))
            assertEquals("3", totals.str("presentChildDays"))
            assertEquals("2", totals.str("absentChildDays"))
            assertEquals("6", totals.str("notArrivedChildDays"))
            assertEquals("1", totals.str("unscheduledChildDays"))
            assertEquals("1", totals.str("lateArrivals"))
            assertEquals("27.3", totals.str("attendanceRatePct"))
            val g = body.arr("byGroup").single()
            assertEquals("Leptirići", g.str("groupName"))
            assertEquals("11", g.str("expectedChildDays"))
            val kid2 = body.arr("byChild").first { it.str("givenName") == "Ana" }
            assertEquals("2", kid2.str("absentSickDays"))
            assertEquals(5, body.arr("byDate").size)
            val thursday = body.arr("byDate")[3].jsonObject.getValue("counters")
            assertEquals("0", thursday.str("expected"))

            // validation, permissions, scope
            assertEquals(HttpStatusCode.UnprocessableEntity, client.getAs(tenants.userA, "$a/reports/attendance-summary?from=$monday").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.getAs(tenants.userA, "$a/reports/attendance-summary?from=$monday&to=${monday.minusDays(1)}").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.getAs(tenants.userA, "$a/reports/attendance-summary?from=$monday&to=${monday.plusDays(400)}").status)
            assertEquals(HttpStatusCode.Forbidden, client.getAs(tenants.userMulti, url).status)
            assertEquals(HttpStatusCode.NotFound, client.getAs(tenants.userB, url).status)
            assertEquals(HttpStatusCode.NotFound, client.getAs(tenants.userA, "$url&groupId=${UUID.randomUUID()}").status)
            assertEquals("11", client.getAs(tenants.userA, "$url&groupId=$group").json().jsonObject.getValue("totals").str("expectedChildDays"))
        }
    }

    @Test
    fun `csv export needs REPORT_EXPORT, is excel friendly and audited, and the audit log is filtered and paged`() = testApplication {
        startApp()
        with(s) {
            val a = base(tenants.orgA)
            val url = "$a/reports/attendance-summary?from=$monday&to=${monday.plusDays(4)}"
            // ADMIN has REPORT_VIEW but not REPORT_EXPORT
            assertEquals(HttpStatusCode.Forbidden, client.getAs(tenants.userA, "$url&format=csv").status)
            assertEquals(HttpStatusCode.Forbidden, getWith(tenants.userA, url, accept = "text/csv").status)
            val csv = client.getAs(tenants.userOwner, "$url&format=csv")
            assertEquals(HttpStatusCode.OK, csv.status, csv.bodyAsText())
            assertTrue(csv.headers[HttpHeaders.ContentType]!!.startsWith("text/csv"))
            assertTrue(csv.headers[HttpHeaders.ContentDisposition]!!.contains("attachment"))
            val bytes = csv.bodyAsBytes()
            assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF })
            val text = String(bytes, Charsets.UTF_8)
            assertTrue(text.contains("Grupa;Dete;Očekivano dana"), text)
            assertTrue(text.contains("Leptirići;Šaković Đorđe;4;3;0;0;0;1;0;1;75,0"), text)
            assertTrue(text.contains(";'=Cmd Treći;"), text)
            assertTrue(text.contains("Ukupno;;11;3;2;0;0;6;1;1;27,3"), text)
            assertEquals(HttpStatusCode.OK, getWith(tenants.userOwner, url, accept = "text/csv").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.getAs(tenants.userOwner, "$url&format=pdf").status)

            // audit log: export recorded; filters; allowlisted metadata; paging; permissions
            val exported = client.getAs(tenants.userA, "$a/audit-log?action=REPORT_EXPORTED")
            assertEquals(HttpStatusCode.OK, exported.status, exported.bodyAsText())
            val entries = exported.json().arr("items")
            assertEquals(2, entries.size)
            assertEquals("ATTENDANCE_SUMMARY_CSV", entries[0].str("purpose"))
            assertEquals("REPORT", entries[0].str("entityType"))
            val meta = client.getAs(tenants.userA, "$a/audit-log?entityType=TEST").json().arr("items").single().jsonObject.getValue("metadata").jsonObject
            assertEquals(setOf("role"), meta.keys)
            assertEquals(0, client.getAs(tenants.userA, "$a/audit-log?actorUserId=${UUID.randomUUID()}").json().arr("items").size)
            assertEquals(0, client.getAs(tenants.userA, "$a/audit-log?action=REPORT_EXPORTED&from=${s.today.plusDays(1)}").json().arr("items").size)
            val page1 = client.getAs(tenants.userA, "$a/audit-log?limit=1").json()
            val cursor = page1.str("nextCursor")
            assertNotNull(cursor)
            val page2 = client.getAs(tenants.userA, "$a/audit-log?limit=1&cursor=$cursor").json()
            assertNotEquals(page1.arr("items").single().str("id"), page2.arr("items").single().str("id"))
            assertEquals(HttpStatusCode.UnprocessableEntity, client.getAs(tenants.userA, "$a/audit-log?action=bad").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.getAs(tenants.userA, "$a/audit-log?cursor=%21%21").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.getAs(tenants.userA, "$a/audit-log?result=MAYBE").status)
            assertEquals(HttpStatusCode.Forbidden, client.getAs(tenants.userMulti, "$a/audit-log").status)
            assertEquals(HttpStatusCode.NotFound, client.getAs(tenants.userB, "$a/audit-log").status)
            assertEquals(HttpStatusCode.Forbidden, client.getAs(tenants.userB, "${base(tenants.orgB)}/audit-log").status)
        }
    }
}
