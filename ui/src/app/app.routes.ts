/**
 * The application's route table, kept out of `main.ts` so it can be exercised
 * in a spec — route *ordering* is load-bearing here (see the column drill-down
 * below) and ordering is exactly the kind of thing a refactor breaks silently.
 */
import {inject} from '@angular/core';
import {RedirectFunction, Router, Routes} from '@angular/router';
import {SchemaListComponent} from './schema-list/schema-list.component';
import {TableNamesComponent} from './table-names/table-names.component';
import {TablesWithColumnComponent} from './tables-with-column/tables-with-column.component';
import {TaggedTableViewComponent} from './tagged-table-view/tagged-table-view.component';
import {TaggedSchemaViewComponent} from './tagged-schema-view/tagged-schema-view.component';
import {TableViewComponent} from './table-view/table-view.component';
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

export const appRoutes: Routes = [
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
    component: TableViewComponent,
    data: { title: 'Table Queries'}
  },
  {
    path: 'table/:schema/:name/join/:joinSchema/:joinTable',
    component: TableViewComponent,
    data: { title: 'Join View'}
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
  }

];
