import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg, useOrgQuery } from '../../api/org';
import { todayIso, useFormat } from '../../app/format';
import { EmptyState } from '../../components/EmptyState';
import { Badge, Loading, Page } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { type NamedRef, type Page as ListPage, useVersionedMutation } from '../announcements/versionedMutation';
import { CalendarEventForm } from './CalendarEventForm';
import type { CalendarEvent } from './calendarTypes';
import { eventsByDay, monthRange, shiftMonth } from './month';
import './calendar.css';

export function CalendarPage() {
  const { t, i18n } = useTranslation();
  const { can, role } = useOrg();
  const fmt = useFormat();
  const [month, setMonth] = useState(todayIso().slice(0, 7));
  const { from, to } = monthRange(month);
  const isStaff = role !== null && role !== 'PARENT';
  const events = useOrgQuery<ListPage<CalendarEvent>>(['calendar'], `/calendar-events?from=${from}&to=${to}&limit=100`, { enabled: can('CALENDAR_READ') });
  const groups = useOrgQuery<ListPage<NamedRef>>(['groups'], '/groups?limit=100', { enabled: isStaff });
  const locations = useOrgQuery<ListPage<NamedRef>>(['locations'], '/locations?limit=100', { enabled: can('CALENDAR_MANAGE') });
  const remove = useVersionedMutation(['calendar']);
  const [editing, setEditing] = useState<CalendarEvent | 'new' | null>(null);
  const canManage = can('CALENDAR_MANAGE');

  const locale = i18n.language === 'en' ? 'en-GB' : i18n.language === 'sr-Cyrl' ? 'sr-Cyrl-RS' : 'sr-Latn-RS';
  const monthLabel = new Date(`${from}T00:00:00`).toLocaleDateString(locale, { month: 'long', year: 'numeric' });
  const scopeLabel = (e: CalendarEvent) => {
    if (e.groupId != null) {
      return groups.data?.items.find((g) => g.id === e.groupId)?.name ?? t('calendar.scope.group');
    }
    if (e.locationId != null) {
      return locations.data?.items.find((l) => l.id === e.locationId)?.name ?? t('calendar.scope.location');
    }
    return t('calendar.wholeOrganization');
  };
  const timeLabel = (e: CalendarEvent) =>
    e.allDay ? (e.startsOn === e.endsOn ? t('calendar.allDay') : `${fmt.date(e.startsOn)} – ${fmt.date(e.endsOn)}`) : `${fmt.time(e.startsAt)} – ${fmt.time(e.endsAt)}`;

  const onDelete = (e: CalendarEvent) => {
    if (window.confirm(t('ui.confirmDelete'))) {
      remove.mutate({ method: 'DELETE', path: `/calendar-events/${e.id}`, version: e.version });
    }
  };

  const days = events.data === undefined ? [] : eventsByDay(events.data.items, from, to);

  return (
    <Page
      title={t('nav.calendar')}
      actions={
        canManage ? (
          <button type="button" className="vc-button vc-button--primary" onClick={() => { setEditing('new'); }}>
            {t('calendar.new')}
          </button>
        ) : undefined
      }
    >
      {!can('CALENDAR_READ') ? (
        <p>{t('ui.noAccess')}</p>
      ) : (
        <>
          <div className="vc-toolbar">
            <button type="button" className="vc-button" aria-label={t('calendar.previousMonth')} onClick={() => { setMonth(shiftMonth(month, -1)); }}>
              ‹
            </button>
            <strong className="vc-calendar-month">{monthLabel}</strong>
            <button type="button" className="vc-button" aria-label={t('calendar.nextMonth')} onClick={() => { setMonth(shiftMonth(month, 1)); }}>
              ›
            </button>
            <button type="button" className="vc-button vc-button--ghost" onClick={() => { setMonth(todayIso().slice(0, 7)); }}>
              {t('calendar.today')}
            </button>
          </div>
          <ProblemAlert error={remove.error} />
          {events.isPending ? <Loading /> : null}
          {events.isError ? <ProblemAlert error={events.error} /> : null}
          {events.data !== undefined && days.length === 0 ? <EmptyState message={t('calendar.empty')} /> : null}
          {days.map(({ day, events: list }) => (
            <section key={day} className="vc-section">
              <h2>{new Date(`${day}T00:00:00`).toLocaleDateString(locale, { weekday: 'long', day: 'numeric', month: 'long' })}</h2>
              <ul className="vc-list">
                {list.map((e) => (
                  <li key={e.id}>
                    <div className="vc-calendar-event">
                      <Badge tone={e.kind === 'CLOSURE' || e.kind === 'HOLIDAY' ? 'warning' : 'info'}>{t(`calendar.kinds.${e.kind}`)}</Badge>
                      <strong>{e.title}</strong>
                      <span className="vc-muted">{timeLabel(e)}</span>
                      <span className="vc-muted">{scopeLabel(e)}</span>
                      {canManage ? (
                        <span className="vc-calendar-actions">
                          <button type="button" className="vc-button vc-button--small" onClick={() => { setEditing(e); }}>
                            {t('ui.edit')}
                          </button>
                          <button type="button" className="vc-button vc-button--small vc-button--danger" disabled={remove.isPending} onClick={() => { onDelete(e); }}>
                            {t('ui.delete')}
                          </button>
                        </span>
                      ) : null}
                    </div>
                    {e.description == null || e.description === '' ? null : <p className="vc-pre">{e.description}</p>}
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </>
      )}
      {editing === null ? null : (
        <CalendarEventForm
          event={editing === 'new' ? null : editing}
          defaultDate={todayIso().startsWith(month) ? todayIso() : from}
          groups={groups.data?.items ?? []}
          locations={locations.data?.items ?? []}
          onClose={() => { setEditing(null); }}
        />
      )}
    </Page>
  );
}
