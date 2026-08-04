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

export interface TagFilterState {
  includeTags: string[];
  excludeTags: string[];
  tagMode: 'ANY' | 'ALL';
}

/**
 * Schema-level tag filtering: an excluded tag hides the schema outright; when
 * include tags are set the schema must carry any (ANY) or all (ALL) of them.
 */
export function matchesTagFilter(schemaTags: string[], filter: TagFilterState): boolean {
  if (filter.excludeTags.some(tag => schemaTags.includes(tag))) {
    return false;
  }
  if (!filter.includeTags.length) {
    return true;
  }
  return filter.tagMode === 'ALL'
    ? filter.includeTags.every(tag => schemaTags.includes(tag))
    : filter.includeTags.some(tag => schemaTags.includes(tag));
}

export interface TreeFilterContext {
  needle: string;
  typeFilter: TypeFilter;
  tagFilter: TagFilterState;
  /** lower-cased schema name → tag names on that schema */
  tagsBySchema: Map<string, string[]>;
}

interface FilterableTable { name: string; view?: boolean; }
interface FilterableSchema<T extends FilterableTable> { name: string; tables: T[]; }

/**
 * The sidebar's whole filter chain as one unit: schema-level tag
 * include/exclude, then the dot-anchored schema.table needle, then the
 * all/tables/views toggle; schemas with no surviving tables drop out.
 */
export function filterSchemaTree<T extends FilterableTable>(
  schemas: FilterableSchema<T>[], context: TreeFilterContext): FilterableSchema<T>[] {
  return schemas
    .filter(schema => matchesTagFilter(context.tagsBySchema.get(schema.name.toLowerCase()) ?? [], context.tagFilter))
    .map(schema => ({
      name: schema.name,
      tables: schema.tables
        .filter(table => matchesTreeFilter(schema.name, table.name, context.needle))
        .filter(table => matchesTypeFilter(context.typeFilter, table.view))
    }))
    .filter(schema => schema.tables.length > 0);
}

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
