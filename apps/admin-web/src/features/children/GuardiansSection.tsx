import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg, useOrgMutation } from '../../api/org';
import { ProblemAlert } from '../../components/ProblemAlert';
import { GuardianEditModal, GuardianLinkModal, GuardianRevokeModal, GuardianStatusBadge } from './GuardianModals';
import type { ChildDetail, Guardian } from './types';

/** Guardians of a child. Staff see every link; GUARDIAN_MANAGE links, confirms, edits and revokes. */
export function GuardiansSection({ child }: { readonly child: ChildDetail }) {
  const { t } = useTranslation();
  const org = useOrg();
  const canManage = org.can('GUARDIAN_MANAGE');
  const [linking, setLinking] = useState(false);
  const [editing, setEditing] = useState<Guardian | null>(null);
  const [revoking, setRevoking] = useState<Guardian | null>(null);
  const confirm = useOrgMutation(['children', 'parents']);
  const visible = canManage ? child.guardians : child.guardians.filter((g) => g.status !== 'REVOKED');

  const rights = (g: Guardian) =>
    [
      g.canManageSchedule ? t('children.flags.canManageSchedule') : null,
      g.canReportAbsence ? t('children.flags.canReportAbsence') : null,
      g.canGiveConsent ? t('children.flags.canGiveConsent') : null,
      g.canViewHealth ? t('children.flags.canViewHealth') : null,
    ]
      .filter((v): v is string => v !== null)
      .join(', ');

  return (
    <section className="vc-section">
      <h2>{t('children.sections.guardians')}</h2>
      <ProblemAlert error={confirm.error} />
      {canManage ? (
        <div className="vc-toolbar">
          <button
            type="button"
            className="vc-button vc-button--small"
            onClick={() => {
              setLinking(true);
            }}
          >
            {t('children.linkParent')}
          </button>
        </div>
      ) : null}
      {visible.length === 0 ? (
        <p className="vc-muted">{t('children.noGuardians')}</p>
      ) : (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th>{t('children.nameColumn')}</th>
                <th>{t('children.relationshipLabel')}</th>
                <th>{t('ui.status')}</th>
                <th>{t('children.guardianRights')}</th>
                {canManage ? <th>{t('ui.actions')}</th> : null}
              </tr>
            </thead>
            <tbody>
              {visible.map((g) => (
                <tr key={g.id}>
                  <td>
                    {g.givenName} {g.familyName}
                    {g.isPrimary ? <span className="vc-muted"> ({t('children.flags.isPrimary')})</span> : null}
                  </td>
                  <td>{t(`children.relationship.${g.relationship}`)}</td>
                  <td>
                    <GuardianStatusBadge status={g.status} />
                  </td>
                  <td>{rights(g)}</td>
                  {canManage ? (
                    <td className="vc-actions">
                      {g.status === 'PENDING' ? (
                        <button
                          type="button"
                          className="vc-button vc-button--small vc-button--primary"
                          disabled={confirm.isPending}
                          onClick={() => {
                            confirm.mutate({ method: 'POST', path: `/guardians/${g.id}/confirm` });
                          }}
                        >
                          {t('children.confirmLink')}
                        </button>
                      ) : null}
                      {g.status === 'REVOKED' ? null : (
                        <>
                          <button
                            type="button"
                            className="vc-button vc-button--small"
                            onClick={() => {
                              setEditing(g);
                            }}
                          >
                            {t('ui.edit')}
                          </button>
                          <button
                            type="button"
                            className="vc-button vc-button--small vc-button--danger"
                            onClick={() => {
                              setRevoking(g);
                            }}
                          >
                            {t('children.revoke')}
                          </button>
                        </>
                      )}
                    </td>
                  ) : null}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {canManage ? <p className="vc-muted">{t('children.inviteHint')}</p> : null}
      {linking ? (
        <GuardianLinkModal
          childId={child.id}
          onClose={() => {
            setLinking(false);
          }}
        />
      ) : null}
      {editing === null ? null : (
        <GuardianEditModal
          guardian={editing}
          onClose={() => {
            setEditing(null);
          }}
        />
      )}
      {revoking === null ? null : (
        <GuardianRevokeModal
          guardianId={revoking.id}
          label={`${revoking.givenName} ${revoking.familyName}`}
          onClose={() => {
            setRevoking(null);
          }}
        />
      )}
    </section>
  );
}
