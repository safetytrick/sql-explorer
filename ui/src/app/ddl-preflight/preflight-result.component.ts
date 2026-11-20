import {Component, Input, OnChanges} from '@angular/core';
import {RouterLink} from '@angular/router';
import {DecimalPipe} from '@angular/common';
import {SqlHighlightDirective} from '../sql-highlight/sql-highlight.directive';
import {formatBytes, formatRowsEstimate} from '../table-size';
import {fmtPerDay, fmtTime} from '../table-workload/table-workload.service';
import {LockHoldersComponent} from './lock-holders.component';
import {algorithmLabel, choiceOutcomeLabel, Preflight, PreflightCheck, sortChecks} from './ddl-preflight';

/**
 * One pre-flight verdict: the predicted algorithm, the table's facts, the
 * recommendation, the checks worst first, each clause, and what the snapshot
 * cannot know. Shared by Admin → DDL pre-flight and a table's change page.
 */
@Component({
  selector: 'app-preflight-result',
  templateUrl: './preflight-result.component.html',
  styleUrls: ['./ddl-preflight.component.scss'],
  imports: [RouterLink, DecimalPipe, SqlHighlightDirective, LockHoldersComponent]
})
export class PreflightResultComponent implements OnChanges {
  @Input() result: Preflight | null = null;
  /** The table's own page links back to itself pointlessly; it turns the link off. */
  @Input() linkTable = true;

  readonly algorithmLabel = algorithmLabel;
  readonly choiceOutcomeLabel = choiceOutcomeLabel;
  readonly formatBytes = formatBytes;
  readonly formatRowsEstimate = formatRowsEstimate;
  readonly fmtPerDay = fmtPerDay;
  readonly fmtTime = fmtTime;

  checks: PreflightCheck[] = [];

  ngOnChanges() {
    this.checks = this.result ? sortChecks(this.result.checks) : [];
  }
}
