import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { fieldError } from '../../api/problem';
import { useFormat } from '../../app/format';
import { TextAreaField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { Badge, Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { useTenant } from '../../tenant/useTenant';
import { ReauthDialog } from '../auth/MfaComponents';
import { isReauthRequired } from '../auth/mfaApi';
import { OrganizationFlags } from './OrganizationFlags';
import { getOrganization, type Organization, orgStatusTone, setOrganizationActive } from './platformApi';
import { SubscriptionPanel } from './SubscriptionPanel';

/** Platform view of one kindergarten: data, suspend/reactivate, subscription and feature flags. */
export function OrganizationDetail({ id }: { readonly id: string }) {
  const { t } = useTranslation();
  const format = useFormat();
  const { userId } = useTenant();
  const organization = useQuery({ queryKey: ['platform', userId, 'organization', id], queryFn: ({ signal }) => getOrganization(id, signal) });
  const [statusDialog, setStatusDialog] = useState(false);
  const o = organization.data;

  return (
    <section className="vc-section" aria-labelledby="org-detail-heading">
      <h2 id="org-detail-heading">{o === undefined ? t('organizations.detail.title') : o.name}</h2>
      <ProblemAlert error={organization.error} />
      {organization.isPending ? <Loading /> : null}
      {o === undefined ? null : (
        <>
          <dl>
            <dt>{t('ui.status')}</dt>
            <dd>
              <Badge tone={orgStatusTone(o.status)}>{t(`organizations.statuses.${o.status}`)}</Badge>
            </dd>
            <dt>{t('organizations.slug')}</dt>
            <dd>{o.slug}</dd>
            {o.legalName === null || o.legalName === undefined ? null : (
              <>
                <dt>{t('organizations.detail.legalName')}</dt>
                <dd>{o.legalName}</dd>
              </>
            )}
            <dt>{t('organizations.detail.country')}</dt>
            <dd>{o.countryCode}</dd>
            <dt>{t('organizations.detail.timezone')}</dt>
            <dd>{o.timezone}</dd>
            <dt>{t('organizations.detail.locale')}</dt>
            <dd>{o.defaultLocale}</dd>
            <dt>{t('organizations.created')}</dt>
            <dd>{format.dateTime(o.createdAt)}</dd>
          </dl>
          {o.status === 'ARCHIVED' ? null : (
            <button
              type="button"
              className={o.status === 'ACTIVE' ? 'vc-button vc-button--danger' : 'vc-button vc-button--primary'}
              onClick={() => {
                setStatusDialog(true);
              }}
            >
              {o.status === 'ACTIVE' ? t('organizations.detail.suspend') : t('organizations.detail.reactivate')}
            </button>
          )}
          <StatusDialog
            organization={o}
            open={statusDialog}
            onClose={() => {
              setStatusDialog(false);
            }}
          />
          <SubscriptionPanel organizationId={o.id} />
          <OrganizationFlags organizationId={o.id} />
        </>
      )}
    </section>
  );
}

function StatusDialog({ organization, open, onClose }: { readonly organization: Organization; readonly open: boolean; readonly onClose: () => void }) {
  const { t } = useTranslation();
  const { userId } = useTenant();
  const queryClient = useQueryClient();
  const [reason, setReason] = useState('');
  const [reauthOpen, setReauthOpen] = useState(false);
  const activate = organization.status !== 'ACTIVE';
  const mutation = useMutation({
    mutationFn: () => setOrganizationActive(organization.id, activate, reason.trim()),
    onSuccess: async () => {
      setReason('');
      await queryClient.invalidateQueries({ queryKey: ['platform', userId] });
      onClose();
    },
    onError: (error) => {
      if (isReauthRequired(error)) {
        setReauthOpen(true);
      }
    },
  });

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    mutation.mutate();
  }

  return (
    <>
      <Modal title={activate ? t('organizations.detail.reactivate') : t('organizations.detail.suspend')} open={open} onClose={onClose}>
        <form onSubmit={submit} noValidate aria-busy={mutation.isPending}>
          <p>{activate ? t('organizations.detail.reactivateIntro') : t('organizations.detail.suspendIntro')}</p>
          {mutation.error !== null && !isReauthRequired(mutation.error) ? <ProblemAlert error={mutation.error} /> : null}
          <TextAreaField id="org-status-reason" label={t('organizations.detail.reason')} value={reason} onChange={setReason} required rows={3} error={fieldError(mutation.error, 'reason')} />
          <div className="vc-toolbar">
            <button type="submit" className={activate ? 'vc-button vc-button--primary' : 'vc-button vc-button--danger'} disabled={mutation.isPending}>
              {activate ? t('organizations.detail.reactivate') : t('organizations.detail.suspend')}
            </button>
            <button type="button" className="vc-button vc-button--ghost" onClick={onClose}>
              {t('ui.cancel')}
            </button>
          </div>
        </form>
      </Modal>
      <ReauthDialog
        open={reauthOpen}
        onClose={() => {
          setReauthOpen(false);
        }}
        onConfirmed={() => {
          setReauthOpen(false);
          mutation.mutate();
        }}
      />
    </>
  );
}
