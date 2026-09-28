import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg, useOrgMutation, useOrgQuery } from '../../api/org';
import { Alert } from '../../components/Alert';
import { EmptyState } from '../../components/EmptyState';
import { InputField } from '../../components/Form';
import { Badge, Loading, Page } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { GuardianLinkModal, GuardianRevokeModal, GuardianStatusBadge } from '../children/GuardianModals';
import type { ChildSummary, Page as ListPage, ParentLink, ParentOverview } from '../children/types';

import '../children/children.css';

/** One guardian of a child as shown in the per-child table. */
interface ChildGuardian {
  readonly parentName: string;
  readonly link: ParentLink;
}

interface ChildRow {
  readonly childId: string;
  readonly childName: string;
  readonly groupName: string;
  readonly guardians: readonly ChildGuardian[];
}

/**
 * /parents (GUARDIAN_MANAGE). Organized by CHILD: each child row shows its mother, father and other
 * guardians, so a parent with two children is never read as "two mothers". Below: parent accounts
 * (who has an account, who is not linked yet) with the "link to child" action.
 */
export function ParentsPage() {
  const { t } = useTranslation();
  const org = useOrg();
  const canManage = org.can('GUARDIAN_MANAGE');
  const parents = useOrgQuery<{ readonly items: readonly ParentOverview[] }>(['parents'], '/parents', { enabled: canManage });
  const children = useOrgQuery<ListPage<ChildSummary>>(['children'], '/children?limit=100', { enabled: canManage });
  const confirm = useOrgMutation(['parents', 'children']);
  const [search, setSearch] = useState('');
  const [linking, setLinking] = useState<{ readonly childId?: string; readonly membershipId?: string } | null>(null);
  const [revoking, setRevoking] = useState<{ readonly label: string; readonly guardianId: string } | null>(null);

  if (!canManage) {
    return (
      <Page title={t('nav.parents')}>
        <p className="vc-muted">{t('ui.noAccess')}</p>
      </Page>
    );
  }

  // child id -> non-revoked guardians (from the parent overview)
  const guardiansByChild = new Map<string, ChildGuardian[]>();
  for (const parent of parents.data?.items ?? []) {
    for (const link of parent.links) {
      if (link.status === 'REVOKED') {
        continue;
      }
      const list = guardiansByChild.get(link.childId) ?? [];
      list.push({ parentName: parent.displayName, link });
      guardiansByChild.set(link.childId, list);
    }
  }

  const needle = search.trim().toLowerCase();
  const rows: ChildRow[] = (children.data?.items ?? [])
    .map((c) => ({
      childId: c.id,
      childName: `${c.givenName} ${c.familyName}`,
      groupName: c.currentEnrollment?.groupName ?? '',
      guardians: guardiansByChild.get(c.id) ?? [],
    }))
    .filter(
      (r) =>
        needle === '' ||
        r.childName.toLowerCase().includes(needle) ||
        r.guardians.some((g) => g.parentName.toLowerCase().includes(needle)),
    );

  const parentAccounts = (parents.data?.items ?? []).filter(
    (p) => needle === '' || p.displayName.toLowerCase().includes(needle) || p.email.toLowerCase().includes(needle),
  );

  const renderGuardians = (row: ChildRow, filter: (g: ChildGuardian) => boolean) => {
    const list = row.guardians.filter(filter);
    if (list.length === 0) {
      return <span className="vc-muted">-</span>;
    }
    return (
      <ul className="vc-list">
        {list.map((g) => (
          <li key={g.link.guardianId}>
            {g.parentName}
            {g.link.relationship === 'MOTHER' || g.link.relationship === 'FATHER' ? null : (
              <span className="vc-muted"> ({t(`children.relationship.${g.link.relationship}`)})</span>
            )}{' '}
            <GuardianStatusBadge status={g.link.status} />{' '}
            {g.link.status === 'PENDING' ? (
              <button
                type="button"
                className="vc-button vc-button--small vc-button--primary"
                disabled={confirm.isPending}
                onClick={() => {
                  confirm.mutate({ method: 'POST', path: `/guardians/${g.link.guardianId}/confirm` });
                }}
              >
                {t('children.confirmLink')}
              </button>
            ) : null}{' '}
            <button
              type="button"
              className="vc-button vc-button--small vc-button--danger"
              onClick={() => {
                setRevoking({ label: `${g.parentName}: ${row.childName}`, guardianId: g.link.guardianId });
              }}
            >
              {t('children.revoke')}
            </button>
          </li>
        ))}
      </ul>
    );
  };

  return (
    <Page title={t('nav.parents')}>
      <Alert variant="info" title={t('parents.inviteHint')} />
      <div className="vc-toolbar">
        <InputField id="parents-search" label={t('ui.search')} value={search} onChange={setSearch} />
      </div>
      <ProblemAlert error={parents.error ?? children.error} />
      <ProblemAlert error={confirm.error} />
      {parents.isPending || children.isPending ? <Loading /> : null}

      <section className="vc-section">
        <h2>{t('parents.byChild')}</h2>
        {children.data !== undefined && rows.length === 0 ? <EmptyState message={t('parents.empty')} /> : null}
        {rows.length > 0 ? (
          <div className="vc-table-wrap">
            <table className="vc-table">
              <thead>
                <tr>
                  <th>{t('parents.child')}</th>
                  <th>{t('children.relationship.MOTHER')}</th>
                  <th>{t('children.relationship.FATHER')}</th>
                  <th>{t('parents.others')}</th>
                  <th>{t('ui.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.childId}>
                    <td>
                      <strong>{row.childName}</strong>
                      <br />
                      <small className="vc-muted">{row.groupName}</small>
                    </td>
                    <td>{renderGuardians(row, (g) => g.link.relationship === 'MOTHER')}</td>
                    <td>{renderGuardians(row, (g) => g.link.relationship === 'FATHER')}</td>
                    <td>{renderGuardians(row, (g) => g.link.relationship !== 'MOTHER' && g.link.relationship !== 'FATHER')}</td>
                    <td className="vc-actions">
                      <button
                        type="button"
                        className="vc-button vc-button--small"
                        onClick={() => {
                          setLinking({ childId: row.childId });
                        }}
                      >
                        {t('parents.linkParent')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </section>

      <section className="vc-section">
        <h2>{t('parents.accounts')}</h2>
        {parentAccounts.length === 0 ? (
          <p className="vc-muted">{t('ui.none')}</p>
        ) : (
          <div className="vc-table-wrap">
            <table className="vc-table">
              <thead>
                <tr>
                  <th>{t('parents.parent')}</th>
                  <th>{t('parents.email')}</th>
                  <th>{t('parents.account')}</th>
                  <th>{t('parents.children')}</th>
                  <th>{t('ui.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {parentAccounts.map((p) => {
                  const active = p.links.filter((l) => l.status !== 'REVOKED');
                  return (
                    <tr key={p.membershipId}>
                      <td>{p.displayName}</td>
                      <td>{p.email}</td>
                      <td>
                        <Badge tone={p.status === 'ACTIVE' ? 'success' : 'neutral'}>{t(`parents.membershipStatus.${p.status}`)}</Badge>
                      </td>
                      <td>
                        {active.length === 0 ? (
                          <span className="vc-muted">{t('parents.noLinks')}</span>
                        ) : (
                          active.map((l) => `${l.childGivenName} ${l.childFamilyName}`).join(', ')
                        )}
                      </td>
                      <td className="vc-actions">
                        {p.status === 'ACTIVE' ? (
                          <button
                            type="button"
                            className="vc-button vc-button--small"
                            onClick={() => {
                              setLinking({ membershipId: p.membershipId });
                            }}
                          >
                            {t('parents.linkChild')}
                          </button>
                        ) : null}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {linking === null ? null : (
        <GuardianLinkModal
          {...(linking.childId === undefined ? {} : { childId: linking.childId })}
          {...(linking.membershipId === undefined ? {} : { membershipId: linking.membershipId })}
          onClose={() => {
            setLinking(null);
          }}
        />
      )}
      {revoking === null ? null : (
        <GuardianRevokeModal
          guardianId={revoking.guardianId}
          label={revoking.label}
          onClose={() => {
            setRevoking(null);
          }}
        />
      )}
    </Page>
  );
}
