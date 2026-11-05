import {Component, ElementRef, EventEmitter, HostListener, inject, Input, OnDestroy, OnInit, Output, ViewChild} from '@angular/core';
import {ActivatedRoute, Params, RouterLink} from '@angular/router';
import {Subscription} from 'rxjs';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ChipStyle, TagColorsService} from '../tag-colors.service';
import type {TagTarget} from '../tag-path';
import {Tag} from '../api-types';

/**
 * Tag editor: chips with a hover `×`, and a `+` that summons a typeahead
 * popover for attaching an existing tag or creating a new one.
 *
 * The point of the design is that **the input is not persistent**. Its
 * predecessor (`app-tag-view`) rendered a live text field in every row, so on
 * a list page an untagged row cost a full-width "add tag…" underline and the
 * tags column outweighed the data beside it. Here a row at rest is chips and
 * nothing else; in `dense` mode the `+` is a faint dotted ghost that comes up
 * only on row hover or keyboard focus, and the `×` appears on chip hover.
 *
 * Candidate names come from `TagColorsService.getSummaries()`, which is
 * `shareReplay(1)`, so a 300-row table issues one request in total rather
 * than one per row. Filtering is client-side against that list — the tag
 * universe is small, and per-keystroke round trips from every mounted row
 * would be far worse than the list is long.
 */
@Component({
  selector: 'app-tag-field',
  templateUrl: './tag-field.component.html',
  styleUrls: ['./tag-field.component.scss'],
  imports: [RouterLink]
})
export class TagFieldComponent implements OnInit, OnDestroy {

  /**
   * What this field tags, stated typed.
   *
   * <p>Was a path string. A path cannot express a name containing `/`, so the
   * component could not tag such a row at all — the storage could hold the tag
   * from sqlx-11v, but nothing could address it (sqlx-3ai).
   */
  @Input() target!: TagTarget;

  /** Pre-loaded tags. When null the component fetches its own for `path`. */
  @Input() tags: Tag[] | null = null;

  /** In-row treatment: affordances stay out of the way until hovered. */
  @Input() dense = false;

  /** When set, the chip label becomes a link to `linkPrefix + tag.name`. */
  @Input() linkPrefix: string | null = null;

  /**
   * The chip label filters the current page by its tag instead of going
   * anywhere (sqlx-c8w.9): it adds the tag to `?tags=`, which the page's tag
   * strip reads, keeping whatever else the URL says. For list pages that carry
   * a tag strip; wins over `linkPrefix`.
   */
  @Input() filterInPlace = false;
  private readonly route = inject(ActivatedRoute, {optional: true});

  /** The query-param patch that adds `name` to the page's tag filter. */
  filterParams(name: string): Params {
    const params = this.route?.snapshot.queryParamMap;
    const current = (params?.get('tags') ?? '').split(',').filter(Boolean);
    const tags = current.some(tag => tag.toLowerCase() === name.toLowerCase()) ? current : [...current, name];
    // written out, like the strip does: ALL here and ANY in the workbench would
    // otherwise make a bare ?tags= mean different things
    return { tags: tags.join(','), tagMode: params?.get('tagMode') ?? 'ALL' };
  }

  @Output() changed = new EventEmitter<Tag[]>();

  @ViewChild('popInput') popInput?: ElementRef<HTMLInputElement>;

  open = false;
  filter = '';
  cursor = 0;
  popTop = 0;
  popLeft = 0;

  /** Every known tag name; also what re-renders the chips once colors land. */
  private candidates: string[] = [];
  private subscriptions = new Subscription();

  constructor(private sqlService: AnalyzedSqlService,
              private tagColors: TagColorsService,
              private host: ElementRef<HTMLElement>) {}

  ngOnInit() {
    if (this.tags === null) {
      this.subscriptions.add(this.sqlService.getTagsForTargets([this.target])
        .subscribe(answer => this.tags = answer[0] ?? []));
    }
    // Subscribing is what populates the color map the chips read; without a
    // subscriber every chip would render in the fallback grey and stay there.
    this.subscriptions.add(this.tagColors.getSummaries()
      .subscribe(summaries => this.candidates = summaries.map(summary => summary.name)));
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
  }

  chipStyle(name: string): ChipStyle {
    return this.tagColors.chipStyle(name);
  }

