package com.vrticconnect

import com.vrticconnect.authz.Authorize
import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.Role
import com.vrticconnect.http.ProblemException
import com.vrticconnect.modules.auth.AuthenticatedUser
import com.vrticconnect.modules.auth.MembershipContext
import com.vrticconnect.modules.tenant.TenantPrincipal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthorizeTest {

    private fun principal(role: Role, extras: Set<String> = emptySet(), platformAdmin: Boolean = false, mfa: Boolean = false) = TenantPrincipal(
        user = AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), isPlatformAdmin = platformAdmin, mfaVerified = mfa),
        membership = MembershipContext(UUID.randomUUID(), UUID.randomUUID(), role, extras),
    )

    @Test
    fun `role capabilities plus grantable extras, forbidden is detail-free`() {
        assertTrue(Authorize.has(principal(Role.OWNER), Permission.ORG_SETTINGS_MANAGE))
        assertFalse(Authorize.has(principal(Role.ADMIN), Permission.ORG_SETTINGS_MANAGE))
        assertFalse(Authorize.has(principal(Role.ADMIN), Permission.CHILD_HEALTH_READ))
        assertTrue(Authorize.has(principal(Role.ADMIN, setOf("CHILD_HEALTH_READ")), Permission.CHILD_HEALTH_READ))
        // a non-grantable permission cannot be smuggled through membership_permissions, unknown names are ignored
        assertFalse(Authorize.has(principal(Role.TEACHER, setOf("GROUP_MANAGE", "NOT_A_PERMISSION")), Permission.GROUP_MANAGE))

        val problem = assertFailsWith<ProblemException> { Authorize.require(principal(Role.PARENT), Permission.ATTENDANCE_RECORD) }
        assertEquals(403, problem.status.value)
        assertNull(problem.detail, "403 must not reveal which permission was missing")
        assertTrue(Authorize.requireAny(principal(Role.PARENT), Permission.ATTENDANCE_RECORD, Permission.ABSENCE_REPORT).membership.role == Role.PARENT)
    }

    @Test
    fun `platform admin needs MFA and everyone else gets 404`() {
        val tenantUser = principal(Role.OWNER).user
        assertEquals(404, assertFailsWith<ProblemException> { Authorize.requirePlatformAdmin(tenantUser) }.status.value)
        val adminNoMfa = principal(Role.PARENT, platformAdmin = true, mfa = false).user
        val noMfa = assertFailsWith<ProblemException> { Authorize.requirePlatformAdmin(adminNoMfa) }
        assertEquals(403, noMfa.status.value)
        assertEquals("MFA_REQUIRED", noMfa.detail)
        val adminMfa = principal(Role.PARENT, platformAdmin = true, mfa = true).user
        assertEquals(adminMfa, Authorize.requirePlatformAdmin(adminMfa))
    }
}
