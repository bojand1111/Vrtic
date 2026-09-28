import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import { apiFetch } from '../../api/client';
import { useOrg } from '../../api/org';
import type { QueryKeyPart } from '../../tenant/queryKeys';

/** Like `useOrgQuery`, with a polling interval (the shared hook has no refetchInterval option). */
export function useOrgPoll<T>(
  parts: readonly QueryKeyPart[],
  path: string,
  intervalMs: number,
  enabled = true,
): UseQueryResult<T> {
  const org = useOrg();
  return useQuery<T>({
    queryKey: org.key(...parts, path),
    queryFn: ({ signal }) => apiFetch<T>(org.path(path), { signal }),
    enabled: org.organizationId !== null && enabled,
    refetchInterval: intervalMs,
  });
}
