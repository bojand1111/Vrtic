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
    /** A confirmed (active) TOTP method exists for the user. */
    val mfaEnabled: Boolean = false,
    /** The user holds an ACTIVE OWNER membership somewhere (MFA is mandatory, docs/SECURITY.md 2.1). */
    val hasOwnerMembership: Boolean = false,
) {
    /** E02-B12 policy: MFA is mandatory for platform admins and kindergarten owners. */
    val mfaPolicyRequired: Boolean get() = isPlatformAdmin || hasOwnerMembership

    /** Policy requires MFA but no method is confirmed yet: the session is limited until enrollment. */
    val mfaEnrollmentRequired: Boolean get() = mfaPolicyRequired && !mfaEnabled && !mfaVerified

    /** MFA is enrolled but this session has not passed it yet: the session is limited until verify. */
    val mfaVerificationRequired: Boolean get() = mfaEnabled && !mfaVerified
}

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

        val path = MfaGate.requestPath(call)
        val addressedOrganization = MfaGate.addressedOrganization(path)
        val user = database.transaction(DbContext.Auth()) { connection ->
            val user = connection.prepareStatement(LOOKUP_SQL).use { statement ->
                statement.setBytes(1, Tokens.sha256(token))
                statement.executeQuery().use { result ->
                    if (!result.next()) return@transaction null
                    AuthenticatedUser(
                        userId = result.getObject("user_id", UUID::class.java),
                        sessionId = result.getObject("session_id", UUID::class.java),
                        isPlatformAdmin = result.getBoolean("is_platform_admin"),
                        mfaVerified = result.getTimestamp("mfa_verified_at") != null,
                        mfaEnabled = result.getBoolean("mfa_enabled"),
                        hasOwnerMembership = result.getBoolean("has_owner_membership"),
                    )
                }
            }
            // E02-B12: a session that still owes MFA may only finish it (checked before any tenant lookup).
            MfaGate.check(call, path, user)
            if (addressedOrganization != null) OrganizationStatusGate.check(connection, user.userId, addressedOrganization)
            user
        }
        return user
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
                ) AS is_platform_admin,
                EXISTS (
                    SELECT 1
                    FROM app.user_mfa_methods mm
                    WHERE mm.user_id = s.user_id
                      AND mm.verified_at IS NOT NULL
                      AND mm.revoked_at IS NULL
                ) AS mfa_enabled,
                EXISTS (
                    SELECT 1
                    FROM app.organization_memberships om
                    JOIN app.organizations o ON o.id = om.organization_id
                    WHERE om.user_id = s.user_id
                      AND om.role = 'OWNER'
                      AND om.status = 'ACTIVE'
                      AND o.deleted_at IS NULL
                ) AS has_owner_membership
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
