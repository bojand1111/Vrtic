package com.vrticconnect.modules.meals

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
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Mounted under /api/v1/organizations/{organizationId}. docs/openapi.yaml "Menus".
 * DELETE /menu-days/{id} is not implemented: app_runtime has no DELETE on menu_days and the table has no deleted_at.
 */
fun Route.menuRoutes(api: TenantApi) {
    val service = MenuService(api)

    route("/menu-days") {
        get {
            val principal = tenantRead(api)
            val q = call.request.queryParameters
            val page = PageRequest.from(q)
            call.respond(
                service.list(
                    principal, call.queryDate("from"), call.queryDate("to"), call.queryUuid("locationId"), q["publishedOnly"] == "true",
                    q["sort"] ?: "menuDate:asc", page.limit,
                ),
            )
        }
        post {
            val principal = tenantWrite(api)
            val dto = service.create(principal, call.receive<MenuDayCreate>(), requestId)
            Versioning.etag(call, dto.version)
            call.respond(HttpStatusCode.Created, dto)
        }
        route("/{menuDayId}") {
            get {
                val principal = tenantRead(api)
                val dto = service.get(principal, call.pathUuid("menuDayId"))
                Versioning.etag(call, dto.version)
                call.respond(dto)
            }
            put {
                val principal = tenantWrite(api)
                val id = call.pathUuid("menuDayId")
                val version = Versioning.ifMatch(call)
                val dto = service.replace(principal, id, version, call.receive<MenuDayUpdate>(), requestId)
                Versioning.etag(call, dto.version)
                call.respond(dto)
            }
            post("/publish") {
                val principal = tenantWrite(api)
                val id = call.pathUuid("menuDayId")
                val dto = service.publish(principal, id, Versioning.ifMatch(call), requestId)
                Versioning.etag(call, dto.version)
                call.respond(dto)
            }
        }
    }
}