  /** Existing tags not already applied here, filtered by what has been typed. */
  get options(): string[] {
    const applied = (this.tags ?? []).map(tag => tag.name.toLowerCase());
    const query = this.filter.trim().toLowerCase();
    return this.candidates
      .filter(name => !applied.includes(name.toLowerCase()))
      .filter(name => !query || name.toLowerCase().includes(query));
  }

  /** The typed value, when it is not an existing tag — offered as "create". */
  get creatable(): string | null {
    const query = this.filter.trim();
    if (!query) {
      return null;
    }
    const known = this.candidates.some(name => name.toLowerCase() === query.toLowerCase());
    const applied = (this.tags ?? []).some(tag => tag.name.toLowerCase() === query.toLowerCase());
    return known || applied ? null : query;
  }

  private get rows(): string[] {
    const creatable = this.creatable;
    return creatable ? [...this.options, creatable] : this.options;
  }

  toggle(event: Event) {
    event.stopPropagation();
    if (this.open) {
      this.close();
      return;
    }
    const button = (event.currentTarget as HTMLElement).getBoundingClientRect();
    // Fixed positioning, so the popover is not clipped by the card's
    // `overflow: hidden` or trapped inside the horizontal table scroller.
    this.popLeft = Math.max(8, Math.min(button.left, window.innerWidth - 244));
    this.popTop = button.bottom + 4;
    this.open = true;
    this.filter = '';
    this.cursor = 0;
    setTimeout(() => this.popInput?.nativeElement.focus());
  }

  close() {
    this.open = false;
    this.filter = '';
    this.cursor = 0;
  }

  onFilter(value: string) {
    this.filter = value;
    this.cursor = 0;
  }

  move(delta: number) {
    const count = this.rows.length;
    if (count) {
      this.cursor = Math.min(Math.max(this.cursor + delta, 0), count - 1);
    }
  }

  commit() {
    const name = this.rows[this.cursor];
    if (name) {
      this.attach(name);
    }
  }

  /**
   * The tag name whose attach or detach was refused, or null.
   *
   * <p>Cleared on the next attempt rather than on a timer: the message should
   * stay until it has been acted on, and a row in a 300-row table is not
   * somewhere a person is looking when a toast would fire.
   */
  failed: string | null = null;

  attach(name: string) {
    const value = name.trim();
    this.close();
    if (!value || (this.tags ?? []).some(tag => tag.name.toLowerCase() === value.toLowerCase())) {
      return;
    }
    this.failed = null;
    this.subscriptions.add(this.sqlService.addTag(value, this.target).subscribe({
      next: tag => {
        this.tags = [...(this.tags ?? []), tag];
        if (!this.candidates.some(candidate => candidate.toLowerCase() === value.toLowerCase())) {
          this.candidates = [...this.candidates, value];
        }
        this.changed.emit(this.tags);
      },
      // Without this the chip simply never appears. The backend rejects a name
      // that is not word-ish and a path it cannot address — a table whose name
      // contains a '/' is the real case (sqlx-0lj) — and a silent no-op leaves
      // someone clicking + repeatedly with nothing to read.
      error: () => this.failed = value
    }));
  }

  detach(tag: Tag, event: Event) {
    event.stopPropagation();
    event.preventDefault();
    this.failed = null;
    const before = this.tags ?? [];
    // Removed optimistically, because the common case succeeds and a chip that
    // lingers while a request is in flight reads as a dead click.
    this.tags = before.filter(existing => existing.id !== tag.id);
    this.changed.emit(this.tags);
    this.subscriptions.add(this.sqlService.removeTag(tag.id).subscribe({
      // ...but put back what the server would not remove. The delete is scoped
      // to the selected environment now (sqlx-8p0), so a refusal means the tag
      // is still there — and leaving the row looking untagged until the next
      // load would be showing something untrue.
      error: () => {
        this.tags = before;
        this.failed = tag.name;
        this.changed.emit(this.tags);
      }
    }));
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    if (this.open && !this.host.nativeElement.contains(event.target as Node)) {
      this.close();
    }
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    this.close();
  }

  // A fixed-position popover does not travel with the row it belongs to.
  @HostListener('window:scroll')
  @HostListener('window:resize')
  onViewportChange() {
    this.close();
  }
}
