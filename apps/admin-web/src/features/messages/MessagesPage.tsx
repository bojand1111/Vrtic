import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';

import { useOrg } from '../../api/org';
import { EmptyState } from '../../components/EmptyState';
import { Loading, Page } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { ConversationList } from './ConversationList';
import { ConversationThread } from './ConversationThread';
import { NewConversationModal } from './NewConversationModal';
import type { ApiPage, Conversation } from './types';
import { useOrgPoll } from './useOrgPoll';
import './messages.css';

/** The list (unread counts) refreshes every 15 s too, so new conversations and replies show up without reload. */
const LIST_POLL_MS = 15_000;

/**
 * Parent-staff messaging (EPIC 14). Every member with MESSAGE_SEND sees the conversations he takes part in:
 * parents their children's, teachers their groups' PARENT_TEACHER threads, managers the PARENT_ADMIN inbox.
 * Two panes on wide screens (list | thread), one pane on phones. `?conversation=<id>` selects a thread
 * (used by notification links).
 */
export function MessagesPage() {
  const { t } = useTranslation();
  const { can, role } = useOrg();
  const [searchParams, setSearchParams] = useSearchParams();
  const [creating, setCreating] = useState(false);
  const selectedId = searchParams.get('conversation');
  const allowed = can('MESSAGE_SEND');
  const list = useOrgPoll<ApiPage<Conversation>>(['conversations'], '/conversations?limit=100', LIST_POLL_MS, allowed);
  const conversations = list.data?.items ?? [];
  const selected = conversations.find((c) => c.id === selectedId) ?? null;

  const select = (id: string | null) => {
    const next = new URLSearchParams(searchParams);
    if (id === null) {
      next.delete('conversation');
    } else {
      next.set('conversation', id);
    }
    setSearchParams(next);
  };

  if (!allowed) {
    return (
      <Page title={t('nav.messages')}>
        <EmptyState message={t('ui.noAccess')} />
      </Page>
    );
  }

  return (
    <Page
      title={t('nav.messages')}
      actions={
        <button
          type="button"
          className="vc-button vc-button--primary"
          onClick={() => {
            setCreating(true);
          }}
        >
          {t('messages.newConversation')}
        </button>
      }
    >
      <ProblemAlert error={list.error} />
      {list.isPending ? <Loading /> : null}
      {list.data !== undefined && conversations.length === 0 ? <EmptyState message={`${t('messages.empty')} ${t('messages.emptyHint')}`} /> : null}
      {conversations.length > 0 ? (
        <div className={selected === null ? 'vc-messages' : 'vc-messages vc-messages--thread-open'}>
          <div className="vc-messages-list">
            <ConversationList conversations={conversations} selectedId={selectedId} viewerIsParent={role === 'PARENT'} onSelect={select} />
          </div>
          <div className="vc-messages-thread">
            {selected === null ? (
              <p className="vc-muted">{t('messages.selectConversation')}</p>
            ) : (
              <ConversationThread
                key={selected.id}
                conversation={selected}
                onBack={() => {
                  select(null);
                }}
              />
            )}
          </div>
        </div>
      ) : null}
      {creating ? (
        <NewConversationModal
          onClose={() => {
            setCreating(false);
          }}
          onCreated={(id) => {
            setCreating(false);
            select(id);
          }}
        />
      ) : null}
    </Page>
  );
}
