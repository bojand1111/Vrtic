package com.vrticconnect.modules.reports

import com.vrticconnect.modules.attendance.DailyOverviewCounters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** docs/openapi.yaml `AttendanceSummaryReport.totals`. */
@Serializable
data class AttendanceTotals(
    val workingDays: Int,
    val expectedChildDays: Int,
    val presentChildDays: Int,
    val absentChildDays: Int,
    val notArrivedChildDays: Int,
    val unscheduledChildDays: Int,
    val lateArrivals: Int,
    val attendanceRatePct: Double,
)

/** docs/openapi.yaml `AttendanceSummaryReport.byGroup[]` (+ additions notArrived/unscheduled/late). */
@Serializable
data class AttendanceGroupRow(
    val groupId: String,
    val groupName: String,
    val expectedChildDays: Int,
    val presentChildDays: Int,
    val absentChildDays: Int,
    val notArrivedChildDays: Int,
    val unscheduledChildDays: Int,
    val lateArrivals: Int,
    val attendanceRatePct: Double,
)

/** Addition: per child row (no health data; absences split by kind). */
@Serializable
data class AttendanceChildRow(
    val childId: String,
    val givenName: String,
    val familyName: String,
    val groupId: String,
    val groupName: String,
    val expectedDays: Int,
    val presentDays: Int,
    val absentSickDays: Int,
    val absentVacationDays: Int,
    val absentOtherDays: Int,
    val notArrivedDays: Int,
    val unscheduledDays: Int,
    val lateArrivals: Int,
    val attendanceRatePct: Double,
)

@Serializable
data class AttendanceDateRow(val date: String, val counters: DailyOverviewCounters)

/**
 * docs/openapi.yaml `AttendanceSummaryReport`. Additions: `countedTo` (last counted date: days after today are
 * not counted), `byChild`. `byDate` is empty for ranges longer than 62 days.
 */
@Serializable
data class AttendanceSummaryReport(
    val organizationId: String,
    val from: String,
    val to: String,
    val countedTo: String?,
    val groupId: String?,
    val locationId: String?,
    val totals: AttendanceTotals,
    val byGroup: List<AttendanceGroupRow>,
    val byChild: List<AttendanceChildRow>,
    val byDate: List<AttendanceDateRow>,
    val generatedAt: String,
)

/** docs/openapi.yaml `AuditLogEntry` (+ `actorName` addition). */
@Serializable
data class AuditLogEntryDto(
    val id: Long,
    val occurredAt: String,
    val actorUserId: String?,
    val actorMembershipId: String?,
    val actorName: String?,
    val action: String,
    val entityType: String,
    val entityId: String?,
    val requestId: String?,
    val result: String,
    val purpose: String?,
    val metadata: JsonObject,
)

@Serializable
data class AuditLogEntryPage(val items: List<AuditLogEntryDto>, val nextCursor: String?)
