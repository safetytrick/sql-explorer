import {mergeVariants, repeatCounts, repeatSpread, perfDigestsOf} from './merge-group';
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

describe('repeatSpread', () => {

  const blocks = (...counts: number[]) => counts.map((count, i) => ({start: i, end: i + 1, count}));

  /** The common case: nothing merged, nothing to warn about. */
  it('says nothing for a profile that was never merged', () => {
    expect(repeatSpread(null, null)).toBeNull();
    expect(repeatSpread(blocks(3), [])).toBeNull();
  });

  it('says nothing when every member repeats the same amount', () => {
    expect(repeatSpread(blocks(3), [child('d1', 10, 1, [3]), child('d2', 10, 1, [3])])).toBeNull();
  });

  it('reports the range when members differ', () => {
    expect(repeatSpread(blocks(2), [child('d1', 10, 1, [10]), child('d2', 10, 1, [5])]))
      .toEqual({min: 2, max: 10});
  });

  /**
   * A member with no repeat block repeated once. Within a group every member
   * shares the normalized text, so an absent block means one occurrence — not
   * an absent construct, and not a member to skip. Skipping it would have
   * hidden the widest real spread in the corpus, where the representative is
   * the single-element case and children run to 57.
   */
  it('counts a member with no repeat block as one', () => {
    expect(repeatSpread(null, [child('d1', 10, 1, [57])])).toEqual({min: 1, max: 57});
  });

  /**
   * Summarised over each member's largest block, which is the one driving the
   * cost — not over every block, which would compare positions that vary
   * together.
   */
  it('summarises each member by its largest block', () => {
    expect(repeatSpread(blocks(2, 2, 2), [child('d1', 10, 1, [10, 10, 10])]))
      .toEqual({min: 2, max: 10});
  });
});

describe('perfDigestsOf', () => {

  /**
   * The bug this exists to stop. `profile.digest` is overwritten with the
   * normalized digest when the profile completes, so reading it as the MySQL
   * digest shows the normalized one under the wrong label — and beside the
   * "app norm" box, the same string twice.
   */
  it('reads the MySQL digest off the metrics, never the profile field', () => {
    expect(perfDigestsOf({
      perfDigests: null,
      runtimeMetrics: {digest: 'f2b9be5f', digestList: ['f2b9be5f']} as never
    })).toEqual(['f2b9be5f']);
  });

  /**
   * perfDigests only reaches the wire for merged profiles — on the generated
   * Avro class it is a Manifold extension rather than a bean getter, so Jackson
   * sees it only on MergedSqlProfile. When it is there it wins, because a merge
   * group has several and the metrics carry one.
   */
  it('prefers the explicit list when the profile is merged', () => {
    expect(perfDigestsOf({
      perfDigests: ['a', 'b', 'c'],
      runtimeMetrics: {digest: 'a', digestList: ['a']} as never
    })).toEqual(['a', 'b', 'c']);
  });

  it('falls back to the metrics when the list is absent or empty', () => {
    expect(perfDigestsOf({perfDigests: [], runtimeMetrics: {digest: 'only'} as never})).toEqual(['only']);
    expect(perfDigestsOf({runtimeMetrics: {digest: 'only'} as never})).toEqual(['only']);
  });

  /** No metrics means no digest to show, not a blank string in a box. */
  it('reports none rather than an empty entry', () => {
    expect(perfDigestsOf({perfDigests: null, runtimeMetrics: null})).toEqual([]);
    expect(perfDigestsOf(null)).toEqual([]);
  });
});
