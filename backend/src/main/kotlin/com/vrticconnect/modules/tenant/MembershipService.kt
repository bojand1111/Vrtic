package com.vrticconnect.modules.tenant

import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import java.util.UUID

/** docs/openapi.yaml `OrganizationRef`. */
@Serializable
data class OrganizationRef(
    val id: String,
    val slug: String,
    val name: String,
    val timezone: String,
    val defaultLocale: String,
    val status: String,
)

/** docs/openapi.yaml `MyMembership`. */
@Serializable
data class MyMembership(
    val id: String,
    val organization: OrganizationRef,
    val role: String,
    val status: String,
    val permissions: List<String>,
    val acceptedAt: String? = null,
    val employeeId: String? = null,
)

@Serializable
data class MyMembershipsResponse(val items: List<MyMembership>)

/**
 * `GET /me/memberships` (operationId listMyMemberships): the only cross-tenant listing a tenant
 * user gets. Runs in [DbContext.User]; every row is filtered by the `self_*` RLS policies.
 */
class MembershipService(private val database: Database) {

    suspend fun listMine(userId: UUID, status: String?): MyMembershipsResponse {
        val statusFilter = status ?: "ACTIVE"
        if (statusFilter !in LISTABLE_STATUSES) {
            throw ProblemException(
                status = HttpStatusCode.UnprocessableEntity,
                type = ProblemTypes.VALIDATION,
                title = "Validation failed",
                errors = listOf(FieldError("status", "INVALID_ENUM", "Allowed: ${LISTABLE_STATUSES.joinToString()}")),
            )
        }
        val items = database.transaction(DbContext.User(userId)) { connection ->
            connection.prepareStatement(LIST_SQL).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, statusFilter)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            @Suppress("UNCHECKED_CAST")
                            val permissions = (result.getArray("permissions").array as Array<Any?>).map { it.toString() }
                            add(
                                MyMembership(
                                    id = result.getObject("id", UUID::class.java).toString(),
                                    organization = OrganizationRef(
                                        id = result.getObject("org_id", UUID::class.java).toString(),
                                        slug = result.getString("slug"),
                                        name = result.getString("name"),
                                        timezone = result.getString("timezone"),
                                        defaultLocale = result.getString("default_locale"),
                                        status = result.getString("org_status"),
                                    ),
                                    role = result.getString("role"),
                                    status = result.getString("status"),
                                    permissions = permissions.sorted(),
                                    acceptedAt = result.getTimestamp("accepted_at")?.toInstant()?.toString(),
                                    employeeId = result.getObject("employee_id", UUID::class.java)?.toString(),
                                ),
                            )
                        }
                    }
                }
            }
        }
        return MyMembershipsResponse(items)
    }

    private companion object {
        val LISTABLE_STATUSES = setOf("ACTIVE", "INVITED", "SUSPENDED")

        // organizations/employees/membership_permissions are visible here only through the self_* policies (V1 + V3).
        const val LIST_SQL = """
            SELECT m.id, m.role, m.status, m.accepted_at,
                   o.id AS org_id, o.slug, o.name, o.timezone, o.default_locale, o.status AS org_status,
                   e.id AS employee_id,
                   COALESCE(array_agg(p.permission) FILTER (WHERE p.permission IS NOT NULL), '{}') AS permissions
            FROM app.organization_memberships m
            JOIN app.organizations o ON o.id = m.organization_id
            LEFT JOIN app.employees e ON e.membership_id = m.id
            LEFT JOIN app.membership_permissions p ON p.membership_id = m.id AND p.revoked_at IS NULL
            WHERE m.user_id = ? AND m.status = ?
            GROUP BY m.id, o.id, e.id
            ORDER BY o.name, m.role
        """
    }
}
