import {Component, inject, Input, OnChanges, OnInit} from '@angular/core';
import {DatePipe, DecimalPipe} from '@angular/common';
import {catchError, of} from 'rxjs';
import {TableStorageStats} from '../api-types';
import {EnvironmentService, ServerRole} from '../environment.service';
import {GLOSSARY} from '../glossary';
import {formatBytes, formatRowsEstimate} from '../table-size';
import {oneValue, StorageSummary, summarizeStorage} from './storage-summary';

/**
 * A table's size and row estimate from information_schema.TABLES as of the
 * last schema load or pull (sqlx-n5j.2): one summary line per figure, totalled
 * over the primaries, with every server's own figures folded away below it.
 * Served from the schema snapshot when the environment is cached, so it can be
 * days old: the read time is always shown.
 */
@Component({
  selector: 'app-table-storage-card',
  templateUrl: './table-storage-card.component.html',
  styleUrls: ['./table-storage-card.component.scss'],
  imports: [DatePipe, DecimalPipe]
})
export class TableStorageCardComponent implements OnInit, OnChanges {
  /** One entry per server reporting the table; null or empty when none has. */
  @Input() stats: TableStorageStats[] | null | undefined = null;
  /**
   * Server name → its configured roles. Read from the current environment
   * when not given; until then every server counts as a primary.
   */
  @Input() roles: Record<string, ServerRole[] | undefined> | null = null;

  readonly glossary = GLOSSARY;
  protected readonly formatBytes = formatBytes;
  protected readonly formatRowsEstimate = formatRowsEstimate;
  protected readonly oneValue = oneValue;

  summary: StorageSummary = summarizeStorage(null);

  private readonly environmentService = inject(EnvironmentService, {optional: true});
  private fetchedRoles: Record<string, ServerRole[] | undefined> = {};

  ngOnInit() {
    if (this.roles || !this.environmentService) { return; }
    this.environmentService.getEnvironments().pipe(catchError(() => of(null))).subscribe(list => {
      const current = this.environmentService?.getCurrent();
      const environment = list?.environments.find(e => e.name === current) ?? list?.environments[0];
      this.fetchedRoles = Object.fromEntries((environment?.servers ?? []).map(s => [s.name, s.roles]));
      this.summarize();
    });
  }

  ngOnChanges() {
    this.summarize();
  }

  get rows(): TableStorageStats[] {
    return this.stats ?? [];
  }

  private summarize() {
    this.summary = summarizeStorage(this.stats, this.roles ?? this.fetchedRoles);
  }

  isExcluded(stats: TableStorageStats): boolean {
    return this.summary.excluded.includes(stats);
  }
}
