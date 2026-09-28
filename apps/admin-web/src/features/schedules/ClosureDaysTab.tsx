import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg, useOrgMutation, useOrgQuery } from '../../api/org';
import { fieldError } from '../../api/problem';
import { todayIso, useFormat } from '../../app/format';
import { EmptyState } from '../../components/EmptyState';
import { CheckboxField, InputField, SelectField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { Badge, Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import type { NamedRef, Page as ListPage } from '../announcements/versionedMutation';
import type { ClosureDay } from './types';

/**
 * Closure days (GET /closure-days for every member). Managers (OWNER/ADMIN) add a day for the whole
 * kindergarten or one location and delete future days; everyone else sees the list read-only.
 */
export function ClosureDaysTab() {
  const { t } = useTranslation();
  const format = useFormat();
  const org = useOrg();
  const isManager = org.role === 'OWNER' || org.role === 'ADMIN';
  const [showPast, setShowPast] = useState(false);
  const today = todayIso();
  const list = useOrgQuery<ListPage<ClosureDay>>(['closure-days'], showPast ? '/closure-days?limit=100' : `/closure-days?from=${today}&limit=100`);
  const remove = useOrgMutation(['closure-days', 'schedules', 'calendar', 'dashboard']);
  const [adding, setAdding] = useState(false);

  const onDelete = (c: ClosureDay) => {
    if (window.confirm(t('ui.confirmDelete'))) {
      remove.mutate({ method: 'DELETE', path: `/closure-days/${c.id}` });
    }
  };

  return (
    <>
      <p className="vc-muted">{t('schedules.closures.hint')}</p>
      <div className="vc-toolbar">
        <CheckboxField id="closures-past" label={t('schedules.closures.showPast')} checked={showPast} onChange={setShowPast} />
        {isManager ? (
          <button type="button" className="vc-button vc-button--primary" onClick={() => { setAdding(true); }}>
            {t('schedules.closures.add')}
          </button>
        ) : null}
      </div>
      <ProblemAlert error={list.error} />
      <ProblemAlert error={remove.error} />
      {list.isPending ? <Loading /> : null}
      {list.data?.items.length === 0 ? <EmptyState message={t('schedules.closures.empty')} /> : null}
      {list.data === undefined || list.data.items.length === 0 ? null : (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th>{t('ui.date')}</th>
                <th>{t('ui.name')}</th>
                <th>{t('schedules.closures.scope')}</th>
                {isManager ? <th>{t('ui.actions')}</th> : null}
              </tr>
            </thead>
            <tbody>
              {list.data.items.map((c) => (
                <tr key={c.id}>
                  <td>{format.date(c.closureDate)}</td>
                  <td>{c.name}</td>
                  <td>{c.locationId == null ? <Badge tone="danger">{t('schedules.closures.wholeOrganization')}</Badge> : (c.locationName ?? '')}</td>
                  {isManager ? (
                    <td className="vc-actions">
                      {c.closureDate > today ? (
                        <button type="button" className="vc-button vc-button--small vc-button--danger" disabled={remove.isPending} onClick={() => { onDelete(c); }}>
                          {t('ui.delete')}
                        </button>
                      ) : null}
                    </td>
                  ) : null}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {adding ? <AddClosureModal onClose={() => { setAdding(false); }} /> : null}
    </>
  );
}

function AddClosureModal({ onClose }: { readonly onClose: () => void }) {
  const { t } = useTranslation();
  const locations = useOrgQuery<ListPage<NamedRef>>(['locations'], '/locations?limit=100');
  const save = useOrgMutation(['closure-days', 'schedules', 'calendar', 'dashboard']);
  const [date, setDate] = useState(todayIso());
  const [name, setName] = useState('');
  const [locationId, setLocationId] = useState('');

  const submit = (e: SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    const body = locationId === '' ? { closureDate: date, name: name.trim() } : { closureDate: date, name: name.trim(), locationId };
    save.mutate({ method: 'POST', path: '/closure-days', body }, { onSuccess: onClose });
  };

  return (
    <Modal
      open
      title={t('schedules.closures.add')}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="vc-button" onClick={onClose}>
            {t('ui.cancel')}
          </button>
          <button type="submit" form="closure-form" className="vc-button vc-button--primary" disabled={save.isPending || name.trim() === '' || date === ''}>
            {save.isPending ? t('ui.saving') : t('ui.save')}
          </button>
        </>
      }
    >
      <form id="closure-form" onSubmit={submit} noValidate>
        <ProblemAlert error={save.error} />
        <ProblemAlert error={locations.error} />
        <InputField id="closure-date" type="date" label={t('ui.date')} value={date} min={todayIso()} onChange={setDate} required error={fieldError(save.error, 'closureDate')} />
        <InputField id="closure-name" label={t('ui.name')} value={name} onChange={setName} required placeholder={t('schedules.closures.namePlaceholder')} error={fieldError(save.error, 'name')} />
        <SelectField
          id="closure-location"
          label={t('schedules.closures.scope')}
          value={locationId}
          onChange={setLocationId}
          emptyLabel={t('schedules.closures.wholeOrganization')}
          options={(locations.data?.items ?? []).map((l) => ({ value: l.id, label: l.name }))}
          error={fieldError(save.error, 'locationId')}
        />
      </form>
    </Modal>
  );
}
