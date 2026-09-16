package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.modules.health.AlwaysUpProbe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Proves the skeleton is "safely closed": no route issues tokens or leaks data. */
class AuthClosedRoutesTest {

    private val deps = AppDependencies(
        config = AppConfig.fromEnvironment(mapOf("APP_ENV" to "dev")),
        database = null,
        readiness = AlwaysUpProbe,
    )

    @Test
    fun `login answers 501 problem+json and never a token`() = testApplication {
        application { module(deps) }
        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"owner@example.test","password":"whatever","clientKind":"WEB"}""")
        }
        assertEquals(HttpStatusCode.NotImplemented, response.status)
        assertEquals("application/problem+json", response.headers[HttpHeaders.ContentType]?.substringBefore(";"))
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\":501"))
        assertFalse(body.contains("token", ignoreCase = true))
        assertTrue(response.headers[HttpHeaders.SetCookie].isNullOrEmpty())
    }

    @Test
    fun `session bootstrap without credentials answers 401`() = testApplication {
        application { module(deps) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/session").status)
    }

    @Test
    fun `refresh answers 501`() = testApplication {
        application { module(deps) }
        assertEquals(HttpStatusCode.NotImplemented, client.post("/api/v1/auth/refresh").status)
    }

    @Test
    fun `tenant route without credentials answers 401`() = testApplication {
        application { module(deps) }
        val response = client.get("/api/v1/organizations/00000000-0000-0000-0000-000000000001/ping")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.headers[HttpHeaders.WWWAuthenticate]?.startsWith("Bearer") == true)
    }

    @Test
    fun `tenant route with an arbitrary bearer token still answers 401`() = testApplication {
        application { module(deps) }
        val response = client.get("/api/v1/organizations/00000000-0000-0000-0000-000000000001/ping") {
            header(HttpHeaders.Authorization, "Bearer vca_not-a-real-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `platform route without credentials answers 401`() = testApplication {
        application { module(deps) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/platform/organizations").status)
    }
}
