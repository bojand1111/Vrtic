package com.vrticconnect.modules.staff

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.PermissionMatrix
import com.vrticconnect.authz.Role
import com.vrticconnect.db.instant
import com.vrticconnect.db.instantOrNull
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.db.uuidOrNull
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import com.vrticconnect.http.conflict
import com.vrticconnect.modules.auth.Tokens
import com.vrticconnect.modules.locations.throwIfErrors
import com.vrticconnect.modules.mail.MailKind
import com.vrticconnect.modules.mail.MailMessage
import com.vrticconnect.modules.mail.MailSender
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.TenantPrincipal
import com.vrticconnect.modules.tenant.audit
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** docs/openapi.yaml `Invitation` plus `childName` for guardian invitations. Never carries the token. */
@Serializable
data class InvitationDto(
    val id: String,
    val organizationId: String,
    val email: String,
    val role: String,
    val childId: String?,
    val childName: String?,
    val invitedBy: String,
    val expiresAt: String,
    val acceptedAt: String?,
    val revokedAt: String?,
    val status: String,
    val createdAt: String,
)

@Serializable
data class InvitationPage(val items: List<InvitationDto>, val nextCursor: String? = null)

/**
 * `StaffInvitationCreate` extended with PARENT + `childId` (guardian invitation from the admin
 * screen). `displayName`/`jobTitle` are accepted for contract compatibility but not stored: the
 * invitations table has no column for them; the staff profile takes the registered user's name.
 */
@Serializable
data class InvitationCreate(
    val email: String? = null,
    val role: String? = null,
    val childId: String? = null,
    val displayName: String? = null,
    val jobTitle: String? = null,
)

data class InvitationFilter(val status: String?, val role: String?, val childId: UUID?, val limit: Int)

/**
 * Invitations (E02-B04 counterpart on the organization side). MEMBER_INVITE; the invited role must
 * be assignable by the actor (OWNER is never invitable -> 403 ROLE_ESCALATION). Only sha256(token)
 * is stored; the raw token travels only in the e-mail link (DEV: printed by LoggingMailSender).
 */
class InvitationService(private val api: TenantApi, private val mailSender: MailSender, private val webOrigin: String) {

    suspend fun list(p: TenantPrincipal, f: InvitationFilter): InvitationPage {
        Authorize.require(p, Permission.MEMBER_INVITE)
        return api.tx(p) { c ->
            InvitationPage(
                c.queryList(
                    "SELECT * FROM ($SELECT) x WHERE x.status = ? AND (?::text IS NULL OR x.role = ?::text) AND (?::uuid IS NULL OR x.child_id = ?::uuid) " +
                        "ORDER BY x.created_at DESC, x.id LIMIT ?",
                    f.status ?: "PENDING", f.role, f.role, f.childId, f.childId, f.limit,
                ) { map(it) },
            )
        }
    }

