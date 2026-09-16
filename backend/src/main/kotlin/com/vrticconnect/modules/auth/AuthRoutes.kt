package com.vrticconnect.modules.auth

import com.vrticconnect.http.ProblemException
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Authentication endpoints are implemented incrementally. Login, refresh and session bootstrap
 * are available when the real database dependencies are wired; remaining endpoints stay closed.
 */
fun Route.authRoutes(
    sessionResolver: SessionResolver,
    loginService: LoginService? = null,
    sessionService: SessionService? = null,
) {
    route("/auth") {
        // DB-backed deployments return the minimal SPA bootstrap payload; DB-free route tests keep the stub.
        get("/session") {
            sessionService?.current(call, sessionResolver) ?: run {
                requireUser(sessionResolver)
                throw ProblemException.notImplemented("Session info")
            }
        }
        post("/register") { throw ProblemException.notImplemented("Registration") }
        post("/login") { loginService?.login(call) ?: throw ProblemException.notImplemented("Login") }
        post("/refresh") { loginService?.refresh(call) ?: throw ProblemException.notImplemented("Token refresh") }
        post("/logout") { throw ProblemException.notImplemented("Logout") }
        post("/logout-all") { throw ProblemException.notImplemented("Logout from all devices") }
        get("/sessions") { throw ProblemException.notImplemented("Session list") }
        delete("/sessions/{sessionId}") { throw ProblemException.notImplemented("Session revocation") }
        post("/verify-email") { throw ProblemException.notImplemented("Email verification") }
        post("/resend-verification") { throw ProblemException.notImplemented("Resend verification") }
        post("/forgot-password") { throw ProblemException.notImplemented("Password reset request") }
        post("/reset-password") { throw ProblemException.notImplemented("Password reset") }
        post("/reauthenticate") { throw ProblemException.notImplemented("Reauthentication") }
        route("/mfa") {
            post("/totp/setup") { throw ProblemException.notImplemented("MFA setup") }
            post("/totp/confirm") { throw ProblemException.notImplemented("MFA confirmation") }
            post("/totp/verify") { throw ProblemException.notImplemented("MFA verification") }
            post("/recovery-codes/regenerate") { throw ProblemException.notImplemented("MFA recovery codes") }
        }
    }
}
