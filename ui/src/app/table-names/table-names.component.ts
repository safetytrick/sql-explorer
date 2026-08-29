import {Component, Input, OnInit} from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute, Router} from '@angular/router';
import {Title} from '@angular/platform-browser';
import { TableListComponent } from '../table-list-component/table-list.component';
import { TagFilterStripComponent } from '../tag-filter/tag-filter-strip.component';
import { emptyTagFilter, matchesTagFilter, TagFilterState } from '../tag-filter/tag-filter-url';
import { TableRollup } from '../api-types';
import { TypeFilter } from '../schema-tree/tree-filter';
import { readTypeFilter, TYPE_PARAM } from './type-filter-url';

@Component({
    selector: 'app-table-names',
    templateUrl: './table-names.component.html',
    styleUrls: ['./table-names.component.scss'],
    imports: [TableListComponent, TagFilterStripComponent]
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

  private filter: TagFilterState = emptyTagFilter();

  constructor(public sqlService: AnalyzedSqlService, private route: ActivatedRoute,
              private router: Router, private titleService: Title) { }

  ngOnInit() {
    // seeded from the snapshot, then kept current — /tables and /tables/:schema
    // are the same component, so a schema change must not drop the type filter
    this.typeFilter = readTypeFilter(this.route.snapshot.queryParamMap.get(TYPE_PARAM));
    this.route.queryParamMap.subscribe(params => {
      this.typeFilter = readTypeFilter(params.get(TYPE_PARAM));
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
    });
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
    this.visible = this.tables.filter(table =>
      matchesTagFilter((table.tags ?? []).map(tag => tag.name), this.filter));
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
