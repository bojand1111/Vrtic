package com.vrticconnect.modules.messaging

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Role
import com.vrticconnect.authz.Scopes
import com.vrticconnect.db.SqlArray
import com.vrticconnect.db.instant
import com.vrticconnect.db.instantOrNull
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.db.uuidOrNull
import com.vrticconnect.http.Cursor
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.PageRequest
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import com.vrticconnect.http.conflict
import com.vrticconnect.http.invalidQuery
import com.vrticconnect.http.toPage
import com.vrticconnect.http.validate
import com.vrticconnect.modules.notifications.NotificationWriter
import com.vrticconnect.modules.tenant.TenantPrincipal
import com.vrticconnect.modules.tenant.audit
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * Parent-staff messaging (docs/openapi.yaml tag Messaging, docs/PRODUCT_SPEC.md 5.12). Every conversation is about
 * one child; participants are derived by [Participants]. Access = ACTIVE membership (pipeline) + MESSAGE_SEND +
 * an active participant row after the lazy sync; anything else answers 404. Message bodies are never logged,
 * audited or copied into notifications.
 */
object MessagingService {
    val KINDS = setOf("PARENT_TEACHER", "PARENT_ADMIN")
    private const val MAX_BODY = 4000

    /** Unread messages of participant row `p` (other senders, after the read position, not deleted). */
    private const val UNREAD_SQL =
        "(SELECT count(*) FROM app.messages mu WHERE mu.conversation_id = cv.id AND mu.deleted_at IS NULL AND mu.sender_membership_id <> p.membership_id " +
            "AND (p.last_read_message_id IS NULL OR (mu.created_at, mu.id) > (SELECT lr.created_at, lr.id FROM app.messages lr WHERE lr.id = p.last_read_message_id)))::int"

    private const val SELECT =
        "SELECT cv.id, cv.organization_id, cv.kind, cv.child_id, ch.given_name, ch.family_name, cv.group_id, cv.subject, cv.last_message_at, " +
            "cv.closed_at, cv.created_at, p.last_read_message_id, $UNREAD_SQL AS unread, " +
            "(SELECT left(ml.body, 80) FROM app.messages ml WHERE ml.conversation_id = cv.id AND ml.deleted_at IS NULL ORDER BY ml.created_at DESC, ml.id DESC LIMIT 1) AS preview, " +
            "coalesce(cv.last_message_at, cv.created_at) AS activity_at " +
            "FROM app.conversations cv JOIN app.children ch ON ch.id = cv.child_id " +
            "JOIN app.conversation_participants p ON p.conversation_id = cv.id AND p.membership_id = ? AND p.left_at IS NULL"

    fun list(c: Connection, principal: TenantPrincipal, query: Parameters): ConversationPage {
        Authorize.require(principal, Permission.MESSAGE_SEND)
        val page = PageRequest.from(query)
        val childId = query["childId"]?.let { runCatching { UUID.fromString(it) }.getOrElse { throw invalidQuery("childId", "UUID") } }
        val kind = query["kind"]
        if (kind != null && kind !in KINDS) throw invalidQuery("kind", KINDS.sorted().joinToString("|"))
        val unreadOnly = bool(query, "unreadOnly")
        val includeClosed = bool(query, "includeClosed")
        val sort = query["sort"] ?: "lastMessageAt:desc"
        if (sort != "lastMessageAt:desc") throw invalidQuery("sort", "lastMessageAt:desc")
        val me = principal.membership.membershipId
        val today = Scopes.today(c)

        // Lazy self-sync: the caller joins conversations he became entitled to and leaves the ones he lost.
        val scope = if (Scopes.isManager(principal)) null else Scopes.childIds(c, principal, today).orEmpty()
        val candidates = c.queryList(
            "SELECT cv.id FROM app.conversations cv WHERE ?::boolean OR cv.child_id = ANY(?) " +
                "OR EXISTS (SELECT 1 FROM app.conversation_participants p WHERE p.conversation_id = cv.id AND p.membership_id = ?)",
            scope == null, SqlArray("uuid", scope.orEmpty().toList()), me,
        ) { it.uuid("id") }
        Participants.sync(c, principal.membership.organizationId, candidates, today, only = me)

        val where = mutableListOf("ch.deleted_at IS NULL")
        val params = mutableListOf<Any?>(me)
        if (childId != null) { where += "cv.child_id = ?"; params += childId }
        if (kind != null) { where += "cv.kind = ?"; params += kind }
        if (!includeClosed) where += "cv.closed_at IS NULL"
        val outer = mutableListOf<String>()
        val outerParams = mutableListOf<Any?>()
        if (unreadOnly) outer += "t.unread > 0"
        page.cursor?.let { cur -> outer += "(t.activity_at, t.id) < (?, ?)"; outerParams += cur.at; outerParams += cur.id }
        val sql = "SELECT * FROM ($SELECT WHERE ${where.joinToString(" AND ")}) t" +
            (if (outer.isEmpty()) "" else " WHERE " + outer.joinToString(" AND ")) +
            " ORDER BY t.activity_at DESC, t.id DESC LIMIT ?"
        val rows = c.queryList(sql, *(params + outerParams + (page.limit + 1)).toTypedArray()) { rs -> rs.uuid("id") to rs.instant("activity_at") }
        val (pageRows, next) = rows.toPage(page.limit) { Cursor(it.second, it.first) }
        return ConversationPage(load(c, principal, pageRows.map { it.first }), next)
    }

