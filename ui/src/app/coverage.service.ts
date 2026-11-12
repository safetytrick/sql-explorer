import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable, shareReplay} from 'rxjs';
import {EnvironmentService} from './environment.service';
import type {
  ClusterSchemaStatus, CoverageFinding, CoverageReport, CoverageSignal, ObservedWindow, SourceCoverage, TableDrift
} from './api-types';

const endpoint = '/api/sql-explorer/';

/**
 * What the loaded corpus can honestly support.
 *
 * <p>`shareReplay(1)` because coverage describes the load, not the request:
 * several views ask the same question on the same page and the answer does not
 * change between them.
 *
 * <p>It does change when the environment does, and that is the one way this
 * cache could actively misinform. `/coverage` is resolved from the
 * environment in its path (`/env/{env}/coverage`), and switching environments does not
 * reload the page — components resubscribe instead — so a cache held for the
 * session would keep asserting the previous environment's window on a page
 * whose entire purpose is to stop people trusting the wrong claim. Dropped on
 * every switch.
 */
@Injectable({providedIn: 'root'})
export class CoverageService {

  private report$?: Observable<CoverageReport>;

  constructor(private http: HttpClient, environmentService: EnvironmentService) {
    environmentService.current$.subscribe(() => this.report$ = undefined);
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

/** Sources under the cluster that holds their server; `cluster` is null for sources outside every cluster. */
export interface SourceGroup {
  cluster: ClusterSchemaStatus | null;
  sources: SourceCoverage[];
}

/**
 * The report's sources grouped by cluster, in cluster order, with anything no
 * cluster claims — an upload, a file — last.
 *
 * <p>A source's sourceRef is its server's name, and names are unique across an
 * environment, so membership is a lookup rather than a guess.
 */
export function groupByCluster(report: CoverageReport | null): SourceGroup[] {
  const sources = report?.sources ?? [];
  const claimed = new Set<SourceCoverage>();
  const groups: SourceGroup[] = (report?.clusters ?? []).map(cluster => {
    const members = sources.filter(source => source.sourceRef !== null && cluster.servers.includes(source.sourceRef));
    members.forEach(member => claimed.add(member));
    return {cluster, sources: members};
  });
  const rest = sources.filter(source => !claimed.has(source));
  return rest.length ? [...groups, {cluster: null, sources: rest}] : groups;
}

/** Whether a cluster earns a heading: more than one server, or a schema that did not come from its first choice. */
export function showsClusterHeading(cluster: ClusterSchemaStatus | null): boolean {
  return !!cluster && (cluster.servers.length > 1 || cluster.fallback || cluster.failure !== null || !cluster.current);
}

/**
 * The definition lookups use for a drifted table: the first variant, in
 * cluster order, whose clusters have the table — not simply the first, which
 * may be the clusters that lack it.
 */
export function usedVariant(drift: TableDrift): TableDrift['variants'][number] | undefined {
  return drift.variants.find(variant => variant.present);
}
