/**
 * Minimal QR Code encoder (ISO/IEC 18004) for the TOTP provisioning URI: byte mode, error
 * correction level M, versions 1..15 (up to 412 bytes), automatic mask selection. No dependency;
 * the output is a boolean matrix (true = dark) rendered by QrCode.tsx as SVG.
 */

const MAX_VERSION = 15;

/** Error correction codewords per block, level M, index = version (0 unused). */
const ECC_PER_BLOCK_M = [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24] as const;
/** Number of error correction blocks, level M, index = version (0 unused). */
const BLOCKS_M = [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10] as const;
/** Format information level indicator for M (L = 1, M = 0, Q = 3, H = 2). */
const FORMAT_BITS_M = 0;

function at<T>(values: readonly T[], index: number): T {
  const value = values[index];
  if (value === undefined) {
    throw new RangeError(`index ${String(index)} out of range`);
  }
  return value;
}

/** Number of data + ECC bits available in a symbol of this version (all non-function modules). */
export function rawDataModules(version: number): number {
  let result = (16 * version + 128) * version + 64;
  if (version >= 2) {
    const numAlign = Math.floor(version / 7) + 2;
    result -= (25 * numAlign - 10) * numAlign - 55;
    if (version >= 7) {
      result -= 36;
    }
  }
  return result;
}

/** Data codewords (bytes) available at level M. */
export function dataCodewords(version: number): number {
  return Math.floor(rawDataModules(version) / 8) - at(ECC_PER_BLOCK_M, version) * at(BLOCKS_M, version);
}

export function alignmentPositions(version: number): number[] {
  if (version === 1) {
    return [];
  }
  const numAlign = Math.floor(version / 7) + 2;
  const size = version * 4 + 17;
  const step = Math.ceil((version * 4 + 4) / (numAlign * 2 - 2)) * 2;
  const result = [6];
  for (let pos = size - 7; result.length < numAlign; pos -= step) {
    result.splice(1, 0, pos);
  }
  return result;
}

function gfMultiply(x: number, y: number): number {
  let z = 0;
  for (let i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d);
    z ^= ((y >>> i) & 1) * x;
  }
  return z;
}

/** Generator polynomial coefficients (highest degree first, leading 1 omitted). */
export function reedSolomonDivisor(degree: number): number[] {
  const result: number[] = new Array<number>(degree - 1).fill(0);
  result.push(1);
  let root = 1;
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < result.length; j++) {
      result[j] = gfMultiply(at(result, j), root);
      if (j + 1 < result.length) {
        result[j] = at(result, j) ^ at(result, j + 1);
      }
    }
    root = gfMultiply(root, 0x02);
  }
  return result;
}

export function reedSolomonRemainder(data: readonly number[], divisor: readonly number[]): number[] {
  const result: number[] = divisor.map(() => 0);
  for (const b of data) {
    const factor = b ^ (result.shift() ?? 0);
    result.push(0);
    divisor.forEach((coef, i) => {
      result[i] = at(result, i) ^ gfMultiply(coef, factor);
    });
  }
  return result;
}

function getBit(value: number, index: number): boolean {
  return ((value >>> index) & 1) !== 0;
}

/** 15-bit format information (level M) for a mask, already XOR-ed with 0x5412. */
export function formatBits(mask: number): number {
  const data = (FORMAT_BITS_M << 3) | mask;
  let rem = data;
  for (let i = 0; i < 10; i++) {
    rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
  }
  return ((data << 10) | rem) ^ 0x5412;
}

/** 18-bit version information (versions 7+). */
export function versionBits(version: number): number {
  let rem = version;
  for (let i = 0; i < 12; i++) {
    rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
  }
  return (version << 12) | rem;
}

function encodeData(bytes: readonly number[], version: number): number[] {
  const bits: number[] = [];
  const append = (value: number, length: number) => {
    for (let i = length - 1; i >= 0; i--) {
      bits.push((value >>> i) & 1);
    }
  };
  append(0b0100, 4);
  append(bytes.length, version <= 9 ? 8 : 16);
  for (const b of bytes) {
    append(b, 8);
  }
  const capacityBits = dataCodewords(version) * 8;
  append(0, Math.min(4, capacityBits - bits.length));
  append(0, (8 - (bits.length % 8)) % 8);
  const codewords: number[] = [];
  for (let i = 0; i < bits.length; i += 8) {
    let byte = 0;
    for (let j = 0; j < 8; j++) {
      byte = (byte << 1) | at(bits, i + j);
    }
    codewords.push(byte);
  }
  for (let pad = 0xec; codewords.length < dataCodewords(version); pad ^= 0xec ^ 0x11) {
    codewords.push(pad);
  }
  return codewords;
}

