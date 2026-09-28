import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrgMutation, useOrgQuery } from '../../api/org';
import { fieldError } from '../../api/problem';
import { todayIso, useFormat } from '../../app/format';
import { InputField } from '../../components/Form';
import { Badge, Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { addDays, mondayOf, type ScheduleRules, type TemplateDayDraft, validateTemplateDays } from '../children/helpers';
import type { ScheduleTemplate, TemplateDay, WeekSchedule } from './types';

const WEEKDAYS = [1, 2, 3, 4, 5, 6, 7] as const;

function weekdayKey(w: number) {
  return `schedules.weekday.${String(w)}` as `schedules.weekday.${'1' | '2' | '3' | '4' | '5' | '6' | '7'}`;
}

/**
 * Weekly schedule of one child: current template, upcoming/previous templates, the computed week
 * (template + absences) and, when allowed, an editor that replaces the template from a date.
 */
export function WeeklyScheduleEditor({ childId, canEdit }: { readonly childId: string; readonly canEdit: boolean }) {
  const { t } = useTranslation();
  const format = useFormat();
  const templates = useOrgQuery<{ readonly items: readonly ScheduleTemplate[] }>(['schedules', childId], `/children/${childId}/schedule/templates`);
  const settings = useOrgQuery<ScheduleRules>(['settings'], '/settings');
  const [editing, setEditing] = useState(false);
  const today = todayIso();
  const items = templates.data?.items ?? [];
  const current = items.find((tpl) => tpl.effectiveFrom <= today && (tpl.effectiveTo === undefined || tpl.effectiveTo === null || tpl.effectiveTo >= today));
  const upcoming = items.filter((tpl) => tpl.effectiveFrom > today);
  const previous = items.filter((tpl) => tpl.effectiveTo !== undefined && tpl.effectiveTo !== null && tpl.effectiveTo < today);

  const period = (tpl: ScheduleTemplate) =>
    tpl.effectiveTo === undefined || tpl.effectiveTo === null
      ? t('schedules.fromDate', { from: format.date(tpl.effectiveFrom) })
      : t('schedules.period', { from: format.date(tpl.effectiveFrom), to: format.date(tpl.effectiveTo) });

  return (
    <div className="vc-schedule">
      <ProblemAlert error={templates.error} />
      {templates.isPending ? <Loading /> : null}
      {settings.data === undefined ? null : (
        <p className="vc-muted">{t('schedules.openingHours', { opens: settings.data.dayOpensAt, closes: settings.data.dayClosesAt })}</p>
      )}
      {templates.data !== undefined && current === undefined && upcoming.length === 0 ? <p className="vc-muted">{t('schedules.noTemplate')}</p> : null}
      {current === undefined ? null : (
        <>
          <h3>
            {t('schedules.current')} <span className="vc-muted">({period(current)})</span>
          </h3>
          <TemplateTable days={current.days} />
        </>
      )}
      {upcoming.map((tpl) => (
        <div key={tpl.id}>
          <h3>
            {t('schedules.upcoming')} <span className="vc-muted">({period(tpl)})</span>
          </h3>
          <TemplateTable days={tpl.days} />
        </div>
      ))}
      {canEdit && !editing ? (
        <div className="vc-toolbar">
          <button
            type="button"
            className="vc-button vc-button--small vc-button--primary"
            onClick={() => {
              setEditing(true);
            }}
          >
            {t('schedules.change')}
          </button>
        </div>
      ) : null}
      {canEdit && editing && settings.data !== undefined ? (
        <TemplateForm
          childId={childId}
          rules={settings.data}
          initial={upcoming.at(-1)?.days ?? current?.days}
          onDone={() => {
            setEditing(false);
          }}
        />
      ) : null}
      <ProblemAlert error={settings.error} />
      {previous.length > 0 ? (
        <details>
          <summary>{t('schedules.previous')}</summary>
          <ul className="vc-list">
            {previous.map((tpl) => (
              <li key={tpl.id}>
                {period(tpl)}: {tpl.days.filter((d) => d.attends).map((d) => `${t(weekdayKey(d.weekday))} ${d.arrivalTime ?? ''}-${d.departureTime ?? ''}`).join(', ') || t('schedules.notAttending')}
              </li>
            ))}
          </ul>
        </details>
      ) : null}
      <WeekView childId={childId} />
    </div>
  );
}

function TemplateTable({ days }: { readonly days: readonly TemplateDay[] }) {
  const { t } = useTranslation();
  return (
    <div className="vc-table-wrap">
      <table className="vc-table">
        <thead>
          <tr>
            <th>{t('schedules.day')}</th>
            <th>{t('schedules.attends')}</th>
            <th>{t('schedules.arrival')}</th>
            <th>{t('schedules.departure')}</th>
          </tr>
        </thead>
        <tbody>
          {days.map((d) => (
            <tr key={d.weekday}>
              <td>{t(weekdayKey(d.weekday))}</td>
              <td>{d.attends ? t('ui.yes') : t('ui.no')}</td>
              <td>{d.attends ? (d.arrivalTime ?? '') : ''}</td>
              <td>{d.attends ? (d.departureTime ?? '') : ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function TemplateForm({ childId, rules, initial, onDone }: { readonly childId: string; readonly rules: ScheduleRules; readonly initial: readonly TemplateDay[] | undefined; readonly onDone: () => void }) {
  const { t } = useTranslation();
  const [effectiveFrom, setEffectiveFrom] = useState(todayIso());
  const [days, setDays] = useState<TemplateDayDraft[]>(() =>
    WEEKDAYS.map((w) => {
      const d = initial?.find((x) => x.weekday === w);
      const working = rules.workingWeekdays.includes(w);
      return d === undefined
        ? { weekday: w, attends: working, arrivalTime: working ? '08:00' : '', departureTime: working ? '16:00' : '' }
        : { weekday: w, attends: d.attends, arrivalTime: d.arrivalTime ?? '', departureTime: d.departureTime ?? '' };
    }),
  );
  const save = useOrgMutation(['schedules', 'children']);
  const errors = validateTemplateDays(days, rules);

  const patchDay = (weekday: number, change: Partial<TemplateDayDraft>) => {
    setDays((prev) => prev.map((d) => (d.weekday === weekday ? { ...d, ...change } : d)));
  };

  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (errors.size > 0) {
      return;
    }
    const body = {
      effectiveFrom,
      days: days.map((d) => (d.attends ? { weekday: d.weekday, attends: true, arrivalTime: d.arrivalTime, departureTime: d.departureTime } : { weekday: d.weekday, attends: false })),
    };
    save.mutate({ method: 'POST', path: `/children/${childId}/schedule/templates`, body }, { onSuccess: onDone });
  };

  return (
    <form onSubmit={submit} noValidate className="vc-section">
      <ProblemAlert error={save.error} />
      <InputField id={`tpl-from-${childId}`} type="date" label={t('schedules.effectiveFrom')} value={effectiveFrom} onChange={setEffectiveFrom} min={todayIso()} required hint={t('schedules.effectiveFromHint')} error={fieldError(save.error, 'effectiveFrom')} />
      <div className="vc-table-wrap">
        <table className="vc-table vc-schedule-grid">
          <thead>
            <tr>
              <th>{t('schedules.day')}</th>
              <th>{t('schedules.attends')}</th>
              <th>{t('schedules.arrival')}</th>
              <th>{t('schedules.departure')}</th>
            </tr>
          </thead>
          <tbody>
            {days.map((d, i) => {
              const error = errors.get(d.weekday);
              const serverError = fieldError(save.error, `days[${String(i)}].arrivalTime`) ?? fieldError(save.error, `days[${String(i)}].departureTime`) ?? fieldError(save.error, `days[${String(i)}].attends`);
              return (
                <tr key={d.weekday}>
                  <td>{t(weekdayKey(d.weekday))}</td>
                  <td>
                    <input
                      type="checkbox"
                      aria-label={`${t(weekdayKey(d.weekday))}: ${t('schedules.attends')}`}
                      checked={d.attends}
                      disabled={!d.attends && !rules.workingWeekdays.includes(d.weekday)}
                      onChange={(e) => {
                        patchDay(d.weekday, e.target.checked ? { attends: true, arrivalTime: d.arrivalTime || '08:00', departureTime: d.departureTime || '16:00' } : { attends: false });
                      }}
                    />
                  </td>
                  <td>
                    <input
                      type="time"
                      aria-label={`${t(weekdayKey(d.weekday))}: ${t('schedules.arrival')}`}
                      value={d.arrivalTime}
                      disabled={!d.attends}
                      min={rules.dayOpensAt}
                      max={rules.dayClosesAt}
                      onChange={(e) => {
                        patchDay(d.weekday, { arrivalTime: e.target.value });
                      }}
                    />
                  </td>
                  <td>
                    <input
                      type="time"
                      aria-label={`${t(weekdayKey(d.weekday))}: ${t('schedules.departure')}`}
                      value={d.departureTime}
                      disabled={!d.attends}
                      min={rules.dayOpensAt}
                      max={rules.dayClosesAt}
                      onChange={(e) => {
                        patchDay(d.weekday, { departureTime: e.target.value });
                      }}
                    />
                    {error === undefined ? null : <div className="vc-cell-error">{t(`schedules.errors.${error}`, { opens: rules.dayOpensAt, closes: rules.dayClosesAt })}</div>}
                    {error === undefined && serverError !== undefined ? <div className="vc-cell-error">{serverError}</div> : null}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <div className="vc-toolbar">
        <button type="button" className="vc-button" onClick={onDone}>
          {t('ui.cancel')}
        </button>
        <button type="submit" className="vc-button vc-button--primary" disabled={save.isPending || errors.size > 0}>
          {save.isPending ? t('ui.saving') : t('schedules.save')}
        </button>
      </div>
    </form>
  );
}

/** The computed week (template + absences) with navigation by week. */
function WeekView({ childId }: { readonly childId: string }) {
  const { t } = useTranslation();
  const format = useFormat();
  const [weekStart, setWeekStart] = useState(() => mondayOf(todayIso()));
  const week = useOrgQuery<WeekSchedule>(['schedules', childId, 'week'], `/children/${childId}/schedule/week?weekStart=${weekStart}`);

  return (
    <div>
      <h3>{t('schedules.week', { from: format.date(weekStart), to: format.date(addDays(weekStart, 6)) })}</h3>
      <div className="vc-toolbar">
        <button
          type="button"
          className="vc-button vc-button--small"
          onClick={() => {
            setWeekStart(addDays(weekStart, -7));
          }}
        >
          {t('schedules.prevWeek')}
        </button>
        <button
          type="button"
          className="vc-button vc-button--small"
          onClick={() => {
            setWeekStart(mondayOf(todayIso()));
          }}
        >
          {t('schedules.thisWeek')}
        </button>
        <button
          type="button"
          className="vc-button vc-button--small"
          onClick={() => {
            setWeekStart(addDays(weekStart, 7));
          }}
        >
          {t('schedules.nextWeek')}
        </button>
      </div>
      <ProblemAlert error={week.error} />
      {week.isPending ? <Loading /> : null}
      {week.data === undefined ? null : (
        <ul className="vc-list">
          {week.data.days.map((d) => (
            <li key={d.date}>
              <strong>
                {t(weekdayKey(d.weekday))} {format.date(d.date)}
              </strong>
              {': '}
              {d.source === 'ABSENCE' ? (
                <Badge tone="warning">{t('schedules.source.ABSENCE')}</Badge>
              ) : d.isExpected ? (
                `${d.expectedArrival ?? ''}-${d.expectedDeparture ?? ''}`
              ) : (
                <span className="vc-muted">{t('schedules.notExpected')}</span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
