import {Component, ElementRef, EventEmitter, HostListener, Input, OnDestroy, OnInit, Output, ViewChild} from '@angular/core';
import {RouterLink} from '@angular/router';
import {Subscription} from 'rxjs';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ChipStyle, TagColorsService} from '../tag-colors.service';
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

  /** Tag association path, e.g. `/schema/app_other` or `/schema/app_other/table/users`. */
  @Input() path!: string;

  /** Pre-loaded tags. When null the component fetches its own for `path`. */
  @Input() tags: Tag[] | null = null;

  /** In-row treatment: affordances stay out of the way until hovered. */
  @Input() dense = false;

  /** When set, the chip label becomes a link to `linkPrefix + tag.name`. */
  @Input() linkPrefix: string | null = null;

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
      this.subscriptions.add(this.sqlService.getTags(this.path).subscribe(data => this.tags = data));
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

  attach(name: string) {
    const value = name.trim();
    this.close();
    if (!value || (this.tags ?? []).some(tag => tag.name.toLowerCase() === value.toLowerCase())) {
      return;
    }
    this.subscriptions.add(this.sqlService.addTag(value, this.path).subscribe(tag => {
      this.tags = [...(this.tags ?? []), tag];
      if (!this.candidates.some(candidate => candidate.toLowerCase() === value.toLowerCase())) {
        this.candidates = [...this.candidates, value];
      }
      this.changed.emit(this.tags);
    }));
  }

  detach(tag: Tag, event: Event) {
    event.stopPropagation();
    event.preventDefault();
    this.subscriptions.add(this.sqlService.removeTag(tag.id).subscribe());
    this.tags = (this.tags ?? []).filter(existing => existing.id !== tag.id);
    this.changed.emit(this.tags);
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
