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
}

/**
 * Indexes no connection in the environment has read.
 *
 * <p>Not cached. Every other report here describes a corpus that was loaded
 * once; this one reads live counters, and the whole question is what they say
 * now.
 */
@Injectable({providedIn: 'root'})
export class UnusedIndexService {

  constructor(private http: HttpClient) { }

  getReport(): Observable<UnusedIndexReport> {
    return this.http.get<UnusedIndexReport>(endpoint + 'unused-indexes');
  }
}