    /** Returns (created, conversation). An existing open conversation of the same kind for the child is reused. */
    fun create(c: Connection, principal: TenantPrincipal, body: ConversationCreateRequest, requestId: String?): Pair<Boolean, ConversationDto> {
        Authorize.require(principal, Permission.MESSAGE_SEND)
        val (childId, clientMessageId) = validate {
            oneOf(body.kind, "kind", KINDS)
            val child = uuid(body.childId, "childId")
            text(body.subject, "subject", 200, required = false)
            val initial = body.initialMessage
            require(initial != null, "initialMessage", "REQUIRED", "initialMessage is required")
            val cmid = initial?.let { messageFields(this, it, "initialMessage.") }
            child to cmid
        }
        childId!!; clientMessageId!!
        val kind = body.kind!!
        Scopes.requireChild(c, principal, childId)
        c.queryOne("SELECT 1 AS ok FROM app.children WHERE id = ? AND deleted_at IS NULL", childId) { true } ?: throw ProblemException.notFound()
        // A teacher is never a participant of an administration conversation.
        if (principal.membership.role == Role.TEACHER && kind == "PARENT_ADMIN") throw Authorize.forbidden()
        val org = principal.membership.organizationId
        val today = Scopes.today(c)
        // Serializes concurrent "start conversation" calls for the same child and kind.
        c.queryOne("SELECT 1 AS x FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) l", "conversation:$org:$childId:$kind") { true }
        val existing = c.queryOne(
            "SELECT id FROM app.conversations WHERE child_id = ? AND kind = ? AND closed_at IS NULL ORDER BY created_at LIMIT 1", childId, kind,
        ) { it.uuid("id") }
        val created = existing == null
        val conversationId = existing ?: run {
            val groupId = c.queryOne(
                "SELECT group_id FROM app.enrollments WHERE child_id = ? AND status IN ('PLANNED','ACTIVE') AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?) " +
                    "ORDER BY valid_from DESC LIMIT 1",
                childId, today, today,
            ) { it.uuid("group_id") }
            c.queryOne(
                "INSERT INTO app.conversations (organization_id, kind, child_id, group_id, subject, created_by_membership_id) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                org, kind, childId, if (kind == "PARENT_TEACHER") groupId else null, body.subject?.trim()?.takeIf { it.isNotEmpty() },
                principal.membership.membershipId,
            ) { it.uuid("id") }!!
        }
        Participants.sync(c, org, listOf(conversationId), today)
        val roles = c.queryList(
            "SELECT membership_id, participant_role FROM app.conversation_participants WHERE conversation_id = ? AND left_at IS NULL", conversationId,
        ) { it.uuid("membership_id") to it.getString("participant_role") }
        if (roles.none { it.second == "GUARDIAN" }) throw fieldProblem("childId", "NO_GUARDIANS", "the child has no confirmed guardian")
        if (roles.none { it.second != "GUARDIAN" }) {
            throw fieldProblem("kind", if (kind == "PARENT_TEACHER") "NO_TEACHERS" else "NO_ADMINS", "no staff member can receive this conversation")
        }
        // The caller must end up as a participant (e.g. a manager is not a party of a PARENT_TEACHER conversation).
        if (roles.none { it.first == principal.membership.membershipId }) throw Authorize.forbidden()
        if (created) principal.audit(c, "CONVERSATION_CREATED", "CONVERSATION", conversationId, requestId)
        postMessage(c, principal, conversationId, clientMessageId, body.initialMessage!!.body!!, requestId)
        return created to get(c, principal, conversationId, synced = true)
    }

