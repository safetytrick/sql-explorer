/**
 * The table-name filter shared by the sidebar tree and `/tables`: the term
 * matches against "schema.table" as one string, so a term matching the
 * schema shows all its tables, and a leading-dot term (".users") anchors
 * past the schema part and matches table names only. Case-insensitive
 * substring.
 *
 * A term written `/like this/` is a regular expression instead (sqlx-c8w.6).
 * A term containing `*` is still read as a wildcard pattern, matched whole against
 * the table name or against "schema.table": `invoice_*` is every table whose
 * name starts with `invoice_`, `app.*_log` every log table in `app`. `_` is
 * literal, not SQL's single-character wildcard — table names are full of it.
 */
export function matchesTreeFilter(schema: string, table: string, needle: string): boolean {
  const term = needle.trim().toLowerCase();
  if (!term) {
    return true;
  }
  const qualified = `${schema}.${table}`.toLowerCase();
  const regex = asRegex(needle.trim());
  if (regex) {
    return regex.test(table) || regex.test(`${schema}.${table}`);
  }
  if (term.includes('*')) {
    const pattern = wildcard(term);
    return pattern.test(table.toLowerCase()) || pattern.test(qualified);
  }
  return qualified.includes(term);
}

/**
 * `/pattern/` is a regular expression (sqlx-c8w.6), case-insensitive, tried
 * against the table name and against "schema.table". An invalid pattern is
 * not an error to shout about mid-typing: it matches nothing until it parses.
 */
function asRegex(needle: string): RegExp | null {
  if (needle.length < 3 || !needle.startsWith('/') || !needle.endsWith('/')) { return null; }
  try {
    return new RegExp(needle.slice(1, -1), 'i');
  } catch {
    return /(?!)/;
  }
}

function wildcard(term: string): RegExp {
  const literal = term.split('*').map(part => part.replace(/[.+?^${}()|[\]\\]/g, '\\$&'));
  return new RegExp(`^${literal.join('.*')}$`);
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

/**
 * What a tag can act on. Include filters narrow schemas, so they only ever
 * resolve through `tagsBySchema`; exclusion also works at table level, so it
 * additionally consults `tablesByTag`. Passing only the schema map is
 * therefore the correct call for an include chip, not an oversight.
 */
export interface TagCarriers {
  /** schema name (lower) → tags on that schema */
  tagsBySchema: Map<string, string[]>;
  /** tag → the `schema.table` keys it covers; exclusion only */
  tablesByTag?: Map<string, string[]>;
}

/**
 * Tags the given carriers cannot act on, so they cannot change anything.
 *
 * A tag that matches neither a schema nor (for exclusion) a table is a typo
 * or a dead tag from a shared URL. Its chip has to say so: an inert chip that
 * looks active is indistinguishable from a filter that silently failed, which
 * is what "I excluded two tags and only the first one worked" turned out to
 * be.
 *
 * Nothing is inert while every carrier is still empty — that is the loading
 * state, and flagging there would mark every chip on first paint.
 */
export function inertTags(tags: string[], carriers: TagCarriers): Set<string> {
  const tablesByTag = carriers.tablesByTag ?? new Map<string, string[]>();
  if (!carriers.tagsBySchema.size && !tablesByTag.size) {
    return new Set();
  }
  const carriedBySchema = new Set<string>();
  for (const schemaTags of carriers.tagsBySchema.values()) {
    for (const tag of schemaTags) {
      carriedBySchema.add(tag);
    }
  }
  return new Set(tags.filter(tag =>
    !carriedBySchema.has(tag) && !(tablesByTag.get(tag)?.length)));
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
