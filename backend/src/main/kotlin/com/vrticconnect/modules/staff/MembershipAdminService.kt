package com.vrticconnect.modules.staff

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Scopes
import com.vrticconnect.db.instant
import com.vrticconnect.db.instantOrNull
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.stringList
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.db.uuidOrNull
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.conflict
import com.vrticconnect.modules.auth.RecentAuthentication
import com.vrticconnect.modules.locations.throwIfErrors
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.TenantPrincipal
import com.vrticconnect.modules.tenant.audit
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** docs/openapi.yaml `Membership` plus `employeeId` (staff profile, when one exists). */
@Serializable
data class MembershipDto(
    val id: String,
    val organizationId: String,
    val userId: String,
    val givenName: String,
    val familyName: String,
    val email: String,
    val role: String,
    val status: String,
    val permissions: List<String>,
    val acceptedAt: String?,
    val suspendedAt: String?,
    val revokedAt: String?,
    val childrenCount: Int,
    val employeeId: String?,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class MembershipPage(val items: List<MembershipDto>, val nextCursor: String? = null)

/** docs/openapi.yaml `RevokeRequest`. */
@Serializable
data class RevokeRequest(val reason: String? = null)

data class MembershipFilter(val role: String?, val status: String?, val search: String?, val limit: Int)

/**
 * Organization memberships (staff and parents). Read: MEMBER_MANAGE or GUARDIAN_MANAGE.
 * Revoke: MEMBER_REVOKE plus a recent authentication (x-requires-reauthentication); nobody may
 * revoke an OWNER or their own membership (409). Revoking closes teacher assignments, revokes
 * guardian links and ends the staff profile.
 */
class MembershipAdminService(private val api: TenantApi) {

    suspend fun list(p: TenantPrincipal, f: MembershipFilter): MembershipPage {
        Authorize.requireAny(p, Permission.MEMBER_MANAGE, Permission.GUARDIAN_MANAGE)
        return api.tx(p) { c ->
            val prefix = f.search?.lowercase()?.let { "$it%" }
            MembershipPage(
                c.queryList(
                    "$SELECT WHERE m.organization_id = app.current_organization_id() AND m.status = ? AND (?::text IS NULL OR m.role = ?::text) " +
                        "AND (?::text IS NULL OR lower(u.given_name) LIKE ?::text OR lower(u.family_name) LIKE ?::text) " +
                        "ORDER BY lower(u.family_name), lower(u.given_name), m.id LIMIT ?",
                    f.status ?: "ACTIVE", f.role, f.role, prefix, prefix, prefix, f.limit,
                ) { map(it) },
            )
        }
    }

    suspend fun revoke(p: TenantPrincipal, id: UUID, body: RevokeRequest, requestId: String?): MembershipDto {
        Authorize.require(p, Permission.MEMBER_REVOKE)
        val reason = body.reason?.trim()
        if (reason == null || reason.length !in 3..500) throwIfErrors(listOf(FieldError("reason", "INVALID_LENGTH", "3..500 characters")))
        return api.tx(p) { c ->
            val target = read(c, id) ?: throw ProblemException.notFound()
            if (target.role == "OWNER") throw conflict("OWNER_NOT_REVOCABLE")
            if (target.id == p.membership.membershipId.toString()) throw conflict("CANNOT_REVOKE_SELF")
            if (target.status == "REVOKED") throw conflict("ALREADY_REVOKED")
            RecentAuthentication.require(c, p.user)
            val today = Scopes.today(c)
            c.update(
                "UPDATE app.organization_memberships SET status = 'REVOKED', revoked_at = now(), revoke_reason = ? WHERE id = ?",
                reason, id,
            )
            // Current and future teacher assignments end now; past ones stay as history.
            c.update(
                "UPDATE app.group_teacher_assignments SET revoked_at = now() WHERE revoked_at IS NULL AND (valid_to IS NULL OR valid_to >= ?) " +
                    "AND employee_id IN (SELECT e.id FROM app.employees e WHERE e.membership_id = ?)",
                today, id,
            )
            c.update(
                "UPDATE app.employees SET ended_at = GREATEST(COALESCE(started_at, ?::date), ?::date) WHERE membership_id = ? AND ended_at IS NULL",
                today, today, id,
            )
            c.update(
                "UPDATE app.guardians SET status = 'REVOKED', revoked_at = now(), revoked_by = ?, revoke_reason = ? WHERE membership_id = ? AND status <> 'REVOKED'",
                p.user.userId, reason, id,
            )
            p.audit(c, "MEMBERSHIP_REVOKED", "MEMBERSHIP", id, requestId, mapOf("role" to target.role))
            read(c, id)!!
        }
    }

    private fun read(c: Connection, id: UUID): MembershipDto? = c.queryOne("$SELECT WHERE m.organization_id = app.current_organization_id() AND m.id = ?", id) { map(it) }

    private fun map(rs: ResultSet) = MembershipDto(
        id = rs.uuid("id").toString(),
        organizationId = rs.uuid("organization_id").toString(),
        userId = rs.uuid("user_id").toString(),
        givenName = rs.getString("given_name"),
        familyName = rs.getString("family_name"),
        email = rs.getString("email"),
        role = rs.getString("role"),
        status = rs.getString("status"),
        permissions = rs.stringList("permissions"),
        acceptedAt = rs.instantOrNull("accepted_at")?.toString(),
        suspendedAt = rs.instantOrNull("suspended_at")?.toString(),
        revokedAt = rs.instantOrNull("revoked_at")?.toString(),
        childrenCount = rs.getInt("children_count"),
        employeeId = rs.uuidOrNull("employee_id")?.toString(),
        createdAt = rs.instant("created_at").toString(),
        updatedAt = rs.instant("updated_at").toString(),
    )

    private companion object {
        const val SELECT =
            "SELECT m.*, u.given_name, u.family_name, u.email, " +
                "ARRAY(SELECT mp.permission FROM app.membership_permissions mp WHERE mp.membership_id = m.id AND mp.revoked_at IS NULL ORDER BY mp.permission) AS permissions, " +
                "(SELECT count(*)::int FROM app.guardians g WHERE g.membership_id = m.id AND g.status <> 'REVOKED') AS children_count, " +
                "(SELECT e.id FROM app.employees e WHERE e.membership_id = m.id) AS employee_id " +
                "FROM app.organization_memberships m JOIN app.users u ON u.id = m.user_id"
    }
}
