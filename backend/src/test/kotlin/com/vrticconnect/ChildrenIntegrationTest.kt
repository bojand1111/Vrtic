package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.TestTenants
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Children, enrollments, guardians, parents overview and pickup persons on native PostgreSQL (VRTIC_TEST_DB=1). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ChildrenIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants
    private lateinit var s: ChildrenTestSupport
    private lateinit var groupA1: UUID
    private lateinit var groupA2: UUID
    private lateinit var groupB: UUID
    private lateinit var childB: UUID
    private lateinit var childBOutside: UUID
    private lateinit var childId: String

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
        s = ChildrenTestSupport(tenants)
        val locA = s.location(tenants.orgA)
        groupA1 = s.group(tenants.orgA, locA, "Leptirići")
        groupA2 = s.group(tenants.orgA, locA, "Bubamare", capacity = 1)
        val locB = s.location(tenants.orgB)
        groupB = s.group(tenants.orgB, locB, "Grupa B")
        val otherB = s.group(tenants.orgB, locB, "Druga B")
        s.assignTeacher(tenants.orgB, tenants.membershipBTeacher, groupB)
        childB = s.child(tenants.orgB, "Ana", "Bojić", groupB)
        childBOutside = s.child(tenants.orgB, "Mila", "Van", otherB)
    }

    @AfterAll
    fun tearDown() {
        if (enabled) tenants.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = tenants.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    private val a get() = s.base(tenants.orgA)
    private val b get() = s.base(tenants.orgB)

    @Test
    @Order(1)
    fun `managers create, list and update children with optimistic versioning`() = testApplication {
        startApp()
        with(s) {
            val body = """{"givenName":"Luka","familyName":"Perić","dateOfBirth":"2021-05-06","generalNotes":"Donosi ćebe","initialEnrollment":{"groupId":"$groupA1","validFrom":"${today.minusDays(3)}"}}"""
            val created = client.postAs(tenants.userA, "$a/children", body)
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            assertEquals("\"1\"", created.headers[HttpHeaders.ETag])
            val json = created.json()
            childId = json.str("id")!!
            val current = json["currentEnrollment"]!!.jsonObject
            assertEquals(groupA1.toString(), current.str("groupId"))
            assertEquals("Leptirići", current.str("groupName"))
            assertEquals("ACTIVE", current.str("status"))

            // 422 with all field errors, 403 for PARENT, 404 for a non-member
            val invalid = client.postAs(tenants.userA, "$a/children", """{"familyName":"","dateOfBirth":"${today.plusDays(1)}"}""")
            assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
            val text = invalid.bodyAsText()
            assertTrue(text.contains("givenName") && text.contains("familyName") && text.contains("IN_FUTURE"), text)
            assertEquals(HttpStatusCode.Forbidden, client.postAs(tenants.userMulti, "$a/children", body).status)
            assertEquals(HttpStatusCode.NotFound, client.postAs(tenants.userB, "$a/children", body).status)

            // list with filters
            val list = client.getAs(tenants.userOwner, "$a/children?groupId=$groupA1&search=Lu").json()
            assertEquals(listOf(childId), list.arr("items").map { it.str("id") })
            assertEquals(0, client.getAs(tenants.userOwner, "$a/children?search=Zz").json().arr("items").size)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.getAs(tenants.userOwner, "$a/children?sort=evil").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.getAs(tenants.userOwner, "$a/children?search=L").status)

            // PATCH: If-Match required, stale version -> 409 with currentVersion
            assertEquals(428, client.patchAs(tenants.userA, "$a/children/$childId", """{"givenName":"Luka M"}""").status.value)
            val ok = client.patchAs(tenants.userA, "$a/children/$childId", """{"givenName":"Luka M","generalNotes":null}""", mapOf(HttpHeaders.IfMatch to "\"1\""))
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
            assertEquals("\"2\"", ok.headers[HttpHeaders.ETag])
            assertEquals(null, ok.json().str("generalNotes"))
            val stale = client.patchAs(tenants.userA, "$a/children/$childId", """{"status":"INACTIVE"}""", mapOf(HttpHeaders.IfMatch to "\"1\""))
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertTrue(stale.bodyAsText().contains("\"currentVersion\":2"), stale.bodyAsText())
            assertEquals(HttpStatusCode.UnprocessableEntity, client.patchAs(tenants.userA, "$a/children/$childId", """{"status":"GONE","version":2}""").status)
            assertEquals(HttpStatusCode.Forbidden, client.patchAs(tenants.userMulti, "$a/children/$childId", """{"givenName":"X"}""", mapOf(HttpHeaders.IfMatch to "\"2\"")).status)
        }
    }

    @Test
    @Order(2)
    fun `enrollments move a child between groups in one transaction and detect overlap and capacity`() = testApplication {
        startApp()
        with(s) {
            val from = today.plusDays(7)
            val overlap = client.postAs(tenants.userA, "$a/children/$childId/enrollments", """{"groupId":"$groupA2","validFrom":"$from"}""")
            assertEquals(HttpStatusCode.Conflict, overlap.status)
            assertTrue(overlap.bodyAsText().contains("ENROLLMENT_OVERLAP"))
            val moved = client.postAs(tenants.userA, "$a/children/$childId/enrollments", """{"groupId":"$groupA2","validFrom":"$from","endCurrentEnrollment":true}""")
            assertEquals(HttpStatusCode.Created, moved.status, moved.bodyAsText())
            assertEquals("PLANNED", moved.json().str("status"))
            val items = client.getAs(tenants.userA, "$a/children/$childId/enrollments").json().arr("items")
            assertEquals(2, items.size)
            assertEquals(from.minusDays(1).toString(), items[1].str("validTo"))
            // group A2 has capacity 1: a second child cannot join on the same date
            val second = s.child(tenants.orgA, "Iva", "Kapacitet", null)
            val full = client.postAs(tenants.userA, "$a/children/$second/enrollments", """{"groupId":"$groupA2","validFrom":"$from"}""")
            assertEquals(HttpStatusCode.Conflict, full.status)
            assertTrue(full.bodyAsText().contains("GROUP_FULL"))
            // cancel the planned enrollment of the second child's future; end validation
            val planned = client.postAs(tenants.userA, "$a/children/$second/enrollments", """{"groupId":"$groupA1","validFrom":"$from"}""").json()
            val cancelled = client.postAs(tenants.userA, "$a/enrollments/${planned.str("id")}/end", """{"validTo":"${from.minusDays(1)}"}""")
            assertEquals("CANCELLED", cancelled.json().str("status"))
            assertEquals(HttpStatusCode.Conflict, client.postAs(tenants.userA, "$a/enrollments/${planned.str("id")}/end", """{"validTo":"$from"}""").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postAs(tenants.userA, "$a/children/$second/enrollments", """{"groupId":"${UUID.randomUUID()}","validFrom":"$from"}""").status)
            assertEquals(HttpStatusCode.Forbidden, client.postAs(tenants.userMulti, "$a/enrollments/${planned.str("id")}/end", """{"validTo":"$from"}""").status)
        }
    }

    @Test
    @Order(3)
    fun `staff link, update, confirm and revoke guardians and a parent only sees linked children`() = testApplication {
        startApp()
        with(s) {
            assertEquals(0, client.getAs(tenants.userMulti, "$a/children").json().arr("items").size)
            assertEquals(HttpStatusCode.NotFound, client.getAs(tenants.userMulti, "$a/children/$childId").status)
            // parents never link themselves
            val link = """{"membershipId":"${tenants.membershipMultiA}","relationship":"MOTHER","canReportAbsence":true}"""
            assertEquals(HttpStatusCode.Forbidden, client.postAs(tenants.userMulti, "$a/children/$childId/guardians", link).status)
            val created = client.postAs(tenants.userA, "$a/children/$childId/guardians", link)
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val guardianId = created.json().str("id")!!
            assertEquals("CONFIRMED", created.json().str("status"))
            assertEquals(HttpStatusCode.Conflict, client.postAs(tenants.userA, "$a/children/$childId/guardians", link).status)
            val ownerMembership = s.ownerMembership(tenants.orgA, tenants.userOwner)
            val notParent = client.postAs(tenants.userA, "$a/children/$childId/guardians", """{"membershipId":"$ownerMembership","relationship":"FATHER"}""")
            assertEquals(HttpStatusCode.UnprocessableEntity, notParent.status)
            assertTrue(notParent.bodyAsText().contains("NOT_PARENT"))

            // parent sees the child and only their own guardian link
            val mine = client.getAs(tenants.userMulti, "$a/children").json().arr("items")
            assertEquals(listOf(childId), mine.map { it.str("id") })
            val second = s.parentMembership(tenants.orgA, tenants.userB)
            s.pendingGuardian(tenants.orgA, UUID.fromString(childId), second)
            val detail = client.getAs(tenants.userMulti, "$a/children/$childId").json()
            assertEquals(1, detail.arr("guardians").size)
            assertEquals(2, client.getAs(tenants.userA, "$a/children/$childId").json().arr("guardians").size)

            // flags, parents overview, confirm a pending link
            val patched = client.patchAs(tenants.userA, "$a/guardians/$guardianId", """{"isPrimary":true,"canReportAbsence":false}""")
            assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
            assertEquals("false", patched.json().str("canReportAbsence"))
            assertEquals(HttpStatusCode.UnprocessableEntity, client.patchAs(tenants.userA, "$a/guardians/$guardianId", """{"relationship":"UNCLE"}""").status)
            val parents = client.getAs(tenants.userA, "$a/parents").json().arr("items")
            val multi = parents.first { it.str("membershipId") == tenants.membershipMultiA.toString() }
            assertEquals(childId, multi.arr("links").single().str("childId"))
            assertEquals(HttpStatusCode.Forbidden, client.getAs(tenants.userMulti, "$a/parents").status)
            val pending = parents.first { it.str("membershipId") == second.toString() }.arr("links").single().str("guardianId")
            assertEquals("CONFIRMED", client.postAs(tenants.userA, "$a/guardians/$pending/confirm").json().str("status"))
            assertEquals(HttpStatusCode.Conflict, client.postAs(tenants.userA, "$a/guardians/$pending/confirm").status)

            // revoke needs a reason; afterwards the parent loses access (404)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postAs(tenants.userA, "$a/guardians/$guardianId/revoke", """{"reason":""}""").status)
            val revoked = client.postAs(tenants.userA, "$a/guardians/$guardianId/revoke", """{"reason":"Test revoke"}""")
            assertEquals("REVOKED", revoked.json().str("status"))
            assertEquals(HttpStatusCode.NotFound, client.getAs(tenants.userMulti, "$a/children/$childId").status)
            assertEquals(HttpStatusCode.NotFound, client.postAs(tenants.userA, "$a/guardians/${UUID.randomUUID()}/confirm").status)
            // relink for the pickup test
            assertEquals(HttpStatusCode.Created, client.postAs(tenants.userA, "$a/children/$childId/guardians", link).status)
        }
    }

    @Test
    @Order(4)
    fun `pickup persons are managed by managers and linked parents only`() = testApplication {
        startApp()
        with(s) {
            val added = client.postAs(tenants.userMulti, "$a/children/$childId/pickup-persons", """{"fullName":"Baka Mira","relationship":"GRANDPARENT","phone":"+381601234567"}""")
            assertEquals(HttpStatusCode.Created, added.status, added.bodyAsText())
            val id = added.json().str("id")!!
            assertEquals(tenants.membershipMultiA.toString(), added.json().str("addedByMembershipId"))
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postAs(tenants.userMulti, "$a/children/$childId/pickup-persons", """{"fullName":"B"}""").status)
            val updated = client.patchAs(tenants.userA, "$a/pickup-persons/$id", """{"note":"Dolazi petkom","phone":null}""")
            assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
            assertEquals(null, updated.json().str("phone"))
            assertEquals(1, client.getAs(tenants.userMulti, "$a/children/$childId").json().arr("pickupPersons").size)
            // a parent without a link to the child gets 404; a teacher has no manage permission (403)
            val other = s.child(tenants.orgA, "Tuđe", "Dete", groupA1)
            assertEquals(HttpStatusCode.NotFound, client.postAs(tenants.userMulti, "$a/children/$other/pickup-persons", """{"fullName":"Neko Neki"}""").status)
            assertEquals(HttpStatusCode.Forbidden, client.postAs(tenants.userB, "$b/children/$childB/pickup-persons", """{"fullName":"Neko Neki"}""").status)
            assertEquals(HttpStatusCode.OK, client.postAs(tenants.userMulti, "$a/pickup-persons/$id/revoke").status)
            assertEquals(HttpStatusCode.Conflict, client.postAs(tenants.userMulti, "$a/pickup-persons/$id/revoke").status)
            assertEquals(HttpStatusCode.Conflict, client.patchAs(tenants.userA, "$a/pickup-persons/$id", """{"note":"x"}""").status)
            val all = client.getAs(tenants.userMulti, "$a/children/$childId/pickup-persons").json().arr("items")
            assertEquals("REVOKED", all.single().str("status"))
        }
    }

    @Test
    @Order(5)
    fun `teacher sees only children of assigned groups and never another tenant`() = testApplication {
        startApp()
        with(s) {
            val list = client.getAs(tenants.userB, "$b/children").json().arr("items")
            assertEquals(listOf(childB.toString()), list.map { it.str("id") })
            assertEquals(HttpStatusCode.OK, client.getAs(tenants.userB, "$b/children/$childB").status)
            assertEquals(HttpStatusCode.OK, client.getAs(tenants.userB, "$b/children/$childB/pickup-persons").status)
            assertEquals(HttpStatusCode.NotFound, client.getAs(tenants.userB, "$b/children/$childBOutside").status)
            assertEquals(HttpStatusCode.NotFound, client.getAs(tenants.userB, "$b/children?groupId=${UUID.randomUUID()}").status)
            // cross-tenant: an org A child through org B is 404
            assertEquals(HttpStatusCode.NotFound, client.getAs(tenants.userB, "$b/children/$childId").status)
            assertEquals(HttpStatusCode.Forbidden, client.postAs(tenants.userB, "$b/children", """{"givenName":"A","familyName":"B","dateOfBirth":"2022-01-01"}""").status)
            assertEquals(HttpStatusCode.Forbidden, client.getAs(tenants.userB, "$b/parents").status)
        }
    }
}
