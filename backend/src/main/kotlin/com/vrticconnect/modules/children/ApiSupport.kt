package com.vrticconnect.modules.children

import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/**
 * Small helpers shared by the children, absences and schedules modules (same owner):
 * ETag / If-Match handling, partial-update (PATCH) field access and enrollment status derivation.
 */
object ApiSupport {
    private val IF_MATCH = Regex("^(W/)?\"?([0-9]{1,9})\"?$")

    fun etag(version: Int): String = "\"$version\""

    fun setEtag(call: ApplicationCall, version: Int) {
        call.response.headers.append(HttpHeaders.ETag, etag(version))
    }

    /** Version from `If-Match` (`"3"`), or null when the header is absent. Malformed -> 422. */
    fun ifMatch(call: ApplicationCall): Int? {
        val raw = call.request.headers[HttpHeaders.IfMatch]?.trim() ?: return null
        val match = IF_MATCH.matchEntire(raw) ?: throw ProblemException(
            status = HttpStatusCode.UnprocessableEntity, type = ProblemTypes.VALIDATION, title = "Validation failed",
            errors = listOf(FieldError("If-Match", "INVALID_FORMAT", "\"<version>\"")),
        )
        return match.groupValues[2].toInt()
    }

    fun preconditionRequired() = ProblemException(
        status = HttpStatusCode(428, "Precondition Required"), type = ProblemTypes.VALIDATION, title = "Precondition required",
        detail = "IF_MATCH_REQUIRED",
    )

    /** Enrollment status as seen on [today]: stored PLANNED/ACTIVE rows become ACTIVE/ENDED by date. */
    fun effectiveEnrollmentStatus(stored: String, validFrom: LocalDate, validTo: LocalDate?, today: LocalDate): String = when {
        stored == "ENDED" || stored == "CANCELLED" -> stored
        validTo != null && validTo < today -> "ENDED"
        validFrom > today -> "PLANNED"
        else -> "ACTIVE"
    }

    fun escapeLike(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

/**
 * Typed access to a PATCH body: distinguishes "absent" (not changed) from explicit `null`.
 * Wrong JSON types become field errors collected by the caller.
 */
class Patch(private val body: JsonObject) {
    val errors = mutableListOf<FieldError>()

    fun has(field: String): Boolean = body.containsKey(field)
    val isEmpty: Boolean get() = body.isEmpty()
    fun keys(): Set<String> = body.keys

    /** Absent -> null; explicit null -> Present(null); string -> Present(value). */
    fun string(field: String): Present<String?>? {
        val element = body[field] ?: return null
        if (element is JsonNull) return Present(null)
        if (element is JsonPrimitive && element.isString) return Present(element.content)
        errors += FieldError(field, "INVALID_TYPE", "string expected")
        return null
    }

    fun boolean(field: String): Boolean? {
        val element = body[field] ?: return null
        if (element is JsonPrimitive && !element.isString) {
            when (element.content) {
                "true" -> return true
                "false" -> return false
            }
        }
        errors += FieldError(field, "INVALID_TYPE", "boolean expected")
        return null
    }

    fun int(field: String): Int? {
        val element = body[field] ?: return null
        if (element is JsonPrimitive && !element.isString) element.content.toIntOrNull()?.let { return it }
        errors += FieldError(field, "INVALID_TYPE", "integer expected")
        return null
    }

    fun throwIfInvalid() {
        if (errors.isNotEmpty()) {
            throw ProblemException(
                status = HttpStatusCode.UnprocessableEntity, type = ProblemTypes.VALIDATION, title = "Validation failed", errors = errors.toList(),
            )
        }
    }

    data class Present<T>(val value: T)
}
