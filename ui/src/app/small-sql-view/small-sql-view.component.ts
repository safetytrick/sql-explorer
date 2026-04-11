import {Component, Input, OnInit} from '@angular/core';

@Component({
    selector: 'app-small-sql-view',
    templateUrl: './small-sql-view.component.html',
    styleUrls: ['./small-sql-view.component.scss'],
    standalone: false
})
export class SmallSqlViewComponent implements OnInit {

  @Input()
  query: any;

  constructor() { }

  ngOnInit() {
  }

}
