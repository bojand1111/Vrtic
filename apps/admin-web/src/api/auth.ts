import type { SessionUser } from '../auth/types';
import { apiFetch } from './client';

export interface LoginRequest {
  readonly email: string;
  readonly password: string;
  readonly clientKind: 'WEB';
}

export interface SessionResponse {
  readonly user: SessionUser;
  /** Session must complete MFA (verify or enrollment) before tenant routes work (E02-B12). */
  readonly mfaRequired?: boolean;
  readonly mfaEnrollmentRequired?: boolean;
}

/**
 * POST /api/v1/auth/login. Web clients receive HttpOnly cookies and the SPA stores no tokens locally.
 */
export function login(request: LoginRequest): Promise<void> {
  return apiFetch<undefined>('/api/v1/auth/login', {
    method: 'POST',
    body: request,
    skipUnauthorizedHandler: true,
  });
}

/** POST /api/v1/auth/logout - revokes the current session (cookies are cleared by the backend). */
export function logout(): Promise<void> {
  return apiFetch<undefined>('/api/v1/auth/logout', { method: 'POST', skipUnauthorizedHandler: true });
}

/**
 * GET /api/v1/auth/session - describes the current cookie session for the SPA bootstrap.
 */
export async function getSession(signal?: AbortSignal): Promise<SessionResponse> {
  const response = await apiFetch<SessionResponse>('/api/v1/auth/session', {
    method: 'GET',
    signal,
    skipUnauthorizedHandler: true,
  });
  // The session endpoint names the OWNER membership role 'KINDERGARTEN_OWNER' (PRODUCT_SPEC role name);
  // every other endpoint and the permission matrix use the membership value 'OWNER'.
  const memberships = response.user.memberships.map((m) =>
    (m.role as string) === 'KINDERGARTEN_OWNER' ? { ...m, role: 'OWNER' as const } : m,
  );
  return { ...response, user: { ...response.user, memberships } };
}
