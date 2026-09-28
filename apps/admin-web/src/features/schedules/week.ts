/** Pure helpers of the schedule screens (week navigation, day overrides, change feed). No React, no I/O. */
import { addDays, mondayOf, type ScheduleRules, type TemplateDayDraft, type TemplateDayError, validateTemplateDays } from '../children/helpers';
import type { ChangeState, ScheduleDay } from './types';

/** Monday `delta` weeks away from the week containing `iso`. */
export function shiftWeek(iso: string, delta: number): string {
  return addDays(mondayOf(iso), delta * 7);
}

/** The seven dates Monday..Sunday of the week starting at `weekStart`. */
export function weekDates(weekStart: string): string[] {
  return [0, 1, 2, 3, 4, 5, 6].map((i) => addDays(weekStart, i));
}

/** True when a change made at `nowMs` would be flagged late (the deadline instant has passed). */
export function isPastDeadline(changeDeadline: string | null | undefined, nowMs: number): boolean {
  if (changeDeadline === null || changeDeadline === undefined || changeDeadline === '') {
    return false;
  }
  const deadline = Date.parse(changeDeadline);
  return !Number.isNaN(deadline) && nowMs >= deadline;
}

export interface OverrideDraft {
  readonly attends: boolean;
  readonly arrivalTime: string;
  readonly departureTime: string;
  readonly reason: string;
}

/** Initial form values for one day: the current effective times, else the template, else opening defaults. */
export function overrideDraftOf(day: ScheduleDay): OverrideDraft {
  const arrival = day.expectedArrival ?? day.templateArrival ?? '08:00';
  const departure = day.expectedDeparture ?? day.templateDeparture ?? '16:00';
  return {
    attends: day.source === 'NONE' ? true : day.isExpected,
    arrivalTime: arrival,
    departureTime: departure,
    reason: day.source === 'OVERRIDE' ? (day.overrideReason ?? '') : '',
  };
}

/** Same client-side rules as the weekly template (the backend validates again). */
export function validateOverride(draft: OverrideDraft, weekday: number, rules: ScheduleRules): TemplateDayError | undefined {
  const day: TemplateDayDraft = { weekday, attends: draft.attends, arrivalTime: draft.arrivalTime, departureTime: draft.departureTime };
  return validateTemplateDays([day], rules).get(weekday);
}

/** Request body of PUT /children/{id}/schedule/overrides/{date}. */
export function overrideBody(draft: OverrideDraft): Record<string, unknown> {
  const reason = draft.reason.trim();
  const base: Record<string, unknown> = draft.attends ? { attends: true, arrivalTime: draft.arrivalTime, departureTime: draft.departureTime } : { attends: false };
  return reason === '' ? base : { ...base, reason };
}

/** Short text of a change-log state: "08:00-16:00", "not attending" (caller passes the translated word), or template days. */
export function describeState(state: ChangeState | null | undefined, notAttending: string): string {
  if (state === null || state === undefined) {
    return '';
  }
  if (state.days !== undefined) {
    const attending = state.days.filter((d) => d.attends).map((d) => `${String(d.weekday)}: ${d.arrivalTime ?? ''}-${d.departureTime ?? ''}`);
    return attending.length === 0 ? notAttending : attending.join(', ');
  }
  if (state.attends === false) {
    return notAttending;
  }
  return `${state.arrivalTime ?? ''}-${state.departureTime ?? ''}`;
}
