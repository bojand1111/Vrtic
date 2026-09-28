package com.vrticconnect.modules.children

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Scopes
import com.vrticconnect.db.dateOrNull
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
import java.time.LocalDate
import java.util.UUID

/**
 * Authorised pickup list. Read: PICKUP_PERSON_READ within the child scope. Manage: PICKUP_PERSON_MANAGE,
 * managers for any child, a PARENT only for children with a CONFIRMED guardian link (Scopes.childIds).
 */
object PickupPersonService {
    private const val MAX_ACTIVE = 10

    private const val SELECT =
        "SELECT id, child_id, full_name, relationship, phone, note, valid_from, valid_to, added_by_membership_id, status, revoked_at, created_at, updated_at " +
            "FROM app.pickup_persons"

    fun forChild(c: Connection, childId: UUID, includeRevoked: Boolean): List<PickupPersonDto> =
        c.queryList(
            "$SELECT WHERE child_id = ?" + (if (includeRevoked) "" else " AND status = 'ACTIVE'") + " ORDER BY status, full_name, id",
            childId,
        ) { map(it) }

    /** Addition to the contract (`GET /children/{childId}/pickup-persons`): active and revoked entries. */
    fun list(c: Connection, principal: TenantPrincipal, childId: UUID): PickupPersonList {
        Authorize.require(principal, Permission.PICKUP_PERSON_READ)
        Scopes.requireChild(c, principal, childId)
        ChildrenService.requireChildExists(c, childId)
        return PickupPersonList(forChild(c, childId, includeRevoked = true))
    }

    fun create(c: Connection, principal: TenantPrincipal, childId: UUID, body: PickupPersonCreateRequest, requestId: String?): PickupPersonDto {
        Authorize.require(principal, Permission.PICKUP_PERSON_MANAGE)
        Scopes.requireChild(c, principal, childId)
        ChildrenService.requireChildExists(c, childId)
        var from: LocalDate? = null
        var to: LocalDate? = null
        validate {
            text(body.fullName, "fullName", 200)
            require((body.fullName?.trim()?.length ?: 2) >= 2, "fullName", "TOO_SHORT", "min 2 characters")
            text(body.relationship, "relationship", 60, required = false)
            text(body.phone, "phone", 32, required = false)
            text(body.note, "note", 500, required = false)
            from = date(body.validFrom, "validFrom", required = false)
            to = date(body.validTo, "validTo", required = false)
            val f = from; val t = to
            if (f != null && t != null) require(!t.isBefore(f), "validTo", "BEFORE_START", "validTo must be on or after validFrom")
        }
        val active = c.queryOne("SELECT count(*) AS n FROM app.pickup_persons WHERE child_id = ? AND status = 'ACTIVE'", childId) { it.getInt("n") } ?: 0
        if (active >= MAX_ACTIVE) throw conflict("PICKUP_LIMIT")
        val id = c.queryOne(
            "INSERT INTO app.pickup_persons (organization_id, child_id, full_name, relationship, phone, note, valid_from, valid_to, added_by_membership_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
            principal.membership.organizationId, childId, body.fullName!!.trim(), blankToNull(body.relationship), blankToNull(body.phone),
            blankToNull(body.note), from, to, principal.membership.membershipId,
        ) { it.uuid("id") }!!
        principal.audit(c, "PICKUP_PERSON_ADDED", "PICKUP_PERSON", id, requestId)
        return byId(c, id)
    }

