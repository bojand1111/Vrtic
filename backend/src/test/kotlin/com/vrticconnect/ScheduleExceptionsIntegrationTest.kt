package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.DbContext
import com.vrticconnect.db.queryList
import com.vrticconnect.db.update
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.OpsApi
import com.vrticconnect.testing.TestTenants
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Closure days, day overrides, late-change flag, change log feed and their effect on every expectation view (VRTIC_TEST_DB=1). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScheduleExceptionsIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants
    private lateinit var s: ChildrenTestSupport
    private lateinit var loc1: UUID
    private lateinit var loc2: UUID
    private lateinit var group1: UUID
    private lateinit var group2: UUID
    private lateinit var child1: UUID
    private lateinit var child2: UUID
    private lateinit var guardian: UUID
    private lateinit var groupB: UUID
    private lateinit var childB: UUID

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
        s = ChildrenTestSupport(tenants)
        loc1 = s.location(tenants.orgA, "L1")
        loc2 = s.location(tenants.orgA, "L2")
        group1 = s.group(tenants.orgA, loc1, "G1")
        group2 = s.group(tenants.orgA, loc2, "G2")
        child1 = s.child(tenants.orgA, "Nikola", "Izuzetak", group1)
        child2 = s.child(tenants.orgA, "Mila", "Drugi", group2)
        guardian = s.confirmedGuardian(tenants.orgA, child1, tenants.membershipMultiA, canManageSchedule = false)
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

    private fun working(from: LocalDate): LocalDate {
        var d = from
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
        return d
    }

    private fun weekend(from: LocalDate): LocalDate = from.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))

    private fun token(user: UUID) = tenants.tokenOf(user)

    private fun template(from: LocalDate): String {
        val days = (1..7).joinToString(",") { w ->
            if (w <= 5) """{"weekday":$w,"attends":true,"arrivalTime":"08:00","departureTime":"16:00"}""" else """{"weekday":$w,"attends":false}"""
        }
        return """{"effectiveFrom":"$from","days":[$days]}"""
    }

    private fun sqlA(block: (java.sql.Connection) -> Unit) = tenants.runtimeDb.transactionBlocking(DbContext.Tenant(tenants.orgA, tenants.platformAdmin), block)

    private fun dayOf(week: JsonObject, date: LocalDate): JsonObject = with(s) { week.arr("days").first { it.str("date") == date.toString() }.jsonObject }

    @Test
    fun `closure days are managed by managers, readable by members and make a day not expected everywhere`() = testApplication {
        startApp()
        with(s) {
            val a = base(tenants.orgA)
            val x = working(today.plusDays(20))
            val y = working(x.plusDays(3))
            val create = client.postAs(tenants.userA, "$a/closure-days", """{"closureDate":"$x","name":"Dan državnosti"}""")
            assertEquals(HttpStatusCode.Created, create.status, create.bodyAsText())
            val closureId = create.json().str("id")!!
            assertEquals(null, create.json().str("locationId"))
            assertEquals(HttpStatusCode.Conflict, client.postAs(tenants.userA, "$a/closure-days", """{"closureDate":"$x","name":"Dup"}""").status)
            assertTrue(client.postAs(tenants.userA, "$a/closure-days", """{"closureDate":"$x","name":"Dup"}""").bodyAsText().contains("CLOSURE_EXISTS"))
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postAs(tenants.userA, "$a/closure-days", """{"closureDate":"${today.minusDays(1)}","name":"Past"}""").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postAs(tenants.userA, "$a/closure-days", """{"closureDate":"$y"}""").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postAs(tenants.userA, "$a/closure-days", """{"closureDate":"$y","name":"X","locationId":"${UUID.randomUUID()}"}""").status)
            val locClosure = client.postAs(tenants.userOwner, "$a/closure-days", """{"closureDate":"$y","name":"Krečenje","locationId":"$loc2"}""")
            assertEquals(HttpStatusCode.Created, locClosure.status, locClosure.bodyAsText())
            assertEquals("L2", locClosure.json().str("locationName"))
            // parent: read yes, write no; other tenant: 404
            assertEquals(HttpStatusCode.Forbidden, client.postAs(tenants.userMulti, "$a/closure-days", """{"closureDate":"$y","name":"P"}""").status)
            val list = client.getAs(tenants.userMulti, "$a/closure-days?from=$today")
            assertEquals(HttpStatusCode.OK, list.status)
            assertEquals(2, list.json().arr("items").size)
            assertEquals(1, client.getAs(tenants.userA, "$a/closure-days?from=$today&locationId=$loc1").json().arr("items").size)
            assertEquals(HttpStatusCode.NotFound, client.getAs(tenants.userB, "$a/closure-days").status)

            // template for both children, then the closure wins everywhere
            assertTrue(client.postAs(tenants.userA, "$a/children/$child1/schedule/templates", template(today)).status.value in 200..201)
            assertTrue(client.postAs(tenants.userA, "$a/children/$child2/schedule/templates", template(today)).status.value in 200..201)
            val week = client.getAs(tenants.userMulti, "$a/children/$child1/schedule/week?weekStart=${x.with(DayOfWeek.MONDAY)}").json()
            val closed = dayOf(week, x)
            assertEquals("CLOSURE", closed.str("source"))
            assertEquals("false", closed.str("isExpected"))
            assertEquals("false", closed.str("isEditable"))
            assertEquals(closureId, closed.str("closureDayId"))
            val expected = client.getAs(tenants.userA, "$a/schedules/expected?groupId=$group1&date=$x").json()
            assertEquals("Dan državnosti", expected.str("closureName"))
            assertEquals("CLOSURE", expected.arr("items").single().str("source"))
            // location closure: only the child of that location
            assertEquals("TEMPLATE", client.getAs(tenants.userA, "$a/schedules/expected?groupId=$group1&date=$y").json().arr("items").single().str("source"))
            assertEquals("CLOSURE", client.getAs(tenants.userA, "$a/schedules/expected?groupId=$group2&date=$y").json().arr("items").single().str("source"))
            // dashboard: nobody expected on an organization closure day; attendance planning agrees
            val dash = client.getAs(tenants.userA, "$a/dashboard?date=$x").bodyAsText()
            assertTrue(dash.contains("\"counters\":{\"expected\":0"), dash)
            assertTrue(client.getAs(tenants.userA, "$a/dashboard?date=$y").bodyAsText().contains("\"counters\":{\"expected\":1"))
            assertTrue(client.getAs(tenants.userA, "$a/attendance/daily-overview?groupId=$group2&date=$y").bodyAsText().contains("\"isClosure\":true"))
            // an override on a closure day is refused
            assertEquals(HttpStatusCode.Conflict, OpsApi.put(client, token(tenants.userA), "$a/children/$child1/schedule/overrides/$x", """{"attends":false}""", null).status)

            // delete: parent 403, unknown 404, today's closure 409 DAY_FROZEN, future 204
            assertEquals(HttpStatusCode.Forbidden, OpsApi.delete(client, token(tenants.userMulti), "$a/closure-days/$closureId", null).status)
            assertEquals(HttpStatusCode.NotFound, OpsApi.delete(client, token(tenants.userA), "$a/closure-days/${UUID.randomUUID()}", null).status)
            val todayClosure = client.postAs(tenants.userA, "$a/closure-days", """{"closureDate":"$today","name":"Danas","locationId":"$loc2"}""").json().str("id")
            val frozen = OpsApi.delete(client, token(tenants.userA), "$a/closure-days/$todayClosure", null)
            assertEquals(HttpStatusCode.Conflict, frozen.status)
            assertTrue(frozen.bodyAsText().contains("DAY_FROZEN"))
            assertEquals(HttpStatusCode.NoContent, OpsApi.delete(client, token(tenants.userA), "$a/closure-days/$closureId", null).status)
            assertEquals("TEMPLATE", client.getAs(tenants.userA, "$a/schedules/expected?groupId=$group1&date=$x").json().arr("items").single().str("source"))
        }
    }

    @Test
    fun `day overrides follow permissions, validation, versions and the late deadline and are logged`() = testApplication {
        startApp()
        with(s) {
            val a = base(tenants.orgA)
            val path = "$a/children/$child1/schedule/overrides"
            client.postAs(tenants.userA, "$a/children/$child1/schedule/templates", template(today))
            val far = working(today.plusDays(40))

            // permissions: parent without can_manage_schedule 403, teacher (no SCHEDULE_MANAGE) 403, no membership in the tenant 404, child of another tenant 404
            assertEquals(HttpStatusCode.Forbidden, OpsApi.put(client, token(tenants.userMulti), "$path/$far", """{"attends":false}""", null).status)
            assertEquals(HttpStatusCode.Forbidden, OpsApi.put(client, token(tenants.userB), "${base(tenants.orgB)}/children/$childB/schedule/overrides/$far", """{"attends":false}""", null).status)
            assertEquals(HttpStatusCode.NotFound, OpsApi.put(client, token(tenants.userA), "${base(tenants.orgB)}/children/$childB/schedule/overrides/$far", """{"attends":false}""", null).status)
            assertEquals(HttpStatusCode.NotFound, OpsApi.put(client, token(tenants.userA), "$a/children/$childB/schedule/overrides/$far", """{"attends":false}""", null).status)

            // validation
            val noTimes = OpsApi.put(client, token(tenants.userA), "$path/$far", """{"attends":true}""", null)
            assertEquals(HttpStatusCode.UnprocessableEntity, noTimes.status)
            assertTrue(OpsApi.put(client, token(tenants.userA), "$path/$far", """{"attends":true,"arrivalTime":"05:00","departureTime":"12:00"}""", null).bodyAsText().contains("OUTSIDE_OPENING_HOURS"))
            assertTrue(OpsApi.put(client, token(tenants.userA), "$path/${weekend(far)}", """{"attends":true,"arrivalTime":"08:00","departureTime":"12:00"}""", null).bodyAsText().contains("NOT_WORKING_DAY"))
            assertEquals(HttpStatusCode.UnprocessableEntity, OpsApi.put(client, token(tenants.userA), "$path/not-a-date", """{"attends":false}""", null).status)
            val past = OpsApi.put(client, token(tenants.userA), "$path/${today.minusDays(1)}", """{"attends":false}""", null)
            assertEquals(HttpStatusCode.Conflict, past.status)
            assertTrue(past.bodyAsText().contains("DAY_NOT_EDITABLE"))

            // manager sets, version conflict, replace; far future is not late
            val set = OpsApi.put(client, token(tenants.userA), "$path/$far", """{"attends":false,"reason":"Izlet"}""", 0)
            assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())
            assertEquals("1", set.json().str("version"))
            assertEquals("false", set.json().str("isLateChange"))
            val stale = OpsApi.put(client, token(tenants.userA), "$path/$far", """{"attends":true,"arrivalTime":"09:00","departureTime":"12:00"}""", 0)
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertTrue(stale.bodyAsText().contains("\"currentVersion\":1"), stale.bodyAsText())
            val replaced = OpsApi.put(client, token(tenants.userA), "$path/$far", """{"attends":true,"arrivalTime":"09:00","departureTime":"12:00"}""", 1)
            assertEquals("2", replaced.json().str("version"))
            val week = client.getAs(tenants.userMulti, "$a/children/$child1/schedule/week?weekStart=${far.with(DayOfWeek.MONDAY)}").json()
            val day = dayOf(week, far)
            assertEquals("OVERRIDE", day.str("source"))
            assertEquals("09:00", day.str("expectedArrival"))
            assertEquals("2", day.str("overrideVersion"))
            assertEquals("08:00", day.str("templateArrival"))
            assertEquals("false", day.str("isEditable")) // parent without the flag

            // absence wins over the override
            client.postAs(tenants.userA, "$a/absences", """{"childId":"$child1","kind":"VACATION","dateFrom":"$far","dateTo":"$far"}""")
            assertEquals("ABSENCE", client.getAs(tenants.userA, "$a/schedules/expected?groupId=$group1&date=$far").json().arr("items").single().str("source"))

            // parent with can_manage_schedule; deadline 168 h makes the next working days late (accepted and flagged)
            s.setGuardianFlag(tenants.orgA, guardian, "can_manage_schedule", true)
            sqlA { c -> c.update("UPDATE app.organization_settings SET schedule_change_deadline_hours = 168 WHERE organization_id = ?", tenants.orgA) }
            val near = working(today.plusDays(1))
            val late = OpsApi.put(client, token(tenants.userMulti), "$path/$near", """{"attends":false,"reason":"Kod bake"}""", null)
            assertEquals(HttpStatusCode.OK, late.status, late.bodyAsText())
            assertEquals("true", late.json().str("isLateChange"))
            val row = client.getAs(tenants.userA, "$a/schedules/expected?groupId=$group1&date=$near").json().arr("items").single()
            assertEquals("OVERRIDE", row.str("source"))
            assertEquals("true", row.str("isLateChange"))
            assertEquals("false", row.str("isExpected"))
            assertTrue(dayOf(client.getAs(tenants.userMulti, "$a/children/$child1/schedule/week?weekStart=${near.with(DayOfWeek.MONDAY)}").json(), near).str("isEditable") == "true")
            // dashboard: override respected + late counter; feed lists the late change
            val dash = client.getAs(tenants.userA, "$a/dashboard?date=$near").bodyAsText()
            assertTrue(dash.contains("\"lateScheduleChangesToday\":1"), dash)
            assertTrue(dash.contains("\"counters\":{\"expected\":1"), dash) // child2 only (no template -> working day)
            val feed = client.getAs(tenants.userA, "$a/schedules/changes?date=$near&lateOnly=true").json().arr("items")
            assertEquals(1, feed.size)
            assertEquals("OVERRIDE_SET", feed[0].str("changeKind"))
            assertEquals("PARENT", feed[0].str("actorRole"))
            assertEquals(HttpStatusCode.Forbidden, client.getAs(tenants.userMulti, "$a/schedules/changes?date=$near").status)

            // remove: back to the template; a second remove is 404
            assertEquals(HttpStatusCode.NoContent, OpsApi.delete(client, token(tenants.userMulti), "$path/$near", 1).status)
            assertEquals(HttpStatusCode.NotFound, OpsApi.delete(client, token(tenants.userMulti), "$path/$near", null).status)
            assertEquals("TEMPLATE", client.getAs(tenants.userA, "$a/schedules/expected?groupId=$group1&date=$near").json().arr("items").single().str("source"))

            // frozen once attendance was recorded for the day
            sqlA { c -> c.update("INSERT INTO app.attendance_days (organization_id, child_id, group_id, attendance_date) VALUES (?, ?, ?, ?)", tenants.orgA, child1, group1, today) }
            val frozenPut = OpsApi.put(client, token(tenants.userA), "$path/$today", """{"attends":false}""", null)
            assertEquals(HttpStatusCode.Conflict, frozenPut.status)
            assertEquals("true", dayOf(client.getAs(tenants.userA, "$a/children/$child1/schedule/week?weekStart=${today.with(DayOfWeek.MONDAY)}").json(), today).str("isFrozen"))

            // change log: template replace + override set/replace/set/remove, late flags, no reason stored
            val log = mutableListOf<Triple<String, Boolean, String?>>()
            sqlA { c ->
                log += c.queryList("SELECT change_kind, is_late_change, after_state::text AS a FROM app.schedule_change_log WHERE child_id = ? ORDER BY id", child1) {
                    Triple(it.getString("change_kind"), it.getBoolean("is_late_change"), it.getString("a"))
                }
            }
            assertEquals(listOf("TEMPLATE_REPLACED", "OVERRIDE_SET", "OVERRIDE_SET", "OVERRIDE_SET", "OVERRIDE_REMOVED"), log.map { it.first }.takeLast(5))
            assertEquals(listOf(false, false, true, true), log.takeLast(4).map { it.second })
            assertTrue(log.none { it.third?.contains("Kod bake") == true || it.third?.contains("Izlet") == true })
            assertEquals(null, log.last().third)
            sqlA { c -> c.update("UPDATE app.organization_settings SET schedule_change_deadline_hours = 12 WHERE organization_id = ?", tenants.orgA) }
        }
    }

    @Test
    fun `teachers see the change feed of their own groups only`() = testApplication {
        startApp()
        with(s) {
            val b = base(tenants.orgB)
            val date = today.plusDays(2)
            tenants.runtimeDb.transactionBlocking(DbContext.Tenant(tenants.orgB, tenants.platformAdmin)) { c ->
                c.update(
                    "INSERT INTO app.schedule_change_log (organization_id, child_id, change_kind, affected_from, affected_to, is_late_change, actor_membership_id, after_state) " +
                        "VALUES (?, ?, 'OVERRIDE_SET', ?, ?, true, ?, '{\"attends\":false}'::jsonb)",
                    tenants.orgB, childB, date, date, tenants.membershipBTeacher,
                )
            }
            val assigned = client.getAs(tenants.userB, "$b/schedules/changes?date=$date")
            assertEquals(HttpStatusCode.OK, assigned.status, assigned.bodyAsText())
            val item = assigned.json().arr("items").single()
            assertEquals(childB.toString(), item.str("childId"))
            assertEquals("B", item.str("groupName"))
            assertNotNull(item.jsonObject["after"])
            assertEquals(1, client.getAs(tenants.userB, "$b/schedules/changes?date=$date&groupId=$groupB").json().arr("items").size)
            // userMulti is a TEACHER in B without an assignment
            assertEquals(0, client.getAs(tenants.userMulti, "$b/schedules/changes?date=$date").json().arr("items").size)
            assertEquals(HttpStatusCode.NotFound, client.getAs(tenants.userMulti, "$b/schedules/changes?date=$date&groupId=$groupB").status)
            assertFalse(client.getAs(tenants.userB, "$b/schedules/changes?date=${date.plusDays(1)}").bodyAsText().contains(childB.toString()))
            assertEquals(HttpStatusCode.UnprocessableEntity, client.getAs(tenants.userB, "$b/schedules/changes?date=bad").status)
        }
    }
}
