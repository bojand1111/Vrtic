package com.vrticconnect.testing

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.db.update
import com.vrticconnect.modules.auth.Tokens
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.util.UUID

/**
 * Additional users (with a WEB session and bearer token) on top of [TestTenants], for tests that need more
 * members than the fixture provides. Created through the runtime role; [destroy] removes them with the owner
 * role in maintenance mode and must run AFTER [TestTenants.destroy] (memberships go with the organizations).
 */
class ExtraUsers(private val config: AppConfig, private val tenants: TestTenants) {
    private val ownerPool = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.jdbcUrl; username = config.dbOwnerUser; password = config.requireOwnerPassword()
            maximumPoolSize = 1; minimumIdle = 0; poolName = "extra-users-owner"; isAutoCommit = false
        },
    )
    private val tag = UUID.randomUUID().toString().take(8)
    private val users = mutableListOf<UUID>()
    private val tokens = mutableMapOf<UUID, String>()

    fun tokenOf(user: UUID): String = tokens.getValue(user)

    /** New user with a session; returns the user id. */
    fun user(name: String): UUID {
        val id = UUID.randomUUID()
        val token = Tokens.generate(Tokens.Kind.ACCESS)
        tenants.runtimeDb.transactionBlocking(DbContext.Auth()) { c ->
            c.update(
                "INSERT INTO app.users (id, email, email_verified_at, given_name, family_name) VALUES (?, ?, now(), ?, 'Extra')",
                id, "extra-$name-$tag@example.test".lowercase(), name,
            )
            val session = UUID.randomUUID()
            c.update("INSERT INTO app.sessions (id, user_id, client_kind, absolute_expires_at) VALUES (?, ?, 'WEB', now() + interval '1 day')", session, id)
            c.prepareStatement("INSERT INTO app.access_tokens (session_id, token_hash, expires_at) VALUES (?, ?, now() + interval '10 minutes')").use { st ->
                st.setObject(1, session); st.setBytes(2, Tokens.sha256(token)); st.executeUpdate()
            }
        }
        users += id
        tokens[id] = token
        return id
    }

    /** ACTIVE membership of [user] in [org]; returns the membership id. */
    fun membership(org: UUID, user: UUID, role: String): UUID = UUID.randomUUID().also { id ->
        tenants.runtimeDb.transactionBlocking(DbContext.Tenant(org, tenants.platformAdmin)) { c ->
            c.update(
                "INSERT INTO app.organization_memberships (id, organization_id, user_id, role, status, accepted_at) VALUES (?, ?, ?, ?, 'ACTIVE', now())",
                id, org, user, role,
            )
        }
    }

    /**
     * messages.sender_membership_id -> organization_memberships has no cascade, so the organization cascade trips over
     * it; conversations (cascading to participants and messages) must go first. Call BEFORE [TestTenants.destroy].
     */
    fun deleteConversations(vararg orgs: UUID) {
        Database(ownerPool).transactionBlocking(DbContext.None) { c ->
            c.createStatement().use { it.execute("SELECT set_config('app.maintenance_mode', 'on', true)") }
            orgs.forEach { c.update("DELETE FROM app.conversations WHERE organization_id = ?", it) }
        }
    }

    fun destroy() {
        Database(ownerPool).transactionBlocking(DbContext.None) { c ->
            c.createStatement().use { it.execute("SELECT set_config('app.maintenance_mode', 'on', true)") }
            users.forEach { c.update("DELETE FROM app.users WHERE id = ?", it) }
        }
        ownerPool.close()
    }
}
