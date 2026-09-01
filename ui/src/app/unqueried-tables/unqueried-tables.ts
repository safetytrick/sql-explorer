import {SchemaTree} from '../schema-lens.service';

/**
 * The MySQL-owned schemas — a LABEL, not a filter.
 *
 * <p>This used to decide what the page showed, which did by name what a
 * `system` tag does by tag: two mechanisms for one intent, and the hardcoded
 * one could not be adjusted per deployment, could not cover a schema a
 * deployment considers operational when MySQL does not, and could not be shared
 * as a link. What is in view now comes from the scope (sqlx-g7z).
 *
 * <p>It stays for the badge, because "this one belongs to MySQL" is useful
 * information about a row whether or not the scope excluded it.
 */
export const SYSTEM_SCHEMAS = new Set(
  ['sys', 'performance_schema', 'mysql', 'information_schema', 'percona']);

export interface UnqueriedTable {
  name: string;
  view: boolean;
  columnCount: number | null;
}

/**
 * A table nothing queries now, that something queried before a reset.
 *
 * <p>Performance Schema is cleared on reset, so a table used only by a
 * quarterly job is absent from a corpus captured a week after a restart —
 * indistinguishable, in the corpus alone, from one nothing has ever touched.
 * Listing it as a deletion candidate would be presenting an artifact of the
 * capture as a fact about the schema. An earlier capture can say otherwise,
 * and when it does the table belongs in its own list with the date (sqlx-qwn).
 */
export interface DormantTable extends UnqueriedTable {
  /** When a query last touched it, from a capture the corpus no longer covers. */
  lastSeen: string;
}

export interface SchemaUsage {
  schema: string;
  system: boolean;
  /**
   * Tables the schema describes that no analyzed query touches, and that no
   * archived capture remembers either. These are the deletion candidates.
   */
  unqueried: UnqueriedTable[];
  /**
   * Tables nothing queries now, but that an earlier capture saw in use. Kept
   * out of `unqueried` deliberately: they are evidence of a slow cadence, not
   * of disuse, and counting them as candidates is the mistake this exists to
   * prevent.
   */
  dormant: DormantTable[];
  /** How many tables the schema describes in total. */
  described: number;
  /**
   * Every table in the schema is unqueried and none is dormant.
   *
   * <p>The distinction the whole report turns on. A schema where *some* tables
   * are unqueried has candidates for deletion in it. A schema where *all* of
   * them are is not making a statement about tables at all — either nothing
   * uses that schema, or the capture never saw its traffic. Presenting the two
   * as one list of "unused tables" would invite deleting a schema that is
   * simply not instrumented.
   *
   * <p>A schema whose quiet tables were used before a reset is NOT silent —
   * the archive has just shown the traffic that made it look that way.
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
      const quiet = described.filter(table => !table.queryCount);
      const shape = (table: typeof quiet[number]) => ({
        name: table.name,
        view: !!table.view,
        columnCount: table.columnCount ?? null
      });
      const unqueried = quiet.filter(table => !table.archivedLastSeen)
        .map(shape)
        .sort((a, b) => a.name.localeCompare(b.name));
      const dormant = quiet.filter(table => !!table.archivedLastSeen)
        .map(table => ({...shape(table), lastSeen: table.archivedLastSeen!}))
        // most recently used first: the ones likeliest to run again
        .sort((a, b) => b.lastSeen.localeCompare(a.lastSeen) || a.name.localeCompare(b.name));
      return {
        schema: schema.name,
        system: SYSTEM_SCHEMAS.has(schema.name.toLowerCase()),
        unqueried,
        dormant,
        described: described.length,
        silent: described.length > 0 && unqueried.length === described.length
      };
    })
    .filter(usage => usage.unqueried.length > 0 || usage.dormant.length > 0)
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
    dormant: rows.reduce((n, r) => n + r.dormant.length, 0),
    silent: rows.filter(r => r.silent).length
  };
}
