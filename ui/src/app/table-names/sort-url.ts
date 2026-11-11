import {TableRollup} from '../api-types';

/**
 * The `/tables` sort column, as `var-sort` (sqlx-n5j.10).
 *
 * `time` — total query time, the server's order — is the default and is
 * omitted, so an untouched page keeps a clean URL. `var-` because the order is
 * this page's own presentation, not a filter another page would understand.
 */
export const SORT_PARAM = 'var-sort';

export type TableSort = 'time' | 'writes';

const VALUES: readonly TableSort[] = ['time', 'writes'];

/** Anything unrecognised falls back to the default order rather than an empty or odd one. */
export function readTableSort(raw: string | null | undefined): TableSort {
  return VALUES.includes(raw as TableSort) ? raw as TableSort : 'time';
}

export function encodeTableSort(sort: TableSort): string | null {
  return sort === 'time' ? null : sort;
}

/**
 * Rows in the chosen order. `time` keeps the order they arrived in — the
 * server already ranks by time, with ignored schemas last — so it is the
 * identity. `writes` is descending writes per day, ties by time.
 */
export function sortTables(tables: TableRollup[], sort: TableSort): TableRollup[] {
  if (sort === 'time') { return tables; }
  return [...tables].sort((a, b) =>
    (b.writesPerDay ?? 0) - (a.writesPerDay ?? 0) || b.totalQueryTime - a.totalQueryTime);
}
