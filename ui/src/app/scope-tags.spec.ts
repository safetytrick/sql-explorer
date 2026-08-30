import {decodeScopeTags, encodeScopeTags, isUnscoped, NO_SCOPE_TAGS, sameScopeTags} from './scope-tags';

function reader(params: Record<string, string>) {
  return {get: (key: string) => (key in params ? params[key] : null)};
}

/**
 * The vocabulary that says which part of the corpus you are working in.
 *
 * <p>Its own module because it used to be spelled in two places — `search-url`
 * pairing `schemaTagsExclude` with the scope params, `tag-filter-url` with the
 * row-filter ones. Exclusion agreed by luck; inclusion did not, so routing the
 * sidebar through the wrong codec wrote its include tags into `tags` and
 * silently asked a different question (sqlx-04q).
 */
describe('scope tags', () => {

  it('reads nothing from an untouched url', () => {
    expect(decodeScopeTags(reader({}))).toEqual(NO_SCOPE_TAGS);
  });

  it('keeps an unscoped view free of params', () => {
    expect(encodeScopeTags(NO_SCOPE_TAGS))
      .toEqual({schemaTags: null, schemaTagsExclude: null, schemaTagMode: null});
  });

  /** The bug in one assertion: an include is `schemaTags`, never `tags`. */
  it('writes an include to the scope parameter, not the row filter', () => {
    const params = encodeScopeTags({includeTags: ['core'], excludeTags: [], tagMode: 'ANY'});

    expect(params['schemaTags']).toBe('core');
    expect(Object.keys(params)).not.toContain('tags');
    expect(Object.keys(params)).not.toContain('tagMode');
  });

  it('round-trips both halves', () => {
    const scope = {includeTags: ['core', 'billing'], excludeTags: ['system'], tagMode: 'ALL' as const};
    const params = encodeScopeTags(scope);

    expect(decodeScopeTags(reader(params as Record<string, string>))).toEqual(scope);
  });

  /** The mode governs the include set, so it is meaningless without one. */
  it('omits the mode when only an exclusion is set', () => {
    const params = encodeScopeTags({includeTags: [], excludeTags: ['system'], tagMode: 'ALL'});

    expect(params['schemaTagsExclude']).toBe('system');
    expect(params['schemaTagMode']).toBeNull();
  });

  it('omits the mode at its default', () => {
    expect(encodeScopeTags({includeTags: ['core'], excludeTags: [], tagMode: 'ANY'})['schemaTagMode'])
      .toBeNull();
  });

  it('falls back to the default mode for anything unrecognised', () => {
    expect(decodeScopeTags(reader({schemaTags: 'core', schemaTagMode: 'SOMETIMES'})).tagMode).toBe('ANY');
  });

  it('ignores blank and padded csv entries', () => {
    expect(decodeScopeTags(reader({schemaTags: 'a, ,b,'})).includeTags).toEqual(['a', 'b']);
  });

  // ── equality, which several writers depend on to not fight ──

  it('recognises an identical scope', () => {
    const scope = {includeTags: ['a'], excludeTags: ['b'], tagMode: 'ALL' as const};

    expect(sameScopeTags(scope, {...scope})).toBeTrue();
  });

  it('separates a differing scope', () => {
    expect(sameScopeTags(
      {includeTags: ['a'], excludeTags: [], tagMode: 'ANY'},
      {includeTags: [], excludeTags: ['a'], tagMode: 'ANY'})).toBeFalse();
  });

  it('knows when there is nothing to resolve', () => {
    expect(isUnscoped(NO_SCOPE_TAGS)).toBeTrue();
    expect(isUnscoped({includeTags: [], excludeTags: ['system'], tagMode: 'ANY'})).toBeFalse();
  });
});
