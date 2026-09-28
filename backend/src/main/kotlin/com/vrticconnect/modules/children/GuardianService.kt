package com.vrticconnect.modules.children

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.db.instant
import com.vrticconnect.db.instantOrNull
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.conflict
import com.vrticconnect.http.validate
import com.vrticconnect.modules.tenant.TenantPrincipal
import com.vrticconnect.modules.tenant.audit
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * Guardian links (PARENT membership <-> child). Only staff with GUARDIAN_MANAGE create, confirm,
 * change or revoke links; parents can never link themselves (docs/SECURITY.md).
 */
object GuardianService {

    private const val SELECT =
        "SELECT gd.id, gd.organization_id, gd.child_id, gd.membership_id, m.user_id, u.given_name, u.family_name, gd.relationship, gd.status, " +
            "gd.is_primary, gd.can_manage_schedule, gd.can_report_absence, gd.can_give_consent, gd.can_view_health, gd.confirmed_at, gd.revoked_at, gd.created_at " +
            "FROM app.guardians gd JOIN app.organization_memberships m ON m.id = gd.membership_id JOIN app.users u ON u.id = m.user_id"

    fun forChild(c: Connection, childId: UUID): List<GuardianDto> =
        c.queryList("$SELECT WHERE gd.child_id = ? ORDER BY CASE gd.status WHEN 'CONFIRMED' THEN 0 WHEN 'PENDING' THEN 1 ELSE 2 END, gd.is_primary DESC, u.family_name, u.given_name", childId) { map(it) }

    /** Addition to the contract: link an existing ACTIVE PARENT membership; CONFIRMED because staff creates it. */
    fun link(c: Connection, principal: TenantPrincipal, childId: UUID, body: GuardianLinkRequest, requestId: String?): GuardianDto {
        Authorize.require(principal, Permission.GUARDIAN_MANAGE)
        ChildrenService.requireChildExists(c, childId)
        val membershipId = validate {
            oneOf(body.relationship, "relationship", GUARDIAN_RELATIONSHIPS)
            uuid(body.membershipId, "membershipId")
        }!!
        val membership = c.queryOne(
            "SELECT role, status FROM app.organization_memberships WHERE id = ? AND organization_id = ?",
            membershipId, principal.membership.organizationId,
        ) { it.getString("role") to it.getString("status") }
            ?: throw fieldProblem("membershipId", "NOT_FOUND", "unknown membership")
        if (membership.first != "PARENT") throw fieldProblem("membershipId", "NOT_PARENT", "membership must have the PARENT role")
        if (membership.second != "ACTIVE") throw fieldProblem("membershipId", "MEMBERSHIP_NOT_ACTIVE", "membership must be ACTIVE")
        val existing = c.queryOne(
            "SELECT id FROM app.guardians WHERE child_id = ? AND membership_id = ? AND status <> 'REVOKED'", childId, membershipId,
        ) { it.uuid("id") }
        if (existing != null) throw conflict("GUARDIAN_ALREADY_LINKED")
        if (parentRoleTaken(c, childId, body.relationship!!, null)) throw relationshipTaken(body.relationship)
        val id = c.queryOne(
            "INSERT INTO app.guardians (organization_id, child_id, membership_id, relationship, status, is_primary, can_manage_schedule, " +
                "can_report_absence, can_give_consent, can_view_health, confirmed_by, confirmed_at) " +
                "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?, ?, ?, now()) RETURNING id",
            principal.membership.organizationId, childId, membershipId, body.relationship, body.isPrimary, body.canManageSchedule,
            body.canReportAbsence, body.canGiveConsent, body.canViewHealth, principal.user.userId,
        ) { it.uuid("id") }!!
        principal.audit(c, "GUARDIAN_LINKED", "GUARDIAN", id, requestId, mapOf("membershipId" to membershipId.toString()))
        return byId(c, id)
    }

