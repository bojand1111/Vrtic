package com.vrticconnect.modules.platform

import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.db.instant
import com.vrticconnect.db.instantOrNull
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.update
import com.vrticconnect.http.PageRequest
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.conflict
import com.vrticconnect.http.invalidQuery
import com.vrticconnect.http.validate
import com.vrticconnect.modules.audit.Audit
import com.vrticconnect.modules.auth.AuthenticatedUser
import com.vrticconnect.modules.billing.BillingQueries
import com.vrticconnect.modules.billing.EffectiveFeatureFlags
import com.vrticconnect.modules.billing.SubscriptionDto
import io.ktor.http.Parameters
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Plans, subscriptions and feature flags for platform admins. Plans and flags are global tables
 * (platform_mode policies); subscriptions and overrides are tenant rows, so after the organization
 * is checked in `DbContext.Platform` the transaction switches to `DbContext.Tenant(org, admin)`.
 * Every change is audited (PLATFORM_*) and subscription changes are appended to subscription_events.
 */
class PlatformBillingService(private val database: Database) {

    suspend fun plans(admin: AuthenticatedUser, query: Parameters): PlanPage {
        val page = PageRequest.from(query)
        val code = query["code"]?.also { if (it !in PLAN_CODES) throw invalidQuery("code", PLAN_CODES.joinToString("|")) }
        val activeOnly = when (query["activeOnly"]) {
            null, "true" -> true
            "false" -> false
            else -> throw invalidQuery("activeOnly", "true|false")
        }
        val items = database.transaction(DbContext.Platform(admin.userId)) { c ->
            c.queryList(
                "SELECT ${BillingQueries.PLAN_COLUMNS} FROM app.plans p WHERE (?::text IS NULL OR p.code = ?::text) AND (NOT ? OR p.is_active) " +
                    "ORDER BY p.code, p.version DESC LIMIT ?",
                code, code, activeOnly, page.limit,
            ) { BillingQueries.mapPlan(it) }
        }
        return PlanPage(items, null)
    }

    suspend fun subscription(admin: AuthenticatedUser, organizationId: UUID): SubscriptionDto =
        inTenant(admin, organizationId) { c -> BillingQueries.currentSubscription(c) } ?: throw ProblemException.notFound()

    suspend fun createSubscription(admin: AuthenticatedUser, organizationId: UUID, body: SubscriptionCreate, requestId: String?): SubscriptionDto {
        val input = validate {
            val planId = uuid(body.planId, "planId")
            val status = oneOf(body.status, "status", setOf("TRIAL", "ACTIVE"))
            val start = instant(this, body.currentPeriodStart, "currentPeriodStart", required = true)
            val end = instant(this, body.currentPeriodEnd, "currentPeriodEnd", required = true)
            val trialEnds = instant(this, body.trialEndsAt, "trialEndsAt", required = status == "TRIAL")
            if (start != null && end != null) require(end.isAfter(start), "currentPeriodEnd", "MUST_BE_AFTER_START", "must be after currentPeriodStart")
            val provider = oneOf(body.provider ?: "MANUAL", "provider", setOf("MANUAL", "STRIPE", "LOCAL_INVOICE"))
            text(body.providerRef, "providerRef", max = 200, required = false)
            NewSubscription(planId ?: UUID(0, 0), status.orEmpty(), start ?: Instant.EPOCH, end ?: Instant.EPOCH, trialEnds, provider ?: "MANUAL", body.providerRef?.trim()?.takeIf { it.isNotEmpty() })
        }
        return inTenant(admin, organizationId) { c ->
            requireActivePlan(c, input.planId)
            if (BillingQueries.currentSubscription(c) != null) throw conflict("SUBSCRIPTION_EXISTS")
            val id = UUID.randomUUID()
            c.update(
                "INSERT INTO app.subscriptions (id, organization_id, plan_id, status, trial_ends_at, current_period_start, current_period_end, provider, provider_ref) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, organizationId, input.planId, input.status, input.trialEndsAt, input.start, input.end, input.provider, input.providerRef,
            )
            event(c, organizationId, id, "CREATED", mapOf("status" to input.status, "planId" to input.planId.toString()))
            Audit.record(c, "PLATFORM_SUBSCRIPTION_CREATED", "SUBSCRIPTION", id, actorUserId = admin.userId, organizationId = organizationId, requestId = requestId)
            BillingQueries.subscriptionById(c, id)!!
        }
    }

