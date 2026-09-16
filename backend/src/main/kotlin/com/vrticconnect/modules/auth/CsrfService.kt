package com.vrticconnect.modules.auth

import com.vrticconnect.config.AppConfig
import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.http.ProblemException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import java.security.MessageDigest

/** Validates the web double-submit token and exact Origin before cookie-backed mutations. */
class CsrfService(
    private val database: Database,
    private val config: AppConfig,
) {
    suspend fun require(call: ApplicationCall, authenticated: AuthenticatedUser) {
        // Bearer clients are not exposed to browser cookie CSRF and do not need this check.
        if (call.bearerToken() != null || call.request.cookies[ACCESS_COOKIE] == null) return

        val origin = call.request.headers[HttpHeaders.Origin]
        val cookieToken = call.request.cookies[CSRF_COOKIE]
        val headerToken = call.request.headers[CSRF_HEADER]
        if (origin != config.webOrigin || cookieToken == null || cookieToken != headerToken || !Tokens.isCsrfToken(cookieToken)) {
            throw ProblemException.csrfInvalid()
        }

        val expectedHash = database.transaction(DbContext.Auth(authenticated.userId)) { connection ->
            connection.prepareStatement(
                "SELECT csrf_token_hash FROM app.sessions WHERE id = ? AND user_id = ? AND client_kind = 'WEB' AND revoked_at IS NULL",
            ).use { statement ->
                statement.setObject(1, authenticated.sessionId)
                statement.setObject(2, authenticated.userId)
                statement.executeQuery().use { result ->
                    if (!result.next()) null else result.getBytes("csrf_token_hash")
                }
            }
        }
        if (expectedHash == null || !MessageDigest.isEqual(expectedHash, Tokens.sha256(cookieToken))) {
            throw ProblemException.csrfInvalid()
        }
    }

    suspend fun requireRefresh(call: ApplicationCall, refreshToken: String) {
        // A mobile refresh token is in the request body and is not ambient browser state.
        if (call.request.cookies[REFRESH_COOKIE] == null) return
        val cookieToken = call.request.cookies[CSRF_COOKIE]
        val headerToken = call.request.headers[CSRF_HEADER]
        if (call.request.headers[HttpHeaders.Origin] != config.webOrigin ||
            cookieToken == null || cookieToken != headerToken || !Tokens.isCsrfToken(cookieToken)
        ) {
            throw ProblemException.csrfInvalid()
        }

        val expectedHash = database.transaction(DbContext.Auth()) { connection ->
            connection.prepareStatement(
                "SELECT s.csrf_token_hash FROM app.refresh_tokens rt " +
                    "JOIN app.sessions s ON s.id = rt.session_id " +
                    "WHERE rt.token_hash = ? AND s.client_kind = 'WEB'",
            ).use { statement ->
                statement.setBytes(1, Tokens.sha256(refreshToken))
                statement.executeQuery().use { result ->
                    if (!result.next()) null else result.getBytes("csrf_token_hash")
                }
            }
        }
        if (expectedHash == null || !MessageDigest.isEqual(expectedHash, Tokens.sha256(cookieToken))) {
            throw ProblemException.csrfInvalid()
        }
    }

    private companion object {
        const val ACCESS_COOKIE = "vc_access"
        const val REFRESH_COOKIE = "vc_refresh"
        const val CSRF_COOKIE = "vc_csrf"
        const val CSRF_HEADER = "X-CSRF-Token"
    }
}
