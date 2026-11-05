/**
 * Aggregates: a root table and the tables that belong to it, in the DDD sense
 * (sqlx-aoy.12). They are a level of the graph's grid, between a schema and
 * its tables, so `invoice_items` is drawn inside `invoice`'s block.
 *
 * <p>A table is a member when <em>both</em> hold:
 * <ul>
 *   <li>its name extends the root's: `invoice_items` and `invoice_item_tax`
 *       extend `invoice`, and also `invoices`, whose singular is `invoice`;</li>
 *   <li>the two are related, by a foreign key or by a join seen in the
 *       queries.</li>
 * </ul>
 * A name match alone is never enough: `account_settings` next to `account`
 * means nothing until something relates them. Joins count as well as foreign
 * keys because large tables often drop their foreign keys for operational
 * reasons, and production may run with foreign key checks off, while the
 * relationship is still there in every query that uses it.
 *
 * <p>Matches nest, and an aggregate is the whole tree: `invoice_item_tax`
 * belongs to `invoice_item`, which belongs to `invoice`, so all three are in
 * `invoice`'s aggregate. Each table's parent is the most specific related
 * root its name extends.
 *
 * <p>Inference is a starting point. Overrides set a table's parent directly,
 * which moves it and everything under it; `null` makes it a root of its own.
 * Aggregates never cross schemas.
 */

export interface AggregateTable {
  schema: string;
  table: string;
}

/** A foreign key or a join between two tables. Direction does not matter here. */
export interface Relation {
  a: AggregateTable;
  b: AggregateTable;
}

/** A table's parent: another table in the same schema, or null for "a root of its own". */
export type AggregateOverrides = Map<string, string | null>;

export interface Aggregate {
  schema: string;
  /** The root table's name; also the aggregate's key within its schema. */
  root: string;
  /** Every table in the aggregate, root first, the rest by name. */
  tables: string[];
}

/** `schema.table`, lower-cased: how tables are matched and how overrides are keyed. */
export function tableKey(schema: string, table: string): string {
  return `${schema}.${table}`.toLowerCase();
}

/**
 * The singular of a table name's last word, or null when it is not plural:
 * `invoices` → `invoice`, `categories` → `category`, `addresses` → `address`.
 * Deliberately simple. A wrong singular can only miss a match, never make one,
 * because a relation is still required.
 */
export function singular(name: string): string | null {
  const lower = name.toLowerCase();
  if (lower.endsWith('ies') && lower.length > 3) { return lower.slice(0, -3) + 'y'; }
  if (/(s|x|z|ch|sh)es$/.test(lower)) { return lower.slice(0, -2); }
  if (lower.endsWith('s') && !lower.endsWith('ss') && lower.length > 1) { return lower.slice(0, -1); }
  return null;
}

/** The prefixes a member of {@code root} starts with: `invoices` gives `invoices_` and `invoice_`. */
function stems(root: string): string[] {
  const lower = root.toLowerCase();
  const one = singular(lower);
  return one && one !== lower ? [lower + '_', one + '_'] : [lower + '_'];
}

/**
 * The inferred parent of each table that has one, keyed by {@link tableKey}
 * and naming the parent table. Tables with no parent are absent.
 */
export function inferParents(tables: AggregateTable[], relations: Relation[]): Map<string, string> {
  const related = new Set<string>();
  for (const relation of relations) {
    const a = tableKey(relation.a.schema, relation.a.table);
    const b = tableKey(relation.b.schema, relation.b.table);
    if (a === b) { continue; }
    related.add(`${a}|${b}`);
    related.add(`${b}|${a}`);
  }
  const bySchema = new Map<string, AggregateTable[]>();
  for (const table of tables) {
    const schema = table.schema.toLowerCase();
    bySchema.set(schema, [...(bySchema.get(schema) ?? []), table]);
  }
  const parents = new Map<string, string>();
  for (const table of tables) {
    const name = table.table.toLowerCase();
    const self = tableKey(table.schema, table.table);
    let best: { name: string; length: number } | null = null;
    for (const candidate of bySchema.get(table.schema.toLowerCase()) ?? []) {
      const candidateName = candidate.table.toLowerCase();
      if (candidateName === name) { continue; }
      if (!related.has(`${self}|${tableKey(candidate.schema, candidate.table)}`)) { continue; }
      for (const stem of stems(candidateName)) {
        // the longest matching stem is the most specific root
        if (name.startsWith(stem) && name.length > stem.length && (!best || stem.length > best.length)) {
          best = { name: candidate.table, length: stem.length };
        }
      }
    }
    if (best) { parents.set(self, best.name); }
  }
  return parents;
}

/**
 * Group a schema's tables into aggregates, inferred and then overridden.
 * Every table lands in exactly one aggregate; a table nothing belongs to is
 * an aggregate of one. Ordered by schema, then root.
 */
export function buildAggregates(tables: AggregateTable[], relations: Relation[],
                                overrides: AggregateOverrides = new Map()): Aggregate[] {
  const inferred = inferParents(tables, relations);
  const byKey = new Map(tables.map(table => [tableKey(table.schema, table.table), table]));
  const parentOf = (key: string): string | null => {
    const table = byKey.get(key)!;
    const parent = overrides.has(key) ? overrides.get(key) ?? null : inferred.get(key) ?? null;
    if (parent === null) { return null; }
    const parentKey = tableKey(table.schema, parent);
    // a parent that is not in this graph (filtered out, or another schema's) is no parent
    return byKey.has(parentKey) && parentKey !== key ? parentKey : null;
  };

  const rootOf = new Map<string, string>();
  for (const key of byKey.keys()) {
    // Walk up to the root. An override can close a loop; the table where the
    // walk meets itself again becomes the root, so the outcome does not depend
    // on which member of the loop was visited first: the smallest key wins.
    const path: string[] = [];
    let at: string | null = key;
    while (at !== null && !path.includes(at)) {
      path.push(at);
      at = parentOf(at);
    }
    let root = path[path.length - 1];
    if (at !== null) {
      const loop = path.slice(path.indexOf(at));
      root = [...loop].sort()[0];
    }
    rootOf.set(key, root);
  }

  const groups = new Map<string, string[]>();
  for (const [key, root] of rootOf) {
    groups.set(root, [...(groups.get(root) ?? []), key]);
  }
  return [...groups.entries()]
    .map(([root, members]) => {
      const rootTable = byKey.get(root)!;
      const others = members.filter(key => key !== root)
        .map(key => byKey.get(key)!.table)
        .sort((a, b) => a.localeCompare(b));
      return { schema: rootTable.schema, root: rootTable.table, tables: [rootTable.table, ...others] };
    })
    .sort((a, b) => a.schema.localeCompare(b.schema) || a.root.localeCompare(b.root));
}
