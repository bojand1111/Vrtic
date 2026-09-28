/** Pure helpers for the weekly menu view (ISO dates, UTC arithmetic). */

function parse(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y ?? 1970, (m ?? 1) - 1, d ?? 1));
}

export function addDays(iso: string, days: number): string {
  const d = parse(iso);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** Monday of the ISO week containing `iso`. */
export function mondayOf(iso: string): string {
  const weekday = (parse(iso).getUTCDay() + 6) % 7; // Monday = 0
  return addDays(iso, -weekday);
}

/** Monday..Friday of the week starting at `monday`. */
export function workWeek(monday: string): readonly string[] {
  return [0, 1, 2, 3, 4].map((i) => addDays(monday, i));
}

/**
 * "gluten, mleko, orašasti plodovi" -> ["GLUTEN", "MLEKO", "ORASASTI_PLODOVI"]: upper case, no diacritics,
 * spaces/dashes to underscores, duplicates and empty entries removed (backend pattern ^[A-Z][A-Z0-9_]{1,30}$).
 */
export function parseAllergenTags(input: string): string[] {
  const tags = input
    .split(',')
    .map((raw) =>
      raw
        .trim()
        .replace(/đ/gi, 'dj')
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .toUpperCase()
        .replace(/[\s-]+/g, '_')
        .replace(/[^A-Z0-9_]/g, ''),
    )
    .filter((t) => t.length > 0);
  return [...new Set(tags)];
}
