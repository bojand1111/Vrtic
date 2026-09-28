import { apiFetch } from '../../api/client';
import type { Locale } from '../../i18n/locale';

/** docs/openapi.yaml `InvitationPreview` (GET /auth/invitations/{token}). */
export interface InvitationPreview {
  readonly organization: { readonly id: string; readonly name: string };
  readonly role: 'OWNER' | 'ADMIN' | 'TEACHER' | 'PARENT';
  readonly email: string;
  readonly childGivenName?: string | null;
  readonly expiresAt: string;
  readonly requiresRegistration: boolean;
}

export interface RegisterInput {
  readonly invitationToken: string;
  readonly password: string;
  readonly givenName: string;
  readonly familyName: string;
  readonly preferredLocale: Locale;
}

/** Public account endpoints: a 401 here is an answer, never a reason to redirect to the login page. */
const PUBLIC = { skipUnauthorizedHandler: true } as const;

export function previewInvitation(token: string, signal?: AbortSignal): Promise<InvitationPreview> {
  return apiFetch<InvitationPreview>(`/api/v1/auth/invitations/${encodeURIComponent(token)}`, { ...PUBLIC, signal });
}

/** POST /auth/register: creates the account, accepts the invitation and starts a cookie session. */
export function register(input: RegisterInput): Promise<unknown> {
  return apiFetch<unknown>('/api/v1/auth/register', { ...PUBLIC, method: 'POST', body: { ...input, device: { clientKind: 'WEB' } } });
}

/** POST /me/invitations/accept for the signed-in user (the invitation must be addressed to their e-mail). */
export function acceptInvitation(invitationToken: string): Promise<unknown> {
  return apiFetch<unknown>('/api/v1/me/invitations/accept', { method: 'POST', body: { invitationToken } });
}

export function verifyEmail(token: string): Promise<void> {
  return apiFetch<undefined>('/api/v1/auth/verify-email', { ...PUBLIC, method: 'POST', body: { token } });
}

export function forgotPassword(email: string): Promise<void> {
  return apiFetch<undefined>('/api/v1/auth/forgot-password', { ...PUBLIC, method: 'POST', body: { email } });
}

export function resetPassword(token: string, newPassword: string): Promise<void> {
  return apiFetch<undefined>('/api/v1/auth/reset-password', { ...PUBLIC, method: 'POST', body: { token, newPassword } });
}

/** Backend PasswordPolicy.MIN_LENGTH; the server checks the full policy again. */
export const PASSWORD_MIN_LENGTH = 12;

/** Client-side password check before submit: null when fine, otherwise an i18n key under `auth.`. */
export function passwordProblem(password: string, confirmation: string): 'passwordTooShort' | 'passwordMismatch' | null {
  if (password.length < PASSWORD_MIN_LENGTH) {
    return 'passwordTooShort';
  }
  return password === confirmation ? null : 'passwordMismatch';
}