    fun update(c: Connection, principal: TenantPrincipal, pickupPersonId: UUID, patch: Patch, requestId: String?): PickupPersonDto {
        Authorize.require(principal, Permission.PICKUP_PERSON_MANAGE)
        val current = locked(c, principal, pickupPersonId)
        val allowed = setOf("fullName", "relationship", "phone", "note", "validFrom", "validTo")
        patch.keys().filter { it !in allowed }.forEach { patch.errors += FieldError(it, "UNKNOWN_FIELD", "not updatable") }
        if (patch.isEmpty) patch.errors += FieldError("body", "EMPTY", "at least one field is required")
        val sets = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        patch.string("fullName")?.let { p ->
            val v = p.value?.trim()
            if (v == null || v.length < 2 || v.length > 200) patch.errors += FieldError("fullName", "INVALID_LENGTH", "2..200 characters")
            else { sets += "full_name = ?"; params += v }
        }
        for ((field, column, max) in listOf(Triple("relationship", "relationship", 60), Triple("phone", "phone", 32), Triple("note", "note", 500))) {
            patch.string(field)?.let { p ->
                val v = blankToNull(p.value)
                if (v != null && v.length > max) patch.errors += FieldError(field, "TOO_LONG", "max $max characters")
                else { sets += "$column = ?"; params += v }
            }
        }
        var from = current.validFrom?.let { LocalDate.parse(it) }
        var to = current.validTo?.let { LocalDate.parse(it) }
        for ((field, column) in listOf("validFrom" to "valid_from", "validTo" to "valid_to")) {
            patch.string(field)?.let { p ->
                val d = p.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                if (p.value != null && d == null) {
                    patch.errors += FieldError(field, "INVALID_FORMAT", "YYYY-MM-DD")
                } else {
                    sets += "$column = ?"; params += d
                    if (field == "validFrom") from = d else to = d
                }
            }
        }
        val f = from; val t = to
        if (f != null && t != null && t.isBefore(f)) patch.errors += FieldError("validTo", "BEFORE_START", "validTo must be on or after validFrom")
        patch.throwIfInvalid()
        if (current.status != "ACTIVE") throw conflict("PICKUP_PERSON_REVOKED")
        c.update("UPDATE app.pickup_persons SET " + sets.joinToString(", ") + " WHERE id = ?", *(params + pickupPersonId).toTypedArray())
        principal.audit(c, "PICKUP_PERSON_UPDATED", "PICKUP_PERSON", pickupPersonId, requestId)
        return byId(c, pickupPersonId)
    }

    fun revoke(c: Connection, principal: TenantPrincipal, pickupPersonId: UUID, body: RevokeRequest?, requestId: String?): PickupPersonDto {
        Authorize.require(principal, Permission.PICKUP_PERSON_MANAGE)
        val current = locked(c, principal, pickupPersonId)
        validate { text(body?.reason, "reason", 500, required = false) }
        if (current.status != "ACTIVE") throw conflict("PICKUP_PERSON_ALREADY_REVOKED")
        c.update("UPDATE app.pickup_persons SET status = 'REVOKED', revoked_at = now() WHERE id = ?", pickupPersonId)
        principal.audit(c, "PICKUP_PERSON_REVOKED", "PICKUP_PERSON", pickupPersonId, requestId)
        return byId(c, pickupPersonId)
    }

    /** Row locked for update after the scope check on its child (404 outside scope). */
    private fun locked(c: Connection, principal: TenantPrincipal, id: UUID): PickupPersonDto {
        val row = c.queryOne("$SELECT WHERE id = ? FOR UPDATE", id) { map(it) } ?: throw ProblemException.notFound()
        Scopes.requireChild(c, principal, UUID.fromString(row.childId))
        return row
    }

    private fun byId(c: Connection, id: UUID): PickupPersonDto = c.queryOne("$SELECT WHERE id = ?", id) { map(it) } ?: throw ProblemException.notFound()

    private fun blankToNull(v: String?): String? = v?.trim()?.takeIf { it.isNotEmpty() }

    private fun map(rs: ResultSet) = PickupPersonDto(
        id = rs.uuid("id").toString(), childId = rs.uuid("child_id").toString(), fullName = rs.getString("full_name"),
        relationship = rs.getString("relationship"), phone = rs.getString("phone"), note = rs.getString("note"),
        validFrom = rs.dateOrNull("valid_from")?.toString(), validTo = rs.dateOrNull("valid_to")?.toString(),
        addedByMembershipId = rs.uuid("added_by_membership_id").toString(), status = rs.getString("status"),
        revokedAt = rs.instantOrNull("revoked_at")?.toString(), createdAt = rs.instant("created_at").toString(),
        updatedAt = rs.instant("updated_at").toString(),
    )
}
