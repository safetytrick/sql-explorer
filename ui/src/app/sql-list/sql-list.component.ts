import {Component, OnInit} from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {Title} from '@angular/platform-browser';
import { Highlight } from 'ngx-highlightjs';
import { SqlSummaryComponent } from '../sql-summary/sql-summary.component';

@Component({
    selector: 'app-sql-list',
    templateUrl: './sql-list.component.html',
    styleUrls: ['./sql-list.component.css'],
    providers: [AnalyzedSqlService],
    imports: [RouterLink, Highlight, SqlSummaryComponent]
})
export class SqlListComponent implements OnInit {

  public sqlList: any = [];
  public limit = 10;
  public offset = 0;
  // true while the most recent page came back full, i.e. there may be another page
  public hasNextPage = false;
  private name: string | null = null;

  constructor(public sqlService: AnalyzedSqlService,
              private route: ActivatedRoute,
              private titleService: Title) {
    this.route.params.subscribe(params => {
      this.name = params['name'] || 'all';
      this.limit = params['limit'] || 50;
      this.offset = 0;

      titleService.setTitle('report: ' + this.name);

      this.getReportSql();
    });
  }

  ngOnInit() {
    this.getReportSql();
  }

  getReportSql() {
    this.sqlList = [];
    this.sqlService.getReportSql(this.name!, this.limit, this.offset).subscribe((data: any) => {
      this.sqlList = data;
      // the endpoint has no total count; a full page means there is likely more.
      this.hasNextPage = data.length === this.limit;
    });
  }

  nextPage() {
    if (!this.hasNextPage) {
      return;
    }
    this.offset += this.limit;
    this.getReportSql();
  }

  previousPage() {
    if (this.offset === 0) {
      return;
    }
    this.offset = Math.max(0, this.offset - this.limit);
    this.getReportSql();
  }

}
