package com.vrticconnect.testing

import com.vrticconnect.db.DbContext
import com.vrticconnect.db.update
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.util.UUID

/**
 * Business fixture data for the daily-operations tests (attendance, announcements, calendar, menus),
 * inserted on top of [TestTenants] through the runtime role in the tenant context of the platform admin.
 */
class DailyOpsFixture(private val t: TestTenants) {
    private fun <T> tx(org: UUID, block: (java.sql.Connection) -> T): T = t.runtimeDb.transactionBlocking(DbContext.Tenant(org, t.platformAdmin), block)

    fun location(org: UUID, name: String = "Lokacija"): UUID = UUID.randomUUID().also { id ->
        tx(org) { c -> c.update("INSERT INTO app.locations (id, organization_id, name) VALUES (?, ?, ?)", id, org, name) }
    }

    fun group(org: UUID, location: UUID, name: String): UUID = UUID.randomUUID().also { id ->
        tx(org) { c -> c.update("INSERT INTO app.groups (id, organization_id, location_id, name) VALUES (?, ?, ?, ?)", id, org, location, name) }
    }

    fun assignTeacher(org: UUID, membership: UUID, group: UUID, from: LocalDate = LocalDate.now().minusDays(30)) {
        tx(org) { c ->
            val employee = UUID.randomUUID()
            c.update(
                "INSERT INTO app.employees (id, organization_id, membership_id, display_name) VALUES (?, ?, ?, 'Teacher') ON CONFLICT (membership_id) DO NOTHING",
                employee, org, membership,
            )
            c.update(
                "INSERT INTO app.group_teacher_assignments (organization_id, group_id, employee_id, valid_from) " +
                    "SELECT ?, ?, id, ? FROM app.employees WHERE membership_id = ?",
                org, group, from, membership,
            )
        }
    }

    fun child(org: UUID, group: UUID, given: String, family: String = "Test"): UUID = UUID.randomUUID().also { id ->
        tx(org) { c ->
            c.update("INSERT INTO app.children (id, organization_id, given_name, family_name, date_of_birth) VALUES (?, ?, ?, ?, DATE '2021-05-01')", id, org, given, family)
            c.update(
                "INSERT INTO app.enrollments (organization_id, child_id, group_id, valid_from) VALUES (?, ?, ?, ?)",
                org, id, group, LocalDate.now().minusDays(60),
            )
        }
    }

    fun guardian(org: UUID, child: UUID, parentMembership: UUID, confirmedBy: UUID) {
        tx(org) { c ->
            c.update(
                "INSERT INTO app.guardians (organization_id, child_id, membership_id, relationship, status, confirmed_by, confirmed_at) VALUES (?, ?, ?, 'MOTHER', 'CONFIRMED', ?, now())",
                org, child, parentMembership, confirmedBy,
            )
        }
    }
}

/** Small HTTP helpers with bearer authentication (bearer requests skip the cookie CSRF check). */
object OpsApi {
    suspend fun get(client: HttpClient, token: String, url: String): HttpResponse =
        client.get(url) { header(HttpHeaders.Authorization, "Bearer $token") }

    suspend fun post(client: HttpClient, token: String, url: String, body: String? = null, ifMatch: Int? = null): HttpResponse =
        client.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            ifMatch?.let { header(HttpHeaders.IfMatch, "\"$it\"") }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }

    suspend fun patch(client: HttpClient, token: String, url: String, body: String, ifMatch: Int?): HttpResponse =
        client.patch(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            ifMatch?.let { header(HttpHeaders.IfMatch, "\"$it\"") }
            contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun put(client: HttpClient, token: String, url: String, body: String, ifMatch: Int?): HttpResponse =
        client.put(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            ifMatch?.let { header(HttpHeaders.IfMatch, "\"$it\"") }
            contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun delete(client: HttpClient, token: String, url: String, ifMatch: Int?): HttpResponse =
        client.delete(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            ifMatch?.let { header(HttpHeaders.IfMatch, "\"$it\"") }
        }

    suspend fun json(r: HttpResponse): JsonObject = Json.parseToJsonElement(r.bodyAsText()).jsonObject

    fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.content?.toIntOrNull()

    fun JsonObject.obj(name: String): JsonObject = this.getValue(name).jsonObject

    fun JsonObject.arr(name: String): JsonArray = this.getValue(name).jsonArray
}
