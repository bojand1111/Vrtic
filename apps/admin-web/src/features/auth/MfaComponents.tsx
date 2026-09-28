import { useMutation } from '@tanstack/react-query';
import { type SubmitEvent, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { ApiProblem, fieldError } from '../../api/problem';
import { useFormat } from '../../app/format';
import { Alert } from '../../components/Alert';
import { Modal } from '../../components/Modal';
import { ProblemAlert } from '../../components/ProblemAlert';
import { TextField } from '../../components/TextField';
import { confirmTotp, formatSecret, isReauthRequired, normalizeOtp, reauthenticate, type RecoveryCodes, startTotpSetup } from './mfaApi';
import { encodeQr } from './qr';

/** Localized message for a wrong/expired code, otherwise null (caller shows the generic problem). */
function codeError(error: unknown, t: (key: 'auth.mfa.invalidCode') => string): string | undefined {
  if (error instanceof ApiProblem && error.status === 422) {
    return t('auth.mfa.invalidCode');
  }
  return fieldError(error, 'code');
}

/** Password re-entry for actions that need a recent sign-in (403 REAUTHENTICATION_REQUIRED). */
export function ReauthDialog({ open, onClose, onConfirmed }: { readonly open: boolean; readonly onClose: () => void; readonly onConfirmed: () => void }) {
  const { t } = useTranslation();
  const [password, setPassword] = useState('');
  const mutation = useMutation({
    mutationFn: () => reauthenticate(password),
    onSuccess: () => {
      setPassword('');
      onConfirmed();
    },
  });

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (password.length > 0) {
      mutation.mutate();
    }
  }

  return (
    <Modal title={t('auth.mfa.reauthTitle')} open={open} onClose={onClose}>
      <form onSubmit={submit} noValidate aria-busy={mutation.isPending}>
        <p>{t('auth.mfa.reauthIntro')}</p>
        <ProblemAlert error={mutation.error} />
        <TextField id="reauth-password" type="password" label={t('auth.password')} value={password} onChange={setPassword} autoComplete="current-password" required />
        <div className="vc-toolbar">
          <button type="submit" className="vc-button vc-button--primary" disabled={mutation.isPending || password.length === 0}>
            {t('auth.mfa.reauthSubmit')}
          </button>
          <button type="button" className="vc-button vc-button--ghost" onClick={onClose}>
            {t('ui.cancel')}
          </button>
        </div>
      </form>
    </Modal>
  );
}

/** Copy button with visible feedback; the text stays selectable when the clipboard is unavailable. */
export function CopyButton({ text, label }: { readonly text: string; readonly label: string }) {
  const { t } = useTranslation();
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle');
  return (
    <span>
      <button
        type="button"
        className="vc-button vc-button--small"
        onClick={() => {
          navigator.clipboard.writeText(text).then(
            () => {
              setState('copied');
            },
            () => {
              setState('failed');
            },
          );
        }}
      >
        {label}
      </button>{' '}
      <span role="status" className="vc-muted">
        {state === 'copied' ? t('auth.mfa.copied') : state === 'failed' ? t('auth.mfa.copyFailed') : ''}
      </span>
    </span>
  );
}

/** QR code as SVG (one path, no HTML injection); a quiet zone of four modules. */
export function QrCode({ text, label }: { readonly text: string; readonly label: string }) {
  const qr = useMemo(() => encodeQr(text), [text]);
  const d = useMemo(() => {
    const parts: string[] = [];
    qr.modules.forEach((row, y) => {
      row.forEach((dark, x) => {
        if (dark) {
          parts.push(`M${String(x + 4)} ${String(y + 4)}h1v1h-1z`);
        }
      });
    });
    return parts.join('');
  }, [qr]);
  const size = qr.size + 8;
  return (
    <svg role="img" aria-label={label} viewBox={`0 0 ${String(size)} ${String(size)}`} width="232" height="232" shapeRendering="crispEdges">
      <rect width={size} height={size} fill="#ffffff" />
      <path d={d} fill="#000000" />
    </svg>
  );
}

