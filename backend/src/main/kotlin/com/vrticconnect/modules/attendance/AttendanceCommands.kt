package com.vrticconnect.modules.attendance

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Role
import com.vrticconnect.authz.Scopes
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import com.vrticconnect.http.conflict
import com.vrticconnect.http.validate
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.TenantPrincipal
import com.vrticconnect.modules.tenant.audit
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class AttendanceCommandType(val eventType: String, val auditAction: String) {
    CHECK_IN("CHECK_IN", "ATTENDANCE_CHECKED_IN"),
    CHECK_OUT("CHECK_OUT", "ATTENDANCE_CHECKED_OUT"),
    MARK_ABSENT("ABSENCE_MARKED", "ATTENDANCE_ABSENCE_MARKED"),
    CLEAR_ABSENCE("ABSENCE_CLEARED", "ATTENDANCE_ABSENCE_CLEARED"),
    CORRECTION("CORRECTION", "ATTENDANCE_CORRECTED"),
}

private data class ParsedCommand(
    val commandId: UUID,
    val expectedVersion: Int,
    val occurredAt: Instant,
    val attendanceDate: LocalDate?,
    val correctionOfEventId: UUID?,
    val correctedOccurredAt: Instant?,
    val voidEvent: Boolean,
)

/**
 * ADR-0008 command handling: one transaction per command:
 * scope -> idempotency (command_id) -> lock/create the day (INSERT ON CONFLICT + SELECT FOR UPDATE) -> expectedVersion ->
 * state transition -> immutable event + projection update -> audit.
 */
class AttendanceCommands(private val api: TenantApi) {

    /** Returns (created, result): created=false for a DUPLICATE replay (HTTP 200 instead of 201). */
    suspend fun execute(
        principal: TenantPrincipal,
        childId: UUID,
        type: AttendanceCommandType,
        req: AttendanceCommandRequest,
        requestId: String?,
    ): Pair<Boolean, AttendanceCommandResult> {
        Authorize.require(principal, if (type == AttendanceCommandType.CORRECTION) Permission.ATTENDANCE_CORRECT else Permission.ATTENDANCE_RECORD)
        val cmd = parse(type, req)
        return api.tx(principal) { c -> run(c, principal, childId, type, req, cmd, requestId) }
    }

    private fun parse(type: AttendanceCommandType, req: AttendanceCommandRequest): ParsedCommand = validate {
        val commandId = uuid(req.commandId, "commandId")
        require(req.expectedVersion != null, "expectedVersion", "REQUIRED", "expectedVersion is required")
        require(req.expectedVersion == null || req.expectedVersion >= 0, "expectedVersion", "INVALID_RANGE", ">= 0")
        val occurredAt = parseInstant(req.occurredAt)
        require(req.occurredAt != null && req.occurredAt.isNotBlank(), "occurredAt", "REQUIRED", "occurredAt is required")
        require(req.occurredAt.isNullOrBlank() || occurredAt != null, "occurredAt", "INVALID_FORMAT", "RFC 3339 instant")
        val date = date(req.attendanceDate, "attendanceDate", required = false)
        text(req.deviceId, "deviceId", 128, required = false)
        text(req.note, "note", 200, required = false)
        var correctionOf: UUID? = null
        var corrected: Instant? = null
        when (type) {
            AttendanceCommandType.MARK_ABSENT -> oneOf(req.absenceKind, "absenceKind", ABSENCE_KINDS)
            AttendanceCommandType.CORRECTION -> {
                correctionOf = uuid(req.correctionOfEventId, "correctionOfEventId")
                val reason = req.reason?.trim().orEmpty()
                require(reason.length >= 3, "reason", "REQUIRED", "reason is required (3..500 characters)")
                require(reason.length <= 500, "reason", "TOO_LONG", "max 500 characters")
                if (req.voidEvent != true) {
                    corrected = parseInstant(req.correctedOccurredAt)
                    require(corrected != null, "correctedOccurredAt", "REQUIRED", "correctedOccurredAt is required unless voidEvent")
                }
            }
            else -> Unit
        }
        ParsedCommand(
            commandId = commandId ?: UUID(0, 0), expectedVersion = req.expectedVersion ?: 0, occurredAt = occurredAt ?: Instant.EPOCH,
            attendanceDate = date, correctionOfEventId = correctionOf, correctedOccurredAt = corrected, voidEvent = req.voidEvent == true,
        )
    }

