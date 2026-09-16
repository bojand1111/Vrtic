/** Screens required by REQUIREMENTS_BRIEF section 23, in sidebar order. */
export const FEATURES = [
  { key: 'dashboard', path: '/' },
  { key: 'organizations', path: '/organizations' },
  { key: 'locations', path: '/locations' },
  { key: 'groups', path: '/groups' },
  { key: 'children', path: '/children' },
  { key: 'parents', path: '/parents' },
  { key: 'employees', path: '/employees' },
  { key: 'attendance', path: '/attendance' },
  { key: 'schedules', path: '/schedules' },
  { key: 'announcements', path: '/announcements' },
  { key: 'calendar', path: '/calendar' },
  { key: 'meals', path: '/meals' },
  { key: 'photos', path: '/photos' },
  { key: 'reports', path: '/reports' },
  { key: 'settings', path: '/settings' },
  { key: 'billing', path: '/billing' },
] as const;

export type FeatureKey = (typeof FEATURES)[number]['key'];

export const LOGIN_PATH = '/login';
