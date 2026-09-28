import { describe, expect, it } from 'vitest';

import { passwordProblem } from '../auth/accountApi';
import { ageRange } from '../groups/types';
import { canRevokeMembership, invitableRoles, type Membership } from './types';

const member = (patch: Partial<Membership>): Membership => ({
  id: 'm-1',
  userId: 'u-2',
  givenName: 'Ana',
  familyName: 'Test',
  email: 'ana@example.test',
  role: 'TEACHER',
  status: 'ACTIVE',
  permissions: [],
  childrenCount: 0,
  ...patch,
});

describe('invitableRoles (mirror of PermissionMatrix.assignableRoles)', () => {
  it('never offers OWNER and limits ADMIN to TEACHER/PARENT', () => {
    expect(invitableRoles('OWNER')).toEqual(['ADMIN', 'TEACHER', 'PARENT']);
    expect(invitableRoles('ADMIN')).toEqual(['TEACHER', 'PARENT']);
    expect(invitableRoles('TEACHER')).toEqual([]);
    expect(invitableRoles('PARENT')).toEqual([]);
    expect(invitableRoles(null)).toEqual([]);
  });
});

describe('canRevokeMembership', () => {
  it('hides revoke for owners, oneself and revoked memberships', () => {
    expect(canRevokeMembership(member({}), 'u-1')).toBe(true);
    expect(canRevokeMembership(member({ role: 'OWNER' }), 'u-1')).toBe(false);
    expect(canRevokeMembership(member({ userId: 'u-1' }), 'u-1')).toBe(false);
    expect(canRevokeMembership(member({ status: 'REVOKED' }), 'u-1')).toBe(false);
  });
});

describe('ageRange', () => {
  it('formats open and closed month ranges', () => {
    expect(ageRange(12, 36)).toBe('12–36');
    expect(ageRange(12, null)).toBe('12+');
    expect(ageRange(undefined, 36)).toBe('–36');
    expect(ageRange(null, undefined)).toBe('');
  });
});

describe('passwordProblem', () => {
  it('checks the minimum length before the confirmation', () => {
    expect(passwordProblem('short', 'short')).toBe('passwordTooShort');
    expect(passwordProblem('long-enough-password', 'other-password-1')).toBe('passwordMismatch');
    expect(passwordProblem('long-enough-password', 'long-enough-password')).toBeNull();
  });
});
