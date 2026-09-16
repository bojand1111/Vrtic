import { describe, expect, it } from 'vitest';

import { ANONYMOUS_USER_SCOPE, NO_ORGANIZATION_SCOPE, qk } from './queryKeys';

describe('qk', () => {
  it('prefixes every key with the user and organization scope', () => {
    expect(qk('user-1', 'org-a', 'children', { page: 2 })).toEqual([
      'user',
      'user-1',
      'org',
      'org-a',
      'children',
      { page: 2 },
    ]);
  });

  it('produces different keys for different organizations of the same user', () => {
    const a = qk('user-1', 'org-a', 'children');
    const b = qk('user-1', 'org-b', 'children');
    expect(a).not.toEqual(b);
    expect(a[3]).toBe('org-a');
    expect(b[3]).toBe('org-b');
  });

  it('produces different keys for different users of the same organization', () => {
    expect(qk('user-1', 'org-a', 'children')).not.toEqual(qk('user-2', 'org-a', 'children'));
  });

  it('never lets a tenant-scoped key collide with a global one', () => {
    const global = qk(null, null, 'health', 'ready');
    expect(global).toEqual(['user', ANONYMOUS_USER_SCOPE, 'org', NO_ORGANIZATION_SCOPE, 'health', 'ready']);
    expect(global).not.toEqual(qk('user-1', null, 'health', 'ready'));
    expect(global).not.toEqual(qk(null, 'org-a', 'health', 'ready'));
  });

  it('keeps the same key for the same scope and parts (React Query hashing relies on structural equality)', () => {
    expect(qk('user-1', 'org-a', 'groups', 'g-1')).toEqual(qk('user-1', 'org-a', 'groups', 'g-1'));
  });
});
