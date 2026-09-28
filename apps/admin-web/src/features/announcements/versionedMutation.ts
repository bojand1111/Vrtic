import { useMutation, type UseMutationResult, useQueryClient } from '@tanstack/react-query';

import { apiFetch, type HttpMethod } from '../../api/client';
import { useOrg } from '../../api/org';

export interface VersionedRequest {
  readonly method: HttpMethod;
  readonly path: string;
  readonly body?: unknown;
  /** Current `version` of the resource; sent as `If-Match: "<version>"` (docs/openapi.yaml IfMatch). */
  readonly version?: number;
}

/**
 * Like `useOrgMutation`, plus the optional `If-Match` header that versioned writes need
 * (announcements, calendar events, menu days). Refetches the organization's queries whose key
 * contains one of `invalidate` on success.
 */
export function useVersionedMutation<TResult = unknown>(
  invalidate: readonly string[],
): UseMutationResult<TResult, Error, VersionedRequest> {
  const org = useOrg();
  const queryClient = useQueryClient();
  return useMutation<TResult, Error, VersionedRequest>({
    mutationFn: (request) =>
      apiFetch<TResult>(org.path(request.path), {
        method: request.method,
        ...(request.version === undefined ? {} : { headers: { 'If-Match': `"${request.version}"` } }),
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
          return k.some((part) => typeof part === 'string' && invalidate.includes(part));
        },
      });
    },
  });
}

/** `{ items, nextCursor }` page returned by list endpoints. */
export interface Page<T> {
  readonly items: readonly T[];
  readonly nextCursor?: string | null;
}

/** Minimal shape of `GET /groups` and `GET /locations` items used for selects and labels. */
export interface NamedRef {
  readonly id: string;
  readonly name: string;
}
