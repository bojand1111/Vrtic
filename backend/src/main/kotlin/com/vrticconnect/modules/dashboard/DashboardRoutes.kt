package com.vrticconnect.modules.dashboard

import com.vrticconnect.http.queryDate
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.tenantRead
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Mounted under /api/v1/organizations/{organizationId}. */
fun Route.dashboardRoutes(api: TenantApi) {
    val service = DashboardService(api)
    // docs/openapi.yaml getDashboardSummary: OWNER/ADMIN (REPORT_VIEW).
    get("/dashboard") {
        val principal = tenantRead(api)
        call.respond(service.summary(principal, call.queryDate("date")))
    }
}
