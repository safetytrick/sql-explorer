import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import type {TopologyReport} from './topology';

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
}
