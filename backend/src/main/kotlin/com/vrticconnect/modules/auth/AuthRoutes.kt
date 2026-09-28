package com.vrticconnect.modules.auth

import com.vrticconnect.http.PageRequest
import com.vrticconnect.http.ProblemException
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

/**
 * Authentication endpoints are implemented incrementally. Login, refresh, session bootstrap and
 * session management (E02-B08) are available when the real database dependencies are wired;
 * remaining endpoints stay closed with 501. No development shortcuts.
 */
fun Route.authRoutes(
    sessionResolver: SessionResolver,
    loginService: LoginService? = null,
    sessionService: SessionService? = null,
    csrfService: CsrfService? = null,
    sessionManagement: SessionManagementService? = null,
    devMode: Boolean = false,
    accountService: AccountService? = null,
    reauthService: ReauthService? = null,
    mfaService: MfaService? = null,
) {
    route("/auth") {
        // DB-backed deployments return the minimal SPA bootstrap payload; DB-free route tests keep the stub.
        get("/session") {
            sessionService?.current(call, sessionResolver) ?: run {
                requireUser(sessionResolver)
                throw ProblemException.notImplemented("Session info")
            }
        }
        // E02-B04: invitation preview + registration through an invitation link (no self-registration).
        get("/invitations/{invitationToken}") {
            (accountService ?: throw ProblemException.notImplemented("Invitation preview")).previewInvitation(call)
        }
        post("/register") { (accountService ?: throw ProblemException.notImplemented("Registration")).register(call) }
        post("/login") { loginService?.login(call) ?: throw ProblemException.notImplemented("Login") }
        post("/refresh") { loginService?.refresh(call) ?: throw ProblemException.notImplemented("Token refresh") }
        post("/logout") {
            if (loginService == null) {
                throw ProblemException.notImplemented("Logout")
            }
            val authenticated = requireUser(sessionResolver)
            csrfService?.require(call, authenticated)
            loginService.logout(call, authenticated)
        }
        // E02-B08: every session of the user; requires recent authentication (403 REAUTHENTICATION_REQUIRED otherwise).
        post("/logout-all") {
            val authenticated = requireUser(sessionResolver)
            val service = sessionManagement ?: throw ProblemException.notImplemented("Logout from all devices")
            csrfService?.require(call, authenticated)
            service.logoutAll(authenticated)
            AuthCookies.clearAll(call, devMode)
            call.respond(HttpStatusCode.NoContent)
        }
        get("/sessions") {
            val authenticated = requireUser(sessionResolver)
            val service = sessionManagement ?: throw ProblemException.notImplemented("Session list")
            call.respond(service.list(authenticated, PageRequest.from(call.request.queryParameters)))
        }
        delete("/sessions/{sessionId}") {
            val authenticated = requireUser(sessionResolver)
            val service = sessionManagement ?: throw ProblemException.notImplemented("Session revocation")
            csrfService?.require(call, authenticated)
            val sessionId = call.parameters["sessionId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw ProblemException.notFound()
            val revokedCurrent = service.revoke(authenticated, sessionId)
            if (revokedCurrent && AuthCookies.isCookieRequest(call)) AuthCookies.clearAll(call, devMode)
            call.respond(HttpStatusCode.NoContent)
        }
        // E02-B05
        post("/verify-email") { (accountService ?: throw ProblemException.notImplemented("Email verification")).verifyEmail(call) }
        post("/resend-verification") { (accountService ?: throw ProblemException.notImplemented("Resend verification")).resendVerification(call) }
        // E02-B09
        post("/forgot-password") { (accountService ?: throw ProblemException.notImplemented("Password reset request")).forgotPassword(call) }
        post("/reset-password") { (accountService ?: throw ProblemException.notImplemented("Password reset")).resetPassword(call) }
        // E02-B10: stamps sessions.reauthenticated_at for x-requires-reauthentication operations.
        post("/reauthenticate") {
            val authenticated = requireUser(sessionResolver)
            val service = reauthService ?: throw ProblemException.notImplemented("Reauthentication")
            csrfService?.require(call, authenticated)
            service.reauthenticate(call, authenticated)
        }
        // E02-B11: TOTP enrollment and per-session verification (MfaService, MfaGate).
        route("/mfa") {
            post("/totp/setup") {
                val authenticated = requireUser(sessionResolver)
                val service = mfaService ?: throw ProblemException.notImplemented("MFA setup")
                csrfService?.require(call, authenticated)
                call.respond(service.setup(authenticated, call.callId))
            }
            post("/totp/confirm") {
                val authenticated = requireUser(sessionResolver)
                val service = mfaService ?: throw ProblemException.notImplemented("MFA confirmation")
                csrfService?.require(call, authenticated)
                call.respond(service.confirm(authenticated, call.receive<TotpCodeRequest>(), call.callId))
            }
            post("/totp/verify") {
                val authenticated = requireUser(sessionResolver)
                val service = mfaService ?: throw ProblemException.notImplemented("MFA verification")
                csrfService?.require(call, authenticated)
                call.respond(service.verify(authenticated, call.receive<TotpVerifyRequest>(), call.callId))
            }
            post("/recovery-codes/regenerate") {
                val authenticated = requireUser(sessionResolver)
                val service = mfaService ?: throw ProblemException.notImplemented("MFA recovery codes")
                csrfService?.require(call, authenticated)
                call.respond(service.regenerateRecoveryCodes(authenticated, call.callId))
            }
        }
    }
}
