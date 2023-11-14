import { Component, OnInit } from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute, Router} from '@angular/router';
import {Title} from '@angular/platform-browser';

@Component({
  selector: 'app-sql-list',
  templateUrl: './sql-list.component.html',
  styleUrls: ['./sql-list.component.css'],
  providers: [AnalyzedSqlService]
})
export class SqlListComponent implements OnInit {

  public sqlList: any = [];
  private limit = 10;
  private name: string = null;

  constructor(public sqlService: AnalyzedSqlService, private route: ActivatedRoute, private router: Router, private titleService: Title) {
    this.route.params.subscribe(params => {
      this.name = params['name'] || 'all';
      this.limit = params['limit'] || 50;

      titleService.setTitle('report: ' + this.name);

      this.getReportSql();
    });
  }

  ngOnInit() {
    this.getReportSql();
  }

  getReportSql() {
    this.sqlList = [];
    this.sqlService.getReportSql(this.name, this.limit).subscribe((data: {}) => {
      this.sqlList = data;
    });
  }

}
