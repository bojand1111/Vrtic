package com.vrticconnect.modules.reports

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.db.SqlArray
import com.vrticconnect.db.date
import com.vrticconnect.db.dateOrNull
import com.vrticconnect.db.instantOrNull
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.timeOrNull
import com.vrticconnect.db.uuid
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.validate
import com.vrticconnect.modules.attendance.AttendancePlanning
import com.vrticconnect.modules.attendance.AttendanceQueries
import com.vrticconnect.modules.attendance.DailyOverviewCounters
import com.vrticconnect.modules.schedules.ScheduleResolver
import com.vrticconnect.modules.schedules.ScheduleService
import com.vrticconnect.modules.tenant.TenantPrincipal
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Attendance summary (docs/openapi.yaml getAttendanceSummaryReport, REPORT_VIEW = OWNER/ADMIN), using the teacher
 * daily-overview definitions per child-day (child enrolled PLANNED/ACTIVE in a group on the date):
 *   plan       = the frozen attendance_days values when attendance was recorded, else ScheduleResolver.attendancePlan
 *                (closure -> not expected, day override, template, no template -> working weekday)
 *   present    = expected and CHECKED_IN/CHECKED_OUT; absent = expected, not arrived, absence context (by kind)
 *   notArrived = expected, never arrived, no absence; unscheduled = not expected but at least one visit that day
 *   late       = expected and first check-in after expected arrival + grace
 * Days after today are not counted (`countedTo`). Max 366 days; `byDate` only for ranges of at most 62 days.
 */
object AttendanceReportService {

    data class Params(val from: LocalDate, val to: LocalDate, val groupId: UUID?, val locationId: UUID?)

    private data class Enr(val childId: UUID, val given: String, val family: String, val groupId: UUID, val groupName: String, val locationId: UUID, val from: LocalDate, val to: LocalDate?) {
        fun covers(d: LocalDate) = !from.isAfter(d) && (to == null || !to.isBefore(d))
    }

    private data class Recorded(val status: String, val isExpected: Boolean, val arrival: LocalTime?, val absenceKind: String?, val firstCheckIn: Instant?)

    private class Acc {
        var expected = 0; var present = 0; var departed = 0; var absent = 0; var sick = 0; var vacation = 0; var otherAbsence = 0
        var notArrived = 0; var unscheduled = 0; var lateArrivals = 0; var lateNow = 0
        val rate: Double get() = rate(present + departed, expected)
    }

    fun parse(fromRaw: String?, toRaw: String?, groupId: UUID?, locationId: UUID?): Params = validate {
        val from = date(fromRaw, "from")
        val to = date(toRaw, "to")
        if (from != null && to != null) {
            require(!to.isBefore(from), "to", "BEFORE_FROM", "to must not be before from")
            require(ChronoUnit.DAYS.between(from, to) < 366, "to", "RANGE_TOO_LONG", "at most 366 days")
        }
        Params(from ?: LocalDate.MIN, to ?: LocalDate.MIN, groupId, locationId)
    }