    fun confirm(c: Connection, principal: TenantPrincipal, guardianId: UUID, requestId: String?): GuardianDto {
        Authorize.require(principal, Permission.GUARDIAN_MANAGE)
        val (status, membershipStatus) = c.queryOne(
            "SELECT gd.status, m.status AS m_status FROM app.guardians gd JOIN app.organization_memberships m ON m.id = gd.membership_id " +
                "WHERE gd.id = ? FOR UPDATE OF gd",
            guardianId,
        ) { it.getString("status") to it.getString("m_status") } ?: throw ProblemException.notFound()
        if (status != "PENDING") throw conflict("GUARDIAN_NOT_PENDING")
        if (membershipStatus != "ACTIVE") throw conflict("MEMBERSHIP_NOT_ACTIVE")
        val (childId, relationship) = c.queryOne("SELECT child_id, relationship FROM app.guardians WHERE id = ?", guardianId) {
            it.uuid("child_id") to it.getString("relationship")
        }!!
        if (parentRoleTaken(c, childId, relationship, guardianId)) throw conflict("RELATIONSHIP_TAKEN")
        c.update("UPDATE app.guardians SET status = 'CONFIRMED', confirmed_by = ?, confirmed_at = now() WHERE id = ?", principal.user.userId, guardianId)
        principal.audit(c, "GUARDIAN_CONFIRMED", "GUARDIAN", guardianId, requestId)
        return byId(c, guardianId)
    }

    fun revoke(c: Connection, principal: TenantPrincipal, guardianId: UUID, body: RevokeRequest, requestId: String?): GuardianDto {
        Authorize.require(principal, Permission.GUARDIAN_MANAGE)
        val status = c.queryOne("SELECT status FROM app.guardians WHERE id = ? FOR UPDATE", guardianId) { it.getString("status") }
            ?: throw ProblemException.notFound()
        val reason = validate {
            text(body.reason, "reason", 500)
            require((body.reason?.trim()?.length ?: 0) >= 3, "reason", "TOO_SHORT", "min 3 characters")
            body.reason?.trim()
        }!!
        if (status == "REVOKED") throw conflict("GUARDIAN_ALREADY_REVOKED")
        c.update(
            "UPDATE app.guardians SET status = 'REVOKED', revoked_by = ?, revoked_at = now(), revoke_reason = ? WHERE id = ?",
            principal.user.userId, reason, guardianId,
        )
        principal.audit(c, "GUARDIAN_REVOKED", "GUARDIAN", guardianId, requestId)
        return byId(c, guardianId)
    }

    fun update(c: Connection, principal: TenantPrincipal, guardianId: UUID, patch: Patch, requestId: String?): GuardianDto {
        Authorize.require(principal, Permission.GUARDIAN_MANAGE)
        val status = c.queryOne("SELECT status FROM app.guardians WHERE id = ? FOR UPDATE", guardianId) { it.getString("status") }
            ?: throw ProblemException.notFound()
        val flags = mapOf(
            "isPrimary" to "is_primary", "canManageSchedule" to "can_manage_schedule", "canReportAbsence" to "can_report_absence",
            "canGiveConsent" to "can_give_consent", "canViewHealth" to "can_view_health",
        )
        patch.keys().filter { it != "relationship" && it !in flags }.forEach { patch.errors += FieldError(it, "UNKNOWN_FIELD", "not updatable") }
        if (patch.isEmpty) patch.errors += FieldError("body", "EMPTY", "at least one field is required")
        val sets = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        patch.string("relationship")?.let { p ->
            if (p.value !in GUARDIAN_RELATIONSHIPS) patch.errors += FieldError("relationship", "INVALID_VALUE", GUARDIAN_RELATIONSHIPS.sorted().joinToString("|"))
            else { sets += "relationship = ?"; params += p.value }
        }
        for ((field, column) in flags) {
            patch.boolean(field)?.let { sets += "$column = ?"; params += it }
        }
        patch.throwIfInvalid()
        if (status == "REVOKED") throw conflict("GUARDIAN_REVOKED")
        patch.string("relationship")?.value?.let { newRelationship ->
            val childId = c.queryOne("SELECT child_id FROM app.guardians WHERE id = ?", guardianId) { it.uuid("child_id") }!!
            if (parentRoleTaken(c, childId, newRelationship, guardianId)) throw relationshipTaken(newRelationship)
        }
        c.update("UPDATE app.guardians SET " + sets.joinToString(", ") + " WHERE id = ?", *(params + guardianId).toTypedArray())
        principal.audit(c, "GUARDIAN_UPDATED", "GUARDIAN", guardianId, requestId)
        return byId(c, guardianId)
    }

