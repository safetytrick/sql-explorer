import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import type {ServerStatements, TopologyReport} from './topology';

const endpoint = '/api/sql-explorer/';

/** The environment's servers and what each ran (sqlx-a9h). Resolved from the environment header. */
@Injectable({providedIn: 'root'})
export class TopologyService {

  constructor(private http: HttpClient) {
  }

  getTopology(): Observable<TopologyReport> {
    return this.http.get<TopologyReport>(endpoint + 'topology');
  }

  getStatements(server: string, limit = 25): Observable<ServerStatements> {
    return this.http.get<ServerStatements>(
      endpoint + 'topology/server/' + encodeURIComponent(server) + '/statements', {params: {limit}});
  }
}
