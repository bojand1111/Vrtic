package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.Database
import com.vrticconnect.db.DataSources
import com.vrticconnect.db.Migrations
import com.vrticconnect.modules.auth.Argon2idPasswordHasher
import com.vrticconnect.modules.health.DatabaseReadinessProbe
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

private val log = LoggerFactory.getLogger("com.vrticconnect.Main")

/**
 * Process entry point. One image, three commands:
 *  - `serve`            : run the HTTP API with the runtime (RLS-bound) database role.
 *  - `migrate`          : run Flyway migrations with the owner role, then exit (single migration job).
 *  - `benchmark-argon2` : measure Argon2id hashing time for the configured parameters.
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "serve"
    val config = AppConfig.fromEnvironment()
    when (command) {
        "serve" -> serve(config)
        "migrate" -> migrate(config)
        "benchmark-argon2" -> benchmarkArgon2(config)
        else -> {
            System.err.println("Unknown command '$command'. Use: serve | migrate | benchmark-argon2")
            exitProcess(2)
        }
    }
}

private fun serve(config: AppConfig) {
    log.info("Starting vrtic-backend env={} port={}", config.env, config.httpPort)
    val runtimeDataSource = DataSources.runtime(config)
    val database = Database(runtimeDataSource)
    val deps = AppDependencies(
        config = config,
        database = database,
        readiness = DatabaseReadinessProbe(database),
    )
    embeddedServer(Netty, port = config.httpPort, host = "0.0.0.0") {
        module(deps)
    }.start(wait = true)
}

private fun migrate(config: AppConfig) {
    log.info("Running migrations env={} db={}", config.env, config.jdbcUrl)
    val result = Migrations.migrate(config)
    log.info("Migrations applied: {} (schema version {})", result.migrationsExecuted, result.targetSchemaVersion)
}

private fun benchmarkArgon2(config: AppConfig) {
    val hasher = Argon2idPasswordHasher(config.argon2)
    val samplePassword = CharArray(24) { 'x' }
    // warm-up
    hasher.hash(samplePassword)
    val runs = 5
    val elapsed = measureTimeMillis { repeat(runs) { hasher.hash(samplePassword) } }
    println(
        "Argon2id m=${config.argon2.memoryKib}KiB t=${config.argon2.iterations} p=${config.argon2.parallelism}: " +
            "avg ${elapsed / runs} ms per hash over $runs runs (target: 250-500 ms on the production server)",
    )
}
