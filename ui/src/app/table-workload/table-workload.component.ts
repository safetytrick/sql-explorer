import {Component, Input, OnChanges} from '@angular/core';
import {RouterLink} from '@angular/router';
import {GLOSSARY} from '../glossary';
import {fmtPerDay, fmtTime, fmtWindow, LockRisk, TableWorkloadService, WriteRate} from './table-workload.service';

/** Statement text shown in a row; the whole of it is the row's hover text. */
const SNIPPET = 110;

/**
 * Two cards for planning a migration on one table: how often it is written
 * (sqlx-n5j.10) and which statements a DDL would wait behind (sqlx-n5j.9).
 *
 * <p>Its own component, fetching its own data, so the table lens only has to
 * place it. Both answers are zeros for a table nothing touches, never an
 * error, so it is safe on every lens page.
 *
 * <p>Holds no view state: both lists are short (lock risk is cut at 20 by the
 * server) and always shown, so there is nothing here the URL would need.
 */
@Component({
  selector: 'app-table-workload',
  templateUrl: './table-workload.component.html',
  styleUrls: ['./table-workload.component.scss'],
  imports: [RouterLink]
})
export class TableWorkloadComponent implements OnChanges {
  @Input({required: true}) schema!: string;
  @Input({required: true}) table!: string;

  readonly glossary = GLOSSARY;
  protected readonly fmtPerDay = fmtPerDay;
  protected readonly fmtTime = fmtTime;
  protected readonly fmtWindow = fmtWindow;

  writeRate: WriteRate | null = null;
  lockRisk: LockRisk | null = null;
  writeFailed = false;
  lockFailed = false;

  /** Which table the loaded answers are for, so a late response for the last table is dropped. */
  private loadedKey = '';

  constructor(private workloadService: TableWorkloadService) { }

  ngOnChanges() {
    const key = `${this.schema}.${this.table}`;
    if (key === this.loadedKey) { return; }
    this.loadedKey = key;
    this.writeRate = null;
    this.lockRisk = null;
    this.writeFailed = false;
    this.lockFailed = false;
    this.workloadService.getWriteRate(this.schema, this.table).subscribe({
      next: rate => { if (key === this.loadedKey) { this.writeRate = rate; } },
      error: () => { if (key === this.loadedKey) { this.writeFailed = true; } }
    });
    this.workloadService.getLockRisk(this.schema, this.table).subscribe({
      next: risk => { if (key === this.loadedKey) { this.lockRisk = risk; } },
      error: () => { if (key === this.loadedKey) { this.lockFailed = true; } }
    });
  }

  snippet(query: string | null | undefined): string {
    const flat = (query ?? '').replace(/\s+/g, ' ').trim();
    return flat.length > SNIPPET ? flat.slice(0, SNIPPET) + '…' : flat;
  }

  /** The long-statement threshold as words: 1000 → "1 s". */
  threshold(): string {
    return fmtTime(this.lockRisk?.longThresholdMs ?? 0);
  }

  isLong(maxTimeMs: number): boolean {
    return !!this.lockRisk && maxTimeMs >= this.lockRisk.longThresholdMs;
  }
}
