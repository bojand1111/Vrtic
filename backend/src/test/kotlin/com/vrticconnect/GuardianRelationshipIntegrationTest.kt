package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.TestTenants
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A child may have at most one MOTHER and one FATHER (non-revoked links); a single parent and other relatives are fine. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GuardianRelationshipIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants
    private lateinit var support: ChildrenTestSupport

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
        support = ChildrenTestSupport(tenants)
    }

    @AfterAll
    fun tearDown() {
        if (enabled) tenants.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = tenants.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    @Test
    fun `second mother or father is refused, single parent and grandparent are allowed`() = testApplication {
        startApp()
        with(support) {
            val org = tenants.orgA
            val a = base(org)
            val group = group(org, location(org), "G")
            val child = child(org, "Mila", "Test", group)
            val mother = parentMembership(org, tenants.userB)
            val secondParent = parentMembership(org, tenants.platformAdmin)
            val third = tenants.membershipMultiA
            fun link(membership: java.util.UUID, relationship: String) = """{"membershipId":"$membership","relationship":"$relationship"}"""

            // single parent: one mother alone is a valid family
            val first = client.postAs(tenants.userA, "$a/children/$child/guardians", link(mother, "MOTHER"))
            assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
            val secondMother = client.postAs(tenants.userA, "$a/children/$child/guardians", link(secondParent, "MOTHER"))
            assertEquals(HttpStatusCode.UnprocessableEntity, secondMother.status)
            assertTrue(secondMother.bodyAsText().contains("RELATIONSHIP_TAKEN"), secondMother.bodyAsText())

            val father = client.postAs(tenants.userA, "$a/children/$child/guardians", link(secondParent, "FATHER"))
            assertEquals(HttpStatusCode.Created, father.status, father.bodyAsText())
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postAs(tenants.userA, "$a/children/$child/guardians", link(third, "FATHER")).status)

            val grandparent = client.postAs(tenants.userA, "$a/children/$child/guardians", link(third, "GRANDPARENT"))
            assertEquals(HttpStatusCode.Created, grandparent.status, grandparent.bodyAsText())
            val grandparentId = grandparent.json().str("id")!!
            // changing the grandparent into a second mother is refused as well
            val patched = client.patchAs(tenants.userA, "$a/guardians/$grandparentId", """{"relationship":"MOTHER"}""")
            assertEquals(HttpStatusCode.UnprocessableEntity, patched.status)
            assertTrue(patched.bodyAsText().contains("RELATIONSHIP_TAKEN"), patched.bodyAsText())

            // after the mother's link is revoked the role is free again
            val motherId = first.json().str("id")!!
            assertEquals(HttpStatusCode.OK, client.postAs(tenants.userA, "$a/guardians/$motherId/revoke", """{"reason":"test revoke"}""").status)
            assertEquals(HttpStatusCode.OK, client.patchAs(tenants.userA, "$a/guardians/$grandparentId", """{"relationship":"MOTHER"}""").status)
        }
    }
}
