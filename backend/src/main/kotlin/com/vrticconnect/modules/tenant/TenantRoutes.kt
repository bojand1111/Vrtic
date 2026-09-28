package com.vrticconnect.modules.tenant

import com.vrticconnect.authz.Authorize
import com.vrticconnect.config.AppConfig
import com.vrticconnect.modules.mail.MailSender
import com.vrticconnect.db.Database
import com.vrticconnect.db.DbContext
import com.vrticconnect.http.ProblemException
import com.vrticconnect.modules.absences.absenceRoutes
import com.vrticconnect.modules.announcements.announcementRoutes
import com.vrticconnect.modules.attendance.attendanceRoutes
import com.vrticconnect.modules.calendar.calendarRoutes
import com.vrticconnect.modules.children.childrenRoutes
import com.vrticconnect.modules.dashboard.dashboardRoutes
import com.vrticconnect.modules.groups.groupRoutes
import com.vrticconnect.modules.locations.locationRoutes
import com.vrticconnect.modules.meals.menuRoutes
import com.vrticconnect.modules.schedules.scheduleRoutes
import com.vrticconnect.modules.staff.staffRoutes
import com.vrticconnect.modules.me.ChangePasswordRequest
import com.vrticconnect.modules.me.LocaleUpdate
import com.vrticconnect.modules.me.MeService
import com.vrticconnect.modules.organizations.OrganizationSettingsService
import com.vrticconnect.modules.organizations.OrganizationSettingsUpdate
import com.vrticconnect.modules.platform.PlatformOrganizationService
import com.vrticconnect.modules.auth.AccountService
import com.vrticconnect.modules.auth.CsrfService
import com.vrticconnect.modules.auth.SessionResolver
import com.vrticconnect.modules.auth.requireUser
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Tenant-scoped routes: /api/v1/organizations/{organizationId}/...
 * The pipeline for every tenant request:
 *   1. resolve session  -> 401 if absent/expired/revoked                      (SessionResolver)
 *   2. resolve ACTIVE membership of the user in {organizationId} -> 404 if none (MembershipResolver)
 *   3. open a DB transaction with DbContext.Tenant(organizationId, userId) -> RLS applies
 *   4. resource-level policy (group assignment, guardian link, extra permission) - per module (E03+)
 * Steps 1-3 are implemented here; `/ping` is the canonical, tested example of the pipeline.
 */
