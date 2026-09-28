import type { OrganizationRole } from '../../auth/types';

export type MembershipStatus = 'INVITED' | 'ACTIVE' | 'SUSPENDED' | 'REVOKED';

/** docs/openapi.yaml `Employee` (+ `primaryLocationName`, today's `groups`). Null fields are omitted. */
export interface Employee {
  readonly id: string;
  readonly membershipId: string;
  readonly userId: string;
  readonly role: OrganizationRole;
  readonly membershipStatus: MembershipStatus;
  readonly displayName: string;
  readonly email: string;
  readonly jobTitle?: string | null;
  readonly phone?: string | null;
  readonly primaryLocationId?: string | null;
  readonly primaryLocationName?: string | null;
  readonly startedAt?: string | null;
  readonly endedAt?: string | null;
  readonly permissions: readonly string[];
  readonly groups: readonly { readonly groupId: string; readonly groupName: string; readonly assignmentRole: AssignmentRole }[];
}

/** docs/openapi.yaml `Membership` (+ `employeeId`). */
export interface Membership {
  readonly id: string;
  readonly userId: string;
  readonly givenName: string;
  readonly familyName: string;
  readonly email: string;
  readonly role: OrganizationRole;
  readonly status: MembershipStatus;
  readonly permissions: readonly string[];
  readonly acceptedAt?: string | null;
  readonly revokedAt?: string | null;
  readonly childrenCount: number;
  readonly employeeId?: string | null;
}

export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'EXPIRED' | 'REVOKED';

/** docs/openapi.yaml `Invitation` (+ `childName`). The token is never part of it. */
export interface Invitation {
  readonly id: string;
  readonly email: string;
  readonly role: OrganizationRole;
  readonly childId?: string | null;
  readonly childName?: string | null;
  readonly expiresAt: string;
  readonly acceptedAt?: string | null;
  readonly revokedAt?: string | null;
  readonly status: InvitationStatus;
  readonly createdAt: string;
}

export type AssignmentRole = 'LEAD' | 'ASSISTANT' | 'SUBSTITUTE';

export const ASSIGNMENT_ROLES: readonly AssignmentRole[] = ['LEAD', 'ASSISTANT', 'SUBSTITUTE'];

/**
 * Roles a member may invite (mirror of backend `PermissionMatrix.assignableRoles`): OWNER can never
 * be invited; ADMIN invites only TEACHER and PARENT. UI hint only, the backend answers 403 otherwise.
 */
export function invitableRoles(actor: OrganizationRole | null): readonly OrganizationRole[] {
  switch (actor) {
    case 'OWNER':
      return ['ADMIN', 'TEACHER', 'PARENT'];
    case 'ADMIN':
      return ['TEACHER', 'PARENT'];
    default:
      return [];
  }
}

/** Revoke is refused by the backend for OWNER memberships, one's own membership and revoked ones. */
export function canRevokeMembership(membership: Membership, currentUserId: string | null): boolean {
  return membership.role !== 'OWNER' && membership.status !== 'REVOKED' && membership.userId !== currentUserId;
}
