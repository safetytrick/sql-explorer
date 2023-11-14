import { Component, OnInit } from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute, Router} from '@angular/router';
import {Title} from '@angular/platform-browser';

@Component({
  selector: 'app-table-view',
  templateUrl: './table-view.component.html',
  styleUrls: ['./table-view.component.css']
})
export class TableViewComponent implements OnInit {

  table: any = null;


  constructor(private sqlService: AnalyzedSqlService, private route: ActivatedRoute,
              private router: Router, private titleService: Title) { }

  ngOnInit() {
    this.route.params.subscribe((params) => {
      if (params['joinSchema'] && params['joinTable']) {
        this.getSqlForTableAndJoin(params['schema'],
          params['name'],
          params['joinSchema'],
          params['joinTable']);
      } else {
        this.getSqlForTable(params['schema'],
          params['name'],
          params['column']);
      }

      this.updateTitle(params);
    });
  }

  updateTitle(params: any) {
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
      (data: {}) => {
        this.table = data;
      });
  }

  getSqlForTableAndJoin(schema: string, table: string, otherSchema: string, otherTable: string) {
    this.table = null;
    this.sqlService.getSqlForTableAndJoin(schema, table, otherSchema, otherTable).subscribe(
      (data: {}) => {
        this.table = data;
      }
    );
  }

  otherTables(originalTableName: string) {
    return (tableName: string) => {
      return tableName !== originalTableName;
    };
  }

  skipPrimary(table: any) {
    return table['name'] !== 'PRIMARY';
  }

}
