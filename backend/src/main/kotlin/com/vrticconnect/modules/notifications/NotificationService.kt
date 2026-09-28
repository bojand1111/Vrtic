package com.vrticconnect.modules.notifications

import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.db.SqlArray
import com.vrticconnect.db.instant
import com.vrticconnect.db.instantOrNull
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.db.uuidOrNull
import com.vrticconnect.http.Cursor
import com.vrticconnect.http.PageRequest
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.conflict
import com.vrticconnect.http.invalidQuery
import com.vrticconnect.http.toPage
import com.vrticconnect.http.validate
import com.vrticconnect.modules.auth.AuthenticatedUser
import io.ktor.http.Parameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** docs/openapi.yaml `Notification` plus `organizationName` (addition, for the cross-tenant inbox). */
@Serializable
data class NotificationDto(
    val id: String,
    val organizationId: String?,
    val organizationName: String?,
    val membershipId: String?,
    val kind: String,
    val titleKey: String,
    val titleArgs: JsonObject,
    val refEntityType: String?,
    val refEntityId: String?,
    val createdAt: String,
    val readAt: String?,
)

/** `NotificationPage` plus `unreadCount` (addition: the bell needs the total without a second request). */
@Serializable
data class NotificationPageDto(val items: List<NotificationDto>, val nextCursor: String?, val unreadCount: Int)

@Serializable
data class NotificationMarkReadRequest(val notificationIds: List<String>? = null, val all: Boolean? = null)

@Serializable
data class UnreadCountDto(val unread: Int)

@Serializable
data class PushTokenRegistration(val platform: String? = null, val token: String? = null, val appVersion: String? = null)

/** docs/openapi.yaml `PushToken`: the token value itself is never returned. */
@Serializable
data class PushTokenDto(val id: String, val platform: String, val sessionId: String?, val appVersion: String?, val createdAt: String, val lastSeenAt: String)

/**
 * The caller's own inbox and device tokens. Runs in DbContext.User: RLS limits notifications and
 * device_push_tokens to rows of the current user, whatever the SQL says.
 * Tenant notifications are listed only while the user still has an ACTIVE membership in that organization.
 */
class NotificationService(private val database: Database) {

    private val json = Json { ignoreUnknownKeys = true }

    private val visible =
        "n.recipient_user_id = app.current_user_id() AND n.created_at <= now() AND (n.organization_id IS NULL OR EXISTS (" +
            "SELECT 1 FROM app.organization_memberships om WHERE om.organization_id = n.organization_id AND om.user_id = app.current_user_id() AND om.status = 'ACTIVE'))"

    suspend fun list(user: AuthenticatedUser, query: Parameters): NotificationPageDto {
        val page = PageRequest.from(query)
        val unreadOnly = boolQuery(query, "unreadOnly")
        val organizationId = query["organizationId"]?.let { runCatching { UUID.fromString(it) }.getOrElse { throw invalidQuery("organizationId", "UUID") } }
        val kind = query["kind"]
        if (kind != null && kind !in NotificationWriter.KINDS) throw invalidQuery("kind", NotificationWriter.KINDS.sorted().joinToString("|"))
        val sort = query["sort"] ?: "createdAt:desc"
        if (sort != "createdAt:desc") throw invalidQuery("sort", "createdAt:desc")
        return database.transaction(DbContext.User(user.userId)) { c ->
            val where = mutableListOf(visible)
            val params = mutableListOf<Any?>()
            if (unreadOnly) where += "n.read_at IS NULL"
            if (organizationId != null) { where += "n.organization_id = ?"; params += organizationId }
            if (kind != null) { where += "n.kind = ?"; params += kind }
            page.cursor?.let { cur -> where += "(n.created_at, n.id) < (?, ?)"; params += cur.at; params += cur.id }
            params += page.limit + 1
            val rows = c.queryList(
                "SELECT n.id, n.organization_id, o.name AS organization_name, n.membership_id, n.kind, n.title_key, n.title_args::text AS args, " +
                    "n.ref_entity_type, n.ref_entity_id, n.created_at, n.read_at FROM app.notifications n " +
                    "LEFT JOIN app.organizations o ON o.id = n.organization_id WHERE " + where.joinToString(" AND ") +
                    " ORDER BY n.created_at DESC, n.id DESC LIMIT ?",
                *params.toTypedArray(),
            ) { map(it) }
            val (items, next) = rows.toPage(page.limit) { Cursor(java.time.Instant.parse(it.createdAt), UUID.fromString(it.id)) }
            NotificationPageDto(items, next, unread(c))
        }
    }

