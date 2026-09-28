import { describe, expect, it } from 'vitest';

import type { ScheduleDay } from './types';
import { describeState, isPastDeadline, overrideBody, overrideDraftOf, shiftWeek, validateOverride, weekDates } from './week';

const rules = { dayOpensAt: '06:00', dayClosesAt: '18:00', workingWeekdays: [1, 2, 3, 4, 5] };

function day(partial: Partial<ScheduleDay>): ScheduleDay {
  return {
    date: '2026-09-30',
    weekday: 3,
    isExpected: true,
    expectedArrival: '08:00',
    expectedDeparture: '16:00',
    source: 'TEMPLATE',
    isLateChange: false,
    isFrozen: false,
    isEditable: true,
    overrideVersion: 0,
    templateAttends: true,
    templateArrival: '08:00',
    templateDeparture: '16:00',
    ...partial,
  };
}

describe('week navigation', () => {
  it('moves by whole weeks from any day and across month/year ends', () => {
    expect(shiftWeek('2026-09-30', 0)).toBe('2026-09-28');
    expect(shiftWeek('2026-09-30', 1)).toBe('2026-10-05');
    expect(shiftWeek('2026-09-28', -1)).toBe('2026-09-21');
    expect(shiftWeek('2026-12-31', 1)).toBe('2027-01-04');
  });

  it('lists Monday..Sunday', () => {
    expect(weekDates('2026-12-28')).toEqual(['2026-12-28', '2026-12-29', '2026-12-30', '2026-12-31', '2027-01-01', '2027-01-02', '2027-01-03']);
  });
});

describe('late change deadline', () => {
  it('is late from the deadline instant on', () => {
    const deadline = '2026-09-29T16:00:00Z';
    expect(isPastDeadline(deadline, Date.parse('2026-09-29T15:59:59Z'))).toBe(false);
    expect(isPastDeadline(deadline, Date.parse('2026-09-29T16:00:00Z'))).toBe(true);
    expect(isPastDeadline(null, Date.now())).toBe(false);
    expect(isPastDeadline('not a date', Date.now())).toBe(false);
  });
});

describe('day override form', () => {
  it('starts from the effective day and keeps an override reason', () => {
    expect(overrideDraftOf(day({}))).toEqual({ attends: true, arrivalTime: '08:00', departureTime: '16:00', reason: '' });
    const override = day({ source: 'OVERRIDE', isExpected: false, expectedArrival: null, expectedDeparture: null, overrideReason: 'Izlet' });
    expect(overrideDraftOf(override)).toEqual({ attends: false, arrivalTime: '08:00', departureTime: '16:00', reason: 'Izlet' });
    expect(overrideDraftOf(day({ source: 'NONE', isExpected: false, expectedArrival: null, templateArrival: null, templateDeparture: null, expectedDeparture: null })).attends).toBe(true);
  });

  it('validates like the template and builds the request body', () => {
    const draft = { attends: true, arrivalTime: '05:30', departureTime: '12:00', reason: ' ' };
    expect(validateOverride(draft, 3, rules)).toBe('outsideHours');
    expect(validateOverride({ ...draft, arrivalTime: '08:00' }, 6, rules)).toBe('notWorkingDay');
    expect(validateOverride({ ...draft, arrivalTime: '13:00' }, 3, rules)).toBe('departureBeforeArrival');
    expect(validateOverride({ ...draft, attends: false }, 6, rules)).toBeUndefined();
    expect(overrideBody({ ...draft, arrivalTime: '08:00' })).toEqual({ attends: true, arrivalTime: '08:00', departureTime: '12:00' });
    expect(overrideBody({ ...draft, attends: false, reason: ' Kod bake ' })).toEqual({ attends: false, reason: 'Kod bake' });
  });
});

describe('change feed state text', () => {
  it('describes override and template states', () => {
    expect(describeState({ attends: true, arrivalTime: '08:00', departureTime: '12:00' }, 'ne dolazi')).toBe('08:00-12:00');
    expect(describeState({ attends: false }, 'ne dolazi')).toBe('ne dolazi');
    expect(describeState(null, 'ne dolazi')).toBe('');
    expect(describeState({ effectiveFrom: '2026-10-01', days: [{ weekday: 1, attends: true, arrivalTime: '08:00', departureTime: '16:00' }, { weekday: 2, attends: false }] }, 'x')).toBe('1: 08:00-16:00');
  });
});
