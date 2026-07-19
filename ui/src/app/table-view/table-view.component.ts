import { Component, OnInit } from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import { ActivatedRoute, Params, Router, RouterLink } from '@angular/router';
import {Title} from '@angular/platform-browser';
import { TagViewComponent } from '../tag-view/tag-view.component';
import { PicoViewComponent } from '../pico-view/pico-view.component';
import { TableLinkComponent } from '../table-link/table-link.component';
import { Highlight } from 'ngx-highlightjs';
import { SqlSummaryComponent } from '../sql-summary/sql-summary.component';
import { ColumnLinkComponent } from '../column-link/column-link.component';
import { CallbackPipePipe } from '../callback-pipe.pipe';
import { Table } from '../api-types';

@Component({
    selector: 'app-table-view',
    templateUrl: './table-view.component.html',
    styleUrls: ['./table-view.component.css'],
    imports: [RouterLink, TagViewComponent, PicoViewComponent, TableLinkComponent, Highlight, SqlSummaryComponent, ColumnLinkComponent, CallbackPipePipe]
})
export class TableViewComponent implements OnInit {

  table: Table | null = null;
  showCreate = false;
  createStatement: string | null = null;
  createLoading = false;
  column: string | null = null;


  constructor(private sqlService: AnalyzedSqlService, private route: ActivatedRoute,
              private router: Router, private titleService: Title) { }

  ngOnInit() {
    this.route.params.subscribe((params) => {
      this.column = null;
      this.showCreate = false;
      this.createStatement = null;
      this.createLoading = false;
      if (params['joinSchema'] && params['joinTable']) {
        this.getSqlForTableAndJoin(params['schema'],
          params['name'],
          params['joinSchema'],
          params['joinTable']);
      } else {
        this.getSqlForTable(params['schema'],
          params['name'],
          params['column']);
        this.column = params['column'];
      }

      this.updateTitle(params);
    });
  }

  updateTitle(params: Params) {
    // no idea how this can be called with a null titleService
    if (this.titleService) {
      let root = params['schema'] + '.' + params['name'];
      if (params['column']) {
        root += '.' + params['column'];
      } else if (params['joinSchema'] && params['joinTable']) {
        root += ' join ' + params['joinSchema'] + '.' + params['joinTable'];
      }
      this.titleService.setTitle(root);
    }
  }

  getSqlForTable(schema: string, name: string, column: string) {
    this.table = null;
    this.sqlService.getTable(schema, name, column).subscribe(
      data => {
        this.table = data;
      });
  }

  getSqlForTableAndJoin(schema: string, table: string, otherSchema: string, otherTable: string) {
    this.table = null;
    this.sqlService.getSqlForTableAndJoin(schema, table, otherSchema, otherTable).subscribe(
      data => {
        this.table = data;
      }
    );
  }

  toggleCreate() {
    this.showCreate = !this.showCreate;
    if (this.showCreate && this.createStatement === null && this.table) {
      this.createLoading = true;
      this.sqlService.getTableCreateStatement(this.table.schema, this.table.name).subscribe(
        (create: string) => {
          this.createStatement = create;
          this.createLoading = false;
        },
        () => {
          this.createLoading = false;
        });
    }
  }

  otherTables(table: Table) {
    return (tableName: string) => {
      return tableName !== table.name && tableName !== (table.schema + '.' + table.name);
    };
  }

  skipPrimary(index: { name: string }) {
    return index['name'] !== 'PRIMARY';
  }

}
