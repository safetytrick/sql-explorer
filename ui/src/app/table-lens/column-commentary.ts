import {HttpClient} from '@angular/common/http';
import {Subscription} from 'rxjs';
import type {Tag} from '../api-types';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {columnPath, columnTarget} from '../tag-path';

const endpoint = '/api/sql-explorer/';

/**
 * Tags and note counts for every column of one table, each fetched in ONE
 * request (sqlx-n5j.1).
 *
 * <p>A wide table has a hundred columns; a tag field that fetched its own
 * tags would be a hundred requests. The batched answers are positional for
 * tags and keyed by path for notes, and both are zipped back to column names
 * here, so the template only ever asks "this column's tags".
 *
 * <p>`tags` stays null until the answer lands, so the row tag fields are not
 * rendered early — a field given null fetches its own, which is exactly the
 * per-row request this exists to avoid.
 */
export class ColumnCommentary {
  tags: Map<string, Tag[]> | null = null;
  private noteCounts = new Map<string, number>();
  private subscription = new Subscription();

  constructor(private sqlService: AnalyzedSqlService, private http: HttpClient) {}

  load(schema: string, table: string, columns: string[]) {
    this.subscription.unsubscribe();
    this.subscription = new Subscription();
    this.tags = null;
    this.noteCounts = new Map();
    if (!columns.length) {
      this.tags = new Map();
      return;
    }
    this.subscription.add(this.sqlService.getTagsForTargets(columns.map(column => columnTarget(schema, table, column)))
      .subscribe({
        next: answers => this.tags = new Map(columns.map((column, at) => [column, answers?.[at] ?? []])),
        error: () => this.tags = new Map(columns.map(column => [column, []]))
      }));
    const paths = columns.map(column => columnPath(schema, table, column));
    this.subscription.add(this.http.post<Record<string, unknown[]>>(endpoint + 'notes/paths', paths).subscribe({
      next: byPath => this.noteCounts = new Map(columns.map((column, at) =>
        [column, (byPath?.[paths[at]] ?? []).length])),
      // counts are a hint; without them the notes glyph just shows no number
      error: () => this.noteCounts = new Map()
    }));
  }

  /** A column's tags; one the batch did not cover gets its own array, stored, so it stays stable. */
  tagsOf(column: string): Tag[] {
    let tags = this.tags?.get(column);
    if (!tags) {
      tags = [];
      this.tags?.set(column, tags);
    }
    return tags;
  }

  changed(column: string, tags: Tag[]) {
    this.tags?.set(column, tags);
  }

  noteCount(column: string): number {
    return this.noteCounts.get(column) ?? 0;
  }

  /** A note was just added from the open column's notes card. */
  noteAdded(column: string) {
    this.noteCounts.set(column, this.noteCount(column) + 1);
  }

  destroy() {
    this.subscription.unsubscribe();
  }
}
