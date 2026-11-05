import {FormsModule} from '@angular/forms';
import {Component, Input, OnInit} from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import { TableListComponent } from '../table-list-component/table-list.component';
import { TagFilterStripComponent, TaggedRow } from '../tag-filter/tag-filter-strip.component';
import { emptyTagFilter, matchesTagFilter, TagFilterState } from '../tag-filter/tag-filter-url';
import { TableRollup } from '../api-types';
import { TypeFilter } from '../schema-tree/tree-filter';
import { readTypeFilter, TYPE_PARAM } from './type-filter-url';
import { matchesTreeFilter, matchesTypeFilter } from '../schema-tree/tree-filter';
import { decodeTableSearch, encodeTableSearch, parseColumns, tableKey, TableSearchState } from './table-search-url';
import {schemaTarget, tableTarget, targetKey, type TagTarget} from '../tag-path';
import { BulkTagBarComponent } from '../bulk-tag/bulk-tag-bar.component';

@Component({
    selector: 'app-table-names',
    templateUrl: './table-names.component.html',
    styleUrls: ['./table-names.component.scss'],
    imports: [TableListComponent, TagFilterStripComponent, BulkTagBarComponent, RouterLink, FormsModule]
})
export class TableNamesComponent implements OnInit {

  @Input()
  schema!: string;

  tables: TableRollup[] = [];

  /**
   * Owned here because the all/tables/views control lives in this page's
   * header; it is handed down to the list component, which still does the
   * filtering.
   *
   * URL-backed as `var-type`. It was component state for a long time, which
   * made "views only" the one thing in this header that could not be sent to
   * anyone — conspicuous, since it sits in the same strip as the `?tags=`
   * chips, which always could. `var-` because this is presentation owned by
   * this page, not a cross-cutting filter: the prefix is what keeps it from
   * colliding with the shared search vocabulary. `all` is the default and is
   * omitted, so an untouched page keeps a clean URL.
   */
  typeFilter: TypeFilter = 'all';
  /** `tables` narrowed by the tag strip; the type filter still applies inside. */
  visible: TableRollup[] = [];

  /**
   * The search: a name pattern applied here, and columns every table must
   * have, applied by the server — the page holds no column lists. URL-backed
   * as `var-name` / `var-columns` (sqlx-v86).
   */
  search: TableSearchState = { name: '', columns: [], exclude: [] };

  /**
   * Rows unchecked, by `schema.table`, that a bulk tag passes over. The
   * exceptions rather than the selection, so a fresh search starts with every
   * row checked; in the URL as `var-exclude`, since it is on screen.
   */
  excluded: ReadonlySet<string> = new Set();

  /**
   * Whether a name or column search narrows the list. That is when the
   * checkboxes are drawn: they exist to pass over a few rows of a searched
   * set before a bulk tag. A tag filter or the all/tables/views control does
   * not count — bulk tagging a tag-filtered list is not useful, and the
   * sidebar's exclude filter travels into the tag strip, so counting it drew
   * every row pre-checked on arrival from /schemas (sqlx-c8w.1). Without the
   * checkboxes exclusions are not applied either: a bulk tag must never skip
   * a row whose checkbox the page is not showing.
   */
  searching = false;

  /** Which schema and columns the loaded `tables` answer; a load is skipped when unchanged. */
  private loadedKey: string | null = null;

  /**
   * Exactly the rows on screen, which is what a bulk tag has to act on.
   *
   * <p>Not the same as {@link visible}: the all/tables/views control narrows
   * further, and it does so inside the list component. Tagging `visible` while
   * the user is looking at "views only" would tag base tables they cannot see,
   * which is the worst kind of bulk-action bug — invisible and plausible
   * (sqlx-agl).
   */
  displayed: TableRollup[] = [];

  /**
   * Rows as the strip counts them: own tags plus what the schema lends.
   *
   * <p>A field rebuilt in {@link applyFilter}, not a template getter. The
   * strip re-counts whenever this array's identity changes, so a getter would
   * hand it a fresh array on every change-detection pass and re-count the
   * whole page each time — the same trap the filtering below already avoids.
   */
  stripRows: TaggedRow[] = [];

