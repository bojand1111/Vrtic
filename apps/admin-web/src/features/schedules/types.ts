/** API shapes of the schedule endpoints (docs/openapi.yaml tag Schedules, plus `/schedules/expected`). */

export interface TemplateDay {
  readonly weekday: number;
  readonly attends: boolean;
  readonly arrivalTime?: string | null;
  readonly departureTime?: string | null;
}

export interface ScheduleTemplate {
  readonly id: string;
  readonly childId: string;
  readonly effectiveFrom: string;
  readonly effectiveTo?: string | null;
  readonly days: readonly TemplateDay[];
  readonly version: number;
}

export type ScheduleSource = 'CLOSURE' | 'ABSENCE' | 'OVERRIDE' | 'TEMPLATE' | 'NONE';

export interface ScheduleDay {
  readonly date: string;
  readonly weekday: number;
  readonly isExpected: boolean;
  readonly expectedArrival?: string | null;
  readonly expectedDeparture?: string | null;
  readonly source: ScheduleSource;
  readonly isLateChange: boolean;
  readonly overrideId?: string | null;
  readonly absenceId?: string | null;
  readonly closureDayId?: string | null;
  readonly isFrozen: boolean;
  readonly isEditable: boolean;
  /** Additions (see backend ScheduleDayDto). */
  readonly overrideVersion: number;
  readonly overrideReason?: string | null;
  readonly closureName?: string | null;
  readonly absenceKind?: AbsenceKind | null;
  readonly templateAttends?: boolean | null;
  readonly templateArrival?: string | null;
  readonly templateDeparture?: string | null;
  /** Instant after which a change of this day is flagged as late. */
  readonly changeDeadline?: string | null;
}

export interface WeekSchedule {
  readonly childId: string;
  readonly weekStart: string;
  readonly timezone: string;
  readonly version: number;
  readonly days: readonly ScheduleDay[];
  readonly changeDeadlineHours: number;
}

export interface ExpectedChild {
  readonly childId: string;
  readonly givenName: string;
  readonly familyName: string;
  readonly isExpected: boolean;
  readonly expectedArrival?: string | null;
  readonly expectedDeparture?: string | null;
  readonly source: ScheduleSource;
  readonly absenceId?: string | null;
  readonly absenceKind?: AbsenceKind | null;
  readonly overrideId?: string | null;
  readonly overrideReason?: string | null;
  readonly isLateChange: boolean;
  readonly closureDayId?: string | null;
}

export interface ExpectedChildren {
  readonly groupId: string;
  readonly date: string;
  readonly weekday: number;
  readonly isWorkingDay: boolean;
  readonly items: readonly ExpectedChild[];
  readonly closureDayId?: string | null;
  readonly closureName?: string | null;
}

export type AbsenceKind = 'SICK' | 'VACATION' | 'OTHER';

/** docs/openapi.yaml ClosureDay (+ locationName). */
export interface ClosureDay {
  readonly id: string;
  readonly locationId?: string | null;
  readonly locationName?: string | null;
  readonly closureDate: string;
  readonly name: string;
  readonly createdAt: string;
}

/** docs/openapi.yaml DayOverride. */
export interface DayOverride {
  readonly id: string;
  readonly childId: string;
  readonly overrideDate: string;
  readonly attends: boolean;
  readonly arrivalTime?: string | null;
  readonly departureTime?: string | null;
  readonly reason?: string | null;
  readonly isLateChange: boolean;
  readonly version: number;
}

export type ChangeKind = 'TEMPLATE_REPLACED' | 'OVERRIDE_SET' | 'OVERRIDE_REMOVED' | 'WEEK_UPDATED';

/** State snapshot stored in schedule_change_log (override: attends + times; template: effectiveFrom + days). */
export interface ChangeState {
  readonly attends?: boolean;
  readonly arrivalTime?: string | null;
  readonly departureTime?: string | null;
  readonly effectiveFrom?: string;
  readonly days?: readonly TemplateDay[];
}

/** Addition: GET /schedules/changes row. */
export interface ScheduleChange {
  readonly id: number;
  readonly childId: string;
  readonly givenName: string;
  readonly familyName: string;
  readonly groupId?: string | null;
  readonly groupName?: string | null;
  readonly changeKind: ChangeKind;
  readonly affectedFrom: string;
  readonly affectedTo?: string | null;
  readonly isLateChange: boolean;
  readonly actorName?: string | null;
  readonly actorRole?: 'OWNER' | 'ADMIN' | 'TEACHER' | 'PARENT' | null;
  readonly before?: ChangeState | null;
  readonly after?: ChangeState | null;
  readonly occurredAt: string;
}

export interface ScheduleChangePage {
  readonly date: string;
  readonly items: readonly ScheduleChange[];
}
