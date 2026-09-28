package com.vrticconnect.modules.tenant

import com.vrticconnect.authz.Role
import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.modules.auth.AuthenticatedUser
import com.vrticconnect.modules.auth.MembershipContext
import java.util.UUID

/** Authenticated user plus the ACTIVE membership that scopes the current tenant request. */
data class TenantPrincipal(val user: AuthenticatedUser, val membership: MembershipContext)

/**
 * Step 2 of the tenant pipeline: does this user hold an ACTIVE membership in the addressed
 * organization? Returns null for "no" so the route answers 404 (never reveals the organization).
 */
fun interface MembershipResolver {
    suspend fun resolve(user: AuthenticatedUser, organizationId: UUID): MembershipContext?
}

/**
 * Looks the membership up in [DbContext.User] (the `self_memberships` policy): the tenant context
 * is set only AFTER this check succeeds, by the route's own [DbContext.Tenant] transaction.
 *
 * A user may hold several roles in one organization (e.g. TEACHER and PARENT). The request is
 * scoped by the highest-privilege membership; extra permissions of that membership are attached.
 */
class DatabaseMembershipResolver(private val database: Database) : MembershipResolver {
    override suspend fun resolve(user: AuthenticatedUser, organizationId: UUID): MembershipContext? =
        database.transaction(DbContext.User(user.userId)) { connection ->
            val membership = connection.prepareStatement(MEMBERSHIP_SQL).use { statement ->
                statement.setObject(1, organizationId)
                statement.setObject(2, user.userId)
                statement.executeQuery().use { result ->
                    if (!result.next()) return@transaction null
                    result.getObject("id", UUID::class.java) to Role.valueOf(result.getString("role"))
                }
            }
            val permissions = connection.prepareStatement(PERMISSIONS_SQL).use { statement ->
                statement.setObject(1, membership.first)
                statement.executeQuery().use { result ->
                    buildSet { while (result.next()) add(result.getString("permission")) }
                }
            }
            MembershipContext(
                organizationId = organizationId,
                membershipId = membership.first,
                role = membership.second,
                extraPermissions = permissions,
            )
        }

    private companion object {
        const val MEMBERSHIP_SQL = """
            SELECT id, role
            FROM app.organization_memberships
            WHERE organization_id = ? AND user_id = ? AND status = 'ACTIVE'
            ORDER BY CASE role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 WHEN 'TEACHER' THEN 2 ELSE 3 END
            LIMIT 1
        """
        const val PERMISSIONS_SQL = """
            SELECT permission FROM app.membership_permissions
            WHERE membership_id = ? AND revoked_at IS NULL
        """
    }
}
