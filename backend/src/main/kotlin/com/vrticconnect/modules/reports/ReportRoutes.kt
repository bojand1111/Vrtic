package com.vrticconnect.modules.reports

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.http.invalidQuery
import com.vrticconnect.http.queryUuid
import com.vrticconnect.modules.audit.Audit
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.requestId
import com.vrticconnect.modules.tenant.tenantRead
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Mounted under /api/v1/organizations/{organizationId}.
 *   GET /reports/attendance-summary            REPORT_VIEW (OWNER/ADMIN); JSON
 *   GET /reports/attendance-summary?format=csv  (or `Accept: text/csv`) additionally REPORT_EXPORT (OWNER, ADMIN only
 *                                               with the extra permission); audited as REPORT_EXPORTED
 *   GET /audit-log                             AUDIT_READ (OWNER/ADMIN)
 */
fun Route.reportRoutes(api: TenantApi) {
    get("/reports/attendance-summary") {
        val principal = tenantRead(api)
        val q = call.request.queryParameters
        val format = q["format"]
        if (format != null && format != "csv" && format != "json") throw invalidQuery("format", "csv|json")
        val accept = call.request.headers[HttpHeaders.Accept].orEmpty()
        val csv = format == "csv" || (format == null && accept.contains("text/csv") && !accept.contains("application/json"))
        Authorize.require(principal, Permission.REPORT_VIEW)
        if (csv) Authorize.require(principal, Permission.REPORT_EXPORT)
        val params = AttendanceReportService.parse(q["from"], q["to"], call.queryUuid("groupId"), call.queryUuid("locationId"))
        if (!csv) {
            call.respond(api.tx(principal) { c -> AttendanceReportService.report(c, principal, params) })
            return@get
        }
        val lang = ReportCsv.langOf(call.request.headers[HttpHeaders.AcceptLanguage])
        val body = api.tx(principal) { c ->
            val report = AttendanceReportService.report(c, principal, params)
            Audit.record(
                c, "REPORT_EXPORTED", "REPORT", null, actorUserId = principal.user.userId, organizationId = principal.membership.organizationId,
                actorMembershipId = principal.membership.membershipId, requestId = requestId, purpose = "ATTENDANCE_SUMMARY_CSV",
            )
            ReportCsv.attendance(report, lang)
        }
        val filename = "attendance-summary-${params.from}-${params.to}.csv"
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString())
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(body, ContentType.Text.CSV.withCharset(Charsets.UTF_8))
    }
    get("/audit-log") {
        val principal = tenantRead(api)
        Authorize.require(principal, Permission.AUDIT_READ)
        call.respond(
            api.tx(principal) { c ->
                val filter = AuditLogService.parse(call.request.queryParameters, AuditLogService.orgZone(c))
                AuditLogService.list(c, principal, filter)
            },
        )
    }
}
