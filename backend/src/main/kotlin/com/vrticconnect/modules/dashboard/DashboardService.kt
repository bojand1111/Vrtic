package com.vrticconnect.modules.dashboard

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Scopes
import com.vrticconnect.db.instantOrNull
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.uuid
import com.vrticconnect.modules.schedules.ScheduleChanges
import com.vrticconnect.modules.schedules.ScheduleResolver
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.TenantPrincipal
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** docs/openapi.yaml `DailyOverviewCounters` (same definitions as the teacher daily overview). */
@Serializable
data class DailyOverviewCounters(
    val expected: Int,
    val present: Int,
    val departed: Int,
    val absent: Int,
    val notArrived: Int,
    val late: Int,
    val unscheduledPresent: Int,
    val physicallyPresent: Int,
)

@Serializable
data class DashboardLocation(val locationId: String, val name: String, val groupsCount: Int, val counters: DailyOverviewCounters)

@Serializable
data class DashboardGroup(val groupId: String, val locationId: String, val name: String, val counters: DailyOverviewCounters)

/** Reduced `Announcement` for the dashboard list (title + publication time). */
@Serializable
data class DashboardAnnouncement(val id: String, val title: String, val status: String, val publishedAt: String?)

/** Reduced `CalendarEvent` for the dashboard list. */
@Serializable
data class DashboardEvent(val id: String, val kind: String, val title: String, val allDay: Boolean, val startsOn: String, val endsOn: String, val startsAt: String?)

@Serializable
data class DashboardSummary(
    val organizationId: String,
    val date: String,
    val timezone: String,
    val counters: DailyOverviewCounters,
    val childrenActive: Int,
    val staffActive: Int,
    val guardiansPending: Int,
    val invitationsPending: Int,
    val absencesToday: Int,
    val lateScheduleChangesToday: Int,
    val locations: List<DashboardLocation>,
    val groups: List<DashboardGroup>,
    val latestAnnouncements: List<DashboardAnnouncement>,
    val upcomingEvents: List<DashboardEvent>,
    val generatedAt: String,
)

/**
 * E16 (P0 part): admin dashboard. Per child enrolled on the date:
 *   expected   = not a closure day (organization or the group's location), then the day override, else the weekly
 *                template for that weekday, else (no template) the weekday is a working day (ScheduleResolver.attendancePlan)
 *   status     = attendance_days.status for (child, date), NOT_ARRIVED when no row exists
 *   absent     = expected, an ACTIVE absence covers the date and the child is not checked in / out
 *   late       = today only: expected, NOT_ARRIVED, no absence and now > expected arrival + grace
 *   lateScheduleChangesToday = schedule_change_log rows flagged late whose change starts on the date
 */
class DashboardService(private val api: TenantApi) {

    private data class ChildDay(
        val groupId: UUID,
        val locationId: UUID,
        val expected: Boolean,
        val status: String,
        val hasAbsence: Boolean,
        val expectedArrival: LocalTime?,
    )

