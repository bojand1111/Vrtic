package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.config.AppEnv
import com.vrticconnect.db.Database
import com.vrticconnect.db.DataSources
import com.vrticconnect.db.DbContext
import com.vrticconnect.db.Migrations
import com.vrticconnect.modules.auth.Argon2idPasswordHasher
import com.vrticconnect.modules.auth.PasswordPolicy
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
 *  - `dev-set-password <email>...` : DEV ONLY (E02-D03), one or more e-mails. Hashes SEED_DEV_PASSWORD from the environment and stores it
 *                         for a seeded account so the dev seed never contains a password. Refuses outside APP_ENV=dev.
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: "serve"
    val config = AppConfig.fromEnvironment()
    when (command) {
        "serve" -> serve(config)
        "migrate" -> migrate(config)
        "benchmark-argon2" -> benchmarkArgon2(config)
        "dev-set-password" -> args.drop(1).ifEmpty { listOf(null) }.forEach { devSetPassword(config, it) }
        else -> {
            System.err.println("Unknown command '$command'. Use: serve | migrate | benchmark-argon2 | dev-set-password <email>")
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

private fun devSetPassword(config: AppConfig, email: String?) {
    if (config.env != AppEnv.DEV) {
        System.err.println("dev-set-password is available only with APP_ENV=dev (current: ${config.env})")
        exitProcess(3)
    }
    if (email.isNullOrBlank()) {
        System.err.println("Usage: dev-set-password <email>   (password is read from SEED_DEV_PASSWORD, never from arguments)")
        exitProcess(2)
    }
    val password = System.getenv("SEED_DEV_PASSWORD")?.toCharArray()
    if (password == null || password.isEmpty()) {
        System.err.println("SEED_DEV_PASSWORD is not set")
        exitProcess(2)
    }
    try {
        val violations = PasswordPolicy.violations(String(password), email)
        if (violations.isNotEmpty()) {
            System.err.println("SEED_DEV_PASSWORD violates the password policy: " + violations.joinToString { it.code })
            exitProcess(4)
        }
        val hash = Argon2idPasswordHasher(config.argon2).hash(password)
        val runtime = DataSources.runtime(config, maxPoolSize = 1)
        try {
            val updated = Database(runtime).transactionBlocking(DbContext.Auth()) { c ->
                c.prepareStatement(
                    "UPDATE app.users SET password_hash = ?, password_updated_at = CURRENT_TIMESTAMP, email_verified_at = COALESCE(email_verified_at, CURRENT_TIMESTAMP), " +
                        "failed_login_count = 0, locked_until = NULL WHERE email = ? AND deleted_at IS NULL",
                ).use { st -> st.setString(1, hash); st.setString(2, email.trim()); st.executeUpdate() }
            }
            if (updated == 1) log.info("dev-set-password: password set for {}", email.trim()) else {
                System.err.println("No such user: ${email.trim()} (load docs/database/seed/dev_seed.sql first)")
                exitProcess(5)
            }
        } finally {
            runtime.close()
        }
    } finally {
        password.fill('\u0000')
    }
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
