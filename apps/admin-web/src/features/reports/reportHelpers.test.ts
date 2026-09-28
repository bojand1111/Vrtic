import { describe, expect, it } from 'vitest';

import { attendanceQuery, auditQuery, csvFileName, formatMetadata, monthStart, normalizeCode } from './reportHelpers';

describe('attendance report helpers', () => {
  it('builds the default range and query', () => {
    expect(monthStart('2026-09-28')).toBe('2026-09-01');
    expect(attendanceQuery('2026-09-01', '2026-09-28', '')).toBe('/reports/attendance-summary?from=2026-09-01&to=2026-09-28');
    expect(attendanceQuery('2026-09-01', '2026-09-28', 'g1')).toBe('/reports/attendance-summary?from=2026-09-01&to=2026-09-28&groupId=g1');
    expect(csvFileName('2026-09-01', '2026-09-28')).toBe('attendance-summary-2026-09-01-2026-09-28.csv');
  });
});

describe('audit log helpers', () => {
  const empty = { from: '', to: '', action: '', entityType: '', result: '' };

  it('sends only valid filters', () => {
    expect(auditQuery(empty, 50, null)).toBe('/audit-log?limit=50');
    expect(auditQuery({ ...empty, action: ' child_updated ', entityType: 'x', result: 'DENIED', from: '2026-09-01' }, 20, 'abc')).toBe(
      '/audit-log?from=2026-09-01&action=CHILD_UPDATED&result=DENIED&limit=20&cursor=abc',
    );
    expect(normalizeCode('report_exported')).toBe('REPORT_EXPORTED');
    expect(normalizeCode('1BAD')).toBe('');
  });

  it('formats allowlisted metadata', () => {
    expect(formatMetadata({ role: 'ADMIN', clientKind: 'WEB', sessionsRevoked: 2 })).toBe('clientKind: WEB, role: ADMIN, sessionsRevoked: 2');
    expect(formatMetadata({})).toBe('');
  });
});
