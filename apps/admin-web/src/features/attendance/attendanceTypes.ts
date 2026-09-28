/** docs/openapi.yaml attendance schemas (nullable fields may be omitted by the backend). */
export type AttendanceStatus = 'NOT_ARRIVED' | 'CHECKED_IN' | 'CHECKED_OUT';
export type AbsenceKind = 'SICK' | 'VACATION' | 'OTHER';
export const ABSENCE_KINDS: readonly AbsenceKind[] = ['SICK', 'VACATION', 'OTHER'];

export interface AttendanceVisit {
  readonly id: string;
  readonly sequenceNo: number;
  readonly checkInAt: string;
  readonly checkOutAt?: string | null;
  readonly checkInEventId: string;
  readonly checkOutEventId?: string | null;
}

export interface AttendanceDay {
  readonly id?: string | null;
  readonly childId: string;
  readonly groupId?: string | null;
  readonly attendanceDate: string;
  readonly status: AttendanceStatus;
  readonly absenceKind?: AbsenceKind | null;
  readonly isExpected: boolean;
  readonly expectedArrival?: string | null;
  readonly expectedDeparture?: string | null;
  readonly isUnscheduled: boolean;
  readonly isLate: boolean;
  readonly firstCheckInAt?: string | null;
  readonly lastCheckOutAt?: string | null;
  readonly visitsCount: number;
  readonly visits: readonly AttendanceVisit[];
  readonly version: number;
}

export interface DailyOverviewCounters {
  readonly expected: number;
  readonly present: number;
  readonly departed: number;
  readonly absent: number;
  readonly notArrived: number;
  readonly late: number;
  readonly unscheduledPresent: number;
  readonly physicallyPresent: number;
}

export interface DailyOverviewChild {
  readonly childId: string;
  readonly givenName: string;
  readonly familyName: string;
  readonly isExpected: boolean;
  readonly expectedArrival?: string | null;
  readonly expectedDeparture?: string | null;
  readonly status: AttendanceStatus;
  readonly absenceKind?: AbsenceKind | null;
  readonly overviewState: 'PRESENT' | 'DEPARTED' | 'ABSENT' | 'NOT_ARRIVED' | 'UNSCHEDULED_PRESENT' | 'NOT_EXPECTED';
  readonly isLate: boolean;
  readonly isUnscheduled: boolean;
  readonly firstCheckInAt?: string | null;
  readonly lastCheckOutAt?: string | null;
  readonly version: number;
}

export interface DailyOverview {
  readonly groupId: string;
  readonly date: string;
  readonly counters: DailyOverviewCounters;
  readonly children: readonly DailyOverviewChild[];
}

export type AttendanceAction = 'check-in' | 'check-out' | 'mark-absent' | 'clear-absence' | 'correction';

/** Request body of an attendance command; a fresh `commandId` per user action (idempotency key). */
export function attendanceCommand(expectedVersion: number, attendanceDate: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    commandId: crypto.randomUUID(),
    expectedVersion,
    occurredAt: new Date().toISOString(),
    attendanceDate,
    ...extra,
  };
}

/** Local wall-clock `HH:MM` on `date` as an ISO instant (browser timezone, which matches the organization's). */
export function localDateTimeToIso(date: string, time: string): string {
  return new Date(`${date}T${time}:00`).toISOString();
}
