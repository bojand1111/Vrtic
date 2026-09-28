import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrgQuery } from '../../api/org';
import { todayIso, useFormat } from '../../app/format';
import { EmptyState } from '../../components/EmptyState';
import { InputField } from '../../components/Form';
import { Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import type { Page } from '../announcements/versionedMutation';
import type { AttendanceDay } from './attendanceTypes';
import { StatusBadges } from './StatusBadges';

interface ChildSummary {
  readonly id: string;
  readonly givenName: string;
  readonly familyName: string;
  readonly currentEnrollment?: { readonly groupName?: string | null } | null;
}

/** PARENT: attendance of the own children for a chosen day (read only). */
export function ParentAttendance() {
  const { t } = useTranslation();
  const [date, setDate] = useState(todayIso());
  const children = useOrgQuery<Page<ChildSummary>>(['children'], '/children?limit=100');

  return (
    <>
      <div className="vc-toolbar">
        <InputField id="attendance-date" type="date" label={t('ui.date')} value={date} max={todayIso()} onChange={(v) => { if (v !== '') setDate(v); }} />
      </div>
      {children.isPending ? <Loading /> : null}
      {children.isError ? <ProblemAlert error={children.error} /> : null}
      {children.data?.items.length === 0 ? <EmptyState message={t('attendance.parent.noChildren')} /> : null}
      <div className="vc-cards vc-attendance-children">
        {children.data?.items.map((c) => <ChildDayCard key={c.id} child={c} date={date} />)}
      </div>
    </>
  );
}

function ChildDayCard({ child, date }: { readonly child: ChildSummary; readonly date: string }) {
  const { t } = useTranslation();
  const fmt = useFormat();
  const day = useOrgQuery<AttendanceDay>(['attendance', 'day'], `/children/${child.id}/attendance/days/${date}`);
  const d = day.data;
  return (
    <section className="vc-card">
      <h3>
        {child.givenName} {child.familyName}
        {child.currentEnrollment?.groupName == null ? '' : ` · ${child.currentEnrollment.groupName}`}
      </h3>
      {day.isPending ? <Loading /> : null}
      {day.isError ? <ProblemAlert error={day.error} /> : null}
      {d === undefined ? null : (
        <>
          <p>
            <StatusBadges
              status={d.status}
              absenceKind={d.absenceKind ?? null}
              isLate={d.isLate}
              isUnscheduled={d.isUnscheduled}
              isExpected={d.isExpected}
            />
          </p>
          {d.expectedArrival == null ? null : (
            <p className="vc-muted">{t('attendance.parent.expectedTimes', { from: d.expectedArrival, to: d.expectedDeparture ?? '' })}</p>
          )}
          {d.visits.length === 0 ? (
            <p className="vc-muted">{t('attendance.parent.noVisits')}</p>
          ) : (
            <ul className="vc-list">
              {d.visits.map((v) => (
                <li key={v.id}>
                  {t('attendance.columns.arrival')}: {fmt.time(v.checkInAt)}
                  {v.checkOutAt == null ? '' : ` · ${t('attendance.columns.departure')}: ${fmt.time(v.checkOutAt)}`}
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </section>
  );
}
