import {
  type QueryKey,
  useMutation,
  type UseMutationResult,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';
import { useCallback, useMemo } from 'react';

import { type Permission, roleCan } from '../auth/permissions';
import type { OrganizationRole } from '../auth/types';
import { qk, type QueryKeyPart } from '../tenant/queryKeys';
import { useTenant } from '../tenant/useTenant';
import { apiFetch, type HttpMethod } from './client';

const ROLE_RANK: Readonly<Record<OrganizationRole, number>> = { OWNER: 0, ADMIN: 1, TEACHER: 2, PARENT: 3 };

export interface OrgContext {
  readonly organizationId: string | null;
  readonly userId: string | null;
  /** Highest-privilege role in the active organization (the backend scopes requests the same way). */
  readonly role: OrganizationRole | null;
  readonly can: (permission: Permission) => boolean;
  /** `/api/v1/organizations/{id}` + `path` (path starts with '/'). */
  readonly path: (path: string) => string;
  /** React Query key scoped by user + organization. */
  readonly key: (...parts: readonly QueryKeyPart[]) => QueryKey;
}

export function useOrg(): OrgContext {
  const { organizationId, userId, memberships } = useTenant();
  const role = useMemo<OrganizationRole | null>(() => {
    const roles = memberships.filter((m) => m.organizationId === organizationId).map((m) => m.role);
    roles.sort((a, b) => ROLE_RANK[a] - ROLE_RANK[b]);
    return roles[0] ?? null;
  }, [memberships, organizationId]);
  const can = useCallback((permission: Permission) => roleCan(role, permission), [role]);
  const path = useCallback(
    (p: string) => `/api/v1/organizations/${encodeURIComponent(organizationId ?? 'none')}${p}`,
    [organizationId],
  );
  const key = useCallback(
    (...parts: readonly QueryKeyPart[]): QueryKey => qk(userId, organizationId, ...parts),
    [userId, organizationId],
  );
  return { organizationId, userId, role, can, path, key };
}

/**
 * GET a tenant resource. `parts` identify the cache entry (scoped by user + organization);
 * `path` is relative to the organization, e.g. '/children?status=ACTIVE'.
 */
export function useOrgQuery<T>(
  parts: readonly QueryKeyPart[],
  path: string,
  options: { readonly enabled?: boolean } = {},
): UseQueryResult<T> {
  const org = useOrg();
  return useQuery<T>({
    queryKey: org.key(...parts, path),
    queryFn: ({ signal }) => apiFetch<T>(org.path(path), { signal }),
    enabled: org.organizationId !== null && (options.enabled ?? true),
  });
}

export interface OrgMutationRequest {
  readonly method: HttpMethod;
  readonly path: string;
  readonly body?: unknown;
}

/**
 * Mutation against a tenant resource. On success every query of the organization whose key contains
 * one of `invalidate` parts is refetched (e.g. ['children'] refreshes lists and details).
 */
export function useOrgMutation<TResult = unknown>(
  invalidate: readonly string[],
): UseMutationResult<TResult, Error, OrgMutationRequest> {
  const org = useOrg();
  const queryClient = useQueryClient();
  return useMutation<TResult, Error, OrgMutationRequest>({
    mutationFn: (request) =>
      apiFetch<TResult>(org.path(request.path), {
        method: request.method,
        ...(request.body === undefined ? {} : { body: request.body }),
      }),
    onSuccess: async () => {
      const prefix = org.key();
      await queryClient.invalidateQueries({
        predicate: (query) => {
          const k = query.queryKey;
          if (!prefix.every((part, i) => k[i] === part)) {
            return false;
          }
          return invalidate.length === 0 || k.some((part) => typeof part === 'string' && invalidate.includes(part));
        },
      });
    },
  });
}
