package com.vrticconnect.modules.messaging

import kotlinx.serialization.Serializable

/** docs/openapi.yaml `ConversationParticipant`. */
@Serializable
data class ConversationParticipantDto(
    val membershipId: String,
    val givenName: String,
    val familyName: String,
    val participantRole: String,
    val joinedAt: String,
    val leftAt: String?,
)

/** docs/openapi.yaml `Conversation` plus `childFamilyName` (addition, for staff lists). */
@Serializable
data class ConversationDto(
    val id: String,
    val organizationId: String,
    val kind: String,
    val childId: String,
    val childGivenName: String,
    val childFamilyName: String,
    val groupId: String?,
    val subject: String?,
    val participants: List<ConversationParticipantDto>,
    val lastMessageAt: String?,
    val lastMessagePreview: String?,
    val unreadCount: Int,
    val lastReadMessageId: String?,
    val closedAt: String?,
    val createdAt: String,
)

@Serializable
data class ConversationPage(val items: List<ConversationDto>, val nextCursor: String?)

/** docs/openapi.yaml `MessageCreate`. */
@Serializable
data class MessageCreateRequest(val clientMessageId: String? = null, val body: String? = null)

/** docs/openapi.yaml `ConversationCreate`. Participants are never taken from the client. */
@Serializable
data class ConversationCreateRequest(
    val kind: String? = null,
    val childId: String? = null,
    val subject: String? = null,
    val initialMessage: MessageCreateRequest? = null,
)

/** docs/openapi.yaml `Message`. */
@Serializable
data class MessageDto(
    val id: String,
    val conversationId: String,
    val senderMembershipId: String,
    val clientMessageId: String,
    val body: String,
    val createdAt: String,
    val editedAt: String?,
    val deletedAt: String?,
    val isOwn: Boolean,
)

@Serializable
data class MessagePage(val items: List<MessageDto>, val nextCursor: String?)

@Serializable
data class ReadPositionUpdate(val lastReadMessageId: String? = null)

/** docs/openapi.yaml `ReadPosition`. */
@Serializable
data class ReadPositionDto(val conversationId: String, val lastReadMessageId: String?, val lastReadAt: String?, val unreadCount: Int)
