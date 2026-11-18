import {Injectable} from '@angular/core';
import {HttpClient, HttpParams} from '@angular/common/http';
import {Observable} from 'rxjs';
import type {TopologyReport} from './topology';
import type {QuerySearchRequest} from '../query-search.service';
import type {CompareReport, CompareState} from '../server-compare/server-compare';

const endpoint = '/api/sql-explorer/';

/**
 * The environment's servers and what each ran (sqlx-a9h). A server's own
 * statements are a search with `sources=<server>` (sqlx-a9h.1).
 */
@Injectable({providedIn: 'root'})
export class TopologyService {

  constructor(private http: HttpClient) {
  }

  getTopology(): Observable<TopologyReport> {
    return this.http.get<TopologyReport>(endpoint + 'topology');
  }

  /** The queries a search matches, compared across one cluster's servers (sqlx-a9h.2). */
  compare(request: QuerySearchRequest, state: CompareState): Observable<CompareReport> {
    let params = new HttpParams().set('by', state.by).set('sort', state.sort);
    if (state.cluster) { params = params.set('cluster', state.cluster); }
    if (state.flags.length) { params = params.set('flags', state.flags.join(',')); }
    if (state.all) { params = params.set('all', 'true'); }
    return this.http.post<CompareReport>(endpoint + 'topology/compare', request, {params});
  }
}
