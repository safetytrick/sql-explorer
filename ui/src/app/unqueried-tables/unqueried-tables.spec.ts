import {SchemaTree} from '../schema-lens.service';
import {totals, unqueriedBySchema} from './unqueried-tables';

/** Minimal tree builder: [name, queryCount, inSchema, view?, archivedLastSeen?]. */
function tree(schemas: Record<string, [string, number, boolean, boolean?, string?][]>): SchemaTree {
  return {
    schemas: Object.entries(schemas).map(([name, tables]) => ({
      name,
      tables: tables.map(([table, queryCount, inSchema, view, archivedLastSeen]) => ({
        name: table, columnCount: 3, queryCount, warn: false, view: !!view, inSchema,
        archivedLastSeen: archivedLastSeen ?? null
      }))
    }))
  } as SchemaTree;
}

describe('unqueriedBySchema', () => {

  it('keeps only tables the schema describes and nothing queries', () => {
    const rows = unqueriedBySchema(tree({app: [['orders', 5, true], ['dead', 0, true]]}));

    expect(rows.map(r => r.schema)).toEqual(['app']);
    expect(rows[0].unqueried.map(t => t.name)).toEqual(['dead']);
  });

  /**
   * The mirror image of this question. A node without inSchema is a name some
   * statement referenced that introspection could not corroborate — counting
   * it here would put a table that does not exist on a list of tables nobody
   * uses. 162 of those existed in the corpus until sqlx-1mm.
   */
  it('never counts a table the schema does not describe', () => {
    const rows = unqueriedBySchema(tree({app: [['phantom_6a12420', 0, false]]}));

    expect(rows).toEqual([]);
  });

  it('leaves out schemas where everything is queried', () => {
    expect(unqueriedBySchema(tree({app: [['orders', 5, true]]}))).toEqual([]);
  });

  /**
   * The distinction the report turns on. A schema where every table is
   * unqueried is not a pile of dead tables — either nothing uses it or its
   * traffic was never captured — and presenting the two alike would invite
   * deleting a schema that is merely uninstrumented.
   */
  it('marks a schema with no traffic at all, separately from one with dead tables', () => {
    const rows = unqueriedBySchema(tree({
      quiet: [['a', 0, true], ['b', 0, true]],
      busy: [['live', 9, true], ['dead', 0, true]]
    }));

    expect(rows.find(r => r.schema === 'quiet')!.silent).toBeTrue();
    expect(rows.find(r => r.schema === 'busy')!.silent).toBeFalse();
  });

  it('says how many of the schema each count is out of', () => {
    const rows = unqueriedBySchema(tree({busy: [['live', 9, true], ['dead', 0, true]]}));

    expect(rows[0].described).toBe(2);
    expect(rows[0].unqueried.length).toBe(1);
  });

  /** A schema with thirty dead tables is a bigger finding than one with a single. */
  it('orders schemas by how much is unqueried', () => {
    const rows = unqueriedBySchema(tree({
      one: [['a', 0, true], ['b', 1, true]],
      many: [['a', 0, true], ['b', 0, true], ['c', 0, true], ['d', 1, true]]
    }));

    expect(rows.map(r => r.schema)).toEqual(['many', 'one']);
  });

  it('flags MySQL-owned schemas so they can be segregated', () => {
    const rows = unqueriedBySchema(tree({mysql: [['help_topic', 0, true]], app: [['dead', 0, true]]}));

    expect(rows.find(r => r.schema === 'mysql')!.system).toBeTrue();
    expect(rows.find(r => r.schema === 'app')!.system).toBeFalse();
  });

  it('reads an absent tree as nothing rather than throwing', () => {
    expect(unqueriedBySchema(null)).toEqual([]);
  });
});

describe('totals', () => {

  it('counts views apart from tables, because a view nobody reads is a different call', () => {
    const rows = unqueriedBySchema(tree({
      app: [['dead_table', 0, true], ['dead_view', 0, true, true]]
    }));

    expect(totals(rows)).toEqual({schemas: 1, tables: 1, views: 1, dormant: 0, silent: 1});
  });
});

/**
 * A reset clears Performance Schema, so a table used by a monthly job is
 * absent from a corpus captured a week later — identical, in the corpus alone,
 * to a table nothing has ever touched. An archived capture can tell them apart,
 * and when it does the difference has to reach the reader (sqlx-qwn).
 */
describe('tables the archive remembers', () => {

  it('keeps a table an earlier capture saw off the deletion list', () => {
    const rows = unqueriedBySchema(tree({
      app: [['live', 9, true], ['dead', 0, true], ['monthly', 0, true, false, '2026-06-01T02:00:00']]
    }));

    expect(rows[0].unqueried.map(t => t.name)).toEqual(['dead']);
    expect(rows[0].dormant.map(t => t.name)).toEqual(['monthly']);
  });

  it('carries the date, which is the whole of the evidence', () => {
    const rows = unqueriedBySchema(tree({app: [['monthly', 0, true, false, '2026-06-01T02:00:00']]}));

    expect(rows[0].dormant[0].lastSeen).toBe('2026-06-01T02:00:00');
  });

  /**
   * THE COUNT, not just the row. A header still reading "3 unqueried tables"
   * while one of them is listed as dormant below would be the same wrong claim
   * in a place people read first.
   */
  it('does not count a dormant table as a deletion candidate', () => {
    const rows = unqueriedBySchema(tree({
      app: [['dead', 0, true], ['monthly', 0, true, false, '2026-06-01T02:00:00']]
    }));

    expect(totals(rows).tables).toBe(1);
    expect(totals(rows).dormant).toBe(1);
  });

  /**
   * A schema whose quiet tables were all used before the reset is not silent —
   * the archive has just shown the traffic that made it look that way. Calling
   * it silent invites exactly the mistake the silent flag exists to prevent.
   */
  it('a schema the archive can account for is not marked silent', () => {
    const rows = unqueriedBySchema(tree({
      quiet: [['a', 0, true, false, '2026-06-01T02:00:00'], ['b', 0, true, false, '2026-06-02T02:00:00']]
    }));

    expect(rows[0].silent).toBeFalse();
    expect(rows[0].unqueried).toEqual([]);
  });

  /** Still reported, or the evidence would be collected and then hidden. */
  it('shows a schema whose only quiet tables are dormant', () => {
    const rows = unqueriedBySchema(tree({app: [['monthly', 0, true, false, '2026-06-01T02:00:00']]}));

    expect(rows.map(r => r.schema)).toEqual(['app']);
  });

  /** Most recently used first: those are the likeliest to run again. */
  it('orders the dormant by when they were last used', () => {
    const rows = unqueriedBySchema(tree({app: [
      ['older', 0, true, false, '2026-01-01T00:00:00'],
      ['newer', 0, true, false, '2026-06-01T00:00:00']
    ]}));

    expect(rows[0].dormant.map(t => t.name)).toEqual(['newer', 'older']);
  });

  /**
   * The ordinary case, and the one that must not regress: before any reset
   * there is no earlier run, every node carries a null, and the report is
   * exactly what it was.
   */
  it('with no archive at all the report is unchanged', () => {
    const rows = unqueriedBySchema(tree({app: [['live', 9, true], ['dead', 0, true]]}));

    expect(rows[0].unqueried.map(t => t.name)).toEqual(['dead']);
    expect(rows[0].dormant).toEqual([]);
    expect(totals(rows).dormant).toBe(0);
  });
});
