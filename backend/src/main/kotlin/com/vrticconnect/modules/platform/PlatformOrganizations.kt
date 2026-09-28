package com.vrticconnect.modules.platform

import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.http.Cursor
import com.vrticconnect.http.FieldError
import com.vrticconnect.http.PageRequest
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import com.vrticconnect.http.toPage
import com.vrticconnect.modules.auth.AuthenticatedUser
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** docs/openapi.yaml `Organization`. */
@Serializable
data class Organization(
    val id: String,
    val slug: String,
    val name: String,
    val legalName: String? = null,
    val countryCode: String,
    val timezone: String,
    val defaultLocale: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

/** docs/openapi.yaml `OrganizationPage` (`nextCursor` is always present). */
@Serializable
data class OrganizationPage(val items: List<Organization>, val nextCursor: JsonElement) {
    constructor(items: List<Organization>, nextCursor: String?) : this(items, nextCursor?.let(::JsonPrimitive) ?: JsonNull)
}

/**
 * `GET /platform/organizations` (platformListOrganizations): the platform pipeline example.
 * The caller must already have passed [com.vrticconnect.authz.Authorize.requirePlatformAdmin];
 * the query runs in DbContext.Platform, which is the only context that lists every tenant.
 * Filters and sort are allowlisted; the cursor is only valid for `createdAt:desc`.
 */
class PlatformOrganizationService(private val database: Database) {

    suspend fun list(admin: AuthenticatedUser, query: Parameters): OrganizationPage {
        val page = PageRequest.from(query)
        val status = query["status"]?.also { if (it !in STATUSES) throw validation("status", "INVALID_ENUM", STATUSES.joinToString()) }
        val search = query["search"]?.trim()?.also { if (it.length !in 2..100) throw validation("search", "INVALID_LENGTH", "2..100") }
        val sort = query["sort"] ?: "createdAt:desc"
        if (sort !in SORTS) throw validation("sort", "INVALID_ENUM", SORTS.joinToString())
        if (page.cursor != null && sort != "createdAt:desc") throw validation("cursor", "INVALID_CURSOR", "cursor pagination only with createdAt:desc")

        val rows = database.transaction(DbContext.Platform(admin.userId)) { c ->
            val sql = StringBuilder("SELECT id, slug, name, legal_name, country_code, timezone, default_locale, status, created_at, updated_at FROM app.organizations WHERE deleted_at IS NULL")
            val params = mutableListOf<Any>()
            status?.let { sql.append(" AND status = ?"); params += it }
            search?.let { sql.append(" AND (lower(name) LIKE ? OR slug LIKE ?)"); params += it.lowercase() + "%"; params += it.lowercase() + "%" }
            page.cursor?.let { sql.append(" AND (created_at < ? OR (created_at = ? AND id < ?))"); params += Timestamp.from(it.at); params += Timestamp.from(it.at); params += it.id }
            sql.append(if (sort == "name:asc") " ORDER BY name ASC, id ASC" else " ORDER BY created_at DESC, id DESC")
            sql.append(" LIMIT ?"); params += page.limit + 1
            c.prepareStatement(sql.toString()).use { st ->
                params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                Row(
                                    createdAt = rs.getTimestamp("created_at").toInstant(),
                                    organization = Organization(
                                        id = rs.getObject("id", UUID::class.java).toString(),
                                        slug = rs.getString("slug"),
                                        name = rs.getString("name"),
                                        legalName = rs.getString("legal_name"),
                                        countryCode = rs.getString("country_code"),
                                        timezone = rs.getString("timezone"),
                                        defaultLocale = rs.getString("default_locale"),
                                        status = rs.getString("status"),
                                        createdAt = rs.getTimestamp("created_at").toInstant().toString(),
                                        updatedAt = rs.getTimestamp("updated_at").toInstant().toString(),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }
        val (items, next) = rows.toPage(page.limit) { Cursor(it.createdAt, UUID.fromString(it.organization.id)) }
        return OrganizationPage(items.map { it.organization }, if (sort == "createdAt:desc") next else null)
    }

    private data class Row(val createdAt: Instant, val organization: Organization)

    private fun validation(field: String, code: String, message: String) =
        ProblemException(status = HttpStatusCode.UnprocessableEntity, type = ProblemTypes.VALIDATION, title = "Validation failed", errors = listOf(FieldError(field, code, message)))

    private companion object {
        val STATUSES = setOf("ACTIVE", "SUSPENDED", "ARCHIVED")
        val SORTS = setOf("createdAt:desc", "name:asc")
    }
}
