import {mergeVariants, repeatCounts} from './merge-group';
import type {MergedChild} from '../query-search.service';

function child(digest: string, executed: number, averageTime: number, counts: number[]): MergedChild {
  return {
    digest,
    executed,
    averageTime,
    repeatedBlocks: counts.map((count, index) => ({start: index, end: index + 1, count}))
  };
}

describe('merge group', () => {

  it('folds members sharing a digest into one variant', () => {
    // the shape that makes a raw member count wrong: same statement, several
    // servers, one digest
    const variants = mergeVariants(
      [child('d1', 10, 2, [3]), child('d1', 30, 6, [3]), child('d2', 5, 1, [3])],
      [{start: 0, end: 1, count: 3}]);

    expect(variants.length).toBe(2);
    expect(variants[0].digest).toBe('d1');
    expect(variants[0].members).toBe(2);
    expect(variants[0].executed).toBe(40);
  });

  it('orders variants by executions, busiest first', () => {
    const variants = mergeVariants(
      [child('quiet', 1, 1, [2]), child('busy', 900, 1, [2])], null);

    expect(variants.map(variant => variant.digest)).toEqual(['busy', 'quiet']);
  });

  it('weights average time by executions rather than averaging averages', () => {
    // 10 runs at 1ms and 30 at 5ms is 4ms, not 3ms
    const variants = mergeVariants([child('d1', 10, 1, []), child('d1', 30, 5, [])], null);

    expect(variants[0].averageTime).toBe(4);
  });

  it('flags a variant whose repeat counts differ from the representative', () => {
    const variants = mergeVariants(
      [child('same', 1, 1, [3]), child('fewer', 1, 1, [2])],
      [{start: 0, end: 1, count: 3}]);

    expect(variants.find(variant => variant.digest === 'same')!.differs).toBe(false);
    expect(variants.find(variant => variant.digest === 'fewer')!.differs).toBe(true);
  });

  it('flags the digest when only one of its members differs', () => {
    const variants = mergeVariants(
      [child('d1', 1, 1, [3]), child('d1', 1, 1, [9])],
      [{start: 0, end: 1, count: 3}]);

    expect(variants[0].members).toBe(2);
    expect(variants[0].differs).toBe(true);
  });

  it('treats a different number of collapsed blocks as a difference', () => {
    const variants = mergeVariants([child('d1', 1, 1, [3, 3])], [{start: 0, end: 1, count: 3}]);

    expect(variants[0].differs).toBe(true);
  });

  it('returns nothing when a profile merged nothing', () => {
    expect(mergeVariants(null, null)).toEqual([]);
    expect(mergeVariants([], [{start: 0, end: 1, count: 2}])).toEqual([]);
  });

  it('reads counts off null and absent block lists', () => {
    expect(repeatCounts(null)).toEqual([]);
    expect(repeatCounts(undefined)).toEqual([]);
    expect(repeatCounts([{start: 0, end: 1, count: 7}])).toEqual([7]);
  });
});
