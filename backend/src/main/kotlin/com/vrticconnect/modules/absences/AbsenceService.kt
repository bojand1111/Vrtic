package com.vrticconnect.modules.absences

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Role
import com.vrticconnect.authz.Scopes
import com.vrticconnect.db.SqlArray
import com.vrticconnect.db.date
import com.vrticconnect.db.instant
import com.vrticconnect.db.instantOrNull
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.db.uuidOrNull
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.conflict
import com.vrticconnect.http.invalidQuery
import com.vrticconnect.http.validate
import com.vrticconnect.modules.children.ChildrenService
import com.vrticconnect.modules.notifications.NotificationWriter
import com.vrticconnect.modules.tenant.TenantPrincipal
import com.vrticconnect.modules.tenant.audit
import io.ktor.http.Parameters
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

/** docs/openapi.yaml `Absence` plus the child's name for lists (addition). */
@Serializable
data class AbsenceDto(
    val id: String,
    val organizationId: String,
    val childId: String,
    val childGivenName: String,
    val childFamilyName: String,
    val kind: String,
    val dateFrom: String,
    val dateTo: String,
    val note: String?,
    val status: String,
    val reportedByMembershipId: String,
    val cancelledByMembershipId: String?,
    val cancelledAt: String?,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
    /** Addition: whether the caller may cancel it (ACTIVE and reporter or manager). */
    val canCancel: Boolean,
)

@Serializable
data class AbsencePage(val items: List<AbsenceDto>, val nextCursor: String?)

/** docs/openapi.yaml `AbsenceCreate`. */
@Serializable
data class AbsenceCreateRequest(
    val childId: String? = null,
    val kind: String? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val note: String? = null,
)

/**
 * Absences (docs/openapi.yaml tag Absences). Read: ABSENCE_READ within the child scope (managers all,
 * TEACHER children of assigned groups, PARENT confirmed children). Report: ABSENCE_REPORT, a PARENT
 * additionally needs the guardian flag `can_report_absence`. Cancel: the reporter or a manager.
 */
object AbsenceService {
    val KINDS = setOf("SICK", "VACATION", "OTHER")
    private val SORTS = mapOf("dateFrom:desc" to "a.date_from DESC, a.created_at DESC, a.id", "createdAt:desc" to "a.created_at DESC, a.id")

    private const val SELECT =
        "SELECT a.id, a.organization_id, a.child_id, ch.given_name, ch.family_name, a.kind, a.date_from, a.date_to, a.note, a.status, " +
            "a.reported_by_membership_id, a.cancelled_by_membership_id, a.cancelled_at, a.version, a.created_at, a.updated_at " +
            "FROM app.absences a JOIN app.children ch ON ch.id = a.child_id"

    fun list(c: Connection, principal: TenantPrincipal, query: Parameters, limit: Int): AbsencePage {
        Authorize.require(principal, Permission.ABSENCE_READ)
        val today = Scopes.today(c)
        val from = dateQuery(query, "from") ?: today
        val to = dateQuery(query, "to") ?: from.plusDays(31)
        val childId = uuidQuery(query, "childId")
        val groupId = uuidQuery(query, "groupId")
        val status = query["status"] ?: "ACTIVE"
        val kind = query["kind"]
        val sort = query["sort"] ?: "dateFrom:desc"
        validate {
            oneOf(status, "status", setOf("ACTIVE", "CANCELLED"))
            oneOf(kind, "kind", KINDS, required = false)
            oneOf(sort, "sort", SORTS.keys)
            require(!to.isBefore(from), "to", "BEFORE_START", "to must be on or after from")
        }
        val scope = Scopes.childIds(c, principal)
        if (childId != null && scope != null && childId !in scope) throw ProblemException.notFound()
        if (groupId != null) {
            val groups = Scopes.groupIds(c, principal)
            if (groups != null && groupId !in groups) throw ProblemException.notFound()
        }
        if (scope != null && scope.isEmpty()) return AbsencePage(emptyList(), null)
        val where = mutableListOf("a.status = ?", "a.date_from <= ?", "a.date_to >= ?", "ch.deleted_at IS NULL")
        val params = mutableListOf<Any?>(status, to, from)
        if (scope != null) { where += "a.child_id = ANY(?)"; params += SqlArray("uuid", scope.toList()) }
        if (childId != null) { where += "a.child_id = ?"; params += childId }
        if (kind != null) { where += "a.kind = ?"; params += kind }
        if (groupId != null) {
            where += "EXISTS (SELECT 1 FROM app.enrollments e WHERE e.child_id = a.child_id AND e.group_id = ? AND e.status IN ('PLANNED','ACTIVE') " +
                "AND e.valid_from <= ? AND (e.valid_to IS NULL OR e.valid_to >= ?))"
            params += groupId; params += to; params += from
        }
        val sql = "$SELECT WHERE " + where.joinToString(" AND ") + " ORDER BY " + SORTS.getValue(sort) + " LIMIT " + limit
        return AbsencePage(c.queryList(sql, *params.toTypedArray()) { map(it, principal) }, null)
    }

