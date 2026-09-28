import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { fieldError } from '../../api/problem';
import { TextAreaField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { Badge, Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { useTenant } from '../../tenant/useTenant';
import { useFlagName } from '../billing/billingUi';
import { getOrganizationFlags, removeOverride, setOverride } from './platformApi';

interface Pending {
  readonly key: string;
  readonly enabled: boolean;
}

/** Effective flags of one tenant with per-tenant overrides (kill switch still wins). */
export function OrganizationFlags({ organizationId }: { readonly organizationId: string }) {
  const { t } = useTranslation();
  const flagName = useFlagName();
  const { userId } = useTenant();
  const queryClient = useQueryClient();
  const key = ['platform', userId, 'org-flags', organizationId];
  const flags = useQuery({ queryKey: key, queryFn: ({ signal }) => getOrganizationFlags(organizationId, signal) });
  const [pending, setPending] = useState<Pending | null>(null);
  const [reason, setReason] = useState('');
  const refresh = () => queryClient.invalidateQueries({ queryKey: key });
  const set = useMutation({
    mutationFn: (p: Pending) => setOverride(organizationId, p.key, p.enabled, reason.trim()),
    onSuccess: async () => {
      setPending(null);
      setReason('');
      await refresh();
    },
  });
  const remove = useMutation({ mutationFn: (flagKey: string) => removeOverride(organizationId, flagKey), onSuccess: refresh });

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending !== null) {
      set.mutate(pending);
    }
  }

  return (
    <section className="vc-section" aria-labelledby="org-flags">
      <h3 id="org-flags">{t('organizations.flags.title')}</h3>
      <ProblemAlert error={flags.error} />
      <ProblemAlert error={remove.error} />
      {flags.isPending ? <Loading /> : null}
      {flags.data === undefined ? null : (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th scope="col">{t('organizations.flags.key')}</th>
                <th scope="col">{t('organizations.flags.effective')}</th>
                <th scope="col">{t('organizations.flags.source')}</th>
                <th scope="col">{t('organizations.flags.override')}</th>
              </tr>
            </thead>
            <tbody>
              {flags.data.flags.map((f) => (
                <tr key={f.key}>
                  <td>
                    {flagName(f.key)} <span className="vc-muted">{f.key}</span>
                  </td>
                  <td>
                    <Badge tone={f.enabled ? 'success' : 'neutral'}>{f.enabled ? t('billing.on') : t('billing.off')}</Badge>
                  </td>
                  <td>{t(`billing.sources.${f.source}`)}</td>
                  <td className="vc-actions">
                    <button
                      type="button"
                      className="vc-button vc-button--small"
                      onClick={() => {
                        set.reset();
                        setPending({ key: f.key, enabled: !f.enabled });
                      }}
                    >
                      {f.enabled ? t('organizations.flags.turnOff') : t('organizations.flags.turnOn')}
                    </button>
                    {f.source === 'TENANT_OVERRIDE' ? (
                      <button
                        type="button"
                        className="vc-button vc-button--small vc-button--ghost"
                        disabled={remove.isPending}
                        onClick={() => {
                          remove.mutate(f.key);
                        }}
                      >
                        {t('organizations.flags.removeOverride')}
                      </button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Modal
        title={t('organizations.flags.overrideTitle')}
        open={pending !== null}
        onClose={() => {
          setPending(null);
        }}
      >
        <form onSubmit={submit} noValidate aria-busy={set.isPending}>
          {pending === null ? null : (
            <p>
              {flagName(pending.key)}: <strong>{pending.enabled ? t('billing.on') : t('billing.off')}</strong>
            </p>
          )}
          <ProblemAlert error={set.error} />
          <TextAreaField id="flag-reason" label={t('organizations.flags.reason')} value={reason} onChange={setReason} rows={2} required error={fieldError(set.error, 'reason')} />
          <button type="submit" className="vc-button vc-button--primary" disabled={set.isPending}>
            {t('ui.save')}
          </button>
        </form>
      </Modal>
    </section>
  );
}
