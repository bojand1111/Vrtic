package com.vrticconnect.modules.groups

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Scopes
import com.vrticconnect.db.SqlArray
import com.vrticconnect.db.dateOrNull
import com.vrticconnect.db.date
import com.vrticconnect.db.instant
import com.vrticconnect.db.intOrNull
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.conflict
import com.vrticconnect.modules.locations.PatchBody
import com.vrticconnect.modules.locations.cleaned
import com.vrticconnect.modules.locations.throwIfErrors
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.TenantPrincipal
import com.vrticconnect.modules.tenant.audit
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

/** Teacher currently assigned to a group (assignment valid today, not revoked). */
@Serializable
data class GroupTeacher(
    val assignmentId: String,
    val employeeId: String,
    val displayName: String,
    val assignmentRole: String,
    val validFrom: String,
    val validTo: String?,
)

/** docs/openapi.yaml `Group` plus `locationName` and `teachers` for the admin screens. */
@Serializable
data class GroupDto(
    val id: String,
    val organizationId: String,
    val locationId: String,
    val locationName: String,
    val name: String,
    val ageFromMonths: Int?,
    val ageToMonths: Int?,
    val capacity: Int?,
    val status: String,
    val activeChildrenCount: Int,
    val teachers: List<GroupTeacher>,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class GroupPage(val items: List<GroupDto>, val nextCursor: String? = null)

@Serializable
data class GroupCreate(
    val locationId: String? = null,
    val name: String? = null,
    val ageFromMonths: Int? = null,
    val ageToMonths: Int? = null,
    val capacity: Int? = null,
)

/**
 * E04 groups. Scope (docs/SECURITY.md 3.1): managers see every group, TEACHER only groups with a
 * valid assignment today, PARENT only groups of children with a CONFIRMED guardian link; anything
 * else is 404. Write: GROUP_MANAGE. Delete is a soft delete.
 */
class GroupService(private val api: TenantApi) {

    suspend fun list(p: TenantPrincipal, locationId: UUID?, status: String?, limit: Int): GroupPage = api.tx(p) { c ->
        val today = Scopes.today(c)
        val scope = Scopes.groupIds(c, p, today)
        val scopeArray = scope?.let { SqlArray("uuid", it.toList()) }
        val rows = c.queryList(
            "$SELECT WHERE g.deleted_at IS NULL AND g.status = ? AND (?::uuid IS NULL OR g.location_id = ?::uuid) " +
                "AND (?::uuid[] IS NULL OR g.id = ANY(?::uuid[])) ORDER BY lower(g.name), g.id LIMIT ?",
            today, today, status ?: "ACTIVE", locationId, locationId, scopeArray, scopeArray, limit,
        ) { Row(it) }
        GroupPage(withTeachers(c, rows, today))
    }

    suspend fun get(p: TenantPrincipal, id: UUID): GroupDto = api.tx(p) { c ->
        Scopes.requireGroup(c, p, id)
        read(c, id, Scopes.today(c)) ?: throw ProblemException.notFound()
    }

    suspend fun create(p: TenantPrincipal, body: GroupCreate, requestId: String?): GroupDto {
        Authorize.require(p, Permission.GROUP_MANAGE)
        val errors = mutableListOf<FieldError>()
        val name = body.name.cleaned()
        if (name == null) errors += FieldError("name", "REQUIRED", "name is required")
        else if (name.length > 120) errors += FieldError("name", "TOO_LONG", "max 120 characters")
        val locationId = when {
            body.locationId.isNullOrBlank() -> { errors += FieldError("locationId", "REQUIRED", "locationId is required"); null }
            else -> runCatching { UUID.fromString(body.locationId) }.getOrElse { errors += FieldError("locationId", "INVALID_FORMAT", "UUID"); null }
        }
        checkNumbers(errors, body.ageFromMonths, body.ageToMonths, body.capacity)
        throwIfErrors(errors)
        return api.tx(p) { c ->
            requireLocation(c, locationId!!)
            requireNameFree(c, locationId, name!!, null)
            val id = UUID.randomUUID()
            c.update(
                "INSERT INTO app.groups (id, organization_id, location_id, name, age_from_months, age_to_months, capacity) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, p.membership.organizationId, locationId, name, body.ageFromMonths, body.ageToMonths, body.capacity,
            )
            p.audit(c, "GROUP_CREATED", "GROUP", id, requestId)
            read(c, id, Scopes.today(c))!!
        }
    }

    suspend fun update(p: TenantPrincipal, id: UUID, body: PatchBody, requestId: String?): GroupDto {
        Authorize.require(p, Permission.GROUP_MANAGE)
        val errors = body.errors
        if (body.isEmpty()) errors += FieldError("body", "EMPTY", "at least one field")
        val name = body.string("name")
        if (body.has("name")) {
            if (name.cleaned() == null) errors += FieldError("name", "REQUIRED", "name is required")
            else if (name!!.trim().length > 120) errors += FieldError("name", "TOO_LONG", "max 120 characters")
        }
        val status = body.string("status")
        if (body.has("status") && status !in setOf("ACTIVE", "INACTIVE")) errors += FieldError("status", "INVALID_VALUE", "ACTIVE|INACTIVE")
        val ageFrom = body.int("ageFromMonths")
        val ageTo = body.int("ageToMonths")
        val capacity = body.int("capacity")
        checkNumbers(errors, ageFrom, ageTo, capacity)
        throwIfErrors(errors)
        return api.tx(p) { c ->
            val today = Scopes.today(c)
            val current = read(c, id, today) ?: throw ProblemException.notFound()
            val newFrom = if (body.has("ageFromMonths")) ageFrom else current.ageFromMonths
            val newTo = if (body.has("ageToMonths")) ageTo else current.ageToMonths
            if (newFrom != null && newTo != null && newFrom > newTo) throwIfErrors(listOf(FieldError("ageToMonths", "MUST_BE_AFTER_FROM", "ageToMonths must be >= ageFromMonths")))
            if (capacity != null && capacity < current.activeChildrenCount) throwIfErrors(listOf(FieldError("capacity", "CAPACITY_BELOW_ENROLLMENT", "current enrollment ${current.activeChildrenCount}")))
            if (name != null) requireNameFree(c, UUID.fromString(current.locationId), name.trim(), id)
            if (status == "INACTIVE" && current.status == "ACTIVE" && openEnrollments(c, id, today) > 0) throw conflict("GROUP_HAS_ENROLLMENTS")
            val sets = mutableListOf<String>()
            val params = mutableListOf<Any?>()
            if (body.has("name")) { sets += "name = ?"; params += name!!.trim() }
            if (body.has("ageFromMonths")) { sets += "age_from_months = ?::int"; params += ageFrom }
            if (body.has("ageToMonths")) { sets += "age_to_months = ?::int"; params += ageTo }
            if (body.has("capacity")) { sets += "capacity = ?::int"; params += capacity }
            if (body.has("status")) { sets += "status = ?"; params += status }
            c.update("UPDATE app.groups SET ${sets.joinToString()} WHERE id = ? AND deleted_at IS NULL", *(params + id).toTypedArray())
            p.audit(c, "GROUP_UPDATED", "GROUP", id, requestId)
            read(c, id, today)!!
        }
    }

    suspend fun delete(p: TenantPrincipal, id: UUID, requestId: String?) {
        Authorize.require(p, Permission.GROUP_MANAGE)
        api.tx(p) { c ->
            val today = Scopes.today(c)
            read(c, id, today) ?: throw ProblemException.notFound()
            if (openEnrollments(c, id, today) > 0) throw conflict("GROUP_HAS_ENROLLMENTS")
            c.update("UPDATE app.groups SET deleted_at = now(), status = 'INACTIVE' WHERE id = ?", id)
            // Teachers lose access immediately; the history of past assignments stays.
            c.update("UPDATE app.group_teacher_assignments SET revoked_at = now() WHERE group_id = ? AND revoked_at IS NULL", id)
            p.audit(c, "GROUP_DELETED", "GROUP", id, requestId)
        }
    }

    private fun openEnrollments(c: Connection, groupId: UUID, today: LocalDate): Int =
        c.queryOne(
            "SELECT count(*)::int AS n FROM app.enrollments WHERE group_id = ? AND status IN ('PLANNED','ACTIVE') AND (valid_to IS NULL OR valid_to >= ?)",
            groupId, today,
        ) { it.getInt("n") } ?: 0

    private fun requireLocation(c: Connection, locationId: UUID) {
        val ok = c.queryOne("SELECT 1 AS x FROM app.locations WHERE id = ? AND deleted_at IS NULL", locationId) { true } ?: false
        if (!ok) throwIfErrors(listOf(FieldError("locationId", "NOT_FOUND", "location does not exist")))
    }

    private fun requireNameFree(c: Connection, locationId: UUID, name: String, exceptId: UUID?) {
        val taken = c.queryOne(
            "SELECT 1 AS x FROM app.groups WHERE location_id = ? AND lower(name) = lower(?) AND deleted_at IS NULL AND (?::uuid IS NULL OR id <> ?::uuid)",
            locationId, name, exceptId, exceptId,
        ) { true } ?: false
        if (taken) throw conflict("GROUP_NAME_TAKEN")
    }

    private fun checkNumbers(errors: MutableList<FieldError>, from: Int?, to: Int?, capacity: Int?) {
        if (from != null && from !in 0..120) errors += FieldError("ageFromMonths", "INVALID_RANGE", "0..120")
        if (to != null && to !in 0..120) errors += FieldError("ageToMonths", "INVALID_RANGE", "0..120")
        if (from != null && to != null && from > to) errors += FieldError("ageToMonths", "MUST_BE_AFTER_FROM", "ageToMonths must be >= ageFromMonths")
        if (capacity != null && capacity !in 1..500) errors += FieldError("capacity", "INVALID_RANGE", "1..500")
    }

    private fun read(c: Connection, id: UUID, today: LocalDate): GroupDto? {
        val row = c.queryOne("$SELECT WHERE g.id = ? AND g.deleted_at IS NULL", today, today, id) { Row(it) } ?: return null
        return withTeachers(c, listOf(row), today).single()
    }

    /** Maps the rows (already materialised by the query helpers) and attaches today's teachers in one query. */
    private fun withTeachers(c: Connection, rows: List<Row>, today: LocalDate): List<GroupDto> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { UUID.fromString(it.id) }
        val teachers = c.queryList(
            "SELECT a.id, a.group_id, a.employee_id, a.assignment_role, a.valid_from, a.valid_to, e.display_name " +
                "FROM app.group_teacher_assignments a JOIN app.employees e ON e.id = a.employee_id " +
                "WHERE a.group_id = ANY(?) AND a.revoked_at IS NULL AND a.valid_from <= ? AND (a.valid_to IS NULL OR a.valid_to >= ?) " +
                "ORDER BY CASE a.assignment_role WHEN 'LEAD' THEN 0 WHEN 'ASSISTANT' THEN 1 ELSE 2 END, lower(e.display_name)",
            SqlArray("uuid", ids), today, today,
        ) { rs ->
            rs.uuid("group_id").toString() to GroupTeacher(
                assignmentId = rs.uuid("id").toString(), employeeId = rs.uuid("employee_id").toString(),
                displayName = rs.getString("display_name"), assignmentRole = rs.getString("assignment_role"),
                validFrom = rs.date("valid_from").toString(), validTo = rs.dateOrNull("valid_to")?.toString(),
            )
        }.groupBy({ it.first }, { it.second })
        return rows.map { it.toDto(teachers[it.id].orEmpty()) }
    }

    /** Row snapshot; ResultSet values are copied inside the query callback. */
    private class Row(rs: ResultSet) {
        val id: String = rs.uuid("id").toString()
        val organizationId: String = rs.uuid("organization_id").toString()
        val locationId: String = rs.uuid("location_id").toString()
        val locationName: String = rs.getString("location_name")
        val name: String = rs.getString("name")
        val ageFrom: Int? = rs.intOrNull("age_from_months")
        val ageTo: Int? = rs.intOrNull("age_to_months")
        val capacity: Int? = rs.intOrNull("capacity")
        val status: String = rs.getString("status")
        val count: Int = rs.getInt("active_children")
        val createdAt: String = rs.instant("created_at").toString()
        val updatedAt: String = rs.instant("updated_at").toString()

        fun toDto(teachers: List<GroupTeacher>) = GroupDto(
            id, organizationId, locationId, locationName, name, ageFrom, ageTo, capacity, status, count, teachers, createdAt, updatedAt,
        )
    }

    private companion object {
        /** Parameters: today, today (active children count window). */
        const val SELECT =
            "SELECT g.*, l.name AS location_name, " +
                "(SELECT count(*)::int FROM app.enrollments en WHERE en.group_id = g.id AND en.status IN ('PLANNED','ACTIVE') " +
                "AND en.valid_from <= ? AND (en.valid_to IS NULL OR en.valid_to >= ?)) AS active_children " +
                "FROM app.groups g JOIN app.locations l ON l.id = g.location_id"
    }
}
