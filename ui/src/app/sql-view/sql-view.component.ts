import {ChangeDetectorRef, Component, OnInit} from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute} from '@angular/router';
import {Title} from '@angular/platform-browser';
import {Highlight} from 'ngx-highlightjs';
import {TagViewComponent} from '../tag-view/tag-view.component';
import {PicoViewComponent} from '../pico-view/pico-view.component';
import {TableLinkComponent} from '../table-link/table-link.component';
import {ColumnLinkComponent} from '../column-link/column-link.component';
import {SmallTableViewComponent} from '../small-table-view/small-table-view.component';
import {SqlProfile, TableStructure} from '../api-types';
import {parseTableRef} from '../refs';

@Component({
    selector: 'app-sql-view',
    templateUrl: './sql-view.component.html',
    styleUrls: ['./sql-view.component.css'],
    providers: [AnalyzedSqlService],
    imports: [Highlight, TagViewComponent, PicoViewComponent, TableLinkComponent, ColumnLinkComponent, SmallTableViewComponent]
})
export class SqlViewComponent implements OnInit {

  public query: SqlProfile | null = null;
  public tables: TableStructure[] | null = null;

  constructor(private sqlService: AnalyzedSqlService, private route: ActivatedRoute,
              private changeDetectorRef: ChangeDetectorRef,
              private titleService: Title
  ) { }

  ngOnInit() {
    this.route.params.subscribe(params => {
      this.getSql(params['id']);
    });
  }

  getSql(id: string) {
    this.sqlService.getSql(id).subscribe(data => {
      this.query = data;
      this.tables = [];
      // `tables` is a list of "schema.table" strings, not objects — reading
      // `.tableName` off them fetched /metadata/table/<schema>/undefined, so
      // this panel was always empty
      data.tables.forEach(tableRef => {
        const ref = parseTableRef(tableRef);
        const schema = ref.schema ?? data.schemaName;
        this.sqlService.getTableStructure(schema, ref.table).subscribe(tableStructure => {
          // the metadata endpoint returns an empty body for tables the loaded
          // schema doesn't know (dropped tables, other servers, misextracted
          // references) — those have nothing to render
          if (tableStructure) {
            this.tables!.push(tableStructure);
            this.changeDetectorRef.markForCheck();
          }
        });
      });
      this.titleService.setTitle('sql: ' + data.tables.map(ref => parseTableRef(ref).table).join(', '));
    });
  }
}
