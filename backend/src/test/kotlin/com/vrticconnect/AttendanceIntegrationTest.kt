package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.DailyOpsFixture
import com.vrticconnect.testing.OpsApi
import com.vrticconnect.testing.OpsApi.arr
import com.vrticconnect.testing.OpsApi.int
import com.vrticconnect.testing.OpsApi.obj
import com.vrticconnect.testing.OpsApi.str
import com.vrticconnect.testing.TestTenants
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Attendance commands and reads (ADR-0008) on native PostgreSQL (VRTIC_TEST_DB=1). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttendanceIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var t: TestTenants
    private lateinit var groupB1: UUID
    private lateinit var childB1: UUID
    private lateinit var childB2: UUID
    private lateinit var childA: UUID
    private lateinit var childAOther: UUID
    private val today = LocalDate.now(ZoneId.of("Europe/Belgrade"))

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        t = TestTenants(config).also { it.create() }
        val f = DailyOpsFixture(t)
        val locB = f.location(t.orgB)
        groupB1 = f.group(t.orgB, locB, "Leptirici")
        val groupB2 = f.group(t.orgB, locB, "Bubamare")
        f.assignTeacher(t.orgB, t.membershipBTeacher, groupB1)
        childB1 = f.child(t.orgB, groupB1, "Ana")
        childB2 = f.child(t.orgB, groupB2, "Boris")
        val locA = f.location(t.orgA)
        val groupA = f.group(t.orgA, locA, "Pcelice")
        childA = f.child(t.orgA, groupA, "Vera")
        childAOther = f.child(t.orgA, groupA, "Zoran")
        f.guardian(t.orgA, childA, t.membershipMultiA, t.userA)
    }

    @AfterAll
    fun tearDown() {
        if (enabled) t.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = t.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    private fun cmd(version: Int, commandId: UUID = UUID.randomUUID(), extra: String = "", occurredAt: Instant = Instant.now()) =
        """{"commandId":"$commandId","expectedVersion":$version,"occurredAt":"$occurredAt"$extra}"""

    private fun url(org: UUID, child: UUID, action: String) = "/api/v1/organizations/$org/children/$child/attendance/$action"

    @Test
    fun `teacher records visits in an assigned group with idempotency, versions and transitions`() = testApplication {
        startApp()
        val token = t.tokenOf(t.userB)
        val commandId = UUID.randomUUID()
        val first = OpsApi.post(client, token, url(t.orgB, childB1, "check-in"), cmd(0, commandId))
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        val day = OpsApi.json(first).obj("attendanceDay")
        assertEquals("CHECKED_IN", day.str("status"))
        assertEquals(1, day.int("version"))
        assertEquals(1, day.arr("visits").size)

        // replay of the same commandId: 200 DUPLICATE, no new event, same version
        val replay = OpsApi.post(client, token, url(t.orgB, childB1, "check-in"), cmd(0, commandId))
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        val replayJson = OpsApi.json(replay)
        assertEquals("DUPLICATE", replayJson.str("outcome"))
        assertNull(replayJson.str("eventId"))
        assertEquals(1, replayJson.obj("attendanceDay").int("version"))

        // invalid transition and stale version
        val again = OpsApi.post(client, token, url(t.orgB, childB1, "check-in"), cmd(1))
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("ALREADY_CHECKED_IN", OpsApi.json(again).str("detail"))
        val stale = OpsApi.post(client, token, url(t.orgB, childB1, "check-out"), cmd(0))
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("VERSION_MISMATCH", OpsApi.json(stale).str("detail"))
        assertEquals(1, OpsApi.json(stale).int("currentVersion"))

        val out = OpsApi.post(client, token, url(t.orgB, childB1, "check-out"), cmd(1))
        assertEquals(HttpStatusCode.Created, out.status, out.bodyAsText())
        assertEquals("CHECKED_OUT", OpsApi.json(out).obj("attendanceDay").str("status"))
        val outAgain = OpsApi.post(client, token, url(t.orgB, childB1, "check-out"), cmd(2))
        assertEquals("NO_OPEN_VISIT", OpsApi.json(outAgain).str("detail"))

        // second visit the same day
        val second = OpsApi.post(client, token, url(t.orgB, childB1, "check-in"), cmd(2))
        assertEquals(HttpStatusCode.Created, second.status, second.bodyAsText())
        val secondDay = OpsApi.json(second).obj("attendanceDay")
        assertEquals(2, secondDay.int("visitsCount"))
        assertEquals(2, secondDay.arr("visits").size)

        // absence context never changes the status
        val absent = OpsApi.post(client, token, url(t.orgB, childB1, "mark-absent"), cmd(3, extra = ""","absenceKind":"SICK""""))
        assertEquals(HttpStatusCode.Created, absent.status, absent.bodyAsText())
        assertEquals("SICK", OpsApi.json(absent).obj("attendanceDay").str("absenceKind"))
        assertEquals("CHECKED_IN", OpsApi.json(absent).obj("attendanceDay").str("status"))
        val cleared = OpsApi.post(client, token, url(t.orgB, childB1, "clear-absence"), cmd(4))
        assertNull(OpsApi.json(cleared).obj("attendanceDay").str("absenceKind"))

        // daily overview of the assigned group
        val overview = OpsApi.get(client, token, "/api/v1/organizations/${t.orgB}/attendance/daily-overview?groupId=$groupB1")
        assertEquals(HttpStatusCode.OK, overview.status, overview.bodyAsText())
        val children = OpsApi.json(overview).arr("children")
        assertEquals(1, children.size)
        val counters = OpsApi.json(overview).obj("counters")
        assertEquals(1, (counters.int("present") ?: 0) + (counters.int("unscheduledPresent") ?: 0))

        // event history
        val events = OpsApi.get(client, token, "/api/v1/organizations/${t.orgB}/attendance/events?date=$today&childId=$childB1")
        assertEquals(HttpStatusCode.OK, events.status, events.bodyAsText())
        assertEquals(5, OpsApi.json(events).arr("items").size)
    }

    @Test
    fun `scope, permissions and validation`() = testApplication {
        startApp()
        val teacher = t.tokenOf(t.userB)
        // child of another group, teacher without assignment, cross-tenant child: 404
        assertEquals(HttpStatusCode.NotFound, OpsApi.post(client, teacher, url(t.orgB, childB2, "check-in"), cmd(0)).status)
        assertEquals(HttpStatusCode.NotFound, OpsApi.post(client, t.tokenOf(t.userMulti), url(t.orgB, childB1, "check-in"), cmd(0)).status)
        assertEquals(HttpStatusCode.NotFound, OpsApi.post(client, teacher, url(t.orgB, childA, "check-in"), cmd(0)).status)
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, teacher, "/api/v1/organizations/${t.orgA}/children/$childA/attendance/days/$today").status)
        val otherGroup = OpsApi.get(client, t.tokenOf(t.userMulti), "/api/v1/organizations/${t.orgB}/attendance/daily-overview?groupId=$groupB1")
        assertEquals(HttpStatusCode.NotFound, otherGroup.status)
        // TEACHER lacks ATTENDANCE_CORRECT
        val correction = """{"commandId":"${UUID.randomUUID()}","expectedVersion":0,"occurredAt":"${Instant.now()}","correctionOfEventId":"${UUID.randomUUID()}","reason":"wrong child"}"""
        assertEquals(HttpStatusCode.Forbidden, OpsApi.post(client, teacher, url(t.orgB, childB1, "correction"), correction).status)
        // PARENT: no recording, no group overview
        val parent = t.tokenOf(t.userMulti)
        assertEquals(HttpStatusCode.Forbidden, OpsApi.post(client, parent, url(t.orgA, childA, "check-in"), cmd(0)).status)
        assertEquals(HttpStatusCode.Forbidden, OpsApi.get(client, parent, "/api/v1/organizations/${t.orgA}/attendance/daily-overview?groupId=${UUID.randomUUID()}").status)
        // validation
        val missing = OpsApi.post(client, teacher, url(t.orgB, childB1, "check-in"), """{"expectedVersion":0}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, missing.status)
        assertTrue(missing.bodyAsText().contains("commandId") && missing.bodyAsText().contains("occurredAt"), missing.bodyAsText())
        val future = OpsApi.post(client, teacher, url(t.orgB, childB1, "check-in"), cmd(0, occurredAt = Instant.now().plusSeconds(3600)))
        assertEquals(HttpStatusCode.UnprocessableEntity, future.status)
        val badKind = OpsApi.post(client, teacher, url(t.orgB, childB1, "mark-absent"), cmd(0, extra = ""","absenceKind":"BORED""""))
        assertEquals(HttpStatusCode.UnprocessableEntity, badKind.status)
    }

    @Test
    fun `admin corrects with a reason and the parent reads only the own child`() = testApplication {
        startApp()
        val admin = t.tokenOf(t.userA)
        val checkIn = OpsApi.post(client, admin, url(t.orgA, childA, "check-in"), cmd(0))
        assertEquals(HttpStatusCode.Created, checkIn.status, checkIn.bodyAsText())
        val eventId = OpsApi.json(checkIn).str("eventId")!!

        val noReason = """{"commandId":"${UUID.randomUUID()}","expectedVersion":1,"occurredAt":"${Instant.now()}","correctionOfEventId":"$eventId","voidEvent":true}"""
        assertEquals(HttpStatusCode.UnprocessableEntity, OpsApi.post(client, admin, url(t.orgA, childA, "correction"), noReason).status)
        val void = """{"commandId":"${UUID.randomUUID()}","expectedVersion":1,"occurredAt":"${Instant.now()}","correctionOfEventId":"$eventId","voidEvent":true,"reason":"wrong child"}"""
        val corrected = OpsApi.post(client, admin, url(t.orgA, childA, "correction"), void)
        assertEquals(HttpStatusCode.Created, corrected.status, corrected.bodyAsText())
        val day = OpsApi.json(corrected).obj("attendanceDay")
        assertEquals("NOT_ARRIVED", day.str("status"))
        assertEquals(0, day.int("visitsCount"))
        assertEquals(0, day.arr("visits").size)
        assertEquals(2, day.int("version"))

        val parent = t.tokenOf(t.userMulti)
        val own = OpsApi.get(client, parent, "/api/v1/organizations/${t.orgA}/children/$childA/attendance/days/$today")
        assertEquals(HttpStatusCode.OK, own.status, own.bodyAsText())
        assertEquals(2, OpsApi.json(own).int("version"))
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, parent, "/api/v1/organizations/${t.orgA}/children/$childAOther/attendance/days/$today").status)
        // a date without events answers a synthetic NOT_ARRIVED day
        val past = OpsApi.get(client, parent, "/api/v1/organizations/${t.orgA}/children/$childA/attendance/days/${today.minusDays(3)}")
        assertEquals(0, OpsApi.json(past).int("version"))
        assertEquals("NOT_ARRIVED", OpsApi.json(past).str("status"))
    }
}