    fun get(c: Connection, principal: TenantPrincipal, absenceId: UUID): AbsenceDto {
        Authorize.require(principal, Permission.ABSENCE_READ)
        val absence = c.queryOne("$SELECT WHERE a.id = ? AND ch.deleted_at IS NULL", absenceId) { map(it, principal) } ?: throw ProblemException.notFound()
        Scopes.requireChild(c, principal, UUID.fromString(absence.childId))
        return absence
    }

    fun create(c: Connection, principal: TenantPrincipal, body: AbsenceCreateRequest, requestId: String?): AbsenceDto {
        Authorize.require(principal, Permission.ABSENCE_REPORT)
        var from: LocalDate? = null
        var to: LocalDate? = null
        val childId = validate {
            val id = uuid(body.childId, "childId")
            oneOf(body.kind, "kind", KINDS)
            from = date(body.dateFrom, "dateFrom")
            to = date(body.dateTo, "dateTo")
            text(body.note, "note", 500, required = false)
            val f = from; val t = to
            if (f != null && t != null) {
                require(!t.isBefore(f), "dateTo", "BEFORE_START", "dateTo must be on or after dateFrom")
                require(!t.isAfter(f.plusDays(365)), "dateTo", "TOO_LONG", "at most 365 days after dateFrom")
            }
            id
        }!!
        Scopes.requireChild(c, principal, childId)
        ChildrenService.requireChildExists(c, childId)
        if (principal.membership.role == Role.PARENT && !Scopes.guardianCan(c, principal, childId, "can_report_absence")) throw Authorize.forbidden()
        val overlap = c.queryOne(
            "SELECT id FROM app.absences WHERE child_id = ? AND status = 'ACTIVE' AND daterange(date_from, date_to, '[]') && daterange(?, ?, '[]') LIMIT 1",
            childId, from, to,
        ) { it.uuid("id") }
        if (overlap != null) throw conflict("ABSENCE_OVERLAP")
        val id = c.queryOne(
            "INSERT INTO app.absences (organization_id, child_id, kind, date_from, date_to, note, reported_by_membership_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
            principal.membership.organizationId, childId, body.kind, from, to, body.note?.trim()?.takeIf { it.isNotEmpty() },
            principal.membership.membershipId,
        ) { it.uuid("id") }!!
        principal.audit(c, "ABSENCE_REPORTED", "ABSENCE", id, requestId)
        NotificationWriter.absenceChanged(c, principal, id, childId, from!!, to!!, Scopes.today(c), "reported")
        return byId(c, principal, id)
    }

