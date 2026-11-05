import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {Subscription} from 'rxjs';
import {TagColorsService} from '../tag-colors.service';
import {TagMode} from '../query-search.service';
import {Tag} from '../api-types';
import {decodeTagFilter, emptyTagFilter, encodeTagFilter, matchesTagFilter, sameTagFilter, TagFilterState} from './tag-filter-url';

/** A tag as offered by the strip: name and how many rows carry it. */
export interface StripTag {
  name: string;
  count: number;
}

/** Anything the strip can count tags on — both list rollups qualify. */
export interface TaggedRow {
  tags: Tag[];
  /**
   * Tags of the thing this row belongs to — a table's schema. Exclusion is a
   * statement about objects, so it reaches a row through what contains it;
   * inclusion is about the row itself and ignores this.
   */
  inheritedTags?: string[];
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
    // Tracked, not read once. The schema-tree sidebar writes the same
    // `schemaTagsExclude` parameter, so a snapshot read would leave this strip
    // showing one state while the URL and the page showed another (sqlx-zl1).
    this.subscriptions.add(this.route.queryParamMap.subscribe(params => {
      const next = decodeTagFilter(params);
      if (sameTagFilter(next, this.state)) { return; }
      this.state = next;
      this.recount();
      this.filterChange.emit(this.state);
    }));
    // Subscribing is also what fills the colour map the chips read.
    this.subscriptions.add(this.tagColors.getSummaries().subscribe(() => this.recount()));
    this.recount();
  }

  ngOnDestroy() {
    this.subscriptions.unsubscribe();
  }

  chipStyle(name: string) {
    return this.tagColors.chipStyle(name);
  }

  /** Says what the chip does next, not just what it counts. */
  chipTitle(tag: StripTag): string {
    const carried = `${tag.count} of the rows on this page carry ${tag.name}`;
    if (this.isExcluded(tag.name)) {
      return `${carried}. Hidden — click to stop filtering on it.`;
    }
    return this.isOn(tag.name)
      ? `${carried}. Required — click to hide them instead.`
      : `${carried}. Click to require it, again to hide it.`;
  }

  get active(): boolean {
    return this.state.tags.length > 0 || this.state.excludeTags.length > 0;
  }

  isOn(name: string): boolean {
    return this.state.tags.includes(name);
  }

  isExcluded(name: string): boolean {
    return this.state.excludeTags.includes(name);
  }

  /**
   * Cycle a chip: off → include → exclude → off.
   *
   * <p>Tri-state rather than a second control, because it is the shape the app
   * already uses for the same question — the workbench's flags are
   * any/yes/no, and "show me what is *not* tagged system" is the same kind of
   * ask as "show me what is *not* a full scan". A separate exclude row would
   * be a second vocabulary for one idea.
   */
  toggle(name: string) {
    const tags = this.state.tags.filter(tag => tag !== name);
    const excludeTags = this.state.excludeTags.filter(tag => tag !== name);
    if (this.isOn(name)) {
      excludeTags.push(name);          // include → exclude
    } else if (!this.isExcluded(name)) {
      tags.push(name);                 // off → include
    }                                  // exclude → off: dropped from both
    this.state = {...this.state, tags, excludeTags};
    this.apply();
  }

  setMode(mode: TagMode) {
    this.state = {...this.state, mode};
    this.apply();
  }

  clear() {
    this.state = {...this.state, tags: [], excludeTags: []};
    this.apply();
  }

  private apply() {
    this.recount();
    // the queryParamMap subscription sees this write come back and no-ops on
    // it, because the decoded state will equal what is already held
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: encodeTagFilter(this.state),
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
    this.filterChange.emit(this.state);
  }

  /**
   * Tags worth offering: those some row carries or inherits, plus any already
   * selected so a filter that now matches nothing can still be switched off.
   *
   * <p>Inherited tags have to be counted or the strip cannot offer the very
   * tag it exists for: `system` may be attached to schemas only, so no row on
   * `/tables` carries it and the chip would never appear.
   */
  private recount() {
    const counts = new Map<string, number>();
    for (const row of this.allRows) {
      const names = new Set((row.tags ?? []).map(tag => tag.name));
      for (const inherited of row.inheritedTags ?? []) {
        names.add(inherited);
      }
      for (const name of names) {
        counts.set(name, (counts.get(name) ?? 0) + 1);
      }
    }
    for (const selected of [...this.state.tags, ...this.state.excludeTags]) {
      if (!counts.has(selected)) {
        counts.set(selected, 0);
      }
    }
    this.stripTags = [...counts.entries()]
      .map(([name, count]) => ({name, count}))
      .sort((left, right) => right.count - left.count || left.name.localeCompare(right.name));
    this.matched = this.allRows.filter(row =>
      matchesTagFilter((row.tags ?? []).map(tag => tag.name), this.state,
        row.inheritedTags ?? [])).length;
    this.modeCounts = this.state.tags.length > 1
      ? {ALL: this.countUnder('ALL'), ANY: this.countUnder('ANY')}
      : null;
  }

  /**
   * How many rows each match mode would keep, shown on its button
   * (sqlx-c8w.10). "all" and "any" are abstract; "all 3 · any 40" is a choice
   * between two visible outcomes.
   */
  modeCounts: {ALL: number, ANY: number} | null = null;

  private countUnder(mode: 'ALL' | 'ANY'): number {
    const state = {...this.state, mode};
    return this.allRows.filter(row =>
      matchesTagFilter((row.tags ?? []).map(tag => tag.name), state, row.inheritedTags ?? [])).length;
  }
}
