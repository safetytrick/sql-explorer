import {DatePipe} from '@angular/common';
import {CoverageService, findings, sharedWindow, worstVerdict} from '../coverage.service';
import type {ObservedWindow} from '../api-types';
import {SEEN_FORMAT} from '../seen-format';
import {Component, OnDestroy, OnInit} from '@angular/core';
import {Subscription} from 'rxjs';
import {RouterLink} from '@angular/router';
import {EnvironmentService} from '../environment.service';
import {IndexConnection, UnusedIndexReport, UnusedIndexService} from './unused-indexes.service';

/**
 * Indexes no connection in the environment has read.
 *
 * <p>THE ONLY PAGE HERE THAT ENDS IN A DELETION. Every other view leads a
 * reader to a query; this one leads to `DROP INDEX`. So the qualifications are
 * not a footnote below the list — the list is not shown at all until what backs
 * it has been read (sqlx-vjr).
 */
@Component({
  selector: 'app-unused-indexes',
  templateUrl: './unused-indexes.component.html',
  styleUrls: ['./unused-indexes.component.scss'],
  imports: [RouterLink, DatePipe]
})
export class UnusedIndexesComponent implements OnInit, OnDestroy {

  report: UnusedIndexReport | null = null;
  loading = true;
  failed = false;

  private readonly subscriptions = new Subscription();

  /**
   * A one-line coverage summary under the header (sqlx-1bo.4): the window the
   * capture observed and whether Performance Schema kept every digest, with a
   * link to Captured. From the cached coverage report, so it shows while the
   * per-connection read is still running; a failure just leaves it out.
   */
  window: ObservedWindow | null = null;
  capacity: 'OK' | 'IMPAIRED' | 'UNKNOWN' | null = null;
  protected readonly SEEN_FORMAT = SEEN_FORMAT;

  constructor(private service: UnusedIndexService,
              private environmentService: EnvironmentService,
              private coverageService?: CoverageService) {
  }

  private loadCoverage() {
    this.window = null;
    this.capacity = null;
    this.coverageService?.getCoverage().subscribe({
      next: report => {
        this.window = sharedWindow(report);
        this.capacity = worstVerdict(findings(report, 'DIGEST_CAPACITY'));
      },
      error: () => undefined
    });
  }

  ngOnInit() {
    // Live counters read per connection, so this belongs to the selected
    // environment as much as any corpus-backed page does.
    this.subscriptions.add(this.environmentService.current$.subscribe(() => {
      this.loadCoverage();
      this.load();
    }));
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
  }

  private load() {
    this.loading = true;
    this.failed = false;
    this.service.getReport().subscribe({
      next: report => { this.report = report; this.loading = false; },
      // An empty list would read as "nothing is unused", which is a strong claim
      // to make out of a failed request — and here a wrong one is expensive.
      error: () => { this.failed = true; this.loading = false; }
    });
  }

  /** Days a connection has been up, or null when it did not answer. */
  daysUp(connection: IndexConnection): number | null {
    return connection.upSeconds === null ? null : Math.floor(connection.upSeconds / 86400);
  }

  get connectionsRead(): number {
    return (this.report?.connections ?? []).filter(c => c.verdict !== 'UNKNOWN').length;
  }
}
