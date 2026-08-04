/**
 * Sidebar tree filter: the term matches against "schema.table" as one
 * string, so a term matching the schema shows all its tables, and a
 * leading-dot term (".users") anchors past the schema part and matches
 * table names only. Case-insensitive substring.
 */
export function matchesTreeFilter(schema: string, table: string, needle: string): boolean {
  const term = needle.trim().toLowerCase();
  if (!term) {
    return true;
  }
  return `${schema}.${table}`.toLowerCase().includes(term);
}

export type TypeFilter = 'all' | 'tables' | 'views';

/**
 * The all/tables/views toggle. A node with no flag at all (a backend build
 * that predates the view field) counts as not-a-view — the visible symptom
 * of that state is "tables shows everything, views shows nothing", which
 * means the backend needs a rebuild, not that data is missing.
 */
export function matchesTypeFilter(filter: TypeFilter, isView: boolean | undefined): boolean {
  switch (filter) {
    case 'tables': return isView !== true;
    case 'views': return isView === true;
    default: return true;
  }
}
