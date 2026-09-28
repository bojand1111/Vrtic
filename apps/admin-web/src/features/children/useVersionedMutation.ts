import { useMutation, type UseMutationResult, useQueryClient } from '@tanstack/react-query';

import { apiFetch, type HttpMethod } from '../../api/client';
import { useOrg } from '../../api/org';

export interface VersionedRequest {
  readonly method: HttpMethod;
  readonly path: string;
  readonly body?: unknown;
  /** Sent as `If-Match: "<version>"` (optimistic concurrency). */
  readonly version: number;
}

/**
 * Like `useOrgMutation`, but sends `If-Match` (docs/openapi.yaml IfMatch). A stale version answers
 * 409 with `currentVersion`, which ProblemAlert shows as a conflict.
 */
export function useVersionedMutation<TResult = unknown>(invalidate: readonly string[]): UseMutationResult<TResult, Error, VersionedRequest> {
  const org = useOrg();
  const queryClient = useQueryClient();
  return useMutation<TResult, Error, VersionedRequest>({
    mutationFn: (request) =>
      apiFetch<TResult>(org.path(request.path), {
        method: request.method,
        headers: { 'If-Match': `"${String(request.version)}"` },
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
