package com.vrticconnect.db

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Small JDBC helpers shared by the business modules (explicit SQL, no ORM; docs/DEVELOPMENT_ROADMAP.md 2).
 * Parameters are bound by their Kotlin type; `null` binds an untyped SQL NULL.
 */
fun PreparedStatement.bind(params: List<Any?>): PreparedStatement {
    params.forEachIndexed { index, value ->
        val i = index + 1
        when (value) {
            null -> setObject(i, null)
            is UUID -> setObject(i, value)
            is String -> setString(i, value)
            is Int -> setInt(i, value)
            is Long -> setLong(i, value)
            is Boolean -> setBoolean(i, value)
            is LocalDate -> setObject(i, value)
            is LocalTime -> setObject(i, value)
            is Instant -> setObject(i, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
            is OffsetDateTime -> setObject(i, value)
            is SqlArray -> setArray(i, connection.createArrayOf(value.sqlType, value.values.toTypedArray()))
            else -> error("Unsupported JDBC parameter type: ${value::class.simpleName}")
        }
    }
    return this
}

/** Array parameter, e.g. `SqlArray("text", listOf("GLUTEN"))` or `SqlArray("uuid", ids)`. */
data class SqlArray(val sqlType: String, val values: List<Any>)

fun <T> Connection.queryList(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        st.bind(params.toList()).executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    prepareStatement(sql).use { st ->
        st.bind(params.toList()).executeQuery().use { rs -> if (rs.next()) map(rs) else null }
    }

fun Connection.update(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { st -> st.bind(params.toList()).executeUpdate() }

fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)
fun ResultSet.date(column: String): LocalDate = getObject(column, LocalDate::class.java)
fun ResultSet.dateOrNull(column: String): LocalDate? = getObject(column, LocalDate::class.java)
fun ResultSet.timeOrNull(column: String): LocalTime? = getObject(column, LocalTime::class.java)
fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()
fun ResultSet.instantOrNull(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

@Suppress("UNCHECKED_CAST")
fun ResultSet.stringList(column: String): List<String> =
    (getArray(column)?.array as Array<Any?>?)?.mapNotNull { it as String? } ?: emptyList()

/** "HH:MM" for API payloads (wall-clock times in the organization's timezone). */
fun LocalTime.hhmm(): String = toString().take(5)
