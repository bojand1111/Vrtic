import { apiFetch } from '../../api/client';
import { ApiProblem } from '../../api/problem';

/** MFA-related part of GET /auth/session (the SPA session provider reads only `user`). */
export interface SessionMfaState {
  readonly sessionId: string;
  readonly mfaVerified: boolean;
  readonly mfaEnabled: boolean;
  /** Enrolled, but this session has not passed MFA yet: only the verify step works. */
  readonly mfaRequired: boolean;
  /** MFA is mandatory for this account (OWNER / platform admin) and not enrolled yet. */
  readonly mfaEnrollmentRequired: boolean;
}

/** docs/openapi.yaml `AuthResult` (web: tokens travel as HttpOnly cookies, never in the body). */
export interface AuthResultBody {
  readonly status: 'AUTHENTICATED' | 'MFA_REQUIRED' | 'EMAIL_VERIFICATION_REQUIRED';
  readonly sessionId?: string | null;
  readonly mfaEnrollmentRequired?: boolean | null;
}

export interface TotpSetup {
  readonly secretUri: string;
  readonly secretBase32: string;
  readonly expiresAt: string;
}

export interface RecoveryCodes {
  readonly codes: readonly string[];
  readonly generatedAt: string;
}

/** Auth endpoints answer 401/403 as part of the flow; they must not trigger the global logout handler. */
const AUTH = { skipUnauthorizedHandler: true } as const;

export function getMfaState(signal?: AbortSignal): Promise<SessionMfaState> {
  return apiFetch<SessionMfaState>('/api/v1/auth/session', { ...AUTH, signal });
}

/** POST /auth/login returning the body, so the login page can branch on MFA. */
export function loginWeb(email: string, password: string): Promise<AuthResultBody> {
  return apiFetch<AuthResultBody>('/api/v1/auth/login', { ...AUTH, method: 'POST', body: { email, password, clientKind: 'WEB' } });
}

export function startTotpSetup(): Promise<TotpSetup> {
  return apiFetch<TotpSetup>('/api/v1/auth/mfa/totp/setup', { ...AUTH, method: 'POST' });
}

export function confirmTotp(code: string): Promise<RecoveryCodes> {
  return apiFetch<RecoveryCodes>('/api/v1/auth/mfa/totp/confirm', { ...AUTH, method: 'POST', body: { code } });
}

export function verifyMfa(code: string): Promise<AuthResultBody> {
  return apiFetch<AuthResultBody>('/api/v1/auth/mfa/totp/verify', { ...AUTH, method: 'POST', body: { code } });
}

export function regenerateRecoveryCodes(): Promise<RecoveryCodes> {
  return apiFetch<RecoveryCodes>('/api/v1/auth/mfa/recovery-codes/regenerate', { ...AUTH, method: 'POST' });
}

export function reauthenticate(password: string): Promise<unknown> {
  return apiFetch<unknown>('/api/v1/auth/reauthenticate', { ...AUTH, method: 'POST', body: { password } });
}

/** Where a freshly signed-in session has to go first: the MFA screen, or nowhere special (null). */
export async function mfaStepAfterSignIn(): Promise<'/mfa' | null> {
  const state = await getMfaState();
  return state.mfaRequired || state.mfaEnrollmentRequired ? '/mfa' : null;
}

/** 403 REAUTHENTICATION_REQUIRED: the action needs a recent password entry (ReauthDialog). */
export function isReauthRequired(error: unknown): boolean {
  return error instanceof ApiProblem && error.status === 403 && error.detail === 'REAUTHENTICATION_REQUIRED';
}

/** "JBSWY3DP..." -> "JBSW Y3DP ..." for manual entry in an authenticator app. */
export function formatSecret(secret: string): string {
  return (secret.match(/.{1,4}/g) ?? []).join(' ');
}

/** Trims a typed code; 6-digit TOTP codes lose inner spaces, recovery codes are upper-cased. */
export function normalizeOtp(input: string): string {
  const compact = input.replace(/\s+/g, '');
  return /^\d+$/.test(compact) ? compact : compact.toUpperCase();
}
