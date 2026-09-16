/**
 * CSRF hook (placeholder until the backend defines the exact scheme).
 *
 * Planned scheme (REQUIREMENTS_BRIEF section 7): HttpOnly session cookies + CSRF token + exact Origin check.
 * The token is expected in a readable (non-HttpOnly) cookie and echoed back in the
 * `X-CSRF-Token` header on every state-changing request (double-submit pattern).
 * If the backend ends up delivering the token differently (e.g. in the session response),
 * register another source with {@link setCsrfTokenSource}; the API client does not care.
 */
export const CSRF_HEADER_NAME = 'X-CSRF-Token';
export const CSRF_COOKIE_NAME = 'vc_csrf';

export const UNSAFE_HTTP_METHODS: ReadonlySet<string> = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

export type CsrfTokenSource = () => string | null;

export function readCsrfCookie(): string | null {
  if (typeof document === 'undefined') {
    return null;
  }
  const prefix = `${CSRF_COOKIE_NAME}=`;
  for (const part of document.cookie.split(';')) {
    const trimmed = part.trim();
    if (trimmed.startsWith(prefix)) {
      const value = trimmed.slice(prefix.length);
      try {
        return value.length > 0 ? decodeURIComponent(value) : null;
      } catch {
        return null;
      }
    }
  }
  return null;
}

let tokenSource: CsrfTokenSource = readCsrfCookie;

export function setCsrfTokenSource(source: CsrfTokenSource | null): void {
  tokenSource = source ?? readCsrfCookie;
}

export function getCsrfToken(): string | null {
  return tokenSource();
}
