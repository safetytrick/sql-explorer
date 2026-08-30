import {Component, OnInit} from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {Title} from '@angular/platform-browser';
import { SchemaListTableComponent } from '../schema-list-table/schema-list-table.component';
import { TagFilterStripComponent } from '../tag-filter/tag-filter-strip.component';
import { emptyTagFilter, matchesTagFilter, TagFilterState } from '../tag-filter/tag-filter-url';
import { SchemaRollup } from '../api-types';
import { BulkTagBarComponent } from '../bulk-tag/bulk-tag-bar.component';
import { schemaPath } from '../tag-path';

@Component({
    selector: 'app-schema-list',
    templateUrl: './schema-list.component.html',
    styleUrls: ['./schema-list.component.scss'],
    imports: [SchemaListTableComponent, TagFilterStripComponent, BulkTagBarComponent]
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
  //
  // No inherited tags: a schema belongs to nothing, so exclusion here is
  // exactly the row's own tags.
  private applyFilter() {
    this.visible = this.schemas.filter(schema =>
      matchesTagFilter((schema.tags ?? []).map(tag => tag.name), this.filter));
  }

  /** The tag path of every schema on screen — nothing narrows further here. */
  get bulkPaths(): string[] {
    return this.visible.map(schema => schemaPath(schema.name));
  }

  get bulkLabel(): string {
    const noun = this.visible.length === 1 ? 'schema' : 'schemas';
    return this.visible.length === this.schemas.length
      ? `all ${this.schemas.length} ${noun}`
      : `${this.visible.length} of ${this.schemas.length} ${noun}`;
  }

  /** Re-read tags for the rows just written, without re-fetching the page. */
  onBulkChanged() {
    const paths = this.bulkPaths;
    this.sqlService.getTagsForPaths(paths).subscribe(byPath => {
      this.schemas = this.schemas.map(schema => {
        const path = schemaPath(schema.name);
        return paths.includes(path) ? {...schema, tags: byPath[path] ?? []} : schema;
      });
      this.applyFilter();
    });
  }
}
