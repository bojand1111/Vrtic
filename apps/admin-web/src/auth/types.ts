/** Mirrors app.organization_memberships.role in docs/database/schema.sql. */
export type OrganizationRole = 'OWNER' | 'ADMIN' | 'TEACHER' | 'PARENT';

export interface OrganizationMembership {
  readonly organizationId: string;
  readonly organizationName: string;
  readonly role: OrganizationRole;
}

/** Placeholder shape of the signed-in user as the session endpoint will describe it. */
export interface SessionUser {
  readonly id: string;
  readonly email: string;
  readonly displayName: string;
  /** app.platform_admins - SUPER_ADMIN with no automatic access to tenant data. */
  readonly isPlatformAdmin: boolean;
  readonly memberships: readonly OrganizationMembership[];
}

export type SessionState =
  | { readonly status: 'loading' }
  | { readonly status: 'unauthenticated'; readonly reason?: 'expired' }
  | { readonly status: 'authenticated'; readonly user: SessionUser; readonly mfaPending?: boolean };
