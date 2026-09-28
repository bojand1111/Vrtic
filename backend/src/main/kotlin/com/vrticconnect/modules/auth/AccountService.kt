package com.vrticconnect.modules.auth

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.http.AuthRateLimits
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import com.vrticconnect.http.RateLimiter
import com.vrticconnect.http.clientIp
import com.vrticconnect.modules.audit.Audit
import com.vrticconnect.modules.mail.MailKind
import com.vrticconnect.modules.mail.MailMessage
import com.vrticconnect.modules.mail.MailSender
import com.vrticconnect.modules.tenant.MyMembership
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Serializable
data class RegisterRequest(
    val invitationToken: String,
    val password: String,
    val givenName: String,
    val familyName: String,
    val preferredLocale: String? = null,
    val device: LoginDevice,
)

@Serializable
data class InvitationPreview(
    val organization: com.vrticconnect.modules.tenant.OrganizationRef,
    val role: String,
    val email: String,
    val childGivenName: String? = null,
    val expiresAt: String,
    val requiresRegistration: Boolean,
)

@Serializable data class AcceptInvitationRequest(val invitationToken: String)
@Serializable data class VerifyEmailRequest(val token: String)
@Serializable data class EmailRequest(val email: String)
@Serializable data class ResetPasswordRequest(val token: String, val newPassword: String)

/**
 * E02-B04 (invitation registration/acceptance), E02-B05 (e-mail verification), E02-B09 (password
 * reset). All token lookups run in the auth-mode DB context and are keyed by sha256(token); the
 * raw token exists only in the e-mail link. Responses never reveal whether an account exists.
 */
