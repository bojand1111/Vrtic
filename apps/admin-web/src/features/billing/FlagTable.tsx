import { useTranslation } from 'react-i18next';

import { Badge } from '../../components/Page';
import type { EffectiveFlags } from './billingTypes';
import { useFlagName } from './billingUi';

/** Effective feature flags (value and the layer that decided it). */
export function FlagTable({ flags }: { readonly flags: EffectiveFlags }) {
  const { t } = useTranslation();
  const flagName = useFlagName();
  return (
    <div className="vc-table-wrap">
      <table className="vc-table">
        <thead>
          <tr>
            <th scope="col">{t('billing.flag')}</th>
            <th scope="col">{t('billing.enabled')}</th>
            <th scope="col">{t('billing.source')}</th>
          </tr>
        </thead>
        <tbody>
          {flags.flags.map((f) => (
            <tr key={f.key}>
              <td>{flagName(f.key)}</td>
              <td>
                <Badge tone={f.enabled ? 'success' : 'neutral'}>{f.enabled ? t('billing.on') : t('billing.off')}</Badge>
              </td>
              <td>{t(`billing.sources.${f.source}`)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
