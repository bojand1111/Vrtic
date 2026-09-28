import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg, useOrgQuery } from '../../api/org';
import { fieldError } from '../../api/problem';
import { todayIso, useFormat } from '../../app/format';
import { Alert } from '../../components/Alert';
import { CheckboxField, FieldRow, InputField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { Badge, Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { addDays, mondayOf, type ScheduleRules } from '../children/helpers';
import { useVersionedMutation } from '../children/useVersionedMutation';
import type { ScheduleDay, WeekSchedule } from './types';
import { isPastDeadline, type OverrideDraft, overrideBody, overrideDraftOf, shiftWeek, validateOverride } from './week';

function weekdayKey(w: number) {
  return `schedules.weekday.${String(w)}` as `schedules.weekday.${'1' | '2' | '3' | '4' | '5' | '6' | '7'}`;
}

/** Source badge of a computed day (closure / absence / override / template / none). */
export function SourceBadge({ source }: { readonly source: ScheduleDay['source'] }) {
  const { t } = useTranslation();
  const tone = source === 'CLOSURE' ? 'danger' : source === 'ABSENCE' ? 'warning' : source === 'OVERRIDE' ? 'info' : 'neutral';
  return <Badge tone={tone}>{t(`schedules.source.${source}`)}</Badge>;
}

/**
 * The computed week of one child (closure > absence > day override > template) with week navigation.
 * Editable days (backend `isEditable`: allowed caller, not past/frozen, not a closure, working day) can be
 * changed for that one date or reset to the weekly template.
 */
export function WeekView({ childId }: { readonly childId: string }) {
  const { t } = useTranslation();
  const format = useFormat();
  const org = useOrg();
  const [weekStart, setWeekStart] = useState(() => mondayOf(todayIso()));
  const week = useOrgQuery<WeekSchedule>(['schedules', childId, 'week'], `/children/${childId}/schedule/week?weekStart=${weekStart}`);
  const settings = useOrgQuery<ScheduleRules>(['settings'], '/settings');
  const reset = useVersionedMutation(['schedules']);
  const [editing, setEditing] = useState<ScheduleDay | null>(null);
  const isManager = org.role === 'OWNER' || org.role === 'ADMIN';

  const onReset = (d: ScheduleDay) => {
    if (window.confirm(t('schedules.override.confirmReset'))) {
      reset.mutate({ method: 'DELETE', path: `/children/${childId}/schedule/overrides/${d.date}`, version: d.overrideVersion });
    }
  };

  return (
    <div>
      <h3>{t('schedules.week', { from: format.date(weekStart), to: format.date(addDays(weekStart, 6)) })}</h3>
      <div className="vc-toolbar">
        <button type="button" className="vc-button vc-button--small" onClick={() => { setWeekStart(shiftWeek(weekStart, -1)); }}>
          {t('schedules.prevWeek')}
        </button>
        <button type="button" className="vc-button vc-button--small" onClick={() => { setWeekStart(mondayOf(todayIso())); }}>
          {t('schedules.thisWeek')}
        </button>
        <button type="button" className="vc-button vc-button--small" onClick={() => { setWeekStart(shiftWeek(weekStart, 1)); }}>
          {t('schedules.nextWeek')}
        </button>
      </div>
      {week.data === undefined ? null : <p className="vc-muted">{t('schedules.override.deadlineRule', { hours: week.data.changeDeadlineHours })}</p>}
      <ProblemAlert error={week.error} />
      <ProblemAlert error={reset.error} />
      {week.isPending ? <Loading /> : null}
      {week.data === undefined ? null : (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th>{t('schedules.day')}</th>
                <th>{t('schedules.plan')}</th>
                <th>{t('schedules.sourceColumn')}</th>
                <th>{t('ui.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {week.data.days.map((d) => (
                <tr key={d.date}>
                  <td>
                    {t(weekdayKey(d.weekday))} {format.date(d.date)}
                  </td>
                  <td>{d.isExpected ? `${d.expectedArrival ?? ''}-${d.expectedDeparture ?? ''}` : <span className="vc-muted">{t('schedules.notExpected')}</span>}</td>
                  <td>
                    <SourceBadge source={d.source} />{' '}
                    {d.source === 'CLOSURE' && d.closureName != null ? <span className="vc-muted">{d.closureName} </span> : null}
                    {d.source === 'ABSENCE' && d.absenceKind != null ? <span className="vc-muted">{t(`schedules.absenceKind.${d.absenceKind}`)} </span> : null}
                    {d.overrideId != null && d.isLateChange ? <Badge tone="warning">{t('schedules.lateChange')}</Badge> : null}
                    {d.source === 'OVERRIDE' && d.overrideReason != null ? <span className="vc-muted"> {d.overrideReason}</span> : null}
                  </td>
                  <td className="vc-actions">
                    {d.isEditable ? (
                      <>
                        <button type="button" className="vc-button vc-button--small" onClick={() => { setEditing(d); }}>
                          {t('schedules.override.change')}
                        </button>
                        {d.overrideId == null ? null : (
                          <button type="button" className="vc-button vc-button--small vc-button--ghost" disabled={reset.isPending} onClick={() => { onReset(d); }}>
                            {t('schedules.override.reset')}
                          </button>
                        )}
                      </>
                    ) : d.isFrozen ? (
                      <span className="vc-muted">{t('schedules.frozen')}</span>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing === null || settings.data === undefined || week.data === undefined ? null : (
        <OverrideModal
          childId={childId}
          day={editing}
          rules={settings.data}
          deadlineHours={week.data.changeDeadlineHours}
          showLateToManager={isManager}
          onClose={() => { setEditing(null); }}
        />
      )}
    </div>
  );
}

function OverrideModal({
  childId,
  day,
  rules,
  deadlineHours,
  showLateToManager,
  onClose,
}: {
  readonly childId: string;
  readonly day: ScheduleDay;
  readonly rules: ScheduleRules;
  readonly deadlineHours: number;
  readonly showLateToManager: boolean;
  readonly onClose: () => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const [draft, setDraft] = useState<OverrideDraft>(() => overrideDraftOf(day));
  const save = useVersionedMutation(['schedules']);
  const error = validateOverride(draft, day.weekday, rules);
  // evaluated once when the dialog opens
  const [openedAt] = useState(() => Date.now());
  const late = isPastDeadline(day.changeDeadline, openedAt);
  const patch = (change: Partial<OverrideDraft>) => {
    setDraft((prev) => ({ ...prev, ...change }));
  };

  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (error !== undefined) {
      return;
    }
    save.mutate({ method: 'PUT', path: `/children/${childId}/schedule/overrides/${day.date}`, body: overrideBody(draft), version: day.overrideVersion }, { onSuccess: onClose });
  };

  return (
    <Modal
      open
      title={t('schedules.override.title', { date: format.date(day.date) })}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="vc-button" onClick={onClose}>
            {t('ui.cancel')}
          </button>
          <button type="submit" form="override-form" className="vc-button vc-button--primary" disabled={save.isPending || error !== undefined}>
            {save.isPending ? t('ui.saving') : t('ui.save')}
          </button>
        </>
      }
    >
      <form id="override-form" onSubmit={submit} noValidate>
        <ProblemAlert error={save.error} />
        {late ? (
          <Alert
            variant="info"
            title={t('schedules.override.lateTitle')}
            detail={t(showLateToManager ? 'schedules.override.lateWarningManager' : 'schedules.override.lateWarning', {
              deadline: format.dateTime(day.changeDeadline),
              hours: deadlineHours,
            })}
          />
        ) : (
          <p className="vc-muted">{t('schedules.override.deadlineHint', { deadline: format.dateTime(day.changeDeadline) })}</p>
        )}
        <p className="vc-muted">
          {day.templateAttends === true
            ? t('schedules.override.templateSays', { arrival: day.templateArrival ?? '', departure: day.templateDeparture ?? '' })
            : t('schedules.override.templateNone')}
        </p>
        <CheckboxField id="override-attends" label={t('schedules.attends')} checked={draft.attends} onChange={(v) => { patch({ attends: v }); }} />
        {draft.attends ? (
          <FieldRow>
            <InputField id="override-arrival" type="time" label={t('schedules.arrival')} value={draft.arrivalTime} min={rules.dayOpensAt} max={rules.dayClosesAt} onChange={(v) => { patch({ arrivalTime: v }); }} error={fieldError(save.error, 'arrivalTime')} />
            <InputField id="override-departure" type="time" label={t('schedules.departure')} value={draft.departureTime} min={rules.dayOpensAt} max={rules.dayClosesAt} onChange={(v) => { patch({ departureTime: v }); }} error={fieldError(save.error, 'departureTime')} />
          </FieldRow>
        ) : null}
        {error === undefined ? null : <p className="vc-cell-error">{t(`schedules.errors.${error}`, { opens: rules.dayOpensAt, closes: rules.dayClosesAt })}</p>}
        <InputField id="override-reason" label={t('schedules.override.reason')} value={draft.reason} onChange={(v) => { patch({ reason: v }); }} error={fieldError(save.error, 'reason')} />
      </form>
    </Modal>
  );
}