export function RecoveryCodesPanel({ codes }: { readonly codes: RecoveryCodes }) {
  const { t } = useTranslation();
  return (
    <section aria-labelledby="recovery-codes-heading" className="vc-section">
      <h2 id="recovery-codes-heading">{t('auth.mfa.recoveryTitle')}</h2>
      <Alert variant="info" title={t('auth.mfa.recoveryIntro')} />
      <pre className="vc-pre" aria-label={t('auth.mfa.recoveryTitle')}>
        {codes.codes.join('\n')}
      </pre>
      <CopyButton text={codes.codes.join('\n')} label={t('auth.mfa.copyCodes')} />
    </section>
  );
}

/**
 * TOTP enrollment: start (may ask for the password first), scan QR or type the key, confirm with the
 * first code, then show the recovery codes once. `onFinished` runs when the user leaves the codes screen.
 */
export function MfaEnrollment({ onFinished }: { readonly onFinished: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const [code, setCode] = useState('');
  // Client-side hint: the setup screen shows the secret key right above the code field, so a pasted key is a common mistake.
  const [codeHint, setCodeHint] = useState<string | undefined>(undefined);
  const [reauthOpen, setReauthOpen] = useState(false);
  const setup = useMutation({
    mutationFn: startTotpSetup,
    onError: (error) => {
      if (isReauthRequired(error)) {
        setReauthOpen(true);
      }
    },
  });
  const confirm = useMutation({ mutationFn: (value: string) => confirmTotp(value) });

  if (confirm.data !== undefined) {
    return (
      <>
        <Alert variant="info" title={t('auth.mfa.enabled')} />
        <RecoveryCodesPanel codes={confirm.data} />
        <button type="button" className="vc-button vc-button--primary" onClick={onFinished}>
          {t('auth.mfa.continue')}
        </button>
      </>
    );
  }

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = normalizeOtp(code);
    if (!/^[0-9]{6}$/.test(value)) {
      setCodeHint(/[a-z]/i.test(value) ? t('auth.mfa.keyNotCode') : t('auth.mfa.sixDigits'));
      return;
    }
    setCodeHint(undefined);
    confirm.mutate(value);
  }

  const data = setup.data;
  return (
    <div>
      {data === undefined ? (
        <>
          <p>{t('auth.mfa.appHint')}</p>
          {setup.error !== null && !isReauthRequired(setup.error) ? <ProblemAlert error={setup.error} /> : null}
          <button
            type="button"
            className="vc-button vc-button--primary"
            disabled={setup.isPending}
            onClick={() => {
              setup.mutate();
            }}
          >
            {t('auth.mfa.start')}
          </button>
        </>
      ) : (
        <>
          <p>{t('auth.mfa.scan')}</p>
          <QrCode text={data.secretUri} label={t('auth.mfa.qrAlt')} />
          <p>{t('auth.mfa.manual')}</p>
          <p>
            <code>{formatSecret(data.secretBase32)}</code> <CopyButton text={data.secretBase32} label={t('auth.mfa.copy')} />
          </p>
          <details>
            <summary>{t('auth.mfa.uriLabel')}</summary>
            <p>
              <code className="vc-pre">{data.secretUri}</code> <CopyButton text={data.secretUri} label={t('auth.mfa.copy')} />
            </p>
          </details>
          <form onSubmit={submit} noValidate aria-busy={confirm.isPending}>
            <p>{t('auth.mfa.confirmIntro', { time: format.dateTime(data.expiresAt) })}</p>
            {confirm.error instanceof ApiProblem && confirm.error.status === 422 ? null : <ProblemAlert error={confirm.error} />}
            <TextField id="mfa-confirm-code" label={t('auth.mfa.code')} value={code} onChange={(v) => {
                setCode(v);
                setCodeHint(undefined);
              }}
              autoComplete="one-time-code"
              required
              error={codeHint ?? codeError(confirm.error, t)}
            />
            <button type="submit" className="vc-button vc-button--primary" disabled={confirm.isPending || code.trim().length === 0}>
              {t('auth.mfa.confirm')}
            </button>
          </form>
        </>
      )}
      <ReauthDialog
        open={reauthOpen}
        onClose={() => {
          setReauthOpen(false);
        }}
        onConfirmed={() => {
          setReauthOpen(false);
          setup.mutate();
        }}
      />
    </div>
  );
}
