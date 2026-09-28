package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.modules.health.AlwaysUpProbe
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Menu days: create, duplicate, publish visibility, item replacement (VRTIC_TEST_DB=1). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MenuIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var t: TestTenants

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        t = TestTenants(config).also { it.create() }
    }

    @AfterAll
    fun tearDown() {
        if (enabled) t.destroy()
    }

    private fun ApplicationTestBuilder.startApp() {
        application { module(AppDependencies(config = config, database = t.runtimeDb, readiness = AlwaysUpProbe)) }
    }

    private val base get() = "/api/v1/organizations/${t.orgA}/menu-days"

    @Test
    fun `manager creates and publishes a menu, parents see only published days`() = testApplication {
        startApp()
        val admin = t.tokenOf(t.userA)
        val parent = t.tokenOf(t.userMulti)
        val body = """{"menuDate":"2030-03-04","note":"Sezonsko","items":[{"mealSlot":"LUNCH","description":"Pasulj","allergenTags":["CELERY"]},{"mealSlot":"BREAKFAST","description":"Kifla i mleko","allergenTags":["GLUTEN","MILK"]}]}"""
        val created = OpsApi.post(client, admin, base, body)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val json = OpsApi.json(created)
        val id = json.str("id")!!
        assertEquals(listOf("BREAKFAST", "LUNCH"), json.arr("items").map { it.jsonObject.str("mealSlot") }, "items ordered by slot")
        assertEquals(HttpStatusCode.Conflict, OpsApi.post(client, admin, base, body).status)
        assertEquals("MENU_DAY_EXISTS", OpsApi.json(OpsApi.post(client, admin, base, body)).str("detail"))

        val range = "?from=2030-03-02&to=2030-03-08"
        assertEquals(0, OpsApi.json(OpsApi.get(client, parent, base + range)).arr("items").size)
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, parent, "$base/$id").status)
        assertEquals(1, OpsApi.json(OpsApi.get(client, admin, base + range)).arr("items").size)

        val published = OpsApi.post(client, admin, "$base/$id/publish", ifMatch = 1)
        assertEquals(HttpStatusCode.OK, published.status, published.bodyAsText())
        assertEquals(2, OpsApi.json(published).int("version"))
        assertEquals(1, OpsApi.json(OpsApi.get(client, parent, base + range)).arr("items").size)

        val replaced = OpsApi.put(client, admin, "$base/$id", """{"note":null,"items":[{"mealSlot":"SNACK_PM","description":"Voce"}]}""", 2)
        assertEquals(HttpStatusCode.OK, replaced.status, replaced.bodyAsText())
        assertEquals(1, OpsApi.json(replaced).arr("items").size)
        assertEquals(null, OpsApi.json(replaced).str("note"))
        assertEquals(HttpStatusCode.Conflict, OpsApi.put(client, admin, "$base/$id", """{"items":[]}""", 2).status)
    }

    @Test
    fun `permissions, validation and tenant isolation`() = testApplication {
        startApp()
        val body = """{"menuDate":"2030-04-01","items":[]}"""
        assertEquals(HttpStatusCode.Forbidden, OpsApi.post(client, t.tokenOf(t.userMulti), base, body).status)
        assertEquals(HttpStatusCode.Forbidden, OpsApi.post(client, t.tokenOf(t.userB), "/api/v1/organizations/${t.orgB}/menu-days", body).status)
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, t.tokenOf(t.userB), "$base?from=2030-04-01&to=2030-04-05").status)
        val admin = t.tokenOf(t.userA)
        val invalid = OpsApi.post(client, admin, base, """{"menuDate":"2030-04-02","items":[{"mealSlot":"DINNER","description":"","allergenTags":["gluten"]}]}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        val text = invalid.bodyAsText()
        assertTrue(text.contains("items[0].mealSlot") && text.contains("items[0].description") && text.contains("items[0].allergenTags"), text)
        assertEquals(HttpStatusCode.UnprocessableEntity, OpsApi.get(client, admin, "$base?from=2030-01-01&to=2030-06-01").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, OpsApi.get(client, admin, base).status)
        val created = OpsApi.post(client, admin, base, body)
        val id = OpsApi.json(created).str("id")!!
        assertEquals(HttpStatusCode.NotFound, OpsApi.get(client, t.tokenOf(t.userB), "/api/v1/organizations/${t.orgB}/menu-days/$id").status)
        assertEquals(HttpStatusCode.Forbidden, OpsApi.put(client, t.tokenOf(t.userMulti), "$base/$id", """{"items":[]}""", 1).status)
    }
}
