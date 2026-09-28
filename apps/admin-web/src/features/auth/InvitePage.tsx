import { useMutation, useQuery } from '@tanstack/react-query';
import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router';

import { ApiProblem, fieldError } from '../../api/problem';
import { useSession } from '../../auth/useSession';
import { Alert } from '../../components/Alert';
import { ProblemAlert } from '../../components/ProblemAlert';
import { TextField } from '../../components/TextField';
import { getCurrentLocale } from '../../i18n/locale';
import { acceptInvitation, type InvitationPreview, passwordProblem, previewInvitation, register } from './accountApi';
import { AuthCard } from './AuthCard';

/**
 * `/invite?token=...` from the invitation e-mail. New e-mail: registration form (the token proves
 * the mailbox). Existing account: sign in first, then accept. Afterwards the session is re-read and
 * the user lands on the dashboard of the new organization.
 */
export function InvitePage() {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const preview = useQuery({
    queryKey: ['public', 'invitation', token],
    queryFn: ({ signal }) => previewInvitation(token, signal),
    enabled: token.length > 0,
    retry: false,
  });

  let body;
  if (token.length === 0 || (preview.error instanceof ApiProblem && preview.error.status === 404)) {
    body = <Alert variant="error" title={t('auth.invite.invalid')} />;
  } else if (preview.isPending) {
    body = <p role="status">{t('app.loading')}</p>;
  } else if (preview.isError) {
    body = <ProblemAlert error={preview.error} />;
  } else {
    body = <InvitationDetails preview={preview.data} token={token} />;
  }

  return <AuthCard title={t('auth.invite.title')}>{body}</AuthCard>;
}

function InvitationDetails({ preview, token }: { readonly preview: InvitationPreview; readonly token: string }) {
  const { t } = useTranslation();
  return (
    <>
      <p>
        {t('auth.invite.intro', { organization: preview.organization.name, role: t(`roles.${preview.role}`) })}
      </p>
      <p className="vc-muted">{t('auth.invite.forEmail', { email: preview.email })}</p>
      {preview.requiresRegistration ? <RegisterForm token={token} /> : <AcceptForExisting token={token} />}
    </>
  );
}

function RegisterForm({ token }: { readonly token: string }) {
  const { t } = useTranslation();
  const { refresh } = useSession();
  const navigate = useNavigate();
  const [givenName, setGivenName] = useState('');
  const [familyName, setFamilyName] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);
  const mutation = useMutation({
    mutationFn: () =>
      register({ invitationToken: token, password, givenName: givenName.trim(), familyName: familyName.trim(), preferredLocale: getCurrentLocale() }),
    onSuccess: async () => {
      await refresh();
      await navigate('/', { replace: true });
    },
  });

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (givenName.trim().length === 0 || familyName.trim().length === 0) {
      setLocalError(t('auth.invite.namesRequired'));
      return;
    }
    const problem = passwordProblem(password, confirmation);
    setLocalError(problem === null ? null : t(`auth.${problem}`));
    if (problem === null) {
      mutation.mutate();
    }
  }

  return (
    <form onSubmit={submit} noValidate aria-busy={mutation.isPending}>
      <p>{t('auth.invite.registerIntro')}</p>
      {localError === null ? null : <Alert variant="error" title={localError} />}
      <ProblemAlert error={mutation.error} />
      <TextField id="given-name" label={t('auth.givenName')} value={givenName} onChange={setGivenName} autoComplete="given-name" required error={fieldError(mutation.error, 'givenName')} />
      <TextField id="family-name" label={t('auth.familyName')} value={familyName} onChange={setFamilyName} autoComplete="family-name" required error={fieldError(mutation.error, 'familyName')} />
      <TextField id="new-password" type="password" label={t('auth.newPassword')} value={password} onChange={setPassword} autoComplete="new-password" required error={fieldError(mutation.error, 'password')} />
      <TextField id="confirm-password" type="password" label={t('auth.confirmPassword')} value={confirmation} onChange={setConfirmation} autoComplete="new-password" required />
      <p className="vc-muted">{t('auth.passwordRules')}</p>
      <button type="submit" className="vc-button vc-button--primary" disabled={mutation.isPending}>
        {mutation.isPending ? t('auth.submitting') : t('auth.invite.register')}
      </button>
    </form>
  );
}

function AcceptForExisting({ token }: { readonly token: string }) {
  const { t } = useTranslation();
  const { state, refresh } = useSession();
  const navigate = useNavigate();
  const mutation = useMutation({
    mutationFn: () => acceptInvitation(token),
    onSuccess: async () => {
      await refresh();
      await navigate('/', { replace: true });
    },
  });

  if (state.status !== 'authenticated') {
    return (
      <>
        <Alert variant="info" title={t('auth.invite.loginFirst')} />
        <Link className="vc-button vc-button--primary" to="/login" state={{ from: `/invite?token=${encodeURIComponent(token)}` }}>
          {t('auth.submit')}
        </Link>
      </>
    );
  }

  const wrongAccount = mutation.error instanceof ApiProblem && mutation.error.status === 404;
  return (
    <>
      <p>{t('auth.invite.signedInAs', { email: state.user.email })}</p>
      {wrongAccount ? <Alert variant="error" title={t('auth.invite.wrongAccount')} /> : <ProblemAlert error={mutation.error} />}
      <button
        type="button"
        className="vc-button vc-button--primary"
        disabled={mutation.isPending}
        onClick={() => {
          mutation.mutate();
        }}
      >
        {mutation.isPending ? t('auth.submitting') : t('auth.invite.accept')}
      </button>
    </>
  );
}
