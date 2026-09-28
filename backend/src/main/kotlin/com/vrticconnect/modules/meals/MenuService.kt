package com.vrticconnect.modules.meals

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.db.SqlArray
import com.vrticconnect.db.date
import com.vrticconnect.db.instant
import com.vrticconnect.db.queryList
import com.vrticconnect.db.queryOne
import com.vrticconnect.db.stringList
import com.vrticconnect.db.update
import com.vrticconnect.db.uuid
import com.vrticconnect.db.uuidOrNull
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.Validation
import com.vrticconnect.http.conflict
import com.vrticconnect.http.invalidQuery
import com.vrticconnect.modules.announcements.Versioning
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.TenantPrincipal
import com.vrticconnect.modules.tenant.audit
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Menus (docs/openapi.yaml "Menus"). MENU_MANAGE members see drafts and edit; everyone else only sees
 * published menu days. Items are replaced as a set (app_runtime has DELETE on menu_items only).
 */
class MenuService(private val api: TenantApi) {

    private data class Item(val slot: String, val description: String, val tags: List<String>, val sortOrder: Int)

    private fun manager(p: TenantPrincipal) = Authorize.has(p, Permission.MENU_MANAGE)

    suspend fun list(p: TenantPrincipal, from: LocalDate?, to: LocalDate?, locationId: UUID?, publishedOnly: Boolean, sort: String, limit: Int): MenuDayPage {
        Authorize.require(p, Permission.MENU_READ)
        if (from == null) throw invalidQuery("from", "required YYYY-MM-DD")
        if (to == null) throw invalidQuery("to", "required YYYY-MM-DD")
        if (to.isBefore(from)) throw invalidQuery("to", "to must not be before from")
        if (ChronoUnit.DAYS.between(from, to) > 62) throw invalidQuery("to", "max 62 days")
        if (sort !in setOf("menuDate:asc", "menuDate:desc")) throw invalidQuery("sort", "menuDate:asc|menuDate:desc")
        return api.tx(p) { c ->
            val where = mutableListOf("m.menu_date BETWEEN ? AND ?")
            val params = mutableListOf<Any?>(from, to)
            if (publishedOnly || !manager(p)) where += "m.is_published"
            if (locationId != null) { where += "(m.location_id IS NULL OR m.location_id = ?)"; params += locationId }
            params += limit
            val dir = if (sort == "menuDate:desc") "DESC" else "ASC"
            val rows = c.queryList("$SELECT WHERE ${where.joinToString(" AND ")} ORDER BY m.menu_date $dir, m.location_id NULLS FIRST, m.id LIMIT ?", *params.toTypedArray()) { map(it) }
            MenuDayPage(withItems(c, rows), null)
        }
    }

    suspend fun get(p: TenantPrincipal, id: UUID): MenuDayDto {
        Authorize.require(p, Permission.MENU_READ)
        return api.tx(p) { c -> load(c, id, manager(p)) }
    }

    suspend fun create(p: TenantPrincipal, req: MenuDayCreate, requestId: String?): MenuDayDto {
        Authorize.require(p, Permission.MENU_MANAGE)
        val v = Validation()
        val date = v.date(req.menuDate, "menuDate")
        val locationId = v.uuid(req.locationId, "locationId", required = false)
        v.text(req.note, "note", 500, required = false)
        val items = items(v, req.items)
        v.throwIfInvalid()
        return api.tx(p) { c ->
            if (locationId != null && c.queryOne("SELECT 1 AS ok FROM app.locations WHERE id = ? AND deleted_at IS NULL", locationId) { true } == null) {
                val check = Validation(); check.require(false, "locationId", "NOT_FOUND", "unknown location"); check.throwIfInvalid()
            }
            val duplicate = if (locationId == null) {
                c.queryOne("SELECT 1 AS ok FROM app.menu_days WHERE location_id IS NULL AND menu_date = ?", date) { true }
            } else {
                c.queryOne("SELECT 1 AS ok FROM app.menu_days WHERE location_id = ? AND menu_date = ?", locationId, date) { true }
            }
            if (duplicate != null) throw conflict("MENU_DAY_EXISTS")
            val id = UUID.randomUUID()
            c.update(
                "INSERT INTO app.menu_days (id, organization_id, location_id, menu_date, note, created_by_membership_id) VALUES (?, ?, ?, ?, ?, ?)",
                id, p.membership.organizationId, locationId, date, req.note?.takeIf { it.isNotBlank() }, p.membership.membershipId,
            )
            insertItems(c, p.membership.organizationId, id, items)
            p.audit(c, "MENU_DAY_CREATED", "MENU_DAY", id, requestId)
            load(c, id, true)
        }
    }

    suspend fun replace(p: TenantPrincipal, id: UUID, expectedVersion: Int, req: MenuDayUpdate, requestId: String?): MenuDayDto {
        Authorize.require(p, Permission.MENU_MANAGE)
        val v = Validation()
        v.text(req.note, "note", 500, required = false)
        val items = items(v, req.items)
        v.throwIfInvalid()
        return api.tx(p) { c ->
            Versioning.requireVersion(expectedVersion, lockVersion(c, id))
            c.update("DELETE FROM app.menu_items WHERE menu_day_id = ?", id)
            insertItems(c, p.membership.organizationId, id, items)
            c.update("UPDATE app.menu_days SET note = ?, version = version + 1 WHERE id = ?", req.note?.takeIf { it.isNotBlank() }, id)
            p.audit(c, "MENU_DAY_UPDATED", "MENU_DAY", id, requestId)
            load(c, id, true)
        }
    }

