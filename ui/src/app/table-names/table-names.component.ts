import {Component, Input, OnInit} from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {ActivatedRoute} from '@angular/router';
import {Title} from '@angular/platform-browser';
import { TableListComponent } from '../table-list-component/table-list.component';
import { TagFilterStripComponent } from '../tag-filter/tag-filter-strip.component';
import { emptyTagFilter, matchesTagFilter, TagFilterState } from '../tag-filter/tag-filter-url';
import { TableRollup } from '../api-types';

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
  /** `tables` narrowed by the rail; the type filter still applies inside. */
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