    /** Addition: every PARENT membership with its guardian links (Parents screen). */
    fun parents(c: Connection, principal: TenantPrincipal): ParentOverviewList {
        Authorize.require(principal, Permission.GUARDIAN_MANAGE)
        data class Row(val membershipId: UUID, val userId: UUID, val name: String, val email: String, val status: String)
        val members = c.queryList(
            "SELECT m.id, m.user_id, u.given_name, u.family_name, u.email, m.status FROM app.organization_memberships m " +
                "JOIN app.users u ON u.id = m.user_id WHERE m.organization_id = ? AND m.role = 'PARENT' ORDER BY u.family_name, u.given_name, m.id",
            principal.membership.organizationId,
        ) { rs ->
            Row(rs.uuid("id"), rs.uuid("user_id"), "${rs.getString("given_name")} ${rs.getString("family_name")}".trim(), rs.getString("email"), rs.getString("status"))
        }
        val links = c.queryList(
            "SELECT gd.id, gd.membership_id, gd.child_id, ch.given_name, ch.family_name, gd.relationship, gd.status, gd.is_primary " +
                "FROM app.guardians gd JOIN app.children ch ON ch.id = gd.child_id WHERE ch.deleted_at IS NULL " +
                "ORDER BY CASE gd.status WHEN 'CONFIRMED' THEN 0 WHEN 'PENDING' THEN 1 ELSE 2 END, ch.family_name, ch.given_name",
        ) { rs ->
            rs.uuid("membership_id") to ParentLink(
                guardianId = rs.uuid("id").toString(), childId = rs.uuid("child_id").toString(),
                childGivenName = rs.getString("given_name"), childFamilyName = rs.getString("family_name"),
                relationship = rs.getString("relationship"), status = rs.getString("status"), isPrimary = rs.getBoolean("is_primary"),
            )
        }.groupBy({ it.first }, { it.second })
        return ParentOverviewList(
            members.map { m -> ParentOverview(m.membershipId.toString(), m.userId.toString(), m.name, m.email, m.status, links[m.membershipId].orEmpty()) },
        )
    }

    /**
     * A child has at most one MOTHER and one FATHER among its non-revoked links (a single parent is fine;
     * grandparents, legal guardians and OTHER are not limited). Locks the child row so two parallel
     * requests cannot both pass the check.
     */
    private fun parentRoleTaken(c: Connection, childId: UUID, relationship: String, excludeGuardianId: UUID?): Boolean {
        if (relationship !in LIMITED_RELATIONSHIPS) return false
        c.queryOne("SELECT id FROM app.children WHERE id = ? FOR UPDATE", childId) { it.uuid("id") }
        return c.queryOne(
            "SELECT 1 AS taken FROM app.guardians WHERE child_id = ? AND relationship = ? AND status <> 'REVOKED' AND (?::uuid IS NULL OR id <> ?::uuid)",
            childId, relationship, excludeGuardianId, excludeGuardianId,
        ) { true } ?: false
    }

    private fun relationshipTaken(relationship: String?) =
        fieldProblem("relationship", "RELATIONSHIP_TAKEN", "the child already has a linked ${relationship?.lowercase()}")

    private val LIMITED_RELATIONSHIPS = setOf("MOTHER", "FATHER")

    private fun byId(c: Connection, id: UUID): GuardianDto = c.queryOne("$SELECT WHERE gd.id = ?", id) { map(it) } ?: throw ProblemException.notFound()

    private fun map(rs: ResultSet) = GuardianDto(
        id = rs.uuid("id").toString(), organizationId = rs.uuid("organization_id").toString(), childId = rs.uuid("child_id").toString(),
        membershipId = rs.uuid("membership_id").toString(), userId = rs.uuid("user_id").toString(),
        givenName = rs.getString("given_name"), familyName = rs.getString("family_name"), relationship = rs.getString("relationship"),
        status = rs.getString("status"), isPrimary = rs.getBoolean("is_primary"), canManageSchedule = rs.getBoolean("can_manage_schedule"),
        canReportAbsence = rs.getBoolean("can_report_absence"), canGiveConsent = rs.getBoolean("can_give_consent"),
        canViewHealth = rs.getBoolean("can_view_health"), confirmedAt = rs.instantOrNull("confirmed_at")?.toString(),
        revokedAt = rs.instantOrNull("revoked_at")?.toString(), createdAt = rs.instant("created_at").toString(),
    )
}
