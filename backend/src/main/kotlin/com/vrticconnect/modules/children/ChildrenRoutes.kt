package com.vrticconnect.modules.children

import com.vrticconnect.http.PageRequest
import com.vrticconnect.http.pathUuid
import com.vrticconnect.modules.tenant.TenantApi
import com.vrticconnect.modules.tenant.requestId
import com.vrticconnect.modules.tenant.tenantRead
import com.vrticconnect.modules.tenant.tenantWrite
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Mounted under /api/v1/organizations/{organizationId}.
 * Children, enrollments, guardians, parents overview and pickup persons (docs/openapi.yaml tag Children).
 * Idempotency-Key is accepted but not enforced (documented simplification).
 */
fun Route.childrenRoutes(api: TenantApi) {
    route("/children") {
        get {
            val principal = tenantRead(api)
            val page = PageRequest.from(call.request.queryParameters)
            call.respond(api.tx(principal) { c -> ChildrenService.list(c, principal, call.request.queryParameters, page.limit) })
        }
        post {
            val principal = tenantWrite(api)
            val body = call.receive<ChildCreateRequest>()
            val child = api.tx(principal) { c -> ChildrenService.create(c, principal, body, requestId) }
            ApiSupport.setEtag(call, child.version)
            call.respond(HttpStatusCode.Created, child)
        }
        route("/{childId}") {
            get {
                val principal = tenantRead(api)
                val childId = call.pathUuid("childId")
                val child = api.tx(principal) { c -> ChildrenService.get(c, principal, childId) }
                ApiSupport.setEtag(call, child.version)
                call.respond(child)
            }
            patch {
                val principal = tenantWrite(api)
                val childId = call.pathUuid("childId")
                val version = ApiSupport.ifMatch(call)
                val patch = Patch(call.receive<JsonObject>())
                val child = api.tx(principal) { c -> ChildrenService.update(c, principal, childId, patch, version, requestId) }
                ApiSupport.setEtag(call, child.version)
                call.respond(child)
            }
            route("/enrollments") {
                get {
                    val principal = tenantRead(api)
                    val childId = call.pathUuid("childId")
                    call.respond(api.tx(principal) { c -> ChildrenService.listEnrollments(c, principal, childId) })
                }
                post {
                    val principal = tenantWrite(api)
                    val childId = call.pathUuid("childId")
                    val body = call.receive<EnrollmentCreateRequest>()
                    call.respond(HttpStatusCode.Created, api.tx(principal) { c -> ChildrenService.createEnrollment(c, principal, childId, body, requestId) })
                }
            }
            post("/guardians") {
                val principal = tenantWrite(api)
                val childId = call.pathUuid("childId")
                val body = call.receive<GuardianLinkRequest>()
                call.respond(HttpStatusCode.Created, api.tx(principal) { c -> GuardianService.link(c, principal, childId, body, requestId) })
            }
            route("/pickup-persons") {
                get {
                    val principal = tenantRead(api)
                    val childId = call.pathUuid("childId")
                    call.respond(api.tx(principal) { c -> PickupPersonService.list(c, principal, childId) })
                }
                post {
                    val principal = tenantWrite(api)
                    val childId = call.pathUuid("childId")
                    val body = call.receive<PickupPersonCreateRequest>()
                    call.respond(HttpStatusCode.Created, api.tx(principal) { c -> PickupPersonService.create(c, principal, childId, body, requestId) })
                }
            }
        }
    }
    post("/enrollments/{enrollmentId}/end") {
        val principal = tenantWrite(api)
        val id = call.pathUuid("enrollmentId")
        val body = call.receive<EnrollmentEndRequest>()
        call.respond(api.tx(principal) { c -> ChildrenService.endEnrollment(c, principal, id, body, requestId) })
    }
    route("/guardians/{guardianId}") {
        post("/confirm") {
            val principal = tenantWrite(api)
            val id = call.pathUuid("guardianId")
            call.respond(api.tx(principal) { c -> GuardianService.confirm(c, principal, id, requestId) })
        }
        post("/revoke") {
            val principal = tenantWrite(api)
            val id = call.pathUuid("guardianId")
            val body = optionalBody(RevokeRequest.serializer()) ?: RevokeRequest()
            call.respond(api.tx(principal) { c -> GuardianService.revoke(c, principal, id, body, requestId) })
        }
        val updateGuardian: suspend RoutingContext.() -> Unit = {
            val principal = tenantWrite(api)
            val id = call.pathUuid("guardianId")
            val patch = Patch(call.receive<JsonObject>())
            call.respond(api.tx(principal) { c -> GuardianService.update(c, principal, id, patch, requestId) })
        }
        patch(updateGuardian)
        put(updateGuardian)
    }
    get("/parents") {
        val principal = tenantRead(api)
        call.respond(api.tx(principal) { c -> GuardianService.parents(c, principal) })
    }
    route("/pickup-persons/{pickupPersonId}") {
        val updatePickup: suspend RoutingContext.() -> Unit = {
            val principal = tenantWrite(api)
            val id = call.pathUuid("pickupPersonId")
            val patch = Patch(call.receive<JsonObject>())
            call.respond(api.tx(principal) { c -> PickupPersonService.update(c, principal, id, patch, requestId) })
        }
        patch(updatePickup)
        put(updatePickup)
        post("/revoke") {
            val principal = tenantWrite(api)
            val id = call.pathUuid("pickupPersonId")
            val body = optionalBody(RevokeRequest.serializer())
            call.respond(api.tx(principal) { c -> PickupPersonService.revoke(c, principal, id, body, requestId) })
        }
    }
}

private val lenientJson = Json { ignoreUnknownKeys = true }

/** Optional JSON body (`requestBody.required: false`): empty body -> null; malformed JSON -> 422 (SerializationException). */
suspend fun <T> RoutingContext.optionalBody(deserializer: DeserializationStrategy<T>): T? {
    val text = call.receiveText()
    if (text.isBlank()) return null
    return lenientJson.decodeFromString(deserializer, text)
}
