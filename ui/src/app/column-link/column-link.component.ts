import {Component, Input, OnInit} from '@angular/core';
import { RouterLink } from '@angular/router';
import {ColumnRef, parseColumnRef} from '../refs';

@Component({
    selector: 'app-column-link',
    templateUrl: './column-link.component.html',
    styleUrls: ['./column-link.component.scss'],
    imports: [RouterLink]
})
export class ColumnLinkComponent implements OnInit {

  private _column!: string;
  private _table!: string;
  private _schema!: string;

  get column(): string {
    return this._column;
  }

  get table(): string {
    return this._table;
  }

  get schema() {
    return this._schema;
  }

  @Input()
  linkClass!: string;

  @Input()
  set schema(schema: string) {
    this._schema = schema;
  }

  /**
   * A `"schema.table.column"` (or `"table.column"`) reference — the shape the
   * backend actually serializes. Parsed right to left by {@link parseColumnRef};
   * the previous hand-rolled split indexed from the left, so anything with an
   * unexpected segment count landed in the wrong slot. An unqualified
   * reference keeps whatever the separate `schema` input supplied.
   */
  @Input()
  set columnReference(ref: ColumnRef) {
    const parsed = parseColumnRef(ref);
    if (parsed.schema) {
      this._schema = parsed.schema;
    }
    this._table = parsed.table ?? this._table;
    this._column = parsed.column;
  }

  /**
   * The drill-down the link opens: the table lens, filtered to this column.
   * `/table/{s}/{t}/{c}` still redirects here, but linking straight to the
   * canonical form means the href a user copies is the one they can share.
   *
   * An unqualified reference with no `schema` input has nothing to build a
   * `schema.table.column` filter out of, so it links to the table alone rather
   * than to a filter that silently matches nothing.
   */
  get drillParams(): Record<string, string> {
    return this.schema ? { columns: `${this.schema}.${this.table}.${this.column}` } : {};
  }

  constructor() { }

  ngOnInit() {

  }



}
