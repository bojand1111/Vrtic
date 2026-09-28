package com.vrticconnect.modules.groups

import com.vrticconnect.http.PageRequest
import com.vrticconnect.http.invalidQuery
import com.vrticconnect.http.pathUuid
import com.vrticconnect.http.queryDate
import com.vrticconnect.http.queryUuid
import com.vrticconnect.modules.locations.PatchBody
import com.vrticconnect.modules.locations.statusQuery
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.requestId
import com.vrticconnect.modules.tenant.tenantRead
import com.vrticconnect.modules.tenant.tenantWrite
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Mounted under /api/v1/organizations/{organizationId}. */
fun Route.groupRoutes(api: TenantApi) {
    val groups = GroupService(api)
    val assignments = AssignmentService(api)
    route("/groups") {
        get {
            val p = tenantRead(api)
            val locationId = call.queryUuid("locationId")
            val status = call.statusQuery()
            call.respond(groups.list(p, locationId, status, PageRequest.from(call.request.queryParameters).limit))
        }
        post {
            val p = tenantWrite(api)
            call.respond(HttpStatusCode.Created, groups.create(p, call.receive<GroupCreate>(), requestId))
        }
        get("/{groupId}") {
            val p = tenantRead(api)
            call.respond(groups.get(p, call.pathUuid("groupId")))
        }
        patch("/{groupId}") {
            val p = tenantWrite(api)
            val id = call.pathUuid("groupId")
            call.respond(groups.update(p, id, PatchBody.receive(call), requestId))
        }
        delete("/{groupId}") {
            val p = tenantWrite(api)
            groups.delete(p, call.pathUuid("groupId"), requestId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
    route("/group-teacher-assignments") {
        get {
            val p = tenantRead(api)
            val q = call.request.queryParameters
            val includeRevoked = when (q["includeRevoked"]) {
                null, "false" -> false
                "true" -> true
                else -> throw invalidQuery("includeRevoked", "true|false")
            }
            val filter = AssignmentFilter(
                groupId = call.queryUuid("groupId"), employeeId = call.queryUuid("employeeId"), activeOn = call.queryDate("activeOn"),
                includeRevoked = includeRevoked, limit = PageRequest.from(q).limit,
            )
            call.respond(assignments.list(p, filter))
        }
        post {
            val p = tenantWrite(api)
            call.respond(HttpStatusCode.Created, assignments.create(p, call.receive<AssignmentCreate>(), requestId))
        }
        delete("/{assignmentId}") {
            val p = tenantWrite(api)
            assignments.revoke(p, call.pathUuid("assignmentId"), requestId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
