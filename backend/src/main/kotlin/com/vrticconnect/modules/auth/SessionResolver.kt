package com.vrticconnect.modules.auth

import com.vrticconnect.authz.Role
import com.vrticconnect.http.ProblemException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.RoutingContext
import java.util.UUID

/** Identity established for one request (from an access token or a web session cookie). */
data class AuthenticatedUser(
    val userId: UUID,
    val sessionId: UUID,
    val isPlatformAdmin: Boolean,
    val mfaVerified: Boolean,
)

/** The caller's membership inside the organization addressed by the request path. */
data class MembershipContext(
    val organizationId: UUID,
    val membershipId: UUID,
    val role: Role,
    val extraPermissions: Set<String>,
)

/**
 * Resolves credentials carried by the request into an [AuthenticatedUser].
 * EPIC 02 provides the real implementation (opaque access token lookup in app.access_tokens with
 * expiry + session revocation check, cookie mode for web with CSRF/Origin verification).
 */
fun interface SessionResolver {
    suspend fun resolve(call: ApplicationCall): AuthenticatedUser?
}

/** Skeleton default: nothing can authenticate. Protected routes therefore always answer 401. */
object NotImplementedSessionResolver : SessionResolver {
    override suspend fun resolve(call: ApplicationCall): AuthenticatedUser? = null
}

/**
 * Helper for protected handlers: obtain the principal or fail with 401 (problem+json).
 * Membership/permission checks (403 vs 404 semantics) are layered on top in EPIC 02/03.
 */
suspend fun RoutingContext.requireUser(resolver: SessionResolver): AuthenticatedUser =
    resolver.resolve(call) ?: throw ProblemException.unauthenticated()

/** Extracts a bearer token without logging it; cookie mode is added in EPIC 02. */
fun ApplicationCall.bearerToken(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
