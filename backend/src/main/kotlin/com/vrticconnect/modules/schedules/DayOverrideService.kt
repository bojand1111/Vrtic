package com.vrticconnect.modules.schedules

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Scopes
import com.vrticconnect.db.hhmm
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.conflict
import com.vrticconnect.http.validate
import com.vrticconnect.modules.tenant.TenantPrincipal
import com.vrticconnect.modules.tenant.audit
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * One-day overrides (docs/openapi.yaml `/children/{childId}/schedule/overrides/{date}`, PRODUCT_SPEC S2/S3/S5/S10/S11).
 *   who:      managers for any child; PARENT with a CONFIRMED guardian link and `can_manage_schedule` (404 / 403)
 *   when:     today or later; not a closure day; not frozen (an attendance day row exists = first event recorded):
 *             otherwise `409 DAY_NOT_EDITABLE`
 *   late:     after (day_opens_at of the date - schedule_change_deadline_hours) the change is accepted and flagged
 *             `is_late_change` (S3: late changes are not refused, for parents too; refusing is a P1 setting)
 *   version:  `If-Match` is checked when sent ("0" = no override yet); stale -> 409 VERSION_MISMATCH
 *   log:      every set/remove appends a `schedule_change_log` row (OVERRIDE_SET / OVERRIDE_REMOVED) and an audit entry
 */
object DayOverrideService {

    private const val SELECT =
        "SELECT id, child_id, override_date, attends, arrival_time, departure_time, reason, is_late_change, version, created_by_membership_id, created_at, updated_at " +
            "FROM app.schedule_day_overrides"

    fun set(c: Connection, principal: TenantPrincipal, childId: UUID, date: LocalDate, body: DayOverrideSetRequest, ifMatch: Int?, requestId: String?): DayOverrideDto {
        Authorize.require(principal, Permission.SCHEDULE_MANAGE)
        ScheduleService.requireManage(c, principal, childId)
        val org = ScheduleService.orgSchedule(c)
        val (arrival, departure) = validate {
            require(body.attends != null, "attends", "REQUIRED", "attends is required")
            if (body.reason != null) text(body.reason, "reason", max = 300, required = false)
            if (body.attends == true) {
                val a = time(body.arrivalTime, "arrivalTime")
                val d = time(body.departureTime, "departureTime")
                require(date.dayOfWeek.value in org.weekdays, "attends", "NOT_WORKING_DAY", "the organization does not work on weekday ${date.dayOfWeek.value}")
                if (a != null) require(!a.isBefore(org.opens) && a.isBefore(org.closes), "arrivalTime", "OUTSIDE_OPENING_HOURS", "${org.opens.hhmm()}..${org.closes.hhmm()}")
                if (d != null) require(d.isAfter(org.opens) && !d.isAfter(org.closes), "departureTime", "OUTSIDE_OPENING_HOURS", "${org.opens.hhmm()}..${org.closes.hhmm()}")
                if (a != null && d != null) require(a.isBefore(d), "departureTime", "BEFORE_ARRIVAL", "departure must be after arrival")
                a to d
            } else {
                null to null
            }
        }
        requireEditable(c, childId, date)
        val existing = c.queryOne("$SELECT WHERE child_id = ? AND override_date = ? AND deleted_at IS NULL FOR UPDATE", childId, date) { ScheduleResolver.overrideOf(it) }
        if (ifMatch != null && ifMatch != (existing?.version ?: 0)) throw conflict("VERSION_MISMATCH", existing?.version ?: 0)
        val late = ScheduleResolver.isLate(org, date)
        val attends = body.attends == true
        val reason = body.reason?.trim()?.ifEmpty { null }
        val id = if (existing != null) {
            c.update(
                "UPDATE app.schedule_day_overrides SET attends = ?, arrival_time = ?, departure_time = ?, reason = ?, is_late_change = ?, " +
                    "created_by_membership_id = ?, version = version + 1 WHERE id = ?",
                attends, arrival, departure, reason, late, principal.membership.membershipId, existing.id,
            )
            existing.id
        } else {
            c.queryOne(
                "INSERT INTO app.schedule_day_overrides (organization_id, child_id, override_date, attends, arrival_time, departure_time, reason, is_late_change, created_by_membership_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                principal.membership.organizationId, childId, date, attends, arrival, departure, reason, late, principal.membership.membershipId,
            ) { it.uuid("id") }!!
        }
        ScheduleChanges.append(
            c, principal, childId, "OVERRIDE_SET", date, date, late,
            existing?.let { ScheduleChanges.overrideState(it.attends, it.arrival?.hhmm(), it.departure?.hhmm()) },
            ScheduleChanges.overrideState(attends, arrival?.hhmm(), departure?.hhmm()),
        )
        principal.audit(c, "OVERRIDE_SET", "SCHEDULE_OVERRIDE", id, requestId)
        return c.queryOne("$SELECT WHERE id = ?", id) { dto(ScheduleResolver.overrideOf(it)) }!!
    }

    fun remove(c: Connection, principal: TenantPrincipal, childId: UUID, date: LocalDate, ifMatch: Int?, requestId: String?) {
        Authorize.require(principal, Permission.SCHEDULE_MANAGE)
        ScheduleService.requireManage(c, principal, childId)
        val existing = c.queryOne("$SELECT WHERE child_id = ? AND override_date = ? AND deleted_at IS NULL FOR UPDATE", childId, date) { ScheduleResolver.overrideOf(it) }
            ?: throw ProblemException.notFound()
        requireEditable(c, childId, date)
        if (ifMatch != null && ifMatch != existing.version) throw conflict("VERSION_MISMATCH", existing.version)
        val late = ScheduleResolver.isLate(ScheduleService.orgSchedule(c), date)
        c.update("UPDATE app.schedule_day_overrides SET deleted_at = now(), version = version + 1 WHERE id = ?", existing.id)
        ScheduleChanges.append(
            c, principal, childId, "OVERRIDE_REMOVED", date, date, late,
            ScheduleChanges.overrideState(existing.attends, existing.arrival?.hhmm(), existing.departure?.hhmm()), null,
        )
        principal.audit(c, "OVERRIDE_REMOVED", "SCHEDULE_OVERRIDE", existing.id, requestId)
    }

    /** Past dates, closure days and days with recorded attendance (frozen at the first event) cannot change. */
    private fun requireEditable(c: Connection, childId: UUID, date: LocalDate) {
        if (date.isBefore(Scopes.today(c))) throw conflict("DAY_NOT_EDITABLE")
        val snapshot = ScheduleResolver.load(c, listOf(childId), date, date)
        if (snapshot.closure(childId, date) != null) throw conflict("DAY_NOT_EDITABLE")
        val frozen = c.queryOne("SELECT 1 AS ok FROM app.attendance_days WHERE child_id = ? AND attendance_date = ?", childId, date) { true }
        if (frozen != null) throw conflict("DAY_NOT_EDITABLE")
    }

    fun dto(o: ScheduleResolver.Override) = DayOverrideDto(
        id = o.id.toString(), childId = o.childId.toString(), overrideDate = o.date.toString(), attends = o.attends,
        arrivalTime = o.arrival?.hhmm(), departureTime = o.departure?.hhmm(), reason = o.reason, isLateChange = o.isLateChange,
        createdByMembershipId = o.createdBy.toString(), version = o.version, createdAt = o.createdAt.toString(), updatedAt = o.updatedAt.toString(),
    )
}
