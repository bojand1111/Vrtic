import { useTranslation } from 'react-i18next';

import { useOrg, useOrgQuery } from '../../api/org';
import { ApiProblem } from '../../api/problem';
import { useFormat } from '../../app/format';
import { Alert } from '../../components/Alert';
import { EmptyState } from '../../components/EmptyState';
import { Badge, Loading, Page } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { type EffectiveFlags, formatMoney, isKnownLimit, type Subscription } from './billingTypes';
import { statusTone, useFlagName } from './billingUi';
import { FlagTable } from './FlagTable';

/**
 * `/billing` (BILLING_VIEW, OWNER): current plan, status, trial and period, limits, entitlements and
 * the effective feature flags. Read-only; online payment is not part of this version.
 */
export function BillingPage() {
  const { t, i18n } = useTranslation();
  const format = useFormat();
  const flagName = useFlagName();
  const { can } = useOrg();
  const allowed = can('BILLING_VIEW');
  const subscription = useOrgQuery<Subscription>(['billing', 'subscription'], '/subscription', { enabled: allowed });
  const flags = useOrgQuery<EffectiveFlags>(['billing', 'flags'], '/feature-flags', { enabled: allowed });

  if (!allowed) {
    return (
      <Page title={t('nav.billing')}>
        <Alert variant="info" title={t('ui.noAccess')} />
      </Page>
    );
  }

  const none = subscription.error instanceof ApiProblem && subscription.error.status === 404;
  const sub = subscription.data;
  return (
    <Page title={t('nav.billing')}>
      <p className="vc-muted">{t('billing.intro')}</p>
      <Alert variant="info" title={t('billing.paymentsNote')} />
      {subscription.isPending ? <Loading /> : null}
      {none ? <EmptyState message={t('billing.none')} /> : <ProblemAlert error={subscription.error} />}
      {sub === undefined ? null : (
        <>
          <section className="vc-section" aria-labelledby="billing-plan">
            <h2 id="billing-plan">{t('billing.plan')}</h2>
            <dl>
              <dt>{t('billing.plan')}</dt>
              <dd>{t('billing.planVersion', { name: sub.plan.name, code: sub.plan.code, version: sub.plan.version })}</dd>
              <dt>{t('billing.price')}</dt>
              <dd>{formatMoney(sub.plan.monthlyPriceMinor, sub.plan.currency, i18n.language)}</dd>
              <dt>{t('billing.status')}</dt>
              <dd>
                <Badge tone={statusTone(sub.status)}>{t(`billing.statuses.${sub.status}`)}</Badge>
                {sub.cancelAtPeriodEnd ? <> {t('billing.cancelAtPeriodEnd')}</> : null}
              </dd>
              {sub.trialEndsAt === null || sub.trialEndsAt === undefined ? null : (
                <>
                  <dt>{t('billing.trialEndsAt')}</dt>
                  <dd>{format.date(sub.trialEndsAt)}</dd>
                </>
              )}
              <dt>{t('billing.periodStart')}</dt>
              <dd>{format.date(sub.currentPeriodStart)}</dd>
              <dt>{t('billing.periodEnd')}</dt>
              <dd>{format.date(sub.currentPeriodEnd)}</dd>
              <dt>{t('billing.provider')}</dt>
              <dd>{t(`billing.providers.${sub.provider}`)}</dd>
            </dl>
          </section>
          <section className="vc-section" aria-labelledby="billing-limits">
            <h2 id="billing-limits">{t('billing.limits')}</h2>
            {Object.keys(sub.plan.limits).length === 0 ? (
              <p className="vc-muted">{t('billing.noLimits')}</p>
            ) : (
              <ul className="vc-list">
                {Object.entries(sub.plan.limits).map(([key, value]) => (
                  <li key={key}>{`${isKnownLimit(key) ? t(`billing.limitNames.${key}`) : key}: ${String(value)}`}</li>
                ))}
              </ul>
            )}
            <h3>{t('billing.entitlements')}</h3>
            <ul className="vc-list">
              {Object.entries(sub.plan.entitlements).map(([key, value]) => (
                <li key={key}>
                  {flagName(key)}: {value ? t('billing.on') : t('billing.off')}
                </li>
              ))}
            </ul>
          </section>
        </>
      )}
      <section className="vc-section" aria-labelledby="billing-flags">
        <h2 id="billing-flags">{t('billing.flags')}</h2>
        <ProblemAlert error={flags.error} />
        {flags.isPending ? <Loading /> : null}
        {flags.data === undefined ? null : <FlagTable flags={flags.data} />}
      </section>
    </Page>
  );
}
