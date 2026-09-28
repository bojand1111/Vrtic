package com.vrticconnect.http

import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Cursor pagination (docs/API.md "Paginacija"): `cursor` is an opaque base64url of
 * `<epoch micros of the sort instant>:<uuid>`, valid only for the same sort and filters;
 * `limit` is 1..100, default 50. Invalid values answer 422.
 */
data class PageRequest(val limit: Int, val cursor: Cursor?) {
    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 100

        fun from(query: Parameters): PageRequest {
            val limit = query["limit"]?.let { raw ->
                raw.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
                    ?: throw validation("limit", "INVALID_RANGE", "limit must be an integer in 1..$MAX_LIMIT")
            } ?: DEFAULT_LIMIT
            val cursor = query["cursor"]?.let { raw ->
                Cursor.decode(raw) ?: throw validation("cursor", "INVALID_CURSOR", "cursor is not valid for this listing")
            }
            return PageRequest(limit, cursor)
        }

        private fun validation(field: String, code: String, message: String) = ProblemException(
            status = HttpStatusCode.UnprocessableEntity,
            type = ProblemTypes.VALIDATION,
            title = "Validation failed",
            errors = listOf(FieldError(field, code, message)),
        )
    }
}

/** Sort position of the last item of a page: (instant, id). */
data class Cursor(val at: Instant, val id: UUID) {
    fun encode(): String {
        val micros = at.epochSecond * 1_000_000 + at.nano / 1_000
        return encoder.encodeToString("$micros:$id".toByteArray(Charsets.US_ASCII))
    }

    companion object {
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        fun decode(raw: String): Cursor? {
            if (raw.isEmpty() || raw.length > 512) return null
            val text = runCatching { String(decoder.decode(raw), Charsets.US_ASCII) }.getOrNull() ?: return null
            val parts = text.split(':')
            if (parts.size != 2) return null
            val micros = parts[0].toLongOrNull() ?: return null
            val id = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return null
            return Cursor(Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1_000), id)
        }
    }
}

/** Trims a `limit + 1` fetch to a page and computes `nextCursor` from the last returned item. */
fun <T> List<T>.toPage(limit: Int, cursorOf: (T) -> Cursor): Pair<List<T>, String?> {
    if (size <= limit) return this to null
    val items = take(limit)
    return items to cursorOf(items.last()).encode()
}
