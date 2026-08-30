import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import type {QuerySearchRequest} from '../query-search.service';

const endpoint = '/api/sql-explorer/';

/**
 * What a bulk write did.
 *
 * `changed` is what actually happened; `unchanged` is the rest of the set —
 * paths that already carried the tag on an attach, or did not carry it on a
 * detach. Reporting both is what lets the UI say "already tagged" instead of
 * looking like it failed.
 */
export interface BulkTagResult {
  name: string;
  requested: number;
  changed: number;
  unchanged: number;
}

/** How many queries a search matches, and whether that exceeds the cap. */
export interface SearchTagPreview {
  count: number;
  limit: number;
  capped: boolean;
}

/**
 * Tagging a whole result set at once.
 *
 * <p>Two doors onto one primitive, because the surfaces genuinely differ. The
 * list pages have already loaded every row they show, so they enumerate paths.
 * The workbench filters server-side over thousands of rows and holds one page,
 * so the server resolves the set from the same search — fetching every digest
 * just to list them back would be the client doing work it has no other reason
 * to do (sqlx-agl).
 */
@Injectable({providedIn: 'root'})
export class BulkTagService {

  constructor(private http: HttpClient) {
  }

  tagPaths(name: string, paths: string[]): Observable<BulkTagResult> {
    return this.http.post<BulkTagResult>(endpoint + 'tags/bulk', {name, paths});
  }

  untagPaths(name: string, paths: string[]): Observable<BulkTagResult> {
    return this.http.post<BulkTagResult>(endpoint + 'tags/bulk/remove', {name, paths});
  }

  /**
   * How many queries the search matches.
   *
   * <p>Resolved by the same server-side call that performs the write, so the
   * number in a confirmation is the number that gets tagged.
   */
  previewSearch(search: QuerySearchRequest): Observable<SearchTagPreview> {
    return this.http.post<SearchTagPreview>(endpoint + 'tags/search/preview', search);
  }

  tagSearch(name: string, search: QuerySearchRequest, remove = false): Observable<BulkTagResult> {
    return this.http.post<BulkTagResult>(endpoint + 'tags/search', {name, search, remove});
  }
}
