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

  /**
   * Minute precision, and fixed-width on purpose. The backend sends a
   * zone-less `LocalDateTime` (`local_timestamp_ms` in the Avro), which
   * reached the template unformatted — 26 characters of raw ISO with
   * microseconds, `2026-05-04T15:49:26.782619`. That overflows the table
   * lens's 190px metrics rail, which is where this component now renders.
   *
   * `yyyy-MM-dd` rather than the workbench's `MMM d, yy` because
   * `.metric-val` is monospace and this is a right-aligned column: a
   * month-name format is 1–2 characters wider in December than in May, so a
   * date that fits in testing can overflow in production. This one is always
   * exactly 16.
   *
   * Seconds and microseconds are dropped rather than shortened. Nothing reads
   * a first/last-seen boundary to sub-minute resolution, and the date-math
   * range filter that queries these is minute-grained at its finest.
   */
  readonly SEEN_FORMAT = 'yyyy-MM-dd HH:mm';

  @Input()
  query!: SqlProfile;

  constructor() { }

  ngOnInit() {
  }

}
