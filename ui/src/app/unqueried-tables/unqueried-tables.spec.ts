import {SchemaTree} from '../schema-lens.service';
import {totals, unqueriedBySchema} from './unqueried-tables';

/** Minimal tree builder: [name, queryCount, inSchema, view?]. */
function tree(schemas: Record<string, [string, number, boolean, boolean?][]>): SchemaTree {
  return {
    schemas: Object.entries(schemas).map(([name, tables]) => ({
      name,
      tables: tables.map(([table, queryCount, inSchema, view]) => ({
        name: table, columnCount: 3, queryCount, warn: false, view: !!view, inSchema
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

    expect(totals(rows)).toEqual({schemas: 1, tables: 1, views: 1, silent: 1});
  });
});
