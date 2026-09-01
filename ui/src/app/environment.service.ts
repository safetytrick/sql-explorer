import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable } from 'rxjs';

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
}

export interface SourcesSummary {
  loaded: boolean;
  sourceCount: number;
  loadedOn: string | null;
  importStats: SqlImportStats | null;
}

export interface EnvironmentSummary {
  name: string;
  servers: ServerSummary[];
  sources: SourcesSummary;
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

  /** Currently selected environment name (null = use the backend default). */
  readonly current$ = new BehaviorSubject<string | null>(localStorage.getItem(STORAGE_KEY));

  constructor(private http: HttpClient) { }

  getEnvironments(): Observable<EnvironmentList> {
    return this.http.get<EnvironmentList>(endpoint + 'environments');
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
