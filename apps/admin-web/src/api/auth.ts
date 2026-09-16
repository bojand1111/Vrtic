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
 * POST /api/v1/auth/login. Bearer login is implemented for mobile clients; web cookie
 * delivery remains a later EPIC 02 task, so the SPA still stores no tokens locally.
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
 * GET /api/v1/auth/session - describes the current cookie session (designed in docs/openapi.yaml; answers 401 until EPIC 02).
 * 401/501 simply means "not signed in" for the SPA.
 */
export function getSession(signal?: AbortSignal): Promise<SessionResponse> {
  return apiFetch<SessionResponse>('/api/v1/auth/session', {
    method: 'GET',
    signal,
    skipUnauthorizedHandler: true,
  });
}
