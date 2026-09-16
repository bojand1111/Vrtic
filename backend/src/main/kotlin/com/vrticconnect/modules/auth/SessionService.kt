package com.vrticconnect.modules.auth

import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.http.ProblemException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.util.UUID

@Serializable
data class SessionUserResponse(
    val id: String,
    val email: String,
    val displayName: String,
    val isPlatformAdmin: Boolean,
    val memberships: List<SessionMembershipResponse>,
)

@Serializable
data class SessionMembershipResponse(
    val organizationId: String,
    val organizationName: String,
    val role: String,
)

@Serializable
data class SessionResponse(val user: SessionUserResponse)

/** Returns the minimal user shape already consumed by the admin SPA tenant bootstrap. */
class SessionService(private val database: Database) {
    suspend fun current(call: ApplicationCall, resolver: SessionResolver) {
        val authenticated = resolver.resolve(call) ?: throw ProblemException.unauthenticated()
        val user = database.transaction(DbContext.User(authenticated.userId)) { connection ->
            findUser(connection, authenticated.userId)
        } ?: throw ProblemException.unauthenticated()

        call.respond(
            SessionResponse(
                user = SessionUserResponse(
                    id = user.id.toString(),
                    email = user.email,
                    displayName = listOf(user.givenName, user.familyName).joinToString(" ").trim(),
                    isPlatformAdmin = authenticated.isPlatformAdmin,
                    memberships = user.memberships,
                ),
            ),
        )
    }

    private fun findUser(connection: Connection, userId: UUID): SessionUserRecord? {
        val user = connection.prepareStatement(
            "SELECT id, email, given_name, family_name FROM app.users WHERE id = ? AND status = 'ACTIVE' AND deleted_at IS NULL",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                SessionUserRecord(
                    id = result.getObject("id", UUID::class.java),
                    email = result.getString("email"),
                    givenName = result.getString("given_name"),
                    familyName = result.getString("family_name"),
                    memberships = emptyList(),
                )
            }
        }

        val memberships = connection.prepareStatement(
            "SELECT m.organization_id, o.name, m.role " +
                "FROM app.organization_memberships m " +
                "JOIN app.organizations o ON o.id = m.organization_id " +
                "WHERE m.user_id = ? AND m.status = 'ACTIVE' ORDER BY o.name",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            SessionMembershipResponse(
                                organizationId = result.getObject("organization_id", UUID::class.java).toString(),
                                organizationName = result.getString("name"),
                                role = result.getString("role").let { if (it == "OWNER") "KINDERGARTEN_OWNER" else it },
                            ),
                        )
                    }
                }
            }
        }
        return user.copy(memberships = memberships)
    }

    private data class SessionUserRecord(
        val id: UUID,
        val email: String,
        val givenName: String,
        val familyName: String,
        val memberships: List<SessionMembershipResponse>,
    )
}
