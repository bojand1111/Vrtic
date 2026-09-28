package com.vrticconnect.modules.attendance

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Role
import com.vrticconnect.authz.Scopes
import com.vrticconnect.db.hhmm
import com.vrticconnect.db.instant
import com.vrticconnect.db.instantOrNull
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.timeOrNull
import com.vrticconnect.db.uuid
import com.vrticconnect.db.uuidOrNull
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.invalidQuery
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.TenantPrincipal
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Read side: daily overview, one child's day, event history. */
class AttendanceQueries(private val api: TenantApi) {

    /** Staff only (x-roles: OWNER/ADMIN/TEACHER); a TEACHER needs an assignment to the group on [date]. */
    suspend fun dailyOverview(principal: TenantPrincipal, groupId: UUID, date: LocalDate?): DailyOverview {
        Authorize.require(principal, Permission.ATTENDANCE_READ)
        if (principal.membership.role == Role.PARENT) throw Authorize.forbidden()
        return api.tx(principal) { c ->
            val clock = AttendancePlanning.clock(c)
            val day = date ?: clock.today
            c.queryOne("SELECT 1 AS ok FROM app.groups WHERE id = ? AND deleted_at IS NULL", groupId) { true } ?: throw ProblemException.notFound()
            Scopes.groupIds(c, principal, day)?.let { if (groupId !in it) throw ProblemException.notFound() }
            overview(c, groupId, day, clock)
        }
    }

    private fun overview(c: Connection, groupId: UUID, date: LocalDate, clock: OrgClock): DailyOverview {
        data class Row(
            val childId: UUID, val given: String, val family: String, val photo: UUID?, val pickups: Int, val day: DayRow?,
        )
        val rows = c.queryList(
            "SELECT ch.id, ch.given_name, ch.family_name, ch.photo_file_id, " +
                "(SELECT count(*) FROM app.pickup_persons p WHERE p.child_id = ch.id AND p.status = 'ACTIVE')::int AS pickups, " +
                "d.id AS day_id, d.status, d.absence_kind, d.is_expected, d.expected_arrival, d.expected_departure, d.is_unscheduled, " +
                "d.first_check_in_at, d.last_check_out_at, d.open_visit_id, d.visits_count, d.version, d.last_event_id, d.updated_at, d.group_id " +
                "FROM app.enrollments en JOIN app.children ch ON ch.id = en.child_id AND ch.deleted_at IS NULL " +
                "LEFT JOIN app.attendance_days d ON d.child_id = ch.id AND d.attendance_date = ? " +
                "WHERE en.group_id = ? AND en.status IN ('PLANNED','ACTIVE') AND en.valid_from <= ? AND (en.valid_to IS NULL OR en.valid_to >= ?) " +
                "ORDER BY ch.family_name, ch.given_name, ch.id",
            date, groupId, date, date,
        ) { rs ->
            val dayId = rs.uuidOrNull("day_id")
            Row(
                childId = rs.uuid("id"), given = rs.getString("given_name"), family = rs.getString("family_name"),
                photo = rs.uuidOrNull("photo_file_id"), pickups = rs.getInt("pickups"),
                day = dayId?.let {
                    DayRow(
                        id = it, childId = rs.uuid("id"), groupId = rs.uuidOrNull("group_id"), date = date, status = rs.getString("status"),
                        absenceKind = rs.getString("absence_kind"), isExpected = rs.getBoolean("is_expected"),
                        expectedArrival = rs.timeOrNull("expected_arrival"), expectedDeparture = rs.timeOrNull("expected_departure"),
                        isUnscheduled = rs.getBoolean("is_unscheduled"), firstCheckInAt = rs.instantOrNull("first_check_in_at"),
                        lastCheckOutAt = rs.instantOrNull("last_check_out_at"), openVisitId = rs.uuidOrNull("open_visit_id"),
                        visitsCount = rs.getInt("visits_count"), version = rs.getInt("version"), lastEventId = rs.uuidOrNull("last_event_id"),
                        updatedAt = rs.instant("updated_at"),
                    )
                },
            )
        }
        val withoutDay = rows.filter { it.day == null }.map { it.childId }
        val plans = AttendancePlanning.plans(c, withoutDay, date, clock)
        val absences = AttendancePlanning.absences(c, withoutDay, date)

        val children = rows.map { r ->
            val d = r.day
            val plan = if (d != null) DayPlan(d.isExpected, d.expectedArrival, d.expectedDeparture) else plans.getValue(r.childId)
            val status = d?.status ?: "NOT_ARRIVED"
            val absence = if (d != null) d.absenceKind else absences[r.childId]
            val state = overviewState(plan.isExpected, status, absence)
            val late = plan.isExpected && (
                AttendancePlanning.checkedInLate(d?.firstCheckInAt, plan.arrival, clock) ||
                    (state == "NOT_ARRIVED" && AttendancePlanning.overdue(date, plan.arrival, clock))
                )
            DailyOverviewChild(
                childId = r.childId.toString(), givenName = r.given, familyName = r.family, photoFileId = r.photo?.toString(),
                hasCriticalHealthAlert = false, isExpected = plan.isExpected, expectedArrival = plan.arrival?.hhmm(),
                expectedDeparture = plan.departure?.hhmm(), status = status, absenceKind = absence, overviewState = state, isLate = late,
                isUnscheduled = d?.isUnscheduled ?: false, firstCheckInAt = d?.firstCheckInAt?.toString(), lastCheckOutAt = d?.lastCheckOutAt?.toString(),
                version = d?.version ?: 0, pickupPersonsCount = r.pickups,
            )
        }
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        return DailyOverview(
            groupId = groupId.toString(), date = date.toString(), timezone = clock.zone.id, isClosure = false,
            lateArrivalGraceMinutes = clock.graceMinutes, counters = counters(children), children = children,
            generatedAt = now.toString(), snapshotValidUntil = now.plus(clock.cacheTtlHours.toLong(), ChronoUnit.HOURS).toString(),
        )
    }

