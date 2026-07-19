import {Component, Input, OnInit} from '@angular/core';
import { RouterLink } from '@angular/router';
import { Highlight } from 'ngx-highlightjs';
import { PicoViewComponent } from '../pico-view/pico-view.component';
import { ColumnLinkComponent } from '../column-link/column-link.component';
import { SqlProfile } from '../api-types';

@Component({
    selector: 'app-small-sql-view',
    templateUrl: './small-sql-view.component.html',
    styleUrls: ['./small-sql-view.component.scss'],
    imports: [RouterLink, Highlight, PicoViewComponent, ColumnLinkComponent]
})
export class SmallSqlViewComponent implements OnInit {

  @Input()
  query!: SqlProfile;

  constructor() { }

  ngOnInit() {
  }

}
