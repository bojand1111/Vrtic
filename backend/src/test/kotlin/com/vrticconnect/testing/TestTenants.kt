package com.vrticconnect.testing

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.modules.auth.Tokens
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.util.UUID

/**
 * E02-D02: reusable native-PostgreSQL fixture with two tenants (requires VRTIC_TEST_DB=1 and a
 * migrated database). Everything is created through the runtime role and the real RLS contexts;
 * only the cleanup uses the owner role in maintenance mode.
 *
 *  - orgA / orgB                      two independent organizations
 *  - userOwner OWNER in A
 *  - userA   ADMIN in A (+ CHILD_HEALTH_READ), REVOKED membership in B
 *  - userB   TEACHER in B
 *  - userMulti PARENT in A and TEACHER in B (one account, two tenants)
 *  - platformAdmin  SUPER_ADMIN (app.platform_admins), no memberships
 * Each user has one WEB session with a valid access token (`tokenOf`).
 */
class TestTenants(private val config: AppConfig) {
    val runtimePool: HikariDataSource = pool(config.dbRuntimeUser, config.requireRuntimePassword(), "fixture-runtime")
    private val ownerPool: HikariDataSource = pool(config.dbOwnerUser, config.requireOwnerPassword(), "fixture-owner")
    val runtimeDb = Database(runtimePool)
    private val ownerDb = Database(ownerPool)

    private val tag = UUID.randomUUID().toString().take(8)
    val orgA: UUID = UUID.randomUUID()
    val orgB: UUID = UUID.randomUUID()
    val orgAName = "Fixture A $tag"
    val orgBName = "Fixture B $tag"
    val userOwner: UUID = UUID.randomUUID()
    val userA: UUID = UUID.randomUUID()
    val userB: UUID = UUID.randomUUID()
    val userMulti: UUID = UUID.randomUUID()
    val platformAdmin: UUID = UUID.randomUUID()
    val membershipAAdmin: UUID = UUID.randomUUID()
    val membershipBTeacher: UUID = UUID.randomUUID()
    val membershipMultiA: UUID = UUID.randomUUID()
    val membershipMultiB: UUID = UUID.randomUUID()

    private val tokens = mutableMapOf<UUID, String>()
    private val emails = mutableMapOf<UUID, String>()
    fun tokenOf(userId: UUID): String = tokens.getValue(userId)
    fun emailOf(userId: UUID): String = emails.getValue(userId)

    fun create() {
        runtimeDb.transactionBlocking(DbContext.Auth()) { c ->
            for ((id, name) in listOf(userOwner to "Owner", userA to "A", userB to "B", userMulti to "Multi", platformAdmin to "Platform")) {
                val email = "fixture-$name-$tag@example.test".lowercase()
                insertUser(c, id, email, name)
                emails[id] = email
                tokens[id] = insertSessionWithToken(c, id)
            }
            c.prepareStatement("INSERT INTO app.platform_admins (user_id, note) VALUES (?, 'fixture')").use { st ->
                st.setObject(1, platformAdmin); st.executeUpdate()
            }
        }
        runtimeDb.transactionBlocking(DbContext.Platform(platformAdmin)) { c ->
            insertOrg(c, orgA, "fixture-a-$tag", orgAName)
            insertOrg(c, orgB, "fixture-b-$tag", orgBName)
        }
        runtimeDb.transactionBlocking(DbContext.Tenant(orgA, platformAdmin)) { c ->
            c.prepareStatement("INSERT INTO app.organization_settings (organization_id) VALUES (?)").use { st -> st.setObject(1, orgA); st.executeUpdate() }
            insertMembership(c, UUID.randomUUID(), orgA, userOwner, "OWNER", "ACTIVE")
            insertMembership(c, membershipAAdmin, orgA, userA, "ADMIN", "ACTIVE")
            insertMembership(c, membershipMultiA, orgA, userMulti, "PARENT", "ACTIVE")
            c.prepareStatement(
                "INSERT INTO app.membership_permissions (organization_id, membership_id, permission) VALUES (?, ?, 'CHILD_HEALTH_READ')",
            ).use { st -> st.setObject(1, orgA); st.setObject(2, membershipAAdmin); st.executeUpdate() }
        }
        runtimeDb.transactionBlocking(DbContext.Tenant(orgB, platformAdmin)) { c ->
            c.prepareStatement("INSERT INTO app.organization_settings (organization_id) VALUES (?)").use { st -> st.setObject(1, orgB); st.executeUpdate() }
            insertMembership(c, UUID.randomUUID(), orgB, userA, "TEACHER", "REVOKED")
            insertMembership(c, membershipBTeacher, orgB, userB, "TEACHER", "ACTIVE")
            insertMembership(c, membershipMultiB, orgB, userMulti, "TEACHER", "ACTIVE")
        }
    }

