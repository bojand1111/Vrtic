package com.vrticconnect.db

import com.vrticconnect.config.AppConfig
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult

/**
 * Flyway runs ONLY with the owner role and ONLY from the `migrate` command (single migration job).
 * The HTTP server never has owner credentials.
 */
object Migrations {
    fun migrate(config: AppConfig): MigrateResult {
        val owner = DataSources.owner(config)
        try {
            val flyway = Flyway.configure()
                .dataSource(owner)
                .schemas("app")
                .defaultSchema("app")
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .validateMigrationNaming(true)
                .placeholderReplacement(false)
                .load()
            return flyway.migrate()
        } finally {
            owner.close()
        }
    }
}
