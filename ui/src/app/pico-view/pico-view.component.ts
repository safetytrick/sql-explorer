import {Component, Input, OnInit} from '@angular/core';

@Component({
    selector: 'app-pico-view',
    templateUrl: './pico-view.component.html',
    styleUrls: ['./pico-view.component.scss'],
    standalone: false
})
export class PicoViewComponent implements OnInit {

  @Input()
  duration: number;

  constructor() { }

  ngOnInit() {
  }

}
