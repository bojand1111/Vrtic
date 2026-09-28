import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { ApiProblem, fieldError } from '../../api/problem';
import { useFormat } from '../../app/format';
import { useSession } from '../../auth/useSession';
import { Alert } from '../../components/Alert';
import { SelectField } from '../../components/Form';
import { Badge, Loading, Page } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { TextField } from '../../components/TextField';
import { isLocale, SUPPORTED_LOCALES } from '../../i18n/locale';
import { passwordProblem } from '../auth/accountApi';
import { MfaEnrollment, ReauthDialog, RecoveryCodesPanel } from '../auth/MfaComponents';
import { isReauthRequired, regenerateRecoveryCodes } from '../auth/mfaApi';
import { changePassword, getProfile, type Profile, updateLocale } from './accountApi';
import { SessionsSection } from './SessionsSection';

/** `/account`: own profile, language, password, two-step verification and signed-in devices. */
export function AccountPage() {
  const { t } = useTranslation();
  const { state } = useSession();
  const userId = state.status === 'authenticated' ? state.user.id : 'anonymous';
  const profile = useQuery({ queryKey: ['account', userId, 'me'], queryFn: ({ signal }) => getProfile(signal) });

  return (
    <Page title={t('nav.account')}>
      <ProblemAlert error={profile.error} />
      {profile.isPending ? <Loading /> : null}
      {profile.data === undefined ? null : (
        <>
          <ProfileSection profile={profile.data} />
          <MfaSection profile={profile.data} />
          <PasswordSection />
        </>
      )}
      <SessionsSection userId={userId} />
    </Page>
  );
}

function ProfileSection({ profile }: { readonly profile: Profile }) {
  const { t } = useTranslation();
  const format = useFormat();
  const { state } = useSession();
  const queryClient = useQueryClient();
  const [locale, setLocale] = useState<string>(profile.preferredLocale);
  const mutation = useMutation({
    mutationFn: () => updateLocale(isLocale(locale) ? locale : profile.preferredLocale),
    onSuccess: (updated) => {
      queryClient.setQueryData(['account', profile.id, 'me'], updated);
    },
  });
  const memberships = state.status === 'authenticated' ? state.user.memberships : [];

  return (
    <section className="vc-section" aria-labelledby="account-profile">
      <h2 id="account-profile">{t('account.profile.title')}</h2>
      <dl>
        <dt>{t('account.profile.name')}</dt>
        <dd>{`${profile.givenName} ${profile.familyName}`}</dd>
        <dt>{t('account.profile.email')}</dt>
        <dd>
          {profile.email}{' '}
          {profile.emailVerifiedAt === null || profile.emailVerifiedAt === undefined ? (
            <Badge tone="warning">{t('account.profile.emailNotVerified')}</Badge>
          ) : (
            <Badge tone="success">{t('account.profile.emailVerified')}</Badge>
          )}
        </dd>
        <dt>{t('account.profile.createdAt')}</dt>
        <dd>{format.dateTime(profile.createdAt)}</dd>
        {profile.lastLoginAt === null || profile.lastLoginAt === undefined ? null : (
          <>
            <dt>{t('account.profile.lastLogin')}</dt>
            <dd>{format.dateTime(profile.lastLoginAt)}</dd>
          </>
        )}
        {memberships.length === 0 ? null : (
          <>
            <dt>{t('account.profile.memberships')}</dt>
            <dd>
              <ul className="vc-list">
                {memberships.map((m) => (
                  <li key={`${m.organizationId}-${m.role}`}>{`${m.organizationName}: ${t(`roles.${m.role}`)}`}</li>
                ))}
              </ul>
            </dd>
          </>
        )}
      </dl>
      {profile.isPlatformAdmin ? <Badge tone="info">{t('account.profile.platformAdmin')}</Badge> : null}
      <form
        onSubmit={(event: SubmitEvent<HTMLFormElement>) => {
          event.preventDefault();
          mutation.mutate();
        }}
      >
        <SelectField
          id="account-locale"
          label={t('account.profile.language')}
          value={locale}
          onChange={setLocale}
          options={SUPPORTED_LOCALES.map((l) => ({ value: l, label: t(`locales.${l}`) }))}
          error={fieldError(mutation.error, 'preferredLocale')}
        />
        <ProblemAlert error={mutation.error instanceof ApiProblem && mutation.error.status === 422 ? null : mutation.error} />
        {mutation.isSuccess ? <p role="status">{t('account.profile.languageSaved')}</p> : null}
        <button type="submit" className="vc-button vc-button--small" disabled={mutation.isPending}>
          {t('account.profile.saveLanguage')}
        </button>
      </form>
    </section>
  );
}

