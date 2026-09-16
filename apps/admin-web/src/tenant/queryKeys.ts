/**
 * React Query keys are always scoped by user *and* organization (tenant), so that
 * cached data can never bleed between accounts or kindergartens
 * (REQUIREMENTS_BRIEF section 23: "cache po user+tenant").
 */
export type QueryKeyPart = string | number | boolean | null | Readonly<Record<string, unknown>>;

export const ANONYMOUS_USER_SCOPE = 'anonymous';
export const NO_ORGANIZATION_SCOPE = 'none';

export type ScopedQueryKey = readonly ['user', string, 'org', string, ...QueryKeyPart[]];

/**
 * Builds a query key scoped to `userId` and `organizationId`.
 * Global data (e.g. health) uses `qk(null, null, ...)`.
 */
export function qk(
  userId: string | null,
  organizationId: string | null,
  ...parts: readonly QueryKeyPart[]
): ScopedQueryKey {
  return [
    'user',
    userId ?? ANONYMOUS_USER_SCOPE,
    'org',
    organizationId ?? NO_ORGANIZATION_SCOPE,
    ...parts,
  ] as const;
}
