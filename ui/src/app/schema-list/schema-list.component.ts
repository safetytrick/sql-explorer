import {Component, OnInit} from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {Title} from '@angular/platform-browser';
import { SchemaListTableComponent } from '../schema-list-table/schema-list-table.component';
import { TagFilterStripComponent } from '../tag-filter/tag-filter-strip.component';
import { emptyTagFilter, matchesTagFilter, TagFilterState } from '../tag-filter/tag-filter-url';
import { SchemaRollup } from '../api-types';

@Component({
    selector: 'app-schema-list',
    templateUrl: './schema-list.component.html',
    styleUrls: ['./schema-list.component.scss'],
    imports: [SchemaListTableComponent, TagFilterStripComponent]
})
export class SchemaListComponent implements OnInit {

  schemas: SchemaRollup[] = [];
  /** `schemas` narrowed by the rail; what the ledger actually renders. */
  visible: SchemaRollup[] = [];

  private filter: TagFilterState = emptyTagFilter();

  constructor(public sqlService: AnalyzedSqlService,
              private titleService: Title) {
    titleService.setTitle('::: schemas :::');
  }

  ngOnInit() {
    this.schemas = [];
    this.sqlService.getSchemas().subscribe(data => {
      this.schemas = data;
      this.applyFilter();
    });
  }

  /**
   * A row was edited in place. The rollup objects are the same, so the strip
   * only re-counts if the array identity changes — hence the copy.
   */
  onRowTagsChanged() {
    this.schemas = [...this.schemas];
    this.applyFilter();
  }

  onFilter(filter: TagFilterState) {
    this.filter = filter;
    this.applyFilter();
  }

  // Recomputed on each change rather than in a template getter: change
  // detection would otherwise re-filter the whole list on every pass.
  private applyFilter() {
    this.visible = this.schemas.filter(schema =>
      matchesTagFilter((schema.tags ?? []).map(tag => tag.name), this.filter));
  }
}
