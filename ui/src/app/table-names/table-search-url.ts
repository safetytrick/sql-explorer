/**
 * The URL spelling of the `/tables` search: a name pattern, the columns a
 * table must have, and the rows left out of a bulk tag (sqlx-v86).
 *
 * All `var-` prefixed, as `var-type` is: they are owned by this page.
 * `columns` and `tables` already belong to the shared search vocabulary and
 * mean something else there — column references a query constrains, and a
 * scope of table keys.
 */
export const NAME_PARAM = 'var-name';
export const COLUMNS_PARAM = 'var-columns';
export const EXCLUDE_PARAM = 'var-exclude';

/** Minimal read surface of Angular's ParamMap, so specs need no router. */
export interface ParamReader { get(name: string): string | null; }

export interface TableSearchState {
  /** Matched by `matchesTreeFilter`: a substring of schema.table, or a `*` pattern. */
  name: string;
  /** A table must have every one of these. Lower-cased and de-duplicated. */
  columns: string[];
  /** `schema.table` keys of rows unchecked, so a bulk tag passes them over. */
  exclude: string[];
}

/** Column names typed as a list: commas or whitespace between them. */
export function parseColumns(text: string): string[] {
  return [...new Set(text.split(/[\s,]+/).map(column => column.trim().toLowerCase()).filter(Boolean))];
}

function list(raw: string | null): string[] {
  return [...new Set((raw ?? '').split(',').map(value => value.trim()).filter(Boolean))];
}

export function decodeTableSearch(params: ParamReader): TableSearchState {
  return {
    name: params.get(NAME_PARAM) ?? '',
    columns: parseColumns(params.get(COLUMNS_PARAM) ?? ''),
    exclude: list(params.get(EXCLUDE_PARAM))
  };
}

/** A patch for a `queryParamsHandling: 'merge'` write: defaults become null, so they leave the URL. */
export function encodeTableSearch(state: TableSearchState): Record<string, string | null> {
  return {
    [NAME_PARAM]: state.name.trim() || null,
    [COLUMNS_PARAM]: state.columns.length ? state.columns.join(',') : null,
    [EXCLUDE_PARAM]: state.exclude.length ? state.exclude.join(',') : null
  };
}

/** The key a row is excluded by. */
export function tableKey(schema: string, name: string): string {
  return `${schema}.${name}`;
}
