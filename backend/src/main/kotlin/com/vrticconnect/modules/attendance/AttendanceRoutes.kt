package com.vrticconnect.modules.attendance

import com.vrticconnect.http.PageRequest
import com.vrticconnect.http.ProblemException
import com.vrticconnect.http.invalidQuery
import com.vrticconnect.http.pathUuid
import com.vrticconnect.http.queryDate
import com.vrticconnect.http.queryUuid
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
import java.time.LocalDate

/**
 * Mounted under /api/v1/organizations/{organizationId}. docs/openapi.yaml "Attendance" + ADR-0008.
 * POST /attendance/sync (offline batch) is not implemented in this round.
 */
fun Route.attendanceRoutes(api: TenantApi) {
    val commands = AttendanceCommands(api)
    val queries = AttendanceQueries(api)

    route("/children/{childId}/attendance") {
        for ((segment, type) in listOf(
            "check-in" to AttendanceCommandType.CHECK_IN,
            "check-out" to AttendanceCommandType.CHECK_OUT,
            "mark-absent" to AttendanceCommandType.MARK_ABSENT,
            "clear-absence" to AttendanceCommandType.CLEAR_ABSENCE,
            "correction" to AttendanceCommandType.CORRECTION,
        )) {
            post("/$segment") {
                val principal = tenantWrite(api)
                val childId = call.pathUuid("childId")
                val body = call.receive<AttendanceCommandRequest>()
                val (created, result) = commands.execute(principal, childId, type, body, requestId)
                call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, result)
            }
        }
        get("/days/{date}") {
            val principal = tenantRead(api)
            val childId = call.pathUuid("childId")
            val date = call.parameters["date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: throw ProblemException.notFound()
            call.respond(queries.childDay(principal, childId, date))
        }
    }

    get("/attendance/daily-overview") {
        val principal = tenantRead(api)
        val groupId = call.queryUuid("groupId") ?: throw invalidQuery("groupId", "required UUID")
        call.respond(queries.dailyOverview(principal, groupId, call.queryDate("date")))
    }

    get("/attendance/events") {
        val principal = tenantRead(api)
        val date = call.queryDate("date") ?: throw invalidQuery("date", "required YYYY-MM-DD")
        val sort = call.request.queryParameters["sort"] ?: "recordedAt:desc"
        if (sort !in setOf("recordedAt:desc", "recordedAt:asc")) throw invalidQuery("sort", "recordedAt:desc|recordedAt:asc")
        val page = PageRequest.from(call.request.queryParameters)
        call.respond(
            queries.events(
                principal, date, call.queryUuid("groupId"), call.queryUuid("childId"), call.request.queryParameters["eventType"],
                ascending = sort == "recordedAt:asc", limit = page.limit,
            ),
        )
    }
}
