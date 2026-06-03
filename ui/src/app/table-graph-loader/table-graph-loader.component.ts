import { Component, OnInit } from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute} from '@angular/router';
import {Observable} from 'rxjs';
import {Title} from '@angular/platform-browser';
import { TableGraphComponent } from '../table-graph/table-graph.component';

@Component({
    selector: 'app-table-graph-loader',
    templateUrl: './table-graph-loader.component.html',
    styleUrls: ['./table-graph-loader.component.scss'],
    imports: [TableGraphComponent]
})
export class TableGraphLoaderComponent implements OnInit {

  depth!: number;
  tag!: string;
  schema!: string;
  table!: string;

  query!: Observable<any>;

  constructor(public sqlService: AnalyzedSqlService,
              private titleService: Title,
              private route: ActivatedRoute) { }

  ngOnInit() {
    this.route.params.subscribe(params => {
      this.depth = params['depth'] || 0;
      this.schema = params['schema'];
      this.tag = params['tag'];
      this.table = params['table'];

      let title: string;
      if (this.tag) {
        title = this.tag;
      } else if (this.table) {
        title = this.schema + '.' + this.table;
      } else if (this.schema) {
        title = this.schema;
      } else {
        title = 'all';
      }

      this.titleService.setTitle('graph: ' + title);

      this.query = this.loadGraph();
    });

  }

  private loadGraph() {
    if (this.tag) {
      return this.sqlService.getTableGraphForTag(this.tag, this.depth);
    } if (this.table) {
      return this.sqlService.getTableGraphForTable(this.schema, this.table, this.depth);
    } else if (this.schema) {
      return this.sqlService.getTableGraphForSchema(this.schema);
    }
    return this.sqlService.getTableGraph();
  }

}
