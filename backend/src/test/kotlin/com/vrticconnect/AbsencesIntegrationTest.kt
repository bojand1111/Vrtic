package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.TestTenants
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Absences on native PostgreSQL (VRTIC_TEST_DB=1): report, overlap, scope per role, cancel rules. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AbsencesIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants
    private lateinit var s: ChildrenTestSupport
    private lateinit var childA: UUID
    private lateinit var childAOther: UUID
    private lateinit var guardian: UUID
    private lateinit var childB: UUID
    private lateinit var childBOutside: UUID
    private lateinit var groupB: UUID

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
        s = ChildrenTestSupport(tenants)
        val groupA = s.group(tenants.orgA, s.location(tenants.orgA), "A")
        childA = s.child(tenants.orgA, "Petar", "Roditeljski", groupA)
        childAOther = s.child(tenants.orgA, "Jana", "Tuđa", groupA)
        guardian = s.confirmedGuardian(tenants.orgA, childA, tenants.membershipMultiA)
        val locB = s.location(tenants.orgB)
        groupB = s.group(tenants.orgB, locB, "B")
        s.assignTeacher(tenants.orgB, tenants.membershipBTeacher, groupB)
        childB = s.child(tenants.orgB, "Sara", "Bgrupa", groupB)
        childBOutside = s.child(tenants.orgB, "Ivan", "Van", s.group(tenants.orgB, locB, "Druga"))
    }

    @AfterAll
    fun tearDown() {
        if (enabled) tenants.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = tenants.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    @Test
    fun `parents report and cancel own absences, managers any, teachers read their groups`() = testApplication {
        startApp()
        with(s) {
            val a = base(tenants.orgA)
            val b = base(tenants.orgB)
            val body = """{"childId":"$childA","kind":"SICK","dateFrom":"${today.plusDays(1)}","dateTo":"${today.plusDays(3)}","note":"Prehlada"}"""
            val created = client.postAs(tenants.userMulti, "$a/absences", body)
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            assertEquals("\"1\"", created.headers[HttpHeaders.ETag])
            val absenceId = created.json().str("id")!!
            assertEquals("Petar", created.json().str("childGivenName"))

            // overlap, validation, foreign child, teacher without ABSENCE_REPORT
            val overlap = client.postAs(tenants.userA, "$a/absences", body.replace("SICK", "VACATION"))
            assertEquals(HttpStatusCode.Conflict, overlap.status)
            assertTrue(overlap.bodyAsText().contains("ABSENCE_OVERLAP"))
            val tooLong = client.postAs(tenants.userMulti, "$a/absences", """{"childId":"$childA","kind":"OTHER","dateFrom":"${today.plusDays(10)}","dateTo":"${today.plusDays(400)}"}""")
            assertEquals(HttpStatusCode.UnprocessableEntity, tooLong.status)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postAs(tenants.userMulti, "$a/absences", """{"childId":"$childA","kind":"FLU","dateFrom":"x","dateTo":"${today}"}""").status)
            assertEquals(HttpStatusCode.NotFound, client.postAs(tenants.userMulti, "$a/absences", body.replace(childA.toString(), childAOther.toString())).status)
            assertEquals(HttpStatusCode.Forbidden, client.postAs(tenants.userB, "$b/absences", """{"childId":"$childB","kind":"SICK","dateFrom":"$today","dateTo":"$today"}""").status)

            // guardian flag can_report_absence
            s.setGuardianFlag(tenants.orgA, guardian, "can_report_absence", false)
            assertEquals(HttpStatusCode.Forbidden, client.postAs(tenants.userMulti, "$a/absences", """{"childId":"$childA","kind":"OTHER","dateFrom":"${today.plusDays(20)}","dateTo":"${today.plusDays(21)}"}""").status)
            s.setGuardianFlag(tenants.orgA, guardian, "can_report_absence", true)

            // reads: parent own child, manager with filters, 404 outside scope
            assertEquals(listOf(absenceId), client.getAs(tenants.userMulti, "$a/absences").json().arr("items").map { it.str("id") })
            assertEquals(HttpStatusCode.NotFound, client.getAs(tenants.userMulti, "$a/absences?childId=$childAOther").status)
            assertEquals(1, client.getAs(tenants.userA, "$a/absences?childId=$childA&from=$today&to=${today.plusDays(5)}&kind=SICK").json().arr("items").size)
            assertEquals(0, client.getAs(tenants.userA, "$a/absences?status=CANCELLED").json().arr("items").size)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.getAs(tenants.userA, "$a/absences?from=bad").status)
            assertEquals(HttpStatusCode.OK, client.getAs(tenants.userMulti, "$a/absences/$absenceId").status)

            // a manager-reported absence cannot be cancelled by the parent; managers cancel anything
            val byAdmin = client.postAs(tenants.userA, "$a/absences", """{"childId":"$childA","kind":"VACATION","dateFrom":"${today.plusDays(30)}","dateTo":"${today.plusDays(35)}"}""").json().str("id")!!
            val seenByParent = client.getAs(tenants.userMulti, "$a/absences?from=$today&to=${today.plusDays(60)}").json().arr("items")
            assertEquals(mapOf(absenceId to "true", byAdmin to "false"), seenByParent.associate { it.str("id")!! to it.str("canCancel") })
            assertEquals(HttpStatusCode.Forbidden, client.postAs(tenants.userMulti, "$a/absences/$byAdmin/cancel").status)
            assertEquals(HttpStatusCode.Conflict, client.postAs(tenants.userA, "$a/absences/$byAdmin/cancel", headers = mapOf(HttpHeaders.IfMatch to "\"7\"")).status)
            assertEquals("CANCELLED", client.postAs(tenants.userA, "$a/absences/$byAdmin/cancel", headers = mapOf(HttpHeaders.IfMatch to "\"1\"")).json().str("status"))
            val cancelled = client.postAs(tenants.userMulti, "$a/absences/$absenceId/cancel")
            assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
            assertEquals("\"2\"", cancelled.headers[HttpHeaders.ETag])
            val again = client.postAs(tenants.userMulti, "$a/absences/$absenceId/cancel")
            assertEquals(HttpStatusCode.Conflict, again.status)
            assertTrue(again.bodyAsText().contains("ABSENCE_ALREADY_CANCELLED"))
            assertEquals(2, client.getAs(tenants.userA, "$a/absences?status=CANCELLED&from=$today&to=${today.plusDays(60)}").json().arr("items").size)

            // teacher: own group only; other tenant 404
            s.run {
                tenants.runtimeDb.transactionBlocking(com.vrticconnect.db.DbContext.Tenant(tenants.orgB, tenants.platformAdmin)) { c ->
                    for (child in listOf(childB, childBOutside)) {
                        c.prepareStatement(
                            "INSERT INTO app.absences (organization_id, child_id, kind, date_from, date_to, reported_by_membership_id) VALUES (?, ?, 'SICK', ?, ?, ?)",
                        ).use { st ->
                            st.setObject(1, tenants.orgB); st.setObject(2, child); st.setObject(3, today); st.setObject(4, today)
                            st.setObject(5, tenants.membershipMultiB); st.executeUpdate()
                        }
                    }
                }
            }
            val teacherList = client.getAs(tenants.userB, "$b/absences?groupId=$groupB").json().arr("items")
            assertEquals(listOf(childB.toString()), teacherList.map { it.str("childId") })
            assertEquals(1, client.getAs(tenants.userB, "$b/absences").json().arr("items").size)
            assertEquals(HttpStatusCode.NotFound, client.getAs(tenants.userB, "$b/absences?childId=$childBOutside").status)
            assertEquals(HttpStatusCode.NotFound, client.getAs(tenants.userB, "$b/absences/$absenceId").status)
        }
    }
}
