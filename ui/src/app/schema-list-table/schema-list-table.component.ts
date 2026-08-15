import { Component, Input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PicoViewComponent } from '../pico-view/pico-view.component';
import { TagFieldComponent } from '../tag-field/tag-field.component';
import { SchemaRollup } from '../api-types';

@Component({
    selector: 'app-schema-list-table',
    templateUrl: './schema-list-table.component.html',
    styleUrls: ['./schema-list-table.component.scss'],
    imports: [RouterLink, PicoViewComponent, TagFieldComponent]
})
export class SchemaListTableComponent {

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
