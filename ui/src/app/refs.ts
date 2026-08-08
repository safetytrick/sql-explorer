/**
 * Table and column references as they actually arrive over the wire.
 *
 * The backend serializes Avro `TableReference`, `ColumnReference` and
 * `JoinColumns` to *strings* through global Jackson modules
 * (`model/TableReferenceSerde` and friends), so a profile's `tables` is
 * `["schema.table"]`, not a list of objects. The TypeScript types used to
 * claim objects, which compiled fine and then read `undefined` at runtime —
 * the table lens silently dropped every view source, and the analyzed-sql
 * page fetched `/metadata/table/<schema>/undefined`.
 *
 * These aliases exist so the types say what the server sends, and the parsers
 * exist so call sites stop splitting the strings by hand — several did, each
 * slicing from the left, which mis-slots anything with an unexpected number
 * of segments.
 */

/** `"schema.table"`, or a bare `"table"` when the schema is unknown. */
export type TableRef = string;

/** `"schema.table.column"`, or `"table.column"` when the schema is unknown. */
export type ColumnRef = string;

/** A join or mismatched-join pair: `[left, right]`. */
export type JoinPair = [ColumnRef, ColumnRef];

export interface ParsedTableRef {
  schema: string | null;
  table: string;
}

export interface ParsedColumnRef {
  schema: string | null;
  table: string | null;
  column: string;
}

/**
 * Split a table reference. Parsed from the **right**: the last segment is the
 * table and anything before it is the schema, so a name that itself contains
 * a dot lands in the schema rather than silently shifting the table out of
 * position (which is what left-to-right indexing did).
 */
export function parseTableRef(ref: TableRef): ParsedTableRef {
  const dot = ref.lastIndexOf('.');
  if (dot < 0) {
    return { schema: null, table: ref };
  }
  return { schema: ref.slice(0, dot) || null, table: ref.slice(dot + 1) };
}

/** Split a column reference, right to left: column, then table, then schema. */
export function parseColumnRef(ref: ColumnRef): ParsedColumnRef {
  const lastDot = ref.lastIndexOf('.');
  if (lastDot < 0) {
    return { schema: null, table: null, column: ref };
  }
  const column = ref.slice(lastDot + 1);
  const { schema, table } = parseTableRef(ref.slice(0, lastDot));
  return { schema, table, column };
}

/** The `schema.table` a column belongs to, or null when it carries no table. */
export function tableRefOf(ref: ColumnRef): TableRef | null {
  const dot = ref.lastIndexOf('.');
  return dot < 0 ? null : ref.slice(0, dot);
}
