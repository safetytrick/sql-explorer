import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable, shareReplay} from 'rxjs';
import type {CoverageFinding, CoverageReport, CoverageSignal, ObservedWindow} from './api-types';

const endpoint = '/api/sql-explorer/';

/**
 * What the loaded corpus can honestly support.
 *
 * <p>`shareReplay(1)` because coverage describes the load, not the request:
 * several views ask the same question on the same page, and the answer does
 * not change until an environment is reloaded — at which point the app
 * navigates anyway.
 */
@Injectable({providedIn: 'root'})
export class CoverageService {

  private report$?: Observable<CoverageReport>;

  constructor(private http: HttpClient) {
  }

  getCoverage(): Observable<CoverageReport> {
    if (!this.report$) {
      this.report$ = this.http.get<CoverageReport>(endpoint + 'coverage').pipe(shareReplay(1));
    }
    return this.report$;
  }
}

/**
 * The window shared by every source, or null when they disagree.
 *
 * <p>One environment can hold several sources, and a sentence like "across 118
 * days" is only true if they all say so. Two sources with different windows
 * have no single answer, and inventing one — the widest, the newest — would be
 * the confident wrong answer the whole API exists to avoid.
 */
export function sharedWindow(report: CoverageReport | null): ObservedWindow | null {
  const windows = (report?.sources ?? []).map(source => source.window);
  if (!windows.length || windows.some(window => !window)) {
    return null;
  }
  const first = windows[0]!;
  return windows.every(w => w!.from === first.from && w!.to === first.to) ? first : null;
}

/** Every source's finding for one signal, in source order. */
export function findings(report: CoverageReport | null, signal: CoverageSignal): CoverageFinding[] {
  return (report?.sources ?? [])
    .map(source => source.findings.find(finding => finding.signal === signal))
    .filter((finding): finding is CoverageFinding => !!finding);
}

/**
 * The verdict for a signal across every source: the worst one.
 *
 * <p>IMPAIRED beats UNKNOWN beats OK. One source missing statements makes the
 * corpus incomplete no matter how healthy the others are, so the strongest
 * claim an environment can make is the weakest claim its sources can.
 */
export function worstVerdict(found: CoverageFinding[]): 'OK' | 'IMPAIRED' | 'UNKNOWN' | null {
  if (!found.length) {
    return null;
  }
  if (found.some(f => f.verdict === 'IMPAIRED')) {
    return 'IMPAIRED';
  }
  return found.some(f => f.verdict === 'UNKNOWN') ? 'UNKNOWN' : 'OK';
}
