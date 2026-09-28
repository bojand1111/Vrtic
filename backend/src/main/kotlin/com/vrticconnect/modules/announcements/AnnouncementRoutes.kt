package com.vrticconnect.modules.announcements

import com.vrticconnect.http.PageRequest
import com.vrticconnect.http.pathUuid
import com.vrticconnect.http.queryUuid
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.requestId
import com.vrticconnect.modules.tenant.tenantRead
import com.vrticconnect.modules.tenant.tenantWrite
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Mounted under /api/v1/organizations/{organizationId}. docs/openapi.yaml "Announcements".
 * Idempotency-Key is accepted but not stored; attachments are not supported yet (files module missing).
 */
fun Route.announcementRoutes(api: TenantApi) {
    val service = AnnouncementService(api)

    route("/announcements") {
        get {
            val principal = tenantRead(api)
            val q = call.request.queryParameters
            val page = PageRequest.from(q)
            call.respond(
                service.list(
                    principal, q["status"], q["unreadOnly"] == "true", call.queryUuid("groupId"), q["sort"] ?: "publishedAt:desc", page.limit,
                ),
            )
        }
        post {
            val principal = tenantWrite(api)
            val created = service.create(principal, call.receive<AnnouncementCreate>(), requestId)
            Versioning.etag(call, created.version)
            call.respond(HttpStatusCode.Created, created)
        }
        route("/{announcementId}") {
            get {
                val principal = tenantRead(api)
                val dto = service.get(principal, call.pathUuid("announcementId"))
                Versioning.etag(call, dto.version)
                call.respond(dto)
            }
            patch {
                val principal = tenantWrite(api)
                val id = call.pathUuid("announcementId")
                val version = Versioning.ifMatch(call)
                val body = Json.parseToJsonElement(call.receiveText()) as? JsonObject ?: JsonObject(emptyMap())
                val dto = service.update(principal, id, version, body, requestId)
                Versioning.etag(call, dto.version)
                call.respond(dto)
            }
            delete {
                val principal = tenantWrite(api)
                val id = call.pathUuid("announcementId")
                service.delete(principal, id, Versioning.ifMatch(call), requestId)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/publish") {
                val principal = tenantWrite(api)
                val id = call.pathUuid("announcementId")
                val version = Versioning.ifMatch(call)
                val text = call.receiveText()
                val sendPush = if (text.isBlank()) true else Json.decodeFromString(AnnouncementPublishRequest.serializer(), text).sendPush ?: true
                val dto = service.publish(principal, id, version, sendPush, requestId)
                Versioning.etag(call, dto.version)
                call.respond(dto)
            }
            post("/archive") {
                val principal = tenantWrite(api)
                val id = call.pathUuid("announcementId")
                val dto = service.archive(principal, id, Versioning.ifMatch(call), requestId)
                Versioning.etag(call, dto.version)
                call.respond(dto)
            }
            get("/recipients") {
                val principal = tenantRead(api)
                val q = call.request.queryParameters
                val page = PageRequest.from(q)
                call.respond(service.recipients(principal, call.pathUuid("announcementId"), q["readState"], q["sort"] ?: "familyName:asc", page.limit))
            }
            post("/read") {
                val principal = tenantWrite(api)
                call.respond(service.markRead(principal, call.pathUuid("announcementId"), requestId))
            }
        }
    }
}
