package com.vrticconnect.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** RFC 9457 problem details. Every non-2xx response of the API uses this shape. */
@Serializable
data class Problem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
    val requestId: String? = null,
    val errors: List<FieldError> = emptyList(),
    /** Present on 409 version conflicts. */
    val currentVersion: Int? = null,
)

@Serializable
data class FieldError(val field: String, val code: String, val message: String)

object ProblemTypes {
    private const val BASE = "https://docs.vrticconnect.example/problems/"
    const val NOT_IMPLEMENTED = BASE + "not-implemented"
    const val UNAUTHENTICATED = BASE + "unauthenticated"
    const val FORBIDDEN = BASE + "forbidden"
    const val NOT_FOUND = BASE + "not-found"
    const val CONFLICT = BASE + "conflict"
    const val VALIDATION = BASE + "validation"
    const val RATE_LIMITED = BASE + "rate-limited"
    const val INTERNAL = BASE + "internal"
}

class ProblemException(
    val status: HttpStatusCode,
    val type: String,
    val title: String,
    val detail: String? = null,
    val errors: List<FieldError> = emptyList(),
    val currentVersion: Int? = null,
    val headers: Map<String, String> = emptyMap(),
) : RuntimeException(title) {
    companion object {
        fun notImplemented(feature: String) = ProblemException(
            status = HttpStatusCode.NotImplemented,
            type = ProblemTypes.NOT_IMPLEMENTED,
            title = "Not implemented",
            detail = "$feature is designed but not implemented in this skeleton. See docs/DEVELOPMENT_ROADMAP.md.",
        )

        fun unauthenticated() = ProblemException(
            status = HttpStatusCode.Unauthorized,
            type = ProblemTypes.UNAUTHENTICATED,
            title = "Authentication required",
            headers = mapOf("WWW-Authenticate" to "Bearer realm=\"vrtic\""),
        )

        /** Used for both "no permission" and "resource belongs to another tenant" — never reveals existence. */
        fun notFound() = ProblemException(
            status = HttpStatusCode.NotFound,
            type = ProblemTypes.NOT_FOUND,
            title = "Not found",
        )
    }
}

val problemJson: Json = Json { encodeDefaults = false; explicitNulls = false }

val ProblemContentType: ContentType = ContentType("application", "problem+json")

suspend fun ApplicationCall.respondProblem(problem: Problem, status: HttpStatusCode, headers: Map<String, String> = emptyMap()) {
    headers.forEach { (k, v) -> response.headers.append(k, v) }
    respondText(problemJson.encodeToString(Problem.serializer(), problem), ProblemContentType, status)
}

fun ApplicationCall.toProblem(e: ProblemException): Problem = Problem(
    type = e.type,
    title = e.title,
    status = e.status.value,
    detail = e.detail,
    instance = request.local.uri,
    requestId = callId,
    errors = e.errors,
    currentVersion = e.currentVersion,
)
