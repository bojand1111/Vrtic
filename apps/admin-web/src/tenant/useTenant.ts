import { useCallback, useContext } from 'react';

import { qk, type QueryKeyPart, type ScopedQueryKey } from './queryKeys';
import { TenantContext, type TenantContextValue } from './tenant-context';

export function useTenant(): TenantContextValue {
  const value = useContext(TenantContext);
  if (value === null) {
    throw new Error('useTenant must be used inside <TenantProvider>');
  }
  return value;
}

/** Returns a `qk` builder pre-bound to the current user and organization. */
export function useScopedQueryKey(): (...parts: readonly QueryKeyPart[]) => ScopedQueryKey {
  const { userId, organizationId } = useTenant();
  return useCallback((...parts: readonly QueryKeyPart[]) => qk(userId, organizationId, ...parts), [userId, organizationId]);
}
