import {Component, Input} from '@angular/core';
import {RouterLink} from '@angular/router';
import {PicoViewComponent} from '../pico-view/pico-view.component';
import {TagFieldComponent} from '../tag-field/tag-field.component';
import {TableRollup} from '../api-types';
import {matchesTypeFilter, TypeFilter} from '../schema-tree/tree-filter';

@Component({
    selector: 'app-table-list-component',
    templateUrl: './table-list.component.html',
    styleUrls: ['./table-list.component.scss'],
    imports: [RouterLink, PicoViewComponent, TagFieldComponent]
})
export class TableListComponent {

  private rows: TableRollup[] = [];
  private heaviest = 1;

  @Input()
  set tables(tables: TableRollup[]) {
    this.rows = tables ?? [];
    // Cached on assignment; see the note in SchemaListTableComponent.
    this.heaviest = Math.max(...this.rows.map(table => table.totalQueryTime), 1);
  }

  get tables(): TableRollup[] {
    return this.rows;
  }

  /** Show all objects, only base tables, or only views. */
  typeFilter: TypeFilter = 'all';

  visibleTables(): TableRollup[] {
    return this.rows.filter(table => matchesTypeFilter(this.typeFilter, table.view));
  }

  hasViews(): boolean {
    return this.rows.some(table => table.view);
  }

  setTypeFilter(value: TypeFilter) {
    this.typeFilter = value;
  }

  /** Width of the duration bar, relative to the heaviest table in the list. */
  weight(totalQueryTime: number): number {
    return Math.max(2, 100 * totalQueryTime / this.heaviest);
  }
}
