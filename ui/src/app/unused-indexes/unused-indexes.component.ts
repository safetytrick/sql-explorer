import {DatePipe} from '@angular/common';
import {CoverageService, findings, sharedWindow, worstVerdict} from '../coverage.service';
import type {ObservedWindow} from '../api-types';
import {SEEN_FORMAT} from '../seen-format';
import {ageOf} from '../environment-health';
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
  /** Fetching the last report, which connects to nothing. */
  loading = true;
  /** Reading the connections, which connects to every server (sqlx-1bo.2). */
  reading = false;
  failed = false;
  /** The environment the page is about, named in the prompt to read. */
  environment: string | null = null;

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
    this.subscriptions.add(this.environmentService.current$.subscribe(name => {
      this.environment = name;
      this.loadCoverage();
      this.load();
    }));
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
  }

  /** The last report, if any: no connection is opened to show it. */
  private load() {
    this.loading = true;
    this.failed = false;
    this.report = null;
    this.service.getLatest().subscribe({
      next: report => { this.report = report ?? null; this.loading = false; },
      // An empty list would read as "nothing is unused", which is a strong claim
      // to make out of a failed request — and here a wrong one is expensive.
      error: () => { this.failed = true; this.loading = false; }
    });
  }

  /** Connect to every server and read its index usage now, asked for rather than on arrival (sqlx-1bo.2). */
  readNow() {
    this.reading = true;
    this.failed = false;
    this.service.read().subscribe({
      next: report => { this.report = report; this.reading = false; },
      error: () => { this.failed = true; this.reading = false; }
    });
  }

  /** Days a connection has been up, or null when it did not answer. */
  daysUp(connection: IndexConnection): number | null {
    return connection.upSeconds === null ? null : Math.floor(connection.upSeconds / 86400);
  }

  /** How old the kept report is, coarsely: "40m", "6h", "3d". */
  get age(): string | null {
    return ageOf(this.report?.readAt);
  }

  /** How long the read took, readably: "850 ms", "4.2 s". */
  get took(): string | null {
    const millis = this.report?.readMillis;
    if (millis === undefined || millis === null) { return null; }
    return millis < 1000 ? `${millis} ms` : `${(millis / 1000).toFixed(1)} s`;
  }

  get connectionsRead(): number {
    return (this.report?.connections ?? []).filter(c => c.verdict !== 'UNKNOWN').length;
  }
}
