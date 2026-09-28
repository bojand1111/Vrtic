/** Pure calendar helpers on ISO dates ('YYYY-MM-DD') and months ('YYYY-MM'); UTC arithmetic, no timezone drift. */

function parse(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y ?? 1970, (m ?? 1) - 1, d ?? 1));
}

function format(date: Date): string {
  return date.toISOString().slice(0, 10);
}

export function addDays(iso: string, days: number): string {
  const d = parse(iso);
  d.setUTCDate(d.getUTCDate() + days);
  return format(d);
}

/** First and last day of a month. */
export function monthRange(month: string): { readonly from: string; readonly to: string } {
  const first = parse(`${month}-01`);
  const last = new Date(Date.UTC(first.getUTCFullYear(), first.getUTCMonth() + 1, 0));
  return { from: format(first), to: format(last) };
}

export function shiftMonth(month: string, delta: number): string {
  const d = parse(`${month}-01`);
  d.setUTCMonth(d.getUTCMonth() + delta);
  return format(d).slice(0, 7);
}

interface DatedEvent {
  readonly startsOn: string;
  readonly endsOn: string;
}

/**
 * Days of [from, to] that have events, each with the events touching that day (multi-day events
 * appear on every day they cover inside the range). Days are ascending, event order is kept.
 */
export function eventsByDay<T extends DatedEvent>(events: readonly T[], from: string, to: string): { readonly day: string; readonly events: readonly T[] }[] {
  const days = new Map<string, T[]>();
  for (const e of events) {
    let day = e.startsOn < from ? from : e.startsOn;
    const end = e.endsOn > to ? to : e.endsOn;
    while (day <= end) {
      const list = days.get(day) ?? [];
      list.push(e);
      days.set(day, list);
      day = addDays(day, 1);
    }
  }
  return [...days.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([day, list]) => ({ day, events: list }));
}

interface DatedClosure {
  readonly closureDate: string;
}

/**
 * Joins the event days with closure days (read-only, from /closure-days): every day that has events or a
 * closure, ascending; closures keep their order.
 */
export function withClosures<E, C extends DatedClosure>(
  days: readonly { readonly day: string; readonly events: readonly E[] }[],
  closures: readonly C[],
): { readonly day: string; readonly events: readonly E[]; readonly closures: readonly C[] }[] {
  const map = new Map<string, { events: readonly E[]; closures: C[] }>();
  for (const d of days) {
    map.set(d.day, { events: d.events, closures: [] });
  }
  for (const c of closures) {
    const entry = map.get(c.closureDate) ?? { events: [], closures: [] };
    entry.closures.push(c);
    map.set(c.closureDate, entry);
  }
  return [...map.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([day, v]) => ({ day, events: v.events, closures: v.closures }));
}