function PasswordSection() {
  const { t } = useTranslation();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);
  const mutation = useMutation({
    mutationFn: () => changePassword(current, next),
    onSuccess: () => {
      setCurrent('');
      setNext('');
      setConfirmation('');
    },
  });
  const wrongCurrent = mutation.error instanceof ApiProblem && mutation.error.detail === 'CURRENT_PASSWORD_INVALID';

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const problem = passwordProblem(next, confirmation);
    setLocalError(problem === null ? null : t(`auth.${problem}`));
    if (problem === null && current.length > 0) {
      mutation.mutate();
    }
  }

  return (
    <section className="vc-section" aria-labelledby="account-password">
      <h2 id="account-password">{t('account.password.title')}</h2>
      <form onSubmit={submit} noValidate aria-busy={mutation.isPending}>
        {localError === null ? null : <Alert variant="error" title={localError} />}
        {wrongCurrent || (mutation.error instanceof ApiProblem && mutation.error.status === 422) ? null : <ProblemAlert error={mutation.error} />}
        {mutation.isSuccess ? <Alert variant="info" title={t('account.password.done')} /> : null}
        <TextField
          id="current-password"
          type="password"
          label={t('account.password.current')}
          value={current}
          onChange={setCurrent}
          autoComplete="current-password"
          required
          error={wrongCurrent ? t('account.password.wrongCurrent') : undefined}
        />
        <TextField id="account-new-password" type="password" label={t('auth.newPassword')} value={next} onChange={setNext} autoComplete="new-password" required error={fieldError(mutation.error, 'newPassword')} />
        <TextField id="account-confirm-password" type="password" label={t('auth.confirmPassword')} value={confirmation} onChange={setConfirmation} autoComplete="new-password" required />
        <p className="vc-muted">{t('auth.passwordRules')}</p>
        <button type="submit" className="vc-button vc-button--primary" disabled={mutation.isPending || current.length === 0}>
          {t('account.password.submit')}
        </button>
      </form>
    </section>
  );
}

function MfaSection({ profile }: { readonly profile: Profile }) {
  const { t } = useTranslation();
  const { state, refresh } = useSession();
  const queryClient = useQueryClient();
  const [enrolling, setEnrolling] = useState(false);
  const [reauthOpen, setReauthOpen] = useState(false);
  const regenerate = useMutation({
    mutationFn: regenerateRecoveryCodes,
    onError: (error) => {
      if (isReauthRequired(error)) {
        setReauthOpen(true);
      }
    },
  });
  const required = profile.isPlatformAdmin || (state.status === 'authenticated' && state.user.memberships.some((m) => m.role === 'OWNER'));

  return (
    <section className="vc-section" aria-labelledby="account-mfa">
      <h2 id="account-mfa">{t('account.mfa.title')}</h2>
      <p>
        {profile.mfaEnabled ? <Badge tone="success">{t('account.mfa.on')}</Badge> : <Badge tone="warning">{t('account.mfa.off')}</Badge>}{' '}
        {t('account.mfa.intro')}
      </p>
      {required ? <p className="vc-muted">{t('account.mfa.required')}</p> : null}
      {!profile.mfaEnabled && !enrolling ? (
        <button
          type="button"
          className="vc-button vc-button--primary"
          onClick={() => {
            setEnrolling(true);
          }}
        >
          {t('account.mfa.enable')}
        </button>
      ) : null}
      {!profile.mfaEnabled && enrolling ? (
        <MfaEnrollment
          onFinished={() => {
            setEnrolling(false);
            void queryClient.invalidateQueries({ queryKey: ['account'] });
            void refresh();
          }}
        />
      ) : null}
      {profile.mfaEnabled ? (
        <>
          <p className="vc-muted">{t('account.mfa.regenerateIntro')}</p>
          {regenerate.error !== null && !isReauthRequired(regenerate.error) ? <ProblemAlert error={regenerate.error} /> : null}
          <button
            type="button"
            className="vc-button"
            disabled={regenerate.isPending}
            onClick={() => {
              regenerate.mutate();
            }}
          >
            {t('account.mfa.regenerate')}
          </button>
          {regenerate.data === undefined ? null : <RecoveryCodesPanel codes={regenerate.data} />}
        </>
      ) : null}
      <ReauthDialog
        open={reauthOpen}
        onClose={() => {
          setReauthOpen(false);
        }}
        onConfirmed={() => {
          setReauthOpen(false);
          regenerate.mutate();
        }}
      />
    </section>
  );
}
