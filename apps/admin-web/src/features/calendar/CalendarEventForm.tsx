import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { fieldError } from '../../api/problem';
import { CheckboxField, FieldRow, InputField, SelectField, TextAreaField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { ProblemAlert } from '../../components/ProblemAlert';
import { type NamedRef, useVersionedMutation } from '../announcements/versionedMutation';
import { type CalendarEvent, EVENT_KINDS, type EventKind } from './calendarTypes';

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

/** Browser-local 'HH:MM' of an instant. */
function localTime(iso: string | null | undefined): string {
  if (iso == null) {
    return '';
  }
  const d = new Date(iso);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

interface CalendarEventFormProps {
  readonly event: CalendarEvent | null;
  readonly defaultDate: string;
  readonly groups: readonly NamedRef[];
  readonly locations: readonly NamedRef[];
  readonly onClose: () => void;
}

/** CALENDAR_MANAGE: create or edit an event (all-day date range, or a timed event on one day). */
export function CalendarEventForm({ event, defaultDate, groups, locations, onClose }: CalendarEventFormProps) {
  const { t } = useTranslation();
  const mutation = useVersionedMutation<CalendarEvent>(['calendar']);
  const [kind, setKind] = useState<EventKind>(event?.kind ?? 'OTHER');
  const [title, setTitle] = useState(event?.title ?? '');
  const [description, setDescription] = useState(event?.description ?? '');
  const [allDay, setAllDay] = useState(event?.allDay ?? true);
  const [startsOn, setStartsOn] = useState(event?.startsOn ?? defaultDate);
  const [endsOn, setEndsOn] = useState(event?.endsOn ?? defaultDate);
  const [startTime, setStartTime] = useState(localTime(event?.startsAt) || '09:00');
  const [endTime, setEndTime] = useState(localTime(event?.endsAt) || '10:00');
  const [locationId, setLocationId] = useState(event?.locationId ?? '');
  const [groupId, setGroupId] = useState(event?.groupId ?? '');

  const submit = () => {
    const timed = allDay
      ? { startsOn, endsOn, startsAt: null, endsAt: null }
      : {
          startsOn,
          endsOn: startsOn,
          startsAt: new Date(`${startsOn}T${startTime}:00`).toISOString(),
          endsAt: new Date(`${startsOn}T${endTime}:00`).toISOString(),
        };
    const body = {
      kind,
      title: title.trim(),
      description: description.trim() === '' ? null : description,
      allDay,
      locationId: groupId !== '' ? null : locationId === '' ? null : locationId,
      groupId: groupId === '' ? null : groupId,
      ...timed,
    };
    mutation.mutate(
      event === null
        ? { method: 'POST', path: '/calendar-events', body: Object.fromEntries(Object.entries(body).filter(([, v]) => v !== null)) }
        : { method: 'PATCH', path: `/calendar-events/${event.id}`, body, version: event.version },
      { onSuccess: onClose },
    );
  };

  return (
    <Modal
      title={event === null ? t('calendar.new') : t('calendar.edit')}
      open
      onClose={onClose}
      footer={
        <>
          <button type="button" className="vc-button" onClick={onClose}>
            {t('ui.cancel')}
          </button>
          <button type="button" className="vc-button vc-button--primary" disabled={mutation.isPending || title.trim() === ''} onClick={submit}>
            {mutation.isPending ? t('ui.saving') : t('ui.save')}
          </button>
        </>
      }
    >
      <FieldRow>
        <SelectField
          id="event-kind"
          label={t('calendar.fields.kind')}
          value={kind}
          onChange={(v) => { setKind(EVENT_KINDS.find((k) => k === v) ?? 'OTHER'); }}
          options={EVENT_KINDS.map((k) => ({ value: k, label: t(`calendar.kinds.${k}`) }))}
        />
        <InputField id="event-title" label={t('calendar.fields.title')} value={title} onChange={setTitle} required error={fieldError(mutation.error, 'title')} />
      </FieldRow>
      <TextAreaField id="event-description" label={t('calendar.fields.description')} value={description} onChange={setDescription} rows={3} />
      <CheckboxField id="event-all-day" label={t('calendar.fields.allDay')} checked={allDay} onChange={setAllDay} />
      {allDay ? (
        <FieldRow>
          <InputField id="event-starts-on" type="date" label={t('ui.from')} value={startsOn} onChange={setStartsOn} required error={fieldError(mutation.error, 'startsOn')} />
          <InputField id="event-ends-on" type="date" label={t('ui.to')} value={endsOn} min={startsOn} onChange={setEndsOn} required error={fieldError(mutation.error, 'endsOn')} />
        </FieldRow>
      ) : (
        <FieldRow>
          <InputField id="event-date" type="date" label={t('ui.date')} value={startsOn} onChange={setStartsOn} required />
          <InputField id="event-start" type="time" label={t('calendar.fields.startTime')} value={startTime} onChange={setStartTime} required error={fieldError(mutation.error, 'startsAt')} />
          <InputField id="event-end" type="time" label={t('calendar.fields.endTime')} value={endTime} onChange={setEndTime} required error={fieldError(mutation.error, 'endsAt')} />
        </FieldRow>
      )}
      <FieldRow>
        <SelectField
          id="event-location"
          label={t('calendar.fields.location')}
          value={groupId !== '' ? '' : locationId}
          onChange={setLocationId}
          disabled={groupId !== ''}
          emptyLabel={t('calendar.wholeOrganization')}
          options={locations.map((l) => ({ value: l.id, label: l.name }))}
        />
        <SelectField
          id="event-group"
          label={t('calendar.fields.group')}
          value={groupId}
          onChange={setGroupId}
          emptyLabel={t('calendar.noGroup')}
          options={groups.map((g) => ({ value: g.id, label: g.name }))}
        />
      </FieldRow>
      <ProblemAlert error={mutation.error} />
    </Modal>
  );
}
