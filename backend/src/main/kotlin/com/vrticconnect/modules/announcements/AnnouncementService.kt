package com.vrticconnect.modules.announcements

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Scopes
import com.vrticconnect.db.SqlArray
import com.vrticconnect.db.instant
import com.vrticconnect.db.instantOrNull
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.db.uuidOrNull
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.Validation
import com.vrticconnect.http.conflict
import com.vrticconnect.http.invalidQuery
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.TenantPrincipal
import com.vrticconnect.modules.tenant.audit
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Announcements (docs/openapi.yaml "Announcements"). Managers (ANNOUNCEMENT_MANAGE) see and edit everything;
 * everyone else only reads PUBLISHED, already visible, not expired announcements they are a snapshot recipient of.
 */
class AnnouncementService(private val api: TenantApi) {

    private fun manager(p: TenantPrincipal) = Authorize.has(p, Permission.ANNOUNCEMENT_MANAGE)

    suspend fun list(p: TenantPrincipal, status: String?, unreadOnly: Boolean, groupId: UUID?, sort: String, limit: Int): AnnouncementPage {
        Authorize.require(p, Permission.ANNOUNCEMENT_READ)
        if (status != null && status !in STATUSES) throw invalidQuery("status", STATUSES.joinToString("|"))
        if (sort !in setOf("publishedAt:desc", "createdAt:desc")) throw invalidQuery("sort", "publishedAt:desc|createdAt:desc")
        val isManager = manager(p)
        return api.tx(p) { c ->
            val where = mutableListOf("a.deleted_at IS NULL")
            val params = mutableListOf<Any?>(p.membership.membershipId)
            if (isManager) {
                if (status != null) { where += "a.status = ?"; params += status }
            } else {
                where += "$RECIPIENT_VISIBLE AND EXISTS (SELECT 1 FROM app.announcement_recipients r WHERE r.announcement_id = a.id AND r.membership_id = ?)"
                params += p.membership.membershipId
            }
            if (unreadOnly) {
                where += "NOT EXISTS (SELECT 1 FROM app.announcement_recipients r WHERE r.announcement_id = a.id AND r.membership_id = ? AND r.read_at IS NOT NULL)"
                params += p.membership.membershipId
            }
            if (groupId != null) {
                where += "EXISTS (SELECT 1 FROM app.announcement_audiences au WHERE au.announcement_id = a.id AND au.group_id = ?)"
                params += groupId
            }
            params += limit
            val order = if (sort == "createdAt:desc") "a.created_at DESC" else "a.published_at DESC NULLS FIRST, a.created_at DESC"
            val rows = c.queryList("$SELECT WHERE ${where.joinToString(" AND ")} ORDER BY $order, a.id LIMIT ?", *params.toTypedArray()) { map(it) }
            AnnouncementPage(withAudiences(c, rows, isManager), null)
        }
    }

    suspend fun get(p: TenantPrincipal, id: UUID): AnnouncementDto {
        Authorize.require(p, Permission.ANNOUNCEMENT_READ)
        return api.tx(p) { c -> load(c, p, id, manager(p)) }
    }

    suspend fun create(p: TenantPrincipal, req: AnnouncementCreate, requestId: String?): AnnouncementDto {
        Authorize.require(p, Permission.ANNOUNCEMENT_MANAGE)
        val v = Validation()
        v.text(req.title, "title", 200)
        v.text(req.body, "body", 20000)
        val audiences = AnnouncementAudiences.parse(v, req.audiences)
        val publishAt = instant(v, req.publishAt, "publishAt")
        val expiresAt = instant(v, req.expiresAt, "expiresAt")
        v.require(expiresAt == null || publishAt == null || expiresAt.isAfter(publishAt), "expiresAt", "MUST_BE_AFTER_PUBLISH_AT", "expiresAt must be after publishAt")
        v.throwIfInvalid()
        return api.tx(p) { c ->
            val check = Validation()
            AnnouncementAudiences.checkTargets(c, check, audiences)
            check.throwIfInvalid()
            val id = UUID.randomUUID()
            c.update(
                "INSERT INTO app.announcements (id, organization_id, title, body, publish_at, expires_at, created_by_membership_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, p.membership.organizationId, req.title!!.trim(), req.body!!, publishAt, expiresAt, p.membership.membershipId,
            )
            AnnouncementAudiences.replace(c, p.membership.organizationId, id, audiences)
            p.audit(c, "ANNOUNCEMENT_CREATED", "ANNOUNCEMENT", id, requestId)
            load(c, p, id, true)
        }
    }

