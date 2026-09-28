package com.vrticconnect.modules.calendar

import kotlinx.serialization.Serializable

/** docs/openapi.yaml CalendarEventCreate; also the merged state of a PATCH before validation. */
@Serializable
data class CalendarEventInput(
    val kind: String? = null,
    val title: String? = null,
    val description: String? = null,
    val locationId: String? = null,
    val groupId: String? = null,
    val allDay: Boolean? = null,
    val startsOn: String? = null,
    val endsOn: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val timezone: String? = null,
    val requiresConsentPolicyId: String? = null,
)

/** docs/openapi.yaml CalendarEvent. */
@Serializable
data class CalendarEventDto(
    val id: String,
    val organizationId: String,
    val kind: String,
    val title: String,
    val description: String?,
    val locationId: String?,
    val groupId: String?,
    val allDay: Boolean,
    val startsOn: String,
    val endsOn: String,
    val startsAt: String?,
    val endsAt: String?,
    val timezone: String,
    val requiresConsentPolicyId: String?,
    val createdByMembershipId: String,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class CalendarEventPage(val items: List<CalendarEventDto>, val nextCursor: String?)