    fun destroy() {
        ownerDb.transactionBlocking(DbContext.None) { c ->
            c.createStatement().use { it.execute("SELECT set_config('app.maintenance_mode', 'on', true)") }
            c.prepareStatement("DELETE FROM app.organizations WHERE id IN (?, ?)").use { st ->
                st.setObject(1, orgA); st.setObject(2, orgB); st.executeUpdate()
            }
            c.prepareStatement("DELETE FROM app.users WHERE id IN (?, ?, ?, ?, ?)").use { st ->
                st.setObject(1, userA); st.setObject(2, userB); st.setObject(3, userMulti); st.setObject(4, platformAdmin); st.setObject(5, userOwner)
                st.executeUpdate()
            }
        }
        runtimePool.close()
        ownerPool.close()
    }

    private fun insertUser(c: Connection, id: UUID, email: String, name: String) {
        c.prepareStatement(
            "INSERT INTO app.users (id, email, email_verified_at, given_name, family_name) VALUES (?, ?, now(), ?, 'Fixture')",
        ).use { st -> st.setObject(1, id); st.setString(2, email); st.setString(3, name); st.executeUpdate() }
    }

    private fun insertSessionWithToken(c: Connection, userId: UUID): String {
        val sessionId = UUID.randomUUID()
        val token = Tokens.generate(Tokens.Kind.ACCESS)
        c.prepareStatement(
            "INSERT INTO app.sessions (id, user_id, client_kind, absolute_expires_at) VALUES (?, ?, 'WEB', now() + interval '1 day')",
        ).use { st -> st.setObject(1, sessionId); st.setObject(2, userId); st.executeUpdate() }
        c.prepareStatement(
            "INSERT INTO app.access_tokens (session_id, token_hash, expires_at) VALUES (?, ?, now() + interval '10 minutes')",
        ).use { st -> st.setObject(1, sessionId); st.setBytes(2, Tokens.sha256(token)); st.executeUpdate() }
        return token
    }

    private fun insertOrg(c: Connection, id: UUID, slug: String, name: String) {
        c.prepareStatement("INSERT INTO app.organizations (id, slug, name) VALUES (?, ?, ?)").use { st ->
            st.setObject(1, id); st.setString(2, slug); st.setString(3, name); st.executeUpdate()
        }
    }

    private fun insertMembership(c: Connection, id: UUID, org: UUID, user: UUID, role: String, status: String) {
        c.prepareStatement(
            "INSERT INTO app.organization_memberships (id, organization_id, user_id, role, status, accepted_at, revoked_at) " +
                "VALUES (?, ?, ?, ?, ?, now(), CASE WHEN ? = 'REVOKED' THEN now() END)",
        ).use { st ->
            st.setObject(1, id); st.setObject(2, org); st.setObject(3, user); st.setString(4, role)
            st.setString(5, status); st.setString(6, status); st.executeUpdate()
        }
    }

    private fun pool(user: String, password: String, name: String) = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.jdbcUrl; username = user; this.password = password
            maximumPoolSize = 2; minimumIdle = 1; poolName = name; isAutoCommit = false
        },
    )
}
