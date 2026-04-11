import {Component, Input, OnInit} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { DurationPipe } from '../duration.pipe';

@Component({
    selector: 'app-pico-view',
    templateUrl: './pico-view.component.html',
    styleUrls: ['./pico-view.component.scss'],
    imports: [DecimalPipe, DurationPipe]
})
export class PicoViewComponent implements OnInit {

  @Input()
  duration: number;

  constructor() { }

  ngOnInit() {
  }

}