function addEccAndInterleave(data: readonly number[], version: number): number[] {
  const numBlocks = at(BLOCKS_M, version);
  const blockEccLen = at(ECC_PER_BLOCK_M, version);
  const rawCodewords = Math.floor(rawDataModules(version) / 8);
  const numShortBlocks = numBlocks - (rawCodewords % numBlocks);
  const shortBlockLen = Math.floor(rawCodewords / numBlocks);
  const divisor = reedSolomonDivisor(blockEccLen);
  const blocks: number[][] = [];
  for (let i = 0, k = 0; i < numBlocks; i++) {
    const dat = data.slice(k, k + shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1));
    k += dat.length;
    const ecc = reedSolomonRemainder(dat, divisor);
    if (i < numShortBlocks) {
      dat.push(0);
    }
    blocks.push(dat.concat(ecc));
  }
  const result: number[] = [];
  const blockLength = at(blocks, 0).length;
  for (let i = 0; i < blockLength; i++) {
    blocks.forEach((block, j) => {
      if (i !== shortBlockLen - blockEccLen || j >= numShortBlocks) {
        result.push(at(block, i));
      }
    });
  }
  return result;
}

class Symbol {
  readonly size: number;
  readonly modules: boolean[][];
  readonly isFunction: boolean[][];

  constructor(readonly version: number) {
    this.size = version * 4 + 17;
    this.modules = Array.from({ length: this.size }, () => new Array<boolean>(this.size).fill(false));
    this.isFunction = Array.from({ length: this.size }, () => new Array<boolean>(this.size).fill(false));
  }

  get(x: number, y: number): boolean {
    return at(at(this.modules, y), x);
  }

  set(x: number, y: number, dark: boolean): void {
    at(this.modules, y)[x] = dark;
  }

  setFunction(x: number, y: number, dark: boolean): void {
    this.set(x, y, dark);
    at(this.isFunction, y)[x] = true;
  }

  drawFunctionPatterns(): void {
    for (let i = 0; i < this.size; i++) {
      this.setFunction(6, i, i % 2 === 0);
      this.setFunction(i, 6, i % 2 === 0);
    }
    this.drawFinder(3, 3);
    this.drawFinder(this.size - 4, 3);
    this.drawFinder(3, this.size - 4);
    const positions = alignmentPositions(this.version);
    const last = positions.length - 1;
    positions.forEach((px, i) => {
      positions.forEach((py, j) => {
        if (!((i === 0 && j === 0) || (i === 0 && j === last) || (i === last && j === 0))) {
          this.drawAlignment(px, py);
        }
      });
    });
    this.drawFormat(0);
    this.drawVersion();
  }

  private drawFinder(x: number, y: number): void {
    for (let dy = -4; dy <= 4; dy++) {
      for (let dx = -4; dx <= 4; dx++) {
        const dist = Math.max(Math.abs(dx), Math.abs(dy));
        const xx = x + dx;
        const yy = y + dy;
        if (xx >= 0 && xx < this.size && yy >= 0 && yy < this.size) {
          this.setFunction(xx, yy, dist !== 2 && dist !== 4);
        }
      }
    }
  }

