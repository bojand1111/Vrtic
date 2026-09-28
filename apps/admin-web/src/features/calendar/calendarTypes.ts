/** docs/openapi.yaml CalendarEvent kinds and shape. */
export const EVENT_KINDS = ['TRIP', 'PHOTO_DAY', 'PERFORMANCE', 'HOLIDAY', 'PARENT_MEETING', 'CLOSURE', 'OTHER'] as const;
export type EventKind = (typeof EVENT_KINDS)[number];

/** docs/openapi.yaml CalendarEvent (nullable fields may be omitted). */
export interface CalendarEvent {
  readonly id: string;
  readonly kind: EventKind;
  readonly title: string;
  readonly description?: string | null;
  readonly locationId?: string | null;
  readonly groupId?: string | null;
  readonly allDay: boolean;
  readonly startsOn: string;
  readonly endsOn: string;
  readonly startsAt?: string | null;
  readonly endsAt?: string | null;
  readonly version: number;
}
