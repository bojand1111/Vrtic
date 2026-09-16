package com.vrticconnect.modules.tenant

import com.vrticconnect.http.ProblemException
import com.vrticconnect.modules.auth.SessionResolver
import com.vrticconnect.modules.auth.requireUser
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Tenant-scoped routes: /api/v1/organizations/{organizationId}/...
 * The pipeline for every tenant request (EPIC 02/03):
 *   1. resolve session  -> 401 if absent/expired/revoked
 *   2. resolve ACTIVE membership of the user in {organizationId} -> 404 if none (never reveal the org)
 *   3. open a DB transaction with DbContext.Tenant(organizationId, userId) -> RLS applies
 *   4. resource-level policy (group assignment, guardian link, extra permission)
 * Step 1 is implemented for bearer access tokens; membership and resource handling remain
 * intentionally unavailable until the following EPIC 02/03 tasks.
 */
fun Route.tenantRoutes(sessionResolver: SessionResolver) {
    route("/organizations/{organizationId}") {
        get("/ping") {
            requireUser(sessionResolver)
            // The resolver is live; the tenant pipeline itself is implemented in a later task.
            throw ProblemException.notImplemented("Tenant pipeline")
        }
    }
    route("/me") {
        get("/memberships") {
            requireUser(sessionResolver)
            throw ProblemException.notImplemented("Membership list")
        }
    }
    route("/platform") {
        get("/organizations") {
            requireUser(sessionResolver)
            throw ProblemException.notImplemented("Platform administration")
        }
    }
}