    fun get(c: Connection, principal: TenantPrincipal, conversationId: UUID, synced: Boolean = false): ConversationDto {
        Authorize.require(principal, Permission.MESSAGE_SEND)
        if (!synced) requireParticipant(c, principal, conversationId)
        return load(c, principal, listOf(conversationId)).firstOrNull() ?: throw ProblemException.notFound()
    }

    fun messages(c: Connection, principal: TenantPrincipal, conversationId: UUID, query: Parameters): MessagePage {
        Authorize.require(principal, Permission.MESSAGE_SEND)
        val page = PageRequest.from(query)
        val sort = query["sort"] ?: "createdAt:desc"
        if (sort !in setOf("createdAt:desc", "createdAt:asc")) throw invalidQuery("sort", "createdAt:asc|createdAt:desc")
        requireParticipant(c, principal, conversationId)
        val asc = sort == "createdAt:asc"
        val params = mutableListOf<Any?>(conversationId)
        var cursorSql = ""
        page.cursor?.let { cur -> cursorSql = " AND (m.created_at, m.id) ${if (asc) ">" else "<"} (?, ?)"; params += cur.at; params += cur.id }
        params += page.limit + 1
        val dir = if (asc) "ASC" else "DESC"
        val rows = c.queryList(
            "$MESSAGE_SELECT WHERE m.conversation_id = ?$cursorSql ORDER BY m.created_at $dir, m.id $dir LIMIT ?", *params.toTypedArray(),
        ) { mapMessage(it, principal) }
        val (items, next) = rows.toPage(page.limit) { Cursor(Instant.parse(it.createdAt), UUID.fromString(it.id)) }
        return MessagePage(items, next)
    }

    /** Returns (created, message); a repeated `clientMessageId` of the same sender returns the original message. */
    fun send(c: Connection, principal: TenantPrincipal, conversationId: UUID, body: MessageCreateRequest, requestId: String?): Pair<Boolean, MessageDto> {
        Authorize.require(principal, Permission.MESSAGE_SEND)
        val clientMessageId = validate { messageFields(this, body, "") }!!
        requireParticipant(c, principal, conversationId)
        return postMessage(c, principal, conversationId, clientMessageId, body.body!!, requestId)
    }

    fun updateReadPosition(c: Connection, principal: TenantPrincipal, conversationId: UUID, body: ReadPositionUpdate): ReadPositionDto {
        Authorize.require(principal, Permission.MESSAGE_SEND)
        val messageId = validate { uuid(body.lastReadMessageId, "lastReadMessageId") }!!
        requireParticipant(c, principal, conversationId)
        c.queryOne("SELECT 1 AS ok FROM app.messages WHERE id = ? AND conversation_id = ?", messageId, conversationId) { true }
            ?: throw fieldProblem("lastReadMessageId", "NOT_FOUND", "message is not part of this conversation")
        // Monotonic: an older message than the stored position is ignored.
        c.update(
            "UPDATE app.conversation_participants p SET last_read_message_id = ?, last_read_at = now() " +
                "WHERE p.conversation_id = ? AND p.membership_id = ? AND (p.last_read_message_id IS NULL OR " +
                "EXISTS (SELECT 1 FROM app.messages n, app.messages o WHERE n.id = ? AND o.id = p.last_read_message_id AND (n.created_at, n.id) > (o.created_at, o.id)))",
            messageId, conversationId, principal.membership.membershipId, messageId,
        )
        return c.queryOne(
            "SELECT p.last_read_message_id, p.last_read_at, $UNREAD_SQL AS unread FROM app.conversation_participants p " +
                "JOIN app.conversations cv ON cv.id = p.conversation_id WHERE p.conversation_id = ? AND p.membership_id = ?",
            conversationId, principal.membership.membershipId,
        ) { rs ->
            ReadPositionDto(conversationId.toString(), rs.uuidOrNull("last_read_message_id")?.toString(), rs.instantOrNull("last_read_at")?.toString(), rs.getInt("unread"))
        }!!
    }

