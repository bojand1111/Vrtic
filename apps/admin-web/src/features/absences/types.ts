/** docs/openapi.yaml `Absence` plus `childGivenName` / `childFamilyName` / `canCancel` (additions). */
export type AbsenceKind = 'SICK' | 'VACATION' | 'OTHER';

export const ABSENCE_KINDS: readonly AbsenceKind[] = ['SICK', 'VACATION', 'OTHER'];

export interface Absence {
  readonly id: string;
  readonly childId: string;
  readonly childGivenName: string;
  readonly childFamilyName: string;
  readonly kind: AbsenceKind;
  readonly dateFrom: string;
  readonly dateTo: string;
  readonly note?: string | null;
  readonly status: 'ACTIVE' | 'CANCELLED';
  readonly reportedByMembershipId: string;
  readonly version: number;
  readonly canCancel: boolean;
}
