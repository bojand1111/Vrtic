package com.vrticconnect.modules.locations

import com.vrticconnect.http.PageRequest
import com.vrticconnect.http.invalidQuery
import com.vrticconnect.http.pathUuid
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.requestId
import com.vrticconnect.modules.tenant.tenantRead
import com.vrticconnect.modules.tenant.tenantWrite
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Mounted under /api/v1/organizations/{organizationId}. */
fun Route.locationRoutes(api: TenantApi) {
    val service = LocationService(api)
    route("/locations") {
        get {
            val p = tenantRead(api)
            val status = call.statusQuery()
            call.respond(service.list(p, status, PageRequest.from(call.request.queryParameters).limit))
        }
        post {
            val p = tenantWrite(api)
            call.respond(HttpStatusCode.Created, service.create(p, call.receive<LocationCreate>(), requestId))
        }
        get("/{locationId}") {
            val p = tenantRead(api)
            call.respond(service.get(p, call.pathUuid("locationId")))
        }
        patch("/{locationId}") {
            val p = tenantWrite(api)
            val id = call.pathUuid("locationId")
            call.respond(service.update(p, id, PatchBody.receive(call), requestId))
        }
        delete("/{locationId}") {
            val p = tenantWrite(api)
            service.delete(p, call.pathUuid("locationId"), requestId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** Optional `status` query (ACTIVE|INACTIVE); anything else answers 422. */
fun ApplicationCall.statusQuery(): String? {
    val raw = request.queryParameters["status"] ?: return null
    if (raw !in setOf("ACTIVE", "INACTIVE")) throw invalidQuery("status", "ACTIVE|INACTIVE")
    return raw
}