    suspend fun summary(principal: TenantPrincipal, requestedDate: LocalDate?): DashboardSummary {
        Authorize.require(principal, Permission.REPORT_VIEW)
        return api.tx(principal) { c ->
            val (timezone, graceMinutes) = c.queryOne(
                "SELECT o.timezone, s.late_arrival_grace_minutes FROM app.organizations o " +
                    "JOIN app.organization_settings s ON s.organization_id = o.id WHERE o.id = app.current_organization_id()",
            ) { rs -> rs.getString("timezone") to rs.getInt("late_arrival_grace_minutes") } ?: ("Europe/Belgrade" to 15)
            val zone = ZoneId.of(timezone)
            val today = Scopes.today(c)
            val date = requestedDate ?: today
            val rows = childDays(c, date)
            val nowLocal = if (date == today) LocalTime.now(zone) else null

            fun counters(items: List<ChildDay>): DailyOverviewCounters {
                val expected = items.filter { it.expected }
                val present = expected.count { it.status == "CHECKED_IN" }
                val departed = expected.count { it.status == "CHECKED_OUT" }
                val absent = expected.count { it.status == "NOT_ARRIVED" && it.hasAbsence }
                val notArrivedList = expected.filter { it.status == "NOT_ARRIVED" && !it.hasAbsence }
                val late = if (nowLocal == null) 0 else notArrivedList.count { d ->
                    d.expectedArrival != null && nowLocal.isAfter(d.expectedArrival.plusMinutes(graceMinutes.toLong()))
                }
                val unscheduled = items.count { !it.expected && it.status == "CHECKED_IN" }
                return DailyOverviewCounters(
                    expected = expected.size, present = present, departed = departed, absent = absent,
                    notArrived = notArrivedList.size, late = late, unscheduledPresent = unscheduled,
                    physicallyPresent = present + unscheduled,
                )
            }

            val groups = c.queryList(
                "SELECT g.id, g.location_id, g.name FROM app.groups g WHERE g.deleted_at IS NULL AND g.status = 'ACTIVE' ORDER BY g.name",
            ) { rs -> Triple(rs.uuid("id"), rs.uuid("location_id"), rs.getString("name")) }
            val locations = c.queryList(
                "SELECT id, name FROM app.locations WHERE deleted_at IS NULL AND status = 'ACTIVE' ORDER BY name",
            ) { rs -> rs.uuid("id") to rs.getString("name") }

            DashboardSummary(
                organizationId = principal.membership.organizationId.toString(),
                date = date.toString(),
                timezone = timezone,
                counters = counters(rows),
                childrenActive = count(
                    c,
                    "SELECT count(DISTINCT e.child_id) AS n FROM app.enrollments e JOIN app.children ch ON ch.id = e.child_id " +
                        "WHERE ch.deleted_at IS NULL AND ch.status = 'ACTIVE' AND e.status IN ('PLANNED','ACTIVE') AND e.valid_from <= ? AND (e.valid_to IS NULL OR e.valid_to >= ?)",
                    date, date,
                ),
                staffActive = count(c, "SELECT count(*) AS n FROM app.organization_memberships WHERE status = 'ACTIVE' AND role IN ('OWNER','ADMIN','TEACHER')"),
                guardiansPending = count(c, "SELECT count(*) AS n FROM app.guardians WHERE status = 'PENDING'"),
                invitationsPending = count(c, "SELECT count(*) AS n FROM app.invitations WHERE accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now()"),
                absencesToday = count(c, "SELECT count(*) AS n FROM app.absences WHERE status = 'ACTIVE' AND date_from <= ? AND date_to >= ?", date, date),
                lateScheduleChangesToday = ScheduleChanges.lateCount(c, date),
                locations = locations.map { (id, name) ->
                    DashboardLocation(id.toString(), name, groups.count { it.second == id }, counters(rows.filter { it.locationId == id }))
                },
                groups = groups.map { (id, locationId, name) ->
                    DashboardGroup(id.toString(), locationId.toString(), name, counters(rows.filter { it.groupId == id }))
                },
                latestAnnouncements = c.queryList(
                    "SELECT id, title, status, published_at FROM app.announcements WHERE deleted_at IS NULL AND status = 'PUBLISHED' " +
                        "AND (expires_at IS NULL OR expires_at > now()) ORDER BY published_at DESC NULLS LAST LIMIT 5",
                ) { rs -> DashboardAnnouncement(rs.uuid("id").toString(), rs.getString("title"), rs.getString("status"), rs.instantOrNull("published_at")?.toString()) },
                upcomingEvents = c.queryList(
                    "SELECT id, kind, title, all_day, starts_on, ends_on, starts_at FROM app.calendar_events " +
                        "WHERE deleted_at IS NULL AND ends_on >= ? ORDER BY starts_on, starts_at NULLS FIRST LIMIT 5",
                    date,
                ) { rs ->
                    DashboardEvent(
                        rs.uuid("id").toString(), rs.getString("kind"), rs.getString("title"), rs.getBoolean("all_day"),
                        rs.getObject("starts_on", LocalDate::class.java).toString(), rs.getObject("ends_on", LocalDate::class.java).toString(),
                        rs.instantOrNull("starts_at")?.toString(),
                    )
                },
                generatedAt = Instant.now().toString(),
            )
        }
    }

    private fun childDays(c: Connection, date: LocalDate): List<ChildDay> {
        data class Row(val childId: UUID, val groupId: UUID, val locationId: UUID, val status: String, val hasAbsence: Boolean)
        val rows = c.queryList(
            """
            SELECT e.child_id, e.group_id, g.location_id,
                   COALESCE(ad.status, 'NOT_ARRIVED') AS status,
                   EXISTS (SELECT 1 FROM app.absences a WHERE a.child_id = e.child_id AND a.status = 'ACTIVE' AND a.date_from <= ? AND a.date_to >= ?) AS has_absence
            FROM app.enrollments e
            JOIN app.children ch ON ch.id = e.child_id AND ch.deleted_at IS NULL AND ch.status = 'ACTIVE'
            JOIN app.groups g ON g.id = e.group_id
            LEFT JOIN app.attendance_days ad ON ad.child_id = e.child_id AND ad.attendance_date = ?
            WHERE e.status IN ('PLANNED','ACTIVE') AND e.valid_from <= ? AND (e.valid_to IS NULL OR e.valid_to >= ?)
            """.trimIndent(),
            date, date, date, date, date,
        ) { rs -> Row(rs.uuid("child_id"), rs.uuid("group_id"), rs.uuid("location_id"), rs.getString("status"), rs.getBoolean("has_absence")) }
        val snapshot = ScheduleResolver.load(c, rows.map { it.childId }, date, date)
        return rows.map { r ->
            val plan = snapshot.attendancePlan(r.childId, date)
            ChildDay(
                groupId = r.groupId, locationId = r.locationId, expected = plan.isExpected, status = r.status,
                hasAbsence = r.hasAbsence, expectedArrival = plan.arrival,
            )
        }
    }

    private fun count(c: Connection, sql: String, vararg params: Any?): Int =
        c.queryOne(sql, *params) { rs -> rs.getInt("n") } ?: 0
}
