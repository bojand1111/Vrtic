import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrgQuery } from '../../api/org';
import { todayIso, useFormat } from '../../app/format';
import { EmptyState } from '../../components/EmptyState';
import { CheckboxField, InputField, SelectField, type SelectOption } from '../../components/Form';
import { Badge, Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { childName } from '../children/types';
import type { ScheduleChangePage } from './types';
import { describeState } from './week';

/**
 * Staff feed of schedule changes that start on a date (GET /schedules/changes): who changed what and whether it
 * was late. TEACHER: own groups only (backend scope); managers: every group.
 */
export function ChangesTab({ groups, initialDate, initialLateOnly }: { readonly groups: readonly SelectOption[]; readonly initialDate: string | null; readonly initialLateOnly: boolean }) {
  const { t } = useTranslation();
  const format = useFormat();
  const [date, setDate] = useState(initialDate ?? todayIso());
  const [groupId, setGroupId] = useState('');
  const [lateOnly, setLateOnly] = useState(initialLateOnly);
  const query = `/schedules/changes?date=${date}${groupId === '' ? '' : `&groupId=${encodeURIComponent(groupId)}`}${lateOnly ? '&lateOnly=true' : ''}&limit=100`;
  const changes = useOrgQuery<ScheduleChangePage>(['schedules', 'changes'], query, { enabled: date !== '' });
  const notAttending = t('schedules.notAttending');

  return (
    <>
      <p className="vc-muted">{t('schedules.changes.hint')}</p>
      <div className="vc-toolbar">
        <InputField id="changes-date" type="date" label={t('ui.date')} value={date} onChange={(v) => { if (v !== '') setDate(v); }} />
        <SelectField id="changes-group" label={t('schedules.group')} value={groupId} onChange={setGroupId} options={groups} emptyLabel={t('ui.all')} />
        <CheckboxField id="changes-late" label={t('schedules.changes.lateOnly')} checked={lateOnly} onChange={setLateOnly} />
      </div>
      <ProblemAlert error={changes.error} />
      {changes.isPending ? <Loading /> : null}
      {changes.data?.items.length === 0 ? <EmptyState message={t('schedules.changes.empty')} /> : null}
      {changes.data === undefined || changes.data.items.length === 0 ? null : (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th>{t('children.nameColumn')}</th>
                <th>{t('schedules.group')}</th>
                <th>{t('schedules.changes.kind')}</th>
                <th>{t('schedules.changes.before')}</th>
                <th>{t('schedules.changes.after')}</th>
                <th>{t('schedules.changes.by')}</th>
                <th>{t('schedules.changes.when')}</th>
              </tr>
            </thead>
            <tbody>
              {changes.data.items.map((c) => (
                <tr key={c.id}>
                  <td>
                    {childName(c)} {c.isLateChange ? <Badge tone="warning">{t('schedules.lateChange')}</Badge> : null}
                  </td>
                  <td>{c.groupName ?? ''}</td>
                  <td>{t(`schedules.changes.kinds.${c.changeKind}`)}</td>
                  <td>{describeState(c.before, notAttending)}</td>
                  <td>{c.changeKind === 'OVERRIDE_REMOVED' ? t('schedules.changes.backToTemplate') : describeState(c.after, notAttending)}</td>
                  <td>
                    {c.actorName ?? ''}
                    {c.actorRole == null ? null : <span className="vc-muted"> ({t(`roles.${c.actorRole}`)})</span>}
                  </td>
                  <td>{format.dateTime(c.occurredAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
