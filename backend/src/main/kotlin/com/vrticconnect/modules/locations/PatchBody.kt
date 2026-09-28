package com.vrticconnect.modules.locations

import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * JSON merge-style PATCH body (docs/openapi.yaml `*Update` schemas): a field that is absent stays
 * unchanged, an explicit `null` clears it. kotlinx data classes cannot tell "absent" from "null",
 * so PATCH handlers read the raw object through this helper. Type errors are collected as 422
 * field errors together with the handler's own validation.
 */
class PatchBody(private val obj: JsonObject) {
    val errors = mutableListOf<FieldError>()

    fun has(field: String): Boolean = obj.containsKey(field)

    fun isEmpty(): Boolean = obj.isEmpty()

    /** Absent -> null (use [has] first); explicit null -> null; non-string -> type error. */
    fun string(field: String): String? {
        val v = obj[field] ?: return null
        if (v is JsonNull) return null
        if (v is JsonPrimitive && v.isString) return v.content
        errors += FieldError(field, "INVALID_TYPE", "string expected"); return null
    }

    fun int(field: String): Int? {
        val v = obj[field] ?: return null
        if (v is JsonNull) return null
        if (v is JsonPrimitive && !v.isString) v.intOrNull?.let { return it }
        errors += FieldError(field, "INVALID_TYPE", "integer expected"); return null
    }

    fun isNull(field: String): Boolean = obj[field] is JsonNull

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun receive(call: ApplicationCall): PatchBody {
            val text = call.receiveText()
            val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
            if (element !is JsonObject) {
                throw ProblemException(
                    status = HttpStatusCode.UnprocessableEntity, type = ProblemTypes.VALIDATION, title = "Validation failed",
                    errors = listOf(FieldError("body", "INVALID_TYPE", "JSON object expected")),
                )
            }
            return PatchBody(element)
        }
    }
}

/** Throws one 422 with [errors] when not empty. */
fun throwIfErrors(errors: List<FieldError>) {
    if (errors.isNotEmpty()) {
        throw ProblemException(status = HttpStatusCode.UnprocessableEntity, type = ProblemTypes.VALIDATION, title = "Validation failed", errors = errors)
    }
}

/** Blank optional text becomes null; the rest is trimmed. */
fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** One 422 field error as an exception (for `?: throw`). */
fun fieldProblem(field: String, code: String, message: String) = ProblemException(
    status = HttpStatusCode.UnprocessableEntity, type = ProblemTypes.VALIDATION, title = "Validation failed",
    errors = listOf(FieldError(field, code, message)),
)
