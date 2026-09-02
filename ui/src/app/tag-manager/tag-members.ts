import {SchemaRollup, SqlProfile, Tag, TableRollup} from '../api-types';
import {queryTarget, type TagTarget} from '../tag-path';

/**
 * A single thing carrying a tag, flattened across the three member types so
 * the detail pane can list them in one table.
 *
 * `assocId` is the id of the tag *association*, not of the tag definition —
 * `DELETE /tag/:id` detaches by association, so a member without one cannot
 * be detached and the UI has to say so rather than silently doing nothing.
 */
export interface TagMember {
  kind: 'schema' | 'table' | 'query';
  /** What to show: `app_other`, `app_other.users`, or a query digest. */
  label: string;
  /** Router path for the member itself. */
  link: string[];
  /** Tag association id, or null when it could not be resolved. */
  assocId: number | null;
  numberOfQueries: number | null;
  totalQueryTime: number | null;
}

function assocOf(tags: Tag[] | null | undefined, tagName: string): number | null {
  const match = (tags ?? []).find(tag => tag.name === tagName);
  return match ? match.id : null;
}

export function schemaMembers(schemas: SchemaRollup[], tagName: string): TagMember[] {
  return schemas.map(schema => ({
    kind: 'schema' as const,
    label: schema.name,
    link: ['/tables', schema.name],
    assocId: assocOf(schema.tags, tagName),
    numberOfQueries: schema.numberOfQueries,
    totalQueryTime: schema.totalQueryTime
  }));
}

export function tableMembers(tables: TableRollup[], tagName: string): TagMember[] {
  return tables.map(table => ({
    kind: 'table' as const,
    label: `${table.schema}.${table.name}`,
    link: ['/table', table.schema, table.name],
    assocId: assocOf(table.tags, tagName),
    numberOfQueries: table.numberOfQueries,
    totalQueryTime: table.totalQueryTime
  }));
}

/**
 * Queries are the awkward case: `SqlProfile` carries no `tags`, so the
 * association id has to be looked up separately. `byIndex` is the response of
 * `getTagsForTargets`, which answers one entry per target in the order sent —
 * so it is zipped against `queries` positionally (sqlx-3ai).
 */
export function queryMembers(queries: SqlProfile[], tagName: string,
                             byIndex: Tag[][]): TagMember[] {
  return queries.map((query, index) => ({
      kind: 'query' as const,
      label: query.digest,
      link: ['/analyzed-sql', query.digest],
      assocId: assocOf(byIndex[index], tagName),
      numberOfQueries: query.runtimeMetrics ? query.runtimeMetrics.executed : null,
      totalQueryTime: query.runtimeMetrics ? query.runtimeMetrics.totalTime : null
  }));
}

/** What the SQL view tags a query against. */
export function queryTargetOf(query: SqlProfile): TagTarget {
  return queryTarget(query.digest);
}

/** Schemas first, then tables, then queries; heaviest first inside each. */
export function orderMembers(members: TagMember[]): TagMember[] {
  const rank = {schema: 0, table: 1, query: 2};
  return [...members].sort((left, right) =>
    rank[left.kind] - rank[right.kind] || (right.totalQueryTime ?? 0) - (left.totalQueryTime ?? 0));
}