fun Route.tenantRoutes(
    sessionResolver: SessionResolver,
    membershipResolver: MembershipResolver? = null,
    membershipService: MembershipService? = null,
    database: Database? = null,
    accountService: AccountService? = null,
    csrfService: CsrfService? = null,
    settingsService: OrganizationSettingsService? = null,
    platformOrganizations: PlatformOrganizationService? = null,
    meService: MeService? = null,
    tenantApi: TenantApi = TenantApi(sessionResolver, membershipResolver, database, csrfService),
    appConfig: AppConfig? = null,
    mailSender: MailSender? = null,
) {
    route("/organizations/{organizationId}") {
        // E03+ business modules; each owns its routes, services and SQL.
        locationRoutes(tenantApi)
        groupRoutes(tenantApi)
        if (appConfig != null && mailSender != null) staffRoutes(tenantApi, appConfig, mailSender) else staffRoutes(tenantApi)
        childrenRoutes(tenantApi)
        absenceRoutes(tenantApi)
        scheduleRoutes(tenantApi)
        attendanceRoutes(tenantApi)
        announcementRoutes(tenantApi)
        calendarRoutes(tenantApi)
        menuRoutes(tenantApi)
        dashboardRoutes(tenantApi)
        get("/ping") {
            val principal = requireTenant(sessionResolver, membershipResolver)
            val db = database ?: throw ProblemException.notImplemented("Tenant pipeline")
            val membership = principal.membership
            // Step 3: the business transaction. Reading the organization proves the RLS context is in place:
            // without app.organization_id the organizations table yields no row for this query.
            val organizationName = db.transaction(DbContext.Tenant(membership.organizationId, principal.user.userId)) { connection ->
                connection.prepareStatement("SELECT name FROM app.organizations WHERE id = app.current_organization_id()").use { statement ->
                    statement.executeQuery().use { result -> if (result.next()) result.getString("name") else null }
                }
            } ?: throw ProblemException.notFound()
            call.respond(
                TenantPingResponse(
                    organizationId = membership.organizationId.toString(),
                    organizationName = organizationName,
                    membershipId = membership.membershipId.toString(),
                    role = membership.role.name,
                    permissions = membership.extraPermissions.sorted(),
                ),
            )
        }
        // E02-B15: first real tenant resource. GET for every ACTIVE member, PUT only with ORG_SETTINGS_MANAGE (403 otherwise).
        route("/settings") {
            get {
                val principal = requireTenant(sessionResolver, membershipResolver)
                val service = settingsService ?: throw ProblemException.notImplemented("Organization settings")
                call.respond(service.get(principal))
            }
            put {
                val principal = requireTenant(sessionResolver, membershipResolver)
                val service = settingsService ?: throw ProblemException.notImplemented("Organization settings")
                csrfService?.require(call, principal.user)
                val update = call.receive<OrganizationSettingsUpdate>()
                call.respond(service.replace(principal, update, call.callId))
            }
        }
    }
    route("/me") {
        // E02-B16: own profile, locale, password (no tenant context).
        get {
            val user = requireUser(sessionResolver)
            val service = meService ?: throw ProblemException.notImplemented("Profile")
            call.respond(service.profile(user))
        }
        put("/locale") {
            val user = requireUser(sessionResolver)
            val service = meService ?: throw ProblemException.notImplemented("Locale")
            csrfService?.require(call, user)
            call.respond(service.updateLocale(user, call.receive<LocaleUpdate>()))
        }
        post("/password") {
            val user = requireUser(sessionResolver)
            val service = meService ?: throw ProblemException.notImplemented("Password change")
            csrfService?.require(call, user)
            service.changePassword(user, call.receive<ChangePasswordRequest>(), call.callId)
            call.respond(HttpStatusCode.NoContent)
        }
        get("/memberships") {
            val user = requireUser(sessionResolver)
            val service = membershipService ?: throw ProblemException.notImplemented("Membership list")
            call.respond(service.listMine(user.userId, call.request.queryParameters["status"]))
        }
        // E02-B04: existing account accepts an invitation addressed to its own e-mail.
        post("/invitations/accept") {
            val user = requireUser(sessionResolver)
            val service = accountService ?: throw ProblemException.notImplemented("Invitation acceptance")
            csrfService?.require(call, user)
            call.respond(HttpStatusCode.Created, service.acceptAsExistingUser(call, user))
        }
    }
    // Platform pipeline: session -> platform admin with MFA (404 for everyone else, 403 MFA_REQUIRED) -> DbContext.Platform.
    route("/platform") {
        get("/organizations") {
            val admin = Authorize.requirePlatformAdmin(requireUser(sessionResolver))
            val service = platformOrganizations ?: throw ProblemException.notImplemented("Platform administration")
            call.respond(service.list(admin, call.request.queryParameters))
        }
    }
}

@Serializable
data class TenantPingResponse(
    val organizationId: String,
    val organizationName: String,
    val membershipId: String,
    val role: String,
    val permissions: List<String>,
)

/**
 * Steps 1 and 2 of the tenant pipeline. Order matters: 401 before any organization lookup, then
 * 404 for a malformed id, an unknown organization or a user without an ACTIVE membership, so the
 * response never reveals whether the organization exists.
 */
suspend fun RoutingContext.requireTenant(
    sessionResolver: SessionResolver,
    membershipResolver: MembershipResolver?,
): TenantPrincipal {
    val user = requireUser(sessionResolver)
    val organizationId = call.parameters["organizationId"]
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProblemException.notFound()
    val resolver = membershipResolver ?: throw ProblemException.notImplemented("Tenant pipeline")
    val membership = resolver.resolve(user, organizationId) ?: throw ProblemException.notFound()
    return TenantPrincipal(user, membership)
}
