import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { fieldError } from '../../api/problem';
import { todayIso, useFormat } from '../../app/format';
import { CheckboxField, FieldRow, InputField, SelectField, TextAreaField } from '../../components/Form';
import { Badge, Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { useTenant } from '../../tenant/useTenant';
import type { Subscription } from '../billing/billingTypes';
import { statusTone } from '../billing/billingUi';
import { createSubscription, getSubscription, isNotFound, listPlans, type SubscriptionChange, updateSubscription } from './platformApi';

const CHANGE_STATUSES = ['ACTIVE', 'PAST_DUE', 'CANCELLED'] as const;

function plusDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** Subscription of one tenant: view, manual change (plan, status, cancellation) or creation when none exists. */
export function SubscriptionPanel({ organizationId }: { readonly organizationId: string }) {
  const { t } = useTranslation();
  const { userId } = useTenant();
  const subscription = useQuery({
    queryKey: ['platform', userId, 'subscription', organizationId],
    queryFn: ({ signal }) => getSubscription(organizationId, signal),
    retry: false,
  });
  const none = isNotFound(subscription.error);

  return (
    <section className="vc-section" aria-labelledby="org-subscription">
      <h3 id="org-subscription">{t('organizations.subscription.title')}</h3>
      {subscription.isPending ? <Loading /> : null}
      {none ? null : <ProblemAlert error={subscription.error} />}
      {subscription.data === undefined ? null : <SubscriptionEditor organizationId={organizationId} subscription={subscription.data} />}
      {none ? <SubscriptionCreator organizationId={organizationId} /> : null}
    </section>
  );
}

function usePlans() {
  const { userId } = useTenant();
  return useQuery({ queryKey: ['platform', userId, 'plans'], queryFn: ({ signal }) => listPlans(signal) });
}

function SubscriptionEditor({ organizationId, subscription }: { readonly organizationId: string; readonly subscription: Subscription }) {
  const { t } = useTranslation();
  const format = useFormat();
  const { userId } = useTenant();
  const queryClient = useQueryClient();
  const plans = usePlans();
  const [planId, setPlanId] = useState(subscription.plan.id);
  const [status, setStatus] = useState('');
  const [cancelAtPeriodEnd, setCancelAtPeriodEnd] = useState(subscription.cancelAtPeriodEnd);
  const [reason, setReason] = useState('');
  const mutation = useMutation({
    mutationFn: () => {
      const change: SubscriptionChange = {
        reason: reason.trim(),
        ...(planId !== subscription.plan.id ? { planId } : {}),
        ...(status === 'ACTIVE' || status === 'PAST_DUE' || status === 'CANCELLED' ? { status } : {}),
        ...(cancelAtPeriodEnd !== subscription.cancelAtPeriodEnd ? { cancelAtPeriodEnd } : {}),
      };
      return updateSubscription(organizationId, change);
    },
    onSuccess: async () => {
      setReason('');
      setStatus('');
      await queryClient.invalidateQueries({ queryKey: ['platform', userId] });
    },
  });
  const planOptions = (plans.data?.items ?? []).map((p) => ({ value: p.id, label: `${p.name} (${p.code} v${String(p.version)})` }));
  if (!planOptions.some((o) => o.value === subscription.plan.id)) {
    planOptions.unshift({ value: subscription.plan.id, label: `${subscription.plan.name} (${subscription.plan.code} v${String(subscription.plan.version)})` });
  }

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    mutation.mutate();
  }

  return (
    <>
      <dl>
        <dt>{t('organizations.subscription.plan')}</dt>
        <dd>{t('billing.planVersion', { name: subscription.plan.name, code: subscription.plan.code, version: subscription.plan.version })}</dd>
        <dt>{t('organizations.subscription.status')}</dt>
        <dd>
          <Badge tone={statusTone(subscription.status)}>{t(`billing.statuses.${subscription.status}`)}</Badge>
          {subscription.cancelAtPeriodEnd ? <> {t('billing.cancelAtPeriodEnd')}</> : null}
        </dd>
        {subscription.trialEndsAt === null || subscription.trialEndsAt === undefined ? null : (
          <>
            <dt>{t('billing.trialEndsAt')}</dt>
            <dd>{format.date(subscription.trialEndsAt)}</dd>
          </>
        )}
        <dt>{t('billing.periodStart')}</dt>
        <dd>{format.date(subscription.currentPeriodStart)}</dd>
        <dt>{t('billing.periodEnd')}</dt>
        <dd>{format.date(subscription.currentPeriodEnd)}</dd>
      </dl>
      <form onSubmit={submit} noValidate aria-busy={mutation.isPending}>
        <h4>{t('organizations.subscription.change')}</h4>
        <ProblemAlert error={mutation.error} />
        <FieldRow>
          <SelectField id="sub-plan" label={t('organizations.subscription.plan')} value={planId} onChange={setPlanId} options={planOptions} error={fieldError(mutation.error, 'planId')} />
          <SelectField
            id="sub-status"
            label={t('organizations.subscription.status')}
            value={status}
            onChange={setStatus}
            emptyLabel={t('organizations.subscription.keepStatus')}
            options={CHANGE_STATUSES.map((s) => ({ value: s, label: t(`billing.statuses.${s}`) }))}
          />
        </FieldRow>
        <p className="vc-muted">{t('organizations.subscription.planChangeNote')}</p>
        <CheckboxField id="sub-cancel" label={t('organizations.subscription.cancelAtPeriodEnd')} checked={cancelAtPeriodEnd} onChange={setCancelAtPeriodEnd} />
        <TextAreaField id="sub-reason" label={t('organizations.subscription.reason')} value={reason} onChange={setReason} rows={2} required error={fieldError(mutation.error, 'reason')} />
        <button type="submit" className="vc-button vc-button--primary" disabled={mutation.isPending}>
          {mutation.isPending ? t('ui.saving') : t('ui.save')}
        </button>
      </form>
    </>
  );
}

