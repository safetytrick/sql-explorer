import {Component, OnInit} from '@angular/core';
import {DatePipe} from '@angular/common';
import {Title} from '@angular/platform-browser';
import {CoverageService} from '../coverage.service';
import {SEEN_FORMAT} from '../seen-format';
import type {CoverageReport, CoverageSignal} from '../api-types';

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
@Component({
  selector: 'app-coverage-lens',
  templateUrl: './coverage-lens.component.html',
  styleUrls: ['./coverage-lens.component.scss'],
  imports: [DatePipe]
})
export class CoverageLensComponent implements OnInit {

  report: CoverageReport | null = null;
  loading = true;
  failed = false;
  protected readonly SEEN_FORMAT = SEEN_FORMAT;

  constructor(private coverageService: CoverageService, titleService: Title) {
    titleService.setTitle('::: what was captured :::');
  }

  ngOnInit() {
    this.coverageService.getCoverage().subscribe({
      next: report => { this.report = report; this.loading = false; },
      // an empty page would read as "nothing is wrong", which is the one
      // conclusion a failed request cannot support
      error: () => { this.failed = true; this.loading = false; }
    });
  }

  label(signal: CoverageSignal) {
    return SIGNAL_LABELS[signal];
  }

  /** Lowercase so it reads as a word in the chip rather than a shout. */
  verdictText(verdict: string): string {
    return verdict === 'OK' ? 'fine' : verdict === 'IMPAIRED' ? 'incomplete' : 'unknown';
  }
}
