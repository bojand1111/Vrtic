package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.TestTenants
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Weekly schedule templates, week view and expected children on native PostgreSQL (VRTIC_TEST_DB=1). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchedulesIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants
    private lateinit var s: ChildrenTestSupport
    private lateinit var groupA: UUID
    private lateinit var childA: UUID
    private lateinit var guardian: UUID
    private lateinit var groupB: UUID
    private lateinit var childB: UUID

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
        s = ChildrenTestSupport(tenants)
        groupA = s.group(tenants.orgA, s.location(tenants.orgA), "A")
        childA = s.child(tenants.orgA, "Nikola", "Raspored", groupA)
        guardian = s.confirmedGuardian(tenants.orgA, childA, tenants.membershipMultiA, canManageSchedule = false)
        groupB = s.group(tenants.orgB, s.location(tenants.orgB), "B")
        s.assignTeacher(tenants.orgB, tenants.membershipBTeacher, groupB)
        childB = s.child(tenants.orgB, "Teodora", "B", groupB)
    }

    @AfterAll
    fun tearDown() {
        if (enabled) {
            ScheduleTestCleanup.removeChangeLog(config, tenants.orgA, tenants.orgB)
            tenants.destroy()
        }
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = tenants.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    private fun template(from: String, arrival: String = "08:00", saturday: Boolean = false): String {
        val days = (1..7).joinToString(",") { w ->
            val attends = w <= 5 || (w == 6 && saturday)
            if (attends) """{"weekday":$w,"attends":true,"arrivalTime":"$arrival","departureTime":"16:00"}""" else """{"weekday":$w,"attends":false}"""
        }
        return """{"effectiveFrom":"$from","days":[$days]}"""
    }

    @Test
    fun `templates are replaced from a date, validated against org hours and computed into week and group views`() = testApplication {
        startApp()
        with(s) {
            val a = base(tenants.orgA)
            val b = base(tenants.orgB)
            val path = "$a/children/$childA/schedule/templates"
            val first = client.postAs(tenants.userA, path, template(today.toString()))
            assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
            assertEquals(7, first.json().arr("days").size)
            val same = client.putAs(tenants.userA, path, template(today.toString(), arrival = "07:30"))
            assertEquals(HttpStatusCode.OK, same.status, same.bodyAsText())
            assertEquals("2", same.json().str("version"))
            val next = today.plusDays(7)
            assertEquals(HttpStatusCode.Created, client.postAs(tenants.userA, path, template(next.toString())).status)
            val items = client.getAs(tenants.userA, path).json().arr("items")
            assertEquals(listOf(next.toString(), today.toString()), items.map { it.str("effectiveFrom") })
            assertEquals(next.minusDays(1).toString(), items[1].str("effectiveTo"))

            // validation: opening hours (06:00..18:00 default), working weekdays, past effectiveFrom, 7 days
            val early = client.postAs(tenants.userA, path, template(next.toString(), arrival = "05:00"))
            assertEquals(HttpStatusCode.UnprocessableEntity, early.status)
            assertTrue(early.bodyAsText().contains("OUTSIDE_OPENING_HOURS"), early.bodyAsText())
            assertTrue(client.postAs(tenants.userA, path, template(next.toString(), saturday = true)).bodyAsText().contains("NOT_WORKING_DAY"))
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postAs(tenants.userA, path, template(today.minusDays(1).toString())).status)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postAs(tenants.userA, path, """{"effectiveFrom":"$next","days":[]}""").status)

            // parent: needs can_manage_schedule; teacher has no SCHEDULE_MANAGE
            assertEquals(HttpStatusCode.Forbidden, client.postAs(tenants.userMulti, path, template(next.toString())).status)
            s.setGuardianFlag(tenants.orgA, guardian, "can_manage_schedule", true)
            assertEquals(HttpStatusCode.OK, client.postAs(tenants.userMulti, path, template(next.toString(), arrival = "09:00")).status)
            assertEquals(HttpStatusCode.OK, client.getAs(tenants.userMulti, path).status)
            assertEquals(HttpStatusCode.Forbidden, client.postAs(tenants.userB, "$b/children/$childB/schedule/templates", template(today.toString())).status)

            // week view: next week's Monday uses the 09:00 template; an absence wins over the template
            val monday = next.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            client.postAs(tenants.userA, "$a/absences", """{"childId":"$childA","kind":"SICK","dateFrom":"${monday.plusDays(1)}","dateTo":"${monday.plusDays(1)}"}""")
            val week = client.getAs(tenants.userMulti, "$a/children/$childA/schedule/week?weekStart=$monday")
            assertEquals(HttpStatusCode.OK, week.status, week.bodyAsText())
            val days = week.json().arr("days")
            assertEquals(7, days.size)
            assertEquals("09:00", days[0].str("expectedArrival"))
            assertEquals("TEMPLATE", days[0].str("source"))
            assertEquals("ABSENCE", days[1].str("source"))
            assertEquals("false", days[5].str("isExpected"))
            assertEquals(HttpStatusCode.UnprocessableEntity, client.getAs(tenants.userA, "$a/children/$childA/schedule/week?weekStart=${monday.plusDays(1)}").status)

            // expected children of a group on a date
            val expected = client.getAs(tenants.userA, "$a/schedules/expected?groupId=$groupA&date=${monday.plusDays(1)}").json()
            val row = expected.arr("items").single().jsonObject
            assertEquals("ABSENCE", row.str("source"))
            assertEquals("SICK", row.str("absenceKind"))
            assertEquals("09:00", client.getAs(tenants.userA, "$a/schedules/expected?groupId=$groupA&date=$monday").json().arr("items").single().str("expectedArrival"))
            assertEquals(HttpStatusCode.Forbidden, client.getAs(tenants.userMulti, "$a/schedules/expected?groupId=$groupA").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.getAs(tenants.userA, "$a/schedules/expected").status)
            assertEquals(HttpStatusCode.OK, client.getAs(tenants.userB, "$b/schedules/expected?groupId=$groupB").status)
            assertEquals(HttpStatusCode.NotFound, client.getAs(tenants.userB, "$b/schedules/expected?groupId=$groupA").status)
            assertEquals(HttpStatusCode.NotFound, client.getAs(tenants.userB, "$b/children/$childA/schedule/templates").status)
        }
    }
}
