package com.vrticconnect.modules.groups

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Role
import com.vrticconnect.db.dateOrNull
import com.vrticconnect.db.date
import com.vrticconnect.db.instant
import com.vrticconnect.db.instantOrNull
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.conflict
import com.vrticconnect.modules.locations.throwIfErrors
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.TenantPrincipal
import com.vrticconnect.modules.tenant.audit
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

/** docs/openapi.yaml `GroupTeacherAssignment` plus display names for the admin screens. */
@Serializable
data class AssignmentDto(
    val id: String,
    val organizationId: String,
    val groupId: String,
    val groupName: String,
    val employeeId: String,
    val employeeDisplayName: String,
    val assignmentRole: String,
    val validFrom: String,
    val validTo: String?,
    val revokedAt: String?,
    val createdAt: String,
)

@Serializable
data class AssignmentPage(val items: List<AssignmentDto>, val nextCursor: String? = null)

@Serializable
data class AssignmentCreate(
    val groupId: String? = null,
    val employeeId: String? = null,
    val assignmentRole: String? = null,
    val validFrom: String? = null,
    val validTo: String? = null,
)

data class AssignmentFilter(val groupId: UUID?, val employeeId: UUID?, val activeOn: LocalDate?, val includeRevoked: Boolean, val limit: Int)

/**
 * Teacher to group assignments (time-boxed). Managers with TEACHER_ASSIGN see and change all;
 * a TEACHER sees only their own assignments (read-only); PARENT has no access (403).
 * Overlaps for the same employee and group are refused (409 ASSIGNMENT_OVERLAP, backed by the
 * exclusion constraint).
 */
class AssignmentService(private val api: TenantApi) {

    suspend fun list(p: TenantPrincipal, f: AssignmentFilter): AssignmentPage {
        val ownOnly = when {
            Authorize.has(p, Permission.TEACHER_ASSIGN) -> false
            p.membership.role == Role.TEACHER -> true
            else -> throw Authorize.forbidden()
        }
        return api.tx(p) { c ->
            val rows = c.queryList(
                "$SELECT WHERE (?::uuid IS NULL OR a.group_id = ?::uuid) AND (?::uuid IS NULL OR a.employee_id = ?::uuid) " +
                    "AND (?::date IS NULL OR (a.valid_from <= ?::date AND (a.valid_to IS NULL OR a.valid_to >= ?::date))) " +
                    "AND (? OR a.revoked_at IS NULL) AND (NOT ? OR e.membership_id = ?) AND g.deleted_at IS NULL " +
                    "ORDER BY a.valid_from DESC, a.id LIMIT ?",
                f.groupId, f.groupId, f.employeeId, f.employeeId, f.activeOn, f.activeOn, f.activeOn,
                f.includeRevoked, ownOnly, p.membership.membershipId, f.limit,
            ) { map(it) }
            AssignmentPage(rows)
        }
    }

