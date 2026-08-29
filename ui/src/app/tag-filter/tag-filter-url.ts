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
   * Tags that disqualify a row outright, whatever else it carries.
   *
   * Exclusion is the operation that makes a list about your work rather than
   * about the database — a `system` tag over information_schema, mysql, sys
   * and performance_schema is not "hide these forever", it is "not while I am
   * looking for index opportunities". Which is why it lives on the filter
   * strip and not in a setting.
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
    excludeTags: csv(params.get('tagsExclude')),
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
    // `tagsExclude` mirrors the workbench's `schemaTagsExclude`: the same
    // `<thing>Exclude` shape, so exclusion reads the same wherever it appears
    tagsExclude: state.excludeTags.length ? state.excludeTags.join(',') : null,
    // mode governs the include set only, so it is pointless without one
    tagMode: state.tags.length ? state.mode : null
  };
}

/**
 * An excluded tag disqualifies the row outright; then ALL — the row must carry
 * every selected tag — or ANY — at least one. No selection matches everything,
 * so an untouched page is unfiltered.
 *
 * <p>Exclusion is checked first and wins, which is the same rule the schema
 * tree's own filter applies (`schema-tree/tree-filter.ts`). Deliberately the
 * same: two places in the app answering "does this row survive the tag filter"
 * differently would be worse than either answer.
 */
export function matchesTagFilter(rowTags: string[], state: TagFilterState): boolean {
  if (state.excludeTags.some(tag => rowTags.includes(tag))) {
    return false;
  }
  if (!state.tags.length) {
    return true;
  }
  return state.mode === 'ALL'
    ? state.tags.every(tag => rowTags.includes(tag))
    : state.tags.some(tag => rowTags.includes(tag));
}
