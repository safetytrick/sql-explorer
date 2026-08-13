/**
 * Scoring a table's indexes against what a query constrains.
 *
 * The question the SQL page has to answer at a glance is "is this statement
 * hitting a useful index?". An index only helps if the query constrains its
 * *leading* column — MySQL reads a composite index left to right, so an index
 * on `(a, b)` does nothing for a query that only filters on `b`. That
 * distinction is the whole point of this module: sharing a column with an
 * index is not the same as being able to use it.
 *
 * Select-list columns are deliberately excluded from "constrains": they cannot
 * drive index selection. Joins, criteria and group-by can.
 */
import type {ColumnRef} from '../refs';
import {parseColumnRef} from '../refs';
import type {TableIndex, TableStructure} from '../api-types';

export interface ScoredIndex {
  index: TableIndex;
  /** Index columns the query constrains. */
  matched: string[];
  /** True when the index's first column is constrained, so it can be used. */
  leading: boolean;
}

export interface TableIndexPanel {
  schema: string;
  table: string;
  /** Columns of this table the query constrains, lower-cased. */
  queryColumns: string[];
  indexes: ScoredIndex[];
  /** The query constrains this table, but no index starts with one of those columns. */
  noLeadingMatch: boolean;
}

/**
 * Build one panel per resolved table, indexes ordered most-useful first.
 *
 * PRIMARY is hidden unless the query actually constrains it — the same rule the
 * old table-indices panel applied (it skipped PRIMARY outright), now relevance
 * aware so a query that *does* use the primary key still sees it.
 */
export function buildIndexPanels(structures: TableStructure[],
                                 constrainingColumns: ColumnRef[],
                                 defaultSchema: string): TableIndexPanel[] {
  const parsed = constrainingColumns.map(reference => parseColumnRef(reference));

  return structures.map(structure => {
    const key = `${structure.schema}.${structure.name}`.toLowerCase();
    const queryColumns = [...new Set(parsed
      .filter(column => `${column.schema ?? defaultSchema}.${column.table}`.toLowerCase() === key)
      .map(column => column.column.toLowerCase()))];

    // the metadata loader can return the same index twice (app_other.groups comes
    // back with two PRIMARY rows); rendering it twice is noise, and duplicate
    // names are also a tracking hazard in the template
    const byName = new Map((structure.indices || []).map(index => [index.name, index]));
    const indexes = [...byName.values()]
      .map(index => scoreIndex(index, queryColumns))
      .filter(scored => scored.index.name !== 'PRIMARY' || scored.matched.length > 0)
      .sort(byUsefulness);

    return {
      schema: structure.schema,
      table: structure.name,
      queryColumns,
      indexes,
      noLeadingMatch: queryColumns.length > 0 && !indexes.some(entry => entry.leading)
    };
  });
}

function scoreIndex(index: TableIndex, queryColumns: string[]): ScoredIndex {
  const columns = index.columns || [];
  return {
    index,
    matched: columns.filter(column => queryColumns.includes(column.toLowerCase())),
    leading: columns.length > 0 && queryColumns.includes(columns[0].toLowerCase())
  };
}

/** Usable before partial, then wider coverage, then unique, then by name. */
function byUsefulness(left: ScoredIndex, right: ScoredIndex): number {
  return Number(right.leading) - Number(left.leading)
    || right.matched.length - left.matched.length
    || Number(right.index.unique) - Number(left.index.unique)
    || left.index.name.localeCompare(right.index.name);
}
