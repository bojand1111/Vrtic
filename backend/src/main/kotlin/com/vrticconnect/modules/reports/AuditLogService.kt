package com.vrticconnect.modules.reports

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.db.instant
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.uuidOrNull
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import com.vrticconnect.modules.tenant.TenantPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

/**
 * `GET /audit-log` (docs/openapi.yaml listAuditLog, AUDIT_READ = OWNER/ADMIN). RLS already limits rows to the
 * tenant; filters are allowlisted and validated (422). Sort is `occurredAt:desc` with a keyset cursor
 * (base64url of `<epoch micros>:<id>`). Only allowlisted metadata keys with scalar values are returned.
 */
object AuditLogService {

    data class Filter(
        val from: Instant?, val to: Instant?, val action: String?, val entityType: String?, val entityId: UUID?, val actorUserId: UUID?,
        val result: String?, val limit: Int, val cursor: Pair<Instant, Long>?,
    )

    /** Same keys as the writer allowlist in modules/audit/Audit.kt. */
    private val METADATA_ALLOWLIST = setOf("role", "clientKind", "reason", "membershipId", "invitationId", "sessionsRevoked", "emailChanged")
    private val CODE = Regex("^[A-Z][A-Z0-9_]{2,63}$")
    private val RESULTS = setOf("SUCCESS", "DENIED", "FAILED")

    /** `from`/`to` accept an RFC 3339 instant or a date (start of that day / end of that day in the organization timezone). */
    fun parse(q: Parameters, zone: ZoneId): Filter {
        val errors = mutableListOf<FieldError>()
        fun instantParam(name: String, endOfDay: Boolean): Instant? {
            val raw = q[name] ?: return null
            runCatching { return OffsetDateTime.parse(raw).toInstant() }
            runCatching {
                val d = LocalDate.parse(raw)
                return (if (endOfDay) d.plusDays(1) else d).atStartOfDay(zone).toInstant()
            }
            errors += FieldError(name, "INVALID_FORMAT", "RFC 3339 instant or YYYY-MM-DD"); return null
        }
        fun code(name: String): String? = q[name]?.also { if (!CODE.matches(it)) errors += FieldError(name, "INVALID_FORMAT", CODE.pattern) }
        fun uuid(name: String): UUID? = q[name]?.let { raw -> runCatching { UUID.fromString(raw) }.getOrElse { errors += FieldError(name, "INVALID_FORMAT", "UUID"); null } }
        val from = instantParam("from", false)
        val to = instantParam("to", true)
        val action = code("action")
        val entityType = code("entityType")
        val entityId = uuid("entityId")
        val actor = uuid("actorUserId")
        val result = q["result"]?.also { if (it !in RESULTS) errors += FieldError("result", "INVALID_VALUE", RESULTS.joinToString("|")) }
        q["sort"]?.let { if (it != "occurredAt:desc") errors += FieldError("sort", "INVALID_VALUE", "occurredAt:desc") }
        val limit = q["limit"]?.let { raw -> raw.toIntOrNull()?.takeIf { it in 1..100 } ?: run { errors += FieldError("limit", "INVALID_RANGE", "1..100"); 50 } } ?: 50
        val cursor = q["cursor"]?.let { raw -> decodeCursor(raw) ?: run { errors += FieldError("cursor", "INVALID_CURSOR", "cursor is not valid for this listing"); null } }
        if (errors.isNotEmpty()) {
            throw ProblemException(status = HttpStatusCode.UnprocessableEntity, type = ProblemTypes.VALIDATION, title = "Validation failed", errors = errors)
        }
        return Filter(from, to, action, entityType, entityId, actor, result, limit, cursor)
    }

    fun list(c: Connection, principal: TenantPrincipal, f: Filter): AuditLogEntryPage {
        Authorize.require(principal, Permission.AUDIT_READ)
        val where = mutableListOf("a.organization_id = app.current_organization_id()")
        val params = mutableListOf<Any?>()
        f.from?.let { where += "a.occurred_at >= ?"; params += it }
        f.to?.let { where += "a.occurred_at < ?"; params += it }
        f.action?.let { where += "a.action = ?"; params += it }
        f.entityType?.let { where += "a.entity_type = ?"; params += it }
        f.entityId?.let { where += "a.entity_id = ?"; params += it }
        f.actorUserId?.let { where += "a.actor_user_id = ?"; params += it }
        f.result?.let { where += "a.result = ?"; params += it }
        f.cursor?.let { (at, id) -> where += "(a.occurred_at, a.id) < (?, ?)"; params += at; params += id }
        params += f.limit + 1
        val rows = c.queryList(
            "SELECT a.id, a.occurred_at, a.actor_user_id, a.actor_membership_id, u.given_name, u.family_name, a.action, a.entity_type, a.entity_id, " +
                "a.request_id, a.result, a.purpose, a.metadata::text AS metadata FROM app.audit_log a LEFT JOIN app.users u ON u.id = a.actor_user_id " +
                "WHERE ${where.joinToString(" AND ")} ORDER BY a.occurred_at DESC, a.id DESC LIMIT ?",
            *params.toTypedArray(),
        ) { rs ->
            val given = rs.getString("given_name")
            AuditLogEntryDto(
                id = rs.getLong("id"), occurredAt = rs.instant("occurred_at").toString(), actorUserId = rs.uuidOrNull("actor_user_id")?.toString(),
                actorMembershipId = rs.uuidOrNull("actor_membership_id")?.toString(), actorName = given?.let { "$it ${rs.getString("family_name")}" },
                action = rs.getString("action"), entityType = rs.getString("entity_type"), entityId = rs.uuidOrNull("entity_id")?.toString(),
                requestId = rs.getString("request_id"), result = rs.getString("result"), purpose = rs.getString("purpose"),
                metadata = allowlisted(rs.getString("metadata")),
            )
        }
        val items = rows.take(f.limit)
        val next = if (rows.size > f.limit) items.last().let { encodeCursor(Instant.parse(it.occurredAt), it.id) } else null
        return AuditLogEntryPage(items, next)
    }

    fun orgZone(c: Connection): ZoneId =
        c.queryOne("SELECT timezone FROM app.organizations WHERE id = app.current_organization_id()") { rs -> ZoneId.of(rs.getString("timezone")) } ?: ZoneId.of("Europe/Belgrade")

    fun allowlisted(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return JsonObject(emptyMap())
        val parsed = Json.parseToJsonElement(raw) as? JsonObject ?: return JsonObject(emptyMap())
        return JsonObject(parsed.filter { (k, v) -> k in METADATA_ALLOWLIST && v is JsonPrimitive })
    }

    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun encodeCursor(at: Instant, id: Long): String = encoder.encodeToString("${at.epochSecond * 1_000_000 + at.nano / 1_000}:$id".toByteArray(Charsets.US_ASCII))

    fun decodeCursor(raw: String): Pair<Instant, Long>? {
        if (raw.isEmpty() || raw.length > 128) return null
        val text = runCatching { String(Base64.getUrlDecoder().decode(raw), Charsets.US_ASCII) }.getOrNull() ?: return null
        val parts = text.split(':')
        if (parts.size != 2) return null
        val micros = parts[0].toLongOrNull() ?: return null
        val id = parts[1].toLongOrNull() ?: return null
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000) to id
    }
}
