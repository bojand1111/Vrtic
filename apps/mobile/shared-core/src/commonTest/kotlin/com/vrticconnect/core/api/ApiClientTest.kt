package com.vrticconnect.core.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ApiClientTest {

    private val config = ApiConfig("http://localhost:8080")

    @Test
    fun baseUrlIsNormalizedWithTrailingSlash() {
        assertEquals("http://localhost:8080/", ApiConfig("http://localhost:8080").normalizedBaseUrl)
        assertEquals("http://localhost:8080/", ApiConfig("http://localhost:8080/").normalizedBaseUrl)
    }

    @Test
    fun healthReadyMapsSuccess() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("http://localhost:8080/health/ready", request.url.toString())
            respond(
                content = """{"status":"ok"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = HealthApi(createHttpClient(config, engine)).ready()
        assertIs<ApiResult.Success<HealthStatus>>(result)
        assertEquals("ok", result.value.status)
    }

    @Test
    fun healthReadyMapsNonSuccessToProblem() = runTest {
        val engine = MockEngine {
            respond(content = "", status = HttpStatusCode.ServiceUnavailable)
        }
        val result = HealthApi(createHttpClient(config, engine)).ready()
        assertIs<ApiResult.Failure>(result)
        assertEquals(503, result.problem.status)
    }

    @Test
    fun healthReadyMapsTransportFailure() = runTest {
        val engine = MockEngine { throw IllegalStateException("connection refused") }
        val result = HealthApi(createHttpClient(config, engine)).ready()
        assertIs<ApiResult.Failure>(result)
        assertEquals(ApiProblem.TYPE_TRANSPORT, result.problem.type)
        assertEquals("connection refused", result.problem.detail)
    }

    @Test
    fun loginMaps501IntoApiProblem() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://localhost:8080/api/v1/auth/login", request.url.toString())
            respond(
                content = """{"type":"about:blank","title":"Not Implemented","status":501,"detail":"login is not implemented yet"}""",
                status = HttpStatusCode.NotImplemented,
                headers = headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        }
        val result = DefaultAuthApi(createHttpClient(config, engine)).login("a@b.c", "secret")
        assertIs<ApiResult.Failure>(result)
        assertTrue(result.problem.isNotImplemented)
        assertEquals("login is not implemented yet", result.problem.detail)
    }

    @Test
    fun refreshMaps501IntoApiProblem() = runTest {
        val engine = MockEngine { request ->
            assertEquals("http://localhost:8080/api/v1/auth/refresh", request.url.toString())
            respond(content = "", status = HttpStatusCode.NotImplemented)
        }
        val result = DefaultAuthApi(createHttpClient(config, engine)).refresh("opaque-refresh")
        assertIs<ApiResult.Failure>(result)
        assertEquals(501, result.problem.status)
    }
}
