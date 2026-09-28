import { describe, expect, it } from 'vitest';

import { addDays, ageParts, groupsFromChildren, isoWeekday, mondayOf, validateTemplateDays } from './helpers';
import type { ChildSummary } from './types';

describe('ageParts', () => {
  it('counts completed years and months', () => {
    expect(ageParts('2021-05-06', '2026-09-28')).toEqual({ years: 5, months: 4 });
    expect(ageParts('2021-09-29', '2026-09-28')).toEqual({ years: 4, months: 11 });
    expect(ageParts('2026-09-28', '2026-09-28')).toEqual({ years: 0, months: 0 });
  });

  it('never returns a negative age', () => {
    expect(ageParts('2027-01-01', '2026-09-28')).toEqual({ years: 0, months: 0 });
  });
});

describe('date helpers', () => {
  it('adds days across month and year ends', () => {
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
  });

  it('computes ISO weekdays and the Monday of a week', () => {
    expect(isoWeekday('2026-09-28')).toBe(1);
    expect(isoWeekday('2026-10-04')).toBe(7);
    expect(mondayOf('2026-10-04')).toBe('2026-09-28');
    expect(mondayOf('2026-09-28')).toBe('2026-09-28');
  });
});

describe('validateTemplateDays', () => {
  const rules = { dayOpensAt: '06:00', dayClosesAt: '18:00', workingWeekdays: [1, 2, 3, 4, 5] };

  it('accepts attending working days inside opening hours', () => {
    const days = [{ weekday: 1, attends: true, arrivalTime: '08:00', departureTime: '16:00' }, { weekday: 6, attends: false, arrivalTime: '', departureTime: '' }];
    expect(validateTemplateDays(days, rules).size).toBe(0);
  });

  it('reports the first problem per weekday', () => {
    const errors = validateTemplateDays(
      [
        { weekday: 6, attends: true, arrivalTime: '08:00', departureTime: '12:00' },
        { weekday: 1, attends: true, arrivalTime: '', departureTime: '12:00' },
        { weekday: 2, attends: true, arrivalTime: '05:30', departureTime: '12:00' },
        { weekday: 3, attends: true, arrivalTime: '12:00', departureTime: '11:00' },
        { weekday: 4, attends: true, arrivalTime: '08:00', departureTime: '18:30' },
      ],
      rules,
    );
    expect(Object.fromEntries(errors)).toEqual({
      6: 'notWorkingDay',
      1: 'timeRequired',
      2: 'outsideHours',
      3: 'departureBeforeArrival',
      4: 'outsideHours',
    });
  });
});

describe('groupsFromChildren', () => {
  it('returns distinct current groups sorted by name', () => {
    const base = { organizationId: 'o', dateOfBirth: '2022-01-01', status: 'ACTIVE', version: 1 } as const;
    const children: ChildSummary[] = [
      { ...base, id: '1', givenName: 'A', familyName: 'A', currentEnrollment: { id: 'e1', groupId: 'g2', groupName: 'Zvezdice', locationId: 'l', validFrom: '2026-01-01', status: 'ACTIVE' } },
      { ...base, id: '2', givenName: 'B', familyName: 'B', currentEnrollment: { id: 'e2', groupId: 'g1', groupName: 'Bubamare', locationId: 'l', validFrom: '2026-01-01', status: 'ACTIVE' } },
      { ...base, id: '3', givenName: 'C', familyName: 'C', currentEnrollment: { id: 'e3', groupId: 'g2', groupName: 'Zvezdice', locationId: 'l', validFrom: '2026-01-01', status: 'ACTIVE' } },
      { ...base, id: '4', givenName: 'D', familyName: 'D', currentEnrollment: null },
    ];
    expect(groupsFromChildren(children)).toEqual([
      { id: 'g1', name: 'Bubamare' },
      { id: 'g2', name: 'Zvezdice' },
    ]);
  });
});
