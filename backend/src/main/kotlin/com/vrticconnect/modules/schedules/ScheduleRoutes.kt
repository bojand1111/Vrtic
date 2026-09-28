package com.vrticconnect.modules.schedules

import com.vrticconnect.http.pathUuid
import com.vrticconnect.http.queryDate
import com.vrticconnect.http.queryUuid
import com.vrticconnect.http.invalidQuery
import com.vrticconnect.modules.children.ApiSupport
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.requestId
import com.vrticconnect.modules.tenant.tenantRead
import com.vrticconnect.modules.tenant.tenantWrite
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Mounted under /api/v1/organizations/{organizationId}. docs/openapi.yaml tag Schedules, limited to
 * what templates + absences can answer: overrides, daily plans, preview, closure days and
 * `PUT /schedule/week` are not implemented (their tables do not exist yet).
 */
fun Route.scheduleRoutes(api: TenantApi) {
    route("/children/{childId}/schedule") {
        get("/week") {
            val principal = tenantRead(api)
            val childId = call.pathUuid("childId")
            val weekStart = call.queryDate("weekStart")
            val week = api.tx(principal) { c -> ScheduleService.week(c, principal, childId, weekStart) }
            call.response.headers.append("ETag", ApiSupport.etag(week.version))
            call.respond(week)
        }
        route("/templates") {
            get {
                val principal = tenantRead(api)
                val childId = call.pathUuid("childId")
                call.respond(api.tx(principal) { c -> ScheduleService.listTemplates(c, principal, childId) })
            }
            val replace: suspend RoutingContext.() -> Unit = {
                val principal = tenantWrite(api)
                val childId = call.pathUuid("childId")
                val body = call.receive<ScheduleTemplateCreateRequest>()
                val (template, created) = api.tx(principal) { c -> ScheduleService.replaceTemplate(c, principal, childId, body, requestId) }
                call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, template)
            }
            post(replace)
            put(replace)
        }
    }
    get("/schedules/expected") {
        val principal = tenantRead(api)
        val groupId = call.queryUuid("groupId") ?: throw invalidQuery("groupId", "UUID")
        val date = call.queryDate("date")
        call.respond(api.tx(principal) { c -> ScheduleService.expected(c, principal, groupId, date) })
    }
}
