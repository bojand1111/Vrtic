import { useMutation } from '@tanstack/react-query';
import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { Alert } from '../../components/Alert';
import { ProblemAlert } from '../../components/ProblemAlert';
import { TextField } from '../../components/TextField';
import { forgotPassword } from './accountApi';
import { AuthCard } from './AuthCard';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** `/forgot-password`: always answers the same way, so it never reveals whether an account exists. */
export function ForgotPasswordPage() {
  const { t } = useTranslation();
  const [email, setEmail] = useState('');
  const [emailError, setEmailError] = useState<string | undefined>(undefined);
  const mutation = useMutation({ mutationFn: (value: string) => forgotPassword(value) });

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = email.trim();
    const error = trimmed.length === 0 ? t('auth.emailRequired') : EMAIL_PATTERN.test(trimmed) ? undefined : t('auth.emailInvalid');
    setEmailError(error);
    if (error === undefined) {
      mutation.mutate(trimmed);
    }
  }

  return (
    <AuthCard title={t('auth.forgot.title')}>
      {mutation.isSuccess ? (
        <Alert variant="info" title={t('auth.forgot.sent')} />
      ) : (
        <form onSubmit={submit} noValidate aria-busy={mutation.isPending}>
          <p>{t('auth.forgot.intro')}</p>
          <ProblemAlert error={mutation.error} />
          <TextField id="email" type="email" label={t('auth.email')} value={email} onChange={setEmail} autoComplete="username" required error={emailError} />
          <button type="submit" className="vc-button vc-button--primary" disabled={mutation.isPending}>
            {mutation.isPending ? t('auth.submitting') : t('auth.forgot.submit')}
          </button>
        </form>
      )}
      <p>
        <Link to="/login">{t('auth.backToLogin')}</Link>
      </p>
    </AuthCard>
  );
}
