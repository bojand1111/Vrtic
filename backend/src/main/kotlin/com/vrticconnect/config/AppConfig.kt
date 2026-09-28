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
 * Application data-encryption key (envelope layer for field encryption, e.g. TOTP secrets).
 * [keyId] is stored next to every ciphertext so a later rotation can tell keys apart.
 */
class DataKey(val keyId: String, private val bytes: ByteArray) {
    init {
        require(bytes.size == 32) { "APP_DATA_KEY must decode to exactly 32 bytes (AES-256)" }
    }

    fun bytes(): ByteArray = bytes.copyOf()

    override fun toString(): String = "DataKey(keyId=$keyId)"

    companion object {
        /**
         * DEV ONLY: fixed, documented key derived from a public constant, so a local database keeps
         * working across restarts without configuration. Never used outside APP_ENV=dev.
         */
        const val DEV_KEY_ID = "dev-1"
        private const val DEV_KEY_SEED = "vrtic-connect-dev-data-key-not-secret"

        fun dev(): DataKey = DataKey(DEV_KEY_ID, java.security.MessageDigest.getInstance("SHA-256").digest(DEV_KEY_SEED.toByteArray(Charsets.US_ASCII)))

        fun parse(base64: String, keyId: String): DataKey {
            val decoded = runCatching { java.util.Base64.getDecoder().decode(base64.trim()) }
                .getOrElse { throw IllegalArgumentException("APP_DATA_KEY is not valid base64") }
            return DataKey(keyId, decoded)
        }
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
    /** `APP_DATA_KEY` (base64, 32 bytes) + optional `APP_DATA_KEY_ID`; DEV falls back to [DataKey.dev]. */
    val dataKey: DataKey? = null,
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
                dataKey = env["APP_DATA_KEY"]?.takeIf { it.isNotBlank() }?.let { DataKey.parse(it, env["APP_DATA_KEY_ID"] ?: "app-1") }
                    ?: if (dev) DataKey.dev() else null,
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

    /** Called while wiring the API (`serve`): outside DEV a missing APP_DATA_KEY stops the process at startup. */
    fun requireDataKey(): DataKey =
        dataKey ?: error("APP_DATA_KEY is required in ${env.name} (base64 of 32 random bytes)")

    fun requireRuntimePassword(): String =
        dbRuntimePassword ?: error("APP_DB_RUNTIME_PASSWORD is required in ${env.name}")
}
