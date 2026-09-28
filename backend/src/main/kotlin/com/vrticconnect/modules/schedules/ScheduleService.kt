package com.vrticconnect.modules.schedules

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Role
import com.vrticconnect.authz.Scopes
import com.vrticconnect.db.SqlArray
import com.vrticconnect.db.date
import com.vrticconnect.db.dateOrNull
import com.vrticconnect.db.hhmm
import com.vrticconnect.db.instant
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.timeOrNull
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.invalidQuery
import com.vrticconnect.http.validate
import com.vrticconnect.modules.absences.AbsenceService
import com.vrticconnect.modules.children.ChildrenService
import com.vrticconnect.modules.tenant.TenantPrincipal
import com.vrticconnect.modules.tenant.audit
import java.sql.Connection
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Weekly schedule templates and the views computed from template + absences. Day overrides,
 * closure days and frozen daily plans are not available yet (no tables), so `source` is only
 * ABSENCE / TEMPLATE / NONE and the week PUT (overrides) is not implemented.
 */
object ScheduleService {

    data class OrgSchedule(val opens: LocalTime, val closes: LocalTime, val weekdays: Set<Int>, val deadlineHours: Int, val timezone: String)

    private data class Template(val id: UUID, val childId: UUID, val from: LocalDate, val to: LocalDate?, val version: Int, val days: Map<Int, TemplateDayDto>) {
        fun covers(d: LocalDate) = !from.isAfter(d) && (to == null || !to.isBefore(d))
    }

    fun orgSchedule(c: Connection): OrgSchedule {
        val tz = c.queryOne("SELECT timezone FROM app.organizations WHERE id = app.current_organization_id()") { it.getString("timezone") } ?: "Europe/Belgrade"
        return c.queryOne(
            "SELECT day_opens_at, day_closes_at, working_weekdays, schedule_change_deadline_hours FROM app.organization_settings WHERE organization_id = app.current_organization_id()",
        ) { rs ->
            @Suppress("UNCHECKED_CAST")
            val weekdays = (rs.getArray("working_weekdays").array as Array<Any?>).map { (it as Number).toInt() }.toSet()
            OrgSchedule(rs.timeOrNull("day_opens_at")!!, rs.timeOrNull("day_closes_at")!!, weekdays, rs.getInt("schedule_change_deadline_hours"), tz)
        } ?: OrgSchedule(LocalTime.of(6, 0), LocalTime.of(18, 0), setOf(1, 2, 3, 4, 5), 12, tz)
    }

    fun listTemplates(c: Connection, principal: TenantPrincipal, childId: UUID): ScheduleTemplateList {
        Authorize.require(principal, Permission.SCHEDULE_READ)
        Scopes.requireChild(c, principal, childId)
        ChildrenService.requireChildExists(c, childId)
        return ScheduleTemplateList(templates(c, "t.child_id = ?", childId).sortedByDescending { it.effectiveFrom })
    }

