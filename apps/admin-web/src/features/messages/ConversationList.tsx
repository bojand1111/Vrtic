import { useTranslation } from 'react-i18next';

import { useFormat } from '../../app/format';
import { Badge } from '../../components/Page';
import { counterpartNames } from './helpers';
import type { Conversation } from './types';

interface Props {
  readonly conversations: readonly Conversation[];
  readonly selectedId: string | null;
  readonly viewerIsParent: boolean;
  readonly onSelect: (id: string) => void;
}

/** Conversation list: child, other side, kind, last activity, preview and unread badge. */
export function ConversationList({ conversations, selectedId, viewerIsParent, onSelect }: Props) {
  const { t } = useTranslation();
  const format = useFormat();
  return (
    <ul className="vc-list vc-conv-list" aria-label={t('messages.conversations')}>
      {conversations.map((c) => {
        const others = counterpartNames(c, viewerIsParent);
        const selected = c.id === selectedId;
        return (
          <li key={c.id}>
            <button
              type="button"
              className={selected ? 'vc-conv-item vc-conv-item--selected' : 'vc-conv-item'}
              aria-current={selected ? 'true' : undefined}
              onClick={() => {
                onSelect(c.id);
              }}
            >
              <span className="vc-conv-line">
                <span className={c.unreadCount > 0 ? 'vc-conv-child vc-conv-child--unread' : 'vc-conv-child'}>
                  {c.childGivenName} {c.childFamilyName}
                </span>
                <span className="vc-conv-time">{format.dateTime(c.lastMessageAt ?? c.createdAt)}</span>
              </span>
              <span className="vc-conv-line">
                <span className="vc-muted">
                  {t(`messages.kinds.${c.kind}`)}
                  {others.length > 0 ? ` · ${others.join(', ')}` : ''}
                </span>
                {c.unreadCount > 0 ? <Badge tone="info">{t('messages.unread', { count: c.unreadCount })}</Badge> : null}
              </span>
              {(c.subject ?? '') !== '' ? <span className="vc-conv-subject">{c.subject}</span> : null}
              {(c.lastMessagePreview ?? '') !== '' ? <span className="vc-conv-preview">{c.lastMessagePreview}</span> : null}
            </button>
          </li>
        );
      })}
    </ul>
  );
}
