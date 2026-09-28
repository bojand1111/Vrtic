package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.DailyOpsFixture
import com.vrticconnect.testing.OpsApi
import com.vrticconnect.testing.OpsApi.arr
import com.vrticconnect.testing.OpsApi.int
import com.vrticconnect.testing.OpsApi.str
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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Calendar events: CRUD with If-Match, scope of org/location/group events (VRTIC_TEST_DB=1). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CalendarIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var t: TestTenants
    private lateinit var groupA: UUID
    private lateinit var groupAOther: UUID

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        t = TestTenants(config).also { it.create() }
        val f = DailyOpsFixture(t)
        val loc = f.location(t.orgA)
        groupA = f.group(t.orgA, loc, "Pcelice")
        groupAOther = f.group(t.orgA, loc, "Ribice")
        f.guardian(t.orgA, f.child(t.orgA, groupA, "Vera"), t.membershipMultiA, t.userA)
    }

    @AfterAll
    fun tearDown() {
        if (enabled) t.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = t.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    private val base get() = "/api/v1/organizations/${t.orgA}/calendar-events"

    @Test
    fun `manager creates, updates and deletes, parents see organization and own group events only`() = testApplication {
        startApp()
        val admin = t.tokenOf(t.userA)
        val parent = t.tokenOf(t.userMulti)
        val orgEvent = OpsApi.post(client, admin, base, """{"kind":"HOLIDAY","title":"Praznik","allDay":true,"startsOn":"2030-05-01","endsOn":"2030-05-02"}""")
        assertEquals(HttpStatusCode.Created, orgEvent.status, orgEvent.bodyAsText())
        val orgId = OpsApi.json(orgEvent).str("id")!!
        val timed = OpsApi.post(
            client, admin, base,
            """{"kind":"PARENT_MEETING","title":"Sastanak","allDay":false,"startsOn":"2030-05-10","endsOn":"2030-05-10","startsAt":"2030-05-10T15:00:00Z","endsAt":"2030-05-10T16:00:00Z","groupId":"$groupA"}""",
        )
        assertEquals(HttpStatusCode.Created, timed.status, timed.bodyAsText())
        assertTrue(OpsApi.json(timed).str("locationId") != null, "group implies its location")
        val hidden = OpsApi.post(client, admin, base, """{"kind":"TRIP","title":"Izlet","allDay":true,"startsOn":"2030-05-12","endsOn":"2030-05-12","groupId":"$groupAOther"}""")
        val hiddenId = OpsApi.json(hidden).str("id")!!

        val range = "?from=2030-05-01&to=2030-05-31"
        assertEquals(3, OpsApi.json(OpsApi.get(client, admin, base + range)).arr("items").size)
        val parentItems = OpsApi.json(OpsApi.get(client, parent, base + range)).arr("items").map { it.jsonObject.str("title") }
        assertEquals(listOf("Praznik", "Sastanak"), parentItems)
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, parent, "$base/$hiddenId").status)

        val patched = OpsApi.patch(client, admin, "$base/$orgId", """{"title":"Prvi maj","description":null}""", 1)
        assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
        assertEquals("Prvi maj", OpsApi.json(patched).str("title"))
        assertEquals("\"2\"", patched.headers["ETag"])
        assertEquals(HttpStatusCode.Conflict, OpsApi.patch(client, admin, "$base/$orgId", """{"title":"x"}""", 1).status)
        assertEquals(428, OpsApi.patch(client, admin, "$base/$orgId", """{"title":"x"}""", null).status.value)
        assertEquals(HttpStatusCode.NoContent, OpsApi.delete(client, admin, "$base/$orgId", 2).status)
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, admin, "$base/$orgId").status)
    }

    @Test
    fun `permissions, validation and tenant isolation`() = testApplication {
        startApp()
        val body = """{"kind":"OTHER","title":"x","allDay":true,"startsOn":"2030-06-01","endsOn":"2030-06-01"}"""
        assertEquals(HttpStatusCode.Forbidden, OpsApi.post(client, t.tokenOf(t.userMulti), base, body).status)
        assertEquals(HttpStatusCode.Forbidden, OpsApi.post(client, t.tokenOf(t.userB), "/api/v1/organizations/${t.orgB}/calendar-events", body).status)
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, t.tokenOf(t.userB), "$base?from=2030-06-01&to=2030-06-30").status)
        val admin = t.tokenOf(t.userA)
        val invalid = OpsApi.post(client, admin, base, """{"kind":"PARTY","title":"","allDay":false,"startsOn":"2030-06-02","endsOn":"2030-06-01"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        val text = invalid.bodyAsText()
        assertTrue(text.contains("\"kind\"") && text.contains("\"title\"") && text.contains("\"startsAt\""), text)
        val backwards = OpsApi.post(client, admin, base, """{"kind":"OTHER","title":"x","allDay":true,"startsOn":"2030-06-02","endsOn":"2030-06-01"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, backwards.status)
        assertEquals(HttpStatusCode.UnprocessableEntity, OpsApi.get(client, admin, "$base?from=2030-01-01&to=2031-06-01").status)
        val created = OpsApi.post(client, admin, base, body)
        assertEquals(1, OpsApi.json(created).int("version"))
        val id = OpsApi.json(created).str("id")!!
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, t.tokenOf(t.userB), "/api/v1/organizations/${t.orgB}/calendar-events/$id").status)
    }
}