  private filter: TagFilterState = emptyTagFilter();

  /**
   * Schema name (lower-cased) → that schema's tag names.
   *
   * <p>Needed because exclusion is a statement about objects, not about rows:
   * `schemaTagsExclude=system` has to take out every table in a schema tagged
   * `system`, not only the tables carrying the tag themselves. That is the
   * semantics `/queries` already applies to the same parameter, and the two
   * must not diverge — one parameter meaning two things per page is the defect
   * this change exists to remove (sqlx-uxo).
   *
   * <p>One request for the whole page: the schema set is small and
   * `tags/path` batches.
   */
  private schemaTags = new Map<string, string[]>();

  constructor(public sqlService: AnalyzedSqlService, private route: ActivatedRoute,
              private router: Router) { }

  ngOnInit() {
    // seeded from the snapshot, then kept current — /tables and /tables/:schema
    // are the same component, so a schema change must not drop the type filter
    this.typeFilter = readTypeFilter(this.route.snapshot.queryParamMap.get(TYPE_PARAM));
    this.readSearch(decodeTableSearch(this.route.snapshot.queryParamMap));
    this.route.params.subscribe(params => {
        this.schema = params['schema'];
        this.load();
    });
    this.route.queryParamMap.subscribe(params => {
      this.typeFilter = readTypeFilter(params.get(TYPE_PARAM));
      this.readSearch(decodeTableSearch(params));
      this.load();
      this.applyFilter();
    });
  }

  private readSearch(search: TableSearchState) {
    this.search = search;
    this.excluded = new Set(search.exclude);
  }

  /**
   * Fetches the tables for the current schema and columns, unless those are
   * what is already loaded — the route and query-param streams both call
   * this, and a name or checkbox change needs no fetch. A response for a
   * schema or columns since replaced is dropped.
   */
  private load() {
    const key = `${this.schema ?? ''}|${this.search.columns.join(',')}`;
    if (key === this.loadedKey) { return; }
    this.loadedKey = key;
    this.tables = [];
    this.applyFilter();
    const columns = this.search.columns;
    const request = this.schema
      ? this.sqlService.getTablesForSchema(this.schema, columns)
      : this.sqlService.getTables(columns);
    request.subscribe(data => {
      if (key !== this.loadedKey) { return; }
      this.tables = data;
      this.applyFilter();
      if (!this.schema) { this.loadSchemaTags(data); }
    });
  }

  /** The tags of every schema represented on this page, batched into one call. */
  private loadSchemaTags(tables: TableRollup[]) {
    const names = [...new Set(tables.map(table => table.schema).filter(Boolean))];
    if (!names.length) { return; }
    this.sqlService.getTagsForTargets(names.map(schemaTarget)).subscribe({
      next: byIndex => {
        this.schemaTags = new Map(names.map((name, index) =>
          [name.toLowerCase(), (byIndex[index] ?? []).map(tag => tag.name)]));
        this.applyFilter();
      },
      // a missing tag map narrows exclusion to the row's own tags rather than
      // failing the page; the filter still works, it just reaches less far
      error: () => { /* keep the empty map */ }
    });
  }

  /** What a table inherits from its schema, for exclusion only. */
  private inherited(table: TableRollup): string[] {
    return this.schemaTags.get((table.schema ?? '').toLowerCase()) ?? [];
  }

  /** See the note in SchemaListComponent. */
  /** Whether any loaded row is a view, which is what the control is for. */
  hasViews(): boolean {
    return this.tables.some(table => table.view);
  }

  onRowTagsChanged() {
    this.tables = [...this.tables];
    this.applyFilter();
  }

  onFilter(filter: TagFilterState) {
    this.filter = filter;
    this.applyFilter();
  }

  // Recomputed on each change rather than in a template getter; see the note
  // in SchemaListComponent.
  private applyFilter() {
    this.stripRows = this.tables.map(table =>
      ({tags: table.tags ?? [], inheritedTags: this.inherited(table)}));
    this.visible = this.tables.filter(table =>
      matchesTreeFilter(table.schema ?? '', table.name, this.search.name)
      && matchesTagFilter((table.tags ?? []).map(tag => tag.name), this.filter,
        this.inherited(table)));
    this.displayed = this.visible.filter(table => matchesTypeFilter(this.typeFilter, table.view));
    this.searching = !!this.search.name.trim() || this.search.columns.length > 0;
  }

