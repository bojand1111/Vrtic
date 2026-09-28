package com.vrticconnect.modules.notifications

import com.vrticconnect.db.Database
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.pathUuid
import com.vrticconnect.modules.auth.CsrfService
import com.vrticconnect.modules.auth.SessionResolver
import com.vrticconnect.modules.auth.requireUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Mounted under /api/v1 (no tenant): the caller's notification inbox (`/me/notifications`) and device push
 * tokens (`/auth/push-tokens`). Session required (401); mutations pass the cookie CSRF/Origin check.
 */
fun Route.notificationRoutes(sessionResolver: SessionResolver, database: Database?, csrfService: CsrfService?) {
    val service = database?.let(::NotificationService)
    fun svc(): NotificationService = service ?: throw ProblemException.notImplemented("Notifications")

    route("/me/notifications") {
        get {
            val user = requireUser(sessionResolver)
            call.respond(svc().list(user, call.request.queryParameters))
        }
        post("/mark-read") {
            val user = requireUser(sessionResolver)
            csrfService?.require(call, user)
            call.respond(svc().markRead(user, call.receive<NotificationMarkReadRequest>()))
        }
    }
    route("/auth/push-tokens") {
        post {
            val user = requireUser(sessionResolver)
            csrfService?.require(call, user)
            val (created, token) = svc().registerPushToken(user, call.receive<PushTokenRegistration>())
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, token)
        }
        delete("/{pushTokenId}") {
            val user = requireUser(sessionResolver)
            csrfService?.require(call, user)
            svc().deletePushToken(user, call.pathUuid("pushTokenId"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
