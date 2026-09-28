import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { useFormat } from '../../app/format';
import { Alert } from '../../components/Alert';
import { EmptyState } from '../../components/EmptyState';
import { InputField, SelectField } from '../../components/Form';
import { Badge, Loading, Page } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { TenantSwitcher } from '../../tenant/TenantSwitcher';
import { useTenant } from '../../tenant/useTenant';
import { CreateOrganizationModal } from './CreateOrganizationModal';
import { GlobalFlags } from './GlobalFlags';
import { OrganizationDetail } from './OrganizationDetail';
import { isMfaProblem, listOrganizations, orgStatusTone, type OrganizationStatus } from './platformApi';

const STATUSES: readonly OrganizationStatus[] = ['ACTIVE', 'SUSPENDED', 'ARCHIVED'];

/**
 * `/organizations`: platform administration for SUPER_ADMIN (list, create with OWNER invitation,
 * suspend/reactivate, subscription, feature flags); for everyone else the kindergarten switcher.
 */
export function OrganizationsPage() {
  const { t } = useTranslation();
  const { isPlatformAdmin } = useTenant();
  return (
    <Page title={t('nav.organizations')}>
      {isPlatformAdmin ? <PlatformOrganizations /> : null}
      <section className="vc-section" aria-labelledby="org-switcher">
        <h2 id="org-switcher" className="vc-visually-hidden">
          {t('organizations.switcherIntro')}
        </h2>
        <p>{t('organizations.switcherIntro')}</p>
        <TenantSwitcher id="organizations-tenant-switcher" />
      </section>
    </Page>
  );
}

function PlatformOrganizations() {
  const { t } = useTranslation();
  const format = useFormat();
  const { userId } = useTenant();
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const [creating, setCreating] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const effectiveSearch = search.trim().length >= 2 ? search.trim() : '';
  const list = useQuery({
    queryKey: ['platform', userId, 'organizations', effectiveSearch, status],
    queryFn: ({ signal }) => listOrganizations(effectiveSearch, status, signal),
  });

  if (isMfaProblem(list.error)) {
    return (
      <section className="vc-section">
        <Alert variant="info" title={t('organizations.mfaNeeded')} />
        <Link className="vc-button vc-button--primary" to="/mfa" state={{ from: '/organizations' }}>
          {t('organizations.mfaLink')}
        </Link>
      </section>
    );
  }

  const items = list.data?.items ?? [];
  return (
    <>
      <div className="vc-toolbar">
        <InputField id="org-search" label={t('organizations.search')} value={search} onChange={setSearch} hint={t('organizations.searchHint')} />
        <SelectField
          id="org-status"
          label={t('ui.status')}
          value={status}
          onChange={setStatus}
          emptyLabel={t('ui.all')}
          options={STATUSES.map((s) => ({ value: s, label: t(`organizations.statuses.${s}`) }))}
        />
        <button
          type="button"
          className="vc-button vc-button--primary"
          onClick={() => {
            setNotice(null);
            setCreating(true);
          }}
        >
          {t('organizations.create.button')}
        </button>
      </div>
      {notice === null ? null : <Alert variant="info" title={notice} />}
      <ProblemAlert error={list.error} />
      {list.isPending ? <Loading /> : null}
      {list.isSuccess && items.length === 0 ? <EmptyState message={t('organizations.empty')} /> : null}
      {items.length > 0 ? (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th scope="col">{t('ui.name')}</th>
                <th scope="col">{t('organizations.slug')}</th>
                <th scope="col">{t('ui.status')}</th>
                <th scope="col">{t('organizations.created')}</th>
                <th scope="col">{t('ui.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((o) => (
                <tr key={o.id} aria-selected={selected === o.id}>
                  <td>{o.name}</td>
                  <td>{o.slug}</td>
                  <td>
                    <Badge tone={orgStatusTone(o.status)}>{t(`organizations.statuses.${o.status}`)}</Badge>
                  </td>
                  <td>{format.date(o.createdAt)}</td>
                  <td className="vc-actions">
                    <button
                      type="button"
                      className="vc-button vc-button--small"
                      onClick={() => {
                        setSelected(selected === o.id ? null : o.id);
                      }}
                    >
                      {selected === o.id ? t('organizations.detail.close') : t('organizations.detail.open')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      {selected === null ? null : <OrganizationDetail id={selected} />}
      <GlobalFlags />
      <CreateOrganizationModal
        open={creating}
        onClose={() => {
          setCreating(false);
        }}
        onCreated={(organization, ownerEmail) => {
          setCreating(false);
          setSelected(organization.id);
          setNotice(t('organizations.create.done', { email: ownerEmail }));
        }}
      />
    </>
  );
}
