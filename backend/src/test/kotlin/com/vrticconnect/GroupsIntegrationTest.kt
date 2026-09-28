package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.DbContext
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.TestTenants
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E04 groups and teacher assignments on native PostgreSQL (VRTIC_TEST_DB=1): manager CRUD, TEACHER
 * scope through a valid assignment, PARENT scope through a CONFIRMED guardian link, 403/404/409/422.
 * Extra fixture: userB also becomes a TEACHER of org A with a staff profile; one child of userMulti (PARENT in A).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroupsIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants
    private val teacherEmployee = UUID.randomUUID()
    private val child = UUID.randomUUID()

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
        tenants.runtimeDb.transactionBlocking(DbContext.Tenant(tenants.orgA, tenants.platformAdmin)) { c ->
            val membership = UUID.randomUUID()
            c.prepareStatement("INSERT INTO app.organization_memberships (id, organization_id, user_id, role, status, accepted_at) VALUES (?, ?, ?, 'TEACHER', 'ACTIVE', now())").use { st ->
                st.setObject(1, membership); st.setObject(2, tenants.orgA); st.setObject(3, tenants.userB); st.executeUpdate()
            }
            c.prepareStatement("INSERT INTO app.employees (id, organization_id, membership_id, display_name) VALUES (?, ?, ?, 'Vaspitačica B')").use { st ->
                st.setObject(1, teacherEmployee); st.setObject(2, tenants.orgA); st.setObject(3, membership); st.executeUpdate()
            }
            c.prepareStatement("INSERT INTO app.children (id, organization_id, given_name, family_name, date_of_birth) VALUES (?, ?, 'Mila', 'Test', DATE '2022-03-01')").use { st ->
                st.setObject(1, child); st.setObject(2, tenants.orgA); st.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO app.guardians (organization_id, child_id, membership_id, relationship, status, confirmed_at, confirmed_by) VALUES (?, ?, ?, 'MOTHER', 'CONFIRMED', now(), ?)",
            ).use { st -> st.setObject(1, tenants.orgA); st.setObject(2, child); st.setObject(3, tenants.membershipMultiA); st.setObject(4, tenants.userOwner); st.executeUpdate() }
        }
    }

    @AfterAll
    fun tearDown() {
        if (enabled) tenants.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = tenants.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    private fun base(org: UUID = tenants.orgA) = "/api/v1/organizations/$org"

    private suspend fun ApplicationTestBuilder.call(method: String, path: String, user: UUID, body: String? = null): HttpResponse {
        val token = tenants.tokenOf(user)
        val build: HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Authorization, "Bearer $token")
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return when (method) {
            "GET" -> client.get(path, build)
            "POST" -> client.post(path, build)
            "PATCH" -> client.patch(path, build)
            "DELETE" -> client.delete(path, build)
            else -> error(method)
        }
    }

    private fun idOf(json: String): String = Regex("\"id\":\"([0-9a-f-]{36})\"").find(json)!!.groupValues[1]

    @Test
    fun `groups, assignments and scopes per role`() = testApplication {
        startApp()
        val tag = UUID.randomUUID().toString().take(6)
        val today = LocalDate.now()
        val location = idOf(call("POST", "${base()}/locations", tenants.userOwner, """{"name":"Vrtić $tag"}""").bodyAsText())

        // create: 422 per field, 403 for PARENT/TEACHER, 201 for managers, 409 duplicate name in the location
        val invalid = call("POST", "${base()}/groups", tenants.userOwner, """{"locationId":"$location","name":"","capacity":0,"ageFromMonths":40,"ageToMonths":20}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        val ib = invalid.bodyAsText()
        assertTrue(ib.contains("\"field\":\"name\"") && ib.contains("\"field\":\"capacity\"") && ib.contains("MUST_BE_AFTER_FROM"), ib)
        assertEquals(HttpStatusCode.UnprocessableEntity, call("POST", "${base()}/groups", tenants.userOwner, """{"locationId":"${UUID.randomUUID()}","name":"X"}""").status)
        assertEquals(HttpStatusCode.Forbidden, call("POST", "${base()}/groups", tenants.userMulti, """{"locationId":"$location","name":"X"}""").status)
        assertEquals(HttpStatusCode.Forbidden, call("POST", "${base()}/groups", tenants.userB, """{"locationId":"$location","name":"X"}""").status)
        val g1 = call("POST", "${base()}/groups", tenants.userOwner, """{"locationId":"$location","name":"Bubamare","ageFromMonths":36,"ageToMonths":48,"capacity":2}""")
        assertEquals(HttpStatusCode.Created, g1.status, g1.bodyAsText())
        val group1 = idOf(g1.bodyAsText())
        assertTrue(g1.bodyAsText().contains("\"locationName\":\"Vrtić $tag\"") && g1.bodyAsText().contains("\"activeChildrenCount\":0"))
        val group2 = idOf(call("POST", "${base()}/groups", tenants.userA, """{"locationId":"$location","name":"Pčelice"}""").bodyAsText())
        val dup = call("POST", "${base()}/groups", tenants.userA, """{"locationId":"$location","name":"bubamare"}""")
        assertEquals(HttpStatusCode.Conflict, dup.status)
        assertTrue(dup.bodyAsText().contains("GROUP_NAME_TAKEN"))

        // TEACHER without assignment sees nothing; PARENT without enrollment sees nothing
        assertFalse(call("GET", "${base()}/groups", tenants.userB).bodyAsText().contains(group1))
        assertEquals(HttpStatusCode.NotFound, call("GET", "${base()}/groups/$group1", tenants.userB).status)
        assertEquals(HttpStatusCode.NotFound, call("GET", "${base()}/groups/$group1", tenants.userMulti).status)

        // assignment: 403 for TEACHER, 422 invalid, 201 for managers, overlap 409
        val assignBody = """{"groupId":"$group1","employeeId":"$teacherEmployee","assignmentRole":"LEAD","validFrom":"${today.minusDays(1)}"}"""
        assertEquals(HttpStatusCode.Forbidden, call("POST", "${base()}/group-teacher-assignments", tenants.userB, assignBody).status)
        assertEquals(HttpStatusCode.UnprocessableEntity, call("POST", "${base()}/group-teacher-assignments", tenants.userA, """{"groupId":"$group1","employeeId":"$teacherEmployee","validFrom":"yesterday"}""").status)
        val assigned = call("POST", "${base()}/group-teacher-assignments", tenants.userA, assignBody)
        assertEquals(HttpStatusCode.Created, assigned.status, assigned.bodyAsText())
        val assignment = idOf(assigned.bodyAsText())
        val overlap = call("POST", "${base()}/group-teacher-assignments", tenants.userA, assignBody.replace("${today.minusDays(1)}", "${today.plusDays(5)}"))
        assertEquals(HttpStatusCode.Conflict, overlap.status)
        assertTrue(overlap.bodyAsText().contains("ASSIGNMENT_OVERLAP"))

        // TEACHER now sees exactly group1, with the teacher name; not group2
        val teacherList = call("GET", "${base()}/groups", tenants.userB).bodyAsText()
        assertTrue(teacherList.contains(group1) && !teacherList.contains(group2), teacherList)
        assertTrue(teacherList.contains("\"displayName\":\"Vaspitačica B\"") && teacherList.contains("\"assignmentRole\":\"LEAD\""), teacherList)
        assertEquals(HttpStatusCode.NotFound, call("GET", "${base()}/groups/$group2", tenants.userB).status)
        // assignments: teacher sees own only, parent 403, manager filters by group
        assertTrue(call("GET", "${base()}/group-teacher-assignments", tenants.userB).bodyAsText().contains(assignment))
        assertEquals(HttpStatusCode.Forbidden, call("GET", "${base()}/group-teacher-assignments", tenants.userMulti).status)
        assertFalse(call("GET", "${base()}/group-teacher-assignments?groupId=$group2", tenants.userA).bodyAsText().contains(assignment))
        assertEquals(HttpStatusCode.UnprocessableEntity, call("GET", "${base()}/group-teacher-assignments?groupId=abc", tenants.userA).status)

        // enrollment of the parent's child in group1: count 1, parent sees group1 only
        tenants.runtimeDb.transactionBlocking(DbContext.Tenant(tenants.orgA, tenants.platformAdmin)) { c ->
            c.prepareStatement("INSERT INTO app.enrollments (organization_id, child_id, group_id, valid_from) VALUES (?, ?, ?, ?)").use { st ->
                st.setObject(1, tenants.orgA); st.setObject(2, child); st.setObject(3, UUID.fromString(group1)); st.setObject(4, today.minusDays(30)); st.executeUpdate()
            }
        }
        val parentList = call("GET", "${base()}/groups", tenants.userMulti).bodyAsText()
        assertTrue(parentList.contains(group1) && !parentList.contains(group2) && parentList.contains("\"activeChildrenCount\":1"), parentList)
        assertEquals(HttpStatusCode.OK, call("GET", "${base()}/groups/$group1", tenants.userMulti).status)

        // edits: capacity below enrollment 422, deactivate / delete with enrollment 409, valid patch 200
        val below = call("PATCH", "${base()}/groups/$group1", tenants.userA, """{"capacity":1,"ageFromMonths":null}""")
        assertEquals(HttpStatusCode.OK, below.status, below.bodyAsText())
        // explicit null clears ageFromMonths (nulls are omitted from JSON output)
        assertTrue(below.bodyAsText().contains("\"capacity\":1") && !below.bodyAsText().contains("\"ageFromMonths\"") && below.bodyAsText().contains("\"ageToMonths\":48"), below.bodyAsText())
        tenants.runtimeDb.transactionBlocking(DbContext.Tenant(tenants.orgA, tenants.platformAdmin)) { c ->
            val second = UUID.randomUUID()
            c.prepareStatement("INSERT INTO app.children (id, organization_id, given_name, family_name, date_of_birth) VALUES (?, ?, 'Luka', 'Test', DATE '2022-05-01')").use { st -> st.setObject(1, second); st.setObject(2, tenants.orgA); st.executeUpdate() }
            c.prepareStatement("INSERT INTO app.enrollments (organization_id, child_id, group_id, valid_from) VALUES (?, ?, ?, ?)").use { st ->
                st.setObject(1, tenants.orgA); st.setObject(2, second); st.setObject(3, UUID.fromString(group1)); st.setObject(4, today); st.executeUpdate()
            }
        }
        val capacity = call("PATCH", "${base()}/groups/$group1", tenants.userA, """{"capacity":1}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, capacity.status)
        assertTrue(capacity.bodyAsText().contains("CAPACITY_BELOW_ENROLLMENT"))
        assertEquals(HttpStatusCode.Forbidden, call("PATCH", "${base()}/groups/$group1", tenants.userB, """{"name":"X"}""").status)
        val deactivate = call("PATCH", "${base()}/groups/$group1", tenants.userA, """{"status":"INACTIVE"}""")
        assertEquals(HttpStatusCode.Conflict, deactivate.status)
        assertTrue(deactivate.bodyAsText().contains("GROUP_HAS_ENROLLMENTS"))
        assertEquals(HttpStatusCode.Conflict, call("DELETE", "${base()}/groups/$group1", tenants.userA).status)

        // cross-tenant: userB is a TEACHER in org B too; group1 is not visible there
        assertEquals(HttpStatusCode.NotFound, call("GET", "${base(tenants.orgB)}/groups/$group1", tenants.userB).status)

        // revoking the assignment removes the teacher's access on the next request
        assertEquals(HttpStatusCode.Forbidden, call("DELETE", "${base()}/group-teacher-assignments/$assignment", tenants.userB).status)
        assertEquals(HttpStatusCode.NoContent, call("DELETE", "${base()}/group-teacher-assignments/$assignment", tenants.userA).status)
        assertEquals(HttpStatusCode.Conflict, call("DELETE", "${base()}/group-teacher-assignments/$assignment", tenants.userA).status)
        assertEquals(HttpStatusCode.NotFound, call("GET", "${base()}/groups/$group1", tenants.userB).status)

        // an empty group can be deleted
        assertEquals(HttpStatusCode.NoContent, call("DELETE", "${base()}/groups/$group2", tenants.userOwner).status)
        assertEquals(HttpStatusCode.NotFound, call("GET", "${base()}/groups/$group2", tenants.userOwner).status)
    }
}
