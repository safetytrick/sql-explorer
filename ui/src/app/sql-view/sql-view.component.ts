import {ChangeDetectorRef, Component, OnInit} from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute} from '@angular/router';
import {Title} from '@angular/platform-browser';
import { Highlight } from 'ngx-highlightjs';
import { TagViewComponent } from '../tag-view/tag-view.component';
import { PicoViewComponent } from '../pico-view/pico-view.component';
import { TableLinkComponent } from '../table-link/table-link.component';
import { ColumnLinkComponent } from '../column-link/column-link.component';
import { SmallTableViewComponent } from '../small-table-view/small-table-view.component';
import { SqlProfile, TableStructure } from '../api-types';

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
      data.tables.forEach(ref => {
        const schema = ref.schemaName ?? data.schemaName;
        this.sqlService.getTableStructure(schema, ref.tableName).subscribe(tableStructure => {
          this.tables!.push(tableStructure);
          this.changeDetectorRef.markForCheck();
        });
      });
      this.titleService.setTitle('sql: ' + data.tables.map(ref => ref.tableName).join(', '));
    });
  }
}
