import {Component, Input, OnInit} from '@angular/core';
import { RouterLink } from '@angular/router';
import {parseTableRef, TableRef} from '../refs';

@Component({
    selector: 'app-table-link',
    templateUrl: './table-link.component.html',
    styleUrls: ['./table-link.component.scss'],
    imports: [RouterLink]
})
export class TableLinkComponent implements OnInit {

  private _table!: string;
  private _schema!: string;
  private _defaultSchema!: string;
  private _joinSchema!: string;
  private _joinTable!: string;

  get table(): string {
    return this._table;
  }

  get schema() {
    return this._schema || this._defaultSchema;
  }

  get name() {
    if (!this._schema || this._schema === this._defaultSchema) {
      return this._table;
    } else {
      return this._schema + '.' + this._table;
    }
  }

  get joinSchema() {
    return this._joinSchema || this._defaultSchema;
  }

  get joinTable() {
    return this._joinTable;
  }

  @Input()
  linkClass!: string;

  @Input()
  set join(joinTable: string) {
    const pair = joinTable.split('.');
    if (pair.length > 1) {
      this._joinSchema = pair[0];
      this._joinTable = pair[1];
    } else {
      this._joinSchema = this._defaultSchema;
      this._joinTable = pair[0];
    }
  }

  @Input()
  set defaultSchema(schema: string) {
    this._defaultSchema = schema;
  }

  /**
   * A `"schema.table"` reference as the backend serializes it. Parsed right to
   * left, so a schema containing a dot doesn't shift the table out of place.
   */
  @Input()
  set tableReference(ref: TableRef) {
    const parsed = parseTableRef(ref);
    this._schema = parsed.schema ?? this._defaultSchema;
    this._table = parsed.table;
  }

  constructor() { }

  ngOnInit() {
  }

}
