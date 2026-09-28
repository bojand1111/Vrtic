package com.vrticconnect.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Collects field errors and throws one 422 `problem+json` with all of them (docs/API.md conventions).
 * Usage: `validate { require(name.isNotBlank(), "name", "REQUIRED") }`.
 */
class Validation {
    private val errors = mutableListOf<FieldError>()

    fun require(condition: Boolean, field: String, code: String, message: String = code) {
        if (!condition) errors += FieldError(field, code, message)
    }

    fun text(value: String?, field: String, max: Int = 200, required: Boolean = true) {
        if (value == null || value.isBlank()) {
            if (required) errors += FieldError(field, "REQUIRED", "$field is required")
            return
        }
        if (value.length > max) errors += FieldError(field, "TOO_LONG", "max $max characters")
    }

    fun date(value: String?, field: String, required: Boolean = true): LocalDate? {
        if (value == null || value.isBlank()) {
            if (required) errors += FieldError(field, "REQUIRED", "$field is required")
            return null
        }
        return runCatching { LocalDate.parse(value) }.getOrElse {
            errors += FieldError(field, "INVALID_FORMAT", "YYYY-MM-DD"); null
        }
    }

    fun time(value: String?, field: String, required: Boolean = true): LocalTime? {
        if (value == null || value.isBlank()) {
            if (required) errors += FieldError(field, "REQUIRED", "$field is required")
            return null
        }
        if (!TIME.matches(value)) {
            errors += FieldError(field, "INVALID_FORMAT", "HH:MM"); return null
        }
        return LocalTime.parse(value)
    }

    fun uuid(value: String?, field: String, required: Boolean = true): UUID? {
        if (value == null || value.isBlank()) {
            if (required) errors += FieldError(field, "REQUIRED", "$field is required")
            return null
        }
        return runCatching { UUID.fromString(value) }.getOrElse {
            errors += FieldError(field, "INVALID_FORMAT", "UUID"); null
        }
    }

    fun <E : Enum<E>> enum(value: String?, field: String, values: Array<E>, required: Boolean = true): E? {
        if (value == null || value.isBlank()) {
            if (required) errors += FieldError(field, "REQUIRED", "$field is required")
            return null
        }
        return values.firstOrNull { it.name == value } ?: run {
            errors += FieldError(field, "INVALID_VALUE", values.joinToString("|") { it.name }); null
        }
    }

    fun oneOf(value: String?, field: String, allowed: Set<String>, required: Boolean = true): String? {
        if (value == null || value.isBlank()) {
            if (required) errors += FieldError(field, "REQUIRED", "$field is required")
            return null
        }
        if (value !in allowed) {
            errors += FieldError(field, "INVALID_VALUE", allowed.sorted().joinToString("|")); return null
        }
        return value
    }

    fun throwIfInvalid() {
        if (errors.isNotEmpty()) {
            throw ProblemException(
                status = HttpStatusCode.UnprocessableEntity, type = ProblemTypes.VALIDATION,
                title = "Validation failed", errors = errors.toList(),
            )
        }
    }

    private companion object {
        val TIME = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    }
}

inline fun <T> validate(block: Validation.() -> T): T {
    val v = Validation()
    val result = v.block()
    v.throwIfInvalid()
    return result
}

/** Path id that must be a UUID; anything else is an unknown resource (404, never 400/422). */
fun ApplicationCall.pathUuid(name: String): UUID =
    parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw ProblemException.notFound()

/** Optional UUID query parameter; malformed -> 422. */
fun ApplicationCall.queryUuid(name: String): UUID? {
    val raw = request.queryParameters[name] ?: return null
    return runCatching { UUID.fromString(raw) }.getOrElse { throw invalidQuery(name, "UUID") }
}

/** Optional ISO date query parameter; malformed -> 422. */
fun ApplicationCall.queryDate(name: String): LocalDate? {
    val raw = request.queryParameters[name] ?: return null
    return runCatching { LocalDate.parse(raw) }.getOrElse { throw invalidQuery(name, "YYYY-MM-DD") }
}

fun invalidQuery(name: String, expected: String) = ProblemException(
    status = HttpStatusCode.UnprocessableEntity, type = ProblemTypes.VALIDATION, title = "Validation failed",
    errors = listOf(FieldError(name, "INVALID_FORMAT", expected)),
)

/** 409 for a stale `version` (optimistic concurrency) or a state that forbids the command. */
fun conflict(detail: String, currentVersion: Int? = null) = ProblemException(
    status = HttpStatusCode.Conflict, type = ProblemTypes.CONFLICT, title = "Conflict", detail = detail, currentVersion = currentVersion,
)
