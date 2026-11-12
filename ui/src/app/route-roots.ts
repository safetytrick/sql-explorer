/**
 * The first path segment of every route in `app.routes.ts`.
 *
 * <p>An environment leads the path (`/prod/queries`, sqlx-e13.7), so telling
 * `/prod/queries` from `/queries` comes down to whether the first segment is
 * one of these. Kept as a plain list with no imports, because the environment
 * service needs it at startup and the route table imports half the app;
 * `app.routes.spec.ts` checks it against the routes so the two cannot drift.
 */
export const ROUTE_ROOTS: ReadonlySet<string> = new Set([
  'queries', 'unqueried', 'captured', 'settings', 'drift', 'collations', 'coverage', 'topology',
  'unused-indexes', 'graph', 'schemas', 'tables', 'tags', 'table', 'analyzed-sql', 'explain',
  'query-utilities', 'ddl-preflight', 'table-graph', 'admin', 'note',
]);
