import {Component, Input, OnInit} from '@angular/core';
import { RouterLink } from '@angular/router';
import { CallbackPipePipe } from '../callback-pipe.pipe';
import { TableStructure } from '../api-types';

@Component({
    selector: 'app-small-table-view',
    templateUrl: './small-table-view.component.html',
    styleUrls: ['./small-table-view.component.scss'],
    imports: [RouterLink, CallbackPipePipe]
})
export class SmallTableViewComponent implements OnInit {

  private _table!: TableStructure;

  @Input()
  set table(table: TableStructure) {
    this._table = table;

  }

  get table(): TableStructure {
    return this._table;
  }

  constructor() { }

  ngOnInit() {
  }

}