    // ---------------------------------------------------------------- internals

    private const val MESSAGE_SELECT =
        "SELECT m.id, m.conversation_id, m.sender_membership_id, m.client_message_id, m.body, m.created_at, m.edited_at, m.deleted_at FROM app.messages m"

    private fun postMessage(c: Connection, principal: TenantPrincipal, conversationId: UUID, clientMessageId: UUID, text: String, requestId: String?): Pair<Boolean, MessageDto> {
        val me = principal.membership.membershipId
        val closed = c.queryOne("SELECT closed_at FROM app.conversations WHERE id = ? FOR UPDATE", conversationId) { it.instantOrNull("closed_at") != null }
            ?: throw ProblemException.notFound()
        val duplicate = c.queryOne("$MESSAGE_SELECT WHERE m.conversation_id = ? AND m.client_message_id = ?", conversationId, clientMessageId) { mapMessage(it, principal) }
        if (duplicate != null) {
            if (duplicate.senderMembershipId != me.toString()) throw conflict("CLIENT_MESSAGE_ID_TAKEN")
            return false to duplicate
        }
        if (closed) throw conflict("CONVERSATION_CLOSED")
        val id = c.queryOne(
            "INSERT INTO app.messages (organization_id, conversation_id, sender_membership_id, client_message_id, body) VALUES (?, ?, ?, ?, ?) RETURNING id",
            principal.membership.organizationId, conversationId, me, clientMessageId, text,
        ) { it.uuid("id") }!!
        c.update("UPDATE app.conversations SET last_message_at = (SELECT created_at FROM app.messages WHERE id = ?) WHERE id = ?", id, conversationId)
        // The sender has read everything up to his own message.
        c.update(
            "UPDATE app.conversation_participants SET last_read_message_id = ?, last_read_at = now() WHERE conversation_id = ? AND membership_id = ?",
            id, conversationId, me,
        )
        principal.audit(c, "MESSAGE_SENT", "MESSAGE", id, requestId)
        notifyNewMessage(c, principal, conversationId, id)
        return true to (c.queryOne("$MESSAGE_SELECT WHERE m.id = ?", id) { mapMessage(it, principal) }!!)
    }

    /** MESSAGE notification to the other active participants: sender and child names only, never the body. */
    private fun notifyNewMessage(c: Connection, principal: TenantPrincipal, conversationId: UUID, messageId: UUID) {
        val others = c.queryList(
            "SELECT membership_id FROM app.conversation_participants WHERE conversation_id = ? AND left_at IS NULL AND membership_id <> ?",
            conversationId, principal.membership.membershipId,
        ) { it.uuid("membership_id") }
        val names = c.queryOne(
            "SELECT u.given_name || ' ' || u.family_name AS sender, ch.given_name || ' ' || ch.family_name AS child " +
                "FROM app.conversations cv JOIN app.children ch ON ch.id = cv.child_id " +
                "JOIN app.organization_memberships om ON om.id = ? JOIN app.users u ON u.id = om.user_id WHERE cv.id = ?",
            principal.membership.membershipId, conversationId,
        ) { it.getString("sender") to it.getString("child") } ?: ("" to "")
        NotificationWriter.notifyMemberships(
            c, principal.membership.organizationId, others, "MESSAGE", "message.received",
            mapOf("senderName" to names.first, "childName" to names.second), "CONVERSATION", conversationId,
            "message:$messageId", principal.user.userId,
        )
    }