    suspend fun create(p: TenantPrincipal, body: AssignmentCreate, requestId: String?): AssignmentDto {
        Authorize.require(p, Permission.TEACHER_ASSIGN)
        val errors = mutableListOf<FieldError>()
        val groupId = uuid(errors, body.groupId, "groupId")
        val employeeId = uuid(errors, body.employeeId, "employeeId")
        val role = body.assignmentRole ?: "LEAD"
        if (role !in ROLES) errors += FieldError("assignmentRole", "INVALID_VALUE", ROLES.joinToString("|"))
        val from = date(errors, body.validFrom, "validFrom", required = true)
        val to = date(errors, body.validTo, "validTo", required = false)
        if (from != null && to != null && to.isBefore(from)) errors += FieldError("validTo", "MUST_BE_AFTER_FROM", "validTo must be >= validFrom")
        throwIfErrors(errors)
        return api.tx(p) { c ->
            val groupOk = c.queryOne("SELECT 1 AS x FROM app.groups WHERE id = ? AND deleted_at IS NULL", groupId) { true } ?: false
            val employeeOk = c.queryOne(
                "SELECT 1 AS x FROM app.employees e JOIN app.organization_memberships m ON m.id = e.membership_id " +
                    "WHERE e.id = ? AND m.status = 'ACTIVE' AND m.role IN ('OWNER','ADMIN','TEACHER')",
                employeeId,
            ) { true } ?: false
            throwIfErrors(
                listOfNotNull(
                    if (groupOk) null else FieldError("groupId", "NOT_FOUND", "group does not exist"),
                    if (employeeOk) null else FieldError("employeeId", "NOT_FOUND", "no active staff member with this id"),
                ),
            )
            val overlap = c.queryOne(
                "SELECT 1 AS x FROM app.group_teacher_assignments WHERE group_id = ? AND employee_id = ? AND revoked_at IS NULL " +
                    "AND daterange(valid_from, valid_to, '[]') && daterange(?::date, ?::date, '[]')",
                groupId, employeeId, from, to,
            ) { true } ?: false
            if (overlap) throw conflict("ASSIGNMENT_OVERLAP")
            val id = UUID.randomUUID()
            c.update(
                "INSERT INTO app.group_teacher_assignments (id, organization_id, group_id, employee_id, assignment_role, valid_from, valid_to, created_by) VALUES (?, ?, ?, ?, ?, ?, ?::date, ?)",
                id, p.membership.organizationId, groupId, employeeId, role, from, to, p.user.userId,
            )
            p.audit(c, "TEACHER_ASSIGNED", "GROUP_TEACHER_ASSIGNMENT", id, requestId)
            read(c, id)!!
        }
    }

    suspend fun revoke(p: TenantPrincipal, id: UUID, requestId: String?) {
        Authorize.require(p, Permission.TEACHER_ASSIGN)
        api.tx(p) { c ->
            val current = read(c, id) ?: throw ProblemException.notFound()
            if (current.revokedAt != null) throw conflict("ASSIGNMENT_ALREADY_REVOKED")
            c.update("UPDATE app.group_teacher_assignments SET revoked_at = now() WHERE id = ?", id)
            p.audit(c, "TEACHER_ASSIGNMENT_REVOKED", "GROUP_TEACHER_ASSIGNMENT", id, requestId)
        }
    }

    private fun read(c: Connection, id: UUID): AssignmentDto? = c.queryOne("$SELECT WHERE a.id = ?", id) { map(it) }

    private fun uuid(errors: MutableList<FieldError>, value: String?, field: String): UUID? {
        if (value.isNullOrBlank()) { errors += FieldError(field, "REQUIRED", "$field is required"); return null }
        return runCatching { UUID.fromString(value) }.getOrElse { errors += FieldError(field, "INVALID_FORMAT", "UUID"); null }
    }

    private fun date(errors: MutableList<FieldError>, value: String?, field: String, required: Boolean): LocalDate? {
        if (value.isNullOrBlank()) {
            if (required) errors += FieldError(field, "REQUIRED", "$field is required")
            return null
        }
        return runCatching { LocalDate.parse(value) }.getOrElse { errors += FieldError(field, "INVALID_FORMAT", "YYYY-MM-DD"); null }
    }

    private fun map(rs: ResultSet) = AssignmentDto(
        id = rs.uuid("id").toString(),
        organizationId = rs.uuid("organization_id").toString(),
        groupId = rs.uuid("group_id").toString(),
        groupName = rs.getString("group_name"),
        employeeId = rs.uuid("employee_id").toString(),
        employeeDisplayName = rs.getString("display_name"),
        assignmentRole = rs.getString("assignment_role"),
        validFrom = rs.date("valid_from").toString(),
        validTo = rs.dateOrNull("valid_to")?.toString(),
        revokedAt = rs.instantOrNull("revoked_at")?.toString(),
        createdAt = rs.instant("created_at").toString(),
    )

    private companion object {
        val ROLES = listOf("LEAD", "ASSISTANT", "SUBSTITUTE")
        const val SELECT =
            "SELECT a.*, g.name AS group_name, e.display_name FROM app.group_teacher_assignments a " +
                "JOIN app.groups g ON g.id = a.group_id JOIN app.employees e ON e.id = a.employee_id"
    }
}
