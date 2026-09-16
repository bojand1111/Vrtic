import { createContext } from 'react';

import type { SessionState } from './types';

export interface SessionContextValue {
  readonly state: SessionState;
  /** Drop the local session view (e.g. after logout or a 401). Nothing is stored client-side, so there is nothing to wipe. */
  readonly markUnauthenticated: (reason?: 'expired') => void;
  /** Re-read the cookie session from the backend. */
  readonly refresh: () => Promise<void>;
}

export const SessionContext = createContext<SessionContextValue | null>(null);
