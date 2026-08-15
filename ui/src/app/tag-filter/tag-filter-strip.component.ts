import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {Subscription} from 'rxjs';
import {TagColorsService} from '../tag-colors.service';
import {TagMode} from '../query-search.service';
import {Tag} from '../api-types';
import {decodeTagFilter, emptyTagFilter, encodeTagFilter, TagFilterState} from './tag-filter-url';

/** A tag as offered by the strip: name and how many rows carry it. */
export interface StripTag {
  name: string;
  count: number;
}

/** Anything the strip can count tags on — both list rollups qualify. */
export interface TaggedRow {
  tags: Tag[];
}

/**
 * Tag filter for the list pages: a row of toggle chips that sits in the view
 * header, so the ledger keeps the full width of the page.
 *
 * Counts are computed from the rows already on the page rather than fetched,
 * so they say "how many of *these* carry it" and stay right when the page is
 * scoped (`/tables/:schema`). Names and colours come from
 * `TagColorsService.getSummaries()`, which is `shareReplay(1)` and already
 * loaded by the TagFields in the rows — the strip adds no request.
 *
 * The strip owns its URL state so both list pages get identical behaviour
 * from one place; the page only has to filter its rows with what it emits.
 */
@Component({
  selector: 'app-tag-filter-strip',
  templateUrl: './tag-filter-strip.component.html',
  styleUrls: ['./tag-filter-strip.component.scss'],
  imports: []
})
export class TagFilterStripComponent implements OnInit, OnDestroy {

  @Input()
  set rows(rows: TaggedRow[] | null) {
    this.allRows = rows ?? [];
    this.recount();
  }

  /** Emitted on init and on every change, so the page can filter in place. */
  @Output() filterChange = new EventEmitter<TagFilterState>();

  state: TagFilterState = emptyTagFilter();
  stripTags: StripTag[] = [];
  matched = 0;

  private allRows: TaggedRow[] = [];
  private subscriptions = new Subscription();

  constructor(private tagColors: TagColorsService,
              private route: ActivatedRoute,
              private router: Router) {}

  ngOnInit() {
    this.state = decodeTagFilter(this.route.snapshot.queryParamMap);
    // Subscribing is also what fills the colour map the chips read.
    this.subscriptions.add(this.tagColors.getSummaries().subscribe(() => this.recount()));
    this.recount();
    this.filterChange.emit(this.state);
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
  }

  chipStyle(name: string) {
    return this.tagColors.chipStyle(name);
  }

  get active(): boolean {
    return this.state.tags.length > 0;
  }

  isOn(name: string): boolean {
    return this.state.tags.includes(name);
  }

  toggle(name: string) {
    this.state = {
      ...this.state,
      tags: this.isOn(name)
        ? this.state.tags.filter(tag => tag !== name)
        : [...this.state.tags, name]
    };
    this.apply();
  }

  setMode(mode: TagMode) {
    this.state = {...this.state, mode};
    this.apply();
  }

  clear() {
    this.state = {...this.state, tags: []};
    this.apply();
  }

  private apply() {
    this.recount();
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: encodeTagFilter(this.state),
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
    this.filterChange.emit(this.state);
  }

  /**
   * Tags worth offering: those some row carries, plus any already selected so
   * a filter that now matches nothing can still be switched off.
   */
  private recount() {
    const counts = new Map<string, number>();
    for (const row of this.allRows) {
      for (const tag of row.tags ?? []) {
        counts.set(tag.name, (counts.get(tag.name) ?? 0) + 1);
      }
    }
    for (const selected of this.state.tags) {
      if (!counts.has(selected)) {
        counts.set(selected, 0);
      }
    }
    this.stripTags = [...counts.entries()]
      .map(([name, count]) => ({name, count}))
      .sort((left, right) => right.count - left.count || left.name.localeCompare(right.name));
    this.matched = this.allRows.filter(row => {
      const names = (row.tags ?? []).map(tag => tag.name);
      return !this.state.tags.length || (this.state.mode === 'ALL'
        ? this.state.tags.every(tag => names.includes(tag))
        : this.state.tags.some(tag => names.includes(tag)));
    }).length;
  }
}
