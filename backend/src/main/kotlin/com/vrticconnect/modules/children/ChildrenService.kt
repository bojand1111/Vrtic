package com.vrticconnect.modules.children

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Role
import com.vrticconnect.authz.Scopes
import com.vrticconnect.db.SqlArray
import com.vrticconnect.db.date
import com.vrticconnect.db.dateOrNull
import com.vrticconnect.db.instant
import com.vrticconnect.db.intOrNull
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.db.uuidOrNull
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.conflict
import com.vrticconnect.http.invalidQuery
import com.vrticconnect.http.validate
import com.vrticconnect.modules.tenant.TenantPrincipal
import com.vrticconnect.modules.tenant.audit
import io.ktor.http.Parameters
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

/**
 * Children and enrollments (docs/openapi.yaml tag Children). Every function runs inside the caller's
 * DbContext.Tenant transaction; RLS limits rows to the organization, [Scopes] limits them to the member.
 */
object ChildrenService {

    private val MIN_DOB: LocalDate = LocalDate.of(2000, 1, 1)
    private val SORTS = mapOf(
        "familyName:asc" to "c.family_name, c.given_name, c.id",
        "givenName:asc" to "c.given_name, c.family_name, c.id",
        "dateOfBirth:asc" to "c.date_of_birth, c.family_name, c.id",
    )

    /** Child row plus the enrollment covering the reference date (or the next upcoming one). First parameter: reference date. */
    private const val SUMMARY_SELECT =
        "SELECT c.id, c.organization_id, c.given_name, c.family_name, c.date_of_birth, c.photo_file_id, c.status, c.version, " +
            "c.general_notes, c.created_at, c.updated_at, " +
            "e.id AS e_id, e.group_id AS e_group_id, g.name AS e_group_name, g.location_id AS e_location_id, " +
            "e.valid_from AS e_valid_from, e.valid_to AS e_valid_to, e.status AS e_status " +
            "FROM app.children c " +
            "LEFT JOIN LATERAL (SELECT en.id, en.group_id, en.valid_from, en.valid_to, en.status FROM app.enrollments en " +
            "  WHERE en.child_id = c.id AND en.status IN ('PLANNED','ACTIVE') AND (en.valid_to IS NULL OR en.valid_to >= ?) " +
            "  ORDER BY en.valid_from LIMIT 1) e ON true " +
            "LEFT JOIN app.groups g ON g.id = e.group_id "

