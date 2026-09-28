package com.vrticconnect.modules.schedules

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** docs/openapi.yaml `TemplateDay`. */
@Serializable
data class TemplateDayDto(val weekday: Int, val attends: Boolean, val arrivalTime: String?, val departureTime: String?)

/** docs/openapi.yaml `ScheduleTemplate`. */
@Serializable
data class ScheduleTemplateDto(
    val id: String,
    val childId: String,
    val effectiveFrom: String,
    val effectiveTo: String?,
    val days: List<TemplateDayDto>,
    val version: Int,
    val createdByMembershipId: String,
    val createdAt: String,
)

@Serializable
data class ScheduleTemplateList(val items: List<ScheduleTemplateDto>)

@Serializable
data class TemplateDayInput(val weekday: Int? = null, val attends: Boolean? = null, val arrivalTime: String? = null, val departureTime: String? = null)

/** docs/openapi.yaml `ScheduleTemplateCreate`. */
@Serializable
data class ScheduleTemplateCreateRequest(val effectiveFrom: String? = null, val days: List<TemplateDayInput>? = null)

/**
 * docs/openapi.yaml `ScheduleDay`. Additions: `overrideVersion` (If-Match for the day override, 0 = none),
 * `overrideReason`, `closureName`, `absenceKind`, `templateAttends`/`templateArrival`/`templateDeparture`
 * (what the template says, so a client can show "reset to template"), `changeDeadline` (instant after which a
 * change of this day is flagged late).
 */
@Serializable
data class ScheduleDayDto(
    val date: String,
    val weekday: Int,
    val isExpected: Boolean,
    val expectedArrival: String?,
    val expectedDeparture: String?,
    val source: String,
    val isLateChange: Boolean,
    val overrideId: String?,
    val absenceId: String?,
    val closureDayId: String?,
    val isFrozen: Boolean,
    val isEditable: Boolean,
    val overrideVersion: Int,
    val overrideReason: String?,
    val closureName: String?,
    val absenceKind: String?,
    val templateAttends: Boolean?,
    val templateArrival: String?,
    val templateDeparture: String?,
    val changeDeadline: String?,
)

/** docs/openapi.yaml `WeekSchedule`. */
@Serializable
data class WeekScheduleDto(
    val childId: String,
    val weekStart: String,
    val timezone: String,
    val version: Int,
    val days: List<ScheduleDayDto>,
    val changeDeadlineHours: Int,
)

/** Addition: `GET /schedules/expected` row. */
@Serializable
data class ExpectedChildDto(
    val childId: String,
    val givenName: String,
    val familyName: String,
    val isExpected: Boolean,
    val expectedArrival: String?,
    val expectedDeparture: String?,
    val source: String,
    val absenceId: String?,
    val absenceKind: String?,
    val overrideId: String?,
    val overrideReason: String?,
    val isLateChange: Boolean,
    val closureDayId: String?,
)

/** `closureDayId` / `closureName` (additions): the date is a closure day for the group's location or the whole organization. */
@Serializable
data class ExpectedChildrenDto(
    val groupId: String,
    val date: String,
    val weekday: Int,
    val isWorkingDay: Boolean,
    val items: List<ExpectedChildDto>,
    val closureDayId: String?,
    val closureName: String?,
)

/** docs/openapi.yaml `ClosureDay` (+ `locationName` addition). */
@Serializable
data class ClosureDayDto(
    val id: String,
    val organizationId: String,
    val locationId: String?,
    val locationName: String?,
    val closureDate: String,
    val name: String,
    val createdAt: String,
)

@Serializable
data class ClosureDayPage(val items: List<ClosureDayDto>, val nextCursor: String?)

/** docs/openapi.yaml `ClosureDayCreate`. */
@Serializable
data class ClosureDayCreateRequest(val locationId: String? = null, val closureDate: String? = null, val name: String? = null)

/** docs/openapi.yaml `DayOverride`. */
@Serializable
data class DayOverrideDto(
    val id: String,
    val childId: String,
    val overrideDate: String,
    val attends: Boolean,
    val arrivalTime: String?,
    val departureTime: String?,
    val reason: String?,
    val isLateChange: Boolean,
    val createdByMembershipId: String,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
)

/** docs/openapi.yaml `DayOverrideSet`. */
@Serializable
data class DayOverrideSetRequest(val attends: Boolean? = null, val arrivalTime: String? = null, val departureTime: String? = null, val reason: String? = null)

/** Addition: one `schedule_change_log` row for the staff feed `GET /schedules/changes`. */
@Serializable
data class ScheduleChangeDto(
    val id: Long,
    val childId: String,
    val givenName: String,
    val familyName: String,
    val groupId: String?,
    val groupName: String?,
    val changeKind: String,
    val affectedFrom: String,
    val affectedTo: String?,
    val isLateChange: Boolean,
    val actorMembershipId: String?,
    val actorName: String?,
    val actorRole: String?,
    val before: JsonElement?,
    val after: JsonElement?,
    val occurredAt: String,
)

@Serializable
data class ScheduleChangePage(val date: String, val items: List<ScheduleChangeDto>, val nextCursor: String?)
