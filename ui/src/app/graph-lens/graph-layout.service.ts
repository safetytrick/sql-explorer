import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {LayoutItem} from './layout-moves';

const endpoint = '/api/sql-explorer/graph/layout';

/**
 * The graph's hand-arranged grid layout (sqlx-aoy.15). Shared by everyone
 * and keyed by schema name, so it is not scoped to the environment.
 * Every write answers the whole layout as it then stands.
 */
@Injectable({ providedIn: 'root' })
export class GraphLayoutService {

  constructor(private http: HttpClient) { }

  load(): Observable<LayoutItem[]> {
    return this.http.get<LayoutItem[]>(endpoint);
  }

  /** All or nothing: one bad item and none of the batch is written. */
  save(items: LayoutItem[]): Observable<LayoutItem[]> {
    return this.http.put<LayoutItem[]>(endpoint, items);
  }

  /** A schema's tables and aggregates back to automatic placement. */
  reset(schema: string): Observable<LayoutItem[]> {
    return this.http.delete<LayoutItem[]>(`${endpoint}/schema/${encodeURIComponent(schema)}`);
  }
}