    /** Lazy full sync of one conversation, then 404 unless the caller is an active participant. */
    private fun requireParticipant(c: Connection, principal: TenantPrincipal, conversationId: UUID) {
        c.queryOne(
            "SELECT 1 AS ok FROM app.conversations cv JOIN app.children ch ON ch.id = cv.child_id WHERE cv.id = ? AND ch.deleted_at IS NULL", conversationId,
        ) { true } ?: throw ProblemException.notFound()
        Participants.sync(c, principal.membership.organizationId, listOf(conversationId), Scopes.today(c))
        c.queryOne(
            "SELECT 1 AS ok FROM app.conversation_participants WHERE conversation_id = ? AND membership_id = ? AND left_at IS NULL",
            conversationId, principal.membership.membershipId,
        ) { true } ?: throw ProblemException.notFound()
    }

    /** Conversations (caller is an active participant) in the given order, with participants. */
    private fun load(c: Connection, principal: TenantPrincipal, ids: List<UUID>): List<ConversationDto> {
        if (ids.isEmpty()) return emptyList()
        val participants = c.queryList(
            "SELECT p.conversation_id, p.membership_id, u.given_name, u.family_name, p.participant_role, p.joined_at, p.left_at " +
                "FROM app.conversation_participants p JOIN app.organization_memberships om ON om.id = p.membership_id JOIN app.users u ON u.id = om.user_id " +
                "WHERE p.conversation_id = ANY(?) ORDER BY p.participant_role, u.family_name, u.given_name",
            SqlArray("uuid", ids),
        ) { rs ->
            rs.uuid("conversation_id") to ConversationParticipantDto(
                membershipId = rs.uuid("membership_id").toString(), givenName = rs.getString("given_name"), familyName = rs.getString("family_name"),
                participantRole = rs.getString("participant_role"), joinedAt = rs.instant("joined_at").toString(), leftAt = rs.instantOrNull("left_at")?.toString(),
            )
        }.groupBy({ it.first }, { it.second })
        val rows = c.queryList("$SELECT WHERE cv.id = ANY(?)", principal.membership.membershipId, SqlArray("uuid", ids)) { rs ->
            val id = rs.uuid("id")
            id to ConversationDto(
                id = id.toString(), organizationId = rs.uuid("organization_id").toString(), kind = rs.getString("kind"),
                childId = rs.uuid("child_id").toString(), childGivenName = rs.getString("given_name"), childFamilyName = rs.getString("family_name"),
                groupId = rs.uuidOrNull("group_id")?.toString(), subject = rs.getString("subject"), participants = participants[id].orEmpty(),
                lastMessageAt = rs.instantOrNull("last_message_at")?.toString(), lastMessagePreview = rs.getString("preview"),
                unreadCount = rs.getInt("unread"), lastReadMessageId = rs.uuidOrNull("last_read_message_id")?.toString(),
                closedAt = rs.instantOrNull("closed_at")?.toString(), createdAt = rs.instant("created_at").toString(),
            )
        }.toMap()
        return ids.mapNotNull { rows[it] }
    }

    private fun messageFields(v: com.vrticconnect.http.Validation, m: MessageCreateRequest, prefix: String): UUID? {
        val id = v.uuid(m.clientMessageId, "${prefix}clientMessageId")
        v.text(m.body, "${prefix}body", MAX_BODY)
        return id
    }

    private fun mapMessage(rs: ResultSet, principal: TenantPrincipal): MessageDto {
        val deletedAt = rs.instantOrNull("deleted_at")
        return MessageDto(
            id = rs.uuid("id").toString(), conversationId = rs.uuid("conversation_id").toString(),
            senderMembershipId = rs.uuid("sender_membership_id").toString(), clientMessageId = rs.uuid("client_message_id").toString(),
            body = if (deletedAt != null) "" else rs.getString("body"), createdAt = rs.instant("created_at").toString(),
            editedAt = rs.instantOrNull("edited_at")?.toString(), deletedAt = deletedAt?.toString(),
            isOwn = rs.uuid("sender_membership_id") == principal.membership.membershipId,
        )
    }

    private fun bool(q: Parameters, name: String): Boolean = when (q[name]) {
        null, "false" -> false
        "true" -> true
        else -> throw invalidQuery(name, "true|false")
    }

    private fun fieldProblem(field: String, code: String, message: String) = ProblemException(
        status = HttpStatusCode.UnprocessableEntity, type = ProblemTypes.VALIDATION, title = "Validation failed",
        errors = listOf(FieldError(field, code, message)),
    )
}
