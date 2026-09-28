package com.vrticconnect.modules.absences

import com.vrticconnect.http.PageRequest
import com.vrticconnect.http.pathUuid
import com.vrticconnect.modules.children.ApiSupport
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
import io.ktor.server.routing.route

/**
 * Mounted under /api/v1/organizations/{organizationId}. docs/openapi.yaml tag Absences.
 * `If-Match` on cancel is checked when sent but not required (documented simplification).
 */
fun Route.absenceRoutes(api: TenantApi) {
    route("/absences") {
        get {
            val principal = tenantRead(api)
            val page = PageRequest.from(call.request.queryParameters)
            call.respond(api.tx(principal) { c -> AbsenceService.list(c, principal, call.request.queryParameters, page.limit) })
        }
        post {
            val principal = tenantWrite(api)
            val body = call.receive<AbsenceCreateRequest>()
            val absence = api.tx(principal) { c -> AbsenceService.create(c, principal, body, requestId) }
            ApiSupport.setEtag(call, absence.version)
            call.respond(HttpStatusCode.Created, absence)
        }
        get("/{absenceId}") {
            val principal = tenantRead(api)
            val id = call.pathUuid("absenceId")
            val absence = api.tx(principal) { c -> AbsenceService.get(c, principal, id) }
            ApiSupport.setEtag(call, absence.version)
            call.respond(absence)
        }
        post("/{absenceId}/cancel") {
            val principal = tenantWrite(api)
            val id = call.pathUuid("absenceId")
            val version = ApiSupport.ifMatch(call)
            val absence = api.tx(principal) { c -> AbsenceService.cancel(c, principal, id, version, requestId) }
            ApiSupport.setEtag(call, absence.version)
            call.respond(absence)
        }
    }
}
