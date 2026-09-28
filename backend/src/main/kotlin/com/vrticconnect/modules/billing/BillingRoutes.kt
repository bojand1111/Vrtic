package com.vrticconnect.modules.billing

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.http.ProblemException
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.tenantRead
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Read-only billing view of the organization (online payments are P2; no payment provider):
 *  - `GET /subscription`: current plan, status and period. BILLING_VIEW (OWNER) or BILLING_MANAGE
 *    (OWNER, or ADMIN with the granted extra permission); 404 when the organization has no current subscription.
 *  - `GET /feature-flags`: effective flags for every ACTIVE member (clients hide features; the server
 *    still authorizes every action independently).
 */
fun Route.billingRoutes(api: TenantApi) {
    get("/subscription") {
        val principal = tenantRead(api)
        Authorize.requireAny(principal, Permission.BILLING_VIEW, Permission.BILLING_MANAGE)
        call.respond(api.tx(principal) { c -> BillingQueries.currentSubscription(c) } ?: throw ProblemException.notFound())
    }
    get("/feature-flags") {
        val principal = tenantRead(api)
        call.respond(api.tx(principal) { c -> BillingQueries.effectiveFlags(c, principal.membership.organizationId) })
    }
}