    private fun run(
        c: Connection,
        principal: TenantPrincipal,
        childId: UUID,
        type: AttendanceCommandType,
        req: AttendanceCommandRequest,
        cmd: ParsedCommand,
        requestId: String?,
    ): Pair<Boolean, AttendanceCommandResult> {
        val clock = AttendancePlanning.clock(c)
        val date = cmd.attendanceDate ?: cmd.occurredAt.atZone(clock.zone).toLocalDate()

        // Scope: the child exists and (TEACHER) belongs on that date to a group the teacher is assigned to.
        c.queryOne("SELECT 1 AS ok FROM app.children WHERE id = ? AND deleted_at IS NULL", childId) { true } ?: throw ProblemException.notFound()
        val groupId = AttendancePlanning.enrollmentGroup(c, childId, date)
        if (!Scopes.isManager(principal)) {
            if (principal.membership.role != Role.TEACHER) throw ProblemException.notFound()
            val groups = Scopes.groupIds(c, principal, date).orEmpty()
            if (groupId == null || groupId !in groups) throw ProblemException.notFound()
        }

        // Idempotency: a replayed commandId returns the current state of the original day, no new event.
        val previous = c.queryOne("SELECT child_id, attendance_day_id FROM app.attendance_events WHERE command_id = ?", cmd.commandId) { rs ->
            rs.uuid("child_id") to rs.uuid("attendance_day_id")
        }
        if (previous != null) {
            if (previous.first != childId) throw conflict("COMMAND_ID_REUSED")
            val day = AttendanceProjection.dayById(c, previous.second) ?: throw ProblemException.notFound()
            return false to AttendanceCommandResult(cmd.commandId.toString(), "DUPLICATE", AttendanceProjection.toDto(c, day, clock), null)
        }

        if (groupId == null) throw invalid("childId", "NOT_ENROLLED", "child is not enrolled in a group on $date")
        checkTimes(type, cmd, date, clock)

        val day = lockDay(c, principal, childId, groupId, date, clock)
        if (day.version != cmd.expectedVersion) {
            throw ProblemException(
                status = HttpStatusCode.Conflict, type = ProblemTypes.CONFLICT, title = "Conflict", detail = "VERSION_MISMATCH",
                currentVersion = day.version, errors = listOf(FieldError("expectedVersion", "STALE", "attendance.version.stale")),
            )
        }
        val eventId = UUID.randomUUID()
        val newVersion = day.version + 1
        val source = source(c, principal)
        val payload = buildJsonObject {
            req.note?.takeIf { it.isNotBlank() }?.let { put("note", JsonPrimitive(it.trim())) }
            if (type == AttendanceCommandType.MARK_ABSENT) put("absenceKind", JsonPrimitive(req.absenceKind))
            if (type == AttendanceCommandType.CORRECTION) {
                put("voidEvent", JsonPrimitive(cmd.voidEvent))
                cmd.correctedOccurredAt?.let { put("correctedOccurredAt", JsonPrimitive(it.toString())) }
            }
        }

        fun appendEvent() = c.update(
            "INSERT INTO app.attendance_events (id, organization_id, attendance_day_id, child_id, event_type, occurred_at, actor_membership_id, source, " +
                "command_id, device_id, resulting_version, correction_of_event_id, correction_reason, payload) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
            eventId, principal.membership.organizationId, day.id, childId, type.eventType, cmd.occurredAt, principal.membership.membershipId, source,
            cmd.commandId, req.deviceId?.takeIf { it.isNotBlank() }, newVersion, cmd.correctionOfEventId,
            if (type == AttendanceCommandType.CORRECTION) req.reason?.trim() else null, payload.toString(),
        )

        when (type) {
            AttendanceCommandType.CHECK_IN -> {
                if (day.status == "CHECKED_IN") throw conflict("ALREADY_CHECKED_IN")
                if (day.lastCheckOutAt != null && cmd.occurredAt.isBefore(day.lastCheckOutAt)) {
                    throw invalid("occurredAt", "BEFORE_LAST_CHECK_OUT", "check-in must not be before the previous check-out")
                }
                appendEvent()
                val visitId = UUID.randomUUID()
                val seq = c.queryOne("SELECT coalesce(max(sequence_no), 0) + 1 AS n FROM app.attendance_visits WHERE attendance_day_id = ?", day.id) { it.getInt("n") } ?: 1
                c.update(
                    "INSERT INTO app.attendance_visits (id, organization_id, attendance_day_id, sequence_no, check_in_at, check_in_event_id) VALUES (?, ?, ?, ?, ?, ?)",
                    visitId, principal.membership.organizationId, day.id, seq, cmd.occurredAt, eventId,
                )
                c.update(
                    "UPDATE app.attendance_days SET status = 'CHECKED_IN', open_visit_id = ?, group_id = ?, first_check_in_at = LEAST(first_check_in_at, ?), " +
                        "visits_count = visits_count + 1, is_unscheduled = (is_unscheduled OR NOT is_expected), version = ?, last_event_id = ?, updated_at = now() WHERE id = ?",
                    visitId, groupId, cmd.occurredAt, newVersion, eventId, day.id,
                )
            }
            AttendanceCommandType.CHECK_OUT -> {
                val openId = day.openVisitId
                if (day.status != "CHECKED_IN" || openId == null) throw conflict("NO_OPEN_VISIT")
                val checkIn = AttendanceProjection.activeVisits(c, day.id).firstOrNull { it.id == openId }?.checkInAt
                if (checkIn != null && cmd.occurredAt.isBefore(checkIn)) throw invalid("occurredAt", "BEFORE_CHECK_IN", "check-out must not be before the check-in")
                appendEvent()
                c.update("UPDATE app.attendance_visits SET check_out_at = ?, check_out_event_id = ? WHERE id = ?", cmd.occurredAt, eventId, openId)
                c.update(
                    "UPDATE app.attendance_days SET status = 'CHECKED_OUT', open_visit_id = NULL, group_id = ?, last_check_out_at = GREATEST(last_check_out_at, ?), " +
                        "version = ?, last_event_id = ?, updated_at = now() WHERE id = ?",
                    groupId, cmd.occurredAt, newVersion, eventId, day.id,
                )
            }
            AttendanceCommandType.MARK_ABSENT, AttendanceCommandType.CLEAR_ABSENCE -> {
                appendEvent()
                c.update(
                    "UPDATE app.attendance_days SET absence_kind = ?, version = ?, last_event_id = ?, updated_at = now() WHERE id = ?",
                    if (type == AttendanceCommandType.MARK_ABSENT) req.absenceKind else null, newVersion, eventId, day.id,
                )
            }
            AttendanceCommandType.CORRECTION -> {
                applyCorrection(c, day, cmd) { appendEvent() }
                reproject(c, day, newVersion, eventId)
            }
        }
        principal.audit(c, type.auditAction, "ATTENDANCE_DAY", day.id, requestId)
        val updated = AttendanceProjection.dayById(c, day.id) ?: throw ProblemException.notFound()
        return true to AttendanceCommandResult(cmd.commandId.toString(), "ACCEPTED", AttendanceProjection.toDto(c, updated, clock), eventId.toString())
    }

