import {Component, DestroyRef, Input, OnChanges, OnInit, inject} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {ActivatedRoute, Router} from '@angular/router';
import {combineLatest} from 'rxjs';
import {EnvironmentService, EnvironmentSummary, ServerSummary} from '../environment.service';
import {Preflight} from './ddl-preflight';
import {
  LOCK_HOLDERS_ENDPOINT, LockHolder, LockHolderReport, LockHolderServer, OLD_SECONDS, SERVERS_PARAM, TableRef,
  decodeServers, defaultServers, encodeServers, fmtAge, holderAge, holderTables, oldestHolder
} from './lock-holders';

/**
 * Who holds a metadata lock right now on the tables a change would lock, read
 * from the servers the user chooses (sqlx-9gk).
 *
 * <p>NOTHING HERE CONNECTS TO A SERVER UNTIL THE BUTTON IS PRESSED. Opening the
 * page, or a link carrying a selection, only restores the selection. The server
 * list comes from `/environments`, which the nav has already loaded and which
 * reads nothing from any database server.
 */
@Component({
  selector: 'app-lock-holders',
  templateUrl: './lock-holders.component.html',
  styleUrls: ['./ddl-preflight.component.scss'],
})
export class LockHoldersComponent implements OnInit, OnChanges {
  @Input() result: Preflight | null = null;

  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly environments = inject(EnvironmentService);
  private readonly destroyRef = inject(DestroyRef);

  readonly fmtAge = fmtAge;
  readonly holderAge = holderAge;
  readonly oldestHolder = oldestHolder;
  readonly OLD_SECONDS = OLD_SECONDS;

  tables: TableRef[] = [];
  servers: ServerSummary[] = [];
  /** Shown as group headings only when there is more than one cluster. */
  clusters: string[] = [];
  selected: string[] = [];
  cached = false;
  environment: string | null = null;

  loading = false;
  error: string | null = null;
  report: LockHolderReport | null = null;
  private tablesKey = '';

  ngOnInit() {
    combineLatest([this.environments.effective(), this.environments.known$]).pipe(
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(([name, list]) => {
      const env = list.environments.find(e => e.name === name) ?? null;
      this.useEnvironment(env);
    });
  }

  ngOnChanges() {
    const tables = holderTables(this.result);
    const key = tables.map(t => t.schema + '.' + t.table).join(',');
    if (key !== this.tablesKey) {
      // a different change: an answer about other tables would mislead
      this.tablesKey = key;
      this.tables = tables;
      this.report = null;
      this.error = null;
    }
  }

  private useEnvironment(env: EnvironmentSummary | null) {
    if (env?.name !== this.environment) {
      this.report = null;
      this.error = null;
    }
    this.environment = env?.name ?? null;
    this.servers = env?.servers ?? [];
    this.cached = !!env?.cached;
    const clusters = [...new Set(this.servers.map(s => s.cluster ?? s.name))];
    this.clusters = clusters.length > 1 ? clusters : [];
    const fromUrl = decodeServers(this.route.snapshot.queryParamMap.get(SERVERS_PARAM), this.servers);
    this.selected = fromUrl ?? defaultServers(this.servers);
  }

  serversIn(cluster: string): ServerSummary[] {
    return this.servers.filter(s => (s.cluster ?? s.name) === cluster);
  }

  isSelected(name: string): boolean {
    return this.selected.includes(name);
  }

  toggle(name: string) {
    this.selected = this.isSelected(name) ? this.selected.filter(s => s !== name) : [...this.selected, name];
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {[SERVERS_PARAM]: encodeServers(this.selected, this.servers)},
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  roleLabel(s: ServerSummary): string {
    return (s.roles ?? []).filter(r => r !== 'sampling').join(', ');
  }

  get canCheck(): boolean {
    return !this.loading && !this.cached && this.tables.length > 0 && this.selected.length > 0;
  }

  /** The only place a request that reaches database servers is sent. */
  check() {
    if (!this.canCheck) { return; }
    this.loading = true;
    this.error = null;
    const servers = this.servers.map(s => s.name).filter(name => this.selected.includes(name));
    this.http.post<LockHolderReport>(LOCK_HOLDERS_ENDPOINT, {tables: this.tables, servers}).pipe(
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: report => { this.report = report; this.loading = false; },
      error: (e: HttpErrorResponse) => {
        this.error = (typeof e.error === 'object' && e.error?.message) || e.message || 'the check failed';
        this.loading = false;
      },
    });
  }

  isOld(h: LockHolder): boolean {
    return (holderAge(h) ?? 0) >= OLD_SECONDS;
  }

  verdict(s: LockHolderServer): string {
    if (s.error) { return 'not read'; }
    const oldest = oldestHolder(s);
    if (!oldest) { return 'no other session holds a lock on these tables'; }
    const age = holderAge(oldest);
    const what = oldest.transactionSeconds !== null ? 'oldest open transaction' : 'longest in its state';
    return `${what}: ${fmtAge(age)}, on ${oldest.schema}.${oldest.table}`;
  }

  verdictClass(s: LockHolderServer): string {
    if (s.error) { return 'unknown'; }
    const oldest = oldestHolder(s);
    if (!oldest) { return 'clear'; }
    return this.isOld(oldest) ? 'busy' : 'clear';
  }
}
