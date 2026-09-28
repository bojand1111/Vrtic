import { describe, expect, it } from 'vitest';

import { isKnownTitleKey, targetPath, titleArguments } from './notificationHelpers';

describe('notification helpers', () => {
  it('recognises only the title keys the backend produces', () => {
    expect(isKnownTitleKey('message.received')).toBe(true);
    expect(isKnownTitleKey('absence.reported')).toBe(true);
    expect(isKnownTitleKey('security.newLogin')).toBe(false);
  });

  it('formats date arguments and stringifies the rest', () => {
    const args = titleArguments({ childName: 'Iva', dateFrom: '2026-09-28', dateTo: '2026-09-30', count: 2 }, (iso) => `D(${iso})`);
    expect(args).toEqual({ childName: 'Iva', dateFrom: 'D(2026-09-28)', dateTo: 'D(2026-09-30)', count: '2' });
  });

  it('maps referenced entities to screens', () => {
    expect(targetPath({ refEntityType: 'CONVERSATION', refEntityId: 'a b' })).toBe('/messages?conversation=a%20b');
    expect(targetPath({ refEntityType: 'ANNOUNCEMENT', refEntityId: 'x' })).toBe('/announcements');
    expect(targetPath({ refEntityType: 'ABSENCE', refEntityId: 'x' })).toBe('/absences');
    expect(targetPath({ refEntityType: 'CHILD', refEntityId: 'x' })).toBe('/children');
    expect(targetPath({ refEntityType: null, refEntityId: null })).toBeNull();
  });
});