    /** occurredAt must not be in the future and (except corrections) must fall on the attendance date; old days are locked. */
    private fun checkTimes(type: AttendanceCommandType, cmd: ParsedCommand, date: LocalDate, clock: OrgClock) {
        val latest = Instant.now().plus(CLOCK_SKEW_MINUTES, ChronoUnit.MINUTES)
        if (date.isAfter(clock.today)) throw invalid("attendanceDate", "IN_FUTURE", "attendanceDate must not be in the future")
        if (cmd.occurredAt.isAfter(latest)) throw invalid("occurredAt", "IN_FUTURE", "occurredAt must not be in the future")
        if (type != AttendanceCommandType.CORRECTION && cmd.occurredAt.atZone(clock.zone).toLocalDate() != date) {
            throw invalid("occurredAt", "NOT_ON_ATTENDANCE_DATE", "occurredAt must fall on the attendance date")
        }
        val window = if (type == AttendanceCommandType.CORRECTION) CORRECTION_WINDOW_DAYS else RECORD_WINDOW_DAYS
        if (date.isBefore(clock.today.minusDays(window))) throw conflict("DAY_LOCKED")
        val corrected = cmd.correctedOccurredAt
        if (corrected != null) {
            if (corrected.isAfter(latest)) throw invalid("correctedOccurredAt", "IN_FUTURE", "correctedOccurredAt must not be in the future")
            if (corrected.atZone(clock.zone).toLocalDate() != date) {
                throw invalid("correctedOccurredAt", "NOT_ON_ATTENDANCE_DATE", "correctedOccurredAt must fall on the attendance date")
            }
        }
    }

