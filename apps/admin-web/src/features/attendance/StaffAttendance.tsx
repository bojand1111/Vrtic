import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';

import { useOrg, useOrgMutation, useOrgQuery } from '../../api/org';
import { InputField, SelectField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { EmptyState } from '../../components/EmptyState';
import { todayIso, useFormat } from '../../app/format';
import type { NamedRef, Page } from '../announcements/versionedMutation';
import {
  ABSENCE_KINDS,
  type AbsenceKind,
  type AttendanceAction,
  attendanceCommand,
  type DailyOverview,
  type DailyOverviewChild,
} from './attendanceTypes';
import { CorrectionModal } from './CorrectionModal';
import { StatusBadges } from './StatusBadges';

/** TEACHER / OWNER / ADMIN: group day overview with check-in, check-out and absence actions. */
export function StaffAttendance() {
  const { t } = useTranslation();
  const { can } = useOrg();
  const fmt = useFormat();
  const groups = useOrgQuery<Page<NamedRef>>(['groups'], '/groups?limit=100');
  const [searchParams] = useSearchParams();
  // dashboard links open a specific group: /attendance?groupId=...
  const [groupChoice, setGroupChoice] = useState(searchParams.get('groupId') ?? '');
  const [date, setDate] = useState(todayIso());
  const groupId = groupChoice !== '' ? groupChoice : (groups.data?.items[0]?.id ?? '');
  const overview = useOrgQuery<DailyOverview>(
    ['attendance'],
    `/attendance/daily-overview?groupId=${encodeURIComponent(groupId)}&date=${date}`,
    { enabled: groupId !== '' },
  );
  const command = useOrgMutation(['attendance']);
  const [absentFor, setAbsentFor] = useState<DailyOverviewChild | null>(null);
  const [absenceKind, setAbsenceKind] = useState<AbsenceKind>('SICK');
  const [correctFor, setCorrectFor] = useState<DailyOverviewChild | null>(null);

  const isToday = date === todayIso();
  const canRecord = can('ATTENDANCE_RECORD') && isToday;
  const canCorrect = can('ATTENDANCE_CORRECT');

  const send = (child: DailyOverviewChild, action: AttendanceAction, extra: Record<string, unknown> = {}) => {
    command.mutate(
      { method: 'POST', path: `/children/${child.childId}/attendance/${action}`, body: attendanceCommand(child.version, date, extra) },
      {
        onSuccess: () => {
          setAbsentFor(null);
        },
      },
    );
  };

  if (groups.isPending) {
    return <Loading />;
  }
  if (groups.isError) {
    return <ProblemAlert error={groups.error} />;
  }
  if (groups.data.items.length === 0) {
    return <EmptyState message={t('attendance.noGroups')} />;
  }

  const counters = overview.data?.counters;
  const counterKeys = ['expected', 'present', 'departed', 'absent', 'notArrived', 'late', 'unscheduledPresent'] as const;

  return (
    <>
      <div className="vc-toolbar">
        <SelectField
          id="attendance-group"
          label={t('attendance.group')}
          value={groupId}
          onChange={setGroupChoice}
          options={groups.data.items.map((g) => ({ value: g.id, label: g.name }))}
        />
        <InputField id="attendance-date" type="date" label={t('ui.date')} value={date} max={todayIso()} onChange={(v) => { if (v !== '') setDate(v); }} />
      </div>
      {isToday ? null : <p className="vc-muted">{t('attendance.pastDateHint')}</p>}
      <ProblemAlert error={command.error} />
      {overview.isError ? <ProblemAlert error={overview.error} /> : null}
      {counters === undefined ? (
        overview.isPending ? <Loading /> : null
      ) : (
        <div className="vc-cards">
          {counterKeys.map((k) => (
            <div key={k} className="vc-card">
              <h3>{t(`attendance.counters.${k}`)}</h3>
              <p className="vc-stat">{counters[k]}</p>
            </div>
          ))}
        </div>
      )}
      {overview.data === undefined ? null : overview.data.children.length === 0 ? (
        <EmptyState message={t('attendance.noChildren')} />
      ) : (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th>{t('attendance.columns.child')}</th>
                <th>{t('ui.status')}</th>
                <th>{t('attendance.columns.expected')}</th>
                <th>{t('attendance.columns.arrival')}</th>
                <th>{t('attendance.columns.departure')}</th>
                <th>{t('ui.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {overview.data.children.map((child) => (
                <tr key={child.childId}>
                  <td>
                    {child.givenName} {child.familyName}
                  </td>
                  <td>
                    <StatusBadges
                      status={child.status}
                      absenceKind={child.absenceKind ?? null}
                      isLate={child.isLate}
                      isUnscheduled={child.isUnscheduled}
                      isExpected={child.isExpected}
                    />
                  </td>
                  <td>
                    {child.expectedArrival == null ? '' : `${child.expectedArrival} – ${child.expectedDeparture ?? ''}`}
                  </td>
                  <td>{fmt.time(child.firstCheckInAt)}</td>
                  <td>{child.status === 'CHECKED_OUT' ? fmt.time(child.lastCheckOutAt) : ''}</td>
                  <td className="vc-actions">
                    {canRecord && child.status !== 'CHECKED_IN' ? (
                      <button type="button" className="vc-button vc-button--primary" disabled={command.isPending} onClick={() => { send(child, 'check-in'); }}>
                        {t('attendance.actions.checkIn')}
                      </button>
                    ) : null}
                    {canRecord && child.status === 'CHECKED_IN' ? (
                      <button type="button" className="vc-button vc-button--primary" disabled={command.isPending} onClick={() => { send(child, 'check-out'); }}>
                        {t('attendance.actions.checkOut')}
                      </button>
                    ) : null}
                    {canRecord && child.absenceKind == null ? (
                      <button type="button" className="vc-button vc-button--small" disabled={command.isPending} onClick={() => { setAbsentFor(child); }}>
                        {t('attendance.actions.markAbsent')}
                      </button>
                    ) : null}
                    {canRecord && child.absenceKind != null ? (
                      <button type="button" className="vc-button vc-button--small" disabled={command.isPending} onClick={() => { send(child, 'clear-absence'); }}>
                        {t('attendance.actions.clearAbsence')}
                      </button>
                    ) : null}
                    {canCorrect && child.version > 0 ? (
                      <button type="button" className="vc-button vc-button--small vc-button--ghost" onClick={() => { setCorrectFor(child); }}>
                        {t('attendance.actions.correct')}
                      </button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Modal
        title={t('attendance.absentModal.title')}
        open={absentFor !== null}
        onClose={() => { setAbsentFor(null); }}
        footer={
          <>
            <button type="button" className="vc-button" onClick={() => { setAbsentFor(null); }}>
              {t('ui.cancel')}
            </button>
            <button
              type="button"
              className="vc-button vc-button--primary"
              disabled={command.isPending}
              onClick={() => { if (absentFor !== null) send(absentFor, 'mark-absent', { absenceKind }); }}
            >
              {t('ui.save')}
            </button>
          </>
        }
      >
        {absentFor === null ? null : (
          <>
            <p>
              {absentFor.givenName} {absentFor.familyName}
            </p>
            <SelectField
              id="absence-kind"
              label={t('attendance.absentModal.kind')}
              value={absenceKind}
              onChange={(v) => { setAbsenceKind(ABSENCE_KINDS.find((k) => k === v) ?? 'OTHER'); }}
              options={ABSENCE_KINDS.map((k) => ({ value: k, label: t(`attendance.absence.${k}`) }))}
            />
            <ProblemAlert error={command.error} />
          </>
        )}
      </Modal>
      {correctFor === null ? null : (
        <CorrectionModal
          child={correctFor}
          date={date}
          onClose={() => { setCorrectFor(null); }}
        />
      )}
    </>
  );
}