    /** Parents: CONFIRMED guardian link; TEACHER: child in an assigned group on [date]; managers: any child. */
    suspend fun childDay(principal: TenantPrincipal, childId: UUID, date: LocalDate): AttendanceDayDto {
        Authorize.require(principal, Permission.ATTENDANCE_READ)
        return api.tx(principal) { c ->
            val clock = AttendancePlanning.clock(c)
            c.queryOne("SELECT 1 AS ok FROM app.children WHERE id = ? AND deleted_at IS NULL", childId) { true } ?: throw ProblemException.notFound()
            Scopes.childIds(c, principal, date)?.let { if (childId !in it) throw ProblemException.notFound() }
            val day = AttendanceProjection.dayOf(c, childId, date)
            if (day != null) {
                AttendanceProjection.toDto(c, day, clock)
            } else {
                val groupId = AttendancePlanning.enrollmentGroup(c, childId, date)
                val plan = if (groupId == null) DayPlan(false, null, null) else AttendancePlanning.plans(c, listOf(childId), date, clock).getValue(childId)
                AttendanceProjection.synthetic(childId, groupId, date, plan, AttendancePlanning.absences(c, listOf(childId), date)[childId])
            }
        }
    }

    suspend fun events(
        principal: TenantPrincipal, date: LocalDate, groupId: UUID?, childId: UUID?, eventType: String?, ascending: Boolean, limit: Int,
    ): AttendanceEventPage {
        Authorize.require(principal, Permission.ATTENDANCE_READ)
        if (principal.membership.role == Role.PARENT) throw Authorize.forbidden()
        if (groupId == null && childId == null) throw invalidQuery("groupId", "groupId or childId is required")
        if (eventType != null && eventType !in EVENT_TYPES) throw invalidQuery("eventType", EVENT_TYPES.joinToString("|"))
        return api.tx(principal) { c ->
            if (groupId != null) Scopes.groupIds(c, principal, date)?.let { if (groupId !in it) throw ProblemException.notFound() }
            if (childId != null) Scopes.childIds(c, principal, date)?.let { if (childId !in it) throw ProblemException.notFound() }
            val where = mutableListOf("d.attendance_date = ?")
            val params = mutableListOf<Any?>(date)
            if (groupId != null) { where += "d.group_id = ?"; params += groupId }
            if (childId != null) { where += "e.child_id = ?"; params += childId }
            if (eventType != null) { where += "e.event_type = ?"; params += eventType }
            params += limit
            val items = c.queryList(
                "SELECT e.id, e.attendance_day_id, e.child_id, e.event_type, e.occurred_at, e.recorded_at, e.actor_membership_id, e.source, " +
                    "e.command_id, e.device_id, e.resulting_version, e.correction_of_event_id, e.correction_reason " +
                    "FROM app.attendance_events e JOIN app.attendance_days d ON d.id = e.attendance_day_id WHERE ${where.joinToString(" AND ")} " +
                    "ORDER BY e.recorded_at ${if (ascending) "ASC" else "DESC"}, e.id LIMIT ?",
                *params.toTypedArray(),
            ) { rs ->
                AttendanceEventDto(
                    id = rs.uuid("id").toString(), attendanceDayId = rs.uuid("attendance_day_id").toString(), childId = rs.uuid("child_id").toString(),
                    eventType = rs.getString("event_type"), occurredAt = rs.instant("occurred_at").toString(), recordedAt = rs.instant("recorded_at").toString(),
                    actorMembershipId = rs.uuid("actor_membership_id").toString(), source = rs.getString("source"), commandId = rs.uuid("command_id").toString(),
                    deviceId = rs.getString("device_id"), resultingVersion = rs.getInt("resulting_version"),
                    correctionOfEventId = rs.uuidOrNull("correction_of_event_id")?.toString(), correctionReason = rs.getString("correction_reason"),
                )
            }
            AttendanceEventPage(items, null)
        }
    }

    companion object {
        val EVENT_TYPES = setOf("CHECK_IN", "CHECK_OUT", "CORRECTION", "ABSENCE_MARKED", "ABSENCE_CLEARED", "UNSCHEDULED_PRESENT")

        /** docs/openapi.yaml DailyOverviewChild.overviewState. */
        fun overviewState(isExpected: Boolean, status: String, absenceKind: String?): String = when {
            !isExpected && status == "CHECKED_IN" -> "UNSCHEDULED_PRESENT"
            !isExpected -> "NOT_EXPECTED"
            status == "CHECKED_IN" -> "PRESENT"
            status == "CHECKED_OUT" -> "DEPARTED"
            absenceKind != null -> "ABSENT"
            else -> "NOT_ARRIVED"
        }

        /** expected = present + departed + absent + notArrived; unscheduled present counted separately. */
        fun counters(children: List<DailyOverviewChild>): DailyOverviewCounters {
            fun count(state: String) = children.count { it.overviewState == state }
            val present = count("PRESENT")
            val unscheduled = count("UNSCHEDULED_PRESENT")
            return DailyOverviewCounters(
                expected = children.count { it.isExpected }, present = present, departed = count("DEPARTED"), absent = count("ABSENT"),
                notArrived = count("NOT_ARRIVED"), late = children.count { it.isExpected && it.isLate }, unscheduledPresent = unscheduled,
                physicallyPresent = present + unscheduled,
            )
        }
    }
}

