package com.vrticconnect.modules.platform

import com.vrticconnect.authz.Authorize
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.pathUuid
import com.vrticconnect.modules.auth.AuthenticatedUser
import com.vrticconnect.modules.auth.CsrfService
import com.vrticconnect.modules.auth.SessionResolver
import com.vrticconnect.modules.auth.requireUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Platform administration (`/api/v1/platform/...`, SUPER_ADMIN with an MFA-verified session).
 * Pipeline: session (401) -> platform admin (404 for everyone else) -> MFA (403 MFA_REQUIRED) ->
 * CSRF/Origin for mutations -> service in DbContext.Platform. `GET /platform/organizations` stays in TenantRoutes.
 */
fun Route.platformAdminRoutes(
    sessionResolver: SessionResolver,
    csrfService: CsrfService?,
    admin: PlatformAdminService?,
    billing: PlatformBillingService?,
) {
    suspend fun RoutingContext.platformRead(): AuthenticatedUser = Authorize.requirePlatformAdmin(requireUser(sessionResolver))
    suspend fun RoutingContext.platformWrite(): AuthenticatedUser = platformRead().also { csrfService?.require(call, it) }
    fun adminService() = admin ?: throw ProblemException.notImplemented("Platform administration")
    fun billingService() = billing ?: throw ProblemException.notImplemented("Platform billing")

    route("/platform") {
        post("/organizations") {
            val user = platformWrite()
            call.respond(HttpStatusCode.Created, adminService().create(user, call.receive<OrganizationCreate>(), call.callId))
        }
        route("/organizations/{organizationId}") {
            get {
                val user = platformRead()
                call.respond(adminService().get(user, call.pathUuid("organizationId")))
            }
            post("/deactivate") {
                val user = platformWrite()
                call.respond(adminService().setStatus(user, call.pathUuid("organizationId"), call.receive<DeactivateRequest>(), suspend = true, requestId = call.callId))
            }
            post("/reactivate") {
                val user = platformWrite()
                call.respond(adminService().setStatus(user, call.pathUuid("organizationId"), call.receive<DeactivateRequest>(), suspend = false, requestId = call.callId))
            }
            route("/subscription") {
                get {
                    val user = platformRead()
                    call.respond(billingService().subscription(user, call.pathUuid("organizationId")))
                }
                post {
                    val user = platformWrite()
                    call.respond(HttpStatusCode.Created, billingService().createSubscription(user, call.pathUuid("organizationId"), call.receive<SubscriptionCreate>(), call.callId))
                }
                patch {
                    val user = platformWrite()
                    call.respond(billingService().updateSubscription(user, call.pathUuid("organizationId"), call.receive<SubscriptionUpdate>(), call.callId))
                }
            }
            get("/feature-flags") {
                val user = platformRead()
                call.respond(billingService().organizationFlags(user, call.pathUuid("organizationId")))
            }
            route("/feature-overrides/{flagKey}") {
                put {
                    val user = platformWrite()
                    val flagKey = call.parameters["flagKey"] ?: throw ProblemException.notFound()
                    call.respond(billingService().setOverride(user, call.pathUuid("organizationId"), flagKey, call.receive<FeatureOverrideUpsert>(), call.callId))
                }
                delete {
                    val user = platformWrite()
                    val flagKey = call.parameters["flagKey"] ?: throw ProblemException.notFound()
                    billingService().removeOverride(user, call.pathUuid("organizationId"), flagKey, call.callId)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
        get("/plans") {
            val user = platformRead()
            call.respond(billingService().plans(user, call.request.queryParameters))
        }
        get("/feature-flags") {
            val user = platformRead()
            call.respond(billingService().featureFlags(user))
        }
    }
}
