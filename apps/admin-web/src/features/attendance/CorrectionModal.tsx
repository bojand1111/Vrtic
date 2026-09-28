import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrgMutation, useOrgQuery } from '../../api/org';
import { fieldError } from '../../api/problem';
import { useFormat } from '../../app/format';
import { InputField, SelectField, TextAreaField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { type AttendanceDay, attendanceCommand, type DailyOverviewChild, localDateTimeToIso } from './attendanceTypes';

interface CorrectionModalProps {
  readonly child: DailyOverviewChild;
  readonly date: string;
  readonly onClose: () => void;
}

/** ATTENDANCE_CORRECT: re-time or void a recorded check-in/check-out; the reason is mandatory. */
export function CorrectionModal({ child, date, onClose }: CorrectionModalProps) {
  const { t } = useTranslation();
  const fmt = useFormat();
  const day = useOrgQuery<AttendanceDay>(['attendance', 'day'], `/children/${child.childId}/attendance/days/${date}`);
  const mutation = useOrgMutation(['attendance']);
  const [eventId, setEventId] = useState('');
  const [mode, setMode] = useState<'retime' | 'void'>('retime');
  const [time, setTime] = useState('');
  const [reason, setReason] = useState('');

  const events = (day.data?.visits ?? []).flatMap((v) => {
    const list = [{ id: v.checkInEventId, label: t('attendance.correction.eventCheckIn', { time: fmt.time(v.checkInAt), n: v.sequenceNo }) }];
    if (v.checkOutEventId != null && v.checkOutAt != null) {
      list.push({ id: v.checkOutEventId, label: t('attendance.correction.eventCheckOut', { time: fmt.time(v.checkOutAt), n: v.sequenceNo }) });
    }
    return list;
  });
  const selected = eventId !== '' ? eventId : (events[0]?.id ?? '');
  const reasonTooShort = reason.trim().length < 3;
  const timeMissing = mode === 'retime' && time === '';

  const submit = () => {
    if (day.data === undefined || selected === '' || reasonTooShort || timeMissing) {
      return;
    }
    const extra: Record<string, unknown> = { correctionOfEventId: selected, reason: reason.trim(), voidEvent: mode === 'void' };
    if (mode === 'retime') {
      extra.correctedOccurredAt = localDateTimeToIso(date, time);
    }
    mutation.mutate(
      { method: 'POST', path: `/children/${child.childId}/attendance/correction`, body: attendanceCommand(day.data.version, date, extra) },
      { onSuccess: onClose },
    );
  };

  return (
    <Modal
      title={`${t('attendance.correction.title')}: ${child.givenName} ${child.familyName}`}
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
            disabled={mutation.isPending || events.length === 0 || reasonTooShort || timeMissing}
            onClick={submit}
          >
            {mutation.isPending ? t('ui.saving') : t('attendance.correction.submit')}
          </button>
        </>
      }
    >
      {day.isPending ? <Loading /> : null}
      {day.isError ? <ProblemAlert error={day.error} /> : null}
      {day.data !== undefined && events.length === 0 ? <p className="vc-muted">{t('attendance.correction.noEvents')}</p> : null}
      {events.length === 0 ? null : (
        <>
          <SelectField
            id="correction-event"
            label={t('attendance.correction.event')}
            value={selected}
            onChange={setEventId}
            options={events.map((e) => ({ value: e.id, label: e.label }))}
          />
          <SelectField
            id="correction-mode"
            label={t('attendance.correction.mode')}
            value={mode}
            onChange={(v) => { setMode(v === 'void' ? 'void' : 'retime'); }}
            options={[
              { value: 'retime', label: t('attendance.correction.retime') },
              { value: 'void', label: t('attendance.correction.void') },
            ]}
          />
          {mode === 'retime' ? (
            <InputField
              id="correction-time"
              type="time"
              label={t('attendance.correction.newTime')}
              value={time}
              onChange={setTime}
              required
              error={fieldError(mutation.error, 'correctedOccurredAt')}
            />
          ) : null}
          <TextAreaField
            id="correction-reason"
            label={t('attendance.correction.reason')}
            hint={t('attendance.correction.reasonHint')}
            value={reason}
            onChange={setReason}
            required
            rows={3}
            error={fieldError(mutation.error, 'reason')}
          />
        </>
      )}
      <ProblemAlert error={mutation.error} />
    </Modal>
  );
}
