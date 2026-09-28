package com.vrticconnect.authz

import com.vrticconnect.db.SqlArray
import com.vrticconnect.db.date
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.uuid
import com.vrticconnect.http.ProblemException
import com.vrticconnect.modules.tenant.TenantPrincipal
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * SQL implementation of the resource scope rules (docs/SECURITY.md 3.1, step 4 of the pipeline).
 * Must run inside the caller's DbContext.Tenant transaction; RLS already limits rows to the tenant.
 *   OWNER / ADMIN : every group and child of the organization
 *   TEACHER       : groups with an assignment (not revoked) valid on [date]; children enrolled
 *                   (PLANNED/ACTIVE) in those groups on [date]
 *   PARENT        : children with a CONFIRMED guardian link to the membership; groups of those children
 * A failed check answers 404 (never 403) so existence is not revealed.
 */
object Scopes {

    fun isManager(principal: TenantPrincipal): Boolean =
        principal.membership.role == Role.OWNER || principal.membership.role == Role.ADMIN

    /** Today in the organization's timezone. */
    fun today(c: Connection): LocalDate =
        c.queryOne("SELECT (now() AT TIME ZONE timezone)::date AS d FROM app.organizations WHERE id = app.current_organization_id()") { it.date("d") }
            ?: LocalDate.now()

    /** null = unrestricted (manager). */
    fun groupIds(c: Connection, principal: TenantPrincipal, date: LocalDate = today(c)): Set<UUID>? = when (principal.membership.role) {
        Role.OWNER, Role.ADMIN -> null
        Role.TEACHER -> c.queryList(
            "SELECT DISTINCT a.group_id FROM app.group_teacher_assignments a JOIN app.employees e ON e.id = a.employee_id " +
                "WHERE e.membership_id = ? AND a.revoked_at IS NULL AND a.valid_from <= ? AND (a.valid_to IS NULL OR a.valid_to >= ?)",
            principal.membership.membershipId, date, date,
        ) { it.uuid("group_id") }.toSet()
        Role.PARENT -> c.queryList(
            "SELECT DISTINCT en.group_id FROM app.enrollments en JOIN app.guardians g ON g.child_id = en.child_id " +
                "WHERE g.membership_id = ? AND g.status = 'CONFIRMED' AND en.status IN ('PLANNED','ACTIVE') " +
                "AND en.valid_from <= ? AND (en.valid_to IS NULL OR en.valid_to >= ?)",
            principal.membership.membershipId, date, date,
        ) { it.uuid("group_id") }.toSet()
    }

    /** null = unrestricted (manager). */
    fun childIds(c: Connection, principal: TenantPrincipal, date: LocalDate = today(c)): Set<UUID>? = when (principal.membership.role) {
        Role.OWNER, Role.ADMIN -> null
        Role.TEACHER -> {
            val groups = groupIds(c, principal, date).orEmpty()
            if (groups.isEmpty()) emptySet() else c.queryList(
                "SELECT DISTINCT child_id FROM app.enrollments WHERE group_id = ANY(?) AND status IN ('PLANNED','ACTIVE') " +
                    "AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)",
                SqlArray("uuid", groups.toList()), date, date,
            ) { it.uuid("child_id") }.toSet()
        }
        Role.PARENT -> c.queryList(
            "SELECT child_id FROM app.guardians WHERE membership_id = ? AND status = 'CONFIRMED'",
            principal.membership.membershipId,
        ) { it.uuid("child_id") }.toSet()
    }

    fun canAccessGroup(c: Connection, principal: TenantPrincipal, groupId: UUID): Boolean =
        groupIds(c, principal)?.contains(groupId) ?: true

    fun canAccessChild(c: Connection, principal: TenantPrincipal, childId: UUID): Boolean =
        childIds(c, principal)?.contains(childId) ?: true

    fun requireGroup(c: Connection, principal: TenantPrincipal, groupId: UUID) {
        if (!canAccessGroup(c, principal, groupId)) throw ProblemException.notFound()
    }

    fun requireChild(c: Connection, principal: TenantPrincipal, childId: UUID) {
        if (!canAccessChild(c, principal, childId)) throw ProblemException.notFound()
    }

    /** Guardian link with a specific capability flag (can_report_absence, can_manage_schedule ...). */
    fun guardianCan(c: Connection, principal: TenantPrincipal, childId: UUID, flagColumn: String): Boolean {
        require(flagColumn in GUARDIAN_FLAGS) { "unknown guardian flag $flagColumn" }
        return c.queryOne(
            "SELECT 1 AS ok FROM app.guardians WHERE membership_id = ? AND child_id = ? AND status = 'CONFIRMED' AND $flagColumn",
            principal.membership.membershipId, childId,
        ) { true } ?: false
    }

    private val GUARDIAN_FLAGS = setOf("can_manage_schedule", "can_report_absence", "can_give_consent", "can_view_health")
}
