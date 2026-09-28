package com.vrticconnect.modules.messaging

import com.vrticconnect.db.SqlArray
import com.vrticconnect.db.instantOrNull
import com.vrticconnect.db.queryList
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * Server-managed conversation participants (docs/PRODUCT_SPEC.md 5.12 M1, M2, M5). Nobody is ever chosen by a client:
 *   GUARDIAN : CONFIRMED guardian links of the child with an ACTIVE membership (both kinds)
 *   TEACHER  : PARENT_TEACHER only; ACTIVE non-parent memberships with a non-revoked group assignment valid on [today]
 *              to a group the child is enrolled in (PLANNED/ACTIVE) on [today]
 *   ADMIN    : PARENT_ADMIN only; ACTIVE OWNER / ADMIN memberships
 * A deleted child has no eligible participants. [sync] reconciles the stored rows lazily on access: newly eligible
 * members join (or re-join), members whose rights ended get `left_at` and lose access to the whole conversation
 * (docs/PRODUCT_SPEC.md 9.10 recommendation).
 */
object Participants {

    data class Eligible(val conversationId: UUID, val membershipId: UUID, val role: String)

    private const val ELIGIBLE_SQL =
        "SELECT DISTINCT ON (x.conversation_id, x.membership_id) x.conversation_id, x.membership_id, x.participant_role FROM (" +
            "SELECT cv.id AS conversation_id, g.membership_id, 'GUARDIAN' AS participant_role, 0 AS prio " +
            "FROM app.conversations cv JOIN app.children ch ON ch.id = cv.child_id AND ch.deleted_at IS NULL " +
            "JOIN app.guardians g ON g.child_id = cv.child_id AND g.status = 'CONFIRMED' " +
            "JOIN app.organization_memberships m ON m.id = g.membership_id AND m.status = 'ACTIVE' " +
            "WHERE cv.id = ANY(?) " +
            "UNION ALL " +
            "SELECT cv.id, e.membership_id, 'TEACHER', 1 " +
            "FROM app.conversations cv JOIN app.children ch ON ch.id = cv.child_id AND ch.deleted_at IS NULL " +
            "JOIN app.enrollments en ON en.child_id = cv.child_id AND en.status IN ('PLANNED','ACTIVE') AND en.valid_from <= ? AND (en.valid_to IS NULL OR en.valid_to >= ?) " +
            "JOIN app.group_teacher_assignments a ON a.group_id = en.group_id AND a.revoked_at IS NULL AND a.valid_from <= ? AND (a.valid_to IS NULL OR a.valid_to >= ?) " +
            "JOIN app.employees e ON e.id = a.employee_id " +
            "JOIN app.organization_memberships m ON m.id = e.membership_id AND m.status = 'ACTIVE' AND m.role <> 'PARENT' " +
            "WHERE cv.id = ANY(?) AND cv.kind = 'PARENT_TEACHER' " +
            "UNION ALL " +
            "SELECT cv.id, m.id, 'ADMIN', 2 " +
            "FROM app.conversations cv JOIN app.children ch ON ch.id = cv.child_id AND ch.deleted_at IS NULL " +
            "JOIN app.organization_memberships m ON m.organization_id = cv.organization_id AND m.status = 'ACTIVE' AND m.role IN ('OWNER','ADMIN') " +
            "WHERE cv.id = ANY(?) AND cv.kind = 'PARENT_ADMIN'" +
            ") x WHERE (?::uuid IS NULL OR x.membership_id = ?::uuid) ORDER BY x.conversation_id, x.membership_id, x.prio"

    /** Eligible participants of [conversationIds]; restricted to one membership when [only] is set. */
    fun eligible(c: Connection, conversationIds: Collection<UUID>, today: LocalDate, only: UUID? = null): List<Eligible> {
        if (conversationIds.isEmpty()) return emptyList()
        val ids = SqlArray("uuid", conversationIds.toList())
        return c.queryList(ELIGIBLE_SQL, ids, today, today, today, today, ids, ids, only, only) { rs ->
            Eligible(rs.uuid("conversation_id"), rs.uuid("membership_id"), rs.getString("participant_role"))
        }
    }

    /**
     * Reconciles stored participant rows with the current rights. With [only] set, just that membership's rows are
     * touched (cheap self-check for the conversation list); otherwise every participant of [conversationIds].
     */
    fun sync(c: Connection, organizationId: UUID, conversationIds: Collection<UUID>, today: LocalDate, only: UUID? = null) {
        if (conversationIds.isEmpty()) return
        val wanted = eligible(c, conversationIds, today, only).associateBy { it.conversationId to it.membershipId }
        data class Row(val conversationId: UUID, val membershipId: UUID, val role: String, val active: Boolean)
        val stored = c.queryList(
            "SELECT conversation_id, membership_id, participant_role, left_at FROM app.conversation_participants " +
                "WHERE conversation_id = ANY(?) AND (?::uuid IS NULL OR membership_id = ?::uuid)",
            SqlArray("uuid", conversationIds.toList()), only, only,
        ) { Row(it.uuid("conversation_id"), it.uuid("membership_id"), it.getString("participant_role"), it.instantOrNull("left_at") == null) }
            .associateBy { it.conversationId to it.membershipId }
        for ((key, e) in wanted) {
            val row = stored[key]
            if (row != null && row.active && row.role == e.role) continue
            c.update(
                "INSERT INTO app.conversation_participants (organization_id, conversation_id, membership_id, participant_role) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (conversation_id, membership_id) DO UPDATE SET participant_role = EXCLUDED.participant_role, " +
                    "left_at = NULL, joined_at = CASE WHEN app.conversation_participants.left_at IS NULL THEN app.conversation_participants.joined_at ELSE now() END",
                organizationId, e.conversationId, e.membershipId, e.role,
            )
        }
        val leaving = stored.filter { (key, row) -> row.active && key !in wanted }.values
        for (row in leaving) {
            c.update(
                "UPDATE app.conversation_participants SET left_at = now() WHERE conversation_id = ? AND membership_id = ? AND left_at IS NULL",
                row.conversationId, row.membershipId,
            )
        }
    }
}
