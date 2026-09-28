package com.vrticconnect.modules.staff

import com.vrticconnect.config.AppConfig
import com.vrticconnect.http.PageRequest
import com.vrticconnect.http.invalidQuery
import com.vrticconnect.http.pathUuid
import com.vrticconnect.http.queryUuid
import com.vrticconnect.modules.locations.PatchBody
import com.vrticconnect.modules.mail.LoggingMailSender
import com.vrticconnect.modules.mail.MailSender
import com.vrticconnect.modules.mail.UnconfiguredMailSender
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.requestId
import com.vrticconnect.modules.tenant.tenantRead
import com.vrticconnect.modules.tenant.tenantWrite
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Mounted under /api/v1/organizations/{organizationId}.
 * TenantApi carries no MailSender/web origin yet, so both are resolved here with the same rule as
 * AppDependencies (DEV logs the link, every other environment refuses until a provider exists).
 */
fun Route.staffRoutes(
    api: TenantApi,
    config: AppConfig = AppConfig.fromEnvironment(),
    mailSender: MailSender = if (config.isDev) LoggingMailSender() else UnconfiguredMailSender(),
) {
    val employees = EmployeeService(api)
    val memberships = MembershipAdminService(api)
    val invitations = InvitationService(api, mailSender, config.webOrigin)

    route("/employees") {
        get {
            val p = tenantRead(api)
            val q = call.request.queryParameters
            val filter = EmployeeFilter(
                role = call.enumQuery("role", STAFF_ROLES), membershipStatus = call.enumQuery("membershipStatus", MEMBERSHIP_STATUSES),
                locationId = call.queryUuid("locationId"), limit = PageRequest.from(q).limit,
            )
            call.respond(employees.list(p, filter))
        }
        post {
            val p = tenantWrite(api)
            call.respond(HttpStatusCode.Created, employees.create(p, call.receive<EmployeeCreate>(), requestId))
        }
        get("/{employeeId}") {
            val p = tenantRead(api)
            call.respond(employees.get(p, call.pathUuid("employeeId")))
        }
        patch("/{employeeId}") {
            val p = tenantWrite(api)
            val id = call.pathUuid("employeeId")
            call.respond(employees.update(p, id, PatchBody.receive(call), requestId))
        }
    }
    route("/memberships") {
        get {
            val p = tenantRead(api)
            val q = call.request.queryParameters
            val search = q["search"]?.trim()?.takeIf { it.isNotEmpty() }
            if (search != null && search.length !in 2..100) throw invalidQuery("search", "2..100 characters")
            val filter = MembershipFilter(
                role = call.enumQuery("role", ALL_ROLES), status = call.enumQuery("status", MEMBERSHIP_STATUSES),
                search = search, limit = PageRequest.from(q).limit,
            )
            call.respond(memberships.list(p, filter))
        }
        post("/{membershipId}/revoke") {
            val p = tenantWrite(api)
            val id = call.pathUuid("membershipId")
            call.respond(memberships.revoke(p, id, call.receive<RevokeRequest>(), requestId))
        }
    }
    route("/invitations") {
        get {
            val p = tenantRead(api)
            val filter = InvitationFilter(
                status = call.enumQuery("status", INVITATION_STATUSES), role = call.enumQuery("role", ALL_ROLES),
                childId = call.queryUuid("childId"), limit = PageRequest.from(call.request.queryParameters).limit,
            )
            call.respond(invitations.list(p, filter))
        }
        post {
            val p = tenantWrite(api)
            call.respond(HttpStatusCode.Created, invitations.create(p, call.receive<InvitationCreate>(), requestId))
        }
        post("/{invitationId}/revoke") {
            val p = tenantWrite(api)
            call.respond(invitations.revoke(p, call.pathUuid("invitationId"), requestId))
        }
    }
}

private val STAFF_ROLES = setOf("OWNER", "ADMIN", "TEACHER")
private val ALL_ROLES = setOf("OWNER", "ADMIN", "TEACHER", "PARENT")
private val MEMBERSHIP_STATUSES = setOf("INVITED", "ACTIVE", "SUSPENDED", "REVOKED")
private val INVITATION_STATUSES = setOf("PENDING", "ACCEPTED", "EXPIRED", "REVOKED")

private fun ApplicationCall.enumQuery(name: String, allowed: Set<String>): String? {
    val raw = request.queryParameters[name] ?: return null
    if (raw !in allowed) throw invalidQuery(name, allowed.joinToString("|"))
    return raw
}
