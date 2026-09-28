import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrgMutation, useOrgQuery } from '../../api/org';
import { fieldError } from '../../api/problem';
import { useFormat } from '../../app/format';
import { EmptyState } from '../../components/EmptyState';
import { FieldRow, InputField, SelectField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { type Location, type Page, textOrNull } from '../locations/types';
import type { Employee } from './types';

interface FormState {
  readonly displayName: string;
  readonly jobTitle: string;
  readonly phone: string;
  readonly primaryLocationId: string;
  readonly startedAt: string;
  readonly endedAt: string;
}

function formOf(e: Employee): FormState {
  return {
    displayName: e.displayName,
    jobTitle: e.jobTitle ?? '',
    phone: e.phone ?? '',
    primaryLocationId: e.primaryLocationId ?? '',
    startedAt: e.startedAt ?? '',
    endedAt: e.endedAt ?? '',
  };
}

/** Staff profiles (app.employees) with an edit modal. MEMBER_MANAGE only (the page checks it). */
export function StaffTab() {
  const { t } = useTranslation();
  const format = useFormat();
  const [status, setStatus] = useState<'ACTIVE' | 'REVOKED'>('ACTIVE');
  const query = useOrgQuery<Page<Employee>>(['employees'], `/employees?membershipStatus=${status}&limit=100`);
  const locations = useOrgQuery<Page<Location>>(['locations'], '/locations?limit=100');
  const mutation = useOrgMutation<Employee>(['employees', 'groups']);
  const [editing, setEditing] = useState<Employee | null>(null);
  const [form, setForm] = useState<FormState | null>(null);

  function openEdit(e: Employee) {
    mutation.reset();
    setForm(formOf(e));
    setEditing(e);
  }

  function close() {
    setEditing(null);
    mutation.reset();
  }

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (editing === null || form === null) {
      return;
    }
    mutation.mutate(
      {
        method: 'PATCH',
        path: `/employees/${editing.id}`,
        body: {
          displayName: form.displayName.trim(),
          jobTitle: textOrNull(form.jobTitle),
          phone: textOrNull(form.phone),
          primaryLocationId: form.primaryLocationId.length > 0 ? form.primaryLocationId : null,
          startedAt: form.startedAt.length > 0 ? form.startedAt : null,
          endedAt: form.endedAt.length > 0 ? form.endedAt : null,
        },
      },
      {
        onSuccess: () => {
          setEditing(null);
        },
      },
    );
  }

  const items = query.data?.items ?? [];
  const update = (patch: Partial<FormState>) => {
    setForm((current) => (current === null ? current : { ...current, ...patch }));
  };

  return (
    <section aria-labelledby="staff-heading">
      <div className="vc-toolbar">
        <h2 id="staff-heading">{t('employees.tabs.staff')}</h2>
        <SelectField
          id="staff-status"
          label={t('ui.status')}
          value={status}
          onChange={(v) => {
            setStatus(v === 'REVOKED' ? 'REVOKED' : 'ACTIVE');
          }}
          options={[
            { value: 'ACTIVE', label: t('employees.status.ACTIVE') },
            { value: 'REVOKED', label: t('employees.status.REVOKED') },
          ]}
        />
      </div>
      {editing === null ? <ProblemAlert error={mutation.error} /> : null}
      <ProblemAlert error={query.error} />
      {query.isPending ? <Loading /> : null}
      {query.isSuccess && items.length === 0 ? <EmptyState message={t('employees.staffEmpty')} /> : null}
      {items.length > 0 ? (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th scope="col">{t('ui.name')}</th>
                <th scope="col">{t('employees.email')}</th>
                <th scope="col">{t('employees.role')}</th>
                <th scope="col">{t('employees.jobTitle')}</th>
                <th scope="col">{t('employees.phone')}</th>
                <th scope="col">{t('employees.primaryLocation')}</th>
                <th scope="col">{t('employees.groups')}</th>
                <th scope="col">{t('employees.startedAt')}</th>
                <th scope="col">{t('ui.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((e) => (
                <tr key={e.id}>
                  <td>{e.displayName}</td>
                  <td>{e.email}</td>
                  <td>{t(`roles.${e.role}`)}</td>
                  <td>{e.jobTitle ?? ''}</td>
                  <td>{e.phone ?? ''}</td>
                  <td>{e.primaryLocationName ?? ''}</td>
                  <td>{e.groups.map((g) => g.groupName).join(', ')}</td>
                  <td>{format.date(e.startedAt)}</td>
                  <td className="vc-actions">
                    <button
                      type="button"
                      className="vc-button vc-button--small"
                      onClick={() => {
                        openEdit(e);
                      }}
                    >
                      {t('ui.edit')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      <Modal title={t('employees.editProfile')} open={editing !== null} onClose={close}>
        {form === null ? null : (
          <form onSubmit={submit} noValidate>
            <ProblemAlert error={mutation.error} />
            <InputField
              id="employee-name"
              label={t('employees.displayName')}
              value={form.displayName}
              onChange={(v) => {
                update({ displayName: v });
              }}
              required
              error={fieldError(mutation.error, 'displayName')}
            />
            <FieldRow>
              <InputField
                id="employee-title"
                label={t('employees.jobTitle')}
                value={form.jobTitle}
                onChange={(v) => {
                  update({ jobTitle: v });
                }}
                error={fieldError(mutation.error, 'jobTitle')}
              />
              <InputField
                id="employee-phone"
                type="tel"
                label={t('employees.phone')}
                value={form.phone}
                onChange={(v) => {
                  update({ phone: v });
                }}
                error={fieldError(mutation.error, 'phone')}
              />
            </FieldRow>
            <SelectField
              id="employee-location"
              label={t('employees.primaryLocation')}
              value={form.primaryLocationId}
              onChange={(v) => {
                update({ primaryLocationId: v });
              }}
              emptyLabel={t('ui.none')}
              options={(locations.data?.items ?? []).map((l) => ({ value: l.id, label: l.name }))}
              error={fieldError(mutation.error, 'primaryLocationId')}
            />
            <FieldRow>
              <InputField
                id="employee-started"
                type="date"
                label={t('employees.startedAt')}
                value={form.startedAt}
                onChange={(v) => {
                  update({ startedAt: v });
                }}
                error={fieldError(mutation.error, 'startedAt')}
              />
              <InputField
                id="employee-ended"
                type="date"
                label={t('employees.endedAt')}
                value={form.endedAt}
                onChange={(v) => {
                  update({ endedAt: v });
                }}
                error={fieldError(mutation.error, 'endedAt')}
              />
            </FieldRow>
            <div className="vc-modal-footer">
              <button type="button" className="vc-button" onClick={close}>
                {t('ui.cancel')}
              </button>
              <button type="submit" className="vc-button vc-button--primary" disabled={mutation.isPending}>
                {mutation.isPending ? t('ui.saving') : t('ui.save')}
              </button>
            </div>
          </form>
        )}
      </Modal>
    </section>
  );
}
