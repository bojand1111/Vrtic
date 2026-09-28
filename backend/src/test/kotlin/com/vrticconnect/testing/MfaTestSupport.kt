package com.vrticconnect.testing

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.modules.auth.Argon2idPasswordHasher
import com.vrticconnect.modules.auth.Base32
import com.vrticconnect.modules.auth.Totp
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/** Helpers for MFA / platform integration tests: password users, bearer login, TOTP codes, owner-role cleanup. */
object MfaTestSupport {
    fun insertPasswordUser(c: Connection, config: AppConfig, id: UUID, email: String, password: String, name: String) {
        val chars = password.toCharArray()
        try {
            c.prepareStatement("INSERT INTO app.users (id, email, email_verified_at, password_hash, given_name, family_name) VALUES (?, ?, now(), ?, ?, 'Mfa')").use { st ->
                st.setObject(1, id); st.setString(2, email); st.setString(3, Argon2idPasswordHasher(config.argon2).hash(chars)); st.setString(4, name); st.executeUpdate()
            }
        } finally {
            chars.fill('\u0000')
        }
    }

    suspend fun ApplicationTestBuilder.login(email: String, password: String): HttpResponse =
        client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"$password","device":{"clientKind":"ANDROID","deviceName":"Test"}}""")
        }

    fun accessToken(loginBody: String): String =
        Regex("\"accessToken\":\"([^\"]+)\"").find(loginBody)?.groupValues?.get(1) ?: error("no access token in $loginBody")

    suspend fun ApplicationTestBuilder.postJson(path: String, token: String, body: String = "{}"): HttpResponse =
        client.post(path) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(body)
        }

    fun secretOf(setupBody: String): ByteArray =
        Base32.decode(Regex("\"secretBase32\":\"([A-Z2-7]+)\"").find(setupBody)?.groupValues?.get(1) ?: error("no secret in $setupBody"))

    /** Code for the current step plus [offset] steps (the server accepts -1..+1 and never the same step twice). */
    fun code(secret: ByteArray, offset: Long = 0): String = Totp.code(secret, Totp.step(Instant.now()) + offset)

    /** A 6-digit code that matches none of the currently accepted steps. */
    fun wrongCode(secret: ByteArray): String {
        val now = Totp.step(Instant.now())
        val valid = (now - 2..now + 2).map { Totp.code(secret, it) }.toSet()
        return (0..999_999).asSequence().map { it.toString().padStart(6, '0') }.first { it !in valid }
    }

    fun recoveryCodes(body: String): List<String> =
        Regex("\"([A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4})\"").findAll(body).map { it.groupValues[1] }.toList()

    /** Owner-role pool for teardown in maintenance mode (append-only tables need their trigger disabled). */
    fun ownerDb(config: AppConfig): Pair<Database, HikariDataSource> {
        val pool = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl; username = config.dbOwnerUser; password = config.requireOwnerPassword(); maximumPoolSize = 1; isAutoCommit = false
            },
        )
        return Database(pool) to pool
    }

    /**
     * Removes subscriptions (+ append-only events) of [organizations], then deletes [deleteOrganizations]
     * (created by the test), [users] and [plans]. Fixture organizations are deleted later by TestTenants.
     */
    fun cleanup(config: AppConfig, organizations: List<UUID>, deleteOrganizations: List<UUID>, users: List<UUID>, plans: List<UUID>) {
        val (db, pool) = ownerDb(config)
        try {
            db.transactionBlocking(DbContext.None) { c ->
                c.createStatement().use { it.execute("SELECT set_config('app.maintenance_mode', 'on', true)") }
                if (organizations.isNotEmpty()) {
                    c.createStatement().use { it.execute("ALTER TABLE app.subscription_events DISABLE TRIGGER subscription_events_append_only") }
                    for (org in organizations) {
                        c.prepareStatement("DELETE FROM app.subscription_events WHERE organization_id = ?").use { st -> st.setObject(1, org); st.executeUpdate() }
                        c.prepareStatement("DELETE FROM app.subscriptions WHERE organization_id = ?").use { st -> st.setObject(1, org); st.executeUpdate() }
                    }
                    c.createStatement().use { it.execute("SET CONSTRAINTS ALL IMMEDIATE") }
                    c.createStatement().use { it.execute("ALTER TABLE app.subscription_events ENABLE TRIGGER subscription_events_append_only") }
                }
                for (org in deleteOrganizations) c.prepareStatement("DELETE FROM app.organizations WHERE id = ?").use { st -> st.setObject(1, org); st.executeUpdate() }
                for (user in users) c.prepareStatement("DELETE FROM app.users WHERE id = ?").use { st -> st.setObject(1, user); st.executeUpdate() }
                for (plan in plans) c.prepareStatement("DELETE FROM app.plans WHERE id = ?").use { st -> st.setObject(1, plan); st.executeUpdate() }
            }
        } finally {
            pool.close()
        }
    }
}
