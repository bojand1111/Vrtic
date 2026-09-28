package com.vrticconnect.modules.messaging

import com.vrticconnect.http.pathUuid
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.requestId
import com.vrticconnect.modules.tenant.tenantRead
import com.vrticconnect.modules.tenant.tenantWrite
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Mounted under /api/v1/organizations/{organizationId}. docs/openapi.yaml tag Messaging.
 * The `messaging` feature flag is not checked (no feature-flag infrastructure yet).
 */
fun Route.messagingRoutes(api: TenantApi) {
    route("/conversations") {
        get {
            val principal = tenantRead(api)
            call.respond(api.tx(principal) { c -> MessagingService.list(c, principal, call.request.queryParameters) })
        }
        post {
            val principal = tenantWrite(api)
            val body = call.receive<ConversationCreateRequest>()
            val (created, conversation) = api.tx(principal) { c -> MessagingService.create(c, principal, body, requestId) }
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, conversation)
        }
        get("/{conversationId}") {
            val principal = tenantRead(api)
            val id = call.pathUuid("conversationId")
            call.respond(api.tx(principal) { c -> MessagingService.get(c, principal, id) })
        }
        get("/{conversationId}/messages") {
            val principal = tenantRead(api)
            val id = call.pathUuid("conversationId")
            call.respond(api.tx(principal) { c -> MessagingService.messages(c, principal, id, call.request.queryParameters) })
        }
        post("/{conversationId}/messages") {
            val principal = tenantWrite(api)
            val id = call.pathUuid("conversationId")
            val body = call.receive<MessageCreateRequest>()
            val (created, message) = api.tx(principal) { c -> MessagingService.send(c, principal, id, body, requestId) }
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, message)
        }
        put("/{conversationId}/read-position") {
            val principal = tenantWrite(api)
            val id = call.pathUuid("conversationId")
            val body = call.receive<ReadPositionUpdate>()
            call.respond(api.tx(principal) { c -> MessagingService.updateReadPosition(c, principal, id, body) })
        }
    }
}
