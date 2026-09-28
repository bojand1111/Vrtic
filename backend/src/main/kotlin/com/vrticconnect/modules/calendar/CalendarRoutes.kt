package com.vrticconnect.modules.calendar

import com.vrticconnect.http.PageRequest
import com.vrticconnect.http.pathUuid
import com.vrticconnect.http.queryDate
import com.vrticconnect.http.queryUuid
import com.vrticconnect.modules.announcements.Versioning
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
 * Mounted under /api/v1/organizations/{organizationId}. docs/openapi.yaml "Calendar".
 * `from`/`to` default to the current month (organization timezone) when omitted.
 */
fun Route.calendarRoutes(api: TenantApi) {
    val service = CalendarService(api)

    route("/calendar-events") {
        get {
            val principal = tenantRead(api)
            val q = call.request.queryParameters
            val page = PageRequest.from(q)
            call.respond(
                service.list(
                    principal, call.queryDate("from"), call.queryDate("to"), call.queryUuid("locationId"), call.queryUuid("groupId"),
                    q["kind"], q["sort"] ?: "startsOn:asc", page.limit,
                ),
            )
        }
        post {
            val principal = tenantWrite(api)
            val dto = service.create(principal, call.receive<CalendarEventInput>(), requestId)
            Versioning.etag(call, dto.version)
            call.respond(HttpStatusCode.Created, dto)
        }
        route("/{eventId}") {
            get {
                val principal = tenantRead(api)
                val dto = service.get(principal, call.pathUuid("eventId"))
                Versioning.etag(call, dto.version)
                call.respond(dto)
            }
            patch {
                val principal = tenantWrite(api)
                val id = call.pathUuid("eventId")
                val version = Versioning.ifMatch(call)
                val body = Json.parseToJsonElement(call.receiveText()) as? JsonObject ?: JsonObject(emptyMap())
                val dto = service.update(principal, id, version, body, requestId)
                Versioning.etag(call, dto.version)
                call.respond(dto)
            }
            delete {
                val principal = tenantWrite(api)
                val id = call.pathUuid("eventId")
                service.delete(principal, id, Versioning.ifMatch(call), requestId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
