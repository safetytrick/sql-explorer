import {Component, Input, OnInit} from '@angular/core';

@Component({
    selector: 'app-sql-summary',
    templateUrl: './sql-summary.component.html',
    styleUrls: ['./sql-summary.component.scss'],
    standalone: false
})
export class SqlSummaryComponent implements OnInit {

  @Input()
  query: any;

  constructor() { }

  ngOnInit() {
  }

}