    fun report(c: Connection, principal: TenantPrincipal, p: Params): AttendanceSummaryReport {
        Authorize.require(principal, Permission.REPORT_VIEW)
        if (p.groupId != null) c.queryOne("SELECT 1 AS ok FROM app.groups WHERE id = ? AND deleted_at IS NULL", p.groupId) { true } ?: throw ProblemException.notFound()
        if (p.locationId != null) c.queryOne("SELECT 1 AS ok FROM app.locations WHERE id = ? AND deleted_at IS NULL", p.locationId) { true } ?: throw ProblemException.notFound()
        val clock = AttendancePlanning.clock(c)
        val countedTo = if (p.to.isAfter(clock.today)) clock.today else p.to
        val org = ScheduleService.orgSchedule(c)
        val where = mutableListOf("e.status IN ('PLANNED','ACTIVE')", "e.valid_from <= ?", "(e.valid_to IS NULL OR e.valid_to >= ?)", "ch.deleted_at IS NULL", "g.deleted_at IS NULL")
        val params = mutableListOf<Any?>(countedTo, p.from)
        if (p.groupId != null) { where += "e.group_id = ?"; params += p.groupId }
        if (p.locationId != null) { where += "g.location_id = ?"; params += p.locationId }
        val enrollments = if (countedTo.isBefore(p.from)) emptyList() else c.queryList(
            "SELECT e.child_id, ch.given_name, ch.family_name, e.group_id, g.name AS group_name, g.location_id, e.valid_from, e.valid_to " +
                "FROM app.enrollments e JOIN app.children ch ON ch.id = e.child_id JOIN app.groups g ON g.id = e.group_id " +
                "WHERE ${where.joinToString(" AND ")} ORDER BY g.name, ch.family_name, ch.given_name, e.valid_from",
            *params.toTypedArray(),
        ) { rs ->
            Enr(
                rs.uuid("child_id"), rs.getString("given_name"), rs.getString("family_name"), rs.uuid("group_id"), rs.getString("group_name"),
                rs.uuid("location_id"), rs.date("valid_from"), rs.dateOrNull("valid_to"),
            )
        }
        val childIds = enrollments.map { it.childId }.distinct()
        val snapshot = ScheduleResolver.load(c, childIds, p.from, if (countedTo.isBefore(p.from)) p.from else countedTo, org)
        val recorded: Map<Pair<UUID, LocalDate>, Recorded> = if (childIds.isEmpty()) emptyMap() else c.queryList(
            "SELECT child_id, attendance_date, status, is_expected, expected_arrival, absence_kind, first_check_in_at FROM app.attendance_days " +
                "WHERE child_id = ANY(?) AND attendance_date BETWEEN ? AND ?",
            SqlArray("uuid", childIds), p.from, countedTo,
        ) { rs ->
            (rs.uuid("child_id") to rs.date("attendance_date")) to
                Recorded(rs.getString("status"), rs.getBoolean("is_expected"), rs.timeOrNull("expected_arrival"), rs.getString("absence_kind"), rs.instantOrNull("first_check_in_at"))
        }.toMap()

        val total = Acc()
        val byGroup = linkedMapOf<UUID, Pair<String, Acc>>()
        val byChild = linkedMapOf<Pair<UUID, UUID>, Pair<Enr, Acc>>()
        val byDate = mutableListOf<AttendanceDateRow>()
        val withDates = ChronoUnit.DAYS.between(p.from, p.to) < 62
        var workingDays = 0
        var d = p.from
        while (!d.isAfter(countedTo)) {
            val closedForAll = snapshot.isOrgClosed(d) || (p.locationId != null && snapshot.closureOfLocation(p.locationId, d) != null)
            if (d.dayOfWeek.value in org.weekdays && !closedForAll) workingDays++
            val day = Acc()
            val seen = mutableSetOf<UUID>()
            for (e in enrollments) {
                if (!e.covers(d) || !seen.add(e.childId)) continue
                val rec = recorded[e.childId to d]
                val plan = snapshot.attendancePlan(e.childId, d)
                val expected = rec?.isExpected ?: plan.isExpected
                val arrival = if (rec != null) rec.arrival else plan.arrival
                val status = rec?.status ?: "NOT_ARRIVED"
                val absence = if (rec != null) rec.absenceKind else snapshot.absence(e.childId, d)?.kind
                val state = AttendanceQueries.overviewState(expected, status, absence)
                val lateArrival = expected && AttendancePlanning.checkedInLate(rec?.firstCheckIn, arrival, clock)
                val overdue = state == "NOT_ARRIVED" && expected && AttendancePlanning.overdue(d, arrival, clock)
                val group = byGroup.getOrPut(e.groupId) { e.groupName to Acc() }.second
                val child = byChild.getOrPut(e.childId to e.groupId) { e to Acc() }.second
                for (acc in listOf(total, group, child, day)) acc.add(expected, status, state, absence, lateArrival, overdue)
            }
            if (withDates) {
                byDate += AttendanceDateRow(
                    d.toString(),
                    DailyOverviewCounters(
                        expected = day.expected, present = day.present, departed = day.departed, absent = day.absent, notArrived = day.notArrived,
                        late = day.lateArrivals + day.lateNow, unscheduledPresent = day.unscheduled, physicallyPresent = day.present + day.unscheduled,
                    ),
                )
            }
            d = d.plusDays(1)
        }
        return AttendanceSummaryReport(
            organizationId = principal.membership.organizationId.toString(), from = p.from.toString(), to = p.to.toString(),
            countedTo = if (countedTo.isBefore(p.from)) null else countedTo.toString(),
            groupId = p.groupId?.toString(), locationId = p.locationId?.toString(),
            totals = AttendanceTotals(
                workingDays, total.expected, total.present + total.departed, total.absent, total.notArrived, total.unscheduled, total.lateArrivals, total.rate,
            ),
            byGroup = byGroup.map { (id, v) ->
                val a = v.second
                AttendanceGroupRow(id.toString(), v.first, a.expected, a.present + a.departed, a.absent, a.notArrived, a.unscheduled, a.lateArrivals, a.rate)
            },
            byChild = byChild.values.map { (e, a) ->
                AttendanceChildRow(
                    e.childId.toString(), e.given, e.family, e.groupId.toString(), e.groupName, a.expected, a.present + a.departed,
                    a.sick, a.vacation, a.otherAbsence, a.notArrived, a.unscheduled, a.lateArrivals, a.rate,
                )
            },
            byDate = byDate,
            generatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        )
    }

    private fun Acc.add(expected: Boolean, status: String, state: String, absence: String?, lateArrival: Boolean, overdue: Boolean) {
        if (expected) this.expected++
        // a report counts every not-expected day with a visit (the live overview only counts children still inside)
        if (!expected && status != "NOT_ARRIVED") unscheduled++
        when (state) {
            "PRESENT" -> present++
            "DEPARTED" -> departed++
            "ABSENT" -> {
                this.absent++
                when (absence) { "SICK" -> sick++; "VACATION" -> vacation++; else -> otherAbsence++ }
            }
            "NOT_ARRIVED" -> notArrived++
        }
        if (lateArrival) lateArrivals++
        if (overdue) lateNow++
    }

    fun rate(present: Int, expected: Int): Double = if (expected == 0) 0.0 else ((present * 1000.0 / expected).roundToInt() / 10.0).coerceAtMost(100.0)
}
