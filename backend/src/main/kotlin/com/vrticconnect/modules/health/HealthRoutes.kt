package com.vrticconnect.modules.health

import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class HealthResponse(val status: String, val checks: Map<String, String> = emptyMap())

fun interface ReadinessProbe {
    /** Returns a map check-name -> "UP"/"DOWN". Must never expose connection strings or errors. */
    suspend fun check(): Map<String, String>
}

class DatabaseReadinessProbe(private val database: Database) : ReadinessProbe {
    private val log = LoggerFactory.getLogger(DatabaseReadinessProbe::class.java)

    override suspend fun check(): Map<String, String> {
        val db = runCatching {
            database.transaction(DbContext.None) { conn ->
                conn.createStatement().use { st -> st.executeQuery("SELECT 1").use { rs -> rs.next() } }
            }
        }.onFailure { log.warn("Readiness: database check failed: {}", it.javaClass.simpleName) }
            .getOrDefault(false)

        // Migrations applied? The runtime role is granted SELECT on flyway_schema_history in V1.
        val migrated = if (!db) false else runCatching {
            database.transaction(DbContext.None) { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT count(*) FROM app.flyway_schema_history WHERE success").use { rs ->
                        rs.next() && rs.getLong(1) >= 1
                    }
                }
            }
        }.getOrDefault(false)

        return mapOf("database" to if (db) "UP" else "DOWN", "migrations" to if (migrated) "UP" else "DOWN")
    }
}

object AlwaysUpProbe : ReadinessProbe {
    override suspend fun check(): Map<String, String> = mapOf("database" to "UP", "migrations" to "UP")
}

fun Route.healthRoutes(readiness: ReadinessProbe) {
    get("/health/live") {
        call.respond(HealthResponse(status = "UP"))
    }
    get("/health/ready") {
        val checks = readiness.check()
        val up = checks.values.all { it == "UP" }
        call.respond(
            if (up) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            HealthResponse(status = if (up) "UP" else "DOWN", checks = checks),
        )
    }
}
