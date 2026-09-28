package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.Database
import com.vrticconnect.http.RateLimiter
import com.vrticconnect.modules.auth.AccountService
import com.vrticconnect.modules.auth.Argon2idPasswordHasher
import com.vrticconnect.modules.auth.DatabaseSessionResolver
import com.vrticconnect.modules.auth.CsrfService
import com.vrticconnect.modules.auth.LoginService
import com.vrticconnect.modules.auth.NotImplementedSessionResolver
import com.vrticconnect.modules.auth.ReauthService
import com.vrticconnect.modules.auth.SessionResolver
import com.vrticconnect.modules.auth.SessionManagementService
import com.vrticconnect.modules.auth.SessionService
import com.vrticconnect.modules.auth.authRoutes
import com.vrticconnect.modules.health.ReadinessProbe
import com.vrticconnect.modules.health.healthRoutes
import com.vrticconnect.modules.mail.LoggingMailSender
import com.vrticconnect.modules.mail.MailSender
import com.vrticconnect.modules.mail.UnconfiguredMailSender
import com.vrticconnect.modules.me.MeService
import com.vrticconnect.modules.organizations.OrganizationSettingsService
import com.vrticconnect.modules.platform.PlatformOrganizationService
import com.vrticconnect.modules.tenant.DatabaseMembershipResolver
import com.vrticconnect.modules.tenant.MembershipResolver
import com.vrticconnect.modules.tenant.MembershipService
import com.vrticconnect.modules.tenant.tenantRoutes
import com.vrticconnect.plugins.configureCallId
import com.vrticconnect.plugins.configureDefaultHeaders
import com.vrticconnect.plugins.configureLogging
import com.vrticconnect.plugins.configureSerialization
import com.vrticconnect.plugins.configureStatusPages
import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * Explicit dependency container (constructor injection, no DI framework in P0).
 * Tests construct it with fakes; `Main.serve` constructs it with real infrastructure.
 */
class AppDependencies(
    val config: AppConfig,
    val database: Database?,
    val readiness: ReadinessProbe,
    /** Resolves an access token into an authenticated principal. */
    val sessionResolver: SessionResolver = database?.let(::DatabaseSessionResolver) ?: NotImplementedSessionResolver,
    /** Validates web cookie mutations against the exact configured origin. */
    val csrfService: CsrfService? = database?.let { CsrfService(it, config) },
    /** E02-B13: in-memory limiter shared by every auth flow of this process (durable counters live in login_attempts). */
    val rateLimiter: RateLimiter = RateLimiter(),
    /** Issues sessions after validating credentials; omitted by DB-free route tests. */
    val loginService: LoginService? = database?.let { LoginService(it, Argon2idPasswordHasher(config.argon2), config.isDev, csrfService, rateLimiter, config.trustProxyHeaders) },
    /** Builds the SPA's current-user bootstrap response from the authenticated DB context. */
    val sessionService: SessionService? = database?.let(::SessionService),
    /** E02-B08: device list, remote revocation, logout from all devices. */
    val sessionManagement: SessionManagementService? = database?.let(::SessionManagementService),
    /** E02-B20: DEV logs links; every other environment must configure a real provider (EPIC 18). */
    val mailSender: MailSender = if (config.isDev) LoggingMailSender() else UnconfiguredMailSender(),
    /** E02-B04/B05/B09: invitations, e-mail verification, password reset. */
    val accountService: AccountService? = database?.let { db ->
        loginService?.let { AccountService(db, Argon2idPasswordHasher(config.argon2), it, mailSender, config, rateLimiter) }
    },
    /** Step 2 of the tenant pipeline: ACTIVE membership lookup before any tenant context is set. */
    val membershipResolver: MembershipResolver? = database?.let(::DatabaseMembershipResolver),
    /** `GET /me/memberships` (tenant switcher). */
    val membershipService: MembershipService? = database?.let(::MembershipService),
    /** E02-B15 examples of the authorization layer: tenant settings and the platform organization list. */
    val settingsService: OrganizationSettingsService? = database?.let(::OrganizationSettingsService),
    val platformOrganizations: PlatformOrganizationService? = database?.let(::PlatformOrganizationService),
    /** E02-B10: re-authentication for sensitive actions. */
    val reauthService: ReauthService? = database?.let { ReauthService(it, Argon2idPasswordHasher(config.argon2), rateLimiter) },
    /** E02-B16: `/me` profile, locale and password change. */
    val meService: MeService? = database?.let { MeService(it, Argon2idPasswordHasher(config.argon2), rateLimiter) },
)

fun Application.module(deps: AppDependencies) {
    configureCallId()
    configureLogging()
    configureDefaultHeaders()
    configureSerialization()
    configureStatusPages()

    routing {
        healthRoutes(deps.readiness)
        route("/api/v1") {
            authRoutes(deps.sessionResolver, deps.loginService, deps.sessionService, deps.csrfService, deps.sessionManagement, deps.config.isDev, deps.accountService, deps.reauthService)
            tenantRoutes(deps.sessionResolver, deps.membershipResolver, deps.membershipService, deps.database, deps.accountService, deps.csrfService, deps.settingsService, deps.platformOrganizations, deps.meService)
        }
    }
}
