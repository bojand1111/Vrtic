package com.vrticconnect.modules.announcements

import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import com.vrticconnect.http.conflict
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Optimistic concurrency per docs/openapi.yaml: responses carry `ETag: "<version>"`, versioned writes
 * need `If-Match` (missing -> 428, malformed -> 422, stale -> 409 VERSION_MISMATCH with currentVersion).
 * Shared by the daily-operations modules (announcements, calendar, meals).
 */
object Versioning {
    private val ETAG = Regex("^(W/)?\"([0-9]{1,9})\"$")

    fun ifMatch(call: ApplicationCall): Int {
        val raw = call.request.headers[HttpHeaders.IfMatch]?.trim()
            ?: throw ProblemException(
                status = HttpStatusCode(428, "Precondition Required"), type = "https://docs.vrticconnect.example/problems/precondition-required",
                title = "Precondition required", detail = "IF_MATCH_REQUIRED",
            )
        return ETAG.find(raw)?.groupValues?.get(2)?.toIntOrNull()
            ?: throw ProblemException(
                status = HttpStatusCode.UnprocessableEntity, type = ProblemTypes.VALIDATION, title = "Validation failed",
                errors = listOf(FieldError("If-Match", "INVALID_FORMAT", "\"<version>\"")),
            )
    }

    fun etag(call: ApplicationCall, version: Int) {
        call.response.headers.append(HttpHeaders.ETag, "\"$version\"")
    }

    fun requireVersion(expected: Int, current: Int) {
        if (expected != current) throw conflict("VERSION_MISMATCH", current)
    }
}

/** PATCH helpers over a raw JSON object: distinguish "absent" from "null". */
object JsonPatch {
    fun has(obj: JsonObject, key: String) = obj.containsKey(key)

    /** String value, null for JSON null; a non-string value is reported through [bad]. */
    fun string(obj: JsonObject, key: String, bad: (String) -> Unit): String? = when (val v: JsonElement? = obj[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (v.isString) v.content else { bad(key); null }
        else -> { bad(key); null }
    }

    fun boolean(obj: JsonObject, key: String, bad: (String) -> Unit): Boolean? = when (val v: JsonElement? = obj[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (!v.isString && (v.content == "true" || v.content == "false")) v.content == "true" else { bad(key); null }
        else -> { bad(key); null }
    }
}
