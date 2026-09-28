import type { AssignmentRole } from '../employees/types';

export interface GroupTeacher {
  readonly assignmentId: string;
  readonly employeeId: string;
  readonly displayName: string;
  readonly assignmentRole: AssignmentRole;
  readonly validFrom: string;
  readonly validTo?: string | null;
}

/** docs/openapi.yaml `Group` (+ `locationName`, today's `teachers`). Null fields are omitted. */
export interface Group {
  readonly id: string;
  readonly locationId: string;
  readonly locationName: string;
  readonly name: string;
  readonly ageFromMonths?: number | null;
  readonly ageToMonths?: number | null;
  readonly capacity?: number | null;
  readonly status: 'ACTIVE' | 'INACTIVE';
  readonly activeChildrenCount: number;
  readonly teachers: readonly GroupTeacher[];
}

/** docs/openapi.yaml `GroupTeacherAssignment` (+ names). */
export interface Assignment {
  readonly id: string;
  readonly groupId: string;
  readonly groupName: string;
  readonly employeeId: string;
  readonly employeeDisplayName: string;
  readonly assignmentRole: AssignmentRole;
  readonly validFrom: string;
  readonly validTo?: string | null;
  readonly revokedAt?: string | null;
}

/** "12–36" / "12+" / "–36" / "" for an age range in months. */
export function ageRange(from: number | null | undefined, to: number | null | undefined): string {
  const f = from ?? null;
  const t = to ?? null;
  if (f === null && t === null) {
    return '';
  }
  if (t === null) {
    return `${String(f)}+`;
  }
  return f === null ? `–${String(t)}` : `${String(f)}–${String(t)}`;
}

/** Optional integer field of an `<input type="number">`: '' -> null, otherwise the number (range is checked by the API). */
export function intOrNull(value: string): number | null {
  const trimmed = value.trim();
  return trimmed.length === 0 ? null : Number(trimmed);
}
