import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { useOrgQuery } from '../../api/org';
import { todayIso, useFormat } from '../../app/format';
import { InputField } from '../../components/Form';
import { Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import type { DailyOverviewCounters, DashboardSummary } from './types';

const COUNTER_KEYS = ['expected', 'present', 'departed', 'absent', 'notArrived', 'late', 'unscheduledPresent'] as const;

function CounterCells({ counters }: { readonly counters: DailyOverviewCounters }) {
  return (
    <>
      {COUNTER_KEYS.map((key) => (
        <td key={key}>{counters[key]}</td>
      ))}
    </>
  );
}

/** OWNER / ADMIN: GET /dashboard (REPORT_VIEW). */
export function ManagerDashboard() {
  const { t } = useTranslation();
  const format = useFormat();
  const [date, setDate] = useState(todayIso());
  const query = useOrgQuery<DashboardSummary>(['dashboard'], `/dashboard?date=${date}`);

  return (
    <>
      <div className="vc-toolbar" style={{ marginBottom: 16 }}>
        <InputField id="dashboard-date" label={t('ui.date')} type="date" value={date} onChange={(v) => { if (v.length > 0) setDate(v); }} />
      </div>
      {query.isPending ? <Loading /> : null}
      <ProblemAlert error={query.error} />
      {query.data === undefined ? null : (
        <>
          <section aria-label={t('dashboard.attendanceToday')} className="vc-cards">
            {COUNTER_KEYS.map((key) => (
              <div className="vc-card" key={key}>
                <h3>{t(`dashboard.counters.${key}`)}</h3>
                <p className="vc-stat">{query.data.counters[key]}</p>
              </div>
            ))}
          </section>

          <section className="vc-cards" aria-label={t('dashboard.organization')}>
            <Link className="vc-card" to="/children">
              <h3>{t('dashboard.childrenActive')}</h3>
              <p className="vc-stat">{query.data.childrenActive}</p>
            </Link>
            <Link className="vc-card" to="/employees">
              <h3>{t('dashboard.staffActive')}</h3>
              <p className="vc-stat">{query.data.staffActive}</p>
            </Link>
            <Link className="vc-card" to="/absences">
              <h3>{t('dashboard.absencesToday')}</h3>
              <p className="vc-stat">{query.data.absencesToday}</p>
            </Link>
            <Link className="vc-card" to="/parents">
              <h3>{t('dashboard.guardiansPending')}</h3>
              <p className="vc-stat">{query.data.guardiansPending}</p>
            </Link>
            <Link className="vc-card" to="/employees">
              <h3>{t('dashboard.invitationsPending')}</h3>
              <p className="vc-stat">{query.data.invitationsPending}</p>
            </Link>
          </section>

          <section className="vc-section">
            <h2>{t('dashboard.byGroup')}</h2>
            <div className="vc-table-wrap">
              <table className="vc-table">
                <thead>
                  <tr>
                    <th scope="col">{t('dashboard.group')}</th>
                    {COUNTER_KEYS.map((key) => (
                      <th scope="col" key={key}>
                        {t(`dashboard.counters.${key}`)}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {query.data.groups.map((g) => (
                    <tr key={g.groupId}>
                      <td>
                        <Link to={`/attendance?groupId=${g.groupId}`}>{g.name}</Link>
                      </td>
                      <CounterCells counters={g.counters} />
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          <div className="vc-field-row">
            <section className="vc-section">
              <h2>{t('dashboard.latestAnnouncements')}</h2>
              {query.data.latestAnnouncements.length === 0 ? (
                <p className="vc-muted">{t('ui.none')}</p>
              ) : (
                <ul className="vc-list">
                  {query.data.latestAnnouncements.map((a) => (
                    <li key={a.id}>
                      <Link to="/announcements">{a.title}</Link>
                      <br />
                      <small className="vc-muted">{format.dateTime(a.publishedAt)}</small>
                    </li>
                  ))}
                </ul>
              )}
            </section>
            <section className="vc-section">
              <h2>{t('dashboard.upcomingEvents')}</h2>
              {query.data.upcomingEvents.length === 0 ? (
                <p className="vc-muted">{t('ui.none')}</p>
              ) : (
                <ul className="vc-list">
                  {query.data.upcomingEvents.map((e) => (
                    <li key={e.id}>
                      <Link to="/calendar">{e.title}</Link>
                      <br />
                      <small className="vc-muted">
                        {e.allDay ? format.date(e.startsOn) : format.dateTime(e.startsAt)}
                        {e.endsOn !== e.startsOn ? ` - ${format.date(e.endsOn)}` : ''}
                      </small>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </div>
        </>
      )}
    </>
  );
}
