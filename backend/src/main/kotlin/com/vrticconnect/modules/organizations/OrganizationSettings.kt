package com.vrticconnect.modules.organizations

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import com.vrticconnect.modules.audit.Audit
import com.vrticconnect.modules.tenant.TenantPrincipal
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.Time
import java.time.LocalTime
import java.util.UUID

/** docs/openapi.yaml `OrganizationSettings`. Wall-clock times are HH:MM in the organization's timezone. */
@Serializable
data class OrganizationSettings(
    val organizationId: String,
    val scheduleChangeDeadlineHours: Int,
    val lateArrivalGraceMinutes: Int,
    val dayOpensAt: String,
    val dayClosesAt: String,
    val workingWeekdays: List<Int>,
    val offlineCacheTtlHours: Int,
    val updatedAt: String,
)

/** docs/openapi.yaml `OrganizationSettingsUpdate` (full replacement). */
@Serializable
data class OrganizationSettingsUpdate(
    val scheduleChangeDeadlineHours: Int,
    val lateArrivalGraceMinutes: Int,
    val dayOpensAt: String,
    val dayClosesAt: String,
    val workingWeekdays: List<Int>,
    val offlineCacheTtlHours: Int = 12,
)

/**
 * First tenant resource behind the full pipeline (E02-B15 example): every ACTIVE member may read
 * the settings; replacing them requires ORG_SETTINGS_MANAGE (OWNER, see PermissionMatrix).
 * Runs inside DbContext.Tenant, so RLS scopes the row and the audit entry to the organization.
 */
class OrganizationSettingsService(private val database: Database) {

    suspend fun get(principal: TenantPrincipal): OrganizationSettings =
        database.transaction(tenant(principal)) { c -> read(c, principal.membership.organizationId) }
            ?: throw ProblemException.notFound()

    suspend fun replace(principal: TenantPrincipal, update: OrganizationSettingsUpdate, requestId: String?): OrganizationSettings {
        Authorize.require(principal, Permission.ORG_SETTINGS_MANAGE)
        validate(update)
        val organizationId = principal.membership.organizationId
        return database.transaction(tenant(principal)) { c ->
            val updated = c.prepareStatement(
                "UPDATE app.organization_settings SET schedule_change_deadline_hours = ?, late_arrival_grace_minutes = ?, day_opens_at = ?, day_closes_at = ?, " +
                    "working_weekdays = ?, offline_cache_ttl_hours = ? WHERE organization_id = ?",
            ).use { st ->
                st.setInt(1, update.scheduleChangeDeadlineHours)
                st.setInt(2, update.lateArrivalGraceMinutes)
                st.setTime(3, Time.valueOf(LocalTime.parse(update.dayOpensAt)))
                st.setTime(4, Time.valueOf(LocalTime.parse(update.dayClosesAt)))
                st.setArray(5, c.createArrayOf("smallint", update.workingWeekdays.sorted().toTypedArray()))
                st.setInt(6, update.offlineCacheTtlHours)
                st.setObject(7, organizationId)
                st.executeUpdate()
            }
            if (updated == 0) throw ProblemException.notFound()
            Audit.record(
                c, "ORG_SETTINGS_UPDATED", "ORGANIZATION", organizationId, actorUserId = principal.user.userId,
                organizationId = organizationId, actorMembershipId = principal.membership.membershipId, requestId = requestId,
            )
            read(c, organizationId) ?: throw ProblemException.notFound()
        }
    }

    private fun tenant(principal: TenantPrincipal) = DbContext.Tenant(principal.membership.organizationId, principal.user.userId)

    private fun read(c: Connection, organizationId: UUID): OrganizationSettings? =
        c.prepareStatement(
            "SELECT organization_id, schedule_change_deadline_hours, late_arrival_grace_minutes, day_opens_at, day_closes_at, working_weekdays, offline_cache_ttl_hours, updated_at " +
                "FROM app.organization_settings WHERE organization_id = ?",
        ).use { st ->
            st.setObject(1, organizationId)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                @Suppress("UNCHECKED_CAST")
                val weekdays = (rs.getArray("working_weekdays").array as Array<Any?>).map { (it as Number).toInt() }
                OrganizationSettings(
                    organizationId = rs.getObject("organization_id", UUID::class.java).toString(),
                    scheduleChangeDeadlineHours = rs.getInt("schedule_change_deadline_hours"),
                    lateArrivalGraceMinutes = rs.getInt("late_arrival_grace_minutes"),
                    dayOpensAt = rs.getTime("day_opens_at").toLocalTime().toString().take(5),
                    dayClosesAt = rs.getTime("day_closes_at").toLocalTime().toString().take(5),
                    workingWeekdays = weekdays,
                    offlineCacheTtlHours = rs.getInt("offline_cache_ttl_hours"),
                    updatedAt = rs.getTimestamp("updated_at").toInstant().toString(),
                )
            }
        }

    private fun validate(u: OrganizationSettingsUpdate) {
        val errors = mutableListOf<FieldError>()
        if (u.scheduleChangeDeadlineHours !in 0..168) errors += FieldError("scheduleChangeDeadlineHours", "INVALID_RANGE", "0..168")
        if (u.lateArrivalGraceMinutes !in 0..180) errors += FieldError("lateArrivalGraceMinutes", "INVALID_RANGE", "0..180")
        if (u.offlineCacheTtlHours !in 1..48) errors += FieldError("offlineCacheTtlHours", "INVALID_RANGE", "1..48")
        val opens = runCatching { LocalTime.parse(u.dayOpensAt) }.getOrNull()
        val closes = runCatching { LocalTime.parse(u.dayClosesAt) }.getOrNull()
        if (opens == null || !TIME.matches(u.dayOpensAt)) errors += FieldError("dayOpensAt", "INVALID_FORMAT", "HH:MM")
        if (closes == null || !TIME.matches(u.dayClosesAt)) errors += FieldError("dayClosesAt", "INVALID_FORMAT", "HH:MM")
        if (opens != null && closes != null && !closes.isAfter(opens)) errors += FieldError("dayClosesAt", "MUST_BE_AFTER_OPENS", "dayClosesAt must be after dayOpensAt")
        if (u.workingWeekdays.isEmpty() || u.workingWeekdays.size > 7 || u.workingWeekdays.any { it !in 1..7 } || u.workingWeekdays.toSet().size != u.workingWeekdays.size) {
            errors += FieldError("workingWeekdays", "INVALID_SET", "1..7, unique, at least one")
        }
        if (errors.isNotEmpty()) {
            throw ProblemException(status = HttpStatusCode.UnprocessableEntity, type = ProblemTypes.VALIDATION, title = "Validation failed", errors = errors)
        }
    }

    private companion object {
        val TIME = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    }
}
