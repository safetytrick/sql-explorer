import {Component, OnDestroy, OnInit} from '@angular/core';
import {Subscription} from 'rxjs';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {DatePipe} from '@angular/common';
import {Title} from '@angular/platform-browser';
import {SchemaLensService} from '../schema-lens.service';
import {CoverageService, findings, sharedWindow, worstVerdict} from '../coverage.service';
import {EnvironmentService} from '../environment.service';
import {SEEN_FORMAT} from '../seen-format';
import type {CoverageVerdict, ObservedWindow} from '../api-types';
import {SchemaUsage, totals, unqueriedBySchema} from './unqueried-tables';

/** Include MySQL's own schemas. Off by default; they are 295 of the 382. */
const SYSTEM_PARAM = 'var-system';

/**
 * Tables the schema describes that no analyzed query touches.
 *
 * <p>Grouped by schema rather than listed flat, because the grouping is the
 * finding. Measured on the regression corpus: of 87 unqueried tables outside
 * MySQL's own schemas, `app_iam` contributes 14 of its 14, `app_gdpr` 5 of 5 and
 * `app_temporal` 29 of 32 — those are schemas with no captured traffic, not
 * schemas full of dead tables. `del` contributing 5 of 30 is the actionable
 * case. A flat list would read as 87 deletion candidates and about half of
 * them would be wrong.
 */
@Component({
  selector: 'app-unqueried-tables',
  templateUrl: './unqueried-tables.component.html',
  styleUrls: ['./unqueried-tables.component.scss'],
  imports: [RouterLink, DatePipe]
})
export class UnqueriedTablesComponent implements OnInit, OnDestroy {

  all: SchemaUsage[] = [];
  loading = true;
  failed = false;
  includeSystem = false;

  /**
   * What the capture can actually support, in place of the hedge this page
   * used to carry. "Either nothing uses the schema, or its traffic was never
   * recorded" named two possibilities and could not tell them apart; these
   * two facts are how it tells them apart (sqlx-n01).
   */
  window: ObservedWindow | null = null;
  capacity: CoverageVerdict | null = null;
  protected readonly SEEN_FORMAT = SEEN_FORMAT;

  private readonly subscriptions = new Subscription();

  constructor(private schemaLensService: SchemaLensService,
              private coverageService: CoverageService,
              private environmentService: EnvironmentService,
              private route: ActivatedRoute,
              private router: Router,
              titleService: Title) {
    titleService.setTitle('::: unqueried tables :::');
  }

  ngOnInit() {
    this.subscriptions.add(this.route.queryParamMap.subscribe(params => {
      this.includeSystem = params.get(SYSTEM_PARAM) === '1';
    }));
    // Both halves belong to the selected environment, so both are re-read when
    // it changes. Switching without this would leave one environment's tables
    // qualified by another's capture, which is worse than saying nothing.
    this.subscriptions.add(this.environmentService.current$.subscribe(() => this.load()));
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
  }

  private load() {
    this.loading = true;
    this.failed = false;
    this.schemaLensService.getSchemaTree().subscribe({
      next: tree => { this.all = unqueriedBySchema(tree); this.loading = false; },
      // an empty list would read as "nothing is unqueried", which is a strong
      // and probably wrong claim to make out of a failed request
      error: () => { this.failed = true; this.loading = false; }
    });
    // A failure here leaves both null and the note simply says less. Coverage
    // qualifies this page's claim; it must never be able to break it.
    this.window = null;
    this.capacity = null;
    this.coverageService.getCoverage().subscribe({
      next: report => {
        this.window = sharedWindow(report);
        this.capacity = worstVerdict(findings(report, 'DIGEST_CAPACITY'));
      },
      error: () => { this.window = null; this.capacity = null; }
    });
  }

  get rows(): SchemaUsage[] {
    return this.includeSystem ? this.all : this.all.filter(usage => !usage.system);
  }

  get counts() {
    return totals(this.rows);
  }

  get systemSchemaCount(): number {
    return this.all.filter(usage => usage.system).length;
  }

  setIncludeSystem(include: boolean) {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {[SYSTEM_PARAM]: include ? '1' : null},
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }
}
