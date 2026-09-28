import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';

import { Badge, Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { useTenant } from '../../tenant/useTenant';
import { useFlagName } from '../billing/billingUi';
import { listFlags } from './platformApi';

/** Global feature flag registry (read-only here): defaults and kill switches. */
export function GlobalFlags() {
  const { t } = useTranslation();
  const flagName = useFlagName();
  const { userId } = useTenant();
  const flags = useQuery({ queryKey: ['platform', userId, 'flags'], queryFn: ({ signal }) => listFlags(signal) });
  return (
    <section className="vc-section" aria-labelledby="global-flags">
      <h2 id="global-flags">{t('organizations.flags.global')}</h2>
      <ProblemAlert error={flags.error} />
      {flags.isPending ? <Loading /> : null}
      {flags.data === undefined ? null : (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th scope="col">{t('organizations.flags.key')}</th>
                <th scope="col">{t('organizations.flags.description')}</th>
                <th scope="col">{t('organizations.flags.defaultEnabled')}</th>
                <th scope="col">{t('organizations.flags.killSwitch')}</th>
              </tr>
            </thead>
            <tbody>
              {flags.data.items.map((f) => (
                <tr key={f.key}>
                  <td>
                    {flagName(f.key)} <span className="vc-muted">{f.key}</span>
                  </td>
                  <td>{f.description}</td>
                  <td>{f.defaultEnabled ? t('ui.yes') : t('ui.no')}</td>
                  <td>{f.killSwitch ? <Badge tone="danger">{t('ui.yes')}</Badge> : t('ui.no')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
