import { readTypeFilter, TYPE_PARAM } from './type-filter-url';

describe('type-filter-url', () => {

  it('round-trips the three real values', () => {
    expect(readTypeFilter('all')).toBe('all');
    expect(readTypeFilter('tables')).toBe('tables');
    expect(readTypeFilter('views')).toBe('views');
  });

  /**
   * An absent param is the untouched page, which shows everything. `all` is
   * also the value the writer omits, so this is the same case twice.
   */
  it('defaults to all when the param is absent', () => {
    expect(readTypeFilter(null)).toBe('all');
    expect(readTypeFilter(undefined)).toBe('all');
  });

  /**
   * Falls back rather than filtering everything out: an empty page reads as
   * "this schema has no tables", which is a worse lie than ignoring a typo.
   */
  it('falls back to all for anything unrecognised', () => {
    expect(readTypeFilter('')).toBe('all');
    expect(readTypeFilter('VIEWS')).toBe('all');
    expect(readTypeFilter('view')).toBe('all');
    expect(readTypeFilter('nonsense')).toBe('all');
  });

  /**
   * The prefix is load-bearing. `tables` unprefixed is already the shared
   * search vocabulary's table-scope param and means a list of table keys.
   */
  it('is namespaced so it cannot collide with the shared search vocabulary', () => {
    expect(TYPE_PARAM).toBe('var-type');
    expect(TYPE_PARAM.startsWith('var-')).toBeTrue();
  });
});
