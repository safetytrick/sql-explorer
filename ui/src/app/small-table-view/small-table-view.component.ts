import {Component, Input, OnInit} from '@angular/core';

@Component({
  selector: 'app-small-table-view',
  templateUrl: './small-table-view.component.html',
  styleUrls: ['./small-table-view.component.scss']
})
export class SmallTableViewComponent implements OnInit {

  private _table: any;

  @Input()
  set table(table: any) {
    this._table = table;

  }

  get table() {
    return this._table;
  }

  constructor() { }

  ngOnInit() {
  }

}
