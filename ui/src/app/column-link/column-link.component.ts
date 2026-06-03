import {Component, Input, OnInit} from '@angular/core';
import { RouterLink } from '@angular/router';

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

  @Input()
  set columnReference(ref: string) {
    const pair = ref.split('.');
    // ugly hack, raw strings are being passed around
    // this needs to be refactored so the underlying models convey the whole path
    if (pair.length > 2) {
      this._schema = pair[0];
      this._table = pair[1];
      this._column = pair[2];
    } else {
      this._table = pair[0];
      this._column = pair[1];
    }
  }

  constructor() { }

  ngOnInit() {

  }



}
