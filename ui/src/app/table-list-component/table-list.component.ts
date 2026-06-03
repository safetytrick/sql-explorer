import {Component, Input, OnInit} from '@angular/core';
import { RouterLink } from '@angular/router';
import { PicoViewComponent } from '../pico-view/pico-view.component';

@Component({
    selector: 'app-table-list-component',
    templateUrl: './table-list.component.html',
    styleUrls: ['./table-list.component.scss'],
    imports: [RouterLink, PicoViewComponent]
})
export class TableListComponent implements OnInit {
  @Input()
  tables!: any[];

  constructor() { }

  ngOnInit() {
  }

}
