import {SEEN_FORMAT} from '../seen-format';
import {Component, Input, OnInit} from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { PicoViewComponent } from '../pico-view/pico-view.component';
import { ColumnLinkComponent } from '../column-link/column-link.component';
import { SqlProfile } from '../api-types';

@Component({
    selector: 'app-sql-summary',
    templateUrl: './sql-summary.component.html',
    styleUrls: ['./sql-summary.component.scss'],
    imports: [PicoViewComponent, ColumnLinkComponent, DecimalPipe, DatePipe]
})
export class SqlSummaryComponent implements OnInit {

  /** @see SEEN_FORMAT — shared so every surface writes these the same way. */
  readonly SEEN_FORMAT = SEEN_FORMAT;

  @Input()
  query!: SqlProfile;

  constructor() { }

  ngOnInit() {
  }

}
