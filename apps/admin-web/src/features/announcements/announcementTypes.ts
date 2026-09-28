/** docs/openapi.yaml Announcement / AnnouncementAudience / AnnouncementRecipient. */
export type AnnouncementStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
export type AudienceType = 'ORGANIZATION' | 'LOCATION' | 'GROUP' | 'GUARDIAN';

export interface AnnouncementAudience {
  readonly audienceType: AudienceType;
  readonly locationId?: string | null;
  readonly groupId?: string | null;
  readonly membershipId?: string | null;
}

export interface Announcement {
  readonly id: string;
  readonly title: string;
  readonly body: string;
  readonly status: AnnouncementStatus;
  readonly publishAt?: string | null;
  readonly expiresAt?: string | null;
  readonly publishedAt?: string | null;
  readonly audiences: readonly AnnouncementAudience[];
  readonly recipientsCount?: number | null;
  readonly readCount?: number | null;
  readonly readAt?: string | null;
  readonly version: number;
  readonly createdAt: string;
}

export interface AnnouncementRecipient {
  readonly membershipId: string;
  readonly givenName: string;
  readonly familyName: string;
  readonly role: 'OWNER' | 'ADMIN' | 'TEACHER' | 'PARENT';
  readonly readAt?: string | null;
  readonly stillEntitled: boolean;
}

/** Recipient inbox order: unread first, then newest published first. */
export function inboxOrder(items: readonly Announcement[]): Announcement[] {
  return [...items].sort((a, b) => {
    const unreadA = a.readAt == null ? 0 : 1;
    const unreadB = b.readAt == null ? 0 : 1;
    if (unreadA !== unreadB) {
      return unreadA - unreadB;
    }
    return (b.publishedAt ?? '').localeCompare(a.publishedAt ?? '');
  });
}