    suspend fun markRead(user: AuthenticatedUser, body: NotificationMarkReadRequest): UnreadCountDto {
        val ids = validate {
            val all = body.all == true
            val raw = body.notificationIds.orEmpty()
            require(all || raw.isNotEmpty(), "notificationIds", "REQUIRED", "notificationIds or all=true is required")
            require(raw.size <= 200, "notificationIds", "TOO_MANY", "max 200 ids")
            val parsed = raw.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            require(parsed.size == raw.size, "notificationIds", "INVALID_FORMAT", "UUID")
            if (all) null else parsed
        }
        return database.transaction(DbContext.User(user.userId)) { c ->
            if (ids == null) {
                c.update("UPDATE app.notifications n SET read_at = now() WHERE $visible AND n.read_at IS NULL")
            } else {
                // Unknown or foreign ids are ignored (RLS hides them); the call is idempotent.
                c.update("UPDATE app.notifications n SET read_at = now() WHERE $visible AND n.read_at IS NULL AND n.id = ANY(?)", SqlArray("uuid", ids))
            }
            UnreadCountDto(unread(c))
        }
    }

    suspend fun registerPushToken(user: AuthenticatedUser, body: PushTokenRegistration): Pair<Boolean, PushTokenDto> {
        validate {
            oneOf(body.platform, "platform", setOf("ANDROID", "IOS"))
            text(body.token, "token", 4096)
            require((body.token?.length ?: 16) >= 16, "token", "TOO_SHORT", "min 16 characters")
            text(body.appVersion, "appVersion", 32, required = false)
        }
        return database.transaction(DbContext.User(user.userId)) { c ->
            val own = c.queryOne(
                "UPDATE app.device_push_tokens SET last_seen_at = now(), session_id = ?, app_version = ?, invalidated_at = NULL, invalidation_reason = NULL " +
                    "WHERE platform = ? AND token = ? RETURNING id",
                user.sessionId, body.appVersion, body.platform, body.token,
            ) { it.uuid("id") }
            if (own != null) return@transaction false to pushToken(c, own)
            val created = c.queryOne(
                "INSERT INTO app.device_push_tokens (user_id, session_id, platform, token, app_version) VALUES (?, ?, ?, ?, ?) " +
                    "ON CONFLICT (platform, token) DO NOTHING RETURNING id",
                user.userId, user.sessionId, body.platform, body.token, body.appVersion,
            ) { it.uuid("id") }
                // The token is registered to another account (device switched users); moving it needs the worker context.
                ?: throw conflict("PUSH_TOKEN_REGISTERED_TO_ANOTHER_USER")
            true to pushToken(c, created)
        }
    }

    suspend fun deletePushToken(user: AuthenticatedUser, id: UUID) {
        val deleted = database.transaction(DbContext.User(user.userId)) { c -> c.update("DELETE FROM app.device_push_tokens WHERE id = ?", id) }
        if (deleted == 0) throw ProblemException.notFound()
    }

    private fun pushToken(c: Connection, id: UUID): PushTokenDto = c.queryOne(
        "SELECT id, platform, session_id, app_version, created_at, last_seen_at FROM app.device_push_tokens WHERE id = ?", id,
    ) { rs ->
        PushTokenDto(
            id = rs.uuid("id").toString(), platform = rs.getString("platform"), sessionId = rs.uuidOrNull("session_id")?.toString(),
            appVersion = rs.getString("app_version"), createdAt = rs.instant("created_at").toString(), lastSeenAt = rs.instant("last_seen_at").toString(),
        )
    } ?: throw ProblemException.notFound()

    private fun unread(c: Connection): Int =
        c.queryOne("SELECT count(*)::int AS n FROM app.notifications n WHERE $visible AND n.read_at IS NULL") { it.getInt("n") } ?: 0

    private fun boolQuery(q: Parameters, name: String): Boolean = when (q[name]) {
        null, "false" -> false
        "true" -> true
        else -> throw invalidQuery(name, "true|false")
    }

    private fun map(rs: ResultSet) = NotificationDto(
        id = rs.uuid("id").toString(), organizationId = rs.uuidOrNull("organization_id")?.toString(),
        organizationName = rs.getString("organization_name"), membershipId = rs.uuidOrNull("membership_id")?.toString(),
        kind = rs.getString("kind"), titleKey = rs.getString("title_key"),
        titleArgs = json.parseToJsonElement(rs.getString("args")).jsonObject,
        refEntityType = rs.getString("ref_entity_type"), refEntityId = rs.uuidOrNull("ref_entity_id")?.toString(),
        createdAt = rs.instant("created_at").toString(), readAt = rs.instantOrNull("read_at")?.toString(),
    )
}
