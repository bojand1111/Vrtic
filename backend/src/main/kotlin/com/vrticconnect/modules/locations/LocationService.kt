package com.vrticconnect.modules.locations

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Role
import com.vrticconnect.authz.Scopes
import com.vrticconnect.db.SqlArray
import com.vrticconnect.db.instant
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.conflict
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.TenantPrincipal
import com.vrticconnect.modules.tenant.audit
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** docs/openapi.yaml `Location`. */
@Serializable
data class LocationDto(
    val id: String,
    val organizationId: String,
    val name: String,
    val addressLine: String?,
    val city: String?,
    val postalCode: String?,
    val countryCode: String,
    val timezone: String?,
    val phone: String?,
    val status: String,
    val activeGroupsCount: Int,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class LocationPage(val items: List<LocationDto>, val nextCursor: String? = null)

/** docs/openapi.yaml `LocationCreate`; every field nullable so validation answers 422 per field. */
@Serializable
data class LocationCreate(
    val name: String? = null,
    val addressLine: String? = null,
    val city: String? = null,
    val postalCode: String? = null,
    val countryCode: String? = null,
    val timezone: String? = null,
    val phone: String? = null,
)

/**
 * E04 locations. Read: every ACTIVE member (PARENT only locations of their children's groups,
 * per the contract). Write: LOCATION_MANAGE. Delete is a soft delete (`deleted_at`).
 */
class LocationService(private val api: TenantApi) {

    suspend fun list(p: TenantPrincipal, status: String?, limit: Int): LocationPage = api.tx(p) { c ->
        val statusFilter = status ?: "ACTIVE"
        val allowed = visibleLocationIds(c, p)
        val rows = c.queryList(
            "$SELECT WHERE l.deleted_at IS NULL AND l.status = ? AND (?::uuid[] IS NULL OR l.id = ANY(?::uuid[])) ORDER BY lower(l.name), l.id LIMIT ?",
            statusFilter, allowed?.let { SqlArray("uuid", it.toList()) }, allowed?.let { SqlArray("uuid", it.toList()) }, limit,
        ) { map(it) }
        LocationPage(rows)
    }

    suspend fun get(p: TenantPrincipal, id: UUID): LocationDto = api.tx(p) { c ->
        val allowed = visibleLocationIds(c, p)
        if (allowed != null && id !in allowed) throw ProblemException.notFound()
        read(c, id) ?: throw ProblemException.notFound()
    }

    suspend fun create(p: TenantPrincipal, body: LocationCreate, requestId: String?): LocationDto {
        Authorize.require(p, Permission.LOCATION_MANAGE)
        val errors = mutableListOf<FieldError>()
        val name = body.name.cleaned()
        if (name == null) errors += FieldError("name", "REQUIRED", "name is required")
        checkLengths(errors, name, body.addressLine, body.city, body.postalCode, body.timezone, body.phone)
        val country = body.countryCode.cleaned() ?: "RS"
        if (!COUNTRY.matches(country)) errors += FieldError("countryCode", "INVALID_FORMAT", "two upper-case letters")
        throwIfErrors(errors)
        return api.tx(p) { c ->
            requireNameFree(c, name!!, null)
            val id = UUID.randomUUID()
            c.update(
                "INSERT INTO app.locations (id, organization_id, name, address_line, city, postal_code, country_code, timezone, phone) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, p.membership.organizationId, name, body.addressLine.cleaned(), body.city.cleaned(), body.postalCode.cleaned(),
                country, body.timezone.cleaned(), body.phone.cleaned(),
            )
            p.audit(c, "LOCATION_CREATED", "LOCATION", id, requestId)
            read(c, id)!!
        }
    }

    suspend fun update(p: TenantPrincipal, id: UUID, body: PatchBody, requestId: String?): LocationDto {
        Authorize.require(p, Permission.LOCATION_MANAGE)
        val errors = body.errors
        if (body.isEmpty()) errors += FieldError("body", "EMPTY", "at least one field")
        val name = body.string("name")
        if (body.has("name") && name.cleaned() == null) errors += FieldError("name", "REQUIRED", "name is required")
        val status = body.string("status")
        if (body.has("status") && status !in setOf("ACTIVE", "INACTIVE")) errors += FieldError("status", "INVALID_VALUE", "ACTIVE|INACTIVE")
        checkLengths(errors, name, body.string("addressLine"), body.string("city"), body.string("postalCode"), body.string("timezone"), body.string("phone"))
        throwIfErrors(errors)
        return api.tx(p) { c ->
            read(c, id) ?: throw ProblemException.notFound()
            if (name != null) requireNameFree(c, name.trim(), id)
            if (status == "INACTIVE" && activeGroups(c, id) > 0) throw conflict("LOCATION_HAS_ACTIVE_GROUPS")
            val sets = mutableListOf<String>()
            val params = mutableListOf<Any?>()
            if (body.has("name")) { sets += "name = ?"; params += name!!.trim() }
            for ((field, column) in OPTIONAL_TEXT) {
                if (body.has(field)) { sets += "$column = ?"; params += body.string(field).cleaned() }
            }
            if (body.has("status")) { sets += "status = ?"; params += status }
            c.update("UPDATE app.locations SET ${sets.joinToString()} WHERE id = ? AND deleted_at IS NULL", *(params + id).toTypedArray())
            p.audit(c, "LOCATION_UPDATED", "LOCATION", id, requestId)
            read(c, id)!!
        }
    }

    suspend fun delete(p: TenantPrincipal, id: UUID, requestId: String?) {
        Authorize.require(p, Permission.LOCATION_MANAGE)
        api.tx(p) { c ->
            read(c, id) ?: throw ProblemException.notFound()
            val groups = c.queryOne("SELECT count(*)::int AS n FROM app.groups WHERE location_id = ? AND deleted_at IS NULL", id) { it.getInt("n") } ?: 0
            if (groups > 0) throw conflict("LOCATION_IN_USE")
            c.update("UPDATE app.locations SET deleted_at = now(), status = 'INACTIVE' WHERE id = ?", id)
            p.audit(c, "LOCATION_DELETED", "LOCATION", id, requestId)
        }
    }

    /** null = every location; PARENT: only locations of the groups of their children. */
    private fun visibleLocationIds(c: Connection, p: TenantPrincipal): Set<UUID>? {
        if (p.membership.role != Role.PARENT) return null
        val groups = Scopes.groupIds(c, p).orEmpty()
        if (groups.isEmpty()) return emptySet()
        return c.queryList("SELECT DISTINCT location_id FROM app.groups WHERE id = ANY(?)", SqlArray("uuid", groups.toList())) { it.uuid("location_id") }.toSet()
    }

    private fun requireNameFree(c: Connection, name: String, exceptId: UUID?) {
        val taken = c.queryOne(
            "SELECT 1 AS x FROM app.locations WHERE lower(name) = lower(?) AND deleted_at IS NULL AND (?::uuid IS NULL OR id <> ?::uuid)",
            name, exceptId, exceptId,
        ) { true } ?: false
        if (taken) throw conflict("LOCATION_NAME_TAKEN")
    }

    private fun activeGroups(c: Connection, id: UUID): Int =
        c.queryOne("SELECT count(*)::int AS n FROM app.groups WHERE location_id = ? AND deleted_at IS NULL AND status = 'ACTIVE'", id) { it.getInt("n") } ?: 0

    private fun read(c: Connection, id: UUID): LocationDto? =
        c.queryOne("$SELECT WHERE l.id = ? AND l.deleted_at IS NULL", id) { map(it) }

    private fun checkLengths(errors: MutableList<FieldError>, name: String?, address: String?, city: String?, postal: String?, tz: String?, phone: String?) {
        fun max(value: String?, field: String, n: Int) {
            if (value != null && value.trim().length > n) errors += FieldError(field, "TOO_LONG", "max $n characters")
        }
        max(name, "name", 120); max(address, "addressLine", 200); max(city, "city", 100)
        max(postal, "postalCode", 20); max(tz, "timezone", 64); max(phone, "phone", 32)
    }

    private fun map(rs: ResultSet) = LocationDto(
        id = rs.uuid("id").toString(),
        organizationId = rs.uuid("organization_id").toString(),
        name = rs.getString("name"),
        addressLine = rs.getString("address_line"),
        city = rs.getString("city"),
        postalCode = rs.getString("postal_code"),
        countryCode = rs.getString("country_code"),
        timezone = rs.getString("timezone"),
        phone = rs.getString("phone"),
        status = rs.getString("status"),
        activeGroupsCount = rs.getInt("active_groups"),
        createdAt = rs.instant("created_at").toString(),
        updatedAt = rs.instant("updated_at").toString(),
    )

    private companion object {
        val COUNTRY = Regex("^[A-Z]{2}$")
        val OPTIONAL_TEXT = listOf("addressLine" to "address_line", "city" to "city", "postalCode" to "postal_code", "timezone" to "timezone", "phone" to "phone")
        const val SELECT =
            "SELECT l.*, (SELECT count(*)::int FROM app.groups g WHERE g.location_id = l.id AND g.deleted_at IS NULL AND g.status = 'ACTIVE') AS active_groups " +
                "FROM app.locations l"
    }
}
