import {Component, Input, OnInit} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { PicoViewComponent } from '../pico-view/pico-view.component';
import { ColumnLinkComponent } from '../column-link/column-link.component';
import { SqlProfile } from '../api-types';

@Component({
    selector: 'app-sql-summary',
    templateUrl: './sql-summary.component.html',
    styleUrls: ['./sql-summary.component.scss'],
    imports: [PicoViewComponent, ColumnLinkComponent, DecimalPipe]
})
export class SqlSummaryComponent implements OnInit {

  @Input()
  query!: SqlProfile;

  constructor() { }

  ngOnInit() {
  }

}
