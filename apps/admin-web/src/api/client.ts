import { getCurrentLocale } from '../i18n/locale';
import { CSRF_HEADER_NAME, getCsrfToken, UNSAFE_HTTP_METHODS } from './csrf';
import { NetworkError, problemFromResponse } from './problem';

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface ApiRequestOptions {
  readonly method?: HttpMethod;
  /** JSON-serialisable request body. */
  readonly body?: unknown;
  readonly signal?: AbortSignal | undefined;
  readonly headers?: Readonly<Record<string, string>>;
  /**
   * Skip the global 401 handler. Used by auth endpoints themselves, where a 401 is an
   * expected answer (wrong password, no session) and must not trigger a redirect loop.
   */
  readonly skipUnauthorizedHandler?: boolean;
}

export type UnauthorizedHandler = () => void;

let unauthorizedHandler: UnauthorizedHandler | null = null;

/**
 * Registered once by the session layer. On any 401 the session is marked as
 * unauthenticated, which makes the router redirect to the login page.
 */
export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler;
}

function isAbortError(value: unknown): value is DOMException {
  return value instanceof DOMException && value.name === 'AbortError';
}

async function parseJsonBody<T>(response: Response): Promise<T> {
  if (response.status === 204 || response.headers.get('content-length') === '0') {
    return undefined as T;
  }
  const text = await response.text();
  if (text.length === 0) {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}

/**
 * Minimal fetch wrapper for the same-origin API.
 *
 * - cookies travel with every request (`credentials: 'include'`); nothing is ever read from storage
 * - `X-CSRF-Token` is attached to unsafe methods when a token source provides one
 * - non-2xx responses become {@link ApiProblem}; transport failures become {@link NetworkError}
 * - 401 notifies the session layer (unless the caller opts out)
 */
export async function apiFetch<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET';
  const headers = new Headers(options.headers);
  headers.set('Accept', 'application/json, application/problem+json');
  headers.set('Accept-Language', getCurrentLocale());

  let body: string | undefined;
  if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json');
    body = JSON.stringify(options.body);
  }

  if (UNSAFE_HTTP_METHODS.has(method)) {
    const token = getCsrfToken();
    if (token !== null) {
      headers.set(CSRF_HEADER_NAME, token);
    }
  }

  const init: RequestInit = {
    method,
    headers,
    credentials: 'include',
    cache: 'no-store',
    redirect: 'follow',
  };
  if (body !== undefined) {
    init.body = body;
  }
  if (options.signal !== undefined) {
    init.signal = options.signal;
  }

  let response: Response;
  try {
    response = await fetch(path, init);
  } catch (cause) {
    if (isAbortError(cause)) {
      throw cause;
    }
    throw new NetworkError({ cause });
  }

  if (response.status === 401 && options.skipUnauthorizedHandler !== true) {
    unauthorizedHandler?.();
  }

  if (!response.ok) {
    throw await problemFromResponse(response);
  }

  return parseJsonBody<T>(response);
}
