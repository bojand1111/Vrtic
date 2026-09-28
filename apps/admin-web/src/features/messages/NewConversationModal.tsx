import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg, useOrgMutation, useOrgQuery } from '../../api/org';
import { fieldError } from '../../api/problem';
import { InputField, SelectField, TextAreaField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { ProblemAlert } from '../../components/ProblemAlert';
import { type ChildSummary, childName } from '../children/types';
import { isSendable } from './helpers';
import { type ApiPage, type Conversation, CONVERSATION_KINDS, type ConversationKind } from './types';

interface Props {
  readonly onClose: () => void;
  readonly onCreated: (conversationId: string) => void;
}

/**
 * "New message": a child in the caller's scope (own children for a parent, group children for a teacher, all for
 * managers) and the recipient side. Participants are chosen by the server, never here.
 * Teachers only write to the group's parents (PARENT_TEACHER); an existing open conversation is reused.
 */
export function NewConversationModal({ onClose, onCreated }: Props) {
  const { t } = useTranslation();
  const { role } = useOrg();
  const kinds: readonly ConversationKind[] = role === 'TEACHER' ? ['PARENT_TEACHER'] : CONVERSATION_KINDS;
  const children = useOrgQuery<ApiPage<ChildSummary>>(['children'], '/children?limit=100');
  const [childId, setChildId] = useState('');
  const [kind, setKind] = useState<ConversationKind>(kinds[0] ?? 'PARENT_TEACHER');
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [clientMessageId] = useState(() => crypto.randomUUID());
  const create = useOrgMutation<Conversation>(['conversations']);
  const err = (field: string) => fieldError(create.error, field);

  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    create.mutate(
      {
        method: 'POST',
        path: '/conversations',
        body: { kind, childId, ...(subject.trim() === '' ? {} : { subject: subject.trim() }), initialMessage: { clientMessageId, body } },
      },
      {
        onSuccess: (conversation) => {
          onCreated(conversation.id);
        },
      },
    );
  };

  const childOptions = (children.data?.items ?? []).map((c) => ({ value: c.id, label: childName(c) }));

  return (
    <Modal title={t('messages.newConversation')} open onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <ProblemAlert error={create.error ?? children.error} />
        <SelectField
          id="conv-child"
          label={t('messages.child')}
          value={childId}
          onChange={setChildId}
          options={childOptions}
          emptyLabel={t('messages.selectChild')}
          required
          error={err('childId')}
        />
        <SelectField
          id="conv-kind"
          label={t('messages.kind')}
          value={kind}
          onChange={(v) => {
            const next = kinds.find((k) => k === v);
            if (next !== undefined) {
              setKind(next);
            }
          }}
          options={kinds.map((k) => ({ value: k, label: t(`messages.kinds.${k}`) }))}
          required
          error={err('kind')}
          hint={t('messages.participantsHint')}
        />
        <InputField id="conv-subject" label={t('messages.subject')} value={subject} onChange={setSubject} error={err('subject')} />
        <TextAreaField
          id="conv-body"
          label={t('messages.firstMessage')}
          value={body}
          onChange={setBody}
          rows={4}
          required
          error={err('initialMessage.body')}
          hint={t('messages.length', { count: body.length })}
        />
        <footer className="vc-modal-footer">
          <button type="button" className="vc-button" onClick={onClose}>
            {t('ui.cancel')}
          </button>
          <button type="submit" className="vc-button vc-button--primary" disabled={create.isPending || childId === '' || !isSendable(body)}>
            {create.isPending ? t('messages.sending') : t('messages.start')}
          </button>
        </footer>
      </form>
    </Modal>
  );
}
