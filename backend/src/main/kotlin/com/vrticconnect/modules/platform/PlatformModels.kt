package com.vrticconnect.modules.platform

import com.vrticconnect.modules.billing.PlanDto
import kotlinx.serialization.Serializable

/** docs/openapi.yaml `OrganizationCreate` (Idempotency-Key is accepted but not stored). */
@Serializable
data class OrganizationCreate(
    val slug: String? = null,
    val name: String? = null,
    val legalName: String? = null,
    val countryCode: String? = null,
    val timezone: String? = null,
    val defaultLocale: String? = null,
    val ownerEmail: String? = null,
    val planId: String? = null,
)

/** docs/openapi.yaml `DeactivateRequest` (also used by reactivate). */
@Serializable
data class DeactivateRequest(val reason: String? = null)

/** docs/openapi.yaml `PlanPage` (all rows up to `limit`; `nextCursor` is always null). */
@Serializable
data class PlanPage(val items: List<PlanDto>, val nextCursor: String?)

/** docs/openapi.yaml `SubscriptionCreate`. */
@Serializable
data class SubscriptionCreate(
    val planId: String? = null,
    val status: String? = null,
    val trialEndsAt: String? = null,
    val currentPeriodStart: String? = null,
    val currentPeriodEnd: String? = null,
    val provider: String? = null,
    val providerRef: String? = null,
)

/** docs/openapi.yaml `SubscriptionUpdate`. */
@Serializable
data class SubscriptionUpdate(
    val planId: String? = null,
    val applyNow: Boolean? = null,
    val status: String? = null,
    val cancelAtPeriodEnd: Boolean? = null,
    val currentPeriodEnd: String? = null,
    val reason: String? = null,
)

/** docs/openapi.yaml `FeatureFlag`. */
@Serializable
data class FeatureFlagDto(val key: String, val description: String, val defaultEnabled: Boolean, val killSwitch: Boolean, val updatedAt: String)

@Serializable
data class FeatureFlagList(val items: List<FeatureFlagDto>)

/** docs/openapi.yaml `FeatureOverrideUpsert`. */
@Serializable
data class FeatureOverrideUpsert(val enabled: Boolean? = null, val reason: String? = null, val expiresAt: String? = null)

/** docs/openapi.yaml `FeatureOverride`. */
@Serializable
data class FeatureOverrideDto(
    val organizationId: String,
    val flagKey: String,
    val enabled: Boolean,
    val reason: String? = null,
    val expiresAt: String? = null,
    val createdAt: String,
)
