package com.vrticconnect.modules.auth

import com.vrticconnect.http.ProblemException
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Authentication endpoints are DESIGNED (docs/API.md, docs/openapi.yaml) but NOT implemented.
 * Every route is deliberately closed with 501 + problem+json. No tokens are ever issued here.
 * Implementation is EPIC 02 (docs/DEVELOPMENT_ROADMAP.md). Do NOT add development shortcuts.
 */
fun Route.authRoutes(sessionResolver: SessionResolver) {
    route("/auth") {
        // Current session (web bootstrap). 401 until EPIC 02 implements sessions.
        get("/session") {
            requireUser(sessionResolver)
            throw ProblemException.notImplemented("Session info")
        }
        post("/register") { throw ProblemException.notImplemented("Registration") }
        post("/login") { throw ProblemException.notImplemented("Login") }
        post("/refresh") { throw ProblemException.notImplemented("Token refresh") }
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
