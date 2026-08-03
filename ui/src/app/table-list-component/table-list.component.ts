import {Component, Input} from '@angular/core';
import {RouterLink} from '@angular/router';
import {PicoViewComponent} from '../pico-view/pico-view.component';
import {TableRollup} from '../api-types';

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
  typeFilter: 'all' | 'tables' | 'views' = 'all';

  visibleTables(): TableRollup[] {
    if (!this.tables) {
      return [];
    }
    switch (this.typeFilter) {
      case 'tables': return this.tables.filter(table => !table.view);
      case 'views': return this.tables.filter(table => table.view);
      default: return this.tables;
    }
  }

  hasViews(): boolean {
    return !!this.tables && this.tables.some(table => table.view);
  }

  setTypeFilter(value: 'all' | 'tables' | 'views') {
    this.typeFilter = value;
  }
}
