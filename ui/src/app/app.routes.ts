/**
 * The application's route table, kept out of `main.ts` so it can be exercised
 * in a spec — route *ordering* is load-bearing here (see the column drill-down
 * below) and ordering is exactly the kind of thing a refactor breaks silently.
 */
import {inject} from '@angular/core';
import {RedirectFunction, Router, Routes} from '@angular/router';
import {tableKey} from './query-scope.service';
import {SchemaListComponent} from './schema-list/schema-list.component';
import {TableNamesComponent} from './table-names/table-names.component';
import {TablesWithColumnComponent} from './tables-with-column/tables-with-column.component';
import {TaggedTableViewComponent} from './tagged-table-view/tagged-table-view.component';
import {TaggedSchemaViewComponent} from './tagged-schema-view/tagged-schema-view.component';
import {SqlViewComponent} from './sql-view/sql-view.component';
import {TaggedSqlViewComponent} from './tagged-sql-view/tagged-sql-view.component';
import {ExplainViewComponent} from './explain-view/explain-view.component';
import {QueryUtilitiesComponent} from './query-utilities/query-utilities.component';
import {TableGraphLoaderComponent} from './table-graph-loader/table-graph-loader.component';
import {AdminSourceListComponent} from './admin-source-list/admin-source-list.component';
import {NoteViewComponent} from './note-view/note-view.component';
import {TableLensComponent} from './table-lens/table-lens.component';
import {QueriesWorkbenchComponent} from './queries-workbench/queries-workbench.component';
import {GraphLensComponent} from './graph-lens/graph-lens.component';
import {TagManagerComponent} from './tag-manager/tag-manager.component';
import {NotFoundComponent} from './not-found/not-found.component';
import {carryScope} from './scope-url';

/**
 * `/table/{schema}/{table}/{column}` → the table lens with that column drilled
 * into. The lens already *is* a column-filtered table view; it reads the
 * selection from `?columns=`, the shared search vocabulary that the workbench
 * and the graph speak too.
 *
 * So this redirects rather than seeding a second input: one state, one
 * shareable URL, per the URL rule in CLAUDE.md. The path form stays as the
 * inbound alias — every `app-column-link` ever shared points at it — and the
 * address bar rewrites to the canonical form on arrival.
 *
 * Existing query params are carried across: a column link followed from an
 * already-filtered view must not silently drop the rest of the filter.
 */
export const columnDrillRedirect: RedirectFunction = route => {
  const router = inject(Router);
  const schema = route.params['schema'];
  const table = route.params['name'];
  const column = route.params['column'];
  return router.createUrlTree(['/table', schema, table], {
    queryParams: {...route.queryParams, columns: `${schema}.${table}.${column}`}
  });
};

/**
 * `/table/{s}/{t}/queries` and `/table/{s}/{t}/join/{js}/{jt}` were the two
 * routes into `TableViewComponent`, which the lens replaced. They redirect
 * rather than 404 for the same reason the column path form does: links to
 * them have been shared, and a link that has been sent to someone should keep
 * working.
 *
 * They cannot simply be deleted either. `table/:schema/:name/:column` matches
 * `/table/x/y/queries` perfectly well, so removing these would send that URL
 * to the column drill-down, which would redirect it to `?columns=x.y.queries`
 * and render a confident, wrong, empty-looking page. A `**` catch-all does
 * not help — `:column` matches first. Declaration order is still the only
 * thing separating them, and `app.routes.spec.ts` still pins it.
 */
export const tableQueriesRedirect: RedirectFunction = route => {
  const router = inject(Router);
  // the lens *is* the queries view; there is nothing extra to express
  return router.createUrlTree(['/table', route.params['schema'], route.params['name']],
    { queryParams: route.queryParams });
};

/**
 * The join view becomes the lens scoped to both tables — `?tables=a,b` with
 * `tableMode=ALL`, which is exactly what the lens's own join toggle writes,
 * so the redirect lands on a URL the page already round-trips.
 */
export const tableJoinRedirect: RedirectFunction = route => {
  const router = inject(Router);
  const schema = route.params['schema'];
  const name = route.params['name'];
  const self = tableKey(schema, name);
  const other = tableKey(route.params['joinSchema'], route.params['joinTable']);
  return router.createUrlTree(['/table', schema, name], {
    queryParams: { ...route.queryParams, tables: `${self},${other}`, tableMode: 'ALL' }
  });
};

