export type ConversationKind = 'PARENT_TEACHER' | 'PARENT_ADMIN';
export type ParticipantRole = 'GUARDIAN' | 'TEACHER' | 'ADMIN';

export const CONVERSATION_KINDS: readonly ConversationKind[] = ['PARENT_TEACHER', 'PARENT_ADMIN'];

export interface ConversationParticipant {
  readonly membershipId: string;
  readonly givenName: string;
  readonly familyName: string;
  readonly participantRole: ParticipantRole;
  readonly joinedAt: string;
  readonly leftAt?: string | null;
}

export interface Conversation {
  readonly id: string;
  readonly organizationId: string;
  readonly kind: ConversationKind;
  readonly childId: string;
  readonly childGivenName: string;
  readonly childFamilyName: string;
  readonly groupId?: string | null;
  readonly subject?: string | null;
  readonly participants: readonly ConversationParticipant[];
  readonly lastMessageAt?: string | null;
  readonly lastMessagePreview?: string | null;
  readonly unreadCount: number;
  readonly lastReadMessageId?: string | null;
  readonly closedAt?: string | null;
  readonly createdAt: string;
}

export interface Message {
  readonly id: string;
  readonly conversationId: string;
  readonly senderMembershipId: string;
  readonly clientMessageId: string;
  readonly body: string;
  readonly createdAt: string;
  readonly editedAt?: string | null;
  readonly deletedAt?: string | null;
  readonly isOwn: boolean;
}

export interface ApiPage<T> {
  readonly items: readonly T[];
  readonly nextCursor?: string | null;
}
