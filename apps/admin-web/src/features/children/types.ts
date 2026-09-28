/** API shapes of the children / guardians / pickup persons endpoints (docs/openapi.yaml, tag Children). */

export type EnrollmentStatus = 'PLANNED' | 'ACTIVE' | 'ENDED' | 'CANCELLED';
export type GuardianRelationship = 'MOTHER' | 'FATHER' | 'LEGAL_GUARDIAN' | 'GRANDPARENT' | 'OTHER';
export type GuardianStatus = 'PENDING' | 'CONFIRMED' | 'REVOKED';

export const RELATIONSHIPS: readonly GuardianRelationship[] = ['MOTHER', 'FATHER', 'LEGAL_GUARDIAN', 'GRANDPARENT', 'OTHER'];

export interface EnrollmentRef {
  readonly id: string;
  readonly groupId: string;
  readonly groupName: string;
  readonly locationId: string;
  readonly validFrom: string;
  /** The server omits null fields. */
  readonly validTo?: string | null;
  readonly status: EnrollmentStatus;
}

export interface ChildSummary {
  readonly id: string;
  readonly organizationId: string;
  readonly givenName: string;
  readonly familyName: string;
  readonly dateOfBirth: string;
  readonly status: 'ACTIVE' | 'INACTIVE';
  readonly currentEnrollment?: EnrollmentRef | null;
  readonly version: number;
}

export interface Page<T> {
  readonly items: readonly T[];
  readonly nextCursor?: string | null;
}

export interface Guardian {
  readonly id: string;
  readonly childId: string;
  readonly membershipId: string;
  readonly userId: string;
  readonly givenName: string;
  readonly familyName: string;
  readonly relationship: GuardianRelationship;
  readonly status: GuardianStatus;
  readonly isPrimary: boolean;
  readonly canManageSchedule: boolean;
  readonly canReportAbsence: boolean;
  readonly canGiveConsent: boolean;
  readonly canViewHealth: boolean;
  readonly confirmedAt?: string | null;
  readonly revokedAt?: string | null;
  readonly createdAt: string;
}

export interface PickupPerson {
  readonly id: string;
  readonly childId: string;
  readonly fullName: string;
  readonly relationship?: string | null;
  readonly phone?: string | null;
  readonly note?: string | null;
  readonly validFrom?: string | null;
  readonly validTo?: string | null;
  readonly addedByMembershipId: string;
  readonly status: 'ACTIVE' | 'REVOKED';
  readonly revokedAt?: string | null;
  readonly createdAt: string;
}

export interface ChildDetail extends ChildSummary {
  readonly generalNotes?: string | null;
  readonly enrollments: readonly EnrollmentRef[];
  readonly guardians: readonly Guardian[];
  readonly pickupPersons: readonly PickupPerson[];
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface ParentLink {
  readonly guardianId: string;
  readonly childId: string;
  readonly childGivenName: string;
  readonly childFamilyName: string;
  readonly relationship: GuardianRelationship;
  readonly status: GuardianStatus;
  readonly isPrimary: boolean;
}

export interface ParentOverview {
  readonly membershipId: string;
  readonly userId: string;
  readonly displayName: string;
  readonly email: string;
  readonly status: 'INVITED' | 'ACTIVE' | 'SUSPENDED' | 'REVOKED';
  readonly links: readonly ParentLink[];
}

/** Subset of docs/openapi.yaml `Group` used for filters and selects. */
export interface GroupOption {
  readonly id: string;
  readonly name: string;
  readonly status?: string;
}

export function childName(c: { readonly givenName: string; readonly familyName: string }): string {
  return `${c.givenName} ${c.familyName}`;
}
