package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.DbContext
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Announcements: drafts, publish snapshot, read receipts, archive, scope (VRTIC_TEST_DB=1). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnnouncementIntegrationTest {

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
        val child = f.child(t.orgA, groupA, "Vera")
        f.guardian(t.orgA, child, t.membershipMultiA, t.userA)
    }

    @AfterAll
    fun tearDown() {
        if (!enabled) return
        // announcement_audiences -> groups has no cascade; the organization cascade would trip over it (runtime may DELETE audiences)
        t.runtimeDb.transactionBlocking(DbContext.Tenant(t.orgA, t.platformAdmin)) { c ->
            c.prepareStatement("DELETE FROM app.announcement_audiences").use { it.executeUpdate() }
        }
        t.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = t.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    private suspend fun ids(r: io.ktor.client.statement.HttpResponse) = OpsApi.json(r).arr("items").map { it.jsonObject.str("id") }

    private val base get() = "/api/v1/organizations/${t.orgA}/announcements"

    @Test
    fun `admin publishes to the organization, the parent reads it, archive hides it`() = testApplication {
        startApp()
        val admin = t.tokenOf(t.userA)
        val parent = t.tokenOf(t.userMulti)
        val created = OpsApi.post(client, admin, base, """{"title":"Roditeljski sastanak","body":"U sredu\nu 17h","audiences":[{"audienceType":"ORGANIZATION"}]}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        assertEquals("\"1\"", created.headers["ETag"])
        val id = OpsApi.json(created).str("id")!!
        assertEquals("DRAFT", OpsApi.json(created).str("status"))

        // drafts are invisible to recipients
        assertTrue(ids(OpsApi.get(client, parent, base)).none { it == id })
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, parent, "$base/$id").status)

        // If-Match: missing 428, stale 409
        assertEquals(428, OpsApi.post(client, admin, "$base/$id/publish").status.value)
        val stale = OpsApi.post(client, admin, "$base/$id/publish", ifMatch = 7)
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals(1, OpsApi.json(stale).int("currentVersion"))
        val published = OpsApi.post(client, admin, "$base/$id/publish", ifMatch = 1)
        assertEquals(HttpStatusCode.OK, published.status, published.bodyAsText())
        assertEquals("PUBLISHED", OpsApi.json(published).str("status"))
        assertEquals(3, OpsApi.json(published).int("recipientsCount"), "owner, admin and parent memberships of org A")
        assertEquals(HttpStatusCode.Conflict, OpsApi.post(client, admin, "$base/$id/publish", ifMatch = 2).status)
        val notEditable = OpsApi.patch(client, admin, "$base/$id", """{"title":"x"}""", 2)
        assertEquals("ANNOUNCEMENT_NOT_EDITABLE", OpsApi.json(notEditable).str("detail"))

        val inbox = OpsApi.json(OpsApi.get(client, parent, base)).arr("items").map { it.jsonObject }.single { it.str("id") == id }
        assertNull(inbox.str("readAt"))
        assertNull(inbox.int("recipientsCount"), "counts are staff only")
        val read = OpsApi.post(client, parent, "$base/$id/read")
        assertEquals(HttpStatusCode.OK, read.status, read.bodyAsText())
        val readAt = OpsApi.json(read).str("readAt")
        assertNotNull(readAt)
        assertEquals(readAt, OpsApi.json(OpsApi.post(client, parent, "$base/$id/read")).str("readAt"), "idempotent")
        assertTrue(ids(OpsApi.get(client, parent, "$base?unreadOnly=true")).none { it == id })

        val recipients = OpsApi.get(client, admin, "$base/$id/recipients")
        assertEquals(HttpStatusCode.OK, recipients.status, recipients.bodyAsText())
        assertEquals(3, OpsApi.json(recipients).arr("items").size)
        assertEquals(1, OpsApi.json(OpsApi.get(client, admin, "$base/$id/recipients?readState=READ")).arr("items").size)
        assertEquals(HttpStatusCode.Forbidden, OpsApi.get(client, parent, "$base/$id/recipients").status)

        val archived = OpsApi.post(client, admin, "$base/$id/archive", ifMatch = 2)
        assertEquals("ARCHIVED", OpsApi.json(archived).str("status"))
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, parent, "$base/$id").status)
        assertEquals(listOf(id), ids(OpsApi.get(client, admin, "$base?status=ARCHIVED")))
    }

    @Test
    fun `group audience reaches only parents of that group, drafts are editable and deletable`() = testApplication {
        startApp()
        val admin = t.tokenOf(t.userA)
        val parent = t.tokenOf(t.userMulti)
        val other = OpsApi.post(client, admin, base, """{"title":"Ribice","body":"b","audiences":[{"audienceType":"GROUP","groupId":"$groupAOther"}]}""")
        val otherId = OpsApi.json(other).str("id")!!
        val otherPublished = OpsApi.post(client, admin, "$base/$otherId/publish", ifMatch = 1)
        assertEquals(1, OpsApi.json(otherPublished).int("recipientsCount"), "only the author")
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, parent, "$base/$otherId").status)

        val mine = OpsApi.post(client, admin, base, """{"title":"Pcelice","body":"b","audiences":[{"audienceType":"GROUP","groupId":"$groupA"}]}""")
        val mineId = OpsApi.json(mine).str("id")!!
        val edited = OpsApi.patch(client, admin, "$base/$mineId", """{"title":"Pcelice izlet","body":"Ponesite kape"}""", 1)
        assertEquals(HttpStatusCode.OK, edited.status, edited.bodyAsText())
        assertEquals("Pcelice izlet", OpsApi.json(edited).str("title"))
        assertEquals(2, OpsApi.json(edited).int("version"))
        val published = OpsApi.post(client, admin, "$base/$mineId/publish", ifMatch = 2)
        assertEquals(2, OpsApi.json(published).int("recipientsCount"), "author + parent")
        assertEquals(HttpStatusCode.OK, OpsApi.get(client, parent, "$base/$mineId").status)

        val draft = OpsApi.post(client, admin, base, """{"title":"Brisanje","body":"b","audiences":[{"audienceType":"ORGANIZATION"}]}""")
        val draftId = OpsApi.json(draft).str("id")!!
        assertEquals(HttpStatusCode.NoContent, OpsApi.delete(client, admin, "$base/$draftId", 1).status)
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, admin, "$base/$draftId").status)
    }

    @Test
    fun `permissions, validation and tenant isolation`() = testApplication {
        startApp()
        val body = """{"title":"x","body":"y","audiences":[{"audienceType":"ORGANIZATION"}]}"""
        assertEquals(HttpStatusCode.Forbidden, OpsApi.post(client, t.tokenOf(t.userMulti), base, body).status)
        assertEquals(HttpStatusCode.Forbidden, OpsApi.post(client, t.tokenOf(t.userB), "/api/v1/organizations/${t.orgB}/announcements", body).status)
        assertEquals(HttpStatusCode.NotFound, OpsApi.post(client, t.tokenOf(t.userB), base, body).status)
        val invalid = OpsApi.post(client, t.tokenOf(t.userA), base, """{"title":"${"x".repeat(201)}","body":"","audiences":[{"audienceType":"GROUP"}]}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        val text = invalid.bodyAsText()
        assertTrue(text.contains("\"title\"") && text.contains("\"body\"") && text.contains("audiences[0].groupId"), text)
        val unknownGroup = OpsApi.post(client, t.tokenOf(t.userA), base, """{"title":"x","body":"y","audiences":[{"audienceType":"GROUP","groupId":"${UUID.randomUUID()}"}]}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, unknownGroup.status)
        val created = OpsApi.post(client, t.tokenOf(t.userA), base, body)
        val id = OpsApi.json(created).str("id")!!
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, t.tokenOf(t.userB), "/api/v1/organizations/${t.orgB}/announcements/$id").status)
    }
}