const routes: Routes = [
  {
    path: 'queries',
    component: QueriesWorkbenchComponent,
    data: { title: 'Queries' }
  },
  {
    path: 'graph',
    component: GraphLensComponent,
    data: { title: 'Graph' }
  },
  {
    path: 'graph/:schema',
    component: GraphLensComponent,
    data: { title: 'Graph' }
  },
  {
    path: 'schemas',
    component: SchemaListComponent,
    data: { title: 'Schemas' }
  },
  {
    path: 'schemas/by-tag/:tag',
    component: TaggedSchemaViewComponent,
    data: { title: 'Tagged Schemas' }
  },
  {
    path: 'tables/:schema',
    component: TableNamesComponent,
    data: { title: 'Table Names' }
  },
  {
    path: 'tables',
    component: TableNamesComponent,
    data: { title: 'Table Names' }
  },
  {
    path: 'tags',
    component: TagManagerComponent,
    data: { title: 'Tags' }
  },
  {
    path: 'tables/with-column/:column',
    component: TablesWithColumnComponent,
    data: { title: 'Tables with Column' }
  },
  {
    path: 'tables/by-tag/:tag',
    component: TaggedTableViewComponent,
    data: { title: 'Tagged Tables' }
  },
  {
    path: 'table/:schema/:name',
    component: TableLensComponent,
    data: { title: 'Table View'}
  },
  // `table/:schema/:name/:column` matches these two as well, so they have to
  // stay ahead of it — `queries` and `join` would otherwise be read as column
  // names. Pinned by app.routes.spec.ts.
  {
    path: 'table/:schema/:name/queries',
    pathMatch: 'full',
    redirectTo: tableQueriesRedirect
  },
  {
    path: 'table/:schema/:name/join/:joinSchema/:joinTable',
    pathMatch: 'full',
    redirectTo: tableJoinRedirect
  },
  {
    path: 'table/:schema/:name/:column',
    pathMatch: 'full',
    redirectTo: columnDrillRedirect
  },
  {
    path: 'analyzed-sql/:id',
    component: SqlViewComponent,
    data: { title: 'Query View' }
  },
  {
    path: 'analyzed-sql/by-tag/:tag',
    component: TaggedSqlViewComponent,
    data: { title: 'Tagged Sql' }
  },
  {
    path: 'explain/from-tsv',
    component: ExplainViewComponent,
    data: { title: 'Explain View' }
  },
  {
    path: 'query-utilities',
    component: QueryUtilitiesComponent,
    data: { title: 'Query Utilities' }
  },
  {
    path: 'table-graph/schema/:schema/:table/depth/:depth',
    component: TableGraphLoaderComponent,
    data: { title: 'Table Graph' }
  },
  {
    path: 'table-graph/schema/:schema/:table',
    component: TableGraphLoaderComponent,
    data: { title: 'Table Graph' }
  },
  {
    path: 'table-graph/schema/:schema',
    component: TableGraphLoaderComponent,
    data: { title: 'Table Graph' }
  },
  {
    path: 'table-graph/by-tag/:tag',
    component: TableGraphLoaderComponent,
    data: { title: 'Table Graph' }
  },
  {
    path: 'table-graph/by-tag/:tag/depth/:depth',
    component: TableGraphLoaderComponent,
    data: { title: 'Table Graph' }
  },
  {
    path: 'table-graph',
    component: TableGraphLoaderComponent,
    data: { title: 'Table Graph' }
  },
  {
    path: 'admin/sources',
    component: AdminSourceListComponent,
    data: { title: 'Sources' }
  },
  {
    path: 'note',
    component: NoteViewComponent,
    data: { note: `### Some Note
1. This is the numbered list
1. And more

[link](https://google.com)
    `,
      createdOn: new Date(),
      author: {

      }
    }
  },
  {
    path: '',
    redirectTo: '/tables',
    pathMatch: 'full'
  },
  /**
   * Last, and it has to stay last — `**` matches everything, so any route
   * declared below it is dead. Before this existed an unmatched URL rendered
   * an empty outlet and the address bar quietly reset to `/`, which reads as
   * a broken app rather than a wrong link.
   *
   * A component rather than a redirect: redirecting to `/tables` would throw
   * away the address that missed, and these are overwhelmingly links someone
   * was sent. Seeing what arrived is what makes the typo findable.
   */
  {
    path: '**',
    component: NotFoundComponent,
    data: { title: 'Not found' }
  }

];

/**
 * Every route carries {@link carryScope}, applied here rather than written on
 * each of the 28 entries.
 *
 * <p>That is a deliberate choice against explicitness: a route added later
 * without the guard would silently drop the working scope, which is the exact
 * failure the guard exists to prevent and the kind nothing would catch. There
 * is no route for which carrying the scope is wrong.
 *
 * <p>Redirect routes are skipped because Angular refuses the pairing outright
 * — NG04014, "redirectTo and canActivate cannot be used together. Redirects
 * happen before guards are executed." Nothing is lost: a redirect resolves to
 * another route in this same list, which carries the guard, so the scope is
 * restored at the destination. The two redirects that build their own target
 * URL already forward the query string explicitly.
 */
export const appRoutes: Routes = routes.map(route =>
  route.redirectTo === undefined ? {...route, canActivate: [carryScope]} : route);
