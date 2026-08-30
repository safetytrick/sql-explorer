import {TagMode} from '../query-search.service';

/**
 * URL vocabulary for the list-page tag filter strip.
 *
 * The param names are deliberately the workbench's — `tags` as a csv and
 * `tagMode` as ANY/ALL — so a tag filter reads the same wherever it appears
 * (see the URL rule in CLAUDE.md). This is its own small codec rather than
 * `search-url.ts` because that one encodes an entire workbench state; only
 * the tag vocabulary is shared, not the shape around it.
 *
 * One deliberate difference from the workbench: `tagMode` is written out
 * whenever tags are selected, even when it equals this page's default. The
 * workbench defaults to ANY and omits it; the strip defaults to ALL, so a bare
 * `?tags=a,b` would mean OR in one place and AND in the other. Being explicit
 * costs one param and removes the trap. An untouched view still has no params
 * at all, which is what "omit defaults" is protecting.
 */
export interface TagFilterState {
  /** Tags a row must carry, per {@link TagFilterState.mode}. */
  tags: string[];
  /**
   * Tags that put an object outside the working set, whatever else it carries.
   *
   * <p>Unlike {@link TagFilterState.tags} this is not a filter over this page's
   * rows — it is a statement about objects in the corpus, and it means the
   * same thing on every page. That is why it is spelled
   * `schemaTagsExclude`, the same parameter the workbench's scope tree writes:
   * "hide the system schemas" had two spellings and two implementations until
   * 2026-08-29 (sqlx-uxo).
   *
   * <p>A `system` tag over information_schema, mysql, sys and
   * performance_schema is not "hide these forever" — it is "not while I am
   * looking for index opportunities". Which is why it is a control and not a
   * setting.
   */
  excludeTags: string[];
  mode: TagMode;
}

export const DEFAULT_TAG_MODE: TagMode = 'ALL';

export function emptyTagFilter(): TagFilterState {
  return {tags: [], excludeTags: [], mode: DEFAULT_TAG_MODE};
}

export interface ParamReader {
  get(key: string): string | null;
}

export function decodeTagFilter(params: ParamReader): TagFilterState {
  const mode = params.get('tagMode') === 'ANY' ? 'ANY'
    : params.get('tagMode') === 'ALL' ? 'ALL'
      : DEFAULT_TAG_MODE;
  return {
    tags: csv(params.get('tags')),
    excludeTags: csv(params.get('schemaTagsExclude')),
    mode
  };
}

function csv(raw: string | null): string[] {
  return raw ? raw.split(',').map(tag => tag.trim()).filter(Boolean) : [];
}

/**
 * Query params for a state. `null` clears a param, which is what
 * `router.navigate` wants for keys that should leave the URL.
 */
export function encodeTagFilter(state: TagFilterState): Record<string, string | null> {
  return {
    tags: state.tags.length ? state.tags.join(',') : null,
    // The workbench's own scope parameter, not a mirror of it. One spelling
    // for one statement — see the note on `excludeTags`. The name undersells
    // it (it excludes tagged tables too, and reads oddly on a page listing
    // tables) and is kept anyway: renaming would break workbench links that
    // already carry it, and one imperfect name beats two good ones.
    schemaTagsExclude: state.excludeTags.length ? state.excludeTags.join(',') : null,
    // mode governs the include set only, so it is pointless without one
    tagMode: state.tags.length ? state.mode : null
  };
}

/**
 * Whether a row survives the strip.
 *
 * <p>The two halves deliberately look at different tag sets, because they are
 * different kinds of statement:
 *
 * <ul>
 *   <li><b>Exclusion is about objects</b>, so it also applies to what the row
 *       belongs to. A table in a schema tagged `system` is outside the working
 *       set even when the table itself carries no tag — which is exactly what
 *       the workbench's scope tree already does with the same parameter.
 *   <li><b>Inclusion is about the row</b>. "Tables tagged core" must not match
 *       every table in a schema tagged core; that would be a different and
 *       much less useful question.
 * </ul>
 *
 * <p>Exclusion is checked first and wins, matching
 * `schema-tree/tree-filter.ts`. Two places answering "does this row survive
 * the tag filter" differently would be worse than either answer.
 *
 * @param inheritedTags tags of the thing this row belongs to — a table's
 *                      schema. Empty for rows that belong to nothing.
 */
export function matchesTagFilter(rowTags: string[], state: TagFilterState,
                                 inheritedTags: string[] = []): boolean {
  if (state.excludeTags.some(tag => rowTags.includes(tag) || inheritedTags.includes(tag))) {
    return false;
  }
  if (!state.tags.length) {
    return true;
  }
  return state.mode === 'ALL'
    ? state.tags.every(tag => rowTags.includes(tag))
    : state.tags.some(tag => rowTags.includes(tag));
}

/**
 * Value equality for a filter state.
 *
 * <p>Shared because two components now write the same URL parameter — the
 * header strip and the schema-tree sidebar — and both have to recognise the
 * echo of their own write, or they bounce off each other (sqlx-zl1).
 */
export function sameTagFilter(a: TagFilterState, b: TagFilterState): boolean {
  const same = (x: string[], y: string[]) => x.length === y.length && x.every((v, i) => v === y[i]);
  return a.mode === b.mode && same(a.tags, b.tags) && same(a.excludeTags, b.excludeTags);
}
