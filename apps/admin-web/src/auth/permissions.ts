import type { OrganizationRole } from './types';

/**
 * Mirror of backend `PermissionMatrix` (backend/.../authz/Permissions.kt, docs/SECURITY.md).
 * Used ONLY to hide UI the member cannot use; the backend enforces every rule again (403/404).
 * Individually granted extras (membership_permissions) are not known here, so an extra can
 * make an action work that the UI hides; that is acceptable for a visibility hint.
 */
export type Permission =
  | 'ORG_SETTINGS_MANAGE'
  | 'LOCATION_MANAGE'
  | 'GROUP_MANAGE'
  | 'MEMBER_INVITE'
  | 'MEMBER_MANAGE'
  | 'MEMBER_REVOKE'
  | 'TEACHER_ASSIGN'
  | 'CHILD_READ'
  | 'CHILD_MANAGE'
  | 'GUARDIAN_MANAGE'
  | 'PICKUP_PERSON_READ'
  | 'PICKUP_PERSON_MANAGE'
  | 'SCHEDULE_READ'
  | 'SCHEDULE_MANAGE'
  | 'ABSENCE_READ'
  | 'ABSENCE_REPORT'
  | 'ATTENDANCE_READ'
  | 'ATTENDANCE_RECORD'
  | 'ATTENDANCE_CORRECT'
  | 'ANNOUNCEMENT_READ'
  | 'ANNOUNCEMENT_MANAGE'
  | 'ANNOUNCEMENT_PUBLISH'
  | 'CALENDAR_READ'
  | 'CALENDAR_MANAGE'
  | 'MENU_READ'
  | 'MENU_MANAGE'
  | 'PHOTO_VIEW'
  | 'MESSAGE_SEND'
  | 'BILLING_VIEW'
  | 'REPORT_VIEW'
  | 'REPORT_EXPORT'
  | 'AUDIT_READ';

const TEACHER: readonly Permission[] = [
  'CHILD_READ',
  'PICKUP_PERSON_READ',
  'SCHEDULE_READ',
  'ABSENCE_READ',
  'ATTENDANCE_READ',
  'ATTENDANCE_RECORD',
  'ANNOUNCEMENT_READ',
  'CALENDAR_READ',
  'MENU_READ',
  'PHOTO_VIEW',
  'MESSAGE_SEND',
];

const PARENT: readonly Permission[] = [
  'CHILD_READ',
  'PICKUP_PERSON_READ',
  'PICKUP_PERSON_MANAGE',
  'SCHEDULE_READ',
  'SCHEDULE_MANAGE',
  'ABSENCE_READ',
  'ABSENCE_REPORT',
  'ATTENDANCE_READ',
  'ANNOUNCEMENT_READ',
  'CALENDAR_READ',
  'MENU_READ',
  'PHOTO_VIEW',
  'MESSAGE_SEND',
];

const ADMIN: readonly Permission[] = [
  ...TEACHER,
  'LOCATION_MANAGE',
  'GROUP_MANAGE',
  'MEMBER_INVITE',
  'MEMBER_MANAGE',
  'MEMBER_REVOKE',
  'TEACHER_ASSIGN',
  'CHILD_MANAGE',
  'GUARDIAN_MANAGE',
  'PICKUP_PERSON_MANAGE',
  'SCHEDULE_MANAGE',
  'ABSENCE_REPORT',
  'ATTENDANCE_CORRECT',
  'ANNOUNCEMENT_MANAGE',
  'ANNOUNCEMENT_PUBLISH',
  'CALENDAR_MANAGE',
  'MENU_MANAGE',
  'REPORT_VIEW',
  'AUDIT_READ',
];

const OWNER: readonly Permission[] = [...ADMIN, 'ORG_SETTINGS_MANAGE', 'BILLING_VIEW', 'REPORT_EXPORT'];

const MATRIX: Readonly<Record<OrganizationRole, ReadonlySet<Permission>>> = {
  OWNER: new Set(OWNER),
  ADMIN: new Set(ADMIN),
  TEACHER: new Set(TEACHER),
  PARENT: new Set(PARENT),
};

export function roleCan(role: OrganizationRole | null, permission: Permission): boolean {
  return role !== null && MATRIX[role].has(permission);
}
