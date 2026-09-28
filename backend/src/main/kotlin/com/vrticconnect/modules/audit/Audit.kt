package com.vrticconnect.modules.audit

import java.sql.Connection
import java.util.UUID

/**
 * Minimal append-only audit writer (docs/SECURITY.md 7). Writes inside the caller's transaction so
 * the audit row commits or rolls back together with the business change. `metadata` accepts only
 * allowlisted scalar keys; never payloads, tokens or PII (E02-B18 extends this with outbox events).
 */
object Audit {
    enum class Result { SUCCESS, DENIED, FAILED }

    fun record(
        connection: Connection,
        action: String,
        entityType: String,
        entityId: UUID?,
        actorUserId: UUID?,
        organizationId: UUID? = null,
        actorMembershipId: UUID? = null,
        requestId: String? = null,
        result: Result = Result.SUCCESS,
        purpose: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) {
        require(metadata.keys.all { it in ALLOWED_METADATA_KEYS }) { "audit metadata key not allowlisted: ${metadata.keys}" }
        val json = if (metadata.isEmpty()) "{}" else metadata.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"${escape(v)}\"" }
        connection.prepareStatement(
            "INSERT INTO app.audit_log (actor_user_id, actor_membership_id, organization_id, action, entity_type, entity_id, request_id, result, purpose, metadata) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
        ).use { st ->
            st.setObject(1, actorUserId)
            st.setObject(2, actorMembershipId)
            st.setObject(3, organizationId)
            st.setString(4, action)
            st.setString(5, entityType)
            st.setObject(6, entityId)
            st.setString(7, requestId)
            st.setString(8, result.name)
            st.setString(9, purpose)
            st.setString(10, json)
            st.executeUpdate()
        }
    }

    private fun escape(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").take(200)

    private val ALLOWED_METADATA_KEYS = setOf("role", "clientKind", "reason", "membershipId", "invitationId", "sessionsRevoked", "emailChanged")
}
