package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.testing.TestTenants
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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** E04 locations on native PostgreSQL (VRTIC_TEST_DB=1): CRUD, permissions, tenant isolation, 409 rules. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocationsIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()
    private lateinit var tenants: TestTenants

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1: integration test skipped")
        tenants = TestTenants(config).also { it.create() }
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
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
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
    fun `managers create and edit locations, others read or get 403, other tenants 404`() = testApplication {
        startApp()
        val tag = UUID.randomUUID().toString().take(6)
        val created = call("POST", "${base()}/locations", tenants.userOwner, """{"name":"Centar $tag","city":"Beograd","phone":"011"}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val id = idOf(created.bodyAsText())
        assertTrue(created.bodyAsText().contains("\"countryCode\":\"RS\"") && created.bodyAsText().contains("\"status\":\"ACTIVE\""))

        // duplicate name (case-insensitive) -> 409, missing name -> 422, bad country -> 422
        val dup = call("POST", "${base()}/locations", tenants.userA, """{"name":"centar $tag"}""")
        assertEquals(HttpStatusCode.Conflict, dup.status)
        assertTrue(dup.bodyAsText().contains("LOCATION_NAME_TAKEN"))
        val invalid = call("POST", "${base()}/locations", tenants.userOwner, """{"city":"x","countryCode":"srb"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        assertTrue(invalid.bodyAsText().contains("\"field\":\"name\"") && invalid.bodyAsText().contains("\"field\":\"countryCode\""), invalid.bodyAsText())

        // PARENT may not write (403) and, without children, sees no location
        assertEquals(HttpStatusCode.Forbidden, call("POST", "${base()}/locations", tenants.userMulti, """{"name":"X $tag"}""").status)
        val parentList = call("GET", "${base()}/locations", tenants.userMulti).bodyAsText()
        assertFalse(parentList.contains(id), parentList)
        assertEquals(HttpStatusCode.NotFound, call("GET", "${base()}/locations/$id", tenants.userMulti).status)

        // ADMIN edits; explicit null clears an optional field, absent fields stay
        val patched = call("PATCH", "${base()}/locations/$id", tenants.userA, """{"city":null,"addressLine":"Glavna 1"}""")
        assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
        val pb = patched.bodyAsText()
        assertTrue(pb.contains("\"addressLine\":\"Glavna 1\"") && !pb.contains("\"city\":\"Beograd\"") && pb.contains("\"phone\":\"011\""), pb)
        assertEquals(HttpStatusCode.UnprocessableEntity, call("PATCH", "${base()}/locations/$id", tenants.userA, """{"status":"GONE"}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, call("PATCH", "${base()}/locations/$id", tenants.userA, "{}").status)

        // manager list contains it; non-member (userB) gets 404; orgB path does not know the id
        assertTrue(call("GET", "${base()}/locations", tenants.userA).bodyAsText().contains(id))
        assertEquals(HttpStatusCode.NotFound, call("GET", "${base()}/locations", tenants.userB).status)
        assertEquals(HttpStatusCode.NotFound, call("GET", "${base(tenants.orgB)}/locations/$id", tenants.userB).status)
        assertEquals(HttpStatusCode.NotFound, call("GET", "${base()}/locations/not-a-uuid", tenants.userA).status)
    }

    @Test
    fun `a location with groups cannot be deactivated or deleted, an empty one can`() = testApplication {
        startApp()
        val tag = UUID.randomUUID().toString().take(6)
        val used = idOf(call("POST", "${base()}/locations", tenants.userOwner, """{"name":"Used $tag"}""").bodyAsText())
        val group = call("POST", "${base()}/groups", tenants.userOwner, """{"locationId":"$used","name":"Leptirići"}""")
        assertEquals(HttpStatusCode.Created, group.status, group.bodyAsText())
        val inactive = call("PATCH", "${base()}/locations/$used", tenants.userOwner, """{"status":"INACTIVE"}""")
        assertEquals(HttpStatusCode.Conflict, inactive.status)
        assertTrue(inactive.bodyAsText().contains("LOCATION_HAS_ACTIVE_GROUPS"))
        val del = call("DELETE", "${base()}/locations/$used", tenants.userOwner)
        assertEquals(HttpStatusCode.Conflict, del.status)
        assertTrue(del.bodyAsText().contains("LOCATION_IN_USE"))

        val empty = idOf(call("POST", "${base()}/locations", tenants.userOwner, """{"name":"Empty $tag"}""").bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, call("DELETE", "${base()}/locations/$empty", tenants.userMulti).status)
        assertEquals(HttpStatusCode.NoContent, call("DELETE", "${base()}/locations/$empty", tenants.userA).status)
        assertEquals(HttpStatusCode.NotFound, call("GET", "${base()}/locations/$empty", tenants.userA).status)
        // the name is free again after the soft delete
        assertEquals(HttpStatusCode.Created, call("POST", "${base()}/locations", tenants.userOwner, """{"name":"Empty $tag"}""").status)
    }
}
