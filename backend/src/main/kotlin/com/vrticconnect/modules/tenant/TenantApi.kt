package com.vrticconnect.modules.tenant

import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.http.ProblemException
import com.vrticconnect.modules.audit.Audit
import com.vrticconnect.modules.auth.CsrfService
import com.vrticconnect.modules.auth.SessionResolver
import io.ktor.server.plugins.callid.callId
import io.ktor.server.routing.RoutingContext
import java.sql.Connection
import java.util.UUID

/**
 * What every business module (E03+) needs from the tenant pipeline, bundled so module route files
 * (`fun Route.xxxRoutes(api: TenantApi)`) stay independent of AppDependencies.
 *   read:  session (401) -> ACTIVE membership (404)
 *   write: the same + CSRF/Origin check for cookie sessions (403)
 *   tx:    DbContext.Tenant transaction, so RLS scopes every statement to the organization
 */
class TenantApi(
    val sessionResolver: SessionResolver,
    val membershipResolver: MembershipResolver?,
    private val databaseOrNull: Database?,
    val csrfService: CsrfService?,
) {
    val database: Database get() = databaseOrNull ?: throw ProblemException.notImplemented("Tenant pipeline")

    suspend fun <T> tx(principal: TenantPrincipal, block: (Connection) -> T): T =
        database.transaction(DbContext.Tenant(principal.membership.organizationId, principal.user.userId), block)
}

/** Steps 1-2 of the pipeline for a read. */
suspend fun RoutingContext.tenantRead(api: TenantApi): TenantPrincipal = requireTenant(api.sessionResolver, api.membershipResolver)

/** Steps 1-2 plus the CSRF/Origin check that every mutation needs in web cookie mode. */
suspend fun RoutingContext.tenantWrite(api: TenantApi): TenantPrincipal {
    val principal = requireTenant(api.sessionResolver, api.membershipResolver)
    api.csrfService?.require(call, principal.user)
    return principal
}

val RoutingContext.requestId: String? get() = call.callId

/** Audit entry inside the caller's tenant transaction (docs/SECURITY.md 7). */
fun TenantPrincipal.audit(c: Connection, action: String, entityType: String, entityId: UUID?, requestId: String?, metadata: Map<String, String> = emptyMap()) =
    Audit.record(
        c, action, entityType, entityId, actorUserId = user.userId, organizationId = membership.organizationId,
        actorMembershipId = membership.membershipId, requestId = requestId, metadata = metadata,
    )
