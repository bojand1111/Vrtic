/** Pure helpers of the Reports screen (no React, no I/O). */

/** First day of the month of `today` ('YYYY-MM-DD'). */
export function monthStart(today: string): string {
  return `${today.slice(0, 7)}-01`;
}

/** Query string of GET /reports/attendance-summary (empty group = all groups). */
export function attendanceQuery(from: string, to: string, groupId: string): string {
  const params = new URLSearchParams({ from, to });
  if (groupId !== '') {
    params.set('groupId', groupId);
  }
  return `/reports/attendance-summary?${params.toString()}`;
}

/** File name offered for the CSV export. */
export function csvFileName(from: string, to: string): string {
  return `attendance-summary-${from}-${to}.csv`;
}

export interface AuditFilters {
  readonly from: string;
  readonly to: string;
  readonly action: string;
  readonly entityType: string;
  readonly result: string;
}

const CODE = /^[A-Z][A-Z0-9_]{2,63}$/;

/** Upper-cases and trims an action / entity type code; returns '' when it cannot be a valid code (filter ignored). */
export function normalizeCode(value: string): string {
  const code = value.trim().toUpperCase();
  return CODE.test(code) ? code : '';
}

/** Query string of GET /audit-log; only valid, non-empty filters are sent. */
export function auditQuery(filters: AuditFilters, limit: number, cursor: string | null): string {
  const params = new URLSearchParams();
  if (filters.from !== '') {
    params.set('from', filters.from);
  }
  if (filters.to !== '') {
    params.set('to', filters.to);
  }
  const action = normalizeCode(filters.action);
  if (action !== '') {
    params.set('action', action);
  }
  const entityType = normalizeCode(filters.entityType);
  if (entityType !== '') {
    params.set('entityType', entityType);
  }
  if (filters.result !== '') {
    params.set('result', filters.result);
  }
  params.set('limit', String(limit));
  if (cursor !== null) {
    params.set('cursor', cursor);
  }
  return `/audit-log?${params.toString()}`;
}

/** "key: value, key: value" of the allowlisted audit metadata (sorted by key). */
export function formatMetadata(metadata: Readonly<Record<string, unknown>>): string {
  return Object.keys(metadata)
    .sort()
    .map((k) => {
      const v = metadata[k];
      return `${k}: ${typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean' ? String(v) : ''}`;
    })
    .join(', ');
}
