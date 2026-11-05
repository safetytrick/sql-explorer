import {Component, EventEmitter, Input, Output} from '@angular/core';
import {RouterLink} from '@angular/router';
import {PicoViewComponent} from '../pico-view/pico-view.component';
import {TagFieldComponent} from '../tag-field/tag-field.component';
import {Tag, TableRollup} from '../api-types';
import {matchesTypeFilter, TypeFilter} from '../schema-tree/tree-filter';
import {tableTarget} from '../tag-path';
import {tableKey} from '../table-names/table-search-url';

@Component({
    selector: 'app-table-list-component',
    templateUrl: './table-list.component.html',
    styleUrls: ['./table-list.component.scss'],
    imports: [RouterLink, PicoViewComponent, TagFieldComponent]
})
export class TableListComponent {
  protected readonly tableTarget = tableTarget;

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

  /** Raised when a row's tags are edited in place; see SchemaListTableComponent. */
  @Output() tagsChanged = new EventEmitter<void>();

  onRowTags(table: TableRollup, tags: Tag[]) {
    table.tags = tags;
    this.tagsChanged.emit();
  }

  /**
   * Show all objects, only base tables, or only views.
   *
   * Bound as an input only when a page hoists the control into its own header
   * (see `showTypeFilter`); the other consumers leave it unbound and this
   * component owns the value outright.
   */
  @Input() typeFilter: TypeFilter = 'all';

  /**
   * Whether to draw the inline all/tables/views control above the table.
   *
   * `/tables` folds it into the page header so there is one control strip
   * rather than two, and sets this false. `tables-with-column` and
   * `tagged-table-view` have no such header and keep the inline control.
   */
  @Input() showTypeFilter = true;

  visibleTables(): TableRollup[] {
    return this.rows.filter(table => matchesTypeFilter(this.typeFilter, table.view));
  }

  hasViews(): boolean {
    return this.rows.some(table => table.view);
  }

  setTypeFilter(value: TypeFilter) {
    this.typeFilter = value;
  }

  /**
   * Draw a checkbox per row and a select-all in the header, for a page whose
   * bulk action should pass over a few rows. Rows are checked unless their
   * `schema.table` key is in {@link excluded}: the page holds the exceptions,
   * not the selection, so "all of these but two" stays a short list — and a
   * row arriving with a new search starts checked.
   */
  /**
   * The Schema column. A list of one schema's tables says which schema in its
   * header, so the column only repeats it on every row (sqlx-c8w.4).
   */
  @Input() showSchema = true;

  @Input() selectable = false;
  @Input() excluded: ReadonlySet<string> = new Set();
  /** The new exclusion set, whole; the page owns it (and puts it in the URL). */
  @Output() excludedChange = new EventEmitter<Set<string>>();

  isChecked(table: TableRollup): boolean {
    return !this.excluded.has(tableKey(table.schema, table.name));
  }

  toggleRow(table: TableRollup) {
    const key = tableKey(table.schema, table.name);
    const next = new Set(this.excluded);
    if (next.has(key)) { next.delete(key); } else { next.add(key); }
    this.excludedChange.emit(next);
  }

  /** Every visible row checked; with some but not all, the header shows indeterminate. */
  allChecked(): boolean {
    return this.visibleTables().every(table => this.isChecked(table));
  }

  someChecked(): boolean {
    return !this.allChecked() && this.visibleTables().some(table => this.isChecked(table));
  }

  /** Checks every visible row unless all already are, in which case it unchecks them. */
  toggleAll() {
    const next = new Set(this.excluded);
    const check = !this.allChecked();
    for (const table of this.visibleTables()) {
      const key = tableKey(table.schema, table.name);
      if (check) { next.delete(key); } else { next.add(key); }
    }
    this.excludedChange.emit(next);
  }

  /** Width of the duration bar, relative to the heaviest table in the list. */
  weight(totalQueryTime: number): number {
    return Math.max(2, 100 * totalQueryTime / this.heaviest);
  }
}
