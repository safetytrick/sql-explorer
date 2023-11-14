import {Component, Input, OnInit} from '@angular/core';

@Component({
  selector: 'app-table-list-component',
  templateUrl: './table-list.component.html',
  styleUrls: ['./table-list.component.scss']
})
export class TableListComponent implements OnInit {
  @Input()
  tables: [];

  constructor() { }

  ngOnInit() {
  }

}