    suspend fun create(p: TenantPrincipal, body: InvitationCreate, requestId: String?): InvitationDto {
        Authorize.require(p, Permission.MEMBER_INVITE)
        val errors = mutableListOf<FieldError>()
        val email = body.email?.trim()?.lowercase()
        if (email.isNullOrEmpty()) errors += FieldError("email", "REQUIRED", "email is required")
        else if (email.length > 254 || !EMAIL.matches(email)) errors += FieldError("email", "INVALID_FORMAT", "e-mail address")
        val role = body.role?.let { r -> Role.entries.firstOrNull { it.name == r } }
        if (body.role.isNullOrBlank()) errors += FieldError("role", "REQUIRED", "role is required")
        else if (role == null) errors += FieldError("role", "INVALID_VALUE", "OWNER|ADMIN|TEACHER|PARENT")
        val childId = parseUuid(errors, body.childId, "childId", required = role == Role.PARENT)
        if (role != null && role != Role.PARENT && body.childId != null) errors += FieldError("childId", "NOT_ALLOWED", "only for PARENT invitations")
        maxLen(errors, body.displayName, "displayName", 200); maxLen(errors, body.jobTitle, "jobTitle", 100)
        throwIfErrors(errors)
        if (role!! !in PermissionMatrix.assignableRoles(p.membership.role)) {
            throw ProblemException(status = HttpStatusCode.Forbidden, type = ProblemTypes.FORBIDDEN, title = "Forbidden", detail = "ROLE_ESCALATION")
        }
        val token = Tokens.generate(Tokens.Kind.INVITE)
        val result = api.tx(p) { c ->
            val childName = if (childId == null) null else {
                c.queryOne("SELECT given_name, family_name FROM app.children WHERE id = ? AND deleted_at IS NULL", childId) { rs ->
                    "${rs.getString("given_name")} ${rs.getString("family_name")}"
                } ?: throw ProblemException.notFound()
            }
            val pending = c.queryOne(
                "SELECT 1 AS x FROM app.invitations WHERE email = ? AND role = ? AND child_id IS NOT DISTINCT FROM ?::uuid " +
                    "AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now()",
                email, role.name, childId,
            ) { true } ?: false
            if (pending) throw conflict("INVITATION_PENDING")
            val existingMembership = c.queryOne(
                "SELECT m.id FROM app.organization_memberships m JOIN app.users u ON u.id = m.user_id " +
                    "WHERE m.organization_id = app.current_organization_id() AND u.email = ? AND m.role = ? AND m.status = 'ACTIVE'",
                email, role.name,
            ) { it.uuid("id") }
            if (existingMembership != null) {
                if (role != Role.PARENT) throw conflict("MEMBERSHIP_EXISTS")
                // A parent may be invited for a further child; the same child twice is refused.
                val linked = c.queryOne(
                    "SELECT 1 AS x FROM app.guardians WHERE membership_id = ? AND child_id = ? AND status <> 'REVOKED'",
                    existingMembership, childId,
                ) { true } ?: false
                if (linked) throw conflict("GUARDIAN_LINK_EXISTS")
            }
            val id = UUID.randomUUID()
            c.prepareStatement(
                "INSERT INTO app.invitations (id, organization_id, email, role, child_id, token_hash, invited_by, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { st ->
                st.setObject(1, id); st.setObject(2, p.membership.organizationId); st.setString(3, email); st.setString(4, role.name)
                st.setObject(5, childId, java.sql.Types.OTHER); st.setBytes(6, Tokens.sha256(token)); st.setObject(7, p.user.userId)
                st.setTimestamp(8, Timestamp.from(Instant.now().plus(VALIDITY_DAYS, ChronoUnit.DAYS)))
                st.executeUpdate()
            }
            p.audit(c, "INVITATION_CREATED", "INVITATION", id, requestId, mapOf("role" to role.name, "invitationId" to id.toString()))
            val org = c.queryOne("SELECT name, default_locale FROM app.organizations WHERE id = app.current_organization_id()") { rs ->
                rs.getString("name") to rs.getString("default_locale")
            } ?: throw ProblemException.notFound()
            val mail = MailMessage(
                to = email!!, kind = MailKind.INVITATION, locale = org.second,
                link = webOrigin.trimEnd('/') + "/invite?token=" + URLEncoder.encode(token, Charsets.UTF_8),
                subjectKey = "mail.invitation.subject",
                args = buildMap {
                    put("organizationName", org.first); put("role", role.name)
                    if (childName != null) put("childName", childName)
                },
            )
            read(c, id)!! to mail
        }
        // After commit: a failing mail provider must not leave a half-written invitation behind silently;
        // the error propagates (500) and the pending invitation can be revoked and re-sent.
        mailSender.send(result.second)
        return result.first
    }

    suspend fun revoke(p: TenantPrincipal, id: UUID, requestId: String?): InvitationDto {
        Authorize.require(p, Permission.MEMBER_INVITE)
        return api.tx(p) { c ->
            val current = read(c, id) ?: throw ProblemException.notFound()
            when (current.status) {
                "ACCEPTED" -> throw conflict("INVITATION_ALREADY_ACCEPTED")
                "REVOKED" -> throw conflict("INVITATION_ALREADY_REVOKED")
            }
            c.update("UPDATE app.invitations SET revoked_at = now() WHERE id = ?", id)
            p.audit(c, "INVITATION_REVOKED", "INVITATION", id, requestId, mapOf("role" to current.role, "invitationId" to id.toString()))
            read(c, id)!!
        }
    }

    private fun read(c: Connection, id: UUID): InvitationDto? = c.queryOne("SELECT * FROM ($SELECT) x WHERE x.id = ?", id) { map(it) }

    private fun map(rs: ResultSet) = InvitationDto(
        id = rs.uuid("id").toString(),
        organizationId = rs.uuid("organization_id").toString(),
        email = rs.getString("email"),
        role = rs.getString("role"),
        childId = rs.uuidOrNull("child_id")?.toString(),
        childName = rs.getString("child_name"),
        invitedBy = rs.uuid("invited_by").toString(),
        expiresAt = rs.instant("expires_at").toString(),
        acceptedAt = rs.instantOrNull("accepted_at")?.toString(),
        revokedAt = rs.instantOrNull("revoked_at")?.toString(),
        status = rs.getString("status"),
        createdAt = rs.instant("created_at").toString(),
    )

    private companion object {
        const val VALIDITY_DAYS = 7L
        val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        const val SELECT =
            "SELECT i.id, i.organization_id, i.email::text AS email, i.role, i.child_id, i.invited_by, i.expires_at, i.accepted_at, i.revoked_at, i.created_at, " +
                "CASE WHEN i.accepted_at IS NOT NULL THEN 'ACCEPTED' WHEN i.revoked_at IS NOT NULL THEN 'REVOKED' " +
                "WHEN i.expires_at <= now() THEN 'EXPIRED' ELSE 'PENDING' END AS status, " +
                "ch.given_name || ' ' || ch.family_name AS child_name " +
                "FROM app.invitations i LEFT JOIN app.children ch ON ch.id = i.child_id " +
                "WHERE i.organization_id = app.current_organization_id()"
    }
}
