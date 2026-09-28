import { useMutation } from '@tanstack/react-query';
import { type SubmitEvent, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, Navigate, useLocation, useNavigate } from 'react-router';

import type { LoginRequest } from '../../api/auth';
import { ApiProblem, NetworkError } from '../../api/problem';
import { HealthWidget } from '../../app/layout/HealthWidget';
import { LocaleSwitcher } from '../../app/layout/LocaleSwitcher';
import type { LoginLocationState } from '../../app/routes/RequireAuth';
import { useDocumentTitle } from '../../app/useDocumentTitle';
import { useSession } from '../../auth/useSession';
import { Alert } from '../../components/Alert';
import { TextField } from '../../components/TextField';
import { loginWeb } from './mfaApi';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

interface FieldErrors {
  readonly email?: string;
  readonly password?: string;
}

interface AlertContent {
  readonly title: string;
  readonly detail?: string | undefined;
  readonly meta?: string | undefined;
}

function readLocationState(value: unknown): LoginLocationState {
  if (typeof value !== 'object' || value === null) {
    return {};
  }
  const candidate = value as Record<string, unknown>;
  return {
    ...(typeof candidate.from === 'string' ? { from: candidate.from } : {}),
    ...(candidate.reason === 'expired' ? { reason: 'expired' as const } : {}),
  };
}

export function LoginPage() {
  const { t } = useTranslation();
  const { state: session, refresh } = useSession();
  const location = useLocation();
  const navigate = useNavigate();
  const title = t('auth.loginTitle');
  useDocumentTitle(title);

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const emailRef = useRef<HTMLInputElement | null>(null);
  const passwordRef = useRef<HTMLInputElement | null>(null);

  const locationState = readLocationState(location.state);

  const mutation = useMutation({
    mutationFn: (request: LoginRequest) => loginWeb(request.email, request.password),
    onSuccess: async (result) => {
      // E02-B12: a session that still owes MFA (verification or mandatory enrollment) goes to /mfa first;
      // the session view is refreshed there once MFA is done.
      if (result.status === 'MFA_REQUIRED' || result.mfaEnrollmentRequired === true) {
        await navigate('/mfa', { replace: true, state: { from: locationState.from } });
        return;
      }
      // Cookies were set by the backend; we only re-read who we are. Nothing is stored locally.
      await refresh();
    },
  });

  if (session.status === 'authenticated') {
    const target = locationState.from !== undefined && locationState.from !== '/login' ? locationState.from : '/';
    return <Navigate to={target} replace />;
  }

  function validate(): FieldErrors {
    const trimmedEmail = email.trim();
    let emailError: string | undefined;
    if (trimmedEmail.length === 0) {
      emailError = t('auth.emailRequired');
    } else if (!EMAIL_PATTERN.test(trimmedEmail)) {
      emailError = t('auth.emailInvalid');
    }
    const passwordError = password.length === 0 ? t('auth.passwordRequired') : undefined;
    return {
      ...(emailError === undefined ? {} : { email: emailError }),
      ...(passwordError === undefined ? {} : { password: passwordError }),
    };
  }

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const errors = validate();
    setFieldErrors(errors);
    if (errors.email !== undefined) {
      emailRef.current?.focus();
      return;
    }
    if (errors.password !== undefined) {
      passwordRef.current?.focus();
      return;
    }
    mutation.mutate({ email: email.trim(), password, clientKind: 'WEB' });
  }

  const hasFieldErrors = fieldErrors.email !== undefined || fieldErrors.password !== undefined;

  let alert: AlertContent | null = null;
  if (hasFieldErrors) {
    alert = { title: t('auth.formInvalid') };
  } else if (mutation.isError) {
    const error: unknown = mutation.error;
    if (error instanceof ApiProblem) {
      alert = {
        title: error.title,
        detail: error.detail,
        meta: error.requestId === undefined ? undefined : `${t('errors.requestId')}: ${error.requestId}`,
      };
    } else if (error instanceof NetworkError) {
      alert = { title: t('errors.network') };
    } else {
      alert = { title: t('errors.generic') };
    }
  }

  return (
    <div className="vc-auth-page">
      <main className="vc-auth-card" id="main">
        <h1>{title}</h1>
        <p>{t('auth.loginIntro')}</p>

        {locationState.reason === 'expired' ? <Alert variant="info" title={t('errors.sessionExpired')} /> : null}
        {alert === null ? null : (
          <Alert variant="error" title={alert.title} detail={alert.detail} meta={alert.meta} />
        )}

        <form onSubmit={handleSubmit} noValidate aria-busy={mutation.isPending}>
          <TextField
            id="email"
            type="email"
            label={t('auth.email')}
            value={email}
            onChange={setEmail}
            autoComplete="username"
            required
            disabled={mutation.isPending}
            error={fieldErrors.email}
            inputRef={emailRef}
          />
          <TextField
            id="password"
            type="password"
            label={t('auth.password')}
            value={password}
            onChange={setPassword}
            autoComplete="current-password"
            required
            disabled={mutation.isPending}
            error={fieldErrors.password}
            inputRef={passwordRef}
          />
          <button type="submit" className="vc-button vc-button--primary" disabled={mutation.isPending}>
            {mutation.isPending ? t('auth.submitting') : t('auth.submit')}
          </button>
        </form>
        <p>
          <Link to="/forgot-password">{t('auth.forgotLink')}</Link>
        </p>
      </main>

      <footer className="vc-footer">
        <HealthWidget />
        <LocaleSwitcher />
      </footer>
    </div>
  );
}
