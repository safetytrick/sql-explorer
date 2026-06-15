import { Component, OnInit } from '@angular/core';
import {AppComponent} from '../app.component';
import { Router, RouterLink } from '@angular/router';
import { EnvironmentService, EnvironmentSummary } from '../environment.service';

@Component({
    selector: 'app-nav-bar',
    templateUrl: './nav-bar.component.html',
    styleUrls: ['./nav-bar.component.scss'],
    imports: [RouterLink]
})
export class NavBarComponent implements OnInit {

  environments: EnvironmentSummary[] = [];
  current: string | null = null;

  constructor(private appComponent: AppComponent,
              private environmentService: EnvironmentService,
              private router: Router) { }

  ngOnInit() {
    this.current = this.environmentService.getCurrent();
    this.environmentService.getEnvironments().subscribe(list => {
      this.environments = list.environments;
      if (!this.current) {
        this.current = list.defaultEnvironment;
      }
    });
  }

  onEnvChange(name: string) {
    this.environmentService.setCurrent(name);
    this.current = name;
    // re-fetch everything against the newly selected environment
    this.router.navigate(['/schemas']);
  }

  toggleExplain() {
    this.appComponent.showExplain = !this.appComponent.showExplain;
  }

}
