import { apiFetch } from '../../api/client';
import type { Locale } from '../../i18n/locale';

/** docs/openapi.yaml `User` as returned by GET /me. */
export interface Profile {
  readonly id: string;
  readonly email: string;
  readonly emailVerifiedAt?: string | null;
  readonly givenName: string;
  readonly familyName: string;
  readonly preferredLocale: Locale;
  readonly status: string;
  readonly mfaEnabled: boolean;
  readonly isPlatformAdmin: boolean;
  readonly lastLoginAt?: string | null;
  readonly createdAt: string;
}

/** docs/openapi.yaml `Session` (GET /auth/sessions). */
export interface DeviceSession {
  readonly id: string;
  readonly clientKind: 'WEB' | 'ANDROID' | 'IOS';
  readonly deviceName?: string | null;
  readonly userAgent?: string | null;
  readonly createdAt: string;
  readonly lastSeenAt: string;
  readonly absoluteExpiresAt: string;
  readonly mfaVerifiedAt?: string | null;
  readonly isCurrent: boolean;
}

export interface SessionPage {
  readonly items: readonly DeviceSession[];
  readonly nextCursor: string | null;
}

export const getProfile = (signal?: AbortSignal) => apiFetch<Profile>('/api/v1/me', { signal });
export const updateLocale = (preferredLocale: Locale) => apiFetch<Profile>('/api/v1/me/locale', { method: 'PUT', body: { preferredLocale } });
export const changePassword = (currentPassword: string, newPassword: string) =>
  apiFetch<undefined>('/api/v1/me/password', { method: 'POST', body: { currentPassword, newPassword } });
export const listSessions = (signal?: AbortSignal) => apiFetch<SessionPage>('/api/v1/auth/sessions?limit=100', { signal });
/** Answers are handled by the page (403 REAUTHENTICATION_REQUIRED, 401 after revoking the own session). */
export const revokeSession = (id: string) =>
  apiFetch<undefined>(`/api/v1/auth/sessions/${encodeURIComponent(id)}`, { method: 'DELETE', skipUnauthorizedHandler: true });
export const logoutEverywhere = () => apiFetch<undefined>('/api/v1/auth/logout-all', { method: 'POST', skipUnauthorizedHandler: true });
