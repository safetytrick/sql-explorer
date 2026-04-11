import { Component, OnInit } from '@angular/core';
import {AppComponent} from '../app.component';
import { RouterLink } from '@angular/router';

@Component({
    selector: 'app-nav-bar',
    templateUrl: './nav-bar.component.html',
    styleUrls: ['./nav-bar.component.scss'],
    imports: [RouterLink]
})
export class NavBarComponent implements OnInit {

  constructor(private appComponent: AppComponent) { }

  ngOnInit() {
  }

  toggleExplain() {
    this.appComponent.showExplain = !this.appComponent.showExplain;
  }

}
