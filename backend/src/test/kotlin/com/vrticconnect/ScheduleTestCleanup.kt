package com.vrticconnect

import com.vrticconnect.config.AppConfig
import java.sql.DriverManager
import java.util.UUID

/**
 * `schedule_change_log` is append-only (trigger), so the cascade from `DELETE organizations` in
 * TestTenants.destroy() would be refused. Tests that change schedules call this before destroy(): as the
 * table owner in maintenance mode, with the trigger disabled for this transaction only.
 */
object ScheduleTestCleanup {
    fun removeChangeLog(config: AppConfig, vararg orgs: UUID) {
        DriverManager.getConnection(config.jdbcUrl, config.dbOwnerUser, config.requireOwnerPassword()).use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use { it.execute("SELECT set_config('app.maintenance_mode', 'on', true)") }
                c.createStatement().use { it.execute("ALTER TABLE app.schedule_change_log DISABLE TRIGGER schedule_change_log_append_only") }
                c.prepareStatement("DELETE FROM app.schedule_change_log WHERE organization_id = ANY(?)").use { st ->
                    st.setArray(1, c.createArrayOf("uuid", orgs.toList().toTypedArray()))
                    st.executeUpdate()
                }
                c.createStatement().use { it.execute("ALTER TABLE app.schedule_change_log ENABLE TRIGGER schedule_change_log_append_only") }
                c.commit()
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }
}
