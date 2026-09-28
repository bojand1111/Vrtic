import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg } from '../../api/org';
import { fieldError } from '../../api/problem';
import { todayIso, useFormat } from '../../app/format';
import { FieldRow, InputField, TextAreaField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { Badge } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { Age } from './Age';
import type { ChildDetail } from './types';
import { useVersionedMutation } from './useVersionedMutation';

/** Basic data; managers edit it (optimistic `version` via If-Match) and (de)activate the child. */
export function BasicDataSection({ child }: { readonly child: ChildDetail }) {
  const { t } = useTranslation();
  const org = useOrg();
  const format = useFormat();
  const canManage = org.can('CHILD_MANAGE');
  const [editing, setEditing] = useState(false);
  const status = useVersionedMutation<ChildDetail>(['children']);

  const toggleStatus = () => {
    const next = child.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
    if (next === 'INACTIVE' && !window.confirm(t('children.confirmDeactivate'))) {
      return;
    }
    status.mutate({ method: 'PATCH', path: `/children/${child.id}`, body: { status: next }, version: child.version });
  };

  return (
    <section className="vc-section">
      <h2>{t('children.sections.basic')}</h2>
      <ProblemAlert error={status.error} />
      <dl className="vc-dl">
        <dt>{t('children.fields.dateOfBirth')}</dt>
        <dd>
          {format.date(child.dateOfBirth)} (<Age dateOfBirth={child.dateOfBirth} />)
        </dd>
        <dt>{t('ui.status')}</dt>
        <dd>
          <Badge tone={child.status === 'ACTIVE' ? 'success' : 'neutral'}>{child.status === 'ACTIVE' ? t('ui.active') : t('ui.inactive')}</Badge>
        </dd>
        <dt>{t('children.fields.generalNotes')}</dt>
        <dd>{child.generalNotes ?? <span className="vc-muted">{t('ui.none')}</span>}</dd>
      </dl>
      {canManage ? (
        <div className="vc-toolbar">
          <button
            type="button"
            className="vc-button vc-button--small"
            onClick={() => {
              setEditing(true);
            }}
          >
            {t('ui.edit')}
          </button>
          <button type="button" className={`vc-button vc-button--small${child.status === 'ACTIVE' ? ' vc-button--danger' : ''}`} onClick={toggleStatus} disabled={status.isPending}>
            {child.status === 'ACTIVE' ? t('children.deactivate') : t('children.activate')}
          </button>
        </div>
      ) : null}
      {canManage ? (
        <EditChildModal
          key={`${child.id}-${String(child.version)}-${String(editing)}`}
          child={child}
          open={editing}
          onClose={() => {
            setEditing(false);
          }}
        />
      ) : null}
    </section>
  );
}

function EditChildModal({ child, open, onClose }: { readonly child: ChildDetail; readonly open: boolean; readonly onClose: () => void }) {
  const { t } = useTranslation();
  const [givenName, setGivenName] = useState(child.givenName);
  const [familyName, setFamilyName] = useState(child.familyName);
  const [dateOfBirth, setDateOfBirth] = useState(child.dateOfBirth);
  const [notes, setNotes] = useState(child.generalNotes ?? '');
  const update = useVersionedMutation<ChildDetail>(['children', 'parents', 'absences']);

  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    update.mutate(
      {
        method: 'PATCH',
        path: `/children/${child.id}`,
        version: child.version,
        body: { givenName, familyName, dateOfBirth, generalNotes: notes.trim() === '' ? null : notes },
      },
      { onSuccess: onClose },
    );
  };
  const err = (field: string) => fieldError(update.error, field);

  return (
    <Modal title={t('children.editChild')} open={open} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <ProblemAlert error={update.error} />
        <FieldRow>
          <InputField id="edit-given" label={t('children.fields.givenName')} value={givenName} onChange={setGivenName} required error={err('givenName')} />
          <InputField id="edit-family" label={t('children.fields.familyName')} value={familyName} onChange={setFamilyName} required error={err('familyName')} />
        </FieldRow>
        <InputField id="edit-dob" type="date" label={t('children.fields.dateOfBirth')} value={dateOfBirth} onChange={setDateOfBirth} max={todayIso()} required error={err('dateOfBirth')} />
        <TextAreaField id="edit-notes" label={t('children.fields.generalNotes')} hint={t('children.generalNotesHint')} value={notes} onChange={setNotes} rows={3} error={err('generalNotes')} />
        <footer className="vc-modal-footer">
          <button type="button" className="vc-button" onClick={onClose}>
            {t('ui.cancel')}
          </button>
          <button type="submit" className="vc-button vc-button--primary" disabled={update.isPending}>
            {update.isPending ? t('ui.saving') : t('ui.save')}
          </button>
        </footer>
      </form>
    </Modal>
  );
}
