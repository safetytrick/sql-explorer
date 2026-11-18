import {Component, OnDestroy, OnInit} from '@angular/core';
import {ActivatedRoute, Params, Router, RouterLink} from '@angular/router';
import {DecimalPipe} from '@angular/common';
import {Subject, Subscription, switchMap} from 'rxjs';
import {TopologyService} from '../topology/topology.service';
import {SearchTagScopeService} from '../search-tag-scope.service';
import {buildSearchRequest, decodeSearchParams, encodeSearchParams} from '../search-url';
import {formatSeconds} from '../topology/topology';
import {GLOSSARY} from '../glossary';
import {
  compact, CompareBy, CompareColumn, CompareFlag, CompareReport, CompareRow, CompareSort, CompareState,
  decodeCompareParams, encodeCompareParams, FLAG_LABELS, FLAG_TERMS, FLAGS, formatMs, hotCells
} from './server-compare';

/**
 * The workbench's search compared across one cluster's servers, ranked by how
 * differently each query runs on them (sqlx-a9h.2). `view=servers`; the
 * filters on the left decide which queries are compared, this view's own
 * `var-` params which cluster and which differences show.
 */
@Component({
  selector: 'app-server-compare',
  standalone: true,
  imports: [RouterLink, DecimalPipe],
  templateUrl: './server-compare.component.html',
  styleUrl: './server-compare.component.scss'
})
export class ServerCompareComponent implements OnInit, OnDestroy {

  readonly FLAGS = FLAGS;
  readonly FLAG_LABELS = FLAG_LABELS;
  readonly glossary = GLOSSARY;
  readonly formatSeconds = formatSeconds;
  readonly formatMs = formatMs;
  readonly compact = compact;

  report: CompareReport | null = null;
  loading = false;
  failed = false;
  state: CompareState = decodeCompareParams({get: () => null});

  private readonly load$ = new Subject<void>();
  private readonly subscriptions = new Subscription();
  /** What was last fetched, so the workbench's own writes to the URL do not refetch. */
  private loadedKey: string | null = null;
  private hot = new WeakMap<CompareRow, Set<number>>();

  constructor(private topologyService: TopologyService,
              private searchTagScope: SearchTagScopeService,
              private route: ActivatedRoute,
              private router: Router) {
  }

  ngOnInit() {
    this.subscriptions.add(this.load$.pipe(switchMap(() => {
      const params = this.route.snapshot.queryParamMap;
      const search = decodeSearchParams(params);
      const state = decodeCompareParams(params);
      return this.searchTagScope.resolve(search.schemaTagFilter).pipe(switchMap(tagScope =>
        this.topologyService.compare(buildSearchRequest(search, tagScope, {limit: null, offset: null}), state)));
    })).subscribe({
      next: report => {
        this.report = report;
        this.hot = new WeakMap();
        this.loading = false;
        this.failed = false;
      },
      error: () => {
        this.loading = false;
        this.failed = true;
      }
    }));
    this.subscriptions.add(this.route.queryParamMap.subscribe(() => this.applyRoute()));
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
  }

  private applyRoute() {
    const params = this.route.snapshot.queryParamMap;
    this.state = decodeCompareParams(params);
    const key = JSON.stringify([encodeSearchParams(decodeSearchParams(params)), encodeCompareParams(this.state)]);
    if (key === this.loadedKey) { return; }
    this.loadedKey = key;
    this.loading = true;
    this.load$.next();
  }

  private update(change: Partial<CompareState>) {
    const next = {...this.state, ...change};
    this.router.navigate([], {relativeTo: this.route, queryParams: encodeCompareParams(next),
      queryParamsHandling: 'merge', replaceUrl: true});
  }

  setCluster(cluster: string) {
    // the busiest is the default, so naming it is still worth keeping: a shared link keeps its cluster
    this.update({cluster});
  }

  setBy(by: CompareBy) { this.update({by}); }

  setSort(sort: CompareSort) { this.update({sort}); }

  toggleAll() { this.update({all: !this.state.all}); }

  /** One flag on or off the filter; with none on, every flagged row shows. */
  toggleFlag(flag: CompareFlag) {
    const flags = this.state.flags.includes(flag)
      ? this.state.flags.filter(f => f !== flag)
      : [...this.state.flags, flag];
    this.update({flags});
  }

  flagTitle(flag: CompareFlag): string {
    return GLOSSARY[FLAG_TERMS[flag]];
  }

  isHot(row: CompareRow, index: number): boolean {
    let hot = this.hot.get(row);
    if (!hot) {
      hot = hotCells(row, this.report?.servers ?? []);
      this.hot.set(row, hot);
    }
    return hot.has(index);
  }

  /** A cell's share of its row's time per day, for the bar under it. */
  share(row: CompareRow, index: number): number {
    const cell = row.cells[index];
    return cell && row.secondsPerDay > 0 ? cell.secondsPerDay / row.secondsPerDay : 0;
  }

  /** The workbench with this search, read from one server only. */
  serverQueries(server: CompareColumn): Params {
    const search = encodeSearchParams(decodeSearchParams(this.route.snapshot.queryParamMap));
    return {...search, sources: server.name};
  }

  /** A table row opens the same comparison by query, narrowed to that table. */
  tableQueries(row: CompareRow): Params {
    const params = this.route.snapshot.queryParamMap;
    const search = encodeSearchParams(decodeSearchParams(params));
    return {...search, tables: row.key, view: 'servers', ...encodeCompareParams({...this.state, by: 'QUERY'})};
  }

  windowTitle(server: CompareColumn): string {
    if (!server.read) { return 'nothing was loaded from this server'; }
    return server.windowDays == null ? 'its counters\' window is unknown'
      : `its counters cover ${server.windowDays.toFixed(1)} days; per-day figures divide by that`;
  }

  cellTitle(server: CompareColumn, row: CompareRow, index: number): string {
    const cell = row.cells[index];
    if (!cell) { return `did not run on ${server.name}`; }
    return `${server.name}: ${cell.executions.toLocaleString()} executions, ${formatSeconds(cell.seconds)} in all; `
      + `${compact(cell.executionsPerDay)} a day, ${compact(cell.rowsExaminedPerExecution)} rows examined and `
      + `${formatMs(cell.msPerExecution)} an execution`
      + (cell.rowsAffected ? `, ${cell.rowsAffected.toLocaleString()} rows changed` : '');
  }
}
