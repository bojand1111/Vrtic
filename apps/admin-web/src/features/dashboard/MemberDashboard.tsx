import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { useOrgQuery } from '../../api/org';
import { todayIso, useFormat } from '../../app/format';
import type { OrganizationRole } from '../../auth/types';
import { Badge, Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import type { Announcement } from '../announcements/announcementTypes';
import type { CalendarEvent } from '../calendar/calendarTypes';
import type { ChildSummary, Page } from '../children/types';
import type { Group } from '../groups/types';
import type { MenuDay } from '../meals/menuTypes';

function plusDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00`);
  d.setDate(d.getDate() + days);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function MyGroups() {
  const { t } = useTranslation();
  const groups = useOrgQuery<Page<Group>>(['groups'], '/groups?limit=100');
  return (
    <section className="vc-section">
      <h2>{t('dashboard.myGroups')}</h2>
      {groups.isPending ? <Loading /> : null}
      <ProblemAlert error={groups.error} />
      <ul className="vc-list">
        {groups.data?.items.map((g) => (
          <li key={g.id}>
            <strong>{g.name}</strong> <span className="vc-muted">({g.locationName}, {g.activeChildrenCount})</span>{' '}
            <Link to={`/attendance?groupId=${g.id}`}>{t('dashboard.openAttendance')}</Link>
          </li>
        ))}
      </ul>
    </section>
  );
}

function MyChildren() {
  const { t } = useTranslation();
  const children = useOrgQuery<Page<ChildSummary>>(['children'], '/children?limit=100');
  return (
    <section className="vc-section">
      <h2>{t('dashboard.myChildren')}</h2>
      {children.isPending ? <Loading /> : null}
      <ProblemAlert error={children.error} />
      <ul className="vc-list">
        {children.data?.items.map((c) => (
          <li key={c.id}>
            <strong>
              {c.givenName} {c.familyName}
            </strong>{' '}
            <span className="vc-muted">{c.currentEnrollment?.groupName ?? ''}</span>{' '}
            <Link to="/attendance">{t('nav.attendance')}</Link> · <Link to={`/absences?report=${c.id}`}>{t('dashboard.reportAbsence')}</Link>
          </li>
        ))}
      </ul>
    </section>
  );
}

function Announcements() {
  const { t } = useTranslation();
  const format = useFormat();
  const list = useOrgQuery<Page<Announcement>>(['announcements'], '/announcements?limit=5');
  return (
    <section className="vc-section">
      <h2>{t('dashboard.latestAnnouncements')}</h2>
      <ProblemAlert error={list.error} />
      {list.data?.items.length === 0 ? <p className="vc-muted">{t('ui.none')}</p> : null}
      <ul className="vc-list">
        {list.data?.items.map((a) => (
          <li key={a.id}>
            <Link to="/announcements">{a.title}</Link>{' '}
            {a.readAt === undefined || a.readAt === null ? <Badge tone="info">{t('dashboard.unread')}</Badge> : null}
            <br />
            <small className="vc-muted">{format.dateTime(a.publishedAt)}</small>
          </li>
        ))}
      </ul>
    </section>
  );
}

function UpcomingEvents() {
  const { t } = useTranslation();
  const format = useFormat();
  const today = todayIso();
  const events = useOrgQuery<Page<CalendarEvent>>(['calendar'], `/calendar-events?from=${today}&to=${plusDays(today, 30)}&limit=5`);
  return (
    <section className="vc-section">
      <h2>{t('dashboard.upcomingEvents')}</h2>
      <ProblemAlert error={events.error} />
      {events.data?.items.length === 0 ? <p className="vc-muted">{t('ui.none')}</p> : null}
      <ul className="vc-list">
        {events.data?.items.slice(0, 5).map((e) => (
          <li key={e.id}>
            <Link to="/calendar">{e.title}</Link>
            <br />
            <small className="vc-muted">{e.allDay ? format.date(e.startsOn) : format.dateTime(e.startsAt)}</small>
          </li>
        ))}
      </ul>
    </section>
  );
}

function TodayMenu() {
  const { t } = useTranslation();
  const today = todayIso();
  const menu = useOrgQuery<Page<MenuDay>>(['menus'], `/menu-days?from=${today}&to=${today}`);
  const day = menu.data?.items[0];
  return (
    <section className="vc-section">
      <h2>{t('dashboard.todayMenu')}</h2>
      <ProblemAlert error={menu.error} />
      {menu.data !== undefined && day === undefined ? <p className="vc-muted">{t('dashboard.noMenu')}</p> : null}
      {day === undefined ? null : (
        <ul className="vc-list">
          {day.items.map((item) => (
            <li key={item.id}>
              <strong>{t(`meals.slots.${item.mealSlot}`)}:</strong> {item.description}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** TEACHER / PARENT home: own groups or children, then the day's news. */
export function MemberDashboard({ role }: { readonly role: OrganizationRole }) {
  return (
    <>
      {role === 'TEACHER' ? <MyGroups /> : <MyChildren />}
      <div className="vc-field-row">
        <Announcements />
        <UpcomingEvents />
      </div>
      <TodayMenu />
    </>
  );
}
