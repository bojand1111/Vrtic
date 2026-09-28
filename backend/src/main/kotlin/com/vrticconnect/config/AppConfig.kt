package com.vrticconnect.config

enum class AppEnv { DEV, STAGING, PRODUCTION;
    companion object {
        fun parse(value: String): AppEnv = when (value.lowercase()) {
            "dev", "development", "local" -> DEV
            "staging", "stage" -> STAGING
            "production", "prod" -> PRODUCTION
            else -> error("Unknown APP_ENV '$value' (expected dev|staging|production)")
        }
    }
}

data class Argon2Config(
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    init {
        require(memoryKib >= 19_456) { "ARGON2_MEMORY_KIB must be >= 19456 (OWASP minimum)" }
        require(iterations >= 2) { "ARGON2_ITERATIONS must be >= 2" }
        require(parallelism in 1..8) { "ARGON2_PARALLELISM must be in 1..8" }
    }
}

/**
 * All configuration is read from environment variables (12-factor). Secrets never have defaults
 * outside DEV; in STAGING/PRODUCTION missing values fail fast at startup.
 */
data class AppConfig(
    val env: AppEnv,
    val httpPort: Int,
    val dbHost: String,
    val dbPort: Int,
    val dbName: String,
    val dbOwnerUser: String,
    val dbOwnerPassword: String?,
    val dbRuntimeUser: String,
    val dbRuntimePassword: String?,
    val webOrigin: String,
    val argon2: Argon2Config,
    /** Only behind the project's own reverse proxy: use the first X-Forwarded-For entry as client IP. */
    val trustProxyHeaders: Boolean = false,
) {
    val jdbcUrl: String get() = "jdbc:postgresql://$dbHost:$dbPort/$dbName"
    val isDev: Boolean get() = env == AppEnv.DEV

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AppConfig {
            val appEnv = AppEnv.parse(env["APP_ENV"] ?: "dev")
            val dev = appEnv == AppEnv.DEV
            fun secret(name: String, devDefault: String): String? =
                env[name] ?: if (dev) devDefault else null

            return AppConfig(
                env = appEnv,
                httpPort = env["APP_HTTP_PORT"]?.toInt() ?: 8080,
                dbHost = env["DB_HOST"] ?: "localhost",
                dbPort = env["DB_PORT"]?.toInt() ?: 5432,
                dbName = env["APP_DB_NAME"] ?: "vrtic",
                dbOwnerUser = env["APP_DB_OWNER_USER"] ?: "app_owner",
                dbOwnerPassword = secret("APP_DB_OWNER_PASSWORD", "change-me-owner"),
                dbRuntimeUser = env["APP_DB_RUNTIME_USER"] ?: "app_runtime",
                dbRuntimePassword = secret("APP_DB_RUNTIME_PASSWORD", "change-me-runtime"),
                webOrigin = env["APP_WEB_ORIGIN"] ?: "http://localhost:5173",
                trustProxyHeaders = env["APP_TRUST_PROXY"]?.equals("true", ignoreCase = true) ?: false,
                argon2 = Argon2Config(
                    memoryKib = env["ARGON2_MEMORY_KIB"]?.toInt() ?: 65_536,
                    iterations = env["ARGON2_ITERATIONS"]?.toInt() ?: 3,
                    parallelism = env["ARGON2_PARALLELISM"]?.toInt() ?: 1,
                ),
            )
        }
    }

    fun requireOwnerPassword(): String =
        dbOwnerPassword ?: error("APP_DB_OWNER_PASSWORD is required in ${env.name}")

    fun requireRuntimePassword(): String =
        dbRuntimePassword ?: error("APP_DB_RUNTIME_PASSWORD is required in ${env.name}")
}
