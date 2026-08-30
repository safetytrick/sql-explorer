import {SchemaTree} from '../schema-lens.service';

/** The MySQL-owned schemas. Segregated by default or they drown the answer. */
export const SYSTEM_SCHEMAS = new Set(
  ['sys', 'performance_schema', 'mysql', 'information_schema', 'percona']);

export interface UnqueriedTable {
  name: string;
  view: boolean;
  columnCount: number | null;
}

export interface SchemaUsage {
  schema: string;
  system: boolean;
  /** Tables the schema describes that no analyzed query touches. */
  unqueried: UnqueriedTable[];
  /** How many tables the schema describes in total. */
  described: number;
  /**
   * Every table in the schema is unqueried.
   *
   * <p>The distinction the whole report turns on. A schema where *some* tables
   * are unqueried has candidates for deletion in it. A schema where *all* of
   * them are is not making a statement about tables at all — either nothing
   * uses that schema, or the capture never saw its traffic. Presenting the two
   * as one list of "unused tables" would invite deleting a schema that is
   * simply not instrumented.
   */
  silent: boolean;
}

/**
 * Which tables the schema describes that nothing queries.
 *
 * <p>Reads `/schema-tree` rather than `/tables`, which cannot answer this: that
 * endpoint returns only tables appearing in queries — 308 of the corpus's 690,
 * none of them with a zero count — so the subject of this report is absent from
 * it entirely.
 *
 * <p>`inSchema` is required, not incidental. A node without it is a name some
 * statement referenced that introspection could not corroborate — the mirror
 * image of this question. Counting those here would put tables that do not
 * exist on a list of tables nobody uses.
 */
export function unqueriedBySchema(tree: SchemaTree | null): SchemaUsage[] {
  if (!tree) { return []; }
  return tree.schemas
    .map(schema => {
      const described = schema.tables.filter(table => table.inSchema);
      const unqueried = described
        .filter(table => !table.queryCount)
        .map(table => ({
          name: table.name,
          view: !!table.view,
          columnCount: table.columnCount ?? null
        }))
        .sort((a, b) => a.name.localeCompare(b.name));
      return {
        schema: schema.name,
        system: SYSTEM_SCHEMAS.has(schema.name.toLowerCase()),
        unqueried,
        described: described.length,
        silent: described.length > 0 && unqueried.length === described.length
      };
    })
    .filter(usage => usage.unqueried.length > 0)
    // most unqueried first; a schema with one dead table is a smaller finding
    // than one with thirty, and the ordering should say so
    .sort((a, b) => b.unqueried.length - a.unqueried.length
      || a.schema.localeCompare(b.schema));
}

/** Totals for the header, over whichever rows are being shown. */
export function totals(rows: SchemaUsage[]) {
  return {
    schemas: rows.length,
    tables: rows.reduce((n, r) => n + r.unqueried.filter(t => !t.view).length, 0),
    views: rows.reduce((n, r) => n + r.unqueried.filter(t => t.view).length, 0),
    silent: rows.filter(r => r.silent).length
  };
}
