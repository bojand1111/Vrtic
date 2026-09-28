package com.vrticconnect.modules.schedules

import com.vrticconnect.db.SqlArray
import com.vrticconnect.db.date
import com.vrticconnect.db.dateOrNull
import com.vrticconnect.db.hhmm
import com.vrticconnect.db.instant
import com.vrticconnect.db.queryList
import com.vrticconnect.db.timeOrNull
import com.vrticconnect.db.uuid
import com.vrticconnect.db.uuidOrNull
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Single implementation of the effective schedule of a child on a date (docs/PRODUCT_SPEC.md 5.6,
 * priority closure > active absence > day override > template > none). Loads everything needed for a
 * set of children and a date range in a few queries, then answers per (child, date) in memory, so the
 * week view, the group view, attendance planning, the dashboard and reports share one definition.
 */
object ScheduleResolver {

    data class Closure(val id: UUID, val locationId: UUID?, val date: LocalDate, val name: String)

    data class Override(
        val id: UUID, val childId: UUID, val date: LocalDate, val attends: Boolean, val arrival: LocalTime?, val departure: LocalTime?,
        val reason: String?, val isLateChange: Boolean, val version: Int, val createdBy: UUID, val createdAt: Instant, val updatedAt: Instant,
    )

    data class Enrollment(val childId: UUID, val groupId: UUID, val locationId: UUID, val from: LocalDate, val to: LocalDate?) {
        fun covers(d: LocalDate) = !from.isAfter(d) && (to == null || !to.isBefore(d))
    }

    data class Absence(val id: UUID, val childId: UUID, val kind: String, val from: LocalDate, val to: LocalDate) {
        fun covers(d: LocalDate) = !from.isAfter(d) && !to.isBefore(d)
    }

    data class Template(val id: UUID, val childId: UUID, val from: LocalDate, val to: LocalDate?, val version: Int, val days: Map<Int, TemplateDayDto>) {
        fun covers(d: LocalDate) = !from.isAfter(d) && (to == null || !to.isBefore(d))
    }

    /** Result for one child and date. `source` is the docs/openapi.yaml `ScheduleSource`. */
    data class Effective(
        val source: String,
        val isExpected: Boolean,
        val arrival: LocalTime?,
        val departure: LocalTime?,
        val isWorkingDay: Boolean,
        val closure: Closure?,
        val absence: Absence?,
        val override: Override?,
        val template: Template?,
    )

    /** Attendance view of a day: closure > override > template > (no template) working weekday. Absences stay separate context. */
    data class AttendancePlan(val isExpected: Boolean, val arrival: LocalTime?, val departure: LocalTime?)

    class Snapshot(
        val org: ScheduleService.OrgSchedule,
        private val closures: List<Closure>,
        private val enrollments: Map<UUID, List<Enrollment>>,
        private val templates: Map<UUID, List<Template>>,
        private val overrides: Map<Pair<UUID, LocalDate>, Override>,
        private val absences: Map<UUID, List<Absence>>,
    ) {
        fun enrollment(childId: UUID, date: LocalDate): Enrollment? = enrollments[childId]?.firstOrNull { it.covers(date) }

        fun template(childId: UUID, date: LocalDate): Template? = templates[childId]?.firstOrNull { it.covers(date) }

        fun templatesOf(childId: UUID): List<Template> = templates[childId].orEmpty()

        fun override(childId: UUID, date: LocalDate): Override? = overrides[childId to date]

        fun absence(childId: UUID, date: LocalDate): Absence? = absences[childId]?.firstOrNull { it.covers(date) }

        /** Organization-wide closure first, else a closure of the location of the child's group on [date]. */
        fun closure(childId: UUID, date: LocalDate): Closure? {
            closures.firstOrNull { it.date == date && it.locationId == null }?.let { return it }
            val location = enrollment(childId, date)?.locationId ?: return null
            return closures.firstOrNull { it.date == date && it.locationId == location }
        }

        fun closureOfLocation(locationId: UUID?, date: LocalDate): Closure? =
            closures.firstOrNull { it.date == date && it.locationId == null }
                ?: locationId?.let { loc -> closures.firstOrNull { it.date == date && it.locationId == loc } }

        fun isOrgClosed(date: LocalDate): Boolean = closures.any { it.date == date && it.locationId == null }

        fun resolve(childId: UUID, date: LocalDate): Effective {
            val working = date.dayOfWeek.value in org.weekdays
            val closure = closure(childId, date)
            val absence = absence(childId, date)
            val override = override(childId, date)
            val template = template(childId, date)
            fun none(source: String) = Effective(source, false, null, null, working, closure, absence, override, template)
            return when {
                closure != null -> none("CLOSURE")
                !working -> none("NONE")
                absence != null -> none("ABSENCE")
                override != null -> Effective(
                    "OVERRIDE", override.attends, override.arrival, override.departure, working, null, null, override, template,
                )
                template != null -> {
                    val day = template.days[date.dayOfWeek.value]
                    val attends = day?.attends == true
                    Effective(
                        "TEMPLATE", attends, if (attends) day.arrivalTime?.let(LocalTime::parse) else null,
                        if (attends) day.departureTime?.let(LocalTime::parse) else null, working, null, null, null, template,
                    )
                }
                else -> none("NONE")
            }
        }

        /**
         * Attendance planning keeps its established fallback (no template -> expected on working weekdays)
         * and treats absences as context of an expected day (ABSENT counter), not as "not expected".
         */
        fun attendancePlan(childId: UUID, date: LocalDate): AttendancePlan {
            if (closure(childId, date) != null) return AttendancePlan(false, null, null)
            override(childId, date)?.let { return AttendancePlan(it.attends, it.arrival, it.departure) }
            val template = template(childId, date)
            if (template != null) {
                val day = template.days[date.dayOfWeek.value]
                return if (day?.attends == true) {
                    AttendancePlan(true, day.arrivalTime?.let(LocalTime::parse), day.departureTime?.let(LocalTime::parse))
                } else {
                    AttendancePlan(false, null, null)
                }
            }
            return AttendancePlan(date.dayOfWeek.value in org.weekdays, null, null)
        }
    }

