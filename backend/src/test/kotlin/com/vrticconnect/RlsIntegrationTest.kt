package com.vrticconnect

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Native PostgreSQL integration test for tenant isolation. Runs only when VRTIC_TEST_DB=1 and the
 * database from AppConfig (env) is reachable with migrations applied (see README "Integracioni testovi").
 *
 * Proves:
 *  1. without tenant context the runtime role sees and writes nothing;
 *  2. tenant A cannot read or write tenant B rows (USING + WITH CHECK);
 *  3. a pooled connection (pool size 1) carries NO context after COMMIT and after ROLLBACK;
 *  4. FORCE RLS also binds the table owner unless it explicitly enters maintenance mode.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RlsIntegrationTest {

    private val enabled = System.getenv("VRTIC_TEST_DB") == "1"
    private val config = AppConfig.fromEnvironment()

    private lateinit var runtimePool: HikariDataSource
    private lateinit var ownerPool: HikariDataSource
    private lateinit var runtimeDb: Database
    private lateinit var ownerDb: Database

    private val orgA = UUID.randomUUID()
    private val orgB = UUID.randomUUID()
    private val platformUser = UUID.randomUUID()
    private val userA = UUID.randomUUID()
    private val userB = UUID.randomUUID()
    private val runTag = "rls-test-" + UUID.randomUUID().toString().take(8)

    @BeforeAll
    fun setUp() {
        assumeTrue(enabled, "VRTIC_TEST_DB != 1 — integration test skipped")
        runtimePool = pool(config.dbRuntimeUser, config.requireRuntimePassword(), "rls-runtime")
        ownerPool = pool(config.dbOwnerUser, config.requireOwnerPassword(), "rls-owner")
        runtimeDb = Database(runtimePool)
        ownerDb = Database(ownerPool)

        // Organizations are created in platform mode (SUPER_ADMIN path).
        runtimeDb.transactionBlocking(DbContext.Platform(platformUser)) { c ->
            insertOrg(c, orgA, "$runTag-a")
            insertOrg(c, orgB, "$runTag-b")
        }
        runtimeDb.transactionBlocking(DbContext.Tenant(orgA, userA)) { c -> insertLocation(c, orgA, "Loc A") }
        runtimeDb.transactionBlocking(DbContext.Tenant(orgB, userB)) { c -> insertLocation(c, orgB, "Loc B") }
    }

    @AfterAll
    fun tearDown() {
        if (!enabled) return
        // Owner cleans up ONLY in explicit maintenance mode (FORCE RLS applies to the owner too).
        ownerDb.transactionBlocking(DbContext.None) { c ->
            c.createStatement().use { it.execute("SELECT set_config('app.maintenance_mode', 'on', true)") }
            c.prepareStatement("DELETE FROM app.organizations WHERE slug LIKE ?").use { ps ->
                ps.setString(1, "$runTag-%"); ps.executeUpdate()
            }
        }
        runtimePool.close(); ownerPool.close()
    }

    @Test
    fun `no context sees nothing and cannot write`() {
        val count = runtimeDb.transactionBlocking(DbContext.None) { c -> countLocations(c) }
        assertEquals(0, count)
        val ex = assertFailsWith<SQLException> {
            runtimeDb.transactionBlocking(DbContext.None) { c -> insertLocation(c, orgA, "should fail") }
        }
        assertEquals("42501", ex.sqlState, "expected insufficient_privilege (RLS WITH CHECK)")
    }

    @Test
    fun `tenant sees only its own rows`() {
        val a = runtimeDb.transactionBlocking(DbContext.Tenant(orgA, userA)) { c -> locationNames(c) }
        val b = runtimeDb.transactionBlocking(DbContext.Tenant(orgB, userB)) { c -> locationNames(c) }
        assertEquals(listOf("Loc A"), a)
        assertEquals(listOf("Loc B"), b)
    }

    @Test
    fun `tenant cannot write a row for another tenant`() {
        val ex = assertFailsWith<SQLException> {
            runtimeDb.transactionBlocking(DbContext.Tenant(orgA, userA)) { c -> insertLocation(c, orgB, "smuggled") }
        }
        assertEquals("42501", ex.sqlState)
        assertEquals(listOf("Loc B"), runtimeDb.transactionBlocking(DbContext.Tenant(orgB, userB)) { locationNames(it) })
    }

    @Test
    fun `pooled connection carries no context after COMMIT`() {
        // pool size is 1 => the very same physical connection is reused.
        runtimeDb.transactionBlocking(DbContext.Tenant(orgA, userA)) { c -> assertEquals(1, countLocations(c)) }
        val after = runtimeDb.transactionBlocking(DbContext.None) { c -> countLocations(c) }
        assertEquals(0, after, "tenant context leaked across COMMIT on a pooled connection")
        // and raw check of the GUC itself
        val guc = runtimePool.connection.use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT current_setting('app.organization_id', true)").use { rs -> rs.next(); rs.getString(1) } }
        }
        assertTrue(guc.isNullOrEmpty(), "app.organization_id must be empty on a fresh transaction, was '$guc'")
    }

    @Test
    fun `pooled connection carries no context after ROLLBACK`() {
        runCatching {
            runtimeDb.transactionBlocking(DbContext.Tenant(orgA, userA)) { c ->
                assertEquals(1, countLocations(c))
                throw IllegalStateException("force rollback")
            }
        }
        val after = runtimeDb.transactionBlocking(DbContext.None) { c -> countLocations(c) }
        assertEquals(0, after, "tenant context leaked across ROLLBACK on a pooled connection")
    }

    @Test
    fun `owner is bound by FORCE RLS unless in maintenance mode`() {
        val withoutMaintenance = ownerDb.transactionBlocking(DbContext.None) { c -> countLocations(c) }
        assertEquals(0, withoutMaintenance, "table owner must not bypass RLS (FORCE)")
        val withMaintenance = ownerDb.transactionBlocking(DbContext.None) { c ->
            c.createStatement().use { it.execute("SELECT set_config('app.maintenance_mode', 'on', true)") }
            c.prepareStatement("SELECT count(*) FROM app.locations WHERE organization_id IN (?, ?)").use { ps ->
                ps.setObject(1, orgA); ps.setObject(2, orgB)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertEquals(2, withMaintenance)
    }

    // -- helpers ------------------------------------------------------------------------------

    private fun pool(user: String, password: String, name: String) = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.jdbcUrl; username = user; this.password = password
            maximumPoolSize = 1; minimumIdle = 1; poolName = name; isAutoCommit = false
        },
    )

    private fun insertOrg(c: Connection, id: UUID, slug: String) {
        c.prepareStatement("INSERT INTO app.organizations (id, slug, name) VALUES (?, ?, ?)").use { ps ->
            ps.setObject(1, id); ps.setString(2, slug); ps.setString(3, "Org $slug"); ps.executeUpdate()
        }
    }

    private fun insertLocation(c: Connection, org: UUID, name: String) {
        c.prepareStatement("INSERT INTO app.locations (organization_id, name) VALUES (?, ?)").use { ps ->
            ps.setObject(1, org); ps.setString(2, name); ps.executeUpdate()
        }
    }

    private fun countLocations(c: Connection): Int =
        c.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM app.locations").use { rs -> rs.next(); rs.getInt(1) } }

    private fun locationNames(c: Connection): List<String> =
        c.createStatement().use { st ->
            st.executeQuery("SELECT name FROM app.locations ORDER BY name").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }
}
