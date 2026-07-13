import {enableProdMode, importProvidersFrom, provideZoneChangeDetection} from '@angular/core';

import {environment} from './environments/environment';
import {AnalyzedSqlService} from './app/analyzed-sql.service';
import {MAT_SNACK_BAR_DEFAULT_OPTIONS, MatSnackBarModule} from '@angular/material/snack-bar';
import {HIGHLIGHT_OPTIONS, HighlightModule} from 'ngx-highlightjs';
import {provideHttpClient, withInterceptors, withInterceptorsFromDi} from '@angular/common/http';
import {environmentInterceptor} from './app/environment.interceptor';
import {provideRouter, Routes, withInMemoryScrolling} from '@angular/router';
import {SchemaListComponent} from './app/schema-list/schema-list.component';
import {TableNamesComponent} from './app/table-names/table-names.component';
import {TagIndexComponent} from './app/tag-index/tag-index.component';
import {TablesWithColumnComponent} from './app/tables-with-column/tables-with-column.component';
import {TaggedTableViewComponent} from './app/tagged-table-view/tagged-table-view.component';
import {TableViewComponent} from './app/table-view/table-view.component';
import {SqlViewComponent} from './app/sql-view/sql-view.component';
import {SqlListComponent} from './app/sql-list/sql-list.component';
import {TaggedSqlViewComponent} from './app/tagged-sql-view/tagged-sql-view.component';
import {ExplainViewComponent} from './app/explain-view/explain-view.component';
import {QueryUtilitiesComponent} from './app/query-utilities/query-utilities.component';
import {TableGraphLoaderComponent} from './app/table-graph-loader/table-graph-loader.component';
import {AdminSourceListComponent} from './app/admin-source-list/admin-source-list.component';
import {NoteViewComponent} from './app/note-view/note-view.component';
import {FormsModule, ReactiveFormsModule} from '@angular/forms';
import {bootstrapApplication} from '@angular/platform-browser';
import {provideAnimations} from '@angular/platform-browser/animations';
import {MatTableModule} from '@angular/material/table';
import {NgxGraphModule} from '@swimlane/ngx-graph';
import {MatChipsModule} from '@angular/material/chips';
import {MatAutocompleteModule} from '@angular/material/autocomplete';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatInputModule} from '@angular/material/input';
import {MatIconModule} from '@angular/material/icon';
import {MatListModule} from '@angular/material/list';
import {AppComponent} from './app/app.component';
import {TableLensComponent} from './app/table-lens/table-lens.component';
import {QueriesWorkbenchComponent} from './app/queries-workbench/queries-workbench.component';
import {GraphLensComponent} from './app/graph-lens/graph-lens.component';
import {TagManagerComponent} from './app/tag-manager/tag-manager.component';

const appRoutes: Routes = [
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
    path: 'tags/index',
    component: TagIndexComponent,
    data: { title: 'Tag Index' }
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
    component: TableViewComponent,
    data: { title: 'Column View'}
  },
  {
    path: 'analyzed-sql/:id',
    component: SqlViewComponent,
    data: { title: 'Query View' }
  },
  {
    path: 'analyzed-sql/reports/:name/:limit',
    component: SqlListComponent,
    data: { title: 'Reports' }
  },
  {
    path: 'analyzed-sql/reports/:name',
    component: SqlListComponent,
    data: { title: 'Reports' }
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



if (environment.production) {
  enableProdMode();
}

bootstrapApplication(AppComponent, {
    providers: [
        provideZoneChangeDetection({ eventCoalescing: true }),
        provideAnimations(),
        importProvidersFrom(FormsModule, ReactiveFormsModule, HighlightModule, MatTableModule, NgxGraphModule, MatChipsModule, MatAutocompleteModule, MatFormFieldModule, MatInputModule, MatIconModule, MatListModule, MatSnackBarModule),
        AnalyzedSqlService,
        { provide: MAT_SNACK_BAR_DEFAULT_OPTIONS, useValue: { duration: 250000, verticalPosition: 'top' } },
        {
            provide: HIGHLIGHT_OPTIONS,
            useValue: {
                coreLibraryLoader: () => import('highlight.js/lib/core'),
                languages: {
                    sql: () => import('highlight.js/lib/languages/sql'),
                    xml: () => import('highlight.js/lib/languages/xml'),
                }
            }
        },
        provideHttpClient(withInterceptorsFromDi(), withInterceptors([environmentInterceptor])),
        provideRouter(appRoutes, withInMemoryScrolling({
            // it would be nice to get this working: https://github.com/angular/angular/issues/24547
            scrollPositionRestoration: 'enabled',
            anchorScrolling: 'enabled' }))
    ]
})
  .catch(err => console.log(err));
