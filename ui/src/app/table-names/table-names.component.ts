import {Component, Input, OnInit} from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute} from '@angular/router';
import {Title} from '@angular/platform-browser';
import { TableListComponent } from '../table-list-component/table-list.component';

@Component({
    selector: 'app-table-names',
    templateUrl: './table-names.component.html',
    styleUrls: ['./table-names.component.css'],
    imports: [TableListComponent]
})
export class TableNamesComponent implements OnInit {

  @Input()
  schema!: string;

  tables: any = [];

  constructor(public sqlService: AnalyzedSqlService, private route: ActivatedRoute,
              private titleService: Title) { }

  ngOnInit() {
    this.route.params.subscribe(params => {
        this.schema = params['schema'];
        if (this.schema) {
          this.titleService.setTitle(`::: schema ${this.schema} :::`);
          this.getTablesForSchema(this.schema);
        } else {
          this.titleService.setTitle('::: sql explorer :::');
          this.getTables();
        }
    });

  }

  getTables() {
    this.tables = [];
    this.sqlService.getTables().subscribe((data: {}) => {
      this.tables = data;
    });
  }

  private getTablesForSchema(schema: string) {
    this.sqlService.getTablesForSchema(schema).subscribe((data: {}) => {
      this.tables = data;
    });
  }
}
