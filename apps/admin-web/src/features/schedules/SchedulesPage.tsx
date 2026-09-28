import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg, useOrgQuery } from '../../api/org';
import { todayIso } from '../../app/format';
import { EmptyState } from '../../components/EmptyState';
import { InputField, SelectField } from '../../components/Form';
import { Badge, Loading, Page } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { groupsFromChildren } from '../children/helpers';
import { type ChildDetail, type ChildSummary, childName, type GroupOption, type Page as ApiPage } from '../children/types';
import type { ExpectedChildren } from './types';
import { WeeklyScheduleEditor } from './WeeklyScheduleEditor';

import '../children/children.css';

/** /schedules: staff see expected children of a group on a date; parents edit their children's weekly schedule. */
export function SchedulesPage() {
  const { t } = useTranslation();
  const org = useOrg();
  if (!org.can('SCHEDULE_READ')) {
    return (
      <Page title={t('nav.schedules')}>
        <p className="vc-muted">{t('ui.noAccess')}</p>
      </Page>
    );
  }
  return org.role === 'PARENT' ? <ParentSchedules /> : <ExpectedByGroup />;
}

function ExpectedByGroup() {
  const { t } = useTranslation();
  const org = useOrg();
  const isManager = org.role === 'OWNER' || org.role === 'ADMIN';
  const groups = useOrgQuery<ApiPage<GroupOption>>(['groups'], '/groups?limit=100', { enabled: isManager });
  const children = useOrgQuery<ApiPage<ChildSummary>>(['children'], '/children?limit=100', { enabled: !isManager });
  const options = useMemo(() => {
    const source = isManager ? (groups.data?.items ?? []) : groupsFromChildren(children.data?.items ?? []);
    return source.map((g) => ({ value: g.id, label: g.name }));
  }, [isManager, groups.data, children.data]);
  const [groupId, setGroupId] = useState('');
  const [date, setDate] = useState(todayIso());
  const effectiveGroup = groupId === '' ? (options[0]?.value ?? '') : groupId;
  const expected = useOrgQuery<ExpectedChildren>(['schedules', 'expected'], `/schedules/expected?groupId=${encodeURIComponent(effectiveGroup)}&date=${date}`, {
    enabled: effectiveGroup !== '' && date !== '',
  });
  const data = expected.data;
  const expectedCount = data?.items.filter((i) => i.isExpected).length ?? 0;

  return (
    <Page title={t('nav.schedules')}>
      <div className="vc-toolbar">
        <SelectField id="schedules-group" label={t('schedules.group')} value={effectiveGroup} onChange={setGroupId} options={options} {...(options.length === 0 ? { emptyLabel: t('schedules.noGroups') } : {})} />
        <InputField id="schedules-date" type="date" label={t('ui.date')} value={date} onChange={setDate} />
      </div>
      <ProblemAlert error={isManager ? groups.error : children.error} />
      <ProblemAlert error={expected.error} />
      {expected.isFetching && data === undefined ? <Loading /> : null}
      {data === undefined ? null : (
        <>
          {data.isWorkingDay ? (
            <p>{t('schedules.expectedCount', { expected: expectedCount, total: data.items.length })}</p>
          ) : (
            <p className="vc-muted">{t('schedules.nonWorkingDay')}</p>
          )}
          {data.items.length === 0 ? (
            <EmptyState message={t('schedules.noChildrenInGroup')} />
          ) : (
            <div className="vc-table-wrap">
              <table className="vc-table">
                <thead>
                  <tr>
                    <th>{t('children.nameColumn')}</th>
                    <th>{t('schedules.expected')}</th>
                    <th>{t('schedules.arrival')}</th>
                    <th>{t('schedules.departure')}</th>
                    <th>{t('schedules.note')}</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((c) => (
                    <tr key={c.childId}>
                      <td>{childName(c)}</td>
                      <td>
                        <Badge tone={c.isExpected ? 'success' : 'neutral'}>{c.isExpected ? t('ui.yes') : t('ui.no')}</Badge>
                      </td>
                      <td>{c.expectedArrival ?? ''}</td>
                      <td>{c.expectedDeparture ?? ''}</td>
                      <td>
                        {c.absenceKind === undefined || c.absenceKind === null ? (
                          c.source === 'NONE' && data.isWorkingDay ? <span className="vc-muted">{t('schedules.noTemplateShort')}</span> : null
                        ) : (
                          <Badge tone="warning">{t(`schedules.absenceKind.${c.absenceKind}`)}</Badge>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </Page>
  );
}

function ParentSchedules() {
  const { t } = useTranslation();
  const list = useOrgQuery<ApiPage<ChildSummary>>(['children'], '/children?limit=100');
  const [selected, setSelected] = useState('');
  const items = list.data?.items ?? [];
  const childId = selected === '' ? (items[0]?.id ?? '') : selected;

  return (
    <Page title={t('nav.schedules')}>
      <ProblemAlert error={list.error} />
      {list.isPending ? <Loading /> : null}
      {list.data !== undefined && items.length === 0 ? <EmptyState message={t('schedules.noLinkedChildren')} /> : null}
      {items.length > 1 ? (
        <div className="vc-toolbar">
          <SelectField id="schedules-child" label={t('schedules.child')} value={childId} onChange={setSelected} options={items.map((c) => ({ value: c.id, label: childName(c) }))} />
        </div>
      ) : null}
      {childId === '' ? null : <ParentChildSchedule key={childId} childId={childId} />}
    </Page>
  );
}

function ParentChildSchedule({ childId }: { readonly childId: string }) {
  const { t } = useTranslation();
  const detail = useOrgQuery<ChildDetail>(['children', childId], `/children/${childId}`);
  const own = detail.data?.guardians.find((g) => g.status === 'CONFIRMED');
  return (
    <section className="vc-section">
      <ProblemAlert error={detail.error} />
      {detail.data === undefined ? null : <h2>{childName(detail.data)}</h2>}
      {detail.data !== undefined && own?.canManageSchedule !== true ? <p className="vc-muted">{t('schedules.readOnlyHint')}</p> : null}
      {detail.data === undefined ? null : <WeeklyScheduleEditor childId={childId} canEdit={own?.canManageSchedule === true} />}
    </section>
  );
}
