import {compactCount, countBadge} from './count-badge';

describe('countBadge', () => {
  it('compacts the label and puts the exact count and rate on hover', () => {
    expect(countBadge('err', 4885, 3121193)).toEqual({
      label: 'err 4.9k',
      title: '4,885 errors across 3,121,193 executions — 0.16% of executions'
    });
  });

  it('says per execution when a count exceeds the runs, as warnings can', () => {
    expect(countBadge('warn', 1583066, 400000).title).toContain('4.0 per execution');
    expect(countBadge('warn', 1, 1).title).toBe('1 warning across 1 execution — 1.0 per execution');
  });

  it('keeps a tiny rate honest rather than rounding it to zero', () => {
    expect(countBadge('err', 1, 1000000).title).toContain('under 0.01% of executions');
  });

  it('compacts by thousands, millions and billions', () => {
    expect(compactCount(999)).toBe('999');
    expect(compactCount(1000)).toBe('1k');
    expect(compactCount(1583066)).toBe('1.6M');
    expect(compactCount(250000)).toBe('250k');
    expect(compactCount(2100000000)).toBe('2.1B');
  });
});
