package com.vrticconnect

import com.vrticconnect.authz.Permission
import com.vrticconnect.authz.PermissionMatrix
import com.vrticconnect.authz.Role
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionMatrixTest {

    @Test
    fun `owner does not get health access implicitly`() {
        assertFalse(PermissionMatrix.has(Role.OWNER, emptySet(), Permission.CHILD_HEALTH_READ))
        assertFalse(PermissionMatrix.has(Role.ADMIN, emptySet(), Permission.CHILD_HEALTH_READ))
        assertTrue(PermissionMatrix.has(Role.ADMIN, setOf(Permission.CHILD_HEALTH_READ), Permission.CHILD_HEALTH_READ))
    }

    @Test
    fun `teacher can record attendance but not correct or manage children`() {
        assertTrue(PermissionMatrix.has(Role.TEACHER, emptySet(), Permission.ATTENDANCE_RECORD))
        assertFalse(PermissionMatrix.has(Role.TEACHER, emptySet(), Permission.ATTENDANCE_CORRECT))
        assertFalse(PermissionMatrix.has(Role.TEACHER, emptySet(), Permission.CHILD_MANAGE))
        assertFalse(PermissionMatrix.has(Role.TEACHER, emptySet(), Permission.MEMBER_INVITE))
    }

    @Test
    fun `parent cannot manage members, groups or publish`() {
        for (p in listOf(Permission.MEMBER_INVITE, Permission.GROUP_MANAGE, Permission.ANNOUNCEMENT_PUBLISH, Permission.ATTENDANCE_RECORD, Permission.GUARDIAN_MANAGE)) {
            assertFalse(PermissionMatrix.has(Role.PARENT, emptySet(), p), "PARENT must not have $p")
        }
        assertTrue(PermissionMatrix.has(Role.PARENT, emptySet(), Permission.ABSENCE_REPORT))
        assertTrue(PermissionMatrix.has(Role.PARENT, emptySet(), Permission.SCHEDULE_MANAGE))
    }

    @Test
    fun `non-grantable permissions cannot be smuggled via extra set`() {
        assertFalse(PermissionMatrix.has(Role.TEACHER, setOf(Permission.GROUP_MANAGE), Permission.GROUP_MANAGE))
        assertFalse(PermissionMatrix.has(Role.PARENT, setOf(Permission.MEMBER_REVOKE), Permission.MEMBER_REVOKE))
    }

    @Test
    fun `nobody can assign OWNER through the API and admins cannot mint admins`() {
        Role.entries.forEach { assertFalse(Role.OWNER in PermissionMatrix.assignableRoles(it)) }
        assertFalse(Role.ADMIN in PermissionMatrix.assignableRoles(Role.ADMIN))
        assertTrue(PermissionMatrix.assignableRoles(Role.TEACHER).isEmpty())
        assertTrue(PermissionMatrix.assignableRoles(Role.PARENT).isEmpty())
    }

    @Test
    fun `owner is a superset of admin, admin of teacher`() {
        assertTrue(PermissionMatrix.permissionsOf(Role.OWNER).containsAll(PermissionMatrix.permissionsOf(Role.ADMIN)))
        assertTrue(PermissionMatrix.permissionsOf(Role.ADMIN).containsAll(PermissionMatrix.permissionsOf(Role.TEACHER)))
    }
}
