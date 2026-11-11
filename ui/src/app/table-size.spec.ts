import {formatBytes, formatRowsEstimate} from './table-size';

describe('table-size', () => {
  it('formats bytes in binary units', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(1023)).toBe('1023 B');
    expect(formatBytes(163840)).toBe('160 KiB');
    expect(formatBytes(1536)).toBe('1.5 KiB');
    expect(formatBytes(5 * 1024 ** 3)).toBe('5.0 GiB');
    expect(formatBytes(null)).toBe('');
  });

  it('marks a row estimate as one', () => {
    expect(formatRowsEstimate(1583066)).toBe('≈1.6M');
    expect(formatRowsEstimate(12)).toBe('≈12');
    expect(formatRowsEstimate(undefined)).toBe('');
  });
});
