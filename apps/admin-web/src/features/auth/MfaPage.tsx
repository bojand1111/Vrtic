import { useMutation, useQuery } from '@tanstack/react-query';
import { type SubmitEvent, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, useLocation, useNavigate } from 'react-router';

import { logout } from '../../api/auth';
import { ApiProblem } from '../../api/problem';
import { useSession } from '../../auth/useSession';
import { Alert } from '../../components/Alert';
import { ProblemAlert } from '../../components/ProblemAlert';
import { TextField } from '../../components/TextField';
import { AuthCard } from './AuthCard';
import { getMfaState, normalizeOtp, verifyMfa } from './mfaApi';
import { MfaEnrollment } from './MfaComponents';

function targetFrom(state: unknown): string {
  if (typeof state === 'object' && state !== null) {
    const from = (state as Record<string, unknown>).from;
    if (typeof from === 'string' && from.startsWith('/') && from !== '/login' && from !== '/mfa') {
      return from;
    }
  }
  return '/';
}

/**
 * `/mfa`: second step after the password (E02-B12). An enrolled account enters a TOTP or recovery
 * code; an OWNER / platform admin without MFA sets it up here. Until then the backend answers every
 * other route with 403, so this screen is the only way forward (sign out is always offered).
 */
export function MfaPage() {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const { refresh, markUnauthenticated } = useSession();
  const target = targetFrom(location.state);
  const state = useQuery({ queryKey: ['auth', 'mfa-state'], queryFn: ({ signal }) => getMfaState(signal), retry: false, staleTime: 0 });
  const leaving = useRef(false);

  async function finish() {
    leaving.current = true;
    await refresh();
    await navigate(target, { replace: true });
  }

  const nothingToDo = state.data !== undefined && !state.data.mfaRequired && !state.data.mfaEnrollmentRequired;
  useEffect(() => {
    if (nothingToDo && !leaving.current) {
      void finish();
    }
  });

  if (state.error instanceof ApiProblem && state.error.status === 401) {
    return <Navigate to="/login" replace />;
  }

  let body;
  if (state.isPending || nothingToDo) {
    body = <p role="status">{t('app.loading')}</p>;
  } else if (state.isError) {
    body = <ProblemAlert error={state.error} />;
  } else if (state.data.mfaRequired) {
    body = <VerifyForm onVerified={() => void finish()} />;
  } else {
    body = (
      <>
        <Alert variant="info" title={t('auth.mfa.enrollIntro')} />
        <MfaEnrollment onFinished={() => void finish()} />
      </>
    );
  }

  return (
    <AuthCard title={t('auth.mfa.title')}>
      {body}
      <p>
        <button
          type="button"
          className="vc-button vc-button--ghost"
          onClick={() => {
            void logout()
              .catch((error: unknown) => {
                // The session may already be gone; the local view is cleared either way.
                console.warn('logout failed', error);
              })
              .finally(() => {
                markUnauthenticated();
                void navigate('/login', { replace: true });
              });
          }}
        >
          {t('auth.mfa.logout')}
        </button>
      </p>
    </AuthCard>
  );
}

function VerifyForm({ onVerified }: { readonly onVerified: () => void }) {
  const { t } = useTranslation();
  const [code, setCode] = useState('');
  const mutation = useMutation({ mutationFn: (value: string) => verifyMfa(value), onSuccess: onVerified });
  const wrongCode = mutation.error instanceof ApiProblem && mutation.error.status === 422;

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = normalizeOtp(code);
    if (value.length > 0) {
      mutation.mutate(value);
    }
  }

  return (
    <form onSubmit={submit} noValidate aria-busy={mutation.isPending}>
      <p>{t('auth.mfa.verifyIntro')}</p>
      {wrongCode ? null : <ProblemAlert error={mutation.error} />}
      <TextField
        id="mfa-code"
        label={t('auth.mfa.code')}
        value={code}
        onChange={setCode}
        autoComplete="one-time-code"
        required
        error={wrongCode ? t('auth.mfa.invalidCode') : undefined}
      />
      <button type="submit" className="vc-button vc-button--primary" disabled={mutation.isPending || code.trim().length === 0}>
        {mutation.isPending ? t('auth.mfa.verifying') : t('auth.mfa.verify')}
      </button>
    </form>
  );
}
