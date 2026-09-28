import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { apiFetch } from '../../api/client';
import { useOrg, useOrgMutation, useOrgQuery } from '../../api/org';
import { fieldError } from '../../api/problem';
import { EmptyState } from '../../components/EmptyState';
import { SelectField, TextAreaField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { Badge, Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { TextField } from '../../components/TextField';
import type { Page } from '../locations/types';
import { canRevokeMembership, type Membership, type MembershipStatus } from './types';

const STATUSES: readonly MembershipStatus[] = ['ACTIVE', 'SUSPENDED', 'REVOKED'];
const ROLES = ['OWNER', 'ADMIN', 'TEACHER', 'PARENT'] as const;

function statusTone(status: MembershipStatus): 'success' | 'warning' | 'danger' | 'neutral' {
  if (status === 'ACTIVE') {
    return 'success';
  }
  if (status === 'SUSPENDED') {
    return 'warning';
  }
  return status === 'REVOKED' ? 'danger' : 'neutral';
}

/**
 * Every membership of the organization. Revoking needs MEMBER_REVOKE and a recent sign-in: the
 * modal asks for the password, calls POST /auth/reauthenticate, then POST .../revoke.
 * Staff memberships without a profile get a "Create staff profile" action (POST /employees).
 */
export function MembersTab() {
  const { t } = useTranslation();
  const { can, userId } = useOrg();
  const [status, setStatus] = useState<MembershipStatus>('ACTIVE');
  const [role, setRole] = useState('');
  const roleParam = role.length > 0 ? `&role=${role}` : '';
  const query = useOrgQuery<Page<Membership>>(['memberships'], `/memberships?status=${status}&limit=100${roleParam}`);
  const revokeMutation = useOrgMutation<Membership>(['memberships', 'employees', 'groups', 'assignments']);
  const profileMutation = useOrgMutation(['memberships', 'employees']);
  const [revoking, setRevoking] = useState<Membership | null>(null);
  const [reason, setReason] = useState('');
  const [password, setPassword] = useState('');
  const [reauthError, setReauthError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  function openRevoke(m: Membership) {
    revokeMutation.reset();
    setReauthError(null);
    setReason('');
    setPassword('');
    setRevoking(m);
  }

  function close() {
    setRevoking(null);
    setPassword('');
  }

  async function revoke(target: Membership) {
    setBusy(true);
    setReauthError(null);
    try {
      await apiFetch('/api/v1/auth/reauthenticate', { method: 'POST', body: { password } });
    } catch (error: unknown) {
      setReauthError(error);
      setBusy(false);
      return;
    }
    try {
      await revokeMutation.mutateAsync({ method: 'POST', path: `/memberships/${target.id}/revoke`, body: { reason: reason.trim() } });
      close();
    } catch {
      // revokeMutation.error holds the problem and is rendered in the modal.
    } finally {
      setBusy(false);
    }
  }

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (revoking !== null) {
      void revoke(revoking);
    }
  }

  function createProfile(m: Membership) {
    profileMutation.mutate({ method: 'POST', path: '/employees', body: { membershipId: m.id } });
  }

  const items = query.data?.items ?? [];
  const canRevoke = can('MEMBER_REVOKE');

  return (
    <section aria-labelledby="members-heading">
      <div className="vc-toolbar">
        <h2 id="members-heading">{t('employees.tabs.members')}</h2>
        <SelectField
          id="members-role"
          label={t('employees.role')}
          value={role}
          onChange={setRole}
          emptyLabel={t('ui.all')}
          options={ROLES.map((r) => ({ value: r, label: t(`roles.${r}`) }))}
        />
        <SelectField
          id="members-status"
          label={t('ui.status')}
          value={status}
          onChange={(v) => {
            setStatus(STATUSES.find((s) => s === v) ?? 'ACTIVE');
          }}
          options={STATUSES.map((s) => ({ value: s, label: t(`employees.status.${s}`) }))}
        />
      </div>
      <ProblemAlert error={profileMutation.error} />
      <ProblemAlert error={query.error} />
      {query.isPending ? <Loading /> : null}
      {query.isSuccess && items.length === 0 ? <EmptyState message={t('employees.membersEmpty')} /> : null}
      {items.length > 0 ? (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th scope="col">{t('ui.name')}</th>
                <th scope="col">{t('employees.email')}</th>
                <th scope="col">{t('employees.role')}</th>
                <th scope="col">{t('ui.status')}</th>
                <th scope="col">{t('employees.children')}</th>
                <th scope="col">{t('ui.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((m) => (
                <tr key={m.id}>
                  <td>{`${m.givenName} ${m.familyName}`}</td>
                  <td>{m.email}</td>
                  <td>{t(`roles.${m.role}`)}</td>
                  <td>
                    <Badge tone={statusTone(m.status)}>{t(`employees.status.${m.status}`)}</Badge>
                  </td>
                  <td>{m.role === 'PARENT' ? m.childrenCount : ''}</td>
                  <td className="vc-actions">
                    {m.role !== 'PARENT' && m.status === 'ACTIVE' && (m.employeeId === undefined || m.employeeId === null) ? (
                      <button
                        type="button"
                        className="vc-button vc-button--small"
                        disabled={profileMutation.isPending}
                        onClick={() => {
                          createProfile(m);
                        }}
                      >
                        {t('employees.createProfile')}
                      </button>
                    ) : null}
                    {canRevoke && canRevokeMembership(m, userId) ? (
                      <button
                        type="button"
                        className="vc-button vc-button--small vc-button--danger"
                        onClick={() => {
                          openRevoke(m);
                        }}
                      >
                        {t('employees.revoke')}
                      </button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      <Modal title={t('employees.revokeTitle', { name: revoking === null ? '' : `${revoking.givenName} ${revoking.familyName}` })} open={revoking !== null} onClose={close}>
        <form onSubmit={submit} noValidate>
          <p>{t('employees.revokeExplain')}</p>
          <ProblemAlert error={reauthError} />
          <ProblemAlert error={revokeMutation.error} />
          <TextAreaField
            id="revoke-reason"
            label={t('employees.revokeReason')}
            value={reason}
            onChange={setReason}
            rows={3}
            required
            error={fieldError(revokeMutation.error, 'reason')}
          />
          <p className="vc-muted">{t('employees.passwordHint')}</p>
          <TextField
            id="revoke-password"
            type="password"
            label={t('employees.yourPassword')}
            value={password}
            onChange={setPassword}
            autoComplete="current-password"
            required
          />
          <div className="vc-modal-footer">
            <button type="button" className="vc-button" onClick={close}>
              {t('ui.cancel')}
            </button>
            <button
              type="submit"
              className="vc-button vc-button--primary vc-button--danger"
              disabled={busy || reason.trim().length < 3 || password.length === 0}
            >
              {busy ? t('ui.saving') : t('employees.revoke')}
            </button>
          </div>
        </form>
      </Modal>
    </section>
  );
}
