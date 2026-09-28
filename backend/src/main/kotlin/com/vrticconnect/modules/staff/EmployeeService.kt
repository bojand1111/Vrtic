package com.vrticconnect.modules.staff

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Scopes
import com.vrticconnect.db.SqlArray
import com.vrticconnect.db.dateOrNull
import com.vrticconnect.db.instant
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.stringList
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.db.uuidOrNull
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.conflict
import com.vrticconnect.modules.locations.PatchBody
import com.vrticconnect.modules.locations.cleaned
import com.vrticconnect.modules.locations.fieldProblem
import com.vrticconnect.modules.locations.throwIfErrors
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.TenantPrincipal
import com.vrticconnect.modules.tenant.audit
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

/** A group the employee is assigned to today. */
@Serializable
data class EmployeeGroup(val groupId: String, val groupName: String, val assignmentRole: String)

/** docs/openapi.yaml `Employee` plus `primaryLocationName` and today's `groups`. */
@Serializable
data class EmployeeDto(
    val id: String,
    val organizationId: String,
    val membershipId: String,
    val userId: String,
    val role: String,
    val membershipStatus: String,
    val displayName: String,
    val email: String,
    val jobTitle: String?,
    val phone: String?,
    val primaryLocationId: String?,
    val primaryLocationName: String?,
    val startedAt: String?,
    val endedAt: String?,
    val permissions: List<String>,
    val groups: List<EmployeeGroup>,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class EmployeePage(val items: List<EmployeeDto>, val nextCursor: String? = null)

/** Not in the contract: creates the staff profile for an ACTIVE staff membership that has none yet. */
@Serializable
data class EmployeeCreate(
    val membershipId: String? = null,
    val displayName: String? = null,
    val jobTitle: String? = null,
    val phone: String? = null,
    val primaryLocationId: String? = null,
    val startedAt: String? = null,
)

data class EmployeeFilter(val role: String?, val membershipStatus: String?, val locationId: UUID?, val limit: Int)

/**
 * Staff profiles (app.employees). List and changes need MEMBER_MANAGE (OWNER/ADMIN); every member
 * may read their OWN profile. Somebody else's profile without MEMBER_MANAGE answers 404.
 */
class EmployeeService(private val api: TenantApi) {

    suspend fun list(p: TenantPrincipal, f: EmployeeFilter): EmployeePage {
        Authorize.require(p, Permission.MEMBER_MANAGE)
        return api.tx(p) { c ->
            val today = Scopes.today(c)
            val rows = c.queryList(
                "$SELECT WHERE e.organization_id = app.current_organization_id() AND m.status = ? AND (?::text IS NULL OR m.role = ?::text) AND (?::uuid IS NULL OR e.primary_location_id = ?::uuid) " +
                    "ORDER BY lower(e.display_name), e.id LIMIT ?",
                f.membershipStatus ?: "ACTIVE", f.role, f.role, f.locationId, f.locationId, f.limit,
            ) { Row(it) }
            EmployeePage(withGroups(c, rows, today))
        }
    }

    suspend fun get(p: TenantPrincipal, id: UUID): EmployeeDto = api.tx(p) { c ->
        val dto = read(c, id) ?: throw ProblemException.notFound()
        if (!Authorize.has(p, Permission.MEMBER_MANAGE) && dto.membershipId != p.membership.membershipId.toString()) throw ProblemException.notFound()
        dto
    }

    suspend fun create(p: TenantPrincipal, body: EmployeeCreate, requestId: String?): EmployeeDto {
        Authorize.require(p, Permission.MEMBER_MANAGE)
        val errors = mutableListOf<FieldError>()
        val membershipId = parseUuid(errors, body.membershipId, "membershipId", required = true)
        val locationId = parseUuid(errors, body.primaryLocationId, "primaryLocationId", required = false)
        val started = parseDate(errors, body.startedAt, "startedAt")
        maxLen(errors, body.displayName, "displayName", 200); maxLen(errors, body.jobTitle, "jobTitle", 100); maxLen(errors, body.phone, "phone", 32)
        throwIfErrors(errors)
        return api.tx(p) { c ->
            val member = c.queryOne(
                "SELECT m.role, m.status, u.given_name, u.family_name, (SELECT e.id FROM app.employees e WHERE e.membership_id = m.id) AS employee_id " +
                    "FROM app.organization_memberships m JOIN app.users u ON u.id = m.user_id WHERE m.id = ? AND m.organization_id = app.current_organization_id()",
                membershipId,
            ) { rs -> listOf(rs.getString("role"), rs.getString("status"), "${rs.getString("given_name")} ${rs.getString("family_name")}".trim(), rs.uuidOrNull("employee_id")) }
                ?: throw fieldProblem("membershipId", "NOT_FOUND", "membership does not exist")
            if (member[0] == "PARENT" || member[1] != "ACTIVE") throwIfErrors(listOf(FieldError("membershipId", "NOT_STAFF", "ACTIVE OWNER/ADMIN/TEACHER membership required")))
            if (member[3] != null) throw conflict("EMPLOYEE_EXISTS")
            if (locationId != null) requireLocation(c, locationId)
            val id = UUID.randomUUID()
            c.update(
                "INSERT INTO app.employees (id, organization_id, membership_id, display_name, job_title, phone, primary_location_id, started_at) VALUES (?, ?, ?, ?, ?, ?, ?::uuid, ?::date)",
                id, p.membership.organizationId, membershipId, body.displayName.cleaned() ?: member[2] as String,
                body.jobTitle.cleaned(), body.phone.cleaned(), locationId, started ?: Scopes.today(c),
            )
            p.audit(c, "EMPLOYEE_CREATED", "EMPLOYEE", id, requestId, mapOf("membershipId" to membershipId.toString()))
            read(c, id)!!
        }
    }

    suspend fun update(p: TenantPrincipal, id: UUID, body: PatchBody, requestId: String?): EmployeeDto {
        Authorize.require(p, Permission.MEMBER_MANAGE)
        val errors = body.errors
        if (body.isEmpty()) errors += FieldError("body", "EMPTY", "at least one field")
        val displayName = body.string("displayName")
        if (body.has("displayName") && displayName.cleaned() == null) errors += FieldError("displayName", "REQUIRED", "displayName is required")
        val jobTitle = body.string("jobTitle")
        val phone = body.string("phone")
        maxLen(errors, displayName, "displayName", 200); maxLen(errors, jobTitle, "jobTitle", 100); maxLen(errors, phone, "phone", 32)
        val locationId = parseUuid(errors, body.string("primaryLocationId"), "primaryLocationId", required = false)
        val started = parseDate(errors, body.string("startedAt"), "startedAt")
        val ended = parseDate(errors, body.string("endedAt"), "endedAt")
        throwIfErrors(errors)
        return api.tx(p) { c ->
            val current = read(c, id) ?: throw ProblemException.notFound()
            val newStarted = if (body.has("startedAt")) started else current.startedAt?.let(LocalDate::parse)
            val newEnded = if (body.has("endedAt")) ended else current.endedAt?.let(LocalDate::parse)
            if (newStarted != null && newEnded != null && newEnded.isBefore(newStarted)) {
                throwIfErrors(listOf(FieldError("endedAt", "MUST_BE_AFTER_START", "endedAt must be >= startedAt")))
            }
            if (locationId != null) requireLocation(c, locationId)
            val sets = mutableListOf<String>()
            val params = mutableListOf<Any?>()
            if (body.has("displayName")) { sets += "display_name = ?"; params += displayName!!.trim() }
            if (body.has("jobTitle")) { sets += "job_title = ?"; params += jobTitle.cleaned() }
            if (body.has("phone")) { sets += "phone = ?"; params += phone.cleaned() }
            if (body.has("primaryLocationId")) { sets += "primary_location_id = ?::uuid"; params += locationId }
            if (body.has("startedAt")) { sets += "started_at = ?::date"; params += started }
            if (body.has("endedAt")) { sets += "ended_at = ?::date"; params += ended }
            c.update("UPDATE app.employees SET ${sets.joinToString()} WHERE id = ?", *(params + id).toTypedArray())
            p.audit(c, "EMPLOYEE_UPDATED", "EMPLOYEE", id, requestId)
            read(c, id)!!
        }
    }

    private fun read(c: Connection, id: UUID): EmployeeDto? {
        val row = c.queryOne("$SELECT WHERE e.organization_id = app.current_organization_id() AND e.id = ?", id) { Row(it) } ?: return null
        return withGroups(c, listOf(row), Scopes.today(c)).single()
    }

    private fun withGroups(c: Connection, rows: List<Row>, today: LocalDate): List<EmployeeDto> {
        if (rows.isEmpty()) return emptyList()
        val groups = c.queryList(
            "SELECT a.employee_id, g.id AS group_id, g.name, a.assignment_role FROM app.group_teacher_assignments a JOIN app.groups g ON g.id = a.group_id " +
                "WHERE a.employee_id = ANY(?) AND a.revoked_at IS NULL AND g.deleted_at IS NULL AND a.valid_from <= ? AND (a.valid_to IS NULL OR a.valid_to >= ?) ORDER BY lower(g.name)",
            SqlArray("uuid", rows.map { it.id }), today, today,
        ) { rs -> rs.uuid("employee_id") to EmployeeGroup(rs.uuid("group_id").toString(), rs.getString("name"), rs.getString("assignment_role")) }
            .groupBy({ it.first }, { it.second })
        return rows.map { it.toDto(groups[it.id].orEmpty()) }
    }

    private fun requireLocation(c: Connection, id: UUID) {
        val ok = c.queryOne("SELECT 1 AS x FROM app.locations WHERE id = ? AND deleted_at IS NULL", id) { true } ?: false
        if (!ok) throwIfErrors(listOf(FieldError("primaryLocationId", "NOT_FOUND", "location does not exist")))
    }

    private class Row(rs: ResultSet) {
        val id: UUID = rs.uuid("id")
        private val dto = EmployeeDto(
            id = id.toString(),
            organizationId = rs.uuid("organization_id").toString(),
            membershipId = rs.uuid("membership_id").toString(),
            userId = rs.uuid("user_id").toString(),
            role = rs.getString("role"),
            membershipStatus = rs.getString("membership_status"),
            displayName = rs.getString("display_name"),
            email = rs.getString("email"),
            jobTitle = rs.getString("job_title"),
            phone = rs.getString("phone"),
            primaryLocationId = rs.uuidOrNull("primary_location_id")?.toString(),
            primaryLocationName = rs.getString("location_name"),
            startedAt = rs.dateOrNull("started_at")?.toString(),
            endedAt = rs.dateOrNull("ended_at")?.toString(),
            permissions = rs.stringList("permissions"),
            groups = emptyList(),
            createdAt = rs.instant("created_at").toString(),
            updatedAt = rs.instant("updated_at").toString(),
        )

        fun toDto(groups: List<EmployeeGroup>) = dto.copy(groups = groups)
    }

    private companion object {
        const val SELECT =
            "SELECT e.*, m.user_id, m.role, m.status AS membership_status, u.email, l.name AS location_name, " +
                "ARRAY(SELECT mp.permission FROM app.membership_permissions mp WHERE mp.membership_id = m.id AND mp.revoked_at IS NULL ORDER BY mp.permission) AS permissions " +
                "FROM app.employees e JOIN app.organization_memberships m ON m.id = e.membership_id JOIN app.users u ON u.id = m.user_id " +
                "LEFT JOIN app.locations l ON l.id = e.primary_location_id"
    }
}

internal fun parseUuid(errors: MutableList<FieldError>, value: String?, field: String, required: Boolean): UUID? {
    if (value.isNullOrBlank()) {
        if (required) errors += FieldError(field, "REQUIRED", "$field is required")
        return null
    }
    return runCatching { UUID.fromString(value) }.getOrElse { errors += FieldError(field, "INVALID_FORMAT", "UUID"); null }
}

internal fun parseDate(errors: MutableList<FieldError>, value: String?, field: String): LocalDate? {
    if (value.isNullOrBlank()) return null
    return runCatching { LocalDate.parse(value) }.getOrElse { errors += FieldError(field, "INVALID_FORMAT", "YYYY-MM-DD"); null }
}

internal fun maxLen(errors: MutableList<FieldError>, value: String?, field: String, max: Int) {
    if (value != null && value.trim().length > max) errors += FieldError(field, "TOO_LONG", "max $max characters")
}
