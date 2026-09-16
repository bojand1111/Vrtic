package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.Database
import com.vrticconnect.modules.auth.Argon2idPasswordHasher
import com.vrticconnect.modules.auth.DatabaseSessionResolver
import com.vrticconnect.modules.auth.CsrfService
import com.vrticconnect.modules.auth.LoginService
import com.vrticconnect.modules.auth.NotImplementedSessionResolver
import com.vrticconnect.modules.auth.SessionResolver
import com.vrticconnect.modules.auth.SessionService
import com.vrticconnect.modules.auth.authRoutes
import com.vrticconnect.modules.health.ReadinessProbe
import com.vrticconnect.modules.health.healthRoutes
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
    /** Issues sessions after validating credentials; omitted by DB-free route tests. */
    val loginService: LoginService? = database?.let { LoginService(it, Argon2idPasswordHasher(config.argon2), config.isDev, csrfService) },
    /** Builds the SPA's current-user bootstrap response from the authenticated DB context. */
    val sessionService: SessionService? = database?.let(::SessionService),
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
            authRoutes(deps.sessionResolver, deps.loginService, deps.sessionService, deps.csrfService)
            tenantRoutes(deps.sessionResolver)
        }
    }
}
