package com.vrticconnect.modules.attendance

import com.vrticconnect.db.SqlArray
import com.vrticconnect.db.date
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.timeOrNull
import com.vrticconnect.db.uuid
import com.vrticconnect.db.uuidOrNull
import com.vrticconnect.modules.schedules.ScheduleResolver
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Organization clock and attendance settings, read inside the tenant transaction. */
data class OrgClock(
    val zone: ZoneId,
    val today: LocalDate,
    val nowLocalTime: LocalTime,
    val graceMinutes: Int,
    val workingWeekdays: Set<Int>,
    val cacheTtlHours: Int,
)

/** Expected attendance of a child on a date (closure, day override, weekly template, else working weekday). */
data class DayPlan(val isExpected: Boolean, val arrival: LocalTime?, val departure: LocalTime?)

object AttendancePlanning {

    fun clock(c: Connection): OrgClock =
        c.queryOne(
            "SELECT o.timezone, (now() AT TIME ZONE o.timezone)::date AS today, (now() AT TIME ZONE o.timezone)::time AS now_time, " +
                "coalesce(s.late_arrival_grace_minutes, 15) AS grace, coalesce(s.working_weekdays, '{1,2,3,4,5}'::smallint[]) AS weekdays, " +
                "coalesce(s.offline_cache_ttl_hours, 12) AS ttl " +
                "FROM app.organizations o LEFT JOIN app.organization_settings s ON s.organization_id = o.id WHERE o.id = app.current_organization_id()",
        ) { rs ->
            @Suppress("UNCHECKED_CAST")
            val weekdays = (rs.getArray("weekdays").array as Array<Any?>).mapNotNull { (it as Number?)?.toInt() }.toSet()
            OrgClock(
                zone = ZoneId.of(rs.getString("timezone")),
                today = rs.date("today"),
                nowLocalTime = rs.timeOrNull("now_time") ?: LocalTime.MIDNIGHT,
                graceMinutes = rs.getInt("grace"),
                workingWeekdays = weekdays,
                cacheTtlHours = rs.getInt("ttl"),
            )
        } ?: error("organization context missing")

    /** Group of the child's PLANNED/ACTIVE enrollment on [date], null when not enrolled. */
    fun enrollmentGroup(c: Connection, childId: UUID, date: LocalDate): UUID? =
        c.queryOne(
            "SELECT group_id FROM app.enrollments WHERE child_id = ? AND status IN ('PLANNED','ACTIVE') " +
                "AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?) LIMIT 1",
            childId, date, date,
        ) { it.uuidOrNull("group_id") }

    /**
     * Expected attendance per child on [date] from the shared schedule resolver: closure day -> not expected,
     * day override, weekly template, else (no template) the working weekday. Absences are separate context
     * ([absences]). Frozen into attendance_days at the first event (see AttendanceCommands.lockDay).
     */
    fun plans(c: Connection, childIds: Collection<UUID>, date: LocalDate, @Suppress("UNUSED_PARAMETER") clock: OrgClock): Map<UUID, DayPlan> {
        if (childIds.isEmpty()) return emptyMap()
        val snapshot = ScheduleResolver.load(c, childIds, date, date)
        return childIds.associateWith { id -> snapshot.attendancePlan(id, date).let { DayPlan(it.isExpected, it.arrival, it.departure) } }
    }

    /** Closure day (organization-wide or of the group's location) on [date], or null. */
    fun groupClosure(c: Connection, groupId: UUID, date: LocalDate): ScheduleResolver.Closure? {
        val location = c.queryOne("SELECT location_id FROM app.groups WHERE id = ?", groupId) { it.uuidOrNull("location_id") }
        return ScheduleResolver.load(c, emptyList(), date, date).closureOfLocation(location, date)
    }

    /** Kind of an ACTIVE absence covering [date], per child. */
    fun absences(c: Connection, childIds: Collection<UUID>, date: LocalDate): Map<UUID, String> {
        if (childIds.isEmpty()) return emptyMap()
        return c.queryList(
            "SELECT child_id, kind FROM app.absences WHERE child_id = ANY(?) AND status = 'ACTIVE' AND date_from <= ? AND date_to >= ?",
            SqlArray("uuid", childIds.toList()), date, date,
        ) { it.uuid("child_id") to it.getString("kind") }.toMap()
    }

    /** First check-in after expected arrival + grace. */
    fun checkedInLate(firstCheckIn: Instant?, arrival: LocalTime?, clock: OrgClock): Boolean {
        if (firstCheckIn == null || arrival == null) return false
        val local = firstCheckIn.atZone(clock.zone).toLocalTime()
        return local.isAfter(arrival.plusMinutes(clock.graceMinutes.toLong()))
    }

    /** Today, expected, not arrived, no absence and already past arrival + grace. */
    fun overdue(date: LocalDate, arrival: LocalTime?, clock: OrgClock): Boolean =
        date == clock.today && arrival != null && clock.nowLocalTime.isAfter(arrival.plusMinutes(clock.graceMinutes.toLong()))
}