    /**
     * Manual plan change, status transition, cancellation flag or period change. A plan change applies
     * immediately (`applyNow` is accepted; scheduling for the next period is not implemented). CANCELLED
     * is terminal: a new subscription is created with POST.
     */
    suspend fun updateSubscription(admin: AuthenticatedUser, organizationId: UUID, body: SubscriptionUpdate, requestId: String?): SubscriptionDto {
        val input = validate {
            val r = body.reason?.trim()
            text(r, "reason", max = 500)
            if (r != null && r.isNotEmpty()) require(r.length >= 3, "reason", "TOO_SHORT", "min 3 characters")
            val planId = uuid(body.planId, "planId", required = false)
            val status = oneOf(body.status, "status", setOf("ACTIVE", "PAST_DUE", "CANCELLED"), required = false)
            val periodEnd = instant(this, body.currentPeriodEnd, "currentPeriodEnd", required = false)
            require(planId != null || status != null || body.cancelAtPeriodEnd != null || periodEnd != null, "planId", "NOTHING_TO_CHANGE", "planId, status, cancelAtPeriodEnd or currentPeriodEnd")
            Change(r.orEmpty(), planId, status, body.cancelAtPeriodEnd, periodEnd)
        }
        return inTenant(admin, organizationId) { c ->
            val current = BillingQueries.currentSubscription(c) ?: throw ProblemException.notFound()
            val id = UUID.fromString(current.id)
            if (input.planId != null) requireActivePlan(c, input.planId)
            if (input.periodEnd != null) {
                validate { require(input.periodEnd.isAfter(Instant.parse(current.currentPeriodStart)), "currentPeriodEnd", "MUST_BE_AFTER_START", "must be after currentPeriodStart") }
            }
            val changes = linkedMapOf<String, String>()
            if (input.planId != null && input.planId.toString() != current.plan.id) {
                c.update("UPDATE app.subscriptions SET plan_id = ? WHERE id = ?", input.planId, id); changes["planId"] = input.planId.toString()
            }
            if (input.status != null && input.status != current.status) {
                c.update(
                    "UPDATE app.subscriptions SET status = ?, cancelled_at = CASE WHEN ? = 'CANCELLED' THEN now() ELSE cancelled_at END WHERE id = ?",
                    input.status, input.status, id,
                )
                changes["status"] = input.status
            }
            if (input.cancelAtPeriodEnd != null && input.cancelAtPeriodEnd != current.cancelAtPeriodEnd) {
                c.update("UPDATE app.subscriptions SET cancel_at_period_end = ? WHERE id = ?", input.cancelAtPeriodEnd, id)
                changes["cancelAtPeriodEnd"] = input.cancelAtPeriodEnd.toString()
            }
            if (input.periodEnd != null) {
                c.update("UPDATE app.subscriptions SET current_period_end = ? WHERE id = ?", input.periodEnd, id); changes["currentPeriodEnd"] = input.periodEnd.toString()
            }
            if (changes.isNotEmpty()) {
                event(c, organizationId, id, "UPDATED", changes)
                Audit.record(
                    c, "PLATFORM_SUBSCRIPTION_UPDATED", "SUBSCRIPTION", id, actorUserId = admin.userId, organizationId = organizationId,
                    requestId = requestId, metadata = mapOf("reason" to input.reason),
                )
            }
            BillingQueries.subscriptionById(c, id)!!
        }
    }

    suspend fun featureFlags(admin: AuthenticatedUser): FeatureFlagList =
        database.transaction(DbContext.Platform(admin.userId)) { c ->
            FeatureFlagList(
                c.queryList("SELECT key, description, default_enabled, kill_switch, updated_at FROM app.feature_flags ORDER BY key") { rs ->
                    FeatureFlagDto(rs.getString("key"), rs.getString("description"), rs.getBoolean("default_enabled"), rs.getBoolean("kill_switch"), rs.instant("updated_at").toString())
                },
            )
        }

    /** Extension of the contract: the effective flags of one tenant as the platform sees them (same resolution as the tenant endpoint). */
    suspend fun organizationFlags(admin: AuthenticatedUser, organizationId: UUID): EffectiveFeatureFlags =
        inTenant(admin, organizationId) { c -> BillingQueries.effectiveFlags(c, organizationId) }

