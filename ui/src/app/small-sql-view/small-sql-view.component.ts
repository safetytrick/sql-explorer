import {Component, Input, OnInit} from '@angular/core';
import { RouterLink } from '@angular/router';
import { Highlight } from 'ngx-highlightjs';
import { SqlSummaryComponent } from '../sql-summary/sql-summary.component';
import { SqlProfile } from '../api-types';

@Component({
    selector: 'app-small-sql-view',
    templateUrl: './small-sql-view.component.html',
    styleUrls: ['./small-sql-view.component.scss'],
    imports: [RouterLink, Highlight, SqlSummaryComponent]
})
export class SmallSqlViewComponent implements OnInit {

  @Input()
  query!: SqlProfile;

  constructor() { }

  ngOnInit() {
  }

}
