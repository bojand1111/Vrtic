package com.vrticconnect.modules.billing

import com.vrticconnect.db.instant
import com.vrticconnect.db.instantOrNull
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.uuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** docs/openapi.yaml `Plan`. */
@Serializable
data class PlanDto(
    val id: String,
    val code: String,
    val version: Int,
    val name: String,
    val currency: String,
    val monthlyPriceMinor: Long,
    val entitlements: Map<String, Boolean>,
    val limits: Map<String, Int>,
    val isActive: Boolean,
    val createdAt: String,
)

/** docs/openapi.yaml `Subscription`. */
@Serializable
data class SubscriptionDto(
    val id: String,
    val organizationId: String,
    val plan: PlanDto,
    val status: String,
    val trialEndsAt: String? = null,
    val currentPeriodStart: String,
    val currentPeriodEnd: String,
    val cancelledAt: String? = null,
    val cancelAtPeriodEnd: Boolean,
    val provider: String,
    val createdAt: String,
    val updatedAt: String,
)

/** docs/openapi.yaml `EffectiveFeatureFlag`. */
@Serializable
data class EffectiveFeatureFlag(val key: String, val enabled: Boolean, val source: String)

/** docs/openapi.yaml `EffectiveFeatureFlags`. */
@Serializable
data class EffectiveFeatureFlags(val organizationId: String, val flags: List<EffectiveFeatureFlag>, val computedAt: String)

/**
 * Read side of plans, subscriptions and feature flags, shared by the tenant billing view and the
 * platform administration. Subscription rows are tenant data: callers must run these functions in a
 * `DbContext.Tenant` of the organization (RLS); plans and flags are global reference data.
 */
object BillingQueries {
    private val json = Json { ignoreUnknownKeys = true }

    const val PLAN_COLUMNS = "p.id, p.code, p.version, p.name, p.currency, p.monthly_price_minor, p.entitlements::text AS entitlements, p.limits::text AS limits, p.is_active, p.created_at"

    fun mapPlan(rs: ResultSet): PlanDto = PlanDto(
        id = rs.uuid("id").toString(),
        code = rs.getString("code"),
        version = rs.getInt("version"),
        name = rs.getString("name"),
        currency = rs.getString("currency"),
        monthlyPriceMinor = rs.getLong("monthly_price_minor"),
        entitlements = parseObject(rs.getString("entitlements")).mapNotNull { (k, v) -> v.jsonPrimitive.booleanOrNull?.let { k to it } }.toMap(),
        limits = parseObject(rs.getString("limits")).mapNotNull { (k, v) -> v.jsonPrimitive.intOrNull?.let { k to it } }.toMap(),
        isActive = rs.getBoolean("is_active"),
        createdAt = rs.instant("created_at").toString(),
    )

    private fun parseObject(text: String?): JsonObject =
        if (text.isNullOrBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject

    fun plan(c: Connection, id: UUID): PlanDto? = c.queryOne("SELECT $PLAN_COLUMNS FROM app.plans p WHERE p.id = ?", id) { mapPlan(it) }

    /** The current (TRIAL/ACTIVE/PAST_DUE) subscription of the organization in the tenant context, or null. */
    fun currentSubscription(c: Connection): SubscriptionDto? =
        c.queryOne(
            "SELECT s.id AS sid, s.organization_id, s.status, s.trial_ends_at, s.current_period_start, s.current_period_end, s.cancelled_at, " +
                "s.cancel_at_period_end, s.provider, s.created_at AS s_created, s.updated_at AS s_updated, $PLAN_COLUMNS " +
                "FROM app.subscriptions s JOIN app.plans p ON p.id = s.plan_id " +
                "WHERE s.organization_id = app.current_organization_id() AND s.status IN ('TRIAL','ACTIVE','PAST_DUE') " +
                "ORDER BY s.created_at DESC LIMIT 1",
        ) { rs -> mapSubscription(rs) }

    fun subscriptionById(c: Connection, id: UUID): SubscriptionDto? =
        c.queryOne(
            "SELECT s.id AS sid, s.organization_id, s.status, s.trial_ends_at, s.current_period_start, s.current_period_end, s.cancelled_at, " +
                "s.cancel_at_period_end, s.provider, s.created_at AS s_created, s.updated_at AS s_updated, $PLAN_COLUMNS " +
                "FROM app.subscriptions s JOIN app.plans p ON p.id = s.plan_id WHERE s.id = ?",
            id,
        ) { rs -> mapSubscription(rs) }

    private fun mapSubscription(rs: ResultSet) = SubscriptionDto(
        id = rs.uuid("sid").toString(),
        organizationId = rs.uuid("organization_id").toString(),
        plan = mapPlan(rs),
        status = rs.getString("status"),
        trialEndsAt = rs.instantOrNull("trial_ends_at")?.toString(),
        currentPeriodStart = rs.instant("current_period_start").toString(),
        currentPeriodEnd = rs.instant("current_period_end").toString(),
        cancelledAt = rs.instantOrNull("cancelled_at")?.toString(),
        cancelAtPeriodEnd = rs.getBoolean("cancel_at_period_end"),
        provider = rs.getString("provider"),
        createdAt = rs.instant("s_created").toString(),
        updatedAt = rs.instant("s_updated").toString(),
    )

    /**
     * Effective flags of the organization in the tenant context:
     * `NOT killSwitch AND (unexpired tenant override ?? current plan entitlement ?? default)`.
     */
    fun effectiveFlags(c: Connection, organizationId: UUID): EffectiveFeatureFlags {
        val entitlements = currentSubscription(c)?.plan?.entitlements.orEmpty()
        val overrides = c.queryList(
            "SELECT flag_key, enabled FROM app.organization_feature_overrides " +
                "WHERE organization_id = app.current_organization_id() AND (expires_at IS NULL OR expires_at > now())",
        ) { rs -> rs.getString("flag_key") to rs.getBoolean("enabled") }.toMap()
        val flags = c.queryList("SELECT key, default_enabled, kill_switch FROM app.feature_flags ORDER BY key") { rs ->
            val key = rs.getString("key")
            when {
                rs.getBoolean("kill_switch") -> EffectiveFeatureFlag(key, false, "KILL_SWITCH")
                key in overrides -> EffectiveFeatureFlag(key, overrides.getValue(key), "TENANT_OVERRIDE")
                key in entitlements -> EffectiveFeatureFlag(key, entitlements.getValue(key), "PLAN_ENTITLEMENT")
                else -> EffectiveFeatureFlag(key, rs.getBoolean("default_enabled"), "DEFAULT")
            }
        }
        return EffectiveFeatureFlags(organizationId.toString(), flags, Instant.now().toString())
    }
}
