import { describe, expect, it } from 'vitest';

import { chronological, composerKeyAction, counterpartNames, isSendable, readPositionToSend } from './helpers';
import type { ConversationParticipant, Message } from './types';

const participant = (name: string, role: ConversationParticipant['participantRole'], leftAt: string | null = null): ConversationParticipant => ({
  membershipId: name,
  givenName: name,
  familyName: 'X',
  participantRole: role,
  joinedAt: '2026-09-01T08:00:00Z',
  leftAt,
});

const message = (id: string, createdAt: string): Message => ({
  id,
  conversationId: 'c',
  senderMembershipId: 'm',
  clientMessageId: id,
  body: id,
  createdAt,
  isOwn: false,
});

describe('counterpartNames', () => {
  const conversation = {
    participants: [participant('Ana', 'GUARDIAN'), participant('Vera', 'TEACHER'), participant('Staro', 'TEACHER', '2026-09-10T08:00:00Z'), participant('Uprava', 'ADMIN')],
  };

  it('shows staff to a parent and guardians to staff, never former participants', () => {
    expect(counterpartNames(conversation, true)).toEqual(['Vera X', 'Uprava X']);
    expect(counterpartNames(conversation, false)).toEqual(['Ana X']);
  });
});

describe('chronological', () => {
  it('orders oldest first with id as the tie breaker', () => {
    const ordered = chronological([message('b', '2026-09-28T10:00:00Z'), message('c', '2026-09-28T09:00:00Z'), message('a', '2026-09-28T10:00:00Z')]);
    expect(ordered.map((m) => m.id)).toEqual(['c', 'a', 'b']);
  });
});

describe('readPositionToSend', () => {
  const ordered = [message('1', '2026-09-28T09:00:00Z'), message('2', '2026-09-28T10:00:00Z')];

  it('returns the newest message only when something is unread and the position differs', () => {
    expect(readPositionToSend({ unreadCount: 1, lastReadMessageId: '1' }, ordered)).toBe('2');
    expect(readPositionToSend({ unreadCount: 0, lastReadMessageId: '1' }, ordered)).toBeNull();
    expect(readPositionToSend({ unreadCount: 1, lastReadMessageId: '2' }, ordered)).toBeNull();
    expect(readPositionToSend({ unreadCount: 3, lastReadMessageId: null }, [])).toBeNull();
  });
});

describe('composer', () => {
  it('sends on Enter only', () => {
    expect(composerKeyAction('Enter', false, false)).toBe('send');
    expect(composerKeyAction('Enter', true, false)).toBe('none');
    expect(composerKeyAction('Enter', false, true)).toBe('none');
    expect(composerKeyAction('a', false, false)).toBe('none');
  });

  it('accepts 1..4000 non-blank characters', () => {
    expect(isSendable('  ')).toBe(false);
    expect(isSendable('Zdravo')).toBe(true);
    expect(isSendable('a'.repeat(4000))).toBe(true);
    expect(isSendable('a'.repeat(4001))).toBe(false);
  });
});
