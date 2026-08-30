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
 * <p>The server derives a tag's *type* from the segment before the last, so
 * the shapes are load-bearing on both sides: `/schema/{s}` is a schema,
 * `/schema/{s}/table/{t}` a table, `/query/{digest}` a query.
 */
export function schemaPath(schema: string): string {
  return '/schema/' + schema;
}

export function tablePath(schema: string, table: string): string {
  return '/schema/' + schema + '/table/' + table;
}

export function queryPath(digest: string): string {
  return '/query/' + digest;
}
