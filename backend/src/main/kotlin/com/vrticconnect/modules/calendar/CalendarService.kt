package com.vrticconnect.modules.calendar

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
import com.vrticconnect.http.Validation
import com.vrticconnect.http.invalidQuery
import com.vrticconnect.modules.announcements.JsonPatch
import com.vrticconnect.modules.announcements.Versioning
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.TenantPrincipal
import com.vrticconnect.modules.tenant.audit
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Calendar events (docs/openapi.yaml "Calendar"). Managers (CALENDAR_MANAGE) see and edit all events;
 * everyone else sees organization-wide events plus events of the locations/groups in their scope
 * (TEACHER: assigned groups today + own primary location; PARENT: groups of their children today).
 */
class CalendarService(private val api: TenantApi) {

    private data class Valid(
        val kind: String, val title: String, val description: String?, val locationId: UUID?, val groupId: UUID?, val allDay: Boolean,
        val startsOn: LocalDate, val endsOn: LocalDate, val startsAt: Instant?, val endsAt: Instant?, val timezone: String, val consentPolicyId: UUID?,
    )

    suspend fun list(
        p: TenantPrincipal, from: LocalDate?, to: LocalDate?, locationId: UUID?, groupId: UUID?, kind: String?, sort: String, limit: Int,
    ): CalendarEventPage {
        Authorize.require(p, Permission.CALENDAR_READ)
        if (kind != null && kind !in KINDS) throw invalidQuery("kind", KINDS.joinToString("|"))
        if (sort !in setOf("startsOn:asc", "startsOn:desc")) throw invalidQuery("sort", "startsOn:asc|startsOn:desc")
        return api.tx(p) { c ->
            val today = Scopes.today(c)
            val start = from ?: today.withDayOfMonth(1)
            val end = to ?: start.withDayOfMonth(start.lengthOfMonth())
            if (end.isBefore(start)) throw invalidQuery("to", "to must not be before from")
            if (ChronoUnit.DAYS.between(start, end) > 366) throw invalidQuery("to", "max 366 days")
            val where = mutableListOf("e.deleted_at IS NULL", "e.starts_on <= ?", "e.ends_on >= ?")
            val params = mutableListOf<Any?>(end, start)
            visibility(c, p)?.let { (sql, args) -> where.add(sql); params.addAll(args) }
            if (locationId != null) { where += "e.location_id = ?"; params += locationId }
            if (groupId != null) { where += "e.group_id = ?"; params += groupId }
            if (kind != null) { where += "e.kind = ?"; params += kind }
            params += limit
            val dir = if (sort == "startsOn:desc") "DESC" else "ASC"
            val items = c.queryList(
                "$SELECT WHERE ${where.joinToString(" AND ")} ORDER BY e.starts_on $dir, e.starts_at $dir NULLS FIRST, e.id LIMIT ?",
                *params.toTypedArray(),
            ) { map(it) }
            CalendarEventPage(items, null)
        }
    }

    suspend fun get(p: TenantPrincipal, id: UUID): CalendarEventDto {
        Authorize.require(p, Permission.CALENDAR_READ)
        return api.tx(p) { c -> load(c, p, id) }
    }

    suspend fun create(p: TenantPrincipal, input: CalendarEventInput, requestId: String?): CalendarEventDto {
        Authorize.require(p, Permission.CALENDAR_MANAGE)
        return api.tx(p) { c ->
            val v = validate(c, input)
            val id = UUID.randomUUID()
            c.update(
                "INSERT INTO app.calendar_events (id, organization_id, kind, title, description, location_id, group_id, all_day, starts_on, ends_on, " +
                    "starts_at, ends_at, timezone, requires_consent_policy_id, created_by_membership_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, p.membership.organizationId, v.kind, v.title, v.description, v.locationId, v.groupId, v.allDay, v.startsOn, v.endsOn,
                v.startsAt, v.endsAt, v.timezone, v.consentPolicyId, p.membership.membershipId,
            )
            p.audit(c, "CALENDAR_EVENT_CREATED", "CALENDAR_EVENT", id, requestId)
            load(c, p, id)
        }
    }

