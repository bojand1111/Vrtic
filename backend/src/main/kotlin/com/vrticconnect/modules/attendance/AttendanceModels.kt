package com.vrticconnect.modules.attendance

import kotlinx.serialization.Serializable

/**
 * docs/openapi.yaml AttendanceCommandBase + CheckIn/CheckOut/MarkAbsent/ClearAbsence/Correction commands.
 * One request shape for every command; fields that do not belong to a command are ignored.
 */
@Serializable
data class AttendanceCommandRequest(
    val commandId: String? = null,
    val expectedVersion: Int? = null,
    val occurredAt: String? = null,
    val attendanceDate: String? = null,
    val deviceId: String? = null,
    val note: String? = null,
    val absenceKind: String? = null,
    val correctionOfEventId: String? = null,
    val reason: String? = null,
    val correctedOccurredAt: String? = null,
    val voidEvent: Boolean? = null,
)

@Serializable
data class AttendanceVisitDto(
    val id: String,
    val sequenceNo: Int,
    val checkInAt: String,
    val checkOutAt: String?,
    val checkInEventId: String,
    val checkOutEventId: String?,
)

/** docs/openapi.yaml AttendanceDay. `id` is null for a synthetic NOT_ARRIVED day (no event yet, version 0). */
@Serializable
data class AttendanceDayDto(
    val id: String?,
    val childId: String,
    val groupId: String?,
    val attendanceDate: String,
    val status: String,
    val absenceKind: String?,
    val isExpected: Boolean,
    val expectedArrival: String?,
    val expectedDeparture: String?,
    val isUnscheduled: Boolean,
    val isLate: Boolean,
    val firstCheckInAt: String?,
    val lastCheckOutAt: String?,
    val openVisitId: String?,
    val visitsCount: Int,
    val visits: List<AttendanceVisitDto>,
    val version: Int,
    val lastEventId: String?,
    val updatedAt: String,
)

@Serializable
data class AttendanceCommandResult(
    val commandId: String,
    val outcome: String,
    val attendanceDay: AttendanceDayDto,
    val eventId: String?,
)

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
data class DailyOverviewChild(
    val childId: String,
    val givenName: String,
    val familyName: String,
    val photoFileId: String?,
    val hasCriticalHealthAlert: Boolean,
    val isExpected: Boolean,
    val expectedArrival: String?,
    val expectedDeparture: String?,
    val status: String,
    val absenceKind: String?,
    val overviewState: String,
    val isLate: Boolean,
    val isUnscheduled: Boolean,
    val firstCheckInAt: String?,
    val lastCheckOutAt: String?,
    val version: Int,
    val pickupPersonsCount: Int,
)

@Serializable
data class DailyOverview(
    val groupId: String,
    val date: String,
    val timezone: String,
    val isClosure: Boolean,
    val lateArrivalGraceMinutes: Int,
    val counters: DailyOverviewCounters,
    val children: List<DailyOverviewChild>,
    val generatedAt: String,
    val snapshotValidUntil: String,
)

@Serializable
data class AttendanceEventDto(
    val id: String,
    val attendanceDayId: String,
    val childId: String,
    val eventType: String,
    val occurredAt: String,
    val recordedAt: String,
    val actorMembershipId: String,
    val source: String,
    val commandId: String,
    val deviceId: String?,
    val resultingVersion: Int,
    val correctionOfEventId: String?,
    val correctionReason: String?,
)

@Serializable
data class AttendanceEventPage(val items: List<AttendanceEventDto>, val nextCursor: String?)
