import {SettingsComponent} from './settings/settings.component';
import {SchemaDriftComponent} from './schema-drift/schema-drift.component';
import {paramTitle} from './page-title';
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
import {SqlViewComponent} from './sql-view/sql-view.component';
import {ExplainViewComponent} from './explain-view/explain-view.component';
import {QueryUtilitiesComponent} from './query-utilities/query-utilities.component';
import {DdlPreflightComponent} from './ddl-preflight/ddl-preflight.component';
import {AdminSourceListComponent} from './admin-source-list/admin-source-list.component';
import {NoteViewComponent} from './note-view/note-view.component';
import {TableProfileComponent} from './table-profile/table-profile.component';
import {TopologyComponent} from './topology/topology.component';
import {TableDdlComponent} from './table-ddl/table-ddl.component';
import {TableLensComponent} from './table-lens/table-lens.component';
import {QueriesWorkbenchComponent} from './queries-workbench/queries-workbench.component';
import {graphRedirectParams, tableGraphRedirectParams} from './graph-lens/graph-url';
import {TagManagerComponent} from './tag-manager/tag-manager.component';
import {NotFoundComponent} from './not-found/not-found.component';
import {UnqueriedTablesComponent} from './unqueried-tables/unqueried-tables.component';
import {CoverageLensComponent} from './coverage-lens/coverage-lens.component';
import {UnusedIndexesComponent} from './unused-indexes/unused-indexes.component';
import {carryScope, droppedScope} from './scope-url';
import {McpSetupComponent} from './mcp-setup/mcp-setup.component';
import {AccessTokensComponent} from './access-tokens/access-tokens.component';
import {CollationsComponent} from './collations/collations.component';

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
 * The graph is a view of the workbench now, `/queries?view=graph`
 * (sqlx-aoy.16). `/graph` and `/graph/:schema` links have been shared, so they
 * redirect to the same question asked there; {@link graphRedirectParams} says
 * how each old form translates.
 */
export const graphRedirect: RedirectFunction = route => {
  const router = inject(Router);
  // A redirect cannot carry the guard, and `/queries` does not re-run it when
  // the graph link is followed from `/queries` itself, so the scope you were
  // browsing under is carried here, as carryScope would.
  const carried = droppedScope(router.routerState.snapshot.root.queryParams, route.queryParams);
  return router.createUrlTree(['/queries'],
    { queryParams: {...carried, ...graphRedirectParams(route.params['schema'] ?? null, route.queryParams)} });
};

/**
 * `/table-graph/**` was the ngx-graph table graph, retired in sqlx-aoy.9. Its
 * links redirect to the graph view asking the same question; see
 * {@link tableGraphRedirectParams}. The scope is carried as for `/graph`.
 */
