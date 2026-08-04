import {Component, Input} from '@angular/core';
import {RouterLink} from '@angular/router';
import {PicoViewComponent} from '../pico-view/pico-view.component';
import {TableRollup} from '../api-types';
import {matchesTypeFilter, TypeFilter} from '../schema-tree/tree-filter';

@Component({
    selector: 'app-table-list-component',
    templateUrl: './table-list.component.html',
    styleUrls: ['./table-list.component.scss'],
    imports: [RouterLink, PicoViewComponent]
})
export class TableListComponent {
  @Input()
  tables!: TableRollup[];

  /** Show all objects, only base tables, or only views. */
  typeFilter: TypeFilter = 'all';

  visibleTables(): TableRollup[] {
    if (!this.tables) {
      return [];
    }
    return this.tables.filter(table => matchesTypeFilter(this.typeFilter, table.view));
  }

  hasViews(): boolean {
    return !!this.tables && this.tables.some(table => table.view);
  }

  setTypeFilter(value: TypeFilter) {
    this.typeFilter = value;
  }
}
