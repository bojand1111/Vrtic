package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.DbContext
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.modules.notifications.NotificationWriter
import com.vrticconnect.testing.ExtraUsers
import com.vrticconnect.testing.OpsApi
import com.vrticconnect.testing.OpsApi.arr
import com.vrticconnect.testing.OpsApi.int
import com.vrticconnect.testing.OpsApi.obj
import com.vrticconnect.testing.OpsApi.str
import com.vrticconnect.testing.TestTenants
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * In-app notifications on native PostgreSQL (VRTIC_TEST_DB=1): producers (announcement publish, absence report and
 * cancel, guardian confirmation), dedup, recipient-only reads, organization filter, mark-read, device push tokens.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationsIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var t: TestTenants
    private lateinit var extra: ExtraUsers
    private lateinit var s: ChildrenTestSupport
    private lateinit var teacherUser: UUID
    private lateinit var parent2User: UUID
    private lateinit var parent2Membership: UUID
    private lateinit var child: UUID

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        t = TestTenants(config).also { it.create() }
        extra = ExtraUsers(config, t)
        s = ChildrenTestSupport(t)
        teacherUser = extra.user("Vaspitac")
        parent2User = extra.user("Otac")
        val teacherMembership = extra.membership(t.orgA, teacherUser, "TEACHER")
        parent2Membership = extra.membership(t.orgA, parent2User, "PARENT")
        val group = s.group(t.orgA, s.location(t.orgA), "Zvoncici")
        s.assignTeacher(t.orgA, teacherMembership, group)
        child = s.child(t.orgA, "Iva", "Obavestena", group)
        s.confirmedGuardian(t.orgA, child, t.membershipMultiA)
    }

    @AfterAll
    fun tearDown() {
        if (enabled) {
            extra.deleteConversations(t.orgA, t.orgB)
            t.destroy()
            extra.destroy()
        }
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = t.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    private val a get() = "/api/v1/organizations/${t.orgA}"

    private suspend fun ApplicationTestBuilder.inbox(token: String, query: String = ""): JsonObject =
        OpsApi.json(OpsApi.get(client, token, "/api/v1/me/notifications$query"))

    private fun JsonObject.keys(): List<String> = arr("items").map { it.jsonObject.str("titleKey")!! }

    @Test
    fun `business events create inbox notifications for the right recipients only`() = testApplication {
        startApp()
        val parent = t.tokenOf(t.userMulti)
        val admin = t.tokenOf(t.userA)
        val owner = t.tokenOf(t.userOwner)
        val teacher = extra.tokenOf(teacherUser)

        // Announcement to the whole organization: every member except the author.
        val ann = OpsApi.json(OpsApi.post(client, admin, "$a/announcements", """{"title":"Sastanak","body":"Sreda","audiences":[{"audienceType":"ORGANIZATION"}]}"""))
        val annId = ann.str("id")!!
        assertEquals(HttpStatusCode.OK, OpsApi.post(client, admin, "$a/announcements/$annId/publish", ifMatch = 1).status)
        val parentInbox = inbox(parent)
        val annNote = parentInbox.arr("items").map { it.jsonObject }.first { it.str("titleKey") == "announcement.published" }
        assertEquals("ANNOUNCEMENT", annNote.str("kind"))
        assertEquals(annId, annNote.str("refEntityId"))
        assertEquals("Sastanak", annNote.obj("titleArgs").str("title"))
        assertEquals(t.orgA.toString(), annNote.str("organizationId"))
        assertTrue("announcement.published" in inbox(teacher).keys())
        assertTrue("announcement.published" !in inbox(admin).keys(), "the author is not notified")

        // Parent reports an absence: group teacher + managers, not the parent; no kind or note in the arguments.
        val today = s.today
        val absence = OpsApi.post(client, parent, "$a/absences", """{"childId":"$child","kind":"SICK","dateFrom":"${today.plusDays(1)}","dateTo":"${today.plusDays(2)}","note":"Temperatura"}""")
        assertEquals(HttpStatusCode.Created, absence.status, absence.bodyAsText())
        val absenceId = OpsApi.json(absence).str("id")!!
        for (token in listOf(teacher, admin, owner)) assertTrue("absence.reported" in inbox(token).keys())
        assertTrue("absence.reported" !in inbox(parent).keys())
        val teacherText = OpsApi.get(client, teacher, "/api/v1/me/notifications?kind=ABSENCE").bodyAsText()
        assertTrue(teacherText.contains("Iva Obavestena") && !teacherText.contains("SICK") && !teacherText.contains("Temperatura"), teacherText)

        // Admin cancels it: staff and (because staff acted) the guardians.
        assertEquals(HttpStatusCode.OK, OpsApi.post(client, admin, "$a/absences/$absenceId/cancel").status)
        assertTrue("absence.cancelled" in inbox(parent).keys())
        assertTrue("absence.cancelled" in inbox(teacher).keys())
        assertTrue("absence.cancelled" !in inbox(admin).keys())

        // Guardian confirmation notifies the parent.
        val pending = s.pendingGuardian(t.orgA, child, parent2Membership)
        assertEquals(HttpStatusCode.OK, OpsApi.post(client, admin, "$a/guardians/$pending/confirm").status)
        val p2 = inbox(extra.tokenOf(parent2User))
        assertEquals(1, p2.keys().count { it == "guardian.confirmed" })
        assertEquals(p2.arr("items").size, p2.int("unreadCount"))

        // Dedup: the same event key never creates a second row.
        val dup = t.runtimeDb.transactionBlocking(DbContext.Tenant(t.orgA, t.platformAdmin)) { c ->
            NotificationWriter.notifyMemberships(
                c, t.orgA, listOf(t.membershipMultiA), "ANNOUNCEMENT", "announcement.published", mapOf("title" to "x"),
                "ANNOUNCEMENT", UUID.fromString(annId), "announcement:$annId", null,
            )
        }
        assertEquals(0, dup)
        assertEquals(1, inbox(parent).keys().count { it == "announcement.published" })

        // Isolation: the other tenant's user sees nothing of org A; org filter; bad query -> 422.
        val otherTenant = inbox(t.tokenOf(t.userB))
        assertTrue(otherTenant.arr("items").none { it.jsonObject.str("organizationId") == t.orgA.toString() })
        assertEquals(0, inbox(parent, "?organizationId=${t.orgB}").arr("items").size)
        assertEquals(HttpStatusCode.UnprocessableEntity, OpsApi.get(client, parent, "/api/v1/me/notifications?kind=NOPE").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me/notifications").status)
    }

    @Test
    fun `mark read is per recipient and idempotent`() = testApplication {
        startApp()
        val owner = t.tokenOf(t.userOwner)
        val ownerMembership = s.ownerMembership(t.orgA, t.userOwner)
        t.runtimeDb.transactionBlocking(DbContext.Tenant(t.orgA, t.platformAdmin)) { c ->
            for (i in 1..3) {
                NotificationWriter.notifyMemberships(c, t.orgA, listOf(ownerMembership), "SYSTEM", "guardian.confirmed", mapOf("childName" to "N$i"), null, null, "test:$i", null)
            }
        }
        val before = inbox(owner, "?unreadOnly=true")
        val unread = before.int("unreadCount")!!
        assertTrue(unread >= 3)
        val firstId = before.arr("items")[0].jsonObject.str("id")!!
        val marked = OpsApi.post(client, owner, "/api/v1/me/notifications/mark-read", """{"notificationIds":["$firstId"]}""")
        assertEquals(HttpStatusCode.OK, marked.status, marked.bodyAsText())
        assertEquals(unread - 1, OpsApi.json(marked).int("unread"))
        // Someone else's id is ignored (RLS), the count does not change.
        assertEquals(HttpStatusCode.OK, OpsApi.post(client, owner, "/api/v1/me/notifications/mark-read", """{"notificationIds":["$firstId"]}""").status)
        assertEquals(HttpStatusCode.OK, OpsApi.post(client, t.tokenOf(t.userA), "/api/v1/me/notifications/mark-read", """{"notificationIds":["${before.arr("items")[1].jsonObject.str("id")}"]}""").status)
        assertEquals(unread - 1, inbox(owner).int("unreadCount"))
        assertEquals(HttpStatusCode.UnprocessableEntity, OpsApi.post(client, owner, "/api/v1/me/notifications/mark-read", "{}").status)
        assertEquals(0, OpsApi.json(OpsApi.post(client, owner, "/api/v1/me/notifications/mark-read", """{"all":true}""")).int("unread"))
        assertEquals(0, inbox(owner, "?unreadOnly=true").arr("items").size)
        // Pagination.
        val page = inbox(owner, "?limit=2")
        assertEquals(2, page.arr("items").size)
        val next = page.str("nextCursor")!!
        assertTrue(inbox(owner, "?limit=2&cursor=$next").arr("items").isNotEmpty())
    }

    @Test
    fun `device push tokens are registered, refreshed and removed by their owner only`() = testApplication {
        startApp()
        val parent = t.tokenOf(t.userMulti)
        val token = "fcm-" + UUID.randomUUID().toString().replace("-", "")
        val created = OpsApi.post(client, parent, "/api/v1/auth/push-tokens", """{"platform":"ANDROID","token":"$token","appVersion":"1.0.0"}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val id = OpsApi.json(created).str("id")!!
        assertTrue(!created.bodyAsText().contains(token), "the token value is never returned")
        assertEquals(HttpStatusCode.OK, OpsApi.post(client, parent, "/api/v1/auth/push-tokens", """{"platform":"ANDROID","token":"$token","appVersion":"1.0.1"}""").status)
        assertEquals(HttpStatusCode.Conflict, OpsApi.post(client, t.tokenOf(t.userA), "/api/v1/auth/push-tokens", """{"platform":"ANDROID","token":"$token"}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, OpsApi.post(client, parent, "/api/v1/auth/push-tokens", """{"platform":"WEB","token":"short"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/auth/push-tokens/$id") { header(HttpHeaders.Authorization, "Bearer ${t.tokenOf(t.userA)}") }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/auth/push-tokens/$id") { header(HttpHeaders.Authorization, "Bearer $parent") }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/auth/push-tokens/$id") { header(HttpHeaders.Authorization, "Bearer $parent") }.status)
    }
}
