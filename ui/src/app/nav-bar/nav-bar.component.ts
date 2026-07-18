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
    // Stay on the current route, but re-instantiate it so its data reloads for
    // the newly selected environment. The interceptor scopes every request to
    // EnvironmentService.getCurrent(), so re-fetching pulls the new env's data.
    const url = this.router.url;
    this.router.navigateByUrl('/', { skipLocationChange: true })
      .then(() => this.router.navigateByUrl(url));
  }

  toggleExplain() {
    this.appComponent.showExplain = !this.appComponent.showExplain;
  }

}
