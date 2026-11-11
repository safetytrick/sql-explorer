import {Component, OnInit} from '@angular/core';
import {AnalyzedSqlService} from '../analyzed-sql.service';
import {RouterLink} from '@angular/router';
import { SchemaListTableComponent } from '../schema-list-table/schema-list-table.component';
import { TagFilterStripComponent, TaggedRow } from '../tag-filter/tag-filter-strip.component';
import { AnnotationService, withSchemaRoles } from '../annotation.service';
import { emptyTagFilter, matchesTagFilter, TagFilterState } from '../tag-filter/tag-filter-url';
import { SchemaRollup } from '../api-types';

@Component({
    selector: 'app-schema-list',
    templateUrl: './schema-list.component.html',
    styleUrls: ['./schema-list.component.scss'],
    imports: [SchemaListTableComponent, TagFilterStripComponent, RouterLink]
})
/**
 * Schemas: the starting page, ranked by total query time. Tags are edited per
 * row; there is no bulk-tag bar here — there are too few schemas for one to
 * earn its place (sqlx-c8w.2).
 */
export class SchemaListComponent implements OnInit {

  schemas: SchemaRollup[] = [];
  /** `schemas` narrowed by the rail; what the ledger actually renders. */
  visible: SchemaRollup[] = [];

  private filter: TagFilterState = emptyTagFilter();

  /**
   * Each schema's roles (lower-cased name → values), e.g. `system`. The
   * `system` tag became a `schema-role` annotation (sqlx-n5j.14), and a role
   * stays a term the strip offers and filters by, so `schemaTagsExclude=system`
   * still hides MySQL's own schemas here.
   */
  private roles = new Map<string, string[]>();
  /** What the strip counts: each row with its roles alongside its tags. Rebuilt, not a getter. */
  stripRows: TaggedRow[] = [];

  constructor(public sqlService: AnalyzedSqlService, private annotationService: AnnotationService) {
  }

  ngOnInit() {
    this.schemas = [];
    this.sqlService.getSchemas().subscribe(data => {
      this.schemas = data;
      this.applyFilter();
    });
    this.annotationService.schemaRoles().subscribe({
      next: roles => { this.roles = roles; this.applyFilter(); },
      error: () => { /* without roles, only tags filter */ }
    });
  }

  private rolesOf(schema: SchemaRollup): string[] {
    return this.roles.get(schema.name.toLowerCase()) ?? [];
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
  // A schema belongs to nothing, so its terms are its own: its tag names and
  // its roles, which match exactly as the `system` tag they replaced did.
  private applyFilter() {
    this.stripRows = this.schemas.map(schema => ({tags: schema.tags ?? [], inheritedTags: this.rolesOf(schema)}));
    this.visible = this.schemas.filter(schema =>
      matchesTagFilter(withSchemaRoles(schema.name, (schema.tags ?? []).map(tag => tag.name), this.roles), this.filter));
  }
}
