import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, shareReplay, tap } from 'rxjs';

const endpoint = '/api/sql-explorer/';
const FALLBACK = '#8b93a8';

export interface TagSummary {
  name: string;
  color: string | null;
  queries: number;
  tables: number;
  other: number;
  lastUsed: string | null;
}

export interface ChipStyle {
  color: string;
  background: string;
}

@Injectable({ providedIn: 'root' })
export class TagColorsService {

  private colors = new Map<string, string>();
  private summaries$: Observable<TagSummary[]> | null = null;

  constructor(private http: HttpClient) { }

  getSummaries(refresh = false): Observable<TagSummary[]> {
    if (refresh || !this.summaries$) {
      this.summaries$ = this.http.get<TagSummary[]>(endpoint + 'tags').pipe(
        tap(summaries => {
          this.colors.clear();
          summaries.forEach(summary => {
            if (summary.color) {
              this.colors.set(summary.name, summary.color);
            }
          });
        }),
        shareReplay(1)
      );
    }
    return this.summaries$;
  }

  define(name: string, color: string): Observable<unknown> {
    return this.http.put(endpoint + 'tags/definitions/' + encodeURIComponent(name), { color });
  }

  deleteTag(name: string): Observable<unknown> {
    return this.http.delete(endpoint + 'tags/definitions/' + encodeURIComponent(name));
  }

  /** Chip colors for a tag name; dim background derived from the definition color. */
  chipStyle(name: string): ChipStyle {
    const color = this.colors.get(name) || FALLBACK;
    return { color, background: color + '26' };
  }
}