    fun load(c: Connection, childIds: Collection<UUID>, from: LocalDate, to: LocalDate, org: ScheduleService.OrgSchedule = ScheduleService.orgSchedule(c)): Snapshot {
        val closures = c.queryList(
            "SELECT id, location_id, closure_date, name FROM app.closure_days WHERE closure_date BETWEEN ? AND ?", from, to,
        ) { Closure(it.uuid("id"), it.uuidOrNull("location_id"), it.date("closure_date"), it.getString("name")) }
        if (childIds.isEmpty()) return Snapshot(org, closures, emptyMap(), emptyMap(), emptyMap(), emptyMap())
        val ids = SqlArray("uuid", childIds.distinct())
        val enrollments = c.queryList(
            "SELECT e.child_id, e.group_id, g.location_id, e.valid_from, e.valid_to FROM app.enrollments e JOIN app.groups g ON g.id = e.group_id " +
                "WHERE e.child_id = ANY(?) AND e.status IN ('PLANNED','ACTIVE') AND e.valid_from <= ? AND (e.valid_to IS NULL OR e.valid_to >= ?) " +
                "ORDER BY e.valid_from",
            ids, to, from,
        ) { Enrollment(it.uuid("child_id"), it.uuid("group_id"), it.uuid("location_id"), it.date("valid_from"), it.dateOrNull("valid_to")) }
            .groupBy { it.childId }
        val templates = templates(c, "t.child_id = ANY(?) AND t.effective_from <= ? AND (t.effective_to IS NULL OR t.effective_to >= ?)", ids, to, from)
            .groupBy { it.childId }
        val overrides = c.queryList(
            "SELECT id, child_id, override_date, attends, arrival_time, departure_time, reason, is_late_change, version, created_by_membership_id, created_at, updated_at " +
                "FROM app.schedule_day_overrides WHERE child_id = ANY(?) AND override_date BETWEEN ? AND ? AND deleted_at IS NULL",
            ids, from, to,
        ) { overrideOf(it) }.associateBy { it.childId to it.date }
        val absences = c.queryList(
            "SELECT id, child_id, kind, date_from, date_to FROM app.absences WHERE child_id = ANY(?) AND status = 'ACTIVE' AND date_from <= ? AND date_to >= ?",
            ids, to, from,
        ) { Absence(it.uuid("id"), it.uuid("child_id"), it.getString("kind"), it.date("date_from"), it.date("date_to")) }.groupBy { it.childId }
        return Snapshot(org, closures, enrollments, templates, overrides, absences)
    }

    fun overrideOf(rs: java.sql.ResultSet) = Override(
        id = rs.uuid("id"), childId = rs.uuid("child_id"), date = rs.date("override_date"), attends = rs.getBoolean("attends"),
        arrival = rs.timeOrNull("arrival_time"), departure = rs.timeOrNull("departure_time"), reason = rs.getString("reason"),
        isLateChange = rs.getBoolean("is_late_change"), version = rs.getInt("version"), createdBy = rs.uuid("created_by_membership_id"),
        createdAt = rs.instant("created_at"), updatedAt = rs.instant("updated_at"),
    )

    fun templates(c: Connection, where: String, vararg params: Any?): List<Template> {
        val rows = c.queryList(
            "SELECT t.id, t.child_id, t.effective_from, t.effective_to, t.version, d.weekday, d.attends, d.arrival_time, d.departure_time " +
                "FROM app.schedule_templates t LEFT JOIN app.schedule_template_days d ON d.template_id = t.id WHERE $where",
            *params,
        ) { rs ->
            val weekday = rs.getInt("weekday").takeUnless { rs.wasNull() }
            val day = weekday?.let { TemplateDayDto(it, rs.getBoolean("attends"), rs.timeOrNull("arrival_time")?.hhmm(), rs.timeOrNull("departure_time")?.hhmm()) }
            Triple(listOf(rs.uuid("id"), rs.uuid("child_id")), Triple(rs.date("effective_from"), rs.dateOrNull("effective_to"), rs.getInt("version")), day)
        }
        return rows.groupBy { it.first }.map { (ids, list) ->
            val meta = list.first().second
            Template(ids[0], ids[1], meta.first, meta.second, meta.third, list.mapNotNull { it.third }.associateBy { it.weekday })
        }.sortedBy { it.from }
    }

    /**
     * docs/PRODUCT_SPEC.md S3 / open question 9.6 (proposed answer): a change affecting [date] is late when it is
     * made after `day_opens_at` of that date in the organization timezone minus `schedule_change_deadline_hours`.
     */
    fun isLate(org: ScheduleService.OrgSchedule, date: LocalDate, now: Instant = Instant.now()): Boolean = !now.isBefore(cutoff(org, date))

    /** Cutoff instant for [date] (see [isLate]); shown to clients as the deadline. */
    fun cutoff(org: ScheduleService.OrgSchedule, date: LocalDate): Instant {
        val zone = runCatching { ZoneId.of(org.timezone) }.getOrDefault(ZoneId.of("Europe/Belgrade"))
        return date.atTime(org.opens).atZone(zone).minusHours(org.deadlineHours.toLong()).toInstant()
    }
}