function SubscriptionCreator({ organizationId }: { readonly organizationId: string }) {
  const { t } = useTranslation();
  const { userId } = useTenant();
  const queryClient = useQueryClient();
  const plans = usePlans();
  const today = todayIso();
  const [planId, setPlanId] = useState('');
  const [status, setStatus] = useState<'TRIAL' | 'ACTIVE'>('TRIAL');
  const [start, setStart] = useState(today);
  const [end, setEnd] = useState(plusDays(today, 30));
  const mutation = useMutation({
    mutationFn: () =>
      createSubscription(organizationId, {
        planId,
        status,
        currentPeriodStart: `${start}T00:00:00Z`,
        currentPeriodEnd: `${end}T00:00:00Z`,
        ...(status === 'TRIAL' ? { trialEndsAt: `${end}T00:00:00Z` } : {}),
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['platform', userId] });
    },
  });

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    mutation.mutate();
  }

  return (
    <form onSubmit={submit} noValidate aria-busy={mutation.isPending}>
      <p className="vc-muted">{t('organizations.subscription.none')}</p>
      <h4>{t('organizations.subscription.createTitle')}</h4>
      <ProblemAlert error={mutation.error} />
      <FieldRow>
        <SelectField
          id="new-sub-plan"
          label={t('organizations.subscription.plan')}
          value={planId}
          onChange={setPlanId}
          emptyLabel="-"
          options={(plans.data?.items ?? []).map((p) => ({ value: p.id, label: `${p.name} (${p.code} v${String(p.version)})` }))}
          error={fieldError(mutation.error, 'planId')}
        />
        <SelectField
          id="new-sub-status"
          label={t('organizations.subscription.status')}
          value={status}
          onChange={(v) => {
            setStatus(v === 'ACTIVE' ? 'ACTIVE' : 'TRIAL');
          }}
          options={(['TRIAL', 'ACTIVE'] as const).map((s) => ({ value: s, label: t(`billing.statuses.${s}`) }))}
        />
      </FieldRow>
      <FieldRow>
        <InputField id="new-sub-start" type="date" label={t('organizations.subscription.periodStart')} value={start} onChange={setStart} error={fieldError(mutation.error, 'currentPeriodStart')} />
        <InputField id="new-sub-end" type="date" label={t('organizations.subscription.periodEnd')} value={end} onChange={setEnd} error={fieldError(mutation.error, 'currentPeriodEnd')} />
      </FieldRow>
      <button type="submit" className="vc-button vc-button--primary" disabled={mutation.isPending}>
        {t('organizations.subscription.create')}
      </button>
    </form>
  );
}
