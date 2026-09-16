package com.vrticconnect.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Transaction-local database context. Mapped 1:1 onto the PostgreSQL GUCs read by RLS policies:
 *   app.organization_id, app.user_id, app.auth_mode, app.platform_mode.
 *
 * Every transaction sets ALL four values explicitly (empty string = unset) via set_config(name, value, true),
 * which PostgreSQL discards at COMMIT/ROLLBACK. A pooled connection therefore never carries a previous
 * request's tenant — see RlsIntegrationTest for the proof.
 */
sealed interface DbContext {
    /** No identity at all: RLS denies every tenant row. Used by readiness checks. */
    data object None : DbContext

    /** Authentication module only (credential/token lookup). Grants access to auth tables. */
    data class Auth(val userId: UUID? = null) : DbContext

    /** Authenticated user without a tenant selected (tenant switcher, own sessions, own notifications). */
    data class User(val userId: UUID) : DbContext

    /** Authenticated user acting inside ONE organization after membership verification. */
    data class Tenant(val organizationId: UUID, val userId: UUID) : DbContext

    /** Verified platform administrator (SUPER_ADMIN) after MFA. */
    data class Platform(val userId: UUID) : DbContext
}

class Database(private val dataSource: DataSource) {

    /**
     * Runs [block] inside a single transaction with [context] applied. Commits on success,
     * rolls back on any exception. Blocking JDBC work is confined to Dispatchers.IO.
     */
    suspend fun <T> transaction(context: DbContext, block: (Connection) -> T): T =
        withContext(Dispatchers.IO) { transactionBlocking(context, block) }

    fun <T> transactionBlocking(context: DbContext, block: (Connection) -> T): T {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                applyContext(conn, context)
                val result = block(conn)
                conn.commit()
                return result
            } catch (t: Throwable) {
                runCatching { conn.rollback() }
                throw t
            }
        }
    }

    companion object {
        private const val APPLY_CONTEXT_SQL =
            "SELECT set_config('app.organization_id', ?, true), set_config('app.user_id', ?, true), " +
                "set_config('app.auth_mode', ?, true), set_config('app.platform_mode', ?, true)"

        fun applyContext(conn: Connection, context: DbContext) {
            val (org, user, auth, platform) = when (context) {
                DbContext.None -> Quad("", "", "", "")
                is DbContext.Auth -> Quad("", context.userId?.toString() ?: "", "on", "")
                is DbContext.User -> Quad("", context.userId.toString(), "", "")
                is DbContext.Tenant -> Quad(context.organizationId.toString(), context.userId.toString(), "", "")
                is DbContext.Platform -> Quad("", context.userId.toString(), "", "on")
            }
            conn.prepareStatement(APPLY_CONTEXT_SQL).use { ps ->
                ps.setString(1, org)
                ps.setString(2, user)
                ps.setString(3, auth)
                ps.setString(4, platform)
                ps.executeQuery().close()
            }
        }

        private data class Quad(val a: String, val b: String, val c: String, val d: String)
    }
}
