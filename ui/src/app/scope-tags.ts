import {TagMode} from './query-search.service';

/**
 * The one place the scope-tag vocabulary is spelled.
 *
 * <p>`schemaTags`, `schemaTagsExclude` and `schemaTagMode` say **which part of
 * the corpus you are working in** — the schemas and tables in play. That is a
 * different question from `tags`/`tagMode`, which ask "of the rows this page
 * lists, which carry this tag", and the two must not be confused: the first
 * travels across navigations (`scope-url.ts`), the second is page-local.
 *
 * <p>This module exists because they *were* confused. Two codecs both spelled
 * `schemaTagsExclude` — `search-url.ts` pairing it with the scope params,
 * `tag-filter-url.ts` with the row-filter ones — so routing the schema-tree
 * sidebar through the second wrote its include tags into `tags`. Exclusion
 * survived because both spellings agreed on it; inclusion silently became a
 * different question (sqlx-04q).
 */
export interface ScopeTags {
  includeTags: string[];
  excludeTags: string[];
  tagMode: TagMode;
}

/** Minimal read surface of Angular's ParamMap, so specs need no router. */
export interface ParamReader {
  get(name: string): string | null;
}

export const NO_SCOPE_TAGS: ScopeTags = {includeTags: [], excludeTags: [], tagMode: 'ANY'};

/** The scope's default mode. ANY — one matching tag is enough to be in scope. */
export const DEFAULT_SCOPE_TAG_MODE: TagMode = 'ANY';

function csv(raw: string | null): string[] {
  return raw ? raw.split(',').map(tag => tag.trim()).filter(Boolean) : [];
}

export function decodeScopeTags(params: ParamReader): ScopeTags {
  return {
    includeTags: csv(params.get('schemaTags')),
    excludeTags: csv(params.get('schemaTagsExclude')),
    tagMode: params.get('schemaTagMode') === 'ALL' ? 'ALL' : DEFAULT_SCOPE_TAG_MODE
  };
}

/**
 * Query params for a scope. `null` clears a param, which is what
 * `router.navigate` wants for keys that should leave the URL; defaults are
 * omitted so an unscoped view keeps a clean URL.
 */
export function encodeScopeTags(scope: ScopeTags): Record<string, string | null> {
  return {
    schemaTags: scope.includeTags.length ? scope.includeTags.join(',') : null,
    schemaTagsExclude: scope.excludeTags.length ? scope.excludeTags.join(',') : null,
    // the mode governs the include set only, so it is meaningless without one
    schemaTagMode: scope.includeTags.length && scope.tagMode !== DEFAULT_SCOPE_TAG_MODE
      ? scope.tagMode : null
  };
}

/**
 * Value equality.
 *
 * <p>Several components write this vocabulary, and each has to recognise the
 * echo of its own write coming back through the URL or they bounce off one
 * another.
 */
export function sameScopeTags(a: ScopeTags, b: ScopeTags): boolean {
  const same = (x: string[], y: string[]) => x.length === y.length && x.every((v, i) => v === y[i]);
  return a.tagMode === b.tagMode && same(a.includeTags, b.includeTags) && same(a.excludeTags, b.excludeTags);
}

/** True when nothing is scoped, so callers can skip resolution work entirely. */
export function isUnscoped(scope: ScopeTags): boolean {
  return !scope.includeTags.length && !scope.excludeTags.length;
}

/**
 * The tag algebra, once.
 *
 * <p>Exclusion first and unconditionally: a tag that puts an object outside the
 * working set does so whatever else it carries. Then inclusion, which is a
 * no-op when nothing is included.
 *
 * <p>Lives here rather than beside either caller because two questions share
 * it. This module asks "is this object in the working set"; `tag-filter-url`
 * asks "of the rows this page lists, which carry this tag". Different
 * questions, different URL vocabulary — but one set algebra, and writing it
 * twice is how the two spellings of `schemaTagsExclude` diverged in the first
 * place (sqlx-04q).
 *
 * @param inheritedTags tags of whatever contains the row — a table's schema.
 *                      Exclusion reaches a row through its container because it
 *                      is a statement about objects; inclusion is about the row
 *                      itself and ignores them.
 */
export function matchesTags(rowTags: string[], includeTags: string[], excludeTags: string[],
                            mode: TagMode, inheritedTags: string[] = []): boolean {
  if (excludeTags.some(tag => rowTags.includes(tag) || inheritedTags.includes(tag))) {
    return false;
  }
  if (!includeTags.length) {
    return true;
  }
  return mode === 'ALL'
    ? includeTags.every(tag => rowTags.includes(tag))
    : includeTags.some(tag => rowTags.includes(tag));
}

/** Whether an object carrying these tags is inside the scope. */
export function matchesScopeTags(rowTags: string[], scope: ScopeTags,
                                 inheritedTags: string[] = []): boolean {
  return matchesTags(rowTags, scope.includeTags, scope.excludeTags, scope.tagMode, inheritedTags);
}
