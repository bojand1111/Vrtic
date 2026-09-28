package com.vrticconnect.modules.schedules

import kotlinx.serialization.Serializable

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

/** docs/openapi.yaml `ScheduleDay` (no overrides/closures yet: those fields stay null/false). */
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
)

@Serializable
data class ExpectedChildrenDto(
    val groupId: String,
    val date: String,
    val weekday: Int,
    val isWorkingDay: Boolean,
    val items: List<ExpectedChildDto>,
)
