import type { Permission } from '../auth/permissions';

/**
 * Screens required by REQUIREMENTS_BRIEF section 23, in sidebar order.
 * `anyOf`: the item is shown when the member's role has at least one of these permissions
 * (a visibility hint only; the backend enforces access). `platform`: SUPER_ADMIN only.
 */
interface FeatureEntry {
  readonly key: string;
  readonly path: string;
  readonly anyOf?: readonly Permission[];
  readonly platform?: boolean;
}

export const FEATURES = [
  { key: 'dashboard', path: '/' },
  { key: 'organizations', path: '/organizations', platform: true },
  { key: 'locations', path: '/locations', anyOf: ['LOCATION_MANAGE'] },
  { key: 'groups', path: '/groups', anyOf: ['GROUP_MANAGE', 'ATTENDANCE_RECORD'] },
  { key: 'children', path: '/children', anyOf: ['CHILD_READ'] },
  { key: 'absences', path: '/absences', anyOf: ['ABSENCE_READ'] },
  { key: 'parents', path: '/parents', anyOf: ['GUARDIAN_MANAGE'] },
  { key: 'employees', path: '/employees', anyOf: ['MEMBER_MANAGE'] },
  { key: 'attendance', path: '/attendance', anyOf: ['ATTENDANCE_READ'] },
  { key: 'schedules', path: '/schedules', anyOf: ['SCHEDULE_READ'] },
  { key: 'announcements', path: '/announcements', anyOf: ['ANNOUNCEMENT_READ'] },
  { key: 'calendar', path: '/calendar', anyOf: ['CALENDAR_READ'] },
  { key: 'meals', path: '/meals', anyOf: ['MENU_READ'] },
  { key: 'photos', path: '/photos', anyOf: ['REPORT_VIEW'] },
  { key: 'reports', path: '/reports', anyOf: ['REPORT_VIEW'] },
  { key: 'settings', path: '/settings', anyOf: ['LOCATION_MANAGE'] },
  { key: 'billing', path: '/billing', anyOf: ['BILLING_VIEW'] },
] as const satisfies readonly FeatureEntry[];

export type FeatureKey = (typeof FEATURES)[number]['key'];

export function isFeatureVisible(
  feature: FeatureEntry,
  can: (permission: Permission) => boolean,
  isPlatformAdmin: boolean,
): boolean {
  if (feature.platform === true) {
    return isPlatformAdmin;
  }
  return feature.anyOf === undefined || feature.anyOf.some(can);
}

export const LOGIN_PATH = '/login';
