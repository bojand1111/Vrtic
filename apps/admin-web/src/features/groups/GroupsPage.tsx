import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg, useOrgMutation, useOrgQuery } from '../../api/org';
import { fieldError } from '../../api/problem';
import { EmptyState } from '../../components/EmptyState';
import { FieldRow, InputField, SelectField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { Badge, Loading, Page } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import type { Location, Page as ApiPage } from '../locations/types';
import { TeachersModal } from './TeachersModal';
import { ageRange, type Group, intOrNull } from './types';

interface FormState {
  readonly locationId: string;
  readonly name: string;
  readonly ageFrom: string;
  readonly ageTo: string;
  readonly capacity: string;
}

const EMPTY_FORM: FormState = { locationId: '', name: '', ageFrom: '', ageTo: '', capacity: '' };

function formOf(group: Group): FormState {
  const str = (n: number | null | undefined) => (n === null || n === undefined ? '' : String(n));
  return { locationId: group.locationId, name: group.name, ageFrom: str(group.ageFromMonths), ageTo: str(group.ageToMonths), capacity: str(group.capacity) };
}

/**
 * Managers: every group with add/edit/deactivate/delete and teacher assignments.
 * TEACHER (own groups) and PARENT (children's groups): read-only list; the API applies the scope.
 */
export function GroupsPage() {
  const { t } = useTranslation();
  const { can } = useOrg();
  const canManage = can('GROUP_MANAGE');
  const canAssign = can('TEACHER_ASSIGN');
  const [status, setStatus] = useState<'ACTIVE' | 'INACTIVE'>('ACTIVE');
  const [locationFilter, setLocationFilter] = useState('');
  const locationParam = locationFilter.length > 0 ? `&locationId=${locationFilter}` : '';
  const query = useOrgQuery<ApiPage<Group>>(['groups'], `/groups?status=${status}&limit=100${locationParam}`);
  const locations = useOrgQuery<ApiPage<Location>>(['locations'], '/locations?limit=100', { enabled: canManage });
  const mutation = useOrgMutation<Group | undefined>(['groups', 'locations']);
  const [editing, setEditing] = useState<Group | 'new' | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [teachersOf, setTeachersOf] = useState<Group | null>(null);

  const locationOptions = (locations.data?.items ?? []).map((l) => ({ value: l.id, label: l.name }));

  function openNew() {
    mutation.reset();
    setForm({ ...EMPTY_FORM, locationId: locationOptions[0]?.value ?? '' });
    setEditing('new');
  }

  function openEdit(group: Group) {
    mutation.reset();
    setForm(formOf(group));
    setEditing(group);
  }

  function close() {
    setEditing(null);
    mutation.reset();
  }

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (editing === null) {
      return;
    }
    const numbers = { ageFromMonths: intOrNull(form.ageFrom), ageToMonths: intOrNull(form.ageTo), capacity: intOrNull(form.capacity) };
    const request =
      editing === 'new'
        ? {
            method: 'POST' as const,
            path: '/groups',
            // create: omit empty optional numbers (the create schema does not accept null)
            body: {
              locationId: form.locationId,
              name: form.name.trim(),
              ...Object.fromEntries(Object.entries(numbers).filter(([, v]) => v !== null)),
            },
          }
        : { method: 'PATCH' as const, path: `/groups/${editing.id}`, body: { name: form.name.trim(), ...numbers } };
    mutation.mutate(request, {
      onSuccess: () => {
        setEditing(null);
      },
    });
  }

  function toggleStatus(group: Group) {
    mutation.mutate({ method: 'PATCH', path: `/groups/${group.id}`, body: { status: group.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' } });
  }

  function remove(group: Group) {
    if (!window.confirm(t('groups.confirmDelete', { name: group.name }))) {
      return;
    }
    mutation.mutate({ method: 'DELETE', path: `/groups/${group.id}` });
  }

  const items = query.data?.items ?? [];
  const modalOpen = editing !== null;

  return (
    <Page
      title={t('nav.groups')}
      actions={
        canManage ? (
          <>
            <SelectField
              id="groups-location"
              label={t('groups.location')}
              value={locationFilter}
              onChange={setLocationFilter}
              emptyLabel={t('ui.all')}
              options={locationOptions}
            />
            <SelectField
              id="groups-status"
              label={t('ui.status')}
              value={status}
              onChange={(v) => {
                setStatus(v === 'INACTIVE' ? 'INACTIVE' : 'ACTIVE');
              }}
              options={[
                { value: 'ACTIVE', label: t('ui.active') },
                { value: 'INACTIVE', label: t('ui.inactive') },
              ]}
            />
            <button type="button" className="vc-button vc-button--primary" onClick={openNew} disabled={locationOptions.length === 0}>
              {t('groups.add')}
            </button>
          </>
        ) : undefined
      }
    >
      {canManage && locations.isSuccess && locationOptions.length === 0 ? <p className="vc-muted">{t('groups.needLocation')}</p> : null}
      {modalOpen ? null : <ProblemAlert error={mutation.error} />}
      <ProblemAlert error={query.error} />
      {query.isPending ? <Loading /> : null}
      {query.isSuccess && items.length === 0 ? <EmptyState message={canManage ? t('groups.empty') : t('groups.emptyOwn')} /> : null}
      {items.length > 0 ? (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th scope="col">{t('ui.name')}</th>
                <th scope="col">{t('groups.location')}</th>
                <th scope="col">{t('groups.ageMonths')}</th>
                <th scope="col">{t('groups.enrolled')}</th>
                <th scope="col">{t('groups.teachers')}</th>
                {canManage ? <th scope="col">{t('ui.status')}</th> : null}
                {canManage ? <th scope="col">{t('ui.actions')}</th> : null}
              </tr>
            </thead>
            <tbody>
              {items.map((group) => (
                <tr key={group.id}>
                  <td>{group.name}</td>
                  <td>{group.locationName}</td>
                  <td>{ageRange(group.ageFromMonths, group.ageToMonths)}</td>
                  <td>
                    {group.capacity === undefined || group.capacity === null
                      ? String(group.activeChildrenCount)
                      : `${String(group.activeChildrenCount)} / ${String(group.capacity)}`}
                  </td>
                  <td>
                    {group.teachers.length === 0
                      ? ''
                      : group.teachers.map((tc) => `${tc.displayName} (${t(`groups.assignmentRoles.${tc.assignmentRole}`)})`).join(', ')}
                  </td>
                  {canManage ? (
                    <td>
                      <Badge tone={group.status === 'ACTIVE' ? 'success' : 'neutral'}>
                        {group.status === 'ACTIVE' ? t('ui.active') : t('ui.inactive')}
                      </Badge>
                    </td>
                  ) : null}
                  {canManage ? (
                    <td className="vc-actions">
                      <button
                        type="button"
                        className="vc-button vc-button--small"
                        onClick={() => {
                          openEdit(group);
                        }}
                      >
                        {t('ui.edit')}
                      </button>
                      {canAssign ? (
                        <button
                          type="button"
                          className="vc-button vc-button--small"
                          onClick={() => {
                            setTeachersOf(group);
                          }}
                        >
                          {t('groups.teachers')}
                        </button>
                      ) : null}
                      <button
                        type="button"
                        className="vc-button vc-button--small"
                        disabled={mutation.isPending}
                        onClick={() => {
                          toggleStatus(group);
                        }}
                      >
                        {group.status === 'ACTIVE' ? t('groups.deactivate') : t('groups.activate')}
                      </button>
                      <button
                        type="button"
                        className="vc-button vc-button--small vc-button--danger"
                        disabled={mutation.isPending}
                        onClick={() => {
                          remove(group);
                        }}
                      >
                        {t('ui.delete')}
                      </button>
                    </td>
                  ) : null}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      <Modal title={editing === 'new' ? t('groups.add') : t('groups.edit')} open={modalOpen} onClose={close}>
        <form onSubmit={submit} noValidate>
          <ProblemAlert error={mutation.error} />
          <SelectField
            id="group-location"
            label={t('groups.location')}
            value={form.locationId}
            onChange={(v) => {
              setForm({ ...form, locationId: v });
            }}
            options={locationOptions}
            disabled={editing !== 'new'}
            required
            error={fieldError(mutation.error, 'locationId')}
          />
          <InputField
            id="group-name"
            label={t('ui.name')}
            value={form.name}
            onChange={(v) => {
              setForm({ ...form, name: v });
            }}
            required
            error={fieldError(mutation.error, 'name')}
          />
          <FieldRow>
            <InputField
              id="group-age-from"
              type="number"
              min={0}
              max={120}
              label={t('groups.ageFrom')}
              value={form.ageFrom}
              onChange={(v) => {
                setForm({ ...form, ageFrom: v });
              }}
              error={fieldError(mutation.error, 'ageFromMonths')}
            />
            <InputField
              id="group-age-to"
              type="number"
              min={0}
              max={120}
              label={t('groups.ageTo')}
              value={form.ageTo}
              onChange={(v) => {
                setForm({ ...form, ageTo: v });
              }}
              error={fieldError(mutation.error, 'ageToMonths')}
            />
            <InputField
              id="group-capacity"
              type="number"
              min={1}
              max={500}
              label={t('groups.capacity')}
              value={form.capacity}
              onChange={(v) => {
                setForm({ ...form, capacity: v });
              }}
              error={fieldError(mutation.error, 'capacity')}
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
      </Modal>
      {canAssign ? (
        <TeachersModal
          key={teachersOf?.id ?? 'none'}
          group={teachersOf}
          onClose={() => {
            setTeachersOf(null);
          }}
        />
      ) : null}
    </Page>
  );
}
