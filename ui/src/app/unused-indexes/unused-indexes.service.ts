import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import type {CoverageVerdict} from '../api-types';

const endpoint = '/api/sql-explorer/';

export interface UnusedIndex {
  schema: string;
  table: string;
  name: string;
}

export interface IndexConnection {
  connection: string;
  verdict: CoverageVerdict;
  /** Why it could not be read, or null. */
  problem: string | null;
  /** How it was read when not the usual way, e.g. without the sys schema (sqlx-jip); or null. */
  note: string | null;
  /** How long it has been up — an UPPER BOUND on how long it has been counting. */
  upSeconds: number | null;
  unusedHere: number;
  readHere: number;
}

export interface UnusedIndexReport {
  environment: string;
  connections: IndexConnection[];
  /**
   * Whether every connection answered.
   *
   * <p>False makes the list a statement about the connections that spoke, not
   * about the environment — a connection that could not be read is not one
   * where the index is unused (sqlx-vjr).
   */
  complete: boolean;
  unused: UnusedIndex[];
  /** The sentence a reader needs before acting; assembled server-side. */
  basis: string;
  /** When the connections were read (ISO instant). Kept on disk, so it can predate a restart. */
  readAt: string;
  /** How long reading every connection took: what reading again will cost. */
  readMillis: number;
  /** The uptime, in days, a connection needed for an OK verdict when this was read. */
  minimumDays: number;
}

/**
 * Indexes no connection in the environment has read.
 *
 * <p>Reading one connects to every server in the environment, so it is done
 * when asked rather than on every visit (sqlx-1bo.2). The server keeps the
 * last report per environment on disk, with when it was read, so it outlives
 * a restart.
 */
@Injectable({providedIn: 'root'})
export class UnusedIndexService {

  constructor(private http: HttpClient) { }

  /** The last report read for the current environment, or null when none has been. Connects to nothing. */
  getLatest(): Observable<UnusedIndexReport | null> {
    return this.http.get<UnusedIndexReport | null>(endpoint + 'unused-indexes');
  }

  /** Read every connection of the current environment now. */
  read(): Observable<UnusedIndexReport> {
    return this.http.post<UnusedIndexReport>(endpoint + 'unused-indexes/read', {});
  }
}