    /** Partial update: absent properties keep their value, explicit null clears a nullable one; the result is validated as a whole. */
    suspend fun update(p: TenantPrincipal, id: UUID, expectedVersion: Int, patch: JsonObject, requestId: String?): CalendarEventDto {
        Authorize.require(p, Permission.CALENDAR_MANAGE)
        val allowed = setOf("kind", "title", "description", "locationId", "groupId", "allDay", "startsOn", "endsOn", "startsAt", "endsAt", "requiresConsentPolicyId")
        val shape = Validation()
        (patch.keys - allowed).forEach { shape.require(false, it, "UNKNOWN_PROPERTY", "not allowed") }
        shape.require(patch.isNotEmpty(), "body", "EMPTY_PATCH", "at least one property")
        val bad: (String) -> Unit = { shape.require(false, it, "INVALID_TYPE", "wrong JSON type") }
        val strings = (allowed - "allDay").associateWith { JsonPatch.string(patch, it, bad) }
        val allDay = JsonPatch.boolean(patch, "allDay", bad)
        shape.throwIfInvalid()
        return api.tx(p) { c ->
            val current = c.queryOne("$SELECT WHERE e.id = ? AND e.deleted_at IS NULL FOR UPDATE", id) { map(it) } ?: throw ProblemException.notFound()
            Versioning.requireVersion(expectedVersion, current.version)
            fun pick(key: String, old: String?) = if (JsonPatch.has(patch, key)) strings[key] else old
            val merged = CalendarEventInput(
                kind = pick("kind", current.kind), title = pick("title", current.title), description = pick("description", current.description),
                locationId = pick("locationId", current.locationId), groupId = pick("groupId", current.groupId),
                allDay = if (JsonPatch.has(patch, "allDay")) allDay else current.allDay,
                startsOn = pick("startsOn", current.startsOn), endsOn = pick("endsOn", current.endsOn),
                startsAt = pick("startsAt", current.startsAt), endsAt = pick("endsAt", current.endsAt), timezone = current.timezone,
                requiresConsentPolicyId = pick("requiresConsentPolicyId", current.requiresConsentPolicyId),
            )
            // A new group implies its location unless the caller sets the location explicitly.
            val input = if (JsonPatch.has(patch, "groupId") && !JsonPatch.has(patch, "locationId")) merged.copy(locationId = null) else merged
            val v = validate(c, input)
            c.update(
                "UPDATE app.calendar_events SET kind = ?, title = ?, description = ?, location_id = ?, group_id = ?, all_day = ?, starts_on = ?, ends_on = ?, " +
                    "starts_at = ?, ends_at = ?, requires_consent_policy_id = ?, version = version + 1 WHERE id = ?",
                v.kind, v.title, v.description, v.locationId, v.groupId, v.allDay, v.startsOn, v.endsOn, v.startsAt, v.endsAt, v.consentPolicyId, id,
            )
            p.audit(c, "CALENDAR_EVENT_UPDATED", "CALENDAR_EVENT", id, requestId)
            load(c, p, id)
        }
    }

    suspend fun delete(p: TenantPrincipal, id: UUID, expectedVersion: Int, requestId: String?) {
        Authorize.require(p, Permission.CALENDAR_MANAGE)
        api.tx(p) { c ->
            val version = c.queryOne("SELECT version FROM app.calendar_events WHERE id = ? AND deleted_at IS NULL FOR UPDATE", id) { it.getInt("version") }
                ?: throw ProblemException.notFound()
            Versioning.requireVersion(expectedVersion, version)
            c.update("UPDATE app.calendar_events SET deleted_at = now(), version = version + 1 WHERE id = ?", id)
            p.audit(c, "CALENDAR_EVENT_DELETED", "CALENDAR_EVENT", id, requestId)
        }
    }

    /** null = no restriction (manager). */
    private fun visibility(c: Connection, p: TenantPrincipal): Pair<String, List<Any?>>? {
        if (Authorize.has(p, Permission.CALENDAR_MANAGE)) return null
        val groups = Scopes.groupIds(c, p).orEmpty().toList()
        val locations = buildSet {
            if (groups.isNotEmpty()) {
                addAll(c.queryList("SELECT DISTINCT location_id FROM app.groups WHERE id = ANY(?)", SqlArray("uuid", groups)) { it.uuid("location_id") })
            }
            if (p.membership.role == Role.TEACHER) {
                c.queryOne("SELECT primary_location_id FROM app.employees WHERE membership_id = ?", p.membership.membershipId) { it.uuidOrNull("primary_location_id") }
                    ?.let { add(it) }
            }
        }.toList()
        return "((e.location_id IS NULL AND e.group_id IS NULL) OR (e.group_id IS NULL AND e.location_id = ANY(?)) OR e.group_id = ANY(?))" to
            listOf(SqlArray("uuid", locations), SqlArray("uuid", groups))
    }

    private fun load(c: Connection, p: TenantPrincipal, id: UUID): CalendarEventDto {
        val (sql, args) = visibility(c, p) ?: ("TRUE" to emptyList())
        return c.queryOne("$SELECT WHERE e.id = ? AND e.deleted_at IS NULL AND $sql", id, *args.toTypedArray()) { map(it) }
            ?: throw ProblemException.notFound()
    }