export const tableGraphRedirect: RedirectFunction = route => {
  const router = inject(Router);
  const carried = droppedScope(router.routerState.snapshot.root.queryParams, route.queryParams);
  return router.createUrlTree(['/queries'],
    { queryParams: {...carried, ...tableGraphRedirectParams(route.params, route.queryParams)} });
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

/**
 * The by-tag pages were separate copies of Schemas, Tables and the workbench
 * narrowed to one tag. Each list page filters by tag itself now, so these
 * addresses redirect to it with `?tags=` (sqlx-m0j.2) — links to them have been
 * shared, and the list page is the better place to land: the filter can be
 * widened from there. `tagMode` is written for the list pages, whose strip
 * always writes it; the workbench defaults to ANY and needs none for one tag.
 */
export function byTagRedirect(target: string, tagMode: 'ALL' | null): RedirectFunction {
  return route => inject(Router).createUrlTree([target], {
    queryParams: {...route.queryParams, tags: route.params['tag'], ...(tagMode ? {tagMode} : {})}
  });
}

const routes: Routes = [
  {
    path: 'queries',
    component: QueriesWorkbenchComponent,
    title: 'Queries'
  },
  {
    path: 'unqueried',
    component: UnqueriedTablesComponent,
    title: 'Unqueried tables'
  },
  {
    path: 'captured',
    component: CoverageLensComponent,
    title: 'Captured'
  },
  {
    path: 'settings',
    component: SettingsComponent,
    title: 'Settings'
  },
  {
    path: 'drift',
    component: SchemaDriftComponent,
    title: 'Schema drift'
  },
  {
    path: 'collations',
    component: CollationsComponent,
    title: 'Charsets & collations'
  },
  /** The page's old name; links to it have been shared (sqlx-om1.4). */
  {
    path: 'coverage',
    redirectTo: '/captured'
  },
  {
    path: 'topology',
    component: TopologyComponent,
    title: 'Topology'
  },
  {
    path: 'unused-indexes',
    component: UnusedIndexesComponent,
    title: 'Unused indexes'
  },
  {
    // full: a prefix match would take `/graph/app_main` too, and lose the schema
    path: 'graph',
    pathMatch: 'full',
    redirectTo: graphRedirect
  },
  {
    path: 'graph/:schema',
    redirectTo: graphRedirect
  },
  {
    path: 'schemas',
    component: SchemaListComponent,
    title: 'Schemas'
  },
  {
    path: 'schemas/by-tag/:tag',
    redirectTo: byTagRedirect('/schemas', 'ALL')
  },
  {
    path: 'tables/:schema',
    component: TableNamesComponent,
    title: paramTitle(p => `Tables · ${p['schema']}`)
  },
  {
    path: 'tables',
    component: TableNamesComponent,
    title: 'Tables'
  },
  {
    path: 'tags',
    component: TagManagerComponent,
    title: 'Tags'
  },
  {
    path: 'tables/with-column/:column',
    component: TablesWithColumnComponent,
    title: paramTitle(p => `Tables with ${p['column']}`)
  },
  {
    path: 'tables/by-tag/:tag',
    redirectTo: byTagRedirect('/tables', 'ALL')
  },
  {
    path: 'table/:schema/:name',
    component: TableLensComponent,
    title: paramTitle(p => `${p['schema']}.${p['name']}`)
  },
  // `table/:schema/:name/:column` matches these three as well, so they have to
  // stay ahead of it — `ddl`, `profile`, `queries` and `join` would otherwise be read as
  // column names. Pinned by app.routes.spec.ts.
  {
    path: 'table/:schema/:name/ddl',
    pathMatch: 'full',
    component: TableDdlComponent,
    title: paramTitle(p => `Plan a change · ${p['schema']}.${p['name']}`)
  },
  {
    path: 'table/:schema/:name/profile',
    pathMatch: 'full',
    component: TableProfileComponent,
    title: paramTitle(p => `Profile data · ${p['schema']}.${p['name']}`)
  },
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
    title: 'Query'
  },
  {
    path: 'analyzed-sql/by-tag/:tag',
    redirectTo: byTagRedirect('/queries', null)
  },
  {
    path: 'explain/from-tsv',
    component: ExplainViewComponent,
    title: 'Explain'
  },
  {
    path: 'query-utilities',
    component: QueryUtilitiesComponent,
    title: 'Query Utilities'
  },
  {
    path: 'ddl-preflight',
    component: DdlPreflightComponent,
    title: 'DDL pre-flight'
  },
  {
    path: 'table-graph/schema/:schema/:table/depth/:depth',
    redirectTo: tableGraphRedirect
  },
  {
    path: 'table-graph/schema/:schema/:table',
    redirectTo: tableGraphRedirect
  },
  {
    path: 'table-graph/schema/:schema',
    redirectTo: tableGraphRedirect
  },
  {
    path: 'table-graph/by-tag/:tag/depth/:depth',
    redirectTo: tableGraphRedirect
  },
  {
    path: 'table-graph/by-tag/:tag',
    redirectTo: tableGraphRedirect
  },
  {
    path: 'table-graph',
    pathMatch: 'full',
    redirectTo: tableGraphRedirect
  },

  {
    path: 'admin/sources',
    component: AdminSourceListComponent,
    title: 'Sources'
  },
  {
    path: 'admin/mcp',
    component: McpSetupComponent,
    title: 'MCP'
  },
  /** Personal access tokens, for MCP and API clients (sqlx-e13.5); linked from the signed-in user in the nav. */
  {
    path: 'tokens',
    component: AccessTokensComponent,
    title: 'Access tokens'
  },
  {
    path: 'note',
    component: NoteViewComponent,
    title: 'Note',
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
  /** Schemas is the starting page: the lay of the land (sqlx-m6r.4). */
  {
    path: '',
    redirectTo: '/schemas',
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
    title: 'Not found'
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
