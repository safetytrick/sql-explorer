import {UserService} from '../user.service';
import {Component, DestroyRef, inject, OnInit} from '@angular/core';
import {DatePipe, DecimalPipe, AsyncPipe} from '@angular/common';
import {HttpClient, HttpErrorResponse, HttpParams} from '@angular/common/http';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {catchError, of, skip, Subscription, timer} from 'rxjs';
import {EnvironmentService} from '../environment.service';
import {SqlHighlightDirective} from '../sql-highlight/sql-highlight.directive';
import {
  bound, ColumnResult, decodeProfileParams, encodeProfileParams, findings, PROFILE_ENDPOINT, ProfileRequestPlan,
  ProfileUrlState, RunDetail, RunSummary, share
} from './profile';

/**
 * Column data profiles for one table (`/table/:schema/:name/profile`,
 * sqlx-n5j.3): the plan — every statement, the servers and the most rows
 * it can read — shown before anything runs; a run, on demand and confirmed;
 * what it found, column by column; and every run ever made.
 *
 * <p>Values never leave the server. What comes back is counts, row keys, and
 * the few characters a corruption signature matched.
 */
@Component({
  selector: 'app-table-profile',
  templateUrl: './table-profile.component.html',
  styleUrls: ['./table-profile.component.scss'],
  imports: [RouterLink, DatePipe, DecimalPipe, SqlHighlightDirective, AsyncPipe]
})
export class TableProfileComponent implements OnInit {
  protected readonly findings = findings;
  protected readonly share = share;
  protected readonly bound = bound;

  schema = '';
  table = '';
  state: ProfileUrlState = {run: null, server: null, column: null, scan: null};

  plan: ProfileRequestPlan | null = null;
  planError: string | null = null;
  confirming = false;
  starting = false;
  startError: string | null = null;

  runs: RunSummary[] = [];
  detail: RunDetail | null = null;
  /** Sampling servers of the environment, for a probe of one. */
  samplingServers: string[] = [];

  private polling: Subscription | null = null;

  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly environmentService = inject(EnvironmentService);
  /** Profiling samples the table's data on its servers: Runner and up (sqlx-e13.4). */
  readonly maySample$ = inject(UserService).can('env:sample');