    /** Creates the day row on first use (plan and absence frozen from schedule/absences) and locks it. */
    private fun lockDay(c: Connection, principal: TenantPrincipal, childId: UUID, groupId: UUID, date: LocalDate, clock: OrgClock): DayRow {
        AttendanceProjection.dayOf(c, childId, date, forUpdate = true)?.let { return it }
        val plan = AttendancePlanning.plans(c, listOf(childId), date, clock).getValue(childId)
        val absence = AttendancePlanning.absences(c, listOf(childId), date)[childId]
        c.update(
            "INSERT INTO app.attendance_days (organization_id, child_id, group_id, attendance_date, absence_kind, is_expected, expected_arrival, expected_departure) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (child_id, attendance_date) DO NOTHING",
            principal.membership.organizationId, childId, groupId, date, absence, plan.isExpected, plan.arrival, plan.departure,
        )
        return AttendanceProjection.dayOf(c, childId, date, forUpdate = true) ?: throw ProblemException.notFound()
    }

    private fun applyCorrection(c: Connection, day: DayRow, cmd: ParsedCommand, appendEvent: () -> Unit) {
        val targetId = cmd.correctionOfEventId ?: throw invalid("correctionOfEventId", "REQUIRED", "correctionOfEventId is required")
        val target = c.queryOne("SELECT event_type, attendance_day_id FROM app.attendance_events WHERE id = ?", targetId) { rs ->
            rs.getString("event_type") to rs.uuid("attendance_day_id")
        }
        if (target == null || target.second != day.id) throw invalid("correctionOfEventId", "NOT_IN_DAY", "event does not belong to this child and date")
        val visits = AttendanceProjection.activeVisits(c, day.id)
        val corrected = cmd.correctedOccurredAt
        when (target.first) {
            "CHECK_IN" -> {
                val idx = visits.indexOfFirst { it.checkInEventId == targetId }
                if (idx < 0) throw conflict("CORRECTION_NOT_APPLICABLE")
                val visit = visits[idx]
                if (cmd.voidEvent) {
                    appendEvent()
                    // Voided visit: closed at its own check-in time and excluded from every aggregate (see ACTIVE_VISIT).
                    if (visit.checkOutAt == null) c.update("UPDATE app.attendance_visits SET check_out_at = check_in_at WHERE id = ?", visit.id)
                } else {
                    val t = corrected ?: throw invalid("correctedOccurredAt", "REQUIRED", "correctedOccurredAt is required")
                    val previousOut = visits.getOrNull(idx - 1)?.checkOutAt
                    if ((visit.checkOutAt != null && t.isAfter(visit.checkOutAt)) || (previousOut != null && t.isBefore(previousOut))) {
                        throw invalid("correctedOccurredAt", "OUT_OF_ORDER", "corrected time overlaps another visit")
                    }
                    appendEvent()
                    c.update("UPDATE app.attendance_visits SET check_in_at = ? WHERE id = ?", t, visit.id)
                }
            }
            "CHECK_OUT" -> {
                val idx = visits.indexOfFirst { it.checkOutEventId == targetId }
                if (idx < 0) throw conflict("CORRECTION_NOT_APPLICABLE")
                val visit = visits[idx]
                if (cmd.voidEvent) {
                    // Re-opening is only possible for the last visit while no other visit is open.
                    if (idx != visits.lastIndex || visits.any { it.checkOutAt == null }) throw conflict("CORRECTION_NOT_APPLICABLE")
                    appendEvent()
                    c.update("UPDATE app.attendance_visits SET check_out_at = NULL, check_out_event_id = NULL WHERE id = ?", visit.id)
                } else {
                    val t = corrected ?: throw invalid("correctedOccurredAt", "REQUIRED", "correctedOccurredAt is required")
                    val nextIn = visits.getOrNull(idx + 1)?.checkInAt
                    if (t.isBefore(visit.checkInAt) || (nextIn != null && t.isAfter(nextIn))) {
                        throw invalid("correctedOccurredAt", "OUT_OF_ORDER", "corrected time overlaps another visit")
                    }
                    appendEvent()
                    c.update("UPDATE app.attendance_visits SET check_out_at = ? WHERE id = ?", t, visit.id)
                }
            }
            else -> throw invalid("correctionOfEventId", "NOT_CORRECTABLE", "only CHECK_IN and CHECK_OUT events can be corrected")
        }
    }

