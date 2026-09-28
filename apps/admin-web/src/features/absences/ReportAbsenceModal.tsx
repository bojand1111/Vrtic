import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrgMutation } from '../../api/org';
import { fieldError } from '../../api/problem';
import { todayIso } from '../../app/format';
import { FieldRow, InputField, type SelectOption, SelectField, TextAreaField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { ProblemAlert } from '../../components/ProblemAlert';
import { ABSENCE_KINDS } from './types';

interface Props {
  readonly childOptions: readonly SelectOption[];
  readonly initialChildId: string;
  readonly onClose: () => void;
}

/** "Report absence": child, kind, period (inclusive, at most 365 days) and an optional non-medical note. */
export function ReportAbsenceModal({ childOptions, initialChildId, onClose }: Props) {
  const { t } = useTranslation();
  const [childId, setChildId] = useState(initialChildId);
  const [kind, setKind] = useState('SICK');
  const [dateFrom, setDateFrom] = useState(todayIso());
  const [dateTo, setDateTo] = useState(todayIso());
  const [note, setNote] = useState('');
  const report = useOrgMutation(['absences', 'schedules']);

  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    report.mutate(
      { method: 'POST', path: '/absences', body: { childId, kind, dateFrom, dateTo, ...(note.trim() === '' ? {} : { note }) } },
      { onSuccess: onClose },
    );
  };
  const err = (field: string) => fieldError(report.error, field);

  return (
    <Modal title={t('absences.report')} open onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <ProblemAlert error={report.error} />
        <SelectField id="absence-child" label={t('absences.child')} value={childId} onChange={setChildId} options={childOptions} emptyLabel={t('absences.selectChild')} required error={err('childId')} />
        <SelectField id="absence-kind" label={t('absences.kind')} value={kind} onChange={setKind} options={ABSENCE_KINDS.map((k) => ({ value: k, label: t(`absences.kinds.${k}`) }))} required error={err('kind')} />
        <FieldRow>
          <InputField
            id="absence-from"
            type="date"
            label={t('ui.from')}
            value={dateFrom}
            onChange={(v) => {
              setDateFrom(v);
              if (dateTo < v) {
                setDateTo(v);
              }
            }}
            required
            error={err('dateFrom')}
          />
          <InputField id="absence-to" type="date" label={t('ui.to')} value={dateTo} onChange={setDateTo} min={dateFrom} required error={err('dateTo')} />
        </FieldRow>
        <TextAreaField id="absence-note" label={t('ui.note')} hint={t('absences.noteHint')} value={note} onChange={setNote} rows={2} error={err('note')} />
        <footer className="vc-modal-footer">
          <button type="button" className="vc-button" onClick={onClose}>
            {t('ui.cancel')}
          </button>
          <button type="submit" className="vc-button vc-button--primary" disabled={report.isPending || childId === ''}>
            {report.isPending ? t('ui.saving') : t('absences.submit')}
          </button>
        </footer>
      </form>
    </Modal>
  );
}