    /** POST (contract) / PUT (alias): replace the recurring template from `effectiveFrom`. Returns (template, created). */
    fun replaceTemplate(c: Connection, principal: TenantPrincipal, childId: UUID, body: ScheduleTemplateCreateRequest, requestId: String?): Pair<ScheduleTemplateDto, Boolean> {
        Authorize.require(principal, Permission.SCHEDULE_MANAGE)
        requireManage(c, principal, childId)
        val org = orgSchedule(c)
        val today = Scopes.today(c)
        val (effectiveFrom, days) = validate {
            val from = date(body.effectiveFrom, "effectiveFrom")
            if (from != null) require(!from.isBefore(today), "effectiveFrom", "IN_PAST", "effectiveFrom must be today or later")
            val input = body.days
            val parsed = mutableListOf<TemplateDayDto>()
            if (input == null || input.size != 7) {
                require(false, "days", "INVALID_SIZE", "exactly 7 entries (weekday 1..7)")
            } else {
                require(input.mapNotNull { it.weekday }.toSet() == (1..7).toSet(), "days", "INVALID_WEEKDAYS", "each weekday 1..7 exactly once")
                input.forEachIndexed { i, d ->
                    val p = "days[$i]"
                    val weekday = d.weekday ?: 0
                    require(d.attends != null, "$p.attends", "REQUIRED", "attends is required")
                    if (d.attends == true) {
                        val arrival = time(d.arrivalTime, "$p.arrivalTime")
                        val departure = time(d.departureTime, "$p.departureTime")
                        require(weekday in org.weekdays, "$p.attends", "NOT_WORKING_DAY", "the organization does not work on weekday $weekday")
                        if (arrival != null) require(!arrival.isBefore(org.opens) && arrival.isBefore(org.closes), "$p.arrivalTime", "OUTSIDE_OPENING_HOURS", "${org.opens.hhmm()}..${org.closes.hhmm()}")
                        if (departure != null) require(departure.isAfter(org.opens) && !departure.isAfter(org.closes), "$p.departureTime", "OUTSIDE_OPENING_HOURS", "${org.opens.hhmm()}..${org.closes.hhmm()}")
                        if (arrival != null && departure != null) require(arrival.isBefore(departure), "$p.departureTime", "BEFORE_ARRIVAL", "departure must be after arrival")
                        parsed += TemplateDayDto(weekday, true, arrival?.hhmm(), departure?.hhmm())
                    } else {
                        parsed += TemplateDayDto(weekday, false, null, null)
                    }
                }
            }
            from to parsed
        }
        val from = effectiveFrom!!
        val existing = c.queryList(
            "SELECT id, effective_from, effective_to FROM app.schedule_templates WHERE child_id = ? ORDER BY effective_from FOR UPDATE", childId,
        ) { Triple(it.uuid("id"), it.date("effective_from"), it.dateOrNull("effective_to")) }
        val same = existing.firstOrNull { it.second == from }
        val templateId: UUID
        if (same != null) {
            templateId = same.first
            c.update("DELETE FROM app.schedule_template_days WHERE template_id = ?", templateId)
            c.update("UPDATE app.schedule_templates SET version = version + 1 WHERE id = ?", templateId)
        } else {
            existing.firstOrNull { it.second.isBefore(from) && (it.third == null || !it.third!!.isBefore(from)) }?.let { covering ->
                c.update("UPDATE app.schedule_templates SET effective_to = ?, version = version + 1 WHERE id = ?", from.minusDays(1), covering.first)
            }
            // A later template already planned stays; the new one fills the gap until it starts.
            val next = existing.filter { it.second.isAfter(from) }.minByOrNull { it.second }
            templateId = c.queryOne(
                "INSERT INTO app.schedule_templates (organization_id, child_id, effective_from, effective_to, created_by_membership_id) VALUES (?, ?, ?, ?, ?) RETURNING id",
                principal.membership.organizationId, childId, from, next?.second?.minusDays(1), principal.membership.membershipId,
            ) { it.uuid("id") }!!
        }
        for (d in days.sortedBy { it.weekday }) {
            c.update(
                "INSERT INTO app.schedule_template_days (organization_id, template_id, weekday, attends, arrival_time, departure_time) VALUES (?, ?, ?, ?, ?, ?)",
                principal.membership.organizationId, templateId, d.weekday, d.attends,
                d.arrivalTime?.let { LocalTime.parse(it) }, d.departureTime?.let { LocalTime.parse(it) },
            )
        }
        principal.audit(c, "TEMPLATE_REPLACED", "SCHEDULE_TEMPLATE", templateId, requestId)
        return templates(c, "t.id = ?", templateId).single() to (same == null)
    }

    fun week(c: Connection, principal: TenantPrincipal, childId: UUID, weekStartParam: LocalDate?): WeekScheduleDto {
        Authorize.require(principal, Permission.SCHEDULE_READ)
        Scopes.requireChild(c, principal, childId)
        ChildrenService.requireChildExists(c, childId)
        val today = Scopes.today(c)
        val weekStart = weekStartParam ?: today.with(DayOfWeek.MONDAY)
        if (weekStart.dayOfWeek != DayOfWeek.MONDAY) throw invalidQuery("weekStart", "a Monday (YYYY-MM-DD)")
        val weekEnd = weekStart.plusDays(6)
        val org = orgSchedule(c)
        val canManage = canManage(c, principal, childId)
        val templates = rawTemplates(
            c, "t.child_id = ? AND t.effective_from <= ? AND (t.effective_to IS NULL OR t.effective_to >= ?)", childId, weekEnd, weekStart,
        )
        val absences = c.queryList(
            "SELECT id, date_from, date_to FROM app.absences WHERE child_id = ? AND status = 'ACTIVE' AND date_from <= ? AND date_to >= ?",
            childId, weekEnd, weekStart,
        ) { Triple(it.uuid("id"), it.date("date_from"), it.date("date_to")) }
        val zone = runCatching { ZoneId.of(org.timezone) }.getOrDefault(ZoneId.of("Europe/Belgrade"))
        val deadline = LocalDateTime.now(zone).plusHours(org.deadlineHours.toLong())
        val used = mutableSetOf<Template>()
        val days = (0L..6L).map { offset ->
            val date = weekStart.plusDays(offset)
            val weekday = date.dayOfWeek.value
            val absence = absences.firstOrNull { !it.second.isAfter(date) && !it.third.isBefore(date) }
            val template = templates.firstOrNull { it.covers(date) }?.also { used += it }
            val day = template?.days?.get(weekday)
            val frozen = date.isBefore(today)
            val (source, expected) = when {
                weekday !in org.weekdays -> "NONE" to false
                absence != null -> "ABSENCE" to false
                template != null -> "TEMPLATE" to (day?.attends == true)
                else -> "NONE" to false
            }
            ScheduleDayDto(
                date = date.toString(), weekday = weekday, isExpected = expected,
                expectedArrival = if (expected) day?.arrivalTime else null, expectedDeparture = if (expected) day?.departureTime else null,
                source = source, isLateChange = false, overrideId = null, absenceId = absence?.first?.toString(), closureDayId = null,
                isFrozen = frozen, isEditable = canManage && !frozen && date.atStartOfDay().isAfter(deadline),
            )
        }
        return WeekScheduleDto(childId.toString(), weekStart.toString(), org.timezone, used.sumOf { it.version }, days, org.deadlineHours)
    }

