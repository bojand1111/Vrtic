package com.vrticconnect.modules.schedules

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Role
import com.vrticconnect.authz.Scopes
import com.vrticconnect.db.SqlArray
import com.vrticconnect.db.date
import com.vrticconnect.db.dateOrNull
import com.vrticconnect.db.instant
import com.vrticconnect.db.queryList
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.db.uuidOrNull
import com.vrticconnect.http.ProblemException
import com.vrticconnect.modules.tenant.TenantPrincipal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * `schedule_change_log` (append-only, docs/PRODUCT_SPEC.md S9): written for every template replace and
 * override set/remove; `before`/`after` hold only times and flags (no names, no reasons, no health data).
 * The staff feed lists the changes that start on a date (`affected_from = date`): for a day override that is
 * its own date, for a template its first day, which is also the date the late flag was computed for.
 */
object ScheduleChanges {

    fun overrideState(attends: Boolean, arrival: String?, departure: String?): JsonObject = JsonObject(
        mapOf("attends" to JsonPrimitive(attends), "arrivalTime" to (arrival?.let(::JsonPrimitive) ?: JsonNull), "departureTime" to (departure?.let(::JsonPrimitive) ?: JsonNull)),
    )

    fun templateState(effectiveFrom: LocalDate, days: Collection<TemplateDayDto>): JsonObject = JsonObject(
        mapOf(
            "effectiveFrom" to JsonPrimitive(effectiveFrom.toString()),
            "days" to JsonArray(
                days.sortedBy { it.weekday }.map { d ->
                    JsonObject(
                        mapOf(
                            "weekday" to JsonPrimitive(d.weekday), "attends" to JsonPrimitive(d.attends),
                            "arrivalTime" to (d.arrivalTime?.let(::JsonPrimitive) ?: JsonNull), "departureTime" to (d.departureTime?.let(::JsonPrimitive) ?: JsonNull),
                        ),
                    )
                },
            ),
        ),
    )

    fun append(
        c: Connection, principal: TenantPrincipal, childId: UUID, kind: String, affectedFrom: LocalDate, affectedTo: LocalDate?,
        isLate: Boolean, before: JsonElement?, after: JsonElement?,
    ) {
        require(kind in KINDS) { "unknown change kind $kind" }
        c.update(
            "INSERT INTO app.schedule_change_log (organization_id, child_id, change_kind, affected_from, affected_to, is_late_change, actor_membership_id, before_state, after_state) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)",
            principal.membership.organizationId, childId, kind, affectedFrom, affectedTo, isLate, principal.membership.membershipId,
            before?.toString(), after?.toString(),
        )
    }

    /** Managers: every child; TEACHER: children enrolled on [date] in an assigned group; PARENT: 403 (staff feed). */
    fun feed(c: Connection, principal: TenantPrincipal, dateParam: LocalDate?, groupId: UUID?, lateOnly: Boolean, limit: Int): ScheduleChangePage {
        Authorize.require(principal, Permission.SCHEDULE_READ)
        if (principal.membership.role == Role.PARENT) throw Authorize.forbidden()
        val date = dateParam ?: Scopes.today(c)
        val groups = Scopes.groupIds(c, principal, date)
        if (groupId != null) {
            if (groups != null && groupId !in groups) throw ProblemException.notFound()
            c.queryList("SELECT 1 AS ok FROM app.groups WHERE id = ? AND deleted_at IS NULL", groupId) { true }.ifEmpty { throw ProblemException.notFound() }
        }
        // lateral enrollment lookup (date, date), then the WHERE parameters, then LIMIT
        val params = mutableListOf<Any?>(date, date, date)
        val where = mutableListOf("l.affected_from = ?")
        if (lateOnly) where += "l.is_late_change"
        if (groupId != null) {
            where += "en.group_id = ?"; params += groupId
        } else if (groups != null) {
            if (groups.isEmpty()) return ScheduleChangePage(date.toString(), emptyList(), null)
            where += "en.group_id = ANY(?)"; params += SqlArray("uuid", groups.toList())
        }
        params += limit
        val items = c.queryList(
            "SELECT l.id, l.child_id, ch.given_name, ch.family_name, en.group_id, g.name AS group_name, l.change_kind, l.affected_from, l.affected_to, " +
                "l.is_late_change, l.actor_membership_id, u.given_name AS actor_given, u.family_name AS actor_family, m.role AS actor_role, " +
                "l.before_state::text AS before_state, l.after_state::text AS after_state, l.occurred_at " +
                "FROM app.schedule_change_log l JOIN app.children ch ON ch.id = l.child_id " +
                "LEFT JOIN LATERAL (SELECT e.group_id FROM app.enrollments e WHERE e.child_id = l.child_id AND e.status IN ('PLANNED','ACTIVE') " +
                "  AND e.valid_from <= ? AND (e.valid_to IS NULL OR e.valid_to >= ?) ORDER BY e.valid_from DESC LIMIT 1) en ON true " +
                "LEFT JOIN app.groups g ON g.id = en.group_id " +
                "LEFT JOIN app.organization_memberships m ON m.id = l.actor_membership_id LEFT JOIN app.users u ON u.id = m.user_id " +
                "WHERE ch.deleted_at IS NULL AND ${where.joinToString(" AND ")} ORDER BY l.occurred_at DESC, l.id DESC LIMIT ?",
            *params.toTypedArray(),
        ) { rs ->
            val actorGiven = rs.getString("actor_given")
            ScheduleChangeDto(
                id = rs.getLong("id"), childId = rs.uuid("child_id").toString(), givenName = rs.getString("given_name"), familyName = rs.getString("family_name"),
                groupId = rs.uuidOrNull("group_id")?.toString(), groupName = rs.getString("group_name"), changeKind = rs.getString("change_kind"),
                affectedFrom = rs.date("affected_from").toString(), affectedTo = rs.dateOrNull("affected_to")?.toString(),
                isLateChange = rs.getBoolean("is_late_change"), actorMembershipId = rs.uuidOrNull("actor_membership_id")?.toString(),
                actorName = actorGiven?.let { "$it ${rs.getString("actor_family")}" }, actorRole = rs.getString("actor_role"),
                before = rs.getString("before_state")?.let { Json.parseToJsonElement(it) }, after = rs.getString("after_state")?.let { Json.parseToJsonElement(it) },
                occurredAt = rs.instant("occurred_at").toString(),
            )
        }
        return ScheduleChangePage(date.toString(), items, null)
    }

    /** Late changes that start on [date] (dashboard `lateScheduleChangesToday`). */
    fun lateCount(c: Connection, date: LocalDate): Int =
        c.queryList("SELECT count(*) AS n FROM app.schedule_change_log WHERE affected_from = ? AND is_late_change", date) { it.getInt("n") }.firstOrNull() ?: 0

    private val KINDS = setOf("TEMPLATE_REPLACED", "OVERRIDE_SET", "OVERRIDE_REMOVED", "WEEK_UPDATED")
}
