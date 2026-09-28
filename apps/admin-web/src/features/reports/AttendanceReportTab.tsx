import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg, useOrgQuery } from '../../api/org';
import { todayIso, useFormat } from '../../app/format';
import { EmptyState } from '../../components/EmptyState';
import { InputField, SelectField } from '../../components/Form';
import { Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import type { NamedRef, Page as ListPage } from '../announcements/versionedMutation';
import { downloadFile } from './download';
import { attendanceQuery, csvFileName, monthStart } from './reportHelpers';
import type { AttendanceSummaryReport } from './reportTypes';

const TOTAL_KEYS = ['workingDays', 'expectedChildDays', 'presentChildDays', 'absentChildDays', 'notArrivedChildDays', 'unscheduledChildDays', 'lateArrivals'] as const;

/**
 * Attendance summary for a date range (REPORT_VIEW): totals, per group and per child. The CSV export is shown
 * only to OWNER (REPORT_EXPORT; an ADMIN with the extra permission can still call the API, the UI cannot know).
 */
export function AttendanceReportTab() {
  const { t, i18n } = useTranslation();
  const format = useFormat();
  const locale = i18n.language === 'en' ? 'en-GB' : i18n.language === 'sr-Cyrl' ? 'sr-Cyrl-RS' : 'sr-Latn-RS';
  const pct = (value: number) => value.toLocaleString(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  const org = useOrg();
  const today = todayIso();
  const [from, setFrom] = useState(monthStart(today));
  const [to, setTo] = useState(today);
  const [groupId, setGroupId] = useState('');
  const groups = useOrgQuery<ListPage<NamedRef>>(['groups'], '/groups?limit=100');
  const valid = from !== '' && to !== '' && from <= to;
  const path = attendanceQuery(from, to, groupId);
  const report = useOrgQuery<AttendanceSummaryReport>(['reports', 'attendance'], path, { enabled: valid });
  const exportCsv = useMutation({ mutationFn: () => downloadFile(org.path(`${path}&format=csv`), 'text/csv', csvFileName(from, to)) });
  const canExport = org.can('REPORT_EXPORT');
  const data = report.data;

  return (
    <>
      <div className="vc-toolbar">
        <InputField id="report-from" type="date" label={t('ui.from')} value={from} max={to} onChange={setFrom} />
        <InputField id="report-to" type="date" label={t('ui.to')} value={to} min={from} onChange={setTo} />
        <SelectField
          id="report-group"
          label={t('reports.group')}
          value={groupId}
          onChange={setGroupId}
          emptyLabel={t('reports.allGroups')}
          options={(groups.data?.items ?? []).map((g) => ({ value: g.id, label: g.name }))}
        />
        {canExport ? (
          <button type="button" className="vc-button" disabled={!valid || exportCsv.isPending} onClick={() => { exportCsv.mutate(); }}>
            {exportCsv.isPending ? t('reports.exporting') : t('reports.exportCsv')}
          </button>
        ) : null}
      </div>
      {valid ? null : <p className="vc-muted">{t('reports.invalidRange')}</p>}
      <ProblemAlert error={groups.error} />
      <ProblemAlert error={report.error} />
      <ProblemAlert error={exportCsv.error} />
      {report.isFetching && data === undefined ? <Loading /> : null}
      {data === undefined ? null : (
        <>
          <p className="vc-muted">
            {data.countedTo == null
              ? t('reports.nothingCounted')
              : t('reports.countedRange', { from: format.date(data.from), to: format.date(data.countedTo) })}
          </p>
          <section className="vc-cards" aria-label={t('reports.totals')}>
            {TOTAL_KEYS.map((key) => (
              <div className="vc-card" key={key}>
                <h3>{t(`reports.totalsLabels.${key}`)}</h3>
                <p className="vc-stat">{data.totals[key]}</p>
              </div>
            ))}
            <div className="vc-card">
              <h3>{t('reports.totalsLabels.attendanceRatePct')}</h3>
              <p className="vc-stat">{t('reports.percent', { value: pct(data.totals.attendanceRatePct) })}</p>
            </div>
          </section>

          <section className="vc-section">
            <h2>{t('reports.byGroup')}</h2>
            {data.byGroup.length === 0 ? (
              <EmptyState message={t('reports.empty')} />
            ) : (
              <div className="vc-table-wrap">
                <table className="vc-table">
                  <thead>
                    <tr>
                      <th>{t('reports.group')}</th>
                      <th>{t('reports.columns.expected')}</th>
                      <th>{t('reports.columns.present')}</th>
                      <th>{t('reports.columns.absent')}</th>
                      <th>{t('reports.columns.notArrived')}</th>
                      <th>{t('reports.columns.unscheduled')}</th>
                      <th>{t('reports.columns.late')}</th>
                      <th>{t('reports.columns.rate')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.byGroup.map((g) => (
                      <tr key={g.groupId}>
                        <td>{g.groupName}</td>
                        <td>{g.expectedChildDays}</td>
                        <td>{g.presentChildDays}</td>
                        <td>{g.absentChildDays}</td>
                        <td>{g.notArrivedChildDays}</td>
                        <td>{g.unscheduledChildDays}</td>
                        <td>{g.lateArrivals}</td>
                        <td>{t('reports.percent', { value: pct(g.attendanceRatePct) })}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          {data.byChild.length === 0 ? null : (
            <section className="vc-section">
              <h2>{t('reports.byChild')}</h2>
              <div className="vc-table-wrap">
                <table className="vc-table">
                  <thead>
                    <tr>
                      <th>{t('reports.child')}</th>
                      <th>{t('reports.group')}</th>
                      <th>{t('reports.columns.expected')}</th>
                      <th>{t('reports.columns.present')}</th>
                      <th>{t('reports.columns.sick')}</th>
                      <th>{t('reports.columns.vacation')}</th>
                      <th>{t('reports.columns.otherAbsence')}</th>
                      <th>{t('reports.columns.notArrived')}</th>
                      <th>{t('reports.columns.unscheduled')}</th>
                      <th>{t('reports.columns.late')}</th>
                      <th>{t('reports.columns.rate')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.byChild.map((c) => (
                      <tr key={`${c.childId}-${c.groupId}`}>
                        <td>
                          {c.familyName} {c.givenName}
                        </td>
                        <td>{c.groupName}</td>
                        <td>{c.expectedDays}</td>
                        <td>{c.presentDays}</td>
                        <td>{c.absentSickDays}</td>
                        <td>{c.absentVacationDays}</td>
                        <td>{c.absentOtherDays}</td>
                        <td>{c.notArrivedDays}</td>
                        <td>{c.unscheduledDays}</td>
                        <td>{c.lateArrivals}</td>
                        <td>{t('reports.percent', { value: pct(c.attendanceRatePct) })}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}
        </>
      )}
    </>
  );
}
