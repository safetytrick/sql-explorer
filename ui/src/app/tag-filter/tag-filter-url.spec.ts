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
    expect(encodeTagFilter(emptyTagFilter())).toEqual({tags: null, schemaTagsExclude: null, tagMode: null});
  });

  it('round-trips a selection', () => {
    const params = encodeTagFilter({tags: ['pii', 'audit'], excludeTags: [], mode: 'ANY'});
    expect(params).toEqual({tags: 'pii,audit', schemaTagsExclude: null, tagMode: 'ANY'});
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

    /** The point of sqlx-uxo: ONE spelling, the workbench's own. */
    it('writes the same parameter the workbench scope tree writes', () => {
      const params = encodeTagFilter({tags: [], excludeTags: ['system', 'clickhouse'], mode: 'ALL'});
      expect(params['schemaTagsExclude']).toBe('system,clickhouse');
      expect(params['tagsExclude']).withContext('the duplicate spelling is gone').toBeUndefined();
      expect(decodeTagFilter(reader(params as Record<string, string>)).excludeTags)
        .toEqual(['system', 'clickhouse']);
    });

    /** The whole point: excluding without including is the common case. */
    it('is usable on its own, with no include set and so no mode', () => {
      const params = encodeTagFilter({tags: [], excludeTags: ['system'], mode: 'ALL'});
      expect(params['tags']).toBeNull();
      expect(params['tagMode']).toBeNull();
      expect(params['schemaTagsExclude']).toBe('system');
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


    // ── exclusion reaches through what a row belongs to ──

    /**
     * The semantics /queries already applies to this parameter. A table in a
     * schema tagged `system` is outside the working set even when the table
     * itself carries no tag — otherwise one parameter would mean two things
     * depending on the page, which is the defect sqlx-uxo exists to remove.
     */
    it('excludes a row whose container carries the tag', () => {
      const state = {tags: [], excludeTags: ['system'], mode: 'ANY' as const};

      expect(matchesTagFilter([], state, ['system'])).toBeFalse();
      expect(matchesTagFilter([], state, ['core'])).toBeTrue();
    });

    /** Inclusion is about the row itself, or "tagged core" would match a whole schema. */
    it('does not let a row be included by what contains it', () => {
      const state = {tags: ['core'], excludeTags: [], mode: 'ANY' as const};

      expect(matchesTagFilter(['core'], state, [])).toBeTrue();
      expect(matchesTagFilter([], state, ['core'])).toBeFalse();
    });

    it('treats an absent container as no inherited tags', () => {
      expect(matchesTagFilter(['core'], {tags: [], excludeTags: ['system'], mode: 'ALL'})).toBeTrue();
    });

    it('leaves an untagged row alone', () => {
      expect(matchesTagFilter([], {tags: [], excludeTags: ['system'], mode: 'ALL'})).toBeTrue();
    });
  });
});
