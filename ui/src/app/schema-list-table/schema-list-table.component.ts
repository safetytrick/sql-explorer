import {GLOSSARY} from '../glossary';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PicoViewComponent } from '../pico-view/pico-view.component';
import { TagFieldComponent } from '../tag-field/tag-field.component';
import { SchemaRollup, Tag } from '../api-types';
import {schemaTarget} from '../tag-path';

@Component({
    selector: 'app-schema-list-table',
    templateUrl: './schema-list-table.component.html',
    styleUrls: ['./schema-list-table.component.scss'],
    imports: [RouterLink, PicoViewComponent, TagFieldComponent]
})
export class SchemaListTableComponent {
  /** Header definitions (hover). */
  readonly glossary = GLOSSARY;

  /** Tag chips filter this page by their tag rather than leaving it; for a page with a tag strip (sqlx-c8w.9). */
  @Input() tagFilterInPlace = false;

  protected readonly schemaTarget = schemaTarget;

  /**
   * Raised when a row's tags are edited in place. TagField reassigns its own
   * field rather than the rollup it was handed, so without this the page — and
   * the tag rail counting off these rows — would keep the stale set.
   */
  @Output() tagsChanged = new EventEmitter<void>();

  onRowTags(schema: SchemaRollup, tags: Tag[]) {
    schema.tags = tags;
    this.tagsChanged.emit();
  }

  private rows: SchemaRollup[] = [];
  private heaviest = 1;

  @Input()
  set schemas(schemas: SchemaRollup[]) {
    this.rows = schemas ?? [];
    // Cached on assignment: `weight` is called once per row per change
    // detection pass, and rescanning the list each time would be quadratic.
    this.heaviest = Math.max(...this.rows.map(schema => schema.totalQueryTime), 1);
  }

  get schemas(): SchemaRollup[] {
    return this.rows;
  }

  /** Width of the duration bar, relative to the heaviest schema in the list. */
  weight(totalQueryTime: number): number {
    return Math.max(2, 100 * totalQueryTime / this.heaviest);
  }
}
