import { type KeyboardEvent, type SubmitEvent, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrgMutation } from '../../api/org';
import { fieldError } from '../../api/problem';
import { useFormat } from '../../app/format';
import { Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { chronological, composerKeyAction, isSendable, MAX_MESSAGE_LENGTH, participantName, readPositionToSend } from './helpers';
import type { ApiPage, Conversation, Message } from './types';
import { useOrgPoll } from './useOrgPoll';

/** The open thread is refreshed every 15 s while shown. */
const THREAD_POLL_MS = 15_000;

interface Props {
  readonly conversation: Conversation;
  readonly onBack: () => void;
}

/** Messages (oldest first, plain text), participants and the composer (Enter sends, Shift+Enter new line). */
export function ConversationThread({ conversation, onBack }: Props) {
  const { t } = useTranslation();
  const format = useFormat();
  const messages = useOrgPoll<ApiPage<Message>>(['conversations', conversation.id, 'messages'], `/conversations/${conversation.id}/messages?limit=100`, THREAD_POLL_MS);
  const ordered = useMemo(() => chronological(messages.data?.items ?? []), [messages.data]);
  const names = useMemo(() => new Map(conversation.participants.map((p) => [p.membershipId, participantName(p)])), [conversation.participants]);
  const readPosition = useOrgMutation(['conversations']);
  const send = useOrgMutation<Message>(['conversations']);
  const [draft, setDraft] = useState('');
  const [clientMessageId, setClientMessageId] = useState(() => crypto.randomUUID());
  const boxRef = useRef<HTMLDivElement | null>(null);
  const sentPosition = useRef<string | null>(null);

  // Mark as read what is on screen (monotonic on the server; sent once per newest message).
  const toSend = readPositionToSend(conversation, ordered);
  const { mutate: sendReadPosition } = readPosition;
  useEffect(() => {
    if (toSend !== null && sentPosition.current !== toSend) {
      sentPosition.current = toSend;
      sendReadPosition({ method: 'PUT', path: `/conversations/${conversation.id}/read-position`, body: { lastReadMessageId: toSend } });
    }
  }, [toSend, conversation.id, sendReadPosition]);

  // Keep the newest message in view when one arrives.
  const newestId = ordered.at(-1)?.id;
  useEffect(() => {
    const box = boxRef.current;
    if (newestId !== undefined && box !== null) {
      box.scrollTop = box.scrollHeight;
    }
  }, [newestId]);

  const closed = (conversation.closedAt ?? null) !== null;
  const submit = () => {
    if (!isSendable(draft) || send.isPending || closed) {
      return;
    }
    // The same clientMessageId is reused until the send succeeds, so a retry never duplicates the message.
    send.mutate(
      { method: 'POST', path: `/conversations/${conversation.id}/messages`, body: { clientMessageId, body: draft } },
      {
        onSuccess: () => {
          setDraft('');
          setClientMessageId(crypto.randomUUID());
        },
      },
    );
  };
  const onSubmit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    submit();
  };
  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (composerKeyAction(e.key, e.shiftKey, e.nativeEvent.isComposing) === 'send') {
      e.preventDefault();
      submit();
    }
  };

  const active = conversation.participants.filter((p) => (p.leftAt ?? null) === null);
  const former = conversation.participants.filter((p) => (p.leftAt ?? null) !== null);

  return (
    <section className="vc-thread" aria-labelledby="vc-thread-title">
      <header className="vc-thread-header">
        <button type="button" className="vc-button vc-button--small vc-button--ghost vc-thread-back" onClick={onBack}>
          {t('messages.back')}
        </button>
        <h2 id="vc-thread-title">
          {conversation.childGivenName} {conversation.childFamilyName} · {t(`messages.kinds.${conversation.kind}`)}
        </h2>
        {(conversation.subject ?? '') !== '' ? <p className="vc-thread-subject">{conversation.subject}</p> : null}
        <p className="vc-muted vc-thread-participants" title={t('messages.participantsHint')}>
          {t('messages.participants')}: {active.map((p) => `${participantName(p)} (${t(`messages.roles.${p.participantRole}`)})`).join(', ')}
          {former.length > 0 ? ` · ${former.map((p) => `${participantName(p)} (${t('messages.formerParticipant')})`).join(', ')}` : ''}
        </p>
      </header>

      <ProblemAlert error={messages.error ?? readPosition.error} />
      <div className="vc-thread-messages" aria-live="polite" ref={boxRef}>
        {messages.isPending ? <Loading /> : null}
        {messages.data !== undefined && ordered.length === 0 ? <p className="vc-muted">{t('messages.noMessages')}</p> : null}
        {ordered.map((m) => (
          <article key={m.id} className={m.isOwn ? 'vc-msg vc-msg--own' : 'vc-msg'}>
            <header className="vc-msg-meta">
              <strong>{m.isOwn ? t('messages.you') : (names.get(m.senderMembershipId) ?? '')}</strong> · {format.dateTime(m.createdAt)}
            </header>
            {(m.deletedAt ?? null) !== null ? <p className="vc-muted">{t('messages.deleted')}</p> : <p className="vc-msg-body">{m.body}</p>}
          </article>
        ))}
      </div>

      {closed ? (
        <p className="vc-muted">{t('messages.closed')}</p>
      ) : (
        <form className="vc-composer" onSubmit={onSubmit} noValidate>
          <ProblemAlert error={send.error} />
          <label htmlFor="vc-composer-input" className="vc-visually-hidden">
            {t('messages.composerLabel')}
          </label>
          <textarea
            id="vc-composer-input"
            rows={3}
            value={draft}
            maxLength={MAX_MESSAGE_LENGTH}
            placeholder={t('messages.composerLabel')}
            aria-describedby="vc-composer-hint"
            aria-invalid={fieldError(send.error, 'body') === undefined ? undefined : 'true'}
            onChange={(e) => {
              setDraft(e.target.value);
            }}
            onKeyDown={onKeyDown}
          />
          <div className="vc-composer-footer">
            <small id="vc-composer-hint" className="vc-muted">
              {t('messages.composerHint')} {t('messages.length', { count: draft.length })}
            </small>
            <button type="submit" className="vc-button vc-button--primary" disabled={!isSendable(draft) || send.isPending}>
              {send.isPending ? t('messages.sending') : t('messages.send')}
            </button>
          </div>
        </form>
      )}
    </section>
  );
}
