package com.vrticconnect.core.api

import com.vrticconnect.core.auth.SessionTokens
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Base URL configuration for the Vrtić Connect API.
 *
 * [baseUrl] must be an absolute URL. A trailing slash is added automatically so that
 * relative request paths ("health/ready") resolve under it.
 */
data class ApiConfig(val baseUrl: String) {
    val normalizedBaseUrl: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
}

/** Shared JSON configuration: tolerant of unknown keys so DTO evolution does not break old clients. */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Platform HTTP engine: CIO on JVM/Android, Darwin on iOS. */
expect fun createPlatformEngine(): HttpClientEngine

/**
 * Builds the shared [HttpClient]. Pass a custom [engine] (e.g. Ktor MockEngine) in tests.
 */
fun createHttpClient(
    config: ApiConfig,
    engine: HttpClientEngine = createPlatformEngine(),
    json: Json = ApiJson,
): HttpClient = HttpClient(engine) {
    expectSuccess = false // non-2xx responses are mapped to ApiProblem explicitly
    install(ContentNegotiation) {
        json(json)
    }
    defaultRequest {
        url(config.normalizedBaseUrl)
    }
}

/**
 * RFC 9457 `application/problem+json` payload as returned by the backend.
 *
 * All fields are optional so that a partially populated body still parses.
 */
@Serializable
data class ApiProblem(
    val type: String = "about:blank",
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
) {
    /** True when the backend deliberately answered 501 Not Implemented (see [AuthApi]). */
    val isNotImplemented: Boolean get() = status == 501

    /** Short human-readable form for diagnostics UI. */
    fun summary(): String = buildString {
        status?.let { append(it).append(' ') }
        append(title ?: type)
        detail?.let { append(": ").append(it) }
    }

    companion object {
        const val TYPE_TRANSPORT = "urn:vrticconnect:problem:transport"

        /** Problem describing a network / transport failure (no HTTP response was received). */
        fun transport(cause: Throwable): ApiProblem = ApiProblem(
            type = TYPE_TRANSPORT,
            title = "Transport error",
            status = null,
            detail = cause.message ?: cause::class.simpleName,
        )
    }
}

/**
 * Parses an HTTP error response into an [ApiProblem].
 *
 * - If the body is a JSON object, it is decoded leniently (unknown keys ignored). A missing
 *   `status` is filled from [httpStatus].
 * - If the body is empty or not JSON, a fallback problem with [httpStatus] and the raw body
 *   (truncated) as `detail` is returned. This function never throws.
 */
fun parseProblem(httpStatus: Int, body: String?, contentType: String? = null): ApiProblem {
    val text = body?.trim().orEmpty()
    val looksLikeJson = text.startsWith("{")
    val declaredJson = contentType?.contains("json", ignoreCase = true) == true
    if (text.isNotEmpty() && (looksLikeJson || declaredJson)) {
        try {
            val element = ApiJson.parseToJsonElement(text)
            if (element is JsonObject) {
                val decoded = ApiJson.decodeFromJsonElement(ApiProblem.serializer(), element)
                return if (decoded.status == null) decoded.copy(status = httpStatus) else decoded
            }
        } catch (_: SerializationException) {
            // fall through to the fallback below
        } catch (_: IllegalArgumentException) {
            // fall through to the fallback below
        }
    }
    return ApiProblem(
        type = "about:blank",
        title = "HTTP $httpStatus",
        status = httpStatus,
        detail = text.takeIf { it.isNotEmpty() }?.take(500),
    )
}

/** Result of an API call: either a decoded value or an [ApiProblem]. */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val problem: ApiProblem) : ApiResult<Nothing>
}

/** Converts a non-2xx [HttpResponse] into an [ApiProblem]. */
suspend fun HttpResponse.toProblem(): ApiProblem =
    parseProblem(status.value, bodyAsText(), contentType()?.toString())

/**
 * Runs [block] and maps transport failures (no response at all) into [ApiResult.Failure].
 * Cancellation is always propagated.
 */
internal suspend inline fun <T> apiCall(crossinline block: suspend () -> ApiResult<T>): ApiResult<T> =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApiResult.Failure(ApiProblem.transport(e))
    }

@Serializable
data class HealthStatus(val status: String = "ok")

/** GET /health/ready — readiness probe used by the "Check API" button. */
class HealthApi(private val client: HttpClient) {
    suspend fun ready(): ApiResult<HealthStatus> = apiCall {
        val response = client.get("health/ready")
        if (response.status.isSuccess()) {
            val status = runCatching { response.body<HealthStatus>() }.getOrDefault(HealthStatus())
            ApiResult.Success(status)
        } else {
            ApiResult.Failure(response.toProblem())
        }
    }
}

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

/** Token pair as returned by the backend. Tokens are opaque strings (see REQUIREMENTS_BRIEF §7). */
@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresInSeconds: Int? = null,
) {
    fun toSessionTokens(): SessionTokens = SessionTokens(access = accessToken, refresh = refreshToken)
}

/**
 * Authentication endpoints. Declared now so the rest of the app can depend on the contract;
 * the backend currently answers 501 Not Implemented, which is surfaced as an [ApiProblem].
 */
interface AuthApi {
    suspend fun login(email: String, password: String): ApiResult<SessionTokens>
    suspend fun refresh(refreshToken: String): ApiResult<SessionTokens>
}

/**
 * Default [AuthApi]: POST api/v1/auth/login and POST api/v1/auth/refresh.
 * Any non-2xx status (including the intentional 501) is mapped to [ApiResult.Failure].
 */
class DefaultAuthApi(private val client: HttpClient) : AuthApi {
    override suspend fun login(email: String, password: String): ApiResult<SessionTokens> = apiCall {
        val response = client.post("api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email, password))
        }
        response.toTokens()
    }

    override suspend fun refresh(refreshToken: String): ApiResult<SessionTokens> = apiCall {
        val response = client.post("api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken))
        }
        response.toTokens()
    }

    private suspend fun HttpResponse.toTokens(): ApiResult<SessionTokens> =
        if (status.isSuccess()) {
            ApiResult.Success(body<TokenResponse>().toSessionTokens())
        } else {
            ApiResult.Failure(toProblem())
        }
}
