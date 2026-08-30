import {Component, EventEmitter, Input, Output} from '@angular/core';
import {BulkTagResult, BulkTagService} from './bulk-tag.service';
import {TagPickerComponent} from './tag-picker.component';
import {TagColorsService} from '../tag-colors.service';
import type {QuerySearchRequest} from '../query-search.service';

/** What the pending action would do, held until the user confirms it. */
interface Pending {
  name: string;
  remove: boolean;
  count: number;
  /** Set when the count exceeds the server's cap; the action is refused. */
  overCap: number | null;
}

/**
 * Apply or remove one tag across everything the current view matched.
 *
 * <p>Sits beside the filter controls, because those are what define the set.
 * The set itself is supplied by the page in one of two ways: `paths` when the
 * page has already loaded every row it shows, or `search` when the server has
 * to resolve it — the workbench filters server-side over thousands of rows.
 *
 * <p>THE CONFIRMATION NAMES THE SET, NOT JUST THE COUNT. On a filtered list
 * page the same button does very different things depending on filter state,
 * so "tag 47 of 308 tables as system?" is the question, and `setLabel` is how
 * the page says which 47.
 *
 * <p>UNTAG SHIPS WITH TAG. Removing tags one at a time is per-association, so
 * tagging 500 rows and wanting it undone would be 500 calls no UI can make. A
 * bulk tag without a bulk untag is a trap.
 */
@Component({
  selector: 'app-bulk-tag-bar',
  templateUrl: './bulk-tag-bar.component.html',
  styleUrls: ['./bulk-tag-bar.component.scss'],
  imports: [TagPickerComponent]
})
export class BulkTagBarComponent {

  /** Enumerated mode: every path the current view matched. */
  @Input() paths: string[] | null = null;

  /** Search mode: the server resolves the set from this request. */
  @Input() search: QuerySearchRequest | null = null;

  /**
   * How many rows the action would touch, when the page knows. In search mode
   * this seeds the confirmation before the preview replies, and is replaced by
   * the server's count.
   */
  @Input() count = 0;

  /** What the set is, in the page's own words: "47 of 308 tables". */
  @Input() setLabel = '';

  /** Emitted after a successful write so the page can re-read its tags. */
  @Output() changed = new EventEmitter<BulkTagResult>();

  pending: Pending | null = null;
  busy = false;
  result: BulkTagResult | null = null;
  failed: string | null = null;

  constructor(private bulkTags: BulkTagService, private tagColors: TagColorsService) {}

  /** Nothing matched, so there is nothing to apply a tag to. */
  get empty(): boolean {
    return this.count < 1;
  }

  chipStyle(name: string) {
    return this.tagColors.chipStyle(name);
  }

  pick(name: string, remove: boolean) {
    this.result = null;
    this.failed = null;
    if (this.search) {
      // Ask the server, because only it knows the set — and asking through the
      // same resolution the write uses is what keeps the number honest.
      this.busy = true;
      this.bulkTags.previewSearch(this.search).subscribe({
        next: preview => {
          this.busy = false;
          this.pending = {
            name, remove, count: preview.count,
            overCap: preview.capped ? preview.limit : null
          };
        },
        error: error => { this.busy = false; this.failed = message(error); }
      });
      return;
    }
    this.pending = {name, remove, count: this.paths?.length ?? 0, overCap: null};
  }

  cancel() {
    this.pending = null;
  }

  confirm() {
    const pending = this.pending;
    if (!pending || pending.overCap !== null) {
      return;
    }
    this.busy = true;
    this.pending = null;
    const request = this.search
      ? this.bulkTags.tagSearch(pending.name, this.search, pending.remove)
      : pending.remove
        ? this.bulkTags.untagPaths(pending.name, this.paths ?? [])
        : this.bulkTags.tagPaths(pending.name, this.paths ?? []);

    request.subscribe({
      next: result => {
        this.busy = false;
        this.result = result;
        // A new tag name only exists on the server now, and the colour map is
        // filled by subscribing to this. Without the refresh the chips the page
        // is about to draw would all fall back to grey.
        this.tagColors.getSummaries(true).subscribe();
        this.changed.emit(result);
      },
      error: error => { this.busy = false; this.failed = message(error); }
    });
  }

  /**
   * What actually happened, which is not always what was asked.
   *
   * <p>"tagged 0 of 47" is the honest report when every row already carried the
   * tag, and it has to read as a result rather than a failure.
   */
  get resultText(): string {
    const result = this.result;
    if (!result) {
      return '';
    }
    if (result.changed === 0) {
      return result.requested === result.unchanged
        ? `all ${result.requested} already as you asked — nothing to change`
        : 'nothing changed';
    }
    const rest = result.unchanged ? `, ${result.unchanged} already` : '';
    return `${result.changed} of ${result.requested}${rest}`;
  }
}

/** The server's own message when it has one; it says what to fix. */
function message(error: unknown): string {
  const body = (error as {error?: {message?: string}})?.error;
  return body?.message ?? 'the change could not be applied';
}
