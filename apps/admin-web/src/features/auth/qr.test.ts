import { describe, expect, it } from 'vitest';

import { alignmentPositions, dataCodewords, encodeQr, formatBits, reedSolomonDivisor, reedSolomonRemainder, versionBits } from './qr';
import { formatSecret, normalizeOtp } from './mfaApi';

const URI =
  'otpauth://totp/Vrtic%20Connect:vlasnik%40happykids.example.test?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=Vrtic%20Connect&algorithm=SHA1&digits=6&period=30';

describe('QR encoder', () => {
  it('computes Reed-Solomon ECC like the ISO example (1-M "HELLO WORLD")', () => {
    const data = [32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17];
    expect(reedSolomonRemainder(data, reedSolomonDivisor(10))).toEqual([196, 35, 39, 119, 235, 215, 231, 226, 93, 23]);
  });

  it('uses the standard format and version information words', () => {
    expect(formatBits(0)).toBe(0b101010000010010);
    expect(formatBits(5)).toBe(0b100000011001110);
    expect(versionBits(7)).toBe(0x07c94);
    expect(versionBits(10)).toBe(0x0a4d3);
  });

  it('places alignment patterns at the ISO positions', () => {
    expect(alignmentPositions(1)).toEqual([]);
    expect(alignmentPositions(2)).toEqual([6, 18]);
    expect(alignmentPositions(7)).toEqual([6, 22, 38]);
    expect(alignmentPositions(9)).toEqual([6, 26, 46]);
    expect(alignmentPositions(14)).toEqual([6, 26, 46, 66]);
    expect(alignmentPositions(15)).toEqual([6, 26, 48, 70]);
  });

  it('matches the level M data capacity table', () => {
    expect([1, 2, 7, 8, 10, 15].map(dataCodewords)).toEqual([16, 28, 124, 154, 216, 415]);
  });

  it('encodes an otpauth URI with finder, timing, dark module and format bits in place', () => {
    const qr = encodeQr(URI);
    expect(qr.version).toBe(9); // 160 bytes: more than 8-M (152), fits 9-M (180)
    expect(qr.size).toBe(53);
    const m = (x: number, y: number) => qr.modules[y]?.[x] ?? false;
    for (const [cx, cy] of [
      [3, 3],
      [qr.size - 4, 3],
      [3, qr.size - 4],
    ] as const) {
      expect(m(cx, cy)).toBe(true);
      expect(m(cx - 2, cy)).toBe(false);
      expect(m(cx - 3, cy)).toBe(true);
    }
    for (let i = 8; i < qr.size - 8; i++) {
      expect(m(i, 6)).toBe(i % 2 === 0);
      expect(m(6, i)).toBe(i % 2 === 0);
    }
    expect(m(8, qr.size - 8)).toBe(true);
    let format = 0;
    for (let i = 0; i <= 5; i++) {
      format |= (m(8, i) ? 1 : 0) << i;
    }
    format |= (m(8, 7) ? 1 : 0) << 6;
    format |= (m(8, 8) ? 1 : 0) << 7;
    format |= (m(7, 8) ? 1 : 0) << 8;
    for (let i = 9; i < 15; i++) {
      format |= (m(14 - i, 8) ? 1 : 0) << i;
    }
    expect(format).toBe(formatBits(qr.mask));
  });

  it('refuses text that does not fit', () => {
    expect(() => encodeQr('x'.repeat(500))).toThrow(RangeError);
  });
});

describe('MFA input helpers', () => {
  it('groups the manual-entry secret in blocks of four', () => {
    expect(formatSecret('JBSWY3DPEHPK3PXP')).toBe('JBSW Y3DP EHPK 3PXP');
  });

  it('normalizes typed codes', () => {
    expect(normalizeOtp(' 123 456 ')).toBe('123456');
    expect(normalizeOtp('abcd-efgh-jkmn-pqrs')).toBe('ABCD-EFGH-JKMN-PQRS');
  });
});
