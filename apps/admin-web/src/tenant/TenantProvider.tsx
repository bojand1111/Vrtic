import { useQueryClient } from '@tanstack/react-query';
import { type ReactNode, useCallback, useMemo, useState } from 'react';

import type { OrganizationMembership } from '../auth/types';
import { useSession } from '../auth/useSession';
import { TenantContext, type TenantContextValue } from './tenant-context';

const NO_MEMBERSHIPS: readonly OrganizationMembership[] = [];

interface TenantProviderProps {
  readonly children: ReactNode;
}

export function TenantProvider({ children }: TenantProviderProps) {
  const { state } = useSession();
  const queryClient = useQueryClient();

  const user = state.status === 'authenticated' ? state.user : null;
  const memberships = user?.memberships ?? NO_MEMBERSHIPS;

  const [selectedOrganizationId, setSelectedOrganizationId] = useState<string | null>(null);

  // The effective tenant is the explicit selection if it is still valid, otherwise the first membership.
  const organizationId = useMemo(() => {
    if (
      selectedOrganizationId !== null &&
      memberships.some((membership) => membership.organizationId === selectedOrganizationId)
    ) {
      return selectedOrganizationId;
    }
    return memberships[0]?.organizationId ?? null;
  }, [selectedOrganizationId, memberships]);

  const switchOrganization = useCallback(
    (next: string | null) => {
      if (next === organizationId) {
        return;
      }
      // Wipe every cached query before anything renders under the new tenant.
      queryClient.clear();
      setSelectedOrganizationId(next);
    },
    [organizationId, queryClient],
  );

  const value = useMemo<TenantContextValue>(
    () => ({
      userId: user?.id ?? null,
      organizationId,
      memberships,
      isPlatformAdmin: user?.isPlatformAdmin ?? false,
      switchOrganization,
    }),
    [user, organizationId, memberships, switchOrganization],
  );

  return <TenantContext value={value}>{children}</TenantContext>;
}
