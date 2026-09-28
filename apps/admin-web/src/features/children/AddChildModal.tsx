import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrgMutation } from '../../api/org';
import { fieldError } from '../../api/problem';
import { todayIso } from '../../app/format';
import { FieldRow, InputField, type SelectOption, SelectField, TextAreaField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { ProblemAlert } from '../../components/ProblemAlert';
import type { ChildDetail } from './types';

interface Props {
  readonly open: boolean;
  readonly groups: readonly SelectOption[];
  readonly onClose: () => void;
  readonly onCreated: (childId: string) => void;
}

/** "Add child": profile plus an optional initial enrollment created in the same request. */
export function AddChildModal({ open, groups, onClose, onCreated }: Props) {
  const { t } = useTranslation();
  const [givenName, setGivenName] = useState('');
  const [familyName, setFamilyName] = useState('');
  const [dateOfBirth, setDateOfBirth] = useState('');
  const [notes, setNotes] = useState('');
  const [groupId, setGroupId] = useState('');
  const [validFrom, setValidFrom] = useState(todayIso());
  const create = useOrgMutation<ChildDetail>(['children', 'parents']);

  const reset = () => {
    setGivenName('');
    setFamilyName('');
    setDateOfBirth('');
    setNotes('');
    setGroupId('');
    setValidFrom(todayIso());
    create.reset();
  };

  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const body = {
      givenName,
      familyName,
      dateOfBirth,
      ...(notes.trim() === '' ? {} : { generalNotes: notes }),
      ...(groupId === '' ? {} : { initialEnrollment: { groupId, validFrom } }),
    };
    create.mutate(
      { method: 'POST', path: '/children', body },
      {
        onSuccess: (child) => {
          reset();
          onCreated(child.id);
        },
      },
    );
  };

  const err = (field: string) => fieldError(create.error, field);

  return (
    <Modal
      title={t('children.add')}
      open={open}
      onClose={() => {
        reset();
        onClose();
      }}
    >
      <form onSubmit={submit} noValidate>
        <ProblemAlert error={create.error} />
        <FieldRow>
          <InputField id="child-given" label={t('children.fields.givenName')} value={givenName} onChange={setGivenName} required error={err('givenName')} />
          <InputField id="child-family" label={t('children.fields.familyName')} value={familyName} onChange={setFamilyName} required error={err('familyName')} />
        </FieldRow>
        <InputField id="child-dob" type="date" label={t('children.fields.dateOfBirth')} value={dateOfBirth} onChange={setDateOfBirth} max={todayIso()} required error={err('dateOfBirth')} />
        <TextAreaField id="child-notes" label={t('children.fields.generalNotes')} hint={t('children.generalNotesHint')} value={notes} onChange={setNotes} rows={3} error={err('generalNotes')} />
        <FieldRow>
          <SelectField id="child-group" label={t('children.fields.group')} value={groupId} onChange={setGroupId} options={groups} emptyLabel={t('children.noGroup')} error={err('initialEnrollment.groupId')} />
          {groupId === '' ? null : (
            <InputField id="child-from" type="date" label={t('children.fields.startDate')} value={validFrom} onChange={setValidFrom} required error={err('initialEnrollment.validFrom')} />
          )}
        </FieldRow>
        <footer className="vc-modal-footer">
          <button type="button" className="vc-button" onClick={onClose}>
            {t('ui.cancel')}
          </button>
          <button type="submit" className="vc-button vc-button--primary" disabled={create.isPending}>
            {create.isPending ? t('ui.saving') : t('ui.save')}
          </button>
        </footer>
      </form>
    </Modal>
  );
}
