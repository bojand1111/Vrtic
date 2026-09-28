package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.DbContext
import com.vrticconnect.db.update
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.ExtraUsers
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Messaging on native PostgreSQL (VRTIC_TEST_DB=1): server-derived participants, scope (outsider / manager /
 * cross-tenant 404), idempotent clientMessageId, unread counts and read position, lazy loss of access after a
 * guardian revocation, validation and role rules, MESSAGE notifications without the body.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessagingIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var t: TestTenants
    private lateinit var extra: ExtraUsers
    private lateinit var s: ChildrenTestSupport

    private lateinit var teacherUser: UUID
    private lateinit var parent2User: UUID
    private lateinit var outsiderUser: UUID
    private lateinit var teacherMembership: UUID
    private lateinit var parent2Membership: UUID
    private lateinit var parent2Guardian: UUID
    private lateinit var child: UUID
    private lateinit var childWithoutGuardian: UUID

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        t = TestTenants(config).also { it.create() }
        extra = ExtraUsers(config, t)
        s = ChildrenTestSupport(t)
        teacherUser = extra.user("Vaspitac")
        parent2User = extra.user("Otac")
        outsiderUser = extra.user("Stranac")
        teacherMembership = extra.membership(t.orgA, teacherUser, "TEACHER")
        parent2Membership = extra.membership(t.orgA, parent2User, "PARENT")
        extra.membership(t.orgA, outsiderUser, "PARENT")
        val group = s.group(t.orgA, s.location(t.orgA), "Leptirici")
        s.assignTeacher(t.orgA, teacherMembership, group)
        child = s.child(t.orgA, "Mila", "Poruka", group)
        childWithoutGuardian = s.child(t.orgA, "Luka", "Bezroditelja", group)
        s.confirmedGuardian(t.orgA, child, t.membershipMultiA)
        parent2Guardian = s.confirmedGuardian(t.orgA, child, parent2Membership)
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

    private val base get() = "/api/v1/organizations/${t.orgA}/conversations"
    private fun msg(body: String, id: UUID = UUID.randomUUID()) = """{"clientMessageId":"$id","body":"$body"}"""

    @Test
    fun `parent and teacher converse with server-derived participants, scope and idempotency`() = testApplication {
        startApp()
        val parent = t.tokenOf(t.userMulti)
        val admin = t.tokenOf(t.userA)
        val teacher = extra.tokenOf(teacherUser)
        val parent2 = extra.tokenOf(parent2User)
        val outsider = extra.tokenOf(outsiderUser)

        // Create: participants come from guardian links + teacher assignment (client cannot pick them).
        val firstId = UUID.randomUUID()
        val created = OpsApi.post(client, parent, base, """{"kind":"PARENT_TEACHER","childId":"$child","subject":"Preuzimanje","initialMessage":${msg("Danas ga vodi baka", firstId)}}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val conv = OpsApi.json(created)
        val convId = conv.str("id")!!
        assertEquals("Mila", conv.str("childGivenName"))
        val roles = conv.arr("participants").map { it.jsonObject.str("membershipId") to it.jsonObject.str("participantRole") }.toSet()
        assertEquals(
            setOf(t.membershipMultiA.toString() to "GUARDIAN", parent2Membership.toString() to "GUARDIAN", teacherMembership.toString() to "TEACHER"),
            roles,
        )
        assertEquals(0, conv.int("unreadCount"))

        // Same kind + child again: existing conversation (200) with the message appended; a resend of the same
        // clientMessageId does not duplicate.
        val again = OpsApi.post(client, parent, base, """{"kind":"PARENT_TEACHER","childId":"$child","initialMessage":${msg("I sutra")}}""")
        assertEquals(HttpStatusCode.OK, again.status, again.bodyAsText())
        assertEquals(convId, OpsApi.json(again).str("id"))
        val resend = OpsApi.post(client, parent, "$base/$convId/messages", msg("Danas ga vodi baka", firstId))
        assertEquals(HttpStatusCode.OK, resend.status)
        assertEquals(2, OpsApi.json(OpsApi.get(client, parent, "$base/$convId/messages")).arr("items").size)

        // Teacher sees it with 2 unread; admin (not a party of PARENT_TEACHER), outsider and other tenants get 404.
        val teacherList = OpsApi.json(OpsApi.get(client, teacher, base)).arr("items")
        assertEquals(listOf(convId), teacherList.map { it.jsonObject.str("id") })
        assertEquals(2, teacherList[0].jsonObject.int("unreadCount"))
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, admin, "$base/$convId").status)
        assertEquals(0, OpsApi.json(OpsApi.get(client, admin, "$base?kind=PARENT_TEACHER")).arr("items").size)
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, outsider, "$base/$convId").status)
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, outsider, "$base/$convId/messages").status)
        assertEquals(HttpStatusCode.NotFound, OpsApi.post(client, outsider, "$base/$convId/messages", msg("Zdravo")).status)
        assertEquals(0, OpsApi.json(OpsApi.get(client, outsider, base)).arr("items").size)
        assertEquals(HttpStatusCode.NotFound, OpsApi.post(client, outsider, base, """{"kind":"PARENT_TEACHER","childId":"$child","initialMessage":${msg("x")}}""").status)
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, t.tokenOf(t.userB), "/api/v1/organizations/${t.orgB}/conversations/$convId").status)
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, t.tokenOf(t.userB), "$base/$convId").status)

        // Teacher answers (idempotent), the parent gets 1 unread and a MESSAGE notification without the body.
        val replyId = UUID.randomUUID()
        val reply = OpsApi.post(client, teacher, "$base/$convId/messages", msg("U redu, hvala", replyId))
        assertEquals(HttpStatusCode.Created, reply.status, reply.bodyAsText())
        val replyMessageId = OpsApi.json(reply).str("id")!!
        assertEquals(replyMessageId, OpsApi.json(OpsApi.post(client, teacher, "$base/$convId/messages", msg("U redu, hvala", replyId))).str("id"))
        assertEquals(HttpStatusCode.Conflict, OpsApi.post(client, parent, "$base/$convId/messages", msg("tudji id", replyId)).status)
        assertEquals(0, OpsApi.json(OpsApi.get(client, teacher, "$base/$convId")).int("unreadCount"))
        assertEquals(1, OpsApi.json(OpsApi.get(client, parent, "$base/$convId")).int("unreadCount"))
        val inbox = OpsApi.get(client, parent, "/api/v1/me/notifications?kind=MESSAGE")
        val inboxText = inbox.bodyAsText()
        assertTrue(inboxText.contains("message.received") && inboxText.contains(convId), inboxText)
        assertFalse(inboxText.contains("U redu, hvala"), "message body must never be copied into notifications")

        // Read position: monotonic, drives unreadCount.
        val read = OpsApi.put(client, parent, "$base/$convId/read-position", """{"lastReadMessageId":"$replyMessageId"}""", null)
        assertEquals(HttpStatusCode.OK, read.status, read.bodyAsText())
        assertEquals(0, OpsApi.json(read).int("unreadCount"))
        val firstMessageId = OpsApi.json(OpsApi.get(client, parent, "$base/$convId/messages?sort=createdAt:asc")).arr("items")[0].jsonObject.str("id")!!
        val older = OpsApi.json(OpsApi.put(client, parent, "$base/$convId/read-position", """{"lastReadMessageId":"$firstMessageId"}""", null))
        assertEquals(replyMessageId, older.str("lastReadMessageId"))
        assertEquals(HttpStatusCode.UnprocessableEntity, OpsApi.put(client, parent, "$base/$convId/read-position", """{"lastReadMessageId":"${UUID.randomUUID()}"}""", null).status)

        // Pagination: newest first, cursor to the older page.
        val page1 = OpsApi.json(OpsApi.get(client, parent, "$base/$convId/messages?limit=2"))
        assertEquals(replyMessageId, page1.arr("items")[0].jsonObject.str("id"))
        val cursor = assertNotNull(page1.str("nextCursor"))
        assertEquals(1, OpsApi.json(OpsApi.get(client, parent, "$base/$convId/messages?limit=2&cursor=$cursor")).arr("items").size)

        // Guardian revoked: loses access on the next request (lazy left_at), stays listed as a former participant.
        assertEquals(HttpStatusCode.OK, OpsApi.get(client, parent2, "$base/$convId").status)
        t.runtimeDb.transactionBlocking(DbContext.Tenant(t.orgA, t.platformAdmin)) { c ->
            c.update("UPDATE app.guardians SET status = 'REVOKED', revoked_at = now() WHERE id = ?", parent2Guardian)
        }
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, parent2, "$base/$convId").status)
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, parent2, "$base/$convId/messages").status)
        assertEquals(HttpStatusCode.NotFound, OpsApi.post(client, parent2, "$base/$convId/messages", msg("Jos sam tu?")).status)
        assertEquals(0, OpsApi.json(OpsApi.get(client, parent2, base)).arr("items").size)
        val left = OpsApi.json(OpsApi.get(client, parent, "$base/$convId")).arr("participants")
            .map { it.jsonObject }.first { it.str("membershipId") == parent2Membership.toString() }
        assertNotNull(left.str("leftAt"))
    }

    @Test
    fun `role rules, administration conversations and validation`() = testApplication {
        startApp()
        val parent = t.tokenOf(t.userMulti)
        val admin = t.tokenOf(t.userA)
        val teacher = extra.tokenOf(teacherUser)

        // A teacher cannot open an administration conversation; the manager can, with admins + guardians as participants.
        assertEquals(HttpStatusCode.Forbidden, OpsApi.post(client, teacher, base, """{"kind":"PARENT_ADMIN","childId":"$child","initialMessage":${msg("x")}}""").status)
        val byAdmin = OpsApi.post(client, admin, base, """{"kind":"PARENT_ADMIN","childId":"$child","initialMessage":${msg("Molimo potvrdu upisa")}}""")
        assertEquals(HttpStatusCode.Created, byAdmin.status, byAdmin.bodyAsText())
        val adminConv = OpsApi.json(byAdmin)
        val adminRoles = adminConv.arr("participants").map { it.jsonObject.str("participantRole") }.toSet()
        assertEquals(setOf("GUARDIAN", "ADMIN"), adminRoles)
        val adminConvId = adminConv.str("id")!!
        // The OWNER is a participant too (joined lazily on his first list call).
        assertEquals(listOf(adminConvId), OpsApi.json(OpsApi.get(client, t.tokenOf(t.userOwner), "$base?kind=PARENT_ADMIN")).arr("items").map { it.jsonObject.str("id") })
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, teacher, "$base/$adminConvId").status)
        assertEquals(1, OpsApi.json(OpsApi.get(client, parent, "$base?unreadOnly=true&kind=PARENT_ADMIN")).arr("items").size)

        // A manager cannot start a PARENT_TEACHER conversation he would not be part of.
        assertEquals(HttpStatusCode.Forbidden, OpsApi.post(client, admin, base, """{"kind":"PARENT_TEACHER","childId":"$child","initialMessage":${msg("x")}}""").status)
        val noGuardian = OpsApi.post(client, admin, base, """{"kind":"PARENT_ADMIN","childId":"$childWithoutGuardian","initialMessage":${msg("x")}}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, noGuardian.status)
        assertTrue(noGuardian.bodyAsText().contains("NO_GUARDIANS"))
        assertEquals(0, OpsApi.json(OpsApi.get(client, admin, "$base?childId=$childWithoutGuardian")).arr("items").size, "failed create must roll back")

        // Validation (422): kind, child id, empty / too long body, bad clientMessageId, bad query.
        assertEquals(HttpStatusCode.UnprocessableEntity, OpsApi.post(client, parent, base, """{"kind":"CHAT","childId":"$child","initialMessage":${msg("x")}}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, OpsApi.post(client, parent, base, """{"kind":"PARENT_TEACHER","childId":"nope","initialMessage":${msg("x")}}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, OpsApi.post(client, parent, base, """{"kind":"PARENT_TEACHER","childId":"$child"}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, OpsApi.post(client, parent, "$base/$adminConvId/messages", msg("   ")).status)
        assertEquals(HttpStatusCode.UnprocessableEntity, OpsApi.post(client, parent, "$base/$adminConvId/messages", msg("a".repeat(4001))).status)
        assertEquals(HttpStatusCode.Created, OpsApi.post(client, parent, "$base/$adminConvId/messages", msg("a".repeat(4000))).status)
        assertEquals(HttpStatusCode.UnprocessableEntity, OpsApi.post(client, parent, "$base/$adminConvId/messages", """{"clientMessageId":"x","body":"ok"}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, OpsApi.get(client, parent, "$base?kind=X").status)
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, parent, "$base/not-a-uuid").status)
    }
}
