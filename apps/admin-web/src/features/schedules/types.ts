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
  readonly absenceId?: string | null;
  readonly isFrozen: boolean;
  readonly isEditable: boolean;
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
  readonly absenceKind?: 'SICK' | 'VACATION' | 'OTHER' | null;
}

export interface ExpectedChildren {
  readonly groupId: string;
  readonly date: string;
  readonly weekday: number;
  readonly isWorkingDay: boolean;
  readonly items: readonly ExpectedChild[];
}