    /** DRAFT: every field; PUBLISHED: only expiresAt; ARCHIVED: nothing (409 ANNOUNCEMENT_NOT_EDITABLE). */
    suspend fun update(p: TenantPrincipal, id: UUID, expectedVersion: Int, body: JsonObject, requestId: String?): AnnouncementDto {
        Authorize.require(p, Permission.ANNOUNCEMENT_MANAGE)
        val v = Validation()
        val unknown = body.keys - setOf("title", "body", "audiences", "publishAt", "expiresAt", "attachments")
        unknown.forEach { v.require(false, it, "UNKNOWN_PROPERTY", "not allowed") }
        v.require(body.isNotEmpty(), "body", "EMPTY_PATCH", "at least one property")
        val bad: (String) -> Unit = { v.require(false, it, "INVALID_TYPE", "wrong JSON type") }
        val title = JsonPatch.string(body, "title", bad)
        val text = JsonPatch.string(body, "body", bad)
        if (JsonPatch.has(body, "title")) v.text(title, "title", 200)
        if (JsonPatch.has(body, "body")) v.text(text, "body", 20000)
        val audiences = if (JsonPatch.has(body, "audiences")) {
            val raw = runCatching { Json.decodeFromJsonElement(ListSerializer(AudienceDto.serializer()), body.getValue("audiences")) }
                .getOrElse { bad("audiences"); null }
            AnnouncementAudiences.parse(v, raw)
        } else null
        val publishAt = instant(v, JsonPatch.string(body, "publishAt", bad), "publishAt")
        val expiresAt = instant(v, JsonPatch.string(body, "expiresAt", bad), "expiresAt")
        v.throwIfInvalid()
        return api.tx(p) { c ->
            val row = c.queryOne("SELECT status, version, publish_at, expires_at FROM app.announcements WHERE id = ? AND deleted_at IS NULL FOR UPDATE", id) { rs ->
                Triple(rs.getString("status"), rs.getInt("version"), rs.instantOrNull("publish_at") to rs.instantOrNull("expires_at"))
            } ?: throw ProblemException.notFound()
            Versioning.requireVersion(expectedVersion, row.second)
            val status = row.first
            if (status == "ARCHIVED" || (status == "PUBLISHED" && body.keys.any { it != "expiresAt" })) throw conflict("ANNOUNCEMENT_NOT_EDITABLE")
            val newPublishAt = if (JsonPatch.has(body, "publishAt")) publishAt else row.third.first
            val newExpiresAt = if (JsonPatch.has(body, "expiresAt")) expiresAt else row.third.second
            val check = Validation()
            check.require(newExpiresAt == null || newPublishAt == null || newExpiresAt.isAfter(newPublishAt), "expiresAt", "MUST_BE_AFTER_PUBLISH_AT", "expiresAt must be after publishAt")
            audiences?.let { AnnouncementAudiences.checkTargets(c, check, it) }
            check.throwIfInvalid()
            c.update(
                "UPDATE app.announcements SET title = coalesce(?, title), body = coalesce(?, body), publish_at = ?, expires_at = ?, version = version + 1 WHERE id = ?",
                title?.trim(), text, newPublishAt, newExpiresAt, id,
            )
            audiences?.let { AnnouncementAudiences.replace(c, p.membership.organizationId, id, it) }
            p.audit(c, "ANNOUNCEMENT_UPDATED", "ANNOUNCEMENT", id, requestId)
            load(c, p, id, true)
        }
    }

    /** Soft delete; only drafts (published ones are archived). */
    suspend fun delete(p: TenantPrincipal, id: UUID, expectedVersion: Int, requestId: String?) {
        Authorize.require(p, Permission.ANNOUNCEMENT_MANAGE)
        api.tx(p) { c ->
            val (status, version) = lockRow(c, id)
            Versioning.requireVersion(expectedVersion, version)
            if (status != "DRAFT") throw conflict("ANNOUNCEMENT_NOT_DRAFT")
            c.update("UPDATE app.announcements SET deleted_at = now(), version = version + 1 WHERE id = ?", id)
            p.audit(c, "ANNOUNCEMENT_DELETED", "ANNOUNCEMENT", id, requestId)
        }
    }

