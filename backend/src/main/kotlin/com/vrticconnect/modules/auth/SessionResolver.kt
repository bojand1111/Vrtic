package com.vrticconnect.modules.auth

import com.vrticconnect.authz.Role
import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
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
 * Opaque access tokens can arrive as Bearer credentials or the web access cookie. Mutating
 * cookie-backed routes apply CSRF/Origin verification before changing state.
 */
fun interface SessionResolver {
    suspend fun resolve(call: ApplicationCall): AuthenticatedUser?
}

/**
 * Resolves an opaque bearer access token against PostgreSQL on every request.
 *
 * The lookup deliberately runs in [DbContext.Auth], which enables the auth-only RLS policies
 * for access_tokens, sessions, users and platform_admins. Expiry and revocation are checked in
 * SQL so a revoked token or session takes effect immediately and no token state is cached locally.
 */
class DatabaseSessionResolver(private val database: Database) : SessionResolver {
    override suspend fun resolve(call: ApplicationCall): AuthenticatedUser? {
        val token = (call.bearerToken() ?: call.request.cookies["vc_access"])
            ?.takeIf { Tokens.isAccessToken(it) }
            ?: return null

        return database.transaction(DbContext.Auth()) { connection ->
            connection.prepareStatement(LOOKUP_SQL).use { statement ->
                statement.setBytes(1, Tokens.sha256(token))
                statement.executeQuery().use { result ->
                    if (!result.next()) return@transaction null
                    AuthenticatedUser(
                        userId = result.getObject("user_id", UUID::class.java),
                        sessionId = result.getObject("session_id", UUID::class.java),
                        isPlatformAdmin = result.getBoolean("is_platform_admin"),
                        mfaVerified = result.getTimestamp("mfa_verified_at") != null,
                    )
                }
            }
        }
    }

    private companion object {
        const val LOOKUP_SQL = """
            SELECT
                at.session_id,
                s.user_id,
                s.mfa_verified_at,
                EXISTS (
                    SELECT 1
                    FROM app.platform_admins pa
                    WHERE pa.user_id = s.user_id
                      AND pa.revoked_at IS NULL
                ) AS is_platform_admin
            FROM app.access_tokens at
            JOIN app.sessions s ON s.id = at.session_id
            JOIN app.users u ON u.id = s.user_id
            WHERE at.token_hash = ?
              AND at.expires_at > CURRENT_TIMESTAMP
              AND at.revoked_at IS NULL
              AND s.revoked_at IS NULL
              AND s.absolute_expires_at > CURRENT_TIMESTAMP
              AND u.status = 'ACTIVE'
              AND u.deleted_at IS NULL
        """
    }
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

/** Extracts a bearer token without logging it. Web cookie fallback is handled by the resolver. */
fun ApplicationCall.bearerToken(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
