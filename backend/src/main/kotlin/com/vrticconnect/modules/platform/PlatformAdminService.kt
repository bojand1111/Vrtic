package com.vrticconnect.modules.platform

import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.db.instant
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.conflict
import com.vrticconnect.http.validate
import com.vrticconnect.modules.audit.Audit
import com.vrticconnect.modules.auth.AuthenticatedUser
import com.vrticconnect.modules.auth.InvitationIssuer
import com.vrticconnect.modules.auth.RecentAuthentication
import com.vrticconnect.modules.mail.MailSender
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Platform administration of tenants (SUPER_ADMIN after [com.vrticconnect.authz.Authorize.requirePlatformAdmin]).
 * Organization rows are read and written in `DbContext.Platform`. Tenant-owned rows (settings,
 * subscription, invitation) are protected by tenant RLS only, so the same transaction switches to
 * `DbContext.Tenant(newOrganization, admin)` for them; nothing else of the tenant is read.
 */
class PlatformAdminService(
    private val database: Database,
    private val mailSender: MailSender,
    private val webOrigin: String,
) {

    /** `POST /platform/organizations`: organization + default settings + TRIAL subscription + OWNER invitation e-mail. */
    suspend fun create(admin: AuthenticatedUser, body: OrganizationCreate, requestId: String?): Organization {
        val input = validate {
            val slug = body.slug?.trim()?.lowercase()
            text(slug, "slug", max = 63)
            if (slug != null && slug.isNotEmpty()) require(SLUG.matches(slug), "slug", "INVALID_FORMAT", "lowercase letters, digits and '-' (3..63)")
            val name = body.name?.trim()
            text(name, "name", max = 200)
            if (name != null && name.isNotEmpty()) require(name.length >= 2, "name", "TOO_SHORT", "min 2 characters")
            text(body.legalName, "legalName", max = 300, required = false)
            val country = body.countryCode?.trim()?.takeIf { it.isNotEmpty() } ?: "RS"
            require(COUNTRY.matches(country), "countryCode", "INVALID_FORMAT", "ISO 3166-1 alpha-2")
            val timezone = body.timezone?.trim()?.takeIf { it.isNotEmpty() } ?: "Europe/Belgrade"
            require(timezone.length <= 64 && runCatching { ZoneId.of(timezone) }.isSuccess, "timezone", "INVALID_VALUE", "IANA timezone")
            val locale = oneOf(body.defaultLocale?.takeIf { it.isNotBlank() } ?: "sr-Latn", "defaultLocale", LOCALES)
            val email = body.ownerEmail?.trim()?.lowercase()
            text(email, "ownerEmail", max = 254)
            if (email != null && email.isNotEmpty()) require(EMAIL.matches(email), "ownerEmail", "INVALID_FORMAT", "e-mail address")
            val planId = uuid(body.planId, "planId")
            NewOrganization(slug.orEmpty(), name.orEmpty(), body.legalName?.trim()?.takeIf { it.isNotEmpty() }, country, timezone, locale ?: "sr-Latn", email.orEmpty(), planId ?: UUID(0, 0))
        }
        val created = database.transaction(DbContext.Platform(admin.userId)) { c ->
            val plan = c.queryOne("SELECT id, is_active FROM app.plans WHERE id = ?", input.planId) { rs -> rs.getBoolean("is_active") }
            validate {
                require(plan != null, "planId", "NOT_FOUND", "unknown plan")
                require(plan != false, "planId", "INACTIVE", "plan version is not active")
            }
            if (c.queryOne("SELECT 1 AS x FROM app.organizations WHERE slug = ?", input.slug) { true } == true) throw conflict("SLUG_TAKEN")
            val orgId = UUID.randomUUID()
            c.prepareStatement(
                "INSERT INTO app.organizations (id, slug, name, legal_name, country_code, timezone, default_locale, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { st ->
                st.setObject(1, orgId); st.setString(2, input.slug); st.setString(3, input.name); st.setString(4, input.legalName)
                st.setString(5, input.countryCode); st.setString(6, input.timezone); st.setString(7, input.locale); st.setObject(8, admin.userId)
                st.executeUpdate()
            }
            Audit.record(c, "PLATFORM_ORGANIZATION_CREATED", "ORGANIZATION", orgId, actorUserId = admin.userId, organizationId = orgId, requestId = requestId)

            // Tenant rows of the new organization (tenant RLS): settings, trial subscription, owner invitation.
            Database.applyContext(c, DbContext.Tenant(orgId, admin.userId))
            c.update("INSERT INTO app.organization_settings (organization_id) VALUES (?)", orgId)
            val now = Instant.now()
            val trialEnd = now.plus(TRIAL_DAYS, ChronoUnit.DAYS)
            val subscriptionId = UUID.randomUUID()
            c.update(
                "INSERT INTO app.subscriptions (id, organization_id, plan_id, status, trial_ends_at, current_period_start, current_period_end) VALUES (?, ?, ?, 'TRIAL', ?, ?, ?)",
                subscriptionId, orgId, input.planId, trialEnd, now, trialEnd,
            )
            c.update(
                "INSERT INTO app.subscription_events (organization_id, subscription_id, event_type, payload) VALUES (?, ?, 'CREATED', jsonb_build_object('status', 'TRIAL', 'planId', ?::text))",
                orgId, subscriptionId, input.planId.toString(),
            )
            val invitation = InvitationIssuer.insert(c, orgId, input.ownerEmail, "OWNER", null, admin.userId)
            Audit.record(
                c, "INVITATION_CREATED", "INVITATION", invitation.id, actorUserId = admin.userId, organizationId = orgId, requestId = requestId,
                metadata = mapOf("role" to "OWNER", "invitationId" to invitation.id.toString()),
            )
            Database.applyContext(c, DbContext.Platform(admin.userId))
            val organization = readOrganization(c, orgId) ?: error("organization vanished inside its own transaction")
            organization to InvitationIssuer.mail(webOrigin, invitation.token, input.ownerEmail, input.locale, input.name, "OWNER")
        }
        // After commit, same as staff invitations: a failing mail provider surfaces as an error (the invitation can be re-sent).
        mailSender.send(created.second)
        return created.first
    }

    suspend fun get(admin: AuthenticatedUser, id: UUID): Organization =
        database.transaction(DbContext.Platform(admin.userId)) { c -> readOrganization(c, id) } ?: throw ProblemException.notFound()

    /** deactivate: ACTIVE -> SUSPENDED (requires recent authentication); reactivate: SUSPENDED -> ACTIVE. */
    suspend fun setStatus(admin: AuthenticatedUser, id: UUID, body: DeactivateRequest, suspend: Boolean, requestId: String?): Organization {
        val reason = validate {
            val r = body.reason?.trim()
            text(r, "reason", max = 500)
            if (r != null && r.isNotEmpty()) require(r.length >= 3, "reason", "TOO_SHORT", "min 3 characters")
            r.orEmpty()
        }
        return database.transaction(DbContext.Platform(admin.userId)) { c ->
            val current = readOrganization(c, id) ?: throw ProblemException.notFound()
            if (suspend) RecentAuthentication.require(c, admin)
            when {
                suspend && current.status == "SUSPENDED" -> throw conflict("ORGANIZATION_ALREADY_SUSPENDED")
                !suspend && current.status == "ACTIVE" -> throw conflict("ORGANIZATION_ALREADY_ACTIVE")
                current.status == "ARCHIVED" -> throw conflict("ORGANIZATION_ARCHIVED")
            }
            c.update("UPDATE app.organizations SET status = ? WHERE id = ?", if (suspend) "SUSPENDED" else "ACTIVE", id)
            Audit.record(
                c, if (suspend) "PLATFORM_ORGANIZATION_SUSPENDED" else "PLATFORM_ORGANIZATION_REACTIVATED", "ORGANIZATION", id,
                actorUserId = admin.userId, organizationId = id, requestId = requestId, metadata = mapOf("reason" to reason),
            )
            readOrganization(c, id)!!
        }
    }

    private data class NewOrganization(
        val slug: String, val name: String, val legalName: String?, val countryCode: String,
        val timezone: String, val locale: String, val ownerEmail: String, val planId: UUID,
    )

    companion object {
        const val TRIAL_DAYS = 30L
        private val SLUG = Regex("^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])?$")
        private val COUNTRY = Regex("^[A-Z]{2}$")
        private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        private val LOCALES = setOf("sr-Latn", "sr-Cyrl", "en")

        /** Requires a context that can see the organization (Platform, or Tenant of that organization). */
        fun readOrganization(c: Connection, id: UUID): Organization? =
            c.queryOne(
                "SELECT id, slug, name, legal_name, country_code, timezone, default_locale, status, created_at, updated_at FROM app.organizations WHERE id = ? AND deleted_at IS NULL",
                id,
            ) { mapOrganization(it) }

        fun mapOrganization(rs: ResultSet) = Organization(
            id = rs.uuid("id").toString(),
            slug = rs.getString("slug"),
            name = rs.getString("name"),
            legalName = rs.getString("legal_name"),
            countryCode = rs.getString("country_code"),
            timezone = rs.getString("timezone"),
            defaultLocale = rs.getString("default_locale"),
            status = rs.getString("status"),
            createdAt = rs.instant("created_at").toString(),
            updatedAt = rs.instant("updated_at").toString(),
        )
    }
}
