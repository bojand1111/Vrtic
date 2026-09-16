package com.vrticconnect.authz

/**
 * Roles are properties of an organization membership (never of a user). SUPER_ADMIN is global
 * (app.platform_admins) and is intentionally NOT a member role.
 */
enum class Role { OWNER, ADMIN, TEACHER, PARENT }

/**
 * Permission vocabulary. Role → permission mapping is the single source of truth for the
 * matrix in docs/SECURITY.md. Scope rules (own groups / own children) are enforced separately by
 * resource policies; a permission only says "this role may attempt the action".
 */
enum class Permission {
    ORG_SETTINGS_MANAGE,
    LOCATION_MANAGE, GROUP_MANAGE,
    MEMBER_INVITE, MEMBER_MANAGE, MEMBER_REVOKE,
    TEACHER_ASSIGN,
    CHILD_READ, CHILD_MANAGE,
    GUARDIAN_MANAGE, PICKUP_PERSON_READ, PICKUP_PERSON_MANAGE,
    CHILD_HEALTH_READ, CHILD_HEALTH_WRITE,
    SCHEDULE_READ, SCHEDULE_MANAGE,
    ABSENCE_READ, ABSENCE_REPORT,
    ATTENDANCE_READ, ATTENDANCE_RECORD, ATTENDANCE_CORRECT,
    ANNOUNCEMENT_READ, ANNOUNCEMENT_MANAGE, ANNOUNCEMENT_PUBLISH,
    CALENDAR_READ, CALENDAR_MANAGE,
    MENU_READ, MENU_MANAGE,
    PHOTO_VIEW, PHOTO_UPLOAD, PHOTO_PUBLISH,
    CONSENT_GIVE, CONSENT_MANAGE_POLICIES,
    MESSAGE_SEND,
    BILLING_VIEW, BILLING_MANAGE,
    REPORT_VIEW, REPORT_EXPORT,
    AUDIT_READ,
}

/** Permissions that may be granted individually via app.membership_permissions. */
val GRANTABLE_EXTRA_PERMISSIONS: Set<Permission> = setOf(
    Permission.CHILD_HEALTH_READ, Permission.CHILD_HEALTH_WRITE, Permission.ATTENDANCE_CORRECT,
    Permission.ANNOUNCEMENT_PUBLISH, Permission.PHOTO_PUBLISH, Permission.BILLING_MANAGE,
    Permission.MEMBER_MANAGE, Permission.REPORT_EXPORT,
)

object PermissionMatrix {
    private val teacher: Set<Permission> = setOf(
        Permission.CHILD_READ, Permission.PICKUP_PERSON_READ,
        Permission.SCHEDULE_READ, Permission.ABSENCE_READ,
        Permission.ATTENDANCE_READ, Permission.ATTENDANCE_RECORD,
        Permission.ANNOUNCEMENT_READ, Permission.CALENDAR_READ, Permission.MENU_READ,
        Permission.PHOTO_VIEW, Permission.PHOTO_UPLOAD, Permission.MESSAGE_SEND,
    )

    private val parent: Set<Permission> = setOf(
        Permission.CHILD_READ, Permission.PICKUP_PERSON_READ, Permission.PICKUP_PERSON_MANAGE,
        Permission.CHILD_HEALTH_READ, Permission.CHILD_HEALTH_WRITE,   // own child only + guardian flags
        Permission.SCHEDULE_READ, Permission.SCHEDULE_MANAGE,
        Permission.ABSENCE_READ, Permission.ABSENCE_REPORT,
        Permission.ATTENDANCE_READ,
        Permission.ANNOUNCEMENT_READ, Permission.CALENDAR_READ, Permission.MENU_READ,
        Permission.PHOTO_VIEW, Permission.CONSENT_GIVE, Permission.MESSAGE_SEND,
    )

    private val admin: Set<Permission> = teacher + setOf(
        Permission.LOCATION_MANAGE, Permission.GROUP_MANAGE,
        Permission.MEMBER_INVITE, Permission.MEMBER_MANAGE, Permission.MEMBER_REVOKE, Permission.TEACHER_ASSIGN,
        Permission.CHILD_MANAGE, Permission.GUARDIAN_MANAGE, Permission.PICKUP_PERSON_MANAGE,
        Permission.SCHEDULE_MANAGE, Permission.ABSENCE_REPORT,
        Permission.ATTENDANCE_CORRECT,
        Permission.ANNOUNCEMENT_MANAGE, Permission.ANNOUNCEMENT_PUBLISH,
        Permission.CALENDAR_MANAGE, Permission.MENU_MANAGE,
        Permission.PHOTO_PUBLISH, Permission.CONSENT_MANAGE_POLICIES,
        Permission.REPORT_VIEW, Permission.AUDIT_READ,
    )

    // Owner = admin + billing + settings. Deliberately WITHOUT CHILD_HEALTH_* — ownership is not a
    // medical need-to-know; it must be granted explicitly and is audited.
    private val owner: Set<Permission> = admin + setOf(
        Permission.ORG_SETTINGS_MANAGE, Permission.BILLING_VIEW, Permission.BILLING_MANAGE, Permission.REPORT_EXPORT,
    )

    private val byRole: Map<Role, Set<Permission>> = mapOf(
        Role.OWNER to owner,
        Role.ADMIN to admin,
        Role.TEACHER to teacher,
        Role.PARENT to parent,
    )

    fun permissionsOf(role: Role): Set<Permission> = byRole.getValue(role)

    fun has(role: Role, extra: Set<Permission>, permission: Permission): Boolean =
        permission in permissionsOf(role) || (permission in GRANTABLE_EXTRA_PERMISSIONS && permission in extra)

    /** Roles a member with [actor] role may assign to others. OWNER can never be granted through the API. */
    fun assignableRoles(actor: Role): Set<Role> = when (actor) {
        Role.OWNER -> setOf(Role.ADMIN, Role.TEACHER, Role.PARENT)
        Role.ADMIN -> setOf(Role.TEACHER, Role.PARENT)
        Role.TEACHER, Role.PARENT -> emptySet()
    }
}
