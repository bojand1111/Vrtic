import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { fieldError } from '../../api/problem';
import { CheckboxField, InputField, SelectField, TextAreaField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { ProblemAlert } from '../../components/ProblemAlert';
import type { Announcement, AnnouncementAudience } from './announcementTypes';
import { type NamedRef, useVersionedMutation } from './versionedMutation';

type EditableAudience = 'ORGANIZATION' | 'LOCATION' | 'GROUP';

interface AnnouncementFormProps {
  /** null = create a new draft. */
  readonly announcement: Announcement | null;
  readonly groups: readonly NamedRef[];
  readonly locations: readonly NamedRef[];
  readonly onClose: () => void;
}

function initialType(a: Announcement | null): EditableAudience {
  const first = a?.audiences[0]?.audienceType;
  return first === 'LOCATION' || first === 'GROUP' ? first : 'ORGANIZATION';
}

/** Create or edit a DRAFT announcement (title, plain-text body, audience). */
export function AnnouncementForm({ announcement, groups, locations, onClose }: AnnouncementFormProps) {
  const { t } = useTranslation();
  const mutation = useVersionedMutation<Announcement>(['announcements']);
  const [title, setTitle] = useState(announcement?.title ?? '');
  const [body, setBody] = useState(announcement?.body ?? '');
  const [type, setType] = useState<EditableAudience>(initialType(announcement));
  const [targets, setTargets] = useState<readonly string[]>(
    (announcement?.audiences ?? []).map((a) => a.groupId ?? a.locationId ?? '').filter((id) => id !== ''),
  );
  const options = type === 'GROUP' ? groups : type === 'LOCATION' ? locations : [];
  const needsTargets = type !== 'ORGANIZATION';
  const selectedTargets = targets.filter((id) => options.some((o) => o.id === id));

  const toggle = (id: string, checked: boolean) => {
    setTargets((prev) => (checked ? [...prev.filter((x) => x !== id), id] : prev.filter((x) => x !== id)));
  };

  const submit = () => {
    const audiences: AnnouncementAudience[] =
      type === 'ORGANIZATION'
        ? [{ audienceType: 'ORGANIZATION' }]
        : selectedTargets.map((id) => (type === 'GROUP' ? { audienceType: 'GROUP', groupId: id } : { audienceType: 'LOCATION', locationId: id }));
    const payload = { title: title.trim(), body, audiences };
    mutation.mutate(
      announcement === null
        ? { method: 'POST', path: '/announcements', body: payload }
        : { method: 'PATCH', path: `/announcements/${announcement.id}`, body: payload, version: announcement.version },
      { onSuccess: onClose },
    );
  };

  return (
    <Modal
      title={announcement === null ? t('announcements.new') : t('announcements.edit')}
      open
      onClose={onClose}
      footer={
        <>
          <button type="button" className="vc-button" onClick={onClose}>
            {t('ui.cancel')}
          </button>
          <button
            type="button"
            className="vc-button vc-button--primary"
            disabled={mutation.isPending || title.trim() === '' || body.trim() === '' || (needsTargets && selectedTargets.length === 0)}
            onClick={submit}
          >
            {mutation.isPending ? t('ui.saving') : t('ui.save')}
          </button>
        </>
      }
    >
      <InputField id="announcement-title" label={t('announcements.fields.title')} value={title} onChange={setTitle} required error={fieldError(mutation.error, 'title')} />
      <TextAreaField id="announcement-body" label={t('announcements.fields.body')} value={body} onChange={setBody} rows={8} required error={fieldError(mutation.error, 'body')} />
      <SelectField
        id="announcement-audience"
        label={t('announcements.fields.audience')}
        value={type}
        onChange={(v) => { setType(v === 'GROUP' || v === 'LOCATION' ? v : 'ORGANIZATION'); }}
        options={(['ORGANIZATION', 'LOCATION', 'GROUP'] as const).map((k) => ({ value: k, label: t(`announcements.audience.${k}`) }))}
      />
      {needsTargets ? (
        <fieldset className="vc-announcement-targets">
          <legend>{type === 'GROUP' ? t('announcements.fields.groups') : t('announcements.fields.locations')}</legend>
          {options.length === 0 ? <p className="vc-muted">{t('announcements.noTargets')}</p> : null}
          {options.map((o) => (
            <CheckboxField
              key={o.id}
              id={`announcement-target-${o.id}`}
              label={o.name}
              checked={selectedTargets.includes(o.id)}
              onChange={(checked) => { toggle(o.id, checked); }}
            />
          ))}
        </fieldset>
      ) : null}
      <ProblemAlert error={mutation.error} />
    </Modal>
  );
}