    /** Recomputes status, open visit, first/last times and visit count from the active visits. */
    private fun reproject(c: Connection, day: DayRow, newVersion: Int, eventId: UUID) {
        val visits = AttendanceProjection.activeVisits(c, day.id)
        val open = visits.firstOrNull { it.checkOutAt == null }
        val status = when {
            open != null -> "CHECKED_IN"
            visits.isNotEmpty() -> "CHECKED_OUT"
            else -> "NOT_ARRIVED"
        }
        c.update(
            "UPDATE app.attendance_days SET status = ?, open_visit_id = ?, first_check_in_at = ?, last_check_out_at = ?, visits_count = ?, " +
                "is_unscheduled = (? AND NOT is_expected), version = ?, last_event_id = ?, updated_at = now() WHERE id = ?",
            status, open?.id, visits.minOfOrNull { it.checkInAt }, visits.mapNotNull { it.checkOutAt }.maxOrNull(), visits.size,
            visits.isNotEmpty(), newVersion, eventId, day.id,
        )
    }

    private fun source(c: Connection, principal: TenantPrincipal): String {
        val kind = c.queryOne("SELECT client_kind FROM app.sessions WHERE id = ?", principal.user.sessionId) { it.getString("client_kind") }
        return if (kind == "ANDROID" || kind == "IOS") "MOBILE" else "WEB"
    }

    companion object {
        val ABSENCE_KINDS = setOf("SICK", "VACATION", "OTHER")
        const val RECORD_WINDOW_DAYS = 2L
        const val CORRECTION_WINDOW_DAYS = 31L
        const val CLOCK_SKEW_MINUTES = 2L

        fun parseInstant(raw: String?): Instant? {
            if (raw.isNullOrBlank()) return null
            return runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull() ?: runCatching { Instant.parse(raw) }.getOrNull()
        }

        fun invalid(field: String, code: String, message: String) = ProblemException(
            status = HttpStatusCode.UnprocessableEntity, type = ProblemTypes.VALIDATION, title = "Validation failed",
            errors = listOf(FieldError(field, code, message)),
        )
    }
}
