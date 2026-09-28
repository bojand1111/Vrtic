import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import { useFormat } from '../../app/format';
import { useSession } from '../../auth/useSession';
import { EmptyState } from '../../components/EmptyState';
import { Badge, Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { ReauthDialog } from '../auth/MfaComponents';
import { isReauthRequired } from '../auth/mfaApi';
import { type DeviceSession, listSessions, logoutEverywhere, revokeSession } from './accountApi';

/** Signed-in devices: revoke one, or sign out everywhere (needs a recent sign-in, else the password dialog). */
export function SessionsSection({ userId }: { readonly userId: string }) {
  const { t } = useTranslation();
  const format = useFormat();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { markUnauthenticated } = useSession();
  const [reauthOpen, setReauthOpen] = useState(false);
  const sessions = useQuery({ queryKey: ['account', userId, 'sessions'], queryFn: ({ signal }) => listSessions(signal) });

  async function signedOut() {
    markUnauthenticated();
    await navigate('/login', { replace: true });
  }

  const revoke = useMutation({
    mutationFn: (s: DeviceSession) => revokeSession(s.id).then(() => s),
    onSuccess: async (s) => {
      if (s.isCurrent) {
        await signedOut();
        return;
      }
      await queryClient.invalidateQueries({ queryKey: ['account', userId, 'sessions'] });
    },
  });
  const logoutAll = useMutation({
    mutationFn: logoutEverywhere,
    onSuccess: signedOut,
    onError: (error) => {
      if (isReauthRequired(error)) {
        setReauthOpen(true);
      }
    },
  });

  const items = sessions.data?.items ?? [];
  return (
    <section className="vc-section" aria-labelledby="account-sessions">
      <div className="vc-toolbar">
        <h2 id="account-sessions">{t('account.sessions.title')}</h2>
        <button
          type="button"
          className="vc-button vc-button--danger"
          disabled={logoutAll.isPending}
          onClick={() => {
            if (window.confirm(t('account.sessions.logoutAllConfirm'))) {
              logoutAll.mutate();
            }
          }}
        >
          {t('account.sessions.logoutAll')}
        </button>
      </div>
      <ProblemAlert error={sessions.error} />
      <ProblemAlert error={revoke.error} />
      {logoutAll.error !== null && !isReauthRequired(logoutAll.error) ? <ProblemAlert error={logoutAll.error} /> : null}
      {sessions.isPending ? <Loading /> : null}
      {sessions.isSuccess && items.length === 0 ? <EmptyState message={t('account.sessions.empty')} /> : null}
      {items.length > 0 ? (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th scope="col">{t('account.sessions.device')}</th>
                <th scope="col">{t('account.sessions.client')}</th>
                <th scope="col">{t('account.sessions.createdAt')}</th>
                <th scope="col">{t('account.sessions.lastSeenAt')}</th>
                <th scope="col">{t('account.sessions.expiresAt')}</th>
                <th scope="col">{t('ui.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((s) => (
                <tr key={s.id}>
                  <td>
                    {s.deviceName ?? s.userAgent ?? t('account.sessions.unknownDevice')}{' '}
                    {s.isCurrent ? <Badge tone="info">{t('account.sessions.current')}</Badge> : null}{' '}
                    {s.mfaVerifiedAt === null || s.mfaVerifiedAt === undefined ? null : <Badge tone="success">{t('account.sessions.mfaVerified')}</Badge>}
                  </td>
                  <td>{t(`account.sessions.clientKinds.${s.clientKind}`)}</td>
                  <td>{format.dateTime(s.createdAt)}</td>
                  <td>{format.dateTime(s.lastSeenAt)}</td>
                  <td>{format.dateTime(s.absoluteExpiresAt)}</td>
                  <td className="vc-actions">
                    <button
                      type="button"
                      className="vc-button vc-button--small"
                      disabled={revoke.isPending}
                      onClick={() => {
                        if (window.confirm(t('account.sessions.revokeConfirm'))) {
                          revoke.mutate(s);
                        }
                      }}
                    >
                      {t('account.sessions.revoke')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      <ReauthDialog
        open={reauthOpen}
        onClose={() => {
          setReauthOpen(false);
        }}
        onConfirmed={() => {
          setReauthOpen(false);
          logoutAll.mutate();
        }}
      />
    </section>
  );
}
