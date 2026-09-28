import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg, useOrgMutation, useOrgQuery } from '../../api/org';
import { fieldError } from '../../api/problem';
import { EmptyState } from '../../components/EmptyState';
import { FieldRow, InputField, SelectField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { Badge, Loading, Page } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { type Location, type Page as ApiPage, textOrNull } from './types';

interface FormState {
  readonly name: string;
  readonly addressLine: string;
  readonly city: string;
  readonly postalCode: string;
  readonly phone: string;
}

const EMPTY_FORM: FormState = { name: '', addressLine: '', city: '', postalCode: '', phone: '' };

function formOf(location: Location): FormState {
  return {
    name: location.name,
    addressLine: location.addressLine ?? '',
    city: location.city ?? '',
    postalCode: location.postalCode ?? '',
    phone: location.phone ?? '',
  };
}

function addressOf(location: Location): string {
  const cityLine = [location.postalCode, location.city].filter((p): p is string => typeof p === 'string' && p.length > 0).join(' ');
  return [location.addressLine ?? '', cityLine].filter((p) => p.length > 0).join(', ');
}

export function LocationsPage() {
  const { t } = useTranslation();
  const { can } = useOrg();
  const canManage = can('LOCATION_MANAGE');
  const [status, setStatus] = useState<'ACTIVE' | 'INACTIVE'>('ACTIVE');
  const query = useOrgQuery<ApiPage<Location>>(['locations'], `/locations?status=${status}&limit=100`);
  const mutation = useOrgMutation<Location | undefined>(['locations', 'groups']);
  const [editing, setEditing] = useState<Location | 'new' | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);

  function openNew() {
    mutation.reset();
    setForm(EMPTY_FORM);
    setEditing('new');
  }

  function openEdit(location: Location) {
    mutation.reset();
    setForm(formOf(location));
    setEditing(location);
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
    const body = {
      name: form.name.trim(),
      addressLine: textOrNull(form.addressLine),
      city: textOrNull(form.city),
      postalCode: textOrNull(form.postalCode),
      phone: textOrNull(form.phone),
    };
    const request =
      editing === 'new'
        ? { method: 'POST' as const, path: '/locations', body }
        : { method: 'PATCH' as const, path: `/locations/${editing.id}`, body };
    mutation.mutate(request, {
      onSuccess: () => {
        setEditing(null);
      },
    });
  }

  function toggleStatus(location: Location) {
    mutation.mutate({
      method: 'PATCH',
      path: `/locations/${location.id}`,
      body: { status: location.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' },
    });
  }

  function remove(location: Location) {
    if (!window.confirm(t('locations.confirmDelete', { name: location.name }))) {
      return;
    }
    mutation.mutate({ method: 'DELETE', path: `/locations/${location.id}` });
  }

  const items = query.data?.items ?? [];
  const modalOpen = editing !== null;

  return (
    <Page
      title={t('nav.locations')}
      actions={
        <>
          <SelectField
            id="location-status"
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
          {canManage ? (
            <button type="button" className="vc-button vc-button--primary" onClick={openNew}>
              {t('locations.add')}
            </button>
          ) : null}
        </>
      }
    >
      {modalOpen ? null : <ProblemAlert error={mutation.error} />}
      <ProblemAlert error={query.error} />
      {query.isPending ? <Loading /> : null}
      {query.isSuccess && items.length === 0 ? <EmptyState message={t('locations.empty')} /> : null}
      {items.length > 0 ? (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th scope="col">{t('ui.name')}</th>
                <th scope="col">{t('locations.address')}</th>
                <th scope="col">{t('locations.phone')}</th>
                <th scope="col">{t('locations.activeGroups')}</th>
                <th scope="col">{t('ui.status')}</th>
                {canManage ? <th scope="col">{t('ui.actions')}</th> : null}
              </tr>
            </thead>
            <tbody>
              {items.map((location) => (
                <tr key={location.id}>
                  <td>{location.name}</td>
                  <td>{addressOf(location)}</td>
                  <td>{location.phone ?? ''}</td>
                  <td>{location.activeGroupsCount}</td>
                  <td>
                    <Badge tone={location.status === 'ACTIVE' ? 'success' : 'neutral'}>
                      {location.status === 'ACTIVE' ? t('ui.active') : t('ui.inactive')}
                    </Badge>
                  </td>
                  {canManage ? (
                    <td className="vc-actions">
                      <button
                        type="button"
                        className="vc-button vc-button--small"
                        onClick={() => {
                          openEdit(location);
                        }}
                      >
                        {t('ui.edit')}
                      </button>
                      <button
                        type="button"
                        className="vc-button vc-button--small"
                        disabled={mutation.isPending}
                        onClick={() => {
                          toggleStatus(location);
                        }}
                      >
                        {location.status === 'ACTIVE' ? t('locations.deactivate') : t('locations.activate')}
                      </button>
                      <button
                        type="button"
                        className="vc-button vc-button--small vc-button--danger"
                        disabled={mutation.isPending}
                        onClick={() => {
                          remove(location);
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

      <Modal title={editing === 'new' ? t('locations.add') : t('locations.edit')} open={modalOpen} onClose={close}>
        <form onSubmit={submit} noValidate>
          <ProblemAlert error={mutation.error} />
          <InputField
            id="location-name"
            label={t('ui.name')}
            value={form.name}
            onChange={(v) => {
              setForm({ ...form, name: v });
            }}
            required
            error={fieldError(mutation.error, 'name')}
          />
          <InputField
            id="location-address"
            label={t('locations.addressLine')}
            value={form.addressLine}
            onChange={(v) => {
              setForm({ ...form, addressLine: v });
            }}
            error={fieldError(mutation.error, 'addressLine')}
          />
          <FieldRow>
            <InputField
              id="location-postal"
              label={t('locations.postalCode')}
              value={form.postalCode}
              onChange={(v) => {
                setForm({ ...form, postalCode: v });
              }}
              error={fieldError(mutation.error, 'postalCode')}
            />
            <InputField
              id="location-city"
              label={t('locations.city')}
              value={form.city}
              onChange={(v) => {
                setForm({ ...form, city: v });
              }}
              error={fieldError(mutation.error, 'city')}
            />
          </FieldRow>
          <InputField
            id="location-phone"
            type="tel"
            label={t('locations.phone')}
            value={form.phone}
            onChange={(v) => {
              setForm({ ...form, phone: v });
            }}
            error={fieldError(mutation.error, 'phone')}
          />
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
    </Page>
  );
}