    fun list(c: Connection, principal: TenantPrincipal, query: Parameters, limit: Int): ChildSummaryPage {
        Authorize.require(principal, Permission.CHILD_READ)
        val today = Scopes.today(c)
        val onDate = query["onDate"]?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw invalidQuery("onDate", "YYYY-MM-DD") } } ?: today
        val groupId = query["groupId"]?.let { uuidParam("groupId", it) }
        val locationId = query["locationId"]?.let { uuidParam("locationId", it) }
        val status = query["status"] ?: "ACTIVE"
        val search = query["search"]?.trim()
        val sort = query["sort"] ?: "familyName:asc"
        validate {
            oneOf(status, "status", setOf("ACTIVE", "INACTIVE"))
            oneOf(sort, "sort", SORTS.keys)
            if (search != null) require(search.length in 2..100, "search", "INVALID_LENGTH", "2..100 characters")
        }

        val scope = Scopes.childIds(c, principal, onDate)
        if (groupId != null) {
            val groups = Scopes.groupIds(c, principal, onDate)
            if (groups != null && groupId !in groups) throw ProblemException.notFound()
        }
        if (scope != null && scope.isEmpty()) return ChildSummaryPage(emptyList(), null)

        val where = mutableListOf("c.deleted_at IS NULL", "c.status = ?")
        val params = mutableListOf<Any?>(onDate, status)
        if (scope != null) {
            where += "c.id = ANY(?)"; params += SqlArray("uuid", scope.toList())
        }
        if (groupId != null || locationId != null) {
            val cond = StringBuilder(
                "EXISTS (SELECT 1 FROM app.enrollments fe JOIN app.groups fg ON fg.id = fe.group_id WHERE fe.child_id = c.id " +
                    "AND fe.status IN ('PLANNED','ACTIVE') AND fe.valid_from <= ? AND (fe.valid_to IS NULL OR fe.valid_to >= ?)",
            )
            params += onDate; params += onDate
            if (groupId != null) { cond.append(" AND fe.group_id = ?"); params += groupId }
            if (locationId != null) { cond.append(" AND fg.location_id = ?"); params += locationId }
            cond.append(")")
            where += cond.toString()
        }
        if (search != null) {
            where += "(c.given_name ILIKE ? OR c.family_name ILIKE ?)"
            val pattern = ApiSupport.escapeLike(search) + "%"
            params += pattern; params += pattern
        }
        val sql = SUMMARY_SELECT + "WHERE " + where.joinToString(" AND ") + " ORDER BY " + SORTS.getValue(sort) + " LIMIT " + limit
        val items = c.queryList(sql, *params.toTypedArray()) { rs -> summary(rs, today) }
        return ChildSummaryPage(items, null)
    }

    fun get(c: Connection, principal: TenantPrincipal, childId: UUID): ChildDetail {
        Authorize.require(principal, Permission.CHILD_READ)
        Scopes.requireChild(c, principal, childId)
        return detail(c, principal, childId)
    }

    fun create(c: Connection, principal: TenantPrincipal, body: ChildCreateRequest, requestId: String?): ChildDetail {
        Authorize.require(principal, Permission.CHILD_MANAGE)
        val today = Scopes.today(c)
        var groupId: UUID? = null
        var from: LocalDate? = null
        var to: LocalDate? = null
        val dob = validate {
            text(body.givenName, "givenName", 100)
            text(body.familyName, "familyName", 100)
            text(body.generalNotes, "generalNotes", 2000, required = false)
            val d = date(body.dateOfBirth, "dateOfBirth")
            if (d != null) {
                require(d > MIN_DOB, "dateOfBirth", "TOO_EARLY", "after 2000-01-01")
                require(!d.isAfter(today), "dateOfBirth", "IN_FUTURE", "must not be in the future")
            }
            body.initialEnrollment?.let { ie ->
                groupId = uuid(ie.groupId, "initialEnrollment.groupId")
                from = date(ie.validFrom, "initialEnrollment.validFrom")
                to = date(ie.validTo, "initialEnrollment.validTo", required = false)
                val f = from; val t = to
                if (f != null && t != null) require(!t.isBefore(f), "initialEnrollment.validTo", "BEFORE_START", "validTo must be on or after validFrom")
            }
            d
        }!!
        val childId = c.queryOne(
            "INSERT INTO app.children (organization_id, given_name, family_name, date_of_birth, general_notes, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
            principal.membership.organizationId, body.givenName!!.trim(), body.familyName!!.trim(), dob,
            body.generalNotes?.trim()?.takeIf { it.isNotEmpty() }, principal.user.userId,
        ) { it.uuid("id") }!!
        principal.audit(c, "CHILD_CREATED", "CHILD", childId, requestId)
        val g = groupId
        val f = from
        if (g != null && f != null) {
            createEnrollmentRow(c, principal, childId, g, f, to, endCurrent = false, requestId = requestId, fieldPrefix = "initialEnrollment.")
        }
        return detail(c, principal, childId)
    }

    fun update(c: Connection, principal: TenantPrincipal, childId: UUID, patch: Patch, headerVersion: Int?, requestId: String?): ChildDetail {
        Authorize.require(principal, Permission.CHILD_MANAGE)
        Scopes.requireChild(c, principal, childId)
        val current = c.queryOne("SELECT version FROM app.children WHERE id = ? AND deleted_at IS NULL FOR UPDATE", childId) { it.getInt("version") }
            ?: throw ProblemException.notFound()
        val bodyVersion = patch.int("version")
        val expected = headerVersion ?: bodyVersion ?: throw ApiSupport.preconditionRequired()
        val today = Scopes.today(c)

        val allowed = setOf("givenName", "familyName", "dateOfBirth", "generalNotes", "photoFileId", "status", "version")
        patch.keys().filter { it !in allowed }.forEach { patch.errors += FieldError(it, "UNKNOWN_FIELD", "not updatable") }
        if (patch.keys().none { it != "version" }) patch.errors += FieldError("body", "EMPTY", "at least one field is required")
        val sets = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        patch.string("givenName")?.let { p -> nameField(patch, "givenName", p.value)?.let { sets += "given_name = ?"; params += it } }
        patch.string("familyName")?.let { p -> nameField(patch, "familyName", p.value)?.let { sets += "family_name = ?"; params += it } }
        patch.string("dateOfBirth")?.let { p ->
            val d = p.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            when {
                d == null -> patch.errors += FieldError("dateOfBirth", "INVALID_FORMAT", "YYYY-MM-DD")
                d <= MIN_DOB -> patch.errors += FieldError("dateOfBirth", "TOO_EARLY", "after 2000-01-01")
                d.isAfter(today) -> patch.errors += FieldError("dateOfBirth", "IN_FUTURE", "must not be in the future")
                else -> { sets += "date_of_birth = ?"; params += d }
            }
        }
        patch.string("generalNotes")?.let { p ->
            val v = p.value?.trim()?.takeIf { it.isNotEmpty() }
            if (v != null && v.length > 2000) patch.errors += FieldError("generalNotes", "TOO_LONG", "max 2000 characters")
            else { sets += "general_notes = ?"; params += v }
        }
        patch.string("photoFileId")?.let { p ->
            // Files/photos are not available yet: only removing the photo is supported.
            if (p.value != null) patch.errors += FieldError("photoFileId", "NOT_SUPPORTED", "profile photos are not available yet")
            else sets += "photo_file_id = NULL"
        }
        patch.string("status")?.let { p ->
            if (p.value !in setOf("ACTIVE", "INACTIVE")) patch.errors += FieldError("status", "INVALID_VALUE", "ACTIVE|INACTIVE")
            else { sets += "status = ?"; params += p.value }
        }
        patch.throwIfInvalid()
        if (expected != current) throw conflict("VERSION_MISMATCH", current)
        val updated = c.update(
            "UPDATE app.children SET " + (sets + "version = version + 1").joinToString(", ") + " WHERE id = ? AND version = ?",
            *(params + childId + expected).toTypedArray(),
        )
        if (updated != 1) throw conflict("VERSION_MISMATCH", current)
        principal.audit(c, "CHILD_UPDATED", "CHILD", childId, requestId)
        return detail(c, principal, childId)
    }

    // ------------------------------------------------------------------ enrollments

    fun listEnrollments(c: Connection, principal: TenantPrincipal, childId: UUID): EnrollmentList {
        Authorize.require(principal, Permission.CHILD_READ)
        Scopes.requireChild(c, principal, childId)
        requireChildExists(c, childId)
        val today = Scopes.today(c)
        return EnrollmentList(
            c.queryList("$ENROLLMENT_SELECT WHERE e.child_id = ? ORDER BY e.valid_from DESC, e.created_at DESC", childId) { enrollment(it, today) },
        )
    }

    fun createEnrollment(c: Connection, principal: TenantPrincipal, childId: UUID, body: EnrollmentCreateRequest, requestId: String?): EnrollmentDto {
        Authorize.require(principal, Permission.CHILD_MANAGE)
        Scopes.requireChild(c, principal, childId)
        requireChildExists(c, childId)
        var from: LocalDate? = null
        var to: LocalDate? = null
        val groupId = validate {
            val g = uuid(body.groupId, "groupId")
            from = date(body.validFrom, "validFrom")
            to = date(body.validTo, "validTo", required = false)
            val f = from; val t = to
            if (f != null && t != null) require(!t.isBefore(f), "validTo", "BEFORE_START", "validTo must be on or after validFrom")
            g
        }!!
        val id = createEnrollmentRow(c, principal, childId, groupId, from!!, to, body.endCurrentEnrollment, requestId, fieldPrefix = "")
        return enrollmentById(c, id)
    }

    fun endEnrollment(c: Connection, principal: TenantPrincipal, enrollmentId: UUID, body: EnrollmentEndRequest, requestId: String?): EnrollmentDto {
        Authorize.require(principal, Permission.CHILD_MANAGE)
        val row = c.queryOne(
            "SELECT child_id, valid_from, status FROM app.enrollments WHERE id = ? FOR UPDATE", enrollmentId,
        ) { Triple(it.uuid("child_id"), it.date("valid_from"), it.getString("status")) } ?: throw ProblemException.notFound()
        Scopes.requireChild(c, principal, row.first)
        val today = Scopes.today(c)
        val validTo = validate {
            text(body.endReason, "endReason", 200, required = false)
            date(body.validTo, "validTo")
        }!!
        if (row.third == "ENDED" || row.third == "CANCELLED") throw conflict("ENROLLMENT_NOT_ACTIVE")
        val validFrom = row.second
        if (validTo.isBefore(validFrom)) {
            // An enrollment that has not started yet is cancelled instead of ended.
            if (validFrom.isAfter(today)) {
                c.update("UPDATE app.enrollments SET status = 'CANCELLED', end_reason = ? WHERE id = ?", body.endReason?.trim(), enrollmentId)
                principal.audit(c, "ENROLLMENT_CANCELLED", "ENROLLMENT", enrollmentId, requestId)
                return enrollmentById(c, enrollmentId)
            }
            throw fieldProblem("validTo", "BEFORE_START", "validTo must be on or after validFrom")
        }
        if (validTo.isBefore(today) && !Authorize.has(principal, Permission.ATTENDANCE_CORRECT)) {
            throw fieldProblem("validTo", "IN_PAST", "validTo must not be in the past")
        }
        val status = if (validTo.isBefore(today)) "ENDED" else row.third
        c.update(
            "UPDATE app.enrollments SET valid_to = ?, status = ?, end_reason = ? WHERE id = ?",
            validTo, status, body.endReason?.trim()?.takeIf { it.isNotEmpty() }, enrollmentId,
        )
        principal.audit(c, "ENROLLMENT_ENDED", "ENROLLMENT", enrollmentId, requestId)
        return enrollmentById(c, enrollmentId)
    }

    /** Validates group, overlap and capacity, optionally ends the enrollment covering [from], inserts. Returns the new id. */
    fun createEnrollmentRow(
        c: Connection, principal: TenantPrincipal, childId: UUID, groupId: UUID, from: LocalDate, to: LocalDate?,
        endCurrent: Boolean, requestId: String?, fieldPrefix: String,
    ): UUID {
        val today = Scopes.today(c)
        val capacity = c.queryOne(
            "SELECT capacity FROM app.groups WHERE id = ? AND deleted_at IS NULL AND status = 'ACTIVE'", groupId,
        ) { rs -> listOf(rs.intOrNull("capacity")) } ?: throw fieldProblem("${fieldPrefix}groupId", "NOT_FOUND", "unknown or inactive group")
        if (endCurrent) {
            val covering = c.queryList(
                "SELECT id FROM app.enrollments WHERE child_id = ? AND status IN ('PLANNED','ACTIVE') AND valid_from < ? " +
                    "AND (valid_to IS NULL OR valid_to >= ?) FOR UPDATE",
                childId, from, from,
            ) { it.uuid("id") }
            for (id in covering) {
                val end = from.minusDays(1)
                c.update(
                    "UPDATE app.enrollments SET valid_to = ?, status = CASE WHEN ? < ? THEN 'ENDED' ELSE status END, end_reason = COALESCE(end_reason, 'MOVED') WHERE id = ?",
                    end, end, today, id,
                )
                principal.audit(c, "ENROLLMENT_ENDED", "ENROLLMENT", id, requestId)
            }
        }
        val overlap = c.queryOne(
            "SELECT id FROM app.enrollments WHERE child_id = ? AND status IN ('PLANNED','ACTIVE') " +
                "AND daterange(valid_from, valid_to, '[]') && daterange(?::date, ?::date, '[]') LIMIT 1",
            childId, from, to,
        ) { it.uuid("id") }
        if (overlap != null) throw conflict("ENROLLMENT_OVERLAP")
        val cap = capacity.first()
        if (cap != null) {
            val enrolled = c.queryOne(
                "SELECT count(DISTINCT child_id) AS n FROM app.enrollments WHERE group_id = ? AND status IN ('PLANNED','ACTIVE') " +
                    "AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)",
                groupId, from, from,
            ) { it.getInt("n") } ?: 0
            if (enrolled >= cap) throw conflict("GROUP_FULL")
        }
        val id = c.queryOne(
            "INSERT INTO app.enrollments (organization_id, child_id, group_id, valid_from, valid_to, status, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
            principal.membership.organizationId, childId, groupId, from, to, if (from.isAfter(today)) "PLANNED" else "ACTIVE", principal.user.userId,
        ) { it.uuid("id") }!!
        principal.audit(c, "ENROLLMENT_CREATED", "ENROLLMENT", id, requestId)
        return id
    }

    // ------------------------------------------------------------------ reading helpers

    fun requireChildExists(c: Connection, childId: UUID) {
        c.queryOne("SELECT 1 AS ok FROM app.children WHERE id = ? AND deleted_at IS NULL", childId) { true } ?: throw ProblemException.notFound()
    }

    fun detail(c: Connection, principal: TenantPrincipal, childId: UUID): ChildDetail {
        val today = Scopes.today(c)
        data class Row(val summary: ChildSummary, val notes: String?, val createdAt: String, val updatedAt: String)
        val row = c.queryOne("$SUMMARY_SELECT WHERE c.id = ? AND c.deleted_at IS NULL", today, childId) { rs ->
            Row(summary(rs, today), rs.getString("general_notes"), rs.instant("created_at").toString(), rs.instant("updated_at").toString())
        } ?: throw ProblemException.notFound()
        val enrollments = c.queryList("$ENROLLMENT_SELECT WHERE e.child_id = ? ORDER BY e.valid_from DESC, e.created_at DESC", childId) { rs ->
            val e = enrollment(rs, today)
            EnrollmentRef(e.id, e.groupId, e.groupName, e.locationId, e.validFrom, e.validTo, e.status)
        }
        // Staff see every link; a parent sees only their own link (docs/openapi.yaml `Child.guardians`).
        val guardians = if (principal.membership.role == Role.PARENT) {
            GuardianService.forChild(c, childId).filter { it.membershipId == principal.membership.membershipId.toString() }
        } else {
            GuardianService.forChild(c, childId)
        }
        val pickup = PickupPersonService.forChild(c, childId, includeRevoked = false)
        val s = row.summary
        return ChildDetail(
            s.id, s.organizationId, s.givenName, s.familyName, s.dateOfBirth, s.photoFileId, s.status, s.currentEnrollment,
            s.hasCriticalHealthAlert, s.version, row.notes, enrollments, guardians, pickup, row.createdAt, row.updatedAt,
        )
    }

    private const val ENROLLMENT_SELECT =
        "SELECT e.id, e.organization_id, e.child_id, e.group_id, g.name AS group_name, g.location_id, e.valid_from, e.valid_to, " +
            "e.status, e.end_reason, e.created_at, e.updated_at FROM app.enrollments e JOIN app.groups g ON g.id = e.group_id"

    private fun enrollmentById(c: Connection, id: UUID): EnrollmentDto {
        val today = Scopes.today(c)
        return c.queryOne("$ENROLLMENT_SELECT WHERE e.id = ?", id) { enrollment(it, today) } ?: throw ProblemException.notFound()
    }

    private fun enrollment(rs: ResultSet, today: LocalDate): EnrollmentDto {
        val from = rs.date("valid_from")
        val to = rs.dateOrNull("valid_to")
        return EnrollmentDto(
            id = rs.uuid("id").toString(), organizationId = rs.uuid("organization_id").toString(), childId = rs.uuid("child_id").toString(),
            groupId = rs.uuid("group_id").toString(), groupName = rs.getString("group_name"), locationId = rs.uuid("location_id").toString(),
            validFrom = from.toString(), validTo = to?.toString(),
            status = ApiSupport.effectiveEnrollmentStatus(rs.getString("status"), from, to, today),
            endReason = rs.getString("end_reason"),
            createdAt = rs.instant("created_at").toString(), updatedAt = rs.instant("updated_at").toString(),
        )
    }

    private fun summary(rs: ResultSet, today: LocalDate): ChildSummary {
        val enrollmentId = rs.uuidOrNull("e_id")
        val current = enrollmentId?.let {
            val from = rs.date("e_valid_from")
            val to = rs.dateOrNull("e_valid_to")
            EnrollmentRef(
                id = it.toString(), groupId = rs.uuid("e_group_id").toString(), groupName = rs.getString("e_group_name"),
                locationId = rs.uuid("e_location_id").toString(), validFrom = from.toString(), validTo = to?.toString(),
                status = ApiSupport.effectiveEnrollmentStatus(rs.getString("e_status"), from, to, today),
            )
        }
        return ChildSummary(
            id = rs.uuid("id").toString(), organizationId = rs.uuid("organization_id").toString(),
            givenName = rs.getString("given_name"), familyName = rs.getString("family_name"),
            dateOfBirth = rs.date("date_of_birth").toString(), photoFileId = rs.uuidOrNull("photo_file_id")?.toString(),
            status = rs.getString("status"), currentEnrollment = current, hasCriticalHealthAlert = null, version = rs.getInt("version"),
        )
    }

    private fun nameField(patch: Patch, field: String, value: String?): String? {
        val v = value?.trim()
        if (v.isNullOrEmpty()) { patch.errors += FieldError(field, "REQUIRED", "$field is required"); return null }
        if (v.length > 100) { patch.errors += FieldError(field, "TOO_LONG", "max 100 characters"); return null }
        return v
    }

    private fun uuidParam(name: String, raw: String): UUID = runCatching { UUID.fromString(raw) }.getOrElse { throw invalidQuery(name, "UUID") }
}

fun fieldProblem(field: String, code: String, message: String) = ProblemException(
    status = io.ktor.http.HttpStatusCode.UnprocessableEntity, type = com.vrticconnect.http.ProblemTypes.VALIDATION,
    title = "Validation failed", errors = listOf(FieldError(field, code, message)),
)
