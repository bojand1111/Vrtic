import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';

import { useOrg, useOrgMutation, useOrgQuery } from '../../api/org';
import { todayIso, useFormat } from '../../app/format';
import { EmptyState } from '../../components/EmptyState';
import { InputField, SelectField } from '../../components/Form';
import { Badge, Loading, Page } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { addDays, groupsFromChildren } from '../children/helpers';
import { type ChildSummary, childName, type GroupOption, type Page as ApiPage } from '../children/types';
import { ReportAbsenceModal } from './ReportAbsenceModal';
import { type Absence, ABSENCE_KINDS } from './types';

/** /absences: list with filters; managers and parents (with the guardian right) report and cancel. */
export function AbsencesPage() {
  const { t } = useTranslation();
  const org = useOrg();
  const format = useFormat();
  const [searchParams, setSearchParams] = useSearchParams();
  const isParent = org.role === 'PARENT';
  const isManager = org.role === 'OWNER' || org.role === 'ADMIN';
  const canReport = org.can('ABSENCE_REPORT');
  const [from, setFrom] = useState(() => addDays(todayIso(), -7));
  const [to, setTo] = useState(() => addDays(todayIso(), 31));
  const [groupId, setGroupId] = useState('');
  const [childId, setChildId] = useState('');
  const [kind, setKind] = useState('');
  const [status, setStatus] = useState<'ACTIVE' | 'CANCELLED'>('ACTIVE');
  const reportFor = searchParams.get('report');
  const [reporting, setReporting] = useState(reportFor !== null);

  const children = useOrgQuery<ApiPage<ChildSummary>>(['children'], '/children?limit=100', { enabled: org.can('CHILD_READ') });
  const groups = useOrgQuery<ApiPage<GroupOption>>(['groups'], '/groups?limit=100', { enabled: isManager });
  const groupOptions = useMemo(() => {
    const source = isManager ? (groups.data?.items ?? []) : groupsFromChildren(children.data?.items ?? []);
    return source.map((g) => ({ value: g.id, label: g.name }));
  }, [isManager, groups.data, children.data]);
  const childOptions = (children.data?.items ?? []).map((c) => ({ value: c.id, label: childName(c) }));

  const params = new URLSearchParams({ limit: '100', status });
  if (from !== '') {
    params.set('from', from);
  }
  if (to !== '') {
    params.set('to', to);
  }
  if (groupId !== '') {
    params.set('groupId', groupId);
  }
  if (childId !== '') {
    params.set('childId', childId);
  }
  if (kind !== '') {
    params.set('kind', kind);
  }
  const list = useOrgQuery<ApiPage<Absence>>(['absences'], `/absences?${params.toString()}`, { enabled: org.can('ABSENCE_READ') });
  const cancel = useOrgMutation(['absences', 'schedules']);

  if (!org.can('ABSENCE_READ')) {
    return (
      <Page title={t('nav.absences')}>
        <p className="vc-muted">{t('ui.noAccess')}</p>
      </Page>
    );
  }

  const closeReport = () => {
    setReporting(false);
    if (reportFor !== null) {
      setSearchParams({});
    }
  };

  return (
    <Page
      title={t('nav.absences')}
      actions={
        canReport ? (
          <button
            type="button"
            className="vc-button vc-button--primary"
            onClick={() => {
              setReporting(true);
            }}
          >
            {t('absences.report')}
          </button>
        ) : undefined
      }
    >
      {org.role === 'TEACHER' ? <p className="vc-muted">{t('absences.teacherHint')}</p> : null}
      <div className="vc-toolbar">
        <InputField id="absences-from" type="date" label={t('ui.from')} value={from} onChange={setFrom} />
        <InputField id="absences-to" type="date" label={t('ui.to')} value={to} onChange={setTo} />
        {isParent ? null : <SelectField id="absences-group" label={t('absences.group')} value={groupId} onChange={setGroupId} options={groupOptions} emptyLabel={t('absences.allGroups')} />}
        <SelectField id="absences-child" label={t('absences.child')} value={childId} onChange={setChildId} options={childOptions} emptyLabel={t('absences.allChildren')} />
        <SelectField id="absences-kind" label={t('absences.kind')} value={kind} onChange={setKind} options={ABSENCE_KINDS.map((k) => ({ value: k, label: t(`absences.kinds.${k}`) }))} emptyLabel={t('absences.allKinds')} />
        <SelectField
          id="absences-status"
          label={t('ui.status')}
          value={status}
          onChange={(v) => {
            setStatus(v === 'CANCELLED' ? 'CANCELLED' : 'ACTIVE');
          }}
          options={[
            { value: 'ACTIVE', label: t('absences.status.ACTIVE') },
            { value: 'CANCELLED', label: t('absences.status.CANCELLED') },
          ]}
        />
      </div>
      <ProblemAlert error={list.error} />
      <ProblemAlert error={cancel.error} />
      {list.isPending ? <Loading /> : null}
      {list.data?.items.length === 0 ? <EmptyState message={t('absences.empty')} /> : null}
      {list.data !== undefined && list.data.items.length > 0 ? (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th>{t('absences.child')}</th>
                <th>{t('absences.kind')}</th>
                <th>{t('ui.from')}</th>
                <th>{t('ui.to')}</th>
                <th>{t('ui.note')}</th>
                <th>{t('ui.status')}</th>
                {canReport ? <th>{t('ui.actions')}</th> : null}
              </tr>
            </thead>
            <tbody>
              {list.data.items.map((a) => (
                <tr key={a.id}>
                  <td>{`${a.childGivenName} ${a.childFamilyName}`}</td>
                  <td>{t(`absences.kinds.${a.kind}`)}</td>
                  <td>{format.date(a.dateFrom)}</td>
                  <td>{format.date(a.dateTo)}</td>
                  <td>{a.note ?? ''}</td>
                  <td>
                    <Badge tone={a.status === 'ACTIVE' ? 'info' : 'neutral'}>{t(`absences.status.${a.status}`)}</Badge>
                  </td>
                  {canReport ? (
                    <td className="vc-actions">
                      {a.canCancel ? (
                        <button
                          type="button"
                          className="vc-button vc-button--small vc-button--danger"
                          disabled={cancel.isPending}
                          onClick={() => {
                            if (window.confirm(t('absences.confirmCancel'))) {
                              cancel.mutate({ method: 'POST', path: `/absences/${a.id}/cancel` });
                            }
                          }}
                        >
                          {t('absences.cancel')}
                        </button>
                      ) : null}
                    </td>
                  ) : null}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      {canReport && reporting ? <ReportAbsenceModal childOptions={childOptions} initialChildId={reportFor ?? ''} onClose={closeReport} /> : null}
    </Page>
  );
}
