package com.vrticconnect.modules.schedules

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Scopes
import com.vrticconnect.db.date
import com.vrticconnect.db.instant
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.db.uuidOrNull
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import com.vrticconnect.http.conflict
import com.vrticconnect.http.validate
import com.vrticconnect.modules.tenant.TenantPrincipal
import com.vrticconnect.modules.tenant.audit
import io.ktor.http.HttpStatusCode
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * Closure days (docs/openapi.yaml `/closure-days`): whole organization (`location_id IS NULL`) or one location.
 * Every member reads (SCHEDULE_READ); only OWNER/ADMIN create or delete. Only future days can be deleted
 * (`409 DAY_FROZEN`); new closures must be today or later (past days are history).
 */
object ClosureDayService {

    private const val SELECT =
        "SELECT cd.id, cd.organization_id, cd.location_id, l.name AS location_name, cd.closure_date, cd.name, cd.created_at " +
            "FROM app.closure_days cd LEFT JOIN app.locations l ON l.id = cd.location_id"

    fun list(c: Connection, principal: TenantPrincipal, from: LocalDate?, to: LocalDate?, locationId: UUID?, limit: Int): ClosureDayPage {
        Authorize.require(principal, Permission.SCHEDULE_READ)
        val where = mutableListOf("true")
        val params = mutableListOf<Any?>()
        if (from != null) { where += "cd.closure_date >= ?"; params += from }
        if (to != null) { where += "cd.closure_date <= ?"; params += to }
        if (locationId != null) { where += "(cd.location_id IS NULL OR cd.location_id = ?)"; params += locationId }
        params += limit
        val items = c.queryList("$SELECT WHERE ${where.joinToString(" AND ")} ORDER BY cd.closure_date, cd.location_id NULLS FIRST, cd.id LIMIT ?", *params.toTypedArray()) { dto(it) }
        return ClosureDayPage(items, null)
    }

    fun create(c: Connection, principal: TenantPrincipal, body: ClosureDayCreateRequest, requestId: String?): ClosureDayDto {
        requireManager(principal)
        val today = Scopes.today(c)
        val (date, locationId, name) = validate {
            val date = date(body.closureDate, "closureDate")
            if (date != null) require(!date.isBefore(today), "closureDate", "IN_PAST", "closureDate must be today or later")
            text(body.name, "name", max = 120)
            val location = uuid(body.locationId, "locationId", required = false)
            Triple(date, location, body.name?.trim())
        }
        if (locationId != null) {
            c.queryOne("SELECT 1 AS ok FROM app.locations WHERE id = ? AND deleted_at IS NULL", locationId) { true }
                ?: throw ProblemException(
                    status = HttpStatusCode.UnprocessableEntity, type = ProblemTypes.VALIDATION, title = "Validation failed",
                    errors = listOf(FieldError("locationId", "NOT_FOUND", "unknown location")),
                )
        }
        val duplicate = c.queryOne(
            "SELECT 1 AS ok FROM app.closure_days WHERE closure_date = ? AND location_id IS NOT DISTINCT FROM ?::uuid", date, locationId,
        ) { true }
        if (duplicate != null) throw conflict("CLOSURE_EXISTS")
        val id = c.queryOne(
            "INSERT INTO app.closure_days (organization_id, location_id, closure_date, name, created_by) VALUES (?, ?::uuid, ?, ?, ?) RETURNING id",
            principal.membership.organizationId, locationId, date, name, principal.user.userId,
        ) { it.uuid("id") }!!
        principal.audit(c, "CLOSURE_DAY_CREATED", "CLOSURE_DAY", id, requestId)
        return c.queryOne("$SELECT WHERE cd.id = ?", id) { dto(it) }!!
    }

    fun delete(c: Connection, principal: TenantPrincipal, id: UUID, requestId: String?) {
        requireManager(principal)
        val date = c.queryOne("SELECT closure_date FROM app.closure_days WHERE id = ? FOR UPDATE", id) { it.date("closure_date") }
            ?: throw ProblemException.notFound()
        if (!date.isAfter(Scopes.today(c))) throw conflict("DAY_FROZEN")
        c.update("DELETE FROM app.closure_days WHERE id = ?", id)
        principal.audit(c, "CLOSURE_DAY_DELETED", "CLOSURE_DAY", id, requestId)
    }

    private fun requireManager(principal: TenantPrincipal) {
        Authorize.require(principal, Permission.SCHEDULE_MANAGE)
        if (!Scopes.isManager(principal)) throw Authorize.forbidden()
    }

    private fun dto(rs: java.sql.ResultSet) = ClosureDayDto(
        id = rs.uuid("id").toString(), organizationId = rs.uuid("organization_id").toString(), locationId = rs.uuidOrNull("location_id")?.toString(),
        locationName = rs.getString("location_name"), closureDate = rs.date("closure_date").toString(), name = rs.getString("name"),
        createdAt = rs.instant("created_at").toString(),
    )
}
