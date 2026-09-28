package com.vrticconnect.modules.schedules

import com.vrticconnect.http.pathUuid
import com.vrticconnect.http.queryDate
import com.vrticconnect.http.queryUuid
import com.vrticconnect.http.PageRequest
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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.LocalDate

/**
 * Mounted under /api/v1/organizations/{organizationId}. docs/openapi.yaml tag Schedules: week view, templates,
 * day overrides, closure days, plus the additions `GET /schedules/expected` and `GET /schedules/changes`.
 * Not implemented: `PUT /schedule/week` (atomic week), `/schedule/preview`, `/schedule/daily-plans`.
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
        route("/overrides/{date}") {
            put {
                val principal = tenantWrite(api)
                val childId = call.pathUuid("childId")
                val date = pathDate(call.parameters["date"])
                val ifMatch = ApiSupport.ifMatch(call)
                val body = call.receive<DayOverrideSetRequest>()
                val result = api.tx(principal) { c -> DayOverrideService.set(c, principal, childId, date, body, ifMatch, requestId) }
                ApiSupport.setEtag(call, result.version)
                call.respond(result)
            }
            delete {
                val principal = tenantWrite(api)
                val childId = call.pathUuid("childId")
                val date = pathDate(call.parameters["date"])
                val ifMatch = ApiSupport.ifMatch(call)
                api.tx(principal) { c -> DayOverrideService.remove(c, principal, childId, date, ifMatch, requestId) }
                call.respond(HttpStatusCode.NoContent)
            }
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
    get("/schedules/changes") {
        val principal = tenantRead(api)
        val groupId = call.queryUuid("groupId")
        val date = call.queryDate("date")
        val lateOnly = call.request.queryParameters["lateOnly"]?.let { it == "true" } ?: false
        val limit = PageRequest.from(call.request.queryParameters).limit
        call.respond(api.tx(principal) { c -> ScheduleChanges.feed(c, principal, date, groupId, lateOnly, limit) })
    }
    route("/closure-days") {
        get {
            val principal = tenantRead(api)
            val from = call.queryDate("from")
            val to = call.queryDate("to")
            val locationId = call.queryUuid("locationId")
            val limit = PageRequest.from(call.request.queryParameters).limit
            call.respond(api.tx(principal) { c -> ClosureDayService.list(c, principal, from, to, locationId, limit) })
        }
        post {
            val principal = tenantWrite(api)
            val body = call.receive<ClosureDayCreateRequest>()
            call.respond(HttpStatusCode.Created, api.tx(principal) { c -> ClosureDayService.create(c, principal, body, requestId) })
        }
        delete("/{closureDayId}") {
            val principal = tenantWrite(api)
            val id = call.pathUuid("closureDayId")
            api.tx(principal) { c -> ClosureDayService.delete(c, principal, id, requestId) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
    get("/schedules/expected") {
        val principal = tenantRead(api)
        val groupId = call.queryUuid("groupId") ?: throw invalidQuery("groupId", "UUID")
        val date = call.queryDate("date")
        call.respond(api.tx(principal) { c -> ScheduleService.expected(c, principal, groupId, date) })
    }
}

/** Path date (YYYY-MM-DD); malformed -> 422. */
private fun pathDate(raw: String?): LocalDate =
    raw?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: throw invalidQuery("date", "YYYY-MM-DD")
