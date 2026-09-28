import { useMutation } from '@tanstack/react-query';
import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';

import { ApiProblem, fieldError } from '../../api/problem';
import { Alert } from '../../components/Alert';
import { ProblemAlert } from '../../components/ProblemAlert';
import { TextField } from '../../components/TextField';
import { passwordProblem, resetPassword } from './accountApi';
import { AuthCard } from './AuthCard';

/** `/reset-password?token=...`: sets a new password; the backend signs out every session of the account. */
export function ResetPasswordPage() {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);
  const mutation = useMutation({ mutationFn: () => resetPassword(token, password) });

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const problem = passwordProblem(password, confirmation);
    setLocalError(problem === null ? null : t(`auth.${problem}`));
    if (problem === null) {
      mutation.mutate();
    }
  }

  let body;
  if (token.length === 0 || (mutation.error instanceof ApiProblem && mutation.error.status === 404)) {
    body = <Alert variant="error" title={t('auth.reset.invalid')} />;
  } else if (mutation.isSuccess) {
    body = <Alert variant="info" title={t('auth.reset.done')} />;
  } else {
    body = (
      <form onSubmit={submit} noValidate aria-busy={mutation.isPending}>
        {localError === null ? null : <Alert variant="error" title={localError} />}
        <ProblemAlert error={mutation.error} />
        <TextField id="new-password" type="password" label={t('auth.newPassword')} value={password} onChange={setPassword} autoComplete="new-password" required error={fieldError(mutation.error, 'newPassword')} />
        <TextField id="confirm-password" type="password" label={t('auth.confirmPassword')} value={confirmation} onChange={setConfirmation} autoComplete="new-password" required />
        <p className="vc-muted">{t('auth.passwordRules')}</p>
        <button type="submit" className="vc-button vc-button--primary" disabled={mutation.isPending}>
          {mutation.isPending ? t('auth.submitting') : t('auth.reset.submit')}
        </button>
      </form>
    );
  }

  return (
    <AuthCard title={t('auth.reset.title')}>
      {body}
      <p>
        <Link to="/login">{t('auth.backToLogin')}</Link>
      </p>
    </AuthCard>
  );
}
