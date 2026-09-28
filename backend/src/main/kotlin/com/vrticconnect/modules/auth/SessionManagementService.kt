package com.vrticconnect.modules.auth

import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.http.Cursor
import com.vrticconnect.http.PageRequest
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.toPage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** docs/openapi.yaml `Session`. */
@Serializable
data class SessionSummary(
    val id: String,
    val clientKind: String,
    val deviceName: String? = null,
    val userAgent: String? = null,
    val createdAt: String,
    val lastSeenAt: String,
    val absoluteExpiresAt: String,
    val mfaVerifiedAt: String? = null,
    val reauthenticatedAt: String? = null,
    val isCurrent: Boolean,
)

/** docs/openapi.yaml `SessionPage`. `nextCursor` is always present (null = last page), so it is a JsonElement: the global Json omits null properties. */
@Serializable
data class SessionPage(val items: List<SessionSummary>, val nextCursor: JsonElement) {
    constructor(items: List<SessionSummary>, nextCursor: String?) : this(items, nextCursor?.let(::JsonPrimitive) ?: JsonNull)
}

/**
 * E02-B08: device list, remote revocation of one session and logout from all devices.
 * Listing runs as the user (`sessions_self` policy); revocation runs in the auth context because
 * access/refresh tokens are reachable only there, always scoped by `user_id = ?`.
 */
class SessionManagementService(private val database: Database) {

    suspend fun list(user: AuthenticatedUser, page: PageRequest): SessionPage {
        val rows = database.transaction(DbContext.User(user.userId)) { connection ->
            val sql = LIST_SQL + (if (page.cursor != null) LIST_CURSOR_CONDITION else "") + LIST_ORDER
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, user.userId)
                var index = 2
                page.cursor?.let { cursor ->
                    statement.setTimestamp(index++, Timestamp.from(cursor.at))
                    statement.setTimestamp(index++, Timestamp.from(cursor.at))
                    statement.setObject(index++, cursor.id)
                }
                statement.setInt(index, page.limit + 1)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            val id = result.getObject("id", UUID::class.java)
                            val createdAt = result.getTimestamp("created_at").toInstant()
                            add(
                                SessionRow(
                                    createdAt = createdAt,
                                    summary = SessionSummary(
                                        id = id.toString(),
                                        clientKind = result.getString("client_kind"),
                                        deviceName = result.getString("device_name"),
                                        userAgent = result.getString("user_agent")?.take(200),
                                        createdAt = createdAt.toString(),
                                        lastSeenAt = result.getTimestamp("last_seen_at").toInstant().toString(),
                                        absoluteExpiresAt = result.getTimestamp("absolute_expires_at").toInstant().toString(),
                                        mfaVerifiedAt = result.getTimestamp("mfa_verified_at")?.toInstant()?.toString(),
                                        reauthenticatedAt = result.getTimestamp("reauthenticated_at")?.toInstant()?.toString(),
                                        isCurrent = id == user.sessionId,
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }
        val (items, nextCursor) = rows.toPage(page.limit) { Cursor(it.createdAt, UUID.fromString(it.summary.id)) }
        return SessionPage(items.map { it.summary }, nextCursor)
    }

    /**
     * Revokes one of the caller's sessions. Another user's session (or an unknown id) answers 404;
     * an already revoked own session is a no-op (idempotent 204). Returns true when the revoked
     * session is the current one, so the route can clear web cookies.
     */
    suspend fun revoke(user: AuthenticatedUser, sessionId: UUID): Boolean =
        database.transaction(DbContext.Auth(user.userId)) { connection ->
            val owned = connection.prepareStatement("SELECT 1 FROM app.sessions WHERE id = ? AND user_id = ?").use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, user.userId)
                statement.executeQuery().use { it.next() }
            }
            if (!owned) throw ProblemException.notFound()
            revokeSessions(connection, user.userId, "USER_LOGOUT", onlySessionId = sessionId)
            sessionId == user.sessionId
        }

    /**
     * Revokes every session of the caller, including the current one (`LOGOUT_ALL`).
     * Requires recent authentication (login or `POST /auth/reauthenticate` within [RecentAuthentication.MAX_AGE]).
     */
    suspend fun logoutAll(user: AuthenticatedUser) {
        database.transaction(DbContext.Auth(user.userId)) { connection ->
            RecentAuthentication.require(connection, user)
            revokeSessions(connection, user.userId, "LOGOUT_ALL", onlySessionId = null)
        }
    }

    /** Session + its access and refresh tokens, always bounded by the owning user. */
    private fun revokeSessions(connection: Connection, userId: UUID, reason: String, onlySessionId: UUID?) {
        val sessionFilter = if (onlySessionId != null) " AND s.id = ?" else ""
        connection.prepareStatement(
            "UPDATE app.sessions s SET revoked_at = CURRENT_TIMESTAMP, revoke_reason = ? WHERE s.user_id = ? AND s.revoked_at IS NULL$sessionFilter",
        ).use { statement ->
            statement.setString(1, reason)
            statement.setObject(2, userId)
            onlySessionId?.let { statement.setObject(3, it) }
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "UPDATE app.access_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE revoked_at IS NULL AND session_id IN " +
                "(SELECT s.id FROM app.sessions s WHERE s.user_id = ?$sessionFilter)",
        ).use { statement ->
            statement.setObject(1, userId)
            onlySessionId?.let { statement.setObject(2, it) }
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "UPDATE app.refresh_tokens SET consumed_at = CURRENT_TIMESTAMP WHERE consumed_at IS NULL AND session_id IN " +
                "(SELECT s.id FROM app.sessions s WHERE s.user_id = ?$sessionFilter)",
        ).use { statement ->
            statement.setObject(1, userId)
            onlySessionId?.let { statement.setObject(2, it) }
            statement.executeUpdate()
        }
    }

    private data class SessionRow(val createdAt: Instant, val summary: SessionSummary)

    private companion object {
        const val LIST_SQL = """
            SELECT id, client_kind, device_name, user_agent, created_at, last_seen_at, absolute_expires_at, mfa_verified_at, reauthenticated_at
            FROM app.sessions
            WHERE user_id = ? AND revoked_at IS NULL AND absolute_expires_at > CURRENT_TIMESTAMP
        """
        const val LIST_CURSOR_CONDITION = " AND (created_at < ? OR (created_at = ? AND id < ?))"
        const val LIST_ORDER = " ORDER BY created_at DESC, id DESC LIMIT ?"
    }
}
