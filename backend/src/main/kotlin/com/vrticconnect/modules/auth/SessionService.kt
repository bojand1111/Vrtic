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

/**
 * `GET /auth/session`. Keeps the historical `user` shape the SPA reads and adds the contract's
 * `CurrentSession` session fields plus the E02-B12 MFA state (`mfaRequired`: this session must pass
 * `POST /auth/mfa/totp/verify`; `mfaEnrollmentRequired`: MFA is mandatory and not enrolled yet).
 */
@Serializable
data class SessionResponse(
    val user: SessionUserResponse,
    val userId: String,
    val sessionId: String,
    val clientKind: String,
    val mfaVerified: Boolean,
    val mfaEnabled: Boolean,
    val mfaRequired: Boolean,
    val mfaEnrollmentRequired: Boolean,
    val reauthenticatedAt: String? = null,
    val absoluteExpiresAt: String,
)

/** Returns the minimal user shape already consumed by the admin SPA tenant bootstrap. */
class SessionService(private val database: Database) {
    suspend fun current(call: ApplicationCall, resolver: SessionResolver) {
        val authenticated = resolver.resolve(call) ?: throw ProblemException.unauthenticated()
        val (user, session) = database.transaction(DbContext.User(authenticated.userId)) { connection ->
            val user = findUser(connection, authenticated.userId) ?: return@transaction null
            val session = connection.prepareStatement(
                "SELECT client_kind, reauthenticated_at, absolute_expires_at FROM app.sessions WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
            ).use { statement ->
                statement.setObject(1, authenticated.sessionId)
                statement.setObject(2, authenticated.userId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return@transaction null
                    Triple(rs.getString("client_kind"), rs.getTimestamp("reauthenticated_at")?.toInstant()?.toString(), rs.getTimestamp("absolute_expires_at").toInstant().toString())
                }
            }
            user to session
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
                userId = user.id.toString(),
                sessionId = authenticated.sessionId.toString(),
                clientKind = session.first,
                mfaVerified = authenticated.mfaVerified,
                mfaEnabled = authenticated.mfaEnabled,
                mfaRequired = authenticated.mfaVerificationRequired,
                mfaEnrollmentRequired = authenticated.mfaEnrollmentRequired,
                reauthenticatedAt = session.second,
                absoluteExpiresAt = session.third,
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
