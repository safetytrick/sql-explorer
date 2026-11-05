import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, tap } from 'rxjs';
import { INITIAL_SEARCH, envFromSearch } from './environment-url';

const endpoint = '/api/sql-explorer/';
const STORAGE_KEY = 'sql-explorer.environment';

export interface ServerSummary {
  name: string;
  schemaLoaded: boolean;
}

export interface SqlImportStats {
  count: number;
  merged: number;
  notMerged: number;
  analysisFailed: number;
  analysisSkipped: number;
  truncated: number;
  dml: number;
  /** Rows whose earlier analysis was reused, with no parsing. */
  reused: number;
  /** New rows left unanalysed because their cluster's schema could not be confirmed current. */
  pending: number;
}

export interface SourcesSummary {
  loaded: boolean;
  sourceCount: number;
  loadedOn: string | null;
  importStats: SqlImportStats | null;
}

/** What an environment is served from and anything wrong with it (sqlx-nbi.8). */
export type EnvironmentState = 'LIVE' | 'CACHED' | 'PULLING' | 'STALE' | 'PARTIAL' | 'UNAVAILABLE';

export interface EnvironmentHealth {
  state: EnvironmentState;
  /** The earliest capture it is served from. */
  oldestCapture: string | null;
  maxAgeMinutes: number;
  /** One plain sentence per problem; empty when LIVE or CACHED. */
  problems: string[];
  lastPull: {at: string; succeeded: boolean; error: string | null} | null;
  /** With none, a pull has nothing to read. */
  configuredSources?: number;
}

export interface EnvironmentSummary {
  name: string;
  servers: ServerSummary[];
  sources: SourcesSummary;
  /** Served from its cache with no server connected; a pull takes it live (sqlx-nbi). */
  cached?: boolean;
  health?: EnvironmentHealth;
}

export interface PullResult {
  environment: string;
  profiles: number;
  window: {from: string; to: string; days: number} | null;
  /** Redundant captures removed, or that would be if pruning were enabled. */
  prunedCaptures: number;
  pruningEnabled: boolean;
}

export interface EnvironmentList {
  defaultEnvironment: string;
  environments: EnvironmentSummary[];
}

@Injectable({
  providedIn: 'root'
})
export class EnvironmentService {

  /**
   * Currently selected environment name (null = use the backend default).
   *
   * <p>PRECEDENCE: the URL, then localStorage, then the backend default. A
   * link that names an environment shows that environment, whatever the
   * recipient last picked — which is the whole point of putting it in the URL.
   *
   * <p>Read from `location.search` rather than the router because the
   * interceptor stamps this onto requests a component constructor can issue,
   * which happens before any router subscription fires.
   */
  private readonly initialSearch = inject(INITIAL_SEARCH);

  readonly current$ = new BehaviorSubject<string | null>(
    envFromSearch(this.initialSearch) ?? localStorage.getItem(STORAGE_KEY));

  /**
   * An environment name that was asked for and does not exist, or null.
   *
   * <p>Reported rather than silently replaced. The backend's
   * `EnvironmentContext.getOrDefault` falls back to the default for an unknown
   * name, which is right for a stale header and wrong for a link a colleague
   * just sent: it would show them a different corpus without a word, and every
   * number on the page would be a confident answer to a question they did not
   * ask.
   */
  readonly unknown$ = new BehaviorSubject<string | null>(null);

  constructor(private http: HttpClient) {
    // A link's environment sticks for this browser: following it is a
    // deliberate act, and the alternative — reverting on the next navigation
    // that omits the parameter — would be harder to explain than it is worth.
    const fromUrl = envFromSearch(this.initialSearch);
    if (fromUrl) {
      localStorage.setItem(STORAGE_KEY, fromUrl);
    }
  }

  getEnvironments(): Observable<EnvironmentList> {
    return this.http.get<EnvironmentList>(endpoint + 'environments').pipe(
      tap(list => this.checkKnown(list)));
  }

  /**
   * Whether the selected environment is one the backend actually has.
   *
   * <p>Deliberately does not correct the selection. Switching the user to the
   * default silently is the behaviour this exists to prevent; the UI shows
   * what was asked for and what happened instead.
   */
  private checkKnown(list: EnvironmentList): void {
    const current = this.current$.value;
    const known = !current || list.environments.some(e => e.name === current);
    this.unknown$.next(known ? null : current);
  }

  /** Drop the environment's cached schema so it reloads from its servers on next use. */
  refreshSchema(name: string): Observable<void> {
    return this.http.post<void>(endpoint + 'environments/' + encodeURIComponent(name) + '/refresh-schema', null);
  }

  /**
   * Read the environment's sources now and answer what the capture holds.
   *
   * <p>Not the same as reloading: the useful answer to "pull now" is whether
   * the capture is worth having — how many profiles, over what period — and a
   * pull that merely succeeded says nothing about that (sqlx-ijz).
   */
  pull(name: string): Observable<PullResult> {
    return this.http.post<PullResult>(endpoint + 'environments/' + encodeURIComponent(name) + '/pull', null);
  }

  getCurrent(): string | null {
    return this.current$.value;
  }

  setCurrent(name: string | null): void {
    if (name) {
      localStorage.setItem(STORAGE_KEY, name);
    } else {
      localStorage.removeItem(STORAGE_KEY);
    }
    this.current$.next(name);
  }
}
