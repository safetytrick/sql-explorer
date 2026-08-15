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
    expect(encodeTagFilter(emptyTagFilter())).toEqual({tags: null, tagMode: null});
  });

  it('round-trips a selection', () => {
    const params = encodeTagFilter({tags: ['pii', 'audit'], mode: 'ANY'});
    expect(params).toEqual({tags: 'pii,audit', tagMode: 'ANY'});
    expect(decodeTagFilter(reader(params as Record<string, string>)))
      .toEqual({tags: ['pii', 'audit'], mode: 'ANY'});
  });

  it('writes the mode whenever tags are set, so a link cannot be read as the workbench default', () => {
    expect(encodeTagFilter({tags: ['pii'], mode: 'ALL'}).tagMode).toBe('ALL');
  });

  it('drops the mode again once the selection is cleared', () => {
    expect(encodeTagFilter({tags: [], mode: 'ANY'}).tagMode).toBeNull();
  });

  it('ignores blank and padded entries in the csv', () => {
    expect(decodeTagFilter(reader({tags: 'pii, ,audit,'})).tags).toEqual(['pii', 'audit']);
  });

  it('falls back to the default for an unrecognised mode', () => {
    expect(decodeTagFilter(reader({tags: 'pii', tagMode: 'SOMETIMES'})).mode).toBe('ALL');
  });

  it('ALL requires every selected tag', () => {
    const state = {tags: ['pii', 'audit'], mode: 'ALL' as const};
    expect(matchesTagFilter(['pii', 'audit', 'legacy'], state)).toBeTrue();
    expect(matchesTagFilter(['pii'], state)).toBeFalse();
  });

  it('ANY requires only one', () => {
    const state = {tags: ['pii', 'audit'], mode: 'ANY' as const};
    expect(matchesTagFilter(['pii'], state)).toBeTrue();
    expect(matchesTagFilter(['legacy'], state)).toBeFalse();
  });

  it('matches everything when nothing is selected', () => {
    expect(matchesTagFilter([], emptyTagFilter())).toBeTrue();
  });
});
