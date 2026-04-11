import {Component, Input, OnInit} from '@angular/core';
import { RouterLink } from '@angular/router';

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

  @Input()
  set tableReference(ref: string) {
    const pair = ref.split('.');
    if (pair.length > 1) {
      this._schema = pair[0];
      this._table = pair[1];
    } else {
      this._schema = this._defaultSchema;
      this._table = pair[0];
    }
  }

  constructor() { }

  ngOnInit() {
  }

}