    fun cancel(c: Connection, principal: TenantPrincipal, absenceId: UUID, expectedVersion: Int?, requestId: String?): AbsenceDto {
        Authorize.require(principal, Permission.ABSENCE_REPORT)
        data class Row(val childId: UUID, val status: String, val reporter: UUID, val version: Int)
        val row = c.queryOne(
            "SELECT child_id, status, reported_by_membership_id, version FROM app.absences WHERE id = ? FOR UPDATE", absenceId,
        ) { Row(it.uuid("child_id"), it.getString("status"), it.uuid("reported_by_membership_id"), it.getInt("version")) }
            ?: throw ProblemException.notFound()
        Scopes.requireChild(c, principal, row.childId)
        if (!Scopes.isManager(principal) && row.reporter != principal.membership.membershipId) throw Authorize.forbidden()
        if (expectedVersion != null && expectedVersion != row.version) throw conflict("VERSION_MISMATCH", row.version)
        if (row.status == "CANCELLED") throw conflict("ABSENCE_ALREADY_CANCELLED")
        c.update(
            "UPDATE app.absences SET status = 'CANCELLED', cancelled_by_membership_id = ?, cancelled_at = now(), version = version + 1 WHERE id = ?",
            principal.membership.membershipId, absenceId,
        )
        principal.audit(c, "ABSENCE_CANCELLED", "ABSENCE", absenceId, requestId)
        val cancelled = byId(c, principal, absenceId)
        NotificationWriter.absenceChanged(
            c, principal, absenceId, row.childId, LocalDate.parse(cancelled.dateFrom), LocalDate.parse(cancelled.dateTo), Scopes.today(c), "cancelled",
        )
        return cancelled
    }

    /** ACTIVE absence of each child covering [date] (used by the schedule views). */
    fun activeOn(c: Connection, childIds: Collection<UUID>, date: LocalDate): Map<UUID, Pair<UUID, String>> {
        if (childIds.isEmpty()) return emptyMap()
        return c.queryList(
            "SELECT id, child_id, kind FROM app.absences WHERE child_id = ANY(?) AND status = 'ACTIVE' AND date_from <= ? AND date_to >= ?",
            SqlArray("uuid", childIds.toList()), date, date,
        ) { rs -> rs.uuid("child_id") to (rs.uuid("id") to rs.getString("kind")) }.toMap()
    }

    private fun byId(c: Connection, principal: TenantPrincipal, id: UUID): AbsenceDto = c.queryOne("$SELECT WHERE a.id = ?", id) { map(it, principal) } ?: throw ProblemException.notFound()

    private fun dateQuery(q: Parameters, name: String): LocalDate? =
        q[name]?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw invalidQuery(name, "YYYY-MM-DD") } }

    private fun uuidQuery(q: Parameters, name: String): UUID? =
        q[name]?.let { runCatching { UUID.fromString(it) }.getOrElse { throw invalidQuery(name, "UUID") } }

    private fun map(rs: ResultSet, principal: TenantPrincipal) = AbsenceDto(
        id = rs.uuid("id").toString(), organizationId = rs.uuid("organization_id").toString(), childId = rs.uuid("child_id").toString(),
        childGivenName = rs.getString("given_name"), childFamilyName = rs.getString("family_name"), kind = rs.getString("kind"),
        dateFrom = rs.date("date_from").toString(), dateTo = rs.date("date_to").toString(), note = rs.getString("note"),
        status = rs.getString("status"), reportedByMembershipId = rs.uuid("reported_by_membership_id").toString(),
        cancelledByMembershipId = rs.uuidOrNull("cancelled_by_membership_id")?.toString(),
        cancelledAt = rs.instantOrNull("cancelled_at")?.toString(), version = rs.getInt("version"),
        createdAt = rs.instant("created_at").toString(), updatedAt = rs.instant("updated_at").toString(),
        canCancel = rs.getString("status") == "ACTIVE" && Authorize.has(principal, Permission.ABSENCE_REPORT) &&
            (Scopes.isManager(principal) || rs.uuid("reported_by_membership_id") == principal.membership.membershipId),
    )
}
