package com.vrticconnect.modules.children

import kotlinx.serialization.Serializable

/** docs/openapi.yaml `EnrollmentRef`. */
@Serializable
data class EnrollmentRef(
    val id: String,
    val groupId: String,
    val groupName: String,
    val locationId: String,
    val validFrom: String,
    val validTo: String?,
    val status: String,
)

/** docs/openapi.yaml `ChildSummary` (`hasCriticalHealthAlert` stays null: no health tables yet). */
@Serializable
data class ChildSummary(
    val id: String,
    val organizationId: String,
    val givenName: String,
    val familyName: String,
    val dateOfBirth: String,
    val photoFileId: String?,
    val status: String,
    val currentEnrollment: EnrollmentRef?,
    val hasCriticalHealthAlert: Boolean?,
    val version: Int,
)

@Serializable
data class ChildSummaryPage(val items: List<ChildSummary>, val nextCursor: String?)

/** docs/openapi.yaml `Child` (ChildSummary + detail fields). */
@Serializable
data class ChildDetail(
    val id: String,
    val organizationId: String,
    val givenName: String,
    val familyName: String,
    val dateOfBirth: String,
    val photoFileId: String?,
    val status: String,
    val currentEnrollment: EnrollmentRef?,
    val hasCriticalHealthAlert: Boolean?,
    val version: Int,
    val generalNotes: String?,
    val enrollments: List<EnrollmentRef>,
    val guardians: List<GuardianDto>,
    val pickupPersons: List<PickupPersonDto>,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class InitialEnrollment(val groupId: String? = null, val validFrom: String? = null, val validTo: String? = null)

/** docs/openapi.yaml `ChildCreate`. */
@Serializable
data class ChildCreateRequest(
    val givenName: String? = null,
    val familyName: String? = null,
    val dateOfBirth: String? = null,
    val generalNotes: String? = null,
    val initialEnrollment: InitialEnrollment? = null,
)

/** docs/openapi.yaml `Enrollment` plus `groupName` / `locationId` for display (addition). */
@Serializable
data class EnrollmentDto(
    val id: String,
    val organizationId: String,
    val childId: String,
    val groupId: String,
    val groupName: String,
    val locationId: String,
    val validFrom: String,
    val validTo: String?,
    val status: String,
    val endReason: String?,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class EnrollmentList(val items: List<EnrollmentDto>)

/**
 * docs/openapi.yaml `EnrollmentCreate`. `endCurrentEnrollment` (addition): moving a child between
 * groups in one transaction; the enrollment covering `validFrom` ends the day before.
 */
@Serializable
data class EnrollmentCreateRequest(
    val groupId: String? = null,
    val validFrom: String? = null,
    val validTo: String? = null,
    val endCurrentEnrollment: Boolean = false,
)

/** docs/openapi.yaml `EnrollmentEnd`. */
@Serializable
data class EnrollmentEndRequest(val validTo: String? = null, val endReason: String? = null)

/** docs/openapi.yaml `Guardian`. */
@Serializable
data class GuardianDto(
    val id: String,
    val organizationId: String,
    val childId: String,
    val membershipId: String,
    val userId: String,
    val givenName: String,
    val familyName: String,
    val relationship: String,
    val status: String,
    val isPrimary: Boolean,
    val canManageSchedule: Boolean,
    val canReportAbsence: Boolean,
    val canGiveConsent: Boolean,
    val canViewHealth: Boolean,
    val confirmedAt: String?,
    val revokedAt: String?,
    val createdAt: String,
)

/** Addition: staff links an existing PARENT membership to a child (CONFIRMED immediately). */
@Serializable
data class GuardianLinkRequest(
    val membershipId: String? = null,
    val relationship: String? = null,
    val isPrimary: Boolean = false,
    val canManageSchedule: Boolean = true,
    val canReportAbsence: Boolean = true,
    val canGiveConsent: Boolean = true,
    val canViewHealth: Boolean = true,
)

/** docs/openapi.yaml `RevokeRequest`. */
@Serializable
data class RevokeRequest(val reason: String? = null)

/** Addition: `GET /parents` row. */
@Serializable
data class ParentLink(
    val guardianId: String,
    val childId: String,
    val childGivenName: String,
    val childFamilyName: String,
    val relationship: String,
    val status: String,
    val isPrimary: Boolean,
)

@Serializable
data class ParentOverview(
    val membershipId: String,
    val userId: String,
    val displayName: String,
    val email: String,
    val status: String,
    val links: List<ParentLink>,
)

@Serializable
data class ParentOverviewList(val items: List<ParentOverview>)

/** docs/openapi.yaml `PickupPerson`. */
@Serializable
data class PickupPersonDto(
    val id: String,
    val childId: String,
    val fullName: String,
    val relationship: String?,
    val phone: String?,
    val note: String?,
    val validFrom: String?,
    val validTo: String?,
    val addedByMembershipId: String,
    val status: String,
    val revokedAt: String?,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class PickupPersonList(val items: List<PickupPersonDto>)

/** docs/openapi.yaml `PickupPersonCreate`. */
@Serializable
data class PickupPersonCreateRequest(
    val fullName: String? = null,
    val relationship: String? = null,
    val phone: String? = null,
    val note: String? = null,
    val validFrom: String? = null,
    val validTo: String? = null,
)

val GUARDIAN_RELATIONSHIPS = setOf("MOTHER", "FATHER", "LEGAL_GUARDIAN", "GRANDPARENT", "OTHER")