class AccountService(
    private val database: Database,
    private val passwordHasher: PasswordHasher,
    private val loginService: LoginService,
    private val mailSender: MailSender,
    private val config: AppConfig,
    private val rateLimiter: RateLimiter = RateLimiter(),
) {
    private fun limitByIp(call: ApplicationCall) =
        rateLimiter.require("auth-ip:" + call.clientIp(config.trustProxyHeaders), AuthRateLimits.IP_PER_MINUTE, AuthRateLimits.IP_WINDOW)

    private fun limitByEmail(scope: String, email: String) =
        rateLimiter.require("$scope:" + Tokens.sha256(email.lowercase()).joinToString("") { "%02x".format(it) }, AuthRateLimits.EMAIL_PER_HOUR, AuthRateLimits.EMAIL_WINDOW)

    // ------------------------------------------------------------------ invitations (E02-B04)

    suspend fun previewInvitation(call: ApplicationCall) {
        limitByIp(call)
        val token = call.parameters["invitationToken"]?.takeIf { Tokens.isInviteToken(it) } ?: throw tokenInvalid()
        val preview = database.transaction(DbContext.Auth()) { c ->
            val inv = findInvitation(c, token, forUpdate = false) ?: throw tokenInvalid()
            val org = findOrganization(c, inv.organizationId)
            InvitationPreview(
                organization = org,
                role = inv.role,
                email = inv.email,
                childGivenName = null, // children table arrives with EPIC 05
                expiresAt = inv.expiresAt.toString(),
                requiresRegistration = findUserIdByEmail(c, inv.email) == null,
            )
        }
        call.respond(preview)
    }

    /** New account through an invitation link: the token proves control of the invited mailbox. */
    suspend fun register(call: ApplicationCall) {
        limitByIp(call)
        val request = call.receive<RegisterRequest>()
        val clientKind = request.device.clientKind.uppercase()
        val givenName = request.givenName.trim()
        val familyName = request.familyName.trim()
        val locale = request.preferredLocale ?: "sr-Latn"
        val errors = buildList {
            if (givenName.isEmpty() || givenName.length > 100) add(FieldError("givenName", "INVALID_LENGTH", "1..100 characters"))
            if (familyName.isEmpty() || familyName.length > 100) add(FieldError("familyName", "INVALID_LENGTH", "1..100 characters"))
            if (locale !in LOCALES) add(FieldError("preferredLocale", "INVALID_ENUM", "sr-Latn, sr-Cyrl or en"))
            if (clientKind !in CLIENT_KINDS) add(FieldError("device.clientKind", "INVALID_ENUM", "WEB, ANDROID or IOS"))
        }
        if (errors.isNotEmpty()) throw validation(errors)
        if (!Tokens.isInviteToken(request.invitationToken)) throw tokenInvalid()

        val passwordChars = request.password.toCharArray()
        try {
            val result = database.transaction(DbContext.Auth()) { c ->
                val inv = findInvitation(c, request.invitationToken, forUpdate = true) ?: throw tokenInvalid()
                PasswordPolicy.validateOrThrow(request.password, inv.email)
                if (findUserIdByEmail(c, inv.email) != null) {
                    throw ProblemException(
                        status = HttpStatusCode.Conflict, type = ProblemTypes.CONFLICT,
                        title = "E-mail already registered", detail = "EMAIL_ALREADY_REGISTERED",
                    )
                }
                val userId = UUID.randomUUID()
                c.prepareStatement(
                    "INSERT INTO app.users (id, email, email_verified_at, password_hash, password_updated_at, given_name, family_name, preferred_locale) " +
                        "VALUES (?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, ?, ?)",
                ).use { st ->
                    st.setObject(1, userId); st.setString(2, inv.email); st.setString(3, passwordHasher.hash(passwordChars))
                    st.setString(4, givenName); st.setString(5, familyName); st.setString(6, locale); st.executeUpdate()
                }
                val membershipId = acceptInvitationRow(c, inv, userId, call.callId)
                Audit.record(c, "USER_REGISTERED", "USER", userId, actorUserId = userId, requestId = call.callId, metadata = mapOf("invitationId" to inv.id.toString(), "membershipId" to membershipId.toString()))
                loginService.issueSession(c, userId, clientKind, request.device)
            }
            loginService.respondAuth(call, result, clientKind, HttpStatusCode.Created)
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    /** Existing, signed-in user accepts an invitation addressed to their own e-mail. */
    suspend fun acceptAsExistingUser(call: ApplicationCall, user: AuthenticatedUser): MyMembership {
        val request = call.receive<AcceptInvitationRequest>()
        if (!Tokens.isInviteToken(request.invitationToken)) throw tokenInvalid()
        val membershipId = database.transaction(DbContext.Auth(user.userId)) { c ->
            val inv = findInvitation(c, request.invitationToken, forUpdate = true) ?: throw tokenInvalid()
            val userEmail = c.prepareStatement("SELECT email FROM app.users WHERE id = ? AND status = 'ACTIVE' AND deleted_at IS NULL").use { st ->
                st.setObject(1, user.userId)
                st.executeQuery().use { rs -> if (rs.next()) rs.getString("email") else null }
            } ?: throw ProblemException.unauthenticated()
            // An invitation for another mailbox is invisible to this user (404), not forbidden (403).
            if (!userEmail.equals(inv.email, ignoreCase = true)) throw tokenInvalid()
            acceptInvitationRow(c, inv, user.userId, call.callId)
        }
        return membershipId.let { id ->
            database.transaction(DbContext.User(user.userId)) { c -> readMyMembership(c, user.userId, id) }
        } ?: throw ProblemException.notFound()
    }

    private fun acceptInvitationRow(c: Connection, inv: InvitationRow, userId: UUID, requestId: String?): UUID {
        // Same (organization, user, role) may exist as INVITED/REVOKED from an earlier round: reactivate it.
        val existing = c.prepareStatement(
            "SELECT id FROM app.organization_memberships WHERE organization_id = ? AND user_id = ? AND role = ?",
        ).use { st ->
            st.setObject(1, inv.organizationId); st.setObject(2, userId); st.setString(3, inv.role)
            st.executeQuery().use { rs -> if (rs.next()) rs.getObject("id", UUID::class.java) else null }
        }
        val membershipId = existing ?: UUID.randomUUID()
        if (existing == null) {
            c.prepareStatement(
                "INSERT INTO app.organization_memberships (id, organization_id, user_id, role, status, invited_by, accepted_at) VALUES (?, ?, ?, ?, 'ACTIVE', ?, CURRENT_TIMESTAMP)",
            ).use { st ->
                st.setObject(1, membershipId); st.setObject(2, inv.organizationId); st.setObject(3, userId)
                st.setString(4, inv.role); st.setObject(5, inv.invitedBy); st.executeUpdate()
            }
        } else {
            c.prepareStatement(
                "UPDATE app.organization_memberships SET status = 'ACTIVE', accepted_at = CURRENT_TIMESTAMP, revoked_at = NULL, revoke_reason = NULL, suspended_at = NULL WHERE id = ?",
            ).use { st -> st.setObject(1, membershipId); st.executeUpdate() }
        }
        c.prepareStatement("UPDATE app.invitations SET accepted_at = CURRENT_TIMESTAMP, accepted_user_id = ? WHERE id = ?").use { st ->
            st.setObject(1, userId); st.setObject(2, inv.id); st.executeUpdate()
        }
        // PARENT invitations carry a child: the PENDING guardian link is created when EPIC 05 adds `guardians`.
        Audit.record(
            c, "INVITATION_ACCEPTED", "INVITATION", inv.id, actorUserId = userId, organizationId = inv.organizationId,
            actorMembershipId = membershipId, requestId = requestId, metadata = mapOf("role" to inv.role, "membershipId" to membershipId.toString()),
        )
        return membershipId
    }

    // ------------------------------------------------------------------ e-mail verification (E02-B05)

    suspend fun verifyEmail(call: ApplicationCall) {
        limitByIp(call)
        val request = call.receive<VerifyEmailRequest>()
        if (!Tokens.isVerifyToken(request.token)) throw tokenInvalid()
        database.transaction(DbContext.Auth()) { c ->
            val row = c.prepareStatement(
                "SELECT id, user_id, new_email FROM app.email_verification_tokens WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP FOR UPDATE",
            ).use { st ->
                st.setBytes(1, Tokens.sha256(request.token))
                st.executeQuery().use { rs -> if (rs.next()) Triple(rs.getObject("id", UUID::class.java), rs.getObject("user_id", UUID::class.java), rs.getString("new_email")) else null }
            } ?: throw tokenInvalid()
            val (tokenId, userId, newEmail) = row
            c.prepareStatement("UPDATE app.email_verification_tokens SET consumed_at = CURRENT_TIMESTAMP WHERE id = ?").use { st -> st.setObject(1, tokenId); st.executeUpdate() }
            if (newEmail != null) {
                c.prepareStatement("UPDATE app.users SET email = ?, email_verified_at = CURRENT_TIMESTAMP WHERE id = ?").use { st -> st.setString(1, newEmail); st.setObject(2, userId); st.executeUpdate() }
            } else {
                c.prepareStatement("UPDATE app.users SET email_verified_at = COALESCE(email_verified_at, CURRENT_TIMESTAMP) WHERE id = ?").use { st -> st.setObject(1, userId); st.executeUpdate() }
            }
            Audit.record(c, "EMAIL_VERIFIED", "USER", userId, actorUserId = userId, requestId = call.callId, metadata = mapOf("emailChanged" to (newEmail != null).toString()))
        }
        call.respond(HttpStatusCode.NoContent)
    }

    /**
     * Always 202 (unauthenticated, `{email}`): an unverified account cannot log in, so it cannot hold
     * a session. A fresh 24 h link is sent only when the account exists and is still unverified.
     */
    suspend fun resendVerification(call: ApplicationCall) {
        limitByIp(call)
        val request = call.receive<EmailRequest>()
        val email = request.email.trim()
        limitByEmail("verify-email", email)
        val mail = if (email.isEmpty() || email.length > 254 || !email.contains('@')) null else database.transaction(DbContext.Auth()) { c ->
            val row = c.prepareStatement("SELECT id, email, email_verified_at, preferred_locale FROM app.users WHERE email = ? AND status = 'ACTIVE' AND deleted_at IS NULL").use { st ->
                st.setString(1, email)
                st.executeQuery().use { rs -> if (rs.next()) listOf(rs.getObject("id", UUID::class.java), rs.getString("email"), rs.getTimestamp("email_verified_at"), rs.getString("preferred_locale")) else null }
            } ?: return@transaction null
            if (row[2] != null) return@transaction null
            val token = Tokens.generate(Tokens.Kind.VERIFY)
            c.prepareStatement("INSERT INTO app.email_verification_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)").use { st ->
                st.setObject(1, row[0] as UUID); st.setBytes(2, Tokens.sha256(token))
                st.setTimestamp(3, Timestamp.from(Instant.now().plus(24, ChronoUnit.HOURS))); st.executeUpdate()
            }
            MailMessage(row[1] as String, MailKind.EMAIL_VERIFICATION, row[3] as String, link("/verify-email", token), "mail.verify.subject")
        }
        mail?.let(mailSender::send)
        call.respond(HttpStatusCode.Accepted)
    }

    // ------------------------------------------------------------------ password reset (E02-B09)

    /** Always 202 in constant shape; a token is generated in every case so timing does not reveal accounts. */
    suspend fun forgotPassword(call: ApplicationCall) {
        limitByIp(call)
        val request = call.receive<EmailRequest>()
        val email = request.email.trim()
        limitByEmail("forgot-password", email)
        val token = Tokens.generate(Tokens.Kind.RESET)
        val tokenHash = Tokens.sha256(token)
        val mail = if (email.isEmpty() || email.length > 254 || !email.contains('@')) null else database.transaction(DbContext.Auth()) { c ->
            val row = c.prepareStatement("SELECT id, email, preferred_locale FROM app.users WHERE email = ? AND status <> 'DISABLED' AND deleted_at IS NULL").use { st ->
                st.setString(1, email)
                st.executeQuery().use { rs -> if (rs.next()) Triple(rs.getObject("id", UUID::class.java), rs.getString("email"), rs.getString("preferred_locale")) else null }
            } ?: return@transaction null
            c.prepareStatement("INSERT INTO app.password_reset_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)").use { st ->
                st.setObject(1, row.first); st.setBytes(2, tokenHash)
                st.setTimestamp(3, Timestamp.from(Instant.now().plus(15, ChronoUnit.MINUTES))); st.executeUpdate()
            }
            Audit.record(c, "PASSWORD_RESET_REQUESTED", "USER", row.first, actorUserId = null, requestId = call.callId)
            MailMessage(row.second, MailKind.PASSWORD_RESET, row.third, link("/reset-password", token), "mail.reset.subject")
        }
        mail?.let(mailSender::send)
        call.respond(HttpStatusCode.Accepted)
    }

    suspend fun resetPassword(call: ApplicationCall) {
        limitByIp(call)
        val request = call.receive<ResetPasswordRequest>()
        if (!Tokens.isResetToken(request.token)) throw tokenInvalid()
        val passwordChars = request.newPassword.toCharArray()
        try {
            database.transaction(DbContext.Auth()) { c ->
                val row = c.prepareStatement(
                    "SELECT t.id, t.user_id, u.email FROM app.password_reset_tokens t JOIN app.users u ON u.id = t.user_id " +
                        "WHERE t.token_hash = ? AND t.consumed_at IS NULL AND t.expires_at > CURRENT_TIMESTAMP AND u.deleted_at IS NULL FOR UPDATE OF t",
                ).use { st ->
                    st.setBytes(1, Tokens.sha256(request.token))
                    st.executeQuery().use { rs -> if (rs.next()) Triple(rs.getObject("id", UUID::class.java), rs.getObject("user_id", UUID::class.java), rs.getString("email")) else null }
                } ?: throw tokenInvalid()
                val (tokenId, userId, email) = row
                PasswordPolicy.validateOrThrow(request.newPassword, email, field = "newPassword")
                c.prepareStatement("UPDATE app.password_reset_tokens SET consumed_at = CURRENT_TIMESTAMP WHERE id = ?").use { st -> st.setObject(1, tokenId); st.executeUpdate() }
                c.prepareStatement(
                    "UPDATE app.users SET password_hash = ?, password_updated_at = CURRENT_TIMESTAMP, failed_login_count = 0, locked_until = NULL, " +
                        "email_verified_at = COALESCE(email_verified_at, CURRENT_TIMESTAMP) WHERE id = ?",
                ).use { st -> st.setString(1, passwordHasher.hash(passwordChars)); st.setObject(2, userId); st.executeUpdate() }
                val revoked = c.prepareStatement("UPDATE app.sessions SET revoked_at = CURRENT_TIMESTAMP, revoke_reason = 'PASSWORD_CHANGED' WHERE user_id = ? AND revoked_at IS NULL").use { st -> st.setObject(1, userId); st.executeUpdate() }
                c.prepareStatement("UPDATE app.access_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE revoked_at IS NULL AND session_id IN (SELECT id FROM app.sessions WHERE user_id = ?)").use { st -> st.setObject(1, userId); st.executeUpdate() }
                c.prepareStatement("UPDATE app.refresh_tokens SET consumed_at = CURRENT_TIMESTAMP WHERE consumed_at IS NULL AND session_id IN (SELECT id FROM app.sessions WHERE user_id = ?)").use { st -> st.setObject(1, userId); st.executeUpdate() }
                Audit.record(c, "PASSWORD_RESET", "USER", userId, actorUserId = userId, requestId = call.callId, metadata = mapOf("sessionsRevoked" to revoked.toString()))
            }
        } finally {
            passwordChars.fill('\u0000')
        }
        call.respond(HttpStatusCode.NoContent)
    }

    // ------------------------------------------------------------------ helpers

    private data class InvitationRow(val id: UUID, val organizationId: UUID, val email: String, val role: String, val invitedBy: UUID, val expiresAt: Instant)

    private fun findInvitation(c: Connection, token: String, forUpdate: Boolean): InvitationRow? =
        c.prepareStatement(
            "SELECT id, organization_id, email, role, invited_by, expires_at FROM app.invitations " +
                "WHERE token_hash = ? AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP" + (if (forUpdate) " FOR UPDATE" else ""),
        ).use { st ->
            st.setBytes(1, Tokens.sha256(token))
            st.executeQuery().use { rs ->
                if (!rs.next()) null else InvitationRow(
                    rs.getObject("id", UUID::class.java), rs.getObject("organization_id", UUID::class.java), rs.getString("email"),
                    rs.getString("role"), rs.getObject("invited_by", UUID::class.java), rs.getTimestamp("expires_at").toInstant(),
                )
            }
        }

    private fun findOrganization(c: Connection, id: UUID): com.vrticconnect.modules.tenant.OrganizationRef =
        c.prepareStatement("SELECT id, slug, name, timezone, default_locale, status FROM app.organizations WHERE id = ? AND deleted_at IS NULL").use { st ->
            st.setObject(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) throw tokenInvalid()
                com.vrticconnect.modules.tenant.OrganizationRef(
                    rs.getObject("id", UUID::class.java).toString(), rs.getString("slug"), rs.getString("name"),
                    rs.getString("timezone"), rs.getString("default_locale"), rs.getString("status"),
                )
            }
        }

    private fun findUserIdByEmail(c: Connection, email: String): UUID? =
        c.prepareStatement("SELECT id FROM app.users WHERE email = ? AND deleted_at IS NULL").use { st ->
            st.setString(1, email)
            st.executeQuery().use { rs -> if (rs.next()) rs.getObject("id", UUID::class.java) else null }
        }

    private fun readMyMembership(c: Connection, userId: UUID, membershipId: UUID): MyMembership? =
        c.prepareStatement(
            "SELECT m.id, m.role, m.status, m.accepted_at, o.id AS org_id, o.slug, o.name, o.timezone, o.default_locale, o.status AS org_status, e.id AS employee_id " +
                "FROM app.organization_memberships m JOIN app.organizations o ON o.id = m.organization_id LEFT JOIN app.employees e ON e.membership_id = m.id " +
                "WHERE m.id = ? AND m.user_id = ?",
        ).use { st ->
            st.setObject(1, membershipId); st.setObject(2, userId)
            st.executeQuery().use { rs ->
                if (!rs.next()) null else MyMembership(
                    id = rs.getObject("id", UUID::class.java).toString(),
                    organization = com.vrticconnect.modules.tenant.OrganizationRef(
                        rs.getObject("org_id", UUID::class.java).toString(), rs.getString("slug"), rs.getString("name"),
                        rs.getString("timezone"), rs.getString("default_locale"), rs.getString("org_status"),
                    ),
                    role = rs.getString("role"), status = rs.getString("status"), permissions = emptyList(),
                    acceptedAt = rs.getTimestamp("accepted_at")?.toInstant()?.toString(),
                    employeeId = rs.getObject("employee_id", UUID::class.java)?.toString(),
                )
            }
        }

    private fun link(path: String, token: String) = config.webOrigin.trimEnd('/') + path + "?token=" + URLEncoder.encode(token, Charsets.UTF_8)

    private fun tokenInvalid() = ProblemException(status = HttpStatusCode.NotFound, type = ProblemTypes.NOT_FOUND, title = "Not found", detail = "TOKEN_INVALID")

    private fun validation(errors: List<FieldError>) = ProblemException(status = HttpStatusCode.UnprocessableEntity, type = ProblemTypes.VALIDATION, title = "Validation failed", errors = errors)

    private companion object {
        val CLIENT_KINDS = setOf("WEB", "ANDROID", "IOS")
        val LOCALES = setOf("sr-Latn", "sr-Cyrl", "en")
    }
}
