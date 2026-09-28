import { useMutation } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';

import { ApiProblem } from '../../api/problem';
import { Alert } from '../../components/Alert';
import { ProblemAlert } from '../../components/ProblemAlert';
import { verifyEmail } from './accountApi';
import { AuthCard } from './AuthCard';

/**
 * `/verify-email?token=...`. The token is consumed by an explicit click (not on mount), so a
 * prefetching mail scanner or a double render cannot use it up.
 */
export function VerifyEmailPage() {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const mutation = useMutation({ mutationFn: () => verifyEmail(token) });

  let body;
  if (token.length === 0 || (mutation.error instanceof ApiProblem && mutation.error.status === 404)) {
    body = <Alert variant="error" title={t('auth.verify.invalid')} />;
  } else if (mutation.isSuccess) {
    body = <Alert variant="info" title={t('auth.verify.done')} />;
  } else {
    body = (
      <>
        <p>{t('auth.verify.intro')}</p>
        <ProblemAlert error={mutation.error} />
        <button
          type="button"
          className="vc-button vc-button--primary"
          disabled={mutation.isPending}
          onClick={() => {
            mutation.mutate();
          }}
        >
          {mutation.isPending ? t('auth.submitting') : t('auth.verify.confirm')}
        </button>
      </>
    );
  }

  return (
    <AuthCard title={t('auth.verify.title')}>
      {body}
      <p>
        <Link to="/login">{t('auth.backToLogin')}</Link>
      </p>
    </AuthCard>
  );
}