  ngOnInit() {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
      this.schema = params.get('schema') ?? '';
      this.table = params.get('name') ?? '';
      this.state = decodeProfileParams(this.route.snapshot.queryParamMap);
      this.loadAll();
    });
    this.environmentService.current$.pipe(skip(1), takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.state = {...this.state, run: null, server: null};
      this.writeUrl();
      this.loadAll();
    });
    this.destroyRef.onDestroy(() => this.polling?.unsubscribe());
  }

  private loadAll() {
    this.loadServers();
    this.loadPlan();
    this.loadRuns();
  }

  private loadServers() {
    this.environmentService.getEnvironments().pipe(catchError(() => of(null))).subscribe(list => {
      const current = this.environmentService.getCurrent();
      const environment = list?.environments.find(e => e.name === current) ?? list?.environments[0];
      this.samplingServers = (environment?.servers ?? []).filter(s => s.roles?.includes('sampling')).map(s => s.name);
    });
  }

  private loadPlan() {
    this.confirming = false;
    this.startError = null;
    let params = new HttpParams();
    if (this.state.server) { params = params.set('server', this.state.server); }
    if (this.state.scan) { params = params.set('fullScan', this.state.scan); }
    this.http.get<ProfileRequestPlan>(`${PROFILE_ENDPOINT}table/${this.schema}/${this.table}/plan`, {params})
      .subscribe({
        next: plan => { this.plan = plan; this.planError = null; },
        error: (e: HttpErrorResponse) => { this.plan = null; this.planError = message(e, 'the plan could not be read'); }
      });
  }

  private loadRuns(select?: number) {
    this.http.get<RunSummary[]>(`${PROFILE_ENDPOINT}table/${this.schema}/${this.table}/runs`)
      .pipe(catchError(() => of([] as RunSummary[])))
      .subscribe(runs => {
        this.runs = runs;
        const id = select ?? this.state.run ?? runs.find(r => r.status !== 'running')?.id ?? runs[0]?.id;
        if (id) { this.loadDetail(id); } else { this.detail = null; }
      });
  }

  private loadDetail(id: number) {
    this.http.get<RunDetail>(`${PROFILE_ENDPOINT}run/${id}`).pipe(catchError(() => of(null))).subscribe(detail => {
      this.detail = detail;
      this.polling?.unsubscribe();
      if (detail?.run.status === 'running') {
        this.polling = timer(1000).subscribe(() => {
          if (this.detail?.run.id === id) {
            this.loadDetail(id);
          }
        });
      } else if (detail && this.runs.some(r => r.id === id && r.status === 'running')) {
        // it finished while shown: refresh the list's row
        this.http.get<RunSummary[]>(`${PROFILE_ENDPOINT}table/${this.schema}/${this.table}/runs`)
          .pipe(catchError(() => of(this.runs))).subscribe(runs => this.runs = runs);
      }
    });
  }

  // ── choosing what to run ──

  setServer(server: string) {
    this.state = {...this.state, server: server || null};
    this.writeUrl();
    this.loadPlan();
  }

  planFullScan(column: string) {
    this.state = {...this.state, scan: column};
    this.writeUrl();
    this.loadPlan();
    window.scrollTo?.({top: 0, behavior: 'smooth'});
  }

  backToSampling() {
    this.state = {...this.state, scan: null};
    this.writeUrl();
    this.loadPlan();
  }

  get availableTargets() {
    return this.plan?.targets.filter(t => t.available) ?? [];
  }

  get skippedTargets() {
    return this.plan?.targets.filter(t => !t.available) ?? [];
  }

  start() {
    if (!this.plan || this.starting) { return; }
    this.starting = true;
    this.startError = null;
    const body = {
      server: this.state.server, fullScan: this.state.scan, confirmed: true,
      columns: this.state.scan ? null : this.plan.columns.map(c => c.name)
    };
    this.http.post<RunSummary>(`${PROFILE_ENDPOINT}table/${this.schema}/${this.table}/runs`, body).subscribe({
      next: run => {
        this.starting = false;
        this.confirming = false;
        this.state = {...this.state, run: run.id, scan: null};
        this.writeUrl();
        this.loadPlan();
        this.loadRuns(run.id);
      },
      error: (e: HttpErrorResponse) => { this.starting = false; this.startError = message(e, 'the run could not start'); }
    });
  }

  cancel() {
    const id = this.detail?.run.id;
    if (!id) { return; }
    this.http.post<RunSummary>(`${PROFILE_ENDPOINT}run/${id}/cancel`, {}).pipe(catchError(() => of(null)))
      .subscribe(() => this.loadDetail(id));
  }

  // ── reading a run ──

  selectRun(id: number) {
    this.state = {...this.state, run: id};
    this.writeUrl();
    this.loadDetail(id);
  }

  toggleColumn(column: ColumnResult) {
    this.state = {...this.state, column: this.state.column === column.column ? null : column.column};
    this.writeUrl();
  }

  isOpen(column: ColumnResult): boolean {
    return this.state.column === column.column;
  }

  /** The servers that found something, for a column's row keys. */
  hasProblems(column: ColumnResult): boolean {
    return column.signatures.some(s => s.rows > 0 && !s.informational);
  }

  scopeLabel(run: RunSummary): string {
    return run.probeServer ? `probe ${run.probeServer}` : 'every cluster';
  }

  methodLabel(method: string | undefined): string {
    switch (method) {
      case 'WINDOWS': return 'sampled windows';
      case 'ENDS': return 'first and last rows';
      case 'FULL_SCAN': return 'full scan';
      default: return 'nothing to read';
    }
  }

  private writeUrl() {
    this.router.navigate([], {
      relativeTo: this.route, queryParams: encodeProfileParams(this.state), queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }
}

function message(error: HttpErrorResponse, fallback: string): string {
  const body = error?.error;
  return (body && typeof body === 'object' && 'message' in body && body.message) || (error.status ? fallback
    : 'the backend could not be reached');
}