    /** Addition: children enrolled in a group on [date] with their expected times (managers, TEACHER of the group). */
    fun expected(c: Connection, principal: TenantPrincipal, groupId: UUID, dateParam: LocalDate?): ExpectedChildrenDto {
        Authorize.require(principal, Permission.SCHEDULE_READ)
        if (principal.membership.role == Role.PARENT) throw Authorize.forbidden()
        val date = dateParam ?: Scopes.today(c)
        val groups = Scopes.groupIds(c, principal, date)
        if (groups != null && groupId !in groups) throw ProblemException.notFound()
        c.queryOne("SELECT 1 AS ok FROM app.groups WHERE id = ? AND deleted_at IS NULL", groupId) { true } ?: throw ProblemException.notFound()
        val org = orgSchedule(c)
        val weekday = date.dayOfWeek.value
        val working = weekday in org.weekdays
        data class Kid(val id: UUID, val given: String, val family: String)
        val kids = c.queryList(
            "SELECT DISTINCT ch.id, ch.given_name, ch.family_name FROM app.enrollments e JOIN app.children ch ON ch.id = e.child_id " +
                "WHERE e.group_id = ? AND e.status IN ('PLANNED','ACTIVE') AND e.valid_from <= ? AND (e.valid_to IS NULL OR e.valid_to >= ?) " +
                "AND ch.deleted_at IS NULL AND ch.status = 'ACTIVE' ORDER BY ch.family_name, ch.given_name, ch.id",
            groupId, date, date,
        ) { Kid(it.uuid("id"), it.getString("given_name"), it.getString("family_name")) }
        val ids = kids.map { it.id }
        val templates: Map<UUID, Template> = if (ids.isEmpty()) emptyMap() else rawTemplates(
            c, "t.child_id = ANY(?) AND t.effective_from <= ? AND (t.effective_to IS NULL OR t.effective_to >= ?)",
            SqlArray("uuid", ids), date, date,
        ).associateBy { it.childId }
        val absences = AbsenceService.activeOn(c, ids, date)
        val items = kids.map { k ->
            val day = templates[k.id]?.days?.get(weekday)
            val absence = absences[k.id]
            val (source, expected) = when {
                !working -> "NONE" to false
                absence != null -> "ABSENCE" to false
                templates[k.id] != null -> "TEMPLATE" to (day?.attends == true)
                else -> "NONE" to false
            }
            ExpectedChildDto(
                childId = k.id.toString(), givenName = k.given, familyName = k.family, isExpected = expected,
                expectedArrival = if (expected) day?.arrivalTime else null, expectedDeparture = if (expected) day?.departureTime else null,
                source = source, absenceId = absence?.first?.toString(), absenceKind = absence?.second,
            )
        }
        return ExpectedChildrenDto(groupId.toString(), date.toString(), weekday, working, items)
    }

    /** Managers: any child; PARENT: confirmed guardian with `can_manage_schedule` (404 outside scope, 403 without the flag). */
    private fun requireManage(c: Connection, principal: TenantPrincipal, childId: UUID) {
        Scopes.requireChild(c, principal, childId)
        ChildrenService.requireChildExists(c, childId)
        if (!canManage(c, principal, childId)) throw Authorize.forbidden()
    }

    private fun canManage(c: Connection, principal: TenantPrincipal, childId: UUID): Boolean = when {
        !Authorize.has(principal, Permission.SCHEDULE_MANAGE) -> false
        Scopes.isManager(principal) -> true
        principal.membership.role == Role.PARENT -> Scopes.guardianCan(c, principal, childId, "can_manage_schedule")
        else -> false
    }

    private fun rawTemplates(c: Connection, where: String, vararg params: Any?): List<Template> {
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
        }
    }

    private fun templates(c: Connection, where: String, vararg params: Any?): List<ScheduleTemplateDto> {
        val raw = rawTemplates(c, where, *params)
        if (raw.isEmpty()) return emptyList()
        val meta = c.queryList(
            "SELECT id, created_by_membership_id, created_at FROM app.schedule_templates WHERE id = ANY(?)", SqlArray("uuid", raw.map { it.id }),
        ) { it.uuid("id") to (it.uuid("created_by_membership_id").toString() to it.instant("created_at").toString()) }.toMap()
        return raw.map { t ->
            val m = meta.getValue(t.id)
            ScheduleTemplateDto(
                t.id.toString(), t.childId.toString(), t.from.toString(), t.to?.toString(),
                (1..7).map { w -> t.days[w] ?: TemplateDayDto(w, false, null, null) }, t.version, m.first, m.second,
            )
        }
    }
}
