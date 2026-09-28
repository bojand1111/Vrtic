package com.vrticconnect.modules.notifications

import com.vrticconnect.authz.Role
import com.vrticconnect.db.SqlArray
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.modules.tenant.TenantPrincipal
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * In-app notification producer (docs/PRODUCT_SPEC.md 5.11). Called by business services INSIDE their tenant
 * transaction, so the inbox row commits or rolls back together with the business change.
 *
 * Rules:
 *  - content is an i18n key + arguments (ids, dates, non-sensitive names); never health data or message bodies
 *  - one row per recipient USER (a user with two memberships gets one row); `dedup_key` makes repeats a no-op
 *  - only ACTIVE memberships of the current organization receive notifications; the actor never notifies himself
 * RLS: the tenant transaction may insert rows for other users of the same organization (V6 notifications policy);
 * rows of other users are not readable, therefore no RETURNING and a plain ON CONFLICT DO NOTHING: naming the
 * conflict target (recipient_user_id, dedup_key) needs SELECT rights, which makes PostgreSQL apply the recipient-only
 * SELECT policy to the new row and reject it.
 * Push/e-mail delivery (notification_deliveries) is not produced here: no provider is configured yet.
 */
object NotificationWriter {

    val KINDS = setOf("ANNOUNCEMENT", "SCHEDULE_CHANGE", "ABSENCE", "ATTENDANCE", "CALENDAR_EVENT", "URGENT", "CONSENT_REQUEST", "MESSAGE", "SECURITY", "SYSTEM")

    /**
     * Inserts one notification per distinct user behind [membershipIds] (ACTIVE memberships only), skipping [actorUserId].
     * [visibleFrom] sets `created_at` (e.g. a scheduled announcement's publish time); the inbox hides future rows.
     * Returns the number of inserted rows.
     */
    fun notifyMemberships(
        c: Connection,
        organizationId: UUID,
        membershipIds: Collection<UUID>,
        kind: String,
        titleKey: String,
        titleArgs: Map<String, String>,
        refEntityType: String?,
        refEntityId: UUID?,
        dedupKey: String,
        actorUserId: UUID?,
        visibleFrom: Instant? = null,
    ): Int {
        require(kind in KINDS) { "unknown notification kind $kind" }
        if (membershipIds.isEmpty()) return 0
        val args = buildJsonObject { titleArgs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }.toString()
        return c.update(
            "INSERT INTO app.notifications (organization_id, recipient_user_id, membership_id, kind, title_key, title_args, ref_entity_type, ref_entity_id, dedup_key, created_at) " +
                "SELECT DISTINCT ON (m.user_id) m.organization_id, m.user_id, m.id, ?::text, ?::text, ?::jsonb, ?::text, ?::uuid, ?::text, coalesce(?::timestamptz, now()) " +
                "FROM app.organization_memberships m WHERE m.organization_id = ? AND m.status = 'ACTIVE' AND m.id = ANY(?) " +
                "AND (?::uuid IS NULL OR m.user_id <> ?::uuid) " +
                "ORDER BY m.user_id, CASE m.role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 WHEN 'TEACHER' THEN 2 ELSE 3 END " +
                "ON CONFLICT DO NOTHING",
            kind, titleKey, args, refEntityType, refEntityId, dedupKey, visibleFrom, organizationId,
            SqlArray("uuid", membershipIds.toList()), actorUserId, actorUserId,
        )
    }

    /** CONFIRMED guardians (ACTIVE PARENT memberships) of a child. */
    fun guardiansOf(c: Connection, childId: UUID): List<UUID> = c.queryList(
        "SELECT g.membership_id FROM app.guardians g JOIN app.organization_memberships m ON m.id = g.membership_id " +
            "WHERE g.child_id = ? AND g.status = 'CONFIRMED' AND m.status = 'ACTIVE'",
        childId,
    ) { it.uuid("membership_id") }

    /** Teachers with a valid assignment on [date] to the group(s) the child is enrolled in on [date]. */
    fun teachersOf(c: Connection, childId: UUID, date: LocalDate): List<UUID> = c.queryList(
        "SELECT DISTINCT e.membership_id FROM app.enrollments en " +
            "JOIN app.group_teacher_assignments a ON a.group_id = en.group_id AND a.revoked_at IS NULL AND a.valid_from <= ? AND (a.valid_to IS NULL OR a.valid_to >= ?) " +
            "JOIN app.employees e ON e.id = a.employee_id " +
            "WHERE en.child_id = ? AND en.status IN ('PLANNED','ACTIVE') AND en.valid_from <= ? AND (en.valid_to IS NULL OR en.valid_to >= ?)",
        date, date, childId, date, date,
    ) { it.uuid("membership_id") }

    /** ACTIVE OWNER / ADMIN memberships of the current organization. */
    fun managers(c: Connection): List<UUID> = c.queryList(
        "SELECT id FROM app.organization_memberships WHERE organization_id = app.current_organization_id() AND status = 'ACTIVE' AND role IN ('OWNER','ADMIN')",
    ) { it.uuid("id") }

    /** "Given Family" of a child, for notification arguments (a name, never health data). */
    fun childName(c: Connection, childId: UUID): String =
        c.queryOne("SELECT given_name || ' ' || family_name AS n FROM app.children WHERE id = ?", childId) { it.getString("n") } ?: ""

    /** SYSTEM notification to the parent whose guardian link became CONFIRMED (confirm, or a staff-created link). */
    fun guardianConfirmed(c: Connection, principal: TenantPrincipal, guardianId: UUID) {
        val (membershipId, childId) = c.queryOne("SELECT membership_id, child_id FROM app.guardians WHERE id = ?", guardianId) {
            it.uuid("membership_id") to it.uuid("child_id")
        } ?: return
        notifyMemberships(
            c, principal.membership.organizationId, listOf(membershipId), "SYSTEM", "guardian.confirmed",
            mapOf("childName" to childName(c, childId)), "CHILD", childId, "guardian:$guardianId:confirmed", principal.user.userId,
        )
    }

    /**
     * ABSENCE notification for a reported (`event` = "reported") or cancelled ("cancelled") absence: teachers of the child's
     * group (on the first absence day, or today when it already started) and managers; the child's guardians too when a
     * staff member acted. Arguments: child name and period only (never the kind or the note).
     */
    fun absenceChanged(c: Connection, principal: TenantPrincipal, absenceId: UUID, childId: UUID, from: LocalDate, to: LocalDate, today: LocalDate, event: String) {
        require(event == "reported" || event == "cancelled") { "unknown absence event $event" }
        val staff = teachersOf(c, childId, maxOf(today, from)) + managers(c)
        val recipients = if (principal.membership.role == Role.PARENT) staff else staff + guardiansOf(c, childId)
        notifyMemberships(
            c, principal.membership.organizationId, recipients.distinct(), "ABSENCE", "absence.$event",
            mapOf("childName" to childName(c, childId), "dateFrom" to from.toString(), "dateTo" to to.toString()),
            "ABSENCE", absenceId, "absence:$absenceId:$event", principal.user.userId,
        )
    }
}
