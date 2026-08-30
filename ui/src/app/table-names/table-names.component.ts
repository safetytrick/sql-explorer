import {Component, Input, OnInit} from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute, Router} from '@angular/router';
import {Title} from '@angular/platform-browser';
import { TableListComponent } from '../table-list-component/table-list.component';
import { TagFilterStripComponent, TaggedRow } from '../tag-filter/tag-filter-strip.component';
import { emptyTagFilter, matchesTagFilter, TagFilterState } from '../tag-filter/tag-filter-url';
import { TableRollup } from '../api-types';
import { TypeFilter } from '../schema-tree/tree-filter';
import { readTypeFilter, TYPE_PARAM } from './type-filter-url';
import { matchesTypeFilter } from '../schema-tree/tree-filter';
import {schemaPath, tablePath} from '../tag-path';
import { BulkTagBarComponent } from '../bulk-tag/bulk-tag-bar.component';

@Component({
    selector: 'app-table-names',
    templateUrl: './table-names.component.html',
    styleUrls: ['./table-names.component.scss'],
    imports: [TableListComponent, TagFilterStripComponent, BulkTagBarComponent]
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
              private router: Router, private titleService: Title) { }

  ngOnInit() {
    // seeded from the snapshot, then kept current — /tables and /tables/:schema
    // are the same component, so a schema change must not drop the type filter
    this.typeFilter = readTypeFilter(this.route.snapshot.queryParamMap.get(TYPE_PARAM));
    this.route.queryParamMap.subscribe(params => {
      this.typeFilter = readTypeFilter(params.get(TYPE_PARAM));
      this.applyFilter();
    });
    this.route.params.subscribe(params => {
        this.schema = params['schema'];
        if (this.schema) {
          this.titleService.setTitle(`::: schema ${this.schema} :::`);
          this.getTablesForSchema(this.schema);
        } else {
          this.titleService.setTitle('::: sql explorer :::');
          this.getTables();
        }
    });

  }

  getTables() {
    this.tables = [];
    this.applyFilter();
    this.sqlService.getTables().subscribe(data => {
      this.tables = data;
      this.applyFilter();
      this.loadSchemaTags(data);
    });
  }

  /** The tags of every schema represented on this page, batched into one call. */
  private loadSchemaTags(tables: TableRollup[]) {
    const names = [...new Set(tables.map(table => table.schema).filter(Boolean))];
    if (!names.length) { return; }
    this.sqlService.getTagsForPaths(names.map(schemaPath)).subscribe({
      next: byPath => {
        this.schemaTags = new Map(names.map(name =>
          [name.toLowerCase(), (byPath[schemaPath(name)] ?? []).map(tag => tag.name)]));
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
      matchesTagFilter((table.tags ?? []).map(tag => tag.name), this.filter,
        this.inherited(table)));
    this.displayed = this.visible.filter(table => matchesTypeFilter(this.typeFilter, table.view));
  }

  /** The tag path of every row on screen. */
  get bulkPaths(): string[] {
    return this.displayed.map(table => tablePath(table.schema, table.name));
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
    return this.displayed.length === this.tables.length
      ? `all ${this.tables.length} ${noun(this.tables.length)}`
      : `${this.displayed.length} of ${this.tables.length} ${noun(this.tables.length)}`;
  }

  /** Re-read tags for the rows just written, without re-fetching the page. */
  onBulkChanged() {
    const paths = this.bulkPaths;
    // A Set, not `paths.includes`: this runs inside a map over every loaded
    // table, and the written set is usually most of them.
    const written = new Set(paths);
    this.sqlService.getTagsForPaths(paths).subscribe(byPath => {
      this.tables = this.tables.map(table => {
        const path = tablePath(table.schema, table.name);
        return written.has(path) ? {...table, tags: byPath[path] ?? []} : table;
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

  private getTablesForSchema(schema: string) {
    this.sqlService.getTablesForSchema(schema).subscribe(data => {
      this.tables = data;
      this.applyFilter();
    });
  }
}
