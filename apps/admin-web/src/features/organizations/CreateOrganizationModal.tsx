import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { fieldError } from '../../api/problem';
import { FieldRow, InputField, SelectField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { ProblemAlert } from '../../components/ProblemAlert';
import { SUPPORTED_LOCALES } from '../../i18n/locale';
import { useTenant } from '../../tenant/useTenant';
import { createOrganization, listPlans, type Organization } from './platformApi';

interface Props {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly onCreated: (organization: Organization, ownerEmail: string) => void;
}

const EMPTY = { name: '', slug: '', legalName: '', countryCode: 'RS', timezone: 'Europe/Belgrade', defaultLocale: 'sr-Latn', ownerEmail: '', planId: '' };

/** POST /platform/organizations: tenant + default settings + TRIAL subscription + OWNER invitation e-mail. */
export function CreateOrganizationModal({ open, onClose, onCreated }: Props) {
  const { t } = useTranslation();
  const { userId } = useTenant();
  const queryClient = useQueryClient();
  const [form, setForm] = useState(EMPTY);
  const plans = useQuery({ queryKey: ['platform', userId, 'plans'], queryFn: ({ signal }) => listPlans(signal), enabled: open });
  const mutation = useMutation({
    mutationFn: () =>
      createOrganization({
        name: form.name.trim(),
        slug: form.slug.trim(),
        ...(form.legalName.trim().length > 0 ? { legalName: form.legalName.trim() } : {}),
        countryCode: form.countryCode.trim().toUpperCase(),
        timezone: form.timezone.trim(),
        defaultLocale: form.defaultLocale,
        ownerEmail: form.ownerEmail.trim(),
        planId: form.planId,
      }),
    onSuccess: async (organization) => {
      const email = form.ownerEmail.trim();
      setForm(EMPTY);
      await queryClient.invalidateQueries({ queryKey: ['platform', userId, 'organizations'] });
      onCreated(organization, email);
    },
  });
  const set = (key: keyof typeof EMPTY) => (value: string) => {
    setForm((f) => ({ ...f, [key]: value }));
  };
  const planOptions = (plans.data?.items ?? []).map((p) => ({ value: p.id, label: `${p.name} (${p.code} v${String(p.version)})` }));

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    mutation.mutate();
  }

  return (
    <Modal
      title={t('organizations.create.title')}
      open={open}
      onClose={() => {
        mutation.reset();
        onClose();
      }}
    >
      <form onSubmit={submit} noValidate aria-busy={mutation.isPending}>
        <ProblemAlert error={mutation.error} />
        <ProblemAlert error={plans.error} />
        <FieldRow>
          <InputField id="org-name" label={t('organizations.create.name')} value={form.name} onChange={set('name')} required error={fieldError(mutation.error, 'name')} />
          <InputField
            id="org-slug"
            label={t('organizations.create.slug')}
            value={form.slug}
            onChange={set('slug')}
            required
            hint={t('organizations.create.slugHint')}
            error={fieldError(mutation.error, 'slug')}
          />
        </FieldRow>
        <InputField id="org-legal" label={t('organizations.create.legalName')} value={form.legalName} onChange={set('legalName')} error={fieldError(mutation.error, 'legalName')} />
        <FieldRow>
          <InputField id="org-country" label={t('organizations.create.country')} value={form.countryCode} onChange={set('countryCode')} error={fieldError(mutation.error, 'countryCode')} />
          <InputField id="org-timezone" label={t('organizations.create.timezone')} value={form.timezone} onChange={set('timezone')} error={fieldError(mutation.error, 'timezone')} />
          <SelectField
            id="org-locale"
            label={t('organizations.create.locale')}
            value={form.defaultLocale}
            onChange={set('defaultLocale')}
            options={SUPPORTED_LOCALES.map((l) => ({ value: l, label: t(`locales.${l}`) }))}
          />
        </FieldRow>
        <InputField
          id="org-owner"
          type="email"
          label={t('organizations.create.ownerEmail')}
          value={form.ownerEmail}
          onChange={set('ownerEmail')}
          required
          hint={t('organizations.create.ownerHint')}
          error={fieldError(mutation.error, 'ownerEmail')}
        />
        {plans.isSuccess && planOptions.length === 0 ? <p className="vc-field-error">{t('organizations.create.noPlans')}</p> : null}
        <SelectField
          id="org-plan"
          label={t('organizations.create.plan')}
          value={form.planId}
          onChange={set('planId')}
          options={planOptions}
          emptyLabel="-"
          required
          error={fieldError(mutation.error, 'planId')}
        />
        <div className="vc-toolbar">
          <button type="submit" className="vc-button vc-button--primary" disabled={mutation.isPending}>
            {mutation.isPending ? t('ui.saving') : t('organizations.create.submit')}
          </button>
          <button type="button" className="vc-button vc-button--ghost" onClick={onClose}>
            {t('ui.cancel')}
          </button>
        </div>
      </form>
    </Modal>
  );
}
