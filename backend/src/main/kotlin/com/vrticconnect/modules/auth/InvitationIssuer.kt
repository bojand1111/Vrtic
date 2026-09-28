package com.vrticconnect.modules.auth

import com.vrticconnect.modules.mail.MailKind
import com.vrticconnect.modules.mail.MailMessage
import java.net.URLEncoder
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Invitation issuing primitives shared with the organization-side invitation flow
 * (modules/staff/InvitationService.kt uses the same token kind, validity, table columns and link):
 * only sha256(token) is stored; the raw token travels only in the e-mail link.
 * Must run in a `DbContext.Tenant` of [organizationId] (invitations are tenant rows under RLS).
 */
object InvitationIssuer {
    const val VALIDITY_DAYS = 7L

    data class Issued(val id: UUID, val token: String)

    fun insert(c: Connection, organizationId: UUID, email: String, role: String, childId: UUID?, invitedBy: UUID): Issued {
        val token = Tokens.generate(Tokens.Kind.INVITE)
        val id = UUID.randomUUID()
        c.prepareStatement(
            "INSERT INTO app.invitations (id, organization_id, email, role, child_id, token_hash, invited_by, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { st ->
            st.setObject(1, id); st.setObject(2, organizationId); st.setString(3, email); st.setString(4, role)
            st.setObject(5, childId, java.sql.Types.OTHER); st.setBytes(6, Tokens.sha256(token)); st.setObject(7, invitedBy)
            st.setTimestamp(8, Timestamp.from(Instant.now().plus(VALIDITY_DAYS, ChronoUnit.DAYS)))
            st.executeUpdate()
        }
        return Issued(id, token)
    }

    /** Same message shape as the staff invitation (link `/invite?token=...`, subject `mail.invitation.subject`). */
    fun mail(webOrigin: String, token: String, email: String, locale: String, organizationName: String, role: String, childName: String? = null) =
        MailMessage(
            to = email, kind = MailKind.INVITATION, locale = locale,
            link = webOrigin.trimEnd('/') + "/invite?token=" + URLEncoder.encode(token, Charsets.UTF_8),
            subjectKey = "mail.invitation.subject",
            args = buildMap {
                put("organizationName", organizationName); put("role", role)
                if (childName != null) put("childName", childName)
            },
        )
}