    suspend fun setOverride(admin: AuthenticatedUser, organizationId: UUID, flagKey: String, body: FeatureOverrideUpsert, requestId: String?): FeatureOverrideDto {
        val input = validate {
            require(body.enabled != null, "enabled", "REQUIRED", "enabled is required")
            val r = body.reason?.trim()
            text(r, "reason", max = 500)
            if (r != null && r.isNotEmpty()) require(r.length >= 3, "reason", "TOO_SHORT", "min 3 characters")
            val expires = instant(this, body.expiresAt, "expiresAt", required = false)
            if (expires != null) require(expires.isAfter(Instant.now()), "expiresAt", "MUST_BE_FUTURE", "must be in the future")
            Triple(body.enabled == true, r.orEmpty(), expires)
        }
        return inTenant(admin, organizationId) { c ->
            requireFlag(c, flagKey)
            c.update(
                "INSERT INTO app.organization_feature_overrides (organization_id, flag_key, enabled, reason, set_by, expires_at) VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (organization_id, flag_key) DO UPDATE SET enabled = EXCLUDED.enabled, reason = EXCLUDED.reason, set_by = EXCLUDED.set_by, " +
                    "expires_at = EXCLUDED.expires_at, created_at = now()",
                organizationId, flagKey, input.first, input.second, admin.userId, input.third,
            )
            Audit.record(
                c, "PLATFORM_FEATURE_OVERRIDE_SET", "ORGANIZATION", organizationId, actorUserId = admin.userId, organizationId = organizationId,
                requestId = requestId, metadata = mapOf("reason" to "$flagKey=${input.first}: ${input.second}"),
            )
            readOverride(c, organizationId, flagKey)!!
        }
    }

    /**
     * `DELETE .../feature-overrides/{flagKey}`: app_runtime has no DELETE grant on overrides, so the
     * override is ended by setting `expires_at = now()`; an expired override no longer applies.
     */
    suspend fun removeOverride(admin: AuthenticatedUser, organizationId: UUID, flagKey: String, requestId: String?) {
        inTenant(admin, organizationId) { c ->
            requireFlag(c, flagKey)
            val ended = c.update(
                "UPDATE app.organization_feature_overrides SET expires_at = now() WHERE organization_id = ? AND flag_key = ? AND (expires_at IS NULL OR expires_at > now())",
                organizationId, flagKey,
            )
            if (ended == 0) throw ProblemException.notFound()
            Audit.record(
                c, "PLATFORM_FEATURE_OVERRIDE_REMOVED", "ORGANIZATION", organizationId, actorUserId = admin.userId, organizationId = organizationId,
                requestId = requestId, metadata = mapOf("reason" to flagKey),
            )
        }
    }

    /** Organization must exist (checked in platform mode, 404 otherwise); then [block] runs in its tenant context. */
    private suspend fun <T> inTenant(admin: AuthenticatedUser, organizationId: UUID, block: (Connection) -> T): T =
        database.transaction(DbContext.Platform(admin.userId)) { c ->
            PlatformAdminService.readOrganization(c, organizationId) ?: throw ProblemException.notFound()
            Database.applyContext(c, DbContext.Tenant(organizationId, admin.userId))
            block(c)
        }

    private fun requireActivePlan(c: Connection, planId: UUID) {
        val active = c.queryOne("SELECT is_active FROM app.plans WHERE id = ?", planId) { it.getBoolean("is_active") }
        validate {
            require(active != null, "planId", "NOT_FOUND", "unknown plan")
            require(active != false, "planId", "INACTIVE", "plan version is not active")
        }
    }

    private fun requireFlag(c: Connection, key: String) {
        if (c.queryOne("SELECT 1 AS x FROM app.feature_flags WHERE key = ?", key) { true } != true) throw ProblemException.notFound()
    }

    private fun readOverride(c: Connection, organizationId: UUID, flagKey: String): FeatureOverrideDto? =
        c.queryOne("SELECT enabled, reason, expires_at, created_at FROM app.organization_feature_overrides WHERE organization_id = ? AND flag_key = ?", organizationId, flagKey) { rs ->
            FeatureOverrideDto(organizationId.toString(), flagKey, rs.getBoolean("enabled"), rs.getString("reason"), rs.instantOrNull("expires_at")?.toString(), rs.instant("created_at").toString())
        }

    private fun event(c: Connection, organizationId: UUID, subscriptionId: UUID, type: String, payload: Map<String, String>) {
        val json = payload.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
        c.update("INSERT INTO app.subscription_events (organization_id, subscription_id, event_type, payload) VALUES (?, ?, ?, ?::jsonb)", organizationId, subscriptionId, type, json)
    }

    private fun instant(v: com.vrticconnect.http.Validation, raw: String?, field: String, required: Boolean): Instant? {
        if (raw.isNullOrBlank()) {
            v.require(!required, field, "REQUIRED", "$field is required")
            return null
        }
        return runCatching { Instant.parse(raw) }.getOrElse { v.require(false, field, "INVALID_FORMAT", "RFC 3339 instant"); null }
    }

    private data class NewSubscription(
        val planId: UUID, val status: String, val start: Instant, val end: Instant, val trialEndsAt: Instant?, val provider: String, val providerRef: String?,
    )

    private data class Change(val reason: String, val planId: UUID?, val status: String?, val cancelAtPeriodEnd: Boolean?, val periodEnd: Instant?)

    private companion object {
        val PLAN_CODES = setOf("STARTER", "STANDARD", "PRO")
    }
}