    suspend fun publish(p: TenantPrincipal, id: UUID, expectedVersion: Int, sendPush: Boolean, requestId: String?): AnnouncementDto {
        Authorize.require(p, Permission.ANNOUNCEMENT_PUBLISH)
        return api.tx(p) { c ->
            val (status, version) = lockRow(c, id)
            Versioning.requireVersion(expectedVersion, version)
            if (status != "DRAFT") throw conflict("ANNOUNCEMENT_NOT_DRAFT")
            val author = c.queryOne("SELECT created_by_membership_id FROM app.announcements WHERE id = ?", id) { it.uuid("created_by_membership_id") }!!
            val audiences = audiencesOf(c, listOf(id))[id].orEmpty()
            val today = Scopes.today(c)
            c.update(
                "UPDATE app.announcements SET status = 'PUBLISHED', published_at = greatest(coalesce(publish_at, now()), now()), version = version + 1 WHERE id = ?",
                id,
            )
            val count = AnnouncementAudiences.snapshot(c, p.membership.organizationId, id, author, audiences, today)
            // Notification fan-out is done by the outbox relay (not part of this round); the event is recorded here.
            val payload = buildJsonObject {
                put("announcementId", JsonPrimitive(id.toString()))
                put("recipientsCount", JsonPrimitive(count))
                put("sendPush", JsonPrimitive(sendPush))
            }
            c.update(
                "INSERT INTO app.outbox_events (organization_id, aggregate_type, aggregate_id, event_type, payload) VALUES (?, 'ANNOUNCEMENT', ?, 'ANNOUNCEMENT_PUBLISHED', ?::jsonb)",
                p.membership.organizationId, id, payload.toString(),
            )
            p.audit(c, "ANNOUNCEMENT_PUBLISHED", "ANNOUNCEMENT", id, requestId)
            load(c, p, id, true)
        }
    }

    suspend fun archive(p: TenantPrincipal, id: UUID, expectedVersion: Int, requestId: String?): AnnouncementDto {
        Authorize.require(p, Permission.ANNOUNCEMENT_MANAGE)
        return api.tx(p) { c ->
            val (status, version) = lockRow(c, id)
            Versioning.requireVersion(expectedVersion, version)
            if (status != "PUBLISHED") throw conflict("ANNOUNCEMENT_NOT_PUBLISHED")
            c.update("UPDATE app.announcements SET status = 'ARCHIVED', version = version + 1 WHERE id = ?", id)
            p.audit(c, "ANNOUNCEMENT_ARCHIVED", "ANNOUNCEMENT", id, requestId)
            load(c, p, id, true)
        }
    }

    suspend fun recipients(p: TenantPrincipal, id: UUID, readState: String?, sort: String, limit: Int): AnnouncementRecipientPage {
        Authorize.require(p, Permission.ANNOUNCEMENT_MANAGE)
        if (readState != null && readState !in setOf("READ", "UNREAD")) throw invalidQuery("readState", "READ|UNREAD")
        if (sort !in setOf("familyName:asc", "readAt:desc")) throw invalidQuery("sort", "familyName:asc|readAt:desc")
        return api.tx(p) { c ->
            c.queryOne("SELECT 1 AS ok FROM app.announcements WHERE id = ? AND deleted_at IS NULL", id) { true } ?: throw ProblemException.notFound()
            val filter = when (readState) { "READ" -> " AND r.read_at IS NOT NULL"; "UNREAD" -> " AND r.read_at IS NULL"; else -> "" }
            val order = if (sort == "readAt:desc") "r.read_at DESC NULLS LAST, u.family_name" else "u.family_name, u.given_name"
            val items = c.queryList(
                "SELECT r.membership_id, u.given_name, u.family_name, m.role, m.status, r.snapshot_at, r.read_at FROM app.announcement_recipients r " +
                    "JOIN app.organization_memberships m ON m.id = r.membership_id JOIN app.users u ON u.id = m.user_id " +
                    "WHERE r.announcement_id = ?$filter ORDER BY $order, r.membership_id LIMIT ?",
                id, limit,
            ) { rs ->
                AnnouncementRecipientDto(
                    membershipId = rs.uuid("membership_id").toString(), givenName = rs.getString("given_name"), familyName = rs.getString("family_name"),
                    role = rs.getString("role"), snapshotAt = rs.instant("snapshot_at").toString(), readAt = rs.instantOrNull("read_at")?.toString(),
                    stillEntitled = rs.getString("status") == "ACTIVE",
                )
            }
            AnnouncementRecipientPage(items, null)
        }
    }

    /** Explicit read receipt of the caller (idempotent: the first read time is kept). */
    suspend fun markRead(p: TenantPrincipal, id: UUID, requestId: String?): AnnouncementDto {
        Authorize.require(p, Permission.ANNOUNCEMENT_READ)
        return api.tx(p) { c ->
            val visible = c.queryOne(
                "SELECT 1 AS ok FROM app.announcements a JOIN app.announcement_recipients r ON r.announcement_id = a.id AND r.membership_id = ? " +
                    "WHERE a.id = ? AND a.deleted_at IS NULL AND $RECIPIENT_VISIBLE",
                p.membership.membershipId, id,
            ) { true } ?: false
            if (!visible) throw ProblemException.notFound()
            val changed = c.update(
                "UPDATE app.announcement_recipients SET read_at = now() WHERE announcement_id = ? AND membership_id = ? AND read_at IS NULL",
                id, p.membership.membershipId,
            )
            if (changed > 0) p.audit(c, "ANNOUNCEMENT_READ", "ANNOUNCEMENT", id, requestId)
            load(c, p, id, manager(p))
        }
    }

