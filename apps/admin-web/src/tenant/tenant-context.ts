import { createContext } from 'react';

import type { OrganizationMembership } from '../auth/types';

export interface TenantContextValue {
  /** Signed-in user id, or null when unauthenticated. */
  readonly userId: string | null;
  /** Active organization (tenant). Null when the user has no memberships or is not signed in. */
  readonly organizationId: string | null;
  readonly memberships: readonly OrganizationMembership[];
  readonly isPlatformAdmin: boolean;
  /** Switches tenant. Clears the whole React Query cache before the new tenant renders. */
  readonly switchOrganization: (organizationId: string | null) => void;
}

export const TenantContext = createContext<TenantContextValue | null>(null);
