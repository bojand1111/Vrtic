package com.vrticconnect.modules.announcements

import kotlinx.serialization.Serializable

/** docs/openapi.yaml AnnouncementAudience (request and response). */
@Serializable
data class AudienceDto(
    val audienceType: String? = null,
    val locationId: String? = null,
    val groupId: String? = null,
    val membershipId: String? = null,
)

/** docs/openapi.yaml AnnouncementCreate. Attachments are not supported yet (files module missing). */
@Serializable
data class AnnouncementCreate(
    val title: String? = null,
    val body: String? = null,
    val audiences: List<AudienceDto>? = null,
    val publishAt: String? = null,
    val expiresAt: String? = null,
)

@Serializable
data class AnnouncementPublishRequest(val sendPush: Boolean? = null)

/** docs/openapi.yaml Announcement. recipientsCount/readCount for managers, readAt for recipients. */
@Serializable
data class AnnouncementDto(
    val id: String,
    val organizationId: String,
    val title: String,
    val body: String,
    val status: String,
    val publishAt: String?,
    val expiresAt: String?,
    val publishedAt: String?,
    val audiences: List<AudienceDto>,
    val attachments: List<String>,
    val createdByMembershipId: String,
    val recipientsCount: Int?,
    val readCount: Int?,
    val readAt: String?,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class AnnouncementPage(val items: List<AnnouncementDto>, val nextCursor: String?)

@Serializable
data class AnnouncementRecipientDto(
    val membershipId: String,
    val givenName: String,
    val familyName: String,
    val role: String,
    val snapshotAt: String,
    val readAt: String?,
    val stillEntitled: Boolean,
)

@Serializable
data class AnnouncementRecipientPage(val items: List<AnnouncementRecipientDto>, val nextCursor: String?)
