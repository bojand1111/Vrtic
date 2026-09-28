/** docs/openapi.yaml AttendanceSummaryReport (+ countedTo, byChild additions) and AuditLogEntry (+ actorName). */
import type { DailyOverviewCounters } from '../dashboard/types';

export interface AttendanceTotals {
  readonly workingDays: number;
  readonly expectedChildDays: number;
  readonly presentChildDays: number;
  readonly absentChildDays: number;
  readonly notArrivedChildDays: number;
  readonly unscheduledChildDays: number;
  readonly lateArrivals: number;
  readonly attendanceRatePct: number;
}

export interface AttendanceGroupRow {
  readonly groupId: string;
  readonly groupName: string;
  readonly expectedChildDays: number;
  readonly presentChildDays: number;
  readonly absentChildDays: number;
  readonly notArrivedChildDays: number;
  readonly unscheduledChildDays: number;
  readonly lateArrivals: number;
  readonly attendanceRatePct: number;
}

export interface AttendanceChildRow {
  readonly childId: string;
  readonly givenName: string;
  readonly familyName: string;
  readonly groupId: string;
  readonly groupName: string;
  readonly expectedDays: number;
  readonly presentDays: number;
  readonly absentSickDays: number;
  readonly absentVacationDays: number;
  readonly absentOtherDays: number;
  readonly notArrivedDays: number;
  readonly unscheduledDays: number;
  readonly lateArrivals: number;
  readonly attendanceRatePct: number;
}

export interface AttendanceSummaryReport {
  readonly from: string;
  readonly to: string;
  readonly countedTo?: string | null;
  readonly totals: AttendanceTotals;
  readonly byGroup: readonly AttendanceGroupRow[];
  readonly byChild: readonly AttendanceChildRow[];
  readonly byDate: readonly { readonly date: string; readonly counters: DailyOverviewCounters }[];
  readonly generatedAt: string;
}

export interface AuditLogEntry {
  readonly id: number;
  readonly occurredAt: string;
  readonly actorUserId?: string | null;
  readonly actorName?: string | null;
  readonly action: string;
  readonly entityType: string;
  readonly entityId?: string | null;
  readonly result: 'SUCCESS' | 'DENIED' | 'FAILED';
  readonly purpose?: string | null;
  readonly metadata: Readonly<Record<string, unknown>>;
}

export interface AuditLogPage {
  readonly items: readonly AuditLogEntry[];
  readonly nextCursor?: string | null;
}
