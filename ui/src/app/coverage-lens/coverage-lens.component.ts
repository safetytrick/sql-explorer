import {Component, OnDestroy, OnInit, Optional} from '@angular/core';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {Subscription} from 'rxjs';
import {DatePipe} from '@angular/common';
import {CoverageService, groupByCluster, showsClusterHeading, usedVariant} from '../coverage.service';
import {EnvironmentService} from '../environment.service';
import {SEEN_FORMAT} from '../seen-format';
import type {CoverageReport, CoverageSignal, SourceCoverage} from '../api-types';

/** What each signal is called on screen, and what question it answers. */
const SIGNAL_LABELS: Record<CoverageSignal, {title: string; question: string}> = {
  DIGEST_CAPACITY: {
    title: 'Room to record',
    question: 'Did the server run out of space and start discarding statements?'
  },
  DIGEST_TRUNCATION: {
    title: 'Statement length',
    question: 'Were statements too long to be recorded in full?'
  },
  OBSERVATION_WINDOW: {
    title: 'Period observed',
    question: 'How long a stretch of traffic does this actually cover?'
  },
  PROVENANCE: {
    title: 'Where it came from',
    question: 'How many hands did these rows pass through before reaching here?'
  },
  SAMPLING_GAPS: {
    title: 'Continuity',
    question: 'Was anything happening while nothing was watching?'
  },
  ARCHIVED_HISTORY: {
    title: 'Earlier history',
    question: 'Are older captures on file that this corpus leaves out?'
  },
  UNREACHABLE: {
    title: 'Not read',
    question: 'Could sql-explorer read this server at all?'
  },
  PENDING_ANALYSIS: {
    title: 'Analysed',
    question: 'Is any statement waiting for a schema that could be confirmed current?'
  }
};

/**
 * What the loaded corpus can honestly support.
 *
 * <p>Exists because an absence is ambiguous. "No query touches this table" is
 * a claim about the capture as much as about the table, and until this page
 * there was nowhere to go and find out which. Each finding arrives from the
 * server with its own explanation attached, so this renders the sentence it
 * was given rather than composing its own — the number and its meaning cannot
 * drift apart if the view never separates them (sqlx-n01).
 */
/** One check across every source: how many each verdict covers, and which sources are not fine. */
export interface FindingSummary {
  signal: CoverageSignal;
  ok: number;
  impaired: number;
  unknown: number;
  problems: string[];
}

/**
 * The findings, one row per check rather than one per source (sqlx-om1.5).
 * A cluster of fifteen sources reported the same few checks fifteen times;
 * the summary states each check once, counts its verdicts, and names only
 * the sources where it is not fine. Ordered worst first, then as reported.
 */
export function summariseFindings(report: CoverageReport | null): FindingSummary[] {
  const bySignal = new Map<CoverageSignal, FindingSummary>();
  for (const source of report?.sources ?? []) {
    for (const finding of source.findings) {
      const row = bySignal.get(finding.signal) ?? {signal: finding.signal, ok: 0, impaired: 0, unknown: 0, problems: []};
      if (finding.verdict === 'OK') { row.ok++; } else if (finding.verdict === 'IMPAIRED') { row.impaired++; } else { row.unknown++; }
      if (finding.verdict !== 'OK') { row.problems.push(source.sourceRef ?? 'unnamed source'); }
      bySignal.set(finding.signal, row);
    }
  }
  const rank = (row: FindingSummary) => row.impaired ? 0 : row.unknown ? 1 : 2;
  return [...bySignal.values()].sort((a, b) => rank(a) - rank(b));
}

@Component({
  selector: 'app-coverage-lens',
  templateUrl: './coverage-lens.component.html',
  styleUrls: ['./coverage-lens.component.scss'],
  imports: [DatePipe, RouterLink]
})
export class CoverageLensComponent implements OnInit, OnDestroy {

  /** Every check, once (sqlx-om1.5). */
  summary: FindingSummary[] = [];
  /**
   * Whether a source card shows its passed checks in full. Off: they fold into
   * one line, so a card shows what needs attention. `var-passed=1`.
   */
  showPassed = false;

  togglePassed() {
    this.router?.navigate([], { relativeTo: this.route ?? undefined, queryParams: {'var-passed': this.showPassed ? null : '1'},
      queryParamsHandling: 'merge', replaceUrl: true });
  }

  /** A source's findings that need attention, and the ones that passed. */
  problemFindings(source: SourceCoverage) {
    return source.findings.filter(finding => finding.verdict !== 'OK');
  }

  passedFindings(source: SourceCoverage) {
    return source.findings.filter(finding => finding.verdict === 'OK');
  }

  passedTitles(source: SourceCoverage): string {
    return this.passedFindings(source).map(finding => this.label(finding.signal).title).join(', ');
  }

  report: CoverageReport | null = null;
  loading = true;
  failed = false;
  protected readonly SEEN_FORMAT = SEEN_FORMAT;

  private readonly subscriptions = new Subscription();

  constructor(private coverageService: CoverageService,
              private environmentService: EnvironmentService,
              @Optional() private route?: ActivatedRoute,
              @Optional() private router?: Router) {
  }

  ngOnInit() {
    this.subscriptions.add(this.route?.queryParamMap.subscribe(params => this.showPassed = params.get('var-passed') === '1')
      ?? new Subscription());
    // Coverage is per environment; showing the previous one's findings under
    // the new one's name is the single most misleading thing this page could do.
    this.subscriptions.add(this.environmentService.current$.subscribe(() => this.load()));
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
  }

  private load() {
    this.loading = true;
    this.failed = false;
    this.report = null;
    this.coverageService.getCoverage().subscribe({
      next: report => { this.report = report; this.summary = summariseFindings(report); this.loading = false; },
      // an empty page would read as "nothing is wrong", which is the one
      // conclusion a failed request cannot support
      error: () => { this.failed = true; this.loading = false; }
    });
  }

  /** Sources grouped under their cluster, so a shard's primary and replicas read as one. */
  groups() {
    return groupByCluster(this.report);
  }

  protected readonly showsClusterHeading = showsClusterHeading;
  protected readonly usedVariant = usedVariant;

  /**
   * Falls back to the signal's own name. The server adds signals before this
   * table learns them — ARCHIVED_HISTORY shipped without a label and broke
   * the page for every loaded source — and a raw name is better than nothing.
   */
  label(signal: CoverageSignal) {
    return SIGNAL_LABELS[signal] ?? {title: signal, question: ''};
  }

  /** Lowercase so it reads as a word in the chip rather than a shout. */
  verdictText(verdict: string): string {
    return verdict === 'OK' ? 'fine' : verdict === 'IMPAIRED' ? 'incomplete' : 'unknown';
  }
}
