import { describe, expect, it } from 'vitest';

import { mondayOf, parseAllergenTags, workWeek } from './week';

describe('menu week helpers', () => {
  it('finds the Monday of any day, also across month and year boundaries', () => {
    expect(mondayOf('2026-09-28')).toBe('2026-09-28'); // Monday
    expect(mondayOf('2026-10-04')).toBe('2026-09-28'); // Sunday
    expect(mondayOf('2027-01-01')).toBe('2026-12-28'); // Friday
  });

  it('lists Monday to Friday', () => {
    expect(workWeek('2026-09-28')).toEqual(['2026-09-28', '2026-09-29', '2026-09-30', '2026-10-01', '2026-10-02']);
  });

  it('normalizes allergen tags to the backend pattern', () => {
    expect(parseAllergenTags(' gluten, Mleko ,orašasti plodovi, gluten,, đumbir')).toEqual(['GLUTEN', 'MLEKO', 'ORASASTI_PLODOVI', 'DJUMBIR']);
    expect(parseAllergenTags('')).toEqual([]);
  });
});
