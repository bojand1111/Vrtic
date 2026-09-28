package com.vrticconnect.modules.attendance

import com.vrticconnect.db.hhmm
import com.vrticconnect.db.instant
import com.vrticconnect.db.instantOrNull
import com.vrticconnect.db.intOrNull
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.timeOrNull
import com.vrticconnect.db.uuid
import com.vrticconnect.db.uuidOrNull
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Row of app.attendance_days. */
data class DayRow(
    val id: UUID,
    val childId: UUID,
    val groupId: UUID?,
    val date: LocalDate,
    val status: String,
    val absenceKind: String?,
    val isExpected: Boolean,
    val expectedArrival: LocalTime?,
    val expectedDeparture: LocalTime?,
    val isUnscheduled: Boolean,
    val firstCheckInAt: Instant?,
    val lastCheckOutAt: Instant?,
    val openVisitId: UUID?,
    val visitsCount: Int,
    val version: Int,
    val lastEventId: UUID?,
    val updatedAt: Instant,
)

data class VisitRow(
    val id: UUID,
    val sequenceNo: Int,
    val checkInAt: Instant,
    val checkOutAt: Instant?,
    val checkInEventId: UUID,
    val checkOutEventId: UUID?,
)

/**
 * Reads of the attendance projection. A visit whose CHECK_IN was voided by a CORRECTION event stays in
 * the table (runtime has no DELETE on attendance_visits) but is excluded from every aggregate and list.
 */
object AttendanceProjection {

    private const val DAY_COLUMNS =
        "id, child_id, group_id, attendance_date, status, absence_kind, is_expected, expected_arrival, expected_departure, is_unscheduled, " +
            "first_check_in_at, last_check_out_at, open_visit_id, visits_count, version, last_event_id, updated_at"

    const val ACTIVE_VISIT =
        "NOT EXISTS (SELECT 1 FROM app.attendance_events ve WHERE ve.correction_of_event_id = v.check_in_event_id " +
            "AND ve.event_type = 'CORRECTION' AND ve.payload->>'voidEvent' = 'true')"

    fun dayById(c: Connection, id: UUID, forUpdate: Boolean = false): DayRow? =
        c.queryOne("SELECT $DAY_COLUMNS FROM app.attendance_days WHERE id = ?" + if (forUpdate) " FOR UPDATE" else "", id) { mapDay(it) }

    fun dayOf(c: Connection, childId: UUID, date: LocalDate, forUpdate: Boolean = false): DayRow? =
        c.queryOne(
            "SELECT $DAY_COLUMNS FROM app.attendance_days WHERE child_id = ? AND attendance_date = ?" + if (forUpdate) " FOR UPDATE" else "",
            childId, date,
        ) { mapDay(it) }

    fun activeVisits(c: Connection, dayId: UUID): List<VisitRow> =
        c.queryList(
            "SELECT v.id, v.sequence_no, v.check_in_at, v.check_out_at, v.check_in_event_id, v.check_out_event_id " +
                "FROM app.attendance_visits v WHERE v.attendance_day_id = ? AND $ACTIVE_VISIT ORDER BY v.sequence_no",
            dayId,
        ) { rs ->
            VisitRow(
                id = rs.uuid("id"), sequenceNo = rs.getInt("sequence_no"), checkInAt = rs.instant("check_in_at"),
                checkOutAt = rs.instantOrNull("check_out_at"), checkInEventId = rs.uuid("check_in_event_id"),
                checkOutEventId = rs.uuidOrNull("check_out_event_id"),
            )
        }

    fun toDto(c: Connection, day: DayRow, clock: OrgClock): AttendanceDayDto =
        AttendanceDayDto(
            id = day.id.toString(),
            childId = day.childId.toString(),
            groupId = day.groupId?.toString(),
            attendanceDate = day.date.toString(),
            status = day.status,
            absenceKind = day.absenceKind,
            isExpected = day.isExpected,
            expectedArrival = day.expectedArrival?.hhmm(),
            expectedDeparture = day.expectedDeparture?.hhmm(),
            isUnscheduled = day.isUnscheduled,
            isLate = day.isExpected && AttendancePlanning.checkedInLate(day.firstCheckInAt, day.expectedArrival, clock),
            firstCheckInAt = day.firstCheckInAt?.toString(),
            lastCheckOutAt = day.lastCheckOutAt?.toString(),
            openVisitId = day.openVisitId?.toString(),
            visitsCount = day.visitsCount,
            visits = activeVisits(c, day.id).map { v ->
                AttendanceVisitDto(
                    id = v.id.toString(), sequenceNo = v.sequenceNo, checkInAt = v.checkInAt.toString(), checkOutAt = v.checkOutAt?.toString(),
                    checkInEventId = v.checkInEventId.toString(), checkOutEventId = v.checkOutEventId?.toString(),
                )
            },
            version = day.version,
            lastEventId = day.lastEventId?.toString(),
            updatedAt = day.updatedAt.toString(),
        )

    /** NOT_ARRIVED day for a date without events (docs/openapi.yaml: synthetic day, version 0). */
    fun synthetic(childId: UUID, groupId: UUID?, date: LocalDate, plan: DayPlan, absenceKind: String?): AttendanceDayDto =
        AttendanceDayDto(
            id = null, childId = childId.toString(), groupId = groupId?.toString(), attendanceDate = date.toString(), status = "NOT_ARRIVED",
            absenceKind = absenceKind, isExpected = plan.isExpected, expectedArrival = plan.arrival?.hhmm(), expectedDeparture = plan.departure?.hhmm(),
            isUnscheduled = false, isLate = false, firstCheckInAt = null, lastCheckOutAt = null, openVisitId = null, visitsCount = 0,
            visits = emptyList(), version = 0, lastEventId = null, updatedAt = Instant.now().toString(),
        )

    private fun mapDay(rs: ResultSet) = DayRow(
        id = rs.uuid("id"),
        childId = rs.uuid("child_id"),
        groupId = rs.uuidOrNull("group_id"),
        date = rs.getObject("attendance_date", LocalDate::class.java),
        status = rs.getString("status"),
        absenceKind = rs.getString("absence_kind"),
        isExpected = rs.getBoolean("is_expected"),
        expectedArrival = rs.timeOrNull("expected_arrival"),
        expectedDeparture = rs.timeOrNull("expected_departure"),
        isUnscheduled = rs.getBoolean("is_unscheduled"),
        firstCheckInAt = rs.instantOrNull("first_check_in_at"),
        lastCheckOutAt = rs.instantOrNull("last_check_out_at"),
        openVisitId = rs.uuidOrNull("open_visit_id"),
        visitsCount = rs.intOrNull("visits_count") ?: 0,
        version = rs.getInt("version"),
        lastEventId = rs.uuidOrNull("last_event_id"),
        updatedAt = rs.instant("updated_at"),
    )
}