    private fun validate(c: Connection, input: CalendarEventInput): Valid {
        val v = Validation()
        val kind = v.oneOf(input.kind, "kind", KINDS)
        v.text(input.title, "title", 200)
        v.text(input.description, "description", 5000, required = false)
        v.require(input.allDay != null, "allDay", "REQUIRED", "allDay is required")
        val allDay = input.allDay ?: true
        val locationId = v.uuid(input.locationId, "locationId", required = false)
        val groupId = v.uuid(input.groupId, "groupId", required = false)
        val consent = v.uuid(input.requiresConsentPolicyId, "requiresConsentPolicyId", required = false)
        val orgZone = c.queryOne("SELECT timezone FROM app.organizations WHERE id = app.current_organization_id()") { it.getString("timezone") } ?: "Europe/Belgrade"
        val timezone = input.timezone?.takeIf { it.isNotBlank() } ?: orgZone
        val zone = runCatching { ZoneId.of(timezone) }.getOrNull()
        v.require(zone != null && timezone.length <= 64, "timezone", "INVALID_VALUE", "IANA timezone")
        var startsOn: LocalDate? = null
        var endsOn: LocalDate? = null
        var startsAt: Instant? = null
        var endsAt: Instant? = null
        if (allDay) {
            startsOn = v.date(input.startsOn, "startsOn")
            endsOn = v.date(input.endsOn, "endsOn")
        } else {
            startsAt = instant(v, input.startsAt, "startsAt")
            endsAt = instant(v, input.endsAt, "endsAt")
            if (startsAt != null && endsAt != null) v.require(endsAt.isAfter(startsAt), "endsAt", "MUST_BE_AFTER_STARTS_AT", "endsAt must be after startsAt")
            if (zone != null) {
                startsOn = startsAt?.atZone(zone)?.toLocalDate()
                endsOn = endsAt?.atZone(zone)?.toLocalDate()
            }
        }
        if (startsOn != null && endsOn != null) {
            v.require(!endsOn.isBefore(startsOn), "endsOn", "MUST_NOT_BE_BEFORE_STARTS_ON", "endsOn must not be before startsOn")
            v.require(ChronoUnit.DAYS.between(startsOn, endsOn) <= 366, "endsOn", "INVALID_RANGE", "max 366 days")
        }
        var location = locationId
        if (locationId != null) {
            v.require(c.queryOne("SELECT 1 AS ok FROM app.locations WHERE id = ? AND deleted_at IS NULL", locationId) { true } == true, "locationId", "NOT_FOUND", "unknown location")
        }
        if (groupId != null) {
            val groupLocation = c.queryOne("SELECT location_id FROM app.groups WHERE id = ? AND deleted_at IS NULL", groupId) { it.uuid("location_id") }
            v.require(groupLocation != null, "groupId", "NOT_FOUND", "unknown group")
            if (groupLocation != null) {
                v.require(locationId == null || locationId == groupLocation, "locationId", "GROUP_LOCATION_MISMATCH", "group belongs to another location")
                location = groupLocation
            }
        }
        v.throwIfInvalid()
        return Valid(
            kind = kind!!, title = input.title!!.trim(), description = input.description?.takeIf { it.isNotBlank() }, locationId = location, groupId = groupId,
            allDay = allDay, startsOn = startsOn!!, endsOn = endsOn!!, startsAt = startsAt, endsAt = endsAt, timezone = timezone, consentPolicyId = consent,
        )
    }

    private fun instant(v: Validation, raw: String?, field: String): Instant? {
        if (raw.isNullOrBlank()) {
            v.require(false, field, "REQUIRED", "$field is required when allDay is false")
            return null
        }
        val parsed = runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull() ?: runCatching { Instant.parse(raw) }.getOrNull()
        v.require(parsed != null, field, "INVALID_FORMAT", "RFC 3339 instant")
        return parsed
    }

    private fun map(rs: ResultSet) = CalendarEventDto(
        id = rs.uuid("id").toString(), organizationId = rs.uuid("organization_id").toString(), kind = rs.getString("kind"), title = rs.getString("title"),
        description = rs.getString("description"), locationId = rs.uuidOrNull("location_id")?.toString(), groupId = rs.uuidOrNull("group_id")?.toString(),
        allDay = rs.getBoolean("all_day"), startsOn = rs.date("starts_on").toString(), endsOn = rs.date("ends_on").toString(),
        startsAt = rs.instantOrNull("starts_at")?.toString(), endsAt = rs.instantOrNull("ends_at")?.toString(), timezone = rs.getString("timezone"),
        requiresConsentPolicyId = rs.uuidOrNull("requires_consent_policy_id")?.toString(),
        createdByMembershipId = rs.uuid("created_by_membership_id").toString(), version = rs.getInt("version"),
        createdAt = rs.instant("created_at").toString(), updatedAt = rs.instant("updated_at").toString(),
    )

    companion object {
        val KINDS = setOf("TRIP", "PHOTO_DAY", "PERFORMANCE", "HOLIDAY", "PARENT_MEETING", "CLOSURE", "OTHER")
        private const val SELECT =
            "SELECT e.id, e.organization_id, e.kind, e.title, e.description, e.location_id, e.group_id, e.all_day, e.starts_on, e.ends_on, e.starts_at, " +
                "e.ends_at, e.timezone, e.requires_consent_policy_id, e.created_by_membership_id, e.version, e.created_at, e.updated_at FROM app.calendar_events e"
    }
}
