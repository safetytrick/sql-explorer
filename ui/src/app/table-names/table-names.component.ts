import {Component, Input, OnInit} from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute} from '@angular/router';
import {Title} from '@angular/platform-browser';
import { TableListComponent } from '../table-list-component/table-list.component';
import { TagFilterStripComponent } from '../tag-filter/tag-filter-strip.component';
import { emptyTagFilter, matchesTagFilter, TagFilterState } from '../tag-filter/tag-filter-url';
import { TableRollup } from '../api-types';
import { TypeFilter } from '../schema-tree/tree-filter';

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
   * filtering. Deliberately not in the URL — it never was, and making it
   * shareable is a change of behaviour rather than of layout.
   */
  typeFilter: TypeFilter = 'all';
  /** `tables` narrowed by the tag strip; the type filter still applies inside. */
  visible: TableRollup[] = [];

  private filter: TagFilterState = emptyTagFilter();

  constructor(public sqlService: AnalyzedSqlService, private route: ActivatedRoute,
              private titleService: Title) { }

  ngOnInit() {
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

  private getTablesForSchema(schema: string) {
    this.sqlService.getTablesForSchema(schema).subscribe(data => {
      this.tables = data;
      this.applyFilter();
    });
  }
}