  private drawAlignment(x: number, y: number): void {
    for (let dy = -2; dy <= 2; dy++) {
      for (let dx = -2; dx <= 2; dx++) {
        this.setFunction(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
      }
    }
  }

  drawFormat(mask: number): void {
    const bits = formatBits(mask);
    for (let i = 0; i <= 5; i++) {
      this.setFunction(8, i, getBit(bits, i));
    }
    this.setFunction(8, 7, getBit(bits, 6));
    this.setFunction(8, 8, getBit(bits, 7));
    this.setFunction(7, 8, getBit(bits, 8));
    for (let i = 9; i < 15; i++) {
      this.setFunction(14 - i, 8, getBit(bits, i));
    }
    for (let i = 0; i < 8; i++) {
      this.setFunction(this.size - 1 - i, 8, getBit(bits, i));
    }
    for (let i = 8; i < 15; i++) {
      this.setFunction(8, this.size - 15 + i, getBit(bits, i));
    }
    this.setFunction(8, this.size - 8, true);
  }

  private drawVersion(): void {
    if (this.version < 7) {
      return;
    }
    const bits = versionBits(this.version);
    for (let i = 0; i < 18; i++) {
      const bit = getBit(bits, i);
      const a = this.size - 11 + (i % 3);
      const b = Math.floor(i / 3);
      this.setFunction(a, b, bit);
      this.setFunction(b, a, bit);
    }
  }

  drawCodewords(data: readonly number[]): void {
    let i = 0;
    for (let right = this.size - 1; right >= 1; right -= 2) {
      if (right === 6) {
        right = 5;
      }
      for (let vert = 0; vert < this.size; vert++) {
        for (let j = 0; j < 2; j++) {
          const x = right - j;
          const upward = ((right + 1) & 2) === 0;
          const y = upward ? this.size - 1 - vert : vert;
          if (!at(at(this.isFunction, y), x) && i < data.length * 8) {
            this.set(x, y, getBit(at(data, i >>> 3), 7 - (i & 7)));
            i++;
          }
        }
      }
    }
  }

  applyMask(mask: number): void {
    for (let y = 0; y < this.size; y++) {
      for (let x = 0; x < this.size; x++) {
        if (!at(at(this.isFunction, y), x) && maskHit(mask, x, y)) {
          this.set(x, y, !this.get(x, y));
        }
      }
    }
  }

  /** Simplified ISO penalty (runs, 2x2 blocks, finder-like patterns, dark balance). */
  penalty(): number {
    let score = 0;
    const lines: string[] = [];
    for (let y = 0; y < this.size; y++) {
      let row = '';
      let col = '';
      for (let x = 0; x < this.size; x++) {
        row += this.get(x, y) ? '1' : '0';
        col += this.get(y, x) ? '1' : '0';
      }
      lines.push(row, col);
    }
    for (const line of lines) {
      for (const run of line.match(/0{5,}|1{5,}/g) ?? []) {
        score += 3 + run.length - 5;
      }
      const padded = `0000${line}0000`;
      for (let i = 0; i + 11 <= padded.length; i++) {
        const window = padded.slice(i, i + 11);
        if (window === '10111010000' || window === '00001011101') {
          score += 40;
        }
      }
    }
    let dark = 0;
    for (let y = 0; y < this.size; y++) {
      for (let x = 0; x < this.size; x++) {
        const c = this.get(x, y);
        if (c) {
          dark++;
        }
        if (x < this.size - 1 && y < this.size - 1 && c === this.get(x + 1, y) && c === this.get(x, y + 1) && c === this.get(x + 1, y + 1)) {
          score += 3;
        }
      }
    }
    const total = this.size * this.size;
    score += Math.floor(Math.abs(dark * 20 - total * 10) / total) * 10;
    return score;
  }
}

function maskHit(mask: number, x: number, y: number): boolean {
  switch (mask) {
    case 0:
      return (x + y) % 2 === 0;
    case 1:
      return y % 2 === 0;
    case 2:
      return x % 3 === 0;
    case 3:
      return (x + y) % 3 === 0;
    case 4:
      return (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0;
    case 5:
      return ((x * y) % 2) + ((x * y) % 3) === 0;
    case 6:
      return (((x * y) % 2) + ((x * y) % 3)) % 2 === 0;
    default:
      return (((x + y) % 2) + ((x * y) % 3)) % 2 === 0;
  }
}

export interface QrMatrix {
  readonly version: number;
  readonly mask: number;
  readonly size: number;
  /** modules[y][x], true = dark. */
  readonly modules: readonly (readonly boolean[])[];
}

/** Encodes UTF-8 text; throws RangeError when it does not fit into version 15-M. */
export function encodeQr(text: string): QrMatrix {
  const bytes = Array.from(new TextEncoder().encode(text));
  let version = 1;
  while (version <= MAX_VERSION && 4 + (version <= 9 ? 8 : 16) + bytes.length * 8 > dataCodewords(version) * 8) {
    version++;
  }
  if (version > MAX_VERSION) {
    throw new RangeError('text too long for a QR code');
  }
  const codewords = addEccAndInterleave(encodeData(bytes, version), version);
  let best: { mask: number; symbol: Symbol } | null = null;
  let bestScore = Number.POSITIVE_INFINITY;
  for (let mask = 0; mask < 8; mask++) {
    const symbol = new Symbol(version);
    symbol.drawFunctionPatterns();
    symbol.drawCodewords(codewords);
    symbol.applyMask(mask);
    symbol.drawFormat(mask);
    const score = symbol.penalty();
    if (score < bestScore) {
      bestScore = score;
      best = { mask, symbol };
    }
  }
  if (best === null) {
    throw new Error('no mask evaluated');
  }
  return { version, mask: best.mask, size: best.symbol.size, modules: best.symbol.modules };
}