    /** Idempotent: publishing a published day only returns it. */
    suspend fun publish(p: TenantPrincipal, id: UUID, expectedVersion: Int, requestId: String?): MenuDayDto {
        Authorize.require(p, Permission.MENU_MANAGE)
        return api.tx(p) { c ->
            Versioning.requireVersion(expectedVersion, lockVersion(c, id))
            val changed = c.update("UPDATE app.menu_days SET is_published = true, version = version + 1 WHERE id = ? AND NOT is_published", id)
            if (changed > 0) p.audit(c, "MENU_DAY_PUBLISHED", "MENU_DAY", id, requestId)
            load(c, id, true)
        }
    }

    private fun lockVersion(c: Connection, id: UUID): Int =
        c.queryOne("SELECT version FROM app.menu_days WHERE id = ? FOR UPDATE", id) { it.getInt("version") } ?: throw ProblemException.notFound()

    private fun items(v: Validation, raw: List<MenuItemInput>?): List<Item> {
        if (raw == null) {
            v.require(false, "items", "REQUIRED", "items is required")
            return emptyList()
        }
        v.require(raw.size <= 40, "items", "TOO_MANY", "max 40 items")
        val parsed = raw.mapIndexedNotNull { i, it ->
            val f = "items[$i]"
            val slot = v.oneOf(it.mealSlot, "$f.mealSlot", SLOTS.keys)
            v.text(it.description, "$f.description", 300)
            val tags = it.allergenTags.orEmpty()
            v.require(tags.size <= 20, "$f.allergenTags", "TOO_MANY", "max 20 tags")
            v.require(tags.toSet().size == tags.size, "$f.allergenTags", "DUPLICATE", "tags must be unique")
            v.require(tags.all { t -> TAG.matches(t) }, "$f.allergenTags", "INVALID_FORMAT", "^[A-Z][A-Z0-9_]{1,30}$")
            val order = it.sortOrder ?: 0
            v.require(order >= 0, "$f.sortOrder", "INVALID_RANGE", ">= 0")
            if (slot == null || it.description.isNullOrBlank()) null else Item(slot, it.description.trim(), tags, order)
        }
        v.require(parsed.map { it.slot to it.sortOrder }.toSet().size == parsed.size, "items", "DUPLICATE_SLOT_ORDER", "mealSlot + sortOrder must be unique")
        return parsed
    }

    private fun insertItems(c: Connection, organizationId: UUID, menuDayId: UUID, items: List<Item>) {
        items.forEach { i ->
            c.update(
                "INSERT INTO app.menu_items (organization_id, menu_day_id, meal_slot, description, allergen_tags, sort_order) VALUES (?, ?, ?, ?, ?, ?)",
                organizationId, menuDayId, i.slot, i.description, SqlArray("text", i.tags), i.sortOrder,
            )
        }
    }

    private fun load(c: Connection, id: UUID, isManager: Boolean): MenuDayDto {
        val row = c.queryOne("$SELECT WHERE m.id = ?" + if (isManager) "" else " AND m.is_published", id) { map(it) } ?: throw ProblemException.notFound()
        return withItems(c, listOf(row)).first()
    }

    private fun withItems(c: Connection, days: List<MenuDayDto>): List<MenuDayDto> {
        if (days.isEmpty()) return days
        val items = c.queryList(
            "SELECT id, menu_day_id, meal_slot, description, allergen_tags, sort_order FROM app.menu_items WHERE menu_day_id = ANY(?)",
            SqlArray("uuid", days.map { UUID.fromString(it.id) }),
        ) { rs ->
            rs.uuid("menu_day_id").toString() to MenuItemDto(
                id = rs.uuid("id").toString(), mealSlot = rs.getString("meal_slot"), description = rs.getString("description"),
                allergenTags = rs.stringList("allergen_tags"), sortOrder = rs.getInt("sort_order"),
            )
        }.groupBy({ it.first }, { it.second })
        return days.map { d ->
            d.copy(items = items[d.id].orEmpty().sortedWith(compareBy({ SLOTS.getValue(it.mealSlot) }, { it.sortOrder })))
        }
    }

    private fun map(rs: ResultSet) = MenuDayDto(
        id = rs.uuid("id").toString(), organizationId = rs.uuid("organization_id").toString(), locationId = rs.uuidOrNull("location_id")?.toString(),
        menuDate = rs.date("menu_date").toString(), isPublished = rs.getBoolean("is_published"), note = rs.getString("note"), items = emptyList(),
        version = rs.getInt("version"), createdByMembershipId = rs.uuid("created_by_membership_id").toString(),
        createdAt = rs.instant("created_at").toString(), updatedAt = rs.instant("updated_at").toString(),
    )

    companion object {
        /** Slot -> display order. */
        val SLOTS = mapOf("BREAKFAST" to 0, "SNACK_AM" to 1, "LUNCH" to 2, "SNACK_PM" to 3)
        private val TAG = Regex("^[A-Z][A-Z0-9_]{1,30}$")
        private const val SELECT =
            "SELECT m.id, m.organization_id, m.location_id, m.menu_date, m.is_published, m.note, m.version, m.created_by_membership_id, m.created_at, m.updated_at " +
                "FROM app.menu_days m"
    }
}
