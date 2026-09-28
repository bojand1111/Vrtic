package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.modules.auth.Tokens
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.TestTenants
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.HttpRequestBuilder
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
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Staff module on native PostgreSQL (VRTIC_TEST_DB=1): employees, memberships (list/revoke),
 * invitations (create/list/revoke) and the profile rows created when an invitation is accepted
 * (employee for ADMIN/TEACHER, PENDING guardian link for PARENT).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class) // acceptance -> revoke builds on the registered teacher
class StaffIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants
    private val tag = UUID.randomUUID().toString().take(8)
    private val child = UUID.randomUUID()
    private val teacherEmail = "staff-teacher-$tag@example.test"
    private val parentEmail = "staff-parent-$tag@example.test"
    private val teacherToken = Tokens.generate(Tokens.Kind.INVITE)
    private val parentToken = Tokens.generate(Tokens.Kind.INVITE)
    private val password = "Staff-Test-Password-$tag"
    private var teacherAccess = ""

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
        tenants.runtimeDb.transactionBlocking(DbContext.Tenant(tenants.orgA, tenants.platformAdmin)) { c ->
            c.prepareStatement("INSERT INTO app.children (id, organization_id, given_name, family_name, date_of_birth) VALUES (?, ?, 'Ana', 'Staff', DATE '2021-06-01')").use { st ->
                st.setObject(1, child); st.setObject(2, tenants.orgA); st.executeUpdate()
            }
            for ((token, email, role) in listOf(Triple(teacherToken, teacherEmail, "TEACHER"), Triple(parentToken, parentEmail, "PARENT"))) {
                c.prepareStatement(
                    "INSERT INTO app.invitations (organization_id, email, role, child_id, token_hash, invited_by, expires_at) VALUES (?, ?, ?, ?, ?, ?, now() + interval '7 days')",
                ).use { st ->
                    st.setObject(1, tenants.orgA); st.setString(2, email); st.setString(3, role)
                    st.setObject(4, if (role == "PARENT") child else null, java.sql.Types.OTHER)
                    st.setBytes(5, Tokens.sha256(token)); st.setObject(6, tenants.userOwner); st.executeUpdate()
                }
            }
        }
        // cross-tenant probe: userOwner is also an ADMIN of org B, so permission checks pass there and only isolation answers
        tenants.runtimeDb.transactionBlocking(DbContext.Tenant(tenants.orgB, tenants.platformAdmin)) { c ->
            c.prepareStatement("INSERT INTO app.organization_memberships (organization_id, user_id, role, status, accepted_at) VALUES (?, ?, 'ADMIN', 'ACTIVE', now())").use { st ->
                st.setObject(1, tenants.orgB); st.setObject(2, tenants.userOwner); st.executeUpdate()
            }
        }
    }

    @AfterAll
    fun tearDown() {
        if (!enabled) return
        val owner = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl; username = config.dbOwnerUser; password = config.requireOwnerPassword(); maximumPoolSize = 1; isAutoCommit = false
            },
        )
        Database(owner).transactionBlocking(DbContext.None) { c ->
            c.createStatement().use { it.execute("SELECT set_config('app.maintenance_mode', 'on', true)") }
            c.prepareStatement("DELETE FROM app.invitations WHERE accepted_user_id IN (SELECT id FROM app.users WHERE email IN (?, ?))").use { st -> st.setString(1, teacherEmail); st.setString(2, parentEmail); st.executeUpdate() }
            c.prepareStatement("DELETE FROM app.organization_memberships WHERE user_id IN (SELECT id FROM app.users WHERE email IN (?, ?))").use { st -> st.setString(1, teacherEmail); st.setString(2, parentEmail); st.executeUpdate() }
            c.prepareStatement("DELETE FROM app.users WHERE email IN (?, ?)").use { st -> st.setString(1, teacherEmail); st.setString(2, parentEmail); st.executeUpdate() }
        }
        owner.close()
        tenants.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = tenants.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    private fun base(org: UUID = tenants.orgA) = "/api/v1/organizations/$org"

    private suspend fun ApplicationTestBuilder.call(method: String, path: String, user: UUID?, body: String? = null, bearer: String? = null): HttpResponse {
        val token = bearer ?: user?.let { tenants.tokenOf(it) }
        val build: HttpRequestBuilder.() -> Unit = {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return when (method) {
            "GET" -> client.get(path, build)
            "POST" -> client.post(path, build)
            "PATCH" -> client.patch(path, build)
            else -> error(method)
        }
    }

    private fun idOf(json: String): String = Regex("\"id\":\"([0-9a-f-]{36})\"").find(json)!!.groupValues[1]

    @Test
    @Order(1)
    fun `invitations - roles, validation, duplicates, revoke, never the token`() = testApplication {
        startApp()
        val email = "new-teacher-$tag@example.test"
        val created = call("POST", "${base()}/invitations", tenants.userA, """{"email":" ${email.uppercase()} ","role":"TEACHER"}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val cb = created.bodyAsText()
        assertTrue(cb.contains("\"status\":\"PENDING\"") && cb.contains("\"email\":\"$email\"") && !cb.contains("vci_") && !cb.contains("token"), cb)
        val invitation = idOf(cb)
        val stored = tenants.runtimeDb.transactionBlocking(DbContext.Tenant(tenants.orgA, tenants.platformAdmin)) { c ->
            c.prepareStatement("SELECT expires_at > now() + interval '6 days 23 hours' AS ok FROM app.invitations WHERE id = ?::uuid").use { st ->
                st.setString(1, invitation); st.executeQuery().use { rs -> rs.next(); rs.getBoolean("ok") }
            }
        }
        assertTrue(stored, "7-day validity")

        val dup = call("POST", "${base()}/invitations", tenants.userOwner, """{"email":"$email","role":"TEACHER"}""")
        assertEquals(HttpStatusCode.Conflict, dup.status)
        assertTrue(dup.bodyAsText().contains("INVITATION_PENDING"))
        val existing = call("POST", "${base()}/invitations", tenants.userOwner, """{"email":"${tenants.emailOf(tenants.userA)}","role":"ADMIN"}""")
        assertEquals(HttpStatusCode.Conflict, existing.status)
        assertTrue(existing.bodyAsText().contains("MEMBERSHIP_EXISTS"))

        // role escalation: ADMIN cannot invite ADMIN, nobody can invite OWNER; PARENT/TEACHER have no MEMBER_INVITE
        val escalation = call("POST", "${base()}/invitations", tenants.userA, """{"email":"x-$tag@example.test","role":"ADMIN"}""")
        assertEquals(HttpStatusCode.Forbidden, escalation.status)
        assertTrue(escalation.bodyAsText().contains("ROLE_ESCALATION"))
        assertEquals(HttpStatusCode.Forbidden, call("POST", "${base()}/invitations", tenants.userOwner, """{"email":"x-$tag@example.test","role":"OWNER"}""").status)
        assertEquals(HttpStatusCode.Forbidden, call("POST", "${base()}/invitations", tenants.userMulti, """{"email":"x-$tag@example.test","role":"TEACHER"}""").status)
        assertEquals(HttpStatusCode.Forbidden, call("GET", "${base()}/invitations", tenants.userMulti).status)

        // validation and child rules
        val invalid = call("POST", "${base()}/invitations", tenants.userOwner, """{"email":"not-an-email","role":"PARENT"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        assertTrue(invalid.bodyAsText().contains("\"field\":\"email\"") && invalid.bodyAsText().contains("\"field\":\"childId\""), invalid.bodyAsText())
        assertEquals(HttpStatusCode.UnprocessableEntity, call("POST", "${base()}/invitations", tenants.userOwner, """{"email":"y-$tag@example.test","role":"TEACHER","childId":"$child"}""").status)
        assertEquals(HttpStatusCode.NotFound, call("POST", "${base()}/invitations", tenants.userOwner, """{"email":"y-$tag@example.test","role":"PARENT","childId":"${UUID.randomUUID()}"}""").status)
        val parentInv = call("POST", "${base()}/invitations", tenants.userOwner, """{"email":"y-$tag@example.test","role":"PARENT","childId":"$child"}""")
        assertEquals(HttpStatusCode.Created, parentInv.status, parentInv.bodyAsText())
        assertTrue(parentInv.bodyAsText().contains("\"childName\":\"Ana Staff\""))

        // list: pending by default; other tenants never see it
        val list = call("GET", "${base()}/invitations", tenants.userA).bodyAsText()
        assertTrue(list.contains(invitation) && list.contains("y-$tag@example.test"), list)
        assertEquals(HttpStatusCode.NotFound, call("POST", "${base(tenants.orgB)}/invitations/$invitation/revoke", tenants.userOwner).status)

        // revoke once (200, REVOKED), then 409; revoked ones leave the pending list
        val revoked = call("POST", "${base()}/invitations/$invitation/revoke", tenants.userA)
        assertEquals(HttpStatusCode.OK, revoked.status, revoked.bodyAsText())
        assertTrue(revoked.bodyAsText().contains("\"status\":\"REVOKED\""))
        assertEquals(HttpStatusCode.Conflict, call("POST", "${base()}/invitations/$invitation/revoke", tenants.userA).status)
        assertFalse(call("GET", "${base()}/invitations", tenants.userA).bodyAsText().contains(invitation))
        assertTrue(call("GET", "${base()}/invitations?status=REVOKED", tenants.userA).bodyAsText().contains(invitation))
        assertEquals(HttpStatusCode.UnprocessableEntity, call("GET", "${base()}/invitations?status=DONE", tenants.userA).status)
    }

    @Test
    @Order(2)
    fun `accepting invitations creates the employee profile and the PENDING guardian link`() = testApplication {
        startApp()
        fun register(token: String, given: String) =
            """{"invitationToken":"$token","password":"$password","givenName":"$given","familyName":"Novak","device":{"clientKind":"IOS"}}"""
        val teacher = call("POST", "/api/v1/auth/register", null, register(teacherToken, "Jelena"))
        assertEquals(HttpStatusCode.Created, teacher.status, teacher.bodyAsText())
        teacherAccess = Regex("\"accessToken\":\"(vca_[^\"]+)\"").find(teacher.bodyAsText())!!.groupValues[1]
        val parent = call("POST", "/api/v1/auth/register", null, register(parentToken, "Marko"))
        assertEquals(HttpStatusCode.Created, parent.status, parent.bodyAsText())

        val employees = call("GET", "${base()}/employees?role=TEACHER", tenants.userOwner).bodyAsText()
        assertTrue(employees.contains("\"displayName\":\"Jelena Novak\"") && employees.contains("\"email\":\"$teacherEmail\""), employees)
        val guardian = tenants.runtimeDb.transactionBlocking(DbContext.Tenant(tenants.orgA, tenants.platformAdmin)) { c ->
            c.prepareStatement(
                "SELECT g.status, g.relationship FROM app.guardians g JOIN app.organization_memberships m ON m.id = g.membership_id " +
                    "JOIN app.users u ON u.id = m.user_id WHERE g.child_id = ? AND u.email = ?",
            ).use { st ->
                st.setObject(1, child); st.setString(2, parentEmail)
                st.executeQuery().use { rs -> if (rs.next()) rs.getString("status") + "/" + rs.getString("relationship") else null }
            }
        }
        assertEquals("PENDING/OTHER", guardian)
        // PENDING is not CONFIRMED: the new parent does not see the child's group scope yet, but is an ACTIVE member
        val members = call("GET", "${base()}/memberships?role=PARENT", tenants.userA).bodyAsText()
        assertTrue(members.contains(parentEmail) && members.contains("\"childrenCount\":1"), members)
        // the teacher reads their own profile, but not the list
        val employeeId = Regex("\"id\":\"([0-9a-f-]{36})\",\"organizationId\":\"[^\"]+\",\"membershipId\"").find(employees)!!.groupValues[1]
        assertEquals(HttpStatusCode.OK, call("GET", "${base()}/employees/$employeeId", null, bearer = teacherAccess).status)
        assertEquals(HttpStatusCode.Forbidden, call("GET", "${base()}/employees", null, bearer = teacherAccess).status)
    }

    @Test
    @Order(3)
    fun `employees - create profile for a staff membership, edit, permissions`() = testApplication {
        startApp()
        val adminMembership = tenants.membershipAAdmin
        assertEquals(HttpStatusCode.Forbidden, call("POST", "${base()}/employees", tenants.userMulti, """{"membershipId":"$adminMembership"}""").status)
        val notStaff = call("POST", "${base()}/employees", tenants.userOwner, """{"membershipId":"${tenants.membershipMultiA}"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, notStaff.status)
        assertTrue(notStaff.bodyAsText().contains("NOT_STAFF"))
        val created = call("POST", "${base()}/employees", tenants.userOwner, """{"membershipId":"$adminMembership","jobTitle":"Direktorka"}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val id = idOf(created.bodyAsText())
        assertTrue(created.bodyAsText().contains("\"displayName\":\"A Fixture\"") && created.bodyAsText().contains("\"permissions\":[\"CHILD_HEALTH_READ\"]"), created.bodyAsText())
        val again = call("POST", "${base()}/employees", tenants.userOwner, """{"membershipId":"$adminMembership"}""")
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertTrue(again.bodyAsText().contains("EMPLOYEE_EXISTS"))

        val patched = call("PATCH", "${base()}/employees/$id", tenants.userA, """{"displayName":"Ana Admin","phone":"060123","startedAt":"2024-09-01","jobTitle":null}""")
        assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
        assertTrue(patched.bodyAsText().contains("\"displayName\":\"Ana Admin\"") && !patched.bodyAsText().contains("Direktorka"), patched.bodyAsText())
        val badDates = call("PATCH", "${base()}/employees/$id", tenants.userA, """{"endedAt":"2024-01-01"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, badDates.status)
        assertTrue(badDates.bodyAsText().contains("MUST_BE_AFTER_START"))
        assertEquals(HttpStatusCode.UnprocessableEntity, call("PATCH", "${base()}/employees/$id", tenants.userA, """{"primaryLocationId":"${UUID.randomUUID()}"}""").status)
        assertEquals(HttpStatusCode.Forbidden, call("PATCH", "${base()}/employees/$id", tenants.userMulti, """{"displayName":"X"}""").status)
        assertEquals(HttpStatusCode.Forbidden, call("GET", "${base()}/employees", tenants.userMulti).status)
        assertEquals(HttpStatusCode.NotFound, call("GET", "${base()}/employees/$id", tenants.userMulti).status)
        assertEquals(HttpStatusCode.NotFound, call("GET", "${base(tenants.orgB)}/employees/$id", tenants.userOwner).status)
        assertTrue(call("GET", "${base()}/employees", tenants.userOwner).bodyAsText().contains("Ana Admin"))
    }

    @Test
    @Order(4)
    fun `memberships - list for managers, revoke rules and effects`() = testApplication {
        startApp()
        val list = call("GET", "${base()}/memberships", tenants.userA)
        assertEquals(HttpStatusCode.OK, list.status)
        val lb = list.bodyAsText()
        assertTrue(lb.contains("\"role\":\"OWNER\"") && lb.contains(tenants.emailOf(tenants.userMulti)) && lb.contains(teacherEmail), lb)
        assertEquals(HttpStatusCode.Forbidden, call("GET", "${base()}/memberships", tenants.userMulti).status)
        assertEquals(HttpStatusCode.UnprocessableEntity, call("GET", "${base()}/memberships?search=a", tenants.userA).status)
        assertTrue(call("GET", "${base()}/memberships?search=jel", tenants.userA).bodyAsText().contains(teacherEmail))

        val ownerMembership = Regex("\"id\":\"([0-9a-f-]{36})\",\"organizationId\":\"[^\"]+\",\"userId\":\"${tenants.userOwner}\"").find(lb)
        assertNotNull(ownerMembership, lb)
        val teacherMembership = Regex("\"id\":\"([0-9a-f-]{36})\"[^}]*\"email\":\"$teacherEmail\"").find(lb)!!.groupValues[1]
        val reason = """{"reason":"Left the kindergarten"}"""
        assertEquals(HttpStatusCode.Forbidden, call("POST", "${base()}/memberships/$teacherMembership/revoke", tenants.userMulti, reason).status)
        assertEquals(HttpStatusCode.UnprocessableEntity, call("POST", "${base()}/memberships/$teacherMembership/revoke", tenants.userA, """{"reason":"x"}""").status)
        val owner = call("POST", "${base()}/memberships/${ownerMembership.groupValues[1]}/revoke", tenants.userA, reason)
        assertEquals(HttpStatusCode.Conflict, owner.status)
        assertTrue(owner.bodyAsText().contains("OWNER_NOT_REVOCABLE"))
        val self = call("POST", "${base()}/memberships/${tenants.membershipAAdmin}/revoke", tenants.userA, reason)
        assertEquals(HttpStatusCode.Conflict, self.status)
        assertTrue(self.bodyAsText().contains("CANNOT_REVOKE_SELF"))
        assertEquals(HttpStatusCode.NotFound, call("POST", "${base(tenants.orgB)}/memberships/$teacherMembership/revoke", tenants.userOwner, reason).status)

        val revoked = call("POST", "${base()}/memberships/$teacherMembership/revoke", tenants.userA, reason)
        assertEquals(HttpStatusCode.OK, revoked.status, revoked.bodyAsText())
        assertTrue(revoked.bodyAsText().contains("\"status\":\"REVOKED\""))
        assertEquals(HttpStatusCode.Conflict, call("POST", "${base()}/memberships/$teacherMembership/revoke", tenants.userA, reason).status)
        // immediate effect: the revoked teacher no longer reaches the tenant; the staff profile is ended
        assertEquals(HttpStatusCode.NotFound, call("GET", "${base()}/settings", null, bearer = teacherAccess).status)
        val ended = call("GET", "${base()}/employees?membershipStatus=REVOKED", tenants.userOwner).bodyAsText()
        assertTrue(ended.contains(teacherEmail) && ended.contains("\"endedAt\":\""), ended)
    }
}
