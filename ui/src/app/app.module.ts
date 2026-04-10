import {BrowserModule} from '@angular/platform-browser';
import {NgModule} from '@angular/core';
import {HttpClientModule} from '@angular/common/http';

import {RouterModule, Routes} from '@angular/router';
import {FormsModule, ReactiveFormsModule} from '@angular/forms';
import {MatLegacyTableModule as MatTableModule} from '@angular/material/legacy-table';
import {BrowserAnimationsModule} from '@angular/platform-browser/animations';

import {AppComponent} from './app.component';
import {TableNamesComponent} from './table-names/table-names.component';
import {TableViewComponent} from './table-view/table-view.component';
import {AnalyzedSqlService} from './analyzed-sql.service';
import {SqlListComponent} from './sql-list/sql-list.component';
import {SqlViewComponent} from './sql-view/sql-view.component';

import {HighlightModule} from 'ngx-highlightjs';

import {DurationPipe} from './duration.pipe';

import sql from 'highlight.js/lib/languages/sql';
import xml from 'highlight.js/lib/languages/xml';
import {PicoViewComponent} from './pico-view/pico-view.component';
import {CallbackPipePipe} from './callback-pipe.pipe';
import {ColumnLinkComponent} from './column-link/column-link.component';
import {NavBarComponent} from './nav-bar/nav-bar.component';
import {ExplainViewComponent} from './explain-view/explain-view.component';
import {SmallTableViewComponent} from './small-table-view/small-table-view.component';
import {NoteViewComponent} from './note-view/note-view.component';
import {MarkdownToHtmlPipe} from './markdown-to-html.pipe';
import {TableLinkComponent} from './table-link/table-link.component';
import {QueryUtilitiesComponent} from './query-utilities/query-utilities.component';
import {TableGraphComponent} from './table-graph/table-graph.component';
import {TagViewComponent} from './tag-view/tag-view.component';
import {NgxGraphModule} from '@swimlane/ngx-graph';
import {TaggedTableViewComponent} from './tagged-table-view/tagged-table-view.component';
import {TaggedSqlViewComponent} from './tagged-sql-view/tagged-sql-view.component';
import {SmallSqlViewComponent} from './small-sql-view/small-sql-view.component';
import {TagIndexComponent} from './tag-index/tag-index.component';
import {TableListComponent} from './table-list-component/table-list.component';
import {TableGraphLoaderComponent} from './table-graph-loader/table-graph-loader.component';
import {MatLegacyChipsModule as MatChipsModule} from '@angular/material/legacy-chips';
import {MatLegacyAutocompleteModule as MatAutocompleteModule} from '@angular/material/legacy-autocomplete';
import {MatLegacyFormFieldModule as MatFormFieldModule} from '@angular/material/legacy-form-field';
import {MatLegacyInputModule as MatInputModule} from '@angular/material/legacy-input';
import {MatIconModule} from '@angular/material/icon';
import {SqlSummaryComponent} from './sql-summary/sql-summary.component';
import {AdminSourceListComponent} from './admin-source-list/admin-source-list.component';
import {MatLegacyListModule as MatListModule} from '@angular/material/legacy-list';
import { MAT_LEGACY_SNACK_BAR_DEFAULT_OPTIONS as MAT_SNACK_BAR_DEFAULT_OPTIONS, MatLegacySnackBarModule as MatSnackBarModule } from '@angular/material/legacy-snack-bar';
import {TablesWithColumnComponent} from './tables-with-column/tables-with-column.component';
import {SchemaListComponent} from './schema-list/schema-list.component';


const appRoutes: Routes = [
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
    component: TagIndexComponent,
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
    component: TableViewComponent,
    data: { title: 'Table View'}
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

/**
 * Import every language you wish to highlight here
 * NOTE: The name of each language must match the file name its imported from
 */
export function hljsLanguages() {
  return [
    {name: 'sql', func: sql},
    {name: 'xml', func: xml}
  ];
}

@NgModule({
  declarations: [
    AppComponent,
    TableNamesComponent,
    TableViewComponent,
    SqlListComponent,
    SqlViewComponent,
    PicoViewComponent,
    CallbackPipePipe,
    ColumnLinkComponent,
    NavBarComponent,
    ExplainViewComponent,
    SmallTableViewComponent,
    NoteViewComponent,
    TableLinkComponent,
    QueryUtilitiesComponent,
    TagViewComponent,
    TaggedTableViewComponent,
    TaggedSqlViewComponent,
    SmallSqlViewComponent,
    TagIndexComponent,
    TableListComponent,
    TableGraphComponent,
    TagViewComponent,
    TableGraphLoaderComponent,
    SqlSummaryComponent,
    AdminSourceListComponent,
    TablesWithColumnComponent,
    SchemaListComponent,
    DurationPipe,
    MarkdownToHtmlPipe
  ],
    imports: [
        RouterModule.forRoot(appRoutes, {
    // it would be nice to get this working: https://github.com/angular/angular/issues/24547
    scrollPositionRestoration: 'enabled',
    anchorScrolling: 'enabled'
}),
        FormsModule,
        BrowserModule,
        BrowserAnimationsModule,
        HttpClientModule,
        HighlightModule.forRoot({languages: hljsLanguages}),
        MatTableModule,

        ReactiveFormsModule,
        NgxGraphModule,
        MatChipsModule,
        MatAutocompleteModule,
        MatFormFieldModule,
        MatInputModule,
        MatIconModule,
        MatListModule,
        MatSnackBarModule
    ],
  providers: [
    AnalyzedSqlService,
    {provide: MAT_SNACK_BAR_DEFAULT_OPTIONS, useValue: {duration: 250000, verticalPosition: 'top'}}
  ],
  bootstrap: [AppComponent]
})
export class AppModule { }
