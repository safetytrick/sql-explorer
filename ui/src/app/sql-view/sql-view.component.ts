import {ChangeDetectorRef, Component, OnInit} from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute} from '@angular/router';
import {Title} from '@angular/platform-browser';

@Component({
  selector: 'app-sql-view',
  templateUrl: './sql-view.component.html',
  styleUrls: ['./sql-view.component.css'],
  providers: [AnalyzedSqlService]
})
export class SqlViewComponent implements OnInit {

  public query: any = null;
  public tables: any[] = null;

  constructor(private sqlService: AnalyzedSqlService, private route: ActivatedRoute,
              private changeDetectorRef: ChangeDetectorRef,
              private titleService: Title
  ) { }

  ngOnInit() {
    this.route.params.subscribe(params => {
      this.getSql(params['id']);
    });
  }

  getSql(id) {
    this.sqlService.getSql(id).subscribe((data: {}) => {
      this.query = data;
      this.tables = [];
      // todo handle queries with cross-schema joins (table is like newt.tag_map)
      this.query.tables.forEach(table => (table.indexOf('.') === -1 ? this.sqlService.getTableStructure(this.query.schemaName, table)
        : this.sqlService.getTableStructure(table.split('.')[0], table.split('.')[1]))
        .subscribe((tableStructure: {}) => {
          this.tables.push(tableStructure);
          this.changeDetectorRef.markForCheck();
        }));
      this.titleService.setTitle('sql: ' + this.query.tables.map(t => t).join(', '));
    });
  }
}
