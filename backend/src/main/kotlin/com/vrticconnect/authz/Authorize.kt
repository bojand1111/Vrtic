package com.vrticconnect.authz

import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import com.vrticconnect.modules.auth.AuthenticatedUser
import com.vrticconnect.modules.tenant.TenantPrincipal
import io.ktor.http.HttpStatusCode
import java.util.UUID

/**
 * E02-B15: authorization decisions on top of the tenant pipeline (docs/SECURITY.md 3.1).
 * Order inside a tenant request: 401 (session) -> 404 (membership) -> 403 (permission) -> 404 (resource scope).
 * A 403 never carries details about which permission was missing.
 */
object Authorize {

    /** The membership's effective permissions: role capabilities plus individually granted extras. */
    fun permissionsOf(principal: TenantPrincipal): Set<Permission> {
        val extras = principal.membership.extraPermissions
            .mapNotNull { name -> runCatching { Permission.valueOf(name) }.getOrNull() }
            .filter { it in GRANTABLE_EXTRA_PERMISSIONS }
        return PermissionMatrix.permissionsOf(principal.membership.role) + extras
    }

    fun has(principal: TenantPrincipal, permission: Permission): Boolean = permission in permissionsOf(principal)

    fun require(principal: TenantPrincipal, permission: Permission): TenantPrincipal {
        if (!has(principal, permission)) throw forbidden()
        return principal
    }

    fun requireAny(principal: TenantPrincipal, vararg permissions: Permission): TenantPrincipal {
        if (permissions.none { has(principal, it) }) throw forbidden()
        return principal
    }

    /**
     * Platform administration: SUPER_ADMIN is global (app.platform_admins) and needs a session that
     * passed MFA. A non-admin gets 404 (the surface does not exist for them); an admin without MFA
     * gets 403 MFA_REQUIRED. Support access into a tenant is a separate grant (E17), never implied.
     */
    fun requirePlatformAdmin(user: AuthenticatedUser): AuthenticatedUser {
        if (!user.isPlatformAdmin) throw ProblemException.notFound()
        if (!user.mfaVerified) {
            throw ProblemException(
                status = HttpStatusCode.Forbidden, type = ProblemTypes.FORBIDDEN,
                title = "Multi-factor authentication required", detail = "MFA_REQUIRED",
            )
        }
        return user
    }

    fun forbidden() = ProblemException(status = HttpStatusCode.Forbidden, type = ProblemTypes.FORBIDDEN, title = "Forbidden")
}

/**
 * Resource scope inside a tenant (step 4 of the pipeline). RLS already guarantees the row belongs
 * to the organization; these checks answer "may THIS member see THIS group/child": a TEACHER only
 * through a valid group assignment, a PARENT only through a CONFIRMED guardian link, OWNER/ADMIN
 * everything in their organization. Implementations arrive with the modules that own the tables
 * (E04 groups/assignments, E05 children/guardians); a failed check answers 404, never 403.
 */
fun interface GroupScope {
    suspend fun canAccessGroup(principal: TenantPrincipal, groupId: UUID): Boolean
}

fun interface ChildScope {
    suspend fun canAccessChild(principal: TenantPrincipal, childId: UUID): Boolean
}

suspend fun GroupScope.requireGroup(principal: TenantPrincipal, groupId: UUID) {
    if (!canAccessGroup(principal, groupId)) throw ProblemException.notFound()
}

suspend fun ChildScope.requireChild(principal: TenantPrincipal, childId: UUID) {
    if (!canAccessChild(principal, childId)) throw ProblemException.notFound()
}
