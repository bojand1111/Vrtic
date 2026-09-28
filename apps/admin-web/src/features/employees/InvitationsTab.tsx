import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg, useOrgMutation, useOrgQuery } from '../../api/org';
import { fieldError } from '../../api/problem';
import type { OrganizationRole } from '../../auth/types';
import { useFormat } from '../../app/format';
import { Alert } from '../../components/Alert';
import { EmptyState } from '../../components/EmptyState';
import { InputField, SelectField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { Badge, Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import type { Page } from '../locations/types';
import { type Invitation, type InvitationStatus, invitableRoles } from './types';

/** The API logs invitation links instead of sending mail only in development (LoggingMailSender). */
const IS_DEV = import.meta.env.DEV;

const STATUSES: readonly InvitationStatus[] = ['PENDING', 'ACCEPTED', 'EXPIRED', 'REVOKED'];

interface ChildOption {
  readonly id: string;
  readonly givenName: string;
  readonly familyName: string;
}

/** Invitations: list by status, revoke pending ones, invite (role limited to what the current role may assign). */
export function InvitationsTab() {
  const { t } = useTranslation();
  const format = useFormat();
  const { role: myRole } = useOrg();
  const roles = invitableRoles(myRole);
  const [status, setStatus] = useState<InvitationStatus>('PENDING');
  const query = useOrgQuery<Page<Invitation>>(['invitations'], `/invitations?status=${status}&limit=100`);
  const mutation = useOrgMutation<Invitation>(['invitations']);
  const [inviting, setInviting] = useState(false);
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<OrganizationRole>(roles[0] ?? 'TEACHER');
  const [childId, setChildId] = useState('');
  const children = useOrgQuery<Page<ChildOption>>(['children'], '/children?limit=100', { enabled: inviting && role === 'PARENT' });
  const [sentTo, setSentTo] = useState<string | null>(null);

  function openInvite() {
    mutation.reset();
    setEmail('');
    setChildId('');
    setRole(roles[0] ?? 'TEACHER');
    setInviting(true);
  }

  function close() {
    setInviting(false);
    mutation.reset();
  }

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = email.trim();
    mutation.mutate(
      {
        method: 'POST',
        path: '/invitations',
        body: { email: trimmed, role, ...(role === 'PARENT' ? { childId } : {}) },
      },
      {
        onSuccess: () => {
          setSentTo(trimmed);
          setInviting(false);
          setStatus('PENDING');
        },
      },
    );
  }

  function revoke(invitation: Invitation) {
    if (!window.confirm(t('employees.confirmRevokeInvitation', { email: invitation.email }))) {
      return;
    }
    mutation.mutate({ method: 'POST', path: `/invitations/${invitation.id}/revoke` });
  }

  const items = query.data?.items ?? [];

  return (
    <section aria-labelledby="invitations-heading">
      <div className="vc-toolbar">
        <h2 id="invitations-heading">{t('employees.tabs.invitations')}</h2>
        <SelectField
          id="invitations-status"
          label={t('ui.status')}
          value={status}
          onChange={(v) => {
            setStatus(STATUSES.find((s) => s === v) ?? 'PENDING');
          }}
          options={STATUSES.map((s) => ({ value: s, label: t(`employees.invitationStatus.${s}`) }))}
        />
        {roles.length > 0 ? (
          <button type="button" className="vc-button vc-button--primary" onClick={openInvite}>
            {t('employees.invite')}
          </button>
        ) : null}
      </div>
      {sentTo === null ? null : (
        <Alert
          variant="info"
          title={t('employees.invitationSent', { email: sentTo })}
          detail={IS_DEV ? t('employees.devMailInfo') : undefined}
        />
      )}
      {inviting ? null : <ProblemAlert error={mutation.error} />}
      <ProblemAlert error={query.error} />
      {query.isPending ? <Loading /> : null}
      {query.isSuccess && items.length === 0 ? <EmptyState message={t('employees.invitationsEmpty')} /> : null}
      {items.length > 0 ? (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th scope="col">{t('employees.email')}</th>
                <th scope="col">{t('employees.role')}</th>
                <th scope="col">{t('employees.child')}</th>
                <th scope="col">{t('employees.sentAt')}</th>
                <th scope="col">{t('employees.expiresAt')}</th>
                <th scope="col">{t('ui.status')}</th>
                <th scope="col">{t('ui.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((inv) => (
                <tr key={inv.id}>
                  <td>{inv.email}</td>
                  <td>{t(`roles.${inv.role}`)}</td>
                  <td>{inv.childName ?? ''}</td>
                  <td>{format.dateTime(inv.createdAt)}</td>
                  <td>{format.dateTime(inv.expiresAt)}</td>
                  <td>
                    <Badge tone={inv.status === 'PENDING' ? 'info' : inv.status === 'ACCEPTED' ? 'success' : 'neutral'}>
                      {t(`employees.invitationStatus.${inv.status}`)}
                    </Badge>
                  </td>
                  <td className="vc-actions">
                    {inv.status === 'PENDING' ? (
                      <button
                        type="button"
                        className="vc-button vc-button--small vc-button--danger"
                        disabled={mutation.isPending}
                        onClick={() => {
                          revoke(inv);
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

      <Modal title={t('employees.invite')} open={inviting} onClose={close}>
        <form onSubmit={submit} noValidate>
          <ProblemAlert error={mutation.error} />
          <InputField
            id="invite-email"
            type="email"
            label={t('employees.email')}
            value={email}
            onChange={setEmail}
            required
            error={fieldError(mutation.error, 'email')}
          />
          <SelectField
            id="invite-role"
            label={t('employees.role')}
            value={role}
            onChange={(v) => {
              setRole(roles.find((r) => r === v) ?? role);
            }}
            options={roles.map((r) => ({ value: r, label: t(`roles.${r}`) }))}
            required
            error={fieldError(mutation.error, 'role')}
          />
          {role === 'PARENT' ? (
            <>
              <ProblemAlert error={children.error} />
              <SelectField
                id="invite-child"
                label={t('employees.child')}
                value={childId}
                onChange={setChildId}
                emptyLabel={t('employees.chooseChild')}
                options={(children.data?.items ?? []).map((c) => ({ value: c.id, label: `${c.givenName} ${c.familyName}` }))}
                required
                error={fieldError(mutation.error, 'childId')}
              />
            </>
          ) : null}
          {IS_DEV ? <p className="vc-muted">{t('employees.devMailInfo')}</p> : null}
          <div className="vc-modal-footer">
            <button type="button" className="vc-button" onClick={close}>
              {t('ui.cancel')}
            </button>
            <button
              type="submit"
              className="vc-button vc-button--primary"
              disabled={mutation.isPending || email.trim().length === 0 || (role === 'PARENT' && childId.length === 0)}
            >
              {mutation.isPending ? t('ui.saving') : t('employees.sendInvitation')}
            </button>
          </div>
        </form>
      </Modal>
    </section>
  );
}
