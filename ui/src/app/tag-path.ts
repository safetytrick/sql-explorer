/**
 * Where a tag attaches. One owner for the three path shapes.
 *
 * <p>These were spelled inline in eight places — `'/schema/' + name`,
 * `'/schema/' + table.schema + '/table/' + table.name`, `'/query/' + digest`.
 * That was survivable while every write was one row the user could see fail.
 * Bulk tagging is not: a path spelled differently matches nothing, so the
 * server attaches a tag to an address no page will ever read, reports success,
 * and the rows the user selected stay untagged (sqlx-agl).
 *
 * <p>TARGETS, NOT PATHS, are what the API now takes. A path cannot express a
 * name containing `/` — MySQL permits one, and the string would neither parse
 * back nor be distinguishable from a different target — so the shapes below
 * survive only for links and for the legacy form the server still accepts
 * (sqlx-3ai). Build a target for anything that talks to the tag API.
 */

/**
 * What a tag points at, as the API states it.
 *
 * <p>Columns are a kind too (sqlx-n5j.1), and the environment itself is one for
 * annotations only (sqlx-n5j.13). `column` is optional so the shapes that
 * predate it still type-check; absent means empty.
 */
export interface TagTarget {
  type: 'schema' | 'table' | 'column' | 'query' | 'environment';
  schema: string;
  table: string;
  digest: string;
  column?: string;
}

export function schemaTarget(schema: string): TagTarget {
  return {type: 'schema', schema, table: '', digest: ''};
}

export function tableTarget(schema: string, table: string): TagTarget {
  return {type: 'table', schema, table, digest: ''};
}

export function columnTarget(schema: string, table: string, column: string): TagTarget {
  return {type: 'column', schema, table, digest: '', column};
}

export function queryTarget(digest: string): TagTarget {
  return {type: 'query', schema: '', table: '', digest};
}

/** The selected environment itself — an annotation target, never a tag's. */
export function environmentTarget(): TagTarget {
  return {type: 'environment', schema: '', table: '', digest: '', column: ''};
}

/**
 * A stable key for a target, for the client's own maps.
 *
 * <p>Not a path: it uses a separator that cannot appear in a MySQL identifier,
 * so two different targets can never produce the same key. A path-shaped key
 * is exactly what could not tell `x`.`a/b` from `x/table/a`.`b`.
 */
export function targetKey(target: TagTarget): string {
  return [target.type, target.schema, target.table, target.digest, target.column ?? ''].join('\u0000');
}
export function schemaPath(schema: string): string {
  return '/schema/' + schema;
}

export function tablePath(schema: string, table: string): string {
  return '/schema/' + schema + '/table/' + table;
}

/** Where a column's notes live: its table's path plus the column (sqlx-n5j.1). */
export function columnPath(schema: string, table: string, column: string): string {
  return tablePath(schema, table) + '/column/' + column;
}

export function queryPath(digest: string): string {
  return '/query/' + digest;
}
