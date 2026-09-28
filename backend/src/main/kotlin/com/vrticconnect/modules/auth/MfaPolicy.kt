package com.vrticconnect.modules.auth

import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import java.sql.Connection
import java.util.UUID

/**
 * E02-B12 MFA policy, enforced for every authenticated request right after the session lookup
 * (docs/SECURITY.md 2.1: "a session without mfa_verified_at may only complete MFA").
 *
 *  - Enrolled user, session not verified yet ([AuthenticatedUser.mfaVerificationRequired]): only the
 *    session bootstrap, logout, `POST /auth/mfa/totp/verify` and reading the own profile work;
 *    everything else answers `403 MFA_REQUIRED`. Password-only possession cannot change the password,
 *    revoke sessions or replace the enrolled authenticator.
 *  - OWNER membership without any enrolled method ([AuthenticatedUser.mfaEnrollmentRequired]): all of
 *    `/auth/...` and `/me/...` work (enrollment needs setup, confirm and possibly re-authentication);
 *    every tenant or platform route answers `403 MFA_ENROLLMENT_REQUIRED` until confirm succeeds.
 *  - Platform admins additionally pass [com.vrticconnect.authz.Authorize.requirePlatformAdmin], which
 *    demands `mfa_verified_at` for every `/platform/...` route.
 */
object MfaGate {
    private const val API = "/api/v1"

    private val verificationAllowed: Set<Pair<HttpMethod, String>> = setOf(
        HttpMethod.Get to "$API/auth/session",
        HttpMethod.Post to "$API/auth/logout",
        HttpMethod.Post to "$API/auth/mfa/totp/verify",
        HttpMethod.Get to "$API/me",
        HttpMethod.Get to "$API/me/memberships",
        HttpMethod.Put to "$API/me/locale",
    )

    fun requestPath(call: ApplicationCall): String = call.request.path().trimEnd('/').ifEmpty { "/" }

    fun check(call: ApplicationCall, path: String, user: AuthenticatedUser) {
        if (user.mfaVerificationRequired) {
            if ((call.request.httpMethod to path) !in verificationAllowed) throw mfaRequired("MFA_REQUIRED")
            return
        }
        // Platform admins without an OWNER membership are handled by requirePlatformAdmin (403 MFA_REQUIRED on /platform).
        if (user.hasOwnerMembership && !user.mfaEnabled && !user.mfaVerified) {
            val allowed = path.startsWith("$API/auth/") || path == "$API/me" || path.startsWith("$API/me/")
            if (!allowed) throw mfaRequired("MFA_ENROLLMENT_REQUIRED")
        }
    }

    /** `{organizationId}` of a tenant route (`/api/v1/organizations/{id}/...`), when well-formed. */
    fun addressedOrganization(path: String): UUID? {
        val prefix = "$API/organizations/"
        if (!path.startsWith(prefix)) return null
        val raw = path.removePrefix(prefix).substringBefore('/')
        return runCatching { UUID.fromString(raw) }.getOrNull()?.takeIf { it.toString() == raw.lowercase() }
    }

    fun mfaRequired(detail: String) = ProblemException(
        status = HttpStatusCode.Forbidden, type = ProblemTypes.FORBIDDEN,
        title = "Multi-factor authentication required", detail = detail,
    )
}

/**
 * `POST /platform/organizations/{id}/deactivate` sets status SUSPENDED: members can still sign in,
 * but every tenant endpoint answers `403 ORGANIZATION_SUSPENDED`. Only members of that organization
 * learn about the suspension; for everyone else the tenant pipeline keeps answering 404.
 */
object OrganizationStatusGate {
    fun check(connection: Connection, userId: UUID, organizationId: UUID) {
        val status = connection.prepareStatement(
            "SELECT o.status FROM app.organizations o JOIN app.organization_memberships m ON m.organization_id = o.id " +
                "WHERE o.id = ? AND m.user_id = ? AND m.status = 'ACTIVE' AND o.deleted_at IS NULL LIMIT 1",
        ).use { st ->
            st.setObject(1, organizationId)
            st.setObject(2, userId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString("status") else null }
        }
        if (status == "SUSPENDED" || status == "ARCHIVED") {
            throw ProblemException(
                status = HttpStatusCode.Forbidden, type = ProblemTypes.FORBIDDEN,
                title = "Organization is not active", detail = "ORGANIZATION_SUSPENDED",
            )
        }
    }
}
