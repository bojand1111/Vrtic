package com.vrticconnect.modules.auth

import com.vrticconnect.http.FieldError
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import io.ktor.http.HttpStatusCode

/**
 * Password rules (docs/SECURITY.md 2.4, docs/openapi.yaml `password` fields): length 12..256,
 * not one of the most common passwords, not equal to the user's e-mail or its local part.
 * No forced character classes: length and blocklist beat composition rules.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 12
    const val MAX_LENGTH = 256

    private val common = setOf(
        "password", "password1", "password123", "123456789012", "qwertyuiop12", "iloveyou1234", "welcome12345",
        "letmein12345", "admin1234567", "administrator", "lozinka12345", "sifra1234567", "vrticconnect", "vrtic1234567",
        "abcdefghijkl", "1234567890ab", "qwerty123456", "passw0rd1234", "changeme1234", "football1234",
    )

    fun violations(password: String, email: String?): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        if (password.length < MIN_LENGTH) errors += FieldError("password", "TOO_SHORT", "at least $MIN_LENGTH characters")
        if (password.length > MAX_LENGTH) errors += FieldError("password", "TOO_LONG", "at most $MAX_LENGTH characters")
        val lowered = password.lowercase()
        if (lowered in common) errors += FieldError("password", "TOO_COMMON", "password is on the blocklist")
        email?.lowercase()?.let { mail ->
            if (lowered == mail || lowered == mail.substringBefore('@')) {
                errors += FieldError("password", "MATCHES_EMAIL", "password must not be the e-mail address")
            }
        }
        return errors
    }

    fun validateOrThrow(password: String, email: String?, field: String = "password") {
        val errors = violations(password, email).map { it.copy(field = field) }
        if (errors.isNotEmpty()) {
            throw ProblemException(
                status = HttpStatusCode.UnprocessableEntity,
                type = ProblemTypes.VALIDATION,
                title = "Password policy violation",
                errors = errors,
            )
        }
    }
}
