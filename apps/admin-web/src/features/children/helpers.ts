import type { ChildSummary, GroupOption } from './types';

/** Parses 'YYYY-MM-DD' without timezone shifts. */
function parts(iso: string): [number, number, number] {
  const [y, m, d] = iso.split('-').map((v) => Number(v));
  return [y ?? 0, m ?? 1, d ?? 1];
}

/** Completed years and months between a date of birth and `today` (both 'YYYY-MM-DD'). */
export function ageParts(dateOfBirth: string, today: string): { readonly years: number; readonly months: number } {
  const [by, bm, bd] = parts(dateOfBirth);
  const [ty, tm, td] = parts(today);
  let months = (ty - by) * 12 + (tm - bm);
  if (td < bd) {
    months -= 1;
  }
  if (months < 0) {
    return { years: 0, months: 0 };
  }
  return { years: Math.floor(months / 12), months: months % 12 };
}

/** 'YYYY-MM-DD' plus `days` calendar days. */
export function addDays(iso: string, days: number): string {
  const [y, m, d] = parts(iso);
  const date = new Date(Date.UTC(y, m - 1, d + days));
  return date.toISOString().slice(0, 10);
}

/** ISO weekday 1 (Monday) .. 7 (Sunday) of a 'YYYY-MM-DD' date. */
export function isoWeekday(iso: string): number {
  const [y, m, d] = parts(iso);
  const day = new Date(Date.UTC(y, m - 1, d)).getUTCDay();
  return day === 0 ? 7 : day;
}

/** Monday of the week containing `iso`. */
export function mondayOf(iso: string): string {
  return addDays(iso, 1 - isoWeekday(iso));
}

/** Distinct groups of the children's current enrollments, sorted by name (teacher / parent group lists). */
export function groupsFromChildren(children: readonly ChildSummary[]): GroupOption[] {
  const map = new Map<string, GroupOption>();
  for (const c of children) {
    const e = c.currentEnrollment;
    if (e !== undefined && e !== null && !map.has(e.groupId)) {
      map.set(e.groupId, { id: e.groupId, name: e.groupName });
    }
  }
  return [...map.values()].sort((a, b) => a.name.localeCompare(b.name));
}

export interface TemplateDayDraft {
  readonly weekday: number;
  readonly attends: boolean;
  readonly arrivalTime: string;
  readonly departureTime: string;
}

export interface ScheduleRules {
  readonly dayOpensAt: string;
  readonly dayClosesAt: string;
  readonly workingWeekdays: readonly number[];
}

export type TemplateDayError = 'notWorkingDay' | 'timeRequired' | 'outsideHours' | 'departureBeforeArrival';

const HHMM = /^([01][0-9]|2[0-3]):[0-5][0-9]$/;

/**
 * Client-side mirror of the backend template validation (the backend checks again):
 * attending days must be working days, have both times within opening hours and arrive before departure.
 * Returns the first problem per weekday.
 */
export function validateTemplateDays(days: readonly TemplateDayDraft[], rules: ScheduleRules): Map<number, TemplateDayError> {
  const errors = new Map<number, TemplateDayError>();
  for (const d of days) {
    if (!d.attends) {
      continue;
    }
    if (!rules.workingWeekdays.includes(d.weekday)) {
      errors.set(d.weekday, 'notWorkingDay');
    } else if (!HHMM.test(d.arrivalTime) || !HHMM.test(d.departureTime)) {
      errors.set(d.weekday, 'timeRequired');
    } else if (d.arrivalTime < rules.dayOpensAt || d.arrivalTime >= rules.dayClosesAt || d.departureTime <= rules.dayOpensAt || d.departureTime > rules.dayClosesAt) {
      errors.set(d.weekday, 'outsideHours');
    } else if (d.departureTime <= d.arrivalTime) {
      errors.set(d.weekday, 'departureBeforeArrival');
    }
  }
  return errors;
}
