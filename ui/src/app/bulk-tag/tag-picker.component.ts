import {Component, ElementRef, EventEmitter, HostListener, Input, OnDestroy, OnInit, Output, ViewChild} from '@angular/core';
import {Subscription} from 'rxjs';
import {ChipStyle, TagColorsService} from '../tag-colors.service';

/**
 * Choose an existing tag or name a new one.
 *
 * <p>Deliberately a separate component from `TagField` rather than a mode on
 * it. `TagField` is mounted once per row — three hundred times on a table page
 * — and its `shareReplay` candidates plus the colour-map subscription that
 * makes chips render at all are documented, load-bearing behaviour. Adding an
 * input that changes its render path to serve one control in a header would
 * put all of that at risk for no gain.
 *
 * <p>It reads the same `TagColorsService.getSummaries()`, so it costs no extra
 * request when a page already has TagFields in its rows, and offers the same
 * client-side filtering over a small tag universe.
 */
@Component({
  selector: 'app-tag-picker',
  templateUrl: './tag-picker.component.html',
  styleUrls: ['./tag-picker.component.scss'],
  imports: []
})
export class TagPickerComponent implements OnInit, OnDestroy {

  /** Placeholder for the input, so the caller can say what it will apply to. */
  @Input() placeholder = 'tag…';

  @Input() disabled = false;

  /**
   * Whether a name that does not exist yet is offered as "create".
   *
   * <p>Off when the picker is choosing something to *remove*: a tag nothing
   * carries cannot be taken off anything, and offering to create one in order
   * to delete it reads as nonsense.
   */
  @Input() allowCreate = true;

  /** Emitted when a name is chosen — existing or newly typed. */
  @Output() picked = new EventEmitter<string>();

  @ViewChild('input') input?: ElementRef<HTMLInputElement>;

  open = false;
  filter = '';
  cursor = 0;

  private candidates: string[] = [];
  private subscriptions = new Subscription();

  constructor(private tagColors: TagColorsService,
              private host: ElementRef<HTMLElement>) {}

  ngOnInit() {
    this.readCandidates();
  }

  /**
   * Re-read on every open, not once on init.
   *
   * <p>`getSummaries(true)` replaces the service's cached observable rather
   * than pushing to its existing subscribers, so a subscription taken at init
   * keeps replaying the list as it was. Bulk tagging creates names — tag three
   * schemas `billing`, then go to remove it and the name you just made is not
   * offered. Opening the popover is a user gesture and the summaries are
   * `shareReplay`, so this costs a request only when something actually
   * refreshed them.
   */
  private readCandidates() {
    this.subscriptions.add(this.tagColors.getSummaries()
      .subscribe(summaries => this.candidates = summaries.map(summary => summary.name)));
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
  }

  chipStyle(name: string): ChipStyle {
    return this.tagColors.chipStyle(name);
  }

  get options(): string[] {
    const query = this.filter.trim().toLowerCase();
    return this.candidates.filter(name => !query || name.toLowerCase().includes(query));
  }

  /**
   * The typed value when it is not an existing tag — offered as "create".
   *
   * <p>Matched case-insensitively against the whole list, not against the
   * filtered options: offering to create `Billing` while `billing` exists would
   * produce two tags that look identical in every list that shows them.
   */
  get newName(): string | null {
    const typed = this.filter.trim();
    if (!typed || !this.allowCreate) {
      return null;
    }
    return this.candidates.some(name => name.toLowerCase() === typed.toLowerCase()) ? null : typed;
  }

  private get choices(): string[] {
    const created = this.newName;
    return created ? [...this.options, created] : this.options;
  }

  toggle() {
    if (this.disabled) {
      return;
    }
    this.open = !this.open;
    if (this.open) {
      this.filter = '';
      this.cursor = 0;
      this.readCandidates();
      setTimeout(() => this.input?.nativeElement.focus());
    }
  }

  onFilter(value: string) {
    this.filter = value;
    this.cursor = 0;
  }

  pick(name: string) {
    this.open = false;
    this.filter = '';
    this.picked.emit(name);
  }

  onKey(event: KeyboardEvent) {
    const choices = this.choices;
    if (event.key === 'ArrowDown') {
      this.cursor = Math.min(this.cursor + 1, choices.length - 1);
      event.preventDefault();
    } else if (event.key === 'ArrowUp') {
      this.cursor = Math.max(this.cursor - 1, 0);
      event.preventDefault();
    } else if (event.key === 'Enter') {
      const chosen = choices[this.cursor];
      if (chosen) {
        this.pick(chosen);
      }
      event.preventDefault();
    } else if (event.key === 'Escape') {
      this.open = false;
      event.preventDefault();
    }
  }

  /** Close on an outside click; the popover has no backdrop of its own. */
  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    if (this.open && !this.host.nativeElement.contains(event.target as Node)) {
      this.open = false;
    }
  }
}
