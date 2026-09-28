package com.vrticconnect

import com.vrticconnect.db.DbContext
import com.vrticconnect.testing.TestTenants
import io.ktor.client.HttpClient
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Test helpers for the children / absences / schedules modules: bearer requests, JSON access and
 * fixture rows (locations, groups, teacher assignments, children, guardian links) inserted through
 * the runtime role in the fixture organization's tenant context.
 */
class ChildrenTestSupport(private val tenants: TestTenants) {
    val today: LocalDate = LocalDate.now(ZoneId.of("Europe/Belgrade"))

    fun base(org: UUID) = "/api/v1/organizations/$org"

    suspend fun HttpClient.getAs(user: UUID, path: String): HttpResponse =
        get(path) { header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(user)}") }

    suspend fun HttpClient.postAs(user: UUID, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse =
        post(path) {
            header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(user)}")
            headers.forEach { (k, v) -> header(k, v) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }

    suspend fun HttpClient.patchAs(user: UUID, path: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        patch(path) {
            header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(user)}")
            headers.forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun HttpClient.putAs(user: UUID, path: String, body: String): HttpResponse =
        put(path) {
            header(HttpHeaders.Authorization, "Bearer ${tenants.tokenOf(user)}")
            contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    fun JsonElement.str(field: String): String? = jsonObject[field]?.let { if (it is JsonObject || it is JsonArray) null else it.jsonPrimitive.contentOrNullSafe() }
    fun JsonElement.arr(field: String): JsonArray = jsonObject.getValue(field).jsonArray

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content

    fun location(org: UUID, name: String = "Loc"): UUID = tenants.runtimeDb.transactionBlocking(DbContext.Tenant(org, tenants.platformAdmin)) { c ->
        c.prepareStatement("INSERT INTO app.locations (organization_id, name) VALUES (?, ?) RETURNING id").use { st ->
            st.setObject(1, org); st.setString(2, name)
            st.executeQuery().use { rs -> rs.next(); rs.getObject("id", UUID::class.java) }
        }
    }

    fun group(org: UUID, location: UUID, name: String, capacity: Int? = null): UUID = tenants.runtimeDb.transactionBlocking(DbContext.Tenant(org, tenants.platformAdmin)) { c ->
        c.prepareStatement("INSERT INTO app.groups (organization_id, location_id, name, capacity) VALUES (?, ?, ?, ?) RETURNING id").use { st ->
            st.setObject(1, org); st.setObject(2, location); st.setString(3, name); st.setObject(4, capacity)
            st.executeQuery().use { rs -> rs.next(); rs.getObject("id", UUID::class.java) }
        }
    }

    fun assignTeacher(org: UUID, membership: UUID, group: UUID) = tenants.runtimeDb.transactionBlocking(DbContext.Tenant(org, tenants.platformAdmin)) { c ->
        val employee = c.prepareStatement("INSERT INTO app.employees (organization_id, membership_id, display_name) VALUES (?, ?, 'Teacher') RETURNING id").use { st ->
            st.setObject(1, org); st.setObject(2, membership)
            st.executeQuery().use { rs -> rs.next(); rs.getObject("id", UUID::class.java) }
        }
        c.prepareStatement("INSERT INTO app.group_teacher_assignments (organization_id, group_id, employee_id, valid_from) VALUES (?, ?, ?, ?)").use { st ->
            st.setObject(1, org); st.setObject(2, group); st.setObject(3, employee); st.setObject(4, today.minusDays(30)); st.executeUpdate()
        }
    }

    fun child(org: UUID, given: String, family: String, group: UUID?): UUID = tenants.runtimeDb.transactionBlocking(DbContext.Tenant(org, tenants.platformAdmin)) { c ->
        val id = c.prepareStatement("INSERT INTO app.children (organization_id, given_name, family_name, date_of_birth) VALUES (?, ?, ?, DATE '2022-03-04') RETURNING id").use { st ->
            st.setObject(1, org); st.setString(2, given); st.setString(3, family)
            st.executeQuery().use { rs -> rs.next(); rs.getObject("id", UUID::class.java) }
        }
        if (group != null) {
            c.prepareStatement("INSERT INTO app.enrollments (organization_id, child_id, group_id, valid_from) VALUES (?, ?, ?, ?)").use { st ->
                st.setObject(1, org); st.setObject(2, id); st.setObject(3, group); st.setObject(4, today.minusDays(60)); st.executeUpdate()
            }
        }
        id
    }

    fun pendingGuardian(org: UUID, child: UUID, membership: UUID): UUID = tenants.runtimeDb.transactionBlocking(DbContext.Tenant(org, tenants.platformAdmin)) { c ->
        c.prepareStatement("INSERT INTO app.guardians (organization_id, child_id, membership_id, relationship) VALUES (?, ?, ?, 'FATHER') RETURNING id").use { st ->
            st.setObject(1, org); st.setObject(2, child); st.setObject(3, membership)
            st.executeQuery().use { rs -> rs.next(); rs.getObject("id", UUID::class.java) }
        }
    }

    fun ownerMembership(org: UUID, user: UUID): UUID = tenants.runtimeDb.transactionBlocking(DbContext.Tenant(org, tenants.platformAdmin)) { c ->
        c.prepareStatement("SELECT id FROM app.organization_memberships WHERE organization_id = ? AND user_id = ?").use { st ->
            st.setObject(1, org); st.setObject(2, user)
            st.executeQuery().use { rs -> rs.next(); rs.getObject("id", UUID::class.java) }
        }
    }

    fun parentMembership(org: UUID, user: UUID): UUID = tenants.runtimeDb.transactionBlocking(DbContext.Tenant(org, tenants.platformAdmin)) { c ->
        c.prepareStatement("INSERT INTO app.organization_memberships (organization_id, user_id, role, status, accepted_at) VALUES (?, ?, 'PARENT', 'ACTIVE', now()) RETURNING id").use { st ->
            st.setObject(1, org); st.setObject(2, user)
            st.executeQuery().use { rs -> rs.next(); rs.getObject("id", UUID::class.java) }
        }
    }

    /** Guardian link created directly (CONFIRMED by the platform fixture user). */
    fun confirmedGuardian(org: UUID, child: UUID, membership: UUID, canReportAbsence: Boolean = true, canManageSchedule: Boolean = true): UUID =
        tenants.runtimeDb.transactionBlocking(DbContext.Tenant(org, tenants.platformAdmin)) { c ->
            c.prepareStatement(
                "INSERT INTO app.guardians (organization_id, child_id, membership_id, relationship, status, confirmed_by, confirmed_at, can_report_absence, can_manage_schedule) " +
                    "VALUES (?, ?, ?, 'MOTHER', 'CONFIRMED', ?, now(), ?, ?) RETURNING id",
            ).use { st ->
                st.setObject(1, org); st.setObject(2, child); st.setObject(3, membership); st.setObject(4, tenants.platformAdmin)
                st.setBoolean(5, canReportAbsence); st.setBoolean(6, canManageSchedule)
                st.executeQuery().use { rs -> rs.next(); rs.getObject("id", UUID::class.java) }
            }
        }

    fun setGuardianFlag(org: UUID, guardian: UUID, column: String, value: Boolean) = tenants.runtimeDb.transactionBlocking(DbContext.Tenant(org, tenants.platformAdmin)) { c ->
        require(column in setOf("can_report_absence", "can_manage_schedule"))
        c.prepareStatement("UPDATE app.guardians SET $column = ? WHERE id = ?").use { st -> st.setBoolean(1, value); st.setObject(2, guardian); st.executeUpdate() }
    }
}
