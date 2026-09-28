package com.vrticconnect.modules.announcements

import com.vrticconnect.db.queryOne
import com.vrticconnect.db.update
import com.vrticconnect.http.Validation
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/** Validated audience rule (docs/openapi.yaml AnnouncementAudience: exactly one target id, none for ORGANIZATION). */
data class Audience(val type: String, val locationId: UUID?, val groupId: UUID?, val membershipId: UUID?) {
    fun toDto() = AudienceDto(type, locationId?.toString(), groupId?.toString(), membershipId?.toString())
}

object AnnouncementAudiences {
    val TYPES = setOf("ORGANIZATION", "LOCATION", "GROUP", "GUARDIAN")

    /** Shape validation (no DB); target existence is checked by [checkTargets] inside the transaction. */
    fun parse(v: Validation, raw: List<AudienceDto>?, field: String = "audiences"): List<Audience> {
        if (raw == null || raw.isEmpty()) {
            v.require(false, field, "REQUIRED", "at least one audience")
            return emptyList()
        }
        v.require(raw.size <= 50, field, "TOO_MANY", "max 50 audiences")
        return raw.mapIndexedNotNull { i, a ->
            val f = "$field[$i]"
            val type = v.oneOf(a.audienceType, "$f.audienceType", TYPES) ?: return@mapIndexedNotNull null
            val location = if (type == "LOCATION") v.uuid(a.locationId, "$f.locationId") else null
            val group = if (type == "GROUP") v.uuid(a.groupId, "$f.groupId") else null
            val membership = if (type == "GUARDIAN") v.uuid(a.membershipId, "$f.membershipId") else null
            v.require(type == "LOCATION" || a.locationId == null, "$f.locationId", "NOT_ALLOWED", "only for LOCATION")
            v.require(type == "GROUP" || a.groupId == null, "$f.groupId", "NOT_ALLOWED", "only for GROUP")
            v.require(type == "GUARDIAN" || a.membershipId == null, "$f.membershipId", "NOT_ALLOWED", "only for GUARDIAN")
            Audience(type, location, group, membership)
        }.distinct()
    }

    fun checkTargets(c: Connection, v: Validation, audiences: List<Audience>, field: String = "audiences") {
        audiences.forEachIndexed { i, a ->
            val f = "$field[$i]"
            when (a.type) {
                "LOCATION" -> v.require(exists(c, "SELECT 1 AS ok FROM app.locations WHERE id = ? AND deleted_at IS NULL", a.locationId), "$f.locationId", "NOT_FOUND", "unknown location")
                "GROUP" -> v.require(exists(c, "SELECT 1 AS ok FROM app.groups WHERE id = ? AND deleted_at IS NULL", a.groupId), "$f.groupId", "NOT_FOUND", "unknown group")
                "GUARDIAN" -> v.require(
                    exists(c, "SELECT 1 AS ok FROM app.organization_memberships WHERE id = ? AND role = 'PARENT' AND status = 'ACTIVE'", a.membershipId),
                    "$f.membershipId", "NOT_FOUND", "unknown parent membership",
                )
            }
        }
    }

    private fun exists(c: Connection, sql: String, id: UUID?): Boolean = id != null && c.queryOne(sql, id) { true } == true

    fun replace(c: Connection, organizationId: UUID, announcementId: UUID, audiences: List<Audience>) {
        c.update("DELETE FROM app.announcement_audiences WHERE announcement_id = ?", announcementId)
        audiences.forEach { a ->
            c.update(
                "INSERT INTO app.announcement_audiences (organization_id, announcement_id, audience_type, location_id, group_id, membership_id) VALUES (?, ?, ?, ?, ?, ?)",
                organizationId, announcementId, a.type, a.locationId, a.groupId, a.membershipId,
            )
        }
    }

    /**
     * Evaluates the rules into announcement_recipients (snapshot at publish time, ACTIVE memberships only):
     *   ORGANIZATION: every member; LOCATION: staff with that primary location or a group assignment there on [today] plus
     *   parents (CONFIRMED guardians) of children enrolled there on [today]; GROUP: teachers assigned to the group on [today]
     *   plus parents of children enrolled in it; GUARDIAN: that parent. The author is always a recipient.
     */
    fun snapshot(c: Connection, organizationId: UUID, announcementId: UUID, authorMembershipId: UUID, audiences: List<Audience>, today: LocalDate): Int {
        fun insert(subquery: String, vararg params: Any?) = c.update(
            "INSERT INTO app.announcement_recipients (organization_id, announcement_id, membership_id) " +
                "SELECT m.organization_id, ?, m.id FROM app.organization_memberships m WHERE m.status = 'ACTIVE' AND m.organization_id = ? " +
                "AND m.id IN ($subquery) ON CONFLICT (announcement_id, membership_id) DO NOTHING",
            announcementId, organizationId, *params,
        )
        val teachersOf = "SELECT e.membership_id FROM app.group_teacher_assignments ga JOIN app.employees e ON e.id = ga.employee_id " +
            "WHERE ga.revoked_at IS NULL AND ga.valid_from <= ? AND (ga.valid_to IS NULL OR ga.valid_to >= ?) AND ga.group_id IN "
        val parentsOf = "SELECT g.membership_id FROM app.guardians g JOIN app.enrollments en ON en.child_id = g.child_id " +
            "WHERE g.status = 'CONFIRMED' AND en.status IN ('PLANNED','ACTIVE') AND en.valid_from <= ? AND (en.valid_to IS NULL OR en.valid_to >= ?) AND en.group_id IN "
        val groupsAt = "(SELECT id FROM app.groups WHERE location_id = ? AND deleted_at IS NULL)"
        audiences.forEach { a ->
            when (a.type) {
                "ORGANIZATION" -> insert("SELECT id FROM app.organization_memberships")
                "LOCATION" -> {
                    insert("SELECT membership_id FROM app.employees WHERE primary_location_id = ?", a.locationId)
                    insert("$teachersOf $groupsAt", today, today, a.locationId)
                    insert("$parentsOf $groupsAt", today, today, a.locationId)
                }
                "GROUP" -> {
                    insert("$teachersOf (?)", today, today, a.groupId)
                    insert("$parentsOf (?)", today, today, a.groupId)
                }
                "GUARDIAN" -> insert("?", a.membershipId)
            }
        }
        insert("?", authorMembershipId)
        return c.queryOne("SELECT count(*)::int AS n FROM app.announcement_recipients WHERE announcement_id = ?", announcementId) { it.getInt("n") } ?: 0
    }
}