  /** The tag target of every row on screen and, when the checkboxes show, still checked. */
  get bulkTargets(): TagTarget[] {
    return this.displayed
      .filter(table => !this.searching || !this.excluded.has(tableKey(table.schema, table.name)))
      .map(table => tableTarget(table.schema, table.name));
  }

  setName(name: string) {
    this.writeSearch({ ...this.search, name, exclude: [] });
  }

  /** Typed as one list — commas or spaces — and applied on Enter or leaving the box. */
  setColumns(text: string) {
    const columns = parseColumns(text);
    if (columns.join(',') === this.search.columns.join(',')) { return; }
    this.writeSearch({ ...this.search, columns, exclude: [] });
  }

  /**
   * The columns box is a chip input (sqlx-c8w.7): each name becomes a chip as
   * soon as it is finished — Enter, comma or space — and applies straight
   * away, so the space-separated syntax no longer has to be known. Backspace
   * in an empty box takes the last chip back off.
   */
  columnDraft = '';

  onColumnKey(event: KeyboardEvent) {
    if (event.key === 'Enter' || event.key === ',' || event.key === ' ') {
      event.preventDefault();
      this.commitColumnDraft();
    } else if (event.key === 'Backspace' && !this.columnDraft && this.search.columns.length) {
      this.removeColumn(this.search.columns[this.search.columns.length - 1]);
    }
  }

  /** Whatever was typed (or pasted, possibly several names) joins the chips. */
  commitColumnDraft() {
    const added = parseColumns(this.columnDraft);
    this.columnDraft = '';
    if (added.length) {
      this.setColumns([...this.search.columns, ...added].join(' '));
    }
  }

  removeColumn(column: string) {
    this.setColumns(this.search.columns.filter(candidate => candidate !== column).join(' '));
  }

  setExcluded(excluded: Set<string>) {
    this.writeSearch({ ...this.search, exclude: [...excluded] });
  }

  /**
   * A new name or column search is a new result set, so it starts with every
   * row checked: the exclusions are cleared with it.
   */
  private writeSearch(search: TableSearchState) {
    this.readSearch(search);
    this.applyFilter();
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: encodeTableSearch(search),
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }

  /**
   * What the confirmation calls the set. Says "of 308" whenever the view is
   * narrowed, because the same button does very different things depending on
   * the filters and the count alone would not show it.
   */
  get bulkLabel(): string {
    // The noun agrees with the number it follows, which is the total in the
    // "N of M" form and the count in the "all N" form.
    const noun = (count: number) => count === 1 ? 'table' : 'tables';
    const selected = this.bulkTargets.length;
    return selected === this.tables.length
      ? `all ${this.tables.length} ${noun(this.tables.length)}`
      : `${selected} of ${this.tables.length} ${noun(this.tables.length)}`;
  }

  /** Re-read tags for the rows just written, without re-fetching the page. */
  onBulkChanged() {
    const targets = this.bulkTargets;
    // A Map of keys, not a scan: this runs inside a map over every loaded
    // table, and the written set is usually most of them.
    const written = new Map(targets.map((target, index) => [targetKey(target), index]));
    this.sqlService.getTagsForTargets(targets).subscribe(byIndex => {
      this.tables = this.tables.map(table => {
        const at = written.get(targetKey(tableTarget(table.schema, table.name)));
        return at === undefined ? table : {...table, tags: byIndex[at] ?? []};
      });
      this.applyFilter();
    });
  }



  /**
   * Written with `replaceUrl` so flipping between all/tables/views does not
   * stack history entries — the back button should leave the page, not walk
   * back through filter states.
   */
  setTypeFilter(typeFilter: TypeFilter) {
    this.typeFilter = typeFilter;
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { [TYPE_PARAM]: typeFilter === 'all' ? null : typeFilter },
      queryParamsHandling: 'merge',
      replaceUrl: true
    });
  }

}