    private fun lockRow(c: Connection, id: UUID): Pair<String, Int> =
        c.queryOne("SELECT status, version FROM app.announcements WHERE id = ? AND deleted_at IS NULL FOR UPDATE", id) { rs ->
            rs.getString("status") to rs.getInt("version")
        } ?: throw ProblemException.notFound()

    private fun load(c: Connection, p: TenantPrincipal, id: UUID, isManager: Boolean): AnnouncementDto {
        val where = if (isManager) "a.id = ? AND a.deleted_at IS NULL"
        else "a.id = ? AND a.deleted_at IS NULL AND $RECIPIENT_VISIBLE AND EXISTS (SELECT 1 FROM app.announcement_recipients r WHERE r.announcement_id = a.id AND r.membership_id = ?)"
        val params = if (isManager) arrayOf<Any?>(p.membership.membershipId, id) else arrayOf<Any?>(p.membership.membershipId, id, p.membership.membershipId)
        val row = c.queryOne("$SELECT WHERE $where", *params) { map(it) } ?: throw ProblemException.notFound()
        return withAudiences(c, listOf(row), isManager).first()
    }

    private data class Row(val dto: AnnouncementDto, val id: UUID)

    private fun map(rs: ResultSet) = Row(
        AnnouncementDto(
            id = rs.uuid("id").toString(), organizationId = rs.uuid("organization_id").toString(), title = rs.getString("title"),
            body = rs.getString("body"), status = rs.getString("status"), publishAt = rs.instantOrNull("publish_at")?.toString(),
            expiresAt = rs.instantOrNull("expires_at")?.toString(), publishedAt = rs.instantOrNull("published_at")?.toString(),
            audiences = emptyList(), attachments = emptyList(), createdByMembershipId = rs.uuid("created_by_membership_id").toString(),
            recipientsCount = rs.getInt("recipients_count"), readCount = rs.getInt("read_count"), readAt = rs.instantOrNull("my_read_at")?.toString(),
            version = rs.getInt("version"), createdAt = rs.instant("created_at").toString(), updatedAt = rs.instant("updated_at").toString(),
        ),
        rs.uuid("id"),
    )

    private fun withAudiences(c: Connection, rows: List<Row>, isManager: Boolean): List<AnnouncementDto> {
        val audiences = audiencesOf(c, rows.map { it.id })
        return rows.map { r ->
            val base = r.dto.copy(audiences = audiences[r.id].orEmpty().map { it.toDto() })
            if (isManager) base else base.copy(recipientsCount = null, readCount = null)
        }
    }

    private fun audiencesOf(c: Connection, ids: List<UUID>): Map<UUID, List<Audience>> {
        if (ids.isEmpty()) return emptyMap()
        return c.queryList(
            "SELECT announcement_id, audience_type, location_id, group_id, membership_id FROM app.announcement_audiences WHERE announcement_id = ANY(?) ORDER BY audience_type, id",
            SqlArray("uuid", ids),
        ) { rs ->
            rs.uuid("announcement_id") to Audience(rs.getString("audience_type"), rs.uuidOrNull("location_id"), rs.uuidOrNull("group_id"), rs.uuidOrNull("membership_id"))
        }.groupBy({ it.first }, { it.second })
    }

    private fun instant(v: Validation, raw: String?, field: String): Instant? {
        if (raw.isNullOrBlank()) return null
        val parsed = runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull() ?: runCatching { Instant.parse(raw) }.getOrNull()
        v.require(parsed != null, field, "INVALID_FORMAT", "RFC 3339 instant")
        return parsed
    }

    companion object {
        val STATUSES = setOf("DRAFT", "PUBLISHED", "ARCHIVED")
        const val RECIPIENT_VISIBLE = "a.status = 'PUBLISHED' AND a.published_at <= now() AND (a.expires_at IS NULL OR a.expires_at > now())"
        private const val SELECT =
            "SELECT a.id, a.organization_id, a.title, a.body, a.status, a.publish_at, a.expires_at, a.published_at, a.created_by_membership_id, " +
                "a.version, a.created_at, a.updated_at, " +
                "(SELECT count(*) FROM app.announcement_recipients r WHERE r.announcement_id = a.id)::int AS recipients_count, " +
                "(SELECT count(*) FROM app.announcement_recipients r WHERE r.announcement_id = a.id AND r.read_at IS NOT NULL)::int AS read_count, " +
                "(SELECT r.read_at FROM app.announcement_recipients r WHERE r.announcement_id = a.id AND r.membership_id = ?) AS my_read_at " +
                "FROM app.announcements a"
    }
}
