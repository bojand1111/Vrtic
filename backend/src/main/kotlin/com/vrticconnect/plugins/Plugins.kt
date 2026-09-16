package com.vrticconnect.plugins

import com.vrticconnect.http.Problem
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.ProblemTypes
import com.vrticconnect.http.respondProblem
import com.vrticconnect.http.toProblem
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID

private val log = LoggerFactory.getLogger("com.vrticconnect.http")

fun Application.configureCallId() {
    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.length in 8..128 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
    }
}

fun Application.configureLogging() {
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
        // Never log query strings, bodies or headers: path + method + status only.
        format { call -> "${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()?.value}" }
    }
}

fun Application.configureDefaultHeaders() {
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "no-referrer")
        header("Cache-Control", "no-store")
        header(HttpHeaders.Server, "vrtic")
    }
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = false
            },
        )
    }
}

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<ProblemException> { call, cause ->
            call.respondProblem(call.toProblem(cause), cause.status, cause.headers)
        }
        exception<SerializationException> { call, cause ->
            call.respondProblem(
                Problem(
                    type = ProblemTypes.VALIDATION,
                    title = "Malformed request body",
                    status = 422,
                    detail = cause.message?.take(200),
                    instance = call.request.path(),
                    requestId = call.callId,
                ),
                HttpStatusCode.UnprocessableEntity,
            )
        }
        exception<Throwable> { call, cause ->
            log.error("Unhandled error requestId={}", call.callId, cause)
            call.respondProblem(
                Problem(
                    type = ProblemTypes.INTERNAL,
                    title = "Internal server error",
                    status = 500,
                    instance = call.request.path(),
                    requestId = call.callId,
                ),
                HttpStatusCode.InternalServerError,
            )
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondProblem(
                Problem(type = ProblemTypes.NOT_FOUND, title = "Not found", status = status.value, instance = call.request.path(), requestId = call.callId),
                status,
            )
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respondProblem(
                Problem(type = ProblemTypes.NOT_FOUND, title = "Method not allowed", status = status.value, instance = call.request.path(), requestId = call.callId),
                status,
            )
        }
    }
}
