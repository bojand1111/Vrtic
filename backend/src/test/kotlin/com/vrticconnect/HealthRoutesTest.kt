package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.modules.health.AlwaysUpProbe
import com.vrticconnect.modules.health.ReadinessProbe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRoutesTest {

    private val config = AppConfig.fromEnvironment(mapOf("APP_ENV" to "dev"))

    private fun deps(probe: ReadinessProbe) = AppDependencies(config = config, database = null, readiness = probe)

    @Test
    fun `live returns UP without touching the database`() = testApplication {
        application { module(deps(AlwaysUpProbe)) }
        val response = client.get("/health/live")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"UP\""))
    }

    @Test
    fun `ready returns 503 when a check is DOWN`() = testApplication {
        val down = ReadinessProbe { mapOf("database" to "DOWN", "migrations" to "DOWN") }
        application { module(deps(down)) }
        val response = client.get("/health/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("\"database\":\"DOWN\""))
    }

    @Test
    fun `ready returns 200 when all checks are UP`() = testApplication {
        application { module(deps(AlwaysUpProbe)) }
        assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)
    }

    @Test
    fun `unknown route answers problem+json 404`() = testApplication {
        application { module(deps(AlwaysUpProbe)) }
        val response = client.get("/api/v1/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("application/problem+json", response.headers["Content-Type"]?.substringBefore(";"))
        assertTrue(response.bodyAsText().contains("\"requestId\""))
    }
}
