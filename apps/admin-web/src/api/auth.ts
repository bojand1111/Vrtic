import type { SessionUser } from '../auth/types';
import { apiFetch } from './client';

export interface LoginRequest {
  readonly email: string;
  readonly password: string;
  readonly clientKind: 'WEB';
}

export interface SessionResponse {
  readonly user: SessionUser;
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
export function getSession(signal?: AbortSignal): Promise<SessionResponse> {
  return apiFetch<SessionResponse>('/api/v1/auth/session', {
    method: 'GET',
    signal,
    skipUnauthorizedHandler: true,
  });
}
