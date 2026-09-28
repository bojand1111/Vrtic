import { type ReactNode, useCallback, useEffect, useMemo, useState } from 'react';

import { getSession } from '../api/auth';
import { setUnauthorizedHandler } from '../api/client';
import { SessionContext, type SessionContextValue } from './session-context';
import type { SessionState } from './types';

interface SessionProviderProps {
  readonly children: ReactNode;
  /**
   * Test/bootstrap override. When given, the provider does not call the backend on mount.
   * Production always starts in `loading` and asks GET /api/v1/auth/session.
   */
  readonly initialState?: SessionState;
}

const UNAUTHENTICATED: SessionState = { status: 'unauthenticated' };
const EXPIRED: SessionState = { status: 'unauthenticated', reason: 'expired' };

/**
 * Holds the *view* of the cookie session. Tokens never reach JavaScript: the backend
 * keeps them in HttpOnly cookies, and this provider only knows "who am I" as reported
 * by the session endpoint. Nothing is persisted in localStorage/sessionStorage.
 */
export function SessionProvider({ children, initialState }: SessionProviderProps) {
  const bootstrap = initialState === undefined;
  const [state, setState] = useState<SessionState>(initialState ?? { status: 'loading' });

  const markUnauthenticated = useCallback((reason?: 'expired') => {
    setState((previous) => {
      if (previous.status === 'unauthenticated') {
        return previous;
      }
      return reason === 'expired' ? EXPIRED : UNAUTHENTICATED;
    });
  }, []);

  const refresh = useCallback(async () => {
    try {
      const response = await getSession();
      setState({ status: 'authenticated', user: response.user, mfaPending: response.mfaRequired === true || response.mfaEnrollmentRequired === true });
    } catch {
      setState(UNAUTHENTICATED);
    }
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(() => {
      setState((previous) => (previous.status === 'authenticated' ? EXPIRED : previous));
    });
    return () => {
      setUnauthorizedHandler(null);
    };
  }, []);

  useEffect(() => {
    if (!bootstrap) {
      return;
    }
    const controller = new AbortController();
    getSession(controller.signal)
      .then((response) => {
        setState({ status: 'authenticated', user: response.user, mfaPending: response.mfaRequired === true || response.mfaEnrollmentRequired === true });
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setState(UNAUTHENTICATED);
        }
      });
    return () => {
      controller.abort();
    };
  }, [bootstrap]);

  const value = useMemo<SessionContextValue>(
    () => ({ state, markUnauthenticated, refresh }),
    [state, markUnauthenticated, refresh],
  );

  return <SessionContext value={value}>{children}</SessionContext>;
}
