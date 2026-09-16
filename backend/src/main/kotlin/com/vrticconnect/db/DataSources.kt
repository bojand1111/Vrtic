package com.vrticconnect.db

import com.vrticconnect.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

object DataSources {
    /** Pool for the API process: `app_runtime`, RLS enforced, no DDL. */
    fun runtime(config: AppConfig, maxPoolSize: Int = 10): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                username = config.dbRuntimeUser
                password = config.requireRuntimePassword()
                maximumPoolSize = maxPoolSize
                minimumIdle = 1
                poolName = "vrtic-runtime"
                isAutoCommit = false
                connectionTimeout = 5_000
                validationTimeout = 2_000
                // Defensive: every connection handed back to the pool is reset. Transaction-local
                // set_config(..., true) values are already discarded at COMMIT/ROLLBACK, so this only
                // guards against session-level settings leaking by mistake.
                connectionInitSql = "SET search_path TO app, public"
                addDataSourceProperty("ApplicationName", "vrtic-api")
                addDataSourceProperty("reWriteBatchedInserts", "true")
            },
        )

    /** Short-lived pool for the migration job: `app_owner`. Never used by the HTTP server. */
    fun owner(config: AppConfig): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                username = config.dbOwnerUser
                password = config.requireOwnerPassword()
                maximumPoolSize = 2
                poolName = "vrtic-migrate"
                addDataSourceProperty("ApplicationName", "vrtic-migrate")
            },
        )

    fun DataSource.pingOrThrow() {
        connection.use { c -> c.createStatement().use { it.execute("SELECT 1") } }
    }
}
