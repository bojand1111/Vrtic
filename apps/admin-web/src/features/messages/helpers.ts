import type { Conversation, ConversationParticipant, Message } from './types';

/** "Given Family" of a participant. */
export function participantName(p: Pick<ConversationParticipant, 'givenName' | 'familyName'>): string {
  return `${p.givenName} ${p.familyName}`.trim();
}

/**
 * Names of the other side of a conversation (active participants only): staff for a parent, guardians for staff.
 * The viewer's own side is recognised by the role, so no membership id is needed on the client.
 */
export function counterpartNames(conversation: Pick<Conversation, 'participants'>, viewerIsParent: boolean): string[] {
  return conversation.participants
    .filter((p) => (p.leftAt ?? null) === null)
    .filter((p) => (viewerIsParent ? p.participantRole !== 'GUARDIAN' : p.participantRole === 'GUARDIAN'))
    .map(participantName);
}

/** Messages oldest first (the API pages newest first); ties broken by id like the server. */
export function chronological(messages: readonly Message[]): Message[] {
  return [...messages].sort((a, b) => {
    const t = Date.parse(a.createdAt) - Date.parse(b.createdAt);
    if (t !== 0) {
      return t;
    }
    return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
  });
}

/**
 * Id to send as the new read position after the thread was shown, or null when nothing changes:
 * the newest message, when it is not the stored position and there is something unread.
 */
export function readPositionToSend(conversation: Pick<Conversation, 'unreadCount' | 'lastReadMessageId'>, ordered: readonly Message[]): string | null {
  const newest = ordered.at(-1);
  if (newest === undefined || conversation.unreadCount === 0) {
    return null;
  }
  return newest.id === (conversation.lastReadMessageId ?? null) ? null : newest.id;
}

/** Enter sends, Shift+Enter (or an IME composition) keeps typing. */
export function composerKeyAction(key: string, shiftKey: boolean, isComposing: boolean): 'send' | 'none' {
  return key === 'Enter' && !shiftKey && !isComposing ? 'send' : 'none';
}

export const MAX_MESSAGE_LENGTH = 4000;

/** Body the API accepts: 1..4000 characters and not only whitespace. */
export function isSendable(body: string): boolean {
  return body.trim().length > 0 && body.length <= MAX_MESSAGE_LENGTH;
}
