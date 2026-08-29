import {decodeTagFilter, emptyTagFilter, encodeTagFilter, matchesTagFilter} from './tag-filter-url';

function reader(params: Record<string, string>) {
  return {get: (key: string) => (key in params ? params[key] : null)};
}

describe('tag filter url', () => {

  it('reads nothing from an untouched url', () => {
    const state = decodeTagFilter(reader({}));
    expect(state.tags).toEqual([]);
    expect(state.mode).toBe('ALL');
  });

  it('keeps an untouched view free of params', () => {
    expect(encodeTagFilter(emptyTagFilter())).toEqual({tags: null, tagsExclude: null, tagMode: null});
  });

  it('round-trips a selection', () => {
    const params = encodeTagFilter({tags: ['pii', 'audit'], excludeTags: [], mode: 'ANY'});
    expect(params).toEqual({tags: 'pii,audit', tagsExclude: null, tagMode: 'ANY'});
    expect(decodeTagFilter(reader(params as Record<string, string>)))
      .toEqual({tags: ['pii', 'audit'], excludeTags: [], mode: 'ANY'});
  });

  it('writes the mode whenever tags are set, so a link cannot be read as the workbench default', () => {
    expect(encodeTagFilter({tags: ['pii'], excludeTags: [], mode: 'ALL'}).tagMode).toBe('ALL');
  });

  it('drops the mode again once the selection is cleared', () => {
    expect(encodeTagFilter({tags: [], excludeTags: [], mode: 'ANY'}).tagMode).toBeNull();
  });

  it('ignores blank and padded entries in the csv', () => {
    expect(decodeTagFilter(reader({tags: 'pii, ,audit,'})).tags).toEqual(['pii', 'audit']);
  });

  it('falls back to the default for an unrecognised mode', () => {
    expect(decodeTagFilter(reader({tags: 'pii', tagMode: 'SOMETIMES'})).mode).toBe('ALL');
  });

  it('ALL requires every selected tag', () => {
    const state = {tags: ['pii', 'audit'], excludeTags: [], mode: 'ALL' as const};
    expect(matchesTagFilter(['pii', 'audit', 'legacy'], state)).toBeTrue();
    expect(matchesTagFilter(['pii'], state)).toBeFalse();
  });

  it('ANY requires only one', () => {
    const state = {tags: ['pii', 'audit'], excludeTags: [], mode: 'ANY' as const};
    expect(matchesTagFilter(['pii'], state)).toBeTrue();
    expect(matchesTagFilter(['legacy'], state)).toBeFalse();
  });

  it('matches everything when nothing is selected', () => {
    expect(matchesTagFilter([], emptyTagFilter())).toBeTrue();
  });

  // ── exclusion ──

  describe('exclusion', () => {

    it('reads nothing from an untouched url', () => {
      expect(decodeTagFilter(reader({})).excludeTags).toEqual([]);
    });

    it('round-trips, spelled the way the workbench spells it', () => {
      const params = encodeTagFilter({tags: [], excludeTags: ['system', 'clickhouse'], mode: 'ALL'});
      expect(params['tagsExclude']).toBe('system,clickhouse');
      expect(decodeTagFilter(reader(params as Record<string, string>)).excludeTags)
        .toEqual(['system', 'clickhouse']);
    });

    /** The whole point: excluding without including is the common case. */
    it('is usable on its own, with no include set and so no mode', () => {
      const params = encodeTagFilter({tags: [], excludeTags: ['system'], mode: 'ALL'});
      expect(params['tags']).toBeNull();
      expect(params['tagMode']).toBeNull();
      expect(params['tagsExclude']).toBe('system');
    });

    it('hides a row carrying an excluded tag', () => {
      const state = {tags: [], excludeTags: ['system'], mode: 'ANY' as const};
      expect(matchesTagFilter(['system'], state)).toBeFalse();
      expect(matchesTagFilter(['core'], state)).toBeTrue();
    });

    /**
     * Exclusion wins. A row that satisfies the include set and also carries an
     * excluded tag is out — the same rule the schema tree applies, so the two
     * filters cannot disagree about the same row.
     */
    it('beats an include that would otherwise keep the row', () => {
      const state = {tags: ['core'], excludeTags: ['legacy'], mode: 'ANY' as const};
      expect(matchesTagFilter(['core'], state)).toBeTrue();
      expect(matchesTagFilter(['core', 'legacy'], state)).toBeFalse();
    });

    it('leaves an untagged row alone', () => {
      expect(matchesTagFilter([], {tags: [], excludeTags: ['system'], mode: 'ALL'})).toBeTrue();
    });
  });
});
