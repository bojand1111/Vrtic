export type NotificationKind =
  | 'ANNOUNCEMENT'
  | 'SCHEDULE_CHANGE'
  | 'ABSENCE'
  | 'ATTENDANCE'
  | 'CALENDAR_EVENT'
  | 'URGENT'
  | 'CONSENT_REQUEST'
  | 'MESSAGE'
  | 'SECURITY'
  | 'SYSTEM';

/** docs/openapi.yaml `Notification` (+ organizationName). Content is an i18n key and arguments only. */
export interface AppNotification {
  readonly id: string;
  readonly organizationId?: string | null;
  readonly organizationName?: string | null;
  readonly kind: NotificationKind;
  readonly titleKey: string;
  readonly titleArgs: Readonly<Record<string, string | number | boolean>>;
  readonly refEntityType?: string | null;
  readonly refEntityId?: string | null;
  readonly createdAt: string;
  readonly readAt?: string | null;
}

export interface NotificationPage {
  readonly items: readonly AppNotification[];
  readonly nextCursor?: string | null;
  readonly unreadCount: number;
}

/** Title keys the backend produces; each has a text under `notifications.<key>` in all three locales. */
export const TITLE_KEYS = ['announcement.published', 'absence.reported', 'absence.cancelled', 'guardian.confirmed', 'message.received'] as const;
export type TitleKey = (typeof TITLE_KEYS)[number];

export function isKnownTitleKey(key: string): key is TitleKey {
  return (TITLE_KEYS as readonly string[]).includes(key);
}

const DATE_ARGS = new Set(['dateFrom', 'dateTo']);

/** Arguments as strings for interpolation; ISO dates go through [formatDate]. */
export function titleArguments(args: AppNotification['titleArgs'], formatDate: (iso: string) => string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [name, value] of Object.entries(args)) {
    out[name] = typeof value === 'string' && DATE_ARGS.has(name) ? formatDate(value) : String(value);
  }
  return out;
}

/** Screen that shows the referenced entity (routes of the web app), or null when there is none. */
export function targetPath(n: Pick<AppNotification, 'refEntityType' | 'refEntityId'>): string | null {
  switch (n.refEntityType) {
    case 'ANNOUNCEMENT':
      return '/announcements';
    case 'ABSENCE':
      return '/absences';
    case 'CHILD':
      return '/children';
    case 'CONVERSATION':
      return n.refEntityId === null || n.refEntityId === undefined ? '/messages' : `/messages?conversation=${encodeURIComponent(n.refEntityId)}`;
    default:
      return null;
  }
}
