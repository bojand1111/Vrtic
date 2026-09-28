import { describe, expect, it } from 'vitest';

import { addDays, eventsByDay, monthRange, shiftMonth, withClosures } from './month';

describe('calendar month helpers', () => {
  it('computes month ranges including leap February', () => {
    expect(monthRange('2026-09')).toEqual({ from: '2026-09-01', to: '2026-09-30' });
    expect(monthRange('2028-02')).toEqual({ from: '2028-02-01', to: '2028-02-29' });
    expect(monthRange('2026-12')).toEqual({ from: '2026-12-01', to: '2026-12-31' });
  });

  it('shifts months across year boundaries', () => {
    expect(shiftMonth('2026-12', 1)).toBe('2027-01');
    expect(shiftMonth('2026-01', -1)).toBe('2025-12');
    expect(addDays('2026-03-28', 3)).toBe('2026-03-31');
    expect(addDays('2026-10-24', 2)).toBe('2026-10-26');
  });

  it('spreads multi-day events over each covered day inside the range', () => {
    const events = [
      { id: 'a', startsOn: '2026-08-30', endsOn: '2026-09-02' },
      { id: 'b', startsOn: '2026-09-02', endsOn: '2026-09-02' },
    ];
    const days = eventsByDay(events, '2026-09-01', '2026-09-30');
    expect(days.map((d) => d.day)).toEqual(['2026-09-01', '2026-09-02']);
    expect(days[1]?.events.map((e) => e.id)).toEqual(['a', 'b']);
  });

  it('joins closure days with event days in date order', () => {
    const days = eventsByDay([{ startsOn: '2026-09-10', endsOn: '2026-09-10' }], '2026-09-01', '2026-09-30');
    const joined = withClosures(days, [{ closureDate: '2026-09-10' }, { closureDate: '2026-09-02' }]);
    expect(joined.map((d) => [d.day, d.events.length, d.closures.length])).toEqual([
      ['2026-09-02', 0, 1],
      ['2026-09-10', 1, 1],
    ]);
  });
});
